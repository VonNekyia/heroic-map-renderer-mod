package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Die drehende Minimap: Winkel, Drehung, Schnitt mit der Form, Marken. Siehe docs/minimap.md, „Drehen“. */
class DrehungTest {

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
