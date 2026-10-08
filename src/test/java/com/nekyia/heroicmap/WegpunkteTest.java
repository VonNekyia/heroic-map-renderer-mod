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
        assertEquals(List.of(new Wegpunkte.Punkt(WELT, 12, -40, 0, true), new Wegpunkte.Punkt("minecraft:the_nether", 1, 2, 1, false)),
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
    void ohneOrdnerNurImSpeicher() {
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        w.setze(WELT, 1, 1);
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
