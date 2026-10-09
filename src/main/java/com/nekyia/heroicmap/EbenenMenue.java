package com.nekyia.heroicmap;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Das Untermenü „Ebenen …“: je Ebene vom Server ein Schalter, die oberste zuerst; ohne Ebenen ein
 * Hinweis. Passen nicht alle auf den Schirm, auf Seiten. Jede Wahl wirkt und speichert gleich.
 * Siehe docs/ebenen.md, „Umschalten“.
 */
final class EbenenMenue extends Screen {

    private static final int TEXT = 0xFFFFFFFF;

    private final Screen zurueck;
    private int seite;

    EbenenMenue(Screen zurueck) {
        super(Component.translatable("heroicmap.ebenen.titel"));
        this.zurueck = zurueck;
    }

    @Override
    protected void init() {
        int[] s = Einstellungen.spalte(width, height);
        int x = s[0], breite = s[1], y = oben();
        List<Ebenen.Eintrag> alle = Ebenen.INSTANZ.alle();
        int jeSeite = Math.max(1, (height - y - 60) / 24), seiten = Math.max(1, Math.ceilDiv(alle.size(), jeSeite));
        seite = Math.min(seite, seiten - 1);
        boolean deutsch = minecraft.getLanguageManager().getSelected().startsWith("de");
        List<Ebenen.Eintrag> hier = alle.subList(seite * jeSeite, Math.min(alle.size(), (seite + 1) * jeSeite));
        for (int i = 0; i < hier.size(); i++) {
            Ebenen.Eintrag e = hier.get(i);
            addRenderableWidget(CycleButton.onOffBuilder(Ebenen.INSTANZ.an(e))
                    .create(x, y + 24 * i, breite, 20, Component.literal(e.name(deutsch)), (b, an) -> Ebenen.INSTANZ.setze(e.id(), an)));
        }
        int unten = y + 24 * Math.max(1, hier.size()) + 4;
        if (seiten > 1) {
            int halb = (breite - 4) / 2;
            addRenderableWidget(Button.builder(Component.literal("<"), b -> blaettere(-1)).bounds(x, unten, halb, 20).build()).active = seite > 0;
            addRenderableWidget(Button.builder(Component.literal(">"), b -> blaettere(1)).bounds(x + breite - halb, unten, halb, 20).build())
                    .active = seite < seiten - 1;
            unten += 24;
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(x, unten, breite, 20).build());
    }

    private void blaettere(int richtung) {
        seite += richtung;
        rebuildWidgets();
    }

    private int oben() {
        return Math.max(30, height / 2 - 84);
    }

    /** Ohne Unschärfe und Abdunkeln, damit die Karte zu sehen ist. */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
        super.extractRenderState(g, mausX, mausY, delta);
        int[] s = Einstellungen.spalte(width, height);
        g.centeredText(font, title, s[0] + s[1] / 2, oben() - 14, TEXT);
        if (Ebenen.INSTANZ.alle().isEmpty()) {
            g.centeredText(font, Component.translatable("heroicmap.ebenen.keine"), s[0] + s[1] / 2, oben() + 6, TEXT);
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(zurueck);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
