package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Die drehende Minimap: Winkel, Drehung, Schnitt mit der Form, Marken. Siehe docs/minimap.md, „Drehen“. */
class DrehungTest {

    @Test
    void mitRahmenDecktDerRingDenRand() {
        // Der Ring ist in Einheiten gestuft: Jede Pixelmitte einer Einheit innerhalb der Bänder liegt im Vieleck,
        // auch nahe den Diagonalen; keine einer Einheit ausserhalb des Rings.
        for (int k : new int[] {1, 2, 3, 4}) {
            for (int seite : new int[] {Minimap.KLEINSTE, Minimap.GROESSE, 200, Minimap.GROESSTE}) {
                for (int baender : new int[] {2, 3, 5}) {
                    float[] form = Minimap.schnitt(0, 0, seite, k, true, baender);
                    for (int y = -2; y < seite + 2; y++) {
                        for (int x = -2; x < seite + 2; x++) {
                            int band = Skin.bandRund(x, y, seite);
                            if (band < -2 || band > baender + 1 || band >= 0 && band < baender) {
                                continue;
                            }
                            for (int py = 0; py < k; py++) {
                                for (int px = 0; px < k; px++) {
                                    assertEquals(band >= baender, drinnen(form, x * k + px + 0.5, y * k + py + 0.5),
                                            "k " + k + ", Seite " + seite + ", Bänder " + baender + ", Einheit " + x + ", " + y);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void ohneRahmenDecktDerUmrissDenRand() {
        // Der Umriss ist ein Ring in Pixeln, k breit, je Zeile auf ganze Pixel: Jede Pixelmitte innerhalb liegt im Vieleck,
        // keine ausserhalb des Umrisses.
        for (int k : new int[] {1, 2, 3, 4}) {
            for (int seite : new int[] {Minimap.KLEINSTE, Minimap.GROESSE, 200, Minimap.GROESSTE}) {
                int n = seite * k, m = n + 2 * k;
                float[] form = Minimap.schnitt(0, 0, seite, k, true, 0);
                for (int y = -k - 1; y < n + k + 1; y++) {
                    int a = Minimap.sehne(n, y), b = Minimap.sehne(m, y + k) - k;
                    for (int x = -k - 1; x < n + k + 1; x++) {
                        if (Math.hypot(x + 0.5 - n / 2.0, y + 0.5 - n / 2.0) < n / 2.0 - 2) {
                            continue;
                        }
                        boolean karte = y >= 0 && y < n && a <= x && x < n - a;
                        boolean umriss = y >= -k && y < n + k && b <= x && x < n - b;
                        boolean drin = drinnen(form, x + 0.5, y + 0.5);
                        if (karte) {
                            assertTrue(drin, "k " + k + ", Seite " + seite + ", Pixel " + x + ", " + y);
                        } else if (!umriss) {
                            assertFalse(drin, "k " + k + ", Seite " + seite + ", Pixel " + x + ", " + y);
                        }
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
        // ±20 um (10, 10) im Bild, links und oben −300, Regionen zu 128 Pixeln: nur Region −3.
        double[] bereich = {-10, -10, 30, 30};
        assertArrayEquals(new int[] {-3, -3, -3, -3}, Drehung.regionen(bereich, -300, -300, 128));
        assertArrayEquals(new int[] {-1, 0, -1, 0}, Drehung.regionen(bereich, 0, 0, 128));
        // Ungedreht das Bild der Minimap: 384 Pixel ab links 100 berühren die Regionen 0 bis 3.
        assertArrayEquals(new int[] {0, 3, 0, 3}, Drehung.regionen(new double[] {0, 0, 384, 384}, 100, 100, 128));
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
    void linienBleibenInDerForm() {
        // Linien alle 32 Pixel über 300 × 300, ungedreht und um 30° gedreht, mit dem Kreis um (150, 150), Radius 100, geschnitten.
        Gitter.Linien l = Minimap.linien(300, 5, 9, 32, 2);
        float[] kreis = Drehung.kreis(150, 150, 100);
        double aussen = 100 / Math.cos(Math.PI / Drehung.ECKEN);
        for (double grad : new double[] {0, 30}) {
            Drehung.Lage lage = Drehung.Lage.von(Math.toRadians(grad), 150, 150, 150, 150);
            int[] vielecke = {0};
            Gitter.gedreht(2, l, 0, 0, lage, kreis, (ecken, anzahl) -> {
                vielecke[0]++;
                for (int i = 0; i < anzahl; i++) {
                    assertTrue(Math.hypot(ecken[2 * i] - 150, ecken[2 * i + 1] - 150) <= aussen + 1e-3, grad + "°");
                }
            });
            assertTrue(vielecke[0] > 0);
        }
    }

    @Test
    void ungedrehtAufDemRaster() {
        // Ungedreht bildet die Lage das Bild Pixel für Pixel ab; ein Quadrat, mit einem grösseren Quadrat geschnitten, bleibt ganz.
        Drehung.Lage lage = Drehung.Lage.von(0, 40, 60, 0, 0);
        assertEquals(40 + 17, lage.x(17, 5), 0);
        assertEquals(60 + 5, lage.y(17, 5), 0);
        float[] region = Drehung.rechteck(lage.x(-50, -50), lage.y(-50, -50), lage.x(46, 46), lage.y(46, 46));
        float[] a = new float[2 * 8], b = new float[2 * 8];
        int n = Drehung.schneide(region, 4, Drehung.rechteck(40, 60, 40 + 128, 60 + 128), 4, a, b);
        assertEquals(46 * 46, flaeche(a, n), 0);
        for (int i = 0; i < 2 * n; i++) {
            assertEquals(Math.round(a[i]), a[i], 0, "ganze Pixel");
        }
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

    @Test
    void markeGedrehtAufGanzenPixeln() {
        // Gier 30 dreht schräg; bei GUI-Massstab 3 läge eine ungerundete Marke zwischen den Pixeln.
        Minimap.Rahmen r = new Minimap.Rahmen(5, 7, 128);
        int k = 3, zoom = 4, n = 384;
        int links = Minimap.ecke(10.3, 10.3, 1f, zoom, k, n), oben = Minimap.ecke(-4.7, -4.7, 1f, zoom, k, n);
        Drehung.Lage lage = Minimap.lage(r, 10.3, -4.7, 30, zoom, k, links, oben);
        // Innen und weit draussen, also am Rand geklemmt.
        for (double[] ort : new double[][] {{12.5, -1.5}, {5.5, -9.5}, {200.5, -150.5}}) {
            float[] m = Minimap.marke(r, ort[0], ort[1], links, oben, k, zoom, true, 50, true, lage);
            assertEquals(Math.rint(m[0] * k), m[0] * k, 1e-3);
            assertEquals(Math.rint(m[1] * k), m[1] * k, 1e-3);
        }
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
