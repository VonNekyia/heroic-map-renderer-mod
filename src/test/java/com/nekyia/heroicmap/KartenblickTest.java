package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void klickTrifftDenGezeichnetenBlock() {
        // scale 4, Lupe 1: Ein Klick 0,1 rechts der gezeichneten Kante von Block 250 trifft Block 250, nicht 249.
        Kartenblick blick = new Kartenblick(256, 0, 6, 6);
        blick.mx = 1000.75;
        assertEquals(250, (int) Math.floor(blick.basisRasterX(blick.rasterX(1000, 854) + 0.1, 854) / 4));
        // Für Lupe 1, 2 und 4, auf der feinsten und einer groben Stufe, rechts und links des Ursprungs:
        // ein Klick genau auf die Kante von Block X und knapp davor.
        for (int stufe : new int[] {6, 3}) {
            for (int lupe : new int[] {1, 2, 4}) {
                blick.zoom = stufe;
                blick.lupe = stufe == 6 ? lupe : 1;
                blick.mx = 1000.75;
                blick.mz = -77.3;
                for (int x : new int[] {250, 3, 0, -1, -129}) {
                    double kante = blick.rasterX(4.0 * x, 854);
                    assertEquals(x, (int) Math.floor(blick.basisRasterX(kante, 854) / 4), "Kante, Stufe " + stufe + ", Lupe " + lupe + ", x " + x);
                    assertEquals(x - 1, (int) Math.floor(blick.basisRasterX(kante - 1e-6, 854) / 4), "davor, x " + x);
                    double kanteZ = blick.rasterY(4.0 * x, 480);
                    assertEquals(x, (int) Math.floor(blick.basisRasterZ(kanteZ, 480) / 4), "z " + x);
                }
            }
        }
    }

    @Test
    void kachelnRasterUndKlickAufEinerKante() {
        // Nahe dem Ursprung nach einem Zug: Die Mitte ist 3e-14. In Doubles rundet schirmX(-512) auf genau -85,
        // schirmX(0) aber auf knapp unter 427; je Kachel gefloort lag Kachel -2 deshalb bei -85, das Raster bei -86.
        // Von einer Kante aus liegen Kachel, Raster und Klick alle bei -86, wie es auch genau gerechnet wäre.
        Kartenblick blick = new Kartenblick(256, 0, 6, 6);
        blick.mx = 3e-14;
        assertEquals(-86, blick.kachelX(-2, 854));
        assertEquals(blick.kachelX(-2, 854), blick.rasterX(-512, 854), 1e-9);
        assertEquals(-128, (int) Math.floor(blick.basisRasterX(blick.rasterX(-512, 854), 854) / 4));
    }

    @Test
    void nameBleibtAufDemSchirmUndNebenDenKnoepfen() {
        // Schirm 400 breit, Knöpfe ab x 306 bis y 48, Name 60 breit, halbe Marke 4: über dem Kopf 14 höher.
        assertArrayEquals(new int[] {170, 30}, Kartenblick.name(200, 44, 4, 400, 60, 306, 48));
        // Am linken und rechten Rand ganz auf dem Schirm.
        assertArrayEquals(new int[] {2, 100}, Kartenblick.name(5, 114, 4, 400, 60, 306, 48));
        assertArrayEquals(new int[] {338, 100}, Kartenblick.name(395, 114, 4, 400, 60, 306, 48));
        // Ein Kopf am rechten Rand unter den Knöpfen, bei y 53: Über ihm träfe der Name die Knöpfe, also steht er
        // unter ihm, nicht 80 Einheiten weiter links.
        assertArrayEquals(new int[] {338, 59}, Kartenblick.name(386, 53, 4, 400, 60, 306, 48));
        // Träfe auch der Platz unter dem Kopf die Knöpfe, steht er links neben ihnen.
        assertArrayEquals(new int[] {244, 2}, Kartenblick.name(395, 14, 4, 400, 60, 306, 48));
        // Ganz oben nicht über den Rand.
        assertEquals(2, Kartenblick.name(100, 8, 4, 400, 60, 306, 48)[1]);
    }

    @Test
    void chunklinienAufDemRasterUndNichtZuDicht() {
        // scale 4: Ein Chunk sind 64 Pixel der Basis, auf der feinsten Stufe mit Lupe 1 also 64 Einheiten.
        Kartenblick blick = new Kartenblick(256, 0, 6, 6);
        blick.mx = 1000.75;
        blick.mz = -77.3;
        assertEquals(64, blick.chunkAbstand(4), 1e-9);
        // Chunk 4 beginnt bei Pixel 256 der Basis, genau auf der Kante von Kachel 1.
        assertEquals(blick.kachelX(1, 854), Math.floor(blick.rasterX(4 * 64.0, 854)), 1e-9);
        assertEquals(blick.kachelY(1, 480), Math.floor(blick.rasterY(4 * 64.0, 480)), 1e-9);
        // Die erste Linie liegt auf dem Schirm, die davor links davon.
        int c = blick.ersterChunkX(4, 854);
        assertTrue(blick.rasterX(c * 64.0, 854) >= 0 && blick.rasterX((c - 1) * 64.0, 854) < 0, "Chunk " + c);
        int z = blick.ersterChunkZ(4, 480);
        assertTrue(blick.rasterY(z * 64.0, 480) >= 0 && blick.rasterY((z - 1) * 64.0, 480) < 0, "Chunk " + z);
        // Die Linien: ab dem ersten Chunk jede auf dem Raster, bis zum Rand, über Breite und Höhe.
        Gitter.Linien l = blick.linien(4, 854, 480);
        assertEquals(854, l.breite());
        assertEquals(480, l.hoehe());
        for (int i = 0; i < l.nx(); i++) {
            assertEquals(Math.floor(blick.rasterX((c + i) * 64.0, 854)), l.xs()[i], 1e-9);
        }
        assertTrue(l.xs()[l.nx() - 1] < 854 && blick.rasterX((c + l.nx()) * 64.0, 854) >= 854);
        for (int j = 0; j < l.ny(); j++) {
            assertEquals(Math.floor(blick.rasterY((z + j) * 64.0, 480)), l.ys()[j], 1e-9);
        }
        assertTrue(l.ys()[l.ny() - 1] < 480 && blick.rasterY((z + l.ny()) * 64.0, 480) >= 480);
        // Gröber, bei GUI-Massstab 2: Stufe 2 hat 4 Einheiten Abstand, die Karte zeichnet; Stufe 1 hätte 2, sie zeichnet keine.
        blick.zoom = 2;
        assertEquals(4, blick.chunkAbstand(4), 1e-9);
        assertTrue(blick.chunklinien(4, 2));
        blick.zoom = 1;
        assertFalse(blick.chunklinien(4, 2));
        // Die Lupe rückt sie wieder auseinander: scale 1, feinste Stufe, Lupe 4 gibt 64.
        blick.zoom = 6;
        blick.lupe = 4;
        assertEquals(64, blick.chunkAbstand(1), 1e-9);
    }

    @Test
    void chunklinienBeiGuiMassstab1Auf4k() {
        // 3840 × 2160 Einheiten. Bei GUI-Massstab 1 sind 8 Pixel des Schirms 8 Einheiten: 4 Einheiten reichen nicht mehr.
        Kartenblick blick = new Kartenblick(256, 0, 6, 6);
        blick.zoom = 2;
        assertEquals(4, blick.chunkAbstand(4), 1e-9);
        assertFalse(blick.chunklinien(4, 1));
        assertTrue(blick.chunklinien(4, 2));
        blick.zoom = 3;
        assertEquals(8, blick.chunkAbstand(4), 1e-9);
        assertTrue(blick.chunklinien(4, 1));
        // Der schlechteste Fall danach: rund 130 000 Rechtecke. Siehe docs/minimap.md, „Chunklinien“.
        Gitter.Linien l = blick.linien(4, 3840, 2160);
        int[] rechtecke = {0};
        Gitter.rechtecke(1, l, (xa, ya, xb, yb) -> rechtecke[0]++);
        assertTrue(rechtecke[0] <= l.nx() + l.ny() * (l.nx() + 1));
        assertTrue(rechtecke[0] > 125_000 && rechtecke[0] < 135_000, rechtecke[0] + " Rechtecke");
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

    @Test
    void stelltGeklemmtWieZuletzt() {
        Kartenblick blick = new Kartenblick(256, 0, 4, 4);
        blick.stelle(12, -34, 4, 4);
        assertEquals(12, blick.mx, 1e-9);
        assertEquals(4, blick.zoom);
        assertEquals(4, blick.lupe);
        // Eine Stufe, die der Satz nicht hat, kommt auf die nächste; eine Lupe gibt es nur auf der feinsten Stufe.
        blick.stelle(0, 0, 9, 3);
        assertEquals(4, blick.zoom);
        assertEquals(2, blick.lupe);
        blick.stelle(0, 0, 2, 4);
        assertEquals(2, blick.zoom);
        assertEquals(1, blick.lupe);
        blick.stelle(0, 0, -3, 0);
        assertEquals(0, blick.zoom);
        assertEquals(1, blick.lupe);
    }

    @Test
    void lageJeDimensionUeberstehtDenNeustart(@TempDir Path ordner) throws Exception {
        assertNull(Kartenlage.lies(ordner, "minecraft:overworld"));
        Kartenlage.schreibe(ordner, "minecraft:overworld", new Kartenlage.Lage(120.5, -40.25, 3, 1));
        Kartenlage.schreibe(ordner, "minecraft:the_nether", new Kartenlage.Lage(8, 9, 2, 1));
        assertEquals(new Kartenlage.Lage(120.5, -40.25, 3, 1), Kartenlage.lies(ordner, "minecraft:overworld"));
        assertEquals(new Kartenlage.Lage(8, 9, 2, 1), Kartenlage.lies(ordner, "minecraft:the_nether"));
        // Ohne Ordner, im Einzelspieler, nur im Speicher; eine kaputte Datei gibt keine Lage.
        Kartenlage.schreibe(null, "minecraft:the_end", new Kartenlage.Lage(1, 2, 0, 1));
        assertEquals(new Kartenlage.Lage(1, 2, 0, 1), Kartenlage.lies(null, "minecraft:the_end"));
        Kartenlage.leeren();
        assertNull(Kartenlage.lies(null, "minecraft:the_end"));
        Files.writeString(ordner.resolve("karte.properties"), "minecraft\\:overworld.x=eins\n");
        assertNull(Kartenlage.lies(ordner, "minecraft:overworld"));
    }
}
