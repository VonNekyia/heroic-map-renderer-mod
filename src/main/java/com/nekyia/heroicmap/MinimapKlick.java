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
 * schaltet die Darstellung weiter, einer auf eine Marke N, O, S, W den Rahmen. Erst das Loslassen entscheidet: höchstens
 * {@link #WEG} Einheiten vom Drücken und über demselben Ziel. Siehe docs/minimap.md, „Aussehen an der Minimap“.
 */
final class MinimapKlick {

    /** So weit darf die Maus zwischen Drücken und Loslassen wandern, in Einheiten des GUI; weiter ist Ziehen. */
    static final double WEG = 3;
    /** Breite des Tooltips in Einheiten, wie bei den Knöpfen. */
    private static final int TOOLTIP = 170;

    private int ziel = Minimap.KEIN_ZIEL;
    private double x, y;

    /** Drücken: Mit der linken Taste auf einem Ziel merkt es sich das Ziel; true, wenn eins getroffen ist. */
    boolean druecke(MouseButtonEvent e) {
        ziel = e.button() == InputConstants.MOUSE_BUTTON_LEFT ? Minimap.INSTANZ.ziel(e.x(), e.y()) : Minimap.KEIN_ZIEL;
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
            schalte(z);
        }
        return z != Minimap.KEIN_ZIEL;
    }

    /** Der Spieler schaltet Kopf, Pfeil, Halb; eine Marke den Rahmen in der Reihenfolge von {@link Skin#NAMEN}. Gespeichert wird beim Schliessen. */
    static void schalte(int ziel) {
        Minimap m = Minimap.INSTANZ;
        if (ziel == Minimap.SPIELER) {
            Minimap.Darstellung[] d = Minimap.Darstellung.values();
            m.setzeDarstellung(d[(m.darstellung().ordinal() + 1) % d.length]);
        } else {
            m.setzeSkin(Skin.NAMEN.get((Skin.NAMEN.indexOf(m.skin()) + 1) % Skin.NAMEN.size()));
        }
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
        Component jetzt = Component.translatable(spieler ? "heroicmap.menue.spieler." + m.darstellung().name().toLowerCase(Locale.ROOT)
                : "heroicmap.rahmen." + m.skin());
        List<FormattedCharSequence> zeilen = new ArrayList<>(font.split(
                Component.translatable(spieler ? "heroicmap.menue.klick.spieler" : "heroicmap.menue.klick.rahmen", jetzt), TOOLTIP));
        zeilen.addAll(font.split(Component.translatable(spieler ? "heroicmap.menue.spieler.beschreibung"
                : "heroicmap.rahmen." + m.skin() + ".beschreibung").withStyle(ChatFormatting.GRAY), TOOLTIP));
        g.setTooltipForNextFrame(zeilen, mausX, mausY);
    }
}
