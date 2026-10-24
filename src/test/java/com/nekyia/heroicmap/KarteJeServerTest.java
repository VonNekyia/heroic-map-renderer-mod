package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Was ein Server zeigt, gilt je Server; die eigene Karte gehört einem Server. Siehe docs/selbst.md, „Wahl“. */
class KarteJeServerTest {

    private static final String OBEN = "minecraft:overworld";

    @TempDir
    Path welt;

    private void download(String baum) throws Exception {
        Satz.schreibe(welt.resolve(baum), "Top North", OBEN, 4);
        Path ordner = Files.createDirectories(welt.resolve(baum).resolve("4"));
        Files.writeString(ordner.resolve("map.json"), "{\"tileSize\":256,\"scale\":4,\"minZoom\":0,\"maxZoom\":8}");
    }

    private String gezeigt(String server) {
        Satz s = Satz.fuer(welt, OBEN, server);
        return s == null ? null : s.ordner().getParent().getFileName().toString();
    }

    @Test
    void ohneWahlErstDerDownloadMitNamen() throws Exception {
        download("top-north-s");
        Path alt = Selbst.anlegen(welt, OBEN, 4);
        Path build = Selbst.anlegen(welt, OBEN, 2, "build");
        assertNotEquals(alt, build, "build hat einen eigenen Baum");
        assertEquals("build", Satz.besitzer(build));
        assertEquals(Zeigt.OHNE, Satz.besitzer(alt), "ein alter Baum gehört einem Server ohne Namen");

        assertEquals("top-north-s", gezeigt("main"), "die Karte von main ist nach dem Update wieder da");
        assertEquals("top-north-s", gezeigt("build"), "ohne Wahl erst der Download");
        assertEquals(alt.getFileName().toString(), gezeigt(Zeigt.OHNE), "ohne Namen wie bisher die eigene vor dem Download");
    }

    @Test
    void dieWahlGiltJeServer() throws Exception {
        download("top-north-s");
        Path build = Selbst.anlegen(welt, OBEN, 4, "build");
        Zeigt.setze(welt, "build", OBEN, build.getFileName().toString());
        assertEquals(build.getFileName().toString(), gezeigt("build"));
        assertEquals("top-north-s", gezeigt("main"), "auf main bleibt der Download an");
        assertTrue(Downloads.ruht(welt.resolve("top-north-s"), "build"));
        assertFalse(Downloads.ruht(welt.resolve("top-north-s"), "main"));
        assertFalse(Downloads.ruht(welt.resolve("halb"), "build"), "was nicht ganz daliegt, ruht nie");

        Zeigt.setze(welt, "build", OBEN, "top-north-s");
        assertEquals("top-north-s", gezeigt("build"), "eine neue Wahl ersetzt die alte");
        assertEquals(List.of("build"), Zeigt.server(welt, "top-north-s"));
    }

    @Test
    void nieDieEigeneKarteEinesAnderen() throws Exception {
        Path alt = Selbst.anlegen(welt, OBEN, 4);
        Path main = Selbst.anlegen(welt, OBEN, 4, "main");
        assertNull(gezeigt("build"), "weder die ohne Namen noch die von main");
        // Auch eine Wahl in zeigt.txt zeigt sie nicht.
        Zeigt.setze(welt, "build", OBEN, alt.getFileName().toString());
        assertNull(gezeigt("build"));
        Zeigt.setze(welt, "build", OBEN, main.getFileName().toString());
        assertNull(gezeigt("build"));
        assertEquals(main.getFileName().toString(), gezeigt("main"));
        assertEquals(alt.getFileName().toString(), gezeigt(Zeigt.OHNE));
        assertTrue(Selbst.an(welt, OBEN, "main"));
        assertFalse(Selbst.an(welt, OBEN, "build"));
    }

    @Test
    void eineWahlAufEinenFehlendenBaumGiltNicht() throws Exception {
        download("top-north-s");
        Zeigt.setze(welt, "main", OBEN, "gibt-es-nicht");
        assertEquals("top-north-s", gezeigt("main"));
    }

    @Test
    void namenDerBaeumeJeServer() {
        assertEquals(Selbst.baum(OBEN), Selbst.baum(OBEN, Zeigt.OHNE), "ohne Namen der Name von früher");
        assertNotEquals(Selbst.baum(OBEN, "main"), Selbst.baum(OBEN, "build"));
        assertTrue(Selbst.baum(OBEN, "x".repeat(64)).length() <= 64);
        assertFalse(Freigabe.baum(Selbst.baum(OBEN, "build")), "nie vom Server geladen");
    }

    @Test
    void zeigtTxtUebergehtKaputteZeilen() throws Exception {
        Files.writeString(welt.resolve(Zeigt.DATEI), "main minecraft:overworld top\nkaputt\nbuild a b c\n");
        assertEquals("top", Zeigt.baum(welt, "main", OBEN));
        assertNull(Zeigt.baum(welt, "build", "a"));
        Zeigt.setze(welt, "build", OBEN, "selbst-x");
        assertEquals("top", Zeigt.baum(welt, "main", OBEN), "andere Server bleiben");
        assertEquals("selbst-x", Zeigt.baum(welt, "build", OBEN));
    }

    @Test
    void backendAusDerAntwortAufShow() {
        assertEquals("build", Downloads.backendAus(JsonParser.parseString("{\"typ\":\"show\",\"backend\":\"build\"}").getAsJsonObject()));
        assertNull(Downloads.backendAus(JsonParser.parseString("{\"typ\":\"show\"}").getAsJsonObject()), "altes Plugin");
        assertNull(Downloads.backendAus(JsonParser.parseString("{\"backend\":\"Build Server\"}").getAsJsonObject()));
        assertNull(Downloads.backendAus(JsonParser.parseString("{\"backend\":\"-\"}").getAsJsonObject()), "nie der Schlüssel ohne Namen");
        assertNull(Downloads.backendAus(JsonParser.parseString("{\"backend\":5}").getAsJsonObject()));
    }
}
