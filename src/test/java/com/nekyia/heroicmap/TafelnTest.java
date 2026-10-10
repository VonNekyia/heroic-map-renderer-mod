package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Die Tafeln vom Plugin: fragen, behalten, zeigen und treffen. Siehe docs/ebenen.md, „Infotafel“. */
class TafelnTest {

    private static final Tafel TAFEL = new Tafel(List.of(new Tafel.Titel("Hafenstadt", Tafel.SCHRIFT)));

    @Test
    void jeObjektUndVersionEinmal() {
        List<Tafeln.Ziel> gefragt = new ArrayList<>();
        Tafeln t = new Tafeln(gefragt::add);
        Tafeln.Ziel stadt = new Tafeln.Ziel("b:staedte", "v1", "stadt-17");
        assertNull(t.tafel(stadt, 0));
        assertNull(t.tafel(stadt, 10));
        assertEquals(List.of(stadt), gefragt);
        t.antwort(new Tafeln.Antwort(stadt, TAFEL));
        assertEquals(Optional.of(TAFEL), t.tafel(stadt, 20));
        // Ohne panel: gemerkt als keine Tafel, nicht wieder gefragt.
        Tafeln.Ziel ohne = new Tafeln.Ziel("b:staedte", "v1", "dorf");
        t.tafel(ohne, 0);
        t.antwort(new Tafeln.Antwort(ohne, null));
        assertEquals(Optional.empty(), t.tafel(ohne, 20));
        assertEquals(2, gefragt.size());
        // Eine Antwort, um die der Mod nicht bat, gilt nicht.
        Tafeln.Ziel fremd = new Tafeln.Ziel("b:staedte", "v1", "fremd");
        t.antwort(new Tafeln.Antwort(fremd, TAFEL));
        assertNull(t.tafel(fremd, 0));
    }

    @Test
    void neueVersionLeertUndHoechstens256() {
        Tafeln t = new Tafeln(z -> true);
        for (int i = 0; i < Tafeln.MAX + 10; i++) {
            Tafeln.Ziel z = new Tafeln.Ziel("b:staedte", "v1", "s" + i);
            t.tafel(z, 0);
            t.antwort(new Tafeln.Antwort(z, TAFEL));
        }
        assertEquals(Tafeln.MAX, t.behalten());
        // Eine neue version der Ebene leert ihre Tafeln; eine späte Antwort der alten gilt nicht mehr.
        Tafeln.Ziel alt = new Tafeln.Ziel("b:staedte", "v1", "spaet");
        t.tafel(alt, 0);
        t.tafel(new Tafeln.Ziel("b:staedte", "v2", "s1"), 0);
        assertEquals(0, t.behalten());
        t.antwort(new Tafeln.Antwort(alt, TAFEL));
        assertEquals(0, t.behalten());
    }

    @Test
    void zeigenNachRuheUndNachlauf() {
        Tafeln.Zeigen z = new Tafeln.Zeigen();
        Tafeln.Ziel a = new Tafeln.Ziel("b:e", "v", "a"), b = new Tafeln.Ziel("b:e", "v", "b");
        z.zeiger(a, false, 0);
        z.zeiger(a, false, Tafeln.Zeigen.RUHE_MS - 1);
        assertNull(z.offen());
        z.zeiger(a, false, Tafeln.Zeigen.RUHE_MS);
        assertEquals(a, z.offen());
        // In die Tafel wandern hält sie; daneben schliesst sie erst nach dem Nachlauf.
        z.zeiger(null, true, 1000);
        z.zeiger(null, false, 2000);
        z.zeiger(null, false, 2000 + Tafeln.Zeigen.NACHLAUF_MS - 1);
        assertEquals(a, z.offen());
        z.zeiger(null, false, 2000 + Tafeln.Zeigen.NACHLAUF_MS);
        assertNull(z.offen());
        // Wandert der Zeiger zu b, öffnet nach der Ruhe b; gehalten wird nichts (mod#75).
        z.zeiger(b, false, 5000);
        z.zeiger(b, false, 5000 + Tafeln.Zeigen.RUHE_MS);
        assertEquals(b, z.offen());
    }

    @Test
    void zweiteFrageNachFuenfSekundenDannOhneTafel() {
        List<Tafeln.Ziel> gefragt = new ArrayList<>();
        Tafeln t = new Tafeln(gefragt::add);
        Tafeln.Ziel z = new Tafeln.Ziel("b:e", "v", "a");
        assertNull(t.tafel(z, 0));
        assertNull(t.tafel(z, Tafeln.WARTEN_MS - 1));
        assertEquals(1, gefragt.size());
        // Nach 5 s ohne Antwort einmal neu, dann noch einmal 5 s „lädt …“.
        assertNull(t.tafel(z, Tafeln.WARTEN_MS));
        assertEquals(2, gefragt.size());
        assertNull(t.tafel(z, 2 * Tafeln.WARTEN_MS - 1));
        // Bleibt auch die zweite ohne Antwort, gilt das Ziel als ohne Tafel; eine späte Antwort ändert das nicht.
        assertEquals(Optional.empty(), t.tafel(z, 2 * Tafeln.WARTEN_MS));
        t.antwort(new Tafeln.Antwort(z, TAFEL));
        assertEquals(Optional.empty(), t.tafel(z, 3 * Tafeln.WARTEN_MS));
        assertEquals(2, gefragt.size());
    }

    @Test
    void ohneKanalOhneTafelUngemerkt() {
        // Geht die Frage nicht hinaus, ist das Ziel ohne Tafel, ungemerkt; eine Antwort darauf gilt nicht.
        boolean[] kanal = {false};
        Tafeln t = new Tafeln(z -> kanal[0]);
        Tafeln.Ziel z = new Tafeln.Ziel("b:e", "v", "a");
        assertEquals(Optional.empty(), t.tafel(z, 0));
        t.antwort(new Tafeln.Antwort(z, TAFEL));
        assertEquals(0, t.behalten());
        // Hört der Server später, fragt der Mod dann, und die Antwort gilt.
        kanal[0] = true;
        assertNull(t.tafel(z, 1));
        t.antwort(new Tafeln.Antwort(z, TAFEL));
        assertEquals(Optional.of(TAFEL), t.tafel(z, 2));
    }

    @Test
    void vonSelbstZuSperrtDasGeschlosseneZiel() {
        // A zeigt „lädt …“, der Zeiger geht auf B; dann kommt für A „ohne Tafel“. B öffnet trotzdem nach seiner Ruhe.
        Tafeln.Zeigen z = new Tafeln.Zeigen();
        Tafeln.Ziel a = new Tafeln.Ziel("b:e", "v", "a"), b = new Tafeln.Ziel("b:e", "v", "b");
        z.zeiger(a, false, 0);
        z.zeiger(a, false, Tafeln.Zeigen.RUHE_MS);
        assertEquals(a, z.offen());
        z.zeiger(b, false, 200);
        z.antwort(Optional.empty());
        assertNull(z.offen());
        z.zeiger(b, false, 200 + Tafeln.Zeigen.RUHE_MS);
        assertEquals(b, z.offen());
        // Gesperrt ist A: Mit dem Zeiger wieder auf A und Ruhe öffnet es, denn der Zeiger berührte dazwischen B.
        z.zeiger(a, false, 1000);
        z.zeiger(a, false, 1000 + Tafeln.Zeigen.RUHE_MS);
        assertEquals(a, z.offen());
        // Bleibt der Zeiger auf dem Ziel ohne Tafel, öffnet es nicht im Kreis.
        z.antwort(Optional.empty());
        z.zeiger(a, false, 2000);
        assertNull(z.offen());
    }

    @Test
    void keineTafelSchliesst() {
        // Solange die Antwort aussteht, bleibt sie offen; ohne Tafel geht sie zu, und ein anderes Ziel öffnet wie sonst.
        Tafeln.Zeigen z = new Tafeln.Zeigen();
        Tafeln.Ziel a = new Tafeln.Ziel("b:e", "v", "a"), b = new Tafeln.Ziel("b:e", "v", "b");
        z.zeiger(a, false, 0);
        z.zeiger(a, false, Tafeln.Zeigen.RUHE_MS);
        z.antwort(null);
        assertEquals(a, z.offen());
        z.antwort(Optional.empty());
        assertNull(z.offen());
        z.zeiger(b, false, 1000);
        z.zeiger(b, false, 1000 + Tafeln.Zeigen.RUHE_MS);
        assertEquals(b, z.offen());
    }

    @Test
    void panelUnlesbarOderLeerHeisstOhneTafel() {
        String kopf = "{\"v\":1,\"typ\":\"tafel\",\"ebene\":\"b:e\",\"version\":\"v\",\"id\":\"a\",\"panel\":";
        // Ohne blocks, blocks kein Feld, nur unbekannte Bausteine: eine Antwort, ohne Tafel.
        for (String panel : new String[] {"{}", "{\"blocks\":5}", "{\"blocks\":[{\"type\":\"unbekannt\"}]}", "7"}) {
            Tafeln.Antwort a = Tafeln.Antwort.lies(kopf + panel + "}");
            assertEquals(new Tafeln.Ziel("b:e", "v", "a"), a.ziel(), panel);
            assertNull(a.tafel(), panel);
        }
    }

    @Test
    void treffenFlaecheUndKreis() {
        Ebenen.Gelesen g = Ebenen.formen(JsonParser.parseString("["
                + "{\"id\":\"r\",\"type\":\"region\",\"polygons\":[{\"outer\":[[0,0],[10,0],[10,10],[0,10]],\"holes\":[[[4,4],[6,4],[6,6],[4,6]]]}]},"
                + "{\"id\":\"k\",\"type\":\"circle\",\"center\":[50,50],\"radius\":5},"
                + "{\"id\":\"l\",\"type\":\"line\",\"points\":[[0,0],[100,100]]}"
                + "]").getAsJsonArray());
        Ebenen.Form r = g.formen().get(0), k = g.formen().get(1), l = g.formen().get(2);
        assertTrue(Tafeln.trifft(r, 1, 1));
        // Im Loch nicht, nach gerade/ungerade.
        assertFalse(Tafeln.trifft(r, 5, 5));
        assertFalse(Tafeln.trifft(r, 11, 5));
        assertTrue(Tafeln.trifft(k, 53, 50));
        assertFalse(Tafeln.trifft(k, 56, 50));
        // Eine Linie hat keine Tafel.
        assertFalse(Tafeln.trifft(l, 50, 50));
        assertEquals("r", ((Ebenen.Flaeche) r).id());
    }

    @Test
    void frageUndAntwortImKanal() {
        JsonObject frage = JsonParser.parseString(Kanal.tafel(new Tafeln.Ziel("beispiel:staedte", "5f3a", "stadt-17"))).getAsJsonObject();
        assertEquals("tafel", frage.get("typ").getAsString());
        assertEquals("beispiel:staedte", frage.get("ebene").getAsString());
        assertEquals("5f3a", frage.get("version").getAsString());
        assertEquals("stadt-17", frage.get("id").getAsString());
        Tafeln.Antwort mit = Tafeln.Antwort.lies("{\"v\":1,\"typ\":\"tafel\",\"jetzt\":1,\"ebene\":\"beispiel:staedte\",\"version\":\"5f3a\","
                + "\"id\":\"stadt-17\",\"panel\":{\"blocks\":[{\"type\":\"title\",\"text\":\"Hafenstadt\"}]}}");
        assertEquals(new Tafeln.Ziel("beispiel:staedte", "5f3a", "stadt-17"), mit.ziel());
        assertEquals(TAFEL, mit.tafel());
        assertNull(Tafeln.Antwort.lies("{\"v\":1,\"typ\":\"tafel\",\"ebene\":\"beispiel:staedte\",\"version\":\"5f3a\",\"id\":\"stadt-17\"}").tafel());
        assertNull(Tafeln.Antwort.lies("{\"v\":1,\"typ\":\"ebene\",\"id\":\"x\"}"));
        assertNull(Tafeln.Antwort.lies("{\"v\":1,\"typ\":\"tafel\",\"ebene\":\"beispiel:staedte\",\"version\":\"5f3a\"}"));
    }
}
