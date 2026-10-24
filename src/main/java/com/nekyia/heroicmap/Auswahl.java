package com.nekyia.heroicmap;

import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Die Karten, die der Server anbietet, je Baum ein Knopf je Massstab mit seiner Grösse. Ein Knopf
 * fragt wie {@code /hmap laden} erst im Dialog nach; den Massstab, den der Spieler schon ganz hat,
 * gleicht er ab wie {@code /hmap abgleich}. Darüber die Wahl „Selbst“ für die Dimension des
 * Spielers. Siehe docs/vollbildkarte.md, „Bedienung“;
 * „Selbst“: siehe docs/selbst.md, „Wahl“.
 */
final class Auswahl extends Screen {

    private static final int TEXT = 0xFFFFFFFF;
    /** Höhe eines Baums: Name, darunter die Knöpfe. */
    private static final int ZEILE = 38;
    /** Unter Titel und Zeile „Server“ beginnen die Zeilen. */
    private static final int OBEN = 40;

    private final Screen zurueck;
    private List<Downloads.Baum> baeume = List.of();
    /** Geht „Selbst“, und ist es an? Dazu die Dimension des Spielers. */
    private boolean selbst, selbstAn;
    /** Wurde hier „Selbst“ gewählt? Dann zeigt die Karte beim Zurückgehen die eigene. */
    private boolean gewaehlt;
    /** Der Massstab für „Selbst“, Pixel je Block. */
    private int massstab = Selbst.MASSSTAB;
    private String dimension;
    /** Was beim letzten Knopf schiefging, oder null. */
    private Component hinweis;

    Auswahl(Screen zurueck) {
        super(Component.translatable("heroicmap.auswahl.titel"));
        this.zurueck = zurueck;
    }

    @Override
    protected void init() {
        baeume = Downloads.INSTANZ.baeume();
        selbst = Selbst.INSTANZ.moeglich(minecraft);
        selbstAn = Selbst.INSTANZ.an(minecraft);
        dimension = minecraft.level == null ? "" : minecraft.level.dimension().identifier().toString();
        int y = OBEN;
        if (selbst && !selbstAn && Selbst.INSTANZ.vorhanden(minecraft)) {
            // Die eigene Karte ruht: ein Klick zeigt sie wieder, der Download ruht dann. Siehe docs/selbst.md, „Wahl“.
            addRenderableWidget(Button.builder(Component.translatable("heroicmap.selbst.ruht", Selbst.INSTANZ.massstab(minecraft)), b -> {
                hinweis = Selbst.INSTANZ.wiederAn(minecraft);
                if (hinweis == null) {
                    gewaehlt = true;
                    hinweis = Component.translatable("heroicmap.selbst.wieder_an");
                }
                rebuildWidgets();
            }).bounds(width / 2 - 60, y + 12, 120, 20).build());
            y += ZEILE;
        } else if (selbst && selbstAn) {
            Button knopf = Button.builder(Component.translatable("heroicmap.selbst.an", Selbst.INSTANZ.massstab(minecraft)), b -> {
            }).bounds(width / 2 - 60, y + 12, 120, 20).build();
            knopf.active = false;
            addRenderableWidget(knopf);
            y += ZEILE;
        } else if (selbst) {
            // Der Massstab steht fest, bis die Karte in der Kartenliste gelöscht ist. Siehe docs/selbst.md, „Massstab“.
            addRenderableWidget(CycleButton.builder((Integer px) -> Component.translatable("heroicmap.menue.px", px), massstab)
                    .withValues(Selbst.MASSSTAEBE)
                    .withTooltip(px -> Tooltip.create(Component.translatable("heroicmap.selbst.massstab.beschreibung")))
                    .create(width / 2 - 94, y + 12, 90, 20, Component.translatable("heroicmap.selbst.massstab"), (b, px) -> massstab = px));
            addRenderableWidget(Button.builder(Component.translatable("heroicmap.selbst.knopf"), b -> frageSelbst())
                    .bounds(width / 2 + 4, y + 12, 90, 20).build());
            y += ZEILE;
        }
        for (Downloads.Baum baum : baeume) {
            // Die Knöpfe teilen sich die Breite des Schirms, höchstens 90 Einheiten je Knopf.
            int n = Math.max(1, baum.bytes().size());
            int breite = Math.min(90, (width - 20 - (n - 1) * 4) / n);
            int x = (width - n * breite - (n - 1) * 4) / 2;
            int hat = Downloads.INSTANZ.vollstaendig(baum.id());
            for (Map.Entry<Integer, Long> massstab : baum.bytes().entrySet()) {
                int m = massstab.getKey();
                Button knopf;
                if (m == hat && Downloads.INSTANZ.ruht(baum.id())) {
                    // Eine ruhende Karte bekommt keinen Abgleich. Siehe docs/download.md, „Ablage“.
                    knopf = Button.builder(Component.translatable("heroicmap.auswahl.ruht", m), b -> {
                    }).bounds(x, y + 12, breite, 20).tooltip(Tooltip.create(Component.translatable("heroicmap.auswahl.ruht.beschreibung"))).build();
                    knopf.active = false;
                } else if (m == hat) {
                    // Den Massstab hat der Spieler schon ganz: Abgleich statt vollem Download.
                    long ab = Downloads.INSTANZ.abgleichAb(baum.id());
                    knopf = Button.builder(ab == 0 ? Component.translatable("heroicmap.auswahl.abgleich", m)
                                    : Component.translatable("heroicmap.auswahl.abgleich_ab", m, Downloads.uhr(ab)),
                            b -> hinweis = Downloads.INSTANZ.frageAbgleich(baum.id())).bounds(x, y + 12, breite, 20).build();
                    knopf.active = ab == 0;
                } else {
                    knopf = Button.builder(
                            Component.translatable("heroicmap.auswahl.massstab", m, Downloads.groesse(massstab.getValue())),
                            b -> hinweis = Downloads.INSTANZ.frageVoll(baum.id(), m)).bounds(x, y + 12, breite, 20).build();
                }
                addRenderableWidget(knopf);
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
        Component server = server();
        if (server != null) {
            g.centeredText(font, server, width / 2, 27, TEXT);
        }
        if (baeume.isEmpty()) {
            g.centeredText(font, Component.translatable("heroicmap.angebot.keins"), width / 2, height / 2, TEXT);
        }
        int y = OBEN;
        if (selbst) {
            g.centeredText(font, Component.translatable("heroicmap.selbst.zeile", dimension),
                    width / 2, y, TEXT);
            y += ZEILE;
        }
        for (Downloads.Baum baum : baeume) {
            g.centeredText(font, baum.name() + "  (" + baum.dimension() + ")", width / 2, y, TEXT);
            y += ZEILE;
        }
        if (hinweis != null) {
            g.centeredText(font, hinweis, width / 2, height - 44, TEXT);
        }
        super.extractRenderState(g, mausX, mausY, delta);
    }

    /**
     * Die Zeile „Server“: erkannt mit Namen, wartet auf die Antwort, ohne Namen oder ohne Heroic Map; im Einzelspieler
     * keine. Siehe docs/selbst.md, „Wahl“.
     */
    private static Component server() {
        if (Downloads.server() == null) {
            return null;
        }
        if (!Downloads.INSTANZ.serverFest()) {
            return Component.translatable("heroicmap.auswahl.server.wartet").withStyle(ChatFormatting.YELLOW);
        }
        String wer = Downloads.INSTANZ.wer();
        if (!Zeigt.OHNE.equals(wer)) {
            return Component.translatable("heroicmap.auswahl.server.erkannt", wer).withStyle(ChatFormatting.GREEN);
        }
        return Component.translatable(Downloads.INSTANZ.beantwortet() ? "heroicmap.auswahl.server.ohne_namen"
                : "heroicmap.auswahl.server.ohne").withStyle(ChatFormatting.GRAY);
    }

    /**
     * „Selbst“ erst nach Rückfrage: Ab dann zeigt die Vollbildkarte auf diesem Server die eigene Karte. Zeigt er schon
     * einen Download der Dimension, fragt der Mod, was mit ihm geschieht. Siehe docs/selbst.md, „Wahl“.
     */
    private void frageSelbst() {
        Satz gezeigt = Satz.fuer(Selbst.INSTANZ.weltOrdner(), dimension, Downloads.INSTANZ.wer());
        if (gezeigt != null && !Selbst.selbst(gezeigt.ordner().getParent())) {
            minecraft.gui.setScreen(new AlteKarte(this, gezeigt, Downloads.INSTANZ.geteiltMit(),
                    Component.translatable("heroicmap.alte.danach_selbst", massstab), w -> waehleSelbst()));
            return;
        }
        minecraft.gui.setScreen(new ConfirmScreen(ja -> {
            minecraft.gui.setScreen(this);
            if (ja) {
                waehleSelbst();
            }
        }, Component.translatable("heroicmap.selbst.titel"), Component.translatable("heroicmap.selbst.frage", massstab)));
    }

    private void waehleSelbst() {
        hinweis = Selbst.INSTANZ.waehle(minecraft, massstab);
        if (hinweis == null) {
            gewaehlt = true;
            hinweis = Component.translatable("heroicmap.selbst.gewaehlt");
        }
        rebuildWidgets();
    }

    /** Zurück; kam die Liste von der Karte und wurde „Selbst“ gewählt, öffnet die Karte neu mit der eigenen. */
    @Override
    public void onClose() {
        if (gewaehlt && zurueck instanceof Karte karte && minecraft.level != null) {
            karte.onClose();
            minecraft.gui.setScreen(new Karte(Satz.fuer(Selbst.INSTANZ.weltOrdner(), dimension, Downloads.INSTANZ.wer())));
            return;
        }
        minecraft.gui.setScreen(zurueck);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
