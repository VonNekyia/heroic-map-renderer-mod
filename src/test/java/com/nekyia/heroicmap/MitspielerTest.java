package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Die Nachricht spieler lesen und verfallen lassen. Siehe docs/minimap.md, „Mitspieler“. */
class MitspielerTest {

    private static final String SAM = "00000000-0000-0000-0000-000000000001";

    private static JsonObject json(String eintraege) {
        return JsonParser.parseString("{\"v\":1,\"typ\":\"spieler\",\"jetzt\":1,\"spieler\":[" + eintraege + "]}").getAsJsonObject();
    }

    private static String eintrag(String uuid, String name, String x) {
        return "{\"uuid\":\"" + uuid + "\",\"name\":\"" + name + "\",\"dimension\":\"minecraft:overworld\",\"x\":" + x
                + ",\"z\":-40.25}";
    }

    @Test
    void lesen() {
        List<Mitspieler.Eintrag> liste = Mitspieler.lies(json(eintrag(SAM, "Sam", "12.5")));
        assertEquals(List.of(new Mitspieler.Eintrag(UUID.fromString(SAM), "Sam", "minecraft:overworld", 12.5, -40.25)), liste);
    }

    @Test
    void kaputteEintraegeFallenWeg() {
        List<Mitspieler.Eintrag> liste = Mitspieler.lies(json(String.join(",",
                eintrag("keine-uuid", "Alex", "1"),
                eintrag(SAM, "mit leerzeichen", "1"),
                eintrag(SAM, "VielZuLangerName17", "1"),
                eintrag(SAM, "Sam", "\"x\""),
                "{\"name\":\"Ohne\"}",
                "42",
                eintrag(SAM, "Sam", "3"))));
        assertEquals(1, liste.size());
        assertEquals(3, liste.getFirst().x());
        // Ohne Liste oder mit etwas anderem als einer Liste: leer, kein Fehler.
        assertTrue(Mitspieler.lies(JsonParser.parseString("{\"v\":1,\"typ\":\"spieler\"}").getAsJsonObject()).isEmpty());
        assertTrue(Mitspieler.lies(JsonParser.parseString("{\"spieler\":7}").getAsJsonObject()).isEmpty());
    }

    @Test
    void hoechstensSovieleEintraege() {
        StringBuilder viele = new StringBuilder();
        for (int i = 0; i < Mitspieler.HOECHSTENS + 10; i++) {
            viele.append(i == 0 ? "" : ",").append(eintrag(SAM, "S" + i, Integer.toString(i)));
        }
        assertEquals(Mitspieler.HOECHSTENS, Mitspieler.lies(json(viele.toString())).size());
    }

    @Test
    void verfaelltNachDerFristUndBeimVerlassen() {
        Mitspieler m = new Mitspieler();
        m.empfange(json(eintrag(SAM, "Sam", "1")), 10_000);
        assertEquals(1, m.aktuell(10_000 + Mitspieler.FRIST).size());
        assertTrue(m.aktuell(10_000 + Mitspieler.FRIST + 1).isEmpty());
        // Eine leere Liste beendet die Sicht gleich.
        m.empfange(json(""), 20_000);
        assertTrue(m.aktuell(20_000).isEmpty());
        m.empfange(json(eintrag(SAM, "Sam", "1")), 30_000);
        m.leeren();
        assertTrue(m.aktuell(30_000).isEmpty());
    }
}
