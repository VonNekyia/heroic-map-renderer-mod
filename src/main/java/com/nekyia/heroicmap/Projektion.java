package com.nekyia.heroicmap;

/**
 * Wo ein Punkt der Welt auf der Karte liegt: genordet von oben wie {@code top-north}
 * des Renderers, x nach rechts, z nach unten, die Höhe zählt nicht.
 * Siehe docs/projektion.md.
 */
public final class Projektion {

    private Projektion() {
    }

    /** Pixel zu einer Koordinate x oder z der Welt bei {@code scale} Pixeln je Block. */
    public static double zuPixel(double welt, int scale) {
        return welt * scale;
    }
}
