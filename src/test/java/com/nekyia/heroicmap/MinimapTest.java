package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der Bereich, den die Minimap zeichnet. Siehe docs/minimap.md, „Neu zeichnen“.
 * Form, Lage und Einstellungen: siehe docs/minimap.md, „Bedienung“.
 */
class MinimapTest {

    @Test
    void verzierungenJeMarkeUndAlteDatei(@TempDir Path ordner) throws Exception {
        // Text hin und zurück; Unbekanntes zählt nicht.
        assertEquals("N,S", Minimap.marken(0b0101));
        assertEquals("", Minimap.marken(0));
        assertEquals(0b1010, Minimap.marken("O, W,x,NN"));
        // Eine Datei von vor mod#175: verzierungen=false heisst alle vier durchsichtig, sonst keine.
        Path alt = ordner.resolve("alt.properties");
        Files.writeString(alt, "verzierungen=false");
        Minimap m = new Minimap();
        m.lies(alt);
        assertFalse(m.verzierungen());
        for (int i = 0; i < 4; i++) {
            assertTrue(m.verzierungAus(i));
        }
        Files.writeString(alt, "verzierungen=true");
        m.lies(alt);
        assertTrue(m.verzierungen());
        // Rechtsklick hin und zurück.
        m.schalteVerzierung(3);
        assertTrue(m.verzierungAus(3) && !m.verzierungAus(0));
        m.schalteVerzierung(3);
        assertTrue(m.verzierungen());
        // Ohne N zeichnet der Rahmen drei Marken, nicht oben.
        Minimap.Rahmen r = new Minimap.Rahmen(0, 0, 128);
        List<Minimap.Ornament> drei = Minimap.ornamente(r, 2, false, false, null, 2, 1, -1, false, -1);
        assertEquals(3, drei.size());
        for (Minimap.Ornament o : drei) {
            assertTrue(o.y() > 2, "Marke oben trotz N durchsichtig: " + o);
        }
    }

    @Test
    void reichweiteJeMassstab() {
        // Sichtbar sind 64 Einheiten je Richtung: ±4, ±2 und ±1 Chunks, dazu 2 Chunks Vorrat.
        assertEquals(6, Minimap.reichweite(1, 128));
        assertEquals(4, Minimap.reichweite(2, 128));
        assertEquals(3, Minimap.reichweite(4, 128));
        assertEquals(10, Minimap.reichweite(1, 256));
        assertEquals(3, Minimap.reichweite(8, 128));
        // Zoom 0,5: 128 Blöcke je Richtung, ±8 Chunks; bei der grössten Seite ±16.
        assertEquals(10, Minimap.reichweite(0.5, 128));
        assertEquals(18, Minimap.reichweite(0.5, 256));
    }

    @Test
    void zoomStufenTasteUndDatei(@TempDir Path ordner) throws Exception {
        Minimap m = new Minimap();
        // Die Taste geht reihum, nach 8 wieder 1/32; eine Stufe ausserhalb gilt nicht.
        double[] reihe = new double[10];
        for (int i = 0; i < reihe.length; i++) {
            reihe[i] = m.zoom();
            m.naechsterZoom();
        }
        assertArrayEquals(new double[] {2, 4, 8, 1.0 / 32, 1.0 / 16, 1.0 / 8, 1.0 / 4, 1.0 / 2, 1, 2}, reihe);
        double vor = m.zoom();
        m.setzeZoom(3);
        assertEquals(vor, m.zoom());
        // Brüche stehen als Dezimalzahl in der Datei, ganze Stufen wie bisher als ganze Zahl.
        Path datei = ordner.resolve("heroicmap.properties");
        m.setzeZoom(0.5);
        m.schreibe(datei);
        assertTrue(Files.readString(datei).contains("zoom=0.5"));
        Minimap gelesen = new Minimap();
        gelesen.lies(datei);
        assertEquals(0.5, gelesen.zoom());
        m.setzeZoom(1.0 / 32);
        m.schreibe(datei);
        assertTrue(Files.readString(datei).contains("zoom=0.03125"));
        gelesen.lies(datei);
        assertEquals(1.0 / 32, gelesen.zoom());
        m.setzeZoom(4);
        m.schreibe(datei);
        java.util.Properties p = new java.util.Properties();
        try (var r = Files.newBufferedReader(datei)) {
            p.load(r);
        }
        assertEquals("4", p.getProperty("zoom"));
        // Ein Wert, den es nicht gibt, gilt als Vorgabe.
        Files.writeString(datei, "zoom=3");
        gelesen.lies(datei);
        assertEquals(2, gelesen.zoom());
    }

    @Test
    void kachelnAbEinemViertel() {
        // Satz mit 4 px, maxZoom 8, minZoom 0, feinste Stufe 8: Stufe z hat 4 / 2^(8 - z) Pixel je Block.
        // GS 2: bei 1/4 0,5 Pixel je Block auf dem Schirm, also die Stufe mit 0,5, z = 5; bei 1/32 1/16, z = 2.
        assertEquals(5, Minimap.kachelStufe(4, 0, 8, 8, 1.0 / 4, 2));
        assertEquals(2, Minimap.kachelStufe(4, 0, 8, 8, 1.0 / 32, 2));
        // GS 3: 0,75 Pixel je Block; die gröbste mit mindestens so vielen hat 1, z = 6, nie gröber als 0,75 auf dem Schirm.
        assertEquals(6, Minimap.kachelStufe(4, 0, 8, 8, 1.0 / 4, 3));
        // Nie gröber als minZoom, nie feiner als die feinste Stufe des Massstabs.
        assertEquals(3, Minimap.kachelStufe(4, 3, 8, 8, 1.0 / 32, 2));
        assertEquals(6, Minimap.kachelStufe(4, 0, 8, 6, 1.0 / 4, 4));
        // Eine Kachel von 256 Pixeln der Stufe 2 deckt 4096 Blöcke, bei 1/32 und GS 2 also 256 Pixel des Schirms.
        Satz satz = new Satz(Path.of("satz"), "Satz", "minecraft:overworld", 4, 256, 0, 8, 4);
        assertEquals(256, Minimap.kachelSeite(satz, 2, 1.0 / 32, 2));
        assertEquals(256, Minimap.kachelSeite(satz, 5, 1.0 / 4, 2));
        assertEquals(192, Minimap.kachelSeite(satz, 6, 1.0 / 4, 3));
    }

    @Test
    void reichweiteHoechstensSichtweite() {
        // Bei 1/32 und 128 Einheiten wären es ±130 Chunks; geladen hat der Client bei Sichtweite 12 nur ±12.
        assertEquals(130, Minimap.reichweite(1.0 / 32, 128));
        assertEquals(12 + Minimap.VORRAT, Minimap.reichweite(1.0 / 32, 128, 12));
        assertEquals(Minimap.reichweite(2, 128), Minimap.reichweite(2, 128, 12));
    }

    @Test
    void beschriftungAlsBruch() {
        String[] erwartet = {"heroicmap.menue.fach_bruch:32", "heroicmap.menue.fach_bruch:16", "heroicmap.menue.fach_bruch:8",
            "heroicmap.menue.fach_bruch:4", "heroicmap.menue.fach_bruch:2", "heroicmap.menue.fach:1", "heroicmap.menue.fach:2",
            "heroicmap.menue.fach:4", "heroicmap.menue.fach:8"};
        for (int i = 0; i < Minimap.ZOOMS.length; i++) {
            net.minecraft.network.chat.contents.TranslatableContents t =
                    (net.minecraft.network.chat.contents.TranslatableContents) Minimap.fach(Minimap.ZOOMS[i]).getContents();
            assertEquals(erwartet[i], t.getKey() + ":" + t.getArgs()[0]);
        }
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
        // Mit Rahmen rückt sie um dessen Einrückung vom Rand, etwa 11 bei „uhr“.
        assertEquals(new Minimap.Rahmen(640 - 128 - 11, 11, 128), Minimap.rahmen(640, 360, 128, 1, 0, 11));
    }

    @Test
    void verschiebenBleibtAufDemSchirm() {
        Minimap minimap = new Minimap();
        minimap.setzeSkin(Skin.OHNE);
        minimap.verschiebe(100, 50, 640, 360);
        assertEquals(new Minimap.Rahmen(100, 50, 128), minimap.rahmen(640, 360));
        minimap.verschiebe(-500, 9999, 640, 360);
        assertEquals(new Minimap.Rahmen(4, 360 - 128 - 4, 128), minimap.rahmen(640, 360));
        minimap.stelle(4, 4, 9999, 640, 360);
        assertEquals(Minimap.GROESSTE, minimap.rahmen(640, 360).seite());
    }

    @Test
    void groesseFolgtDemFenster(@TempDir Path ordner) throws Exception {
        // Der erste Schirm nimmt die Vorgabe als Anteil seiner kürzeren Seite: 854 × 480 bei GUI-Massstab 2.
        Minimap m = new Minimap();
        m.setzeSkin(Skin.OHNE);
        assertEquals(128, m.rahmen(427, 240).seite());
        // 1280 × 720 beim selben GUI-Massstab: anderthalbmal so gross; bei GUI-Massstab 1 ebenso viele Pixel.
        assertEquals(192, m.rahmen(640, 360).seite());
        assertEquals(256, m.rahmen(854, 480).seite());
        // Darüber nicht: 256 Einheiten halten die Kosten, siehe 0013.
        assertEquals(Minimap.GROESSTE, m.rahmen(1280, 720).seite());
        // Ein kleineres Fenster, eine kleinere Minimap, auch unter der kleinsten beim Ziehen; die Reichweite folgt.
        assertEquals(43, m.rahmen(160, 80).seite());
        assertEquals(Minimap.reichweite(m.zoom(), Minimap.sicht(43, true, false)), m.reichweite());
        // Gezogen gilt die Seite für diesen Schirm; der Anteil übersteht den Neustart.
        m.stelle(4, 4, 200, 640, 360);
        assertEquals(200, m.rahmen(640, 360).seite());
        assertEquals(133, m.rahmen(427, 240).seite());
        Path datei = ordner.resolve("heroicmap.properties");
        m.schreibe(datei);
        Minimap neu = new Minimap();
        neu.lies(datei);
        neu.setzeSkin(Skin.OHNE);
        assertEquals(133, neu.rahmen(427, 240).seite());
    }

    @Test
    void alteGroesseGiltImErstenSchirm(@TempDir Path ordner) throws Exception {
        // Eine Datei von vor dem Anteil: Die Seite in Einheiten gilt im ersten Schirm, danach folgt sie dem Fenster.
        Path datei = ordner.resolve("heroicmap.properties");
        Files.writeString(datei, "groesse=200\n");
        Minimap m = new Minimap();
        m.lies(datei);
        m.setzeSkin(Skin.OHNE);
        assertEquals(200, m.rahmen(640, 360).seite());
        assertEquals(100, m.rahmen(320, 180).seite());
        m.schreibe(datei);
        assertTrue(Files.readString(datei).contains("groesse_anteil="));
        // Ein Anteil von 0 oder Unlesbares gilt nicht; dann wieder die Seite aus groesse.
        Files.writeString(datei, "groesse_anteil=0\ngroesse=100\n");
        m = new Minimap();
        m.lies(datei);
        m.setzeSkin(Skin.OHNE);
        assertEquals(100, m.rahmen(640, 360).seite());
    }

    @Test
    void einstellungenUeberstehenDenNeustart(@TempDir Path ordner) throws Exception {
        Path datei = ordner.resolve("config").resolve("heroicmap.properties");
        Minimap vorher = new Minimap();
        vorher.setzeSkin(Skin.OHNE);
        vorher.setzeSichtbar(false);
        vorher.setzeScale(4);
        vorher.setzeZoom(1);
        vorher.setzeRund(true);
        vorher.setzeShow(false);
        vorher.setzeAblage(Downloads.Ablage.HASH);
        vorher.setzeChunklinien(true);
        vorher.setzeEffekte(false);
        vorher.setzeDrehen(true);
        vorher.schalteVerzierung(0);
        vorher.schalteVerzierung(2);
        vorher.setzeTechnikOffen(true);
        vorher.stelle(20, 30, 200, 640, 360);
        vorher.schreibe(datei);

        Minimap nachher = new Minimap();
        assertTrue(nachher.verzierungen(), "Vorgabe an");
        assertFalse(nachher.technikOffen(), "Vorgabe zu");
        nachher.lies(datei);
        // N und S durchsichtig, O und W nicht; Technik offen (mod#175).
        assertTrue(nachher.verzierungAus(0) && !nachher.verzierungAus(1) && nachher.verzierungAus(2) && !nachher.verzierungAus(3));
        assertTrue(nachher.technikOffen());
        assertFalse(nachher.sichtbar());
        assertEquals(4, nachher.aufloesung());
        assertEquals(1, nachher.zoom());
        assertTrue(nachher.rund());
        assertFalse(nachher.show());
        assertEquals(Downloads.Ablage.HASH, nachher.ablage());
        assertTrue(nachher.chunklinien());
        assertFalse(nachher.effekte());
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
    void koordinatenDreiModiUndGespeichert(@TempDir Path ordner) throws Exception {
        // Blockkoordinaten, auch negativ abgerundet.
        assertNull(Minimap.werte(Minimap.Koordinaten.AUS, 1.5, 64.2, -0.5));
        assertArrayEquals(new Object[] {1, -1}, Minimap.werte(Minimap.Koordinaten.XZ, 1.5, 64.2, -0.5));
        assertArrayEquals(new Object[] {1, 64, -1}, Minimap.werte(Minimap.Koordinaten.XYZ, 1.5, 64.2, -0.5));
        Path datei = ordner.resolve("heroicmap.properties");
        for (Minimap.Koordinaten k : Minimap.Koordinaten.values()) {
            Minimap m = new Minimap();
            m.setzeKoordinaten(k);
            m.schreibe(datei);
            Minimap neu = new Minimap();
            neu.lies(datei);
            assertEquals(k, neu.koordinaten());
        }
    }

    @Test
    void zeitAmTodespunktReihumUndGespeichert(@TempDir Path ordner) throws Exception {
        // Ein Klick geht reihum, nach keiner wieder Datum und Uhrzeit; Vorgabe Datum und Uhrzeit, auch bei einem unbekannten Wert.
        assertEquals(Minimap.TodZeit.DATUM_UHRZEIT, new Minimap().todZeit());
        Minimap.TodZeit stil = Minimap.TodZeit.DATUM_UHRZEIT;
        List<Minimap.TodZeit> reihe = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            stil = stil.naechste();
            reihe.add(stil);
        }
        assertEquals(List.of(Minimap.TodZeit.DATUM, Minimap.TodZeit.UHRZEIT, Minimap.TodZeit.AUS, Minimap.TodZeit.DATUM_UHRZEIT), reihe);
        Path datei = ordner.resolve("heroicmap.properties");
        for (Minimap.TodZeit z : Minimap.TodZeit.values()) {
            Minimap m = new Minimap();
            m.setzeTodZeit(z);
            m.schreibe(datei);
            assertTrue(Files.readString(datei).contains("tod_zeit=" + z.name().toLowerCase(Locale.ROOT)), z.name());
            Minimap neu = new Minimap();
            neu.lies(datei);
            assertEquals(z, neu.todZeit());
        }
        Files.writeString(datei, "tod_zeit=morgen\n");
        Minimap neu = new Minimap();
        neu.lies(datei);
        assertEquals(Minimap.TodZeit.DATUM_UHRZEIT, neu.todZeit());
    }

    @Test
    void beiF3BleibtWegOderMitteUndGespeichert(@TempDir Path ordner) throws Exception {
        // Oben rechts auf einem Schirm 427 breit: bleibt, weg, oder waagrecht in die Mitte auf derselben Höhe.
        Minimap.Rahmen r = new Minimap.Rahmen(427 - 128 - 13, 13, 128);
        assertEquals(r, Minimap.beiF3(r, Minimap.BeiF3.BLEIBT, 427));
        assertNull(Minimap.beiF3(r, Minimap.BeiF3.AUS, 427));
        assertEquals(new Minimap.Rahmen((427 - 128) / 2, 13, 128), Minimap.beiF3(r, Minimap.BeiF3.MITTE, 427));
        // Vorgabe bleibt, wie bisher; jede Wahl übersteht Schreiben und Lesen.
        assertEquals(Minimap.BeiF3.BLEIBT, new Minimap().beiF3());
        Path datei = ordner.resolve("heroicmap.properties");
        for (Minimap.BeiF3 f : Minimap.BeiF3.values()) {
            Minimap m = new Minimap();
            m.setzeBeiF3(f);
            m.schreibe(datei);
            Minimap neu = new Minimap();
            neu.lies(datei);
            assertEquals(f, neu.beiF3());
        }
    }

    @Test
    void koordinatenUnterOderUeberDerMinimap() {
        // Mittig unter der Minimap; ganz unten am Schirm darüber.
        Minimap.Rahmen r = new Minimap.Rahmen(100, 10, 128);
        assertArrayEquals(new int[] {100 + (128 - 60) / 2, 10 + 128 + 1 + 2}, Minimap.koordinatenLage(r, 1, 4, 640, 360, 60, 9));
        Minimap.Rahmen unten = new Minimap.Rahmen(100, 360 - 128 - 4, 128);
        assertArrayEquals(new int[] {100 + (128 - 60) / 2, 360 - 128 - 4 - 4 - 2 - 9}, Minimap.koordinatenLage(unten, 4, 4, 640, 360, 60, 9));
    }

    @Test
    void koordinatenGanzAufDemSchirm() {
        // Die Zeile mit y am Rand der Welt, 180 Einheiten, unter einer Minimap mit 64 Seite auf 320 × 240 (mod#157).
        // In der Ecke links und rechts bündig mit der Aussenkante der Minimap, in der Mitte mittig darunter.
        assertArrayEquals(new int[] {4, 4 + 64 + 1 + 2}, Minimap.koordinatenLage(new Minimap.Rahmen(4, 4, 64), 1, 4, 320, 240, 180, 9));
        assertArrayEquals(new int[] {252 + 64 - 180, 71}, Minimap.koordinatenLage(new Minimap.Rahmen(252, 4, 64), 1, 4, 320, 240, 180, 9));
        assertArrayEquals(new int[] {128 + (64 - 180) / 2, 71}, Minimap.koordinatenLage(new Minimap.Rahmen(128, 4, 64), 1, 4, 320, 240, 180, 9));
        // Weder darunter noch darüber Platz: unten am Schirm, auf der Minimap.
        assertArrayEquals(new int[] {30, 240 - 9}, Minimap.koordinatenLage(new Minimap.Rahmen(4, 4, 232), 1, 4, 320, 240, 180, 9));
        // Breiter als der Schirm: links am Abstand.
        assertArrayEquals(new int[] {4, 71}, Minimap.koordinatenLage(new Minimap.Rahmen(252, 4, 64), 1, 4, 320, 240, 400, 9));
        // Verkleinert nur, wenn sie nicht zwischen die Ränder passt: 180 auf 320 nicht, 400 auf 312 Platz.
        assertEquals(1f, Minimap.koordinatenMassstab(320, 4, 180));
        assertEquals(1f, Minimap.koordinatenMassstab(320, 4, 312));
        assertEquals(312f / 400, Minimap.koordinatenMassstab(320, 4, 400), 1e-6);
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
        // Ein unbekannter Rahmen ist die Vorgabe.
        nachher.setzeSkin("quatsch");
        assertEquals(Skin.BIOM, nachher.skin());
    }

    @Test
    void rahmenNurGewaehltGespeichert(@TempDir Path ordner) throws Exception {
        Path datei = ordner.resolve("heroicmap.properties");
        Minimap m = new Minimap();
        // Aus einer älteren Version: ein Rahmen ausser „ohne“ war gewählt; rahmen=ohne ist von der alten Vorgabe nicht zu unterscheiden.
        Files.writeString(datei, "rahmen=uhr\n");
        m.lies(datei);
        assertEquals("uhr", m.skin());
        m.schreibe(datei);
        assertTrue(Files.readString(datei).contains("rahmen_wahl=uhr"));
        Files.writeString(datei, "rahmen=ohne\n");
        m.lies(datei);
        assertEquals(Skin.BIOM, m.skin());
        // Ohne Wahl schreibt der Mod nichts zum Rahmen; so gilt später eine andere Vorgabe.
        m.schreibe(datei);
        assertFalse(Files.readString(datei).contains("rahmen"));
        // Gewählt bleibt gewählt, auch „ohne“; ein unbekannter gilt als nicht gewählt.
        Files.writeString(datei, "rahmen_wahl=ohne\nrahmen=uhr\n");
        m.lies(datei);
        assertEquals(Skin.OHNE, m.skin());
        Files.writeString(datei, "rahmen_wahl=quatsch\n");
        m.lies(datei);
        assertEquals(Skin.BIOM, m.skin());
        m.schreibe(datei);
        assertFalse(Files.readString(datei).contains("rahmen"));
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
        minimap.setzeSkin(Skin.OHNE);
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
        // Zoom 0,5: bei GUI-Massstab 2 ein Pixel je Block, bei 4 zwei; bei 3 sind es 1,5, dann 1 px je Block.
        assertEquals(1, Minimap.effektiv(4, 0.5, 2));
        assertEquals(2, Minimap.effektiv(4, 0.5, 4));
        assertEquals(1, Minimap.effektiv(4, 0.5, 3));
        assertEquals(1, Minimap.effektiv(4, 0.5, 1));
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
    void bannerNahDurchsichtig() {
        // Ab 24 Blöcken deckend, darunter linear bis 35 % bei 8, näher bleibt es dabei.
        assertEquals(1f, Minimap.deckung(30));
        assertEquals(1f, Minimap.deckung(24));
        assertEquals(0.675f, Minimap.deckung(16), 1e-6f);
        assertEquals(0.35f, Minimap.deckung(8));
        assertEquals(0.35f, Minimap.deckung(0));
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
        // Effekte in der Welt: Vorgabe an, die Wahl des Reviewers (mod#36).
        assertTrue(minimap.effekte());
        assertEquals(Minimap.Koordinaten.XZ, minimap.koordinaten());
        // Drehen: Vorgabe an, der Wunsch des Users.
        assertTrue(minimap.drehen());
        // Rahmen: Vorgabe biom, der Wunsch des Users.
        assertEquals(Skin.BIOM, minimap.skin());
        // Rechts oben; den Abstand der Skins kennt erst das Spiel.
        minimap.setzeSkin(Skin.OHNE);
        assertEquals(new Minimap.Rahmen(640 - 128 - 4, 4, 128), minimap.rahmen(640, 360));
    }

    @Test
    void schleierGekapptUndGespeichert(@TempDir Path ordner) throws Exception {
        Minimap m = new Minimap();
        assertEquals(Schleier.HOEHE, m.schleier());
        m.setzeSchleier(99);
        assertEquals(Schleier.MAX_HOEHE, m.schleier());
        m.setzeSchleier(0);
        assertEquals(Schleier.HOEHE, m.schleier());
        m.setzeSchleier(11);
        Path datei = ordner.resolve("heroicmap.properties");
        m.schreibe(datei);
        Minimap neu = new Minimap();
        neu.lies(datei);
        assertEquals(11, neu.schleier());
        // Was jemand von Hand über die Grenze schreibt, kommt gekappt an.
        Files.writeString(datei, "schleier=40\n");
        neu.lies(datei);
        assertEquals(Schleier.MAX_HOEHE, neu.schleier());
    }

    /** Wie viele Todespunkte: Vorgabe 1, 0 bis 16 gekappt und gespeichert; der alte Schalter todespunkt zieht um (mod#151). */
    @Test
    void todespunkteGespeichertUndAlterSchalter(@TempDir Path ordner) throws Exception {
        assertEquals(1, new Minimap().todespunkte());
        Minimap m = new Minimap();
        m.setzeTodespunkte(20);
        assertEquals(Minimap.MAX_TODESPUNKTE, m.todespunkte());
        m.setzeTodespunkte(-1);
        assertEquals(0, m.todespunkte());
        m.setzeTodespunkte(5);
        Path datei = ordner.resolve("heroicmap.properties");
        m.schreibe(datei);
        Minimap neu = new Minimap();
        neu.lies(datei);
        assertEquals(5, neu.todespunkte());
        // Bis 0.2.57: aus heisst keiner, an oder ohne Eintrag einer.
        Path alt = ordner.resolve("alt.properties");
        java.nio.file.Files.writeString(alt, "todespunkt=false\n");
        Minimap aus = new Minimap();
        aus.lies(alt);
        assertEquals(0, aus.todespunkte());
        java.nio.file.Files.writeString(alt, "todespunkt=true\n");
        Minimap an = new Minimap();
        an.lies(alt);
        assertEquals(1, an.todespunkte());
    }
}
