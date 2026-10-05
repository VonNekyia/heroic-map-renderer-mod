package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

/** Der Bereich, den die Minimap zeichnet. Siehe docs/minimap.md, „Neu zeichnen“. */
class MinimapTest {

    @Test
    void reichweiteJeMassstab() {
        // Sichtbar sind 64 Einheiten je Richtung: ±4, ±2 und ±1 Chunks, dazu 2 Chunks Vorrat.
        assertEquals(6, Minimap.reichweite(1));
        assertEquals(4, Minimap.reichweite(2));
        assertEquals(3, Minimap.reichweite(4));
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
}
