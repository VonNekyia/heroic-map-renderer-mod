package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Die Schnellverwaltung ohne Minecraft: Zeilen, Suche, Filter, Sortierung, Seiten und Spalten. Siehe docs/verwaltung.md. */
class VerwaltungTest {

    private static final String WELT = "minecraft:overworld";

    /** Ein Wegpunkt, eine Region, eine Linie, ein altes Rechteck und ein Todespunkt in der Welt, dazu ein Wegpunkt im Nether. */
    private static Wegpunkte welt() {
        Wegpunkte w = new Wegpunkte();
        w.setze(WELT, 100, 0);
        w.benenne(w.punkte().getLast(), "Mine");
        w.setze(WELT, 10, 10);
        w.setze("minecraft:the_nether", 5, 5);
        w.setzeForm(WELT, List.of(new Wegpunkte.Ecke(0, 0, 0), new Wegpunkte.Ecke(20, 0, 0), new Wegpunkte.Ecke(20, 20, 0)));
        w.benenne(w.eigeneFormen().getLast(), "hof");
        w.setzeForm(WELT, List.of(new Wegpunkte.Ecke(-50, 0, 0), new Wegpunkte.Ecke(-40, 0, 0)));
        w.benenne(w.eigeneFormen().getLast(), "Weg zum Hafen");
        w.setze(WELT, 200, 200, 210, 210);
        w.setzeTod(WELT, 3, 3, 16, 1000);
        w.setzeTod(WELT, 4, 4, 16, 2000);
        return w;
    }

    private static List<Verwaltung.Zeile> zeilen(Wegpunkte w) {
        return Verwaltung.zeilen(w.punkte(), w.eigeneFormen(), w.regionen(), WELT);
    }

    @Test
    void zeilenNurDerDimension() {
        List<Verwaltung.Zeile> z = zeilen(welt());
        assertEquals(7, z.size(), "zwei Wegpunkte, Region, Linie, Rechteck, zwei Todespunkte; der im Nether fehlt");
        assertEquals(2, z.stream().filter(x -> x.art() == Verwaltung.Art.TOD).count());
        assertEquals(1, z.stream().filter(x -> x.art() == Verwaltung.Art.RECHTECK).count());
        // Die Mitte einer Form ist die Mitte des Kastens um ihre Ecken, die eines Wegpunkts die Mitte seines Blocks.
        Verwaltung.Zeile hof = z.stream().filter(x -> "hof".equals(x.name())).findFirst().orElseThrow();
        assertEquals(10.5, hof.x());
        assertEquals(10.5, hof.z());
        assertEquals(Verwaltung.Art.REGION, hof.art());
    }

    @Test
    void sucheFilterUndSortierung() {
        List<Verwaltung.Zeile> alle = zeilen(welt());
        // Nach Namen, gross oder klein gleich; Objekte ohne Namen hinter denen mit; Todespunkte am Ende, der neueste zuerst.
        List<Verwaltung.Zeile> name = Verwaltung.auswahl(alle, "", Verwaltung.Filter.ALLE, Verwaltung.ALLE_EBENEN, Verwaltung.Sortierung.NAME, 0, 0);
        assertEquals(List.of("hof", "Mine", "Weg zum Hafen"), name.subList(0, 3).stream().map(Verwaltung.Zeile::name).toList());
        assertEquals(Verwaltung.Art.TOD, name.get(5).art());
        assertEquals(2000, name.get(5).neu());
        assertEquals(1000, name.get(6).neu());
        // Die Suche findet Teile des Namens, gross oder klein, aber keine Todespunkte und nichts ohne Namen.
        assertEquals(List.of("Weg zum Hafen"), Verwaltung.auswahl(alle, " HAFEN ", Verwaltung.Filter.ALLE, Verwaltung.ALLE_EBENEN,
                Verwaltung.Sortierung.NAME, 0, 0).stream().map(Verwaltung.Zeile::name).toList());
        // Die Art: Todespunkte zählen zu den Wegpunkten, alte Rechtecke zu den Regionen.
        assertEquals(4, Verwaltung.auswahl(alle, "", Verwaltung.Filter.WEGPUNKTE, Verwaltung.ALLE_EBENEN, Verwaltung.Sortierung.NAME, 0, 0).size());
        assertEquals(2, Verwaltung.auswahl(alle, "", Verwaltung.Filter.REGIONEN, Verwaltung.ALLE_EBENEN, Verwaltung.Sortierung.NAME, 0, 0).size());
        assertEquals(1, Verwaltung.auswahl(alle, "", Verwaltung.Filter.LINIEN, Verwaltung.ALLE_EBENEN, Verwaltung.Sortierung.NAME, 0, 0).size());
        // Nach Nähe zu (100, 0): zuerst „Mine“; die Todespunkte trotzdem am Ende.
        List<Verwaltung.Zeile> nah = Verwaltung.auswahl(alle, "", Verwaltung.Filter.ALLE, Verwaltung.ALLE_EBENEN, Verwaltung.Sortierung.NAEHE, 100, 0);
        assertEquals("Mine", nah.getFirst().name());
        assertEquals(Verwaltung.Art.TOD, nah.getLast().art());
        // Neu zuerst: Wegpunkte nach id, dann Formen von hinten in der Datei, dann Rechtecke.
        List<Verwaltung.Zeile> neu = Verwaltung.auswahl(alle, "", Verwaltung.Filter.ALLE, Verwaltung.ALLE_EBENEN, Verwaltung.Sortierung.NEU, 0, 0);
        assertEquals(List.of(Verwaltung.Art.WEGPUNKT, Verwaltung.Art.WEGPUNKT, Verwaltung.Art.LINIE, Verwaltung.Art.REGION, Verwaltung.Art.RECHTECK),
                neu.subList(0, 5).stream().map(Verwaltung.Zeile::art).toList());
        assertEquals(null, neu.getFirst().name(), "der zuletzt gesetzte Wegpunkt zuerst");
    }

    @Test
    void ebeneFiltern() {
        Wegpunkte w = welt();
        int wege = w.neueEbene("Wege");
        w.ordne(w.eigeneFormen().getLast(), wege);
        List<Verwaltung.Zeile> alle = zeilen(w);
        List<Verwaltung.Zeile> nur = Verwaltung.auswahl(alle, "", Verwaltung.Filter.ALLE, wege, Verwaltung.Sortierung.NAME, 0, 0);
        assertEquals(List.of("Weg zum Hafen"), nur.stream().map(Verwaltung.Zeile::name).toList());
        assertEquals(6, Verwaltung.auswahl(alle, "", Verwaltung.Filter.ALLE, Wegpunkte.BASE, Verwaltung.Sortierung.NAME, 0, 0).size());
        // Reihum durch die eigenen Ebenen, nach der letzten die erste.
        assertEquals(wege, Verwaltung.naechsteEbene(w.ebenen(), Wegpunkte.BASE));
        assertEquals(Wegpunkte.BASE, Verwaltung.naechsteEbene(w.ebenen(), wege));
    }

    @Test
    void seitenUndSpalten() {
        assertEquals(314, Verwaltung.BREITE);
        assertEquals(1, Verwaltung.seiten(0, 5));
        assertEquals(8, Verwaltung.seiten(40, 5));
        assertEquals(List.of(5, 6), Verwaltung.seite(List.of(0, 1, 2, 3, 4, 5, 6), 1, 5));
        assertEquals(List.of(5, 6), Verwaltung.seite(List.of(0, 1, 2, 3, 4, 5, 6), 9, 5), "über die letzte Seite hinaus die letzte");
        assertEquals(10, Verwaltung.jeSeite(220, false));
        assertEquals(5, Verwaltung.jeSeite(220, true));
        assertEquals(1, Verwaltung.jeSeite(3, true));
        assertFalse(Verwaltung.zweiZeilen(314));
        assertTrue(Verwaltung.zweiZeilen(313));
        // Je Zeile nebeneinander ohne Überlappen, genau 314 breit; in zwei Zeilen höchstens 170, die zweite eine Zeile tiefer.
        for (boolean zwei : new boolean[] {false, true}) {
            Verwaltung.Lage[] l = Verwaltung.spalten(zwei);
            int rechts = 0;
            for (int i = 0; i < l.length; i++) {
                rechts = Math.max(rechts, l[i].x() + l[i].breite());
                for (int j = 0; j < i; j++) {
                    boolean getrennt = l[i].y() != l[j].y() || l[i].x() >= l[j].x() + l[j].breite() || l[j].x() >= l[i].x() + l[i].breite();
                    assertTrue(getrennt, (zwei ? "zwei Zeilen: " : "eine Zeile: ") + Verwaltung.Spalte.values()[i] + " über " + Verwaltung.Spalte.values()[j]);
                }
            }
            assertEquals(zwei ? Verwaltung.BREITE_ZWEI : Verwaltung.BREITE, rechts);
        }
        assertEquals(Verwaltung.ZEILE, Verwaltung.spalten(true)[Verwaltung.Spalte.EBENE.ordinal()].y());
    }

    @Test
    void schriftBiegungUndFarbe() {
        assertEquals(1.5, Verwaltung.schrift(1, 1));
        assertEquals(2.0 / 3, Verwaltung.schrift(1, -1));
        assertEquals(4, Verwaltung.schrift(4, 1), "an den Enden fest");
        assertEquals(0.25, Verwaltung.schrift(0.25, -1));
        assertEquals(15, Verwaltung.biegung(0, 1));
        assertEquals(180, Verwaltung.biegung(180, 1));
        assertEquals(-180, Verwaltung.biegung(-170, -1));
        // Die Farbe eines Wegpunkts wechselt, Name und Schrift bleiben; ein Todespunkt bleibt, wie er ist.
        Wegpunkte w = welt();
        Wegpunkte.Punkt mine = w.punkte().stream().filter(p -> "Mine".equals(p.name())).findFirst().orElseThrow();
        mine = w.stil(mine, 2, true);
        Wegpunkte.Punkt neu = w.faerbe(mine, mine.farbe() + 1);
        assertEquals(Math.floorMod(mine.farbe() + 1, Wegpunkte.FARBEN.length), neu.farbe());
        assertEquals("Mine", neu.name());
        assertEquals(2, neu.schrift());
        assertTrue(neu.minecraft());
        assertTrue(w.punkte().contains(neu));
        Wegpunkte.Punkt tod = w.tod();
        assertEquals(tod, w.faerbe(tod, tod.farbe() + 1));
        Wegpunkte.Region r = w.regionen().getFirst();
        assertEquals(Math.floorMod(r.farbe() + 3, Wegpunkte.FARBEN.length), w.faerbe(r, r.farbe() + 3).farbe());
    }
}
