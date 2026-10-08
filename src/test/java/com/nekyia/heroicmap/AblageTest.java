package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ordner je Welt und Umzug aus der alten Ablage, siehe docs/download.md, „Ablage“;
 * die Kartenliste, siehe docs/download.md, „Kartenliste“.
 */
class AblageTest {

    private static final long SEED = 0x0123456789abcdefL;

    @Test
    void ordnerJeWahl() {
        assertEquals("welt-0123456789abcdef", Downloads.weltOrdner(Downloads.Ablage.HASH, "Mc.Example.com:25566", SEED));
        assertEquals("mc.example.com/welt-0123456789abcdef", Downloads.weltOrdner(Downloads.Ablage.IP, "Mc.Example.com:25566", SEED));
        assertEquals("mc.example.com_25566/welt-0123456789abcdef",
                Downloads.weltOrdner(Downloads.Ablage.IP_PORT, "Mc.Example.com:25566", SEED));
        // Ohne Port gilt der des Spiels; derselbe Server mit und ohne Port ist ein Ordner.
        assertEquals(Downloads.weltOrdner(Downloads.Ablage.IP_PORT, "mc.example.com:25565", SEED),
                Downloads.weltOrdner(Downloads.Ablage.IP_PORT, "mc.example.com", SEED));
        // Ein negativer Hash bleibt 16 Stellen hex.
        assertEquals("welt-ffffffffffffffff", Downloads.weltOrdner(Downloads.Ablage.HASH, "x", -1));
    }

    @Test
    void umzugNurInsLeere(@TempDir Path wurzel) throws Exception {
        Path alt = wurzel.resolve("mc.example.com").resolve("survival");
        Path neu = wurzel.resolve("mc.example.com").resolve("welt-0123456789abcdef").resolve("survival");
        Files.createDirectories(alt);
        Files.writeString(alt.resolve("massstab.txt"), "4 100 10");
        assertTrue(Laden.zieheUm(alt, neu));
        assertTrue(Files.exists(neu.resolve("massstab.txt")));
        assertFalse(Files.exists(alt));
        // Ein zweites Mal gibt es nichts mehr umzuziehen, und ein besetztes Ziel bleibt, wie es ist.
        assertFalse(Laden.zieheUm(alt, neu));
        Files.createDirectories(alt);
        Files.writeString(alt.resolve("massstab.txt"), "1 1 1");
        assertFalse(Laden.zieheUm(alt, neu));
        assertEquals("4 100 10", Files.readString(neu.resolve("massstab.txt")));
    }

    @Test
    void bestandUeberAlleAblagen(@TempDir Path wurzel) throws Exception {
        // Neu mit Host, nur Hash, alt ohne Welt, dazu Dateien ausserhalb jedes Baums.
        schreibe(wurzel.resolve("host/welt-01/survival/massstab.txt"), 10);
        schreibe(wurzel.resolve("host/welt-01/survival/4/0/0/0.webp"), 1000);
        schreibe(wurzel.resolve("welt-02/nether/satz.json"), 20);
        schreibe(wurzel.resolve("alt.example/survival/massstab.txt"), 30);
        schreibe(wurzel.resolve("alt.example/survival/2/tmp/teil"), 300);
        schreibe(wurzel.resolve("host/welt-01/wegpunkte.json"), 5);
        schreibe(wurzel.resolve("zustimmung.txt"), 7);

        Laden.Bestand b = Laden.bestand(wurzel);
        assertEquals(1000 + 10 + 20 + 30 + 300 + 5 + 7, b.bytes());
        List<String> pfade = b.karten().stream().map(Laden.AufPlatte::pfad).toList();
        assertEquals(List.of("alt.example/survival", "host/welt-01/survival", "welt-02/nether"), pfade);
        assertEquals(List.of(330L, 1010L, 20L), b.karten().stream().map(Laden.AufPlatte::bytes).toList());
        // Ohne map.json ist kein Satz vollständig.
        assertNull(b.karten().getFirst().satz());
    }

    @Test
    void ohneOrdnerNichts(@TempDir Path wurzel) throws Exception {
        assertEquals(new Laden.Bestand(List.of(), 0), Laden.bestand(wurzel.resolve("fehlt")));
        assertEquals("1.50 GB", Downloads.gb(1_500_000_000L));
    }

    private static void schreibe(Path datei, int bytes) throws Exception {
        Files.createDirectories(datei.getParent());
        Files.write(datei, new byte[bytes]);
    }
}
