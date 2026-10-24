package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Der Name, den ein Server meldet: Prüfung, Gedächtnis und Umzug des Ordners. Siehe docs/download.md, „Ablage“. */
class ServernameTest {

    @Test
    void nurEinHostname() {
        assertEquals("play.example.net", Servername.gueltig("play.example.net"));
        assertEquals("a-b.c0", Servername.gueltig("a-b.c0"));
        assertNull(Servername.gueltig(null));
        assertNull(Servername.gueltig(""));
        assertNull(Servername.gueltig("Play.Example.net"));
        assertNull(Servername.gueltig("../oben"));
        assertNull(Servername.gueltig("a/b"));
        assertNull(Servername.gueltig("a b"));
        // Ein echter Hostname, wie das Plugin ihn schickt: keine leeren Teile, kein Bindestrich am Rand, Teile bis 63, zusammen bis 253.
        assertNull(Servername.gueltig("."));
        assertNull(Servername.gueltig(".."));
        assertNull(Servername.gueltig("a..b"));
        assertNull(Servername.gueltig(".a"));
        assertNull(Servername.gueltig("a."));
        assertNull(Servername.gueltig("-a.de"));
        assertNull(Servername.gueltig("a-.de"));
        assertEquals("x".repeat(63) + ".de", Servername.gueltig("x".repeat(63) + ".de"));
        assertNull(Servername.gueltig("x".repeat(64) + ".de"));
        String lang = ("x".repeat(63) + ".").repeat(3) + "x".repeat(61);
        assertEquals(253, lang.length());
        assertEquals(lang, Servername.gueltig(lang));
        assertNull(Servername.gueltig(lang + "x"));
    }

    @Test
    void gemerktJeAdresse(@TempDir Path wurzel) {
        assertNull(Servername.lies(wurzel, "0"));
        Servername.merke(wurzel, "0", "play.example.net");
        Servername.merke(wurzel, "Play.Example.net:25566", "example.net");
        assertEquals("play.example.net", Servername.lies(wurzel, "0"));
        // Gross und klein wie eingetippt zählen gleich.
        assertEquals("example.net", Servername.lies(wurzel, "play.example.net:25566"));
        // Ein angebot ohne Namen nimmt ihn heraus, die anderen bleiben.
        Servername.merke(wurzel, "0", null);
        assertNull(Servername.lies(wurzel, "0"));
        assertEquals("example.net", Servername.lies(wurzel, "play.example.net:25566"));
        // Ein neuer Name ersetzt den alten.
        Servername.merke(wurzel, "play.example.net:25566", "neu.example.net");
        assertEquals("neu.example.net", Servername.lies(wurzel, "Play.Example.net:25566"));
    }

    @Test
    void angebotOhneNamenLaesstIhnStehen(@TempDir Path wurzel) {
        // Erst ein Angebot mit Namen, dann eines ohne, etwa von einem anderen Server hinter derselben Adresse; die nächste
        // Sitzung liest den Namen noch, und die Karte liegt weiter in seinem Ordner. Siehe docs/download.md, „Ablage“.
        Servername.nachAngebot(wurzel, "0", "play.example.net");
        Servername.nachAngebot(wurzel, "0", null);
        assertEquals("play.example.net", Servername.lies(wurzel, "0"));
        // Ein anderer Name ersetzt ihn.
        Servername.nachAngebot(wurzel, "0", "neu.example.net");
        assertEquals("neu.example.net", Servername.lies(wurzel, "0"));
        // Ohne gemerkten Namen bleibt es ohne.
        Servername.nachAngebot(wurzel, "1", null);
        assertNull(Servername.lies(wurzel, "1"));
    }

    @Test
    void ordnerZiehtEinmalUm(@TempDir Path wurzel) throws Exception {
        Path alt = wurzel.resolve("0").resolve("welt-0123456789abcdef"), neu = wurzel.resolve("play.example.net").resolve("welt-0123456789abcdef");
        Files.createDirectories(alt.resolve("survival").resolve("4"));
        Files.writeString(alt.resolve("wegpunkte.json"), "[]");
        assertTrue(Servername.zieheUm(alt, neu));
        assertTrue(Files.exists(neu.resolve("wegpunkte.json")));
        assertTrue(Files.isDirectory(neu.resolve("survival").resolve("4")));
        // Der leere Ordner der Adresse geht mit.
        assertFalse(Files.exists(wurzel.resolve("0")));
        // Ein zweites Mal gibt es nichts mehr umzuziehen.
        assertFalse(Servername.zieheUm(alt, neu));
    }

    @Test
    void ohneUmzugWennDerNeueSchonDaIst(@TempDir Path wurzel) throws Exception {
        Path alt = wurzel.resolve("1.2.3.4").resolve("welt-1"), neu = wurzel.resolve("play.example.net").resolve("welt-1");
        Path andere = wurzel.resolve("1.2.3.4").resolve("welt-2");
        Files.createDirectories(alt);
        Files.createDirectories(andere);
        Files.createDirectories(neu);
        Files.writeString(alt.resolve("wegpunkte.json"), "[1]");
        // Derselbe Server unter zwei Adressen: nicht zusammengeführt, der alte bleibt.
        assertFalse(Servername.zieheUm(alt, neu));
        assertTrue(Files.exists(alt.resolve("wegpunkte.json")));
        // Gleicher Pfad, etwa in der Ablage Hash: nichts zu tun.
        assertFalse(Servername.zieheUm(neu, neu));
        // Zieht welt-1 um, bleibt der Ordner der Adresse für welt-2.
        Files.delete(neu);
        assertTrue(Servername.zieheUm(alt, neu));
        assertTrue(Files.isDirectory(andere));
    }

    @Test
    void nameGiltNurOhneAltenOrdnerOderNachDemUmzug(@TempDir Path wurzel) throws Exception {
        Path alt = wurzel.resolve("0").resolve("welt-1"), neu = wurzel.resolve("play.example.net").resolve("welt-1");
        // Kein Ordner nach der Adresse: nichts umzuziehen, der Name gilt.
        assertTrue(Servername.gilt(alt, neu, false));
        // Gleicher Pfad, ohne Namen oder in der Ablage Hash.
        Files.createDirectories(alt);
        assertTrue(Servername.gilt(alt, alt, true));
        // Ein Download läuft in den alten: kein Name, der alte bleibt.
        assertFalse(Servername.gilt(alt, neu, true));
        assertTrue(Files.isDirectory(alt));
        // Der Umzug scheitert, hier weil an der Stelle des Ordners für den Namen eine Datei liegt: kein Name.
        Files.writeString(neu.getParent(), "");
        assertFalse(Servername.gilt(alt, neu, false));
        assertTrue(Files.isDirectory(alt));
        // Den neuen gibt es schon: kein Name, nicht zusammengeführt.
        Files.delete(neu.getParent());
        Files.createDirectories(neu);
        assertFalse(Servername.gilt(alt, neu, false));
        // Der Umzug klappt: der Name gilt.
        Files.delete(neu);
        assertTrue(Servername.gilt(alt, neu, false));
        assertTrue(Files.isDirectory(neu));
        assertFalse(Files.exists(alt));
    }

    @Test
    void reiheKenntDownloadsUnterEinemOrdner() {
        Reihe reihe = new Reihe();
        assertTrue(reihe.halte(Path.of("heroicmap", "0", "welt-1", "survival").toString()));
        assertTrue(reihe.belegtUnter(Path.of("heroicmap", "0", "welt-1").toString()));
        assertFalse(reihe.belegtUnter(Path.of("heroicmap", "0", "welt-2").toString()));
    }
}
