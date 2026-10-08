package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Wie die Vollbildkarte Kacheln auf den Schirm legt. Siehe docs/vollbildkarte.md. */
class KartenblickTest {

    @Test
    void hinUndZurueck() {
        Kartenblick blick = new Kartenblick(256, 0, 6, 5);
        blick.mx = 1000;
        blick.mz = -300;
        blick.lupe = 2;
        for (double sx : new double[] {0, 37.5, 400, 853}) {
            assertEquals(sx, blick.schirmX(blick.basisX(sx, 854), 854), 1e-9);
            assertEquals(sx, blick.schirmY(blick.basisZ(sx, 480), 480), 1e-9);
        }
        // Stufe 5 unter maxZoom 6: ein Pixel der Stufe sind zwei der Basis, die Lupe halbiert.
        assertEquals(1000 + 10, blick.basisX(427 + 10, 854), 1e-9);
    }

    @Test
    void markenAufDemRasterDerKacheln() {
        // Die Kacheln liegen mit Mth.floor auf ganzen Einheiten; eine Marke rückt mit ihnen, nicht mit der Mitte.
        Kartenblick blick = new Kartenblick(256, 0, 6, 6);
        for (double frac : new double[] {0, 0.25, 0.75}) {
            blick.mx = 1000 + frac;
            double kante = Math.floor(blick.schirmX(3 * 256, 854));
            assertEquals(kante + 10.5, blick.rasterX(3 * 256 + 10.5, 854), 1e-9, "frac " + frac);
            assertEquals(Math.floor(blick.schirmY(0, 480)) + 7, blick.rasterY(7, 480), 1e-9);
        }
    }

    @Test
    void markeWeichtDenKnoepfenAus() {
        // Schirm 400 × 200, Rand 14, Knöpfe ab x 306 bis y 48, halbe Marke 4.
        double[] oben = Kartenblick.marke(1700, -900, 400, 200, 14, 4, 306, 48);
        assertEquals(14, oben[1], 1e-9);
        assertTrue(oben[0] + 4 <= 306, "oben links neben die Knöpfe: " + oben[0]);
        double[] rechts = Kartenblick.marke(5000, -1200, 400, 200, 14, 4, 306, 48);
        assertEquals(400 - 14, rechts[0], 1e-9);
        assertTrue(rechts[1] - 4 >= 48, "rechts unter die Knöpfe: " + rechts[1]);
        // Drinnen bleibt eine Marke, wo sie ist, auch unter den Knöpfen.
        assertArrayEquals(new double[] {350, 30}, Kartenblick.marke(350, 30, 400, 200, 14, 4, 306, 48), 1e-9);
        // Fern der Knöpfe gilt nur der Rand.
        assertArrayEquals(new double[] {14, 100}, Kartenblick.marke(-5000, 100, 400, 200, 14, 4, 306, 48), 1e-9);
    }

    @Test
    void zoomUeberStufenUndLupe() {
        Kartenblick blick = new Kartenblick(256, 2, 6, 4);
        blick.ferner();
        blick.ferner();
        blick.ferner();
        assertEquals(2, blick.zoom);
        for (int i = 0; i < 5; i++) {
            blick.naeher();
        }
        assertEquals(4, blick.zoom);
        assertEquals(4, blick.lupe);
        blick.ferner();
        assertEquals(4, blick.zoom);
        assertEquals(2, blick.lupe);
    }

    @Test
    void sichtbareKacheln() {
        Kartenblick blick = new Kartenblick(256, 0, 6, 6);
        assertArrayEquals(new int[] {-1, 1, -1, 0}, blick.kacheln(512, 480));
        blick.mx = 300;
        blick.mz = 256 * 3;
        blick.zoom = 5;
        // Auf Stufe 5 deckt eine Kachel 512 Pixel der Basis: x von -212 bis 812, z von 288 bis 1248.
        assertArrayEquals(new int[] {-1, 1, 0, 2}, blick.kacheln(512, 480));
    }

    @Test
    void platzhalterAusGroeberenStufen() {
        // Eine Stufe gröber: Kachel (5, 3) ist das rechte untere Viertel von (2, 1).
        assertArrayEquals(new int[] {2, 1, 128, 128, 128}, Kartenblick.grob(5, 3, 1, 256));
        // Zwei Stufen gröber und negativ: (-1, -3) liegt in (-1, -1) bei Viertel 3 und 1 von 4.
        assertArrayEquals(new int[] {-1, -1, 192, 64, 64}, Kartenblick.grob(-1, -3, 2, 256));
        // Unter 1 Pixel gibt es keinen Ausschnitt.
        assertNull(Kartenblick.grob(0, 0, 9, 256));
    }

    @Test
    void schiebenFolgtDerMaus() {
        Kartenblick blick = new Kartenblick(256, 0, 6, 4);
        blick.schiebe(10, -20);
        // Stufe 4: ein Pixel der Stufe sind vier der Basis; der Inhalt folgt der Maus.
        assertEquals(-40, blick.mx, 1e-9);
        assertEquals(80, blick.mz, 1e-9);
    }
}
