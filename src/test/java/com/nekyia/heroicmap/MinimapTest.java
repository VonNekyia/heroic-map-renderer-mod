package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Der Bereich, den die Minimap zeichnet, ihre Form, Lage und Einstellungen. Siehe docs/minimap.md, „Neu zeichnen“ und „Bedienung“. */
class MinimapTest {

    @Test
    void reichweiteJeMassstab() {
        // Sichtbar sind 64 Einheiten je Richtung: ±4, ±2 und ±1 Chunks, dazu 2 Chunks Vorrat.
        assertEquals(6, Minimap.reichweite(1, 128));
        assertEquals(4, Minimap.reichweite(2, 128));
        assertEquals(3, Minimap.reichweite(4, 128));
        assertEquals(10, Minimap.reichweite(1, 256));
    }

    @Test
    void markiereNurImBereich() {
        Minimap minimap = new Minimap();
        minimap.mitte = new ChunkPos(10, -20);
        int r = minimap.reichweite();

        minimap.markiere(10 + r, -20 - r);
        minimap.markiere(10 + r + 1, -20);
        minimap.markiere(10, -20 - r - 1);

        assertTrue(minimap.offen.contains(ChunkPos.pack(10 + r, -20 - r)));
        assertFalse(minimap.offen.contains(ChunkPos.pack(10 + r + 1, -20)));
        assertFalse(minimap.offen.contains(ChunkPos.pack(10, -20 - r - 1)));
        assertEquals(1, minimap.offen.size());
    }

    @Test
    void ohneMitteNimmtSieAllesAuf() {
        Minimap minimap = new Minimap();
        minimap.markiere(1000, 1000);
        assertTrue(minimap.offen.contains(ChunkPos.pack(1000, 1000)));
    }

    @Test
    void eckigIstEinLauf() {
        List<int[]> laeufe = Minimap.laeufe(384, false);
        assertEquals(1, laeufe.size());
        assertArrayEquals(new int[] {0, 384, 0, 384}, laeufe.getFirst());
    }

    @Test
    void rundIstDerKreis() {
        int n = 384;
        List<int[]> laeufe = Minimap.laeufe(n, true);
        long flaeche = 0;
        int y = 0;
        for (int[] l : laeufe) {
            // Läufe schliessen lückenlos aneinander, jeder ist symmetrisch zur Mitte.
            assertEquals(y, l[0]);
            assertEquals(n, l[2] + l[3]);
            flaeche += (long) (l[1] - l[0]) * (l[3] - l[2]);
            y = l[1];
        }
        assertEquals(n, y);
        assertEquals(Math.PI * n * n / 4, flaeche, Math.PI * n * n / 4 * 0.005);
        // Gleich breite Zeilen sind zusammengefasst: etwa 1,2 Läufe je Pixel des Radius, nicht n.
        assertTrue(laeufe.size() < n * 0.7, "Läufe: " + laeufe.size());
    }

    @Test
    void rahmenNachLageUndSchirm() {
        // Vorgabe: rechts oben, 4 Einheiten vom Rand.
        assertEquals(new Minimap.Rahmen(640 - 128 - 4, 4, 128), Minimap.rahmen(640, 360, 128, 1, 0));
        assertEquals(new Minimap.Rahmen(4, 360 - 128 - 4, 128), Minimap.rahmen(640, 360, 128, 0, 1));
        // Ein kleiner Schirm kappt die Seite.
        assertEquals(200 - 8, Minimap.rahmen(300, 200, 256, 1, 0).seite());
    }

    @Test
    void verschiebenBleibtAufDemSchirm() {
        Minimap minimap = new Minimap();
        minimap.verschiebe(100, 50, 640, 360);
        assertEquals(new Minimap.Rahmen(100, 50, 128), minimap.rahmen(640, 360));
        minimap.verschiebe(-500, 9999, 640, 360);
        assertEquals(new Minimap.Rahmen(4, 360 - 128 - 4, 128), minimap.rahmen(640, 360));
        minimap.stelle(4, 4, 9999, 640, 360);
        assertEquals(Minimap.GROESSTE, minimap.rahmen(640, 360).seite());
    }

    @Test
    void einstellungenUeberstehenDenNeustart(@TempDir Path ordner) throws Exception {
        Path datei = ordner.resolve("config").resolve("heroicmap.properties");
        Minimap vorher = new Minimap();
        vorher.setzeSichtbar(false);
        vorher.setzeScale(4);
        vorher.setzeRund(true);
        vorher.stelle(20, 30, 200, 640, 360);
        vorher.schreibe(datei);

        Minimap nachher = new Minimap();
        nachher.lies(datei);
        assertFalse(nachher.sichtbar());
        assertEquals(4, nachher.scale());
        assertTrue(nachher.rund());
        assertEquals(vorher.rahmen(640, 360), nachher.rahmen(640, 360));
    }

    @Test
    void unlesbareWerteGebenDieVorgabe(@TempDir Path ordner) throws Exception {
        Path datei = ordner.resolve("heroicmap.properties");
        Files.writeString(datei, String.join("\n", "minimap=vielleicht", "massstab=3", "form=dreieckig",
                "groesse=x", "lage_x=NaN", "lage_y=7"));
        Minimap minimap = new Minimap();
        minimap.lies(datei);
        assertTrue(minimap.sichtbar());
        assertEquals(2, minimap.scale());
        assertFalse(minimap.rund());
        assertEquals(new Minimap.Rahmen(640 - 128 - 4, 360 - 128 - 4, 128), minimap.rahmen(640, 360));
    }

    @Test
    void ohneDateiDieVorgabe(@TempDir Path ordner) {
        Minimap minimap = new Minimap();
        minimap.lies(ordner.resolve("fehlt.properties"));
        assertTrue(minimap.sichtbar());
        assertEquals(new Minimap.Rahmen(640 - 128 - 4, 4, 128), minimap.rahmen(640, 360));
    }
}
