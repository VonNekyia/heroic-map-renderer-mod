package com.nekyia.heroicmap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;

/**
 * Die Infotafel eines Objekts: Bausteine statt HTML, gelesen auf dem Thread des Netzes, gesetzt in
 * Einheiten des GUI zu Stücken, die die Vollbildkarte zeichnet. Siehe docs/ebenen.md, „Infotafel“.
 */
record Tafel(List<Baustein> bausteine) {

    /** Grenzen aus dem Format: Bausteine je Tafel, Zeichen eines Titels und einer Zeile, Seite eines Bilds, Punkte je Reihe. */
    static final int MAX_BAUSTEINE = 64, MAX_TITEL = 64, MAX_ZEILE = 120, MAX_BILD = 512, MAX_PUNKTE = 20;
    /** Eigene Grenzen: Zeilen je Baustein und Reihen je Wertung. */
    static final int MAX_ZEILEN = 64, MAX_REIHEN = 20;
    /** Die Schrift der Tafel, hell auf allen Fassungen, denn die Fläche ist überall dunkel. */
    static final int SCHRIFT = 0xFFD9D9D9;
    /**
     * In Einheiten des GUI: Breite des Inhalts höchstens, Innenabstand, Abstand zwischen Bausteinen und vor einem Abschnitt.
     * Siehe docs/entscheidungen/0010-tafel-200-einheiten.md.
     */
    static final int BREITE = 200, INNEN = 6, ABSTAND = 4, VOR_ABSCHNITT = 6;
    /**
     * Wertung: die Spalte der Labels so breit wie das breiteste und eine Lücke, höchstens {@code LABEL};
     * die Punkte quadratisch mit einer Einheit Lücke.
     */
    static final int LABEL = 80, LUECKE = 4, PUNKT = 5;

    sealed interface Baustein permits Titel, Zeilen, Bild, Abschnitt, Wertung, Spalten {
    }

    record Titel(String text, int farbe) implements Baustein {
    }

    record Zeilen(List<String> zeilen) implements Baustein {
    }

    /** Ein Bild der Ebene; {@code ausrichtung} -1 links, 0 mittig, 1 rechts; {@code alt} steht, wo es fehlt. */
    record Bild(String feld, int breite, int hoehe, int ausrichtung, String alt) implements Baustein {
    }

    /** Ein Abschnitt mit Überschrift als Bild oder Text; sein Inhalt ohne eigene Abschnitte und Spalten. */
    record Abschnitt(Bild kopfBild, String kopfText, List<Baustein> inhalt) implements Baustein {
    }

    record Reihe(String name, int wert, int max, int farbe) {
    }

    record Wertung(List<Reihe> reihen) implements Baustein {
    }

    /** Zwei Spalten, oben bündig, die rechte so breit wie ihr Inhalt; beide ohne eigene Abschnitte und Spalten. */
    record Spalten(List<Baustein> links, List<Baustein> rechts) implements Baustein {
    }

    /**
     * Liest {@code panel}: höchstens {@link #MAX_BAUSTEINE} Bausteine, zwei Ebenen tief; unbekannte und
     * kaputte fallen weg, ebenso eine verschachtelte Spalte oder ein verschachtelter Abschnitt.
     */
    static Tafel lies(JsonObject panel) {
        int[] zahl = {0};
        return new Tafel(liste(panel.getAsJsonArray("blocks"), true, zahl));
    }

    private static List<Baustein> liste(JsonArray bloecke, boolean oben, int[] zahl) {
        List<Baustein> aus = new ArrayList<>();
        for (JsonElement e : bloecke) {
            if (zahl[0] >= MAX_BAUSTEINE) {
                break;
            }
            try {
                Baustein b = baustein(e.getAsJsonObject(), oben, zahl);
                if (b != null) {
                    aus.add(b);
                }
            } catch (RuntimeException fehler) {
                // Ein kaputter Baustein fehlt, die übrigen gelten.
            }
        }
        return List.copyOf(aus);
    }

    private static Baustein baustein(JsonObject o, boolean oben, int[] zahl) {
        String typ = o.has("type") ? o.get("type").getAsString() : "";
        Baustein b = switch (typ) {
            case "title" -> {
                String t = Ebenen.text(o, "text", MAX_TITEL);
                yield t == null ? null : new Titel(t, o.has("color") ? Ebenen.farbeMitAlpha(o.get("color").getAsString(), SCHRIFT) : SCHRIFT);
            }
            case "lines" -> {
                List<String> zeilen = new ArrayList<>();
                for (JsonElement z : o.getAsJsonArray("lines")) {
                    String t = z.getAsString();
                    if (zeilen.size() < MAX_ZEILEN && t.length() <= MAX_ZEILE) {
                        zeilen.add(ChatFormatting.stripFormatting(t));
                    }
                }
                yield new Zeilen(List.copyOf(zeilen));
            }
            case "image" -> bild(o);
            case "rating" -> {
                List<Reihe> reihen = new ArrayList<>();
                for (JsonElement r : o.getAsJsonArray("rows")) {
                    JsonObject reihe = r.getAsJsonObject();
                    int max = reihe.get("max").getAsInt(), wert = reihe.get("value").getAsInt();
                    if (reihen.size() < MAX_REIHEN && max >= 1 && max <= MAX_PUNKTE && wert >= 0 && wert <= max) {
                        String name = Ebenen.text(reihe, "label", MAX_TITEL);
                        reihen.add(new Reihe(name == null ? "" : name, wert, max,
                                reihe.has("color") ? Ebenen.farbeMitAlpha(reihe.get("color").getAsString(), SCHRIFT) : SCHRIFT));
                    }
                }
                yield new Wertung(List.copyOf(reihen));
            }
            case "section" -> {
                if (!oben) {
                    yield null;
                }
                JsonObject kopf = o.has("heading") ? o.getAsJsonObject("heading") : new JsonObject();
                Bild bild = kopf.has("image") ? bild(kopf) : null;
                String text = Ebenen.text(kopf, "text", MAX_TITEL);
                zahl[0]++;
                yield new Abschnitt(bild, bild == null ? text : null, liste(o.getAsJsonArray("blocks"), false, zahl));
            }
            case "columns" -> {
                if (!oben) {
                    yield null;
                }
                JsonArray spalten = o.getAsJsonArray("columns");
                zahl[0]++;
                yield new Spalten(liste(spalten.get(0).getAsJsonArray(), false, zahl), liste(spalten.get(1).getAsJsonArray(), false, zahl));
            }
            default -> null;
        };
        // Abschnitt und Spalten zählen sich schon vor ihrem Inhalt.
        if (b != null && !(b instanceof Abschnitt) && !(b instanceof Spalten)) {
            zahl[0]++;
        }
        return b;
    }

    private static Bild bild(JsonObject o) {
        String feld = o.get("image").getAsString();
        int b = o.get("width").getAsInt(), h = o.get("height").getAsInt();
        if (feld.length() > Ebenen.MAX_FELD || !Symbole.FELD.matcher(feld).matches() || b < 1 || h < 1 || b > MAX_BILD || h > MAX_BILD) {
            return null;
        }
        int ausrichtung = switch (o.has("align") ? o.get("align").getAsString() : "left") {
            case "center" -> 0;
            case "right" -> 1;
            default -> -1;
        };
        return new Bild(feld, b, h, ausrichtung, Ebenen.text(o, "alt", MAX_TITEL));
    }

    /** Wie breit Text ist und wie er umbricht; im Spiel die Schrift, in Tests eine feste Breite je Zeichen. */
    interface Masse {

        int breite(String text, boolean fett);

        List<String> umbruch(String text, int breite, boolean fett);

        int zeile();

        /** Der Text, so weit er in {@code breite} passt. */
        default String kuerze(String text, int breite, boolean fett) {
            String t = text;
            while (!t.isEmpty() && breite(t, fett) > breite) {
                t = t.substring(0, t.length() - 1);
            }
            return t;
        }
    }

    /** Ein Stück der gesetzten Tafel, relativ zur linken oberen Ecke des Inhalts. */
    sealed interface Stueck permits Text, Bildstueck, Punkt {
    }

    record Text(int x, int y, String text, int farbe, boolean fett) implements Stueck {
    }

    record Bildstueck(int x, int y, int breite, int hoehe, Bild bild) implements Stueck {
    }

    record Punkt(int x, int y, int seite, int farbe) implements Stueck {
    }

    /** Die gesetzte Tafel: ihre Stücke und die Grösse des Inhalts. */
    record Satz(List<Stueck> stuecke, int breite, int hoehe) {
    }

    /** Setzt die Tafel: so breit wie ihr breitester Baustein, höchstens {@link #BREITE}, so hoch wie ihr Inhalt. */
    static Satz setze(Tafel t, Masse m) {
        int breite = 1;
        for (Baustein b : t.bausteine()) {
            breite = Math.max(breite, natuerlich(b, m));
        }
        breite = Math.min(breite, BREITE);
        List<Stueck> aus = new ArrayList<>();
        int y = setze(t.bausteine(), 0, 0, breite, m, aus);
        return new Satz(List.copyOf(aus), breite, y);
    }

    /** Setzt die Bausteine ab (x, y) in der Breite b; zurück kommt das y unter dem letzten. */
    private static int setze(List<Baustein> bausteine, int x, int y, int b, Masse m, List<Stueck> aus) {
        boolean erster = true;
        for (Baustein baustein : bausteine) {
            if (!erster) {
                y += baustein instanceof Abschnitt ? VOR_ABSCHNITT : ABSTAND;
            }
            erster = false;
            y = switch (baustein) {
                case Titel titel -> text(titel.text(), titel.farbe(), true, x, y, b, m, aus);
                case Zeilen zeilen -> {
                    int z = y;
                    for (String zeile : zeilen.zeilen()) {
                        z = text(zeile, SCHRIFT, false, x, z, b, m, aus);
                    }
                    yield z;
                }
                case Bild bild -> bild(bild, x, y, b, aus);
                case Abschnitt a -> {
                    int z = a.kopfBild() != null ? bild(a.kopfBild(), x, y, b, aus)
                            : a.kopfText() != null ? text(a.kopfText(), SCHRIFT, true, x, y, b, m, aus) : y;
                    // Ohne Überschrift kein Abstand vor dem Inhalt.
                    yield a.inhalt().isEmpty() ? z : setze(a.inhalt(), x, z == y ? z : z + ABSTAND, b, m, aus);
                }
                case Wertung w -> {
                    int z = y, spalte = spalte(w, m);
                    for (Reihe r : w.reihen()) {
                        aus.add(new Text(x, z, m.kuerze(r.name(), spalte - LUECKE, false), SCHRIFT, false));
                        for (int i = 0; i < r.max(); i++) {
                            // Die Punkte über dem Wert in der Farbe zu 25 % deckend.
                            int farbe = i < r.wert() ? r.farbe() : (r.farbe() & 0x00FFFFFF) | 0x40000000;
                            aus.add(new Punkt(x + spalte + i * (PUNKT + 1), z + (m.zeile() - PUNKT) / 2, PUNKT, farbe));
                        }
                        z += m.zeile();
                    }
                    yield z;
                }
                case Spalten s -> {
                    int rechts = 0;
                    for (Baustein r : s.rechts()) {
                        rechts = Math.max(rechts, natuerlich(r, m));
                    }
                    rechts = Math.min(rechts, b / 2);
                    int links = b - rechts - ABSTAND;
                    yield Math.max(setze(s.links(), x, y, links, m, aus), setze(s.rechts(), x + links + ABSTAND, y, rechts, m, aus));
                }
            };
        }
        return y;
    }

    private static int text(String text, int farbe, boolean fett, int x, int y, int b, Masse m, List<Stueck> aus) {
        for (String zeile : m.umbruch(text, b, fett)) {
            aus.add(new Text(x, y, zeile, farbe, fett));
            y += m.zeile();
        }
        return y;
    }

    /** Ein Bild in seiner Grösse, breiter als der Platz mit gleichem Seitenverhältnis verkleinert, nie vergrössert. */
    private static int bild(Bild bild, int x, int y, int b, List<Stueck> aus) {
        int w = Math.min(bild.breite(), b), h = (int) Math.round((double) bild.hoehe() * w / bild.breite());
        int dx = bild.ausrichtung() < 0 ? 0 : bild.ausrichtung() == 0 ? (b - w) / 2 : b - w;
        aus.add(new Bildstueck(x + dx, y, w, h, bild));
        return y + h;
    }

    /** Die Spalte der Labels einer Wertung: das breiteste Label und die Lücke, höchstens {@link #LABEL}. */
    private static int spalte(Wertung w, Masse m) {
        return Math.min(w.reihen().stream().mapToInt(r -> m.breite(r.name(), false)).max().orElse(0) + LUECKE, LABEL);
    }

    /** Wie breit ein Baustein ohne Umbruch wäre. */
    private static int natuerlich(Baustein b, Masse m) {
        return switch (b) {
            case Titel t -> m.breite(t.text(), true);
            case Zeilen z -> z.zeilen().stream().mapToInt(s -> m.breite(s, false)).max().orElse(0);
            case Bild bild -> bild.breite();
            case Abschnitt a -> Math.max(a.kopfBild() != null ? a.kopfBild().breite() : a.kopfText() != null ? m.breite(a.kopfText(), true) : 0,
                    a.inhalt().stream().mapToInt(i -> natuerlich(i, m)).max().orElse(0));
            case Wertung w -> spalte(w, m) + w.reihen().stream().mapToInt(r -> r.max() * (PUNKT + 1)).max().orElse(0);
            case Spalten s -> s.links().stream().mapToInt(i -> natuerlich(i, m)).max().orElse(0) + ABSTAND
                    + s.rechts().stream().mapToInt(i -> natuerlich(i, m)).max().orElse(0);
        };
    }
}
