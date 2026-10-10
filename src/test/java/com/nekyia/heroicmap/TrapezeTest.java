package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Die Füllung als Trapeze. Siehe docs/ebenen.md, „Flächen, Kreise und Linien“. */
class TrapezeTest {

    /** Die Fläche aller Trapeze; jedes zählt einmal, so zeigt sie Lücken und Doppeltes. */
    private static double flaeche(double[] t) {
        double f = 0;
        for (int i = 0; i < t.length; i += 6) {
            f += ((t[i + 2] - t[i + 1]) + (t[i + 5] - t[i + 4])) / 2 * (t[i + 3] - t[i]);
        }
        return f;
    }

    @Test
    void quadratInBeidenUmlaufsinnen() {
        assertArrayEquals(new double[] {0, 0, 10, 10, 0, 10}, Trapeze.von(List.of(new double[] {0, 0, 10, 0, 10, 10, 0, 10})));
        assertArrayEquals(new double[] {0, 0, 10, 10, 0, 10}, Trapeze.von(List.of(new double[] {0, 0, 0, 10, 10, 10, 10, 0})));
    }

    @Test
    void schraegeKanteGenau() {
        // Das Dreieck aus dem Review: Die Füllung endet genau auf der schrägen Kante, ohne Treppe.
        double[] t = Trapeze.von(List.of(new double[] {0, 0, 100, 0, 0, 100}));
        assertEquals(5000, flaeche(t), 1e-9);
        for (int i = 0; i < t.length; i += 6) {
            assertEquals(100, t[i + 2] + t[i], 1e-9);
            assertEquals(100, t[i + 5] + t[i + 3], 1e-9);
        }
        assertEquals(1, t.length / 6);
    }

    @Test
    void lFormUndLoch() {
        double[] l = Trapeze.von(List.of(new double[] {0, 0, 20, 0, 20, 10, 10, 10, 10, 30, 0, 30}));
        assertEquals(20 * 10 + 10 * 20, flaeche(l), 1e-9);
        // Ein Loch nach gerade/ungerade, in beiden Umlaufsinnen gleich.
        double[] loch = Trapeze.von(List.of(new double[] {0, 0, 30, 0, 30, 30, 0, 30}, new double[] {10, 10, 20, 10, 20, 20, 10, 20}));
        double[] andersrum = Trapeze.von(List.of(new double[] {0, 0, 30, 0, 30, 30, 0, 30}, new double[] {10, 10, 10, 20, 20, 20, 20, 10}));
        assertEquals(900 - 100, flaeche(loch), 1e-9);
        assertEquals(900 - 100, flaeche(andersrum), 1e-9);
    }

    @Test
    void ueberlappendNachGeradeUngerade() {
        // Zwei Quadrate, die sich zu 5 × 5 überlappen: Die Überlappung fällt heraus, die Kanten kreuzen sich.
        double[] t = Trapeze.von(List.of(new double[] {0, 0, 10, 0, 10, 10, 0, 10}, new double[] {5, 5, 15, 5, 15, 15, 5, 15}));
        assertEquals(100 + 100 - 2 * 25, flaeche(t), 1e-9);
        // Eine Schleife, deren Kanten sich in der Mitte kreuzen: zwei Dreiecke zu je 25.
        double[] schleife = Trapeze.von(List.of(new double[] {0, 0, 10, 10, 10, 0, 0, 10}));
        assertEquals(50, flaeche(schleife), 1e-9);
        for (int i = 0; i < schleife.length; i += 6) {
            // Konvex: links nie rechts von rechts, oben wie unten.
            assertTrue(schleife[i + 1] <= schleife[i + 2] + 1e-9 && schleife[i + 4] <= schleife[i + 5] + 1e-9);
        }
    }

    @Test
    void stueckeMitDenPunkten() {
        // Ein Kreis aus 256 Punkten: Die Spannen laufen über die Bänder weiter, solange ihre Kanten bleiben.
        double[] kreis = new double[512];
        for (int i = 0; i < 256; i++) {
            kreis[2 * i] = 1000 * Math.cos(2 * Math.PI * i / 256);
            kreis[2 * i + 1] = 1000 * Math.sin(2 * Math.PI * i / 256);
        }
        double[] t = Trapeze.von(List.<double[]>of(kreis));
        assertTrue(t.length / 6 <= 256, "Trapeze " + t.length / 6);
        double soll = 0;
        for (int i = 0; i < 256; i++) {
            int j = (i + 1) % 256;
            soll += kreis[2 * i] * kreis[2 * j + 1] - kreis[2 * j] * kreis[2 * i + 1];
        }
        assertEquals(Math.abs(soll) / 2, flaeche(t), 1e-6);
        // Eine Treppe wie ein Claim aus Chunks, 200 Stufen: weniger Trapeze als Punkte, auch bei grossen Koordinaten.
        List<Double> treppe = new ArrayList<>();
        double x0 = 29_000_000;
        for (int i = 0; i < 200; i++) {
            treppe.addAll(List.of(x0 + 16 * i, 16.0 * i, x0 + 16 * i + 16, 16.0 * i));
        }
        treppe.addAll(List.of(x0 + 3200, 3200.0, x0, 3200.0));
        double[] r = treppe.stream().mapToDouble(Double::doubleValue).toArray();
        double[] s = Trapeze.von(List.<double[]>of(r));
        assertTrue(s.length / 6 <= r.length / 2, "Trapeze " + s.length / 6);
        assertEquals(16 * 16 * (200 * 201 / 2.0), flaeche(s), 1e-3);
    }

    @Test
    void zuAufwendigOhneFuellung() {
        // Ein Kamm aus 2000 Zinken mit verschiedenen Enden: viele Kanten in jedem Band, über dem Deckel der Arbeit.
        int zinken = 2000;
        double[] r = new double[2 * (4 * zinken + 2)];
        int n = 0;
        for (int i = 0; i < zinken; i++) {
            double[] p = {2 * i, 0, 2 * i, 1000 + i, 2 * i + 1, 1000 + i, 2 * i + 1, 0};
            System.arraycopy(p, 0, r, n, 8);
            n += 8;
        }
        r[n++] = 2 * zinken;
        r[n++] = -1;
        r[n++] = 0;
        r[n] = -1;
        long start = System.nanoTime();
        assertNull(Trapeze.von(List.<double[]>of(r)));
        assertTrue(System.nanoTime() - start < 2_000_000_000L, "der Deckel greift früh");
    }
}
