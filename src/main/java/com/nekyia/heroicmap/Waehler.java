package com.nekyia.heroicmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Der Wähler der Rahmen, über dem Menü: je Familie eine kleine Minimap mit ihrem Rahmen auf einer festen Beispielkarte, darunter
 * ein Farbfeld je Ordner ihrer Varianten. Ein Klick auf ein Feld färbt nur die Vorschau; ein Klick auf eine Vorschau übernimmt
 * Familie und Variante und schliesst; Esc oder ein Klick daneben schliesst ohne Änderung. Bei „lettern“ wählt „innen | aussen“
 * die Lage, bei „biom“ „&lt; Kategorie &gt;“ die Kategorie, deren Variante die Felder stellen. Siehe docs/rahmen.md, „Wähler“.
 */
final class Waehler {

    /** Die Seite der Vorschau, ihr Rand für die Marken und die Breite einer Karte, in Einheiten des GUI. */
    static final int SEITE = 56, RAND = 10, KARTE = SEITE + 2 * RAND, ABSTAND = 4, KOPF = 18, HOEHE = KARTE + 40, FELD = 9;
    private static final int GRUND = 0xF0181818, LINIE = 0xFF5A5A5A, GEWAEHLT = 0xFFFFFFA0, TEXT = 0xFFFFFFFF, GRAU = 0xFFA0A0A0;
    private static final Identifier KARTE_ECKIG = Identifier.fromNamespaceAndPath(HeroicMap.ID, "textures/gui/waehler_karte.png");
    private static final Identifier KARTE_RUND = Identifier.fromNamespaceAndPath(HeroicMap.ID, "textures/gui/waehler_karte_rund.png");

    private boolean offen;
    /** Je Familie, bei „biom“ je Kategorie, die Variante in der Vorschau. */
    private final Map<String, String> vorschau = new HashMap<>();
    /** Die Kategorie von „biom“ in der Vorschau. */
    private int kategorie;
    private int x0, y0;

    boolean offen() {
        return offen;
    }

    /** Öffnet mit den gewählten Varianten in der Vorschau und der Kategorie, die „biom“ gerade zeigt. */
    void oeffne() {
        Minimap m = Minimap.INSTANZ;
        vorschau.clear();
        for (String f : Skin.MIT_VARIANTEN) {
            Skin.Variante v = Skin.variante(f, m.variante(f));
            if (v != null) {
                vorschau.put(f, v.name());
            }
        }
        kategorie = Math.max(0, Biom.KATEGORIEN.indexOf(m.kategorie()));
        offen = true;
    }

    void schliesse() {
        offen = false;
    }

    /** Die Familie der Karte j, bei „biom“ die der Kategorie in der Vorschau. */
    private String familie(int j) {
        String f = Skin.FAMILIEN.get(j);
        return Skin.BIOM.equals(f) ? Biom.ORDNER.get(Biom.KATEGORIEN.get(kategorie)) : f;
    }

    /** Je Ordner die erste Variante: ein Farbfeld je Recolor; bei „lettern“ unterscheiden sich Varianten eines Ordners nur in der Lage. */
    private static List<Skin.Variante> felder(String familie) {
        List<Skin.Variante> aus = new ArrayList<>();
        for (Skin.Variante v : Skin.varianten(familie)) {
            if (aus.stream().noneMatch(w -> w.ordner().equals(v.ordner()))) {
                aus.add(v);
            }
        }
        return aus;
    }

    /** Die Lage, die eine Variante in ihrer Zeile setzt, sonst leer. */
    private static String lage(Skin.Variante v) {
        return v.schalter().stream().filter(s -> s.startsWith("lage=")).map(s -> s.substring(5)).findFirst().orElse("");
    }

    /** Die Variante der Familie mit diesem Ordner und dieser Lage, sonst die erste mit dem Ordner. */
    private static String variante(String familie, String ordner, String lage) {
        List<Skin.Variante> alle = Skin.varianten(familie).stream().filter(v -> v.ordner().equals(ordner)).toList();
        return alle.stream().filter(v -> lage(v).equals(lage)).findFirst().orElse(alle.getFirst()).name();
    }

    private void lege(int breite, int hoehe) {
        int n = Skin.FAMILIEN.size(), b = n * KARTE + (n - 1) * ABSTAND + 12, h = KOPF + HOEHE + 6;
        x0 = (breite - b) / 2;
        y0 = Math.max(2, (hoehe - h) / 2);
    }

    private int karteX(int j) {
        return x0 + 6 + j * (KARTE + ABSTAND);
    }

    private int karteY() {
        return y0 + KOPF;
    }

    /** Für die Gametests: die Mitte der Vorschau der Familie j, in Einheiten des GUI. */
    double[] vorschauMitte(int j, int breite, int hoehe) {
        lege(breite, hoehe);
        return new double[] {karteX(j) + KARTE / 2.0, karteY() + KARTE / 2.0};
    }

    /** Für die Gametests: die Mitte des Farbfelds i unter der Vorschau der Familie j. */
    double[] feldMitte(int j, int i, int breite, int hoehe) {
        lege(breite, hoehe);
        return new double[] {karteX(j) + 4 + i * (FELD + 2) + FELD / 2.0, karteY() + KARTE + 12 + FELD / 2.0};
    }

    /** Ein Klick, solange der Wähler offen ist; er nimmt jeden. */
    void klick(double mx, double my, int breite, int hoehe) {
        lege(breite, hoehe);
        int n = Skin.FAMILIEN.size(), b = n * KARTE + (n - 1) * ABSTAND + 12, h = KOPF + HOEHE + 6;
        if (mx < x0 || my < y0 || mx >= x0 + b || my >= y0 + h) {
            schliesse();
            return;
        }
        for (int j = 0; j < n; j++) {
            int x = karteX(j), y = karteY();
            if (mx < x || mx >= x + KARTE || my < y || my >= y + HOEHE) {
                continue;
            }
            String f = familie(j);
            List<Skin.Variante> felder = felder(f);
            // Die Farbfelder unter dem Namen.
            for (int i = 0; i < felder.size(); i++) {
                int fx = x + 4 + i * (FELD + 2), fy = y + KARTE + 12;
                if (mx >= fx && mx < fx + FELD && my >= fy && my < fy + FELD) {
                    String jetzt = vorschau.get(f);
                    String lage = jetzt == null ? "" : lage(Skin.variante(f, jetzt));
                    vorschau.put(f, variante(f, felder.get(i).ordner(), lage));
                    return;
                }
            }
            // Darunter: „innen | aussen“ bei „lettern“, „< Kategorie >“ bei „biom“.
            int zeile = y + KARTE + 24;
            if (my >= zeile && my < zeile + 10) {
                if ("lettern".equals(f) && vorschau.containsKey(f)) {
                    String ordner = Skin.variante(f, vorschau.get(f)).ordner();
                    vorschau.put(f, variante(f, ordner, mx < x + KARTE / 2.0 ? "innen" : "aussen"));
                } else if (Skin.BIOM.equals(Skin.FAMILIEN.get(j))) {
                    int k = Biom.KATEGORIEN.size();
                    kategorie = (kategorie + (mx < x + KARTE / 2.0 ? k - 1 : 1)) % k;
                }
                return;
            }
            if (my < y + KARTE + 10) {
                uebernimm(j, f);
            }
            return;
        }
    }

    /** Übernimmt Familie und Variante der Karte j und schliesst. Gespeichert wird beim Schliessen des Menüs. */
    private void uebernimm(int j, String familie) {
        Minimap m = Minimap.INSTANZ;
        m.setzeSkin(Skin.FAMILIEN.get(j));
        if (vorschau.containsKey(familie)) {
            m.setzeVariante(familie, vorschau.get(familie));
        }
        schliesse();
    }

    /** Zeichnet den Wähler über dem Menü; die Maus auf einem Feld nennt dessen Variante. */
    void zeichne(GuiGraphicsExtractor g, Font font, int mausX, int mausY, int breite, int hoehe) {
        lege(breite, hoehe);
        Minimap m = Minimap.INSTANZ;
        int n = Skin.FAMILIEN.size(), b = n * KARTE + (n - 1) * ABSTAND + 12, h = KOPF + HOEHE + 6;
        g.fill(0, 0, breite, hoehe, 0x90000000);
        g.fill(x0, y0, x0 + b, y0 + h, GRUND);
        g.outline(x0, y0, b, h, LINIE);
        g.text(font, Component.translatable("heroicmap.waehler.titel"), x0 + 6, y0 + 5, TEXT, true);
        Component tooltip = null;
        for (int j = 0; j < n; j++) {
            String familieJ = Skin.FAMILIEN.get(j), f = familie(j);
            int x = karteX(j), y = karteY();
            boolean jetzt = familieJ.equals(m.skin()), ueber = mausX >= x && mausX < x + KARTE && mausY >= y && mausY < y + KARTE + 10;
            g.fill(x, y, x + KARTE, y + HOEHE, 0xFF262626);
            g.outline(x, y, KARTE, HOEHE, jetzt ? GEWAEHLT : ueber ? 0xFFB0B0B0 : LINIE);
            Skin skin = vorschau.containsKey(f) ? Skin.von(f, vorschau.get(f)) : null;
            vorschau(g, skin, x + RAND, y + RAND, m.rund());
            g.text(font, Component.translatable("heroicmap.rahmen." + familieJ), x + 4, y + KARTE + 1, TEXT, true);
            List<Skin.Variante> felder = felder(f);
            for (int i = 0; i < felder.size(); i++) {
                Skin.Variante v = felder.get(i);
                Skin s = Skin.von(f, v.name());
                int fx = x + 4 + i * (FELD + 2), fy = y + KARTE + 12;
                boolean an = skin != null && v.ordner().equals(Skin.variante(f, vorschau.get(f)).ordner());
                g.fill(fx - 1, fy - 1, fx + FELD + 1, fy + FELD + 1, an ? 0xFFFFFFFF : 0xFF101010);
                g.fill(fx, fy, fx + FELD, fy + FELD, s == null ? 0xFF808080 : s.licht[s.licht.length - 1]);
                if (mausX >= fx && mausX < fx + FELD && mausY >= fy && mausY < fy + FELD) {
                    tooltip = Component.translatable("heroicmap.rahmen." + f.replace('/', '.') + "." + v.name());
                }
            }
            int zeile = y + KARTE + 25;
            if ("lettern".equals(f) && skin != null) {
                boolean innen = "innen".equals(lage(Skin.variante(f, vorschau.get(f))));
                g.text(font, Component.translatable("heroicmap.waehler.innen"), x + 4, zeile, innen ? TEXT : GRAU, true);
                g.text(font, Component.translatable("heroicmap.waehler.aussen"), x + KARTE / 2 + 2, zeile, innen ? GRAU : TEXT, true);
            } else if (Skin.BIOM.equals(familieJ)) {
                g.centeredText(font, Component.literal("< ").append(Component.translatable("heroicmap.rahmen.biom." + Biom.KATEGORIEN.get(kategorie)))
                        .append(" >"), x + KARTE / 2, zeile, TEXT);
            }
            if (ueber && tooltip == null) {
                tooltip = Component.translatable("heroicmap.rahmen." + familieJ + ".beschreibung");
            }
        }
        if (tooltip != null) {
            g.setTooltipForNextFrame(font, tooltip, mausX, mausY);
        }
    }

    /**
     * Eine Minimap von {@link #SEITE} Einheiten mit dem Rahmen {@code skin} auf der Beispielkarte, mit denselben Routinen wie die
     * Minimap: Bänder oder Ring, dann die Marken, genordet; ohne Skin der dünne Umriss.
     */
    private static void vorschau(GuiGraphicsExtractor g, Skin skin, int x, int y, boolean rund) {
        g.innerBlit(RenderPipelines.GUI_TEXTURED, rund ? KARTE_RUND : KARTE_ECKIG, x, x + SEITE, y, y + SEITE, 0, 1, 0, 1, 0xFFFFFFFF);
        if (skin == null) {
            if (!rund) {
                g.outline(x - 1, y - 1, SEITE + 2, SEITE + 2, 0xFF000000);
            }
            return;
        }
        if (rund) {
            g.innerBlit(RenderPipelines.GUI_TEXTURED, skin.ring(SEITE), x, x + SEITE, y, y + SEITE, 0, 1, 0, 1, 0xFFFFFFFF);
        } else {
            skin.baender(g, x, y, SEITE, SEITE, 1);
        }
        for (Minimap.Ornament o : Minimap.ornamente(new Minimap.Rahmen(x, y, SEITE), skin.baender(), rund, skin.marken, null, 1, 0, -1,
                false, Minimap.KEIN_ZIEL)) {
            skin.ornament(g, o.teil(), o.ecke(), o.x(), o.y(), 1, o.winkel());
        }
    }
}
