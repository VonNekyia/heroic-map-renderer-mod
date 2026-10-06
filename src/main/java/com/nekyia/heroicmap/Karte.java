package com.nekyia.heroicmap;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * Die Vollbildkarte aus dem geladenen Satz: Ziehen verschiebt, das Mausrad zoomt über die
 * Stufen des Satzes und danach mit der Lupe. Siehe docs/vollbildkarte.md.
 */
final class Karte extends Screen {

    private static final int HINTERGRUND = 0xFF101010;
    private static final int TEXT = 0xFFFFFFFF;

    private final Satz satz;
    private final Kacheln kacheln;
    private final Kartenblick blick;

    /** {@code satz} ist null, wenn für diese Dimension nichts geladen ist. */
    Karte(Satz satz) {
        super(Component.translatable("heroicmap.karte.titel"));
        this.satz = satz;
        this.kacheln = satz == null ? null : new Kacheln(satz.ordner(), satz.kachel());
        this.blick = satz == null ? null : new Kartenblick(satz.kachel(), satz.minZoom(), satz.maxZoom(), satz.stufe());
    }

    @Override
    protected void init() {
        LocalPlayer spieler = minecraft.player;
        if (blick != null && spieler != null && blick.mx == 0 && blick.mz == 0) {
            blick.mx = Projektion.zuPixel(spieler.getX(), satz.scale());
            blick.mz = Projektion.zuPixel(spieler.getZ(), satz.scale());
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
        g.fill(0, 0, width, height, HINTERGRUND);
        if (satz == null) {
            g.centeredText(font, Component.translatable("heroicmap.karte.keine"), width / 2, height / 2, TEXT);
            return;
        }
        int[] k = blick.kacheln(width, height);
        int seite = satz.kachel() * blick.lupe;
        double basis = satz.kachel() * blick.teiler();
        for (int ty = k[2]; ty <= k[3]; ty++) {
            for (int tx = k[0]; tx <= k[1]; tx++) {
                Identifier id = kacheln.textur(blick.zoom, tx, ty);
                if (id != null) {
                    int sx = Mth.floor(blick.schirmX(tx * basis, width)), sy = Mth.floor(blick.schirmY(ty * basis, height));
                    g.blit(RenderPipelines.GUI_TEXTURED, id, sx, sy, 0, 0, seite, seite, satz.kachel(), satz.kachel(),
                            satz.kachel(), satz.kachel());
                }
            }
        }
        LocalPlayer spieler = minecraft.player;
        if (spieler != null) {
            int px = Mth.floor(blick.schirmX(Projektion.zuPixel(spieler.getX(), satz.scale()), width));
            int pz = Mth.floor(blick.schirmY(Projektion.zuPixel(spieler.getZ(), satz.scale()), height));
            Minimap.pfeil(g, px, pz, spieler.getYRot());
        }
        int bx = Mth.floor(blick.basisX(mausX, width) / satz.scale()), bz = Mth.floor(blick.basisZ(mausY, height) / satz.scale());
        g.text(font, satz.name() + "   x " + bx + "   z " + bz, 4, height - 12, TEXT);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent ereignis, double dx, double dy) {
        if (blick != null && ereignis.button() == 0) {
            blick.schiebe(dx, dy);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double weitX, double weitY) {
        if (blick == null) {
            return false;
        }
        if (weitY > 0) {
            blick.naeher();
        } else if (weitY < 0) {
            blick.ferner();
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent ereignis) {
        if (HeroicMap.karte != null && HeroicMap.karte.matches(ereignis)) {
            onClose();
            return true;
        }
        return super.keyPressed(ereignis);
    }

    @Override
    public void onClose() {
        if (kacheln != null) {
            kacheln.close();
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
