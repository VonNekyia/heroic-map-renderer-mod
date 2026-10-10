package com.nekyia.heroicmap;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flächen, Kreise und Linien der Ebenen, flach wie die Karten des Mods: Füllungen als Trapeze aus
 * {@link Trapeze} oder als Vieleck des Kreises, Ränder und Linien als Züge mit Gehrung, gestrichelt
 * entlang des ganzen Zugs. Jede Füllung und jeder Rand ist ein Element des GUI in einer Farbe,
 * geschnitten mit der Form der Minimap oder dem Schirm. Siehe docs/ebenen.md, „Flächen, Kreise und Linien“.
 */
final class Formen {

    /** Die Spitze einer Gehrung reicht höchstens so viele halbe Breiten weit, also bis zu Ecken von 60°; spitzer wird es eine Fase. */
    static final double GEHRUNG = 2;
    /** Höchstens so viele Striche je sichtbarer Strecke; mehr zeichnet der Mod durchgezogen. */
    static final int MAX_STRICHE = 1000;
    /** Näher liegen zwei Punkte eines Zugs nicht, ohne einer zu sein; die Rundung macht sonst winzige Stücke ohne Gehrung. */
    static final double NAH = 1e-6;
    /** So viele Ecken legt ein Neubau höchstens; was darüber geht, fehlt, bei gleicher Ansicht immer dasselbe, also ohne Flackern. */
    static final int MAX_ECKEN = 1_000_000;
    private static final Logger LOGGER = LoggerFactory.getLogger(HeroicMap.ID);

    private Formen() {
    }

    /** Wie ein Punkt der Welt in die Einheiten des Elements kommt; affin, etwa verschoben, skaliert und gedreht. */
    interface Abbild {
        void ab(double wx, double wz, double[] aus);
    }

    /**
     * Was eine Ansicht gibt: das Abbild; wie lang ein Block und eine Einheit des GUI in Einheiten des
     * Elements sind, und wie viele Pixel des Schirms eine Einheit des Elements; das konvexe Vieleck,
     * an dem geschnitten wird; das sichtbare Rechteck der Welt {x0, z0, x1, z1}; Pose und Grenzen; wie
     * hoch die Grossbuchstaben der Kartenschrift höchstens stehen, in Einheiten des GUI.
     */
    record Ansicht(Abbild abbild, double block, double einheit, double pixel, float[] schnitt, double[] welt, Matrix3x2fc pose,
            ScreenRectangle bounds, double schrift) {
    }

    /** Zählt jedes Neuladen der Ressourcen: Die gespeicherten Texte halten Glyphen der Schrift, die dabei verfällt. */
    static int generation;

    /**
     * Ruft der Reload-Listener nach den Schriften des Spiels, ebenso {@link HeroicMap#schriftenGewechselt},
     * wenn die Optionen der Schrift wechseln; danach baut jede Ansicht neu.
     */
    static void neuGeladen() {
        generation++;
    }

    /** Was eine Ansicht zuletzt gezeichnet hat: Bleiben Ansicht und Formen gleich, hängt sie nur die fertigen Elemente wieder an. */
    static final class Speicher {

        private double[] schluessel = new double[0];
        private int generation;
        private String dimension;
        private List<List<Ebenen.Form>> formen = List.of();
        /** Die fertigen Elemente in ihrer Reihenfolge: Vielecke und Text des Spiels. */
        private List<Object> elemente = List.of();
        /** Die Ebenen, deren Formen oder Schrift das Budget schon einmal leerten. */
        private final Set<List<Ebenen.Form>> gewarnt = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<List<Ebenen.Form>> gewarntSchrift = Collections.newSetFromMap(new IdentityHashMap<>());

        /** Gilt, was gespeichert ist, noch für diese Ansicht, Dimension und Ebenen, mit der Schrift von jetzt? */
        boolean gilt(double[] schluessel, String dimension, List<List<Ebenen.Form>> ebenen) {
            return Arrays.equals(schluessel, this.schluessel) && this.generation == Formen.generation
                    && dimension.equals(this.dimension) && gleich(ebenen, formen);
        }

        void merke(double[] schluessel, String dimension, List<List<Ebenen.Form>> ebenen, List<Object> elemente) {
            this.schluessel = schluessel;
            this.generation = Formen.generation;
            this.dimension = dimension;
            this.formen = ebenen;
            this.elemente = elemente;
        }
    }

    /**
     * Zeichnet die Formen der Ebenen in ihrer Reihenfolge, in dieser Dimension: je Ebene erst alle
     * Füllungen, dann Ränder und Linien, dann die Schrift; ohne {@code flaechen} nur die Schrift, wie
     * auf der Minimap. Neu gerechnet nur, wenn sich Ansicht, Dimension oder eine Ebene ändert; sonst
     * hängt sie die fertigen Elemente wieder an.
     */
    static void zeichne(GuiGraphicsExtractor g, Ansicht a, String dimension, List<List<Ebenen.Form>> ebenen, Speicher sp, Font font,
            boolean flaechen) {
        double[] schluessel = schluessel(a);
        if (!sp.gilt(schluessel, dimension, ebenen)) {
            List<Object> neu = new ArrayList<>();
            int[] rest = {MAX_ECKEN}, zeichen = {MAX_ZEICHEN};
            Set<List<Ebenen.Form>> jetzt = Collections.newSetFromMap(new IdentityHashMap<>());
            jetzt.addAll(ebenen);
            sp.gewarnt.removeIf(l -> !jetzt.contains(l));
            sp.gewarntSchrift.removeIf(l -> !jetzt.contains(l));
            for (List<Ebenen.Form> formen : ebenen) {
                boolean vorher = rest[0] > 0;
                List<Vielecke> vielecke = new ArrayList<>();
                if (flaechen) {
                    baue(a, dimension, formen, vielecke, rest);
                }
                neu.addAll(vielecke);
                boolean schriftFehlt = texte(font, a, dimension, formen, neu, zeichen);
                // Einmal je Ebene und version: Eine neue version ist eine neue Liste.
                if (vorher && rest[0] <= 0 && sp.gewarnt.add(formen)) {
                    LOGGER.warn("Heroic Map: Formen über {} Ecken; was in dieser und den Ebenen darüber noch käme, fehlt", MAX_ECKEN);
                }
                if (schriftFehlt && sp.gewarntSchrift.add(formen)) {
                    LOGGER.warn("Heroic Map: Kartenschrift über {} Zeichen; eine Schrift, die nicht mehr ganz passt, fehlt", MAX_ZEICHEN);
                }
            }
            sp.merke(schluessel, dimension, ebenen, neu);
        }
        for (Object o : sp.elemente) {
            if (o instanceof Vielecke v) {
                g.guiRenderState.addGuiElement(v);
            } else {
                g.guiRenderState.addText((GuiTextRenderState) o);
            }
        }
    }

    /** Alles, was das Bild einer Ansicht bestimmt; das Abbild ist affin, drei Punkte legen es fest. */
    static double[] schluessel(Ansicht a) {
        double[] p = new double[2];
        float[] s = a.schnitt();
        Matrix3x2fc m = a.pose();
        double[] k = new double[25 + s.length];
        for (int i = 0; i < 3; i++) {
            a.abbild().ab(i == 1 ? 1 : 0, i == 2 ? 1 : 0, p);
            k[2 * i] = p[0];
            k[2 * i + 1] = p[1];
        }
        double[] rest = {a.block(), a.einheit(), a.pixel(), a.welt()[0], a.welt()[1], a.welt()[2], a.welt()[3],
            m.m00(), m.m01(), m.m10(), m.m11(), m.m20(), m.m21(), a.bounds().left(), a.bounds().top(), a.bounds().width(),
            a.bounds().height(), a.schrift(), s.length};
        System.arraycopy(rest, 0, k, 6, rest.length);
        for (int i = 0; i < s.length; i++) {
            k[25 + i] = s[i];
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
        baue(a, dimension, formen, aus, new int[] {MAX_ECKEN});
    }

    /** Wie oben, mit {@code rest} Ecken übrig für diesen Neubau. */
    static void baue(Ansicht a, String dimension, List<Ebenen.Form> formen, List<Vielecke> aus, int[] rest) {
        Bogen[] boegen = new Bogen[formen.size()];
        double[] kasten = kasten(a.schnitt(), GEHRUNG * Ebenen.MAX_BREITE * a.einheit() / 2 + 1);
        for (int i = 0; i < formen.size(); i++) {
            Ebenen.Form f = formen.get(i);
            if (f.dimension().equals(dimension) && sichtbar(a, f) && rest[0] > 0) {
                if (f instanceof Ebenen.Kreis k) {
                    boegen[i] = bogen(a, k, kasten);
                    if (boegen[i] == null) {
                        continue;
                    }
                }
                fuellung(a, f, boegen[i], kasten, aus, rest);
            }
        }
        for (int i = 0; i < formen.size(); i++) {
            Ebenen.Form f = formen.get(i);
            if (f.dimension().equals(dimension) && sichtbar(a, f) && (!(f instanceof Ebenen.Kreis) || boegen[i] != null)) {
                rand(a, f, boegen[i], aus, rest);
            }
        }
    }

    /** Die Kartenschrift: Schrift „karte“ aus assets/heroicmap/font/karte.json, IM Fell English SC in 16 Einheiten je Geviert. */
    static final Style STIL = Style.EMPTY.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath(HeroicMap.ID, "karte")));
    /** Höhe der Grossbuchstaben in Einheiten der Schrift: 16 · 1384 / 2048, die Oberkante des H, wie die Webkarte (0096 des Renderers). */
    static final float KAPPE = 16f * 1384 / 2048;
    /** Die Grundlinie liegt 7 Einheiten unter dem y des Texts, wie bei jeder Schrift des Spiels (GlyphBitmap.getTop). */
    static final float GRUNDLINIE = 7;
    /** Unter so vielen Einheiten des GUI Höhe fehlt die Schrift, über so vielen bleibt sie so gross, wie im Format. */
    static final double KLEINSTE_SCHRIFT = 8, GROESSTE_SCHRIFT = 96;
    /** So viele Zeichen legt ein Neubau höchstens, die Kopien der Kontur mitgezählt; was darüber geht, fehlt. */
    static final int MAX_ZEICHEN = 20_000;
    /** So weit reicht die Kontur höchstens, in Anteilen der Höhe der Grossbuchstaben; breiter zerfiele sie in Kopien. */
    static final double KONTUR_HOECHSTENS = 0.12;

    /**
     * Die Höhe der Grossbuchstaben in Einheiten des Elements: {@code groesse} Blöcke sind
     * groesse · block / einheit Einheiten des GUI. Unter {@link #KLEINSTE_SCHRIFT} fehlt die Schrift (0),
     * darüber steht sie höchstens {@link #GROESSTE_SCHRIFT} und {@code hoechstens} Einheiten hoch.
     */
    static double kappe(double groesse, double block, double einheit, double hoechstens) {
        double gui = groesse * block / einheit;
        return gui < KLEINSTE_SCHRIFT ? 0 : Math.min(Math.min(gui, GROESSTE_SCHRIFT), hoechstens) * einheit;
    }

    /** Die Breite der Kontur: gewünscht, höchstens {@link #KONTUR_HOECHSTENS} der Höhe der Grossbuchstaben; breiter zerfiele sie. */
    static double kontur(double gewuenscht, double kappe) {
        return Math.min(gewuenscht, KONTUR_HOECHSTENS * kappe);
    }

    /** Der Versatz der {@code k}-ten der acht Kopien der Kontur rundum, im Abstand {@code r}. */
    static double versatzX(int k, double r) {
        return r * Math.cos(k * Math.PI / 4);
    }

    static double versatzY(int k, double r) {
        return r * Math.sin(k * Math.PI / 4);
    }

    /** Ein Zeichen der Kartenschrift: welches, seine Mitte, Winkel und Massstab, der Versatz der Kontur in Einheiten der Schrift, die Farbe. */
    record Glyphe(int zeichen, double x, double y, double winkel, double massstab, double dx, double dy, int farbe) {
    }

    /**
     * Die Glyphen einer Kartenschrift auf dem Pfad p in Einheiten des Elements: erst alle Kopien der
     * Kontur der ganzen Schrift, dann alle Füllungen, so deckt keine Kontur ein Zeichen davor. Ein Zeichen,
     * dessen Mitte ausserhalb des Schnitts liegt, fehlt, ein Durchgang in unsichtbarer Farbe auch.
     * {@code breiten} in Einheiten der Schrift. Die Glyphen gehen ganz von {@code rest[0]} ab; passen sie
     * nicht ganz, null und {@code rest} bleibt, so steht keine Kontur ohne ihre Zeichen.
     */
    static List<Glyphe> glyphen(Ebenen.Schrift s, double[] p, double[] breiten, double kappe, double einheit, float[] schnitt, int[] rest) {
        double massstab = kappe / KAPPE;
        double[] b = new double[breiten.length];
        for (int i = 0; i < b.length; i++) {
            b[i] = breiten[i] * massstab;
        }
        double[] lage = anordnung(p, b, s.sperrung() * kappe);
        boolean[] drin = new boolean[b.length];
        for (int i = 0; i < b.length; i++) {
            drin[i] = innen(schnitt, lage[3 * i], lage[3 * i + 1]);
        }
        double r = kontur(s.konturBreite() * einheit, kappe) / massstab;
        boolean mitKontur = r > 0 && Ebenen.sichtbar(s.konturFarbe()), mitFuellung = Ebenen.sichtbar(s.farbe());
        int n = 0;
        for (boolean d : drin) {
            n += d ? 1 : 0;
        }
        int kosten = n * ((mitKontur ? 8 : 0) + (mitFuellung ? 1 : 0));
        if (kosten > rest[0]) {
            return null;
        }
        rest[0] -= kosten;
        List<Glyphe> aus = new ArrayList<>(kosten);
        for (int k = mitKontur ? 0 : 8; k <= (mitFuellung ? 8 : 7); k++) {
            // k 0 bis 7: die Kopien der Kontur rundum; 8: die Füllung.
            boolean kontur = k < 8;
            for (int i = 0; i < b.length; i++) {
                if (drin[i]) {
                    aus.add(new Glyphe(i, lage[3 * i], lage[3 * i + 1], lage[3 * i + 2], massstab, kontur ? versatzX(k, r) : 0,
                            kontur ? versatzY(k, r) : 0, kontur ? s.konturFarbe() : s.farbe()));
                }
            }
        }
        return aus;
    }

    /**
     * Die Kartenschrift einer Ebene als fertiger Text des Spiels, je Glyphe ein Element mit seiner Pose,
     * im Ausschnitt der Ansicht; true, wenn eine Schrift nicht mehr ins Budget passte.
     * Siehe docs/ebenen.md, „Kartenschrift“.
     */
    private static boolean texte(Font font, Ansicht a, String dimension, List<Ebenen.Form> formen, List<Object> aus, int[] rest) {
        boolean fehlt = false;
        for (Ebenen.Form f : formen) {
            if (!(f instanceof Ebenen.Schrift s) || !s.dimension().equals(dimension) || !sichtbar(a, s)) {
                continue;
            }
            double kappe = kappe(s.groesse(), a.block(), a.einheit(), a.schrift());
            if (kappe <= 0) {
                continue;
            }
            int[] codes = s.text().codePoints().toArray();
            FormattedCharSequence[] zeichen = new FormattedCharSequence[codes.length];
            double[] breiten = new double[codes.length];
            for (int i = 0; i < codes.length; i++) {
                Component c = Component.literal(Character.toString(codes[i])).withStyle(STIL);
                zeichen[i] = c.getVisualOrderText();
                breiten[i] = font.getSplitter().stringWidth(c);
            }
            List<Glyphe> glyphen = glyphen(s, abgebildet(a, s.pfad()), breiten, kappe, a.einheit(), a.schnitt(), rest);
            if (glyphen == null) {
                fehlt = true;
                continue;
            }
            for (Glyphe gl : glyphen) {
                // Die Mitte des Zeichens auf dem Punkt, die Mitte der Grossbuchstaben auf der Linie.
                Matrix3x2f pose = new Matrix3x2f(a.pose()).translate((float) gl.x(), (float) gl.y()).rotate((float) gl.winkel())
                        .scale((float) gl.massstab()).translate((float) (gl.dx() - breiten[gl.zeichen()] / 2), (float) (gl.dy() + KAPPE / 2 - GRUNDLINIE));
                aus.add(new GuiTextRenderState(font, zeichen[gl.zeichen()], pose, 0, 0, gl.farbe(), 0, false, false, a.bounds()));
            }
        }
        return fehlt;
    }

    /** Liegt der Punkt im konvexen Vieleck {@code schnitt}? */
    static boolean innen(float[] schnitt, double x, double y) {
        int c = schnitt.length / 2;
        double vorzeichen = 0;
        for (int e = 0; e < c; e++) {
            int f = (e + 1) % c;
            double kreuz = (schnitt[2 * f] - schnitt[2 * e]) * (y - schnitt[2 * e + 1]) - (schnitt[2 * f + 1] - schnitt[2 * e + 1]) * (x - schnitt[2 * e]);
            if (kreuz != 0) {
                if (vorzeichen == 0) {
                    vorzeichen = Math.signum(kreuz);
                } else if (Math.signum(kreuz) != vorzeichen) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Wo die Zeichen einer Kartenschrift liegen: je Zeichen Mitte und Winkel {x, y, w, …} auf dem Pfad p
     * in Einheiten des Elements, mittig, mit {@code luecke} zwischen Zeichen der Breiten {@code breiten}.
     * Läuft der Pfad im Bild nach links, gilt er umgekehrt, so steht die Schrift nie auf dem Kopf. Ist er
     * kürzer als der Text, läuft die Schrift an den Enden in Richtung des ersten und letzten Stücks weiter;
     * ein einzelner Punkt heisst waagrecht.
     */
    static double[] anordnung(double[] p, double[] breiten, double luecke) {
        // Doppelte Punkte fallen weg, sie hätten keine Richtung.
        double[] q = new double[p.length];
        int n = 0;
        for (int i = 0; i < p.length; i += 2) {
            if (n == 0 || p[i] != q[2 * n - 2] || p[i + 1] != q[2 * n - 1]) {
                q[2 * n] = p[i];
                q[2 * n + 1] = p[i + 1];
                n++;
            }
        }
        if (n > 1 && q[2 * n - 2] < q[0]) {
            for (int i = 0; i < n / 2; i++) {
                for (int k = 0; k < 2; k++) {
                    double t = q[2 * i + k];
                    q[2 * i + k] = q[2 * (n - 1 - i) + k];
                    q[2 * (n - 1 - i) + k] = t;
                }
            }
        }
        double[] l = new double[n];
        for (int i = 1; i < n; i++) {
            l[i] = l[i - 1] + Math.hypot(q[2 * i] - q[2 * i - 2], q[2 * i + 1] - q[2 * i - 1]);
        }
        double text = luecke * (breiten.length - 1);
        for (double b : breiten) {
            text += b;
        }
        double d = (l[n - 1] - text) / 2;
        double[] aus = new double[3 * breiten.length];
        for (int c = 0; c < breiten.length; c++) {
            double mitte = d + breiten[c] / 2;
            if (n == 1) {
                aus[3 * c] = q[0] + mitte;
                aus[3 * c + 1] = q[1];
            } else {
                int i = 0;
                while (i < n - 2 && l[i + 1] < mitte) {
                    i++;
                }
                double t = (mitte - l[i]) / (l[i + 1] - l[i]), dx = q[2 * i + 2] - q[2 * i], dy = q[2 * i + 3] - q[2 * i + 1];
                aus[3 * c] = q[2 * i] + t * dx;
                aus[3 * c + 1] = q[2 * i + 1] + t * dy;
                aus[3 * c + 2] = Math.atan2(dy, dx);
            }
            d += breiten[c] + luecke;
        }
        return aus;
    }

    /** Das umschliessende Rechteck des Schnitts {x0, y0, x1, y1}, um {@code rand} weiter. */
    static double[] kasten(float[] schnitt, double rand) {
        double[] k = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int i = 0; i < schnitt.length; i += 2) {
            k[0] = Math.min(k[0], schnitt[i]);
            k[1] = Math.min(k[1], schnitt[i + 1]);
            k[2] = Math.max(k[2], schnitt[i]);
            k[3] = Math.max(k[3], schnitt[i + 1]);
        }
        return new double[] {k[0] - rand, k[1] - rand, k[2] + rand, k[3] + rand};
    }

    /**
     * Was von einem Kreis im Kasten liegt, in Einheiten des Elements: {@code innen}, wenn der ganze Kasten
     * im Kreis liegt; sonst Punkte auf dem Kreis, alle mit {@code ganz}, wenn die Mitte im Kasten liegt,
     * sonst nur der Bogen über den Kasten, mit der Mitte (mx, my) für die Füllung. {@code phase} ist die
     * Länge des Kreises bis zum ersten Punkt, für das Muster der Striche. Null, wenn nichts davon im Kasten liegt.
     */
    record Bogen(double[] punkte, boolean ganz, boolean innen, double phase, double mx, double my) {
    }

    /** Siehe {@link Bogen}; die Punkte sind die Ecken des ganzen Vielecks aus {@link #ecken}, so bleibt das Muster beim Verschieben stehen. */
    static Bogen bogen(Ansicht a, Ebenen.Kreis k, double[] kasten) {
        double[] m = new double[2];
        a.abbild().ab(k.x(), k.z(), m);
        double r = k.radius() * a.block();
        double dx = Math.max(Math.max(kasten[0] - m[0], 0), m[0] - kasten[2]), dy = Math.max(Math.max(kasten[1] - m[1], 0), m[1] - kasten[3]);
        if (Math.hypot(dx, dy) > r) {
            return null;
        }
        double fern = 0, w0 = Math.atan2((kasten[1] + kasten[3]) / 2 - m[1], (kasten[0] + kasten[2]) / 2 - m[0]), lo = 0, hi = 0;
        for (int e = 0; e < 4; e++) {
            double ex = kasten[(e & 1) == 0 ? 0 : 2] - m[0], ey = kasten[(e & 2) == 0 ? 1 : 3] - m[1];
            fern = Math.max(fern, Math.hypot(ex, ey));
            double d = Math.IEEEremainder(Math.atan2(ey, ex) - w0, 2 * Math.PI);
            lo = Math.min(lo, d);
            hi = Math.max(hi, d);
        }
        if (fern <= r) {
            return new Bogen(null, false, true, 0, m[0], m[1]);
        }
        int n = ecken(r * a.pixel()), von = 0, bis = n;
        double schritt = 2 * Math.PI / n;
        boolean ganz = m[0] >= kasten[0] && m[0] <= kasten[2] && m[1] >= kasten[1] && m[1] <= kasten[3];
        if (!ganz) {
            // Von aussen sieht die Mitte den Kasten unter weniger als 180°: nur die Ecken darüber, eine davor und dahinter.
            von = (int) Math.floor((w0 + lo) / schritt) - 1;
            bis = Math.min((int) Math.ceil((w0 + hi) / schritt) + 2, von + n);
        }
        double[] p = new double[2 * (bis - von)];
        for (int i = von; i < bis; i++) {
            p[2 * (i - von)] = m[0] + r * Math.cos(i * schritt);
            p[2 * (i - von) + 1] = m[1] + r * Math.sin(i * schritt);
        }
        return new Bogen(p, ganz, false, Math.floorMod(von, n) * 2 * r * Math.sin(Math.PI / n), m[0], m[1]);
    }

    /** Liegt die Form, samt Rand, im sichtbaren Rechteck der Welt? */
    static boolean sichtbar(Ansicht a, Ebenen.Form f) {
        double[] b = switch (f) {
            case Ebenen.Flaeche fl -> fl.box();
            case Ebenen.Linie l -> l.box();
            case Ebenen.Kreis k -> new double[] {k.x() - k.radius(), k.z() - k.radius(), k.x() + k.radius(), k.z() + k.radius()};
            // Die Schrift reicht über ihren Pfad hinaus, so weit, wie ihre Zeichen in der gekappten Grösse breit sein können.
            case Ebenen.Schrift s -> {
                double h = kappe(s.groesse(), a.block(), a.einheit(), a.schrift()) / a.block();
                double w = h * 1.5 * (1 + s.sperrung()) * (s.text().length() + 1);
                yield new double[] {s.box()[0] - w, s.box()[1] - w, s.box()[2] + w, s.box()[3] + w};
            }
        };
        double rand = 1 + GEHRUNG * Ebenen.MAX_BREITE * a.einheit() / a.block();
        return b[2] >= a.welt()[0] - rand && b[0] <= a.welt()[2] + rand && b[3] >= a.welt()[1] - rand && b[1] <= a.welt()[3] + rand;
    }

    private static void fuellung(Ansicht a, Ebenen.Form f, Bogen bogen, double[] kasten, List<Vielecke> aus, int[] rest) {
        if (f instanceof Ebenen.Flaeche fl && fl.trapeze() != null) {
            Sammler s = new Sammler(a.schnitt(), rest);
            double[] t = fl.trapeze(), w = a.welt(), q = new double[8], k1 = new double[16], k2 = new double[16], p = new double[2];
            // Ein Block mehr: Das Rechteck der Welt ist schon das sichtbare, den Rest schneidet die Form.
            double[] welt = {w[0] - 1, w[1] - 1, w[2] + 1, w[3] + 1};
            float[] e = new float[16];
            for (int i = 0; i < t.length && rest[0] > 0; i += 6) {
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
            Sammler s = new Sammler(a.schnitt(), rest);
            if (bogen.innen()) {
                s.vieleck(Drehung.rechteck(kasten[0], kasten[1], kasten[2], kasten[3]), 4);
            } else {
                // Ganz das Vieleck des Kreises; sonst der Ausschnitt von der Mitte über den Bogen, erst in Doubles gekappt,
                // denn die Mitte kann weit draussen liegen. Beides ist konvex.
                double[] p = bogen.punkte();
                int n = p.length / 2;
                if (!bogen.ganz()) {
                    double[] stueck = new double[p.length + 2], a1 = new double[p.length + 10], a2 = new double[p.length + 10];
                    stueck[0] = bogen.mx();
                    stueck[1] = bogen.my();
                    System.arraycopy(p, 0, stueck, 2, p.length);
                    n = kappe(stueck, n + 1, kasten, a1, a2);
                    p = a1;
                }
                float[] e = new float[2 * n];
                for (int i = 0; i < 2 * n; i++) {
                    e[i] = (float) p[i];
                }
                if (n >= 3) {
                    s.vieleck(e, n);
                }
            }
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

    private static void rand(Ansicht a, Ebenen.Form f, Bogen bogen, List<Vielecke> aus, int[] rest) {
        Ebenen.Rand r = switch (f) {
            case Ebenen.Flaeche fl -> fl.rand();
            case Ebenen.Kreis k -> k.rand();
            case Ebenen.Linie l -> l.rand();
            case Ebenen.Schrift s -> null;
        };
        if (r == null || rest[0] <= 0) {
            return;
        }
        Sammler s = new Sammler(a.schnitt(), rest);
        double h = r.breite() * a.einheit() / 2, strich = r.strich() * a.einheit(), luecke = r.luecke() * a.einheit();
        switch (f) {
            case Ebenen.Flaeche fl -> {
                for (double[] ring : fl.ringe()) {
                    streifen(s, abgebildet(a, ring), ring.length / 2, true, h, strich, luecke);
                }
            }
            case Ebenen.Kreis k -> {
                if (!bogen.innen()) {
                    streifen(s, bogen.punkte(), bogen.punkte().length / 2, bogen.ganz(), h, strich, luecke, bogen.phase());
                }
            }
            case Ebenen.Linie l -> streifen(s, abgebildet(a, l.punkte()), l.punkte().length / 2, false, h, strich, luecke);
            case Ebenen.Schrift t -> {
            }
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
        streifen(s, p, n, zu, h, strich, luecke, 0);
    }

    /** Wie oben, das Muster der Striche schon um {@code phase} weiter. */
    static void streifen(Sammler s, double[] p, int n, boolean zu, double h, double strich, double luecke, double phase) {
        int strecken = zu ? n : n - 1;
        if (strecken < 1) {
            return;
        }
        double rand = GEHRUNG * h + 1, muster = strich + luecke, pos = strich > 0 ? phase % muster : 0;
        double[] kasten = {s.x0 - rand, s.y0 - rand, s.x1 + rand, s.y1 + rand}, t = new double[2];
        // Geschlossen und durchgezogen beginnt der Zug hinter einer Strecke, die nicht ganz im Kasten liegt; so bricht er nur dort ab.
        int start = 0;
        boolean ganz = true;
        if (zu && strich <= 0) {
            for (int i = 0; i < strecken && ganz; i++) {
                boolean drin = imKasten(p, i, (i + 1) % n, kasten, t);
                if (!(drin && t[0] == 0 && t[1] == 1)) {
                    // Beginnt die Strecke draussen, beginnt der Zug mit ihr, sonst hinter ihr.
                    start = drin && t[0] > 0 ? i : (i + 1) % n;
                    ganz = false;
                }
            }
        }
        Zug z = new Zug(s, h);
        // Ist das Budget leer, rechnet der Zug nicht weiter.
        for (int q = 0; q < strecken && s.rest[0] > 0; q++) {
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
            double a = t[0] * laenge, e = t[1] * laenge;
            if (strich <= 0 || (e - a) / muster > MAX_STRICHE) {
                if (t[0] > 0 || strich > 0) {
                    z.ende(false);
                }
                // An den Enden die genauen Punkte, sonst trennte die Rundung, was zusammengehört.
                z.punkt(t[0] == 0 ? x0 : x0 + (x1 - x0) * t[0], t[0] == 0 ? y0 : y0 + (y1 - y0) * t[0]);
                z.punkt(t[1] == 1 ? x1 : x0 + (x1 - x0) * t[1], t[1] == 1 ? y1 : y0 + (y1 - y0) * t[1]);
                if (t[1] < 1 || strich > 0) {
                    z.ende(false);
                }
                pos = strich > 0 ? (pos + laenge) % muster : 0;
                continue;
            }
            if (a > 0) {
                z.ende(false);
                pos = (pos + a) % muster;
            }
            for (double l = a; l < e && s.rest[0] > 0; ) {
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
            if (n > 0 && Math.abs(p[2 * n - 2] - x) + Math.abs(p[2 * n - 1] - y) < NAH) {
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
            if (zu && n > 2 && Math.abs(p[0] - p[2 * n - 2]) + Math.abs(p[1] - p[2 * n - 1]) < NAH) {
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
        /** Die Ecken, die der Neubau noch legen darf, geteilt mit den anderen Sammlern. */
        final int[] rest;
        final double x0, y0, x1, y1;
        float[] ecken = new float[256];
        int[] anzahl = new int[16];
        int n, punkte;
        private float[] a = new float[64], b = new float[64], quad = new float[8];

        Sammler(float[] schnitt) {
            this(schnitt, new int[] {MAX_ECKEN});
        }

        Sammler(float[] schnitt, int[] rest) {
            this.schnitt = schnitt;
            this.rest = rest;
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
            if (rest[0] < k) {
                rest[0] = 0;
                return;
            }
            rest[0] -= k;
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
