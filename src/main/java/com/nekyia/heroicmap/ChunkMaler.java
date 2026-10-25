package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.QuadInstance;
import it.unimi.dsi.fastutil.objects.Reference2BooleanOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CopperChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3fc;

/**
 * Zeichnet einen Chunk von oben: je Spalte die Flächen nach oben, die der Tesselator des
 * Spiels liefert, mit Tönung, weicher Beleuchtung und Licht. {@link #abziehen} läuft auf
 * dem Render-Thread, {@link #male} im Worker; dieser liest die Blöcke aus den Kopien des
 * Abzugs, Licht und Tönung über die Region live aus der Welt.
 * Siehe docs/minimap.md.
 */
final class ChunkMaler {

    /** Ab hier deckt ein Pixel. */
    private static final float DECKT = 0.996f;
    /** Spalte ohne Inhalt, etwa eine Wand unter einer Decke. */
    private static final int LEER = Integer.MIN_VALUE;
    /** sRGB-Wert nach linearem Licht. */
    static final float[] LINEAR = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            float c = i / 255f;
            LINEAR[i] = c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055) / 1.055, 2.4);
        }
    }

    /** Das erste Bild eines Sprites, kopiert. */
    record Texel(int[] argb, int breite, int hoehe) {

        static Texel von(TextureAtlasSprite sprite) {
            SpriteContents inhalt = sprite.contents();
            NativeImage bild = inhalt.originalImage;
            int breite = inhalt.width(), hoehe = inhalt.height();
            int[] argb = new int[breite * hoehe];
            for (int y = 0; y < hoehe; y++) {
                for (int x = 0; x < breite; x++) {
                    argb[y * breite + x] = bild.getPixel(x, y);
                }
            }
            return new Texel(argb, breite, hoehe);
        }

        int at(int x, int y) {
            return argb[y * breite + x];
        }

        /** Die Texel aller Sprites des Atlas, auf dem Render-Thread kopiert. */
        static Map<TextureAtlasSprite, Texel> vomAtlas(TextureAtlas atlas) {
            Map<TextureAtlasSprite, Texel> texel = new IdentityHashMap<>();
            for (TextureAtlasSprite sprite : atlas.sprites) {
                texel.put(sprite, von(sprite));
            }
            return texel;
        }
    }

    /**
     * Was der Worker für einen Chunk braucht: je Spalte die erste Höhe, die Abschnitte von
     * {@code minSektion} an als Kopien und die Texel aller Sprites des Block-Atlas. Mit {@code relief} dazu die Höhen um den
     * Chunk ({@link Relief#codes}); ohne beides null.
     */
    record Auftrag(int cx, int cz, int scale, Licht licht, Map<TextureAtlasSprite, Texel> texel,
            int[] start, int minSektion, RenderSectionRegion[] abschnitte, long stand, int[] hoehen, Relief.Art relief) {
    }

    /**
     * Zieht den Chunk auf dem Render-Thread ab. Je Spalte reicht er von der Höhenkarte, oder
     * von {@code decke}, bis zum ersten vollen Block darunter. Legt {@code male} das Relief {@code relief} auf, und gibt es
     * keine Decke, kommen die Höhen um den Chunk dazu; „ohne“ und null legen keins auf. Siehe docs/minimap.md, „Relief“.
     */
    static Auftrag abziehen(ClientLevel level, LevelChunk chunk, int scale, int decke, Licht licht,
            Map<TextureAtlasSprite, Texel> texel, long stand, Relief.Art relief) {
        Relief.Art auf = relief == null || relief == Relief.Art.OHNE || decke != Integer.MAX_VALUE ? null : relief;
        int[] hoehen = auf == null ? null : hoehen(level, chunk.getPos().x(), chunk.getPos().z());
        int cx = chunk.getPos().x(), cz = chunk.getPos().z();
        int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int[] start = new int[256];
        int unten = Integer.MAX_VALUE, oben = Integer.MIN_VALUE;
        for (int i = 0; i < 256; i++) {
            int y = Math.min(chunk.getHeight(Heightmap.Types.WORLD_SURFACE, i & 15, i >> 4), decke);
            pos.set(bx + (i & 15), y, bz + (i >> 4));
            if (decke != Integer.MAX_VALUE && chunk.getBlockState(pos).isSolidRender()) {
                start[i] = LEER;
                continue;
            }
            start[i] = y;
            oben = Math.max(oben, y);
            while (y > level.getMinY() && !chunk.getBlockState(pos.setY(y)).isSolidRender()) {
                y--;
            }
            unten = Math.min(unten, y);
        }
        if (oben == Integer.MIN_VALUE) {
            return new Auftrag(cx, cz, scale, licht, texel, start, 0, new RenderSectionRegion[0], stand, hoehen, auf);
        }
        int minSektion = SectionPos.blockToSectionCoord(unten), maxSektion = SectionPos.blockToSectionCoord(oben);
        RenderRegionCache kopien = new RenderRegionCache();
        RenderSectionRegion[] abschnitte = new RenderSectionRegion[maxSektion - minSektion + 1];
        for (int sy = minSektion; sy <= maxSektion; sy++) {
            abschnitte[sy - minSektion] = kopien.createRegion(level, SectionPos.asLong(cx, sy, cz));
        }
        return new Auftrag(cx, cz, scale, licht, texel, start, minSektion, abschnitte, stand, hoehen, auf);
    }

    /**
     * Die Höhen um den Chunk für das Relief, aus WORLD_SURFACE: {@link Relief#SEITE} im Quadrat ab 32 Blöcken nordwestlich der
     * Ecke, bis 1 Block östlich und südlich; wo kein Chunk geladen ist, {@link Relief#FEHLT}.
     */
    static int[] hoehen(ClientLevel level, int cx, int cz) {
        int[] h = new int[Relief.SEITE * Relief.SEITE];
        int x0 = SectionPos.sectionToBlockCoord(cx) - Relief.REICHWEITE, z0 = SectionPos.sectionToBlockCoord(cz) - Relief.REICHWEITE;
        for (int kz = SectionPos.blockToSectionCoord(z0); kz <= cz + 1; kz++) {
            for (int kx = SectionPos.blockToSectionCoord(x0); kx <= cx + 1; kx++) {
                LevelChunk c = level.getChunkSource().getChunk(kx, kz, ChunkStatus.FULL, false);
                for (int lz = 0; lz < 16; lz++) {
                    int z = SectionPos.sectionToBlockCoord(kz) + lz - z0;
                    for (int lx = 0; lx < 16; lx++) {
                        int x = SectionPos.sectionToBlockCoord(kx) + lx - x0;
                        if (x >= 0 && z >= 0 && x < Relief.SEITE && z < Relief.SEITE) {
                            h[z * Relief.SEITE + x] = c == null ? Relief.FEHLT : c.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                        }
                    }
                }
            }
        }
        return h;
    }

    private final Minecraft minecraft = Minecraft.getInstance();
    private final ModelBlockRenderer tesselator = new ModelBlockRenderer(true, true, minecraft.getBlockColors());
    private final Reference2BooleanOpenHashMap<BlockState> ohneGeometrie = new Reference2BooleanOpenHashMap<>();
    private final List<Flaeche> flaechen = new ArrayList<>();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final float[] hell = new float[3];

    /** Der Auftrag, an dem der Maler gerade ist; null zwischen zwei Aufträgen. */
    private Auftrag auftrag;
    /** Die Texel des letzten Auftrags; ändern sie sich, ist neu geladen. */
    private Map<TextureAtlasSprite, Texel> texelStand;
    private int scale;
    /** Der Abschnitt mit seinen Nachbarn, in dem der aktuelle Block liegt. */
    private RenderSectionRegion welt;
    /** Hat der aktuelle Block eine Fläche nach oben, bei einer Pflanze eine liegende? */
    private boolean oben;
    /**
     * Die Flächen des aktuellen Blocks, die gekippt werden, über alle Blöcke wiederverwendet: je Fläche das Objekt, 12 Werte
     * Ecken (x, y, z samt Versatz), 8 UV, je Ecke Lichtkoordinaten und Farbe roh, der Faktor nach oben, Sprite und Schicht.
     * Das Licht rechnet {@link #kippe} nur für die Flächen, die nach dem Kippen gezeichnet werden.
     */
    private BakedQuad[] kippQuad = new BakedQuad[16];
    private float[] kippEcken = new float[12 * 16], kippUv = new float[8 * 16], kippFaktor = new float[16];
    private int[] kippLichtRoh = new int[4 * 16], kippFarbe = new int[4 * 16];
    private TextureAtlasSprite[] kippSprite = new TextureAtlasSprite[16];
    private ChunkSectionLayer[] kippSchicht = new ChunkSectionLayer[16];
    private int kippAnzahl;
    /** Der Versatz des Blocks, dessen Flächen gemerkt sind. */
    private float kippX, kippY, kippZ;
    private final Schluessel suche = new Schluessel();
    /** Die Draufsichten der gekippten Pflanzen beim aktuellen scale, die zuletzt gebrauchte zuletzt. */
    // ponytail: feste Obergrenze, rund 10 MB je Maler; misst ein Paket mit vielen Pflanzen zu viele Fehlgriffe, nach Bytes
    // statt nach Zahl begrenzen.
    private final LinkedHashMap<Schluessel, Draufsicht> draufsichten = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Schluessel, Draufsicht> aelteste) {
            return size() > Math.min(4096, (1 << 18) / (scale * scale));
        }
    };
    /** Je Pixel der Spalte vormultipliziert in sRGB: r, g, b, a. */
    private float[] summe = new float[0];

    /**
     * Eine Fläche nach oben in x und z, mit UV, Sprite, Schicht und dem Faktor ihrer Ecken
     * aus Licht, Schatten und Tönung; {@code hoehe} ordnet sie im Block. Mit {@code draufsicht} die gekippten Flächen einer
     * Pflanze: {@code ecken} trägt dann den Faktor je Fläche der Draufsicht, xz, UV, Sprite und Schicht bleiben leer.
     */
    private record Flaeche(float hoehe, float[] xz, float[] uv, TextureAtlasSprite sprite,
            ChunkSectionLayer schicht, float[][] ecken, Draufsicht draufsicht) {
    }

    /**
     * Die gekippten Flächen einer Pflanze von oben, je Pixel vorgerechnet: für jede gezeichnete Fläche, die höchste zuerst, ihr
     * Anteil am Pixel und ihre Farbe in sRGB, je 4 Werte, dazu wie weit alle zusammen decken. {@code flaechen} nennt die
     * gemerkten Flächen in dieser Folge, {@code hoehe} ordnet sie im Block; {@code zEgal}: der Versatz in z ändert nichts.
     */
    private record Draufsicht(int[] flaechen, float[] zellen, float[] deckung, float hoehe, boolean zEgal) {
    }

    /** Die gemerkten Flächen eines Blocks und sein Versatz; gleich sind dieselben Objekte, nicht gleiche. */
    private static final class Schluessel {
        private BakedQuad[] quads;
        private int anzahl, hash;
        private float x, y, z;
        private boolean zEgal;

        Schluessel setze(BakedQuad[] quads, int anzahl, float x, float y, float z, boolean zEgal) {
            this.quads = quads;
            this.anzahl = anzahl;
            this.x = x;
            this.y = y;
            this.z = z;
            this.zEgal = zEgal;
            int h = 31 * Boolean.hashCode(zEgal) + Float.floatToIntBits(x);
            h = 31 * (31 * h + Float.floatToIntBits(y)) + Float.floatToIntBits(z);
            for (int i = 0; i < anzahl; i++) {
                h = 31 * h + System.identityHashCode(quads[i]);
            }
            hash = h;
            return this;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Schluessel s) || s.hash != hash || s.anzahl != anzahl || s.zEgal != zEgal
                    || Float.floatToIntBits(s.x) != Float.floatToIntBits(x) || Float.floatToIntBits(s.y) != Float.floatToIntBits(y)
                    || Float.floatToIntBits(s.z) != Float.floatToIntBits(z)) {
                return false;
            }
            for (int i = 0; i < anzahl; i++) {
                if (s.quads[i] != quads[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Zeichnet den Chunk; je Pixel ARGB, Zeile für Zeile, 16 · scale breit. */
    int[] male(Auftrag a) {
        if (a.scale() != scale || a.texel() != texelStand) {
            // Neu geladen: Raster und Modelle können anders sein.
            raster.clear();
            draufsichten.clear();
            ohneGeometrie.clear();
            texelStand = a.texel();
        }
        auftrag = a;
        if (scale != a.scale()) {
            scale = a.scale();
            summe = new float[scale * scale * 4];
        }
        try {
            int[] pixel = zeichne(a);
            if (a.relief() != null) {
                Relief.auf(pixel, scale, Relief.codes(a.hoehen()), a.relief());
            }
            return pixel;
        } finally {
            welt = null;
            auftrag = null;
        }
    }

    private int[] zeichne(Auftrag a) {
        int seite = 16 * scale;
        int[] pixel = new int[seite * seite];
        ModelManager modelle = minecraft.getModelManager();
        int bx = SectionPos.sectionToBlockCoord(a.cx()), bz = SectionPos.sectionToBlockCoord(a.cz());
        for (int i = 0; i < 256; i++) {
            int lx = i & 15, lz = i >> 4;
            Arrays.fill(summe, 0);
            boolean oberflaeche = false;
            for (int y = a.start()[i]; y != LEER && !deckt(); y--) {
                int abschnitt = SectionPos.blockToSectionCoord(y) - a.minSektion();
                if (abschnitt < 0 || abschnitt >= a.abschnitte().length) {
                    break;
                }
                if (welt != a.abschnitte()[abschnitt]) {
                    welt = a.abschnitte()[abschnitt];
                }
                pos.set(bx + lx, y, bz + lz);
                BlockState state = welt.getBlockState(pos);
                if (state.isAir()) {
                    continue;
                }
                flaechen.clear();
                if (state.getRenderShape() == RenderShape.MODEL) {
                    sammleBlock(modelle.getBlockStateModelSet().get(state), state);
                }
                FluidState fluid = state.getFluidState();
                if (!fluid.isEmpty() && !oberflaeche) {
                    oberflaeche = true;
                    sammleFluessigkeit(modelle.getFluidStateModelSet().get(fluid), state, fluid);
                }
                // Von oben nach unten; was aus dem Wasser ragt, liegt vor der Oberfläche.
                flaechen.sort((f, g) -> Float.compare(g.hoehe(), f.hoehe()));
                for (Flaeche f : flaechen) {
                    if (f.draufsicht() != null) {
                        lege(summe, f.draufsicht().zellen(), f.draufsicht().deckung(), f.ecken());
                    } else {
                        schicht(f.xz(), f.uv(), f.sprite(), f.schicht(), f.ecken());
                    }
                }
            }
            schreibe(pixel, seite, lx * scale, lz * scale);
        }
        return pixel;
    }

    private boolean deckt() {
        for (int i = 3; i < summe.length; i += 4) {
            if (summe[i] < DECKT) {
                return false;
            }
        }
        return true;
    }

    private void sammleBlock(BlockStateModel model, BlockState state) {
        if (ohneGeometrie.computeIfAbsent(state, s -> ohneFlaechen(model))) {
            sammleBlockentity(model, state);
            return;
        }
        boolean pflanze = Pflanzen.bodenpflanze(state);
        boolean bambus = !pflanze && state.is(Blocks.BAMBOO);
        oben = false;
        kippAnzahl = 0;
        tesselator.tesselateBlock((x, y, z, quad, instanz) -> {
            // Bei einer Pflanze zählt nur eine liegende Fläche; die übrigen kippen, auch die gekippten eines Pakets. Bei Bambus
            // kippen nur die Blätter. Siehe docs/minimap.md, „Pflanzen von oben“,
            // und docs/minimap.md, „Pflanzen aus Paketen“.
            if (pflanze ? !liegt(quad) : !nachOben(quad)) {
                if (pflanze || bambus && quad.materialInfo().sprite().contents().name().getPath().contains("leaves")) {
                    merke(quad, instanz, x, y, z);
                }
                return;
            }
            oben = true;
            // x, y und z tragen den Versatz des Blocks, etwa bei Bambus.
            float[] xz = new float[8], uv = new float[8];
            float hoehe = Float.NEGATIVE_INFINITY;
            for (int k = 0; k < 4; k++) {
                Vector3fc p = quad.position(k);
                xz[2 * k] = p.x() + x;
                xz[2 * k + 1] = p.z() + z;
                hoehe = Math.max(hoehe, p.y() + y);
                long packed = quad.packedUV(k);
                uv[2 * k] = UVPair.unpackU(packed);
                uv[2 * k + 1] = UVPair.unpackV(packed);
            }
            flaechen.add(new Flaeche(hoehe, xz, uv, quad.materialInfo().sprite(), quad.materialInfo().layer(),
                    ecken(instanz, quad.materialInfo().lightEmission()), null));
        }, 0, 0, 0, welt, pos, state, model, state.getSeed(pos));
        // Ein Modell mit einer liegenden Fläche, etwa ein Blütenteppich, bleibt, wie es ist; Bambus kippt nur seine Blätter.
        if (kippAnzahl > 0 && (bambus || !oben)) {
            FluidState wasser = state.getFluidState();
            kippe(wasser.isEmpty() ? Float.POSITIVE_INFINITY : wasser.getHeight(welt, pos) - 1 / 64f);
        }
    }

    /** Merkt eine Fläche zum Kippen: sie selbst, Ecken samt Versatz, UV, Licht roh, den Faktor nach oben, Sprite und Schicht. */
    private void merke(BakedQuad quad, QuadInstance instanz, float x, float y, float z) {
        if (kippAnzahl == kippSprite.length) {
            int n = 2 * kippAnzahl;
            kippEcken = Arrays.copyOf(kippEcken, 12 * n);
            kippUv = Arrays.copyOf(kippUv, 8 * n);
            kippFaktor = Arrays.copyOf(kippFaktor, n);
            kippLichtRoh = Arrays.copyOf(kippLichtRoh, 4 * n);
            kippFarbe = Arrays.copyOf(kippFarbe, 4 * n);
            kippSprite = Arrays.copyOf(kippSprite, n);
            kippSchicht = Arrays.copyOf(kippSchicht, n);
            kippQuad = Arrays.copyOf(kippQuad, n);
        }
        int i = kippAnzahl++;
        kippQuad[i] = quad;
        kippX = x;
        kippY = y;
        kippZ = z;
        kippFaktor[i] = schattenNachOben(welt.cardinalLighting(), quad.materialInfo().shadeDirectionOverride(), quad.direction());
        int leuchten = quad.materialInfo().lightEmission();
        for (int k = 0; k < 4; k++) {
            Vector3fc p = quad.position(k);
            kippEcken[12 * i + 3 * k] = p.x() + x;
            kippEcken[12 * i + 3 * k + 1] = p.y() + y;
            kippEcken[12 * i + 3 * k + 2] = p.z() + z;
            long packed = quad.packedUV(k);
            kippUv[8 * i + 2 * k] = UVPair.unpackU(packed);
            kippUv[8 * i + 2 * k + 1] = UVPair.unpackV(packed);
            kippLichtRoh[4 * i + k] = instanz.getLightCoordsWithEmission(k, leuchten);
            kippFarbe[4 * i + k] = instanz.getColor(k);
        }
        kippSprite[i] = quad.materialInfo().sprite();
        kippSchicht[i] = quad.materialInfo().layer();
    }

    /**
     * Legt die gemerkten Flächen gekippt in die Spalte, im Wasser höchstens {@code hoechstens} hoch eingereiht: ihre
     * Draufsicht aus dem Vorrat, sonst vorgerechnet, je Fläche im Mittel des Lichts ihrer Ecken und nach oben schattiert.
     * Siehe docs/minimap.md, „Pflanzen von oben“.
     */
    private void kippe(float hoechstens) {
        Draufsicht d = draufsichten.get(suche.setze(kippQuad, kippAnzahl, kippX, kippY, 0, true));
        if (d == null) {
            d = draufsichten.get(suche.setze(kippQuad, kippAnzahl, kippX, kippY, kippZ, false));
        }
        if (d == null) {
            d = baue();
            draufsichten.put(new Schluessel().setze(Arrays.copyOf(kippQuad, kippAnzahl), kippAnzahl, kippX, kippY,
                    d.zEgal() ? 0 : kippZ, d.zEgal()), d);
        }
        float[][] faktoren = new float[d.flaechen().length][3];
        for (int j = 0; j < faktoren.length; j++) {
            int i = d.flaechen()[j];
            for (int k = 0; k < 4; k++) {
                auftrag.licht().hell(kippLichtRoh[4 * i + k], hell);
                int farbe = kippFarbe[4 * i + k];
                for (int c = 0; c < 3; c++) {
                    faktoren[j][c] += hell[c] * ((farbe >> (16 - 8 * c)) & 0xFF) / 255f * kippFaktor[i] / 4;
                }
            }
        }
        flaechen.add(new Flaeche(Math.min(d.hoehe(), hoechstens), null, null, null, null, faktoren, d));
    }

    /**
     * Kippt die gemerkten Flächen 45° nach Norden, setzt sie auf ihre alte Tiefe und passt die, die danach nach oben zeigen,
     * entlang z in den Block ein; dann je Pixel, von der höchsten Fläche an, Anteil und Farbe aus ihrem gemittelten Raster.
     * Siehe docs/minimap.md, „Pflanzen von oben“.
     */
    private Draufsicht baue() {
        float alt = Float.MAX_VALUE, neu = Float.MAX_VALUE, zMin = Float.MAX_VALUE, zMax = -Float.MAX_VALUE;
        boolean[] sichtbar = new boolean[kippAnzahl];
        for (int i = 0; i < kippAnzahl; i++) {
            int b = 12 * i;
            Texel textur = auftrag.texel().get(kippSprite[i]);
            if (textur != null) {
                TextureAtlasSprite s = kippSprite[i];
                zuschneiden(kippEcken, b, kippUv, 8 * i, textur, s.getU0(), s.getU1() - s.getU0(), s.getV0(), s.getV1() - s.getV0());
            }
            for (int k = 0; k < 4; k++) {
                alt = Math.min(alt, kippEcken[b + 3 * k + 1]);
            }
            kippen(kippEcken, b);
            for (int k = 0; k < 4; k++) {
                neu = Math.min(neu, kippEcken[b + 3 * k + 1]);
            }
            float[] e = kippEcken;
            sichtbar[i] = obenAnteil(e[b + 3] - e[b], e[b + 4] - e[b + 1], e[b + 5] - e[b + 2], e[b + 6] - e[b], e[b + 7] - e[b + 1],
                    e[b + 8] - e[b + 2]) > 1e-4f;
            for (int k = 0; k < 4 && sichtbar[i]; k++) {
                zMin = Math.min(zMin, e[b + 3 * k + 2]);
                zMax = Math.max(zMax, e[b + 3 * k + 2]);
            }
        }
        float faktor = einpassFaktor(zMin, zMax), schieben = einpassVerschiebung(zMin, zMax);
        // Die sichtbaren Flächen nach Höhe, die höchste zuerst, gleich hohe in ihrer Reihenfolge.
        int n = 0;
        int[] folge = new int[kippAnzahl];
        float[] hoehen = new float[kippAnzahl];
        float[][] xz = new float[kippAnzahl][8];
        for (int i = 0; i < kippAnzahl; i++) {
            if (!sichtbar[i]) {
                continue;
            }
            float h = Float.NEGATIVE_INFINITY;
            for (int k = 0; k < 4; k++) {
                int j = 12 * i + 3 * k;
                xz[i][2 * k] = kippEcken[j];
                xz[i][2 * k + 1] = kippEcken[j + 2] * faktor + schieben;
                h = Math.max(h, kippEcken[j + 1] + alt - neu);
            }
            hoehen[i] = h;
            int m = n++;
            for (; m > 0 && hoehen[folge[m - 1]] < h; m--) {
                folge[m] = folge[m - 1];
            }
            folge[m] = i;
        }
        folge = Arrays.copyOf(folge, n);
        float[] zellen = new float[scale * scale * n * 4], deckung = new float[scale * scale];
        for (int j = 0; j < n; j++) {
            int i = folge[j];
            Texel textur = auftrag.texel().get(kippSprite[i]);
            if (textur == null) {
                continue;
            }
            float[] voll = raster(kippSprite[i], textur, kippSchicht[i]), e = xz[i];
            float[] reiheX = duenn(e, 0, scale), reiheZ = duenn(e, 1, scale);
            for (int pz = 0; pz < scale; pz++) {
                for (int px = 0; px < scale; px++) {
                    int p = pz * scale + px;
                    if (deckung[p] >= DECKT || reiheX != null && px != (int) reiheX[0] || reiheZ != null && pz != (int) reiheZ[0]) {
                        continue;
                    }
                    float cx = reiheX != null ? reiheX[1] : (px + 0.5f) / scale, cz = reiheZ != null ? reiheZ[1] : (pz + 0.5f) / scale;
                    int[] d = dreieck(e, cx, cz);
                    if (d == null) {
                        continue;
                    }
                    int a = 8 * i + 2 * d[0], b = 8 * i + 2 * d[1], c = 8 * i + 2 * d[2];
                    int z = zelle(w[0] * kippUv[a] + w[1] * kippUv[b] + w[2] * kippUv[c],
                            w[0] * kippUv[a + 1] + w[1] * kippUv[b + 1] + w[2] * kippUv[c + 1], kippSprite[i]);
                    deckung[p] = stapel(zellen, 4 * (p * n + j), deckung[p], voll[z + 3], voll[z], voll[z + 1], voll[z + 2]);
                }
            }
        }
        return new Draufsicht(folge, zellen, deckung, n == 0 ? Float.NEGATIVE_INFINITY : hoehen[folge[0]], faktor != 1);
    }

    /**
     * Schneidet eine Fläche vor dem Kippen waagrecht zu: Was ausserhalb des Blocks liegt, unter y 0 oder über 1, und dort im
     * Bereich ihrer UV ganz durchsichtig ist, fällt weg; ganz durchsichtig fällt sie auf 0 bis 1. Ecken ab {@code b}, UV ab
     * {@code bu}; das Sprite liegt im Atlas bei u0, v0 mit der Breite du, dv. Siehe docs/minimap.md, „Zuschneiden“.
     */
    static void zuschneiden(float[] e, int b, float[] uv, int bu, Texel textur, float u0, float du, float v0, float dv) {
        float unten = Float.MAX_VALUE, oben = -Float.MAX_VALUE;
        for (int k = 0; k < 4; k++) {
            unten = Math.min(unten, e[b + 3 * k + 1]);
            oben = Math.max(oben, e[b + 3 * k + 1]);
        }
        if (unten >= 0 && oben <= 1) {
            return;
        }
        float[] deckend = deckendesY(e, b, uv, bu, textur, u0, du, v0, dv);
        float neuOben = Math.max(deckend == null ? -Float.MAX_VALUE : deckend[1], Math.min(oben, 1));
        float neuUnten = Math.min(deckend == null ? Float.MAX_VALUE : deckend[0], Math.max(unten, 0));
        // Liegt sie ganz ausserhalb und ganz durchsichtig, bleibt keine Höhe; dann zeigt sie nicht mehr nach oben.
        neuOben = Math.max(neuOben, neuUnten);
        if (neuOben < oben || neuUnten > unten) {
            schneide(e, b, uv, bu, neuUnten, neuOben);
        }
    }

    /**
     * Das kleinste und grösste y der deckenden Texel einer Fläche, über die Ecken ihrer Zellen im Rechteck der UV; null ohne
     * deckendes Texel. Lässt sich y nicht aus u und v rechnen, die Fläche selbst, so schneidet nichts.
     */
    static float[] deckendesY(float[] e, int b, float[] uv, int bu, Texel textur, float u0, float du, float v0, float dv) {
        int breite = textur.breite(), hoehe = textur.hoehe();
        float[] tu = new float[4], tv = new float[4];
        float uMin = Float.MAX_VALUE, uMax = -Float.MAX_VALUE, vMin = Float.MAX_VALUE, vMax = -Float.MAX_VALUE;
        for (int k = 0; k < 4; k++) {
            tu[k] = (uv[bu + 2 * k] - u0) / du * breite;
            tv[k] = (uv[bu + 2 * k + 1] - v0) / dv * hoehe;
            uMin = Math.min(uMin, tu[k]);
            uMax = Math.max(uMax, tu[k]);
            vMin = Math.min(vMin, tv[k]);
            vMax = Math.max(vMax, tv[k]);
        }
        // y = a · u + c · v + d aus den Ecken 0, 1 und 2, in Texeln.
        float y0 = e[b + 1], y1 = e[b + 4], y2 = e[b + 7];
        float det = (tu[1] - tu[0]) * (tv[2] - tv[0]) - (tu[2] - tu[0]) * (tv[1] - tv[0]);
        if (Math.abs(det) < 1e-6f) {
            float unten = Math.min(Math.min(y0, y1), Math.min(y2, e[b + 10])), oben = Math.max(Math.max(y0, y1), Math.max(y2, e[b + 10]));
            return new float[] {unten, oben};
        }
        float a = ((y1 - y0) * (tv[2] - tv[0]) - (y2 - y0) * (tv[1] - tv[0])) / det;
        float c = ((tu[1] - tu[0]) * (y2 - y0) - (tu[2] - tu[0]) * (y1 - y0)) / det;
        float d = y0 - a * tu[0] - c * tv[0];
        float unten = Float.MAX_VALUE, oben = -Float.MAX_VALUE;
        int x0 = Math.max(0, (int) Math.floor(uMin)), x1 = Math.min(breite, (int) Math.ceil(uMax));
        int z0 = Math.max(0, (int) Math.floor(vMin)), z1 = Math.min(hoehe, (int) Math.ceil(vMax));
        for (int ty = z0; ty < z1; ty++) {
            for (int tx = x0; tx < x1; tx++) {
                if (textur.at(tx, ty) >>> 24 == 0) {
                    continue;
                }
                // Die Zelle des Texels, auf das Rechteck der UV beschnitten; y ist affin, also zählen ihre Ecken.
                float ua = Math.max(tx, uMin), ub = Math.min(tx + 1, uMax), va = Math.max(ty, vMin), vb = Math.min(ty + 1, vMax);
                for (float u : new float[] {ua, ub}) {
                    for (float v : new float[] {va, vb}) {
                        float y = a * u + c * v + d;
                        unten = Math.min(unten, y);
                        oben = Math.max(oben, y);
                    }
                }
            }
        }
        return unten > oben ? null : new float[] {unten, oben};
    }

    /**
     * Schneidet die Fläche auf y von {@code unten} bis {@code oben}, wenn ein Paar ihrer Kanten waagrecht liegt: die Ecken
     * rücken entlang des anderen Paars, die UV linear mit. Sonst bleibt sie.
     */
    static void schneide(float[] e, int b, float[] uv, int bu, float unten, float oben) {
        int[][] seiten;
        if (waagrecht(e, b, 0, 1) && waagrecht(e, b, 3, 2)) {
            seiten = new int[][] {{0, 3}, {1, 2}};
        } else if (waagrecht(e, b, 1, 2) && waagrecht(e, b, 0, 3)) {
            seiten = new int[][] {{0, 1}, {3, 2}};
        } else {
            return;
        }
        for (int[] seite : seiten) {
            int tief = seite[0], hoch = seite[1];
            if (e[b + 3 * tief + 1] > e[b + 3 * hoch + 1]) {
                tief = seite[1];
                hoch = seite[0];
            }
            float ya = e[b + 3 * tief + 1], yb = e[b + 3 * hoch + 1];
            if (yb - ya < 1e-6f) {
                return;
            }
            float[] p = new float[5], q = new float[5];
            for (int j = 0; j < 3; j++) {
                p[j] = e[b + 3 * tief + j];
                q[j] = e[b + 3 * hoch + j];
            }
            for (int j = 0; j < 2; j++) {
                p[3 + j] = uv[bu + 2 * tief + j];
                q[3 + j] = uv[bu + 2 * hoch + j];
            }
            float ta = (unten - ya) / (yb - ya), tb = (oben - ya) / (yb - ya);
            for (int j = 0; j < 3; j++) {
                e[b + 3 * tief + j] = p[j] + ta * (q[j] - p[j]);
                e[b + 3 * hoch + j] = p[j] + tb * (q[j] - p[j]);
            }
            for (int j = 0; j < 2; j++) {
                uv[bu + 2 * tief + j] = p[3 + j] + ta * (q[3 + j] - p[3 + j]);
                uv[bu + 2 * hoch + j] = p[3 + j] + tb * (q[3 + j] - p[3 + j]);
            }
        }
    }

    private static boolean waagrecht(float[] e, int b, int i, int j) {
        return Math.abs(e[b + 3 * i + 1] - e[b + 3 * j + 1]) < 1e-6f;
    }

    /**
     * Stapelt eine Fläche unter die darüber, die den Pixel schon {@code deckung} weit decken: legt ab {@code o} ihren Anteil
     * und ihre Farbe aus linearem r, g, b in sRGB ab und gibt die neue Deckung zurück.
     */
    static float stapel(float[] zellen, int o, float deckung, float alpha, float r, float g, float b) {
        if (!(alpha > 0)) {
            return deckung;
        }
        float anteil = (1 - deckung) * alpha;
        zellen[o] = anteil;
        zellen[o + 1] = srgb(r);
        zellen[o + 2] = srgb(g);
        zellen[o + 3] = srgb(b);
        return deckung + anteil;
    }

    /**
     * Legt eine Draufsicht unter das, was die Spalte schon deckt, jede Fläche in ihrem Faktor aus {@code faktoren}; so, als
     * kämen ihre Flächen einzeln, wie in {@link #schicht}.
     */
    static void lege(float[] summe, float[] zellen, float[] deckung, float[][] faktoren) {
        int n = faktoren.length;
        for (int p = 0; p < deckung.length; p++) {
            int i = 4 * p;
            if (summe[i + 3] >= DECKT || !(deckung[p] > 0)) {
                continue;
            }
            float rest = 1 - summe[i + 3];
            for (int j = 0, o = 4 * p * n; j < n; j++, o += 4) {
                float anteil = rest * zellen[o];
                if (anteil > 0) {
                    for (int c = 0; c < 3; c++) {
                        summe[i + c] += anteil * Math.min(1, zellen[o + 1 + c] * faktoren[j][c]);
                    }
                }
            }
            summe[i + 3] += rest * deckung[p];
        }
    }

    /**
     * Kippt die vier Ecken ab {@code b} (je x, y, z) starr 45° um die Achse in x durch (·, 0, 0,5): oben wandert nach Norden,
     * nach −z; x bleibt.
     */
    static void kippen(float[] e, int b) {
        for (int k = 0; k < 4; k++) {
            int j = b + 3 * k;
            float t = 0.5f - e[j + 2], y = e[j + 1];
            e[j + 1] = (y - t) * WURZEL_HALB;
            e[j + 2] = 0.5f - (t + y) * WURZEL_HALB;
        }
    }

    private static final float WURZEL_HALB = (float) Math.sqrt(0.5);

    /** Der Faktor, mit dem die Ausdehnung [zMin, zMax] in den Block passt: 1 bis 1 Block Spanne, darüber 1 / Spanne. */
    static float einpassFaktor(float zMin, float zMax) {
        return zMax - zMin > 1 ? 1 / (zMax - zMin) : 1;
    }

    /**
     * Die Verschiebung nach dem Faktor: über 1 Block Spanne an 0 gelegt, sonst so wenig wie nötig nach [0, 1]; was passt,
     * bleibt, etwa der Versatz einer kleinen Pflanze.
     */
    static float einpassVerschiebung(float zMin, float zMax) {
        if (zMax - zMin > 1) {
            return -zMin / (zMax - zMin);
        }
        return zMin < 0 ? -zMin : zMax > 1 ? 1 - zMax : 0;
    }

    /**
     * Der Faktor, der die Schattierung einer Fläche durch die nach oben ersetzt: Das Spiel schattiert nach
     * {@code ueberschrieben}, sonst nach ihrer {@code richtung}.
     */
    static float schattenNachOben(CardinalLighting licht, Direction ueberschrieben, Direction richtung) {
        return licht.up() / licht.byFace(ueberschrieben != null ? ueberschrieben : richtung);
    }

    /**
     * Blockentities wie Truhen, Schilder und Köpfe haben im Modell keine Fläche: über ihre Form von
     * oben das Partikel-Sprite, bei einer Truhe der Deckel aus ihrer Textur; im Licht des Blocks darüber.
     * Siehe docs/minimap.md, „Blockentities“.
     */
    private void sammleBlockentity(BlockStateModel model, BlockState state) {
        AABB box;
        try {
            VoxelShape form = state.getShape(welt, pos);
            if (form.isEmpty()) {
                return;
            }
            box = form.bounds();
        } catch (RuntimeException e) {
            // Ein Block eines Mods, der im Worker mehr als eine Region erwartet: der ganze Block.
            box = new AABB(0, 0, 0, 1, 1, 1);
        }
        float x0 = (float) Math.max(0, box.minX), x1 = (float) Math.min(1, box.maxX);
        float z0 = (float) Math.max(0, box.minZ), z1 = (float) Math.min(1, box.maxZ);
        float[] xz = {x0, z0, x0, z1, x1, z1, x1, z0}, uv = new float[8];
        TextureAtlasSprite s;
        float du, dv;
        if (state.getBlock() instanceof AbstractChestBlock<?>) {
            ChestType typ = state.hasProperty(ChestBlock.TYPE) ? state.getValue(ChestBlock.TYPE) : ChestType.SINGLE;
            Direction facing = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                    ? state.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.NORTH;
            SpriteId id = Sheets.chooseSprite(truhe(state), typ);
            // atlasLocation ist der Pfad der Textur des Atlas, nicht seine Kennung; die Truhen liegen in AtlasIds.CHESTS.
            s = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.CHESTS).getSprite(id.texture());
            float[] pixel = deckel(facing, typ, xz);
            du = (s.getU1() - s.getU0()) / 64;
            dv = (s.getV1() - s.getV0()) / 64;
            for (int k = 0; k < 4; k++) {
                uv[2 * k] = s.getU0() + pixel[2 * k] * du;
                uv[2 * k + 1] = s.getV0() + pixel[2 * k + 1] * dv;
            }
        } else {
            s = model.particleMaterial().sprite();
            du = s.getU1() - s.getU0();
            dv = s.getV1() - s.getV0();
            for (int k = 0; k < 4; k++) {
                uv[2 * k] = s.getU0() + xz[2 * k] * du;
                uv[2 * k + 1] = s.getV0() + xz[2 * k + 1] * dv;
            }
        }
        auftrag.licht().hell(LightCoordsUtil.getLightCoords(welt, pos.above()), hell);
        flaechen.add(new Flaeche((float) box.maxY, xz, uv, s, ChunkSectionLayer.SOLID,
                new float[][] {hell.clone(), hell.clone(), hell.clone(), hell.clone()}, null));
    }

    /**
     * Die Textur einer Truhe, wie das Spiel sie wählt (ChestRenderer.getChestMaterial, belegt per javap
     * am Client 26.3): Kupfer nach seinem Zustand, Ender, Falle, sonst die gewöhnliche; Weihnachten nicht.
     */
    static ChestRenderState.ChestMaterialType truhe(BlockState state) {
        return switch (state.getBlock()) {
            case CopperChestBlock k -> switch (k.getState()) {
                case UNAFFECTED -> ChestRenderState.ChestMaterialType.COPPER_UNAFFECTED;
                case EXPOSED -> ChestRenderState.ChestMaterialType.COPPER_EXPOSED;
                case WEATHERED -> ChestRenderState.ChestMaterialType.COPPER_WEATHERED;
                case OXIDIZED -> ChestRenderState.ChestMaterialType.COPPER_OXIDIZED;
            };
            case EnderChestBlock e -> ChestRenderState.ChestMaterialType.ENDER_CHEST;
            case TrappedChestBlock t -> ChestRenderState.ChestMaterialType.TRAPPED;
            default -> ChestRenderState.ChestMaterialType.REGULAR;
        };
    }

    /**
     * Wo die Ecken {@code xz} des Blocks auf der Oberseite des Deckels liegen, in Pixeln einer Textur von
     * 64: einzeln u 28 bis 42, als linke oder rechte Hälfte 29 bis 44, v 0 bis 14. Das Spiel dreht das
     * Modell um die Mitte um −{@code facing.toYRot()}; der Deckel reicht im Modell einzeln über x 1 bis 15,
     * links 0 bis 15, rechts 1 bis 16, und über z 1 bis 15 (ChestModel, ChestRenderer, belegt per javap).
     */
    static float[] deckel(Direction facing, ChestType typ, float[] xz) {
        double w = Math.toRadians(facing.toYRot()), c = Math.cos(w), sn = Math.sin(w);
        int von = typ == ChestType.SINGLE ? 28 : 29, breite = typ == ChestType.SINGLE ? 14 : 15;
        // Wo im Modell x = 0 auf u fällt: einzeln und rechts beginnt der Deckel bei x = 1.
        double null0 = typ == ChestType.LEFT ? von : von - 1;
        float[] aus = new float[8];
        for (int k = 0; k < 4; k++) {
            double dx = xz[2 * k] - 0.5, dz = xz[2 * k + 1] - 0.5;
            double mx = dx * c + dz * sn + 0.5, mz = -dx * sn + dz * c + 0.5;
            aus[2 * k] = (float) Math.clamp(null0 + mx * 16, von, von + breite);
            aus[2 * k + 1] = (float) Math.clamp(15 - mz * 16, 0, 14);
        }
        return aus;
    }

    /** Die Oberfläche einer Flüssigkeit, in der Höhe, in der das Spiel sie zeichnet. */
    private void sammleFluessigkeit(FluidModel model, BlockState state, FluidState fluid) {
        TextureAtlasSprite sprite = model.stillMaterial().sprite();
        int tint = model.tintSource() == null ? -1 : model.tintSource().colorInWorld(state, welt, pos);
        auftrag.licht().hell(LightCoordsUtil.max(LightCoordsUtil.getLightCoords(welt, pos),
                LightCoordsUtil.getLightCoords(welt, pos.above())), hell);
        float schatten = welt.cardinalLighting().up();
        float[] faktor = new float[3];
        for (int c = 0; c < 3; c++) {
            faktor[c] = hell[c] * schatten * ((tint >> (16 - 8 * c)) & 0xFF) / 255f;
        }
        flaechen.add(new Flaeche(fluid.getHeight(welt, pos), VOLL, voll(sprite), sprite, model.layer(),
                new float[][] {faktor, faktor, faktor, faktor}, null));
    }

    private boolean ohneFlaechen(BlockStateModel model) {
        List<BlockStateModelPart> teile = new ArrayList<>();
        model.collectParts(RandomSource.create(0), teile);
        for (BlockStateModelPart teil : teile) {
            if (!teil.getQuads(null).isEmpty()) {
                return false;
            }
            for (Direction d : Direction.values()) {
                if (!teil.getQuads(d).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Liegt die Fläche eher, als sie steht: ihre Normale höchstens 45° von oben, n_y ≥ cos 45° · |n|? */
    static boolean liegt(BakedQuad q) {
        return liegt(obenAnteil(q));
    }

    static boolean liegt(float obenAnteil) {
        // Etwas Spiel unter cos 45°, so liegt eine Fläche, die genau 45° geneigt ist, trotz der Rundung von float.
        return obenAnteil >= (float) Math.sqrt(0.5) - 1e-6f;
    }

    /** Zeigt die Fläche nach oben? Die Ecken liegen gegen den Uhrzeigersinn, von vorn gesehen. */
    static boolean nachOben(BakedQuad q) {
        return obenAnteil(q) > 1e-4f;
    }

    static float obenAnteil(BakedQuad q) {
        Vector3fc p0 = q.position(0), p1 = q.position(1), p2 = q.position(2);
        return obenAnteil(p1.x() - p0.x(), p1.y() - p0.y(), p1.z() - p0.z(), p2.x() - p0.x(), p2.y() - p0.y(), p2.z() - p0.z());
    }

    /** Wie weit eine Fläche nach oben zeigt: n_y / |n| ihrer Normale a × b, von −1 bis 1, 0 ohne Fläche. */
    static float obenAnteil(float ax, float ay, float az, float bx, float by, float bz) {
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float laenge = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        return laenge == 0 ? 0 : ny / laenge;
    }

    /** Je Ecke Licht, mit dem Leuchten der Fläche, mal Farbe, die Schatten und Tönung trägt. */
    private float[][] ecken(QuadInstance instanz, int leuchten) {
        float[][] ecken = new float[4][3];
        for (int k = 0; k < 4; k++) {
            auftrag.licht().hell(instanz.getLightCoordsWithEmission(k, leuchten), hell);
            int farbe = instanz.getColor(k);
            for (int c = 0; c < 3; c++) {
                ecken[k][c] = hell[c] * ((farbe >> (16 - 8 * c)) & 0xFF) / 255f;
            }
        }
        return ecken;
    }

    /** Der ganze Block in xz, Ecken in der Reihenfolge einer Oberseite. */
    private static final float[] VOLL = {0, 0, 0, 1, 1, 1, 1, 0};

    /** Das ganze Sprite auf {@link #VOLL}, u nach x, v nach z. */
    private static float[] voll(TextureAtlasSprite s) {
        return new float[] {s.getU0(), s.getV0(), s.getU0(), s.getV1(), s.getU1(), s.getV1(), s.getU1(), s.getV0()};
    }

    /**
     * Legt eine Fläche unter das, was die Spalte schon deckt. Ein Pixel gehört zur Fläche,
     * wenn seine Mitte darin liegt; trifft sie in einer Achse keine Mitte, gilt dort die Reihe, in der
     * ihre Mitte liegt, so ist jede Fläche mindestens ein Pixel breit. Die Textur wird über den Pixel
     * in linearem Licht gemittelt, mit dem Test der Schicht. Siehe docs/minimap.md, „Flächen und Pixel“.
     */
    private void schicht(float[] xz, float[] uv, TextureAtlasSprite sprite, ChunkSectionLayer schicht, float[][] ecken) {
        Texel textur = auftrag.texel().get(sprite);
        if (textur == null) {
            return;
        }
        int breite = textur.breite(), hoehe = textur.hoehe();
        float u0 = sprite.getU0(), du = sprite.getU1() - u0, v0 = sprite.getV0(), dv = sprite.getV1() - v0;
        float uMin = Math.min(Math.min(uv[0], uv[2]), Math.min(uv[4], uv[6]));
        float uMax = Math.max(Math.max(uv[0], uv[2]), Math.max(uv[4], uv[6]));
        float vMin = Math.min(Math.min(uv[1], uv[3]), Math.min(uv[5], uv[7]));
        float vMax = Math.max(Math.max(uv[1], uv[3]), Math.max(uv[5], uv[7]));
        // Eine volle Oberseite mit dem ganzen Sprite: das gemittelte Raster, ohne abzutasten.
        float[] voll = ganzeOberseite(xz, uv, sprite) ? raster(sprite, textur, schicht) : null;
        int n = Math.max(1, 16 / scale);
        float[] reiheX = duenn(xz, 0, scale), reiheZ = duenn(xz, 1, scale);
        for (int pz = 0; pz < scale; pz++) {
            for (int px = 0; px < scale; px++) {
                int i = (pz * scale + px) * 4;
                if (summe[i + 3] >= DECKT || reiheX != null && px != (int) reiheX[0] || reiheZ != null && pz != (int) reiheZ[0]) {
                    continue;
                }
                float cx = reiheX != null ? reiheX[1] : (px + 0.5f) / scale, cz = reiheZ != null ? reiheZ[1] : (pz + 0.5f) / scale;
                int[] d = dreieck(xz, cx, cz);
                if (d == null) {
                    continue;
                }
                // u und v affin über das Dreieck der Mitte, auch für Abtastpunkte daneben.
                float ua = uv[2 * d[0]], ub = uv[2 * d[1]], uc = uv[2 * d[2]];
                float va = uv[2 * d[0] + 1], vb = uv[2 * d[1] + 1], vc = uv[2 * d[2] + 1];
                float uMitte = w[0] * ua + w[1] * ub + w[2] * uc, vMitte = w[0] * va + w[1] * vb + w[2] * vc;
                float lr, lg, lb, alpha;
                if (voll != null) {
                    int z = zelle(uMitte, vMitte, sprite);
                    lr = voll[z];
                    lg = voll[z + 1];
                    lb = voll[z + 2];
                    alpha = voll[z + 3];
                } else {
                    float dux = (ub - ua) * ableitung[0] + (uc - ua) * ableitung[2];
                    float duz = (ub - ua) * ableitung[1] + (uc - ua) * ableitung[3];
                    float dvx = (vb - va) * ableitung[0] + (vc - va) * ableitung[2];
                    float dvz = (vb - va) * ableitung[1] + (vc - va) * ableitung[3];
                    mittel.leeren();
                    for (int sz = 0; sz < n; sz++) {
                        float dz = ((sz + 0.5f) / n - 0.5f) / scale;
                        for (int sx = 0; sx < n; sx++) {
                            float dx = ((sx + 0.5f) / n - 0.5f) / scale;
                            float u = Math.clamp(uMitte + dux * dx + duz * dz, uMin, uMax);
                            float v = Math.clamp(vMitte + dvx * dx + dvz * dz, vMin, vMax);
                            int tx = Math.clamp((int) ((u - u0) / du * breite), 0, breite - 1);
                            int ty = Math.clamp((int) ((v - v0) / dv * hoehe), 0, hoehe - 1);
                            mittel.dazu(textur.at(tx, ty), schicht, sx == n / 2 && sz == n / 2);
                        }
                    }
                    alpha = mittel.alpha(schicht);
                    lr = mittel.r / mittel.a;
                    lg = mittel.g / mittel.a;
                    lb = mittel.b / mittel.a;
                }
                if (!(alpha > 0)) {
                    continue;
                }
                float rest = 1 - summe[i + 3];
                for (int c = 0; c < 3; c++) {
                    float linear = c == 0 ? lr : c == 1 ? lg : lb;
                    float faktor = w[0] * ecken[d[0]][c] + w[1] * ecken[d[1]][c] + w[2] * ecken[d[2]][c];
                    summe[i + c] += rest * alpha * Math.min(1, srgb(linear) * faktor);
                }
                summe[i + 3] += rest * alpha;
            }
        }
    }

    /**
     * Ist die Fläche {@code xz} in der Achse {@code achse} (0 x, 1 z) so schmal, dass sie bei
     * {@code scale} Pixeln je Block keine Mitte eines Pixels trifft: {Reihe, wo sie geprüft wird}, die Reihe
     * ihrer Mitte und ihre Mitte; sonst null.
     */
    static float[] duenn(float[] xz, int achse, int scale) {
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        for (int k = 0; k < 4; k++) {
            min = Math.min(min, xz[2 * k + achse]);
            max = Math.max(max, xz[2 * k + achse]);
        }
        // Mitten liegen bei (r + 0,5) / scale; keine im Bereich, wenn die erste darüber hinter der letzten darunter liegt.
        if (Math.ceil(min * scale - 0.5f) <= Math.floor(max * scale - 0.5f)) {
            return null;
        }
        float mitte = (min + max) / 2;
        return new float[] {Math.clamp((int) Math.floor(mitte * scale), 0, scale - 1), mitte};
    }

    /** Sammelt die Texel eines Pixels nach der Regel seiner Schicht. */
    private static final class Mittel {
        float r, g, b, a, mitteA;
        int deckend, proben;

        void leeren() {
            r = g = b = a = mitteA = 0;
            deckend = proben = 0;
        }

        void dazu(int texel, ChunkSectionLayer schicht, boolean mitte) {
            float ta = (texel >>> 24) / 255f;
            if (schicht == ChunkSectionLayer.SOLID) {
                ta = 1;
            } else if (schicht == ChunkSectionLayer.CUTOUT) {
                ta = ta >= 0.5f ? 1 : 0;
            } else if (ta < 0.1f) {
                ta = 0;
            }
            if (ta > 0) {
                r += ta * LINEAR[(texel >> 16) & 0xFF];
                g += ta * LINEAR[(texel >> 8) & 0xFF];
                b += ta * LINEAR[texel & 0xFF];
                a += ta;
                deckend++;
            }
            if (mitte) {
                mitteA = ta;
            }
            proben++;
        }

        /** Laub ist Laub oder Loch: in CUTOUT deckt ganz, was mehr als die Hälfte deckt. */
        float alpha(ChunkSectionLayer schicht) {
            if (schicht == ChunkSectionLayer.CUTOUT) {
                return 2 * deckend > proben || (2 * deckend == proben && mitteA > 0) ? 1 : 0;
            }
            return a / proben;
        }
    }

    private final Mittel mittel = new Mittel();
    /** Je Sprite und Schicht das Raster einer vollen Oberseite beim aktuellen scale. */
    private final Map<TextureAtlasSprite, float[][]> raster = new IdentityHashMap<>();

    /** Deckt die Fläche den Block in x und z ganz, mit je einer Ecke des Sprites an jeder Ecke? */
    private static boolean ganzeOberseite(float[] xz, float[] uv, TextureAtlasSprite s) {
        float eu = 1e-4f * (s.getU1() - s.getU0()), ev = 1e-4f * (s.getV1() - s.getV0());
        for (int k = 0; k < 4; k++) {
            boolean ecke = (Math.abs(xz[2 * k]) < 1e-4f || Math.abs(xz[2 * k] - 1) < 1e-4f)
                    && (Math.abs(xz[2 * k + 1]) < 1e-4f || Math.abs(xz[2 * k + 1] - 1) < 1e-4f)
                    && (Math.abs(uv[2 * k] - s.getU0()) < eu || Math.abs(uv[2 * k] - s.getU1()) < eu)
                    && (Math.abs(uv[2 * k + 1] - s.getV0()) < ev || Math.abs(uv[2 * k + 1] - s.getV1()) < ev);
            if (!ecke) {
                return false;
            }
        }
        // Vier verschiedene Ecken in x und z, keine entartete Fläche.
        float flaeche = (xz[4] - xz[0]) * (xz[7] - xz[1]) - (xz[6] - xz[0]) * (xz[5] - xz[1]);
        return Math.abs(flaeche) > 0.5f;
    }

    /** Die Zelle des Rasters von {@code s} unter u, v, als Index in {@link #raster}. */
    private int zelle(float u, float v, TextureAtlasSprite s) {
        int zx = Math.clamp((int) ((u - s.getU0()) / (s.getU1() - s.getU0()) * scale), 0, scale - 1);
        int zy = Math.clamp((int) ((v - s.getV0()) / (s.getV1() - s.getV0()) * scale), 0, scale - 1);
        return (zy * scale + zx) * 4;
    }

    /** Das Sprite je Zelle von scale × scale gemittelt: linear r, g, b und Alpha. */
    private float[] raster(TextureAtlasSprite sprite, Texel textur, ChunkSectionLayer schicht) {
        float[][] jeSchicht = raster.computeIfAbsent(sprite, k -> new float[ChunkSectionLayer.values().length][]);
        float[] fertig = jeSchicht[schicht.ordinal()];
        if (fertig != null) {
            return fertig;
        }
        int breite = textur.breite(), hoehe = textur.hoehe();
        float[] neu = new float[scale * scale * 4];
        for (int zy = 0; zy < scale; zy++) {
            for (int zx = 0; zx < scale; zx++) {
                int x0 = zx * breite / scale, x1 = Math.max(x0 + 1, (zx + 1) * breite / scale);
                int y0 = zy * hoehe / scale, y1 = Math.max(y0 + 1, (zy + 1) * hoehe / scale);
                int mx = (x0 + x1) / 2, my = (y0 + y1) / 2;
                mittel.leeren();
                for (int ty = y0; ty < y1; ty++) {
                    for (int tx = x0; tx < x1; tx++) {
                        mittel.dazu(textur.at(tx, ty), schicht, tx == mx && ty == my);
                    }
                }
                int z = (zy * scale + zx) * 4;
                neu[z + 3] = mittel.alpha(schicht);
                if (mittel.a > 0) {
                    neu[z] = mittel.r / mittel.a;
                    neu[z + 1] = mittel.g / mittel.a;
                    neu[z + 2] = mittel.b / mittel.a;
                }
            }
        }
        jeSchicht[schicht.ordinal()] = neu;
        return neu;
    }

    private static final int[][] DREIECKE = {{0, 1, 2}, {0, 2, 3}};
    /** Gewichte der Ecken des Dreiecks an der Pixelmitte. */
    private final float[] w = new float[3];
    /** Ableitungen der Gewichte von Ecke 1 und 2 nach x und z. */
    private final float[] ableitung = new float[4];

    /** Das Dreieck der Fläche, in dem (x, z) liegt; füllt {@link #w} und {@link #ableitung}. */
    private int[] dreieck(float[] xz, float x, float z) {
        for (int[] d : DREIECKE) {
            float x0 = xz[2 * d[0]], z0 = xz[2 * d[0] + 1];
            float x1 = xz[2 * d[1]], z1 = xz[2 * d[1] + 1];
            float x2 = xz[2 * d[2]], z2 = xz[2 * d[2] + 1];
            float det = (x1 - x0) * (z2 - z0) - (x2 - x0) * (z1 - z0);
            if (Math.abs(det) < 1e-9f) {
                continue;
            }
            float w1 = ((x - x0) * (z2 - z0) - (x2 - x0) * (z - z0)) / det;
            float w2 = ((x1 - x0) * (z - z0) - (x - x0) * (z1 - z0)) / det;
            float w0 = 1 - w1 - w2;
            if (w0 >= 0 && w1 >= 0 && w2 >= 0) {
                w[0] = w0;
                w[1] = w1;
                w[2] = w2;
                ableitung[0] = (z2 - z0) / det;
                ableitung[1] = -(x2 - x0) / det;
                ableitung[2] = -(z1 - z0) / det;
                ableitung[3] = (x1 - x0) / det;
                return d;
            }
        }
        return null;
    }

    static float srgb(float linear) {
        return linear <= 0.0031308f ? linear * 12.92f : (float) (1.055 * Math.pow(linear, 1 / 2.4) - 0.055);
    }

    /** Schreibt die Spalte über Schwarz; eine leere Spalte bleibt durchsichtig. */
    private void schreibe(int[] pixel, int seite, int x, int y) {
        for (int pz = 0; pz < scale; pz++) {
            for (int px = 0; px < scale; px++) {
                int i = (pz * scale + px) * 4;
                int argb = 0;
                if (summe[i + 3] > 0) {
                    argb = 0xFF000000 | kanal(summe[i]) << 16 | kanal(summe[i + 1]) << 8 | kanal(summe[i + 2]);
                }
                pixel[(y + pz) * seite + x + px] = argb;
            }
        }
    }

    private static int kanal(float c) {
        return Math.clamp(Math.round(c * 255), 0, 255);
    }
}
