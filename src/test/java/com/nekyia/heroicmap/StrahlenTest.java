package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Welche Wegpunkte einen Strahl bekommen und wie breit er steht. Siehe docs/wegpunkte.md, „Strahl“. */
class StrahlenTest {

    private static final String WELT = "minecraft:overworld";

    @Test
    void nurAngeheftetInDieserDimensionUndInSichtweite() {
        List<Wegpunkte.Punkt> punkte = List.of(
                new Wegpunkte.Punkt(WELT, 10, 0, 0, true),
                new Wegpunkte.Punkt(WELT, 20, 0, 1, false),
                new Wegpunkte.Punkt("minecraft:the_nether", 0, 0, 2, true),
                // Genau auf der Sichtweite zählt die Mitte des Blocks: 99,5 noch, 100,5 nicht mehr.
                new Wegpunkte.Punkt(WELT, 99, 0, 3, true),
                new Wegpunkte.Punkt(WELT, 100, 0, 4, true),
                new Wegpunkte.Punkt(WELT, -70, -70, 5, true));
        int[] aus = new int[Strahlen.MAX_STRAHLEN];
        int n = Strahlen.waehle(punkte, WELT, 0, 0, 100, aus);
        assertArrayEquals(new int[] {0, 3, 5}, Arrays.copyOf(aus, n));
    }

    @Test
    void hoechstensSovieleWieInDasFeldPassen() {
        List<Wegpunkte.Punkt> punkte = new ArrayList<>();
        for (int i = 0; i < Strahlen.MAX_STRAHLEN + 10; i++) {
            punkte.add(new Wegpunkte.Punkt(WELT, i, 0, 0, true));
        }
        int[] aus = new int[Strahlen.MAX_STRAHLEN];
        assertEquals(Strahlen.MAX_STRAHLEN, Strahlen.waehle(punkte, WELT, 0, 0, 1000, aus));
        // Die ersten der Reihe nach.
        assertEquals(Strahlen.MAX_STRAHLEN - 1, aus[Strahlen.MAX_STRAHLEN - 1]);
    }

    @Test
    void breiterInDerFerneNichtDurchsFernrohr() {
        assertEquals(1, Strahlen.breite(10, false));
        assertEquals(1, Strahlen.breite(96, false));
        assertEquals(2, Strahlen.breite(192, false));
        assertEquals(1, Strahlen.breite(192, true));
    }
}
