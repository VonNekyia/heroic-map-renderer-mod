package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.time.ZoneId;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
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
    /** Überschriften der Gruppen, hellgelb. */
    private static final int UEBERSCHRIFT = 0xFFFFFFA0;

    private final Screen zurueck;
    /** Der Wähler der Rahmen über dem Untermenü, wie im Menü. Siehe docs/rahmen.md, „Wähler“. */
    private final Waehler waehler = new Waehler();
    private final MinimapKlick klick = new MinimapKlick(waehler);
    /** Die Ablage, nach der Wegpunkte, Ebenen und Karten gerade liegen; ändert sie das Menü, ziehen sie nach. */
    private Downloads.Ablage ablage = Minimap.INSTANZ.ablage();

    /** Für die Gametests. */
    Waehler waehler() {
        return waehler;
    }
    /** Höhe einer Überschrift über ihrer Gruppe. */
    private static final int GRUPPE = 12;
    /** Eine Überschrift und wo sie steht. */
    private record Gruppe(Component text, int y) {
    }

    private final List<Gruppe> gruppen = new ArrayList<>();
    /** Breite des Bilds vor einem Regler, Höhe seiner Zeile, in Einheiten des GUI. */
    static final int BILD = 56, ZEILE = 30;
    /** So hoch ist das Untermenü mit offener Technik, von der ersten Überschrift bis unter „Fertig“. */
    private static final int HOEHE = 2 * (GRUPPE + ZEILE) + 24 + 3 * 24 + 4 + 20;
    /** Der dunkle, halb durchsichtige Grund hinter den Bildern. */
    private static final int GRUND = 0x80000000;
    /** Wo die Region mit dem Schleier steht, links oben. */
    private int[] region;

    Anzeige(Screen zurueck) {
        super(Component.translatable("heroicmap.anzeige.titel"));
        this.zurueck = zurueck;
    }

    @Override
    protected void init() {
        Minimap m = Minimap.INSTANZ;
        int[] s = Einstellungen.spalte(width, height);
        int x = s[0], breite = s[1], y = oben();
        int halb = (breite - 4) / 2;
        // In Gruppen mit Überschrift; was die Minimap selbst angeht, stellt das Hauptmenü an ihr ein (mod#173, mod#175). Todespunkt
        // und Region stehen als Bild da, ihre Regler daneben. „Technik“ ist ein Knopf zum Auf- und Zuklappen, gemerkt in
        // heroicmap.properties. Siehe docs/minimap.md, „Bedienung“.
        gruppen.clear();
        gruppen.add(new Gruppe(Component.translatable("heroicmap.anzeige.gruppe.wegpunkte"), y));
        y += GRUPPE;
        addRenderableWidget(new TodBild(x, y));
        addRenderableWidget(new TodRegler(x + BILD + 4, y + 4, breite - BILD - 4));
        y += ZEILE;
        gruppen.add(new Gruppe(Component.translatable("heroicmap.anzeige.gruppe.welt"), y));
        y += GRUPPE;
        region = new int[] {x, y};
        Einstellungen.Symbol effekte = new Einstellungen.Symbol(Einstellungen.Symbol.EFFEKTE);
        effekte.setPosition(x + BILD + 4, y + 4);
        addRenderableWidget(effekte);
        int regler = BILD + 8 + Einstellungen.SYMBOL;
        addRenderableWidget(new SchleierRegler(x + regler, y + 4, breite - regler));
        y += ZEILE;
        addRenderableWidget(Button.builder(Component.translatable(m.technikOffen() ? "heroicmap.anzeige.gruppe.technik.auf"
                : "heroicmap.anzeige.gruppe.technik.zu"), b -> {
            m.setzeTechnikOffen(!m.technikOffen());
            rebuildWidgets();
        }).bounds(x, y, breite, 20).build());
        y += 24;
        if (m.technikOffen()) {
            addRenderableWidget(CycleButton.builder((Minimap.BeiF3 f) -> Component.translatable(
                            "heroicmap.menue.bei_f3." + f.name().toLowerCase(Locale.ROOT)), m.beiF3())
                    .withValues(Minimap.BeiF3.values())
                    .withTooltip(f -> Tooltip.create(Component.translatable("heroicmap.menue.bei_f3.beschreibung")))
                    .create(x, y, breite, 20, Component.translatable("heroicmap.menue.bei_f3"), (b, f) -> m.setzeBeiF3(f)));
            y += 24;
            addRenderableWidget(CycleButton.builder((Downloads.Ablage a) -> Component.translatable(
                            "heroicmap.menue.ablage." + a.name().toLowerCase(Locale.ROOT)), m.ablage())
                    .withValues(Downloads.Ablage.values())
                    .create(x, y, breite, 20, Component.translatable("heroicmap.menue.ablage"), (b, a) -> m.setzeAblage(a)));
            y += 24;
            addRenderableWidget(CycleButton.onOffBuilder(m.neueVersion())
                    .withTooltip(an -> Tooltip.create(Component.translatable("heroicmap.menue.neue_version.beschreibung")))
                    .create(x, y, breite, 20, Component.translatable("heroicmap.menue.neue_version"), (b, an) -> {
                        m.setzeNeueVersion(an);
                        // Angeschaltet fragt der Mod, wenn er in diesem Start noch nicht gefragt hat. Siehe docs/vollbildkarte.md, „Neue Version“.
                        NeueVersion.frage();
                    }));
            y += 24;
        }
        y += 4;
        addRenderableWidget(Button.builder(Component.translatable("heroicmap.menue.ebenen"),
                b -> minecraft.gui.setScreen(new EbenenMenue(this))).bounds(x, y, halb, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(x + breite - halb, y, halb, 20).build());
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

    /**
     * Oben so weit, dass alles mittig steht, aber Platz für den Titel bleibt, und auch mit offener Technik alles auf den Schirm
     * passt; bei 240 Einheiten Höhe reicht „Fertig“ dann bis 234. Die Technik verschiebt das Menü nicht.
     */
    private int oben() {
        return Math.max(30, Math.min(height / 2 - 74, height - HOEHE - 6));
    }

    /**
     * Der Todespunkt wie auf der Karte, darunter die Zeit im gewählten Stil, mit der Zeit von jetzt als Beispiel; bei keiner grau
     * „Zeit aus“. Ein Klick wechselt reihum Datum und Uhrzeit, Datum, Uhrzeit, keine. Siehe docs/wegpunkte.md, „Todespunkt“.
     */
    static final class TodBild extends AbstractButton {

        TodBild(int x, int y) {
            super(x, y, BILD, ZEILE - 2, Component.translatable("heroicmap.menue.tod_zeit"));
            tooltip();
        }

        private void tooltip() {
            Minimap.TodZeit stil = Minimap.INSTANZ.todZeit();
            setTooltip(Tooltip.create(Component.translatable("heroicmap.menue.symbol.klick", getMessage(), name(stil), name(stil.naechste()))));
        }

        private static Component name(Minimap.TodZeit stil) {
            return Component.translatable("heroicmap.menue.tod_zeit." + stil.name().toLowerCase(Locale.ROOT));
        }

        @Override
        public void onPress(InputWithModifiers e) {
            Minimap.INSTANZ.setzeTodZeit(Minimap.INSTANZ.todZeit().naechste());
            tooltip();
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
            int x = getX(), y = getY();
            g.fill(x, y, x + width, y + height, isHoveredOrFocused() ? 0xA0303030 : GRUND);
            float mx = x + width / 2f, my = y + 9, groesse = 14;
            Minimap.todespunkt(g, mx, my, groesse);
            // Unter der unteren Spitze der Raute, wie auf der Karte.
            float fuss = (float) (6 * Math.sqrt(2) * groesse / 16);
            Minimap.TodZeit stil = Minimap.INSTANZ.todZeit();
            Font font = Minecraft.getInstance().font;
            if (stil == Minimap.TodZeit.AUS) {
                g.centeredText(font, Component.translatable("heroicmap.menue.tod_zeit_aus"), (int) mx, (int) (my + fuss + 2), 0xFF8A8A8A);
                return;
            }
            g.pose().pushMatrix();
            g.pose().translate(mx, my + fuss);
            Ebenen.name(g, font, Wegpunkte.todZeit(System.currentTimeMillis(), ZoneId.systemDefault(),
                    Component.translatable(stil.schluessel).getString(), stil.fest));
            g.pose().popMatrix();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput ausgabe) {
            defaultButtonNarrationText(ausgabe);
        }
    }

    /**
     * Eine Region mit Schleier, wie in der Welt, als Bild in (x, y, {@link #BILD} × {@link #ZEILE} − 2): ein Stück Boden schräg
     * von oben in der Farbe einer eigenen Region, an seinen Kanten der Schleier, {@code hoehe} Blöcke als Einheiten hoch, unten so
     * deckend wie in der Welt ({@link Schleier#deckung}), oben durchsichtig. Siehe docs/wegpunkte.md, „Schleier“.
     */
    static void schleierBild(GuiGraphicsExtractor g, int x, int y, int hoehe) {
        g.fill(x, y, x + BILD, y + ZEILE - 2, GRUND);
        int farbe = Wegpunkte.FARBEN[1] & 0xFFFFFF, unten = Math.round(Schleier.deckung(hoehe) * 255) << 24 | farbe;
        // Hinten von x0 + tiefe bis x1 + tiefe auf yb, vorn von x0 bis x1 auf yf; je Zeile eine Einheit nach links.
        int tiefe = 8, x0 = x + 6, x1 = x + BILD - 6 - tiefe, yf = y + ZEILE - 5, yb = yf - tiefe;
        // Hinten und die Seiten zuerst, dann der Boden, vorn zuletzt.
        g.fillGradient(x0 + tiefe, yb - hoehe, x1 + tiefe, yb, farbe, unten);
        for (int c = 0; c < tiefe; c++) {
            g.fillGradient(x0 + c, yf - c - hoehe, x0 + c + 1, yf - c, farbe, unten);
            g.fillGradient(x1 + c, yf - c - hoehe, x1 + c + 1, yf - c, farbe, unten);
        }
        for (int r = 0; r <= tiefe; r++) {
            g.fill(x0 + tiefe - r, yb + r, x1 + tiefe - r, yb + r + 1, 0x50000000 | farbe);
            g.fill(x0 + tiefe - r, yb + r, x0 + tiefe - r + 1, yb + r + 1, 0xFF000000 | farbe);
            g.fill(x1 + tiefe - r - 1, yb + r, x1 + tiefe - r, yb + r + 1, 0xFF000000 | farbe);
        }
        g.fill(x0 + tiefe, yb, x1 + tiefe, yb + 1, 0xFF000000 | farbe);
        g.fill(x0, yf, x1, yf + 1, 0xFF000000 | farbe);
        g.fillGradient(x0, yf - hoehe, x1, yf, farbe, unten);
    }

    /** Ohne Unschärfe und Abdunkeln, damit die Minimap im HUD zu sehen ist. */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
        // Ist der Wähler offen, gilt die Maus nur ihm: das Menü darunter ohne Hover und Tooltip.
        int echtX = mausX, echtY = mausY;
        if (waehler.offen()) {
            mausX = -1;
            mausY = -1;
        }
        super.extractRenderState(g, mausX, mausY, delta);
        int[] s = Einstellungen.spalte(width, height);
        g.centeredText(font, title, s[0] + s[1] / 2, oben() - 14, TEXT);
        for (Gruppe gruppe : gruppen) {
            g.text(font, gruppe.text(), s[0], gruppe.y() + 1, UEBERSCHRIFT, true);
        }
        schleierBild(g, region[0], region[1], Minimap.INSTANZ.schleier());
        klick.zeichne(g, font, mausX, mausY, true);
        if (waehler.offen()) {
            waehler.zeichne(g, font, echtX, echtY, width, height);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent ereignis) {
        // Offen nimmt der Wähler jede Taste; Esc schliesst nur ihn, nicht das Menü.
        if (waehler.offen()) {
            if (ereignis.key() == InputConstants.KEY_ESCAPE) {
                waehler.schliesse();
            }
            return true;
        }
        return super.keyPressed(ereignis);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doppelt) {
        if (waehler.offen()) {
            waehler.klick(e.x(), e.y(), width, height);
            return true;
        }
        return super.mouseClicked(e, doppelt) || Minimap.INSTANZ.sichtbar() && klick.druecke(e);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        return klick.lasse(e) || waehler.offen() || super.mouseReleased(e);
    }

    @Override
    public void removed() {
        Minimap.INSTANZ.hervor(Minimap.KEIN_ZIEL);
        Minimap.INSTANZ.schreibe(HeroicMap.einstellungen());
        // Erst hier, nicht beim Ziehen des Reglers: Von 8 auf 1 und zurück nimmt sonst sieben weg.
        Wegpunkte.INSTANZ.kappeTode(Minimap.INSTANZ.todespunkte());
        // Eine andere Ablage heisst ein anderer Ordner der Welt, auch für Wegpunkte und Ebenen; die Welt zieht erst um.
        Runnable lies = () -> {
            Wegpunkte.INSTANZ.wechsel(Downloads.weltOrdner());
            Ebenen.INSTANZ.wechsel(Downloads.weltOrdner());
            Ebenenreihe.INSTANZ.wechsel(Downloads.weltOrdner());
        };
        if (Minimap.INSTANZ.ablage() == ablage) {
            lies.run();
            return;
        }
        ablage = Minimap.INSTANZ.ablage();
        Downloads.INSTANZ.ablageGewechselt(() -> {
            lies.run();
            Selbst.INSTANZ.neuGewaehlt(Minecraft.getInstance());
        });
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
