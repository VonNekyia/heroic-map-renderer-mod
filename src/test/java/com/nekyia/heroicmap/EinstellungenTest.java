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
