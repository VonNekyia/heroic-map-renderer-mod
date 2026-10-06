package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Die anfrage an das Plugin. Siehe docs/download.md, „Kanal“. */
class KanalTest {

    @Test
    void anfrageOhneNeu() {
        JsonObject json = JsonParser.parseString(Kanal.anfrage("welt", 4, "voll", false)).getAsJsonObject();
        assertEquals(1, json.get("v").getAsInt());
        assertEquals("anfrage", json.get("typ").getAsString());
        assertEquals("welt", json.get("baum").getAsString());
        assertEquals(4, json.get("massstab").getAsInt());
        assertEquals("voll", json.get("art").getAsString());
        // Ohne Feld gilt das Fortsetzen; ein neu=false schickt der Mod nie.
        assertFalse(json.has("neu"));
    }

    @Test
    void anfrageMitNeu() {
        JsonObject json = JsonParser.parseString(Kanal.anfrage("welt", 2, "voll", true)).getAsJsonObject();
        assertTrue(json.get("neu").getAsBoolean());
    }
}
