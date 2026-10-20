package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Die Karte vom Plugin an der Antwort auf show und ihr Ordner, siehe docs/download.md, „Ablage“ (heroic-map-renderer#318). */
class KartennameTest {

    private static final long SEED = 0x0123456789abcdefL;
    private static final long NETHER = 0x1111111111111111L;
    private static final long ENDE = 0x2222222222222222L;

    @Test
    void karteAusDerAntwort() {
        assertEquals("main", Downloads.karteAus(JsonParser.parseString(
                "{\"v\":1,\"typ\":\"show\",\"erlaubt\":true,\"karte\":\"main\"}").getAsJsonObject()));
        assertEquals("bau_1-x", Downloads.karteAus(JsonParser.parseString("{\"karte\":\"bau_1-x\"}").getAsJsonObject()));
        assertNull(Downloads.karteAus(JsonParser.parseString("{\"v\":1,\"typ\":\"show\"}").getAsJsonObject()), "ohne Feld");
        // Nur, was das Plugin schickt: klein, ohne Pfad, höchstens 64 Zeichen.
        for (String falsch : new String[] {"\"Main\"", "\"../main\"", "\"a b\"", "\"\"", "\"" + "x".repeat(65) + "\"", "1", "{}"}) {
            assertNull(Downloads.karteAus(JsonParser.parseString("{\"karte\":" + falsch + "}").getAsJsonObject()), falsch);
        }
    }

    @Test
    void ordnerMitKarte() {
        assertEquals("mc.example.com/karte-main/welt-0123456789abcdef",
                Downloads.weltOrdner(Downloads.Ablage.IP, "Mc.Example.com:25566", null, "main", SEED));
        assertEquals("mc.example.com_25566/karte-main/welt-0123456789abcdef",
                Downloads.weltOrdner(Downloads.Ablage.IP_PORT, "Mc.Example.com:25566", null, "main", SEED));
        assertEquals("karte-main/welt-0123456789abcdef", Downloads.weltOrdner(Downloads.Ablage.HASH, "x", null, "main", SEED));
        // Der Name vom Server ersetzt die Adresse, die Karte kommt darunter.
        assertEquals("play.example.net/karte-build/welt-0123456789abcdef",
                Downloads.weltOrdner(Downloads.Ablage.IP, "0", "play.example.net", "build", SEED));
        // Ohne Karte wie bisher; ein Gerätename von Windows ist mit karte- davor keiner mehr.
        assertEquals("mc.example.com/welt-0123456789abcdef", Downloads.weltOrdner(Downloads.Ablage.IP, "mc.example.com", null, null, SEED));
        assertEquals("karte-con/welt-0123456789abcdef", Downloads.weltOrdner(Downloads.Ablage.HASH, "x", null, "con", SEED));
    }

    private static boolean gilt(Path wurzel, String karte, boolean belegt) {
        return Downloads.karteGilt(wurzel, Downloads.Ablage.IP, "mc.example.com", null, karte, SEED, p -> belegt);
    }

    @Test
    void umzugAusDemOrdnerOhneKarte(@TempDir Path wurzel) throws Exception {
        Path alt = wurzel.resolve("mc.example.com/welt-0123456789abcdef");
        Path neu = wurzel.resolve("mc.example.com/karte-main/welt-0123456789abcdef");
        Files.createDirectories(alt);
        Files.writeString(alt.resolve("wegpunkte.json"), "[]");
        assertTrue(gilt(wurzel, "main", false));
        assertEquals("[]", Files.readString(neu.resolve("wegpunkte.json")), "Wegpunkte ziehen mit");
        assertFalse(Files.exists(alt));
        // Den anderen Server gibt es danach ohne alten Ordner: Er fängt leer an.
        assertTrue(gilt(wurzel, "build", false));
        assertFalse(Files.exists(wurzel.resolve("mc.example.com/karte-build")), "nichts angelegt, nichts umgezogen");
    }

    @Test
    void solangeEinDownloadLaeuftKeinUmzug(@TempDir Path wurzel) throws Exception {
        Path alt = Files.createDirectories(wurzel.resolve("mc.example.com/welt-0123456789abcdef"));
        assertFalse(gilt(wurzel, "main", true), "in dieser Sitzung der Ordner ohne Karte");
        assertTrue(Files.isDirectory(alt));
        assertFalse(Files.exists(wurzel.resolve("mc.example.com/karte-main")));
    }

    @Test
    void gibtEsDenOrdnerDerKarteSchonGilterEr(@TempDir Path wurzel) throws Exception {
        // Was der Mod vor der Antwort auf show schrieb, liegt ohne Karte; der Ordner der Karte gilt trotzdem.
        Path alt = Files.createDirectories(wurzel.resolve("mc.example.com/welt-0123456789abcdef"));
        Files.writeString(alt.resolve("wegpunkte.json"), "[1]");
        Path neu = Files.createDirectories(wurzel.resolve("mc.example.com/karte-main/welt-0123456789abcdef"));
        Files.writeString(neu.resolve("wegpunkte.json"), "[2]");
        assertTrue(gilt(wurzel, "main", true), "auch während eines Downloads in den alten");
        assertEquals("[1]", Files.readString(alt.resolve("wegpunkte.json")), "zusammengeführt wird nicht");
        assertEquals("[2]", Files.readString(neu.resolve("wegpunkte.json")));
    }

    private static final Kartenname.Gilt JA = (karte, s) -> true;
    private static final Kartenname.Gilt NICHT_FRAGEN = (karte, s) -> {
        throw new AssertionError("nichts neu zu entscheiden");
    };

    @Test
    void jeSitzungUndJeHash() {
        var k = new Kartenname();
        var gefragt = new ArrayList<Long>();
        k.betreten(SEED, null, JA);
        assertNull(k.fuer(SEED), "vor der Antwort auf show, ohne Gedächtnis");
        assertTrue(k.vomServer("main", List.of(SEED), (karte, s) -> gefragt.add(s)));
        assertEquals(List.of(SEED), gefragt, "die schon betretene Welt");
        assertEquals("main", k.fuer(SEED));
        assertFalse(k.vomServer("build", List.of(SEED), JA), "die erste Antwort der Sitzung gilt");
        assertEquals("main", k.fuer(SEED));

        // Eine Welt, die erst später betreten wird, entscheidet beim Betreten, einmal.
        k.betreten(ENDE, null, JA);
        assertEquals("main", k.fuer(ENDE));
        k.betreten(NETHER, null, (karte, s) -> false);
        assertNull(k.fuer(NETHER), "dort läuft ein Download in den alten Ordner");
        k.betreten(NETHER, null, JA);
        assertNull(k.fuer(NETHER), "fest bis zur nächsten Sitzung");

        k.neueSitzung();
        assertNull(k.karte());
        assertFalse(k.bestaetigt());
        assertNull(k.fuer(SEED), "ein neuer Login vergisst die Karte");
        k.betreten(SEED, null, JA);
        assertNull(k.fuer(SEED), "ohne Karte nichts zu entscheiden");
        assertTrue(k.vomServer("build", List.of(SEED), JA), "die nächste Sitzung darf eine andere haben");
        assertEquals("build", k.fuer(SEED));
    }

    @Test
    void gemerkteKarteGiltGleichUndDerServerKorrigiert() {
        var k = new Kartenname();
        k.betreten(SEED, "main", JA);
        assertEquals("main", k.fuer(SEED), "gleich beim Betreten, vor der Antwort auf show");
        assertFalse(k.vomServer("main", List.of(SEED), NICHT_FRAGEN), "dieselbe: kein Wechsel");
        assertTrue(k.bestaetigt());

        k.neueSitzung();
        k.betreten(SEED, "main", JA);
        var gefragt = new ArrayList<String>();
        assertTrue(k.vomServer("build", List.of(SEED), (karte, s) -> gefragt.add(karte)), "eine andere korrigiert");
        assertEquals(List.of("build"), gefragt);
        assertEquals("build", k.fuer(SEED));

        k.neueSitzung();
        k.betreten(SEED, "main", JA);
        assertTrue(k.vomServer(null, List.of(SEED), NICHT_FRAGEN), "keine Karte korrigiert auch");
        assertNull(k.fuer(SEED));
        k.betreten(NETHER, "main", JA);
        assertNull(k.fuer(NETHER), "nach der Antwort zählt das Gedächtnis nicht mehr");

        k.neueSitzung();
        k.betreten(SEED, "../main", JA);
        assertNull(k.fuer(SEED), "ein ungültiger Eintrag gilt nicht");
    }

    @Test
    void gedaechtnisJeAdresseUndHash(@TempDir Path wurzel) {
        assertNull(Kartenname.lies(wurzel, "mc.example.com", SEED));
        Kartenname.merke(wurzel, "Mc.Example.com", List.of(SEED, NETHER), "main");
        assertEquals("main", Kartenname.lies(wurzel, "mc.example.com", SEED), "ohne Gross und Klein, wie beim Namen");
        assertEquals("main", Kartenname.lies(wurzel, "mc.example.com", NETHER));
        assertNull(Kartenname.lies(wurzel, "mc.example.com:25566", SEED), "je eingetippter Adresse");
        assertNull(Kartenname.lies(wurzel, "mc.example.com", ENDE), "je Hash");

        Kartenname.merke(wurzel, "andere.example", List.of(SEED), "build");
        Kartenname.merke(wurzel, "mc.example.com", List.of(SEED), "build");
        assertEquals("build", Kartenname.lies(wurzel, "mc.example.com", SEED), "die zuletzt gemeldete");
        assertEquals("main", Kartenname.lies(wurzel, "mc.example.com", NETHER));

        Kartenname.merke(wurzel, "mc.example.com", List.of(SEED, NETHER), null);
        assertNull(Kartenname.lies(wurzel, "mc.example.com", SEED), "keine Karte nimmt sie heraus");
        assertNull(Kartenname.lies(wurzel, "mc.example.com", NETHER));
        assertEquals("build", Kartenname.lies(wurzel, "andere.example", SEED), "andere Adressen bleiben");
    }

    @Test
    void bestandFindetBaeumeUnterKarte(@TempDir Path wurzel) throws Exception {
        schreibe(wurzel.resolve("host/karte-main/welt-01/survival/massstab.txt"), 10);
        schreibe(wurzel.resolve("host/karte-main/welt-01/survival/4/0/0/0.webp"), 1000);
        schreibe(wurzel.resolve("karte-main/welt-02/nether/satz.json"), 20);
        schreibe(wurzel.resolve("host/welt-01/survival/massstab.txt"), 30);
        Laden.Bestand b = Laden.bestand(wurzel, () -> false);
        assertEquals(List.of("host/karte-main/welt-01/survival", "host/welt-01/survival", "karte-main/welt-02/nether"),
                b.karten().stream().map(Laden.AufPlatte::pfad).toList());
        assertEquals(List.of(1010L, 30L, 20L), b.karten().stream().map(Laden.AufPlatte::bytes).toList());
    }

    private static void schreibe(Path datei, int bytes) throws Exception {
        Files.createDirectories(datei.getParent());
        Files.write(datei, new byte[bytes]);
    }
}
