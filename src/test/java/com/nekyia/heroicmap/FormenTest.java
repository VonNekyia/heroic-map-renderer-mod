package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.joml.Matrix3x2f;
import org.junit.jupiter.api.Test;

/** Ränder, Kreise und der Schnitt der Formen. Siehe docs/ebenen.md, „Flächen, Kreise und Linien“. */
class FormenTest {

    private static Formen.Sammler sammler() {
        return new Formen.Sammler(Drehung.rechteck(-1000, -1000, 1000, 1000));
    }

    /** Die Fläche aller gesammelten Vielecke, jedes einmal. */
    private static double flaeche(Formen.Sammler s) {
        double summe = 0;
        int o = 0;
        for (int v = 0; v < s.n; v++) {
            double f = 0;
            for (int i = 0; i < s.anzahl[v]; i++) {
                int j = (i + 1) % s.anzahl[v];
                f += (double) s.ecken[2 * (o + i)] * s.ecken[2 * (o + j) + 1] - (double) s.ecken[2 * (o + j)] * s.ecken[2 * (o + i) + 1];
            }
            summe += Math.abs(f) / 2;
            o += s.anzahl[v];
        }
        return summe;
    }

    /** Hat das Vieleck v die Ecke (x, y)? */
    private static boolean ecke(Formen.Sammler s, int v, float x, float y) {
        int o = 0;
        for (int i = 0; i < v; i++) {
            o += s.anzahl[i];
        }
        for (int i = o; i < o + s.anzahl[v]; i++) {
            if (Math.abs(s.ecken[2 * i] - x) < 1e-4 && Math.abs(s.ecken[2 * i + 1] - y) < 1e-4) {
                return true;
            }
        }
        return false;
    }

    @Test
    void eckenNachRadius() {
        // Die Sehne weicht höchstens einen halben Pixel ab; mindestens 16, höchstens 4096 Ecken.
        assertEquals(16, Formen.ecken(0.5));
        assertEquals(16, Formen.ecken(2));
        assertEquals(100, Formen.ecken(1000));
        assertEquals(4096, Formen.ecken(1e9));
    }

    @Test
    void gehrungTeiltDieKante() {
        // Rechter Winkel: Gehrung, beide Vierecke teilen die Ecken (9, 1) und (11, -1).
        Formen.Sammler s = sammler();
        Formen.streifen(s, new double[] {0, 0, 10, 0, 10, 10}, 3, false, 1, 0, 0);
        assertEquals(2, s.n);
        for (int v = 0; v < 2; v++) {
            assertTrue(ecke(s, v, 9, 1) && ecke(s, v, 11, -1), "Viereck " + v);
        }
        // Der Fall aus dem Review: ein Claim aus Chunks, geschlossen. Aussen fehlt nichts, innen liegt nichts doppelt.
        Formen.Sammler claim = sammler();
        Formen.streifen(claim, new double[] {0, 0, 16, 0, 16, 16, 0, 16}, 4, true, 1, 0, 0);
        assertEquals(4, claim.n);
        assertEquals(18 * 18 - 14 * 14, flaeche(claim), 1e-3);
    }

    @Test
    void spitzMitFase() {
        // Eine spitze Ecke: Statt einer langen Spitze enden beide Strecken gerade, ein Dreieck füllt aussen.
        Formen.Sammler s = sammler();
        Formen.streifen(s, new double[] {0, 0, 10, 0, 0, 1}, 3, false, 1, 0, 0);
        assertEquals(3, s.n);
        int dreiecke = 0;
        for (int v = 0; v < s.n; v++) {
            dreiecke += s.anzahl[v] == 3 ? 1 : 0;
        }
        assertEquals(1, dreiecke);
        // Geschlossenes Dreieck: zwei spitze Ecken mit Fase, eine rechtwinklige mit Gehrung.
        Formen.Sammler zu = sammler();
        Formen.streifen(zu, new double[] {0, 0, 10, 0, 10, 10}, 3, true, 1, 0, 0);
        assertEquals(3 + 2, zu.n);
    }

    @Test
    void strichelnUeberDieEcke() {
        // 20 lang, Strich 8 und Lücke 6: 0–8 auf der ersten Strecke, Lücke bis 14, dann 14–20 auf der zweiten.
        Formen.Sammler s = sammler();
        Formen.streifen(s, new double[] {0, 0, 10, 0, 10, 10}, 3, false, 1, 8, 6);
        assertEquals(2, s.n);
        // Der zweite Strich liegt auf der zweiten Strecke bei y 4 bis 10.
        float ymin = Float.MAX_VALUE, ymax = -Float.MAX_VALUE;
        int start = s.anzahl[0];
        for (int i = start; i < start + s.anzahl[1]; i++) {
            ymin = Math.min(ymin, s.ecken[2 * i + 1]);
            ymax = Math.max(ymax, s.ecken[2 * i + 1]);
        }
        assertEquals(4, ymin, 1e-4);
        assertEquals(10, ymax, 1e-4);
        // Strich 12: Der erste läuft um die Ecke, mit Gehrung wie der durchgezogene Zug; dann 18–20.
        Formen.Sammler um = sammler();
        Formen.streifen(um, new double[] {0, 0, 10, 0, 10, 10}, 3, false, 1, 12, 6);
        assertEquals(3, um.n);
        assertTrue(ecke(um, 0, 9, 1) && ecke(um, 1, 9, 1) && ecke(um, 0, 11, -1) && ecke(um, 1, 11, -1));
    }

    @Test
    void nebenDemSchnittNurMuster() {
        // Zwei Strecken ganz draussen, 7 und 50 lang, schieben das Muster um 57, also 1 in den Strich hinein.
        // Die dritte, von x -43 nach 57 bei y 0, trägt Striche bei x [-2, 6), [12, 20), [26, 34), [40, 48), [54, 57).
        Formen.Sammler s = new Formen.Sammler(Drehung.rechteck(0, -10, 100, 10));
        Formen.streifen(s, new double[] {-50, 50, -43, 50, -43, 0, 57, 0}, 4, false, 1, 8, 6);
        assertEquals(5, s.n);
        // Der erste sichtbare Strich endet bei 6; ohne das Weiterschieben endete er bei 7.
        float xmax = -Float.MAX_VALUE;
        for (int i = 0; i < s.anzahl[0]; i++) {
            xmax = Math.max(xmax, s.ecken[2 * i]);
        }
        assertEquals(6, xmax, 1e-4);
    }

    @Test
    void weltgrenzeGestricheltNurImBild() {
        // Der Fall aus dem Review: 60 Mio. Blöcke bei Zoom 8 und GUI-Massstab 3, gestrichelt; Striche nur im Kasten.
        Formen.Sammler s = new Formen.Sammler(Drehung.rechteck(0, 0, 384, 384));
        double weit = 30_000_000.0 * 24;
        long start = System.nanoTime();
        Formen.streifen(s, new double[] {-weit, 100, weit, 100, weit, 200, -weit, 200}, 4, true, 3, 24, 18);
        assertTrue(System.nanoTime() - start < 100_000_000L);
        assertTrue(s.n > 10 && s.n < 30, "Striche " + s.n);
    }

    @Test
    void winzigesMusterDurchgezogen() {
        // Mehr als 1000 Striche auf einer sichtbaren Strecke: durchgezogen, ein Viereck statt Millionen.
        Formen.Sammler s = new Formen.Sammler(Drehung.rechteck(0, 0, 384, 384));
        long start = System.nanoTime();
        Formen.streifen(s, new double[] {-1e6, 100, 1e6, 100}, 2, false, 1, 0.01, 0.01);
        assertTrue(System.nanoTime() - start < 100_000_000L);
        assertEquals(1, s.n);
    }

    @Test
    void gedrehteQuadrateMitGehrung() {
        // Der Fall aus dem Review: Gedreht und mit gebrochener Mitte trennt die Rundung keine Ecke mehr.
        java.util.Random zufall = new java.util.Random(7);
        for (int versuch = 0; versuch < 200; versuch++) {
            double mx = 100 + zufall.nextDouble() * 50, my = 100 + zufall.nextDouble() * 50, w = zufall.nextDouble() * Math.PI;
            double[] p = new double[8];
            for (int i = 0; i < 4; i++) {
                double a = w + i * Math.PI / 2, d = 8 * Math.sqrt(2);
                p[2 * i] = mx + d * Math.cos(a + Math.PI / 4);
                p[2 * i + 1] = my + d * Math.sin(a + Math.PI / 4);
            }
            Formen.Sammler s = sammler();
            Formen.streifen(s, p, 4, true, 1, 0, 0);
            assertEquals(4, s.n, "Versuch " + versuch);
            assertEquals(18 * 18 - 14 * 14, flaeche(s), 1e-2, "Versuch " + versuch);
        }
    }

    @Test
    void ringMitErstemPunktDraussen() {
        // Der erste Punkt liegt ausserhalb; die Ecke bei (50, 10) bekommt trotzdem ihre Gehrung.
        Formen.Sammler s = new Formen.Sammler(Drehung.rechteck(0, 0, 100, 100));
        Formen.streifen(s, new double[] {-50, 10, 50, 10, 50, 50, 10, 50}, 4, true, 1, 0, 0);
        int mitEcke = 0;
        for (int v = 0; v < s.n; v++) {
            mitEcke += ecke(s, v, 49, 11) && ecke(s, v, 51, 9) ? 1 : 0;
        }
        assertEquals(2, mitEcke);
    }

    @Test
    void kreiseNurImKasten() {
        double[] kasten = {0, 0, 384, 384};
        Formen.Ansicht a = new Formen.Ansicht((wx, wz, aus) -> {
            aus[0] = wx;
            aus[1] = wz;
        }, 1, 1, 1, Drehung.rechteck(0, 0, 384, 384), new double[] {0, 0, 384, 384}, new Matrix3x2f(), new ScreenRectangle(0, 0, 384, 384));
        // Weit draussen: nichts. Der Kasten ganz innen: nur er.
        assertNull(Formen.bogen(a, kreis(1000, 1000, 100), kasten));
        assertTrue(Formen.bogen(a, kreis(192, 192, 100_000), kasten).innen());
        // Die Mitte im Kasten: das ganze Vieleck.
        Formen.Bogen ganz = Formen.bogen(a, kreis(192, 192, 100), kasten);
        assertTrue(ganz.ganz());
        assertEquals(Formen.ecken(100), ganz.punkte().length / 2);
        // Die Mitte weit draussen, der Rand quer durch: nur wenige Ecken, alle nah am Kasten.
        Formen.Bogen bogen = Formen.bogen(a, kreis(192, -99_800, 100_000), kasten);
        assertFalse(bogen.ganz() || bogen.innen());
        assertTrue(bogen.punkte().length / 2 < 20, "Ecken " + bogen.punkte().length / 2);
        for (int i = 0; i < bogen.punkte().length; i += 2) {
            // Eine Ecke vor und hinter dem Kasten; eine Sehne ist hier rund 630 lang.
            assertTrue(Math.abs(bogen.punkte()[i] - 192) < 2000);
        }
        // Die Phase ist die Länge des Kreises bis zur ersten Ecke: Das Muster bleibt beim Verschieben stehen.
        int n = Formen.ecken(100_000);
        double sehne = 2 * 100_000 * Math.sin(Math.PI / n);
        assertEquals(0, Math.IEEEremainder(bogen.phase(), sehne), 1e-6);
    }

    @Test
    void zehntausendGrosseKreise() {
        // Der Fall aus dem Review: 10 000 Kreise mit 100 000 Blöcken um den Spieler, bei Zoom 8 und GUI-Massstab 3.
        java.util.List<Ebenen.Form> kreise = new java.util.ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            kreise.add(new Ebenen.Kreis(Ebenen.UEBERWELT, i % 100, i / 100, 100_000, 0x40FF0000, new Ebenen.Rand(0xFFFFFFFF, 2, 0, 0), null));
        }
        Formen.Ansicht a = new Formen.Ansicht(Minimap.abbild(8, 3, 0, 0, Drehung.Lage.von(0.3, 192, 192, 0, 0)), 24, 3, 1,
                Drehung.rechteck(0, 0, 384, 384), new double[] {-16, -16, 16, 16}, new Matrix3x2f(), new ScreenRectangle(0, 0, 384, 384));
        java.util.List<Formen.Vielecke> aus = new java.util.ArrayList<>();
        long start = System.nanoTime();
        Formen.baue(a, Ebenen.UEBERWELT, kreise, aus);
        assertTrue(System.nanoTime() - start < 1_000_000_000L, "Neubau " + (System.nanoTime() - start) / 1_000_000 + " ms");
        // Je Kreis eine Füllung, der Kasten; kein Rand, der liegt weit draussen.
        assertEquals(10_000, aus.size());
    }

    @Test
    void budgetDerEcken() {
        // Zwei Vierecke passen in 8 Ecken, das dritte fehlt; der Rest ist geteilt.
        int[] rest = {8};
        Formen.Sammler s = new Formen.Sammler(Drehung.rechteck(0, 0, 10, 10), rest);
        for (int i = 0; i < 3; i++) {
            s.vieleck(new float[] {1, 1, 1, 2, 2, 2, 2, 1}, 4);
        }
        assertEquals(2, s.n);
        assertEquals(0, rest[0]);
    }

    private static Ebenen.Kreis kreis(double x, double z, double r) {
        return new Ebenen.Kreis(Ebenen.UEBERWELT, x, z, r, 0x40FF0000, new Ebenen.Rand(0xFFFFFFFF, 2, 0, 0), null);
    }

    @Test
    void kappeAufDieWelt() {
        double[] a = new double[16], b = new double[16];
        // Ein Trapez halb über den Rand: geschnitten bei x = 5.
        int m = Formen.kappe(new double[] {0, 0, 10, 0, 8, 4, 2, 4}, 4, new double[] {5, -10, 100, 10}, a, b);
        assertEquals(4, m);
        for (int i = 0; i < m; i++) {
            assertTrue(a[2 * i] >= 5 - 1e-9);
        }
        assertEquals(0, Formen.kappe(new double[] {0, 0, 1, 0, 1, 1}, 3, new double[] {5, 5, 6, 6}, a, b));
    }

    @Test
    void umlaufsinnUndSchnitt() {
        Formen.Sammler s = new Formen.Sammler(Drehung.rechteck(0, 0, 10, 10));
        // Beide Umlaufsinne, eins halb draussen: alle gespeichert mit negativer Fläche, das halbe geschnitten.
        s.vieleck(new float[] {1, 1, 1, 2, 2, 2, 2, 1}, 4);
        s.vieleck(new float[] {1, 1, 2, 1, 2, 2, 1, 2}, 4);
        s.vieleck(new float[] {5, 5, 5, 15, 15, 15, 15, 5}, 4);
        s.vieleck(new float[] {20, 20, 20, 30, 30, 30, 30, 20}, 4);
        assertEquals(3, s.n);
        int o = 0;
        for (int v = 0; v < s.n; v++) {
            double f = 0;
            for (int i = 0; i < s.anzahl[v]; i++) {
                int j = (i + 1) % s.anzahl[v];
                f += (double) s.ecken[2 * (o + i)] * s.ecken[2 * (o + j) + 1] - (double) s.ecken[2 * (o + j)] * s.ecken[2 * (o + i) + 1];
                assertTrue(s.ecken[2 * (o + i)] <= 10 && s.ecken[2 * (o + i) + 1] <= 10);
            }
            assertTrue(f < 0, "Vieleck " + v);
            o += s.anzahl[v];
        }
        // Ganz innen in einer Raute: ohne Schnitt; halb draussen nicht.
        Formen.Sammler raute = new Formen.Sammler(new float[] {0, -10, 10, 0, 0, 10, -10, 0});
        assertTrue(raute.drin(new float[] {-1, -1, -1, 1, 1, 1, 1, -1}, 4));
        assertFalse(raute.drin(new float[] {6, 6, 6, 7, 7, 7, 7, 6}, 4));
    }

    @Test
    void schriftMittigAufDemPfad() {
        // Drei Zeichen zu 10, Lücke 2, auf einer Strecke von 0 bis 100: mittig ab 33, also Mitten bei 38, 50 und 62.
        double[] l = Formen.anordnung(new double[] {0, 0, 100, 0}, new double[] {10, 10, 10}, 2);
        assertArrayEquals(new double[] {38, 0, 0, 50, 0, 0, 62, 0, 0}, l, 1e-9);
        // Nach links gezeichnet: umgekehrt, so steht die Schrift aufrecht, und dieselben Orte.
        assertArrayEquals(l, Formen.anordnung(new double[] {100, 0, 0, 0}, new double[] {10, 10, 10}, 2), 1e-9);
        // Um eine Ecke: das zweite Zeichen auf der zweiten Strecke, im Winkel nach unten (y nach unten).
        double[] ecke = Formen.anordnung(new double[] {0, 0, 10, 0, 10, 10}, new double[] {4, 4}, 0);
        assertArrayEquals(new double[] {8, 0, 0, 10, 2, Math.PI / 2}, ecke, 1e-9);
    }

    @Test
    void schriftUeberDieEnden() {
        // Kürzer als der Text: Die Schrift läuft in Richtung des ersten und letzten Stücks weiter.
        double[] l = Formen.anordnung(new double[] {0, 0, 10, 0}, new double[] {10, 10, 10}, 0);
        assertArrayEquals(new double[] {-5, 0, 0, 5, 0, 0, 15, 0, 0}, l, 1e-9);
        // Ein Punkt heisst waagrecht dort; doppelte Punkte zählen als einer.
        double[] p = Formen.anordnung(new double[] {7, 3, 7, 3}, new double[] {4, 6}, 1);
        assertArrayEquals(new double[] {3.5, 3, 0, 9.5, 3, 0}, p, 1e-9);
    }

    @Test
    void gleicheAnsichtGleicherSchluessel() {
        // Der Speicher rechnet nur neu, wenn sich der Schlüssel ändert; gedreht und verschoben ändert er sich.
        Formen.Ansicht a = ansicht(Minimap.abbild(2, 2, 100, 200, Drehung.Lage.von(0.3, 64, 64, 10, 20)));
        Formen.Ansicht b = ansicht(Minimap.abbild(2, 2, 100, 200, Drehung.Lage.von(0.3, 64, 64, 10, 20)));
        Formen.Ansicht c = ansicht(Minimap.abbild(2, 2, 100, 200, Drehung.Lage.von(0.31, 64, 64, 10, 20)));
        Formen.Ansicht d = ansicht(Minimap.abbild(2, 2, 101, 200, Drehung.Lage.von(0.3, 64, 64, 10, 20)));
        assertArrayEquals(Formen.schluessel(a), Formen.schluessel(b));
        assertFalse(Arrays.equals(Formen.schluessel(a), Formen.schluessel(c)));
        assertFalse(Arrays.equals(Formen.schluessel(a), Formen.schluessel(d)));
    }

    private static Formen.Ansicht ansicht(Formen.Abbild abbild) {
        return new Formen.Ansicht(abbild, 4, 2, 1, Drehung.rechteck(0, 0, 256, 256), new double[] {0, 0, 64, 64}, new Matrix3x2f(),
                new ScreenRectangle(0, 0, 256, 256));
    }
}
