package com.nekyia.heroicmap;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

/**
 * Die Chunklinien als ein einziges Element des GUI je Frame: senkrechte Linien ganz, waagrechte
 * ohne die Spalten der senkrechten, so deckt keine Kreuzung doppelt. Die Rechtecke entstehen erst
 * beim Zeichnen. Mit {@code lage} auf den Schirm und mit der {@code form} geschnitten, siehe
 * docs/minimap.md, „Form“. Siehe docs/minimap.md, „Chunklinien“.
 */
record Gitter(Matrix3x2fc pose, int x0, int y0, int dicke, int farbe, Linien linien, ScreenRectangle bounds,
        Drehung.Lage lage, float[] form) implements GuiElementRenderState {

    /**
     * Die Linien in Pixeln ab (x0, y0) auf der Fläche {@code breite} × {@code hoehe}: senkrechte
     * Linie i bei {@code xs[i]}, aufsteigend, über die ganze Höhe; waagrechte Linie j bei
     * {@code ys[j]} über die ganze Breite.
     */
    record Linien(int[] xs, int nx, int[] ys, int ny, int breite, int hoehe) {
    }

    /** Ein Rechteck, Enden ausschliesslich. */
    interface Rechteck {
        void fill(int xa, int ya, int xb, int yb);
    }

    /** Hängt die Linien ab (x0, y0) unter der aktuellen Pose an; die Fläche w × h begrenzt sie. */
    static void zeichne(GuiGraphicsExtractor g, int x0, int y0, int w, int h, int dicke, int farbe, Linien linien) {
        Matrix3x2f pose = new Matrix3x2f(g.pose());
        g.guiRenderState.addGuiElement(new Gitter(pose, x0, y0, dicke, farbe, linien,
                new ScreenRectangle(x0, y0, w, h).transformMaxBounds(pose), null, null));
    }

    /**
     * Wie {@link #zeichne}, die Linien ab (x0, y0) im Bild, gedreht mit {@code lage} und mit dem
     * konvexen Vieleck {@code form} geschnitten; {@code flaeche} begrenzt sie auf dem Schirm.
     */
    static void zeichne(GuiGraphicsExtractor g, int x0, int y0, ScreenRectangle flaeche, int dicke, int farbe, Linien linien,
            Drehung.Lage lage, float[] form) {
        Matrix3x2f pose = new Matrix3x2f(g.pose());
        g.guiRenderState.addGuiElement(new Gitter(pose, x0, y0, dicke, farbe, linien, flaeche.transformMaxBounds(pose), lage, form));
    }

    /** Die Rechtecke der Linien: erst alle senkrechten, dann die waagrechten in Stücken zwischen ihnen. */
    static void rechtecke(int dicke, Linien l, Rechteck aus) {
        for (int i = 0; i < l.nx(); i++) {
            aus.fill(l.xs()[i], 0, l.xs()[i] + dicke, l.hoehe());
        }
        for (int j = 0; j < l.ny(); j++) {
            int y = l.ys()[j], von = 0;
            for (int i = 0; i < l.nx(); i++) {
                if (l.xs()[i] > von) {
                    aus.fill(von, y, l.xs()[i], y + dicke);
                }
                von = l.xs()[i] + dicke;
            }
            if (von < l.breite()) {
                aus.fill(von, y, l.breite(), y + dicke);
            }
        }
    }

    @Override
    public void buildVertices(VertexConsumer v) {
        if (lage == null) {
            rechtecke(dicke, linien, (xa, ya, xb, yb) -> {
                v.addVertexWith2DPose(pose, x0 + xa, y0 + ya).setColor(farbe);
                v.addVertexWith2DPose(pose, x0 + xa, y0 + yb).setColor(farbe);
                v.addVertexWith2DPose(pose, x0 + xb, y0 + yb).setColor(farbe);
                v.addVertexWith2DPose(pose, x0 + xb, y0 + ya).setColor(farbe);
            });
            return;
        }
        gedreht(dicke, linien, x0, y0, lage, form, (ecken, anzahl) -> Drehung.faecher(anzahl,
                i -> v.addVertexWith2DPose(pose, ecken[2 * i], ecken[2 * i + 1]).setColor(farbe)));
    }

    /** Ein Vieleck auf dem Schirm, {@code anzahl} Ecken in {@code ecken}. */
    interface Vieleck {
        void vieleck(float[] ecken, int anzahl);
    }

    /**
     * Die Rechtecke der Linien ab (x0, y0) im Bild, gedreht mit {@code lage} und mit {@code form}
     * geschnitten; leere fallen weg. Die Puffer gehören dem Render-Thread.
     */
    static void gedreht(int dicke, Linien l, int x0, int y0, Drehung.Lage lage, float[] form, Vieleck aus) {
        int m = form.length / 2;
        float[] ecke = PUFFER.ecke(), a = PUFFER.ecken(), b = PUFFER.b();
        rechtecke(dicke, l, (xa, ya, xb, yb) -> {
            // Dieselbe Reihenfolge wie ungedreht: Der Umlaufsinn bleibt, das GUI verwirft nichts.
            Drehung.setze(ecke, 0, lage, x0 + xa, y0 + ya);
            Drehung.setze(ecke, 1, lage, x0 + xa, y0 + yb);
            Drehung.setze(ecke, 2, lage, x0 + xb, y0 + yb);
            Drehung.setze(ecke, 3, lage, x0 + xb, y0 + ya);
            int anzahl = Drehung.schneide(ecke, 4, form, m, a, b);
            if (anzahl >= 3) {
                aus.vieleck(a, anzahl);
            }
        });
    }

    private static final Drehung.Puffer PUFFER = new Drehung.Puffer();

    @Override
    public RenderPipeline pipeline() {
        return RenderPipelines.GUI;
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.noTexture();
    }

    @Override
    public ScreenRectangle scissorArea() {
        return null;
    }
}
