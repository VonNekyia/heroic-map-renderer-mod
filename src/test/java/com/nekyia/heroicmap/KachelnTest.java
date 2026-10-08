package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.junit.jupiter.api.Test;

/** WebP mit TwelveMonkeys lesen. Siehe docs/vollbildkarte.md, „Kacheln“. */
class KachelnTest {

    private static byte[] kachel() throws IOException {
        return datei("/kachel.webp");
    }

    private static byte[] datei(String name) throws IOException {
        try (InputStream rein = KachelnTest.class.getResourceAsStream(name)) {
            return rein.readAllBytes();
        }
    }

    /** Wie TwelveMonkeys selbst liest, ohne die Kopie in {@code webp/}. */
    private static int[] twelveMonkeys(byte[] webp) throws IOException {
        ImageReader leser = new WebPImageReaderSpi().createReaderInstance();
        try (MemoryCacheImageInputStream rein = new MemoryCacheImageInputStream(new ByteArrayInputStream(webp))) {
            leser.setInput(rein);
            BufferedImage bild = leser.read(0);
            return bild.getRGB(0, 0, bild.getWidth(), bild.getHeight(), null, 0, bild.getWidth());
        } finally {
            leser.dispose();
        }
    }

    /** Die Pixel von {@code farbindex.webp}, wie farbindex.py sie malt: Schachbrett aus Schwarz, Alpha 0, und Grau. */
    private static int[] farbindex() {
        int[] argb = new int[8 * 8];
        for (int i = 0; i < argb.length; i++) {
            int v = 8 * (1 + i / 2 % 31);
            argb[i] = (i % 8 + i / 8) % 2 == 0 ? 0 : 0xFF000000 | v * 0x010101;
        }
        return argb;
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

    @Test
    void indexHinterDerPaletteIstDurchsichtig() throws Exception {
        // libwebp lässt das durchsichtige Schwarz als letzten Eintrag der Palette weg; die Pixel zeigen dahinter.
        byte[] webp = datei("/farbindex.webp");
        assertArrayEquals(farbindex(), Kacheln.dekodiere(webp, 8).argb());
        assertFalse(Arrays.equals(farbindex(), twelveMonkeys(webp)),
                "TwelveMonkeys liest farbindex.webp richtig: Die Kopie in webp/ kann weg, siehe docs/vollbildkarte.md");
    }

    @Test
    void sonstWieTwelveMonkeys() throws Exception {
        // Ohne Index hinter der Palette liest die Kopie jede Kachel genau wie TwelveMonkeys.
        assertArrayEquals(twelveMonkeys(kachel()), Kacheln.dekodiere(kachel(), 2).argb());
        List<Path> satz;
        try (Stream<Path> dateien = Files.walk(Path.of("src/gametest/resources/satz"))) {
            satz = dateien.filter(p -> p.toString().endsWith(".webp")).toList();
        }
        assertEquals(24, satz.size());
        for (Path p : satz) {
            byte[] webp = Files.readAllBytes(p);
            assertArrayEquals(twelveMonkeys(webp), Kacheln.dekodiere(webp, 256).argb(), p.toString());
        }
    }
}
