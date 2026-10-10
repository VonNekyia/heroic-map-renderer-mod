package com.nekyia.heroicmap;

import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Das Untermenü „Einstellungen …“ für Vorlieben der Anzeige: Form, Auflösung, Chunklinien, der eigene Spieler, Drehen,
 * Verzierungen, Rahmen, die Ablage der Karten, die Koordinaten unter der Minimap und das Untermenü „Ebenen …“. Die Minimap im HUD bleibt sichtbar, jede Wahl wirkt gleich. Gespeichert wird
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
        // Je zwei Schalter teilen sich eine Zeile, so passt das Untermenü auch bei 240 Einheiten Höhe.
        int halb = (breite - 4) / 2;
        addRenderableWidget(CycleButton.onOffBuilder(m.chunklinien())
                .create(x, y + 48, halb, 20, Component.translatable("heroicmap.menue.chunklinien"), (b, an) -> m.setzeChunklinien(an)));
        addRenderableWidget(CycleButton.builder((Minimap.Darstellung d) -> Component.translatable(
                        "heroicmap.menue.spieler." + d.name().toLowerCase(Locale.ROOT)), m.darstellung())
                .withValues(Minimap.Darstellung.values())
                .withTooltip(d -> Tooltip.create(Component.translatable("heroicmap.menue.spieler.beschreibung")))
                .create(x + breite - halb, y + 48, halb, 20, Component.translatable("heroicmap.menue.spieler"),
                        (b, d) -> m.setzeDarstellung(d)));
        addRenderableWidget(CycleButton.onOffBuilder(m.drehen())
                .create(x, y + 72, halb, 20, Component.translatable("heroicmap.menue.drehen"), (b, an) -> m.setzeDrehen(an)));
        addRenderableWidget(CycleButton.onOffBuilder(m.verzierungen())
                .withTooltip(an -> Tooltip.create(Component.translatable("heroicmap.menue.verzierungen.beschreibung")))
                .create(x + breite - halb, y + 72, halb, 20, Component.translatable("heroicmap.menue.verzierungen"),
                        (b, an) -> m.setzeVerzierungen(an)));
        addRenderableWidget(CycleButton.builder((String skin) -> Component.translatable("heroicmap.rahmen." + skin), m.skin())
                .withValues(Skin.NAMEN)
                .withTooltip(skin -> Tooltip.create(Component.translatable("heroicmap.rahmen." + skin + ".beschreibung")))
                .create(x, y + 96, breite, 20, Component.translatable("heroicmap.menue.rahmen"), (b, skin) -> m.setzeSkin(skin)));
        addRenderableWidget(CycleButton.builder((Downloads.Ablage a) -> Component.translatable(
                        "heroicmap.menue.ablage." + a.name().toLowerCase(Locale.ROOT)), m.ablage())
                .withValues(Downloads.Ablage.values())
                .create(x, y + 120, breite, 20, Component.translatable("heroicmap.menue.ablage"), (b, a) -> m.setzeAblage(a)));
        addRenderableWidget(CycleButton.builder((Minimap.Koordinaten k) -> Component.translatable(
                        "heroicmap.menue.koordinaten." + k.name().toLowerCase(Locale.ROOT)), m.koordinaten())
                .withValues(Minimap.Koordinaten.values())
                .create(x, y + 144, halb, 20, Component.translatable("heroicmap.menue.koordinaten"), (b, k) -> m.setzeKoordinaten(k)));
        addRenderableWidget(Button.builder(Component.translatable("heroicmap.menue.ebenen"),
                b -> minecraft.gui.setScreen(new EbenenMenue(this))).bounds(x + breite - halb, y + 144, halb, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(x, y + 172, breite, 20).build());
    }

    private int oben() {
        return Math.max(30, height / 2 - 84);
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
        // Eine andere Ablage heisst ein anderer Ordner der Welt, auch für Wegpunkte und Ebenen.
        Wegpunkte.INSTANZ.wechsel(Downloads.weltOrdner());
        Ebenen.INSTANZ.wechsel(Downloads.weltOrdner());
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
