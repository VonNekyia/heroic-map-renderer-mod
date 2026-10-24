package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Die Rahmen: Palette, Bänder, Ring, Ornamente und Griff. Siehe docs/rahmen.md. */
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
        // drehung=aussen, auch ein altes aussen=ja: Die Marken zeigen von der Mitte weg; ohne drehen sie starr mit der Karte.
        assertEquals(Skin.Stellung.AUSSEN, Skin.lies("t", "#000000\n#111111", "schatten=ja\naussen=ja\n", 7).marken.stellung());
        assertEquals(Skin.Stellung.AUSSEN, Skin.lies("t", "#000000\n#111111", "drehung=aussen", 7).marken.stellung());
        assertEquals(Skin.Stellung.AUFRECHT, Skin.lies("t", "#000000\n#111111", "drehung=aufrecht", 7).marken.stellung());
        assertEquals(Skin.Stellung.KARTE, Skin.lies("t", "#000000\n#111111", "schatten=ja", 7).marken.stellung());
        assertThrows(IllegalArgumentException.class, () -> Skin.lies("t", "#000000\n#111111", "drehung=schief", 7));
        // Je Schlüssel gilt die letzte Zeile; so überschreibt eine Zeile aus varianten.txt ihren Ordner.
        assertFalse(Skin.lies("t", "#000000\n#111111", "schatten=ja\nschatten=nein", 7).mitSchatten);
        assertThrows(IllegalArgumentException.class, () -> Skin.lies("t", "#000000", "", 7));
        assertThrows(IllegalArgumentException.class, () -> Skin.lies("t", "#000000 #111111 #222222", "", 7));
        assertThrows(IllegalArgumentException.class, () -> Skin.lies("t", "#00000", "", 7));
        assertThrows(IllegalArgumentException.class, () -> Skin.lies("t", "// nur Kommentar", "", 7));
    }

    @Test
    void dieFamilienDesPakets() throws IOException {
        assertEquals(List.of(Skin.OHNE, Skin.BIOM, "schild", "rose", "lettern"), Skin.FAMILIEN);
        // Drei Bänder und mehr nur bei „schild“, wie die alten grau, holz und papier; sonst zwei.
        Map<String, Integer> baender = Map.of("schild/grau", 3, "schild/gold", 4, "schild/papier", 5);
        Map<String, JsonObject> sprachen = new HashMap<>();
        for (String sprache : List.of("de_de", "en_us")) {
            try (InputStream rein = SkinTest.class.getResourceAsStream("/assets/heroicmap/lang/" + sprache + ".json")) {
                sprachen.put(sprache, JsonParser.parseString(new String(rein.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            }
        }
        for (String familie : Skin.FAMILIEN) {
            for (JsonObject lang : sprachen.values()) {
                assertTrue(lang.has("heroicmap.rahmen." + familie) && lang.has("heroicmap.rahmen." + familie + ".beschreibung"), familie);
            }
        }
        int biomRand = 0, letternRand = 0;
        for (String familie : Skin.MIT_VARIANTEN) {
            for (Skin.Variante v : Skin.liesVarianten(text(familie, "varianten.txt"))) {
                String ordner = familie + "/" + v.ordner(), wo = familie + "/" + v.name();
                // Wie Skin.laden: die Grössen der Marken, die es gibt, und info.txt mit den Schaltern der Zeile dahinter.
                Map<String, int[]> bilder = new HashMap<>();
                for (String teil : List.of("norden", "marke", "marke_quer", "osten", "sueden", "westen")) {
                    BufferedImage b = bild(ordner, teil);
                    if (b != null) {
                        bilder.put(teil, new int[] {b.getWidth(), b.getHeight()});
                    }
                }
                Skin s = Skin.lies(wo, ordner, text(ordner, "palette.txt"), text(ordner, "info.txt") + "\n" + String.join("\n", v.schalter()), bilder);
                assertEquals(baender.getOrDefault(ordner, 2), s.baender(), wo);
                List<String> teile = s.marken.eigene() ? List.of("griff", "norden", "osten", "sueden", "westen")
                        : List.of("griff", "norden", "marke", "marke_quer");
                for (String teil : teile) {
                    assertNotNull(bild(ordner, teil), wo + "/" + teil);
                    assertNotNull(bild(ordner, teil + "_aktiv"), wo + "/" + teil + "_aktiv");
                }
                // Stellung und Lage, wie der Designer sie je Familie festlegt.
                if (familie.startsWith(Skin.BIOM + "/")) {
                    assertEquals(Skin.Stellung.AUSSEN, s.marken.stellung(), wo);
                    BufferedImage m = bild(ordner, "marke");
                    Skin.Platz kante = new Skin.Platz(Skin.Lage.KANTE, m.getWidth(), m.getHeight(), 1);
                    assertArrayEquals(new Skin.Platz[] {null, kante, kante, kante}, s.marken.plaetze(), wo);
                } else if ("lettern".equals(familie)) {
                    assertEquals(Skin.Stellung.AUFRECHT, s.marken.stellung(), wo);
                    assertTrue(s.marken.eigene(), wo);
                    Skin.Lage lage = v.name().endsWith("_aussen") ? Skin.Lage.AUSSEN : Skin.Lage.INNEN;
                    for (int i = 0; i < 4; i++) {
                        assertEquals(lage, s.marken.platz(i).lage(), wo + " " + i);
                    }
                } else {
                    assertNull(s.marken.plaetze(), wo);
                }
                if ("schild".equals(familie) || "rose".equals(familie)) {
                    assertNotNull(bild(ordner, "tafel"), wo + ": Grund der Tafel");
                }
                for (JsonObject lang : sprachen.values()) {
                    assertTrue(lang.has("heroicmap.rahmen." + familie.replace('/', '.') + "." + v.name()), wo + ": Name");
                }
                // Die zier gibt es nicht mehr, auch nicht im Jar.
                assertNull(bild(ordner, "zier"), wo);
                // Mit Rahmen hält die Minimap so viel Abstand, dass jede Marke in jeder Drehung ganz auf dem Schirm bleibt, auch ihr
                // Schatten um (+1, +1); siehe auch letternLiegenInnenUndAussen.
                int rand = Minimap.rand(s);
                assertTrue(rand + s.baender() / 2.0 - s.groesste * Math.sqrt(2) / 2 - 1 >= 0, wo);
                biomRand = familie.startsWith(Skin.BIOM + "/") ? Math.max(biomRand, rand) : biomRand;
                letternRand = "lettern".equals(familie) ? Math.max(letternRand, rand) : letternRand;
            }
        }
        // „biom“ hält den grössten Abstand seiner Kategorien, so springt die Minimap beim Wechsel nicht; „lettern“ aussen 11 wie
        // beim Designer.
        assertEquals(11, biomRand);
        assertEquals(11, letternRand);
        // Die alten Skins sind weg; eine gespeicherte Wahl zieht um, siehe MinimapTest.
        for (String alt : Skin.ALT.keySet()) {
            assertNull(SkinTest.class.getResourceAsStream(ORDNER + alt + "/palette.txt"), alt);
        }
    }

    @Test
    void variantenTxt() {
        List<Skin.Variante> v = Skin.liesVarianten("// Kopf\ntinte_innen ordner=tinte lage=innen\n\ntinte_aussen ordner=tinte lage=aussen // Kommentar\ngrau\n");
        assertEquals(List.of(new Skin.Variante("tinte_innen", "tinte", List.of("lage=innen")),
                new Skin.Variante("tinte_aussen", "tinte", List.of("lage=aussen")), new Skin.Variante("grau", "grau", List.of())), v);
        assertThrows(IllegalArgumentException.class, () -> Skin.liesVarianten("// nur Kommentar"));
        assertThrows(IllegalArgumentException.class, () -> Skin.liesVarianten("Grün"));
        assertThrows(IllegalArgumentException.class, () -> Skin.liesVarianten("gold ordner=../x"));
        assertThrows(IllegalArgumentException.class, () -> Skin.liesVarianten("gold lage"));
    }

    @Test
    void abstandZumRandMitRahmen() {
        assertEquals(Minimap.RAND, Minimap.rand(null));
        assertEquals(Minimap.RAND, Minimap.rand(Skin.lies("klein", "#000000\n#000000", "", 5)));
        assertEquals(5, Minimap.rand(Skin.lies("grau", "#000000\n#000000", "", 7)));
        assertEquals(13, Minimap.rand(Skin.lies("uhr", "#000000\n#000000", "", 17)));
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
        // Seite 128, drei Bänder, von Hand gerechnet: In der mittleren Zeile liegen die Pixel 0 bis 2 im Ring, ab 3 die
        // Karte. Auf der Diagonale (10, 10) ausserhalb, (19, 19) im zweiten Band, (20, 20) im dritten, (21, 21) die Karte.
        // Wie weit die Karte unter den Ring reicht, prüft DrehungTest.mitRahmenDecktDerRingDenRand.
        int s = 128;
        Skin skin = Skin.lies("t", "#111111\n#222222 #333333\n#444444", "", 7);
        assertEquals(0xFF111111, skin.ringFarbe(0, 64, s));
        assertEquals(0xFF222222, skin.ringFarbe(1, 64, s));
        assertEquals(0xFF444444, skin.ringFarbe(2, 64, s));
        assertEquals(0, skin.ringFarbe(3, 64, s));
        assertEquals(0xFF333333, skin.ringFarbe(126, 64, s));
        assertEquals(0xFF111111, skin.ringFarbe(127, 64, s));
        assertEquals(0, skin.ringFarbe(10, 10, s));
        assertEquals(0xFF222222, skin.ringFarbe(19, 19, s));
        assertEquals(0xFF444444, skin.ringFarbe(20, 20, s));
        assertEquals(0, skin.ringFarbe(21, 21, s));
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
        // Die halbe Diagonale, aufgerundet: So bleibt eine gedrehte Verzierung ganz auf dem Schirm.
        assertEquals(5, Skin.einrueckung(7));
        assertEquals(11, Skin.einrueckung(15));
        for (int w = 5; w <= 16; w++) {
            for (int grad = 0; grad < 90; grad++) {
                double a = Math.toRadians(grad), halb = w * (Math.cos(a) + Math.sin(a)) / 2;
                assertTrue(halb <= Skin.einrueckung(w), w + " bei " + grad + "°");
            }
        }
    }

    @Test
    void biomMarkenLiegenAmRahmen() throws IOException {
        // Bei „biom“ liegen O, S und W samt Schatten um (+1, +1) innen an der äusseren Kante und ragen nicht darüber, eckig und
        // rund, genordet und gedreht, bei GS 1 bis 4; N bleibt auf der Mitte der Bänder. Wunsch des Users vom 23.10.
        // Siehe docs/rahmen.md, „Nach aussen“.
        for (String kategorie : Biom.KATEGORIEN) {
            String name = Skin.BIOM + "/" + kategorie + "/standard";
            BufferedImage bild = bild(name, "marke");
            int w = bild.getWidth(), h = bild.getHeight();
            Skin skin = Skin.lies(name, name, text(name, "palette.txt"), text(name, "info.txt"), Map.of("norden", new int[] {15, 15}, "marke", new int[] {w, h}));
            for (int seite : new int[] {64, 101, 128, 256}) {
                Minimap.Rahmen r = new Minimap.Rahmen(10, 21, seite);
                double cx = r.x() + seite / 2.0, cy = r.y() + seite / 2.0, halb = seite / 2.0;
                for (boolean rund : new boolean[] {false, true}) {
                    for (int k = 1; k <= 4; k++) {
                        for (int grad = -1; grad < 360; grad += 3) {
                            Drehung.Lage lage = grad < 0 ? null : Drehung.Lage.von(Math.toRadians(grad), 0, 0, 0, 0);
                            List<Minimap.Ornament> marken = Minimap.ornamente(r, 2, rund, skin.marken, lage, k, 0, -1, false, -1);
                            String wo = name + ", Seite " + seite + (rund ? " rund" : " eckig") + ", GS " + k + ", " + (grad < 0 ? "genordet" : grad + "°");
                            assertEquals(Minimap.ornamente(r, 2, rund, Skin.Marken.AUSSEN, lage, k, 0, -1, false, -1).get(3), marken.get(3), "N bleibt, " + wo);
                            for (int i = 0; i < 3; i++) {
                                Minimap.Ornament o = marken.get(i);
                                String m = "NOSW".charAt(i + 1) + ", " + wo;
                                if (lage == null) {
                                    // Ungedreht ganze Einheiten wie in Skin.blit: O und W quer. Das Bild und sein Schatten, ohne die zwei
                                    // Ecken des Kastens, die keins von beiden deckt.
                                    boolean quer = i != 1;
                                    int bw = quer ? h : w, bh = quer ? w : h, x0 = Skin.lage(o.x(), bw), y0 = Skin.lage(o.y(), bh);
                                    for (int y = y0; y <= y0 + bh; y++) {
                                        for (int x = x0; x <= x0 + bw; x++) {
                                            if (x == x0 + bw && y == y0 || x == x0 && y == y0 + bh) {
                                                continue;
                                            }
                                            double mx = x + 0.5 - cx, my = y + 0.5 - cy;
                                            assertTrue(rund ? Math.hypot(mx, my) <= halb : Math.max(Math.abs(mx), Math.abs(my)) <= halb,
                                                    m + ": Pixel " + x + ", " + y + " ausserhalb");
                                        }
                                    }
                                    // Anliegend: eckig genau an der Kante, rund höchstens eine Einheit davor.
                                    int luecke = i == 0 ? r.x() + seite - (x0 + bw + 1) : i == 1 ? r.y() + seite - (y0 + bh + 1) : x0 - r.x();
                                    assertTrue(rund ? luecke >= 0 && luecke <= 1 : luecke == 0, m + ": " + luecke + " Einheiten vor der Kante");
                                } else {
                                    // Gedreht die Ecken des gedrehten Bilds und seines Schattens; die Mitte liegt auf ganzen Pixeln.
                                    double nx = Math.sin(o.winkel()), ny = -Math.cos(o.winkel()), weit = 0, spiel = 0.5 / k * Math.sqrt(2) + 1e-9;
                                    for (int j = 0; j < 8; j++) {
                                        double a = (j & 1) == 0 ? h / 2.0 : -h / 2.0, b = (j & 2) == 0 ? w / 2.0 : -w / 2.0, sch = j < 4 ? 0 : 1;
                                        double x = o.x() + a * nx - b * ny + sch - cx, y = o.y() + a * ny + b * nx + sch - cy;
                                        weit = Math.max(weit, rund ? Math.hypot(x, y) : Math.max(Math.abs(x), Math.abs(y)));
                                    }
                                    assertTrue(weit <= halb + spiel, m + ": ragt " + (weit - halb) + " über die Kante");
                                    assertTrue(weit >= halb - spiel, m + ": liegt " + (halb - weit) + " vor der Kante");
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void lageInInfoTxt() {
        // lage_norden gilt wie lage, wenn die Zeile fehlt; kante nur mit drehung=aussen, innen und aussen nur mit drehung=aufrecht.
        Map<String, int[]> bilder = Map.of("norden", new int[] {15, 15}, "marke", new int[] {5, 9}, "marke_quer", new int[] {9, 5});
        Skin.Platz n = new Skin.Platz(Skin.Lage.KANTE, 15, 15, 1), m = new Skin.Platz(Skin.Lage.KANTE, 5, 9, 1);
        assertNull(Skin.lies("t", "t", "#000000\n#111111", "aussen=ja\nlage=mitte", bilder).marken.plaetze());
        assertArrayEquals(new Skin.Platz[] {n, m, m, m}, Skin.lies("t", "t", "#000000\n#111111", "schatten=ja\naussen=ja\nlage=kante", bilder).marken.plaetze());
        Skin.Platz m0 = new Skin.Platz(Skin.Lage.KANTE, 5, 9, 0);
        assertArrayEquals(new Skin.Platz[] {null, m0, m0, m0},
                Skin.lies("t", "t", "#000000\n#111111", "drehung=aussen\nlage=kante\nlage_norden=mitte", bilder).marken.plaetze());
        // Aufrecht ohne eigene Bilder nimmt O und W marke_quer, S marke, so wie sie stehen.
        Skin.Platz[] innen = Skin.lies("t", "t", "#000000\n#111111", "drehung=aufrecht\nlage=innen", bilder).marken.plaetze();
        assertEquals(new Skin.Platz(Skin.Lage.INNEN, 9, 5, 0), innen[1]);
        assertEquals(new Skin.Platz(Skin.Lage.INNEN, 5, 9, 0), innen[2]);
        assertThrows(IllegalArgumentException.class, () -> Skin.lies("t", "t", "#000000\n#111111", "lage=kante", bilder));
        assertThrows(IllegalArgumentException.class, () -> Skin.lies("t", "t", "#000000\n#111111", "aussen=ja\nlage=innen", bilder));
        assertThrows(IllegalArgumentException.class, () -> Skin.lies("t", "t", "#000000\n#111111", "drehung=aufrecht\nlage=kante", bilder));
        assertThrows(IllegalArgumentException.class, () -> Skin.lies("t", "t", "#000000\n#111111", "drehung=aufrecht\nlage=schief", bilder));
    }

    @Test
    void letternLiegenInnenUndAussen() throws IOException {
        // „lettern“: aufrecht, innen ganz in der Karte mit einer Einheit Abstand zu den Bändern, aussen ganz ausserhalb mit einer
        // Einheit Abstand, eckig und rund, genordet und gedreht; aussen nie weiter als der Abstand zum Rand. Siehe docs/rahmen.md, „Lage“.
        for (String lage : List.of("innen", "aussen")) {
            String ordner = "lettern/tinte";
            Map<String, int[]> bilder = new HashMap<>();
            for (String teil : List.of("norden", "osten", "sueden", "westen")) {
                BufferedImage b = bild(ordner, teil);
                bilder.put(teil, new int[] {b.getWidth(), b.getHeight()});
            }
            Skin skin = Skin.lies("lettern/tinte_" + lage, ordner, text(ordner, "palette.txt"), text(ordner, "info.txt") + "\nlage=" + lage, bilder);
            int baender = skin.baender(), rand = Minimap.rand(skin);
            for (int seite : new int[] {64, 128, 256}) {
                Minimap.Rahmen r = new Minimap.Rahmen(0, 0, seite);
                double halb = seite / 2.0;
                for (boolean rund : new boolean[] {false, true}) {
                    for (int grad = -1; grad < 360; grad += 5) {
                        Drehung.Lage d = grad < 0 ? null : Drehung.Lage.von(Math.toRadians(grad), 0, 0, 0, 0);
                        List<Minimap.Ornament> marken = Minimap.ornamente(r, baender, rund, skin.marken, d, 2, 0, -1, false, -1);
                        for (int j = 0; j < 4; j++) {
                            Minimap.Ornament o = marken.get(j);
                            String wo = lage + ", Seite " + seite + (rund ? " rund" : " eckig") + ", " + (grad < 0 ? "genordet" : grad + "°") + ", " + j;
                            // Aufrecht: nie gedreht, das eigene Bild je Richtung. Gedreht liegt die Mitte auf ganzen Pixeln, bei GS 2 auf halben
                            // Einheiten; daher das Spiel.
                            assertEquals(0, o.winkel(), wo);
                            assertEquals(Skin.EIGENE_JE_RICHTUNG[(j + 1) % 4], o.teil(), wo);
                            int[] b = bilder.get(List.of("osten", "sueden", "westen", "norden").get(j));
                            double w = b[0] / 2.0, h = b[1] / 2.0, x = o.x() - halb, y = o.y() - halb, spiel = 0.25 * Math.sqrt(2) + 1e-9;
                            if ("innen".equals(lage)) {
                                // Der weiteste Punkt des Bilds liegt auf der Linie eine Einheit innerhalb der Bänder.
                                double innen = halb - baender - 1;
                                double weit = rund ? Math.hypot(Math.abs(x) + w, Math.abs(y) + h) : Math.max(Math.abs(x) + w, Math.abs(y) + h);
                                assertEquals(innen, weit, spiel + 0.01, wo);
                            } else {
                                // Der nächste Punkt des Bilds liegt eine Einheit ausserhalb der Minimap; samt Schatten bleibt es im Abstand zum Rand.
                                double dx = Math.max(0, Math.abs(x) - w), dy = Math.max(0, Math.abs(y) - h);
                                double nah = rund ? Math.hypot(dx, dy) : Math.max(dx, dy);
                                assertEquals(halb + 1, nah, spiel + 0.01, wo);
                                assertTrue(Math.max(Math.abs(x) + w, Math.abs(y) + h) + 1 <= halb + rand + spiel, wo + ": über den Abstand zum Rand");
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void spiegelnJeEcke() {
        // griff ist für unten rechts gezeichnet; Ecken 0 oben links bis 3 unten rechts. Die Marken, Ecke 0, nie.
        boolean[][] griff = {{true, true}, {false, true}, {true, false}, {false, false}};
        for (int e = 0; e < 4; e++) {
            assertEquals(griff[e][0], Skin.spiegeltX(e, true), "griff " + e);
            assertEquals(griff[e][1], Skin.spiegeltY(e, true), "griff " + e);
        }
        assertFalse(Skin.spiegeltX(0, false));
        assertFalse(Skin.spiegeltY(0, false));
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
