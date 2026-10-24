package com.nekyia.heroicmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Die Widgets an der Minimap: je eine Zeile Text, eingerastet unten oder oben an ihr oder frei. Lesen, Schreiben, die Übernahme
 * der alten Zeile der Koordinaten, wohin ein gezogenes Widget fällt und wo sein × liegt; rein und ohne Minecraft. Siehe
 * docs/widgets.md.
 */
final class Widgets {

    /** So viele Widgets hält die Minimap höchstens; mehr liest sie nicht. */
    static final int MAX = 16;
    /** Die kleinste und grösste Grösse in Pixeln des Geräts je Pixel der Schrift. */
    static final int KLEINSTE = 1, GROESSTE = 8;
    /** So nah an der Minimap und ihren Stapeln rastet ein gezogenes Widget ein, in Einheiten, wie das Einrasten mit Strg. */
    static final int EINRASTEN = 8;
    /** Die Seite des × beim Darüberfahren, in Einheiten; es sitzt halb über der rechten oberen Ecke. */
    static final int KREUZ = 8;

    /** Was ein Widget zeigt, und seine Versionen in der Reihe, in der sie wechseln. */
    enum Art {
        KOORDINATEN("koordinaten", "xz", "xyz");

        final String name;
        final List<String> versionen;

        Art(String name, String... versionen) {
            this.name = name;
            this.versionen = List.of(versionen);
        }

        static Art von(String name) {
            for (Art a : values()) {
                if (a.name.equals(name)) {
                    return a;
                }
            }
            return null;
        }
    }

    /** Wo ein Widget liegt: im Stapel unter oder über der Minimap, oder frei. */
    enum Anker { UNTEN, OBEN, FREI }

    /**
     * Ein Widget: seine Art, die Version als Stelle in {@link Art#versionen}, die Grösse in Pixeln des Geräts je Pixel der
     * Schrift, 0 wie der GUI-Massstab, sein Anker und frei seine Lage als Anteil des freien Platzes, sonst 0. In einem Stapel
     * stehen die Widgets in der Reihe der Liste.
     */
    record Widget(Art art, int version, int groesse, Anker anker, float x, float y) {

        Widget(Art art, int version, Anker anker) {
            this(art, version, 0, anker, 0, 0);
        }

        String versionName() {
            return art.versionen.get(version);
        }

        Widget naechsteVersion() {
            return new Widget(art, (version + 1) % art.versionen.size(), groesse, anker, x, y);
        }

        /** Pixel des Geräts je Pixel der Schrift bei GUI-Massstab {@code gs}. */
        int pixel(int gs) {
            return groesse == 0 ? gs : groesse;
        }

        /** Eine Stufe grösser oder kleiner, von {@link #KLEINSTE} bis {@link #GROESSTE}; danach gilt die Zahl, nicht mehr der GUI-Massstab. */
        Widget stufe(int gs, int richtung) {
            return new Widget(art, version, Math.clamp(pixel(gs) + richtung, KLEINSTE, GROESSTE), anker, x, y);
        }

        Widget nach(Anker neu, float nx, float ny) {
            return new Widget(art, version, groesse, neu, neu == Anker.FREI ? nx : 0, neu == Anker.FREI ? ny : 0);
        }
    }

    /** Wohin ein gezogenes Widget fällt: sein Anker, im Stapel an welche Stelle, frei als Anteil des freien Platzes. */
    record Ziel(Anker anker, int stelle, float x, float y) {
    }

    private Widgets() {
    }

    /**
     * Die Widgets aus dem Text, je Widget {@code art:version:groesse:unten}, {@code …:oben} oder {@code …:frei:x:y}, getrennt durch
     * {@code ;}; aus 0.2.69 auch {@code art:version:unten}. Ein Eintrag mit unbekannter Art, Version oder Anker, Grösse ausser 0
     * bis 8, Lage ausser 0 bis 1 oder falscher Zahl Teile fehlt; die übrigen bleiben, höchstens {@link #MAX}.
     */
    static List<Widget> lies(String text) {
        List<Widget> widgets = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return widgets;
        }
        for (String eintrag : text.trim().split(";")) {
            Widget w = eintrag(eintrag.trim().split(":"));
            if (w != null && widgets.size() < MAX) {
                widgets.add(w);
            }
        }
        return widgets;
    }

    private static Widget eintrag(String[] t) {
        Art art = t.length >= 3 ? Art.von(t[0]) : null;
        int version = art == null ? -1 : art.versionen.indexOf(t[1]);
        if (version < 0) {
            return null;
        }
        if (t.length == 3) {
            return t[2].equals("unten") ? new Widget(art, version, Anker.UNTEN) : null;
        }
        try {
            int groesse = Integer.parseInt(t[2]);
            if (groesse < 0 || groesse > GROESSTE) {
                return null;
            }
            if (t.length == 4 && (t[3].equals("unten") || t[3].equals("oben"))) {
                return new Widget(art, version, groesse, t[3].equals("unten") ? Anker.UNTEN : Anker.OBEN, 0, 0);
            }
            if (t.length == 6 && t[3].equals("frei")) {
                float x = Float.parseFloat(t[4]), y = Float.parseFloat(t[5]);
                return x >= 0 && x <= 1 && y >= 0 && y <= 1 ? new Widget(art, version, groesse, Anker.FREI, x, y) : null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    /** Der Text zu {@link #lies}. */
    static String schreibe(List<Widget> widgets) {
        List<String> teile = new ArrayList<>();
        for (Widget w : widgets) {
            String anfang = w.art().name + ":" + w.versionName() + ":" + w.groesse() + ":" + w.anker().name().toLowerCase(Locale.ROOT);
            teile.add(w.anker() == Anker.FREI ? anfang + ":" + w.x() + ":" + w.y() : anfang);
        }
        return String.join(";", teile);
    }

    /**
     * Die Widgets ohne Eintrag {@code widgets}, aus der alten Einstellung {@code koordinaten}: {@code aus} keins, {@code xyz}
     * die Koordinaten mit y, sonst, auch ohne Eintrag, die Koordinaten mit x und z, je unten eingerastet.
     */
    static List<Widget> ausKoordinaten(String alt) {
        return switch (String.valueOf(alt).trim()) {
            case "aus" -> new ArrayList<>();
            case "xyz" -> new ArrayList<>(List.of(new Widget(Art.KOORDINATEN, 1, Anker.UNTEN)));
            default -> new ArrayList<>(List.of(new Widget(Art.KOORDINATEN, 0, Anker.UNTEN)));
        };
    }

    /**
     * Wohin ein Widget fällt, dessen linke obere Ecke beim Ziehen bei (x, y) liegt, {@code breite} × {@code hoehe} gross. Die
     * Minimap ist {@code minimap} {x, y, Seite}; {@code unten} und {@code oben} sind die Kästen {x, y, Breite, Höhe} der Zeilen
     * ihrer Stapel ohne das gezogene Widget, in ihrer Reihe. Es zählt die Mitte des Widgets:
     * <ul>
     * <li>Unten, wenn sie waagrecht über der Minimap liegt, bis {@link #EINRASTEN} daneben, und senkrecht zwischen ihrer
     * Unterkante und dem Ende des unteren Stapels samt {@link #EINRASTEN}.</li>
     * <li>Oben ebenso über der Oberkante; steht der obere Stapel neben der Minimap, weil darüber kein Platz ist, auch über
     * seinen Zeilen.</li>
     * <li>Sonst frei, die Lage als Anteil des Platzes zwischen den Rändern im Abstand {@code rand}.</li>
     * </ul>
     * Im Stapel fällt es an die Stelle seiner Mitte.
     */
    static Ziel ziel(int x, int y, int breite, int hoehe, int[] minimap, List<int[]> unten, List<int[]> oben, int schirmBreite,
            int schirmHoehe, int rand) {
        double mx = x + breite / 2.0, my = y + hoehe / 2.0;
        boolean waagrecht = mx >= minimap[0] - EINRASTEN && mx <= minimap[0] + minimap[2] + EINRASTEN;
        int unterkante = minimap[1] + minimap[2], oberkante = minimap[1];
        int untenEnde = unten.isEmpty() ? unterkante : unten.getLast()[1] + unten.getLast()[3];
        if (waagrecht && my >= unterkante && my <= untenEnde + EINRASTEN) {
            return new Ziel(Anker.UNTEN, stelle(unten, my, true), 0, 0);
        }
        boolean neben = !oben.isEmpty() && oben.getFirst()[1] >= oberkante;
        int von = neben || oben.isEmpty() ? oberkante : oben.getLast()[1];
        int bis = neben ? oben.getLast()[1] + oben.getLast()[3] : oberkante;
        boolean ueberZeilen = neben && oben.stream().anyMatch(z -> mx >= z[0] - EINRASTEN && mx <= z[0] + z[2] + EINRASTEN);
        if ((waagrecht || ueberZeilen) && my >= von - EINRASTEN && my <= bis + EINRASTEN) {
            return new Ziel(Anker.OBEN, stelle(oben, my, neben), 0, 0);
        }
        return new Ziel(Anker.FREI, -1, anteil(x - rand, schirmBreite - breite - 2 * rand), anteil(y - rand, schirmHoehe - hoehe - 2 * rand));
    }

    /** Wie viele Zeilen eines Stapels vor {@code y} liegen: abwärts mit ihrer Mitte darüber, aufwärts darunter. */
    private static int stelle(List<int[]> zeilen, double y, boolean abwaerts) {
        int n = 0;
        for (int[] z : zeilen) {
            double mitte = z[1] + z[3] / 2.0;
            n += abwaerts ? (mitte < y ? 1 : 0) : (mitte > y ? 1 : 0);
        }
        return n;
    }

    private static float anteil(int wert, int platz) {
        return platz <= 0 ? 0 : Math.clamp(wert / (float) platz, 0, 1);
    }

    /** Eine Liste und die Stelle des Widgets, das gerade an seinen Platz kam. */
    record Gesetzt(List<Widget> liste, int stelle) {
    }

    /**
     * Die Liste mit dem Widget an Stelle {@code i} am Ziel: frei mit seiner Lage hinten, im Stapel vor dem
     * {@code ziel.stelle()}-ten Widget mit diesem Anker, sonst hinter dem letzten.
     */
    static Gesetzt setze(List<Widget> liste, int i, Ziel ziel) {
        List<Widget> neu = new ArrayList<>(liste);
        Widget w = neu.remove(i).nach(ziel.anker(), ziel.x(), ziel.y());
        int stelle = neu.size();
        if (ziel.anker() != Anker.FREI) {
            int n = 0;
            for (int k = 0; k < neu.size(); k++) {
                if (neu.get(k).anker() == ziel.anker() && n++ == ziel.stelle()) {
                    stelle = k;
                    break;
                }
            }
        }
        neu.add(stelle, w);
        return new Gesetzt(neu, stelle);
    }

    /** Das × zu einem Widget mit dem Kasten {x, y, Breite, Höhe}: {@link #KREUZ} im Quadrat, halb über seiner rechten oberen Ecke. */
    static int[] kreuz(int[] kasten) {
        return new int[] {kasten[0] + kasten[2] - KREUZ / 2, kasten[1] - KREUZ / 2, KREUZ, KREUZ};
    }
}
