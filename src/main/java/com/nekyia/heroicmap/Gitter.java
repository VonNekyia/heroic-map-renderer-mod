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
 * beim Zeichnen. Mit {@code lage} gedreht und mit der {@code form} geschnitten, siehe
 * docs/minimap.md, „Drehen“. Siehe docs/minimap.md, „Chunklinien“.
 */
record Gitter(Matrix3x2fc pose, int x0, int y0, int dicke, int farbe, Linien linien, ScreenRectangle bounds,
        Drehung.Lage lage, float[] form) implements GuiElementRenderState {

    /**
     * Die Linien in Pixeln ab (x0, y0): senkrechte Linie i bei {@code xs[i]}, aufsteigend, von
     * {@code va[i]} bis {@code vb[i]}; waagrechte Linie j bei {@code ys[j]} von {@code ha[j]} bis
     * {@code hb[j]}. Enden ausschliesslich.
     */
    record Linien(int[] xs, int[] va, int[] vb, int nx, int[] ys, int[] ha, int[] hb, int ny) {
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
            aus.fill(l.xs()[i], l.va()[i], l.xs()[i] + dicke, l.vb()[i]);
        }
        for (int j = 0; j < l.ny(); j++) {
            int y = l.ys()[j], von = l.ha()[j], bis = l.hb()[j];
            for (int i = 0; i < l.nx() && l.xs()[i] < bis; i++) {
                // Nur eine senkrechte, die diese Zeile wirklich kreuzt, spart Spalten aus.
                if (l.xs()[i] + dicke > von && l.va()[i] < y + dicke && y < l.vb()[i]) {
                    if (l.xs()[i] > von) {
                        aus.fill(von, y, l.xs()[i], y + dicke);
                    }
                    von = l.xs()[i] + dicke;
                }
            }
            if (von < bis) {
                aus.fill(von, y, bis, y + dicke);
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

    /** Die Rechtecke der Linien ab (x0, y0) im Bild, gedreht mit {@code lage} und mit {@code form} geschnitten; leere fallen weg. */
    static void gedreht(int dicke, Linien l, int x0, int y0, Drehung.Lage lage, float[] form, Vieleck aus) {
        int m = form.length / 2;
        float[] ecke = new float[8], a = new float[2 * (4 + m)], b = new float[2 * (4 + m)];
        rechtecke(dicke, l, (xa, ya, xb, yb) -> {
            // Dieselbe Reihenfolge wie ungedreht: Der Umlaufsinn bleibt, das GUI verwirft nichts.
            int[] xs = {x0 + xa, x0 + xa, x0 + xb, x0 + xb}, ys = {y0 + ya, y0 + yb, y0 + yb, y0 + ya};
            for (int i = 0; i < 4; i++) {
                ecke[2 * i] = (float) lage.x(xs[i], ys[i]);
                ecke[2 * i + 1] = (float) lage.y(xs[i], ys[i]);
            }
            int anzahl = Drehung.schneide(ecke, 4, form, m, a, b);
            if (anzahl >= 3) {
                aus.vieleck(a, anzahl);
            }
        });
    }

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
