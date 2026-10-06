package com.nekyia.heroicmap;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
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
    private CompletableFuture<Long> laufend;
    /** Zählt jedes Leeren mit; ein Bild aus einem älteren Stand fällt weg. */
    private long stand;
    /** Der Satz für diese Welt, oder null; gesucht beim ersten Bedarf nach jedem Leeren. */
    private Satz satz;
    private boolean gesucht;
    private Licht licht;
    /** Die Texel des Block-Atlas und die Liste der Sprites, aus der sie stammen. */
    private Map<TextureAtlasSprite, ChunkMaler.Texel> atlas;
    private List<TextureAtlasSprite> atlasStand;

    private Live() {
    }

    /** Ein Block an (x, z) hat sich sichtbar geändert; vom Mixin, auf dem Render-Thread. */
    public void markiere(int x, int z) {
        if (satz() == null) {
            return;
        }
        int cx = x >> 4, cz = z >> 4, lx = x & 15, lz = z & 15;
        // Am Rand ändert sich der Schatten des Nachbarn mit.
        for (int dz = lz == 0 ? -1 : 0; dz <= (lz == 15 ? 1 : 0); dz++) {
            for (int dx = lx == 0 ? -1 : 0; dx <= (lx == 15 ? 1 : 0); dx++) {
                offen.add(ChunkPos.pack(cx + dx, cz + dz));
            }
        }
    }

    /** Vergisst alles, etwa beim Wechsel der Welt, beim Trennen oder nach einem Download. */
    void leeren() {
        offen.clear();
        zuletzt.clear();
        satz = null;
        gesucht = false;
        licht = null;
        laufend = null;
        stand++;
    }

    /** Für den Gametest: ein Satz ohne Server, im Einzelspieler. */
    void satzFuerTest(Satz s) {
        satz = s;
        gesucht = true;
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
            uebernimm(laufend);
            laufend = null;
        }
        if (offen.isEmpty() || Minimap.INSTANZ.beschaeftigt()) {
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
        pruefeAtlas(mc);
        ChunkMaler.Auftrag auftrag = ChunkMaler.abziehen(level, chunk, s.scale(), Integer.MAX_VALUE, licht, atlas, stand,
                s.mischung());
        Path ordner = Ebene.ordner(s.ordner().getParent());
        // Gezeichnet mit scale Pixeln je Block, abgelegt in der Auflösung der feinsten Stufe des Satzes.
        int mal = s.maxZoom() - s.stufe(), seite = s.chunk();
        long zeit = Downloads.INSTANZ.serverzeit();
        long k = ChunkPos.pack(auftrag.cx(), auftrag.cz());
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
            return k;
        }, worker).exceptionally(fehler -> {
            LOGGER.warn("Live-Ebene: Chunk nicht gezeichnet", fehler);
            return null;
        });
    }

    private void uebernimm(CompletableFuture<Long> fertig) {
        Long k;
        try {
            k = fertig.join();
        } catch (CompletionException e) {
            return;
        }
        if (k != null) {
            Kacheln.geaendert(ChunkPos.getX(k), ChunkPos.getZ(k));
        }
    }

    /** Kopiert die Texel des Block-Atlas neu, wenn er neu geladen ist. */
    private void pruefeAtlas(Minecraft mc) {
        TextureAtlas a = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        if (a.sprites != atlasStand) {
            atlas = ChunkMaler.Texel.vomAtlas(a);
            atlasStand = a.sprites;
        }
    }
}
