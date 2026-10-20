package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Verkleinern wie die Pyramide des Renderers bei top-north, mit demselben Fall wie dessen Test. Siehe docs/selbst.md, „Pyramide“. */
class PyramideTest {

    /** Derselbe Fall wie naechster_pixel_je_tiefe des Renderers: x und y in Rot und Grün, Alpha 100 + x + 4 · y, (3, 3) durchsichtig. */
    private static int[] bild() {
        int[] b = new int[16];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                int alpha = x == 3 && y == 3 ? 0 : 100 + x + 4 * y;
                b[y * 4 + x] = alpha << 24 | x << 16 | y << 8 | 7;
            }
        }
        return b;
    }

    private static int pixel(int r, int g, int b, int a) {
        return a << 24 | r << 16 | g << 8 | b;
    }

    @Test
    void ungeradeTiefeRechtsUnten() {
        int[] klein = Pyramide.halbiere(bild(), 4, 1);
        assertEquals(pixel(1, 1, 7, 105), klein[0], "von (1, 1)");
        assertEquals(pixel(3, 1, 7, 107), klein[1], "von (3, 1)");
        assertEquals(0, klein[3], "durchsichtig ohne Farbe");
    }

    @Test
    void geradeTiefeLinksOben() {
        int[] klein = Pyramide.halbiere(bild(), 4, 2);
        assertEquals(pixel(2, 0, 7, 102), klein[1], "von (2, 0)");
        assertEquals(pixel(2, 2, 7, 110), klein[3], "von (2, 2)");
    }

    @Test
    void keinMittel() {
        // Weiss und Schwarz je Spalte: ein Pixel davon, kein Grau dazwischen.
        int w = 0xFFFFFFFF, s = 0xFF000000;
        assertArrayEquals(new int[] {s}, Pyramide.halbiere(new int[] {w, s, w, s}, 2, 1));
        assertArrayEquals(new int[] {w}, Pyramide.halbiere(new int[] {w, s, w, s}, 2, 2));
    }

    @Test
    void jedesViertelAusSeinemBlock() {
        // 4 × 4 aus vier einfarbigen Blöcken 2 × 2: Ein falscher Index, etwa y · seite statt 2 · y · seite, mischte Blöcke.
        int a = 0xFF102030, b = 0xFF405060, c = 0xFF708090, d = 0xFFA0B0C0;
        int[] bild = {
            a, a, b, b,
            a, a, b, b,
            c, c, d, d,
            c, c, d, d};
        assertArrayEquals(new int[] {a, b, c, d}, Pyramide.halbiere(bild, 4, 1));
        assertArrayEquals(new int[] {a, b, c, d}, Pyramide.halbiere(bild, 4, 2));
    }
}
