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

/** Der Zeiger auf die Karte eines anderen Servers mit derselben Welt, und der Übergang von karte.von. Siehe docs/download.md, „Ablage“. */
class ZeigerTest {

    private static final String OBEN = "minecraft:overworld", HASH = "welt-3fa1c2d4e5f60718";

    @TempDir
    Path wurzel;

    private Path welt(String karte) throws Exception {
        Path adresse = wurzel.resolve("play.example.net");
        return Files.createDirectories(karte == null ? adresse.resolve(HASH) : adresse.resolve("karte-" + karte).resolve(HASH));
    }

    private static void download(Path welt, String baum) throws Exception {
        Satz.schreibe(welt.resolve(baum), "Top North", OBEN, 4);
        Path ordner = Files.createDirectories(welt.resolve(baum).resolve("4"));
        Files.writeString(ordner.resolve("map.json"), "{\"tileSize\":256,\"scale\":4,\"minZoom\":0,\"maxZoom\":8}");
    }

    private static String gezeigt(Path welt, String server) {
        Satz s = Satz.fuer(welt, OBEN, server);
        return s == null ? null : s.ordner().getParent().getParent().getFileName() + "/" + s.ordner().getParent().getFileName();
    }

    @Test
    void kandidatenNurGleicheWeltUnterDerAdresse() throws Exception {
        Path build = welt("build");
        assertEquals(List.of(), Zeiger.kandidaten(build), "allein keine");
        welt("main");
        welt(null);
        Files.createDirectories(wurzel.resolve("play.example.net/karte-main/welt-0000000000000000"));
        Files.createDirectories(wurzel.resolve("andere.example.net/karte-alt/" + HASH));
        assertEquals(List.of(Zeigt.OHNE, "main"), Zeiger.kandidaten(build), "ohne Karte zuerst; nicht andere Welten oder Adressen");
    }

    @Test
    void zeigerZeigtDenDownloadDesZielsNieSeineEigeneKarte() throws Exception {
        Path main = welt("main"), build = welt("build");
        download(main, "top-north-s");
        Selbst.anlegen(main, OBEN, 4, "main");
        Path eigene = Selbst.anlegen(build, OBEN, 4, "build");
        assertEquals(HASH + "/" + eigene.getFileName(), gezeigt(build, "build"), "ohne Zeiger die eigene");

        Zeiger.setze(build, "main");
        assertEquals(main, Zeiger.ziel(build));
        assertEquals(HASH + "/top-north-s", gezeigt(build, "build"), "mit Zeiger der Download von main vor der eigenen");
        Zeigt.setze(build, "build", OBEN, eigene.getFileName().toString());
        assertEquals(HASH + "/" + eigene.getFileName(), gezeigt(build, "build"), "die Wahl geht vor");
        Zeigt.setze(build, "build", OBEN, Zeiger.DORT + "top-north-s");
        assertEquals(HASH + "/top-north-s", gezeigt(build, "build"), "@ wählt einen Baum im Ziel");
        Zeigt.setze(build, "build", OBEN, Zeiger.DORT + Selbst.baum(OBEN, "main"));
        assertEquals(HASH + "/top-north-s", gezeigt(build, "build"), "nie die eigene Karte im Ziel, auch mit Wahl");

        Laden.loesche(main);
        assertNull(Zeiger.ziel(build), "das Ziel fehlt");
        assertEquals("main", Zeiger.name(build), "der Zeiger bleibt, bis der Spieler ihn löst");
        assertEquals(HASH + "/" + eigene.getFileName(), gezeigt(build, "build"), "ohne Ziel die Regel ohne Zeiger");
        Zeiger.loese(build);
        assertNull(Zeiger.name(build));
    }

    @Test
    void nurEinNameUndKeinLink() throws Exception {
        Path build = welt("build");
        Files.writeString(build.resolve(Zeiger.DATEI), "../../etwas");
        assertNull(Zeiger.name(build), "nur ein Name wie eine Karte");
        Files.writeString(build.resolve(Zeiger.DATEI), "build\n");
        assertNull(Zeiger.ziel(build), "nicht auf sich selbst");
        Path echt = Files.createDirectories(wurzel.resolve("woanders/" + HASH));
        Files.createDirectories(wurzel.resolve("play.example.net/karte-link"));
        try {
            Files.createSymbolicLink(wurzel.resolve("play.example.net/karte-link/" + HASH), echt);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return;
        }
        Zeiger.setze(build, "link");
        assertNull(Zeiger.ziel(build), "keinem Link folgen");
        assertFalse(Zeiger.kandidaten(build).contains("link"));

        // Ein Link weiter oben: Der Ordner selbst ist keiner, liegt aber woanders, wie hinter einer Junction unter Windows.
        Path oben = Files.createDirectories(wurzel.resolve("anderswo/karte-oben/" + HASH)).getParent();
        Files.createSymbolicLink(wurzel.resolve("play.example.net/karte-oben"), oben);
        Zeiger.setze(build, "oben");
        assertNull(Zeiger.ziel(build), "keinem Link weiter oben folgen");
        assertFalse(Zeiger.kandidaten(build).contains("oben"));
    }

    @Test
    void uebergangGenauEinmal() throws Exception {
        Path main = welt("main"), build = welt("build");
        download(main, "top-north-s");
        long hash = 0x3fa1c2d4e5f60718L;
        Uebergang.merke(wurzel, "Play.Example.net", hash, "main", "main");
        assertNull(Uebergang.einmal(wurzel, "play.example.net", hash, "main"), "die eigene Karte merkt er nicht");

        Uebergang.merke(wurzel, "Play.Example.net", hash, "build", "main");
        String alt = Uebergang.einmal(wurzel, "play.example.net", hash, "build");
        assertEquals("main", alt, "build zeigte main");
        assertTrue(Downloads.zeigeEinmal(build, alt));
        assertEquals(main, Zeiger.ziel(build));
        assertNull(Uebergang.einmal(wurzel, "play.example.net", hash, "build"), "nur einmal");

        // Gelöst kommt er nicht wieder.
        Zeiger.loese(build);
        assertNull(Uebergang.einmal(wurzel, "play.example.net", hash, "build"));
        assertNull(Zeiger.name(build));

        // Ohne den Ordner von main gibt es nichts, worauf er zeigen könnte.
        Path neu = welt("neu");
        assertFalse(Downloads.zeigeEinmal(neu, "fehlt"));
        assertNull(Zeiger.name(neu));
    }
}
