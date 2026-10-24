package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Lesen, Schreiben und Übernahme der Widgets. Siehe docs/widgets.md. */
class WidgetsTest {

    private static final Widgets.Widget XZ = new Widgets.Widget(Widgets.Art.KOORDINATEN, 0, Widgets.Anker.UNTEN);
    private static final Widgets.Widget XYZ = new Widgets.Widget(Widgets.Art.KOORDINATEN, 1, Widgets.Anker.UNTEN);

    @Test
    void hinUndZurueck() {
        List<Widgets.Widget> w = List.of(XZ, XYZ);
        assertEquals("koordinaten:xz:unten;koordinaten:xyz:unten", Widgets.schreibe(w));
        assertEquals(w, Widgets.lies(Widgets.schreibe(w)));
        assertEquals(List.of(), Widgets.lies(""));
        assertEquals(List.of(), Widgets.lies(null));
        assertEquals("", Widgets.schreibe(List.of()));
    }

    @Test
    void kaputteEintraegeFehlenDieUebrigenBleiben() {
        assertEquals(List.of(XZ, XYZ), Widgets.lies(" koordinaten:xz:unten ; uhr:24h:unten;koordinaten:xy:unten;koordinaten:xz:mitte;"
                + "koordinaten:xz;koordinaten:xz:0:unten;koordinaten:xyz:unten"));
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
}
