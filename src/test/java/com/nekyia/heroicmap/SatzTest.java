package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Den Satz auf der Platte finden. Siehe docs/vollbildkarte.md, „Welcher Satz“. */
class SatzTest {

    @TempDir
    Path server;

    @Test
    void satzZurDimension() throws Exception {
        legeAn("oberwelt", "Die Welt", "minecraft:overworld", 2);
        legeAn("nether", "Der Nether", "minecraft:the_nether", 4);

        Satz satz = Satz.fuer(server, "minecraft:overworld");
        assertEquals("Die Welt", satz.name());
        assertEquals(2, satz.massstab());
        assertEquals(server.resolve("oberwelt").resolve("2"), satz.ordner());
        assertEquals(256, satz.kachel());
        // maxZoom 9 bei 2 px: die feinste Stufe ist 8.
        assertEquals(8, satz.stufe());
        assertEquals(4, satz.scale());
        assertEquals("Der Nether", Satz.fuer(server, "minecraft:the_nether").name());
        assertNull(Satz.fuer(server, "minecraft:the_end"));
        assertNull(Satz.fuer(server.resolve("gibt-es-nicht"), "minecraft:overworld"));
    }

    @Test
    void ohneMapJsonKeinSatz() throws Exception {
        Satz.schreibe(server.resolve("halb"), "Halb", "minecraft:overworld", 4);
        assertNull(Satz.lies(server.resolve("halb")));
    }

    @Test
    void kaputterBaumVerdecktKeinenAnderen() throws Exception {
        legeAn("kaputt", "Kaputt", "minecraft:overworld", 4);
        Files.writeString(server.resolve("kaputt").resolve("satz.json"), "{\"name\":\"ohne Dimension\",\"massstab\":4}");
        legeAn("oberwelt", "Die Welt", "minecraft:overworld", 4);
        assertNull(Satz.lies(server.resolve("kaputt")));
        assertEquals("Die Welt", Satz.fuer(server, "minecraft:overworld").name());
    }

    @Test
    void mapJsonDesServersHatGrenzen() throws Exception {
        legeAn("welt", "Die Welt", "minecraft:overworld", 1);
        Path karte = server.resolve("welt").resolve("1").resolve("map.json");
        // 1 px heisst Stufe maxZoom − 2; die muss es geben.
        for (String gut : new String[] {"64,2,0,2", "1024,1,0,30", "256,4,3,5"}) {
            Files.writeString(karte, mapJson(gut));
            assertEquals(gut, kurz(Satz.lies(server.resolve("welt"))), gut);
        }
        for (String falsch : new String[] {"0,4,0,9", "1,4,0,9", "32,4,0,9", "300,4,0,9", "2048,4,0,9", "256,0,0,9",
            "256,4,-1,9", "256,4,0,31", "256,4,5,4", "256,4,4,5"}) {
            Files.writeString(karte, mapJson(falsch));
            assertNull(Satz.lies(server.resolve("welt")), falsch);
        }
        Files.writeString(server.resolve("welt").resolve("satz.json"),
                "{\"name\":\"x\",\"dimension\":\"minecraft:overworld\",\"massstab\":3}");
        Files.createDirectories(server.resolve("welt").resolve("3"));
        Files.writeString(server.resolve("welt").resolve("3").resolve("map.json"), mapJson("256,4,0,9"));
        assertNull(Satz.lies(server.resolve("welt")));
    }

    /** {@code tileSize,scale,minZoom,maxZoom} als map.json. */
    private static String mapJson(String werte) {
        String[] w = werte.split(",");
        return "{\"tileSize\":" + w[0] + ",\"scale\":" + w[1] + ",\"minZoom\":" + w[2] + ",\"maxZoom\":" + w[3] + "}";
    }

    private static String kurz(Satz s) {
        return s == null ? null : s.kachel() + "," + s.scale() + "," + s.minZoom() + "," + s.maxZoom();
    }

    private void legeAn(String baum, String name, String dimension, int massstab) throws Exception {
        Satz.schreibe(server.resolve(baum), name, dimension, massstab);
        Path ordner = Files.createDirectories(server.resolve(baum).resolve(String.valueOf(massstab)));
        Files.writeString(ordner.resolve("map.json"), "{\"tileSize\":256,\"scale\":4,\"minZoom\":0,\"maxZoom\":9}");
    }
}
