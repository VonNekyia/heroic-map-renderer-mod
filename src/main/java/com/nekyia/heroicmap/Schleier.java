package com.nekyia.heroicmap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntBinaryOperator;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Der Schleier am Rand angehefteter Regionen und Kreise in der Welt: je Stück der Kante, das in einem
 * Block liegt, ein Viereck vom Gelände {@link #HOEHE} Blöcke hoch, unten {@link #DECKUNG} deckend, oben
 * durchsichtig, in der Farbe der Form. Gebaut nur bei einer Änderung; je Frame reicht der Mod die
 * fertigen Ecken weiter. Nur der Render-Thread. Siehe docs/wegpunkte.md, „Schleier“.
 */
public final class Schleier {

    public static final Schleier INSTANZ = new Schleier();
    private static final Logger LOGGER = LoggerFactory.getLogger(HeroicMap.ID);
    /** So hoch steht der Schleier über dem Gelände, in Blöcken; oben ist er ganz durchsichtig. */
    static final float HOEHE = 4;
    /** So deckend ist er unten am Gelände, als Anteil vom Alpha seiner Farbe. */
    static final float DECKUNG = 0.5f;
    /** So viele Vierecke höchstens; darüber die nächsten zuerst. Siehe docs/wegpunkte.md, „Schleier“. */
    static final int MAX_VIERECKE = 5_000;
    /** So weit, in Blöcken, geht der Spieler, bis der Mod neu baut; so weit reicht der Schleier über die Sichtweite hinaus. */
    static final int NEU_AB = 16;
    /** So oft baut der Mod höchstens neu, in ms; nur der erste Bau und eine andere Dimension warten nicht. */
    static final long NEU_FRUEHESTENS_MS = 500;

    /** Ein Stück einer Kante von (x0, z0) bis (x1, z1). */
    interface Stueck {
        void stueck(double x0, double z0, double x1, double z1);
    }

    /** Je Viereck die Ecken unten {x0, y0, z0, x1, y1, z1} relativ zum Ursprung, und seine Farben unten und oben. */
    private float[] ecken = new float[0];
    private int[] unten = new int[0], oben = new int[0];
    private int vierecke;
    private int ursprungX, ursprungZ;
    /** Woraus gebaut ist; ändert sich etwas davon, baut der Mod neu. */
    private boolean gebaut, schmutzig, gewarnt;
    private Ebenen gebautAus;
    private int ebenenStand, wegpunkteStand, weite;
    private ResourceKey<Level> dimension;
    private long gebautUm;
    /** Die Chunks unter den Stücken, geladen oder nicht; ändert sich einer, baut der Mod neu. */
    private final LongOpenHashSet chunks = new LongOpenHashSet();
    /** Einmal angelegt, so legt der Frame kein Objekt an. */
    private final SubmitNodeCollector.CustomGeometryRenderer zeichner = this::schreibe;

    /** Aus {@code LevelRenderEvents.COLLECT_SUBMITS}: baut, wenn nötig, und reicht die Ecken weiter. */
    void zeichne(LevelRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || !Minimap.INSTANZ.effekte()) {
            return;
        }
        int weit = mc.options.getEffectiveRenderDistance() * 16, bx = mc.player.getBlockX(), bz = mc.player.getBlockZ();
        long jetzt = Util.getMillis();
        // Ein Bau liest die Höhen aus der Welt und kostet bis zu einigen ms auf dem Render-Thread; darum höchstens alle 500 ms.
        boolean noetig = gebautAus != Ebenen.INSTANZ || ebenenStand != Ebenen.INSTANZ.stand() || wegpunkteStand != Wegpunkte.INSTANZ.stand()
                || weite != weit || Math.abs(bx - ursprungX) > NEU_AB || Math.abs(bz - ursprungZ) > NEU_AB || schmutzig;
        if (!gebaut || dimension != level.dimension() || noetig && jetzt - gebautUm >= NEU_FRUEHESTENS_MS) {
            baue(level, bx, bz, weit, jetzt);
        }
        if (vierecke == 0) {
            return;
        }
        Vec3 kamera = ctx.levelState().cameraRenderState.pos;
        PoseStack pose = ctx.poseStack();
        pose.pushPose();
        pose.translate(ursprungX - kamera.x, -kamera.y, ursprungZ - kamera.z);
        ctx.submitNodeCollector().submitCustomGeometry(pose, RenderTypes.debugQuads(), zeichner);
        pose.popPose();
    }

    /** Je Viereck vier Ecken: unten in der Farbe unten, oben {@link #HOEHE} höher, durchsichtig. */
    private void schreibe(PoseStack.Pose pose, VertexConsumer c) {
        for (int i = 0; i < vierecke; i++) {
            int o = 6 * i;
            c.addVertex(pose, ecken[o], ecken[o + 1], ecken[o + 2]).setColor(unten[i]);
            c.addVertex(pose, ecken[o + 3], ecken[o + 4], ecken[o + 5]).setColor(unten[i]);
            c.addVertex(pose, ecken[o + 3], ecken[o + 4] + HOEHE, ecken[o + 5]).setColor(oben[i]);
            c.addVertex(pose, ecken[o], ecken[o + 1] + HOEHE, ecken[o + 2]).setColor(oben[i]);
        }
    }

    /** Wenn ein Chunk unter dem Schleier sich ändert, aus dem Haken {@code setSectionDirty}. */
    public void markiere(int cx, int cz) {
        if (chunks.contains(ChunkPos.pack(cx, cz))) {
            schmutzig = true;
        }
    }

    /** Wie viele Vierecke gebaut sind; für den Gametest. */
    int vierecke() {
        return vierecke;
    }

    /**
     * Baut den Schleier um den Block (bx, bz) bis zur Sichtweite {@code weit} und {@link #NEU_AB} dazu:
     * die Kanten der angehefteten Formen dieser Dimension in Stücken je Block, die nächsten
     * {@link #MAX_VIERECKE}, unten auf dem Gelände. Stücke über Chunks, die nicht geladen sind, fehlen.
     */
    void baue(ClientLevel level, int bx, int bz, int weit, long jetzt) {
        gebaut = true;
        gebautAus = Ebenen.INSTANZ;
        ebenenStand = Ebenen.INSTANZ.stand();
        wegpunkteStand = Wegpunkte.INSTANZ.stand();
        dimension = level.dimension();
        weite = weit;
        ursprungX = bx;
        ursprungZ = bz;
        gebautUm = jetzt;
        schmutzig = false;
        chunks.clear();
        String name = dimension.identifier().toString();
        double r = weit + NEU_AB, mx = bx + 0.5, mz = bz + 0.5;
        double[] kasten = {bx - r, bz - r, bx + 1 + r, bz + 1 + r};
        Sammlung s = new Sammlung();
        for (List<Ebenen.Form> formen : Wegpunkte.INSTANZ.minimap(Ebenen.INSTANZ)) {
            for (Ebenen.Form f : formen) {
                int farbe = farbe(f);
                if (f.dimension().equals(name) && Ebenen.sichtbar(farbe)) {
                    kanten(f, kasten, (x0, z0, x1, z1) -> {
                        double dx = (x0 + x1) / 2 - mx, dz = (z0 + z1) / 2 - mz;
                        if (dx * dx + dz * dz <= r * r) {
                            s.add(x0, z0, x1, z1, dx * dx + dz * dz, farbe);
                        }
                    });
                }
            }
        }
        int[] reihe = naechste(s.abstand, s.n, MAX_VIERECKE);
        if (s.n > MAX_VIERECKE && !gewarnt) {
            LOGGER.warn("Heroic Map: Schleier mit {} Vierecken, nur die nächsten {}", s.n, MAX_VIERECKE);
        }
        gewarnt = s.n > MAX_VIERECKE;
        ecken = new float[6 * reihe.length];
        unten = new int[reihe.length];
        oben = new int[reihe.length];
        IntBinaryOperator gelaende = (x, z) -> level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int n = 0;
        for (int k : reihe) {
            double x0 = s.punkte[4 * k], z0 = s.punkte[4 * k + 1], x1 = s.punkte[4 * k + 2], z1 = s.punkte[4 * k + 3];
            // Ein Stück liegt in einem Block; dessen Chunk merkt sich der Mod, geladen oder nicht.
            int cx = Math.floorDiv((int) Math.floor((x0 + x1) / 2), 16), cz = Math.floorDiv((int) Math.floor((z0 + z1) / 2), 16);
            chunks.add(ChunkPos.pack(cx, cz));
            if (!level.getChunkSource().hasChunk(cx, cz)) {
                continue;
            }
            int o = 6 * n;
            ecken[o] = (float) (x0 - bx);
            ecken[o + 1] = hoehe(gelaende, x0, z0);
            ecken[o + 2] = (float) (z0 - bz);
            ecken[o + 3] = (float) (x1 - bx);
            ecken[o + 4] = hoehe(gelaende, x1, z1);
            ecken[o + 5] = (float) (z1 - bz);
            int farbe = s.farben[k];
            unten[n] = Math.round(DECKUNG * (farbe >>> 24)) << 24 | farbe & 0x00FFFFFF;
            oben[n] = farbe & 0x00FFFFFF;
            n++;
        }
        vierecke = n;
    }

    /** Die Farbe des Schleiers einer Form: ihr Rand, ohne sichtbaren Rand ihre Füllung ohne Alpha; sonst 0. */
    static int farbe(Ebenen.Form f) {
        Ebenen.Rand rand = switch (f) {
            case Ebenen.Flaeche fl -> fl.rand();
            case Ebenen.Kreis k -> k.rand();
            default -> null;
        };
        int fuellung = switch (f) {
            case Ebenen.Flaeche fl -> fl.fuellung();
            case Ebenen.Kreis k -> k.fuellung();
            default -> 0;
        };
        return rand != null && Ebenen.sichtbar(rand.farbe()) ? rand.farbe() : Ebenen.sichtbar(fuellung) ? 0xFF000000 | fuellung : 0;
    }

    /**
     * Die Kanten einer Fläche oder eines Kreises im Kasten {x0, z0, x1, z1}, in Stücken, die je in einem
     * Block liegen. Ein Kreis wird dafür ein Vieleck mit Sehnen von höchstens einem Block, nur über den
     * Bogen, der den Kasten treffen kann.
     */
    static void kanten(Ebenen.Form f, double[] kasten, Stueck aus) {
        double[] t = new double[2];
        switch (f) {
            case Ebenen.Flaeche fl -> {
                for (double[] ring : fl.ringe()) {
                    int n = ring.length / 2;
                    for (int i = 0; i < n; i++) {
                        int j = (i + 1) % n;
                        if (Formen.imKasten(ring, i, j, kasten, t)) {
                            double dx = ring[2 * j] - ring[2 * i], dz = ring[2 * j + 1] - ring[2 * i + 1];
                            teile(ring[2 * i] + dx * t[0], ring[2 * i + 1] + dz * t[0], ring[2 * i] + dx * t[1], ring[2 * i + 1] + dz * t[1], aus);
                        }
                    }
                }
            }
            case Ebenen.Kreis k -> {
                double[] bogen = bogen(k.x(), k.z(), k.radius(), kasten);
                if (bogen == null) {
                    return;
                }
                int n = Math.max(1, (int) Math.ceil((bogen[1] - bogen[0]) * k.radius()));
                double[] p = new double[4];
                for (int i = 0; i < n; i++) {
                    double w0 = bogen[0] + (bogen[1] - bogen[0]) * i / n, w1 = bogen[0] + (bogen[1] - bogen[0]) * (i + 1) / n;
                    p[0] = k.x() + k.radius() * Math.cos(w0);
                    p[1] = k.z() + k.radius() * Math.sin(w0);
                    p[2] = k.x() + k.radius() * Math.cos(w1);
                    p[3] = k.z() + k.radius() * Math.sin(w1);
                    if (Formen.imKasten(p, 0, 1, kasten, t)) {
                        double dx = p[2] - p[0], dz = p[3] - p[1];
                        teile(p[0] + dx * t[0], p[1] + dz * t[0], p[0] + dx * t[1], p[1] + dz * t[1], aus);
                    }
                }
            }
            default -> {
            }
        }
    }

    /**
     * Der Bogen {von, bis} in Bogenmass des Kreises um (x, z) mit Radius r, dessen Punkte im Umkreis des
     * Kastens liegen können; der ganze Kreis, wenn der Umkreis seine Mitte enthält; null, wenn keiner.
     */
    static double[] bogen(double x, double z, double r, double[] kasten) {
        double kx = (kasten[0] + kasten[2]) / 2, kz = (kasten[1] + kasten[3]) / 2;
        double u = Math.hypot(kasten[2] - kasten[0], kasten[3] - kasten[1]) / 2, d = Math.hypot(kx - x, kz - z);
        if (d + u <= r - 1 && d <= r || d - u >= r + 1) {
            // Der Umkreis liegt ganz im Kreis oder ganz daneben: keine Kante im Kasten.
            return null;
        }
        double c = d == 0 ? -2 : (d * d + r * r - u * u) / (2 * d * r);
        if (c <= -1) {
            return new double[] {0, 2 * Math.PI};
        }
        double mitte = Math.atan2(kz - z, kx - x), halb = Math.acos(Math.min(1, c));
        return new double[] {mitte - halb, mitte + halb};
    }

    /** Die Strecke von a nach b in Stücken, die je in einem Block liegen: geteilt, wo sie eine ganze Zahl in x oder z kreuzt. */
    static void teile(double ax, double az, double bx, double bz, Stueck aus) {
        double dx = bx - ax, dz = bz - az;
        double tx = dx == 0 ? Double.POSITIVE_INFINITY : ((dx > 0 ? Math.floor(ax) + 1 : Math.ceil(ax) - 1) - ax) / dx;
        double tz = dz == 0 ? Double.POSITIVE_INFINITY : ((dz > 0 ? Math.floor(az) + 1 : Math.ceil(az) - 1) - az) / dz;
        double sx = dx == 0 ? 0 : 1 / Math.abs(dx), sz = dz == 0 ? 0 : 1 / Math.abs(dz);
        double t = 0, px = ax, pz = az;
        while (t < 1) {
            double tn = Math.min(1, Math.min(tx, tz));
            double nx = tn == 1 ? bx : ax + dx * tn, nz = tn == 1 ? bz : az + dz * tn;
            if (nx != px || nz != pz) {
                aus.stueck(px, pz, nx, nz);
            }
            if (tx <= tn) {
                tx += sx;
            }
            if (tz <= tn) {
                tz += sz;
            }
            t = tn;
            px = nx;
            pz = nz;
        }
    }

    /** Die Höhe des Geländes an (x, z): die grösste der Blöcke, die der Punkt berührt, so taucht der Schleier an keiner Stufe ein. */
    static float hoehe(IntBinaryOperator gelaende, double x, double z) {
        int x0 = (int) Math.floor(x - 1e-6), x1 = (int) Math.floor(x + 1e-6), z0 = (int) Math.floor(z - 1e-6), z1 = (int) Math.floor(z + 1e-6);
        int h = gelaende.applyAsInt(x0, z0);
        if (x1 != x0) {
            h = Math.max(h, gelaende.applyAsInt(x1, z0));
        }
        if (z1 != z0) {
            h = Math.max(h, gelaende.applyAsInt(x0, z1));
            if (x1 != x0) {
                h = Math.max(h, gelaende.applyAsInt(x1, z1));
            }
        }
        return h;
    }

    /** Die Indizes der höchstens {@code max} kleinsten Abstände, die nächsten zuerst. */
    static int[] naechste(double[] abstand, int n, int max) {
        long[] schluessel = new long[n];
        for (int i = 0; i < n; i++) {
            // Positive Floats sortieren als Bits wie als Zahlen.
            schluessel[i] = (long) Float.floatToIntBits((float) abstand[i]) << 32 | i;
        }
        Arrays.sort(schluessel);
        int[] aus = new int[Math.min(n, max)];
        for (int i = 0; i < aus.length; i++) {
            aus[i] = (int) schluessel[i];
        }
        return aus;
    }

    /** Die gesammelten Stücke eines Baus, wachsend. */
    private static final class Sammlung {

        double[] punkte = new double[4 * 1024], abstand = new double[1024];
        int[] farben = new int[1024];
        int n;

        void add(double x0, double z0, double x1, double z1, double d, int farbe) {
            if (n == abstand.length) {
                punkte = Arrays.copyOf(punkte, 8 * n);
                abstand = Arrays.copyOf(abstand, 2 * n);
                farben = Arrays.copyOf(farben, 2 * n);
            }
            punkte[4 * n] = x0;
            punkte[4 * n + 1] = z0;
            punkte[4 * n + 2] = x1;
            punkte[4 * n + 3] = z1;
            abstand[n] = d;
            farben[n] = farbe;
            n++;
        }
    }
}
