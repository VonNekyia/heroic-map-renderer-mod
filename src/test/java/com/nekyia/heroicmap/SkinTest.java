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
                // Stellung und Lage, wie sie je Familie festgelegt sind: biom O, S, W aussen auf dem Rand, N auf den Bändern,
                // lettern mit dem Kopf nach aussen. Siehe docs/rahmen.md, „Lage“, und 0041.
                if (familie.startsWith(Skin.BIOM + "/")) {
                    assertEquals(Skin.Stellung.AUSSEN, s.marken.stellung(), wo);
                    BufferedImage m = bild(ordner, "marke");
                    Skin.Platz aussen = new Skin.Platz(Skin.Lage.AUSSEN, m.getWidth(), m.getHeight(), 1);
                    assertArrayEquals(new Skin.Platz[] {null, aussen, aussen, aussen}, s.marken.plaetze(), wo);
                } else if ("lettern".equals(familie)) {
                    assertEquals(Skin.Stellung.AUSSEN, s.marken.stellung(), wo);
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
                // Schatten um (+1, +1); siehe auch jedeLageMitJederDrehung.
                int rand = Minimap.rand(s);
                assertTrue(rand + s.baender() / 2.0 - s.groesste * Math.sqrt(2) / 2 - 1 >= 0, wo);
                biomRand = familie.startsWith(Skin.BIOM + "/") ? Math.max(biomRand, rand) : biomRand;
                letternRand = "lettern".equals(familie) ? Math.max(letternRand, rand) : letternRand;
            }
        }
        // „biom“ hält den grössten Abstand seiner Kategorien, so springt die Minimap beim Wechsel nicht: 16 mit O, S und W aussen,
        // getrieben von den Marken 11 × 8 und 9 × 10. „lettern“ aussen 14 mit dem Kopf nach aussen. Siehe 0041.
        assertEquals(16, biomRand);
        assertEquals(14, letternRand);
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
    void bandNachDerFormel() {
        // Das Beispiel grasland, zwei Bänder, B = 8 Texel, ein Texel je Pixel; die Werte von Hand gerechnet.
        Skin gras = Skin.lies("t", "#1E1B18\n#C8E86A", "", 7);
        // Mitte eines Bands: ohne Fase und Übergang die Farbe der Palette.
        assertEquals(0xFF1E1B18, gras.bandFarbe(2, 1, 1));
        assertEquals(0xFFC8E86A, gras.bandFarbe(6, 0.5, 1));
        // Fase: aussen 7,5 % heller, innen 7,5 % dunkler.
        assertEquals(0xFF2F2C29, gras.bandFarbe(0.5, 1, 1));
        assertEquals(0xFFB9D762, gras.bandFarbe(7.5, 1, 1));
        // Auf der Grenze zweier Bänder je zur Hälfte, jedes mit seiner Fase.
        assertEquals(0xFF748147, gras.bandFarbe(4, 1, 1));
        // Schatten auf die Karte: 38 % am inneren Rand, nach 3 Texeln nichts; aussen nichts.
        assertEquals(0x51000000, gras.bandFarbe(8.5, 1, 1));
        assertEquals(0, gras.bandFarbe(11.5, 1, 1));
        assertEquals(0, gras.bandFarbe(-0.5, 1, 1));
        // Bei GUI-Massstab 1 ist ein Pixel 4 Texel: aussen über ihn weich, innen das Band zu 75 % über 38 % Schatten.
        assertEquals(0xBF292624, gras.bandFarbe(1, 1, 4));
        assertEquals(0xD7A9C459, gras.bandFarbe(7, 1, 4));
        // Ein Band mit Licht und Schatten.
        Skin drei = Skin.lies("t", "#111111\n#222222 #333333\n#444444", "", 7);
        assertEquals(0xFF222222, drei.bandFarbe(6, 1, 1));
        assertEquals(0xFF333333, drei.bandFarbe(6, 0, 1));
        // Der Ring bei Seite 128 und GUI-Skala 4: oben Licht, unten Schatten, je mit 2,5 % Fase; Mitte und Ecke leer.
        // Wie weit die Karte unter den Ring reicht, prüft DrehungTest.mitRahmenDecktDerRingDenRand.
        assertEquals(0xFF282828, drei.ringFarbe(256, 5, 128, 4));
        assertEquals(0xFF383838, drei.ringFarbe(256, 506, 128, 4));
        assertEquals(0, drei.ringFarbe(256, 256, 128, 4));
        assertEquals(0, drei.ringFarbe(0, 0, 128, 4));
        // Eckig ebenso: oben Licht, unten Schatten.
        assertEquals(0xFF282828, drei.eckigFarbe(256, 5, 128, 4));
        assertEquals(0xFF383838, drei.eckigFarbe(256, 506, 128, 4));
    }

    @Test
    void eckigAusStueckenGenau() {
        // Die Stücke aus der Textur der Ecken geben jeden Pixel so, wie ihn die Formel für die ganze Seite rechnet: abgetastet
        // wie mit Nearest an der Mitte des Pixels. Jeder Pixel bis c vom Rand liegt in genau einem Stück, die Mitte in keinem und
        // ist leer; eine Einheit hinter c ganz geprüft, weiter innen jeder siebte.
        for (String palette : new String[] {"#1E1B18\n#C8E86A", "#111111\n#222222 #333333\n#444444",
                "#101010\n#202020\n#303030 #404040\n#505050\n#606060"}) {
            Skin skin = Skin.lies("t", palette, "", 7);
            int c = skin.ecke(), m = 2 * c + 1;
            for (int k = 1; k <= 4; k++) {
                for (int s : new int[] {56, Minimap.GROESSE, Minimap.GROESSTE}) {
                    List<Skin.Stueck> stuecke = Skin.stuecke(0, 0, s, c);
                    for (int py = 0; py < s * k; py++) {
                        for (int px = 0; px < s * k; px++) {
                            int weg = Math.min(Math.min(px, py), Math.min(s * k - 1 - px, s * k - 1 - py));
                            boolean rand = weg < c * k;
                            if (weg >= (c + 1) * k && (px % 7 != 0 || py % 7 != 0)) {
                                continue;
                            }
                            double ux = (px + 0.5) / k, uy = (py + 0.5) / k;
                            int treffer = 0, farbe = 0;
                            for (Skin.Stueck t : stuecke) {
                                if (ux >= t.x1() && ux < t.x2() && uy >= t.y1() && uy < t.y2()) {
                                    treffer++;
                                    double u = t.u1() + (ux - t.x1()) / (t.x2() - t.x1()) * (t.u2() - t.u1());
                                    double v = t.v1() + (uy - t.y1()) / (t.y2() - t.y1()) * (t.v2() - t.v1());
                                    farbe = skin.eckigFarbe((int) Math.floor(u * m * k), (int) Math.floor(v * m * k), m, k);
                                }
                            }
                            String wo = "Bänder " + skin.baender() + ", k " + k + ", Seite " + s + ", Pixel " + px + ", " + py;
                            assertEquals(rand ? 1 : 0, treffer, wo);
                            assertEquals(skin.eckigFarbe(px, py, s, k), farbe, wo);
                        }
                    }
                }
            }
        }
    }

    @Test
    void hdNachFlaeche() {
        // Gleich gross bleibt jeder Pixel, wie er ist, auch durchsichtige mit Farbe: GUI-Massstab 4 zeigt das HD-Bild 1:1.
        int[] bild = {0xFF102030, 0x00FFFFFF, 0x80FF0000, 0xFF00FF00};
        assertArrayEquals(bild, Skin.verkleinere(bild, 2, 2, 2, 2));
        // Auf die Hälfte: 2 × 2 gemittelt, mit vormultipliziertem Alpha. Weiss neben Durchsichtig wird halb deckendes Weiss,
        // kein Grau.
        assertArrayEquals(new int[] {0x80FFFFFF}, Skin.verkleinere(new int[] {0xFFFFFFFF, 0x00000000, 0x00000000, 0xFFFFFFFF}, 2, 2, 1, 1));
        assertArrayEquals(new int[] {0xFF808080}, Skin.verkleinere(new int[] {0xFFFFFFFF, 0xFF000000, 0xFF000000, 0xFFFFFFFF}, 2, 2, 1, 1));
        // 4 auf 3 nach Fläche: Die erste Spalte des Ziels deckt die erste der Quelle ganz und ein Drittel der zweiten.
        int[] streifen = new int[16];
        for (int y = 0; y < 4; y++) {
            streifen[y * 4] = 0xFFFF0000;
        }
        int[] drei = Skin.verkleinere(streifen, 4, 4, 3, 3);
        // HD erst ab GUI-Massstab 2, und nur gewählt.
        assertFalse(Skin.mitHd(true, 1));
        assertTrue(Skin.mitHd(true, 2));
        assertFalse(Skin.mitHd(false, 4));
        for (int y = 0; y < 3; y++) {
            assertEquals(0xBFFF0000, drei[y * 3], "Zeile " + y);
            assertEquals(0, drei[y * 3 + 1]);
            assertEquals(0, drei[y * 3 + 2]);
        }
    }

    @Test
    void hdBilderViermalSoGross() throws IOException {
        // Zu jedem Bild einer Marke und des Griffs im Paket gibt es die HD-Fassung, genau D-mal so breit und hoch, samt _aktiv.
        int gezaehlt = 0;
        for (String familie : Skin.MIT_VARIANTEN) {
            for (Skin.Variante v : Skin.liesVarianten(text(familie, "varianten.txt"))) {
                for (String teil : new String[] {"griff", "norden", "marke", "marke_quer", "osten", "sueden", "westen"}) {
                    for (String aktiv : new String[] {"", "_aktiv"}) {
                        BufferedImage pixel = bild(familie + "/" + v.ordner(), teil + aktiv);
                        if (pixel == null) {
                            continue;
                        }
                        String hd = "/assets/heroicmap/textures/rahmen/" + familie + "/" + v.ordner() + "/" + teil + "_hd" + aktiv + ".png";
                        try (InputStream rein = SkinTest.class.getResourceAsStream(hd)) {
                            assertNotNull(rein, hd);
                            BufferedImage gross = ImageIO.read(rein);
                            assertEquals(pixel.getWidth() * Skin.D, gross.getWidth(), hd);
                            assertEquals(pixel.getHeight() * Skin.D, gross.getHeight(), hd);
                        }
                        gezaehlt++;
                    }
                }
            }
        }
        assertTrue(gezaehlt >= 100, "nur " + gezaehlt);
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
    void kanteLiegtAmRahmen() throws IOException {
        // lage=kante: O, S und W liegen samt Schatten um (+1, +1) innen an der äusseren Kante und ragen nicht darüber, eckig und
        // rund, genordet und gedreht, bei GS 1 bis 4; N bleibt auf der Mitte der Bänder. So lag biom bis 0041. Siehe docs/rahmen.md,
        // „Lage“.
        for (String kategorie : Biom.KATEGORIEN) {
            String name = Skin.BIOM + "/" + kategorie + "/standard";
            BufferedImage bild = bild(name, "marke");
            int w = bild.getWidth(), h = bild.getHeight();
            Skin skin = Skin.lies(name, name, text(name, "palette.txt"), "schatten=ja\ndrehung=aussen\nlage=kante\nlage_norden=mitte",
                    Map.of("norden", new int[] {15, 15}, "marke", new int[] {w, h}));
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
                                    double weit = weit(ecken(o, w, h, 1), cx, cy, rund), spiel = 0.5 / k * Math.sqrt(2) + 1e-9;
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
    void jedeLageMitJederDrehung() {
        // Jede Lage geht mit jeder Drehung (0041): Der Kasten des Bilds, wie es auf dem Schirm gedreht steht, liegt nach der Lage,
        // eckig und rund, genordet und gedreht. Das Bild steht starr, mit dem Kopf von der Mitte weg, zu ihr hin oder aufrecht.
        Map<String, int[]> bilder = Map.of("norden", new int[] {15, 15}, "marke", new int[] {5, 9}, "marke_quer", new int[] {9, 5});
        Map<Integer, int[]> je = Map.of(Skin.NORDEN, bilder.get("norden"), Skin.MARKE, bilder.get("marke"), Skin.MARKE_QUER, bilder.get("marke_quer"));
        for (String drehung : List.of("karte", "aussen", "innen", "aufrecht")) {
            for (String lage : List.of("kante", "innen", "aussen")) {
                Skin skin = Skin.lies("t", "t", "#000000\n#111111", "schatten=ja\ndrehung=" + drehung + "\nlage=" + lage, bilder);
                int baender = skin.baender();
                for (int seite : new int[] {64, 128}) {
                    Minimap.Rahmen r = new Minimap.Rahmen(0, 0, seite);
                    double halb = seite / 2.0;
                    for (boolean rund : new boolean[] {false, true}) {
                        for (int grad = -1; grad < 360; grad += 7) {
                            Drehung.Lage d = grad < 0 ? null : Drehung.Lage.von(Math.toRadians(grad), 0, 0, 0, 0);
                            double karte = d == null ? 0 : d.winkel(), spiel = d == null ? 1e-5 : 0.5 / 2 * Math.sqrt(2) + 1e-5;
                            for (Minimap.Ornament o : Minimap.ornamente(r, baender, rund, skin.marken, d, 2, 0, -1, false, -1)) {
                                String wo = drehung + ", " + lage + ", Seite " + seite + (rund ? " rund" : " eckig") + ", "
                                        + (grad < 0 ? "genordet" : grad + "°") + ", " + "NOSW".charAt(o.richtung());
                                double erwartet = switch (drehung) {
                                    case "karte" -> karte;
                                    case "aussen" -> karte + o.richtung() * Math.PI / 2;
                                    case "innen" -> karte + o.richtung() * Math.PI / 2 + Math.PI;
                                    default -> 0;
                                };
                                assertEquals(erwartet, o.winkel(), 1e-12, wo);
                                boolean mitDerMitte = "aussen".equals(drehung) || "innen".equals(drehung);
                                assertEquals(o.richtung() == 0 ? Skin.NORDEN : mitDerMitte ? Skin.MARKE : Skin.MARKE_JE_RICHTUNG[o.richtung()], o.teil(), wo);
                                int[] b = je.get(o.teil());
                                switch (lage) {
                                    case "kante" -> assertEquals(halb, weit(ecken(o, b[0], b[1], 1), halb, halb, rund), spiel, wo);
                                    case "innen" -> assertEquals(halb - baender - 1, weit(ecken(o, b[0], b[1], 0), halb, halb, rund), spiel, wo);
                                    default -> {
                                        assertEquals(halb + 1, nah(ecken(o, b[0], b[1], 0), halb, halb, rund), spiel + 0.01, wo);
                                        assertTrue(weit(ecken(o, b[0], b[1], 1), halb, halb, false) <= halb + skin.einrueckung() + spiel,
                                                wo + ": über den Abstand zum Rand");
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void biomUndLetternMitDemKopfNachAussen() throws IOException {
        // Der Wunsch des Users (0041): biom mit O, S und W aussen auf dem Rand, der Fuss am Rand, die Blüte nach aussen, N auf den
        // Bändern; lettern mit dem Kopf nach aussen, S kopfüber, O und W quer, innen oder aussen. Dazu der Abstand zum Rand als Zahl.
        String grasland = Skin.BIOM + "/grasland/standard";
        BufferedImage m = bild(grasland, "marke");
        Skin biom = Skin.lies(grasland, grasland, text(grasland, "palette.txt"), text(grasland, "info.txt"),
                Map.of("norden", new int[] {15, 15}, "marke", new int[] {m.getWidth(), m.getHeight()}));
        assertEquals(13, biom.einrueckung());
        Minimap.Rahmen r = new Minimap.Rahmen(0, 0, 128);
        List<Minimap.Ornament> marken = Minimap.ornamente(r, 2, false, biom.marken, null, 2, 0, -1, false, -1);
        assertEquals(Minimap.ornamente(r, 2, false, Skin.Marken.AUSSEN, null, 2, 0, -1, false, -1).get(3), marken.get(3), "N auf den Bändern");
        // Genordet O, S und W: die Blüte aussen, der Fuss eine Einheit ausserhalb der Minimap.
        assertArrayEquals(new double[] {128 + 1 + 4.5, 64, Math.PI / 2}, new double[] {marken.get(0).x(), marken.get(0).y(), marken.get(0).winkel()}, 1e-5);
        assertArrayEquals(new double[] {64, 128 + 1 + 4.5, Math.PI}, new double[] {marken.get(1).x(), marken.get(1).y(), marken.get(1).winkel()}, 1e-5);
        assertArrayEquals(new double[] {-1 - 4.5, 64, 3 * Math.PI / 2}, new double[] {marken.get(2).x(), marken.get(2).y(), marken.get(2).winkel()}, 1e-5);
        for (String lage : List.of("innen", "aussen")) {
            String ordner = "lettern/tinte";
            Map<String, int[]> bilder = new HashMap<>();
            for (String teil : List.of("norden", "osten", "sueden", "westen")) {
                BufferedImage b = bild(ordner, teil);
                bilder.put(teil, new int[] {b.getWidth(), b.getHeight()});
            }
            Skin lettern = Skin.lies("lettern/tinte_" + lage, ordner, text(ordner, "palette.txt"), text(ordner, "info.txt") + "\nlage=" + lage, bilder);
            assertEquals("aussen".equals(lage) ? 14 : 7, lettern.einrueckung(), lage);
            List<Minimap.Ornament> buchstaben = Minimap.ornamente(r, lettern.baender(), false, lettern.marken, null, 2, 0, -1, false, -1);
            for (Minimap.Ornament o : buchstaben) {
                assertEquals(Skin.EIGENE_JE_RICHTUNG[o.richtung()], o.teil(), lage);
                assertEquals(o.richtung() * Math.PI / 2, o.winkel(), 1e-12, lage + ": der Kopf nach aussen");
            }
            // Genordet liegt S unten, mitten, eine Einheit innerhalb der Bänder oder ausserhalb der Minimap.
            Minimap.Ornament s = buchstaben.get(1);
            assertEquals(64, s.x(), 1e-5);
            assertEquals("aussen".equals(lage) ? 128 + 1 + 4.5 : 128 - lettern.baender() - 1 - 4.5, s.y(), 1e-5, lage);
        }
    }

    @Test
    void lageInInfoTxt() {
        // lage_norden gilt wie lage, wenn die Zeile fehlt; jede Lage geht mit jeder Drehung, nur unbekannte Werte nicht.
        Map<String, int[]> bilder = Map.of("norden", new int[] {15, 15}, "marke", new int[] {5, 9}, "marke_quer", new int[] {9, 5});
        Skin.Platz n = new Skin.Platz(Skin.Lage.KANTE, 15, 15, 1), m = new Skin.Platz(Skin.Lage.KANTE, 5, 9, 1);
        assertNull(Skin.lies("t", "t", "#000000\n#111111", "aussen=ja\nlage=mitte", bilder).marken.plaetze());
        assertArrayEquals(new Skin.Platz[] {n, m, m, m}, Skin.lies("t", "t", "#000000\n#111111", "schatten=ja\naussen=ja\nlage=kante", bilder).marken.plaetze());
        Skin.Platz m0 = new Skin.Platz(Skin.Lage.KANTE, 5, 9, 0);
        assertArrayEquals(new Skin.Platz[] {null, m0, m0, m0},
                Skin.lies("t", "t", "#000000\n#111111", "drehung=aussen\nlage=kante\nlage_norden=mitte", bilder).marken.plaetze());
        // Aufrecht und starr ohne eigene Bilder nehmen O und W marke_quer, S marke, so wie sie stehen; innen wie aussen marke.
        Skin.Platz[] innen = Skin.lies("t", "t", "#000000\n#111111", "drehung=aufrecht\nlage=innen", bilder).marken.plaetze();
        assertEquals(new Skin.Platz(Skin.Lage.INNEN, 9, 5, 0), innen[1]);
        assertEquals(new Skin.Platz(Skin.Lage.INNEN, 5, 9, 0), innen[2]);
        assertEquals(new Skin.Platz(Skin.Lage.AUSSEN, 5, 9, 0), Skin.lies("t", "t", "#000000\n#111111", "drehung=innen\nlage=aussen", bilder).marken.plaetze()[1]);
        assertEquals(new Skin.Platz(Skin.Lage.KANTE, 9, 5, 0), Skin.lies("t", "t", "#000000\n#111111", "lage=kante", bilder).marken.plaetze()[1]);
        assertEquals(Skin.Stellung.INNEN, Skin.lies("t", "t", "#000000\n#111111", "drehung=innen", bilder).marken.stellung());
        assertThrows(IllegalArgumentException.class, () -> Skin.lies("t", "t", "#000000\n#111111", "drehung=aufrecht\nlage=schief", bilder));
        assertThrows(IllegalArgumentException.class, () -> Skin.lies("t", "t", "#000000\n#111111", "drehung=quer", bilder));
    }

    /**
     * Die Ecken des Bilds einer Marke von w × h Einheiten auf dem Schirm, um ihren Winkel gedreht: oben zeigt nach (sin, −cos),
     * rechts nach (cos, sin). Mit {@code schatten} dazu die Ecken des Schattens um (+schatten, +schatten).
     */
    private static double[][] ecken(Minimap.Ornament o, int w, int h, int schatten) {
        double ox = Math.sin(o.winkel()), oy = -Math.cos(o.winkel()), rx = -oy, ry = ox;
        double[][] e = new double[schatten > 0 ? 8 : 4][];
        for (int j = 0; j < e.length; j++) {
            double a = (j & 1) == 0 ? h / 2.0 : -h / 2.0, b = (j & 2) == 0 ? w / 2.0 : -w / 2.0, s = j < 4 ? 0 : schatten;
            e[j] = new double[] {o.x() + a * ox + b * rx + s, o.y() + a * oy + b * ry + s};
        }
        return e;
    }

    /** Wie weit die äusserste Ecke von der Mitte (cx, cy) liegt: rund im Kreis, eckig im Quadrat. */
    private static double weit(double[][] ecken, double cx, double cy, boolean rund) {
        double weit = 0;
        for (double[] e : ecken) {
            double x = e[0] - cx, y = e[1] - cy;
            weit = Math.max(weit, rund ? Math.hypot(x, y) : Math.max(Math.abs(x), Math.abs(y)));
        }
        return weit;
    }

    /**
     * Wie nah ein Bild mit diesen vier Ecken der Mitte (cx, cy) kommt: rund der nächste Punkt seines Rands, abgetastet; eckig die
     * Seite des Quadrats, die es ganz verlassen hat, gemessen an seiner nächsten Ecke auf dieser Achse.
     */
    private static double nah(double[][] ecken, double cx, double cy, boolean rund) {
        if (rund) {
            // Der Rand in der Reihenfolge der Ecken: oben rechts, oben links, unten links, unten rechts.
            int[] folge = {0, 2, 3, 1};
            double nah = Double.MAX_VALUE;
            for (int j = 0; j < 4; j++) {
                double[] a = ecken[folge[j]], b = ecken[folge[(j + 1) % 4]];
                for (int t = 0; t <= 2000; t++) {
                    double x = a[0] + (b[0] - a[0]) * t / 2000.0 - cx, y = a[1] + (b[1] - a[1]) * t / 2000.0 - cy;
                    nah = Math.min(nah, Math.hypot(x, y));
                }
            }
            return nah;
        }
        double mx = 0, my = 0;
        for (double[] e : ecken) {
            mx += e[0] / 4 - cx / 4;
            my += e[1] / 4 - cy / 4;
        }
        double nahX = Double.MAX_VALUE, nahY = Double.MAX_VALUE;
        for (double[] e : ecken) {
            nahX = Math.min(nahX, (e[0] - cx) * Math.signum(mx));
            nahY = Math.min(nahY, (e[1] - cy) * Math.signum(my));
        }
        return Math.max(nahX, nahY);
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
