package com.nekyia.heroicmap;

/**
 * Verkleinert ein Bild auf die halbe Kante wie die Pyramide des Renderers bei top-north: je 2 × 2 genau ein Pixel, in
 * ungerader Tiefe über der gezeichneten Stufe der rechts unten, in gerader der links oben. Gemittelt würde die Karte
 * mit jeder Stufe weicher. Siehe docs/selbst.md, „Pyramide“.
 */
final class Pyramide {

    private Pyramide() {
    }

    /**
     * {@code argb} ist {@code seite} × {@code seite}, Zeile für Zeile; das Ergebnis halb so breit. {@code tiefe} ist die
     * des Ergebnisses über der gezeichneten Stufe, ab 1. Ein durchsichtiger Pixel hat keine Farbe.
     */
    static int[] halbiere(int[] argb, int seite, int tiefe) {
        int halb = seite / 2, d = tiefe % 2;
        int[] aus = new int[halb * halb];
        for (int y = 0; y < halb; y++) {
            for (int x = 0; x < halb; x++) {
                int p = argb[(2 * y + d) * seite + 2 * x + d];
                aus[y * halb + x] = p >>> 24 == 0 ? 0 : p;
            }
        }
        return aus;
    }
}
