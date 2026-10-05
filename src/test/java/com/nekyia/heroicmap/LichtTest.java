package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Die Lightmap gegen die Werte, die die Doku des Renderers nennt: „Helligkeit wie im Spiel“
 * und „Tiefe über das Licht“ in docs/renderer/wasser-und-licht.md. Siehe docs/minimap.md, „Licht“.
 */
class LichtTest {

    /** Die Oberwelt aus der Tabelle in docs/renderer/dimensionstypen.md. */
    private static final float[] UMGEBUNG = grau(0x0a), HIMMEL = grau(0xff);
    private static final float[] BLOCK_TINT = {1, 0xd8 / 255f, 0x8c / 255f};

    @Test
    void unterFreiemHimmelGanzHell() {
        assertArrayEquals(new float[] {1, 1, 1}, Licht.hell(UMGEBUNG, HIMMEL, 1, BLOCK_TINT, 15, 0), 1e-6f);
    }

    @Test
    void grundUnterWasserWieInDerTabelle() {
        // Licht des Grunds bei 1, 2, 3, 5, 10 und ab 15 Blöcken Tiefe, und wie viel von ihm
        // durch Wasser mit Alpha 180 zu sehen ist, in Prozent.
        int[] licht = {14, 13, 12, 10, 5, 0};
        int[] prozent = {27, 24, 22, 18, 9, 3};
        for (int i = 0; i < licht.length; i++) {
            float b = Licht.hell(UMGEBUNG, HIMMEL, 1, BLOCK_TINT, licht[i], 0)[0];
            assertEquals(prozent[i], Math.round(100 * (1 - 180 / 255f) * b), "Licht " + licht[i]);
        }
    }

    @Test
    void zwischenDenStufenLinear() {
        Licht oberwelt = new Licht(UMGEBUNG, HIMMEL, 1, BLOCK_TINT);
        float[] mitte = new float[3];
        // Himmelslicht 14,5 in Sechzehnteln, ohne Blocklicht.
        oberwelt.hell(232 << 16, mitte);
        float b14 = Licht.hell(UMGEBUNG, HIMMEL, 1, BLOCK_TINT, 14, 0)[0];
        assertEquals((b14 + 1) / 2, mitte[0], 1e-6f);
    }

    private static float[] grau(int wert) {
        return new float[] {wert / 255f, wert / 255f, wert / 255f};
    }
}
