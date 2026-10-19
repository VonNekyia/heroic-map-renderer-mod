package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Eigene Ebenen als Code teilen: hin und zurück, jede Grenze beim Einfügen. Siehe docs/wegpunkte.md, „Ebenen teilen“. */
class EbenenCodeTest {

    private static final String WELT = "minecraft:overworld";

    private static Wegpunkte.Ecke e(int x, int z) {
        return new Wegpunkte.Ecke(x, z);
    }

    @Test
    void hinUndZurueckOhneAngeheftetesUndTodespunkt() {
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        int farmen = w.neueEbene("Farmen");
        w.setze(WELT, 12, -40);
        w.setze("minecraft:the_nether", 3, 4);
        w.umschalten(w.punkte().getFirst());
        w.setzeForm(WELT, List.of(e(0, 0), e(10, 0), e(10, 10)));
        w.setzeForm(WELT, List.of(e(0, 20), e(10, 20)));
        w.benenne(w.eigeneFormen().get(0), "Hof");
        w.faerbe(w.eigeneFormen().get(0), 0x3A7FD5);
        w.umschalten(w.eigeneFormen().get(1));
        for (Wegpunkte.Punkt p : List.copyOf(w.punkte())) {
            w.ordne(p, farmen);
        }
        for (Wegpunkte.EigeneForm f : List.copyOf(w.eigeneFormen())) {
            w.ordne(f, farmen);
        }
        // Der Todespunkt und was in Base liegt, gehören nicht in den Code.
        w.setzeTod(WELT, 50, 50);
        w.setze(WELT, 60, 60);
        // Aus Base kopiert: der Wegpunkt dort, der Todespunkt nicht.
        assertEquals(List.of(new EbenenCode.Ort(WELT, 60, 60, w.punkte().getLast().farbe())),
                EbenenCode.lies(EbenenCode.schreibe(w, Wegpunkte.BASE)).inhalt().orte());
        String code = EbenenCode.schreibe(w, farmen);
        assertTrue(code.startsWith(EbenenCode.VORSILBE) && code.substring(EbenenCode.VORSILBE.length()).matches("[A-Za-z0-9_-]+"), code);
        EbenenCode.Gelesen g = EbenenCode.lies("  " + code + "\n");
        assertNull(g.fehler());
        assertEquals("Farmen", g.inhalt().name());
        assertEquals(List.of(new EbenenCode.Ort(WELT, 12, -40, w.punkte().get(0).farbe()),
                new EbenenCode.Ort("minecraft:the_nether", 3, 4, w.punkte().get(1).farbe())), g.inhalt().orte());
        // Mit eigener Farbe steht im Code nur „#RRGGBB“, kein Index; gelesen ist er 0.
        assertEquals(List.of(new EbenenCode.Form(WELT, List.of(e(0, 0), e(10, 0), e(10, 10)), 0, 0x3A7FD5, "Hof"),
                new EbenenCode.Form(WELT, List.of(e(0, 20), e(10, 20)), w.eigeneFormen().get(1).farbe(), Wegpunkte.KEIN_HEX, null)),
                g.inhalt().formen());
        // Eingefügt in eine andere Welt: eine neue Ebene, an, nichts angeheftet.
        Wegpunkte andere = new Wegpunkte();
        andere.lies((Path) null);
        Wegpunkte.Eingefuegt r = andere.fuegeEin(g.inhalt());
        assertNull(r.fehler());
        assertEquals(2, r.punkte());
        assertEquals(2, r.formen());
        assertEquals(new Wegpunkte.EigeneEbene(r.ebene(), "Farmen", true), andere.ebene(r.ebene()));
        assertTrue(andere.punkte().stream().allMatch(p -> p.ebene() == r.ebene() && !p.angeheftet()));
        assertTrue(andere.eigeneFormen().stream().allMatch(f -> f.ebene() == r.ebene() && !f.angeheftet()));
        assertEquals("Hof", andere.eigeneFormen().get(0).name());
        assertEquals(0x3A7FD5, andere.eigeneFormen().get(0).hex());
    }

    @Test
    void gleicherNameBekommtEineZahlUndBelegteBloeckeBleiben() {
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        w.neueEbene("Farmen");
        w.setze(WELT, 1, 1);
        EbenenCode.Inhalt inhalt = new EbenenCode.Inhalt("Farmen", List.of(new EbenenCode.Ort(WELT, 1, 1, 0), new EbenenCode.Ort(WELT, 2, 2, 0)),
                List.of());
        Wegpunkte.Eingefuegt r = w.fuegeEin(inhalt);
        assertEquals("Farmen (2)", w.ebene(r.ebene()).name());
        assertEquals(1, r.punkte());
        assertEquals("Farmen (3)", w.ebene(w.fuegeEin(inhalt).ebene()).name());
        // Ein langer Name wird vorn gekürzt, so passt die Zahl.
        String lang = "A".repeat(Wegpunkte.MAX_NAME);
        w.neueEbene(lang);
        assertEquals("A".repeat(Wegpunkte.MAX_NAME - 4) + " (2)", w.ebene(w.fuegeEin(new EbenenCode.Inhalt(lang, List.of(), List.of())).ebene()).name());
    }

    @Test
    void grenzenBeimEinfuegenInDieWelt() {
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        for (int i = 0; i < Wegpunkte.MAX_EBENEN; i++) {
            w.neueEbene("E" + i);
        }
        assertEquals("ebenen_voll", w.fuegeEin(new EbenenCode.Inhalt("X", List.of(), List.of())).fehler());
        Wegpunkte voll = new Wegpunkte();
        voll.lies((Path) null);
        for (int i = 0; i < Wegpunkte.MAX_EIGENE_FORMEN; i++) {
            voll.setzeForm(WELT, List.of(e(i, 0), e(i, 5)));
        }
        EbenenCode.Inhalt eine = new EbenenCode.Inhalt("X", List.of(),
                List.of(new EbenenCode.Form(WELT, List.of(e(0, 9), e(5, 9)), 0, Wegpunkte.KEIN_HEX, null)));
        assertEquals("formen_voll", voll.fuegeEin(eine).fehler());
        assertEquals(1, voll.ebenen().size());
    }

    @Test
    void lesenPrueftAllesOderNichts() {
        String gut = "{\"v\":1,\"name\":\"Farmen\",\"wegpunkte\":[{\"dimension\":\"minecraft:overworld\",\"x\":1,\"z\":2,\"farbe\":3}],"
                + "\"formen\":[{\"dimension\":\"minecraft:overworld\",\"ecken\":[[0,0],[5,5]],\"farbe\":\"#12AB34\",\"name\":\"Weg\"}]}";
        assertNull(EbenenCode.lies(EbenenCode.packe(gut)).fehler());
        // Unbekannte Felder überliest es, ebenso fehlende Listen.
        assertNull(EbenenCode.lies(EbenenCode.packe("{\"v\":1,\"name\":\"N\",\"neu\":[1,2],\"wegpunkte\":[{\"dimension\":\"a:b\",\"x\":0,\"z\":0,\"farbe\":0,\"name\":\"später\"}]}")).fehler());
        assertNull(EbenenCode.lies(EbenenCode.packe("{\"v\":1,\"name\":\"N\"}")).fehler());
        // Nicht von uns, zu gross, kaputt.
        assertEquals("kein_code", EbenenCode.lies(null).fehler());
        assertEquals("kein_code", EbenenCode.lies("Hallo").fehler());
        assertEquals("zu_gross", EbenenCode.lies(EbenenCode.VORSILBE + "A".repeat(EbenenCode.MAX_TEXT)).fehler());
        assertEquals("kaputt", EbenenCode.lies(EbenenCode.VORSILBE + "!!!").fehler());
        assertEquals("kaputt", EbenenCode.lies(EbenenCode.VORSILBE + "AAAA").fehler());
        String halb = EbenenCode.packe(gut);
        assertEquals("kaputt", EbenenCode.lies(halb.substring(0, halb.length() / 2)).fehler());
        // Eine Bombe: klein gepackt, entpackt über der Grenze.
        assertEquals("zu_gross", EbenenCode.lies(EbenenCode.packe(" ".repeat(EbenenCode.MAX_ENTPACKT + 10))).fehler());
        assertEquals("version", EbenenCode.lies(EbenenCode.packe(gut.replace("\"v\":1", "\"v\":2"))).fehler());
        StringBuilder viele = new StringBuilder("{\"v\":1,\"name\":\"N\",\"wegpunkte\":[");
        for (int i = 0; i <= EbenenCode.MAX_JE_CODE; i++) {
            viele.append(i == 0 ? "" : ",").append("{\"dimension\":\"a:b\",\"x\":").append(i).append(",\"z\":0,\"farbe\":0}");
        }
        assertEquals("zu_viele", EbenenCode.lies(EbenenCode.packe(viele + "]}")).fehler());
        // Jedes falsche Feld macht den ganzen Code kaputt.
        for (String[] falsch : new String[][] {
            {"\"name\":\"Farmen\"", "\"name\":\" \""},
            {"\"name\":\"Farmen\"", "\"name\":[1]"},
            {"\"x\":1", "\"x\":30000001"},
            {"\"x\":1", "\"x\":1.5"},
            {"\"x\":1", "\"x\":\"1\""},
            {"\"dimension\":\"minecraft:overworld\",\"x\"", "\"dimension\":\"Overworld\",\"x\""},
            {"\"dimension\":\"minecraft:overworld\",\"x\"", "\"dimension\":\"a:" + "b".repeat(63) + "\",\"x\""},
            {"\"farbe\":3", "\"farbe\":8"},
            {"\"farbe\":\"#12AB34\"", "\"farbe\":\"#12AB3\""},
            {"[[0,0],[5,5]]", "[[0,0]]"},
            {"[[0,0],[5,5]]", "[[0,0],[5]]"},
            {"\"name\":\"Weg\"", "\"name\":7"},
        }) {
            assertTrue(gut.contains(falsch[0]), falsch[0]);
            assertEquals("kaputt", EbenenCode.lies(EbenenCode.packe(gut.replace(falsch[0], falsch[1]))).fehler(), falsch[1]);
        }
        StringBuilder ecken = new StringBuilder("[");
        for (int i = 0; i <= Wegpunkte.MAX_PUNKTE_FORM; i++) {
            ecken.append(i == 0 ? "" : ",").append("[").append(i).append(",0]");
        }
        assertEquals("kaputt", EbenenCode.lies(EbenenCode.packe(gut.replace("[[0,0],[5,5]]", ecken + "]"))).fehler());
        assertFalse(EbenenCode.lies(EbenenCode.packe(gut)).inhalt().formen().isEmpty());
    }

    @Test
    void boesartigeCodesSindKaputtOhneZuWerfen() {
        // 100 000 öffnende Klammern: tief verschachtelt, entpackt unter der Grenze (Review von #146).
        assertEquals("kaputt", EbenenCode.lies(EbenenCode.packe("[".repeat(100_000))).fehler());
        // Eine Koordinate mit 200 000 Ziffern.
        String riesig = "{\"v\":1,\"name\":\"N\",\"wegpunkte\":[{\"dimension\":\"a:b\",\"x\":" + "9".repeat(200_000) + ",\"z\":0,\"farbe\":0}]}";
        assertTrue(riesig.length() < EbenenCode.MAX_ENTPACKT);
        assertEquals("kaputt", EbenenCode.lies(EbenenCode.packe(riesig)).fehler());
    }
}
