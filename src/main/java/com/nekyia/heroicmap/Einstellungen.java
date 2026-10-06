package com.nekyia.heroicmap;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Das Menü hinter {@code /hmap}: Minimap an oder aus, Massstab, Form, dazu die Karten des Servers.
 * Die Minimap im HUD bleibt sichtbar; Ziehen verschiebt sie, der Griff an der Ecke zur Mitte des
 * Schirms zieht sie grösser oder kleiner. Gespeichert wird beim Schliessen.
 * Siehe docs/minimap.md, „Bedienung“.
 */
final class Einstellungen extends Screen {

    private static final int TEXT = 0xFFFFFFFF;
    /** Halbe Seite des Griffs in Einheiten des GUI. */
    private static final int GRIFF = 3;

    private enum Zug { KEINER, LAGE, GROESSE }

    private Zug zug = Zug.KEINER;
    /** Beim Verschieben: wo die Maus die Minimap gegriffen hat. Beim Ziehen: die feste Ecke. */
    private int festX, festY;
    /** Beim Ziehen: liegt der Griff links oder oben? */
    private boolean griffLinks, griffOben;

    Einstellungen() {
        super(Component.translatable("heroicmap.menue.titel"));
    }

    @Override
    protected void init() {
        Minimap m = Minimap.INSTANZ;
        int x = width / 2 - 100, y = height / 2 - 60;
        addRenderableWidget(CycleButton.onOffBuilder(m.sichtbar())
                .create(x, y, 200, 20, Component.translatable("heroicmap.menue.minimap"), (b, an) -> m.setzeSichtbar(an)));
        addRenderableWidget(CycleButton.builder((Integer px) -> Component.translatable("heroicmap.menue.px", px), m.scale())
                .withValues(1, 2, 4)
                .create(x, y + 24, 200, 20, Component.translatable("heroicmap.menue.massstab"), (b, px) -> m.setzeScale(px)));
        addRenderableWidget(CycleButton.booleanBuilder(Component.translatable("heroicmap.menue.rund"),
                        Component.translatable("heroicmap.menue.eckig"), m.rund())
                .create(x, y + 48, 200, 20, Component.translatable("heroicmap.menue.form"), (b, rund) -> m.setzeRund(rund)));
        addRenderableWidget(Button.builder(Component.translatable("heroicmap.karte.laden"),
                b -> minecraft.gui.setScreen(new Auswahl(this))).bounds(x, y + 72, 200, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(x, y + 100, 200, 20).build());
    }

    /** Ohne Unschärfe und Abdunkeln, damit die Minimap im HUD zu sehen ist. */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
        super.extractRenderState(g, mausX, mausY, delta);
        g.centeredText(font, title, width / 2, height / 2 - 80, TEXT);
        g.centeredText(font, Component.translatable("heroicmap.menue.ziehen"), width / 2, height / 2 + 66, TEXT);
        if (Minimap.INSTANZ.sichtbar()) {
            Minimap.Rahmen r = Minimap.INSTANZ.rahmen(width, height);
            g.outline(r.x() - 2, r.y() - 2, r.seite() + 4, r.seite() + 4, TEXT);
            int gx = griffX(r), gy = griffY(r);
            g.fill(gx - GRIFF, gy - GRIFF, gx + GRIFF, gy + GRIFF, TEXT);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doppelt) {
        if (super.mouseClicked(e, doppelt)) {
            return true;
        }
        if (e.button() != 0 || !Minimap.INSTANZ.sichtbar()) {
            return false;
        }
        Minimap.Rahmen r = Minimap.INSTANZ.rahmen(width, height);
        if (Math.abs(e.x() - griffX(r)) <= GRIFF + 1 && Math.abs(e.y() - griffY(r)) <= GRIFF + 1) {
            zug = Zug.GROESSE;
            griffLinks = links(r);
            griffOben = oben(r);
            festX = griffLinks ? r.x() + r.seite() : r.x();
            festY = griffOben ? r.y() + r.seite() : r.y();
            return true;
        }
        if (r.enthaelt(e.x(), e.y())) {
            zug = Zug.LAGE;
            festX = Mth.floor(e.x()) - r.x();
            festY = Mth.floor(e.y()) - r.y();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        Minimap m = Minimap.INSTANZ;
        int mx = Mth.floor(e.x()), my = Mth.floor(e.y());
        switch (zug) {
            case LAGE -> m.verschiebe(mx - festX, my - festY, width, height);
            case GROESSE -> {
                int platz = Math.min(width, height) - 2 * Minimap.RAND;
                int s = Mth.clamp(Math.max(Math.abs(mx - festX), Math.abs(my - festY)),
                        Minimap.KLEINSTE, Math.max(Minimap.KLEINSTE, Math.min(Minimap.GROESSTE, platz)));
                m.stelle(griffLinks ? festX - s : festX, griffOben ? festY - s : festY, s, width, height);
            }
            case KEINER -> {
                return super.mouseDragged(e, dx, dy);
            }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        zug = Zug.KEINER;
        return super.mouseReleased(e);
    }

    /** Der Griff sitzt an der Ecke, die zur Mitte des Schirms zeigt; so hat er dort Platz zum Ziehen. */
    private boolean links(Minimap.Rahmen r) {
        return r.x() + r.seite() / 2 > width / 2;
    }

    private boolean oben(Minimap.Rahmen r) {
        return r.y() + r.seite() / 2 > height / 2;
    }

    private int griffX(Minimap.Rahmen r) {
        return links(r) ? r.x() : r.x() + r.seite();
    }

    private int griffY(Minimap.Rahmen r) {
        return oben(r) ? r.y() : r.y() + r.seite();
    }

    @Override
    public void removed() {
        Minimap.INSTANZ.schreibe(HeroicMap.einstellungen());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
