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
