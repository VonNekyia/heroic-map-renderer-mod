package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

/** „Im Browser öffnen“: Wurzel und Link aus der Basis der Kacheln, das Token, die Gründe. Siehe docs/vollbildkarte.md, „Im Browser öffnen“. */
class WebkarteTest {

    private static final String TOKEN = "eyJ2IjoyfQ.abc_DEF-123";

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void wurzelAusDerBasisDerKacheln() {
        assertEquals(URI.create("http://127.0.0.1:8082/"), Webkarte.wurzel(URI.create("http://127.0.0.1:8082/tiles")));
        assertEquals(URI.create("https://karte.example/welt/"), Webkarte.wurzel(URI.create("https://karte.example/welt/tiles")));
        assertEquals(URI.create("https://karte.example/welt/"), Webkarte.wurzel(URI.create("https://karte.example/welt/tiles/")));
        assertEquals(URI.create("http://[2001:db8::1]:8082/"), Webkarte.wurzel(URI.create("http://[2001:db8::1]:8082/tiles")));
        // Ohne Basis oder ohne /tiles am Ende kein Link; ein Pfad, der nur auf tiles endet, zählt nicht.
        assertNull(Webkarte.wurzel(null));
        assertNull(Webkarte.wurzel(URI.create("http://127.0.0.1:8082/")));
        assertNull(Webkarte.wurzel(URI.create("http://127.0.0.1:8082/meinetiles")));
    }

    @Test
    void linkMitDemTokenNurImFragment() {
        URI link = Webkarte.link(URI.create("https://karte.example/welt/"), TOKEN);
        assertEquals("https://karte.example/welt/#anmelden=" + TOKEN, link.toString());
        assertEquals("/welt/", link.getPath());
        assertNull(link.getQuery());
    }

    @Test
    void tokenNurInFassung2() {
        assertTrue(Webkarte.token(TOKEN));
        assertTrue(Webkarte.token("a".repeat(127) + "." + "b".repeat(128)));
        assertFalse(Webkarte.token("a".repeat(128) + "." + "b".repeat(128)));
        for (String falsch : new String[] {null, "", "ohnepunkt", ".b", "a.", "a.b.c", "a#b.c", "a/b.c", "a?b.c", "a b.c", "a.b&x=1", "ä.b"}) {
            assertFalse(Webkarte.token(falsch), falsch);
        }
    }

    @Test
    void feldWebDerAntwortAufShow() {
        assertEquals(Boolean.TRUE, Webkarte.web(json("{\"v\":1,\"typ\":\"show\",\"erlaubt\":true,\"web\":true}")));
        assertEquals(Boolean.FALSE, Webkarte.web(json("{\"v\":1,\"typ\":\"show\",\"web\":false}")));
        // Ein altes Plugin kennt das Feld nicht; ein Text oder eine Zahl gilt auch nicht.
        assertNull(Webkarte.web(json("{\"v\":1,\"typ\":\"show\",\"erlaubt\":true}")));
        assertNull(Webkarte.web(json("{\"web\":\"true\"}")));
        assertNull(Webkarte.web(json("{\"web\":1}")));
    }

    @Test
    void grundInDieserReihe() {
        assertEquals("heroicmap.web.kein_plugin", Webkarte.grund(false, true, true, false));
        assertEquals("heroicmap.web.alt", Webkarte.grund(true, null, true, false));
        assertEquals("heroicmap.web.nicht_frei", Webkarte.grund(true, false, true, false));
        assertEquals("heroicmap.web.ohne_basis", Webkarte.grund(true, true, false, false));
        assertEquals("heroicmap.web.laeuft", Webkarte.grund(true, true, true, true));
        assertNull(Webkarte.grund(true, true, true, false));
    }

    private static void absage(String json, String schluessel, Object... argumente) {
        Component c = Webkarte.absage(json(json));
        TranslatableContents t = (TranslatableContents) c.getContents();
        assertEquals(schluessel, t.getKey(), json);
        assertArrayEquals(argumente, t.getArgs(), json);
    }

    @Test
    void absageMitGrund() {
        absage("{\"v\":1,\"typ\":\"web\",\"erlaubt\":false,\"grund\":\"permission\"}", "heroicmap.web.grund.permission");
        absage("{\"v\":1,\"typ\":\"web\",\"erlaubt\":false,\"grund\":\"webserver\"}", "heroicmap.web.grund.webserver");
        // wieder sind die Sekunden bis zum nächsten Link, mindestens 1; anders als bei abgelehnt keine Uhrzeit.
        absage("{\"v\":1,\"typ\":\"web\",\"jetzt\":1760000000,\"erlaubt\":false,\"grund\":\"rate\",\"wieder\":12}", "heroicmap.web.grund.rate", 12L);
        absage("{\"v\":1,\"typ\":\"web\",\"erlaubt\":false,\"grund\":\"rate\",\"wieder\":0}", "heroicmap.web.grund.rate", 1L);
        absage("{\"v\":1,\"typ\":\"web\",\"erlaubt\":false,\"grund\":\"rate\"}", "heroicmap.web.grund.rate_ohne");
        absage("{\"v\":1,\"typ\":\"web\",\"erlaubt\":false,\"grund\":\"sonst\"}", "heroicmap.web.grund.sonst");
        absage("{\"v\":1,\"typ\":\"web\",\"erlaubt\":false}", "heroicmap.web.grund.sonst");
        absage("{\"v\":1,\"typ\":\"web\",\"erlaubt\":false,\"grund\":\"<b>\"}", "heroicmap.web.grund.sonst");
    }
}
