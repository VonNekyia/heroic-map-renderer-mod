package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Die Live-Ebene auf der Platte und in den Kacheln. Siehe docs/live.md. */
class EbeneTest {

    @TempDir
    Path baum;

    private static int[] voll(int argb, int seite) {
        int[] bild = new int[seite * seite];
        Arrays.fill(bild, argb);
        return bild;
    }

    @Test
    void schreibenUndLesen() throws Exception {
        Path ordner = Ebene.ordner(baum);
        int[] bild = voll(0x80FF0000, 64);
        bild[0] = 0;
        bild[1] = 0xFF00FF00;
        Ebene.schreibe(ordner, -3, 7, bild, 64, 1_000_000_000_000L);
        assertArrayEquals(bild, Ebene.lies(ordner, -3, 7, 64));
        assertEquals(1_000_000_000_000L, Files.getLastModifiedTime(ordner.resolve("-3.7.png")).toMillis());
        // Ein Bild in falscher Grösse gilt nicht.
        assertNull(Ebene.lies(ordner, -3, 7, 32));
        Files.writeString(ordner.resolve("kein.png"), "x");
        Files.writeString(ordner.resolve("1.2.txt"), "x");
        assertEquals(Set.of(Ebene.schluessel(-3, 7)), Ebene.liste(ordner));
        assertEquals(-3, Ebene.cx(Ebene.schluessel(-3, 7)));
        assertEquals(7, Ebene.cz(Ebene.schluessel(-3, 7)));
    }

    @Test
    void abgleichRaeumtWasDieKachelnEnthalten() throws Exception {
        Path ordner = Ebene.ordner(baum);
        Ebene.schreibe(ordner, 0, 0, voll(0xFF000000, 16), 16, 1_000_000L);
        Ebene.schreibe(ordner, 1, 0, voll(0xFF000000, 16), 16, 2_000_000L);
        assertEquals(1, Ebene.raeume(ordner, 2_000_000L));
        assertEquals(Set.of(Ebene.schluessel(1, 0)), Ebene.liste(ordner));
    }

    @Test
    void wechselDesMassstabs() throws Exception {
        Path ordner = Ebene.ordner(baum);
        Ebene.schreibe(ordner, 0, 0, voll(0xFF123456, 64), 64, 5_000_000L);
        // Gröber: verkleinert, mit derselben Zeit.
        Ebene.wechsle(ordner, 64, 16);
        assertArrayEquals(voll(0xFF123456, 16), Ebene.lies(ordner, 0, 0, 16));
        assertEquals(5_000_000L, Files.getLastModifiedTime(ordner.resolve("0.0.png")).toMillis());
        // Feiner: weg.
        Ebene.wechsle(ordner, 16, 32);
        assertTrue(Ebene.liste(ordner).isEmpty());
    }

    @Test
    void inDieKachelGelegt() throws Exception {
        Path ordner = Ebene.ordner(baum);
        // 4 px je Block auf der feinsten Stufe 6: ein Chunk 64 Pixel, eine Kachel 256 fasst 4 × 4.
        Ebene.schreibe(ordner, 1, 2, voll(0xFF00FF00, 64), 64, 0);
        Ebene.schreibe(ordner, 5, 0, voll(0xFF0000FF, 64), 64, 0);
        Set<Long> chunks = Ebene.liste(ordner);

        int[] kachel = voll(0xFFFFFFFF, 256);
        assertSame(kachel, Ebene.lege(kachel, 256, 6, 0, 0, 6, 64, chunks, ordner));
        assertEquals(0xFF00FF00, kachel[128 * 256 + 64]);
        assertEquals(0xFF00FF00, kachel[191 * 256 + 127]);
        assertEquals(0xFFFFFFFF, kachel[127 * 256 + 64]);
        assertEquals(0xFFFFFFFF, kachel[128 * 256 + 128]);

        // Eine Stufe gröber ist der Chunk 32 breit, Chunk 5 liegt in derselben Kachel bei x 160.
        int[] grob = Ebene.lege(null, 256, 5, 0, 0, 6, 64, chunks, ordner);
        assertEquals(0xFF00FF00, grob[64 * 256 + 32]);
        assertEquals(0xFF0000FF, grob[0 * 256 + 160]);
        assertEquals(0, grob[0]);

        // Ohne Bild in der Kachel bleibt sie, wie sie ist, auch null.
        assertNull(Ebene.lege(null, 256, 6, 9, 9, 6, 64, chunks, ordner));
        // Sechs Stufen gröber ist ein Chunk noch 1 Pixel, darunter bleibt die Kachel des Servers.
        assertEquals(0xFF00FF00, Ebene.lege(null, 256, 0, 0, 0, 6, 64, chunks, ordner)[2 * 256 + 1]);
        assertNull(Ebene.lege(null, 256, 1, 0, 0, 8, 64, chunks, ordner));
        // Feiner als die feinste Stufe gibt es nicht.
        assertNull(Ebene.lege(null, 256, 7, 0, 0, 6, 64, chunks, ordner));
        assertFalse(Files.exists(baum.resolve("0")));
    }
}
