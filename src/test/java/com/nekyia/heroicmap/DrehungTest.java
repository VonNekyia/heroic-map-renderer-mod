package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Die drehende Minimap: Winkel, Drehung, Schnitt mit der Form, Marken. Siehe docs/minimap.md, „Drehen“. */
class DrehungTest {

    @Test
    void mitRahmenKeineLueckeZumRing() {
        // Der Ring ist in Einheiten gestuft: Jede Pixelmitte einer Einheit innerhalb der Bänder liegt im Vieleck,
        // auch nahe den Diagonalen; was darüber ragt, liegt unter dem Ring.
        for (int k : new int[] {2, 3, 4}) {
            for (int seite : new int[] {128, 256}) {
                for (int baender : new int[] {2, 3, 5}) {
                    float[] form = Minimap.schnitt(0, 0, seite, k, true, baender);
                    for (int y = 0; y < seite; y++) {
                        for (int x = 0; x < seite; x++) {
                            int band = Skin.bandRund(x, y, seite);
                            if (band < baender || band > baender + 1) {
                                continue;
                            }
                            for (int py = 0; py < k; py++) {
                                for (int px = 0; px < k; px++) {
                                    assertTrue(drinnen(form, x * k + px + 0.5, y * k + py + 0.5),
                                            "k " + k + ", Seite " + seite + ", Bänder " + baender + ", Einheit " + x + ", " + y);
                                }
                            }
                        }
                    }
                    for (int i = 0; i < form.length / 2; i++) {
                        assertTrue(Math.hypot(form[2 * i] - seite * k / 2.0, form[2 * i + 1] - seite * k / 2.0) < seite * k / 2.0);
                    }
                }
            }
        }
    }

    @Test
    void regionUndUvFuehrenAufDenselbenBildpunkt() {
        float[] form = Drehung.kreis(128, 128, 100);
        Drehung.Puffer puffer = new Drehung.Puffer();
        for (float gier : new float[] {30, 180, -123.4f}) {
            Drehung.Lage lage = Drehung.Lage.von(Drehung.winkel(gier), 128, 128, 40.5, -70.25);
            for (int[] q : new int[][] {{-256, -256}, {0, -128}, {-50, 30}}) {
                int anzahl = Drehung.region(lage, q[0], q[1], 128, form, puffer);
                for (int i = 0; i < anzahl; i++) {
                    float u = puffer.uv()[2 * i], v = puffer.uv()[2 * i + 1];
                    assertTrue(u > -1e-4 && u < 1 + 1e-4 && v > -1e-4 && v < 1 + 1e-4, "UV " + u + ", " + v);
                    double bx = q[0] + u * 128, by = q[1] + v * 128;
                    assertEquals(puffer.ecken()[2 * i], lage.x(bx, by), 1e-2, "Gier " + gier);
                    assertEquals(puffer.ecken()[2 * i + 1], lage.y(bx, by), 1e-2, "Gier " + gier);
                }
            }
        }
        // Gier 180, ganz in der Form: das Quadrat selbst, die UV von 0 bis 1.
        Drehung.Lage norden = Drehung.Lage.von(Drehung.winkel(180), 128, 128, 0, 0);
        assertEquals(4, Drehung.region(norden, -20, -20, 40, Drehung.rechteck(0, 0, 256, 256), puffer));
        assertArrayEquals(new float[] {108, 108, 108, 148, 148, 148, 148, 108}, java.util.Arrays.copyOf(puffer.ecken(), 8), 1e-4f);
        assertArrayEquals(new float[] {0, 0, 0, 1, 1, 1, 1, 0}, java.util.Arrays.copyOf(puffer.uv(), 8), 1e-6f);
    }

    @Test
    void regionenAuchNegativ() {
        // ±20 um den Spieler bei (10, 10) im Bild, links und oben −300, Regionen zu 128 Pixeln: nur Region −3.
        Drehung.Lage lage = Drehung.Lage.von(0, 0, 0, 10, 10);
        assertArrayEquals(new int[] {-3, -3, -3, -3}, Drehung.regionen(lage, 20, -300, -300, 128));
        assertArrayEquals(new int[] {-1, 0, -1, 0}, Drehung.regionen(lage, 20, 0, 0, 128));
    }

    /** Liegt (x, y) im konvexen Vieleck p, gleich welcher Umlaufsinn? */
    private static boolean drinnen(float[] p, double x, double y) {
        int m = p.length / 2, plus = 0, minus = 0;
        for (int i = 0; i < m; i++) {
            int j = (i + 1) % m;
            double kreuz = (p[2 * j] - p[2 * i]) * (y - p[2 * i + 1]) - (p[2 * j + 1] - p[2 * i + 1]) * (x - p[2 * i]);
            plus += kreuz > 0 ? 1 : 0;
            minus += kreuz < 0 ? 1 : 0;
        }
        return plus == 0 || minus == 0;
    }

    @Test
    void blickrichtungLiegtOben() {
        // Gier 180 Norden, 0 Süden, 90 Westen, 270 Osten: Ein Punkt 10 Pixel in Blickrichtung liegt 10 über der Mitte.
        double[][] blick = {{180, 0, -10}, {0, 0, 10}, {90, -10, 0}, {270, 10, 0}};
        for (double[] b : blick) {
            Drehung.Lage lage = Drehung.Lage.von(Drehung.winkel((float) b[0]), 100, 50, 0, 0);
            assertEquals(100, lage.x(b[1], b[2]), 1e-9, "Gier " + b[0]);
            assertEquals(40, lage.y(b[1], b[2]), 1e-9, "Gier " + b[0]);
        }
        // Nach Norden dreht nichts.
        Drehung.Lage norden = Drehung.Lage.von(Drehung.winkel(180), 100, 50, 7, 3);
        assertEquals(100 + 5, norden.x(12, 3), 1e-9);
        assertEquals(50 + 4, norden.y(7, 7), 1e-9);
    }

    @Test
    void zurueckInsBild() {
        Drehung.Lage lage = Drehung.Lage.von(Drehung.winkel(33.3f), 200, 120, 64.5, 70.25);
        for (double[] p : new double[][] {{0, 0}, {64.5, 70.25}, {-40, 300}, {500, -7}}) {
            double sx = lage.x(p[0], p[1]), sy = lage.y(p[0], p[1]);
            assertEquals(p[0], lage.bildX(sx, sy), 1e-9);
            assertEquals(p[1], lage.bildY(sx, sy), 1e-9);
        }
        // Der Spieler liegt auf der Mitte.
        assertEquals(200, lage.x(64.5, 70.25), 1e-9);
        assertEquals(120, lage.y(64.5, 70.25), 1e-9);
    }

    @Test
    void schnittKonvexerVielecke() {
        float[] a = new float[2 * 12], b = new float[2 * 12];
        float[] quadrat = Drehung.rechteck(0, 0, 10, 10);
        int n = Drehung.schneide(quadrat, 4, Drehung.rechteck(5, 5, 15, 15), 4, a, b);
        assertEquals(25, flaeche(a, n), 1e-4);
        // Ganz drinnen bleibt es, ganz draussen fällt es weg.
        n = Drehung.schneide(quadrat, 4, Drehung.rechteck(-1, -1, 11, 11), 4, a, b);
        assertEquals(100, flaeche(a, n), 1e-4);
        assertEquals(0, Drehung.schneide(quadrat, 4, Drehung.rechteck(20, 20, 30, 30), 4, a, b));
        // Der Umlaufsinn der Form spielt keine Rolle.
        float[] verkehrt = {5, 5, 15, 5, 15, 15, 5, 15};
        n = Drehung.schneide(quadrat, 4, verkehrt, 4, a, b);
        assertEquals(25, flaeche(a, n), 1e-4);
    }

    @Test
    void kreisDecktDenKreis() {
        float[] kreis = Drehung.kreis(10, 20, 50);
        assertEquals(2 * Drehung.ECKEN, kreis.length);
        for (int i = 0; i < Drehung.ECKEN; i++) {
            int j = (i + 1) % Drehung.ECKEN;
            // Die Mitte jeder Kante liegt auf dem Kreis, die Ecken aussen.
            double mx = (kreis[2 * i] + kreis[2 * j]) / 2.0 - 10, my = (kreis[2 * i + 1] + kreis[2 * j + 1]) / 2.0 - 20;
            assertEquals(50, Math.hypot(mx, my), 1e-3);
            assertTrue(Math.hypot(kreis[2 * i] - 10, kreis[2 * i + 1] - 20) > 50);
        }
        // Ein gedrehtes Quadrat, mit dem Kreis geschnitten, bleibt im Vieleck um den Kreis.
        Drehung.Lage lage = Drehung.Lage.von(0.7, 10, 20, 0, 0);
        float[] gedreht = new float[8];
        double[][] ecken = {{-60, -60}, {-60, 60}, {60, 60}, {60, -60}};
        for (int i = 0; i < 4; i++) {
            gedreht[2 * i] = (float) lage.x(ecken[i][0], ecken[i][1]);
            gedreht[2 * i + 1] = (float) lage.y(ecken[i][0], ecken[i][1]);
        }
        float[] a = new float[2 * (4 + Drehung.ECKEN)], b = new float[2 * (4 + Drehung.ECKEN)];
        int n = Drehung.schneide(gedreht, 4, kreis, Drehung.ECKEN, a, b);
        double aussen = 50 / Math.cos(Math.PI / Drehung.ECKEN);
        for (int i = 0; i < n; i++) {
            assertTrue(Math.hypot(a[2 * i] - 10, a[2 * i + 1] - 20) <= aussen + 1e-3, "Ecke " + i);
        }
        assertTrue(flaeche(a, n) >= Math.PI * 50 * 50);
    }

    @Test
    void faecherAusVierecken() {
        List<Integer> ecken = new ArrayList<>();
        Drehung.faecher(6, ecken::add);
        assertEquals(List.of(0, 1, 2, 2, 0, 2, 3, 3, 0, 3, 4, 4, 0, 4, 5, 5), ecken);
    }

    @Test
    void gedrehteLinienBleibenInDerForm() {
        // Linien alle 32 Pixel über 300 × 300, um 30° gedreht, mit dem Kreis um (150, 150), Radius 100, geschnitten.
        Gitter.Linien l = Minimap.linien(300, 5, 9, 32, 2, Minimap.laeufe(300, false));
        Drehung.Lage lage = Drehung.Lage.von(Math.toRadians(30), 150, 150, 150, 150);
        float[] kreis = Drehung.kreis(150, 150, 100);
        double aussen = 100 / Math.cos(Math.PI / Drehung.ECKEN);
        int[] vielecke = {0};
        Gitter.gedreht(2, l, 0, 0, lage, kreis, (ecken, anzahl) -> {
            vielecke[0]++;
            for (int i = 0; i < anzahl; i++) {
                assertTrue(Math.hypot(ecken[2 * i] - 150, ecken[2 * i + 1] - 150) <= aussen + 1e-3);
            }
        });
        assertTrue(vielecke[0] > 0);
    }

    @Test
    void markenAufDerMitteDerBaender() {
        // Rund auf dem Kreis durch die Mitte der Bänder, eckig auf dem Quadrat; 30° gedreht.
        double ux = Math.sin(Math.toRadians(30)), uy = -Math.cos(Math.toRadians(30));
        double[] rund = Skin.marke(10, 20, 128, 2, true, ux, uy);
        assertEquals(63, Math.hypot(rund[0] - 74, rund[1] - 84), 1e-9);
        double[] eckig = Skin.marke(10, 20, 128, 2, false, ux, uy);
        assertEquals(63, Math.max(Math.abs(eckig[0] - 74), Math.abs(eckig[1] - 84)), 1e-9);
        assertEquals(Skin.NORDEN, Skin.markeFuer(true, 1, 0));
        assertEquals(Skin.MARKE, Skin.markeFuer(false, 0, 1));
        assertEquals(Skin.MARKE_QUER, Skin.markeFuer(false, 1, 0));
        assertEquals(Skin.MARKE, Skin.markeFuer(false, 1, 1));
    }

    @Test
    void eckigGedrehtReichtWeiter() {
        assertEquals(128, Minimap.sicht(128, false, false));
        assertEquals(128, Minimap.sicht(128, true, true));
        assertEquals(182, Minimap.sicht(128, true, false));
        assertTrue(Minimap.reichweite(1, Minimap.sicht(128, true, false)) > Minimap.reichweite(1, 128));
    }

    @Test
    void markeGedrehtInBlickrichtung() {
        // Blick nach Süden: Ein Wegpunkt 20 Blöcke südlich liegt über der Mitte, bei Zoom 1 und GUI-Massstab 1 20 Einheiten.
        Minimap.Rahmen r = new Minimap.Rahmen(0, 0, 128);
        int k = 1, zoom = 1, n = 128;
        int links = Minimap.ecke(0.5, 0.5, 1f, zoom, k, n), oben = Minimap.ecke(0.5, 0.5, 1f, zoom, k, n);
        Drehung.Lage lage = Minimap.lage(r, 0.5, 0.5, 0, zoom, k, links, oben);
        float[] m = Minimap.marke(r, 0.5, 20.5, links, oben, k, zoom, false, 60, true, lage);
        assertEquals(64, m[0], 1e-4);
        assertEquals(44, m[1], 1e-4);
    }

    private static double flaeche(float[] p, int n) {
        double f = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            f += (double) p[2 * i] * p[2 * j + 1] - (double) p[2 * j] * p[2 * i + 1];
        }
        return Math.abs(f) / 2;
    }
}
