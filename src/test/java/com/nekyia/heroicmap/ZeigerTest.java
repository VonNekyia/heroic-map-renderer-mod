package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der Zeiger auf die Karte eines anderen Servers mit derselben Welt, auch unter einer anderen Adresse, und der Übergang von
 * karte.von. Siehe docs/download.md, „Ablage“.
 */
class ZeigerTest {

    private static final String OBEN = "minecraft:overworld", NETHER = "minecraft:the_nether", HASH = "welt-3fa1c2d4e5f60718";
    private static final String UID = "0d9c1a2b-3c4d-4e5f-8a9b-0c1d2e3f4a5b", ANDERE = "7a1e0000-1111-4222-8333-444455556666";

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

    /** Ein Ordner der Welt unter {@code heroicmap/<adresse>[/karte-<karte>]}, mit einem Download der Oberwelt. */
    private Path woanders(String adresse, String karte) throws Exception {
        Path a = wurzel.resolve("heroicmap").resolve(adresse);
        Path welt = Files.createDirectories((karte == null ? a : a.resolve("karte-" + karte)).resolve(HASH));
        download(welt, "top-north-s");
        return welt;
    }

    private static void ausweis(Path welt, String netz, String uid) throws Exception {
        Instant t = Instant.parse("2026-10-24T10:00:00Z");
        new Ausweis(netz, "", HASH.substring("welt-".length()), Map.of(OBEN, new Ausweis.Welt(t, t, 1, 0, 10, 10, uid))).schreibe(welt);
    }

    @Test
    void gleicheWeltUnterAnderenAdressen() throws Exception {
        Path build = Files.createDirectories(wurzel.resolve("heroicmap/play.example.net/karte-build/" + HASH));
        ausweis(build, "play.example.net", UID);
        assertEquals(List.of(), Zeiger.fremde(build, OBEN), "allein keine");
        ausweis(woanders("play.example.net_25566", "main"), "play.example.net:25566", UID);
        woanders("zweit.example.net", null);
        ausweis(woanders("drei.example.net", null), "drei.example.net", ANDERE);
        ausweis(woanders("main", null), "play.example.net", null);
        woanders("play.example.net_25565", null);
        Path einzel = woanders("einzelspieler_probe", null);
        Files.createDirectories(wurzel.resolve("heroicmap/vier.example.net/" + HASH));
        Files.writeString(woanders("sechs.example.net", null).resolve("top-north-s/" + Reset.RUHT), "x");
        Selbst.anlegen(Files.createDirectories(wurzel.resolve("heroicmap/sieben.example.net/" + HASH)), OBEN, 4, "sieben");
        // Ein anderer Port ja, ohne Ausweis ja; nicht mit anderer UID, nicht unter derselben eingetippten Adresse, nicht
        // derselbe Host mit _25565, kein Einzelspieler, nicht ohne Download, nicht ruhend, nicht nur eine eigene Karte.
        assertEquals(List.of("@play.example.net_25566/karte-main", "@zweit.example.net"), Zeiger.fremde(build, OBEN));
        assertEquals(List.of(), Zeiger.fremde(build, NETHER), "andere Welt");
        assertEquals(List.of(), Zeiger.kandidaten(build), "nicht unter derselben Adresse");
        // Aus dem Einzelspieler ohne Ausweis: alle Server mit einem Download der Welt.
        assertEquals(List.of("@drei.example.net", "@main", "@play.example.net_25565", "@play.example.net_25566/karte-main",
                "@zweit.example.net"), Zeiger.fremde(einzel, OBEN));

        Zeiger.setze(build, "@zweit.example.net");
        assertEquals("@zweit.example.net", Zeiger.name(build));
        assertEquals(wurzel.resolve("heroicmap/zweit.example.net/" + HASH), Zeiger.ziel(build));
        assertEquals(HASH + "/top-north-s", gezeigt(build, "build"), "der Download dort");
    }

    @Test
    void fremdNurUnterHeroicmap() throws Exception {
        Path build = Files.createDirectories(wurzel.resolve("heroicmap/play.example.net/karte-build/" + HASH));
        for (String falsch : List.of("@", "@..", "@../x", "@Zweit.example.net", "@zweit.example.net/", "@zweit.example.net/main",
                "@zweit.example.net/karte-a/b", "@play.example.net", "@play.example.net_25565/karte-main", "@einzelspieler_probe")) {
            Files.writeString(build.resolve(Zeiger.DATEI), falsch);
            assertNull(Zeiger.name(build), falsch);
        }
        Files.writeString(build.resolve(Zeiger.DATEI), "@zweit.example.net/karte-main\n");
        assertEquals("@zweit.example.net/karte-main", Zeiger.name(build));
        assertNull(Zeiger.ziel(build), "das Ziel fehlt");
        woanders("zweit.example.net", null);
        // Ablage Hash: ohne Adresse kein Zeiger über Adressen; ohne heroicmap/ darüber auch nicht.
        Path hash = Files.createDirectories(wurzel.resolve("heroicmap/" + HASH));
        Files.writeString(hash.resolve(Zeiger.DATEI), "@zweit.example.net");
        assertNull(Zeiger.name(hash));
        assertEquals(List.of(), Zeiger.fremde(hash, OBEN));
        Path ohne = welt("build");
        Files.writeString(ohne.resolve(Zeiger.DATEI), "@zweit.example.net");
        assertNull(Zeiger.name(ohne));
        // Kein Link, weder als Adresse noch als Ordner der Welt.
        Path echt = woanders("echt.example.net", null);
        try {
            Files.createSymbolicLink(wurzel.resolve("heroicmap/link.example.net"), echt.getParent());
            Files.createSymbolicLink(Files.createDirectories(wurzel.resolve("heroicmap/tief.example.net")).resolve(HASH), echt);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return;
        }
        assertEquals(List.of("@echt.example.net", "@zweit.example.net"), Zeiger.fremde(build, OBEN));
        Zeiger.setze(build, "@tief.example.net");
        assertNull(Zeiger.ziel(build), "keinem Link folgen");
    }
}
