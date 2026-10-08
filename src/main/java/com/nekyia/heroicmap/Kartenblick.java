package com.nekyia.heroicmap;

/**
 * Wie die Vollbildkarte die Kacheln eines Satzes auf den Schirm legt: eine Stufe des Baums,
 * eine Lupe über der feinsten Stufe und die Mitte in Pixeln der Basis, der Stufe
 * {@code maxZoom}. Siehe docs/vollbildkarte.md.
 */
final class Kartenblick {

    final int kachel, minZoom, maxZoom, stufe;
    /** Die Stufe, deren Kacheln gezeichnet werden. */
    int zoom;
    /** Einheiten des GUI je Pixel der Kachel: 1, 2 oder 4, nur auf der feinsten Stufe. */
    int lupe = 1;
    /** Die Mitte des Schirms in Pixeln der Basis. */
    double mx, mz;

    /** {@code stufe} ist die feinste Stufe des Satzes. */
    Kartenblick(int kachel, int minZoom, int maxZoom, int stufe) {
        this.kachel = kachel;
        this.minZoom = minZoom;
        this.maxZoom = maxZoom;
        this.stufe = stufe;
        this.zoom = stufe;
    }

    /** Pixel der Basis je Pixel der aktuellen Stufe. */
    double teiler() {
        return Math.scalb(1.0, maxZoom - zoom);
    }

    double basisX(double schirmX, int breite) {
        return mx + (schirmX - breite / 2.0) / lupe * teiler();
    }

    double basisZ(double schirmY, int hoehe) {
        return mz + (schirmY - hoehe / 2.0) / lupe * teiler();
    }

    double schirmX(double basisX, int breite) {
        return breite / 2.0 + (basisX - mx) / teiler() * lupe;
    }

    double schirmY(double basisZ, int hoehe) {
        return hoehe / 2.0 + (basisZ - mz) / teiler() * lupe;
    }

    /**
     * Wie {@link #schirmX}, aber auf dem Raster der gezeichneten Kacheln: Deren linke Kante liegt
     * auf ganzen Einheiten ({@code Mth.floor} in {@code Karte}), so steht eine Marke immer auf
     * demselben Fleck der Karte.
     */
    double rasterX(double basisX, int breite) {
        double t = kachel * teiler(), start = Math.floor(basisX / t) * t;
        return Math.floor(schirmX(start, breite)) + (basisX - start) / teiler() * lupe;
    }

    double rasterY(double basisZ, int hoehe) {
        double t = kachel * teiler(), start = Math.floor(basisZ / t) * t;
        return Math.floor(schirmY(start, hoehe)) + (basisZ - start) / teiler() * lupe;
    }

    /**
     * Wo eine Marke an (x, y) des Schirms steht: drinnen, wo sie ist; sonst {@code rand} Einheiten
     * vom Rand des Schirms auf der Linie von der Mitte zu ihr. Dort weicht sie den Knöpfen rechts
     * oben aus, die ab x {@code knopfX} bis y {@code knopfY} reichen: oben nach links, rechts nach
     * unten. Siehe docs/wegpunkte.md, „Am Rand“.
     */
    static double[] marke(double x, double y, int breite, int hoehe, double rand, double halb, int knopfX, int knopfY) {
        double px = x - breite / 2.0, pz = y - hoehe / 2.0;
        double f = Minimap.rand(px, pz, breite / 2.0 - rand, hoehe / 2.0 - rand, false);
        if (f >= 1) {
            return new double[] {x, y};
        }
        double mx = breite / 2.0 + px * f, my = hoehe / 2.0 + pz * f;
        if (mx + halb > knopfX && my - halb < knopfY) {
            if (my - rand < 1e-6) {
                mx = knopfX - halb - 1;
            } else {
                my = knopfY + halb + 1;
            }
        }
        return new double[] {mx, my};
    }

    /** Eine Stufe feiner, auf der feinsten die Lupe grösser. */
    void naeher() {
        if (zoom < stufe) {
            zoom++;
        } else if (lupe < 4) {
            lupe *= 2;
        }
    }

    /** Eine Stufe gröber, zuerst die Lupe kleiner. */
    void ferner() {
        if (lupe > 1) {
            lupe /= 2;
        } else if (zoom > minZoom) {
            zoom--;
        }
    }

    /** Verschiebt den Inhalt um (dx, dy) Einheiten des GUI. */
    void schiebe(double dx, double dy) {
        mx -= dx / lupe * teiler();
        mz -= dy / lupe * teiler();
    }

    /**
     * Wo die Kachel (x, y) in ihrer Vorfahrin {@code k} Stufen gröber liegt: deren x und y, dann u
     * und v in Pixeln und die Seite des Ausschnitts. Null, wenn er unter 1 Pixel fiele.
     */
    static int[] grob(int x, int y, int k, int kachel) {
        int teil = kachel >> k;
        if (teil < 1) {
            return null;
        }
        int maske = (1 << k) - 1;
        return new int[] {x >> k, y >> k, (x & maske) * teil, (y & maske) * teil, teil};
    }

    /** Die sichtbaren Kacheln der aktuellen Stufe: x von, x bis, y von, y bis, jeweils eingeschlossen. */
    int[] kacheln(int breite, int hoehe) {
        double t = teiler() * kachel;
        return new int[] {
            (int) Math.floor(basisX(0, breite) / t), (int) Math.floor(basisX(breite, breite) / t),
            (int) Math.floor(basisZ(0, hoehe) / t), (int) Math.floor(basisZ(hoehe, hoehe) / t)};
    }
}
