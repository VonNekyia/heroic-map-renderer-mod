package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

/** Die Rahmen: Palette, Bänder, Ring und Maske, Ornamente und Griff. Siehe docs/rahmen.md. */
class SkinTest {

    private static final String ORDNER = "/assets/heroicmap/textures/gui/sprites/rahmen/";

    @Test
    void paletteMitKommentarenUndLichtUndSchatten() {
        Skin s = Skin.lies("t", "// Kopf\n#181717\n#A8A8A8 #4F4D4D  // Licht und Schatten\n\n#D8D8D8\n", "schatten=nein\n", 7);
        assertEquals(3, s.baender());
        assertEquals(0xFF181717, s.licht[0]);
        assertEquals(0xFF181717, s.schatten[0]);
        assertEquals(0xFFA8A8A8, s.licht[1]);
        assertEquals(0xFF4F4D4D, s.schatten[1]);
        assertFalse(s.mitSchatten);
        assertTrue(Skin.lies("t", "#000000\n#111111", "schatten=ja", 7).mitSchatten);
        assertThrows(IllegalArgumentException.class, () -> Skin.lies("t", "#000000", "", 7));
        assertThrows(IllegalArgumentException.class, () -> Skin.lies("t", "#000000 #111111 #222222", "", 7));
        assertThrows(IllegalArgumentException.class, () -> Skin.lies("t", "#00000", "", 7));
        assertThrows(IllegalArgumentException.class, () -> Skin.lies("t", "// nur Kommentar", "", 7));
    }

    @Test
    void dieSkinsDesPakets() throws IOException {
        Map<String, Integer> baender = Map.of("grau", 3, "holz", 4, "papier", 5, "kompass", 2, "uhr", 2, "kartograph", 2);
        assertEquals(Skin.OHNE, Skin.NAMEN.getFirst());
        assertEquals(baender.size() + 1, Skin.NAMEN.size());
        for (String name : Skin.NAMEN.subList(1, Skin.NAMEN.size())) {
            BufferedImage zier = bild(name, "zier");
            Skin s = Skin.lies(name, text(name, "palette.txt"), text(name, "info.txt"), Math.max(zier.getWidth(), zier.getHeight()));
            assertEquals(baender.get(name), s.baender(), name);
            for (String teil : List.of("zier", "zier_aktiv", "griff", "griff_aktiv")) {
                assertNotNull(bild(name, teil), name + "/" + teil);
            }
            // Mit Rahmen hält die Minimap so viel Abstand, dass die zier in der Ecke ganz auf dem Schirm bleibt.
            int rand = Minimap.rand(s);
            double[][] ecken = Skin.ecken(rand, rand, 128, 128, s.baender(), false);
            assertTrue(Skin.lage(ecken[0][0], zier.getWidth()) >= 0, name);
            assertTrue(Skin.lage(ecken[0][1], zier.getHeight()) >= 0, name);
        }
    }

    @Test
    void abstandZumRandMitRahmen() {
        assertEquals(Minimap.RAND, Minimap.rand(null));
        assertEquals(Minimap.RAND, Minimap.rand(Skin.lies("grau", "#000000\n#000000", "", 7)));
        assertEquals(8, Minimap.rand(Skin.lies("uhr", "#000000\n#000000", "", 15)));
    }

    @Test
    void fehlschlagBleibtGemerkt() {
        int[] versuche = {0};
        assertNull(Skin.von("test-fehlschlag", name -> {
            versuche[0]++;
            return null;
        }));
        assertNull(Skin.von("test-fehlschlag", name -> {
            versuche[0]++;
            return null;
        }));
        assertEquals(1, versuche[0]);
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
    void ringLiegtInDenBaendern() {
        // Wie weit die Karte unter den Ring reicht, prüft DrehungTest.mitRahmenDecktDerRingDenRand.
        int s = 128, anzahl = 3;
        Skin skin = Skin.lies("t", "#111111\n#222222 #333333\n#444444", "", 7);
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                int band = Skin.bandRund(x, y, s);
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

    @Test
    void spiegelnJeEcke() {
        // zier ist für oben links gezeichnet, griff für unten rechts; Ecken 0 oben links bis 3 unten rechts.
        boolean[][] zier = {{false, false}, {true, false}, {false, true}, {true, true}};
        for (int e = 0; e < 4; e++) {
            assertEquals(zier[e][0], Skin.spiegeltX(e, false), "zier " + e);
            assertEquals(zier[e][1], Skin.spiegeltY(e, false), "zier " + e);
            assertEquals(!zier[e][0], Skin.spiegeltX(e, true), "griff " + e);
            assertEquals(!zier[e][1], Skin.spiegeltY(e, true), "griff " + e);
        }
    }

    @Test
    void griffZurMitteDesSchirms() {
        // Rechts oben auf 640 × 360: Der Griff sitzt unten links, mit Rahmen auf der Mitte der Bänder.
        Minimap.Rahmen oben = new Minimap.Rahmen(504, 8, 128);
        assertEquals(2, Minimap.griffEcke(oben, 640, 360));
        assertEquals(1, Minimap.griffEcke(new Minimap.Rahmen(8, 224, 128), 640, 360));
        assertEquals(3, Minimap.griffEcke(new Minimap.Rahmen(8, 8, 128), 640, 360));
        double[] griff = Skin.ecken(oben.x(), oben.y(), oben.seite(), oben.seite(), 2, false)[Minimap.griffEcke(oben, 640, 360)];
        assertEquals(505, griff[0], 1e-9);
        assertEquals(135, griff[1], 1e-9);
        // Greifen lässt er sich 9 × 9 Einheiten um seine Mitte.
        assertTrue(Minimap.imGriff(501, 131, griff[0], griff[1]));
        assertTrue(Minimap.imGriff(509, 139, griff[0], griff[1]));
        assertFalse(Minimap.imGriff(500, 135, griff[0], griff[1]));
        assertFalse(Minimap.imGriff(505, 140, griff[0], griff[1]));
    }

    private static String text(String skin, String datei) throws IOException {
        try (InputStream rein = SkinTest.class.getResourceAsStream(ORDNER + skin + "/" + datei)) {
            assertNotNull(rein, skin + "/" + datei);
            return new String(rein.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static BufferedImage bild(String skin, String teil) throws IOException {
        try (InputStream rein = SkinTest.class.getResourceAsStream(ORDNER + skin + "/" + teil + ".png")) {
            return rein == null ? null : ImageIO.read(rein);
        }
    }
}
