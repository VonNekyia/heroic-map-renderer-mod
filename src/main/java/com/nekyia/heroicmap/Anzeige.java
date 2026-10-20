package com.nekyia.heroicmap;

import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Das Untermenü „Einstellungen …“ für Vorlieben der Anzeige: Form, Bei F3, Auflösung, Todespunkt, Chunklinien, Drehen, Verzierungen, Effekte in der
 * Welt, der Schleier eigener Regionen, die Ablage der Karten, die Koordinaten unter der Minimap, die Frage nach einer neuen Version
 * und das Untermenü „Ebenen …“. Die Minimap im HUD bleibt sichtbar,
 * jede Wahl wirkt gleich; den Spieler und den Rahmen stellt ein Klick auf die Minimap ein ({@link MinimapKlick}).
 * Gespeichert wird beim Schliessen. Siehe docs/minimap.md, „Bedienung“.
 */
final class Anzeige extends Screen {

    private static final int TEXT = 0xFFFFFFFF;

    private final Screen zurueck;
    private final MinimapKlick klick = new MinimapKlick();

    Anzeige(Screen zurueck) {
        super(Component.translatable("heroicmap.anzeige.titel"));
        this.zurueck = zurueck;
    }

    @Override
    protected void init() {
        Minimap m = Minimap.INSTANZ;
        int[] s = Einstellungen.spalte(width, height);
        int x = s[0], breite = s[1], y = oben();
        // Je zwei Schalter teilen sich eine Zeile, so passt das Untermenü auch bei 240 Einheiten Höhe.
        int halb = (breite - 4) / 2;
        addRenderableWidget(CycleButton.booleanBuilder(Component.translatable("heroicmap.menue.rund"),
                        Component.translatable("heroicmap.menue.eckig"), m.rund())
                .create(x, y, halb, 20, Component.translatable("heroicmap.menue.form"), (b, rund) -> m.setzeRund(rund)));
        addRenderableWidget(CycleButton.builder((Minimap.BeiF3 f) -> Component.translatable(
                        "heroicmap.menue.bei_f3." + f.name().toLowerCase(Locale.ROOT)), m.beiF3())
                .withValues(Minimap.BeiF3.values())
                .withTooltip(f -> Tooltip.create(Component.translatable("heroicmap.menue.bei_f3.beschreibung")))
                .create(x + breite - halb, y, halb, 20, Component.translatable("heroicmap.menue.bei_f3"), (b, f) -> m.setzeBeiF3(f)));
        // Kurz, so passt neben ihr der Todespunkt; der volle Text steht im Tooltip. Siehe docs/minimap.md, „Bedienung“.
        addRenderableWidget(CycleButton.builder((Integer px) -> Component.translatable("heroicmap.menue.px_kurz", px), m.aufloesung())
                .withValues(1, 2, 4, 8, 16)
                .withTooltip(px -> Tooltip.create(Component.translatable("heroicmap.menue.aufloesung.beschreibung")))
                .create(x, y + 24, halb, 20, Component.translatable("heroicmap.menue.aufloesung"), (b, px) -> m.setzeScale(px)));
        addRenderableWidget(new TodRegler(x + breite - halb, y + 24, halb));
        addRenderableWidget(CycleButton.onOffBuilder(m.chunklinien())
                .create(x, y + 48, halb, 20, Component.translatable("heroicmap.menue.chunklinien"), (b, an) -> m.setzeChunklinien(an)));
        addRenderableWidget(CycleButton.onOffBuilder(m.drehen())
                .create(x + breite - halb, y + 48, halb, 20, Component.translatable("heroicmap.menue.drehen"), (b, an) -> m.setzeDrehen(an)));
        addRenderableWidget(CycleButton.onOffBuilder(m.verzierungen())
                .withTooltip(an -> Tooltip.create(Component.translatable("heroicmap.menue.verzierungen.beschreibung")))
                .create(x, y + 72, halb, 20, Component.translatable("heroicmap.menue.verzierungen"), (b, an) -> m.setzeVerzierungen(an)));
        addRenderableWidget(CycleButton.builder((Minimap.Koordinaten k) -> Component.translatable(
                        "heroicmap.menue.koordinaten." + k.name().toLowerCase(Locale.ROOT)), m.koordinaten())
                .withValues(Minimap.Koordinaten.values())
                .create(x + breite - halb, y + 72, halb, 20, Component.translatable("heroicmap.menue.koordinaten"), (b, k) -> m.setzeKoordinaten(k)));
        addRenderableWidget(CycleButton.onOffBuilder(m.effekte())
                .withTooltip(an -> Tooltip.create(Component.translatable("heroicmap.menue.effekte.beschreibung")))
                .create(x, y + 96, halb, 20, Component.translatable("heroicmap.menue.effekte"), (b, an) -> m.setzeEffekte(an)));
        addRenderableWidget(new SchleierRegler(x + breite - halb, y + 96, halb));
        addRenderableWidget(CycleButton.builder((Downloads.Ablage a) -> Component.translatable(
                        "heroicmap.menue.ablage." + a.name().toLowerCase(Locale.ROOT)), m.ablage())
                .withValues(Downloads.Ablage.values())
                .create(x, y + 120, breite, 20, Component.translatable("heroicmap.menue.ablage"), (b, a) -> m.setzeAblage(a)));
        addRenderableWidget(CycleButton.onOffBuilder(m.neueVersion())
                .withTooltip(an -> Tooltip.create(Component.translatable("heroicmap.menue.neue_version.beschreibung")))
                .create(x, y + 144, breite, 20, Component.translatable("heroicmap.menue.neue_version"), (b, an) -> {
                    m.setzeNeueVersion(an);
                    // Angeschaltet fragt der Mod, wenn er in diesem Start noch nicht gefragt hat. Siehe docs/vollbildkarte.md, „Neue Version“.
                    NeueVersion.frage();
                }));
        addRenderableWidget(Button.builder(Component.translatable("heroicmap.menue.ebenen"),
                b -> minecraft.gui.setScreen(new EbenenMenue(this))).bounds(x, y + 172, halb, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(x + breite - halb, y + 172, halb, 20).build());
    }

    /** Der Regler für die Höhe des Schleiers eigener Regionen, in ganzen Blöcken. Siehe docs/wegpunkte.md, „Schleier“. */
    static final class SchleierRegler extends AbstractSliderButton {

        private static final int SPANNE = Schleier.MAX_HOEHE - Schleier.HOEHE;

        SchleierRegler(int x, int y, int breite) {
            super(x, y, breite, 20, Component.empty(), (double) (Minimap.INSTANZ.schleier() - Schleier.HOEHE) / SPANNE);
            setTooltip(Tooltip.create(Component.translatable("heroicmap.menue.schleier.beschreibung")));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("heroicmap.menue.schleier", Minimap.INSTANZ.schleier()));
        }

        @Override
        protected void applyValue() {
            Minimap.INSTANZ.setzeSchleier(Schleier.HOEHE + (int) Math.round(value * SPANNE));
        }
    }

    /** Der Regler, wie viele Todespunkte der Mod behält: aus, 1 bis 16. Siehe docs/wegpunkte.md, „Todespunkt“. */
    static final class TodRegler extends AbstractSliderButton {

        TodRegler(int x, int y, int breite) {
            super(x, y, breite, 20, Component.empty(), (double) Minimap.INSTANZ.todespunkte() / Minimap.MAX_TODESPUNKTE);
            setTooltip(Tooltip.create(Component.translatable("heroicmap.menue.todespunkt.beschreibung")));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int n = Minimap.INSTANZ.todespunkte();
            setMessage(Component.translatable("heroicmap.menue.todespunkte", n == 0 ? Component.translatable("options.off") : Component.literal(
                    Integer.toString(n))));
        }

        @Override
        protected void applyValue() {
            Minimap.INSTANZ.setzeTodespunkte((int) Math.round(value * Minimap.MAX_TODESPUNKTE));
        }
    }

    /** Oben so weit, dass alles mittig steht, aber Platz für den Titel bleibt; bei 240 Einheiten Höhe reicht „Fertig“ bis 238. */
    private int oben() {
        return Math.max(30, height / 2 - 74);
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
        klick.zeichne(g, font, mausX, mausY, true);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doppelt) {
        return super.mouseClicked(e, doppelt) || Minimap.INSTANZ.sichtbar() && klick.druecke(e);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        return klick.lasse(e) || super.mouseReleased(e);
    }

    @Override
    public void removed() {
        Minimap.INSTANZ.hervor(Minimap.KEIN_ZIEL);
        Minimap.INSTANZ.schreibe(HeroicMap.einstellungen());
        // Erst hier, nicht beim Ziehen des Reglers: Von 8 auf 1 und zurück nimmt sonst sieben weg.
        Wegpunkte.INSTANZ.kappeTode(Minimap.INSTANZ.todespunkte());
        // Eine andere Ablage heisst ein anderer Ordner der Welt, auch für Wegpunkte und Ebenen.
        Wegpunkte.INSTANZ.wechsel(Downloads.weltOrdner());
        Ebenen.INSTANZ.wechsel(Downloads.weltOrdner());
        Ebenenreihe.INSTANZ.wechsel(Downloads.weltOrdner());
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
