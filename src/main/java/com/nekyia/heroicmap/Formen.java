package com.nekyia.heroicmap;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2fc;

/**
 * Flächen, Kreise und Linien der Ebenen, flach wie die Karten des Mods: Füllungen als Trapeze aus
 * {@link Trapeze} oder als Vieleck des Kreises, Ränder und Linien als Züge mit Gehrung, gestrichelt
 * entlang des ganzen Zugs. Jede Füllung und jeder Rand ist ein Element des GUI in einer Farbe,
 * geschnitten mit der Form der Minimap oder dem Schirm. Siehe docs/ebenen.md, „Flächen, Kreise und Linien“.
 */
final class Formen {

    /** Die Spitze einer Gehrung reicht höchstens so viele halbe Breiten weit, also bis zu Ecken von 60°; spitzer wird es eine Fase. */
    static final double GEHRUNG = 2;

    private Formen() {
    }

    /** Wie ein Punkt der Welt in die Einheiten des Elements kommt; affin, etwa verschoben, skaliert und gedreht. */
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

    /** Was eine Ansicht zuletzt gezeichnet hat: Bleiben Ansicht und Formen gleich, hängt sie nur die fertigen Elemente wieder an. */
    static final class Speicher {

        private double[] schluessel = new double[0];
        private String dimension;
        private List<List<Ebenen.Form>> formen = List.of();
        private List<Vielecke> elemente = List.of();
    }

    /**
     * Zeichnet die Formen der Ebenen in ihrer Reihenfolge, in dieser Dimension: je Ebene erst alle
     * Füllungen, dann Ränder und Linien. Neu gerechnet nur, wenn sich Ansicht, Dimension oder eine Ebene ändert.
     */
    static void zeichne(GuiGraphicsExtractor g, Ansicht a, String dimension, List<List<Ebenen.Form>> ebenen, Speicher sp) {
        double[] schluessel = schluessel(a);
        if (!Arrays.equals(schluessel, sp.schluessel) || !dimension.equals(sp.dimension) || !gleich(ebenen, sp.formen)) {
            List<Vielecke> neu = new ArrayList<>();
            for (List<Ebenen.Form> formen : ebenen) {
                baue(a, dimension, formen, neu);
            }
            sp.schluessel = schluessel;
            sp.dimension = dimension;
            sp.formen = ebenen;
            sp.elemente = neu;
        }
        for (Vielecke v : sp.elemente) {
            g.guiRenderState.addGuiElement(v);
        }
    }

    /** Alles, was das Bild einer Ansicht bestimmt; das Abbild ist affin, drei Punkte legen es fest. */
    static double[] schluessel(Ansicht a) {
        double[] p = new double[2];
        float[] s = a.schnitt();
        Matrix3x2fc m = a.pose();
        double[] k = new double[24 + s.length];
        for (int i = 0; i < 3; i++) {
            a.abbild().ab(i == 1 ? 1 : 0, i == 2 ? 1 : 0, p);
            k[2 * i] = p[0];
            k[2 * i + 1] = p[1];
        }
        double[] rest = {a.block(), a.einheit(), a.pixel(), a.welt()[0], a.welt()[1], a.welt()[2], a.welt()[3],
            m.m00(), m.m01(), m.m10(), m.m11(), m.m20(), m.m21(), a.bounds().left(), a.bounds().top(), a.bounds().width(),
            a.bounds().height(), s.length};
        System.arraycopy(rest, 0, k, 6, rest.length);
        for (int i = 0; i < s.length; i++) {
            k[24 + i] = s[i];
        }
        return k;
    }

    /** Dieselben Ebenen, jede dieselbe Liste? Eine geänderte Ebene ist eine neue Liste. */
    private static boolean gleich(List<List<Ebenen.Form>> a, List<List<Ebenen.Form>> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
    }

    /** Die Elemente einer Ebene: erst alle Füllungen, dann Ränder und Linien; ein Kreis wird dafür einmal gerechnet. */
    static void baue(Ansicht a, String dimension, List<Ebenen.Form> formen, List<Vielecke> aus) {
        float[][] kreise = new float[formen.size()][];
        for (int i = 0; i < formen.size(); i++) {
            Ebenen.Form f = formen.get(i);
            if (f.dimension().equals(dimension) && sichtbar(a, f)) {
                if (f instanceof Ebenen.Kreis k) {
                    kreise[i] = kreis(a, k);
                }
                fuellung(a, f, kreise[i], aus);
            }
        }
        for (int i = 0; i < formen.size(); i++) {
            Ebenen.Form f = formen.get(i);
            if (f.dimension().equals(dimension) && sichtbar(a, f)) {
                rand(a, f, kreise[i], aus);
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
        double rand = 1 + GEHRUNG * Ebenen.MAX_BREITE * a.einheit() / a.block();
        return b[2] >= a.welt()[0] - rand && b[0] <= a.welt()[2] + rand && b[3] >= a.welt()[1] - rand && b[1] <= a.welt()[3] + rand;
    }

    private static void fuellung(Ansicht a, Ebenen.Form f, float[] kreis, List<Vielecke> aus) {
        if (f instanceof Ebenen.Flaeche fl && fl.trapeze() != null) {
            Sammler s = new Sammler(a.schnitt());
            double[] t = fl.trapeze(), w = a.welt(), q = new double[8], k1 = new double[16], k2 = new double[16], p = new double[2];
            // Ein Block mehr: Das Rechteck der Welt ist schon das sichtbare, den Rest schneidet die Form.
            double[] welt = {w[0] - 1, w[1] - 1, w[2] + 1, w[3] + 1};
            float[] e = new float[16];
            for (int i = 0; i < t.length; i += 6) {
                if (t[i + 3] < welt[1] || t[i] > welt[3] || Math.max(t[i + 2], t[i + 5]) < welt[0] || Math.min(t[i + 1], t[i + 4]) > welt[2]) {
                    continue;
                }
                q[0] = t[i + 1];
                q[1] = t[i];
                q[2] = t[i + 2];
                q[3] = t[i];
                q[4] = t[i + 5];
                q[5] = t[i + 3];
                q[6] = t[i + 4];
                q[7] = t[i + 3];
                // In Doubles auf das Rechteck der Welt: Ein Trapez über die ganze Welt hätte als Float am Rand Fehler von vielen Pixeln.
                int m = kappe(q, 4, welt, k1, k2);
                for (int j = 0; j < m; j++) {
                    a.abbild().ab(k1[2 * j], k1[2 * j + 1], p);
                    e[2 * j] = (float) p[0];
                    e[2 * j + 1] = (float) p[1];
                }
                if (m >= 3) {
                    s.vieleck(e, m);
                }
            }
            s.element(aus, a, fl.fuellung());
        } else if (f instanceof Ebenen.Kreis k && Ebenen.sichtbar(k.fuellung())) {
            Sammler s = new Sammler(a.schnitt());
            s.vieleck(kreis, kreis.length / 2);
            s.element(aus, a, k.fuellung());
        }
    }

    /**
     * Schneidet das Vieleck p aus n Ecken {x, z, …} mit dem Rechteck w {x0, z0, x1, z1}, in Doubles;
     * das Ergebnis steht in a, zurück kommt die Zahl seiner Ecken. a und b fassen n + 4 Ecken.
     */
    static int kappe(double[] p, int n, double[] w, double[] a, double[] b) {
        System.arraycopy(p, 0, a, 0, 2 * n);
        for (int seite = 0; seite < 4 && n > 0; seite++) {
            int achse = seite & 1;
            boolean bisMax = seite >= 2;
            double grenze = w[seite];
            int m = 0;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                double vi = a[2 * i + achse], vj = a[2 * j + achse];
                boolean ii = bisMax ? vi <= grenze : vi >= grenze, ij = bisMax ? vj <= grenze : vj >= grenze;
                if (ii) {
                    b[2 * m] = a[2 * i];
                    b[2 * m + 1] = a[2 * i + 1];
                    m++;
                }
                if (ii != ij) {
                    double t = (grenze - vi) / (vj - vi);
                    b[2 * m] = a[2 * i] + t * (a[2 * j] - a[2 * i]);
                    b[2 * m + 1] = a[2 * i + 1] + t * (a[2 * j + 1] - a[2 * i + 1]);
                    m++;
                }
            }
            System.arraycopy(b, 0, a, 0, 2 * m);
            n = m;
        }
        return n;
    }

    private static void rand(Ansicht a, Ebenen.Form f, float[] kreis, List<Vielecke> aus) {
        Ebenen.Rand r = switch (f) {
            case Ebenen.Flaeche fl -> fl.rand();
            case Ebenen.Kreis k -> k.rand();
            case Ebenen.Linie l -> l.rand();
        };
        if (r == null) {
            return;
        }
        Sammler s = new Sammler(a.schnitt());
        double h = r.breite() * a.einheit() / 2, strich = r.strich() * a.einheit(), luecke = r.luecke() * a.einheit();
        switch (f) {
            case Ebenen.Flaeche fl -> {
                for (double[] ring : fl.ringe()) {
                    streifen(s, abgebildet(a, ring), ring.length / 2, true, h, strich, luecke);
                }
            }
            case Ebenen.Kreis k -> {
                double[] p = new double[kreis.length];
                for (int i = 0; i < kreis.length; i++) {
                    p[i] = kreis[i];
                }
                streifen(s, p, p.length / 2, true, h, strich, luecke);
            }
            case Ebenen.Linie l -> streifen(s, abgebildet(a, l.punkte()), l.punkte().length / 2, false, h, strich, luecke);
        }
        s.element(aus, a, r.farbe());
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
     * Legt den Linienzug p aus n Punkten, geschlossen mit {@code zu}, als Züge der halben Breite h in den
     * Sammler. Gestrichelt mit {@code strich} und {@code luecke} (0: durchgezogen); die Striche laufen
     * über die Ecken weiter, mit Gehrung wie der durchgezogene Zug. Nur der Teil jeder Strecke im
     * Kasten des Schnitts bekommt Striche; der Rest schiebt nur das Muster weiter.
     */
    static void streifen(Sammler s, double[] p, int n, boolean zu, double h, double strich, double luecke) {
        int strecken = zu ? n : n - 1;
        if (strecken < 1) {
            return;
        }
        double rand = GEHRUNG * h + 1, muster = strich + luecke, pos = 0;
        double[] kasten = {s.x0 - rand, s.y0 - rand, s.x1 + rand, s.y1 + rand}, t = new double[2];
        // Geschlossen und durchgezogen beginnt der Zug hinter einer Strecke, die nicht ganz im Kasten liegt; so bricht er nur dort ab.
        int start = 0;
        boolean ganz = true;
        if (zu && strich <= 0) {
            for (int i = 0; i < strecken && ganz; i++) {
                if (!(imKasten(p, i, (i + 1) % n, kasten, t) && t[0] == 0 && t[1] == 1)) {
                    start = (i + 1) % n;
                    ganz = false;
                }
            }
        }
        Zug z = new Zug(s, h);
        for (int q = 0; q < strecken; q++) {
            int i = (start + q) % n, j = (i + 1) % n;
            double x0 = p[2 * i], y0 = p[2 * i + 1], x1 = p[2 * j], y1 = p[2 * j + 1], laenge = Math.hypot(x1 - x0, y1 - y0);
            if (laenge == 0) {
                continue;
            }
            if (!imKasten(p, i, j, kasten, t)) {
                z.ende(false);
                pos = strich > 0 ? (pos + laenge) % muster : 0;
                continue;
            }
            if (strich <= 0) {
                if (t[0] > 0) {
                    z.ende(false);
                }
                z.punkt(x0 + (x1 - x0) * t[0], y0 + (y1 - y0) * t[0]);
                z.punkt(x0 + (x1 - x0) * t[1], y0 + (y1 - y0) * t[1]);
                if (t[1] < 1) {
                    z.ende(false);
                }
                continue;
            }
            double a = t[0] * laenge, e = t[1] * laenge;
            if (a > 0) {
                z.ende(false);
                pos = (pos + a) % muster;
            }
            for (double l = a; l < e; ) {
                boolean an = pos < strich;
                double schritt = Math.min(an ? strich - pos : muster - pos, e - l);
                if (an) {
                    z.punkt(x0 + (x1 - x0) * l / laenge, y0 + (y1 - y0) * l / laenge);
                    z.punkt(x0 + (x1 - x0) * (l + schritt) / laenge, y0 + (y1 - y0) * (l + schritt) / laenge);
                } else {
                    z.ende(false);
                }
                l += schritt;
                pos = (pos + schritt) % muster;
            }
            if (e < laenge) {
                z.ende(false);
                pos = (pos + laenge - e) % muster;
            }
        }
        z.ende(zu && strich <= 0 && ganz);
    }

    /** Der Abschnitt [t0, t1] der Strecke von Punkt i zu Punkt j im Kasten {x0, y0, x1, y1}; false, wenn keiner. */
    static boolean imKasten(double[] p, int i, int j, double[] kasten, double[] t) {
        double x0 = p[2 * i], y0 = p[2 * i + 1], dx = p[2 * j] - x0, dy = p[2 * j + 1] - y0;
        double von = 0, bis = 1;
        double[] d = {-dx, dx, -dy, dy}, q = {x0 - kasten[0], kasten[2] - x0, y0 - kasten[1], kasten[3] - y0};
        for (int k = 0; k < 4; k++) {
            if (d[k] == 0) {
                if (q[k] < 0) {
                    return false;
                }
            } else if (d[k] < 0) {
                von = Math.max(von, q[k] / d[k]);
            } else {
                bis = Math.min(bis, q[k] / d[k]);
            }
        }
        t[0] = von;
        t[1] = bis;
        return von < bis;
    }

    /** Ein zusammenhängender Zug, Punkt für Punkt; am Ende als Vierecke mit Gehrung in den Sammler. */
    private static final class Zug {

        private final Sammler s;
        private final double h;
        private double[] p = new double[32];
        private int n;

        Zug(Sammler s, double h) {
            this.s = s;
            this.h = h;
        }

        void punkt(double x, double y) {
            if (n > 0 && p[2 * n - 2] == x && p[2 * n - 1] == y) {
                return;
            }
            if (2 * n + 2 > p.length) {
                p = Arrays.copyOf(p, 2 * p.length);
            }
            p[2 * n] = x;
            p[2 * n + 1] = y;
            n++;
        }

        void ende(boolean zu) {
            if (zu && n > 2 && p[0] == p[2 * n - 2] && p[1] == p[2 * n - 1]) {
                n--;
            }
            if (n >= 2) {
                s.zug(p, n, zu && n > 2, h);
            }
            n = 0;
        }
    }

    /**
     * Sammelt Vielecke in Einheiten des Elements, geschnitten mit dem konvexen Vieleck {@code schnitt},
     * erst mit seinem umschliessenden Rechteck, dann mit ihm; ganz innen liegende ohne Schnitt. Der
     * Umlaufsinn wird wie bei den Regionen der Karte gelegt, denn das GUI verwirft Rückseiten.
     */
    static final class Sammler {

        private final float[] schnitt, kasten;
        final double x0, y0, x1, y1;
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

        /**
         * Der Zug p aus n Punkten mit der halben Breite h, geschlossen mit {@code zu}: je Strecke ein
         * Viereck, an den Ecken mit Gehrung, sodass Nachbarn sich eine Kante teilen. Ist die Ecke zu
         * spitz ({@link #GEHRUNG}) oder eine Strecke zu kurz für die Spitze, endet jede Strecke gerade
         * und ein Dreieck füllt aussen die Fase. Die Enden eines offenen Zugs sind gerade.
         */
        void zug(double[] p, int n, boolean zu, double h) {
            int strecken = zu ? n : n - 1;
            double[] nx = new double[strecken], ny = new double[strecken], l = new double[strecken];
            for (int i = 0; i < strecken; i++) {
                int j = (i + 1) % n;
                double dx = p[2 * j] - p[2 * i], dy = p[2 * j + 1] - p[2 * i + 1];
                l[i] = Math.hypot(dx, dy);
                nx[i] = -dy / l[i];
                ny[i] = dx / l[i];
            }
            // Je Punkt die Ecken links und rechts: ab für die Strecke ab ihm, bis für die Strecke bis zu ihm.
            double[] ab = new double[4 * n], bis = new double[4 * n];
            for (int i = 0; i < n; i++) {
                double x = p[2 * i], y = p[2 * i + 1];
                if (!zu && (i == 0 || i == n - 1)) {
                    int k = i == 0 ? 0 : strecken - 1;
                    setze(ab, i, x + nx[k] * h, y + ny[k] * h, x - nx[k] * h, y - ny[k] * h);
                    System.arraycopy(ab, 4 * i, bis, 4 * i, 4);
                    continue;
                }
                int vor = (i - 1 + strecken) % strecken, nach = i;
                double mx = nx[vor] + nx[nach], my = ny[vor] + ny[nach], ml = Math.hypot(mx, my), cos = ml / 2;
                double weit = cos > 0 ? h / cos : Double.POSITIVE_INFINITY, laengs = weit * Math.sqrt(Math.max(0, 1 - cos * cos));
                if (weit <= GEHRUNG * h && laengs <= Math.min(l[vor], l[nach]) / 2) {
                    double ox = mx / ml * weit, oy = my / ml * weit;
                    setze(ab, i, x + ox, y + oy, x - ox, y - oy);
                    System.arraycopy(ab, 4 * i, bis, 4 * i, 4);
                } else {
                    setze(bis, i, x + nx[vor] * h, y + ny[vor] * h, x - nx[vor] * h, y - ny[vor] * h);
                    setze(ab, i, x + nx[nach] * h, y + ny[nach] * h, x - nx[nach] * h, y - ny[nach] * h);
                    // Biegt der Zug zur linken Seite, liegt die Fase rechts, sonst links.
                    double dx = p[2 * ((i + 1) % n)] - x, dy = p[2 * ((i + 1) % n) + 1] - y;
                    int seite = nx[vor] * dx + ny[vor] * dy > 0 ? 2 : 0;
                    quad[0] = (float) x;
                    quad[1] = (float) y;
                    quad[2] = (float) bis[4 * i + seite];
                    quad[3] = (float) bis[4 * i + seite + 1];
                    quad[4] = (float) ab[4 * i + seite];
                    quad[5] = (float) ab[4 * i + seite + 1];
                    vieleck(quad, 3);
                }
            }
            for (int i = 0; i < strecken; i++) {
                int j = (i + 1) % n;
                quad[0] = (float) ab[4 * i];
                quad[1] = (float) ab[4 * i + 1];
                quad[2] = (float) ab[4 * i + 2];
                quad[3] = (float) ab[4 * i + 3];
                quad[4] = (float) bis[4 * j + 2];
                quad[5] = (float) bis[4 * j + 3];
                quad[6] = (float) bis[4 * j];
                quad[7] = (float) bis[4 * j + 1];
                vieleck(quad, 4);
            }
        }

        private static void setze(double[] k, int i, double lx, double ly, double rx, double ry) {
            k[4 * i] = lx;
            k[4 * i + 1] = ly;
            k[4 * i + 2] = rx;
            k[4 * i + 3] = ry;
        }

        void vieleck(float[] p, int k) {
            int platz = 2 * (k + schnitt.length / 2 + 8);
            if (a.length < platz) {
                a = new float[platz];
                b = new float[platz];
            }
            int m;
            if (drin(p, k)) {
                System.arraycopy(p, 0, a, 0, 2 * k);
                m = k;
            } else {
                m = Drehung.schneide(p, k, kasten, 4, a, b);
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

        /** Liegt das Vieleck ganz im Schnitt? Dann braucht es keinen; der Schnitt ist konvex. */
        boolean drin(float[] p, int k) {
            for (int i = 0; i < k; i++) {
                if (p[2 * i] < x0 || p[2 * i] > x1 || p[2 * i + 1] < y0 || p[2 * i + 1] > y1) {
                    return false;
                }
            }
            if (schnitt.length == 8 && Arrays.equals(schnitt, kasten)) {
                return true;
            }
            int c = schnitt.length / 2;
            double vorzeichen = 0;
            for (int e = 0; e < c; e++) {
                int f = (e + 1) % c;
                double ex = schnitt[2 * f] - schnitt[2 * e], ey = schnitt[2 * f + 1] - schnitt[2 * e + 1];
                for (int i = 0; i < k; i++) {
                    double kreuz = ex * (p[2 * i + 1] - schnitt[2 * e + 1]) - ey * (p[2 * i] - schnitt[2 * e]);
                    if (kreuz != 0) {
                        if (vorzeichen == 0) {
                            vorzeichen = Math.signum(kreuz);
                        } else if (Math.signum(kreuz) != vorzeichen) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        /** Hängt die gesammelten Vielecke als ein Element an; ohne Vielecke keins. */
        void element(List<Vielecke> aus, Ansicht a, int farbe) {
            if (n > 0) {
                aus.add(new Vielecke(a.pose(), farbe, ecken, anzahl, n, a.bounds()));
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
