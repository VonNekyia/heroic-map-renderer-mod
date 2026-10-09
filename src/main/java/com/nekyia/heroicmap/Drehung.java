package com.nekyia.heroicmap;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2fc;

/**
 * Wie die Minimap ihr Bild auf den Schirm bringt: mit einer Lage, die ungedreht nur verschiebt und
 * gedreht die Blickrichtung nach oben legt; Vielecke werden mit der Form geschnitten und als Fächer
 * gezeichnet. Siehe docs/minimap.md, „Form“.
 * Gedreht siehe docs/minimap.md, „Drehen“.
 */
final class Drehung {

    /** Ecken des Vielecks um den Kreis der runden Minimap; wie weit es über den Kreis ragt, steht in docs/minimap.md, „Form“. */
    static final int ECKEN = 64;

    private Drehung() {
    }

    /** Um wie viel die Karte dreht, in Bogenmass, damit die Blickrichtung oben liegt: bei Gier 180, Blick nach Norden, nicht. */
    static double winkel(float gier) {
        return Math.toRadians(180 - gier);
    }

    /**
     * Wie das Bild der Minimap auf den Schirm kommt, in Pixeln des Schirms: Der Punkt (px, py) des
     * Bilds, der Spieler, liegt auf (cx, cy), alles andere dreht um ihn.
     */
    record Lage(double cos, double sin, double cx, double cy, double px, double py) {

        static Lage von(double winkel, double cx, double cy, double px, double py) {
            return new Lage(Math.cos(winkel), Math.sin(winkel), cx, cy, px, py);
        }

        /** x auf dem Schirm für den Punkt (x, y) des Bilds. */
        double x(double x, double y) {
            return cx + (x - px) * cos - (y - py) * sin;
        }

        double y(double x, double y) {
            return cy + (x - px) * sin + (y - py) * cos;
        }

        /** x im Bild für den Punkt (x, y) des Schirms, die Umkehrung. */
        double bildX(double x, double y) {
            return px + (x - cx) * cos + (y - cy) * sin;
        }

        double bildY(double x, double y) {
            return py - (x - cx) * sin + (y - cy) * cos;
        }

        /** Eine Richtung (dx, dy) gedreht, x; ohne Verschiebung. */
        double richtungX(double dx, double dy) {
            return dx * cos - dy * sin;
        }

        double richtungY(double dx, double dy) {
            return dx * sin + dy * cos;
        }
    }

    /** Ein Vieleck um den Kreis (cx, cy, r) mit {@link #ECKEN} Ecken, aussen anliegend: Es deckt den Kreis ganz. */
    static float[] kreis(double cx, double cy, double r) {
        float[] ecken = new float[2 * ECKEN];
        double aussen = r / Math.cos(Math.PI / ECKEN);
        for (int i = 0; i < ECKEN; i++) {
            double w = 2 * Math.PI * i / ECKEN;
            ecken[2 * i] = (float) (cx + aussen * Math.cos(w));
            ecken[2 * i + 1] = (float) (cy + aussen * Math.sin(w));
        }
        return ecken;
    }

    /** Das Rechteck von (x0, y0) bis (x1, y1) als Vieleck. */
    static float[] rechteck(double x0, double y0, double x1, double y1) {
        return new float[] {(float) x0, (float) y0, (float) x0, (float) y1, (float) x1, (float) y1, (float) x1, (float) y0};
    }

    /**
     * Schneidet das konvexe Vieleck p, n Ecken mit x und y abwechselnd, mit dem konvexen Vieleck c,
     * m Ecken; beide Umlaufsinne gehen. Das Ergebnis steht danach in a, die Zahl seiner Ecken kommt
     * zurück. a und b fassen mindestens n + m Ecken.
     */
    static int schneide(float[] p, int n, float[] c, int m, float[] a, float[] b) {
        double sinn = 0;
        for (int i = 0; i < m; i++) {
            int j = (i + 1) % m;
            sinn += (double) c[2 * i] * c[2 * j + 1] - (double) c[2 * j] * c[2 * i + 1];
        }
        float[] ein = a, aus = b;
        System.arraycopy(p, 0, ein, 0, 2 * n);
        int anzahl = n;
        for (int i = 0; i < m && anzahl > 0; i++) {
            int j = (i + 1) % m;
            double ax = c[2 * i], ay = c[2 * i + 1], ex = c[2 * j] - ax, ey = c[2 * j + 1] - ay;
            int neu = 0;
            for (int q = 0; q < anzahl; q++) {
                int r = (q + 1) % anzahl;
                double qx = ein[2 * q], qy = ein[2 * q + 1], rx = ein[2 * r], ry = ein[2 * r + 1];
                double dq = (ex * (qy - ay) - ey * (qx - ax)) * sinn, dr = (ex * (ry - ay) - ey * (rx - ax)) * sinn;
                if (dq >= 0) {
                    aus[2 * neu] = (float) qx;
                    aus[2 * neu + 1] = (float) qy;
                    neu++;
                }
                if ((dq >= 0) != (dr >= 0)) {
                    double t = dq / (dq - dr);
                    aus[2 * neu] = (float) (qx + t * (rx - qx));
                    aus[2 * neu + 1] = (float) (qy + t * (ry - qy));
                    neu++;
                }
            }
            float[] tausch = ein;
            ein = aus;
            aus = tausch;
            anzahl = neu;
        }
        if (ein != a) {
            System.arraycopy(ein, 0, a, 0, 2 * anzahl);
        }
        return anzahl;
    }

    /** Zwischenspeicher für {@link #region}: Ecken, UV und einer für den Schnitt, für Formen bis {@link #ECKEN} Ecken. */
    record Puffer(float[] ecke, float[] ecken, float[] b, float[] uv) {

        Puffer() {
            this(new float[8], new float[2 * (4 + ECKEN)], new float[2 * (4 + ECKEN)], new float[2 * (4 + ECKEN)]);
        }
    }

    /**
     * Die Regionen, die zu sehen sein können: alle, die den Bereich {x0, y0, x1, y1} des Bilds
     * berühren, x1 und y1 ausschliesslich; {@code s} ist die Seite einer Region in Pixeln.
     * {rx0, rx1, rz0, rz1}, Enden eingeschlossen.
     */
    static int[] regionen(double[] bereich, int links, int oben, int s) {
        return new int[] {Math.floorDiv((int) Math.floor(bereich[0]) + links, s), Math.floorDiv((int) Math.ceil(bereich[2]) - 1 + links, s),
            Math.floorDiv((int) Math.floor(bereich[1]) + oben, s), Math.floorDiv((int) Math.ceil(bereich[3]) - 1 + oben, s)};
    }

    /**
     * Das Vieleck einer Region auf dem Schirm: ihr Quadrat ab (qx, qy) im Bild, Seite s, mit
     * {@code lage} gedreht und mit {@code form} geschnitten. Die Ecken stehen danach in
     * {@code puffer.ecken()}, je Ecke die UV der Textur in {@code puffer.uv()}: aus der Drehung
     * zurück ins Bild. Gibt die Zahl der Ecken zurück, unter 3 ist die Region nicht zu sehen.
     */
    static int region(Lage lage, int qx, int qy, int s, float[] form, Puffer puffer) {
        float[] ecke = puffer.ecke(), uv = puffer.uv();
        // Wie ungedreht: links oben, links unten, rechts unten, rechts oben.
        setze(ecke, 0, lage, qx, qy);
        setze(ecke, 1, lage, qx, qy + s);
        setze(ecke, 2, lage, qx + s, qy + s);
        setze(ecke, 3, lage, qx + s, qy);
        int anzahl = schneide(ecke, 4, form, form.length / 2, puffer.ecken(), puffer.b());
        float[] ecken = puffer.ecken();
        for (int i = 0; i < anzahl; i++) {
            uv[2 * i] = (float) ((lage.bildX(ecken[2 * i], ecken[2 * i + 1]) - qx) / s);
            uv[2 * i + 1] = (float) ((lage.bildY(ecken[2 * i], ecken[2 * i + 1]) - qy) / s);
        }
        return anzahl;
    }

    /** Ecke i des Vielecks: der Punkt (x, y) des Bilds auf dem Schirm. */
    static void setze(float[] ecke, int i, Lage lage, double x, double y) {
        ecke[2 * i] = (float) lage.x(x, y);
        ecke[2 * i + 1] = (float) lage.y(x, y);
    }

    /** Ein Vieleck als Fächer: je Dreieck ein Viereck mit doppelter letzter Ecke, wie es die Vierecke des GUI wollen. */
    interface Ecke {
        void ecke(int i);
    }

    static void faecher(int anzahl, Ecke aus) {
        for (int i = 1; i + 1 < anzahl; i++) {
            aus.ecke(0);
            aus.ecke(i);
            aus.ecke(i + 1);
            aus.ecke(i + 1);
        }
    }

    /**
     * Eine gedrehte Textur: das Vieleck aus dem Schnitt ihres Quadrats mit der Form, Ecken in
     * Pixeln des Schirms, UV je Ecke. Gedreht bleibt der Umlaufsinn, das GUI verwirft nichts.
     */
    record Bild(Matrix3x2fc pose, TextureSetup textureSetup, float[] ecken, float[] uv, int anzahl, ScreenRectangle bounds)
            implements GuiElementRenderState {

        @Override
        public void buildVertices(VertexConsumer v) {
            faecher(anzahl, i -> v.addVertexWith2DPose(pose, ecken[2 * i], ecken[2 * i + 1]).setUv(uv[2 * i], uv[2 * i + 1]).setColor(-1));
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI_TEXTURED;
        }

        @Override
        public ScreenRectangle scissorArea() {
            return null;
        }
    }
}
