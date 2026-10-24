package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Wegwerf- und Rundenkarten: welche es sind, welche nach Alter und Grösse gehen. Siehe docs/selbst.md, „Wegwerfkarten“. */
class WegwerfTest {

    private static final String OBEN = "minecraft:overworld";
    private static final Instant JETZT = Instant.parse("2026-10-24T18:00:00Z");

    private static Ausweis.Welt welt(int tage, int rueckfaelle, Instant zuletzt) {
        return new Ausweis.Welt(zuletzt, zuletzt, tage, 0, 10, 10, null, rueckfaelle);
    }

    @Test
    void welcheWelten() {
        assertTrue(Wegwerf.wegwerf(welt(1, 0, JETZT)), "nur an einem Tag besucht");
        assertFalse(Wegwerf.wegwerf(welt(2, 0, JETZT)), "an zwei Tagen: bleibt");
        assertFalse(Wegwerf.wegwerf(welt(5, 1, JETZT)), "einmal zurückgesetzt, etwa eine Sicherung: bleibt");
        assertTrue(Wegwerf.wegwerf(welt(5, 2, JETZT)), "zweimal: eine Runde");
        assertFalse(Wegwerf.wegwerf(null), "ohne Ausweis nie");
    }

    @Test
    void nachAlterUndGroesse() {
        Path a = Path.of("a"), b = Path.of("b"), c = Path.of("c"), d = Path.of("d");
        Instant frisch = JETZT.minus(Duration.ofDays(1)), alt = JETZT.minus(Duration.ofDays(7)).minusSeconds(1);
        // Älter als 7 Tage: weg, wie klein auch immer; genau 7 Tage bleibt.
        assertEquals(List.of(a), Wegwerf.weg(List.of(new Wegwerf.Karte(a, alt, 1), new Wegwerf.Karte(b, JETZT.minus(Duration.ofDays(7)), 1)), JETZT));
        // Über 500 MB zusammen: die am längsten nicht besuchten zuerst, bis es passt.
        List<Wegwerf.Karte> gross = List.of(new Wegwerf.Karte(b, frisch, 300_000_000L), new Wegwerf.Karte(c, frisch.minusSeconds(60), 150_000_000L),
                new Wegwerf.Karte(d, frisch.minusSeconds(120), 100_000_000L));
        assertEquals(List.of(d), Wegwerf.weg(gross, JETZT), "550 MB, die älteste geht, dann 450 MB");
        assertEquals(List.of(), Wegwerf.weg(List.of(new Wegwerf.Karte(b, frisch, 500_000_000L)), JETZT), "genau 500 MB bleiben");
        assertEquals(List.of(b), Wegwerf.weg(List.of(new Wegwerf.Karte(b, frisch, 500_000_001L)), JETZT));
    }

    /** Ein Baum der Oberwelt mit map.json: ein Download, eine eigene Karte von Hand oder eine ohne Klick begonnene. */
    private static Path baum(Path welt, String art) throws IOException {
        if (!"download".equals(art)) {
            Path baum = Selbst.anlegen(welt, OBEN, 4, "main");
            Selbst.ohneKlick(baum, "ohne Klick".equals(art));
            return baum;
        }
        Path baum = welt.resolve("top");
        Satz.schreibe(baum, "Karte", OBEN, 4);
        Files.createDirectories(baum.resolve("4"));
        Files.writeString(baum.resolve("4/map.json"), "{\"tileSize\":256,\"scale\":4,\"minZoom\":0,\"maxZoom\":2}");
        return baum;
    }

    private static Path welt(Path wurzel, String ordner, int tage, int rueckfaelle, Instant zuletzt) throws IOException {
        Path welt = Files.createDirectories(wurzel.resolve(ordner));
        String hash = welt.getFileName().toString().substring("welt-".length());
        new Ausweis("play.example.net", "main", hash, Map.of(OBEN, welt(tage, rueckfaelle, zuletzt))).schreibe(welt);
        return welt;
    }

    @Test
    void nurOhneKlickBegonnene(@TempDir Path wurzel) throws IOException {
        // Review mod#218: Nach dem Update auf den Ausweis hat jede alte Welt einen Besuchstag. Eine von Hand begonnene Karte
        // dort bleibt, auch 8 Tage ohne Besuch; dieselbe ohne Klick begonnen geht.
        Path welt = welt(wurzel, "play.example.net/karte-main/welt-1111111111111111", 1, 0, JETZT.minus(Duration.ofDays(8)));
        Path eigene = baum(welt, "von Hand");
        assertEquals(List.of(), Wegwerf.weg(Wegwerf.sammle(wurzel), JETZT));
        Selbst.ohneKlick(eigene, true);
        assertEquals(List.of(eigene), Wegwerf.weg(Wegwerf.sammle(wurzel), JETZT));
        // Ein Klick nimmt die Marke wieder weg.
        Selbst.ohneKlick(eigene, false);
        assertFalse(Files.exists(eigene.resolve(Selbst.OHNE_KLICK)));
        assertEquals(List.of(), Wegwerf.sammle(wurzel));
    }

    @Test
    void sammeltNurOhneKlickBegonneneVonWegwerfwelten(@TempDir Path wurzel) throws IOException {
        Instant zuletzt = JETZT.minus(Duration.ofDays(3));
        Path runde = welt(wurzel, "play.example.net/karte-main/welt-1111111111111111", 4, 2, zuletzt);
        Path eigene = baum(runde, "ohne Klick");
        baum(runde, "download");
        Path tag = welt(wurzel, "zweit.example.net/welt-2222222222222222", 1, 0, zuletzt);
        Path eintag = baum(tag, "ohne Klick");
        baum(welt(wurzel, "play.example.net/karte-main/welt-3333333333333333", 4, 0, zuletzt), "ohne Klick");
        baum(welt(wurzel, "play.example.net/karte-main/welt-4444444444444444", 4, 2, zuletzt), "von Hand");
        baum(Files.createDirectories(wurzel.resolve("play.example.net/welt-5555555555555555")), "ohne Klick");
        baum(welt(wurzel, "einzelspieler_probe/welt-6666666666666666", 1, 0, zuletzt), "ohne Klick");
        Path hash = welt(wurzel, "welt-7777777777777777", 1, 0, zuletzt);
        Path ablageHash = baum(hash, "ohne Klick");

        List<Wegwerf.Karte> karten = Wegwerf.sammle(wurzel);
        // Die Runde und die Eintagswelt, auch in der Ablage Hash; keine Dauerwelt, kein von Hand begonnener, keiner ohne
        // Ausweis, keiner im Einzelspieler, kein Download.
        assertEquals(List.of(eigene, eintag, ablageHash).stream().sorted().toList(),
                karten.stream().map(Wegwerf.Karte::baum).sorted().toList(), karten.toString());
        Wegwerf.Karte k = karten.stream().filter(x -> x.baum().equals(eigene)).findFirst().orElseThrow();
        assertEquals(zuletzt, k.zuletzt());
        long bytes;
        try (var alle = Files.walk(eigene)) {
            bytes = alle.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
        }
        assertEquals(bytes, k.bytes(), "nur die Dateien des Baums");
    }
}
