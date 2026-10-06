package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Die freigabe lesen und entscheiden, ob der Mod fragt und woran er misst. Siehe docs/download.md, „Zustimmung und Grösse“. */
class FreigabeTest {

    private static final long SATZ = 1_000_000;
    /** Was ein voller Download mit 4 px abgelegt hat. */
    private static final Freigabe.Stand GESPEICHERT = new Freigabe.Stand(4, SATZ, 1000);

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
    void abgleichMisstAmGespeichertenStand() {
        assertEquals(Freigabe.Weg.STILL, freigabe("abgleich", 4, SATZ / 10).weg(GESPEICHERT, null, true));
        assertEquals(Freigabe.Weg.HOST, freigabe("abgleich", 4, SATZ / 10).weg(GESPEICHERT, null, false));
        // Ein anderer Massstab, keiner gespeichert, oder mehr als 11 % des gespeicherten Satzes: fragen.
        assertEquals(Freigabe.Weg.FRAGEN, freigabe("abgleich", 1, SATZ / 10).weg(GESPEICHERT, null, true));
        assertEquals(Freigabe.Weg.FRAGEN, freigabe("abgleich", 4, SATZ / 10).weg(null, null, true));
        assertEquals(Freigabe.Weg.FRAGEN, freigabe("abgleich", 4, SATZ / 100 * 11 + 1).weg(GESPEICHERT, null, true));
        // Ein Server, der im angebot 5e12 nennt, kommt an weg gar nicht heran; auch eine Bestätigung zählt beim Abgleich nicht.
        Freigabe gross = freigabe("abgleich", 4, 500_000_000_000L);
        assertEquals(Freigabe.Weg.FRAGEN, gross.weg(GESPEICHERT, new Freigabe.Stand(4, 5_000_000_000_000L, 1_000_000_000), true));
        // Gemessen wird am gespeicherten Stand, auch nach dem Dialog.
        assertEquals(GESPEICHERT, gross.mass(GESPEICHERT, null, 1_000_000_000));
    }

    @Test
    void abgleichOhneStandMisstAnDerGezeigtenGroesse() {
        // Ohne gespeicherten Stand hat der Spieler im Dialog 1000 Byte gesehen; der Deckel eines Abgleichs ist 10 % des Satzes.
        Freigabe.Stand mass = freigabe("abgleich", 4, 1000).mass(null, null, 1_000_000_000);
        assertEquals(new Freigabe.Stand(4, 10_000, 1_000_000_000), mass);
        // Die Kacheln aus dem angebot zählen nur bis zu einer Zeile je 4 KiB dieser Grösse.
        Laden.Auftrag a = new Laden.Auftrag(URI.create("http://k.example/b"), "t", "", 1000, 4, true, mass.bytes(),
                mass.kacheln(), null);
        assertEquals(11_000 / Laden.ZEILE_MIN + 16, a.zeilen());
    }

    @Test
    void vollMisstAnDerBestaetigung() {
        Freigabe.Stand bestaetigt = new Freigabe.Stand(2, SATZ, 500);
        assertEquals(Freigabe.Weg.FRAGEN, freigabe("voll", 4, SATZ).weg(GESPEICHERT, null, true));
        assertEquals(Freigabe.Weg.FRAGEN, freigabe("voll", 4, SATZ).weg(null, bestaetigt, true));
        assertEquals(Freigabe.Weg.STILL, freigabe("voll", 2, SATZ).weg(GESPEICHERT, bestaetigt, true));
        assertEquals(Freigabe.Weg.HOST, freigabe("voll", 2, SATZ).weg(null, bestaetigt, false));
        assertEquals(Freigabe.Weg.STILL, freigabe("voll", 2, SATZ + SATZ / 10).weg(null, bestaetigt, true));
        assertEquals(Freigabe.Weg.FRAGEN, freigabe("voll", 2, SATZ + SATZ / 10 + 1).weg(null, bestaetigt, true));
        assertEquals(bestaetigt, freigabe("voll", 2, SATZ).mass(GESPEICHERT, bestaetigt, 9_999_999));
        // Nach dem Dialog misst er an der Grösse, die der Spieler dort sah.
        assertEquals(new Freigabe.Stand(4, 7, 9), freigabe("voll", 4, 7).mass(GESPEICHERT, null, 9));
    }

    @Test
    void ablehnungSperrtNurDenGenanntenAbgleich() {
        String vorlage = "{\"v\":1,\"typ\":\"abgelehnt\",\"grund\":\"x\",\"jetzt\":1000,\"wieder\":4600";
        // Angefragt war ein Abgleich von A; die Ablehnung nennt B: gesperrt wird B, eine Stunde lang.
        Freigabe.Sperre b = Freigabe.sperre(JsonParser.parseString(vorlage
                + ",\"baum\":\"nether\",\"art\":\"abgleich\"}").getAsJsonObject(), 5_000_000);
        assertEquals(new Freigabe.Sperre("nether", 5_000_000 + 3_600_000), b);
        // Ein voller Download sperrt keinen Abgleich, ohne Baum oder Zeit nichts, ein Gerätename auch nicht.
        assertNull(Freigabe.sperre(JsonParser.parseString(vorlage + ",\"baum\":\"welt\",\"art\":\"voll\"}").getAsJsonObject(), 0));
        assertNull(Freigabe.sperre(JsonParser.parseString(vorlage + ",\"art\":\"abgleich\"}").getAsJsonObject(), 0));
        assertNull(Freigabe.sperre(JsonParser.parseString(vorlage + ",\"baum\":\"con\",\"art\":\"abgleich\"}").getAsJsonObject(), 0));
        assertNull(Freigabe.sperre(JsonParser.parseString(
                "{\"v\":1,\"typ\":\"abgelehnt\",\"grund\":\"x\",\"baum\":\"welt\",\"art\":\"abgleich\"}").getAsJsonObject(), 0));
    }

    @Test
    void standAufDerPlatte(@TempDir Path ordner) throws IOException {
        Path datei = ordner.resolve("welt").resolve("massstab.txt");
        assertNull(Freigabe.Stand.lies(datei));
        GESPEICHERT.schreibe(datei);
        assertEquals(GESPEICHERT, Freigabe.Stand.lies(datei));
        for (String kaputt : new String[] {"3 1 1", "4 -1 1", "4 1", "4 1 1 1", "vier 1 1", ""}) {
            Files.writeString(datei, kaputt);
            assertNull(Freigabe.Stand.lies(datei), kaputt);
        }
    }
}
