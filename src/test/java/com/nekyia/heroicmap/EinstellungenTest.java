package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Wo der Regler „Zoom“ neben der Minimap im Menü steht (mod#139). Siehe docs/minimap.md, „Bedienung“. */
class EinstellungenTest {

    private static final int RAND = Minimap.RAND, B = Einstellungen.ZOOM_BREITE;

    @Test
    void linksSonstRechtsSonstInnen() {
        // Platz links: im Abstand des Randes daneben, so hoch wie die Minimap.
        assertArrayEquals(new int[] {100 - RAND - B, 30, 128}, Einstellungen.zoomLage(new Minimap.Rahmen(100, 30, 128), RAND, 480));
        // Links genau so viel Platz, dass er mit dem Abstand des Randes zum Schirm passt.
        int knapp = 2 * RAND + B;
        assertEquals(RAND, Einstellungen.zoomLage(new Minimap.Rahmen(knapp, 0, 64), RAND, 480)[0]);
        // Eine Einheit weniger: rechts daneben.
        assertEquals(knapp - 1 + 64 + RAND, Einstellungen.zoomLage(new Minimap.Rahmen(knapp - 1, 0, 64), RAND, 480)[0]);
        // Am linken Rand: rechts.
        assertArrayEquals(new int[] {RAND + 128 + RAND, RAND, 128}, Einstellungen.zoomLage(new Minimap.Rahmen(RAND, RAND, 128), RAND, 480));
        // Auf keiner Seite Platz, ein fast quadratischer Schirm: innen am linken Rand.
        assertArrayEquals(new int[] {RAND, RAND, 232}, Einstellungen.zoomLage(new Minimap.Rahmen(RAND, RAND, 232), RAND, 240));
    }

    @Test
    void aufloesungHinterDenKoordinaten() {
        int h = Einstellungen.REGLER_HOEHE;
        Minimap.Rahmen r = new Minimap.Rahmen(100, RAND, 128);
        // Die Zeile unter der Minimap: der Regler 2 darunter, so breit wie die Minimap.
        int[] unten = {120, RAND + 128 + RAND + 2, 80, 9};
        assertArrayEquals(new int[] {100, unten[1] + 9 + 2, 128}, Einstellungen.aufloesungLage(r, unten, RAND, 480));
        // Unter der Zeile kein Platz mehr: über der Minimap, wenn dort Platz ist, sonst innen an ihrem unteren Rand.
        Minimap.Rahmen mitte = new Minimap.Rahmen(100, 60, 128);
        int[] zeile = {120, 60 + 128 + RAND + 2, 80, 9};
        int hoehe = zeile[1] + 9 + 2 + h - 1;
        assertEquals(60 - RAND - 2 - h, Einstellungen.aufloesungLage(mitte, zeile, RAND, hoehe)[1]);
        assertEquals(RAND + 128 - h, Einstellungen.aufloesungLage(r, unten, RAND, unten[1] + 9 + 2 + h - 1)[1]);
        // Die Zeile über der Minimap, die unten steht: der Regler über der Zeile.
        Minimap.Rahmen tief = new Minimap.Rahmen(100, 300 - RAND - 128, 128);
        int[] oben = {120, tief.y() - RAND - 2 - 9, 80, 9};
        assertEquals(oben[1] - 2 - h, Einstellungen.aufloesungLage(tief, oben, RAND, 300)[1]);
        // Ohne Zeile, etwa vor dem ersten Frame: unter der Minimap im Abstand des Randes.
        assertEquals(RAND + 128 + RAND + 2, Einstellungen.aufloesungLage(r, null, RAND, 480)[1]);
        // Die Zeile liegt unten auf einer Minimap, die fast die ganze Höhe nimmt: innen, über der Zeile.
        Minimap.Rahmen gross = new Minimap.Rahmen(100, RAND, 232);
        int[] drauf = {120, 240 - 9, 80, 9};
        assertEquals(drauf[1] - 2 - h, Einstellungen.aufloesungLage(gross, drauf, RAND, 240)[1]);
    }

    @Test
    void lUmDieMinimapBeiJedemMassstab() {
        // Wie ganzAufDemSchirmBeiJedemMassstab, dazu die Zeile der Koordinaten, 60 breit, wie sie die Minimap legt.
        String[] namen = {"Nordpfeil", "Zoom", "Form", "Auflösung", "Raster"};
        int s = Einstellungen.SYMBOL;
        for (int[] fenster : new int[][] {{854, 480}, {1280, 720}, {1920, 1080}}) {
            for (int gs = 1; gs <= 4; gs++) {
                int b = fenster[0] / gs, h = fenster[1] / gs;
                int groesste = Math.min(Minimap.GROESSTE, Math.min(b, h) - 2 * RAND);
                for (int seite : new int[] {Minimap.KLEINSTE, Minimap.GROESSE, groesste}) {
                    if (seite < Minimap.KLEINSTE || seite > groesste) {
                        continue;
                    }
                    for (int x : new int[] {RAND, (b - seite) / 2, b - RAND - seite}) {
                        for (int y : new int[] {RAND, (h - seite) / 2, h - RAND - seite}) {
                            Minimap.Rahmen r = new Minimap.Rahmen(x, y, seite);
                            int[] wo = Minimap.koordinatenLage(r, RAND, RAND, b, h, 60, 9);
                            int[] kasten = {wo[0], wo[1], 60, 9};
                            int[][] l = Einstellungen.lLage(r, kasten, RAND, b, h);
                            String fall = b + " × " + h + ", " + r;
                            int[] z = Einstellungen.zoomLage(r, RAND, b), a = Einstellungen.aufloesungLage(r, kasten, RAND, h);
                            boolean innen = z[0] == r.x() || a[1] >= r.y() && a[1] < r.y() + seite;
                            for (int i = 0; i < l.length; i++) {
                                int[] w = l[i];
                                assertTrue(w[0] >= 0 && w[1] >= 0 && w[0] + w[2] <= b && w[1] + w[3] <= h, namen[i] + " nicht ganz auf dem Schirm: " + fall);
                                assertTrue(!schneidet(w, kasten), namen[i] + " auf der Zeile der Koordinaten: " + fall);
                                assertTrue(innen || !schneidet(w, new int[] {r.x(), r.y(), seite, seite}), namen[i] + " auf der Minimap: " + fall);
                                for (int j = i + 1; j < l.length; j++) {
                                    assertTrue(!schneidet(w, l[j]), namen[i] + " auf " + namen[j] + ": " + fall);
                                }
                            }
                            // Die Form genau in der Ecke zwischen Spalte und Reihe; Zoom und Auflösung behalten Platz für ihre Rasten.
                            assertEquals(l[1][0], l[2][0], "Form nicht in der Spalte: " + fall);
                            assertEquals(l[3][1], l[2][1], "Form nicht in der Reihe: " + fall);
                            assertTrue(l[1][3] >= 2 * s && l[3][2] >= 2 * s, "Regler zu klein: " + fall);
                        }
                    }
                }
            }
        }
    }

    @Test
    void lZeigtDenNordpfeilFernDerReihe() {
        // Oben rechts: Spalte links, Reihe unter der Zeile; der Nordpfeil oben in der Spalte, das Raster rechts in der Reihe.
        Minimap.Rahmen r = new Minimap.Rahmen(300, RAND, 128);
        int[] kasten = {330, RAND + 128 + RAND + 2, 60, 9};
        int[][] l = Einstellungen.lLage(r, kasten, RAND, 480, 270);
        int s = Einstellungen.SYMBOL + 2;
        assertArrayEquals(new int[] {300 - RAND - B, RAND, 20, 20}, l[0]);
        assertArrayEquals(new int[] {300 - RAND - B, RAND + s, B, 128 - s}, l[1]);
        assertArrayEquals(new int[] {300 - RAND - B, kasten[1] + 9 + 2, 20, 20}, l[2]);
        assertArrayEquals(new int[] {300, kasten[1] + 9 + 2, 128 - s, 20}, l[3]);
        assertArrayEquals(new int[] {300 + 128 - 20, kasten[1] + 9 + 2, 20, 20}, l[4]);
        // Unten links: Spalte rechts, Reihe über der Zeile; der Nordpfeil unten, das Raster links.
        Minimap.Rahmen u = new Minimap.Rahmen(RAND, 270 - RAND - 128, 128);
        int[] oben = {RAND, u.y() - RAND - 2 - 9, 60, 9};
        int[][] m = Einstellungen.lLage(u, oben, RAND, 480, 270);
        int zx = RAND + 128 + RAND;
        assertArrayEquals(new int[] {zx, u.y() + 128 - 20, 20, 20}, m[0]);
        assertArrayEquals(new int[] {zx, u.y(), B, 128 - s}, m[1]);
        assertArrayEquals(new int[] {RAND, oben[1] - 2 - 20, 20, 20}, m[4]);
        assertArrayEquals(new int[] {RAND + s, oben[1] - 2 - 20, 128 - s, 20}, m[3]);
    }

    private static boolean schneidet(int[] a, int[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    @Test
    void ganzAufDemSchirmBeiJedemMassstab() {
        // Fenster 854 × 480, 1280 × 720 und 1920 × 1080, GUI-Massstab 1 bis 4; die Minimap in jeder Ecke, in der Mitte und
        // mittig am Rand, klein, mittel und so gross, wie sie der Schirm erlaubt.
        for (int[] fenster : new int[][] {{854, 480}, {1280, 720}, {1920, 1080}}) {
            for (int gs = 1; gs <= 4; gs++) {
                int b = fenster[0] / gs, h = fenster[1] / gs;
                int groesste = Math.min(Minimap.GROESSTE, Math.min(b, h) - 2 * RAND);
                for (int s : new int[] {Minimap.KLEINSTE, Minimap.GROESSE, groesste}) {
                    // Grösser, als der Schirm erlaubt, wird die Minimap nie (Minimap.rahmen).
                    if (s < Minimap.KLEINSTE || s > groesste) {
                        continue;
                    }
                    int[] xs = {RAND, (b - s) / 2, b - RAND - s}, ys = {RAND, (h - s) / 2, h - RAND - s};
                    for (int x : xs) {
                        for (int y : ys) {
                            Minimap.Rahmen r = new Minimap.Rahmen(x, y, s);
                            int[] z = Einstellungen.zoomLage(r, RAND, b);
                            String fall = b + " × " + h + ", " + r + " → " + z[0];
                            assertTrue(z[0] >= 0 && z[0] + B <= b && z[1] >= 0 && z[1] + z[2] <= h, "nicht ganz auf dem Schirm: " + fall);
                            boolean daneben = z[0] + B + RAND <= r.x() || z[0] >= r.x() + s + RAND;
                            // Innen nur, wenn weder links noch rechts Platz ist.
                            boolean platz = r.x() - RAND - B >= RAND || r.x() + s + RAND + B <= b - RAND;
                            assertTrue(daneben || !platz && z[0] == r.x(), "auf der Minimap, obwohl daneben Platz ist: " + fall);
                        }
                    }
                }
            }
        }
    }
}
