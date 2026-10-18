package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Das Untermenü „Ebenen …“: zuerst die eigenen Ebenen mit Schalter, Umbenennen und Löschen, dazu „Neue Ebene …“, dann je
 * Ebene vom Server ein Schalter, die oberste zuerst. Passen nicht alle auf den Schirm, auf Seiten. Jede Wahl wirkt und
 * speichert gleich. Siehe docs/ebenen.md, „Umschalten“.
 * Siehe docs/wegpunkte.md, „Eigene Ebenen“.
 */
final class EbenenMenue extends Screen {

    private static final int TEXT = 0xFFFFFFFF;

    private final Screen zurueck;
    private int seite;
    /** Welche eigene Ebene gerade umbenannt wird, {@link #NEU} beim Anlegen, sonst -1; dann steht in ihrer Zeile das Feld. */
    private int bearbeitet = -1;
    private EditBox feld;
    private static final int NEU = -2, KLEIN = 20;
    /**
     * Neu aufbauen im nächsten Tick, nicht im Klick: Nach dem Klick auf einen Knopf gibt das Spiel diesem den Fokus, auch wenn er
     * beim Aufbau schon weg ist; das Feld bekäme dann keine Taste.
     */
    private boolean neuBauen;
    /** Wo der Hinweis ohne Ebenen vom Server steht: unter „Fertig“. */
    private int hinweisY;
    /** Was Kopieren oder Einfügen zuletzt meldete, unter „Fertig“, oder null. */
    private Component meldung;

    EbenenMenue(Screen zurueck) {
        super(Component.translatable("heroicmap.ebenen.titel"));
        this.zurueck = zurueck;
    }

    @Override
    protected void init() {
        int[] s = Einstellungen.spalte(width, height);
        int x = s[0], breite = s[1], y = oben();
        feld = null;
        boolean deutsch = minecraft.getLanguageManager().getSelected().startsWith("de");
        // Je Zeile eine Aufgabe, die bei y baut: die eigenen Ebenen, „Neue Ebene …“, dann die vom Server.
        List<IntConsumer> zeilen = new ArrayList<>();
        for (Wegpunkte.EigeneEbene e : Wegpunkte.INSTANZ.ebenen()) {
            zeilen.add(z -> eigene(e, x, z, breite));
        }
        zeilen.add(z -> neue(x, z, breite));
        for (Ebenen.Eintrag e : Ebenen.INSTANZ.alle()) {
            zeilen.add(z -> addRenderableWidget(CycleButton.onOffBuilder(Ebenen.INSTANZ.an(e))
                    .create(x, z, breite, 20, Component.literal(e.name(deutsch)), (b, an) -> Ebenen.INSTANZ.setze(e.id(), an))));
        }
        int jeSeite = Math.max(1, (height - y - 60) / 24), seiten = Math.max(1, Math.ceilDiv(zeilen.size(), jeSeite));
        seite = Math.min(seite, seiten - 1);
        List<IntConsumer> hier = zeilen.subList(seite * jeSeite, Math.min(zeilen.size(), (seite + 1) * jeSeite));
        for (int i = 0; i < hier.size(); i++) {
            hier.get(i).accept(y + 24 * i);
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
        hinweisY = unten + 28;
    }

    /** Die Zeile einer eigenen Ebene: Schalter, dahinter Umbenennen und Löschen, ausser bei Base; beim Umbenennen das Feld. */
    private void eigene(Wegpunkte.EigeneEbene e, int x, int y, int breite) {
        if (bearbeitet == e.id()) {
            feld(e.name(), x, y, breite);
            return;
        }
        boolean base = e.id() == Wegpunkte.BASE;
        int w = breite - (base ? 1 : 3) * (KLEIN + 4);
        addRenderableWidget(CycleButton.onOffBuilder(e.an())
                .create(x, y, w, 20, Component.literal(e.name()), (b, an) -> Wegpunkte.INSTANZ.schalteEbene(e.id(), an)));
        // Als Code in die Zwischenablage, auch Base. Siehe docs/wegpunkte.md, „Ebenen teilen“.
        addRenderableWidget(Button.builder(Component.literal("⧉"), b -> {
            String code = EbenenCode.schreibe(Wegpunkte.INSTANZ, e.id());
            minecraft.keyboardHandler.setClipboard(code);
            meldung = Component.translatable("heroicmap.ebenen.kopiert", e.name(), code.length());
        }).bounds(x + w + 4, y, KLEIN, 20).tooltip(Tooltip.create(Component.translatable("heroicmap.ebenen.kopieren"))).build());
        if (!base) {
            addRenderableWidget(Button.builder(Component.literal("✎"), b -> bearbeite(e.id())).bounds(x + w + 8 + KLEIN, y, KLEIN, 20)
                    .tooltip(Tooltip.create(Component.translatable("heroicmap.ebenen.umbenennen"))).build());
            addRenderableWidget(Button.builder(Component.literal("✕"), b -> {
                Wegpunkte.INSTANZ.loescheEbene(e.id());
                neuBauen = true;
            }).bounds(x + w + 12 + 2 * KLEIN, y, KLEIN, 20).tooltip(Tooltip.create(Component.translatable("heroicmap.ebenen.loeschen"))).build());
        }
    }

    /** „Neue Ebene …“ und „Code einfügen“ nebeneinander, beim Anlegen das Feld. */
    private void neue(int x, int y, int breite) {
        if (bearbeitet == NEU) {
            feld("", x, y, breite);
            return;
        }
        int halb = (breite - 4) / 2;
        addRenderableWidget(Button.builder(Component.translatable("heroicmap.ebenen.neu"), b -> bearbeite(NEU)).bounds(x, y, halb, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("heroicmap.ebenen.einfuegen"), b -> einfuegen())
                .bounds(x + breite - halb, y, halb, 20).build());
    }

    /** Liest den Code in der Zwischenablage und legt ihn als neue Ebene an; sonst sagt es, warum nicht. */
    private void einfuegen() {
        EbenenCode.Gelesen g = EbenenCode.lies(minecraft.keyboardHandler.getClipboard());
        if (g.fehler() != null) {
            meldung = Component.translatable("heroicmap.ebenen.code." + g.fehler());
            return;
        }
        Wegpunkte.Eingefuegt r = Wegpunkte.INSTANZ.fuegeEin(g.inhalt());
        meldung = r.fehler() != null ? Component.translatable("heroicmap.ebenen.code." + r.fehler())
                : Component.translatable("heroicmap.ebenen.eingefuegt", Wegpunkte.INSTANZ.ebene(r.ebene()).name(), r.punkte(), r.formen());
        neuBauen = true;
    }

    private void feld(String wert, int x, int y, int breite) {
        feld = addRenderableWidget(new EditBox(font, x, y, breite, 20, Component.translatable("heroicmap.ebenen.neu")));
        feld.setMaxLength(Wegpunkte.MAX_NAME);
        feld.setValue(wert);
        feld.setResponder(t -> feld.setTextColor(EditBox.DEFAULT_TEXT_COLOR));
        setFocused(feld);
    }

    private void bearbeite(int id) {
        bearbeitet = id;
        neuBauen = true;
    }

    @Override
    public void tick() {
        if (neuBauen) {
            neuBauen = false;
            rebuildWidgets();
        }
    }

    /** Im Feld speichert Enter, Escape bricht nur das Feld ab; ein leerer Name bleibt rot stehen. */
    @Override
    public boolean keyPressed(KeyEvent ereignis) {
        if (feld != null) {
            int k = ereignis.key();
            if (k == InputConstants.KEY_ESCAPE) {
                bearbeite(-1);
                return true;
            }
            if (k == InputConstants.KEY_RETURN || k == InputConstants.KEY_NUMPADENTER) {
                boolean gut = bearbeitet == NEU ? Wegpunkte.INSTANZ.neueEbene(feld.getValue()) >= 0
                        : Wegpunkte.INSTANZ.benenneEbene(bearbeitet, feld.getValue());
                if (gut) {
                    bearbeite(-1);
                } else {
                    feld.setTextColor(0xFFFF5555);
                }
                return true;
            }
            feld.keyPressed(ereignis);
            return true;
        }
        return super.keyPressed(ereignis);
    }

    private void blaettere(int richtung) {
        seite += richtung;
        rebuildWidgets();
    }

    private int oben() {
        return Math.max(30, height / 2 - 84);
    }

    /** Für den Gametest: die letzte Meldung, oder null. */
    String meldung() {
        return meldung == null ? null : meldung.getString();
    }

    /** Für den Gametest: der Text im Feld, oder null, wenn keins offen ist. */
    String feld() {
        return feld == null ? null : feld.getValue();
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
        int y = hinweisY;
        if (meldung != null) {
            g.centeredText(font, meldung, s[0] + s[1] / 2, y, TEXT);
            y += 12;
        }
        if (Ebenen.INSTANZ.alle().isEmpty()) {
            // Unter „Fertig“; oben beginnt die Liste mit den eigenen Ebenen.
            g.centeredText(font, Component.translatable("heroicmap.ebenen.keine"), s[0] + s[1] / 2, y, TEXT);
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
