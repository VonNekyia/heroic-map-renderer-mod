package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/** WebP mit TwelveMonkeys lesen. Siehe docs/vollbildkarte.md, „Kacheln“. */
class KachelnTest {

    private static byte[] kachel() throws IOException {
        try (InputStream rein = KachelnTest.class.getResourceAsStream("/kachel.webp")) {
            return rein.readAllBytes();
        }
    }

    @Test
    void verlustfreiesWebp() throws Exception {
        Kacheln.Bild bild = Kacheln.dekodiere(kachel(), 2);
        assertEquals(2, bild.breite());
        assertEquals(2, bild.hoehe());
        assertArrayEquals(new int[] {0xFFFF0000, 0x8000FF00, 0xFF0000FF, 0xFF0A141E}, bild.argb());
    }

    @Test
    void groesseAusDemKopfVorDemDekodieren() throws Exception {
        // Eine andere Grösse als tileSize gilt nicht.
        assertThrows(IOException.class, () -> Kacheln.dekodiere(kachel(), 256));
        // Kleine Datei, Kopf 16384 × 16384: dekodiert wären das 1 GiB. Abgelehnt wird nach dem Kopf.
        byte[] webp = kachel();
        ByteBuffer puffer = ByteBuffer.wrap(webp).order(ByteOrder.LITTLE_ENDIAN);
        // VP8L: Signatur an Byte 20, dahinter je 14 Bit Breite − 1 und Höhe − 1.
        assertEquals(0x2F, webp[20]);
        puffer.putInt(21, (puffer.getInt(21) & 0xF000_0000) | (0x3FFF << 14) | 0x3FFF);
        IOException fehler = assertThrows(IOException.class, () -> Kacheln.dekodiere(webp, 256));
        assertTrue(fehler.getMessage().startsWith("Grösse 16384 × 16384"), fehler.getMessage());
    }
}
