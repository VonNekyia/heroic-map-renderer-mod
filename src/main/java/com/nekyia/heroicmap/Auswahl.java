package com.nekyia.heroicmap;

import java.util.List;
import java.util.Map;
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
        int y = 34;
        if (selbst && selbstAn) {
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
                if (m == hat) {
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
        if (baeume.isEmpty()) {
            g.centeredText(font, Component.translatable("heroicmap.angebot.keins"), width / 2, height / 2, TEXT);
        }
        int y = 34;
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

    /** „Selbst“ erst nach Rückfrage: Ab dann zeigt die Vollbildkarte nur noch die eigene Karte. */
    private void frageSelbst() {
        minecraft.gui.setScreen(new ConfirmScreen(ja -> {
            if (ja) {
                hinweis = Selbst.INSTANZ.waehle(minecraft, massstab);
                if (hinweis == null) {
                    gewaehlt = true;
                    hinweis = Component.translatable("heroicmap.selbst.gewaehlt");
                }
            }
            minecraft.gui.setScreen(this);
        }, Component.translatable("heroicmap.selbst.titel"), Component.translatable("heroicmap.selbst.frage", massstab)));
    }

    /** Zurück; kam die Liste von der Karte und wurde „Selbst“ gewählt, öffnet die Karte neu mit der eigenen. */
    @Override
    public void onClose() {
        if (gewaehlt && zurueck instanceof Karte karte && minecraft.level != null) {
            karte.onClose();
            minecraft.gui.setScreen(new Karte(Satz.fuer(Selbst.INSTANZ.weltOrdner(), dimension)));
            return;
        }
        minecraft.gui.setScreen(zurueck);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
