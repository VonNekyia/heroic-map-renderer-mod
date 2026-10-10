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
        assertNull(t.tafel(stadt));
        assertNull(t.tafel(stadt));
        assertEquals(List.of(stadt), gefragt);
        t.antwort(new Tafeln.Antwort(stadt, TAFEL));
        assertEquals(Optional.of(TAFEL), t.tafel(stadt));
        // Ohne panel: gemerkt als keine Tafel, nicht wieder gefragt.
        Tafeln.Ziel ohne = new Tafeln.Ziel("b:staedte", "v1", "dorf");
        t.tafel(ohne);
        t.antwort(new Tafeln.Antwort(ohne, null));
        assertEquals(Optional.empty(), t.tafel(ohne));
        assertEquals(2, gefragt.size());
        // Eine Antwort, um die der Mod nicht bat, gilt nicht.
        Tafeln.Ziel fremd = new Tafeln.Ziel("b:staedte", "v1", "fremd");
        t.antwort(new Tafeln.Antwort(fremd, TAFEL));
        assertNull(t.tafel(fremd));
    }

    @Test
    void neueVersionLeertUndHoechstens256() {
        Tafeln t = new Tafeln(z -> {
        });
        for (int i = 0; i < Tafeln.MAX + 10; i++) {
            Tafeln.Ziel z = new Tafeln.Ziel("b:staedte", "v1", "s" + i);
            t.tafel(z);
            t.antwort(new Tafeln.Antwort(z, TAFEL));
        }
        assertEquals(Tafeln.MAX, t.behalten());
        // Eine neue version der Ebene leert ihre Tafeln; eine späte Antwort der alten gilt nicht mehr.
        Tafeln.Ziel alt = new Tafeln.Ziel("b:staedte", "v1", "spaet");
        t.tafel(alt);
        t.tafel(new Tafeln.Ziel("b:staedte", "v2", "s1"));
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
        // Ein Klick hält sie, auch wenn der Zeiger zu b wandert; Escape oder ein Klick daneben schliesst zuerst nur sie.
        z.halte(a);
        z.zeiger(b, false, 5000);
        z.zeiger(b, false, 9000);
        assertEquals(a, z.offen());
        assertTrue(z.schliesse());
        assertFalse(z.schliesse());
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
