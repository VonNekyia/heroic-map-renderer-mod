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

    /** Ein Baum der Oberwelt, eigene Karte oder Download, mit map.json. */
    private static Path baum(Path welt, boolean eigene) throws IOException {
        if (eigene) {
            return Selbst.anlegen(welt, OBEN, 4, "main");
        }
        Path baum = welt.resolve("top");
        Satz.schreibe(baum, "Karte", OBEN, 4);
        Files.createDirectories(baum.resolve("4"));
        Files.writeString(baum.resolve("4/map.json"), "{\"tileSize\":256,\"scale\":4,\"minZoom\":0,\"maxZoom\":2}");
        return baum;
    }

    @Test
    void sammeltNurEigeneKartenVonWegwerfwelten(@TempDir Path wurzel) throws IOException {
        Instant zuletzt = JETZT.minus(Duration.ofDays(3));
        Path runde = wurzel.resolve("play.example.net/karte-main/welt-1111111111111111");
        Path eigene = baum(runde, true);
        baum(runde, false);
        new Ausweis("play.example.net", "main", "1111111111111111", Map.of(OBEN, welt(4, 2, zuletzt))).schreibe(runde);
        Path dauer = wurzel.resolve("play.example.net/karte-main/welt-2222222222222222");
        baum(dauer, true);
        new Ausweis("play.example.net", "main", "2222222222222222", Map.of(OBEN, welt(4, 0, zuletzt))).schreibe(dauer);
        Path ohne = wurzel.resolve("play.example.net/karte-main/welt-3333333333333333");
        baum(ohne, true);
        Path einzel = wurzel.resolve("einzelspieler_probe/welt-4444444444444444");
        baum(einzel, true);
        new Ausweis("", "", "4444444444444444", Map.of(OBEN, welt(1, 0, zuletzt))).schreibe(einzel);

        List<Wegwerf.Karte> karten = Wegwerf.sammle(wurzel, Laden.bestand(wurzel, () -> false).karten());
        // Nur die eigene Karte der Runde: kein Download, keine Dauerwelt, nichts ohne Ausweis, nichts im Einzelspieler.
        assertEquals(1, karten.size(), karten.toString());
        assertEquals(eigene, karten.getFirst().baum());
        assertEquals(zuletzt, karten.getFirst().zuletzt());
    }
}
