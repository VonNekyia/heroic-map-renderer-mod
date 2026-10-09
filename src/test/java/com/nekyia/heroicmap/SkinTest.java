package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Die Rahmen: Palette, Bänder, Ring und Maske, Ornamente. Siehe docs/rahmen.md. */
class SkinTest {

    @Test
    void paletteMitKommentarenUndLichtUndSchatten() {
        Skin s = Skin.lies("t", "// Kopf\n#181717\n#A8A8A8 #4F4D4D  // Licht und Schatten\n\n#D8D8D8\n", "name.de=X\nschatten=nein\n");
        assertEquals(3, s.baender());
        assertEquals(0xFF181717, s.licht[0]);
        assertEquals(0xFF181717, s.schatten[0]);
        assertEquals(0xFFA8A8A8, s.licht[1]);
        assertEquals(0xFF4F4D4D, s.schatten[1]);
        assertFalse(s.mitSchatten);
        assertTrue(Skin.lies("t", "#000000", "schatten=ja").mitSchatten);
        assertThrows(IllegalArgumentException.class, () -> Skin.lies("t", "#000000 #111111 #222222", ""));
        assertThrows(IllegalArgumentException.class, () -> Skin.lies("t", "#00000", ""));
        assertThrows(IllegalArgumentException.class, () -> Skin.lies("t", "// nur Kommentar", ""));
    }

    @Test
    void dieSkinsDesPakets() throws IOException {
        Map<String, Integer> baender = Map.of("grau", 3, "holz", 4, "papier", 5, "kompass", 2, "uhr", 2, "kartograph", 2);
        assertEquals(Skin.OHNE, Skin.NAMEN.getFirst());
        assertEquals(baender.size() + 1, Skin.NAMEN.size());
        for (String name : Skin.NAMEN.subList(1, Skin.NAMEN.size())) {
            Skin s = Skin.lies(name, text(name, "palette.txt"), text(name, "info.txt"));
            assertEquals(baender.get(name), s.baender(), name);
            for (String teil : List.of("zier", "zier_aktiv", "griff", "griff_aktiv")) {
                BufferedImage bild = bild(name, teil);
                assertNotNull(bild, name + "/" + teil);
                // Die Vollbildkarte rückt um die halbe zier ein: So bleibt sie in der Ecke ganz auf dem Schirm.
                if (teil.equals("zier")) {
                    int e = Skin.einrueckung(Math.max(bild.getWidth(), bild.getHeight()));
                    assertTrue(Skin.lage(e + s.baender() / 2.0, bild.getWidth()) >= 0, name);
                    assertTrue(Skin.lage(e + s.baender() / 2.0, bild.getHeight()) >= 0, name);
                }
            }
        }
    }

    @Test
    void baenderEckigNachDerRegel() {
        // Jedes Pixel der Bänder genau einmal, im richtigen Band, oben und links Licht nach der Regel des Pakets.
        int w = 20, h = 14, anzahl = 4;
        int[][] band = new int[h][w], licht = new int[h][w], decke = new int[h][w];
        Skin.baender(0, 0, w, h, anzahl, (xa, ya, xb, yb, i, l) -> {
            for (int y = ya; y < yb; y++) {
                for (int x = xa; x < xb; x++) {
                    decke[y][x]++;
                    band[y][x] = i;
                    licht[y][x] = l ? 1 : 0;
                }
            }
        });
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int soll = Skin.bandEckig(x, y, w, h);
                assertEquals(soll < anzahl ? 1 : 0, decke[y][x], x + ", " + y);
                if (soll < anzahl) {
                    assertEquals(soll, band[y][x], x + ", " + y);
                    assertEquals(Skin.lichtEckig(x, y, w, h) ? 1 : 0, licht[y][x], x + ", " + y);
                }
            }
        }
    }

    @Test
    void ringUndMaskeRechnenGleich() {
        // Die Karte liegt genau dort, wo der Ring endet: in Einheiten des GUI, beim GUI-Massstab 3 je 3 Pixel.
        int s = 128, anzahl = 3, k = 3;
        Skin skin = Skin.lies("t", "#111111\n#222222 #333333\n#444444", "");
        boolean[][] maske = new boolean[s][s];
        for (int[] lauf : Skin.maskeRund(s, anzahl, k)) {
            assertEquals(0, lauf[0] % k);
            assertEquals(0, lauf[2] % k);
            for (int y = lauf[0] / k; y < lauf[1] / k; y++) {
                for (int x = lauf[2] / k; x < lauf[3] / k; x++) {
                    maske[y][x] = true;
                }
            }
        }
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                int band = Skin.bandRund(x, y, s);
                assertEquals(band >= anzahl, maske[y][x], x + ", " + y);
                assertEquals(band >= 0 && band < anzahl, skin.ringFarbe(x, y, s) != 0, x + ", " + y);
            }
        }
        // Oben in der Mitte das äussere Band; im zweiten Band oben links Licht, unten rechts Schatten.
        assertEquals(0xFF111111, skin.ringFarbe(s / 2, 0, s));
        assertEquals(0xFF222222, skin.ringFarbe(s / 2, 1, s));
        assertEquals(0xFF333333, skin.ringFarbe(s / 2, s - 2, s));
        assertEquals(0, skin.ringFarbe(s / 2, s / 2, s));
    }

    @Test
    void ornamenteAufDerMitteDerBaender() {
        double[][] eckig = Skin.ecken(10, 20, 100, 60, 3, false);
        assertEquals(11.5, eckig[0][0], 1e-9);
        assertEquals(21.5, eckig[0][1], 1e-9);
        assertEquals(108.5, eckig[3][0], 1e-9);
        assertEquals(78.5, eckig[3][1], 1e-9);
        // Rund bei 45° auf dem Kreis durch die Mitte der Bänder.
        double[][] rund = Skin.ecken(0, 0, 128, 128, 2, true);
        for (double[] p : rund) {
            assertEquals(63, Math.hypot(p[0] - 64, p[1] - 64), 1e-9);
            assertEquals(Math.abs(p[0] - 64), Math.abs(p[1] - 64), 1e-9);
        }
        assertTrue(rund[1][0] > 64 && rund[1][1] < 64);
        assertTrue(rund[2][0] < 64 && rund[2][1] > 64);
        // Die Mitte eines Bilds der Breite 13 auf 11,5: von 5 bis 18.
        assertEquals(5, Skin.lage(11.5, 13));
        assertEquals(4, Skin.einrueckung(7));
        assertEquals(8, Skin.einrueckung(15));
    }

    private static String text(String skin, String datei) throws IOException {
        try (InputStream rein = SkinTest.class.getResourceAsStream("/assets/heroicmap/textures/rahmen/" + skin + "/" + datei)) {
            assertNotNull(rein, skin + "/" + datei);
            return new String(rein.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static BufferedImage bild(String skin, String teil) throws IOException {
        try (InputStream rein = SkinTest.class.getResourceAsStream("/assets/heroicmap/textures/rahmen/" + skin + "/" + teil + ".png")) {
            return rein == null ? null : ImageIO.read(rein);
        }
    }
}
