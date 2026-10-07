package com.nekyia.heroicmap;

import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Die Karten, die der Server anbietet, je Baum ein Knopf je Massstab mit seiner Grösse. Ein Knopf
 * fragt wie {@code /hmap laden} erst im Dialog nach. Siehe docs/vollbildkarte.md, „Bedienung“.
 */
final class Auswahl extends Screen {

    private static final int TEXT = 0xFFFFFFFF;
    /** Höhe eines Baums: Name, darunter die Knöpfe. */
    private static final int ZEILE = 38;

    private final Screen zurueck;
    private List<Downloads.Baum> baeume = List.of();
    /** Was beim letzten Knopf schiefging, oder null. */
    private Component hinweis;

    Auswahl(Screen zurueck) {
        super(Component.translatable("heroicmap.auswahl.titel"));
        this.zurueck = zurueck;
    }

    @Override
    protected void init() {
        baeume = Downloads.INSTANZ.baeume();
        int y = 34;
        for (Downloads.Baum baum : baeume) {
            // Die Knöpfe teilen sich die Breite des Schirms, höchstens 90 Einheiten je Knopf.
            int n = Math.max(1, baum.bytes().size());
            int breite = Math.min(90, (width - 20 - (n - 1) * 4) / n);
            int x = (width - n * breite - (n - 1) * 4) / 2;
            for (Map.Entry<Integer, Long> massstab : baum.bytes().entrySet()) {
                int m = massstab.getKey();
                addRenderableWidget(Button.builder(
                        Component.translatable("heroicmap.auswahl.massstab", m, Downloads.groesse(massstab.getValue())),
                        b -> hinweis = Downloads.INSTANZ.frageVoll(baum.id(), m)).bounds(x, y + 12, breite, 20).build());
                x += breite + 4;
            }
            y += ZEILE;
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                .bounds(width / 2 - 50, height - 28, 100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
        g.fill(0, 0, width, height, 0xFF101010);
        g.centeredText(font, title, width / 2, 16, TEXT);
        if (baeume.isEmpty()) {
            g.centeredText(font, Component.translatable("heroicmap.angebot.keins"), width / 2, height / 2, TEXT);
        }
        int y = 34;
        for (Downloads.Baum baum : baeume) {
            g.centeredText(font, baum.name() + "  (" + baum.dimension() + ")", width / 2, y, TEXT);
            y += ZEILE;
        }
        if (hinweis != null) {
            g.centeredText(font, hinweis, width / 2, height - 44, TEXT);
        }
        super.extractRenderState(g, mausX, mausY, delta);
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
