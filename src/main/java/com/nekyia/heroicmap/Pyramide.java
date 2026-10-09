package com.nekyia.heroicmap;

/**
 * Verkleinert ein Bild auf die halbe Kante wie die Pyramide des Renderers: je 2 × 2 Pixel in
 * linearem Licht mit vormultipliziertem Alpha, links oben, rechts oben, links unten, rechts
 * unten. Siehe docs/selbst.md, „Pyramide“.
 */
final class Pyramide {

    private Pyramide() {
    }

    /** {@code argb} ist {@code seite} × {@code seite}, Zeile für Zeile; das Ergebnis halb so breit. */
    static int[] halbiere(int[] argb, int seite) {
        int halb = seite / 2;
        int[] aus = new int[halb * halb];
        for (int y = 0; y < halb; y++) {
            for (int x = 0; x < halb; x++) {
                int o = 2 * y * seite + 2 * x;
                int[] vier = {argb[o], argb[o + 1], argb[o + seite], argb[o + seite + 1]};
                float r = 0, g = 0, b = 0;
                int alpha = 0;
                for (int p : vier) {
                    int a = p >>> 24;
                    alpha += a;
                    r += ChunkMaler.LINEAR[(p >> 16) & 0xFF] * a;
                    g += ChunkMaler.LINEAR[(p >> 8) & 0xFF] * a;
                    b += ChunkMaler.LINEAR[p & 0xFF] * a;
                }
                // Ohne Deckung keine Farbe; das Pixel ist ohnehin durchsichtig.
                if (alpha == 0) {
                    continue;
                }
                aus[y * halb + x] = ((alpha + 3) / 4) << 24 | kanal(r / alpha) << 16 | kanal(g / alpha) << 8 | kanal(b / alpha);
            }
        }
        return aus;
    }

    private static int kanal(float linear) {
        return Math.clamp(Math.round(ChunkMaler.srgb(linear) * 255), 0, 255);
    }
}
