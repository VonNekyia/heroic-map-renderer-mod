package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Die Ebenen vom Server: Liste, Teile, Nadeln und ihre Grösse. Siehe docs/ebenen.md. */
class EbenenTest {

    private static void liste(Ebenen e, String eintraege) {
        e.empfange(JsonParser.parseString("{\"v\":1,\"typ\":\"ebenen\",\"jetzt\":1,\"ebenen\":[" + eintraege + "]}").getAsJsonObject());
    }

    private static String eintrag(String id, String version) {
        return "{\"id\":\"" + id + "\",\"name\":{\"de\":\"Städte\",\"en\":\"Towns\"},\"visible\":true,\"order\":100,\"version\":\"" + version + "\"}";
    }

    private static void teil(Ebenen e, String id, String version, int teil, int teile, String objekte) {
        e.empfange(JsonParser.parseString("{\"v\":1,\"typ\":\"ebene\",\"jetzt\":1,\"id\":\"" + id + "\",\"version\":\"" + version
                + "\",\"teil\":" + teil + ",\"teile\":" + teile + ",\"objects\":[" + objekte + "]}").getAsJsonObject());
    }

    private static String nadel(String id, double x) {
        return "{\"id\":\"" + id + "\",\"type\":\"pin\",\"at\":[" + x + ",-340.5],\"name\":\"" + id + "\"}";
    }

    @Test
    void erstAlleTeileErsetzenDieEbene() {
        Ebenen e = new Ebenen();
        liste(e, eintrag("b:staedte", "v1"));
        teil(e, "b:staedte", "v1", 2, 2, nadel("zwei", 2));
        assertEquals(List.of(), e.nadeln("b:staedte"));
        teil(e, "b:staedte", "v1", 1, 2, nadel("eins", 1));
        // In der Reihenfolge der Teile, nicht der Ankunft.
        assertEquals(List.of("eins", "zwei"), e.nadeln("b:staedte").stream().map(Ebenen.Nadel::name).toList());
    }

    @Test
    void dieAlteBleibtBisDieNeueGanzIst() {
        Ebenen e = new Ebenen();
        liste(e, eintrag("b:staedte", "v1"));
        teil(e, "b:staedte", "v1", 1, 1, nadel("alt", 1));
        liste(e, eintrag("b:staedte", "v2"));
        teil(e, "b:staedte", "v2", 1, 2, nadel("neu1", 1));
        assertEquals("alt", e.nadeln("b:staedte").getFirst().name());
        // Die Liste nennt v3: Die halbe v2 ist verworfen, ein später Teil von v2 gilt nicht und stört v3 nicht.
        liste(e, eintrag("b:staedte", "v3"));
        teil(e, "b:staedte", "v3", 1, 2, nadel("drei1", 1));
        teil(e, "b:staedte", "v2", 2, 2, nadel("neu2", 2));
        assertEquals("alt", e.nadeln("b:staedte").getFirst().name());
        teil(e, "b:staedte", "v3", 2, 2, nadel("drei2", 2));
        assertEquals(List.of("drei1", "drei2"), e.nadeln("b:staedte").stream().map(Ebenen.Nadel::name).toList());
        // Ein Teil vor seiner Liste gilt nicht.
        teil(e, "b:staedte", "v4", 1, 1, nadel("vier", 1));
        assertEquals("drei1", e.nadeln("b:staedte").getFirst().name());
    }

    @Test
    void wasAusDerListeFaelltIstWeg() {
        Ebenen e = new Ebenen();
        liste(e, eintrag("b:staedte", "v1") + "," + eintrag("b:doerfer", "v1"));
        teil(e, "b:staedte", "v1", 1, 1, nadel("a", 1));
        teil(e, "b:doerfer", "v1", 1, 2, nadel("b", 1));
        liste(e, eintrag("b:staedte", "v1"));
        assertEquals(1, e.nadeln("b:staedte").size());
        // Die halbe Sammlung ist mit weg: Ein zweiter Teil allein macht die Ebene nicht ganz.
        liste(e, eintrag("b:staedte", "v1") + "," + eintrag("b:doerfer", "v1"));
        teil(e, "b:doerfer", "v1", 2, 2, nadel("c", 2));
        assertEquals(List.of(), e.nadeln("b:doerfer"));
        // Teile einer Ebene, die die Liste nicht nennt, gelten nicht.
        teil(e, "b:fremd", "v1", 1, 1, nadel("d", 1));
        assertEquals(List.of(), e.nadeln("b:fremd"));
        e.leeren();
        assertEquals(List.of(), e.sichtbar());
        assertEquals(List.of(), e.nadeln("b:staedte"));
    }

    @Test
    void nurNadelnMitVorgaben() {
        JsonArray objekte = JsonParser.parseString("["
                + "{\"id\":\"a\",\"type\":\"pin\",\"at\":[120.5,-340.5]},"
                + "{\"id\":\"b\",\"type\":\"region\",\"points\":[[0,0],[10,0],[10,10]]},"
                + "{\"id\":\"c\",\"type\":\"pin\",\"at\":[1,2],\"size\":\"large\",\"color\":\"#40E53F80\",\"dimension\":\"minecraft:the_nether\",\"neu\":1},"
                + "{\"id\":\"d\",\"type\":\"pin\",\"at\":\"kaputt\"},"
                + "{\"id\":\"e\",\"type\":\"pin\",\"at\":[3,4],\"size\":\"small\",\"color\":\"rot\"}"
                + "]").getAsJsonArray();
        List<Ebenen.Nadel> n = Ebenen.nadeln(new JsonArray[] {objekte});
        assertEquals(3, n.size());
        assertEquals(new Ebenen.Nadel(120.5, -340.5, Ebenen.UEBERWELT, null, 1, Ebenen.FARBE), n.get(0));
        // Das Alpha wirkt am Schild nicht; unbekannte Felder übergeht der Mod.
        assertEquals(new Ebenen.Nadel(1, 2, "minecraft:the_nether", null, 0, 0xFF40E53F), n.get(1));
        assertEquals(new Ebenen.Nadel(3, 4, Ebenen.UEBERWELT, null, 2, Ebenen.FARBE), n.get(2));
    }

    @Test
    void grenzen() {
        StringBuilder viele = new StringBuilder();
        for (int i = 0; i < Ebenen.MAX_NADELN + 5; i++) {
            viele.append(i == 0 ? "" : ",").append(nadel("n" + i, i));
        }
        assertEquals(Ebenen.MAX_NADELN, Ebenen.nadeln(new JsonArray[] {JsonParser.parseString("[" + viele + "]").getAsJsonArray()}).size());
        Ebenen e = new Ebenen();
        StringBuilder ebenen = new StringBuilder();
        for (int i = 0; i < Ebenen.MAX_EBENEN + 1; i++) {
            ebenen.append(i == 0 ? "" : ",").append(eintrag("b:e" + i, "v"));
        }
        liste(e, ebenen.toString());
        assertEquals(Ebenen.MAX_EBENEN, e.sichtbar().size());
    }

    @Test
    void kaputteNachrichtAendertNichts() {
        Ebenen e = new Ebenen();
        liste(e, eintrag("b:staedte", "v1"));
        liste(e, "{\"id\":\"b:ohne_version\"}");
        assertEquals(List.of("b:staedte"), e.sichtbar().stream().map(Ebenen.Eintrag::id).toList());
        teil(e, "b:staedte", "v1", 3, 2, nadel("a", 1));
        teil(e, "b:staedte", "v1", 0, 2, nadel("a", 1));
        assertEquals(List.of(), e.nadeln("b:staedte"));
    }

    @Test
    void reihenfolgeUndName() {
        Ebenen e = new Ebenen();
        liste(e, "{\"id\":\"b:oben\",\"name\":{\"en\":\"Top\"},\"order\":5,\"version\":\"v\"},"
                + "{\"id\":\"b:verborgen\",\"name\":{\"de\":\"Verborgen\"},\"visible\":false,\"version\":\"v\"},"
                + "{\"id\":\"b:unten\",\"name\":{\"de\":\"Unten\"},\"version\":\"v\"}");
        // Unten zuerst; verborgene zeichnet der Mod nicht.
        List<Ebenen.Eintrag> s = e.sichtbar();
        assertEquals(List.of("b:unten", "b:oben"), s.stream().map(Ebenen.Eintrag::id).toList());
        assertEquals("Top", s.get(1).name(true));
        assertEquals("Unten", s.get(0).name(false));
    }

    @Test
    void wahlUeberstehtDenNeustart(@TempDir Path ordner) {
        String zwei = eintrag("b:staedte", "v") + ",{\"id\":\"b:geheim\",\"name\":{\"en\":\"Hidden\"},\"visible\":false,\"order\":200,\"version\":\"v\"}";
        Ebenen e = new Ebenen();
        e.wechsel(ordner.resolve("welt"));
        liste(e, zwei);
        assertEquals(List.of("b:staedte"), e.sichtbar().stream().map(Ebenen.Eintrag::id).toList());
        // Im Menü die oberste zuerst, auch verborgene.
        assertEquals(List.of("b:geheim", "b:staedte"), e.alle().stream().map(Ebenen.Eintrag::id).toList());
        e.setze("b:staedte", false);
        e.setze("b:geheim", true);
        Ebenen neu = new Ebenen();
        neu.wechsel(ordner.resolve("welt"));
        liste(neu, zwei);
        assertEquals(List.of("b:geheim"), neu.sichtbar().stream().map(Ebenen.Eintrag::id).toList());
        // Eine andere Welt hat ihre eigene Wahl.
        neu.wechsel(ordner.resolve("andere"));
        assertEquals(List.of("b:staedte"), neu.sichtbar().stream().map(Ebenen.Eintrag::id).toList());
    }

    @Test
    void kaputteWahlGiltNicht(@TempDir Path ordner) throws IOException {
        Files.writeString(ordner.resolve("ebenen.properties"), "b\\:staedte=false\nkaputt=\\u00zz\n");
        Ebenen e = new Ebenen();
        e.wechsel(ordner);
        liste(e, eintrag("b:staedte", "v"));
        assertEquals(1, e.sichtbar().size());
        // Ohne Ordner nur im Speicher.
        e.wechsel(null);
        e.setze("b:staedte", false);
        assertEquals(0, e.sichtbar().size());
    }

    @Test
    void schlichterText() {
        JsonArray objekte = JsonParser.parseString("[{\"id\":\"a\",\"type\":\"pin\",\"at\":[0,0],\"name\":\"\u00a7cRot\"}]").getAsJsonArray();
        assertEquals("Rot", Ebenen.nadeln(new JsonArray[] {objekte}).getFirst().name());
    }

    @Test
    void stufenNachBlockbreite() {
        // Ab 1/2 Einheit je Block die Grundgrösse, ab 1/8 eine kleiner, ab 1/32 zwei, darunter keine.
        assertEquals(0, Ebenen.stufen(4));
        assertEquals(0, Ebenen.stufen(0.5));
        assertEquals(1, Ebenen.stufen(0.4999));
        assertEquals(1, Ebenen.stufen(1 / 8.0));
        assertEquals(2, Ebenen.stufen(0.1249));
        assertEquals(2, Ebenen.stufen(1 / 32.0));
        assertEquals(3, Ebenen.stufen(0.031));
    }

    @Test
    void farben() {
        assertEquals(0xFF40E53F, Ebenen.farbe("#40E53F"));
        assertEquals(0xFF40E53F, Ebenen.farbe("#40e53fDD"));
        assertEquals(Ebenen.FARBE, Ebenen.farbe("#40E53"));
        assertNull(new Ebenen.Nadel(0, 0, Ebenen.UEBERWELT, null, 1, 0).name());
        assertTrue(Ebenen.FARBE >>> 24 == 0xFF);
    }
}
