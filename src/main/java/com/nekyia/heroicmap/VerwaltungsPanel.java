package com.nekyia.heroicmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/**
 * Die Knöpfe der Schnellverwaltung auf der Vollbildkarte, links neben ihrer Spalte: oben Suche, Art, Ebene und Sortierung, je
 * Objekt eine Zeile nach {@link Verwaltung#spalten}, unten die Seiten. Ohne Hintergrund, so sieht man jede Änderung gleich auf
 * der Karte. Neu gebaut wird nie mitten in einem Klick, sondern im nächsten Tick der Karte ({@link Stand#neuBauen}). Siehe
 * docs/verwaltung.md.
 */
final class VerwaltungsPanel {

    private static final int TEXT = 0xFFFFFFFF, GRAU = 0xFFA0A0A0, ROT = 0xFFE04040;

    /** Was das Panel von der Karte braucht. */
    interface Gastgeber {
        <T extends AbstractWidget> T dazu(T w);

        void fokus(AbstractWidget w);

        void zentriere(double x, double z);

        /** Öffnet das Feld „Farbe…“ der Form an (x, y). */
        void faerben(Wegpunkte.EigeneForm f, int x, int y);

        /** Meldet, wenn nichts mehr angeheftet werden konnte. */
        void angeheftet(boolean geschehen);

        /** Die Zeit eines Todespunkts, wie die Karte sie unter ihn schreibt. */
        Component tod(Wegpunkte.Punkt p);
    }

    /** Was die Schnellverwaltung über einen Neuaufbau der Karte behält, solange die Karte offen ist. */
    static final class Stand {
        boolean offen;
        String suche = "";
        boolean sucheFokus;
        Verwaltung.Filter art = Verwaltung.Filter.ALLE;
        int ebene = Verwaltung.ALLE_EBENEN;
        Verwaltung.Sortierung sortierung = Verwaltung.Sortierung.NAME;
        int seite;
        /** Das Objekt, dessen × einmal geklickt ist, und das vom Klick davor; der zweite Klick auf dasselbe löscht. */
        Object loeschen, loeschenVorher;
        /** Etwas am Panel änderte sich: Die Karte baut es im nächsten Tick neu. */
        boolean neuBauen;
        /** Der Stand der Wegpunkte beim letzten Bau; ändern sie sich, baut die Karte neu. */
        int wegpunkte = -1;
        /** Der Kasten des Panels: x, y, Breite, Höhe; null, solange es zu ist. */
        int[] kasten;
        final List<Text> texte = new ArrayList<>();

        /** Liegt (x, y) im Panel? */
        boolean drin(double x, double y) {
            return offen && kasten != null && x >= kasten[0] && x < kasten[0] + kasten[2] && y >= kasten[1] && y < kasten[1] + kasten[3];
        }
    }

    /** Ein Text, der nach den Knöpfen gezeichnet wird. */
    record Text(Component text, int x, int y, int farbe, boolean mitte) {
    }

    private VerwaltungsPanel() {
    }

    /**
     * Baut das Panel mit der rechten Kante bei {@code rechts}, von {@code oben} bis {@code hoehe}: die eigenen Objekte in
     * {@code dimension}, nach Nähe zu {@code spieler} (x, z) sortierbar.
     */
    static void baue(Stand s, Gastgeber k, Font font, int rechts, int oben, int hoehe, String dimension, double[] spieler) {
        s.texte.clear();
        s.wegpunkte = Wegpunkte.INSTANZ.stand();
        boolean zwei = Verwaltung.zweiZeilen(rechts - 4);
        int breite = zwei ? Verwaltung.BREITE_ZWEI : Verwaltung.BREITE, x0 = rechts - breite, y = oben;
        // Oben Suche, Art, Ebene, Sortierung; in zwei Zeilen die Suche allein.
        SuchFeld suche = k.dazu(new SuchFeld(font, x0, y, zwei ? breite : 116, s));
        if (s.sucheFokus) {
            k.fokus(suche);
        }
        int fx = zwei ? x0 : x0 + 118, fy = zwei ? y + Verwaltung.ZEILE : y, fb = zwei ? 54 : 64;
        k.dazu(CycleButton.builder((Verwaltung.Filter f) -> Component.translatable("heroicmap.verwaltung.art." + f.name().toLowerCase(Locale.ROOT)), s.art)
                .withValues(Verwaltung.Filter.values())
                .create(fx, fy, fb, 20, Component.translatable("heroicmap.verwaltung.art"), (b, f) -> {
                    s.art = f;
                    s.seite = 0;
                    s.neuBauen = true;
                }));
        List<Integer> ebenen = new ArrayList<>(List.of(Verwaltung.ALLE_EBENEN));
        Wegpunkte.INSTANZ.ebenen().forEach(e -> ebenen.add(e.id()));
        if (!ebenen.contains(s.ebene)) {
            s.ebene = Verwaltung.ALLE_EBENEN;
        }
        k.dazu(CycleButton.builder((Integer e) -> e == Verwaltung.ALLE_EBENEN ? Component.translatable("heroicmap.verwaltung.ebene.alle")
                        : Component.literal(Wegpunkte.INSTANZ.ebene(e).name()), s.ebene)
                .withValues(ebenen)
                .create(fx + fb + 2, fy, fb, 20, Component.translatable("heroicmap.verwaltung.ebene"), (b, e) -> {
                    s.ebene = e;
                    s.seite = 0;
                    s.neuBauen = true;
                }));
        k.dazu(CycleButton.builder((Verwaltung.Sortierung o) -> Component.translatable("heroicmap.verwaltung.sortierung." + o.name().toLowerCase(Locale.ROOT)),
                        s.sortierung)
                .withValues(Verwaltung.Sortierung.values())
                .create(fx + 2 * (fb + 2), fy, breite - 2 * (fb + 2) - (fx - x0), 20, Component.translatable("heroicmap.verwaltung.sortierung"),
                        (b, o) -> {
                            s.sortierung = o;
                            s.seite = 0;
                            s.neuBauen = true;
                        }));
        y = fy + Verwaltung.ZEILE;
        // Die Zeilen bis über die Seiten unten; die stehen über Satz und Koordinaten unten links.
        int unten = hoehe - 36, jeSeite = Verwaltung.jeSeite(unten - 2 - y, zwei);
        List<Verwaltung.Zeile> alle = Verwaltung.auswahl(Verwaltung.zeilen(Wegpunkte.INSTANZ.punkte(), Wegpunkte.INSTANZ.eigeneFormen(),
                Wegpunkte.INSTANZ.regionen(), dimension), s.suche, s.art, s.ebene, s.sortierung, spieler[0], spieler[1]);
        int seiten = Verwaltung.seiten(alle.size(), jeSeite);
        s.seite = Math.clamp(s.seite, 0, seiten - 1);
        Verwaltung.Lage[] l = Verwaltung.spalten(zwei);
        for (Verwaltung.Zeile z : Verwaltung.seite(alle, s.seite, jeSeite)) {
            zeile(s, k, font, z, x0, y, l);
            y += zwei ? 2 * Verwaltung.ZEILE : Verwaltung.ZEILE;
        }
        if (alle.isEmpty()) {
            s.texte.add(new Text(Component.translatable("heroicmap.verwaltung.leer"), x0, y + 6, GRAU, false));
        }
        // Unten die Seiten.
        Button zurueck = k.dazu(Button.builder(Component.literal("<"), b -> {
            s.seite--;
            s.neuBauen = true;
        }).bounds(x0, unten, 20, 20).build());
        Button weiter = k.dazu(Button.builder(Component.literal(">"), b -> {
            s.seite++;
            s.neuBauen = true;
        }).bounds(x0 + breite - 20, unten, 20, 20).build());
        zurueck.active = s.seite > 0;
        weiter.active = s.seite < seiten - 1;
        s.texte.add(new Text(Component.literal((s.seite + 1) + " / " + seiten), x0 + breite / 2, unten + 6, TEXT, true));
        s.kasten = new int[] {x0, oben, breite, unten + 20 - oben};
    }

    /** Eine Zeile für das Objekt {@code z} mit ihrer linken oberen Ecke bei (x0, y). */
    private static void zeile(Stand s, Gastgeber k, Font font, Verwaltung.Zeile z, int x0, int y, Verwaltung.Lage[] l) {
        Verwaltung.Art art = z.art();
        boolean tod = art == Verwaltung.Art.TOD, mitText = art == Verwaltung.Art.WEGPUNKT || art == Verwaltung.Art.REGION
                || art == Verwaltung.Art.LINIE;
        Object o = z.objekt();
        int farbe = farbe(o);
        Verwaltung.Lage a = l[Verwaltung.Spalte.ART.ordinal()];
        String zeichen = switch (art) {
            case WEGPUNKT, TOD -> "◆";
            case REGION, RECHTECK -> "▲";
            case LINIE -> "╱";
        };
        s.texte.add(new Text(Component.literal(zeichen), x0 + a.x() + a.breite() / 2, y + a.y() + 6, tod ? GRAU : farbe, true));
        if (!tod) {
            Verwaltung.Lage f = l[Verwaltung.Spalte.FARBE.ordinal()];
            k.dazu(new Feld(x0 + f.x(), y + f.y(), f.breite(), farbe, Component.translatable(o instanceof Wegpunkte.EigeneForm
                    ? "heroicmap.verwaltung.farbe_feld" : "heroicmap.verwaltung.farbe_reihum"), knopf -> {
                switch (o) {
                    case Wegpunkte.Punkt p -> Wegpunkte.INSTANZ.faerbe(p, p.farbe() + 1);
                    case Wegpunkte.Region r -> Wegpunkte.INSTANZ.faerbe(r, r.farbe() + 1);
                    case Wegpunkte.EigeneForm form -> k.faerben(form, knopf.getX(), knopf.getY() + 20);
                    default -> { }
                }
            }));
        }
        Verwaltung.Lage n = l[Verwaltung.Spalte.NAME.ordinal()];
        String ort = Component.translatable("heroicmap.verwaltung.ort", (int) Math.floor(z.x()), (int) Math.floor(z.z())).getString();
        if (mitText) {
            NameFeld feld = k.dazu(new NameFeld(font, x0 + n.x(), y + n.y(), n.breite(), o));
            feld.setHint(Component.literal(ort).withStyle(ChatFormatting.DARK_GRAY));
        } else {
            Component text = tod ? k.tod((Wegpunkte.Punkt) o) : Component.literal(ort);
            s.texte.add(new Text(text, x0 + n.x() + 4, y + n.y() + 6, GRAU, false));
        }
        if (mitText) {
            boolean benannt = z.name() != null;
            double schrift = o instanceof Wegpunkte.Punkt p ? p.schrift() : ((Wegpunkte.EigeneForm) o).schrift();
            stufen(s, k, l[Verwaltung.Spalte.SCHRIFT.ordinal()], x0, y, Component.literal(Karte.SCHRIFT_TEXTE[raste(schrift)]), benannt,
                    "heroicmap.verwaltung.schrift", schritt -> {
                        switch (o) {
                            case Wegpunkte.Punkt p -> Wegpunkte.INSTANZ.stil(p, Verwaltung.schrift(p.schrift(), schritt), p.minecraft());
                            case Wegpunkte.EigeneForm f -> Wegpunkte.INSTANZ.stil(f, Verwaltung.schrift(f.schrift(), schritt), f.biegung(), f.gewicht());
                            default -> { }
                        }
                        Wegpunkte.INSTANZ.speichere();
                    });
            if (art == Verwaltung.Art.REGION) {
                Wegpunkte.EigeneForm f = (Wegpunkte.EigeneForm) o;
                stufen(s, k, l[Verwaltung.Spalte.BIEGUNG.ordinal()], x0, y, Component.literal(f.biegung() + "°"), benannt,
                        "heroicmap.verwaltung.biegung", schritt -> {
                            Wegpunkte.INSTANZ.stil(f, f.schrift(), Verwaltung.biegung(f.biegung(), schritt), f.gewicht());
                            Wegpunkte.INSTANZ.speichere();
                        });
            }
            boolean mc = o instanceof Wegpunkte.Punkt p ? p.minecraft() : ((Wegpunkte.EigeneForm) o).minecraft();
            Verwaltung.Lage sa = l[Verwaltung.Spalte.SCHRIFTART.ordinal()];
            Button b = k.dazu(Button.builder(Component.translatable(mc ? "heroicmap.verwaltung.schriftart.minecraft" : "heroicmap.verwaltung.schriftart.karte"),
                    knopf -> {
                        switch (o) {
                            case Wegpunkte.Punkt p -> Wegpunkte.INSTANZ.stil(p, p.schrift(), !p.minecraft());
                            case Wegpunkte.EigeneForm f -> Wegpunkte.INSTANZ.schriftart(f, !f.minecraft());
                            default -> { }
                        }
                        Wegpunkte.INSTANZ.speichere();
                    }).tooltip(Tooltip.create(Component.translatable("heroicmap.verwaltung.schriftart.tooltip")))
                    .bounds(x0 + sa.x(), y + sa.y(), sa.breite(), 20).build());
            b.active = benannt;
        }
        if (!tod) {
            Verwaltung.Lage e = l[Verwaltung.Spalte.EBENE.ordinal()];
            Wegpunkte.EigeneEbene ebene = Wegpunkte.INSTANZ.ebene(z.ebene());
            k.dazu(Button.builder(Component.literal(font.plainSubstrByWidth(ebene == null ? "?" : ebene.name(), e.breite() - 6)), knopf -> {
                int neu = Verwaltung.naechsteEbene(Wegpunkte.INSTANZ.ebenen(), z.ebene());
                switch (o) {
                    case Wegpunkte.Punkt p -> Wegpunkte.INSTANZ.ordne(p, neu);
                    case Wegpunkte.Region r -> Wegpunkte.INSTANZ.ordne(r, neu);
                    case Wegpunkte.EigeneForm f -> Wegpunkte.INSTANZ.ordne(f, neu);
                    default -> { }
                }
            }).tooltip(Tooltip.create(Component.translatable("heroicmap.verwaltung.ebene.tooltip"))).bounds(x0 + e.x(), y + e.y(), e.breite(), 20).build());
            Verwaltung.Lage h = l[Verwaltung.Spalte.ANHEFTEN.ordinal()];
            boolean an = o instanceof Wegpunkte.Punkt p ? p.angeheftet() : o instanceof Wegpunkte.Region r ? r.angeheftet()
                    : ((Wegpunkte.EigeneForm) o).angeheftet();
            k.dazu(new Zeichen(x0 + h.x(), y + h.y(), h.breite(), Zeichen.NADEL, () -> an, () -> false,
                    Component.translatable(an ? "heroicmap.verwaltung.loesen" : "heroicmap.verwaltung.anheften"), () -> {
                        switch (o) {
                            case Wegpunkte.Punkt p -> Wegpunkte.INSTANZ.umschalten(p);
                            case Wegpunkte.Region r -> k.angeheftet(Wegpunkte.INSTANZ.umschalten(r));
                            case Wegpunkte.EigeneForm f -> k.angeheftet(Wegpunkte.INSTANZ.umschalten(f));
                            default -> { }
                        }
                    }));
        }
        Verwaltung.Lage hin = l[Verwaltung.Spalte.HINSPRINGEN.ordinal()];
        k.dazu(new Zeichen(x0 + hin.x(), y + hin.y(), hin.breite(), Zeichen.ZIEL, () -> false, () -> false,
                Component.translatable("heroicmap.verwaltung.hinspringen"), () -> k.zentriere(z.x(), z.z())));
        Verwaltung.Lage weg = l[Verwaltung.Spalte.LOESCHEN.ordinal()];
        k.dazu(new Zeichen(x0 + weg.x(), y + weg.y(), weg.breite(), Zeichen.WEG, () -> false, () -> Objects.equals(s.loeschen, o),
                Component.translatable("heroicmap.verwaltung.loeschen"), () -> {
                    if (Objects.equals(s.loeschenVorher, o)) {
                        switch (o) {
                            case Wegpunkte.Punkt p -> Wegpunkte.INSTANZ.loesche(p);
                            case Wegpunkte.Region r -> Wegpunkte.INSTANZ.loesche(r);
                            case Wegpunkte.EigeneForm f -> Wegpunkte.INSTANZ.loesche(f);
                            default -> { }
                        }
                    } else {
                        s.loeschen = o;
                    }
                }));
    }

    /** − Wert +: zwei kleine Knöpfe mit dem Wert dazwischen, grau ohne Namen. */
    private static void stufen(Stand s, Gastgeber k, Verwaltung.Lage l, int x0, int y, Component wert, boolean an, String tooltip,
            Consumer<Integer> schritt) {
        int x = x0 + l.x(), oben = y + l.y();
        Button minus = k.dazu(Button.builder(Component.literal("−"), b -> schritt.accept(-1))
                .tooltip(Tooltip.create(Component.translatable(tooltip))).bounds(x, oben, 10, 20).build());
        Button plus = k.dazu(Button.builder(Component.literal("+"), b -> schritt.accept(1))
                .tooltip(Tooltip.create(Component.translatable(tooltip))).bounds(x + l.breite() - 10, oben, 10, 20).build());
        minus.active = an;
        plus.active = an;
        s.texte.add(new Text(wert, x + l.breite() / 2, oben + 6, an ? TEXT : GRAU, true));
    }

    /** Die Raste von {@link Wegpunkte#SCHRIFTEN} mit dem Faktor {@code f}. */
    private static int raste(double f) {
        int i = 0;
        while (i < Wegpunkte.SCHRIFTEN.length - 1 && Wegpunkte.SCHRIFTEN[i] < f) {
            i++;
        }
        return i;
    }

    /** Die Farbe des Objekts, wie die Karte es zeichnet, deckend. */
    static int farbe(Object o) {
        return switch (o) {
            case Wegpunkte.Punkt p -> 0xFF000000 | Wegpunkte.FARBEN[p.farbe()];
            case Wegpunkte.Region r -> 0xFF000000 | Wegpunkte.FARBEN[r.farbe()];
            case Wegpunkte.EigeneForm f -> 0xFF000000 | f.argb();
            default -> GRAU;
        };
    }

    /** Zeichnet die Texte des Panels nach seinen Knöpfen. */
    static void zeichne(Stand s, GuiGraphicsExtractor g, Font font) {
        if (!s.offen) {
            return;
        }
        for (Text t : s.texte) {
            if (t.mitte()) {
                g.centeredText(font, t.text(), t.x(), t.y(), t.farbe());
            } else {
                g.text(font, t.text(), t.x(), t.y(), t.farbe(), true);
            }
        }
    }

    /** Ein Feld, das Esc und Enter der Karte selbst behandelt: Esc verwirft, was getippt ist. */
    interface Eingabe {
        void verwerfen();
    }

    /** Die Suche: Jeder Buchstabe filtert gleich; die Karte baut neu und gibt dem neuen Feld den Fokus. */
    static final class SuchFeld extends EditBox implements Eingabe {
        private final Stand s;

        SuchFeld(Font font, int x, int y, int breite, Stand s) {
            super(font, x, y, breite, 20, Component.translatable("heroicmap.verwaltung.suche"));
            this.s = s;
            setMaxLength(Wegpunkte.MAX_NAME);
            setValue(s.suche);
            setHint(Component.translatable("heroicmap.verwaltung.suche").withStyle(ChatFormatting.DARK_GRAY));
            setResponder(t -> {
                s.suche = t;
                s.seite = 0;
                s.neuBauen = true;
            });
        }

        @Override
        public void setFocused(boolean fokus) {
            super.setFocused(fokus);
            s.sucheFokus = fokus;
        }

        @Override
        public void verwerfen() {
        }
    }

    /**
     * Der Name eines Objekts, direkt bearbeitbar: Verliert das Feld den Fokus, durch Enter, einen Klick daneben oder eine andere
     * Zeile, speichert es den Namen; Esc verwirft vorher, was getippt ist. Leer nimmt den Namen weg.
     */
    static final class NameFeld extends EditBox implements Eingabe {
        private final Object objekt;
        private final String vorher;

        NameFeld(Font font, int x, int y, int breite, Object objekt) {
            super(font, x, y, breite, 20, Component.translatable("heroicmap.karte.name_hinweis"));
            this.objekt = objekt;
            vorher = objekt instanceof Wegpunkte.Punkt p ? Objects.toString(p.name(), "") : Objects.toString(((Wegpunkte.EigeneForm) objekt).name(), "");
            setMaxLength(Wegpunkte.MAX_NAME);
            setValue(vorher);
        }

        @Override
        public void setFocused(boolean fokus) {
            boolean war = isFocused();
            super.setFocused(fokus);
            if (war && !fokus && !getValue().equals(vorher)) {
                switch (objekt) {
                    case Wegpunkte.Punkt p -> Wegpunkte.INSTANZ.benenne(p, getValue());
                    case Wegpunkte.EigeneForm f -> Wegpunkte.INSTANZ.benenne(f, getValue());
                    default -> { }
                }
            }
        }

        @Override
        public void verwerfen() {
            setValue(vorher);
        }
    }

    /** Das Feld der Farbe: ein Quadrat in der Farbe des Objekts im Rahmen eines Knopfs. */
    static final class Feld extends AbstractButton {
        private final int farbe;
        private final Consumer<Feld> klick;

        Feld(int x, int y, int breite, int farbe, Component tooltip, Consumer<Feld> klick) {
            super(x, y, breite, 20, tooltip);
            this.farbe = farbe;
            this.klick = klick;
            setTooltip(Tooltip.create(tooltip));
        }

        @Override
        public void onPress(InputWithModifiers e) {
            klick.accept(this);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
            int x = getX() + 1, y = getY() + 4;
            g.fill(x, y, x + width - 2, y + 12, isHoveredOrFocused() ? 0xFFFFFFFF : 0xFF000000);
            g.fill(x + 1, y + 1, x + width - 3, y + 11, farbe);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput ausgabe) {
            defaultButtonNarrationText(ausgabe);
        }
    }

    /** Ein kleiner Knopf mit gezeichnetem Zeichen: Nadel zum Anheften, Fadenkreuz zum Hinspringen, × zum Löschen. */
    static final class Zeichen extends AbstractButton {
        static final int NADEL = 0, ZIEL = 1, WEG = 2;
        private final int art;
        private final java.util.function.BooleanSupplier an, rot;
        private final Runnable klick;

        Zeichen(int x, int y, int breite, int art, java.util.function.BooleanSupplier an, java.util.function.BooleanSupplier rot,
                Component tooltip, Runnable klick) {
            super(x, y, breite, 20, tooltip);
            this.art = art;
            this.an = an;
            this.rot = rot;
            this.klick = klick;
            setTooltip(Tooltip.create(tooltip));
        }

        /** Für den Gametest: {@link #NADEL}, {@link #ZIEL} oder {@link #WEG}. */
        int art() {
            return art;
        }

        @Override
        public void onPress(InputWithModifiers e) {
            klick.run();
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
            extractDefaultSprite(g);
            int mx = getX() + width / 2, my = getY() + height / 2, farbe = an.getAsBoolean() ? 0xFFFFE060 : TEXT;
            switch (art) {
                case NADEL -> {
                    g.fill(mx - 2, my - 5, mx + 2, my - 1, ROT);
                    g.fill(mx, my - 1, mx + 1, my + 5, farbe);
                    if (an.getAsBoolean()) {
                        g.outline(getX() + 1, getY() + 1, width - 2, height - 2, farbe);
                    }
                }
                case ZIEL -> {
                    g.outline(mx - 4, my - 4, 9, 9, TEXT);
                    g.fill(mx, my - 6, mx + 1, my + 7, TEXT);
                    g.fill(mx - 6, my, mx + 7, my + 1, TEXT);
                }
                default -> {
                    boolean nochmal = rot.getAsBoolean();
                    if (nochmal) {
                        g.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, 0xFF802020);
                    }
                    g.centeredText(net.minecraft.client.Minecraft.getInstance().font, Component.literal(nochmal ? "?" : "×"), mx + 1, my - 4,
                            nochmal ? TEXT : ROT);
                }
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput ausgabe) {
            defaultButtonNarrationText(ausgabe);
        }
    }
}
