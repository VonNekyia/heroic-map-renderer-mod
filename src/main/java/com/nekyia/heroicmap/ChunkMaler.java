package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.QuadInstance;
import it.unimi.dsi.fastutil.objects.Reference2BooleanOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import org.joml.Vector3fc;

/**
 * Zeichnet einen Chunk von oben: je Spalte die Flächen nach oben, die der Tesselator des
 * Spiels liefert, mit Tönung, weicher Beleuchtung und Licht. {@link #abziehen} läuft auf
 * dem Render-Thread, {@link #male} im Worker und liest nur, was der Abzug kopiert hat.
 * Siehe docs/minimap.md.
 */
final class ChunkMaler {

    /** Ab hier deckt ein Pixel. */
    private static final float DECKT = 0.996f;
    /** Spalte ohne Inhalt, etwa eine Wand unter einer Decke. */
    private static final int LEER = Integer.MIN_VALUE;
    private static final float[] LINEAR = new float[256];

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
     * {@code minSektion} an als Kopien und die Texel aller Sprites des Block-Atlas.
     */
    record Auftrag(int cx, int cz, int scale, Licht licht, Map<TextureAtlasSprite, Texel> texel,
            int[] start, int minSektion, RenderSectionRegion[] abschnitte, long stand) {
    }

    /**
     * Zieht den Chunk auf dem Render-Thread ab. Je Spalte reicht er von der Höhenkarte, oder
     * von {@code decke}, bis zum ersten vollen Block darunter.
     */
    static Auftrag abziehen(ClientLevel level, LevelChunk chunk, int scale, int decke, Licht licht,
            Map<TextureAtlasSprite, Texel> texel, long stand) {
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
            return new Auftrag(cx, cz, scale, licht, texel, start, 0, new RenderSectionRegion[0], stand);
        }
        int minSektion = SectionPos.blockToSectionCoord(unten), maxSektion = SectionPos.blockToSectionCoord(oben);
        RenderRegionCache kopien = new RenderRegionCache();
        RenderSectionRegion[] abschnitte = new RenderSectionRegion[maxSektion - minSektion + 1];
        for (int sy = minSektion; sy <= maxSektion; sy++) {
            abschnitte[sy - minSektion] = kopien.createRegion(level, SectionPos.asLong(cx, sy, cz));
        }
        return new Auftrag(cx, cz, scale, licht, texel, start, minSektion, abschnitte, stand);
    }

    private final Minecraft minecraft = Minecraft.getInstance();
    private final ModelBlockRenderer tesselator = new ModelBlockRenderer(true, true, minecraft.getBlockColors());
    private final Reference2BooleanOpenHashMap<BlockState> ohneGeometrie = new Reference2BooleanOpenHashMap<>();
    private final List<Flaeche> flaechen = new ArrayList<>();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final float[] hell = new float[3];

    private Auftrag auftrag;
    private int scale;
    /** Der Abschnitt mit seinen Nachbarn, in dem der aktuelle Block liegt. */
    private RenderSectionRegion welt;
    /** Je Pixel der Spalte vormultipliziert in sRGB: r, g, b, a. */
    private float[] summe = new float[0];

    /** Eine Fläche nach oben mit dem Faktor ihrer Ecken aus Licht, Schatten und Tönung. */
    private record Flaeche(BakedQuad quad, float hoehe, float[][] ecken) {
    }

    /** Zeichnet den Chunk; je Pixel ARGB, Zeile für Zeile, 16 · scale breit. */
    int[] male(Auftrag a) {
        if (a.scale() != scale || auftrag == null || a.texel() != auftrag.texel()) {
            raster.clear();
        }
        auftrag = a;
        if (scale != a.scale()) {
            scale = a.scale();
            summe = new float[scale * scale * 4];
        }
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
                welt = a.abschnitte()[abschnitt];
                pos.set(bx + lx, y, bz + lz);
                BlockState state = welt.getBlockState(pos);
                if (state.isAir()) {
                    continue;
                }
                FluidState fluid = state.getFluidState();
                if (!fluid.isEmpty() && !oberflaeche) {
                    oberflaeche = true;
                    maleFluessigkeit(modelle.getFluidStateModelSet().get(fluid), state);
                }
                if (state.getRenderShape() == RenderShape.MODEL) {
                    maleBlock(modelle.getBlockStateModelSet().get(state), state);
                }
            }
            schreibe(pixel, seite, lx * scale, lz * scale);
        }
        welt = null;
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

    private void maleBlock(BlockStateModel model, BlockState state) {
        if (ohneGeometrie.computeIfAbsent(state, s -> ohneFlaechen(model))) {
            // Blockentities wie Truhen: das Partikel-Sprite über den ganzen Block.
            TextureAtlasSprite sprite = model.particleMaterial().sprite();
            auftrag.licht().hell(LightCoordsUtil.getLightCoords(welt, pos.above()), hell);
            float[][] ecken = {hell.clone(), hell.clone(), hell.clone(), hell.clone()};
            schicht(VOLL, voll(sprite), sprite, ChunkSectionLayer.SOLID, ecken);
            return;
        }
        flaechen.clear();
        tesselator.tesselateBlock((x, y, z, quad, instanz) -> {
            if (nachOben(quad)) {
                flaechen.add(new Flaeche(quad, hoehe(quad), ecken(instanz)));
            }
        }, 0, 0, 0, welt, pos, state, model, state.getSeed(pos));
        flaechen.sort((a, b) -> Float.compare(b.hoehe(), a.hoehe()));
        for (Flaeche f : flaechen) {
            BakedQuad q = f.quad();
            float[] xz = new float[8], uv = new float[8];
            for (int k = 0; k < 4; k++) {
                Vector3fc p = q.position(k);
                xz[2 * k] = p.x();
                xz[2 * k + 1] = p.z();
                long packed = q.packedUV(k);
                uv[2 * k] = UVPair.unpackU(packed);
                uv[2 * k + 1] = UVPair.unpackV(packed);
            }
            schicht(xz, uv, q.materialInfo().sprite(), q.materialInfo().layer(), f.ecken());
        }
    }

    private void maleFluessigkeit(FluidModel model, BlockState state) {
        TextureAtlasSprite sprite = model.stillMaterial().sprite();
        int tint = model.tintSource() == null ? -1 : model.tintSource().colorInWorld(state, welt, pos);
        auftrag.licht().hell(LightCoordsUtil.max(LightCoordsUtil.getLightCoords(welt, pos),
                LightCoordsUtil.getLightCoords(welt, pos.above())), hell);
        float schatten = welt.cardinalLighting().up();
        float[] faktor = new float[3];
        for (int c = 0; c < 3; c++) {
            faktor[c] = hell[c] * schatten * ((tint >> (16 - 8 * c)) & 0xFF) / 255f;
        }
        schicht(VOLL, voll(sprite), sprite, model.layer(), new float[][] {faktor, faktor, faktor, faktor});
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

    /** Zeigt die Fläche nach oben? Die Ecken liegen gegen den Uhrzeigersinn, von vorn gesehen. */
    static boolean nachOben(BakedQuad q) {
        Vector3fc p0 = q.position(0), p1 = q.position(1), p2 = q.position(2);
        float ax = p1.x() - p0.x(), ay = p1.y() - p0.y(), az = p1.z() - p0.z();
        float bx = p2.x() - p0.x(), by = p2.y() - p0.y(), bz = p2.z() - p0.z();
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        return ny > 1e-4f * (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
    }

    private static float hoehe(BakedQuad q) {
        float max = Float.NEGATIVE_INFINITY;
        for (int k = 0; k < 4; k++) {
            max = Math.max(max, q.position(k).y());
        }
        return max;
    }

    /** Je Ecke Licht mal Farbe, die Schatten und Tönung trägt. */
    private float[][] ecken(QuadInstance instanz) {
        float[][] ecken = new float[4][3];
        for (int k = 0; k < 4; k++) {
            auftrag.licht().hell(instanz.getLightCoords(k), hell);
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
     * wenn seine Mitte darin liegt; die Textur wird über den Pixel in linearem Licht
     * gemittelt, mit dem Test der Schicht. Siehe docs/minimap.md, „Flächen und Pixel“.
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
        for (int pz = 0; pz < scale; pz++) {
            for (int px = 0; px < scale; px++) {
                int i = (pz * scale + px) * 4;
                if (summe[i + 3] >= DECKT) {
                    continue;
                }
                float cx = (px + 0.5f) / scale, cz = (pz + 0.5f) / scale;
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
                    int zx = Math.clamp((int) ((uMitte - u0) / du * scale), 0, scale - 1);
                    int zy = Math.clamp((int) ((vMitte - v0) / dv * scale), 0, scale - 1);
                    int z = (zy * scale + zx) * 4;
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

    private static float srgb(float linear) {
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
