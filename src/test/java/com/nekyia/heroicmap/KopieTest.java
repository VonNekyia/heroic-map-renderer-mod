package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** „Karte von … kopieren“: Hardlinks je Kachel, die Quelle bleibt unberührt, ganz oder gar nicht. Siehe docs/selbst.md, „Kopie“. */
class KopieTest {

    private static final String DIM = "minecraft:overworld";
    private static final byte[] ALT = {1, 2, 3}, NEU = {9, 9};

    /** Ein Download wie vom Plugin: Massstab 4, zwei Kacheln, dazu Stand und Zwischenordner, die nicht mitkommen. */
    private static Path quelle(Path w) throws IOException {
        Path baum = w.resolve("karte-main/welt-0123456789abcdef/top"), m = baum.resolve("4");
        Satz.schreibe(baum, "Oberwelt", DIM, 4);
        Files.createDirectories(m.resolve("2/0"));
        Files.createDirectories(m.resolve("1/-1"));
        Files.createDirectories(m.resolve("tmp"));
        Files.writeString(m.resolve("map.json"), "{\"tileSize\":256,\"scale\":4,\"minZoom\":0,\"maxZoom\":2}");
        Files.write(m.resolve("2/0/0.webp"), ALT);
        Files.write(m.resolve("1/-1/-1.webp"), ALT);
        Files.writeString(m.resolve("etags.txt"), "x");
        Files.writeString(m.resolve("tmp/halb.webp"), "x");
        return baum;
    }

    /** Ersetzt eine Datei, wie Kachelwerk und Laden es tun: über eine Zwischendatei mit ATOMIC_MOVE. */
    private static void ersetze(Path datei, byte[] neu) throws IOException {
        Path tmp = datei.resolveSibling(datei.getFileName() + ".tmp");
        Files.write(tmp, neu);
        Files.move(tmp, datei, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Test
    void hardlinksUndDieQuelleBleibt(@TempDir Path w) throws IOException {
        assertTrue(Kopie.hartMoeglich(w), "das Laufwerk der Tests kann Hardlinks");
        try (Stream<Path> rest = Files.list(w)) {
            assertEquals(0, rest.count(), "die Probe räumt auf");
        }
        Path quelle = quelle(w), ziel = w.resolve("karte-build/welt-0123456789abcdef/" + Selbst.baum(DIM, "build"));
        Kopie.kopiere(quelle, ziel, "build", true);
        // Eine eigene Karte von build, mit Name, Dimension und Massstab der Quelle und der Pyramide ohne Neubau.
        assertTrue(Selbst.gehoert(ziel, "build"));
        Satz satz = Satz.lies(ziel);
        assertEquals("Oberwelt", satz.name());
        assertEquals(4, satz.massstab());
        assertEquals(2, satz.maxZoom());
        assertTrue(Files.readString(ziel.resolve("4/map.json"), StandardCharsets.UTF_8).contains("\"downscale\":\"nearest\""));
        // Die Kacheln sind dieselben Dateien; Stand und Zwischenordner kommen nicht mit.
        assertTrue(Files.isSameFile(quelle.resolve("4/2/0/0.webp"), ziel.resolve("4/2/0/0.webp")));
        assertTrue(Files.isSameFile(quelle.resolve("4/1/-1/-1.webp"), ziel.resolve("4/1/-1/-1.webp")));
        assertFalse(Files.exists(ziel.resolve("4/etags.txt")));
        assertFalse(Files.exists(ziel.resolve("4/tmp")));
        // Zeichnet Selbst eine Kachel der Kopie neu, bleibt die Quelle; gleicht der Download die Quelle ab, bleibt die Kopie.
        ersetze(ziel.resolve("4/2/0/0.webp"), NEU);
        assertArrayEquals(ALT, Files.readAllBytes(quelle.resolve("4/2/0/0.webp")));
        ersetze(quelle.resolve("4/1/-1/-1.webp"), NEU);
        assertArrayEquals(ALT, Files.readAllBytes(ziel.resolve("4/1/-1/-1.webp")));
        // Den Link der Kopie löschen trifft die Quelle nicht.
        Files.delete(ziel.resolve("4/2/0/0.webp"));
        assertArrayEquals(ALT, Files.readAllBytes(quelle.resolve("4/2/0/0.webp")));
    }

    @Test
    void ohneHardlinksEineEchteKopie(@TempDir Path w) throws IOException {
        Path quelle = quelle(w), ziel = w.resolve("b/" + Selbst.baum(DIM, "build"));
        Kopie.kopiere(quelle, ziel, "build", false);
        assertFalse(Files.isSameFile(quelle.resolve("4/2/0/0.webp"), ziel.resolve("4/2/0/0.webp")));
        assertArrayEquals(ALT, Files.readAllBytes(ziel.resolve("4/2/0/0.webp")));
    }

    @Test
    void ganzOderGarNicht(@TempDir Path w) throws IOException {
        Path quelle = quelle(w), ziel = w.resolve("b/" + Selbst.baum(DIM, "build"));
        // Nie auf Bestehendes.
        Files.createDirectories(ziel);
        assertThrows(IOException.class, () -> Kopie.kopiere(quelle, ziel, "build", true));
        try (Stream<Path> rest = Files.list(ziel)) {
            assertEquals(0, rest.count());
        }
        Files.delete(ziel);
        // Scheitert es mittendrin, hier an einer Kachel, die sich nicht lesen lässt, ist das Ziel wieder weg; map.json lag da schon.
        Path gesperrt = quelle.resolve("4/1/-1/-1.webp");
        assumeTrue(gesperrt.toFile().setReadable(false, false) && !Files.isReadable(gesperrt), "nur, wo sich Lesen verbieten lässt");
        try {
            assertThrows(IOException.class, () -> Kopie.kopiere(quelle, ziel, "build", false));
            assertFalse(Files.exists(ziel));
        } finally {
            gesperrt.toFile().setReadable(true, false);
        }
        assertArrayEquals(ALT, Files.readAllBytes(quelle.resolve("4/2/0/0.webp")));
    }

    @Test
    void ohneSatzNichts(@TempDir Path w) throws IOException {
        Path ziel = w.resolve("b/" + Selbst.baum(DIM, "build"));
        // Ohne satz.json oder mit kaputter map.json gibt es nichts zu kopieren, und kein Ziel entsteht.
        Path quelle = quelle(w);
        Files.writeString(quelle.resolve("4/map.json"), "{kaputt");
        assertThrows(IOException.class, () -> Kopie.kopiere(quelle, ziel, "build", true));
        assertFalse(Files.exists(ziel));
        assertThrows(IOException.class, () -> Kopie.kopiere(w.resolve("fehlt"), ziel, "build", true));
    }
}
