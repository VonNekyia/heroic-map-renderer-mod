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
    void satzAusMapJson(@TempDir Path ordner) throws IOException {
        Path karte = ordner.resolve("map.json");
        Files.writeString(karte, "{\"relief\":\"slope\",\"reliefTiles\":\"relief/{z}/{x}/{y}.webp\"}", StandardCharsets.UTF_8);
        Relief.Satzrelief s = Relief.von(ordner);
        assertEquals(new Relief.Satzrelief(Relief.Art.SLOPE, true), s);
        assertFalse(s.noetig(Relief.Art.SLOPE));
        assertTrue(s.noetig(Relief.Art.OHNE));
        assertTrue(s.noetig(Relief.Art.SHADOWS));
        // Relief ohne Schicht (Renderer vor 0.19.0) oder in einem anderen Muster: nichts zu rechnen.
        Files.writeString(karte, "{\"relief\":\"shadows\"}", StandardCharsets.UTF_8);
        assertEquals(new Relief.Satzrelief(Relief.Art.SHADOWS, false), Relief.von(ordner));
        Files.writeString(karte, "{\"relief\":\"slope\",\"reliefTiles\":\"relief2/{z}/{x}/{y}.webp\"}", StandardCharsets.UTF_8);
        assertFalse(Relief.von(ordner).noetig(Relief.Art.OHNE));
        // Ohne Relief, mit unbekanntem oder ohne map.json: ohne.
        Files.writeString(karte, "{\"relief\":\"bergig\"}", StandardCharsets.UTF_8);
        assertEquals(new Relief.Satzrelief(Relief.Art.OHNE, false), Relief.von(ordner));
        Files.delete(karte);
        assertArrayEquals(new Object[] {Relief.Art.OHNE, false}, new Object[] {Relief.von(ordner).gebacken(), Relief.von(ordner).schicht()});
    }
}
