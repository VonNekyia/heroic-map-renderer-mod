package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.joml.Matrix3x2fStack;
import org.slf4j.Logger;

/**
 * Die Minimap im HUD: eckig, genordet, der Spieler in der Mitte. Gezeichnet und behalten wird
 * der sichtbare Bereich plus {@link #VORRAT} Chunks. Chunks, die sich ändern, zeichnet ein
 * Worker nach, die nächsten zuerst; der Render-Thread zieht sie ab und übernimmt die Bilder,
 * je Frame höchstens {@link #BUDGET_NS}.
 * Siehe docs/minimap.md.
 */
public final class Minimap {

    public static final Minimap INSTANZ = new Minimap();
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Chunks je Seite einer Region, einer Textur. */
    static final int CHUNKS_JE_REGION = 8;
    /** Zeit je Frame auf dem Render-Thread für Abzüge und fertige Bilder. */
    static final long BUDGET_NS = 2_000_000L;
    /** So viele Chunks hat der Worker höchstens vor sich. */
    private static final int IN_ARBEIT = 4;
    /** Seite der Minimap in Einheiten des GUI. */
    static final int GROESSE = 128;
    /** Chunks je Richtung über den sichtbaren Bereich hinaus. */
    static final int VORRAT = 2;
    private static final int RAND = 4;
    /** Ändert sich die Höhe des Kopfes unter einer Decke um so viele Blöcke, wird neu gezeichnet. */
    private static final int DECKE_SCHRITT = 2;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Heroic Map Minimap");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    /** Gehört dem Worker; nur er legt ihn an und ruft ihn. */
    private ChunkMaler maler;

    private boolean sichtbar = true;
    private int scale = 2;
    final LongLinkedOpenHashSet offen = new LongLinkedOpenHashSet();
    /** Chunks im Bereich, deren Bild in der Textur steht. */
    private final LongOpenHashSet gezeichnet = new LongOpenHashSet();
    /** Zahl der übernommenen Bilder, für die Messung. */
    private long uebernommen;
    private final Long2ObjectMap<Region> regionen = new Long2ObjectOpenHashMap<>();
    private final ArrayDeque<CompletableFuture<Bild>> laufend = new ArrayDeque<>();
    /** Zählt jedes Leeren mit; ein Bild aus einem älteren Stand fällt weg. */
    private long stand;
    /** Der Chunk des Spielers, um den der Bereich liegt; null, bis er wieder bekannt ist. */
    ChunkPos mitte;
    private Licht licht;
    private int decke = Integer.MAX_VALUE;
    /** Der Biomübergang, mit dem gezeichnet ist; -1, solange keiner. */
    private int mischung = -1;
    /** Die Texel des Block-Atlas und die Liste der Sprites, aus der sie stammen. */
    private Map<TextureAtlasSprite, ChunkMaler.Texel> texel;
    private List<TextureAtlasSprite> atlasStand;
    /** Wann {@code arbeite} zuletzt lief, in ns. */
    private long gearbeitet;

    Minimap() {
    }

    /** Ein fertiges Bild des Workers. */
    private record Bild(int cx, int cz, int scale, long stand, int[] pixel) {
    }

    /** Eine Textur über {@link #CHUNKS_JE_REGION}² Chunks. */
    private static final class Region {
        final Identifier id;
        final DynamicTexture textur;

        Region(int rx, int rz, int scale) {
            int seite = CHUNKS_JE_REGION * 16 * scale;
            id = Identifier.fromNamespaceAndPath(HeroicMap.ID, "region/" + rx + "_" + rz);
            textur = new DynamicTexture(() -> "heroicmap " + id, seite, seite, true);
            // Einmal ganz und leer; danach nur noch die Bilder einzelner Chunks.
            textur.upload();
            Minecraft.getInstance().getTextureManager().register(id, textur);
        }

        void schliessen() {
            Minecraft.getInstance().getTextureManager().release(id);
        }
    }

    /** Der Chunk (x, z) ist neu zu zeichnen, wenn er im Bereich liegt. */
    public void markiere(int x, int z) {
        if (mitte == null || imBereich(x, z)) {
            offen.add(ChunkPos.pack(x, z));
        }
    }

    void umschalten() {
        setzeSichtbar(!sichtbar);
    }

    /**
     * Zeigt oder verbirgt die Minimap. Verborgen läuft {@code arbeite} nicht, die Mitte veraltet;
     * beim Zeigen ist sie deshalb unbekannt, bis der nächste Frame den Bereich anpasst.
     */
    void setzeSichtbar(boolean sichtbar) {
        if (sichtbar && !this.sichtbar) {
            mitte = null;
        }
        this.sichtbar = sichtbar;
    }

    /** 1, 2 oder 4 Pixel je Block, der Reihe nach. */
    void naechsterMassstab() {
        scale = scale == 4 ? 1 : scale * 2;
        leeren();
    }

    int scale() {
        return scale;
    }

    long uebernommen() {
        return uebernommen;
    }

    /**
     * Zeigt die Minimap, hat noch zu zeichnen und hat eben gearbeitet? Dann wartet die Live-Ebene.
     * Ohne HUD, etwa mit F1, läuft {@code arbeite} nicht; dann wartet sie nicht auf die Minimap.
     */
    boolean beschaeftigt() {
        return sichtbar && !fertig() && System.nanoTime() - gearbeitet < 250_000_000L;
    }

    /** Die Texel des Block-Atlas, auf dem Render-Thread; auch die Live-Ebene zeichnet aus dieser Kopie. */
    Map<TextureAtlasSprite, ChunkMaler.Texel> atlasTexel(Minecraft mc) {
        pruefeAtlas(mc);
        return texel;
    }

    /** Ist nichts mehr nachzuzeichnen? */
    boolean fertig() {
        return mitte != null && offen.isEmpty() && laufend.isEmpty();
    }

    /** Chunks je Richtung um den Spieler, die die Minimap zeichnet: sichtbar plus Vorrat. */
    int reichweite() {
        return reichweite(scale);
    }

    static int reichweite(int scale) {
        return Mth.ceil(GROESSE / 2f / (16f * scale)) + VORRAT;
    }

    private boolean imBereich(int x, int z) {
        int r = reichweite();
        return Math.abs(x - mitte.x()) <= r && Math.abs(z - mitte.z()) <= r;
    }

    /**
     * Vergisst alles, etwa beim Wechsel der Welt oder beim Trennen. Bilder, die noch laufen,
     * fallen weg; mit der nächsten Mitte wird der Bereich neu markiert.
     */
    void leeren() {
        regionen.values().forEach(Region::schliessen);
        regionen.clear();
        offen.clear();
        gezeichnet.clear();
        mitte = null;
        licht = null;
        stand++;
    }

    /** Zeichnet den Bereich neu, ohne die Texturen zu leeren. */
    private void neuZeichnen() {
        gezeichnet.clear();
        mitte = null;
    }

    void zeichne(GuiGraphicsExtractor g, DeltaTracker zeit) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer spieler = mc.player;
        ClientLevel level = mc.level;
        if (!sichtbar || spieler == null || level == null) {
            return;
        }
        arbeite(mc, level, spieler);

        int x0 = g.guiWidth() - GROESSE - RAND, y0 = RAND;
        g.fill(x0 - 1, y0 - 1, x0 + GROESSE + 1, y0 + GROESSE + 1, 0xFF000000);
        int links = Mth.floor(Projektion.zuPixel(spieler.getX(), scale)) - GROESSE / 2;
        int oben = Mth.floor(Projektion.zuPixel(spieler.getZ(), scale)) - GROESSE / 2;
        int seite = CHUNKS_JE_REGION * 16 * scale;
        g.enableScissor(x0, y0, x0 + GROESSE, y0 + GROESSE);
        for (int rz = Math.floorDiv(oben, seite); rz <= Math.floorDiv(oben + GROESSE - 1, seite); rz++) {
            for (int rx = Math.floorDiv(links, seite); rx <= Math.floorDiv(links + GROESSE - 1, seite); rx++) {
                Region region = regionen.get(ChunkPos.pack(rx, rz));
                if (region != null) {
                    g.blit(RenderPipelines.GUI_TEXTURED, region.id, x0 + rx * seite - links, y0 + rz * seite - oben,
                            0, 0, seite, seite, seite, seite);
                }
            }
        }
        g.disableScissor();
        pfeil(g, x0 + GROESSE / 2, y0 + GROESSE / 2, spieler.getYRot());
    }

    /**
     * Übernimmt fertige Bilder und gibt dem Worker neue Chunks, die nächsten zuerst, bis das
     * Budget des Frames aufgebraucht ist.
     */
    private void arbeite(Minecraft mc, ClientLevel level, LocalPlayer spieler) {
        gearbeitet = System.nanoTime();
        pruefeAtlas(mc);
        int radius = mc.options.biomeBlendRadius().get();
        if (radius != mischung) {
            // Ein anderer Biomübergang läuft über allChanged, nicht über setSectionDirty.
            if (mischung != -1) {
                neuZeichnen();
            }
            mischung = radius;
        }
        if (licht == null) {
            licht = Licht.von(level.dimensionType());
        }
        if (level.dimensionType().hasCeiling()) {
            int kopf = Mth.floor(spieler.getEyeY());
            if (Math.abs(kopf - decke) >= DECKE_SCHRITT) {
                decke = kopf;
                neuZeichnen();
            }
        } else {
            decke = Integer.MAX_VALUE;
        }
        ChunkPos jetzt = spieler.chunkPosition();
        if (!jetzt.equals(mitte)) {
            mitte = jetzt;
            nachBereich();
        }
        long ende = System.nanoTime() + BUDGET_NS;
        while (!laufend.isEmpty() && laufend.peek().isDone() && System.nanoTime() < ende) {
            uebernimm(laufend.poll());
        }
        while (laufend.size() < IN_ARBEIT && !offen.isEmpty() && System.nanoTime() < ende) {
            long naechster = naechster();
            offen.remove(naechster);
            LevelChunk chunk = level.getChunkSource()
                    .getChunk(ChunkPos.getX(naechster), ChunkPos.getZ(naechster), ChunkStatus.FULL, false);
            if (chunk == null) {
                continue;
            }
            ChunkMaler.Auftrag auftrag = ChunkMaler.abziehen(level, chunk, scale, decke, licht, texel, stand);
            laufend.add(CompletableFuture.supplyAsync(() -> {
                if (maler == null) {
                    maler = new ChunkMaler();
                }
                return new Bild(auftrag.cx(), auftrag.cz(), auftrag.scale(), auftrag.stand(), maler.male(auftrag));
            }, worker));
        }
    }

    /**
     * Passt alles an die neue Mitte an: Was den Bereich verlassen hat, fällt weg; was neu in
     * ihm liegt und kein Bild hat, wird markiert.
     */
    private void nachBereich() {
        int r = reichweite();
        offen.removeIf((long k) -> !imBereich(ChunkPos.getX(k), ChunkPos.getZ(k)));
        gezeichnet.removeIf((long k) -> !imBereich(ChunkPos.getX(k), ChunkPos.getZ(k)));
        regionen.long2ObjectEntrySet().removeIf(e -> {
            int rx = ChunkPos.getX(e.getLongKey()), rz = ChunkPos.getZ(e.getLongKey());
            boolean draussen = rx * CHUNKS_JE_REGION > mitte.x() + r || (rx + 1) * CHUNKS_JE_REGION - 1 < mitte.x() - r
                    || rz * CHUNKS_JE_REGION > mitte.z() + r || (rz + 1) * CHUNKS_JE_REGION - 1 < mitte.z() - r;
            if (draussen) {
                e.getValue().schliessen();
            }
            return draussen;
        });
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                long k = ChunkPos.pack(mitte.x() + dx, mitte.z() + dz);
                if (!gezeichnet.contains(k)) {
                    offen.add(k);
                }
            }
        }
    }

    /** Kopiert die Texel neu, wenn der Block-Atlas neu geladen ist, und zeichnet alles neu. */
    private void pruefeAtlas(Minecraft mc) {
        TextureAtlas atlas = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        if (atlas.sprites == atlasStand) {
            return;
        }
        boolean nachgeladen = atlasStand != null;
        texel = ChunkMaler.Texel.vomAtlas(atlas);
        atlasStand = atlas.sprites;
        if (nachgeladen) {
            leeren();
        }
    }

    /** Schreibt das Bild eines Chunks in seine Region, nur diesen Ausschnitt auf die GPU. */
    private void uebernimm(CompletableFuture<Bild> fertig) {
        Bild bild;
        try {
            bild = fertig.join();
        } catch (CompletionException e) {
            LOGGER.warn("Minimap: Chunk nicht gezeichnet", e.getCause());
            return;
        }
        if (bild.stand() != stand || bild.scale() != scale || mitte == null || !imBereich(bild.cx(), bild.cz())) {
            return;
        }
        Region region = regionen.computeIfAbsent(ChunkPos.pack(bild.cx() >> 3, bild.cz() >> 3),
                k -> new Region(ChunkPos.getX(k), ChunkPos.getZ(k), scale));
        int seite = 16 * scale, ox = (bild.cx() & 7) * seite, oy = (bild.cz() & 7) * seite;
        try (NativeImage stueck = new NativeImage(seite, seite, false)) {
            for (int y = 0; y < seite; y++) {
                for (int x = 0; x < seite; x++) {
                    stueck.setPixel(x, y, bild.pixel()[y * seite + x]);
                }
            }
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(region.textur.getTexture(), stueck, 0, 0, ox, oy);
        }
        gezeichnet.add(ChunkPos.pack(bild.cx(), bild.cz()));
        uebernommen++;
    }

    // ponytail: sucht linear, im Bereich liegen höchstens 169 Chunks; sonst ein Heap.
    private long naechster() {
        long bester = offen.firstLong();
        int besteWeite = Integer.MAX_VALUE;
        for (LongIterator it = offen.iterator(); it.hasNext(); ) {
            long k = it.nextLong();
            int dx = ChunkPos.getX(k) - mitte.x(), dz = ChunkPos.getZ(k) - mitte.z();
            int w = dx * dx + dz * dz;
            if (w < besteWeite) {
                besteWeite = w;
                bester = k;
            }
        }
        return bester;
    }

    /** Ein Pfeil in Blickrichtung: bei Gier 0 sieht der Spieler nach Süden, auf der Karte nach unten. */
    static void pfeil(GuiGraphicsExtractor g, int x, int y, float gier) {
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.rotate((float) Math.toRadians(gier + 180));
        for (int i = 0; i < 4; i++) {
            g.fill(-i - 1, -4 + i, i + 2, -2 + i, 0xFF000000);
        }
        for (int i = 0; i < 4; i++) {
            g.fill(-i, -3 + i, i + 1, -2 + i, 0xFFFFFFFF);
        }
        pose.popMatrix();
    }
}
