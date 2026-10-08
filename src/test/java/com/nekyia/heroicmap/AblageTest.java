package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
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
    void baumUnterDemHashSeinerDimension(@TempDir Path wurzel) {
        // Paper hat einen Seed je Welt: Der Spieler steht in E, der Baum zeigt D.
        Welten welten = new Welten(wurzel.resolve("welten.properties"));
        String server = Welten.server("mc.example.com");
        welten.merke(server, "minecraft:overworld", 1);
        welten.merke(server, "plugin:farmwelt", 2);
        assertEquals(wurzel.resolve("mc.example.com/welt-0000000000000001/survival"),
                Downloads.ordner(wurzel, Downloads.Ablage.IP, "mc.example.com", welten, "minecraft:overworld", "survival"));
        // Eine Dimension, die der Spieler nie betreten hat: kein Ordner, der Download wartet.
        assertNull(Downloads.ordner(wurzel, Downloads.Ablage.IP, "mc.example.com", welten, "minecraft:the_end", "ende"));
        // Gespeichert, und je Server mit Port getrennt.
        Welten nachher = new Welten(wurzel.resolve("welten.properties"));
        assertEquals(2L, nachher.hash(server, "plugin:farmwelt"));
        assertNull(nachher.hash(Welten.server("mc.example.com:25566"), "plugin:farmwelt"));
    }

    @Test
    void nurHashTeiltDenOrdner() {
        // Mit der Wahl Hash teilen sich zwei Server mit demselben Seed, oder demselben festen Wert, einen Ordner.
        assertEquals(Downloads.weltOrdner(Downloads.Ablage.HASH, "a.example", 0),
                Downloads.weltOrdner(Downloads.Ablage.HASH, "b.example", 0));
        assertFalse(Downloads.weltOrdner(Downloads.Ablage.IP, "a.example", 0)
                .equals(Downloads.weltOrdner(Downloads.Ablage.IP, "b.example", 0)));
    }

    @Test
    void umzugOhneOverlay(@TempDir Path wurzel) throws Exception {
        Path alt = wurzel.resolve("host/baum"), neu = wurzel.resolve("host/welt-01/baum");
        schreibe(alt.resolve("massstab.txt"), 1);
        schreibe(alt.resolve("overlay/0.0.png"), 1);
        assertTrue(Laden.zieheBaumUm(alt, neu));
        assertTrue(Files.exists(neu.resolve("massstab.txt")));
        assertFalse(Files.exists(neu.resolve("overlay")));
    }

    @Test
    void unlesbarerOrdnerFaelltNurSelbstWeg(@TempDir Path wurzel) throws Exception {
        assumeTrue(wurzel.getFileSystem().supportedFileAttributeViews().contains("posix"), "nur mit POSIX-Rechten");
        schreibe(wurzel.resolve("a/eins/massstab.txt"), 1);
        schreibe(wurzel.resolve("a/zwei/massstab.txt"), 1);
        Path zu = wurzel.resolve("a/zu");
        schreibe(zu.resolve("massstab.txt"), 1);
        Files.setPosixFilePermissions(zu, java.util.Set.of());
        try {
            assumeTrue(!Files.isReadable(zu), "als root ist alles lesbar");
            assertEquals(List.of("a/eins", "a/zwei"), Laden.bestand(wurzel, () -> false).karten().stream()
                    .map(Laden.AufPlatte::pfad).toList());
        } finally {
            Files.setPosixFilePermissions(zu, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void junctionBleibtUnberuehrt(@TempDir Path ordner) throws Exception {
        Path draussen = ordner.resolve("draussen"), wurzel = ordner.resolve("heroicmap");
        schreibe(draussen.resolve("wichtig.txt"), 5);
        schreibe(wurzel.resolve("host/baum/massstab.txt"), 1);
        Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", wurzel.resolve("host/baum/link").toString(), draussen.toString())
                .redirectErrorStream(true).start();
        assertEquals(0, p.waitFor());
        // Zählen steigt nicht hinab, Löschen nimmt nur die Junction.
        assertEquals(1, Laden.bestand(wurzel, () -> false).karten().getFirst().bytes());
        Laden.loesche(wurzel.resolve("host/baum"));
        assertFalse(Files.exists(wurzel.resolve("host/baum")));
        assertTrue(Files.exists(draussen.resolve("wichtig.txt")));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void symlinkBleibtUnberuehrt(@TempDir Path ordner) throws Exception {
        Path draussen = ordner.resolve("draussen"), wurzel = ordner.resolve("heroicmap");
        schreibe(draussen.resolve("wichtig.txt"), 5);
        schreibe(wurzel.resolve("host/baum/massstab.txt"), 1);
        Files.createSymbolicLink(wurzel.resolve("host/baum/link"), draussen);
        assertEquals(1, Laden.bestand(wurzel, () -> false).karten().getFirst().bytes());
        Laden.loesche(wurzel.resolve("host/baum"));
        assertFalse(Files.exists(wurzel.resolve("host/baum")));
        assertTrue(Files.exists(draussen.resolve("wichtig.txt")));
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

        Laden.Bestand b = Laden.bestand(wurzel, () -> false);
        assertEquals(1000 + 10 + 20 + 30 + 300 + 5 + 7, b.bytes());
        List<String> pfade = b.karten().stream().map(Laden.AufPlatte::pfad).toList();
        assertEquals(List.of("alt.example/survival", "host/welt-01/survival", "welt-02/nether"), pfade);
        assertEquals(List.of(330L, 1010L, 20L), b.karten().stream().map(Laden.AufPlatte::bytes).toList());
        // Ohne map.json ist kein Satz vollständig.
        assertNull(b.karten().getFirst().satz());
    }

    @Test
    void ohneOrdnerNichts(@TempDir Path wurzel) throws Exception {
        assertEquals(new Laden.Bestand(List.of(), 0), Laden.bestand(wurzel.resolve("fehlt"), () -> false));
        // Abgebrochen gibt es nichts.
        schreibe(wurzel.resolve("a/baum/massstab.txt"), 1);
        assertNull(Laden.bestand(wurzel, () -> true));
        assertEquals("1.50 GB", Downloads.gb(1_500_000_000L));
    }

    private static void schreibe(Path datei, int bytes) throws Exception {
        Files.createDirectories(datei.getParent());
        Files.write(datei, new byte[bytes]);
    }
}
