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

    private void legeAn(String baum, String name, String dimension, int massstab) throws Exception {
        Satz.schreibe(server.resolve(baum), name, dimension, massstab);
        Path ordner = Files.createDirectories(server.resolve(baum).resolve(String.valueOf(massstab)));
        Files.writeString(ordner.resolve("map.json"), "{\"tileSize\":256,\"scale\":4,\"minZoom\":0,\"maxZoom\":9}");
    }
}
