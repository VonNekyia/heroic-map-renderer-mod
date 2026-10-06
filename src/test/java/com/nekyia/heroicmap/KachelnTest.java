package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** WebP mit TwelveMonkeys lesen. Siehe docs/vollbildkarte.md, „Kacheln“. */
class KachelnTest {

    @Test
    void verlustfreiesWebp() throws Exception {
        byte[] webp;
        try (InputStream rein = KachelnTest.class.getResourceAsStream("/kachel.webp")) {
            webp = rein.readAllBytes();
        }
        Kacheln.Bild bild = Kacheln.dekodiere(webp);
        assertEquals(2, bild.breite());
        assertEquals(2, bild.hoehe());
        assertArrayEquals(new int[] {0xFFFF0000, 0x8000FF00, 0xFF0000FF, 0xFF0A141E}, bild.argb());
    }
}
