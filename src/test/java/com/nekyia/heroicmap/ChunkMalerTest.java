package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.junit.jupiter.api.Test;

/**
 * Dünne Flächen mindestens ein Pixel, und der Deckel einer Truhe in ihrer Textur. Siehe docs/minimap.md, „Flächen und Pixel“,
 * und docs/minimap.md, „Blockentities“.
 */
class ChunkMalerTest {

    private static float[] rechteck(float x0, float z0, float x1, float z1) {
        return new float[] {x0, z0, x0, z1, x1, z1, x1, z0};
    }

    @Test
    void duenneFlaecheMindestensEinPixel() {
        // Die Oberseite einer Tür am Nordrand, 3/16 tief: bei 2 px trifft sie keine Mitte (1/4, 3/4), dann die Reihe ihrer Mitte.
        float[] tuer = rechteck(0, 0, 1, 3 / 16f);
        assertNull(ChunkMaler.duenn(tuer, 0, 2));
        assertArrayEquals(new float[] {0, 3 / 32f}, ChunkMaler.duenn(tuer, 1, 2));
        // Bei 4 px liegt die Mitte 1/8 darin, nichts zu tun; bei 1 px auch nicht, die Mitte 1/2 liegt nicht darin, also Reihe 0.
        assertNull(ChunkMaler.duenn(tuer, 1, 4));
        assertArrayEquals(new float[] {0, 3 / 32f}, ChunkMaler.duenn(tuer, 1, 1));
        // Eine Fackel in der Mitte, 2/16 breit: bei 2 px keine Mitte, die Reihe 1, in der ihre Mitte 1/2 liegt.
        float[] fackel = rechteck(7 / 16f, 7 / 16f, 9 / 16f, 9 / 16f);
        assertArrayEquals(new float[] {1, 0.5f}, ChunkMaler.duenn(fackel, 0, 2));
        // Eine volle Oberseite ist nie dünn.
        assertNull(ChunkMaler.duenn(rechteck(0, 0, 1, 1), 0, 16));
    }

    @Test
    void deckelNachFacingUndHaelfte() {
        float[] deckel = rechteck(1 / 16f, 1 / 16f, 15 / 16f, 15 / 16f);
        // Nach Süden: x 1..15 auf u 28..42, z 1 auf v 14 und z 15 auf v 0.
        assertArrayEquals(new float[] {28, 14, 28, 0, 42, 0, 42, 14}, ChunkMaler.deckel(Direction.SOUTH, ChestType.SINGLE, deckel), 1e-4f);
        // Nach Norden um 180 gedreht: jede Ecke liegt gegenüber.
        assertArrayEquals(new float[] {42, 0, 42, 14, 28, 14, 28, 0}, ChunkMaler.deckel(Direction.NORTH, ChestType.SINGLE, deckel), 1e-4f);
        // Nach Osten (toYRot 270): Die Front, im Modell z 15 und v 0, zeigt nach +x, das Modell-x 15, u 42, nach Norden;
        // die Ecke Nordost (x 15, z 1) liegt also bei u 42, v 0.
        float[] osten = ChunkMaler.deckel(Direction.EAST, ChestType.SINGLE, deckel);
        assertArrayEquals(new float[] {42, 0}, new float[] {osten[6], osten[7]}, 1e-4f);
        // Die Hälften einer Doppeltruhe nach Süden: links x 0..15 auf u 29..44, rechts x 1..16 auf u 29..44.
        assertArrayEquals(new float[] {29, 14, 29, 0, 44, 0, 44, 14},
                ChunkMaler.deckel(Direction.SOUTH, ChestType.LEFT, rechteck(0, 1 / 16f, 15 / 16f, 15 / 16f)), 1e-4f);
        assertArrayEquals(new float[] {29, 14, 29, 0, 44, 0, 44, 14},
                ChunkMaler.deckel(Direction.SOUTH, ChestType.RIGHT, rechteck(1 / 16f, 1 / 16f, 1, 15 / 16f)), 1e-4f);
    }
}
