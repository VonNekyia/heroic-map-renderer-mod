package com.nekyia.heroicmap;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2fc;

/**
 * Flächen, Kreise und Linien der Ebenen, flach wie die Karten des Mods: Füllungen als Rechtecke aus
 * Blöcken oder als Vieleck des Kreises, Ränder und Linien als Streifen je Strecke, gestrichelt
 * entlang des ganzen Zugs. Jede Füllung und jeder Rand ist ein Element des GUI in einer Farbe,
 * geschnitten mit der Form der Minimap oder dem Schirm. Siehe docs/ebenen.md, „Flächen, Kreise und Linien“.
 */
final class Formen {

    private Formen() {
    }

    /** Wie ein Punkt der Welt in die Einheiten des Elements kommt. */
    interface Abbild {
        void ab(double wx, double wz, double[] aus);
    }

    /**
     * Was eine Ansicht gibt: das Abbild; wie lang ein Block und eine Einheit des GUI in Einheiten des
     * Elements sind, und wie viele Pixel des Schirms eine Einheit des Elements; das konvexe Vieleck,
     * an dem geschnitten wird; das sichtbare Rechteck der Welt {x0, z0, x1, z1}; Pose und Grenzen.
     */
    record Ansicht(Abbild abbild, double block, double einheit, double pixel, float[] schnitt, double[] welt, Matrix3x2fc pose,
            ScreenRectangle bounds) {
    }

    /** Zeichnet die Formen einer Ebene in dieser Dimension: erst alle Füllungen, dann Ränder und Linien. */
    static void zeichne(GuiGraphicsExtractor g, Ansicht a, String dimension, List<Ebenen.Form> formen) {
        for (Ebenen.Form f : formen) {
            if (f.dimension().equals(dimension) && sichtbar(a, f)) {
                fuellung(g, a, f);
            }
        }
        for (Ebenen.Form f : formen) {
            if (f.dimension().equals(dimension) && sichtbar(a, f)) {
                rand(g, a, f);
            }
        }
    }

    /** Liegt die Form, samt Rand, im sichtbaren Rechteck der Welt? */
    static boolean sichtbar(Ansicht a, Ebenen.Form f) {
        double[] b = switch (f) {
            case Ebenen.Flaeche fl -> fl.box();
            case Ebenen.Linie l -> l.box();
            case Ebenen.Kreis k -> new double[] {k.x() - k.radius(), k.z() - k.radius(), k.x() + k.radius(), k.z() + k.radius()};
        };
        double rand = 1 + Ebenen.MAX_BREITE * a.einheit() / a.block();
        return b[2] >= a.welt()[0] - rand && b[0] <= a.welt()[2] + rand && b[3] >= a.welt()[1] - rand && b[1] <= a.welt()[3] + rand;
    }

    private static void fuellung(GuiGraphicsExtractor g, Ansicht a, Ebenen.Form f) {
        Sammler s = new Sammler(a.schnitt());
        double[] p = new double[2];
        float[] ecken = new float[8];
        int farbe = 0;
        if (f instanceof Ebenen.Flaeche fl && fl.fuellung() != 0) {
            farbe = fl.fuellung();
            int[] r = fl.rechtecke();
            double[] w = a.welt();
            for (int i = 0; i < r.length; i += 4) {
                if (r[i + 2] < w[0] || r[i] > w[2] || r[i + 3] < w[1] || r[i + 1] > w[3]) {
                    continue;
                }
                // Dieselbe Reihenfolge wie die Regionen der Karte: links oben, links unten, rechts unten, rechts oben.
                ecke(a, r[i], r[i + 1], p, ecken, 0);
                ecke(a, r[i], r[i + 3], p, ecken, 1);
                ecke(a, r[i + 2], r[i + 3], p, ecken, 2);
                ecke(a, r[i + 2], r[i + 1], p, ecken, 3);
                s.vieleck(ecken, 4);
            }
        } else if (f instanceof Ebenen.Kreis k && k.fuellung() != 0) {
            farbe = k.fuellung();
            float[] kreis = kreis(a, k);
            s.vieleck(kreis, kreis.length / 2);
        }
        s.element(g, a, farbe);
    }

    private static void ecke(Ansicht a, double wx, double wz, double[] p, float[] ecken, int i) {
        a.abbild().ab(wx, wz, p);
        ecken[2 * i] = (float) p[0];
        ecken[2 * i + 1] = (float) p[1];
    }

    private static void rand(GuiGraphicsExtractor g, Ansicht a, Ebenen.Form f) {
        Ebenen.Rand r = switch (f) {
            case Ebenen.Flaeche fl -> fl.rand();
            case Ebenen.Kreis k -> k.rand();
            case Ebenen.Linie l -> l.rand();
        };
        if (r == null) {
            return;
        }
        Sammler s = new Sammler(a.schnitt());
        double b = r.breite() * a.einheit(), strich = r.strich() * a.einheit(), luecke = r.luecke() * a.einheit();
        switch (f) {
            case Ebenen.Flaeche fl -> {
                for (double[] ring : fl.ringe()) {
                    double[] p = abgebildet(a, ring);
                    streifen(s, p, p.length / 2, true, b, strich, luecke);
                }
            }
            case Ebenen.Kreis k -> {
                float[] kreis = kreis(a, k);
                double[] p = new double[kreis.length];
                for (int i = 0; i < kreis.length; i++) {
                    p[i] = kreis[i];
                }
                streifen(s, p, p.length / 2, true, b, strich, luecke);
            }
            case Ebenen.Linie l -> {
                double[] p = abgebildet(a, l.punkte());
                streifen(s, p, p.length / 2, false, b, strich, luecke);
            }
        }
        s.element(g, a, r.farbe());
    }

    private static double[] abgebildet(Ansicht a, double[] welt) {
        double[] aus = new double[welt.length], p = new double[2];
        for (int i = 0; i < welt.length; i += 2) {
            a.abbild().ab(welt[i], welt[i + 1], p);
            aus[i] = p[0];
            aus[i + 1] = p[1];
        }
        return aus;
    }

    /**
     * Der Kreis als Vieleck in Einheiten des Elements: so viele Ecken, dass die Sehne höchstens einen
     * halben Pixel vom Kreis abweicht, mindestens 16, höchstens 4096.
     */
    static float[] kreis(Ansicht a, Ebenen.Kreis k) {
        double[] m = new double[2];
        a.abbild().ab(k.x(), k.z(), m);
        double r = k.radius() * a.block();
        int n = ecken(r * a.pixel());
        float[] p = new float[2 * n];
        for (int i = 0; i < n; i++) {
            double w = 2 * Math.PI * i / n;
            p[2 * i] = (float) (m[0] + r * Math.cos(w));
            p[2 * i + 1] = (float) (m[1] + r * Math.sin(w));
        }
        return p;
    }

    /** Wie viele Ecken ein Kreis mit {@code rPixel} Pixeln Radius braucht, damit die Sehne höchstens einen halben Pixel abweicht. */
    static int ecken(double rPixel) {
        if (!(rPixel > 1)) {
            return 16;
        }
        double n = Math.ceil(Math.PI / Math.acos(1 - 0.5 / rPixel));
        return (int) Math.max(16, Math.min(4096, n));
    }

    /**
     * Legt den Linienzug p aus n Punkten, geschlossen mit {@code zu}, als Streifen der Breite b in den
     * Sammler, je Strecke ein Rechteck. Gestrichelt mit {@code strich} und {@code luecke} (0:
     * durchgezogen); die Striche laufen über die Ecken weiter. Strecken, die ganz neben dem Schnitt
     * liegen, schieben nur das Muster weiter.
     */
    static void streifen(Sammler s, double[] p, int n, boolean zu, double b, double strich, double luecke) {
        double h = b / 2, muster = strich + luecke, pos = 0;
        int strecken = zu ? n : n - 1;
        for (int i = 0; i < strecken; i++) {
            int j = (i + 1) % n;
            double x0 = p[2 * i], y0 = p[2 * i + 1], x1 = p[2 * j], y1 = p[2 * j + 1];
            double laenge = Math.hypot(x1 - x0, y1 - y0);
            if (laenge == 0) {
                continue;
            }
            if (!s.nahe(Math.min(x0, x1) - h, Math.min(y0, y1) - h, Math.max(x0, x1) + h, Math.max(y0, y1) + h)) {
                pos = strich > 0 ? (pos + laenge) % muster : 0;
                continue;
            }
            if (strich <= 0) {
                s.streifen(x0, y0, x1, y1, h);
                continue;
            }
            double t = 0;
            while (t < laenge) {
                boolean an = pos < strich;
                double schritt = Math.min(an ? strich - pos : muster - pos, laenge - t);
                if (an) {
                    s.streifen(x0 + (x1 - x0) * t / laenge, y0 + (y1 - y0) * t / laenge,
                            x0 + (x1 - x0) * (t + schritt) / laenge, y0 + (y1 - y0) * (t + schritt) / laenge, h);
                }
                t += schritt;
                pos = (pos + schritt) % muster;
            }
        }
    }

    /**
     * Sammelt Vielecke in Einheiten des Elements, geschnitten mit dem konvexen Vieleck {@code schnitt},
     * erst mit seinem umschliessenden Rechteck, dann mit ihm. Der Umlaufsinn wird wie bei den Regionen
     * der Karte gelegt, denn das GUI verwirft Rückseiten.
     */
    static final class Sammler {

        private final float[] schnitt, kasten;
        private final double x0, y0, x1, y1;
        float[] ecken = new float[256];
        int[] anzahl = new int[16];
        int n, punkte;
        private float[] a = new float[64], b = new float[64], quad = new float[8];

        Sammler(float[] schnitt) {
            this.schnitt = schnitt;
            double ax = Double.MAX_VALUE, ay = Double.MAX_VALUE, bx = -Double.MAX_VALUE, by = -Double.MAX_VALUE;
            for (int i = 0; i < schnitt.length; i += 2) {
                ax = Math.min(ax, schnitt[i]);
                ay = Math.min(ay, schnitt[i + 1]);
                bx = Math.max(bx, schnitt[i]);
                by = Math.max(by, schnitt[i + 1]);
            }
            x0 = ax;
            y0 = ay;
            x1 = bx;
            y1 = by;
            kasten = Drehung.rechteck(ax, ay, bx, by);
        }

        /** Berührt das Rechteck den Kasten des Schnitts? */
        boolean nahe(double ax, double ay, double bx, double by) {
            return bx >= x0 && ax <= x1 && by >= y0 && ay <= y1;
        }

        /** Ein Rechteck von (xa, ya) nach (xb, yb), h zu jeder Seite. */
        void streifen(double xa, double ya, double xb, double yb, double h) {
            double l = Math.hypot(xb - xa, yb - ya), nx = -(yb - ya) / l * h, ny = (xb - xa) / l * h;
            quad[0] = (float) (xa + nx);
            quad[1] = (float) (ya + ny);
            quad[2] = (float) (xa - nx);
            quad[3] = (float) (ya - ny);
            quad[4] = (float) (xb - nx);
            quad[5] = (float) (yb - ny);
            quad[6] = (float) (xb + nx);
            quad[7] = (float) (yb + ny);
            vieleck(quad, 4);
        }

        void vieleck(float[] p, int k) {
            int platz = 2 * (k + schnitt.length / 2 + 8);
            if (a.length < platz) {
                a = new float[platz];
                b = new float[platz];
            }
            int m = Drehung.schneide(p, k, kasten, 4, a, b);
            if (m < 3) {
                return;
            }
            if (schnitt.length != 8 || !Arrays.equals(schnitt, kasten)) {
                float[] zwischen = Arrays.copyOf(a, 2 * m);
                m = Drehung.schneide(zwischen, m, schnitt, schnitt.length / 2, a, b);
                if (m < 3) {
                    return;
                }
            }
            if (punkte + m > ecken.length / 2) {
                ecken = Arrays.copyOf(ecken, Math.max(2 * ecken.length, 2 * (punkte + m)));
            }
            if (n == anzahl.length) {
                anzahl = Arrays.copyOf(anzahl, 2 * n);
            }
            // Negativer Umlaufsinn wie links oben, links unten, rechts unten, rechts oben bei y nach unten.
            double flaeche = 0;
            for (int i = 0; i < m; i++) {
                int j = (i + 1) % m;
                flaeche += (double) a[2 * i] * a[2 * j + 1] - (double) a[2 * j] * a[2 * i + 1];
            }
            for (int i = 0; i < m; i++) {
                int q = flaeche > 0 ? m - 1 - i : i;
                ecken[2 * (punkte + i)] = a[2 * q];
                ecken[2 * (punkte + i) + 1] = a[2 * q + 1];
            }
            anzahl[n++] = m;
            punkte += m;
        }

        /** Hängt die gesammelten Vielecke als ein Element des GUI an; ohne Vielecke nichts. */
        void element(GuiGraphicsExtractor g, Ansicht a, int farbe) {
            if (n > 0) {
                g.guiRenderState.addGuiElement(new Vielecke(a.pose(), farbe, ecken, anzahl, n, a.bounds()));
            }
        }
    }

    /** Vielecke in einer Farbe, die Ecken schon in Einheiten der Pose, als Fächer. */
    record Vielecke(Matrix3x2fc pose, int farbe, float[] ecken, int[] anzahl, int n, ScreenRectangle bounds) implements GuiElementRenderState {

        @Override
        public void buildVertices(VertexConsumer v) {
            int start = 0;
            for (int i = 0; i < n; i++) {
                int o = start;
                Drehung.faecher(anzahl[i], j -> v.addVertexWith2DPose(pose, ecken[2 * (o + j)], ecken[2 * (o + j) + 1]).setColor(farbe));
                start += anzahl[i];
            }
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }

        @Override
        public ScreenRectangle scissorArea() {
            return null;
        }
    }
}
