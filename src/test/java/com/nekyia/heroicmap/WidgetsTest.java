package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Lesen, Schreiben und Übernahme der Widgets, wohin ein gezogenes fällt, Grösse und ×, Uhr und Tag. Siehe docs/widgets.md. */
class WidgetsTest {

    private static final Widgets.Widget XZ = new Widgets.Widget(Widgets.Art.KOORDINATEN, 0, Widgets.Anker.UNTEN);
    private static final Widgets.Widget XYZ = new Widgets.Widget(Widgets.Art.KOORDINATEN, 1, Widgets.Anker.UNTEN);
    private static final Widgets.Widget OBEN = new Widgets.Widget(Widgets.Art.KOORDINATEN, 0, 3, Widgets.Anker.OBEN, 0, 0);
    private static final Widgets.Widget FREI = new Widgets.Widget(Widgets.Art.KOORDINATEN, 1, 5, Widgets.Anker.FREI, 0.25f, 0.75f);

    @Test
    void hinUndZurueck() {
        List<Widgets.Widget> w = List.of(XZ, XYZ, OBEN, FREI);
        assertEquals("koordinaten:xz:0:unten;koordinaten:xyz:0:unten;koordinaten:xz:3:oben;koordinaten:xyz:5:frei:0.25:0.75",
                Widgets.schreibe(w));
        assertEquals(w, Widgets.lies(Widgets.schreibe(w)));
        assertEquals(List.of(), Widgets.lies(""));
        assertEquals(List.of(), Widgets.lies(null));
        assertEquals("", Widgets.schreibe(List.of()));
        // Aus 0.2.69 ohne Grösse.
        assertEquals(List.of(XZ, XYZ), Widgets.lies("koordinaten:xz:unten;koordinaten:xyz:unten"));
        // Zeit und Biom mit jeder Version.
        String alle = "zeit:24h:0:unten;zeit:12h:0:unten;zeit:tag:0:oben;zeit:echt:2:frei:0.5:0.5;biom:name:0:unten;biom:punkt:0:oben";
        List<Widgets.Widget> w2 = Widgets.lies(alle);
        assertEquals(6, w2.size());
        assertEquals(alle, Widgets.schreibe(w2));
        assertEquals(List.of("24h", "12h", "tag", "echt", "name", "punkt"), w2.stream().map(Widgets.Widget::versionName).toList());
    }

    @Test
    void kaputteEintraegeFehlenDieUebrigenBleiben() {
        assertEquals(List.of(XZ, XYZ), Widgets.lies(" koordinaten:xz:unten ; uhr:24h:unten;koordinaten:xy:unten;koordinaten:xz:mitte;"
                + "koordinaten:xz;koordinaten:xz:9:unten;koordinaten:xz:-1:unten;koordinaten:xz:a:oben;koordinaten:xz:0:frei:0.5;"
                + "koordinaten:xz:0:frei:1.5:0;koordinaten:xz:0:frei:0:-0.1;koordinaten:xz:0:frei:a:b;koordinaten:xz:0:oben:0:0;"
                + "koordinaten:xyz:0:unten"));
        // Höchstens 16, als Zahl, nicht die Konstante.
        assertEquals(16, Widgets.lies("koordinaten:xz:unten;".repeat(20)).size());
    }

    @Test
    void uebernahmeAusDerAltenZeile() {
        assertEquals(List.of(), Widgets.ausKoordinaten("aus"));
        assertEquals(List.of(XYZ), Widgets.ausKoordinaten("xyz"));
        assertEquals(List.of(XZ), Widgets.ausKoordinaten("xz"));
        // Ohne Eintrag oder mit unbekanntem Wert die Vorgabe, wie bisher.
        assertEquals(List.of(XZ), Widgets.ausKoordinaten(null));
        assertEquals(List.of(XZ), Widgets.ausKoordinaten("quer"));
    }

    @Test
    void versionReihumGroesseInStufen() {
        assertEquals(XYZ, XZ.naechsteVersion());
        assertEquals(XZ, XYZ.naechsteVersion());
        // Die Zeit reihum über vier Versionen, das Biom über zwei.
        Widgets.Widget zeit = new Widgets.Widget(Widgets.Art.ZEIT, 3, Widgets.Anker.OBEN);
        assertEquals(new Widgets.Widget(Widgets.Art.ZEIT, 0, Widgets.Anker.OBEN), zeit.naechsteVersion());
        assertEquals(0, new Widgets.Widget(Widgets.Art.BIOM, 1, Widgets.Anker.UNTEN).naechsteVersion().version());
        // Grösse 0 heisst wie der GUI-Massstab; eine Raste gibt dann eine Zahl, von 1 bis 8.
        assertEquals(3, XZ.pixel(3));
        assertEquals(4, XZ.stufe(3, 1).groesse());
        assertEquals(2, XZ.stufe(3, -1).groesse());
        assertEquals(8, OBEN.stufe(2, 1).stufe(2, 10).groesse());
        assertEquals(1, OBEN.stufe(2, -10).groesse());
        // Im Stapel gilt keine Lage, frei schon.
        assertEquals(new Widgets.Widget(Widgets.Art.KOORDINATEN, 1, 5, Widgets.Anker.UNTEN, 0, 0), FREI.nach(Widgets.Anker.UNTEN, 0.5f, 0.5f));
        assertEquals(new Widgets.Widget(Widgets.Art.KOORDINATEN, 0, 0, Widgets.Anker.FREI, 0.5f, 0.1f), XZ.nach(Widgets.Anker.FREI, 0.5f, 0.1f));
    }

    @Test
    void uhrUndTag() {
        // Tick 0 ist 6:00, 1000 Ticks eine Stunde, 1000 / 60 Ticks eine Minute.
        assertEquals("6:00", Widgets.uhr(0, false));
        assertEquals("12:00", Widgets.uhr(6000, false));
        assertEquals("0:00", Widgets.uhr(18000, false));
        assertEquals("19:30", Widgets.uhr(13500, false));
        assertEquals("5:59", Widgets.uhr(23999, false));
        assertEquals("6:00", Widgets.uhr(24000 * 5, false));
        assertEquals("6:00 AM", Widgets.uhr(0, true));
        assertEquals("12:00 PM", Widgets.uhr(6000, true));
        assertEquals("7:30 PM", Widgets.uhr(13500, true));
        assertEquals("12:00 AM", Widgets.uhr(18000, true));
        assertEquals("12:59 AM", Widgets.uhr(18999, true));
        assertEquals("11:00 AM", Widgets.uhr(5000, true));
        // Der Tag ab 1.
        assertEquals(1, Widgets.tag(0));
        assertEquals(1, Widgets.tag(23999));
        assertEquals(2, Widgets.tag(24000));
        assertEquals(12, Widgets.tag(24000 * 11 + 6000));
    }

    @Test
    void zielUntenObenNebenFrei() {
        // Die Minimap bei (100, 50), Seite 128; unten zwei Zeilen, 9 hoch.
        int[] m = {100, 50, 128};
        List<int[]> unten = List.of(new int[] {130, 181, 60, 9}, new int[] {140, 192, 40, 9});
        // Mitte unter der Unterkante, über der ersten Zeile: unten an Stelle 0; zwischen den Zeilen an 1; hinter ihnen bis 8 Einheiten an 2.
        assertEquals(new Widgets.Ziel(Widgets.Anker.UNTEN, 0, 0, 0), Widgets.ziel(140, 176, 40, 9, m, unten, List.of(), 640, 360, 4));
        assertEquals(new Widgets.Ziel(Widgets.Anker.UNTEN, 1, 0, 0), Widgets.ziel(140, 183, 40, 9, m, unten, List.of(), 640, 360, 4));
        assertEquals(new Widgets.Ziel(Widgets.Anker.UNTEN, 2, 0, 0), Widgets.ziel(140, 201, 40, 9, m, unten, List.of(), 640, 360, 4));
        // Weiter als 8 darunter, oder waagrecht weiter als 8 neben der Minimap: frei.
        assertEquals(Widgets.Anker.FREI, Widgets.ziel(140, 214, 40, 9, m, unten, List.of(), 640, 360, 4).anker());
        assertEquals(Widgets.Anker.FREI, Widgets.ziel(10, 176, 40, 9, m, unten, List.of(), 640, 360, 4).anker());
        // Oben ohne Zeilen: bis 8 über und unter der Oberkante.
        assertEquals(new Widgets.Ziel(Widgets.Anker.OBEN, 0, 0, 0), Widgets.ziel(140, 38, 40, 9, m, unten, List.of(), 640, 360, 4));
        assertEquals(Widgets.Anker.FREI, Widgets.ziel(140, 25, 40, 9, m, unten, List.of(), 640, 360, 4).anker());
        // Oben über der Minimap: eine Zeile darüber; über ihr an Stelle 1.
        List<int[]> darueber = List.of(new int[] {130, 39, 60, 9});
        assertEquals(new Widgets.Ziel(Widgets.Anker.OBEN, 1, 0, 0), Widgets.ziel(140, 28, 40, 9, m, unten, darueber, 640, 360, 4));
        // Oben neben der Minimap, weil darüber kein Platz ist: über den Zeilen, auch links von der Minimap.
        int[] amRand = {500, 4, 128};
        List<int[]> neben = List.of(new int[] {420, 4, 50, 9});
        assertEquals(new Widgets.Ziel(Widgets.Anker.OBEN, 1, 0, 0), Widgets.ziel(420, 12, 50, 9, amRand, List.of(), neben, 640, 360, 4));
        assertEquals(new Widgets.Ziel(Widgets.Anker.OBEN, 0, 0, 0), Widgets.ziel(420, 0, 50, 9, amRand, List.of(), neben, 640, 360, 4));
        // Frei: die Lage als Anteil des Platzes zwischen den Rändern.
        assertEquals(new Widgets.Ziel(Widgets.Anker.FREI, -1, 0.5f, 0.5f), Widgets.ziel(4 + 296, 4 + 171, 40, 10, m, unten, List.of(), 640, 360, 4));
        assertEquals(new Widgets.Ziel(Widgets.Anker.FREI, -1, 0, 1), Widgets.ziel(-20, 400, 40, 10, m, unten, List.of(), 640, 360, 4));
    }

    @Test
    void setzenUndKreuz() {
        List<Widgets.Widget> liste = List.of(XZ, OBEN, XYZ);
        // Das erste ans Ende des unteren Stapels: hinter XYZ.
        Widgets.Gesetzt g = Widgets.setze(liste, 0, new Widgets.Ziel(Widgets.Anker.UNTEN, 1, 0, 0));
        assertEquals(List.of(OBEN, XYZ, XZ), g.liste());
        assertEquals(2, g.stelle());
        // XYZ oben vor OBEN.
        g = Widgets.setze(liste, 2, new Widgets.Ziel(Widgets.Anker.OBEN, 0, 0, 0));
        assertEquals(List.of(XZ, new Widgets.Widget(Widgets.Art.KOORDINATEN, 1, 0, Widgets.Anker.OBEN, 0, 0), OBEN), g.liste());
        assertEquals(1, g.stelle());
        // Frei: hinten, mit Lage.
        g = Widgets.setze(liste, 1, new Widgets.Ziel(Widgets.Anker.FREI, -1, 0.3f, 0.4f));
        assertEquals(List.of(XZ, XYZ, new Widgets.Widget(Widgets.Art.KOORDINATEN, 0, 3, Widgets.Anker.FREI, 0.3f, 0.4f)), g.liste());
        assertEquals(2, g.stelle());
        // Das × halb über der rechten oberen Ecke, 8 im Quadrat.
        assertArrayEquals(new int[] {156, 46, 8, 8}, Widgets.kreuz(new int[] {100, 50, 60, 9}));
    }
}
