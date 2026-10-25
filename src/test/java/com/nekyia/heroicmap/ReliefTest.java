package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReliefTest {

    private static int grau(int v) {
        return 0xFF000000 | v << 16 | v << 8 | v;
    }

    /** Ein Pixel der Schicht mit dem Code in Rot, Grün und Blau, Alpha 255, wie der Renderer sie schreibt. */
    private static int code(int c) {
        return grau(c);
    }

    @Test
    void faktorenJeCode() {
        float[] slope = {0.76f, 0.82f, 0.88f, 0.94f, 1, 1.06f, 1.12f, 1.18f, 1.24f};
        float[] imSchatten = {0.57f, 0.615f, 0.66f, 0.705f, 0.75f, 0.795f, 0.84f, 0.885f, 0.93f};
        for (int c = 0; c < 18; c++) {
            assertEquals(slope[c % 9], Relief.faktor(c, Relief.Art.SLOPE), 1e-6f, "slope " + c);
            assertEquals(c < 9 ? slope[c] : imSchatten[c - 9], Relief.faktor(c, Relief.Art.SHADOWS), 1e-6f, "shadows " + c);
            assertEquals(1, Relief.faktor(c, Relief.Art.OHNE));
        }
        // Ein Code über 17 gilt wie 4: unverändert.
        assertEquals(1, Relief.faktor(20, Relief.Art.SHADOWS));
        assertEquals(1, Relief.faktor(255, Relief.Art.SLOPE));
    }

    @Test
    void schneeZurueckgerechnet() {
        // Schnee 250 in der Kachel mit slope bei Code 8, f 1,24: ohne 202, shadows 250; bei Code 17 im Schatten 188.
        int[] k = {grau(250)};
        Relief.rechne(k, new int[] {code(8)}, null, Relief.Art.SLOPE, Relief.Art.OHNE);
        assertEquals(grau(202), k[0]);
        k = new int[] {grau(250)};
        Relief.rechne(k, new int[] {code(8)}, null, Relief.Art.SLOPE, Relief.Art.SHADOWS);
        assertEquals(grau(250), k[0]);
        k = new int[] {grau(250)};
        Relief.rechne(k, new int[] {code(17)}, null, Relief.Art.SLOPE, Relief.Art.SHADOWS);
        assertEquals(grau(188), k[0]);
    }

    @Test
    void abgeschnittenNurMitRest() {
        // Ohne Relief 230 gäbe mit 1,24 genau 285; die Kachel trägt 255, der Rest 230.
        int[] k = {grau(255)};
        Relief.rechne(k, new int[] {code(8)}, new int[] {grau(230)}, Relief.Art.SLOPE, Relief.Art.OHNE);
        assertEquals(grau(230), k[0]);
        // Ohne den Rest käme 206 heraus: genau dafür liefert der Server ihn.
        k = new int[] {grau(255)};
        Relief.rechne(k, new int[] {code(8)}, new int[] {0}, Relief.Art.SLOPE, Relief.Art.OHNE);
        assertEquals(grau(206), k[0]);
    }

    @Test
    void alphaBleibtUndOhneSchichtNichts() {
        // Alpha 0 bleibt ganz, auch wo Schicht und Rest etwas sagen; Alpha 128 bleibt 128.
        int durchsichtig = 0x00FA0000, halb = 0x80FAFAFA;
        int[] k = {durchsichtig, halb};
        Relief.rechne(k, new int[] {code(8), code(8)}, new int[] {grau(10), 0}, Relief.Art.SLOPE, Relief.Art.OHNE);
        assertEquals(durchsichtig, k[0]);
        assertEquals(0x80CACACA, k[1]);
        // Ohne Schicht gilt Code 4: die Kachel bleibt.
        k = new int[] {grau(123)};
        Relief.rechne(k, null, null, Relief.Art.SLOPE, Relief.Art.OHNE);
        assertEquals(grau(123), k[0]);
    }

    @Test
    void restNurBeiAbgeschnittenen() {
        // Nur ein sichtbarer Pixel mit einem Kanal 255 kann abgeschnitten sein; durchsichtig zählt nicht.
        assertTrue(Relief.abgeschnitten(new int[] {grau(10), 0xFF10FF10}));
        assertFalse(Relief.abgeschnitten(new int[] {grau(254), 0x00FFFFFF}));
    }

    @Test
    void ausShadowsWirdSlope() {
        // Gebacken mit shadows bei Code 13 (eben, im Schatten, f 0,75): 150 ist ohne 200, mit slope bleibt es 200.
        int[] k = {grau(150)};
        Relief.rechne(k, new int[] {code(13)}, null, Relief.Art.SHADOWS, Relief.Art.SLOPE);
        assertEquals(grau(200), k[0]);
    }

    private static int[] kachel(String pfad) throws IOException {
        try (java.io.InputStream rein = ReliefTest.class.getResourceAsStream("/relief/" + pfad)) {
            return rein == null ? null : Kacheln.dekodiere(rein.readAllBytes(), 256).argb();
        }
    }

    @Test
    void wieDerRenderer() throws IOException {
        // Zwei Basiskacheln der Testwelt von Renderer 0.19.0, siehe src/test/resources/relief/LIESMICH.txt: aus slope mit
        // Schicht und Rest gerechnet treffen ohne und shadows die Kacheln des Renderers je Kanal auf ±1, wie map.json verspricht.
        int pixel = 0, eins = 0, rest = 0, schatten = 0;
        for (String k : new String[] {"8/-44/-52.webp", "8/-45/-53.webp"}) {
            int[] slope = kachel("slope/" + k), schicht = kachel("slope/relief/" + k), r = kachel("slope/relief/rest/" + k);
            for (int p = 0; p < r.length; p++) {
                rest += r[p] >>> 24 == 255 ? 1 : 0;
                schatten += (schicht[p] >> 16 & 0xFF) >= 9 ? 1 : 0;
            }
            for (Relief.Art art : new Relief.Art[] {Relief.Art.OHNE, Relief.Art.SHADOWS}) {
                int[] ist = slope.clone(), soll = kachel(art.name + "/" + k);
                Relief.rechne(ist, schicht, r, Relief.Art.SLOPE, art);
                for (int p = 0; p < ist.length; p++) {
                    assertEquals(soll[p] >>> 24, ist[p] >>> 24, "Alpha " + art + " " + k + " " + p);
                    int d = 0;
                    for (int s = 0; s <= 16; s += 8) {
                        d = Math.max(d, Math.abs((ist[p] >> s & 0xFF) - (soll[p] >> s & 0xFF)));
                    }
                    assertTrue(d <= 1, art + " " + k + " Pixel " + p + ": " + d);
                    pixel++;
                    eins += d;
                }
            }
        }
        // Die Kacheln haben abgeschnittene Pixel und Schatten, sonst prüfte das wenig; und nicht jeder Pixel liegt um 1 daneben.
        assertTrue(rest > 1000 && schatten > 1000, "Rest " + rest + ", Schatten " + schatten);
        assertTrue(eins < pixel / 4, eins + " von " + pixel + " um 1 daneben");
    }

    @Test
    void codeJeSpalte() {
        // Eben: 4. Steigt nach Osten um 3 und nach Süden um 2: n = 5, begrenzt 4, Code 8. Fehlt ein Nachbar, gilt er gleich hoch.
        java.util.Map<Long, Integer> h = new java.util.HashMap<>();
        Relief.Hoehen hoehen = (x, z) -> h.getOrDefault((long) x << 32 | (z & 0xFFFFFFFFL), Relief.FEHLT);
        java.util.function.BiConsumer<int[], Integer> setze = (p, v) -> h.put((long) p[0] << 32 | (p[1] & 0xFFFFFFFFL), v);
        for (int x = -40; x <= 2; x++) {
            for (int z = -40; z <= 2; z++) {
                setze.accept(new int[] {x, z}, 64);
            }
        }
        assertEquals(4, Relief.code(hoehen, 0, 0));
        setze.accept(new int[] {1, 0}, 67);
        setze.accept(new int[] {0, 1}, 66);
        assertEquals(8, Relief.code(hoehen, 0, 0));
        h.remove((long) 1 << 32);
        assertEquals(6, Relief.code(hoehen, 0, 0));
        // Schatten: 20 Blöcke nach Nordwesten eine Spalte 29 höher, mehr als 20 · √2 = 28,3: im Schatten, Code 6 + 9.
        setze.accept(new int[] {-20, -20}, 64 + 29);
        assertEquals(15, Relief.code(hoehen, 0, 0));
        setze.accept(new int[] {-20, -20}, 64 + 28);
        assertEquals(6, Relief.code(hoehen, 0, 0));
        // Ohne die Spalte selbst: 4.
        assertEquals(4, Relief.code(hoehen, 100, 100));
    }

    @Test
    void fehlendeChunks() {
        // Im Fenster fehlen der Chunk 2 nordwestlich, Spalten 0 bis 15, und östlich die letzte Spalte, die Chunks dx = 1.
        int[] hoehen = new int[Relief.SEITE * Relief.SEITE];
        java.util.Arrays.fill(hoehen, 64);
        for (int z = 0; z < Relief.SEITE; z++) {
            for (int x = 0; x < Relief.SEITE; x++) {
                if (x < 16 && z < 16 || x == Relief.SEITE - 1) {
                    hoehen[z * Relief.SEITE + x] = Relief.FEHLT;
                }
            }
        }
        int maske = Relief.fehlende(hoehen);
        assertEquals(1 | 1 << 3 | 1 << 7 | 1 << 11 | 1 << 15, maske);
        assertTrue(Relief.fehlte(maske, -2, -2));
        assertTrue(Relief.fehlte(maske, 1, 0));
        assertFalse(Relief.fehlte(maske, -1, -1));
        assertFalse(Relief.fehlte(maske, 0, 0));
    }

    @Test
    void codesUndFaktorJeChunk() {
        // Die Höhen um einen Chunk ab 32 Blöcken nordwestlich: eben 64, seine Spalte (1, 0) 66. Westlich davon steigt es nach
        // Osten, Code 6; sie selbst ist eben umgeben, 4; östlich davon fällt es, 2.
        int[] hoehen = new int[Relief.SEITE * Relief.SEITE];
        java.util.Arrays.fill(hoehen, 64);
        hoehen[32 * Relief.SEITE + 33] = 66;
        byte[] codes = Relief.codes(hoehen);
        assertEquals(6, codes[0]);
        assertEquals(4, codes[1]);
        assertEquals(2, codes[2]);
        // Bei 2 px je Block: Code 8 mit slope, 1,24: 100 wird 124, 255 bleibt 255; Alpha 0 bleibt; Code 4 bleibt.
        codes[0] = 8;
        int[] pixel = new int[32 * 32];
        pixel[0] = grau(100);
        pixel[1] = grau(255);
        pixel[32] = 0x00646464;
        pixel[2] = grau(100);
        Relief.auf(pixel, 2, codes, Relief.Art.SLOPE);
        assertEquals(grau(124), pixel[0]);
        assertEquals(grau(255), pixel[1]);
        assertEquals(0x00646464, pixel[32]);
        assertEquals(grau(100), pixel[2]);
    }

    @Test
    void codesWieDerRenderer() throws IOException {
        // Die Höhen der Testwelt (hoehen.bin, siehe hoehen.py) ergeben genau die Schicht von Renderer 0.19.0 für dieselben zwei
        // Basiskacheln, je Block ein Code, bei scale 4 je 4 × 4 Pixel.
        java.nio.ByteBuffer roh;
        try (java.io.InputStream rein = ReliefTest.class.getResourceAsStream("/relief/hoehen.bin")) {
            roh = java.nio.ByteBuffer.wrap(rein.readAllBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        }
        int x0 = roh.getInt(), z0 = roh.getInt(), breite = roh.getInt(), tiefe = roh.getInt();
        short[] werte = new short[breite * tiefe];
        roh.asShortBuffer().get(werte);
        Relief.Hoehen hoehen = (x, z) -> {
            int i = x - x0, j = z - z0;
            return i < 0 || j < 0 || i >= breite || j >= tiefe || werte[j * breite + i] == Short.MIN_VALUE ? Relief.FEHLT : werte[j * breite + i];
        };
        int gleich = 0, alle = 0;
        for (int[] k : new int[][] {{-44, -52}, {-45, -53}}) {
            int[] schicht = kachel("slope/relief/8/" + k[0] + "/" + k[1] + ".webp");
            for (int bz = 0; bz < 64; bz++) {
                for (int bx = 0; bx < 64; bx++) {
                    int soll = schicht[(bz * 4 + 1) * 256 + bx * 4 + 1] >> 16 & 0xFF;
                    gleich += Relief.code(hoehen, k[0] * 64 + bx, k[1] * 64 + bz) == soll ? 1 : 0;
                    alle++;
                }
            }
        }
        assertEquals(8192, alle);
        assertEquals(alle, gleich, gleich + " von " + alle + " Codes wie der Renderer");
    }

    @Test
    void satzAusMapJson(@TempDir Path ordner) throws IOException {
        Path karte = ordner.resolve("map.json");
        Files.writeString(karte, "{\"relief\":\"slope\",\"reliefTiles\":\"relief/{z}/{x}/{y}.webp\"}", StandardCharsets.UTF_8);
        Relief.Satzrelief s = Relief.von(ordner);
        assertEquals(new Relief.Satzrelief(Relief.Art.SLOPE, true, false), s);
        assertFalse(s.noetig(Relief.Art.SLOPE));
        assertTrue(s.noetig(Relief.Art.OHNE));
        assertTrue(s.noetig(Relief.Art.SHADOWS));
        // Relief ohne Schicht (Renderer vor 0.19.0) oder in einem anderen Muster: nichts zu rechnen.
        Files.writeString(karte, "{\"relief\":\"shadows\"}", StandardCharsets.UTF_8);
        assertEquals(new Relief.Satzrelief(Relief.Art.SHADOWS, false, false), Relief.von(ordner));
        Files.writeString(karte, "{\"relief\":\"slope\",\"reliefTiles\":\"relief2/{z}/{x}/{y}.webp\"}", StandardCharsets.UTF_8);
        assertFalse(Relief.von(ordner).noetig(Relief.Art.OHNE));
        // Ohne Relief, mit unbekanntem oder ohne map.json: ohne.
        Files.writeString(karte, "{\"relief\":\"bergig\"}", StandardCharsets.UTF_8);
        assertEquals(new Relief.Satzrelief(Relief.Art.OHNE, false, false), Relief.von(ordner));
        Files.delete(karte);
        assertArrayEquals(new Object[] {Relief.Art.OHNE, false}, new Object[] {Relief.von(ordner).gebacken(), Relief.von(ordner).schicht()});
    }

    @Test
    void satzDerEigenenKarte(@TempDir Path welt) throws IOException {
        // Die eigene Karte: Kacheln ohne Relief, die Schicht als PNG; jede Art ausser Flach wird gerechnet.
        Path baum = Selbst.anlegen(welt, "minecraft:overworld", 4);
        Relief.Satzrelief s = Relief.von(baum.resolve("4"));
        assertEquals(new Relief.Satzrelief(Relief.Art.OHNE, true, true), s);
        assertFalse(s.noetig(Relief.Art.OHNE));
        assertTrue(s.noetig(Relief.Art.SLOPE));
    }

    @Test
    void schichtJeChunk() {
        // Bei 2 px je Block: der Block (1, 0) mit Code 8 in den Pixeln x 2 bis 3, y 0 bis 1, grau, Alpha 255; die übrigen 4.
        byte[] codes = new byte[256];
        java.util.Arrays.fill(codes, (byte) 4);
        codes[1] = 8;
        int[] pixel = Relief.schicht(codes, 2);
        assertEquals(32 * 32, pixel.length);
        assertEquals(0xFF080808, pixel[2]);
        assertEquals(0xFF080808, pixel[32 + 3]);
        assertEquals(0xFF040404, pixel[1]);
        assertEquals(0xFF040404, pixel[4]);
        assertEquals(0xFF040404, pixel[2 * 32 + 2]);
    }

    @Test
    void durchsichtigeSchichtGiltVier() {
        // Die eigene Karte ohne Relief in den Kacheln: wo die Schicht durchsichtig ist, bleibt die Kachel, sonst mal den Faktor.
        int[] kachel = {0xFF646464, 0xFF646464};
        Relief.rechne(kachel, new int[] {0, 0xFF080808}, null, Relief.Art.OHNE, Relief.Art.SLOPE);
        assertEquals(0xFF646464, kachel[0]);
        assertEquals(0xFF7C7C7C, kachel[1]);
    }
}
