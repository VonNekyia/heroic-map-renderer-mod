package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der Bereich, den die Minimap zeichnet. Siehe docs/minimap.md, „Neu zeichnen“.
 * Form, Lage und Einstellungen: siehe docs/minimap.md, „Bedienung“.
 */
class MinimapTest {

    @Test
    void reichweiteJeMassstab() {
        // Sichtbar sind 64 Einheiten je Richtung: ±4, ±2 und ±1 Chunks, dazu 2 Chunks Vorrat.
        assertEquals(6, Minimap.reichweite(1, 128));
        assertEquals(4, Minimap.reichweite(2, 128));
        assertEquals(3, Minimap.reichweite(4, 128));
        assertEquals(10, Minimap.reichweite(1, 256));
        assertEquals(3, Minimap.reichweite(8, 128));
    }

    @Test
    void eckeZwischenZweiTicks() {
        // Ein Tick von x = 10 nach 11, Zoom 2, GUI-Massstab 2: 4 Pixel je Block, Seite 256 Pixel.
        assertEquals(40 - 128, Minimap.ecke(10, 11, 0f, 2, 2, 256));
        assertEquals(42 - 128, Minimap.ecke(10, 11, 0.5f, 2, 2, 256));
        assertEquals(44 - 128, Minimap.ecke(10, 11, 1f, 2, 2, 256));
        // Ein Pixel, weniger als eine Einheit des GUI.
        assertEquals(41 - 128, Minimap.ecke(10, 11, 0.25f, 2, 2, 256));
        // Abgerundet, auch links vom Ursprung.
        assertEquals(-42 - 128, Minimap.ecke(-10, -11, 0.3f, 2, 2, 256));
    }

    @Test
    void markiereNurImBereich() {
        Minimap minimap = new Minimap();
        minimap.mitte = new ChunkPos(10, -20);
        int r = minimap.reichweite();

        minimap.markiere(10 + r, -20 - r);
        minimap.markiere(10 + r + 1, -20);
        minimap.markiere(10, -20 - r - 1);

        assertTrue(minimap.offen.contains(ChunkPos.pack(10 + r, -20 - r)));
        assertFalse(minimap.offen.contains(ChunkPos.pack(10 + r + 1, -20)));
        assertFalse(minimap.offen.contains(ChunkPos.pack(10, -20 - r - 1)));
        assertEquals(1, minimap.offen.size());
    }

    @Test
    void ohneMitteNimmtSieAllesAuf() {
        Minimap minimap = new Minimap();
        minimap.markiere(1000, 1000);
        assertTrue(minimap.offen.contains(ChunkPos.pack(1000, 1000)));
    }

    @Test
    void rundIstDerKreis() {
        // Je Zeile die Sehne, symmetrisch zur Mitte und nie leer; zusammen die Fläche des Kreises.
        int n = 384;
        long flaeche = 0;
        for (int y = 0; y < n; y++) {
            int a = Minimap.sehne(n, y);
            assertTrue(a < n - a, "Zeile " + y);
            flaeche += n - 2 * a;
        }
        assertEquals(Math.PI * n * n / 4, flaeche, Math.PI * n * n / 4 * 0.005);
    }

    @Test
    void rahmenNachLageUndSchirm() {
        // Vorgabe: rechts oben, 4 Einheiten vom Rand.
        assertEquals(new Minimap.Rahmen(640 - 128 - 4, 4, 128), Minimap.rahmen(640, 360, 128, 1, 0, Minimap.RAND));
        assertEquals(new Minimap.Rahmen(4, 360 - 128 - 4, 128), Minimap.rahmen(640, 360, 128, 0, 1, Minimap.RAND));
        // Ein kleiner Schirm kappt die Seite.
        assertEquals(200 - 8, Minimap.rahmen(300, 200, 256, 1, 0, Minimap.RAND).seite());
        // Mit Rahmen rückt sie um dessen Einrückung vom Rand, etwa 8 bei „uhr“.
        assertEquals(new Minimap.Rahmen(640 - 128 - 8, 8, 128), Minimap.rahmen(640, 360, 128, 1, 0, 8));
    }

    @Test
    void verschiebenBleibtAufDemSchirm() {
        Minimap minimap = new Minimap();
        minimap.verschiebe(100, 50, 640, 360);
        assertEquals(new Minimap.Rahmen(100, 50, 128), minimap.rahmen(640, 360));
        minimap.verschiebe(-500, 9999, 640, 360);
        assertEquals(new Minimap.Rahmen(4, 360 - 128 - 4, 128), minimap.rahmen(640, 360));
        minimap.stelle(4, 4, 9999, 640, 360);
        assertEquals(Minimap.GROESSTE, minimap.rahmen(640, 360).seite());
    }

    @Test
    void einstellungenUeberstehenDenNeustart(@TempDir Path ordner) throws Exception {
        Path datei = ordner.resolve("config").resolve("heroicmap.properties");
        Minimap vorher = new Minimap();
        vorher.setzeSichtbar(false);
        vorher.setzeScale(4);
        vorher.setzeZoom(1);
        vorher.setzeRund(true);
        vorher.setzeShow(false);
        vorher.setzeAblage(Downloads.Ablage.HASH);
        vorher.setzeChunklinien(true);
        vorher.setzeDrehen(true);
        vorher.stelle(20, 30, 200, 640, 360);
        vorher.schreibe(datei);

        Minimap nachher = new Minimap();
        nachher.lies(datei);
        assertFalse(nachher.sichtbar());
        assertEquals(4, nachher.aufloesung());
        assertEquals(1, nachher.zoom());
        assertTrue(nachher.rund());
        assertFalse(nachher.show());
        assertEquals(Downloads.Ablage.HASH, nachher.ablage());
        assertTrue(nachher.chunklinien());
        assertTrue(nachher.drehen());
        assertEquals(vorher.rahmen(640, 360), nachher.rahmen(640, 360));
    }

    @Test
    void drehenNurGewaehltGespeichert(@TempDir Path ordner) throws Exception {
        Path datei = ordner.resolve("heroicmap.properties");
        // Aus einer älteren Version: drehen=true war gewählt, drehen=false ist von der alten Vorgabe nicht zu unterscheiden.
        Files.writeString(datei, "drehen=true\n");
        Minimap m = new Minimap();
        m.lies(datei);
        assertTrue(m.drehen());
        Files.writeString(datei, "drehen=false\n");
        m.lies(datei);
        assertTrue(m.drehen());
        // Ohne Wahl schreibt der Mod nichts zu Drehen; so gilt später eine andere Vorgabe.
        m.schreibe(datei);
        assertFalse(Files.readString(datei).contains("drehen"));
        // Selbst ausgeschaltet bleibt aus, auch über den Neustart.
        m.setzeDrehen(false);
        m.schreibe(datei);
        Minimap neu = new Minimap();
        neu.lies(datei);
        assertFalse(neu.drehen());
    }

    @Test
    void rahmenUebersteht(@TempDir Path ordner) throws Exception {
        // Ohne Lage und Grösse: Die rechnen mit dem Abstand des Skins, und den kennt erst das Spiel.
        Path datei = ordner.resolve("heroicmap.properties");
        Minimap vorher = new Minimap();
        vorher.setzeSkin("uhr");
        vorher.schreibe(datei);
        Minimap nachher = new Minimap();
        nachher.lies(datei);
        assertEquals("uhr", nachher.skin());
        // Ein unbekannter Rahmen ist „ohne“.
        nachher.setzeSkin("quatsch");
        assertEquals(Skin.OHNE, nachher.skin());
    }

    @Test
    void unlesbareWerteGebenDieVorgabe(@TempDir Path ordner) throws Exception {
        Path datei = ordner.resolve("heroicmap.properties");
        Files.writeString(datei, String.join("\n", "minimap=vielleicht", "massstab=3", "form=dreieckig",
                "groesse=x", "lage_x=NaN", "lage_y=7"));
        Minimap minimap = new Minimap();
        minimap.lies(datei);
        assertTrue(minimap.sichtbar());
        assertEquals(2, minimap.aufloesung());
        assertFalse(minimap.rund());
        assertEquals(new Minimap.Rahmen(640 - 128 - 4, 360 - 128 - 4, 128), minimap.rahmen(640, 360));
    }

    @Test
    void ohneZoomGiltDerMassstab(@TempDir Path ordner) throws Exception {
        // Eine Datei von vor dem Zoom: Der Ausschnitt bleibt, wie er war.
        Path datei = ordner.resolve("heroicmap.properties");
        Files.writeString(datei, "massstab=4");
        Minimap minimap = new Minimap();
        minimap.lies(datei);
        assertEquals(4, minimap.aufloesung());
        assertEquals(4, minimap.zoom());
    }

    @Test
    void zoomBestimmtDieReichweite() {
        Minimap minimap = new Minimap();
        minimap.setzeScale(4);
        minimap.setzeZoom(1);
        // Eckig und gedreht, die Vorgabe, reicht sie in den Ecken weiter; genordet so weit wie die Seite.
        assertEquals(Minimap.reichweite(1, Minimap.sicht(Minimap.GROESSE, true, false)), minimap.reichweite());
        minimap.setzeDrehen(false);
        assertEquals(Minimap.reichweite(1, Minimap.GROESSE), minimap.reichweite());
    }

    @Test
    void nieVerkleinertImmerGanzeTexel() {
        // GUI-Massstab 2, Zoom 1: 2 Pixel je Block auf dem Schirm, 4 px in der Textur wären verkleinert.
        assertEquals(2, Minimap.effektiv(4, 1, 2));
        // GUI-Massstab 3: 3 Pixel je Block, 2 ginge nicht ganz auf.
        assertEquals(1, Minimap.effektiv(4, 1, 3));
        assertEquals(2, Minimap.effektiv(4, 2, 3));
        assertEquals(4, Minimap.effektiv(4, 4, 3));
        // Die Wahl ist die Obergrenze.
        assertEquals(1, Minimap.effektiv(1, 4, 4));
        assertEquals(2, Minimap.effektiv(2, 4, 4));
        // Zoom 8 bei GUI-Massstab 2: 16 Pixel je Block, 16 px gehen ganz auf.
        assertEquals(16, Minimap.effektiv(16, 8, 2));
        assertEquals(8, Minimap.effektiv(16, 4, 2));
        for (int a : new int[] {1, 2, 4, 8, 16}) {
            for (int z : new int[] {1, 2, 4, 8}) {
                for (int k = 1; k <= 8; k++) {
                    int r = Minimap.effektiv(a, z, k);
                    assertTrue(r <= a && r <= z * k && (z * k) % r == 0, a + " " + z + " " + k);
                }
            }
        }
    }

    @Test
    void kopfWaechstMitDerSeite() {
        assertEquals(Minimap.KOPF, Minimap.kopf(Minimap.GROESSE));
        assertEquals(2 * Minimap.KOPF, Minimap.kopf(2 * Minimap.GROESSE));
        // Klein bleibt er erkennbar.
        assertEquals(4, Minimap.kopf(Minimap.KLEINSTE));
    }

    @Test
    void amRandInSeinerRichtung() {
        // Drinnen bleibt der Punkt, wo er ist.
        assertEquals(1, Minimap.rand(10, -20, 50, 50, false));
        assertEquals(1, Minimap.rand(0, 0, 50, 50, true));
        // Eckig an die nähere Kante: (200, 50) auf x = 50, also auf (50, 12,5).
        assertEquals(0.25, Minimap.rand(200, 50, 50, 50, false));
        // Ein Rechteck wie der Schirm: (0, 400) unten auf z = 100.
        assertEquals(0.25, Minimap.rand(0, 400, 300, 100, false));
        assertEquals(0.25, Minimap.rand(0, -400, 300, 100, false));
        // Rund auf den Kreis: (30, 40) hat die Länge 50.
        assertEquals(0.5, Minimap.rand(30, 40, 25, 25, true), 1e-12);
    }

    @Test
    void markeAufDemPixelDerKarte() {
        // Zoom 1, GUI-Massstab 3: 3 Pixel je Block. Der Spieler steht bei x = 12 + frac; die Kante des Bildes rückt
        // mit ihm in ganzen Pixeln. Die Marke der Mitte von Block 10 bleibt auf demselben Pixel des gezeichneten
        // Blocks, gleich welches frac; mit der Lage relativ zum ungerundeten Spieler wäre sie um einen Pixel gewandert.
        int zoom = 1, k = 3, n = 128 * k;
        Minimap.Rahmen r = new Minimap.Rahmen(20, 30, 128);
        for (double frac : new double[] {0, 0.1, 0.25, 0.5, 0.75, 0.99}) {
            int links = Minimap.ecke(12 + frac, 12 + frac, 1f, zoom, k, n), oben = Minimap.ecke(-3, -3, 1f, zoom, k, n);
            float[] m = Minimap.marke(r, 10.5, -2.5, links, oben, k, zoom, false, 64, false, null);
            // Links beginnt Block 10 bei Pixel r.x·k + 10·3 − links; die Marke liegt 2 Pixel weiter.
            assertEquals(r.x() * k + 10 * zoom * k - links + 2, m[0] * k, 1e-4, "frac " + frac);
            assertEquals(r.y() * k + (-3) * zoom * k - oben + 2, m[1] * k, 1e-4, "frac " + frac);
        }
    }

    @Test
    void chunklinienAufDemRasterDerKarte() {
        // Zoom 2, GUI-Massstab 3: ein Chunk sind 96 Pixel des Bildes, eine Linie 3 breit.
        int zoom = 2, k = 3, n = 128 * k, schritt = 16 * zoom * k;
        for (int links : new int[] {-1000, -50, -1, 0, 1, 3, 50, 94, 95, 96, 1000}) {
            Gitter.Linien l = Minimap.linien(n, links, links, schritt, k);
            assertEquals(n, l.breite());
            assertEquals(n, l.hoehe());
            // Die erste ganz im Bild, die davor nicht; die letzte ganz im Bild, die danach nicht. Bei links 3 endet die letzte genau am Rand.
            assertTrue(l.xs()[0] >= 0 && l.xs()[0] - schritt < 0, "links " + links);
            assertTrue(l.xs()[l.nx() - 1] + k <= n && l.xs()[l.nx() - 1] + schritt + k > n, "links " + links);
            for (int i = 0; i < l.nx(); i++) {
                // Jede Linie, wo die Karte den ersten Block ihres Chunks zeichnet.
                int chunk = Math.floorDiv(l.xs()[i] + links, schritt);
                assertEquals(Minimap.pixel(16.0 * chunk, zoom, k, links), l.xs()[i], "links " + links + ", Chunk " + chunk);
            }
            assertArrayEquals(l.xs(), l.ys());
            assertEquals(l.nx(), l.ny());
        }
    }

    @Test
    void kreuzungenDeckenEinfach() {
        // Zwei senkrechte, eine waagrechte Linie, 2 breit, auf 50 × 40: Jedes Pixel einer Linie genau einmal.
        Gitter.Linien l = new Gitter.Linien(new int[] {10, 30}, 2, new int[] {20}, 1, 50, 40);
        int[][] decke = decke(50, 40, 2, l);
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 50; x++) {
                boolean linie = x >= 10 && x < 12 || x >= 30 && x < 32 || y >= 20 && y < 22;
                assertEquals(linie ? 1 : 0, decke[y][x], x + ", " + y);
            }
        }
    }

    /** Wie oft {@link Gitter#rechtecke} jedes Pixel deckt. */
    private static int[][] decke(int breite, int hoehe, int dicke, Gitter.Linien l) {
        int[][] decke = new int[hoehe][breite];
        Gitter.rechtecke(dicke, l, (xa, ya, xb, yb) -> {
            for (int y = ya; y < yb; y++) {
                for (int x = xa; x < xb; x++) {
                    decke[y][x]++;
                }
            }
        });
        return decke;
    }

    @Test
    void ohneDateiDieVorgabe(@TempDir Path ordner) {
        Minimap minimap = new Minimap();
        minimap.lies(ordner.resolve("fehlt.properties"));
        assertTrue(minimap.sichtbar());
        // show: Vorgabe simplevoicechat, die Wahl des Maintainers.
        assertTrue(minimap.show());
        // Ablage: Vorgabe IP und Hash, die Wahl des Users.
        assertEquals(Downloads.Ablage.IP, minimap.ablage());
        assertFalse(minimap.chunklinien());
        // Drehen: Vorgabe an, der Wunsch des Users.
        assertTrue(minimap.drehen());
        assertEquals(Skin.OHNE, minimap.skin());
        assertEquals(new Minimap.Rahmen(640 - 128 - 4, 4, 128), minimap.rahmen(640, 360));
    }
}
