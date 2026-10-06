package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** Die freigabe lesen und entscheiden, ob der Mod fragt. Siehe docs/download.md, „Zustimmung und Grösse“. */
class FreigabeTest {

    private static final long SATZ = 1_000_000;

    private static JsonObject json(String baum, String art, int massstab, long bytes, String url) {
        return JsonParser.parseString("{\"v\":1,\"typ\":\"freigabe\",\"baum\":\"" + baum + "\",\"art\":\"" + art
                + "\",\"massstab\":" + massstab + ",\"bytes\":" + bytes + ",\"url\":\"" + url
                + "\",\"token\":\"t\",\"manifest_sha256\":\"00\"}").getAsJsonObject();
    }

    private static Freigabe freigabe(String art, int massstab, long bytes) {
        return Freigabe.lies(json("welt", art, massstab, bytes, "https://karte.example/welt"));
    }

    @Test
    void lesen() {
        Freigabe f = freigabe("abgleich", 4, 5);
        assertEquals("welt", f.baum());
        assertTrue(f.abgleich());
        for (JsonObject falsch : new JsonObject[] {
            json("con", "voll", 4, 1, "https://k.example/x"),
            json("lpt1", "voll", 4, 1, "https://k.example/x"),
            json("Welt", "voll", 4, 1, "https://k.example/x"),
            json("welt", "halb", 4, 1, "https://k.example/x"),
            json("welt", "voll", 3, 1, "https://k.example/x"),
            json("welt", "voll", 4, -1, "https://k.example/x"),
            json("welt", "voll", 4, 1, "https://k.example/x?a="),
            json("welt", "voll", 4, 1, "https://k.example/x#a"),
            json("welt", "voll", 4, 1, "https://nutzer@k.example/x"),
            json("welt", "voll", 4, 1, "ftp://k.example/x")}) {
            assertThrows(RuntimeException.class, () -> Freigabe.lies(falsch), falsch.toString());
        }
    }

    @Test
    void reservierteNamen() {
        for (String name : new String[] {"con", "NUL", "aux", "prn", "com1", "lpt9", "nul.example.com"}) {
            assertTrue(Freigabe.reserviert(name), name);
        }
        for (String name : new String[] {"cons", "nullpunkt", "com", "example.con"}) {
            assertFalse(Freigabe.reserviert(name), name);
        }
    }

    @Test
    void abgleichStillNurImGespeichertenMassstabUndKlein() {
        assertEquals(Freigabe.Weg.STILL, freigabe("abgleich", 4, SATZ / 10).weg(4, SATZ, 0, true));
        assertEquals(Freigabe.Weg.HOST, freigabe("abgleich", 4, SATZ / 10).weg(4, SATZ, 0, false));
        // Ein anderer Massstab, keiner gespeichert, oder mehr als 11 % des Satzes: fragen.
        assertEquals(Freigabe.Weg.FRAGEN, freigabe("abgleich", 1, SATZ / 10).weg(4, SATZ, 0, true));
        assertEquals(Freigabe.Weg.FRAGEN, freigabe("abgleich", 4, SATZ / 10).weg(0, SATZ, 0, true));
        assertEquals(Freigabe.Weg.FRAGEN, freigabe("abgleich", 4, 500_000_000_000L).weg(4, SATZ, 0, true));
        assertEquals(Freigabe.Weg.FRAGEN, freigabe("abgleich", 4, SATZ / 100 * 11 + 1).weg(4, SATZ, 0, true));
    }

    @Test
    void vollStillNurNachBestaetigung() {
        assertEquals(Freigabe.Weg.FRAGEN, freigabe("voll", 4, SATZ).weg(4, SATZ, 0, true));
        assertEquals(Freigabe.Weg.STILL, freigabe("voll", 2, SATZ).weg(4, SATZ, SATZ, true));
        assertEquals(Freigabe.Weg.HOST, freigabe("voll", 2, SATZ).weg(4, SATZ, SATZ, false));
        assertEquals(Freigabe.Weg.STILL, freigabe("voll", 2, SATZ + SATZ / 10).weg(4, SATZ, SATZ, true));
        assertEquals(Freigabe.Weg.FRAGEN, freigabe("voll", 2, SATZ + SATZ / 10 + 1).weg(4, SATZ, SATZ, true));
    }
}
