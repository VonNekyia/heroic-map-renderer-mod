package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /** Wie der Kanal: erst auf dem Thread des Netzes gelesen, dann an die Ebenen. */
    private static void teil(Ebenen e, String id, String version, int teil, int teile, String objekte) {
        Ebenen.Teil t = Ebenen.Teil.lies("{\"v\":1,\"typ\":\"ebene\",\"jetzt\":1,\"id\":\"" + id + "\",\"version\":\"" + version
                + "\",\"teil\":" + teil + ",\"teile\":" + teile + ",\"objects\":[" + objekte + "]}");
        if (t != null) {
            e.teil(t);
        }
    }

    private static String viele(String vorsilbe, int anzahl) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < anzahl; i++) {
            s.append(i == 0 ? "" : ",").append(nadel(vorsilbe + i, i));
        }
        return s.toString();
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
    void keineEbeneAusZweiVersionen() {
        // Die Folge aus dem Review: v2 Teil 1/2, dann die Liste mit v3, dann v3 Teil 2/2.
        Ebenen e = new Ebenen();
        liste(e, eintrag("b:staedte", "v2"));
        teil(e, "b:staedte", "v2", 1, 2, nadel("zwei1", 1));
        liste(e, eintrag("b:staedte", "v3"));
        teil(e, "b:staedte", "v3", 2, 2, nadel("drei2", 2));
        assertEquals(List.of(), e.nadeln("b:staedte"));
        teil(e, "b:staedte", "v3", 1, 2, nadel("drei1", 1));
        assertEquals(List.of("drei1", "drei2"), e.nadeln("b:staedte").stream().map(Ebenen.Nadel::name).toList());
        // Nennt die Liste eine Kennung zweimal, gilt der erste Eintrag; ein Teil der zweiten version gilt nicht.
        Ebenen f = new Ebenen();
        liste(f, eintrag("b:staedte", "v2") + "," + eintrag("b:staedte", "v3"));
        assertEquals(1, f.sichtbar().size());
        teil(f, "b:staedte", "v2", 1, 2, nadel("zwei1", 1));
        teil(f, "b:staedte", "v3", 2, 2, nadel("drei2", 2));
        assertEquals(List.of(), f.nadeln("b:staedte"));
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
                + "{\"id\":\"c\",\"type\":\"pin\",\"at\":[1,2],\"size\":\"large\",\"color\":\"#40E53F80\",\"dimension\":\"minecraft:the_nether\",\"neu\":1,\"symbol\":{\"large\":\"images/burg_16.png\"}},"
                + "{\"id\":\"d\",\"type\":\"pin\",\"at\":\"kaputt\"},"
                + "{\"id\":\"e\",\"type\":\"pin\",\"at\":[3,4],\"size\":\"small\",\"color\":\"rot\"}"
                + "]").getAsJsonArray();
        List<Ebenen.Nadel> n = Ebenen.nadeln("b:staedte", "v1", objekte);
        assertEquals(3, n.size());
        assertEquals(new Ebenen.Nadel(120.5, -340.5, Ebenen.UEBERWELT, null, 1, Ebenen.FARBE, "b:staedte", "v1", null, null), n.get(0));
        // Das Alpha wirkt am Schild nicht; unbekannte Felder übergeht der Mod.
        assertEquals(new Ebenen.Nadel(1, 2, "minecraft:the_nether", null, 0, 0xFF40E53F, "b:staedte", "v1", "images/burg_16.png", null), n.get(1));
        assertEquals(new Ebenen.Nadel(3, 4, Ebenen.UEBERWELT, null, 2, Ebenen.FARBE, "b:staedte", "v1", null, null), n.get(2));
    }

    @Test
    void grenzen() {
        // Ein Teil liest höchstens eine Nadel über der Grenze; so merkt die Sammlung, dass es zu viele sind.
        assertEquals(Ebenen.MAX_NADELN + 1, Ebenen.nadeln("b:e", "v", JsonParser.parseString("[" + viele("n", Ebenen.MAX_NADELN + 5) + "]").getAsJsonArray()).size());
        Ebenen e = new Ebenen();
        StringBuilder ebenen = new StringBuilder();
        for (int i = 0; i < Ebenen.MAX_EBENEN + 1; i++) {
            ebenen.append(i == 0 ? "" : ",").append(eintrag("b:e" + i, "v"));
        }
        liste(e, ebenen.toString());
        assertEquals(Ebenen.MAX_EBENEN, e.sichtbar().size());
    }

    @Test
    void zuVieleNadelnVerwerfenDieSammlung() {
        Ebenen e = new Ebenen();
        liste(e, eintrag("b:staedte", "v1"));
        teil(e, "b:staedte", "v1", 1, 1, nadel("alt", 1));
        liste(e, eintrag("b:staedte", "v2"));
        teil(e, "b:staedte", "v2", 1, 3, viele("a", 600));
        teil(e, "b:staedte", "v2", 2, 3, viele("b", 600));
        teil(e, "b:staedte", "v2", 3, 3, nadel("c", 1));
        // Über 1000 in der Sammlung: verworfen, die alte bleibt.
        assertEquals(List.of("alt"), e.nadeln("b:staedte").stream().map(Ebenen.Nadel::name).toList());
        // Genau 1000 gehen.
        liste(e, eintrag("b:staedte", "v3"));
        teil(e, "b:staedte", "v3", 1, 2, viele("a", 600));
        teil(e, "b:staedte", "v3", 2, 2, viele("b", 400));
        assertEquals(Ebenen.MAX_NADELN, e.nadeln("b:staedte").size());
        // Mehr als 256 Teile nimmt der Mod nicht.
        liste(e, eintrag("b:staedte", "v4"));
        teil(e, "b:staedte", "v4", 1, Ebenen.MAX_TEILE + 1, nadel("viel", 1));
        assertEquals(Ebenen.MAX_NADELN, e.nadeln("b:staedte").size());
    }

    @Test
    void langeTexteUebergangen() {
        String name64 = "n".repeat(Ebenen.MAX_TEXT), name65 = "n".repeat(Ebenen.MAX_TEXT + 1);
        JsonArray objekte = JsonParser.parseString("[{\"id\":\"a\",\"type\":\"pin\",\"at\":[0,0],\"name\":\"" + name64 + "\"},"
                + "{\"id\":\"b\",\"type\":\"pin\",\"at\":[0,0],\"name\":\"" + name65 + "\"},"
                + "{\"id\":\"c\",\"type\":\"pin\",\"at\":[0,0],\"dimension\":\"" + "d".repeat(Ebenen.MAX_KENNUNG + 1) + "\"}]").getAsJsonArray();
        List<Ebenen.Nadel> n = Ebenen.nadeln("b:e", "v", objekte);
        // Ein zu langer Name fehlt, die Nadel bleibt; eine zu lange Dimension nimmt die Nadel mit.
        assertEquals(2, n.size());
        assertEquals(name64, n.get(0).name());
        assertNull(n.get(1).name());
        // Ein zu langes Feld eines Symbols fehlt, die Nadel bleibt.
        String feld = "images/" + "s".repeat(Ebenen.MAX_FELD - 12) + ".webp", lang = "images/" + "s".repeat(Ebenen.MAX_FELD - 11) + ".webp";
        JsonArray symbole = JsonParser.parseString("[{\"id\":\"a\",\"type\":\"pin\",\"at\":[0,0],\"symbol\":{\"large\":\"" + feld
                + "\",\"medium\":\"" + lang + "\"}}]").getAsJsonArray();
        Ebenen.Nadel mitSymbol = Ebenen.nadeln("b:e", "v", symbole).getFirst();
        assertEquals(feld, mitSymbol.symbolGross());
        assertNull(mitSymbol.symbolMittel());
        assertNull(Ebenen.Teil.lies("{\"v\":1,\"typ\":\"ebene\",\"id\":\"" + "i".repeat(Ebenen.MAX_KENNUNG + 1)
                + "\",\"version\":\"v\",\"teil\":1,\"teile\":1,\"objects\":[]}"));
    }

    @Test
    void kaputteNachrichtAendertNichts() {
        Ebenen e = new Ebenen();
        liste(e, eintrag("b:staedte", "v1"));
        liste(e, "{\"id\":\"b:ohne_version\"}");
        assertFalse(e.empfange(JsonParser.parseString("{\"ebenen\":[{\"id\":\"b:ohne_version\"}]}").getAsJsonObject()));
        assertTrue(e.empfange(JsonParser.parseString("{\"ebenen\":[" + eintrag("b:staedte", "v1") + "]}").getAsJsonObject()));
        assertEquals(List.of("b:staedte"), e.sichtbar().stream().map(Ebenen.Eintrag::id).toList());
        // Ein Teil ausserhalb von teile und einer ohne lesbare Objekte lassen die Sammlung stehen.
        teil(e, "b:staedte", "v1", 1, 3, nadel("a", 1));
        teil(e, "b:staedte", "v1", 5, 2, nadel("x", 1));
        teil(e, "b:staedte", "v1", 0, 3, nadel("x", 1));
        assertNull(Ebenen.Teil.lies("{\"v\":1,\"typ\":\"ebene\",\"id\":\"b:staedte\",\"version\":\"v1\",\"teil\":2,\"teile\":2,\"objects\":\"x\"}"));
        teil(e, "b:staedte", "v1", 2, 3, nadel("b", 2));
        teil(e, "b:staedte", "v1", 3, 3, nadel("c", 3));
        assertEquals(List.of("a", "b", "c"), e.nadeln("b:staedte").stream().map(Ebenen.Nadel::name).toList());
        // Andere Nachrichten sind kein Teil.
        assertNull(Ebenen.Teil.lies("{\"v\":1,\"typ\":\"spieler\"}"));
        assertNull(Ebenen.Teil.lies("{\"v\":2,\"typ\":\"ebene\",\"id\":\"b:staedte\",\"version\":\"v1\",\"teil\":1,\"teile\":1,\"objects\":[]}"));
        assertNull(Ebenen.Teil.lies("kein json"));
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
        // Gleichstand bei order: Die kleinere id steht in der Liste vorn und liegt oben, wird also zuletzt gezeichnet.
        liste(e, "{\"id\":\"b:a\",\"order\":1,\"version\":\"v\"},{\"id\":\"b:b\",\"order\":1,\"version\":\"v\"}");
        assertEquals(List.of("b:b", "b:a"), e.sichtbar().stream().map(Ebenen.Eintrag::id).toList());
        assertEquals(List.of("b:a", "b:b"), e.alle().stream().map(Ebenen.Eintrag::id).toList());
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
        assertEquals("Rot", Ebenen.nadeln("b:e", "v", objekte).getFirst().name());
    }

    @Test
    void formenMitVorgaben() {
        JsonArray objekte = JsonParser.parseString("["
                + "{\"id\":\"r\",\"type\":\"region\",\"polygons\":[{\"outer\":[[0,0],[10,0],[10,10],[0,10]]}],\"fill\":\"#40E53F55\"},"
                + "{\"id\":\"k\",\"type\":\"circle\",\"center\":[5,5],\"radius\":100,\"stroke\":{\"color\":\"#FFFFFFAA\",\"style\":\"dashed\"}},"
                + "{\"id\":\"l\",\"type\":\"line\",\"points\":[[0,0],[5,5]],\"stroke\":{\"width\":3,\"style\":\"dashed\",\"dash\":[10,4]}},"
                + "{\"id\":\"n\",\"type\":\"region\",\"polygons\":[{\"outer\":[[0,0],[1,0],[1,1]]}],\"stroke\":{\"width\":0}},"
                + "{\"id\":\"p\",\"type\":\"pin\",\"at\":[0,0]}"
                + "]").getAsJsonArray();
        Ebenen.Gelesen g = Ebenen.formen(objekte);
        List<Ebenen.Form> f = g.formen();
        assertEquals(4, f.size());
        assertEquals(0, g.verworfen());
        // Punkte: 4 der Region, 1 für den Kreis, 2 der Linie, 3 der zweiten Region.
        assertEquals(10, g.punkte());
        // Füllung mit Alpha; der Rand ohne Angabe 2 breit in der Füllung ohne Alpha.
        Ebenen.Flaeche r = (Ebenen.Flaeche) f.get(0);
        assertEquals(0x5540E53F, r.fuellung());
        assertEquals(new Ebenen.Rand(0xFF40E53F, 2, 0, 0), r.rand());
        assertArrayEquals(new double[] {0, 0, 10, 10, 0, 10}, r.trapeze());
        assertArrayEquals(new double[] {0, 0, 10, 10}, r.box());
        // Gestrichelt ohne dash: 8 und 6.
        Ebenen.Kreis k = (Ebenen.Kreis) f.get(1);
        assertEquals(0, k.fuellung());
        assertEquals(new Ebenen.Rand(0xAAFFFFFF, 2, 8, 6), k.rand());
        // Ohne Farbe und ohne Füllung #2B2B2B.
        assertEquals(new Ebenen.Rand(Ebenen.RANDFARBE, 3, 10, 4), ((Ebenen.Linie) f.get(2)).rand());
        // width 0 heisst ohne Rand.
        assertNull(((Ebenen.Flaeche) f.get(3)).rand());
        assertNull(((Ebenen.Flaeche) f.get(3)).trapeze());
    }

    @Test
    void formenGrenzen() {
        StringBuilder lang = new StringBuilder();
        for (int i = 0; i <= Ebenen.MAX_PUNKTE; i++) {
            lang.append(i == 0 ? "" : ",").append("[").append(i).append(",0]");
        }
        StringBuilder loecher = new StringBuilder();
        for (int i = 0; i <= Ebenen.MAX_LOECHER; i++) {
            loecher.append(i == 0 ? "" : ",").append("[[1,1],[2,1],[2,2]]");
        }
        JsonArray objekte = JsonParser.parseString("["
                + "{\"type\":\"line\",\"points\":[" + lang + "]},"
                + "{\"type\":\"circle\",\"center\":[0,0],\"radius\":" + (Ebenen.MAX_RADIUS + 1) + "},"
                + "{\"type\":\"circle\",\"center\":[0,0],\"radius\":0},"
                + "{\"type\":\"region\",\"polygons\":[{\"outer\":[[0,0],[10,0],[10,10]],\"holes\":[" + loecher + "]}]},"
                + "{\"type\":\"region\",\"polygons\":[{\"outer\":[[0,0],[10,0]]}]},"
                + "{\"type\":\"line\",\"points\":[[0,0]]},"
                + "{\"type\":\"line\",\"points\":[[0,0],[30000001,0]]},"
                + "{\"type\":\"circle\",\"center\":[0,0],\"radius\":" + Ebenen.MAX_RADIUS + "}"
                + "]").getAsJsonArray();
        // Nur der letzte Kreis hält alle Grenzen ein; die anderen sieben zählen als verworfen.
        Ebenen.Gelesen g = Ebenen.formen(objekte);
        List<Ebenen.Form> f = g.formen();
        assertEquals(1, f.size());
        assertEquals(7, g.verworfen());
        assertEquals(Ebenen.MAX_RADIUS, ((Ebenen.Kreis) f.getFirst()).radius());
    }

    @Test
    void formenKommenMitDerEbene() {
        Ebenen e = new Ebenen();
        liste(e, eintrag("b:staedte", "v1"));
        teil(e, "b:staedte", "v1", 1, 2, nadel("a", 1));
        teil(e, "b:staedte", "v1", 2, 2, "{\"id\":\"k\",\"type\":\"circle\",\"center\":[0,0],\"radius\":5,\"dimension\":\"minecraft:the_nether\"}");
        assertEquals(1, e.nadeln("b:staedte").size());
        assertEquals(1, e.formen("b:staedte").size());
        assertEquals("minecraft:the_nether", e.formen("b:staedte").getFirst().dimension());
        liste(e, eintrag("b:andere", "v1"));
        assertEquals(List.of(), e.formen("b:staedte"));
    }

    @Test
    void zuVieleObjekteVerwerfenDieSammlung() {
        Ebenen e = new Ebenen();
        liste(e, eintrag("b:staedte", "v1"));
        StringBuilder kreise = new StringBuilder();
        for (int i = 0; i < 6000; i++) {
            kreise.append(i == 0 ? "" : ",").append("{\"type\":\"circle\",\"center\":[0,0],\"radius\":5}");
        }
        teil(e, "b:staedte", "v1", 1, 2, kreise.toString());
        teil(e, "b:staedte", "v1", 2, 2, kreise.toString());
        assertEquals(List.of(), e.formen("b:staedte"));
    }

    @Test
    void teilErsetzt() {
        // Derselbe Teil zweimal: Der zweite ersetzt den ersten, auch in den Zählern; sonst wären es 12 001 Objekte.
        Ebenen e = new Ebenen();
        liste(e, eintrag("b:staedte", "v1"));
        String kreise = mal("{\"type\":\"circle\",\"center\":[0,0],\"radius\":5}", 6000);
        teil(e, "b:staedte", "v1", 1, 2, kreise);
        teil(e, "b:staedte", "v1", 1, 2, "{\"type\":\"circle\",\"center\":[0,0],\"radius\":7}");
        teil(e, "b:staedte", "v1", 2, 2, kreise);
        assertEquals(6001, e.formen("b:staedte").size());
        assertEquals(7, ((Ebenen.Kreis) e.formen("b:staedte").getFirst()).radius());
    }

    @Test
    void deckelDerPunkte() {
        String linie = linie(Ebenen.MAX_PUNKTE);
        // Ein Teil mit 210 000 Punkten taugt nicht; er liest nicht weiter als bis über den Deckel.
        assertNull(Ebenen.Teil.lies(ebene(1, 1, mal(linie, 21))));
        // Zwei Teile mit je 150 000 Punkten: Die Sammlung kommt über den Deckel und ist verworfen.
        Ebenen e = new Ebenen();
        liste(e, eintrag("b:grenzen", "v1"));
        teil(e, "b:grenzen", "v1", 1, 2, mal(linie, 15));
        teil(e, "b:grenzen", "v1", 2, 2, mal(linie, 15));
        assertEquals(List.of(), e.formen("b:grenzen"));
        // Gegenprobe: 150 000 und 50 000 Punkte gehen.
        liste(e, eintrag("b:grenzen", "v2"));
        teil(e, "b:grenzen", "v2", 1, 2, mal(linie, 15));
        teil(e, "b:grenzen", "v2", 2, 2, mal(linie, 5));
        assertEquals(20, e.formen("b:grenzen").size());
    }

    @Test
    void deckelUeberAlleEbenen() {
        // Drei Ebenen mit je 190 000 Punkten: Die dritte käme über 500 000 und bleibt, wie sie war.
        Ebenen e = new Ebenen();
        liste(e, eintrag("b:a", "v1") + "," + eintrag("b:b", "v1") + "," + eintrag("b:c", "v1"));
        String linien = mal(linie(Ebenen.MAX_PUNKTE), 19);
        teil(e, "b:a", "v1", 1, 1, linien);
        teil(e, "b:b", "v1", 1, 1, linien);
        teil(e, "b:c", "v1", 1, 1, linien);
        assertEquals(19, e.formen("b:a").size());
        assertEquals(19, e.formen("b:b").size());
        assertEquals(List.of(), e.formen("b:c"));
        // Eine neue version der ersten zählt nicht doppelt: je Ebene die grössere der beiden Sammlungen.
        liste(e, eintrag("b:a", "v2") + "," + eintrag("b:b", "v1") + "," + eintrag("b:c", "v1"));
        teil(e, "b:a", "v2", 1, 1, mal(linie(Ebenen.MAX_PUNKTE), 18));
        assertEquals(18, e.formen("b:a").size());
    }

    @Test
    void punkteUeberAlleRinge() {
        // Aussen 6000 und ein Loch mit 5000 Punkten: Jeder Ring allein hält die Grenze ein, zusammen nicht.
        JsonArray objekte = JsonParser.parseString("[{\"type\":\"region\",\"fill\":\"#FF000080\",\"polygons\":[{\"outer\":" + kreis(6000, 1000)
                + ",\"holes\":[" + kreis(5000, 100) + "]}]}]").getAsJsonArray();
        Ebenen.Gelesen g = Ebenen.formen(objekte);
        assertEquals(List.of(), g.formen());
        assertEquals(1, g.verworfen());
    }

    @Test
    void zehntausendGrosseDreiecke() {
        // Der Fall aus dem Review: Grosse Flächen kosten so viel wie ihre Ecken, nicht wie ihre Fläche.
        String dreieck = "{\"type\":\"region\",\"fill\":\"#FF000080\",\"polygons\":[{\"outer\":[[-1000000,-1000000],[1000000,-1000000],[0,1000000]]}]}";
        Ebenen.Gelesen g = Ebenen.formen(JsonParser.parseString("[" + mal(dreieck, Ebenen.MAX_OBJEKTE) + "]").getAsJsonArray());
        assertEquals(Ebenen.MAX_OBJEKTE, g.formen().size());
        int trapeze = 0;
        for (Ebenen.Form f : g.formen()) {
            trapeze += ((Ebenen.Flaeche) f).trapeze().length / 6;
        }
        // Je Dreieck ein Trapez: oben 2 000 000 breit, unten spitz.
        assertEquals(Ebenen.MAX_OBJEKTE, trapeze);
    }

    @Test
    void ohneFuellungSchwarzerRand() {
        // #00000000 heisst ohne Füllung; der Rand ohne Farbe ist trotzdem die Füllung ohne Alpha, also Schwarz.
        JsonArray objekte = JsonParser.parseString("["
                + "{\"type\":\"region\",\"fill\":\"#00000000\",\"polygons\":[{\"outer\":[[0,0],[10,0],[10,10]]}]},"
                + "{\"type\":\"circle\",\"fill\":\"#00000000\",\"center\":[0,0],\"radius\":5}"
                + "]").getAsJsonArray();
        Ebenen.Gelesen g = Ebenen.formen(objekte);
        Ebenen.Flaeche f = (Ebenen.Flaeche) g.formen().get(0);
        assertNull(f.trapeze());
        assertEquals(0xFF000000, f.rand().farbe());
        assertFalse(Ebenen.sichtbar(((Ebenen.Kreis) g.formen().get(1)).fuellung()));
        assertEquals(0xFF000000, ((Ebenen.Kreis) g.formen().get(1)).rand().farbe());
        assertEquals(0, g.verworfen());
    }

    @Test
    void randGekappt() {
        JsonArray objekte = JsonParser.parseString("[{\"type\":\"line\",\"points\":[[0,0],[1,1]],"
                + "\"stroke\":{\"width\":100,\"style\":\"dashed\",\"dash\":[5000,4000]}}]").getAsJsonArray();
        assertEquals(new Ebenen.Rand(Ebenen.RANDFARBE, Ebenen.MAX_BREITE, Ebenen.MAX_STRICH, Ebenen.MAX_STRICH),
                ((Ebenen.Linie) Ebenen.formen(objekte).formen().getFirst()).rand());
    }

    /** Dasselbe Objekt so oft, durch Kommas getrennt. */
    private static String mal(String objekt, int anzahl) {
        return String.join(",", java.util.Collections.nCopies(anzahl, objekt));
    }

    private static String ebene(int teil, int teile, String objekte) {
        return "{\"v\":1,\"typ\":\"ebene\",\"jetzt\":1,\"id\":\"b:grenzen\",\"version\":\"v1\",\"teil\":" + teil + ",\"teile\":" + teile
                + ",\"objects\":[" + objekte + "]}";
    }

    private static String linie(int punkte) {
        StringBuilder p = new StringBuilder("{\"type\":\"line\",\"points\":[");
        for (int i = 0; i < punkte; i++) {
            p.append(i == 0 ? "" : ",").append("[").append(i).append(",0]");
        }
        return p.append("]}").toString();
    }

    private static String kreis(int punkte, double r) {
        StringBuilder p = new StringBuilder("[");
        for (int i = 0; i < punkte; i++) {
            double w = 2 * Math.PI * i / punkte;
            p.append(i == 0 ? "" : ",").append("[").append(r * Math.cos(w)).append(",").append(r * Math.sin(w)).append("]");
        }
        return p.append("]").toString();
    }

    @Test
    void farbeMitAlpha() {
        assertEquals(0x5540E53F, Ebenen.farbeMitAlpha("#40E53F55", 0));
        assertEquals(0xFF40E53F, Ebenen.farbeMitAlpha("#40e53f", 0));
        assertEquals(7, Ebenen.farbeMitAlpha("rot", 7));
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
        assertEquals(Ebenen.FARBE, Ebenen.farbe("40E53F"));
        // Schwarz ist deckend schwarz, nicht durchsichtig.
        assertEquals(0xFF000000, Ebenen.farbe("#000000"));
        assertEquals(0xFF000000, Ebenen.farbe("#00000000"));
    }
}
