package com.nekyia.heroicmap;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
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
 * Stufen des Satzes und danach mit der Lupe; Knöpfe laden und gleichen ab. Siehe docs/vollbildkarte.md.
 */
final class Karte extends Screen {

    private static final int HINTERGRUND = 0xFF101010;
    private static final int TEXT = 0xFFFFFFFF;
    /** Breite der Knöpfe in Einheiten des GUI. */
    private static final int KNOPF = 90;

    private final Satz satz;
    private final Kacheln kacheln;
    private final Kartenblick blick;
    /** Was beim letzten Knopf schiefging, oder null. */
    private Component hinweis;
    /** Der Knopf für den Abgleich, oder null ohne Satz; er ist aus, bis ein Abgleich wieder geht. */
    private Button abgleich;
    private String baum;
    /** Das Menü zum Teleportieren nach Rechtsklick: der Block, oder null, und wo das Menü steht. */
    private int[] ziel;
    private int menueX, menueY;

    /** {@code satz} ist null, wenn für diese Dimension nichts geladen ist. */
    Karte(Satz satz) {
        super(Component.translatable("heroicmap.karte.titel"));
        this.satz = satz;
        this.kacheln = satz == null ? null : new Kacheln(satz);
        this.blick = satz == null ? null : new Kartenblick(satz.kachel(), satz.minZoom(), satz.maxZoom(), satz.stufe());
    }

    @Override
    protected void init() {
        LocalPlayer spieler = minecraft.player;
        if (blick != null && spieler != null && blick.mx == 0 && blick.mz == 0) {
            blick.mx = Projektion.zuPixel(spieler.getX(), satz.scale());
            blick.mz = Projektion.zuPixel(spieler.getZ(), satz.scale());
        }
        int x = width - KNOPF - 4;
        addRenderableWidget(Button.builder(Component.translatable("heroicmap.karte.laden"),
                b -> minecraft.gui.setScreen(new Auswahl(this))).bounds(x, 4, KNOPF, 20).build());
        if (satz != null) {
            // Der Baum ist der Ordner über dem Massstab.
            baum = satz.ordner().getParent().getFileName().toString();
            abgleich = addRenderableWidget(Button.builder(Component.translatable("heroicmap.karte.abgleich"),
                    b -> hinweis = Downloads.INSTANZ.frageAbgleich(baum)).bounds(x, 28, KNOPF, 20).build());
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
        g.fill(0, 0, width, height, HINTERGRUND);
        if (satz == null) {
            g.centeredText(font, Component.translatable("heroicmap.karte.keine"), width / 2, height / 2, TEXT);
            super.extractRenderState(g, mausX, mausY, delta);
            return;
        }
        int[] k = blick.kacheln(width, height);
        int seite = satz.kachel() * blick.lupe;
        double basis = satz.kachel() * blick.teiler();
        for (int ty = k[2]; ty <= k[3]; ty++) {
            for (int tx = k[0]; tx <= k[1]; tx++) {
                Identifier id = kacheln.textur(blick.zoom, tx, ty);
                int sx = Mth.floor(blick.schirmX(tx * basis, width)), sy = Mth.floor(blick.schirmY(ty * basis, height));
                if (id != null) {
                    g.blit(RenderPipelines.GUI_TEXTURED, id, sx, sy, 0, 0, seite, seite, satz.kachel(), satz.kachel(),
                            satz.kachel(), satz.kachel());
                } else {
                    platzhalter(g, tx, ty, sx, sy, seite);
                }
            }
        }
        LocalPlayer spieler = minecraft.player;
        if (spieler != null) {
            int px = Mth.floor(blick.schirmX(Projektion.zuPixel(spieler.getX(), satz.scale()), width));
            int pz = Mth.floor(blick.schirmY(Projektion.zuPixel(spieler.getZ(), satz.scale()), height));
            Minimap.avatar(g, spieler, px, pz);
            // Die Mitspieler mit Namen, in der Dimension des Spielers. Siehe docs/minimap.md, „Mitspieler“.
            String dimension = spieler.level().dimension().identifier().toString();
            for (Mitspieler.Eintrag e : Mitspieler.INSTANZ.aktuell(System.currentTimeMillis())) {
                if (!e.dimension().equals(dimension) || e.uuid().equals(spieler.getUUID())) {
                    continue;
                }
                double[] lage = Mitspieler.lage(minecraft, e);
                int ex = Mth.floor(blick.schirmX(Projektion.zuPixel(lage[0], satz.scale()), width));
                int ez = Mth.floor(blick.schirmY(Projektion.zuPixel(lage[1], satz.scale()), height));
                Mitspieler.kopf(g, minecraft, e, ex, ez, Minimap.KOPF);
                g.centeredText(font, e.name(), ex, ez - Minimap.KOPF / 2 - 10, TEXT);
            }
        }
        g.text(font, satz.name(), 4, height - 12, TEXT);
        if (hinweis != null) {
            g.text(font, hinweis, 4, height - 24, TEXT);
        }
        // Höchstens ein Abgleich je Tag: Nach einer Ablehnung mit wieder ist der Knopf bis dahin aus.
        long ab = Downloads.INSTANZ.abgleichAb(baum);
        abgleich.active = ab == 0;
        abgleich.setMessage(ab == 0 ? Component.translatable("heroicmap.karte.abgleich")
                : Component.translatable("heroicmap.karte.abgleich_ab", Downloads.uhr(ab)));
        super.extractRenderState(g, mausX, mausY, delta);
        if (ziel == null && getChildAt(mausX, mausY).isEmpty()) {
            int[] block = block(mausX, mausY);
            g.setTooltipForNextFrame(font, Component.translatable("heroicmap.koordinaten", block[0], block[1]), mausX, mausY);
        }
        if (ziel != null) {
            Component text = eintrag();
            g.fill(menueX, menueY, menueX + font.width(text) + 8, menueY + 14, 0xE0000000);
            g.outline(menueX, menueY, font.width(text) + 8, 14, TEXT);
            g.text(font, text, menueX + 4, menueY + 3, TEXT);
        }
    }

    /** Der Block unter (x, y) des Schirms. */
    private int[] block(double x, double y) {
        return new int[] {Mth.floor(blick.basisX(x, width) / satz.scale()), Mth.floor(blick.basisZ(y, height) / satz.scale())};
    }

    private Component eintrag() {
        return Component.translatable("heroicmap.karte.teleport", ziel[0], ziel[1]);
    }

    /**
     * Rechtsklick öffnet das Menü „Hierher teleportieren“, erst ein Klick darauf schickt den
     * Befehl. Nur mit execute und tp im Befehlsbaum und nicht unter einer Decke, sonst landete man
     * auf dem Dach. Siehe docs/vollbildkarte.md, „Bedienung“.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doppelt) {
        if (ziel != null) {
            int[] z = ziel;
            boolean treffer = e.button() == 0 && e.x() >= menueX && e.x() < menueX + font.width(eintrag()) + 8
                    && e.y() >= menueY && e.y() < menueY + 14;
            ziel = null;
            if (treffer) {
                minecraft.getConnection().sendCommand(Teleport.befehl(minecraft.level.dimension().identifier().toString(), z[0], z[1]));
                onClose();
                return true;
            }
        }
        if (super.mouseClicked(e, doppelt)) {
            return true;
        }
        if (e.button() == 1 && blick != null && minecraft.level != null && minecraft.getConnection() != null
                && !minecraft.level.dimensionType().hasCeiling() && Teleport.erlaubt(minecraft.getConnection().getCommands())) {
            ziel = block(e.x(), e.y());
            menueX = Mth.floor(e.x());
            menueY = Mth.floor(e.y());
            return true;
        }
        return false;
    }

    /**
     * Solange eine Kachel lädt, ihr Ausschnitt aus der nächsten gröberen Stufe, die schon geladen
     * ist, vergrössert; nichts wird dafür angefragt. Siehe docs/vollbildkarte.md, „Kacheln“.
     */
    private void platzhalter(GuiGraphicsExtractor g, int tx, int ty, int sx, int sy, int seite) {
        for (int k = 1; blick.zoom - k >= satz.minZoom(); k++) {
            int[] grob = Kartenblick.grob(tx, ty, k, satz.kachel());
            if (grob == null) {
                return;
            }
            Identifier id = kacheln.vorhanden(blick.zoom - k, grob[0], grob[1]);
            if (id != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, id, sx, sy, grob[2], grob[3], seite, seite, grob[4], grob[4],
                        satz.kachel(), satz.kachel());
                return;
            }
        }
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
