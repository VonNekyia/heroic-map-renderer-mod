package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Die selbst gezeichnete Karte: Baum, Kacheln und Pyramide auf der Platte. Siehe docs/selbst.md, „Kacheln“. */
class SelbstTest {

    private static final int ROT = 0xFFFF0000, BLAU = 0xFF0000FF, GRUEN = 0xFF00FF00, GELB = 0xFFFFFF00;

    @Test
    void chunkLiegtInSeinerKachel(@TempDir Path ordner) throws Exception {
        // 256 Pixel je Kachel, 64 je Chunk: 4 × 4 Chunks; Chunk (-1, 5) liegt in Kachel (-1, 1) rechts, zweite Zeile.
        Kachelwerk werk = new Kachelwerk(ordner, 256, 64, 6, 8);
        werk.lege(-1, 5, voll(64, ROT));
        List<Kachelwerk.Kachel> fertig = schreibe(werk, true);
        assertEquals(new Kachelwerk.Kachel(8, -1, 1), fertig.getFirst());
        int[] kachel = lies(ordner.resolve("8/-1/1.png"));
        assertEquals(ROT, kachel[64 * 256 + 192]);
        assertEquals(ROT, kachel[127 * 256 + 255]);
        assertEquals(0, kachel[64 * 256 + 191]);
        assertEquals(0, kachel[63 * 256 + 192]);
        // Darüber je Stufe ein Vorfahr bis minZoom.
        assertEquals(Set.of(new Kachelwerk.Kachel(8, -1, 1), new Kachelwerk.Kachel(7, -1, 0), new Kachelwerk.Kachel(6, -1, 0)),
                new HashSet<>(fertig));
    }

    @Test
    void webpEinerKopieAlsGrundlage(@TempDir Path w) throws Exception {
        // Eine Kachel der Kopie ist ein Link auf die WebP der Quelle, aus dem Testsatz der Gametests: 256 Pixel, Massstab 4.
        byte[] webp = Files.readAllBytes(Path.of("src/gametest/resources/satz/4/2/0/0.webp"));
        Path quelle = w.resolve("quelle/2/0/0.webp"), ordner = w.resolve("kopie");
        Files.createDirectories(quelle.getParent());
        Files.write(quelle, webp);
        Files.createDirectories(ordner.resolve("2/0"));
        Files.createLink(ordner.resolve("2/0/0.webp"), quelle);
        int[] vorher = Kacheln.dekodiere(webp, 256).argb();
        Kachelwerk werk = new Kachelwerk(ordner, 256, 64, 0, 2);
        werk.lege(1, 2, voll(64, ROT));
        schreibe(werk, true);
        // Die neue PNG: der Chunk rot, alles andere bitgleich zur WebP; der Link ist weg, die Quelle unverändert.
        int[] nachher = lies(ordner.resolve("2/0/0.png"));
        for (int i = 0; i < vorher.length; i++) {
            int x = i % 256, y = i / 256;
            boolean imChunk = x >= 64 && x < 128 && y >= 128 && y < 192;
            assertEquals(imChunk ? ROT : vorher[i], nachher[i], "Pixel " + x + ", " + y);
        }
        assertFalse(Files.exists(ordner.resolve("2/0/0.webp")));
        assertArrayEquals(webp, Files.readAllBytes(quelle));
    }

    @Test
    void vorfahrAusVierKindern(@TempDir Path ordner) throws Exception {
        // Vier Kinder in vier Farben: Vertauschte Viertel oder Achsen fielen auf.
        Kachelwerk werk = new Kachelwerk(ordner, 256, 64, 7, 8);
        int[] farben = {ROT, BLAU, GRUEN, GELB};
        for (int i = 0; i < 4; i++) {
            fuelle(werk, i & 1, i >> 1, farben[i]);
        }
        schreibe(werk, true);
        int[] grob = lies(ordner.resolve("7/0/0.png"));
        for (int i = 0; i < 4; i++) {
            int[] klein = Pyramide.halbiere(lies(ordner.resolve("8/" + (i & 1) + "/" + (i >> 1) + ".png")), 256, 1);
            int ox = (i & 1) * 128, oy = (i >> 1) * 128;
            for (int y = 0; y < 128; y++) {
                assertArrayEquals(Arrays.copyOfRange(klein, y * 128, y * 128 + 128),
                        Arrays.copyOfRange(grob, (oy + y) * 256 + ox, (oy + y) * 256 + ox + 128), "Viertel " + i + ", Zeile " + y);
            }
            assertEquals(farben[i], grob[(oy + 5) * 256 + ox + 5]);
        }
    }

    @Test
    void nurDasGeaenderteViertel(@TempDir Path ordner) throws Exception {
        Kachelwerk erstes = new Kachelwerk(ordner, 256, 64, 7, 8);
        fuelle(erstes, 0, 0, ROT);
        fuelle(erstes, 1, 1, BLAU);
        schreibe(erstes, true);
        // Das Kind (0, 0) ändert sich auf der Platte, ohne dass sein Vorfahr es erfährt.
        Files.write(ordner.resolve("8/0/0.png"), pngVoll(GELB));
        // Ein neues Werk ändert nur (1, 1): Das Viertel von (0, 0) kommt aus dem Vorfahren und bleibt Rot.
        // Rechnete es den Vorfahren aus allen Kindern neu, wäre es Gelb.
        Kachelwerk zweites = new Kachelwerk(ordner, 256, 64, 7, 8);
        zweites.lege(4, 4, voll(64, GRUEN));
        assertEquals(List.of(new Kachelwerk.Kachel(8, 1, 1), new Kachelwerk.Kachel(7, 0, 0)), schreibe(zweites, true));
        int[] grob = lies(ordner.resolve("7/0/0.png"));
        assertEquals(ROT, grob[5 * 256 + 5]);
        assertEquals(GRUEN, grob[(128 + 5) * 256 + 128 + 5]);
        assertEquals(BLAU, grob[(255) * 256 + 255]);
    }

    @Test
    void speicherBleibtBei64(@TempDir Path ordner) throws Exception {
        // Eine grobe Kachel bleibt bis zum nächsten groben Schreiben geändert; der Speicher wächst trotzdem nicht.
        Kachelwerk werk = new Kachelwerk(ordner, 256, 64, 6, 8);
        werk.lege(0, 0, voll(64, ROT));
        schreibe(werk, false);
        for (int i = 1; i <= 200; i++) {
            werk.lege(i * 4, 0, voll(64, BLAU));
            schreibe(werk, false);
            assertTrue(werk.imSpeicher() <= 64, "Kachel " + i + ": " + werk.imSpeicher());
        }
    }

    @Test
    void kaputteKachelWieFehlend(@TempDir Path ordner) throws Exception {
        // Eine PNG mit Signatur und abgeschnittenem Rest: Der Reader erkennt sie und wirft, statt null zu geben.
        Files.createDirectories(ordner.resolve("7/0"));
        Files.write(ordner.resolve("7/0/0.png"), Arrays.copyOf(pngVoll(ROT), 60));
        Kachelwerk werk = new Kachelwerk(ordner, 256, 64, 7, 8);
        werk.lege(0, 0, voll(64, ROT));
        schreibe(werk, true);
        assertEquals(ROT, lies(ordner.resolve("7/0/0.png"))[5 * 256 + 5]);
    }

    @Test
    void grobeStufenWarten(@TempDir Path ordner) throws Exception {
        Kachelwerk werk = new Kachelwerk(ordner, 256, 64, 5, 8);
        werk.lege(0, 0, voll(64, ROT));
        assertEquals(Set.of(8, 7), stufen(schreibe(werk, false)));
        assertFalse(Files.exists(ordner.resolve("6/0/0.png")));
        assertEquals(Set.of(6, 5), stufen(schreibe(werk, true)));
        assertTrue(Files.exists(ordner.resolve("5/0/0.png")));
    }

    @Test
    void dauerhafterSchreibfehlerBegrenztDenSpeicher(@TempDir Path ordner) throws Exception {
        // Wo der Ordner des Massstabs hingehört, liegt eine Datei: Jedes Schreiben scheitert.
        Path massstab = ordner.resolve("4");
        Files.writeString(massstab, "im Weg");
        Kachelwerk werk = new Kachelwerk(massstab, 256, 64, 0, 8);
        int gescheitert = 0;
        for (int i = 0; i < 1000; i++) {
            werk.lege(i * 4, 0, voll(64, ROT));
            try {
                werk.schreibe(true, new ArrayList<>());
            } catch (IOException erwartet) {
                gescheitert++;
            }
        }
        assertTrue(werk.aufgegeben());
        assertEquals(Kachelwerk.VERSUCHE, gescheitert, "danach nimmt es nichts mehr an");
        assertEquals(0, werk.geaenderte());
    }

    @Test
    void scheitertDasSchreibenBleibtDerVorfahr(@TempDir Path ordner) throws Exception {
        Kachelwerk werk = new Kachelwerk(ordner, 256, 64, 7, 8);
        werk.lege(0, 0, voll(64, ROT));
        // Eine Datei, wo der Ordner 7/0 hingehört.
        Files.createDirectories(ordner.resolve("7"));
        Files.writeString(ordner.resolve("7/0"), "im Weg");
        List<Kachelwerk.Kachel> fertig = new ArrayList<>();
        assertThrows(IOException.class, () -> werk.schreibe(true, fertig));
        assertEquals(List.of(new Kachelwerk.Kachel(8, 0, 0)), fertig);
        Files.delete(ordner.resolve("7/0"));
        assertEquals(List.of(new Kachelwerk.Kachel(7, 0, 0)), schreibe(werk, true));
        assertEquals(ROT, lies(ordner.resolve("7/0/0.png"))[5 * 256 + 5]);
        try (var reste = Files.walk(ordner)) {
            assertTrue(reste.noneMatch(p -> p.toString().endsWith(".tmp")));
        }
    }

    @Test
    void einNeuesWerkLiestWasDaLiegt(@TempDir Path ordner) throws Exception {
        Kachelwerk erstes = new Kachelwerk(ordner, 256, 64, 8, 8);
        erstes.lege(0, 0, voll(64, ROT));
        schreibe(erstes, true);
        // Nach einem Neustart kommt ein zweiter Chunk in dieselbe Kachel; der erste bleibt.
        Kachelwerk zweites = new Kachelwerk(ordner, 256, 64, 8, 8);
        zweites.lege(1, 0, voll(64, BLAU));
        schreibe(zweites, true);
        int[] kachel = lies(ordner.resolve("8/0/0.png"));
        assertEquals(ROT, kachel[0]);
        assertEquals(BLAU, kachel[64]);
        // Ohne Änderung schreibt es nichts.
        assertTrue(schreibe(zweites, true).isEmpty());
    }

    @Test
    void baumJeDimension(@TempDir Path welt) throws Exception {
        assertTrue(Selbst.baum("minecraft:overworld").startsWith("selbst-minecraft_overworld-"));
        // Gleich gelesen, verschieden gemeint: zwei Bäume.
        assertNotEquals(Selbst.baum("mod:a/b"), Selbst.baum("mod:a_b"));
        assertTrue(Selbst.baum("x:" + "a".repeat(100)).length() <= 64);
        // Ein Baum vom Server, nach dem Namen vor dem eigenen (Satz.fuer sortiert): Der eigene geht trotzdem vor.
        satzVomServer(welt.resolve("a-server"), "minecraft:overworld");
        assertEquals("Survival", Satz.fuer(welt, "minecraft:overworld", Zeigt.OHNE).name());
        Path baum = Selbst.anlegen(welt, "minecraft:overworld", Selbst.MASSSTAB);
        Satz satz = Satz.fuer(welt, "minecraft:overworld", Zeigt.OHNE);
        assertEquals(baum.resolve("4"), satz.ordner());
        assertEquals(Selbst.MAX_ZOOM, satz.stufe());
        assertEquals(Selbst.SCALE, satz.scale());
        assertTrue(Selbst.selbst(baum));
        assertTrue(Selbst.an(welt, "minecraft:overworld"));
        assertFalse(Selbst.an(welt, "minecraft:the_nether"));
    }

    @Test
    void einBaumMitPraefixVomServerIstNichtDerEigene(@TempDir Path welt) throws Exception {
        // Lädt der Server einen solchen Baum, sperrt ihn schon Freigabe.baum; ohne MARKE gälte er ohnehin nicht.
        assertFalse(Freigabe.baum(Selbst.baum("minecraft:overworld")));
        Path fremd = welt.resolve(Selbst.baum("minecraft:overworld"));
        satzVomServer(fremd, "minecraft:overworld");
        assertFalse(Selbst.selbst(fremd));
        assertFalse(Selbst.an(welt, "minecraft:overworld"));
    }

    @Test
    void anPrueftDieDimension(@TempDir Path welt) throws Exception {
        // Der Baum der einen Dimension mit der Dimension einer anderen in satz.json: nicht an.
        Path baum = Selbst.anlegen(welt, "mod:a_b", Selbst.MASSSTAB);
        Files.move(baum, welt.resolve(Selbst.baum("mod:a/b")));
        assertFalse(Selbst.an(welt, "mod:a/b"));
    }

    @Test
    void massstabJeKarte(@TempDir Path welten) throws Exception {
        // Wie ein Satz des Servers: map.json für jeden Massstab gleich, die feinste Stufe nach dem Massstab,
        // Stufe 0 deckt immer 16 384 Blöcke.
        for (int massstab : Selbst.MASSSTAEBE) {
            Path welt = welten.resolve(String.valueOf(massstab));
            Path baum = Selbst.anlegen(welt, "minecraft:overworld", massstab);
            Satz satz = Satz.fuer(welt, "minecraft:overworld", Zeigt.OHNE);
            assertEquals(baum.resolve(String.valueOf(massstab)), satz.ordner());
            assertEquals(massstab, satz.massstab());
            assertEquals(Selbst.SCALE, satz.scale());
            assertEquals(Selbst.MAX_ZOOM, satz.maxZoom());
            assertEquals(Selbst.MAX_ZOOM - Integer.numberOfTrailingZeros(Selbst.SCALE / massstab), satz.stufe());
            assertEquals(16_384, Selbst.KACHEL / massstab << satz.stufe());
        }
    }

    @Test
    void chunkLiegtJeMassstabInSeinerKachel(@TempDir Path ordner) throws Exception {
        // 1 px: 16 × 16 Chunks je Kachel auf Stufe 6, Chunk (17, -1) in Kachel (1, -1) bei (16, 240); 2 px: 8 × 8 auf Stufe 7.
        Kachelwerk eins = new Kachelwerk(ordner.resolve("1"), 256, 16, 6, 6);
        eins.lege(17, -1, voll(16, ROT));
        assertEquals(new Kachelwerk.Kachel(6, 1, -1), schreibe(eins, true).getFirst());
        int[] kachel = lies(ordner.resolve("1/6/1/-1.png"));
        assertEquals(ROT, kachel[240 * 256 + 16]);
        assertEquals(0, kachel[240 * 256 + 15]);
        Kachelwerk zwei = new Kachelwerk(ordner.resolve("2"), 256, 32, 7, 7);
        zwei.lege(-9, 8, voll(32, BLAU));
        assertEquals(new Kachelwerk.Kachel(7, -2, 1), schreibe(zwei, true).getFirst());
        assertEquals(BLAU, lies(ordner.resolve("2/7/-2/1.png"))[0 * 256 + 7 * 32]);
        // Ein Chunk in anderem Massstab passt nicht und wird abgelehnt, statt die Kachel still zu verdrehen.
        assertThrows(IllegalArgumentException.class, () -> zwei.lege(0, 0, voll(64, ROT)));
    }

    @Test
    void pngMitFalscherGroesseFaelltWeg(@TempDir Path ordner) throws Exception {
        BufferedImage bild = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        Path datei = ordner.resolve("gross.png");
        ImageIO.write(bild, "png", datei.toFile());
        byte[] bytes = Files.readAllBytes(datei);
        assertThrows(IOException.class, () -> Kacheln.png(bytes, 256));
        assertEquals(512 * 512, Kacheln.png(bytes, 512).argb().length);
    }

    @Test
    void alterSatzBautDieGroeberenNeu(@TempDir Path ordner) throws Exception {
        alterSatz(ordner, 4, 4);
        Kachelwerk werk = new Kachelwerk(ordner, 256, 64, 6, 8);
        assertTrue(werk.neubauOffen(), "map.json ohne downscale: gemittelt");
        List<Kachelwerk.Kachel> fertig = new ArrayList<>();
        werk.baueNeu(16, fertig);
        assertFalse(werk.neubauOffen());
        assertEquals("{\"tileSize\":256,\"minZoom\":6,\"maxZoom\":8,\"scale\":4,\"downscale\":\"nearest\"}",
                Files.readString(ordner.resolve("map.json")), "erst jetzt, die übrigen Felder bleiben");
        assertTrue(fertig.containsAll(List.of(new Kachelwerk.Kachel(7, 1, 1), new Kachelwerk.Kachel(6, 0, 0))));
        pruefePyramide(ordner, 2, 2);
    }

    @Test
    void einAbgebrochenerNeubauBeginntVonVorn(@TempDir Path ordner) throws Exception {
        alterSatz(ordner, 8, 4);
        Kachelwerk erstes = new Kachelwerk(ordner, 256, 64, 6, 8);
        erstes.baueNeu(16, new ArrayList<>());
        assertTrue(erstes.neubauOffen(), "die Hälfte");
        assertFalse(Files.readString(ordner.resolve("map.json")).contains("downscale"), "abgebrochen: noch kein downscale");
        // Welt verlassen, Spiel zu: Ein neues Werk baut wieder alles, auch was schon neu ist.
        Kachelwerk zweites = new Kachelwerk(ordner, 256, 64, 6, 8);
        assertTrue(zweites.neubauOffen());
        zweites.baueNeu(16, new ArrayList<>());
        assertTrue(zweites.neubauOffen());
        zweites.baueNeu(16, new ArrayList<>());
        assertFalse(zweites.neubauOffen());
        assertTrue(Files.readString(ordner.resolve("map.json")).contains("\"downscale\":\"nearest\""));
        pruefePyramide(ordner, 4, 2);
    }

    @Test
    void neueSaetzeBauenNichtNeu(@TempDir Path welt) throws Exception {
        Path baum = Selbst.anlegen(welt, "minecraft:overworld", 4);
        assertTrue(Files.readString(baum.resolve("4/map.json")).contains("\"downscale\":\"nearest\""));
        assertFalse(new Kachelwerk(baum.resolve("4"), 256, 64, 0, 8).neubauOffen());
        assertFalse(new Kachelwerk(welt.resolve("ohne-map-json"), 256, 64, 0, 8).neubauOffen(), "ohne map.json nichts");
    }

    /** Ein Satz von früher: {@code breite} × {@code hoehe} Kacheln der Stufe 8 mit Muster, gröbere Stufen gelb, map.json ohne downscale. */
    private static void alterSatz(Path ordner, int breite, int hoehe) throws IOException {
        for (int ty = 0; ty < hoehe; ty++) {
            for (int tx = 0; tx < breite; tx++) {
                int[] muster = new int[256 * 256];
                for (int i = 0; i < muster.length; i++) {
                    muster[i] = 0xFF000000 | ((i % 256) * 7 + (i / 256) * 13 + tx * 31 + ty * 57) & 0xFFFFFF;
                }
                schreibePng(ordner.resolve("8/" + tx + "/" + ty + ".png"), muster);
            }
        }
        for (int z = 7; z >= 6; z--) {
            int n = 8 - z;
            for (int ty = 0; ty < (hoehe + (1 << n) - 1) >> n; ty++) {
                for (int tx = 0; tx < (breite + (1 << n) - 1) >> n; tx++) {
                    Path p = ordner.resolve(z + "/" + tx + "/" + ty + ".png");
                    Files.createDirectories(p.getParent());
                    Files.write(p, pngVoll(GELB));
                }
            }
        }
        Files.writeString(ordner.resolve("map.json"), "{\"tileSize\":256,\"minZoom\":6,\"maxZoom\":8,\"scale\":4}");
    }

    /** Jede Kachel der Stufen 7 und 6 ist ihre vier Kinder je 2 × 2 ein Pixel, in Tiefe 1 rechts unten, in Tiefe 2 links oben. */
    private static void pruefePyramide(Path ordner, int breite7, int hoehe7) throws IOException {
        for (int z = 7; z >= 6; z--) {
            int b = z == 7 ? breite7 : (breite7 + 1) / 2, h = z == 7 ? hoehe7 : (hoehe7 + 1) / 2;
            for (int ty = 0; ty < h; ty++) {
                for (int tx = 0; tx < b; tx++) {
                    int[] grob = lies(ordner.resolve(z + "/" + tx + "/" + ty + ".png"));
                    for (int i = 0; i < 4; i++) {
                        Path kind = ordner.resolve((z + 1) + "/" + (2 * tx + (i & 1)) + "/" + (2 * ty + (i >> 1)) + ".png");
                        int[] klein = Files.exists(kind) ? Pyramide.halbiere(lies(kind), 256, 8 - z) : new int[128 * 128];
                        int ox = (i & 1) * 128, oy = (i >> 1) * 128;
                        for (int y = 0; y < 128; y++) {
                            assertArrayEquals(Arrays.copyOfRange(klein, y * 128, y * 128 + 128),
                                    Arrays.copyOfRange(grob, (oy + y) * 256 + ox, (oy + y) * 256 + ox + 128),
                                    "Stufe " + z + ", Kachel " + tx + "/" + ty + ", Viertel " + i + ", Zeile " + y);
                        }
                    }
                }
            }
        }
    }

    private static void schreibePng(Path datei, int[] argb) throws IOException {
        Files.createDirectories(datei.getParent());
        BufferedImage bild = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        bild.setRGB(0, 0, 256, 256, argb, 0, 256);
        ImageIO.write(bild, "png", datei.toFile());
    }

    private static void satzVomServer(Path baum, String dimension) throws IOException {
        Satz.schreibe(baum, "Survival", dimension, 4);
        Files.createDirectories(baum.resolve("4"));
        Files.writeString(baum.resolve("4/map.json"), "{\"tileSize\":256,\"minZoom\":0,\"maxZoom\":5,\"scale\":4}");
    }

    /** Füllt die Kachel (tx, ty) der Stufe 8 mit 4 × 4 Chunks einer Farbe. */
    private static void fuelle(Kachelwerk werk, int tx, int ty, int farbe) throws IOException {
        for (int cz = 0; cz < 4; cz++) {
            for (int cx = 0; cx < 4; cx++) {
                werk.lege(tx * 4 + cx, ty * 4 + cz, voll(64, farbe));
            }
        }
    }

    private static List<Kachelwerk.Kachel> schreibe(Kachelwerk werk, boolean grob) throws IOException {
        List<Kachelwerk.Kachel> fertig = new ArrayList<>();
        werk.schreibe(grob, fertig);
        return fertig;
    }

    private static Set<Integer> stufen(List<Kachelwerk.Kachel> kacheln) {
        Set<Integer> stufen = new HashSet<>();
        kacheln.forEach(k -> stufen.add(k.z()));
        return stufen;
    }

    private static byte[] pngVoll(int argb) throws IOException {
        BufferedImage bild = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        bild.setRGB(0, 0, 256, 256, voll(256, argb), 0, 256);
        java.io.ByteArrayOutputStream raus = new java.io.ByteArrayOutputStream();
        ImageIO.write(bild, "png", raus);
        return raus.toByteArray();
    }

    private static int[] voll(int seite, int argb) {
        int[] pixel = new int[seite * seite];
        Arrays.fill(pixel, argb);
        return pixel;
    }

    private static int[] lies(Path datei) throws IOException {
        BufferedImage bild = ImageIO.read(datei.toFile());
        return bild.getRGB(0, 0, 256, 256, null, 0, 256);
    }

    @Test
    void weiterInNeuenWelten(@TempDir Path wurzel) throws Exception {
        // „Selbst“ für neue Welten gilt je Netz und Server mit seinem Massstab; 0 vergisst ihn, eine leere Liste fällt weg.
        String oben = "minecraft:overworld";
        assertEquals(0, Selbst.weiter(wurzel, "play.example.net", "main"));
        Selbst.merkeWeiter(wurzel, "play.example.net", "main", 2);
        Selbst.merkeWeiter(wurzel, "play.example.net", "build", 4);
        Selbst.merkeWeiter(wurzel, "play.example.net", "main", 1);
        assertEquals(1, Selbst.weiter(wurzel, "play.example.net", "main"), "die letzte Wahl gilt");
        assertEquals(4, Selbst.weiter(wurzel, "play.example.net", "build"));
        assertEquals(0, Selbst.weiter(wurzel, "zweit.example.net", "main"), "ein anderes Netz");
        Selbst.merkeWeiter(wurzel, "play.example.net", "main", 0);
        assertEquals(0, Selbst.weiter(wurzel, "play.example.net", "main"));
        Selbst.merkeWeiter(wurzel, "play.example.net", "build", 0);
        assertFalse(Files.exists(wurzel.resolve(Selbst.WEITER)), "leer fällt die Datei weg");
        Files.writeString(wurzel.resolve(Selbst.WEITER), "play.example.net\tmain\t3\nkaputt\nplay.example.net\t\t4\n");
        assertEquals(0, Selbst.weiter(wurzel, "play.example.net", "main"), "3 px gibt es nicht");
        assertEquals(0, Selbst.weiter(wurzel, "play.example.net", ""), "ohne Server nichts");

        // Wo es ohne Klick zeichnet: nur ohne eigene Karte, ohne Wahl in zeigt.txt und ohne andere Karte der Dimension.
        Path welt = Files.createDirectories(wurzel.resolve("play.example.net/karte-main/welt-1111111111111111"));
        assertTrue(Selbst.weiterHier(welt, oben, "main", 4));
        assertFalse(Selbst.weiterHier(welt, oben, "main", 0), "nicht gewählt");
        Path download = welt.resolve("top");
        Satz.schreibe(download, "Karte", oben, 4);
        Files.createDirectories(download.resolve("4"));
        Files.writeString(download.resolve("4/map.json"), "{\"tileSize\":256,\"scale\":4,\"minZoom\":0,\"maxZoom\":2}");
        assertFalse(Selbst.weiterHier(welt, oben, "main", 4), "ein Download der Dimension ersetzt es nie still");
        assertTrue(Selbst.weiterHier(welt, "minecraft:the_end", "main", 4), "eine andere Dimension");
        Laden.loesche(download);
        Path eigene = Selbst.anlegen(welt, oben, 4, "main");
        assertFalse(Selbst.weiterHier(welt, oben, "main", 4), "schon eine eigene Karte");
        Laden.loesche(eigene);
        Zeigt.setze(welt, "main", oben, "fehlt");
        assertFalse(Selbst.weiterHier(welt, oben, "main", 4), "eine Wahl in zeigt.txt");
    }
}
