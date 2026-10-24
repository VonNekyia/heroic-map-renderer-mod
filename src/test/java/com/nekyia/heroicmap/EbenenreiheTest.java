package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekyia.heroicmap.Ebenenreihe.Schicht;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Die Reihe der Ebenen je Spieler (renderer#317 Teil 4). Siehe docs/vollbildkarte.md, „Reihe der Ebenen“. */
class EbenenreiheTest {

    private static final Schicht A = Schicht.vomServer("a"), B = Schicht.vomServer("b"), C = Schicht.vomServer("c");
    private static final Schicht BASE = Schicht.eigene(0), FARM = Schicht.eigene(3);

    /** Ebenen vom Server aufsteigend wie {@code Ebenen.liste}: a mit order 5, b mit 2, c mit 0. */
    private static final List<Ebenen.Eintrag> SERVER = List.of(eintrag("c", 0), eintrag("b", 2), eintrag("a", 5));

    private static Ebenen.Eintrag eintrag(String id, int order) {
        return new Ebenen.Eintrag(id, id, id, true, order, "1", 0);
    }

    @Test
    void ohneUmstellenEigeneObenWieVorher() {
        assertEquals(List.of(BASE, FARM, A, B, C), Ebenenreihe.wirksam(null, SERVER, List.of(0, 3)));
    }

    @Test
    void umgestelltOhneVerschwundeneUndNeueNachDerRegel() {
        // Gemerkt: b oben, Farm, a, Base; c ist neu, d gibt es nicht mehr.
        List<Schicht> gemerkt = List.of(B, FARM, Schicht.vomServer("d"), A, BASE);
        // c mit order 0: über der obersten bekannten mit order höchstens 0, also über Farm (eigene zählen 0).
        assertEquals(List.of(B, Schicht.vomServer("c"), FARM, A, BASE), Ebenenreihe.wirksam(gemerkt, SERVER, List.of(0, 3)));
        // Mehrere neue nacheinander, erst die eigenen, dann die vom Server von oben nach unten; jede zählt die vor ihr eingereihten
        // mit. Die eigene 7 zählt 0: über Farm. hoch mit order 9: ganz oben. c mit 0: über 7. tief mit −1: Keine liegt
        // höchstens auf −1, also ganz unten.
        List<Ebenen.Eintrag> mehr = List.of(eintrag("tief", -1), eintrag("c", 0), eintrag("b", 2), eintrag("a", 5), eintrag("hoch", 9));
        assertEquals(List.of(Schicht.vomServer("hoch"), B, Schicht.vomServer("c"), Schicht.eigene(7), FARM, A, BASE,
                Schicht.vomServer("tief")), Ebenenreihe.wirksam(gemerkt, mehr, List.of(0, 3, 7)));
    }

    @Test
    void verschiebenUeberVersteckteHinweg() {
        List<Schicht> alle = List.of(BASE, FARM, A, B, C);
        Set<Schicht> gezeigt = Set.of(FARM, A, B, C);
        // Farm runter: unter a; Base, die nicht gezeigt ist, bleibt oben.
        assertEquals(List.of(BASE, A, FARM, B, C), Ebenenreihe.verschoben(alle, gezeigt, FARM, 1));
        // a hoch: über Farm, Base bleibt davor.
        assertEquals(List.of(BASE, A, FARM, B, C), Ebenenreihe.verschoben(alle, gezeigt, A, -1));
        // Über eine versteckte hinweg: Farm hoch springt über Base nicht, denn darüber ist keine gezeigte mehr.
        assertNull(Ebenenreihe.verschoben(alle, gezeigt, FARM, -1));
        assertNull(Ebenenreihe.verschoben(alle, gezeigt, C, 1));
        // Zwischen zwei gezeigten eine versteckte: Sie bleibt, wo sie war.
        List<Schicht> mitte = List.of(A, BASE, B);
        assertEquals(List.of(BASE, B, A), Ebenenreihe.verschoben(mitte, Set.of(A, B), A, 1));
    }

    @Test
    void gespeichertJeWeltUndZurueck(@TempDir Path ordner) throws Exception {
        Ebenenreihe r = new Ebenenreihe();
        r.wechsel(ordner);
        assertFalse(r.umgestellt());
        List<Schicht> alle = r.wirksam(SERVER, List.of(0, 3));
        int stand = r.stand();
        r.verschiebe(alle, Set.copyOf(alle), FARM, 1);
        assertTrue(r.umgestellt() && r.stand() > stand);
        Path datei = ordner.resolve(Ebenenreihe.DATEI);
        assertEquals("{\"reihe\":[{\"eigen\":0},{\"server\":\"a\"},{\"eigen\":3},{\"server\":\"b\"},{\"server\":\"c\"}]}",
                Files.readString(datei, StandardCharsets.UTF_8));
        Ebenenreihe neu = new Ebenenreihe();
        neu.wechsel(ordner);
        assertEquals(List.of(BASE, A, FARM, B, C), neu.wirksam(SERVER, List.of(0, 3)));
        // Die Ebenen vom Server in der Reihe, für das Untermenü.
        assertEquals(List.of("a", "b", "c"), neu.vomServer(SERVER.reversed(), List.of(0, 3)).stream().map(Ebenen.Eintrag::id).toList());
        // „Reihe des Servers“ löscht die Datei; wieder wie ohne Umstellen.
        neu.zuruecksetzen();
        assertFalse(neu.umgestellt() || Files.exists(datei));
        assertEquals(List.of(BASE, FARM, A, B, C), neu.wirksam(SERVER, List.of(0, 3)));
        // Unlesbar gilt order.
        Files.writeString(datei, "{kaputt", StandardCharsets.UTF_8);
        assertNull(Ebenenreihe.lies(datei));
    }
}
