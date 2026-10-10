package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Wegpunkte setzen, anheften, löschen und über den Neustart behalten. Siehe docs/wegpunkte.md. */
class WegpunkteTest {

    private static final String WELT = "minecraft:overworld";
    private static final UUID SAM = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void ueberstehenDenNeustart(@TempDir Path ordner) {
        Wegpunkte vorher = new Wegpunkte();
        vorher.lies(ordner);
        vorher.setze(WELT, 12, -40);
        vorher.setze("minecraft:the_nether", 1, 2);
        vorher.umschalten(vorher.punkte().getFirst());
        vorher.umschalten(SAM);

        Wegpunkte nachher = new Wegpunkte();
        nachher.lies(ordner);
        assertEquals(List.of(new Wegpunkte.Punkt(WELT, 12, -40, 0, true), new Wegpunkte.Punkt("minecraft:the_nether", 1, 2, 0, false)),
                nachher.punkte());
        assertTrue(nachher.angeheftet(SAM));
    }

    @Test
    void setzenLoeschenLoesen(@TempDir Path ordner) {
        Wegpunkte w = new Wegpunkte();
        w.lies(ordner);
        w.setze(WELT, 5, 5);
        // Auf demselben Block bleibt es einer.
        w.setze(WELT, 5, 5);
        assertEquals(1, w.punkte().size());
        w.umschalten(SAM);
        w.umschalten(SAM);
        assertFalse(w.angeheftet(SAM));
        w.loesche(w.punkte().getFirst());
        assertTrue(w.punkte().isEmpty());
        assertTrue(Files.exists(ordner.resolve("wegpunkte.json")));
        assertFalse(Files.exists(ordner.resolve("wegpunkte.json.tmp")));
    }

    @Test
    void regionenSetzenLoeschenBehalten(@TempDir Path ordner) {
        Wegpunkte w = new Wegpunkte();
        w.lies(ordner);
        // Die Ecken in beliebiger Folge: ein Rechteck der Blöcke samt beiden Ecken.
        w.setze(WELT, 10, -5, 2, 3);
        assertEquals(List.of(new Wegpunkte.Region(WELT, 2, -5, 10, 3, 0, false)), w.regionen());
        // Dieselbe Region noch einmal bleibt eine; ein Wegpunkt daneben bekommt die nächste Farbe.
        w.setze(WELT, 2, 3, 10, -5);
        w.setze(WELT, 0, 0);
        assertEquals(1, w.regionen().size());
        assertEquals(1, w.punkte().getFirst().farbe());
        // Der Block in der Ecke gehört dazu, der daneben nicht; eine andere Dimension nie.
        assertEquals(w.regionen().getFirst(), w.region(WELT, 10, 3));
        assertEquals(null, w.region(WELT, 11, 3));
        assertEquals(null, w.region("minecraft:the_nether", 5, 0));
        // Überlappen zwei, gilt die zuletzt gesetzte.
        w.setze(WELT, 4, 0, 6, 1);
        assertEquals(new Wegpunkte.Region(WELT, 4, 0, 6, 1, 2, false), w.region(WELT, 5, 0));

        Wegpunkte nachher = new Wegpunkte();
        nachher.lies(ordner);
        assertEquals(w.regionen(), nachher.regionen());
        nachher.loesche(nachher.regionen().getFirst());
        assertEquals(List.of(new Wegpunkte.Region(WELT, 4, 0, 6, 1, 2, false)), nachher.regionen());
    }

    @Test
    void hoechstens256Regionen() {
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        for (int i = 0; i < Wegpunkte.MAX_REGIONEN + 5; i++) {
            w.setze(WELT, i, 0, i, 0);
        }
        assertEquals(Wegpunkte.MAX_REGIONEN, w.regionen().size());
        // Auch aus der Datei nicht mehr; kaputte Einträge fallen weg.
        StringBuilder viele = new StringBuilder("{\"regionen\":[{\"dimension\":5},");
        for (int i = 0; i < Wegpunkte.MAX_REGIONEN + 5; i++) {
            viele.append(i == 0 ? "" : ",").append("{\"dimension\":\"").append(WELT).append("\",\"x0\":").append(i)
                    .append(",\"z0\":0,\"x1\":").append(i).append(",\"z1\":0,\"farbe\":3,\"minimap\":false}");
        }
        Wegpunkte gelesen = new Wegpunkte();
        gelesen.lies(JsonParser.parseString(viele.append("]}").toString()).getAsJsonObject());
        assertEquals(Wegpunkte.MAX_REGIONEN, gelesen.regionen().size());
    }

    @Test
    void ohneOrdnerNurImSpeicher() {
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        w.setze(WELT, 1, 1);
        assertEquals(1, w.punkte().size());
    }

    @Test
    void wechselLiestNurEinenAnderenOrdner(@TempDir Path wurzel) throws Exception {
        Wegpunkte w = new Wegpunkte();
        w.wechsel(wurzel.resolve("welt-01"));
        w.setze(WELT, 1, 1);
        // Die Datei ändert sich von aussen: Derselbe Ordner liest sie nicht neu, ein Wechsel hin und zurück schon.
        Files.writeString(wurzel.resolve("welt-01/wegpunkte.json"),
                "{\"wegpunkte\":[{\"dimension\":\"minecraft:overworld\",\"x\":9,\"z\":9,\"farbe\":0,\"minimap\":false}]}");
        w.wechsel(wurzel.resolve("welt-01"));
        assertEquals(1, w.punkte().getFirst().x());
        w.wechsel(wurzel.resolve("welt-02"));
        assertTrue(w.punkte().isEmpty());
        w.wechsel(wurzel.resolve("welt-01"));
        assertEquals(9, w.punkte().getFirst().x());
        // Im Einzelspieler bleibt beim Wechsel der Dimension, was nur im Speicher liegt.
        w.leeren();
        w.wechsel(null);
        w.setze(WELT, 1, 1);
        w.wechsel(null);
        assertEquals(1, w.punkte().size());
    }

    @Test
    void kaputteEintraegeFallenWeg() {
        Wegpunkte w = new Wegpunkte();
        w.lies(JsonParser.parseString("""
                {"wegpunkte":[
                  {"dimension":"minecraft:overworld","x":1,"z":2,"farbe":99,"minimap":false},
                  {"dimension":"minecraft:overworld","x":"eins","z":2,"farbe":0,"minimap":false},
                  {"x":1,"z":2},
                  42],
                 "spieler":["keine-uuid","00000000-0000-0000-0000-000000000001"]}
                """).getAsJsonObject());
        // Die Farbe läuft in die Liste der Farben.
        assertEquals(List.of(new Wegpunkte.Punkt(WELT, 1, 2, 99 % Wegpunkte.FARBEN.length, false)), w.punkte());
        assertTrue(w.angeheftet(SAM));
    }

    @Test
    void unlesbareDateiBleibtGesichert(@TempDir Path ordner) throws Exception {
        Files.writeString(ordner.resolve("wegpunkte.json"), "{kaputt");
        Wegpunkte w = new Wegpunkte();
        w.lies(ordner);
        assertTrue(w.punkte().isEmpty());
        // Die nächste Änderung schreibt eine neue Datei; die alte liegt daneben.
        w.setze(WELT, 1, 1);
        assertEquals("{kaputt", Files.readString(ordner.resolve("wegpunkte.json.kaputt")));
        Wegpunkte neu = new Wegpunkte();
        neu.lies(ordner);
        assertEquals(1, neu.punkte().size());
    }

    @Test
    void farbenBleibenVerschieden() {
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        w.setze(WELT, 1, 1);
        w.setze(WELT, 2, 2);
        w.setze(WELT, 3, 3);
        w.loesche(w.punkte().getFirst());
        w.setze(WELT, 4, 4);
        // Ein Wegpunkt einer anderen Dimension nimmt keiner Farbe den Platz.
        w.setze("minecraft:the_nether", 5, 5);
        assertEquals(java.util.Set.of(0, 1, 2), w.punkte().stream().filter(p -> p.dimension().equals(WELT))
                .map(Wegpunkte.Punkt::farbe).collect(java.util.stream.Collectors.toSet()));
        assertEquals(0, w.punkte().getLast().farbe());
    }
}
