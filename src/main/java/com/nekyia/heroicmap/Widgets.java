package com.nekyia.heroicmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Die Widgets an der Minimap: je eine Zeile Text, eingerastet unten oder oben an ihr. Lesen, Schreiben und die Übernahme der
 * alten Zeile der Koordinaten; rein und ohne Minecraft. Siehe docs/widgets.md.
 */
final class Widgets {

    /** So viele Widgets hält die Minimap höchstens; mehr liest sie nicht. */
    static final int MAX = 16;

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

    /** Wo ein Widget eingerastet ist; oben und frei kommen mit dem Ziehen. */
    enum Anker { UNTEN }

    /** Ein Widget: seine Art, die Version als Stelle in {@link Art#versionen}, und sein Anker; im Stapel in der Reihe der Liste. */
    record Widget(Art art, int version, Anker anker) {

        String versionName() {
            return art.versionen.get(version);
        }
    }

    private Widgets() {
    }

    /**
     * Die Widgets aus dem Text {@code art:version:anker;…}, etwa {@code koordinaten:xz:unten}. Ein Eintrag mit unbekannter
     * Art, Version oder Anker oder mit falscher Zahl Teile fehlt; die übrigen bleiben, höchstens {@link #MAX}.
     */
    static List<Widget> lies(String text) {
        List<Widget> widgets = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return widgets;
        }
        for (String eintrag : text.trim().split(";")) {
            String[] t = eintrag.trim().split(":");
            Art art = t.length == 3 ? Art.von(t[0]) : null;
            if (art == null || widgets.size() >= MAX) {
                continue;
            }
            int version = art.versionen.indexOf(t[1]);
            if (version >= 0 && t[2].equals("unten")) {
                widgets.add(new Widget(art, version, Anker.UNTEN));
            }
        }
        return widgets;
    }

    /** Der Text zu {@link #lies}. */
    static String schreibe(List<Widget> widgets) {
        List<String> teile = new ArrayList<>();
        for (Widget w : widgets) {
            teile.add(w.art().name + ":" + w.versionName() + ":" + w.anker().name().toLowerCase(Locale.ROOT));
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
}
