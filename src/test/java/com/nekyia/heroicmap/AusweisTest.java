package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Der Kartenausweis eines Weltordners: Besuche, Tage, Spielzeit, Lesen und Schreiben. Siehe docs/download.md, „Ausweis“. */
class AusweisTest {

    private static final String OBEN = "minecraft:overworld", NETHER = "minecraft:the_nether", HASH = "3fa1c2d4e5f60718";
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    @Test
    void besucheUndSpielzeit() {
        Instant t1 = Instant.parse("2026-10-24T10:00:00Z");
        Ausweis a = Ausweis.neu(HASH).mit("play.example.net:25566", "build").besucht(OBEN, t1, ZONE);
        Ausweis.Welt o = a.welten().get(OBEN);
        assertEquals(new Ausweis.Welt(t1, t1, 1, -1, -1, -1), o, "ohne Spielzeit -1");
        // Die erste gesehene bleibt, die grösste wächst, die letzte darf fallen.
        a = a.alter(OBEN, 500).alter(OBEN, 900).alter(OBEN, 400);
        assertEquals(new Ausweis.Welt(t1, t1, 1, 500, 900, 400), a.welten().get(OBEN));
        // Am selben Tag kein Tag mehr, am nächsten einer; der Tag zählt in der Zone, nicht in UTC.
        Instant spaeter = t1.plusSeconds(3600), mitternacht = Instant.parse("2026-10-24T22:30:00Z");
        a = a.besucht(OBEN, spaeter, ZONE);
        assertEquals(1, a.welten().get(OBEN).tage());
        assertEquals(t1, a.welten().get(OBEN).erste());
        assertEquals(spaeter, a.welten().get(OBEN).zuletzt());
        a = a.besucht(OBEN, mitternacht, ZONE);
        assertEquals(2, a.welten().get(OBEN).tage(), "00:30 in Berlin ist der nächste Tag");
        // Eine zweite Welt im selben Ordner, etwa der Nether mit demselben Seed.
        a = a.besucht(NETHER, mitternacht, ZONE);
        assertEquals(List.of(OBEN, NETHER), List.copyOf(a.welten().keySet()), "sortiert");
        assertEquals(-1, a.welten().get(NETHER).alterMax());
        // Spielzeit ohne Besuch der Welt ändert nichts.
        assertSame(a, a.alter("minecraft:farm", 5));
        assertEquals("play.example.net:25566", a.netz());
        assertEquals("build", a.server());
    }

    @Test
    void schreibenUndLesen(@TempDir Path w) throws IOException {
        Instant t = Instant.parse("2026-10-24T10:00:00Z");
        Ausweis a = Ausweis.neu(HASH).mit("0", "main").besucht(OBEN, t, ZONE).alter(OBEN, 1234).besucht(NETHER, t, ZONE);
        a.schreibe(w);
        assertEquals(a, Ausweis.lies(w));
        assertFalse(Files.exists(w.resolve(Ausweis.DATEI + ".tmp")), "keine Zwischendatei bleibt");
        // Gleicher Inhalt, gleiche Datei.
        String datei = Files.readString(w.resolve(Ausweis.DATEI));
        Ausweis.lies(w).schreibe(w);
        assertEquals(datei, Files.readString(w.resolve(Ausweis.DATEI)));
        // Fehlt die Datei, ist sie kaputt oder fehlt ein Feld: keiner.
        assertNull(Ausweis.lies(w.resolve("fehlt")));
        Files.writeString(w.resolve(Ausweis.DATEI), "{kaputt");
        assertNull(Ausweis.lies(w));
        Files.writeString(w.resolve(Ausweis.DATEI), "{\"version\":1,\"netz\":\"0\",\"server\":\"\",\"welten\":{}}");
        assertNull(Ausweis.lies(w), "ohne Hash");
    }
}
