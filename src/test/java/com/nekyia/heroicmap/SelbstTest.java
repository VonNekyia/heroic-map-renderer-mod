package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Die selbst gezeichnete Karte: Baum, Kacheln und Pyramide auf der Platte. Siehe docs/selbst.md, „Kacheln“. */
class SelbstTest {

    private static final int ROT = 0xFFFF0000, BLAU = 0xFF0000FF;

    @Test
    void chunkLiegtInSeinerKachel(@TempDir Path ordner) throws Exception {
        // 256 Pixel je Kachel, 64 je Chunk: 4 × 4 Chunks; Chunk (-1, 5) liegt in Kachel (-1, 1) rechts, zweite Zeile.
        Kachelwerk werk = new Kachelwerk(ordner, 256, 64, 6, 8);
        werk.lege(-1, 5, voll(64, ROT));
        List<Kachelwerk.Kachel> fertig = werk.schreibe();
        assertEquals(new Kachelwerk.Kachel(8, -1, 1), fertig.getFirst());
        int[] kachel = lies(ordner.resolve("8/-1/1.png"), 256);
        assertEquals(ROT, kachel[64 * 256 + 192]);
        assertEquals(ROT, kachel[127 * 256 + 255]);
        assertEquals(0, kachel[64 * 256 + 191]);
        assertEquals(0, kachel[63 * 256 + 192]);
        // Darüber je Stufe ein Vorfahr bis minZoom.
        Set<Kachelwerk.Kachel> alle = new HashSet<>(fertig);
        assertEquals(Set.of(new Kachelwerk.Kachel(8, -1, 1), new Kachelwerk.Kachel(7, -1, 0), new Kachelwerk.Kachel(6, -1, 0)), alle);
    }

    @Test
    void vorfahrIstDieVerkleinerteKachel(@TempDir Path ordner) throws Exception {
        Kachelwerk werk = new Kachelwerk(ordner, 256, 64, 7, 8);
        for (int cz = 0; cz < 4; cz++) {
            for (int cx = 0; cx < 4; cx++) {
                werk.lege(cx, cz, voll(64, BLAU));
            }
        }
        werk.schreibe();
        int[] fein = lies(ordner.resolve("8/0/0.png"), 256), grob = lies(ordner.resolve("7/0/0.png"), 256);
        int[] links = Pyramide.halbiere(fein, 256);
        for (int y = 0; y < 128; y++) {
            assertArrayEquals(Arrays.copyOfRange(links, y * 128, y * 128 + 128), Arrays.copyOfRange(grob, y * 256, y * 256 + 128));
            // Die anderen drei Viertel haben keine Kachel und bleiben durchsichtig.
            assertEquals(0, grob[y * 256 + 128]);
        }
    }

    @Test
    void einNeuesWerkLiestWasDaLiegt(@TempDir Path ordner) throws Exception {
        Kachelwerk erstes = new Kachelwerk(ordner, 256, 64, 8, 8);
        erstes.lege(0, 0, voll(64, ROT));
        erstes.schreibe();
        // Nach einem Neustart kommt ein zweiter Chunk in dieselbe Kachel; der erste bleibt.
        Kachelwerk zweites = new Kachelwerk(ordner, 256, 64, 8, 8);
        zweites.lege(1, 0, voll(64, BLAU));
        zweites.schreibe();
        int[] kachel = lies(ordner.resolve("8/0/0.png"), 256);
        assertEquals(ROT, kachel[0]);
        assertEquals(BLAU, kachel[64]);
        assertFalse(Files.exists(ordner.resolve("8/0/0.png.tmp")));
        // Ohne Änderung schreibt es nichts.
        assertTrue(zweites.schreibe().isEmpty());
    }

    @Test
    void baumJeDimension(@TempDir Path welt) throws Exception {
        assertEquals("selbst-minecraft_overworld", Selbst.baum("minecraft:overworld"));
        assertEquals("selbst-mod_welten_a_b", Selbst.baum("Mod:welten/a.b"));
        assertTrue(Selbst.baum("x:" + "a".repeat(100)).length() <= 64);
        // Der angelegte Baum ist ein gültiger Satz, und er geht dem Satz des Servers vor.
        Satz.schreibe(welt.resolve("survival"), "Survival", "minecraft:overworld", 4);
        Files.writeString(welt.resolve("survival/4/map.json"), "{\"tileSize\":256,\"minZoom\":0,\"maxZoom\":5,\"scale\":4}");
        assertEquals("Survival", Satz.fuer(welt, "minecraft:overworld").name());
        Path baum = Selbst.anlegen(welt, "minecraft:overworld");
        Satz satz = Satz.fuer(welt, "minecraft:overworld");
        assertEquals(baum.resolve("4"), satz.ordner());
        assertEquals(Selbst.MAX_ZOOM, satz.stufe());
        assertEquals(Selbst.SCALE, satz.scale());
        assertTrue(Selbst.selbst(baum));
    }

    @Test
    void pngMitFalscherGroesseFaelltWeg(@TempDir Path ordner) throws Exception {
        BufferedImage bild = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        Path datei = ordner.resolve("gross.png");
        ImageIO.write(bild, "png", datei.toFile());
        byte[] bytes = Files.readAllBytes(datei);
        try {
            Kacheln.png(bytes, 256);
            throw new AssertionError("Eine Kachel 512 × 512 statt 256 × 256 wurde gelesen");
        } catch (java.io.IOException erwartet) {
            // So soll es sein.
        }
        assertEquals(512 * 512, Kacheln.png(bytes, 512).argb().length);
    }

    private static int[] voll(int seite, int argb) {
        int[] pixel = new int[seite * seite];
        Arrays.fill(pixel, argb);
        return pixel;
    }

    private static int[] lies(Path datei, int seite) throws Exception {
        BufferedImage bild = ImageIO.read(datei.toFile());
        return bild.getRGB(0, 0, seite, seite, null, 0, seite);
    }
}
