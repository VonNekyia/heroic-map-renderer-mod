package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Klicks auf die Minimap in den Menüs, die sie zeigen ({@link Einstellungen}, {@link Anzeige}): Ein Klick auf den Spieler
 * schaltet die Darstellung weiter; einer auf eine Marke N, O, S, W mit der linken oder rechten Taste öffnet den Wähler der
 * Rahmen, mit der mittleren macht er die Marke durchsichtig oder holt sie zurück. Erst das Loslassen entscheidet: höchstens
 * {@link #WEG} Einheiten vom Drücken und über demselben Ziel. Siehe docs/minimap.md, „Aussehen an der Minimap“.
 */
final class MinimapKlick {

    /** So weit darf die Maus zwischen Drücken und Loslassen wandern, in Einheiten des GUI; weiter ist Ziehen. */
    static final double WEG = 3;
    /** Breite des Tooltips in Einheiten, wie bei den Knöpfen. */
    private static final int TOOLTIP = 170;

    private int ziel = Minimap.KEIN_ZIEL;
    /** Mit der mittleren Taste gedrückt: Das Ziel ist eine Marke, die durchsichtig wird oder zurückkommt. */
    private boolean mitte;
    private double x, y;
    private final Waehler waehler;

    MinimapKlick(Waehler waehler) {
        this.waehler = waehler;
    }

    /**
     * Drücken: Mit der linken Taste auf einem Ziel, mit der rechten oder mittleren auf einer Marke merkt es sich das Ziel; true,
     * wenn eins getroffen ist. Siehe docs/rahmen.md, „Verzierungen“.
     * Siehe docs/rahmen.md, „Wähler“.
     */
    boolean druecke(MouseButtonEvent e) {
        int z = Minimap.INSTANZ.ziel(e.x(), e.y());
        mitte = e.button() == InputConstants.MOUSE_BUTTON_MIDDLE;
        boolean marke = (mitte || e.button() == InputConstants.MOUSE_BUTTON_RIGHT) && z != Minimap.SPIELER;
        ziel = e.button() == InputConstants.MOUSE_BUTTON_LEFT || marke ? z : Minimap.KEIN_ZIEL;
        x = e.x();
        y = e.y();
        return ziel != Minimap.KEIN_ZIEL;
    }

    /** Ist die Maus seit dem Drücken weiter als {@link #WEG} gewandert? Dann ist es kein Klick mehr. */
    boolean gezogen(MouseButtonEvent e) {
        return Math.hypot(e.x() - x, e.y() - y) > WEG;
    }

    /** Loslassen: Liegt die Maus noch nah und über demselben Ziel, schaltet es weiter; true, wenn ein Ziel gedrückt war. */
    boolean lasse(MouseButtonEvent e) {
        int z = ziel;
        ziel = Minimap.KEIN_ZIEL;
        if (z != Minimap.KEIN_ZIEL && !gezogen(e) && Minimap.INSTANZ.ziel(e.x(), e.y()) == z) {
            Minimap m = Minimap.INSTANZ;
            if (z == Minimap.SPIELER) {
                // Kopf, Pfeil, Halb; gespeichert wird beim Schliessen.
                Minimap.Darstellung[] d = Minimap.Darstellung.values();
                m.setzeDarstellung(d[(m.darstellung().ordinal() + 1) % d.length]);
            } else if (mitte) {
                m.schalteVerzierung(z);
            } else {
                waehler.oeffne();
            }
        }
        return z != Minimap.KEIN_ZIEL;
    }

    /**
     * Je Frame zuletzt im Menü: halbe Marken, wenn der Rahmen keine zeigt; über einem Ziel die Hand als Zeiger und ein
     * Tooltip mit dem, was jetzt gilt. {@code frei} ist false, solange die Minimap gezogen wird.
     */
    void zeichne(GuiGraphicsExtractor g, Font font, int mausX, int mausY, boolean frei) {
        Minimap m = Minimap.INSTANZ;
        int z = frei && m.sichtbar() ? m.ziel(mausX, mausY) : Minimap.KEIN_ZIEL;
        m.hervor(z);
        m.geister(g);
        if (z == Minimap.KEIN_ZIEL) {
            return;
        }
        g.requestCursor(CursorTypes.POINTING_HAND);
        boolean spieler = z == Minimap.SPIELER;
        Component jetzt = spieler ? Component.translatable("heroicmap.menue.spieler." + m.darstellung().name().toLowerCase(Locale.ROOT))
                : rahmenName(m);
        List<FormattedCharSequence> zeilen = new ArrayList<>(font.split(
                Component.translatable(spieler ? "heroicmap.menue.klick.spieler" : "heroicmap.menue.klick.rahmen", jetzt), TOOLTIP));
        if (!spieler) {
            String jetztZier = m.verzierungAus(z) ? "aus" : m.hd(z) ? "hd" : "pixel";
            zeilen.addAll(font.split(Component.translatable("heroicmap.menue.mitte",
                    Component.translatable("heroicmap.menue.mitte." + jetztZier)), TOOLTIP));
        }
        zeilen.addAll(font.split(Component.translatable(spieler ? "heroicmap.menue.spieler.beschreibung"
                : "heroicmap.rahmen." + m.skin() + ".beschreibung").withStyle(ChatFormatting.GRAY), TOOLTIP));
        g.setTooltipForNextFrame(zeilen, mausX, mausY);
    }

    /** Der Name des Rahmens für den Tooltip: die Familie, mit Variante, bei „biom“ die der gezeigten Kategorie. */
    private static Component rahmenName(Minimap m) {
        String f = Skin.BIOM.equals(m.skin()) ? Biom.ORDNER.get(m.kategorie()) : m.skin();
        Skin.Variante v = Skin.variante(f, m.variante(f));
        Component name = Component.translatable("heroicmap.rahmen." + m.skin());
        return v == null ? name : name.copy().append(" – ").append(Component.translatable("heroicmap.rahmen." + f.replace('/', '.') + "." + v.name()));
    }
}
