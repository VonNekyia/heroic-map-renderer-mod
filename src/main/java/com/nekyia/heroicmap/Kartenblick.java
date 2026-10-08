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
     * auf ganzen Einheiten ({@code Mth.floor} in {@code Karte}). Jede Kachel ist ganze Einheiten
     * breit, also rücken alle um denselben Rest nach links; so steht eine Marke immer auf
     * demselben Fleck der Karte.
     */
    double rasterX(double basisX, int breite) {
        return schirmX(basisX, breite) - rest(schirmX(0, breite));
    }

    double rasterY(double basisZ, int hoehe) {
        return schirmY(basisZ, hoehe) - rest(schirmY(0, hoehe));
    }

    /** Die Umkehrung von {@link #rasterX}: der Pixel der Basis, den die Karte unter dem Schirm-x zeichnet. */
    double basisRasterX(double schirmX, int breite) {
        return basisX(schirmX + rest(schirmX(0, breite)), breite);
    }

    double basisRasterZ(double schirmY, int hoehe) {
        return basisZ(schirmY + rest(schirmY(0, hoehe)), hoehe);
    }

    private static double rest(double schirm) {
        return schirm - Math.floor(schirm);
    }

    /**
     * Wo der Name über einer Marke beginnt, links und oben: mittig über ihr, aber ganz auf dem
     * Schirm; reicht er unter die Knöpfe rechts oben, ab x {@code knopfX} bis y {@code knopfUnten},
     * steht er links daneben. Siehe docs/wegpunkte.md, „Am Rand“.
     */
    static int[] name(double x, double oben, int breite, int textBreite, int knopfX, int knopfUnten) {
        int nx = Math.max(2, Math.min((int) Math.round(x - textBreite / 2.0), breite - textBreite - 2));
        int ny = Math.max(2, (int) Math.floor(oben));
        if (nx + textBreite > knopfX && ny < knopfUnten) {
            nx = Math.max(2, knopfX - textBreite - 2);
        }
        return new int[] {nx, ny};
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
