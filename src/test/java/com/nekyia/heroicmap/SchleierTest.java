package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Wie der Schleier seine Kanten in Stücke je Block teilt, wo er steht und welche er nimmt. Siehe docs/wegpunkte.md, „Schleier“. */
class SchleierTest {

    private static final String WELT = "minecraft:overworld";

    private static List<double[]> stuecke(Ebenen.Form f, double[] kasten) {
        List<double[]> aus = new ArrayList<>();
        Schleier.kanten(f, kasten, (x0, z0, x1, z1) -> aus.add(new double[] {x0, z0, x1, z1}));
        return aus;
    }

    private static List<double[]> teile(double ax, double az, double bx, double bz) {
        List<double[]> aus = new ArrayList<>();
        Schleier.teile(ax, az, bx, bz, (x0, z0, x1, z1) -> aus.add(new double[] {x0, z0, x1, z1}));
        return aus;
    }

    /** Liegt das Stück in einem Block? Beide Enden auf seinem Rand oder darin. */
    private static boolean inEinemBlock(double[] s) {
        double mx = Math.floor((s[0] + s[2]) / 2), mz = Math.floor((s[1] + s[3]) / 2);
        for (int i = 0; i < 4; i += 2) {
            if (s[i] < mx - 1e-9 || s[i] > mx + 1 + 1e-9 || s[i + 1] < mz - 1e-9 || s[i + 1] > mz + 1 + 1e-9) {
                return false;
            }
        }
        return true;
    }

    @Test
    void geteiltAnJederGanzenZahl() {
        // Auf einer Blockgrenze, auch rückwärts von einer ganzen Zahl aus: ein Stück je Block, keins leer.
        List<double[]> vor = teile(0, 5, 3, 5), zurueck = teile(3, 5, 0, 5);
        assertEquals(3, vor.size());
        assertEquals(3, zurueck.size());
        assertArrayEquals(new double[] {3, 5, 2, 5}, zurueck.get(0));
        // Schräg: Kreuzungen bei x = 1 (t = 0,25), z = 1 (0,5), x = 2 (0,75).
        List<double[]> schraeg = teile(0.5, 0.5, 2.5, 1.5);
        assertEquals(4, schraeg.size());
        assertTrue(schraeg.stream().allMatch(SchleierTest::inEinemBlock));
        assertArrayEquals(new double[] {2, 1.25, 2.5, 1.5}, schraeg.get(3), 1e-12);
        // Durch eine Ecke: x und z zugleich, ohne leeres Stück dazwischen.
        assertEquals(2, teile(0.5, 0.5, 1.5, 1.5).size());
    }

    @Test
    void rechteckEinStueckJeBlockDerKante() {
        double[] ring = {0, 0, 4, 0, 4, 4, 0, 4};
        Ebenen.Flaeche f = new Ebenen.Flaeche(WELT, 0x80FF0000, null, null, List.of(ring), new double[] {0, 0, 4, 4}, "r");
        assertEquals(16, stuecke(f, new double[] {-100, -100, 100, 100}).size());
        // Nur was im Kasten liegt: die linke Kante und je ein Block oben und unten.
        assertEquals(6, stuecke(f, new double[] {-1, -1, 1, 5}).size());
    }

    @Test
    void kreisInSehnenUeberDenBogen() {
        Ebenen.Kreis k = new Ebenen.Kreis(WELT, 0, 0, 10, 0, null, "k");
        List<double[]> ganz = stuecke(k, new double[] {-100, -100, 100, 100});
        double laenge = ganz.stream().mapToDouble(s -> Math.hypot(s[2] - s[0], s[3] - s[1])).sum();
        assertEquals(2 * Math.PI * 10, laenge, 0.1);
        assertTrue(ganz.stream().allMatch(SchleierTest::inEinemBlock));
        // Ein Kreis von 100 000 Blöcken: nur der Bogen beim Kasten, kein Stück ausserhalb.
        Ebenen.Kreis gross = new Ebenen.Kreis(WELT, 100_000, 0, 100_000, 0, null, "g");
        double[] kasten = {-50, -50, 50, 50};
        List<double[]> bogen = stuecke(gross, kasten);
        assertTrue(bogen.size() >= 90 && bogen.size() < 300, "Stücke " + bogen.size());
        assertTrue(bogen.stream().allMatch(s -> s[0] >= -50 - 1e-6 && s[2] <= 50 + 1e-6 && Math.abs(s[1]) <= 50 + 1e-6));
        // Liegt der Kasten ganz im Kreis oder ganz daneben, keine Kante.
        assertNull(Schleier.bogen(0, 0, 1000, kasten));
        assertNull(Schleier.bogen(5000, 0, 10, kasten));
    }

    @Test
    void hoeheDieGroessteDerBeruehrtenBloecke() {
        // Block (x, z) hat die Höhe 10 · x + z.
        assertEquals(12, Schleier.hoehe((x, z) -> 10 * x + z, 1.5, 2.5));
        // Auf der Kante zwischen x = 0 und x = 1: der höhere.
        assertEquals(12, Schleier.hoehe((x, z) -> 10 * x + z, 1, 2.5));
        // Auf der Ecke von vier Blöcken: der höchste.
        assertEquals(12, Schleier.hoehe((x, z) -> 10 * x + z, 1, 2));
        // Links des Ursprungs: der Block (-1, -1).
        assertEquals(-11, Schleier.hoehe((x, z) -> 10 * x + z, -0.5, -0.5));
    }

    @Test
    void dieNaechstenZuerstHoechstensSoViele() {
        double[] abstand = {9, 1, 4, 0, 16};
        assertArrayEquals(new int[] {3, 1, 2}, Schleier.naechste(abstand, 5, 3));
        assertArrayEquals(new int[] {3, 1, 2, 0, 4}, Schleier.naechste(abstand, 5, 100));
    }

    /** Die alte Auswahl: alles sortieren, die ersten nehmen. */
    private static int[] vollSortiert(double[] abstand, int n, int max) {
        long[] schluessel = new long[n];
        for (int i = 0; i < n; i++) {
            schluessel[i] = (long) Float.floatToIntBits((float) abstand[i]) << 32 | i;
        }
        java.util.Arrays.sort(schluessel);
        int[] aus = new int[Math.min(n, max)];
        for (int i = 0; i < aus.length; i++) {
            aus[i] = (int) schluessel[i];
        }
        return aus;
    }

    @Test
    void auswahlGibtDasselbeWieVollesSortieren() {
        // Viele gleiche Abstände, wie Stücke auf einem Ring um den Spieler; dieselben in derselben Reihe.
        java.util.Random zufall = new java.util.Random(36);
        for (int n : new int[] {0, 1, 7, 5_000, 40_000}) {
            double[] abstand = new double[n];
            for (int i = 0; i < n; i++) {
                abstand[i] = zufall.nextInt(2_000) * 3.0;
            }
            for (int max : new int[] {0, 1, 4_999, 5_000, 5_001, 40_000}) {
                assertArrayEquals(vollSortiert(abstand, n, max), Schleier.naechste(abstand, n, max), "n " + n + ", max " + max);
            }
        }
        // Schon sortiert und rückwärts, gegen einen schlechten Teiler.
        double[] auf = new double[20_000], ab = new double[20_000];
        for (int i = 0; i < auf.length; i++) {
            auf[i] = i;
            ab[i] = auf.length - i;
        }
        assertArrayEquals(vollSortiert(auf, auf.length, 5_000), Schleier.naechste(auf, auf.length, 5_000));
        assertArrayEquals(vollSortiert(ab, ab.length, 5_000), Schleier.naechste(ab, ab.length, 5_000));
    }

    /** Die Stücke, deren Mitte höchstens {@code r} von (mx, mz) liegt, wie der Bau sie behält. */
    private static List<double[]> behalten(List<double[]> stuecke, double mx, double mz, double r) {
        return stuecke.stream().filter(s -> {
            double dx = (s[0] + s[2]) / 2 - mx, dz = (s[1] + s[3]) / 2 - mz;
            return dx * dx + dz * dz <= r * r;
        }).toList();
    }

    @Test
    void imKreisFehltKeinStueckDasDerBauBehaelt() {
        // Ein Block mehr als die Reichweite: Was der Bau behält, ist mit und ohne Kreis dasselbe; ohne Kreis entstehen mehr Stücke.
        java.util.Random zufall = new java.util.Random(91);
        double mx = 0.5, mz = 0.5, r = 60;
        double[] kasten = {mx - r, mz - r, mx + r, mz + r};
        for (int versuch = 0; versuch < 200; versuch++) {
            double[] ring = new double[2 * (3 + zufall.nextInt(6))];
            for (int i = 0; i < ring.length; i++) {
                ring[i] = zufall.nextDouble() * 200 - 100;
            }
            Ebenen.Form f = versuch % 2 == 0
                    ? new Ebenen.Flaeche(WELT, 0x80FF0000, null, null, List.of(ring), new double[] {-100, -100, 100, 100}, "f")
                    : new Ebenen.Kreis(WELT, ring[0], ring[1], 1 + zufall.nextDouble() * 90, 0x80FF0000, null, "k");
            List<double[]> ohne = new ArrayList<>(), mit = new ArrayList<>();
            Schleier.kanten(f, kasten, (x0, z0, x1, z1) -> ohne.add(new double[] {x0, z0, x1, z1}));
            Schleier.kanten(f, kasten, mx, mz, r + 1, (x0, z0, x1, z1) -> mit.add(new double[] {x0, z0, x1, z1}));
            List<double[]> a = behalten(ohne, mx, mz, r), b = behalten(mit, mx, mz, r);
            assertEquals(a.size(), b.size(), "Versuch " + versuch);
            for (int i = 0; i < a.size(); i++) {
                assertArrayEquals(a.get(i), b.get(i), 1e-9, "Versuch " + versuch + ", Stück " + i);
            }
            assertTrue(mit.size() <= ohne.size());
        }
    }

    @Test
    void farbeVomRandSonstDieFuellung() {
        Ebenen.Rand rand = new Ebenen.Rand(0xFF112233, 2, 0, 0);
        double[] ring = {0, 0, 1, 0, 1, 1};
        assertEquals(0xFF112233, Schleier.farbe(new Ebenen.Flaeche(WELT, 0x80445566, null, rand, List.of(ring), new double[] {0, 0, 1, 1}, "a")));
        assertEquals(0xFF445566, Schleier.farbe(new Ebenen.Kreis(WELT, 0, 0, 1, 0x80445566, null, "b")));
        assertEquals(0, Schleier.farbe(new Ebenen.Kreis(WELT, 0, 0, 1, 0, null, "c")));
        // Eine Linie, auch eine eigene aus Wegpunkten, bekommt keinen Schleier.
        assertEquals(0, Schleier.farbe(new Ebenen.Linie(WELT, new double[] {0, 0, 4, 4}, rand, new double[] {0, 0, 4, 4})));
    }
}
