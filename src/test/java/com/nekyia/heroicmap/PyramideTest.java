package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Verkleinern wie die Pyramide des Renderers, mit denselben Fällen wie dessen Tests. Siehe docs/live.md, „Raster“. */
class PyramideTest {

    @Test
    void eineFlaecheBehaeltIhreFarbe() {
        int[] bild = new int[8 * 8];
        Arrays.fill(bild, 0xFF0AC81E);
        int[] klein = Pyramide.halbiere(bild, 8);
        assertEquals(16, klein.length);
        for (int p : klein) {
            assertEquals(0xFF0AC81E, p);
        }
    }

    @Test
    void jederSrgbWertUebersteht() {
        // Jeder Grauwert, je 2 × 2 gleich: Hin nach linear und zurück ändert nichts.
        for (int c = 0; c < 256; c++) {
            int p = 0xFF000000 | c << 16 | c << 8 | c;
            assertEquals(p, Pyramide.halbiere(new int[] {p, p, p, p}, 2)[0], "Grau " + c);
        }
    }

    @Test
    void vormultipliziert() {
        // Ein rotes Pixel, drei durchsichtige mit Schwarz darunter: Die Farbe verwässert nicht.
        assertArrayEquals(new int[] {0x40FF0000}, Pyramide.halbiere(new int[] {0xFFFF0000, 0, 0, 0}, 2));
        assertArrayEquals(new int[] {0}, Pyramide.halbiere(new int[] {0, 0, 0, 0}, 2));
    }

    @Test
    void inLinearemLicht() {
        // Halb Schwarz, halb Weiss: 188, nicht 128.
        int w = 0xFFFFFFFF, s = 0xFF000000;
        assertArrayEquals(new int[] {0xFFBCBCBC}, Pyramide.halbiere(new int[] {w, s, w, s}, 2));
    }

    @Test
    void summiertInFesterReihenfolge() {
        // Derselbe Fall wie verkleinern_summiert_in_fester_reihenfolge des Renderers.
        int[] bild = {0xFF765D6B, 0xFF4C160E, 0xFF6019CA, 0xFF52D5E1};
        assertArrayEquals(new int[] {0xFF5F7BA5}, Pyramide.halbiere(bild, 2));
    }

    @Test
    void zweimalHalbiert() {
        int[] bild = new int[4 * 4];
        Arrays.fill(bild, 0xFF123456);
        assertArrayEquals(new int[] {0xFF123456}, Pyramide.verkleinere(bild, 4, 2));
        assertEquals(bild, Pyramide.verkleinere(bild, 4, 0));
    }
}
