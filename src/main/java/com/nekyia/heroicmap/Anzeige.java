package com.nekyia.heroicmap;

import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Das Untermenü „Einstellungen …“ für Vorlieben der Anzeige: Form, Auflösung, Chunklinien und die
 * Ablage der Karten. Die Minimap im HUD bleibt sichtbar, jede Wahl wirkt gleich. Gespeichert wird
 * beim Schliessen. Siehe docs/minimap.md, „Bedienung“.
 */
final class Anzeige extends Screen {

    private static final int TEXT = 0xFFFFFFFF;

    private final Screen zurueck;

    Anzeige(Screen zurueck) {
        super(Component.translatable("heroicmap.anzeige.titel"));
        this.zurueck = zurueck;
    }

    @Override
    protected void init() {
        Minimap m = Minimap.INSTANZ;
        int[] s = Einstellungen.spalte(width, height);
        int x = s[0], breite = s[1], y = oben();
        addRenderableWidget(CycleButton.booleanBuilder(Component.translatable("heroicmap.menue.rund"),
                        Component.translatable("heroicmap.menue.eckig"), m.rund())
                .create(x, y, breite, 20, Component.translatable("heroicmap.menue.form"), (b, rund) -> m.setzeRund(rund)));
        addRenderableWidget(CycleButton.builder((Integer px) -> Component.translatable("heroicmap.menue.px", px), m.aufloesung())
                .withValues(1, 2, 4, 8, 16)
                .create(x, y + 24, breite, 20, Component.translatable("heroicmap.menue.massstab"), (b, px) -> m.setzeScale(px)));
        addRenderableWidget(CycleButton.onOffBuilder(m.chunklinien())
                .create(x, y + 48, breite, 20, Component.translatable("heroicmap.menue.chunklinien"), (b, an) -> m.setzeChunklinien(an)));
        addRenderableWidget(CycleButton.builder((Downloads.Ablage a) -> Component.translatable(
                        "heroicmap.menue.ablage." + a.name().toLowerCase(Locale.ROOT)), m.ablage())
                .withValues(Downloads.Ablage.values())
                .create(x, y + 72, breite, 20, Component.translatable("heroicmap.menue.ablage"), (b, a) -> m.setzeAblage(a)));
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(x, y + 100, breite, 20).build());
    }

    private int oben() {
        return Math.max(30, height / 2 - 60);
    }

    /** Ohne Unschärfe und Abdunkeln, damit die Minimap im HUD zu sehen ist. */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
        super.extractRenderState(g, mausX, mausY, delta);
        int[] s = Einstellungen.spalte(width, height);
        g.centeredText(font, title, s[0] + s[1] / 2, oben() - 14, TEXT);
    }

    @Override
    public void removed() {
        Minimap.INSTANZ.schreibe(HeroicMap.einstellungen());
        // Eine andere Ablage heisst ein anderer Ordner der Welt, auch für die Wegpunkte.
        Wegpunkte.INSTANZ.wechsel(Downloads.weltOrdner());
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
