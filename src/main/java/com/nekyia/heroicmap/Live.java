package com.nekyia.heroicmap;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntPredicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

/**
 * Die Live-Ebene über der Vollbildkarte: Chunks, in denen sich sichtbar ein Block ändert,
 * zeichnet ein Worker mit dem Maler der Minimap wie die Karte des Servers, verkleinert sie wie
 * die Pyramide und legt je Chunk ein PNG ab, bis der nächste Abgleich die Änderung bringt.
 * Siehe docs/live.md.
 */
public final class Live {

    public static final Live INSTANZ = new Live();
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Je Chunk höchstens so oft: Wasser fliesst, Getreide wächst. */
    static final long PAUSE_MS = 5000;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Heroic Map Live");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    /** Gehören dem Worker. */
    private ChunkMaler maler;
    private Map<TextureAtlasSprite, ChunkMaler.Texel> vanilla;
    private Map<TextureAtlasSprite, ChunkMaler.Texel> vanillaAus;

    /** Chunks mit Änderungen, die noch zu zeichnen sind. */
    private final LongLinkedOpenHashSet offen = new LongLinkedOpenHashSet();
    /** Wann jeder Chunk zuletzt gezeichnet wurde, in ms. */
    private final Long2LongOpenHashMap zuletzt = new Long2LongOpenHashMap();
    /** Der Auftrag im Worker, wahr, wenn sein Bild abgelegt ist; dazu sein Chunk. */
    private CompletableFuture<Boolean> laufend;
    private long laufendChunk;
    /** Der Satz für diese Welt, oder null; gesucht beim ersten Bedarf nach jedem Leeren. */
    private Satz satz;
    private boolean gesucht;
    private Licht licht;
    /** Im Gametest stempelt die Ebene mit der Uhr des Spielers; sonst nur mit bekannter Uhr des Servers. */
    private boolean test;

    private Live() {
    }

    /**
     * Ein Block hat sich geändert; vom Mixin, auf dem Render-Thread, noch vor dem Filter des
     * Spiels. Markiert wird nur, was das Bild ändern kann, siehe docs/live.md, „Wann gezeichnet wird“.
     */
    public void markiere(BlockPos pos, BlockState alt, BlockState neu) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || satz() == null || !mc.getModelManager().requiresRender(alt, neu)) {
            return;
        }
        int x = pos.getX(), z = pos.getZ(), cx = x >> 4, cz = z >> 4, lx = x & 15, lz = z & 15;
        LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null) {
            return;
        }
        BlockPos.MutableBlockPos spalte = new BlockPos.MutableBlockPos(x, 0, z);
        if (!sichtbar(pos.getY(), chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz),
                y -> chunk.getBlockState(spalte.setY(y)).isSolidRender())) {
            return;
        }
        // Am Rand ändert sich der Schatten des Nachbarn mit.
        for (int dz = lz == 0 ? -1 : 0; dz <= (lz == 15 ? 1 : 0); dz++) {
            for (int dx = lx == 0 ? -1 : 0; dx <= (lx == 15 ? 1 : 0); dx++) {
                offen.add(ChunkPos.pack(cx + dx, cz + dz));
            }
        }
    }

    /** Für den Gametest: genau diesen Chunk zeichnen, ohne Änderung. */
    void markiereChunk(int cx, int cz) {
        offen.add(ChunkPos.pack(cx, cz));
    }

    /**
     * Kann eine Änderung in Höhe {@code y} von oben zu sehen sein? Nicht, wenn zwischen ihr und
     * dem obersten Block der Spalte, {@code oben}, ein deckender liegt; dort endet auch die Spalte
     * in {@code ChunkMaler.abziehen}.
     */
    static boolean sichtbar(int y, int oben, IntPredicate deckend) {
        for (int h = oben; h > y; h--) {
            if (deckend.test(h)) {
                return false;
            }
        }
        return true;
    }

    /** Vergisst alles, etwa beim Wechsel der Welt oder beim Trennen. */
    void leeren() {
        offen.clear();
        zuletzt.clear();
        satz = null;
        gesucht = false;
        licht = null;
        laufend = null;
        test = false;
    }

    /** Für den Gametest: ein Satz ohne Server, im Einzelspieler, mit der Uhr des Spielers. */
    void satzFuerTest(Satz s) {
        satz = s;
        gesucht = true;
        test = true;
    }

    /** Nach einem Download: Der Satz wird beim nächsten Bedarf neu gesucht; offene Änderungen bleiben. */
    void satzNeu() {
        gesucht = false;
        satz = null;
    }

    /** Der Satz für die Welt des Spielers, wenn die Live-Ebene für ihn zeichnen kann. */
    private Satz satz() {
        if (!gesucht) {
            gesucht = true;
            ClientLevel level = Minecraft.getInstance().level;
            Satz s = level == null ? null
                    : Satz.fuer(Downloads.serverOrdner(), level.dimension().identifier().toString());
            // Der Maler kann 1, 2 und 4 Pixel je Block; unter einer Decke zeichnet die Karte anders.
            boolean passt = s != null && (s.scale() == 1 || s.scale() == 2 || s.scale() == 4)
                    && !level.dimensionType().hasCeiling();
            satz = passt ? s : null;
            if (satz != null) {
                // Zwischendateien eines abgebrochenen Spiels.
                Path ordner = Ebene.ordner(satz.ordner().getParent());
                worker.execute(() -> Ebene.raeumeZwischen(ordner));
            }
        }
        return satz;
    }

    /** Je Tick: ein fertiges Bild übernehmen oder einen Chunk abziehen, wenn die Minimap nichts zu tun hat. */
    void arbeite(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || satz() == null) {
            return;
        }
        if (laufend != null) {
            if (!laufend.isDone()) {
                return;
            }
            uebernimm(laufend, laufendChunk);
            laufend = null;
        }
        // Ohne Uhr des Servers passten die Zeiten nicht zu abdeckt_bis; die Änderungen warten.
        if (offen.isEmpty() || Minimap.INSTANZ.beschaeftigt() || !(test || Downloads.INSTANZ.uhrBekannt())) {
            return;
        }
        long jetzt = System.currentTimeMillis();
        for (LongIterator it = offen.iterator(); it.hasNext(); ) {
            long k = it.nextLong();
            int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
            LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk == null) {
                it.remove();
                continue;
            }
            if (jetzt - zuletzt.getOrDefault(k, Long.MIN_VALUE / 2) < PAUSE_MS || !nachbarnGeladen(level, cx, cz)) {
                continue;
            }
            it.remove();
            zuletzt.put(k, jetzt);
            starte(mc, level, chunk);
            return;
        }
    }

    /** Schatten und Biomübergang am Rand brauchen alle 8 Nachbarn. */
    private static boolean nachbarnGeladen(ClientLevel level, int cx, int cz) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (level.getChunkSource().getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private void starte(Minecraft mc, ClientLevel level, LevelChunk chunk) {
        Satz s = satz;
        if (licht == null) {
            licht = Licht.von(level.dimensionType());
        }
        ChunkMaler.Auftrag auftrag = ChunkMaler.abziehen(level, chunk, s.scale(), Integer.MAX_VALUE, licht,
                Minimap.INSTANZ.atlasTexel(mc), 0, s.mischung());
        Path ordner = Ebene.ordner(s.ordner().getParent());
        // Gezeichnet mit scale Pixeln je Block, abgelegt in der Auflösung der feinsten Stufe des Satzes.
        int mal = s.maxZoom() - s.stufe(), seite = s.chunk();
        long zeit = test ? System.currentTimeMillis() : Downloads.INSTANZ.serverzeit();
        laufendChunk = ChunkPos.pack(auftrag.cx(), auftrag.cz());
        laufend = CompletableFuture.supplyAsync(() -> {
            if (maler == null) {
                maler = new ChunkMaler();
            }
            if (vanillaAus != auftrag.texel()) {
                vanilla = ChunkMaler.Texel.vanilla(auftrag.texel(), mc.getVanillaPackResources().fullResources());
                vanillaAus = auftrag.texel();
            }
            int[] pixel = maler.male(auftrag.mitTexel(vanilla));
            try {
                Ebene.schreibe(ordner, auftrag.cx(), auftrag.cz(), Pyramide.verkleinere(pixel, 16 * s.scale(), mal), seite, zeit);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return true;
        }, worker).exceptionally(fehler -> {
            LOGGER.warn("Live-Ebene: Chunk nicht abgelegt", fehler);
            return false;
        });
    }

    /** Ein abgelegtes Bild meldet der Mod der offenen Karte; ein gescheitertes kommt wieder in die Reihe. */
    private void uebernimm(CompletableFuture<Boolean> fertig, long k) {
        boolean abgelegt;
        try {
            abgelegt = fertig.join();
        } catch (CompletionException e) {
            abgelegt = false;
        }
        if (abgelegt) {
            Kacheln.geaendert(ChunkPos.getX(k), ChunkPos.getZ(k));
        } else {
            offen.add(k);
        }
    }
}
