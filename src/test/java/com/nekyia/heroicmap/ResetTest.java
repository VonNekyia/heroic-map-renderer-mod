package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reset erkennen: der Entscheid, die veralteten Ordner, die Bäume einer Welt und ihr Ruhename. Siehe docs/download.md, „Reset“. */
class ResetTest {

    private static final String OBEN = "minecraft:overworld", NETHER = "minecraft:the_nether";
    private static final String UID = "0d9c1a2b-3c4d-4e5f-8a9b-0c1d2e3f4a5b", ANDERE = "7a1e0000-1111-4222-8333-444455556666";
    private static final Instant T = Instant.parse("2026-10-24T10:00:00Z");

    private static Ausweis.Welt welt(long zuletzt, String uid) {
        return new Ausweis.Welt(T, T, 1, 0, zuletzt, zuletzt, uid);
    }

    @Test
    void entscheid() {
        assertEquals(Reset.Art.GLEICH, Reset.pruefe(null, UID, 5), "ohne Ausweis nichts zu vergleichen");
        // Mit UID vom Plugin entscheidet nur die UID; ohne alte wird sie eingetragen.
        assertEquals(Reset.Art.GLEICH, Reset.pruefe(welt(1_000_000, null), UID, 5));
        assertEquals(Reset.Art.GLEICH, Reset.pruefe(welt(1_000_000, UID), UID, 5), "gleiche UID, auch wenn die Zeit fällt");
        assertEquals(Reset.Art.SICHER, Reset.pruefe(welt(5, UID), ANDERE, 1_000_000), "andere UID, auch wenn die Zeit steigt");
        // Ohne UID: unter ein Viertel der letzten und um mehr als eine Stunde, 72 000 Ticks.
        assertEquals(Reset.Art.UNSICHER, Reset.pruefe(welt(1_000_000, null), null, 1_000));
        assertEquals(Reset.Art.GLEICH, Reset.pruefe(welt(1_000_000, null), null, 250_000), "genau ein Viertel ist nicht darunter");
        assertEquals(Reset.Art.UNSICHER, Reset.pruefe(welt(1_000_000, null), null, 249_999));
        assertEquals(Reset.Art.GLEICH, Reset.pruefe(welt(72_000, null), null, 0), "genau eine Stunde ist nicht mehr");
        assertEquals(Reset.Art.UNSICHER, Reset.pruefe(welt(72_001, null), null, 0));
        assertEquals(Reset.Art.GLEICH, Reset.pruefe(welt(50_000, null), null, 0), "kurze Welt, etwa ein anderer Pod");
        assertEquals(Reset.Art.GLEICH, Reset.pruefe(welt(1_000_000, null), null, -1), "ohne Spielzeit");
        assertEquals(Reset.Art.GLEICH, Reset.pruefe(welt(-1, null), null, 0), "ohne alte Spielzeit");
        assertEquals(Reset.Art.GLEICH, Reset.pruefe(welt(1_000_000, null), null, 2_000_000));
    }

    @Test
    void nurUnterDerselbenAdresse() {
        // Server und Spiegel teilen einen Ordner, gleicher Name und Hash, jeder mit eigener UID: Ein Wechsel trägt nur ein.
        String server = "play.example.net", spiegel = "spiegel.example.net:25566", dritte = "3b2c0000-5555-4666-8777-888899990000";
        Ausweis a = Ausweis.neu("1111111111111111").mit(server, "main").besucht(OBEN, T, ZoneOffset.UTC).uid(OBEN, UID);
        assertEquals(Reset.Art.GLEICH, Reset.pruefe(a, OBEN, spiegel, ANDERE, -1), "andere Adresse, andere UID: kein Löschen");
        Ausweis b = a.mit(spiegel, "main").uid(OBEN, ANDERE);
        assertEquals(Reset.Art.SICHER, Reset.pruefe(b, OBEN, spiegel, dritte, -1), "dieselbe Adresse, neue UID: löschen");
        assertEquals(Reset.Art.GLEICH, Reset.pruefe(b, OBEN, server, UID, -1), "zurück zum Server: wieder nur eintragen");
        // Ohne UID ebenso: Die Spielzeit fällt nur gegen die unter derselben Adresse.
        Ausweis c = a.alter(OBEN, 1_000_000);
        assertEquals(Reset.Art.GLEICH, Reset.pruefe(c, OBEN, spiegel, null, 1_000));
        assertEquals(Reset.Art.UNSICHER, Reset.pruefe(c, OBEN, server, null, 1_000));
        assertEquals(Reset.Art.GLEICH, Reset.pruefe(null, OBEN, server, UID, -1), "ohne Ausweis");
        assertEquals(Reset.Art.GLEICH, Reset.pruefe(a.mit("", "main"), OBEN, server, ANDERE, -1), "nach „Ort …“ ohne Netz");
        assertEquals(Reset.Art.GLEICH, Reset.pruefe(a, NETHER, server, ANDERE, -1), "eine Welt, die der Ausweis nicht kennt");
    }

    @Test
    void uidAusDerAntwort() {
        assertTrue(Reset.uid(UID));
        assertFalse(Reset.uid(UID.toUpperCase()));
        assertFalse(Reset.uid(UID.replace("-", "")));
        assertFalse(Reset.uid(null));
        JsonObject json = JsonParser.parseString("{\"welten\":{\"minecraft:overworld\":{\"uid\":\"" + UID + "\"},"
                + "\"minecraft:the_nether\":{\"uid\":\"KAPUTT\"},\"minecraft:farm\":\"" + UID + "\",\"minecraft:the_end\":{}}}").getAsJsonObject();
        assertEquals(Map.of(OBEN, UID), Downloads.uidsAus(json));
        assertEquals(Map.of(), Downloads.uidsAus(JsonParser.parseString("{\"karte\":\"main\"}").getAsJsonObject()), "älteres Plugin");
        JsonObject viele = new JsonObject(), welten = new JsonObject();
        for (int i = 0; i < 70; i++) {
            JsonObject w = new JsonObject();
            w.addProperty("uid", UID);
            welten.add("minecraft:w" + i, w);
        }
        viele.add("welten", welten);
        assertEquals(64, Downloads.uidsAus(viele).size());
    }

    /** Ein Download der Welt {@code dimension} mit satz.json und map.json, Massstab 4. */
    private static void karte(Path baum, String dimension) throws IOException {
        Satz.schreibe(baum, "Karte", dimension, 4);
        Files.createDirectories(baum.resolve("4"));
        Files.writeString(baum.resolve("4/map.json"), "{\"tileSize\":256,\"scale\":4,\"minZoom\":0,\"maxZoom\":2}");
    }

    /** Ein Ordner einer Welt unter play.example.net/karte-<server> mit Ausweis und einem Baum der Oberwelt. */
    private static Path ordner(Path w, String server, String hash, String netz, Instant erste, Instant zuletzt) throws IOException {
        Path welt = w.resolve("play.example.net/karte-" + server + "/welt-" + hash);
        karte(welt.resolve("top"), OBEN);
        new Ausweis(netz, server, hash, Map.of(OBEN, new Ausweis.Welt(erste, zuletzt, 1, 0, 10, 10, null))).schreibe(welt);
        return welt;
    }

    @Test
    void veraltet(@TempDir Path w) throws IOException {
        Instant frueh = T.minusSeconds(86_400), spaet = T;
        Path alt = ordner(w, "main", "1111111111111111", "play.example.net", frueh, frueh);
        Path neu = ordner(w, "main", "2222222222222222", "play.example.net", spaet, spaet);
        Ausweis jetzt = Ausweis.lies(neu);
        assertEquals(List.of(alt), Reset.veraltet(neu, jetzt, OBEN));
        assertEquals(List.of(), Reset.veraltet(neu, jetzt, NETHER), "andere Welt");
        // Andere Adresse, etwa der Spiegel: nie.
        assertEquals(List.of(), Reset.veraltet(neu, jetzt.mit("0", "main"), OBEN));
        // Ohne Netz oder ohne Namen: nie.
        assertEquals(List.of(), Reset.veraltet(neu, jetzt.mit("", "main"), OBEN));
        assertEquals(List.of(), Reset.veraltet(neu, jetzt.mit("play.example.net", ""), OBEN));
        // Der alte war nach dem ersten Besuch des neuen noch einmal dran: kein Wechselspiel aus Löschen.
        new Ausweis("play.example.net", "main", "1111111111111111", Map.of(OBEN, new Ausweis.Welt(frueh, spaet.plusSeconds(60), 2, 0, 10, 10, null)))
                .schreibe(alt);
        assertEquals(List.of(), Reset.veraltet(neu, jetzt, OBEN));
        // Ein anderer Server unter seiner eigenen Karte zählt nicht.
        Path build = ordner(w, "build", "3333333333333333", "play.example.net", frueh, frueh);
        assertEquals(List.of(), Reset.veraltet(neu, jetzt, OBEN));
        assertTrue(Files.exists(build));
    }

    @Test
    void baeumeUndRuhen(@TempDir Path w) throws IOException {
        Path welt = w.resolve("welt-1111111111111111");
        karte(welt.resolve("top"), OBEN);
        Path eigene = Selbst.anlegen(welt, OBEN, 4);
        karte(welt.resolve("tief"), NETHER);
        karte(welt.resolve("top-bis-20260101"), OBEN);
        Files.writeString(welt.resolve("top-bis-20260101/" + Reset.RUHT), "x");
        // Downloads und eigene Karten der Welt, ohne ruhende und ohne andere Welten.
        assertEquals(List.of(eigene, welt.resolve("top")), Reset.baeume(welt, OBEN));
        // Ruhend zeigt nie: ohne den aktiven Baum der Oberwelt keine Karte.
        assertNotNull(Satz.fuer(welt, OBEN, "main"));
        Laden.loesche(welt.resolve("top"));
        Laden.loesche(eigene);
        assertNull(Satz.fuer(welt, OBEN, "main"), "der ruhende Baum zeigt nicht");
    }

    @Test
    void ruhename() {
        LocalDate tag = LocalDate.of(2026, 10, 24);
        assertEquals("top-north-s-bis-20261024", Reset.ruhename("top-north-s", tag, Set.of()));
        assertEquals("top-north-s-bis-20261024-2", Reset.ruhename("top-north-s", tag, Set.of("top-north-s-bis-20261024")));
        assertEquals("top-north-s-bis-20261024-3",
                Reset.ruhename("top-north-s", tag, Set.of("top-north-s-bis-20261024", "top-north-s-bis-20261024-2")));
        // Höchstens 64 Zeichen, und ein Download besteht die Prüfung der Baumnamen.
        String lang = "a".repeat(64), name = Reset.ruhename(lang, tag, Set.of());
        assertEquals(64, name.length());
        assertTrue(name.endsWith("-bis-20261024"));
        assertTrue(Freigabe.baum(name));
        assertTrue(Freigabe.baum(Reset.ruhename("top", tag, Set.of())));
        // Eine eigene Karte behält ihr Präfix, das kein Baum vom Server haben kann.
        assertTrue(Reset.ruhename(Selbst.baum(OBEN, "main"), tag, Set.of()).startsWith(Selbst.PRAEFIX));
        assertEquals(64, Reset.ruhename(lang, tag, Set.of(name)).length());
    }

    @Test
    void neueGenerationNachDemReset() {
        Ausweis a = Ausweis.neu("1111111111111111").besucht(OBEN, T, ZoneOffset.UTC).alter(OBEN, 1_000_000).uid(OBEN, UID);
        Ausweis.Welt w = a.neueGeneration(OBEN, 20, ANDERE).welten().get(OBEN);
        assertEquals(20, w.alterZuletzt());
        assertEquals(20, w.alterMax());
        assertEquals(ANDERE, w.uid());
        // Danach ist derselbe Stand gleich, kein zweiter Reset.
        assertEquals(Reset.Art.GLEICH, Reset.pruefe(w, null, 30));
        assertEquals(Reset.Art.GLEICH, Reset.pruefe(w, ANDERE, 30));
    }
}
