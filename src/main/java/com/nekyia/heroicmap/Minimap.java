package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
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
 * Die Minimap im HUD: eckig, genordet, der Spieler in der Mitte. Chunks, die sich ändern,
 * zeichnet ein Worker nach, die nächsten zuerst; der Render-Thread zieht sie ab und
 * übernimmt die Bilder, je Frame höchstens {@link #BUDGET_NS}.
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
    private final LongLinkedOpenHashSet offen = new LongLinkedOpenHashSet();
    private final Long2ObjectMap<Region> regionen = new Long2ObjectOpenHashMap<>();
    private final ArrayDeque<CompletableFuture<Bild>> laufend = new ArrayDeque<>();
    /** Zählt jedes Leeren mit; ein Bild aus einem älteren Stand fällt weg. */
    private long stand;
    private Licht licht;
    private int decke = Integer.MAX_VALUE;
    /** Die Texel des Block-Atlas und die Liste der Sprites, aus der sie stammen. */
    private Map<TextureAtlasSprite, ChunkMaler.Texel> texel;
    private List<TextureAtlasSprite> atlasStand;

    private Minimap() {
    }

    /** Ein fertiges Bild des Workers. */
    private record Bild(int cx, int cz, int scale, long stand, int[] pixel) {
    }

    /** Eine Textur über {@link #CHUNKS_JE_REGION}² Chunks. */
    private static final class Region {
        final Identifier id;
        final DynamicTexture textur;
        boolean geaendert;

        Region(int rx, int rz, int scale) {
            int seite = CHUNKS_JE_REGION * 16 * scale;
            id = Identifier.fromNamespaceAndPath(HeroicMap.ID, "region/" + rx + "_" + rz);
            textur = new DynamicTexture(() -> "heroicmap " + id, seite, seite, true);
            Minecraft.getInstance().getTextureManager().register(id, textur);
        }

        void schliessen() {
            Minecraft.getInstance().getTextureManager().release(id);
        }
    }

    /** Der Chunk (x, z) ist neu zu zeichnen. */
    public void markiere(int x, int z) {
        offen.add(ChunkPos.pack(x, z));
    }

    void umschalten() {
        sichtbar = !sichtbar;
    }

    void setzeSichtbar(boolean sichtbar) {
        this.sichtbar = sichtbar;
    }

    /** 1, 2 oder 4 Pixel je Block, der Reihe nach. */
    void naechsterMassstab() {
        scale = scale == 4 ? 1 : scale * 2;
        leeren();
        markiereUmSpieler();
    }

    int scale() {
        return scale;
    }

    /** Ist nichts mehr nachzuzeichnen? */
    boolean fertig() {
        return offen.isEmpty() && laufend.isEmpty();
    }

    /** Vergisst alles, etwa beim Wechsel der Welt. Bilder, die noch laufen, fallen weg. */
    void leeren() {
        regionen.values().forEach(Region::schliessen);
        regionen.clear();
        offen.clear();
        licht = null;
        stand++;
    }

    private void markiereUmSpieler() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        int weite = mc.options.getEffectiveRenderDistance();
        ChunkPos mitte = mc.player.chunkPosition();
        for (int dz = -weite; dz <= weite; dz++) {
            for (int dx = -weite; dx <= weite; dx++) {
                markiere(mitte.x() + dx, mitte.z() + dz);
            }
        }
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
                if (region == null) {
                    continue;
                }
                if (region.geaendert) {
                    region.textur.upload();
                    region.geaendert = false;
                }
                g.blit(RenderPipelines.GUI_TEXTURED, region.id, x0 + rx * seite - links, y0 + rz * seite - oben,
                        0, 0, seite, seite, seite, seite);
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
        pruefeAtlas(mc);
        if (licht == null) {
            licht = Licht.von(level.dimensionType());
        }
        if (level.dimensionType().hasCeiling()) {
            int kopf = Mth.floor(spieler.getEyeY());
            if (Math.abs(kopf - decke) >= DECKE_SCHRITT) {
                decke = kopf;
                markiereUmSpieler();
            }
        } else {
            decke = Integer.MAX_VALUE;
        }
        ChunkPos mitte = spieler.chunkPosition();
        raeume(mitte, mc.options.getEffectiveRenderDistance());
        long ende = System.nanoTime() + BUDGET_NS;
        while (!laufend.isEmpty() && laufend.peek().isDone() && System.nanoTime() < ende) {
            uebernimm(laufend.poll());
        }
        while (laufend.size() < IN_ARBEIT && !offen.isEmpty() && System.nanoTime() < ende) {
            long naechster = naechster(mitte);
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
            markiereUmSpieler();
        }
    }

    private void uebernimm(CompletableFuture<Bild> fertig) {
        Bild bild;
        try {
            bild = fertig.join();
        } catch (CompletionException e) {
            LOGGER.warn("Minimap: Chunk nicht gezeichnet", e.getCause());
            return;
        }
        if (bild.stand() != stand || bild.scale() != scale) {
            return;
        }
        Region region = regionen.computeIfAbsent(ChunkPos.pack(bild.cx() >> 3, bild.cz() >> 3),
                k -> new Region(ChunkPos.getX(k), ChunkPos.getZ(k), scale));
        NativeImage ziel = region.textur.getPixels();
        int seite = 16 * scale, ox = (bild.cx() & 7) * seite, oy = (bild.cz() & 7) * seite;
        for (int y = 0; y < seite; y++) {
            for (int x = 0; x < seite; x++) {
                ziel.setPixel(ox + x, oy + y, bild.pixel()[y * seite + x]);
            }
        }
        region.geaendert = true;
    }

    // ponytail: sucht linear, bei einigen hundert offenen Chunks je Frame genug; sonst ein Heap.
    private long naechster(ChunkPos mitte) {
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

    /** Gibt Regionen frei, die weit hinter der Sichtweite liegen. */
    private void raeume(ChunkPos mitte, int weite) {
        int grenze = weite / CHUNKS_JE_REGION + 2;
        regionen.long2ObjectEntrySet().removeIf(e -> {
            long k = e.getLongKey();
            boolean weit = Math.abs(ChunkPos.getX(k) - (mitte.x() >> 3)) > grenze
                    || Math.abs(ChunkPos.getZ(k) - (mitte.z() >> 3)) > grenze;
            if (weit) {
                e.getValue().schliessen();
            }
            return weit;
        });
    }

    /** Ein Pfeil in Blickrichtung: bei Gier 0 sieht der Spieler nach Süden, auf der Karte nach unten. */
    private static void pfeil(GuiGraphicsExtractor g, int x, int y, float gier) {
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
