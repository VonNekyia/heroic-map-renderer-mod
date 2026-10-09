package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Ränder, Kreise und der Schnitt der Formen. Siehe docs/ebenen.md, „Flächen, Kreise und Linien“. */
class FormenTest {

    private static Formen.Sammler sammler() {
        return new Formen.Sammler(Drehung.rechteck(-1000, -1000, 1000, 1000));
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
    void durchgezogenJeStreckeEinStreifen() {
        Formen.Sammler s = sammler();
        Formen.streifen(s, new double[] {0, 0, 10, 0, 10, 10}, 3, false, 2, 0, 0);
        assertEquals(2, s.n);
        Formen.Sammler zu = sammler();
        Formen.streifen(zu, new double[] {0, 0, 10, 0, 10, 10}, 3, true, 2, 0, 0);
        assertEquals(3, zu.n);
    }

    @Test
    void strichelnUeberDieEcke() {
        // 20 lang, Strich 8 und Lücke 6: 0–8 auf der ersten Strecke, Lücke bis 14, dann 14–20 auf der zweiten.
        Formen.Sammler s = sammler();
        Formen.streifen(s, new double[] {0, 0, 10, 0, 10, 10}, 3, false, 2, 8, 6);
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
    }

    @Test
    void nebenDemSchnittNurMuster() {
        // Zwei Strecken ganz draussen, 7 und 50 lang, schieben das Muster um 57, also 1 in den Strich hinein.
        // Die dritte, von x -43 nach 57 bei y 0, trägt Striche bei x [-43, -36), [-30, -22), …, [-2, 6), [12, 20), …
        Formen.Sammler s = new Formen.Sammler(Drehung.rechteck(0, -10, 100, 10));
        Formen.streifen(s, new double[] {-50, 50, -43, 50, -43, 0, 57, 0}, 4, false, 2, 8, 6);
        assertEquals(5, s.n);
        // Der erste sichtbare Strich endet bei 6; ohne das Weiterschieben endete er bei 7.
        float xmax = -Float.MAX_VALUE;
        for (int i = 0; i < s.anzahl[0]; i++) {
            xmax = Math.max(xmax, s.ecken[2 * i]);
        }
        assertEquals(6, xmax, 1e-4);
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
    }
}
