package com.nekyia.heroicmap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Das Relief der Kacheln vom Server: aus Kachel, Schicht und Rest jede der drei Arten, genau nach der Regel des Renderers in
 * f32. Ohne Minecraft. Siehe docs/vollbildkarte.md, „Relief“.
 */
final class Relief {

    /** Die drei Arten, mit dem Namen wie in {@code map.json} und in den Einstellungen. */
    enum Art {
        OHNE("ohne"), SLOPE("slope"), SHADOWS("shadows");

        final String name;

        Art(String name) {
            this.name = name;
        }

        /** Die Art zu einem Namen, sonst {@code sonst}. */
        static Art von(String name, Art sonst) {
            for (Art a : values()) {
                if (a.name.equals(name)) {
                    return a;
                }
            }
            return sonst;
        }
    }

    /** Das einzige Muster der Schicht, das der Mod kennt; ein neues Format bekäme ein neues Feld in {@code map.json}. */
    static final String SCHICHT = "relief/{z}/{x}/{y}.webp";

    /**
     * Was ein Satz mitbringt: die Art in seinen Kacheln, ob seine Schicht im bekannten Format daliegt, und ob er die eigene Karte
     * ist, deren Schicht der Mod selbst als PNG schreibt.
     */
    record Satzrelief(Art gebacken, boolean schicht, boolean selbst) {

        /** Muss der Mod rechnen, um {@code gewaehlt} zu zeigen? Nur mit der Schicht und einer anderen Art als gebacken. */
        boolean noetig(Art gewaehlt) {
            return schicht && gewaehlt != gebacken;
        }
    }

    private Relief() {
    }

    /**
     * Liest {@code relief} und {@code reliefTiles} aus {@code map.json} im Ordner des Massstabs; ohne lesbare Datei kein
     * Relief. Siehe docs/vollbildkarte.md, „Relief“.
     */
    static Satzrelief von(Path ordner) {
        if (Selbst.selbst(ordner.getParent())) {
            // Kacheln ohne Relief, die Schicht daneben. Siehe docs/selbst.md, „Relief“.
            return new Satzrelief(Art.OHNE, true, true);
        }
        try {
            JsonObject karte = JsonParser.parseString(Files.readString(ordner.resolve("map.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            Art gebacken = karte.has("relief") ? Art.von(karte.get("relief").getAsString(), null) : Art.OHNE;
            if (gebacken == null || gebacken == Art.OHNE) {
                return new Satzrelief(Art.OHNE, false, false);
            }
            return new Satzrelief(gebacken, karte.has("reliefTiles") && SCHICHT.equals(karte.get("reliefTiles").getAsString()), false);
        } catch (IOException | RuntimeException e) {
            return new Satzrelief(Art.OHNE, false, false);
        }
    }

    /**
     * Der Faktor einer Art bei einem Code der Schicht: 1 + 0,12 · (n / 2) mit n = (code mod 9) − 4, begrenzt auf 0,76 bis
     * 1,24, mit {@code SHADOWS} ab Code 9 mal 0,75; ohne Relief 1. Ein Code über 17 gilt wie 4. In derselben Folge wie der
     * Renderer, so trifft das letzte Bit.
     */
    static float faktor(int code, Art art) {
        if (art == Art.OHNE) {
            return 1;
        }
        int c = code > 17 ? 4 : code;
        float f = Math.clamp(1f + 0.12f * ((c % 9 - 4) / 2f), 1f - 0.24f, 1f + 0.24f);
        return art == Art.SHADOWS && c >= 9 ? f * 0.75f : f;
    }

    /**
     * Rechnet die Kachel {@code kachel} (ARGB, Art {@code gebacken}) in die Art {@code gewaehlt} um, an Ort und Stelle: je
     * Pixel mit Alpha über 0 die Farbe ohne Relief aus dem Rest, wo er Alpha 255 hat, sonst je Kanal round(k / f) mit dem
     * Faktor der Kachel; dann mal den Faktor der gewählten Art, gerundet und bei 255 gekappt. Alpha bleibt. Der Code steht
     * in Rot der Schicht; ohne Schicht und wo sie durchsichtig ist gilt 4, ohne Rest keiner.
     */
    static void rechne(int[] kachel, int[] schicht, int[] rest, Art gebacken, Art gewaehlt) {
        // Je Code einmal jeder Wert eines Kanals der Kachel, und jeder Wert ohne Relief für den Rest: dieselbe Rechnung, nur
        // nicht je Pixel.
        int[][] ausKachel = new int[18][256], ausRest = new int[18][256];
        for (int c = 0; c < 18; c++) {
            float fk = faktor(c, gebacken), fg = faktor(c, gewaehlt);
            for (int v = 0; v < 256; v++) {
                ausKachel[c][v] = Math.min(255, Math.round(Math.round(v / fk) * fg));
                ausRest[c][v] = Math.min(255, Math.round(v * fg));
            }
        }
        for (int p = 0; p < kachel.length; p++) {
            int argb = kachel[p];
            if (argb >>> 24 == 0) {
                continue;
            }
            // Durchsichtig nur in der Schicht der eigenen Karte, wo ein Chunk ohne Relief gezeichnet ist.
            int code = schicht == null || schicht[p] >>> 24 == 0 ? 4 : schicht[p] >> 16 & 0xFF;
            code = code > 17 ? 4 : code;
            boolean mitRest = rest != null && rest[p] >>> 24 == 255;
            int[] tafel = mitRest ? ausRest[code] : ausKachel[code];
            int quelle = mitRest ? rest[p] : argb;
            kachel[p] = argb & 0xFF000000 | tafel[quelle >> 16 & 0xFF] << 16 | tafel[quelle >> 8 & 0xFF] << 8 | tafel[quelle & 0xFF];
        }
    }

    /** Hat die Kachel einen sichtbaren Pixel mit einem Kanal 255? Nur dort kann das Relief abgeschnitten haben. */
    static boolean abgeschnitten(int[] kachel) {
        for (int argb : kachel) {
            if (argb >>> 24 != 0 && ((argb >> 16 & 0xFF) == 255 || (argb >> 8 & 0xFF) == 255 || (argb & 0xFF) == 255)) {
                return true;
            }
        }
        return false;
    }

    /** Eine fehlende Spalte in {@link Hoehen}. */
    static final int FEHLT = Integer.MIN_VALUE;
    /** So weit sucht der Schatten diagonal nach Nordwesten, in Blöcken. */
    static final int REICHWEITE = 32;
    /** Die Seite der Höhen um einen Chunk: 32 Blöcke nach Nordwesten, der Chunk, 1 Block nach Osten und Süden. */
    static final int SEITE = REICHWEITE + 17;

    /** Die Höhe der Oberfläche je Spalte der Welt, das y aus WORLD_SURFACE, oder {@link #FEHLT}. */
    interface Hoehen {
        int an(int x, int z);
    }

    /**
     * Der Code der Spalte (x, z) wie in der Schicht des Renderers: n + 4 + 9 · s mit n = hO − hW + hS − hN, begrenzt auf −4
     * bis 4, und s = 1, wenn k Blöcke diagonal nach Nordwesten eine Spalte um mehr als k · √2 höher ist, für ein k bis 32.
     * Fehlt ein Nachbar, gilt er als gleich hoch; fehlt die Spalte selbst, 4. Siehe renderer docs/renderer/relief.md, „Die
     * Schicht“, und docs/minimap.md, „Relief“.
     */
    static int code(Hoehen h, int x, int z) {
        int h0 = h.an(x, z);
        if (h0 == FEHLT) {
            return 4;
        }
        int w = h.an(x - 1, z), o = h.an(x + 1, z), n = h.an(x, z - 1), s = h.an(x, z + 1);
        int d = (o == FEHLT ? h0 : o) - (w == FEHLT ? h0 : w) + (s == FEHLT ? h0 : s) - (n == FEHLT ? h0 : n);
        return Math.clamp(d, -4, 4) + 4 + (imSchatten(h, x, z, h0) ? 9 : 0);
    }

    private static boolean imSchatten(Hoehen h, int x, int z, int h0) {
        for (int k = 1; k <= REICHWEITE; k++) {
            int hk = h.an(x - k, z - k);
            // In double wie im Renderer.
            if (hk != FEHLT && (double) (hk - h0) > k * Math.sqrt(2)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Die Codes der 256 Spalten eines Chunks, Zeile für Zeile nach z, aus den Höhen um ihn, {@link #SEITE} im Quadrat ab 32
     * Blöcken nordwestlich seiner Ecke.
     */
    static byte[] codes(int[] hoehen) {
        Hoehen h = (x, z) -> x < 0 || z < 0 || x >= SEITE || z >= SEITE ? FEHLT : hoehen[z * SEITE + x];
        byte[] codes = new byte[256];
        for (int i = 0; i < 256; i++) {
            codes[i] = (byte) code(h, REICHWEITE + (i & 15), REICHWEITE + (i >> 4));
        }
        return codes;
    }

    /** Aus den Höhen um einen Chunk ({@link #SEITE} im Quadrat) seine eigenen 16 × 16, Zeile für Zeile nach z. */
    static int[] eigene(int[] hoehen) {
        int[] eigene = new int[256];
        for (int z = 0; z < 16; z++) {
            System.arraycopy(hoehen, (REICHWEITE + z) * SEITE + REICHWEITE, eigene, z * 16, 16);
        }
        return eigene;
    }

    /**
     * Welche der 4 × 4 Chunks im Fenster um einen Chunk fehlten, aus seinen Höhen ({@link #SEITE} im Quadrat): je Chunk mit dem
     * Abstand dx und dz von −2 bis 1 das Bit (dz + 2) · 4 + dx + 2. Ein geladener Chunk hat an jeder Spalte eine Höhe.
     */
    static int fehlende(int[] hoehen) {
        int maske = 0;
        for (int dz = -2; dz <= 1; dz++) {
            for (int dx = -2; dx <= 1; dx++) {
                if (hoehen[(dz + 2) * 16 * SEITE + (dx + 2) * 16] == FEHLT) {
                    maske |= 1 << (dz + 2) * 4 + dx + 2;
                }
            }
        }
        return maske;
    }

    /** Fehlte in {@code maske} ({@link #fehlende}) der Chunk mit dem Abstand dx, dz? */
    static boolean fehlte(int maske, int dx, int dz) {
        return (maske >> (dz + 2) * 4 + dx + 2 & 1) != 0;
    }

    /**
     * Die Schicht eines Chunks wie die des Renderers aus seinen 256 Codes: je Block {@code scale} × {@code scale} Pixel grau mit
     * dem Code in Rot, Grün und Blau, Alpha 255.
     */
    static int[] schicht(byte[] codes, int scale) {
        int seite = 16 * scale;
        int[] pixel = new int[seite * seite];
        for (int p = 0; p < pixel.length; p++) {
            pixel[p] = 0xFF000000 | 0x010101 * codes[p % seite / scale * 16 + p / seite / scale];
        }
        return pixel;
    }

    /**
     * Legt die Art {@code art} auf das Bild eines Chunks ohne Relief, je Block {@code scale} × {@code scale} Pixel mit dem Code
     * seiner Spalte: je Kanal gerundet und bei 255 gekappt, Alpha bleibt, wie der Renderer auf eine Kachel.
     */
    static void auf(int[] pixel, int scale, byte[] codes, Art art) {
        int seite = 16 * scale;
        for (int i = 0; i < 256; i++) {
            float f = faktor(codes[i], art);
            if (f == 1) {
                continue;
            }
            int bx = (i & 15) * scale, bz = (i >> 4) * scale;
            for (int y = bz; y < bz + scale; y++) {
                for (int x = bx; x < bx + scale; x++) {
                    int p = y * seite + x, argb = pixel[p];
                    if (argb >>> 24 == 0) {
                        continue;
                    }
                    int neu = argb & 0xFF000000;
                    for (int s = 16; s >= 0; s -= 8) {
                        neu |= Math.min(255, Math.round((argb >> s & 0xFF) * f)) << s;
                    }
                    pixel[p] = neu;
                }
            }
        }
    }
}
