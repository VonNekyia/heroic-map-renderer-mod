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
     * Die Kante des Pixels 0 der Basis auf dem Schirm, ganzzahlig. Kacheln, Marken und Klicks
     * rechnen alle von ihr aus, so liegen sie auf demselben Raster, auch in Doubles.
     */
    int kanteX(int breite) {
        return (int) Math.floor(schirmX(0, breite));
    }

    int kanteZ(int hoehe) {
        return (int) Math.floor(schirmY(0, hoehe));
    }

    /** Die linke Kante der Kachel {@code tx} der aktuellen Stufe auf dem Schirm. */
    int kachelX(int tx, int breite) {
        return kanteX(breite) + tx * kachel * lupe;
    }

    int kachelY(int ty, int hoehe) {
        return kanteZ(hoehe) + ty * kachel * lupe;
    }

    /** Wie die Formen einen Punkt der Welt auf den Schirm legen: auf das Raster der Kacheln, wie die Nadeln. */
    Formen.Abbild abbild(int scale, int breite, int hoehe) {
        return (wx, wz, aus) -> {
            aus[0] = rasterX(Projektion.zuPixel(wx, scale), breite);
            aus[1] = rasterY(Projektion.zuPixel(wz, scale), hoehe);
        };
    }

    /** Wo die Karte den Pixel {@code basisX} der Basis zeichnet: von der Kante aus, wie die Kacheln. */
    double rasterX(double basisX, int breite) {
        return kanteX(breite) + basisX / teiler() * lupe;
    }

    double rasterY(double basisZ, int hoehe) {
        return kanteZ(hoehe) + basisZ / teiler() * lupe;
    }

    /** Die Umkehrung von {@link #rasterX}: der Pixel der Basis, den die Karte unter dem Schirm-x zeichnet. */
    double basisRasterX(double schirmX, int breite) {
        return (schirmX - kanteX(breite)) * teiler() / lupe;
    }

    double basisRasterZ(double schirmY, int hoehe) {
        return (schirmY - kanteZ(hoehe)) * teiler() / lupe;
    }

    /**
     * Wo der Name über einer Marke mit der Mitte (x, y) und der halben Seite {@code halb} beginnt,
     * links und oben: mittig über ihr, aber ganz auf dem Schirm. Träfe er dort die Knöpfe rechts
     * oben, ab x {@code knopfX} bis y {@code knopfUnten}, steht er unter der Marke, so bleibt er
     * bei seinem Kopf; erst wenn auch das sie träfe, links neben ihnen. Siehe docs/wegpunkte.md, „Am Rand“.
     */
    static int[] name(double x, double y, double halb, int breite, int textBreite, int knopfX, int knopfUnten) {
        int nx = Math.max(2, Math.min((int) Math.round(x - textBreite / 2.0), breite - textBreite - 2));
        int ny = Math.max(2, (int) Math.floor(y - halb) - 10);
        int unter = (int) Math.ceil(y + halb) + 2;
        if (nx + textBreite > knopfX && ny < knopfUnten) {
            if (unter >= knopfUnten) {
                ny = unter;
            } else {
                nx = Math.max(2, knopfX - textBreite - 2);
            }
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

    /** Unter diesem Abstand, in Einheiten des GUI, zeichnet die Karte keine Chunklinien. */
    static final double LINIEN_MIN = 4;
    /** Ebenso in Pixeln des Schirms; es gilt der grössere der beiden Abstände. */
    static final double LINIEN_MIN_PIXEL = 8;

    /** Abstand der Chunklinien in Einheiten des GUI: 16 Blöcke bei {@code scale} Pixeln der Basis je Block. */
    double chunkAbstand(int scale) {
        return 16.0 * scale / teiler() * lupe;
    }

    /** Zeichnet die Karte Chunklinien, oder lägen sie dichter als {@link #LINIEN_MIN} und {@link #LINIEN_MIN_PIXEL}? */
    boolean chunklinien(int scale, int guiMassstab) {
        return chunkAbstand(scale) >= Math.max(LINIEN_MIN, LINIEN_MIN_PIXEL / guiMassstab);
    }

    /** Der erste Chunk, dessen Linie bei Schirm-x 0 oder rechts davon liegt. */
    int ersterChunkX(int scale, int breite) {
        return (int) Math.ceil(basisRasterX(0, breite) / (16.0 * scale));
    }

    int ersterChunkZ(int scale, int hoehe) {
        return (int) Math.ceil(basisRasterZ(0, hoehe) / (16.0 * scale));
    }

    /**
     * Die sichtbaren Chunklinien auf dem Raster der Kacheln, eine Einheit breit: senkrechte über die
     * ganze Höhe, waagrechte über die ganze Breite. Siehe docs/minimap.md, „Chunklinien“.
     */
    Gitter.Linien linien(int scale, int breite, int hoehe) {
        double chunk = 16.0 * scale, abstand = chunkAbstand(scale);
        int mx = (int) (breite / abstand) + 2, mz = (int) (hoehe / abstand) + 2, nx = 0, ny = 0;
        int[] xs = new int[mx], ys = new int[mz];
        for (int c = ersterChunkX(scale, breite); nx < mx; c++) {
            int x = (int) Math.floor(rasterX(c * chunk, breite));
            if (x >= breite) {
                break;
            }
            xs[nx++] = x;
        }
        for (int c = ersterChunkZ(scale, hoehe); ny < mz; c++) {
            int y = (int) Math.floor(rasterY(c * chunk, hoehe));
            if (y >= hoehe) {
                break;
            }
            ys[ny++] = y;
        }
        return new Gitter.Linien(xs, nx, ys, ny, breite, hoehe);
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
    /**
     * Stellt Mitte, Stufe und Lupe, etwa wie die Karte zuletzt stand: die Stufe zwischen der gröbsten
     * und {@code stufe}, die Lupe 1, 2 oder 4 und nur auf der feinsten Stufe. Siehe docs/vollbildkarte.md, „Lage merken“.
     */
    void stelle(double mx, double mz, int zoom, int lupe) {
        this.mx = mx;
        this.mz = mz;
        this.zoom = Math.clamp(zoom, minZoom, stufe);
        this.lupe = this.zoom < stufe ? 1 : lupe >= 4 ? 4 : lupe >= 2 ? 2 : 1;
    }

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
