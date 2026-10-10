package com.nekyia.heroicmap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Die Ebenen vom Server: die Liste aus {@code ebenen}, die Objekte jeder Ebene aus den Teilen von
 * {@code ebene}, dazu die Wahl des Spielers, welche an ist: Nadeln, Flächen, Kreise und Linien. Ein Teil
 * liest schon der Thread des Netzes ({@link Teil#lies}), alles andere läuft auf dem Render-Thread.
 * Siehe docs/ebenen.md.
 */
final class Ebenen {

    static final Ebenen INSTANZ = new Ebenen();
    /** Grenzen aus dem Format, siehe docs/ebenen.md, „Grenzen“. */
    static final int MAX_EBENEN = 64, MAX_NADELN = 1000;
    /** Eine eigene Grenze des Mods: Für eine Datei von 4 MiB braucht ein ehrliches Plugin rund 130 Teile. */
    static final int MAX_TEILE = 256;
    /** So lang ist höchstens ein Name, eine Kennung oder eine Dimension; länger übergeht der Mod. */
    static final int MAX_TEXT = 64, MAX_KENNUNG = 129;
    /** So lang ist höchstens ein Feld eines Symbols: {@code images/}, 64 Zeichen, {@code .webp}. */
    static final int MAX_FELD = 76;
    /** Grenzen aus dem Format für Objekte, Punkte, Löcher und Radius, siehe docs/ebenen.md, „Grenzen“. */
    static final int MAX_OBJEKTE = 10_000, MAX_PUNKTE = 10_000, MAX_LOECHER = 100, MAX_RADIUS = 100_000;
    /**
     * Eine eigene Grenze des Mods: so viele Punkte über alle Formen je Teil und je Sammlung, ein Kreis
     * zählt einen. Als JSON sind das rund 3 MiB, so viel wie eine Datei im Format; im Speicher mit den
     * Trapezen höchstens rund 30 MB.
     */
    static final int MAX_PUNKTE_EBENE = 200_000;
    /**
     * Über alle Ebenen höchstens so viele Punkte, je Ebene die fertige Sammlung oder die halbe, die
     * grössere. So bleiben Formen im Speicher höchstens rund 80 MB, auch während einer neuen {@code version} rund 160 MB.
     */
    static final int MAX_PUNKTE_GESAMT = 500_000;
    /** Ein Rand ist höchstens so breit, Strich und Lücke höchstens so lang, in Einheiten des GUI. */
    static final float MAX_BREITE = 64, MAX_STRICH = 1000;
    /** Weiter draussen liegt kein Punkt einer Welt. */
    static final double MAX_KOORDINATE = 30_000_000;
    /** Die Farbe eines Rands ohne Farbe und ohne Füllung. */
    static final int RANDFARBE = 0xFF2B2B2B;
    /** Kartenschrift: höchstens so viele Punkte im Pfad, wie im Format; Farbe und Kontur ohne Angabe; Sperrung gekappt. */
    static final int MAX_PFAD = 64, SCHRIFTFARBE = 0xFF2B2B2B, KONTURFARBE = 0xFFF2E8D0;
    static final float MAX_SPERRUNG = 1;
    static final String UEBERWELT = "minecraft:overworld";
    /** Die Farbe des Schilds ohne {@code color}. */
    static final int FARBE = 0xFFD9443A;
    /** Der Name unter dem Fuss: Kartenschrift mit 12 Einheiten je Geviert, Zeilenhöhe 1,4, 3 Einheiten Rand zur Seite. */
    static final float NAME_GROESSE = 12, NAME_ZEILE = 1.4f * NAME_GROESSE, NAME_RAND = 3;
    /** Die Farben der UI wie auf der Webkarte: Grund weiss mit Alpha 0,8, Schrift schwarz, ohne Kontur. */
    private static final int NAME_GRUND = 0xCCFFFFFF, NAME_SCHRIFT = 0xFF000000;
    private static final Logger LOGGER = LoggerFactory.getLogger(HeroicMap.ID);

    /** Ein Eintrag der Liste; {@code order} höher liegt oben. */
    record Eintrag(String id, String nameDe, String nameEn, boolean sichtbar, int order, String version) {

        /** Der Name in der Sprache des Spiels, sonst in der anderen. */
        String name(boolean deutsch) {
            String erst = deutsch ? nameDe : nameEn, sonst = deutsch ? nameEn : nameDe;
            return erst != null ? erst : sonst != null ? sonst : id;
        }
    }

    /** Ein Ort der Karte mit Fuss bei (x, z): eine Nadel oder ein Banner; zusammen höchstens {@link #MAX_NADELN} je Ebene. */
    sealed interface Ort permits Nadel, Banner {

        double x();

        double z();

        String dimension();

        String name();
    }

    /**
     * Eine Nadel mit dem Fuss bei (x, z); {@code groesse} 0 ist {@code large}, 1 {@code medium}, 2
     * {@code small}. Dazu ihre Ebene und deren {@code version} und die Felder ihrer Symbole oder null.
     */
    record Nadel(double x, double z, String dimension, String name, int groesse, int farbe, String ebene, String version,
            String symbolGross, String symbolMittel) implements Ort {
    }

    /** Ein Banner: das Bild {@code bild} der Ebene, Pixel auf Pixel, der Fuss unten mittig auf dem Ort; darunter der Name. */
    record Banner(double x, double z, String dimension, String name, String bild, String ebene, String version) implements Ort {
    }

    /** Ein Rand in Einheiten des GUI: Farbe mit Alpha, Breite, gestrichelt Strich und Lücke, sonst beide 0. */
    record Rand(int farbe, float breite, float strich, float luecke) {
    }

    /** Eine Fläche, ein Kreis oder eine Linie einer Ebene, flach gezeichnet. */
    sealed interface Form permits Flaeche, Kreis, Linie, Schrift {

        String dimension();
    }

    /**
     * Eine Region: die Füllung mit Alpha als Trapeze aus {@link Trapeze}, null ohne Füllung oder wenn sie
     * zu aufwendig ist; der Rand (null ohne) um alle Ringe {x0, z0, …}; {@code box} {x0, z0, x1, z1}.
     */
    record Flaeche(String dimension, int fuellung, double[] trapeze, Rand rand, List<double[]> ringe, double[] box) implements Form {
    }

    /** Ein Kreis um (x, z) mit {@code radius} Blöcken, Füllung mit Alpha (Alpha 0 ohne) und Rand (null ohne). */
    record Kreis(String dimension, double x, double z, double radius, int fuellung, Rand rand) implements Form {
    }

    /** Eine Linie durch {@code punkte} {x0, z0, …}; {@code box} {x0, z0, x1, z1}. */
    record Linie(String dimension, double[] punkte, Rand rand, double[] box) implements Form {
    }

    /**
     * Eine Kartenschrift: {@code text} entlang des Pfads {x0, z0, …}; {@code groesse} die Höhe der
     * Grossbuchstaben in Blöcken, {@code sperrung} der Abstand zwischen den Zeichen in Anteilen davon;
     * Farbe mit Alpha; Kontur in Einheiten des GUI, Breite 0 ohne. {@code box} {x0, z0, x1, z1} des Pfads.
     */
    record Schrift(String dimension, String text, double[] pfad, float groesse, float sperrung, int farbe, int konturFarbe, float konturBreite,
            double[] box) implements Form {
    }

    /** Schild und Nadel einer Grösse: Feld zum Tönen und Rahmen mit Nadel, gleich gross, der Fuss unten in der Mitte. */
    private record Schild(Identifier feld, Identifier rahmen, int breite, int hoehe) {

        static Schild von(String name, int breite, int hoehe) {
            return new Schild(Identifier.fromNamespaceAndPath(HeroicMap.ID, "ebenen/schild_" + name),
                    Identifier.fromNamespaceAndPath(HeroicMap.ID, "ebenen/schild_" + name + "_rahmen"), breite, hoehe);
        }
    }

    private static final Schild[] SCHILDE = {Schild.von("gross", 23, 33), Schild.von("mittel", 15, 23), Schild.von("klein", 9, 15)};

    /**
     * Ein Teil {@code ebene}, schon gelesen: seine Nadeln, höchstens {@link #MAX_NADELN} + 1, und seine
     * Formen mit ihren Punkten und wie viele verworfen sind, damit der Render-Thread kein JSON bekommt
     * und keins liegen bleibt.
     */
    record Teil(String id, String version, int teil, int teile, List<Ort> nadeln, List<Form> formen, int punkte, int verworfen) {

        /** Liest eine Nachricht auf dem Thread des Netzes; null, wenn sie keine {@code ebene} ist oder nicht taugt. */
        static Teil lies(String text) {
            try {
                JsonObject json = JsonParser.parseString(text).getAsJsonObject();
                if (json.get("v").getAsInt() != 1 || !"ebene".equals(json.get("typ").getAsString())) {
                    return null;
                }
                String id = text(json, "id", MAX_KENNUNG), version = text(json, "version", MAX_KENNUNG);
                Gelesen f = id == null || version == null ? null : Ebenen.formen(json.getAsJsonArray("objects"));
                int[] banner = {0};
                List<Ort> nadeln = f == null ? null : Ebenen.nadeln(id, version, json.getAsJsonArray("objects"), banner);
                return f == null ? null
                        : new Teil(id, version, json.get("teil").getAsInt(), json.get("teile").getAsInt(), nadeln, f.formen(), f.punkte(),
                                f.verworfen() + banner[0]);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** Die Formen eines Teils, ihre Punkte und wie viele Objekte als Form verworfen sind; die Banner zählt {@link #nadeln}. */
    record Gelesen(List<Form> formen, int punkte, int verworfen) {
    }

    /** Die Teile einer {@code version}, die noch nicht alle da sind, und was sie schon zusammen haben. */
    private static final class Sammlung {

        final String version;
        final List<Teil> teile;
        int da, nadeln, objekte, punkte, verworfen;

        Sammlung(String version, int teile) {
            this.version = version;
            this.teile = new ArrayList<>(Collections.nCopies(teile, null));
        }
    }

    private List<Eintrag> liste = List.of();
    private final Map<String, List<Ort>> nadeln = new HashMap<>();
    private final Map<String, List<Form>> formen = new HashMap<>();
    /** Die Punkte der fertigen Formen je Ebene. */
    private final Map<String, Integer> punkte = new HashMap<>();
    /** Je Ebene die version einer verworfenen Sammlung; ihre übrigen Teile übergeht der Mod. */
    private final Map<String, String> verworfen = new HashMap<>();
    private final Map<String, Sammlung> sammlungen = new HashMap<>();
    /** Die Wahl des Spielers je Kennung und wo sie liegt; null heisst nur im Speicher. */
    private final Map<String, Boolean> wahl = new HashMap<>();
    private Path datei;

    /** Die Liste {@code ebenen}; eine kaputte ändert nichts und gibt false. */
    boolean empfange(JsonObject json) {
        try {
            liste(json);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("Heroic Map: Liste der Ebenen nicht lesbar: {}", e.toString());
            return false;
        }
    }

    /** Die Liste {@code ebenen}: Was fehlt, ist weg, samt seinen Nadeln und halben Teilen. */
    void liste(JsonObject json) {
        List<Eintrag> neu = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("ebenen")) {
            if (neu.size() == MAX_EBENEN) {
                LOGGER.warn("Heroic Map: mehr als {} Ebenen, die übrigen fehlen", MAX_EBENEN);
                break;
            }
            JsonObject o = e.getAsJsonObject();
            JsonObject name = o.has("name") ? o.getAsJsonObject("name") : new JsonObject();
            String id = text(o, "id", MAX_KENNUNG), version = text(o, "version", MAX_KENNUNG);
            if (id == null || version == null) {
                throw new IllegalArgumentException("id oder version");
            }
            if (neu.stream().anyMatch(x -> x.id().equals(id))) {
                // Eine Kennung zweimal: Es gilt der erste Eintrag.
                continue;
            }
            neu.add(new Eintrag(id, text(name, "de", MAX_TEXT), text(name, "en", MAX_TEXT),
                    !o.has("visible") || o.get("visible").getAsBoolean(), o.has("order") ? o.get("order").getAsInt() : 0, version));
        }
        // Oben liegt, was später gezeichnet wird: aufsteigend nach order, bei Gleichstand nach id absteigend.
        neu.sort(Comparator.comparingInt(Eintrag::order).thenComparing(Eintrag::id, Comparator.reverseOrder()));
        liste = List.copyOf(neu);
        nadeln.keySet().retainAll(liste.stream().map(Eintrag::id).toList());
        formen.keySet().retainAll(liste.stream().map(Eintrag::id).toList());
        punkte.keySet().retainAll(liste.stream().map(Eintrag::id).toList());
        verworfen.entrySet().removeIf(v -> liste.stream().noneMatch(e -> e.id().equals(v.getKey()) && e.version().equals(v.getValue())));
        // Halbe Teile gelten nur, solange die Liste ihre version nennt.
        sammlungen.entrySet().removeIf(s -> liste.stream().noneMatch(e -> e.id().equals(s.getKey()) && e.version().equals(s.getValue().version)));
    }

    /**
     * Ein Teil {@code ebene}. Er gilt nur mit der {@code version}, die die Liste nennt; das Plugin
     * schickt die Liste vor den Teilen. Sind alle Teile da, ersetzen ihre Nadeln die der Ebene, bis
     * dahin bleibt die alte. Kommt eine Sammlung über {@link #MAX_NADELN} Nadeln, {@link #MAX_OBJEKTE}
     * Objekte oder {@link #MAX_PUNKTE_EBENE} Punkte, ist sie verworfen. Verworfene Formen und Banner meldet
     * das Log einmal je Ebene und {@code version}, wenn sie fertig ist.
     */
    void teil(Teil t) {
        if (t.teile() < 1 || t.teile() > MAX_TEILE || t.teil() < 1 || t.teil() > t.teile()
                || liste.stream().noneMatch(e -> e.id().equals(t.id()) && e.version().equals(t.version()))
                || t.version().equals(verworfen.get(t.id()))) {
            return;
        }
        Sammlung s = sammlungen.get(t.id());
        if (s == null || s.teile.size() != t.teile()) {
            s = new Sammlung(t.version(), t.teile());
            sammlungen.put(t.id(), s);
        }
        Teil vorher = s.teile.set(t.teil() - 1, t);
        s.da += vorher == null ? 1 : 0;
        s.nadeln += t.nadeln().size() - (vorher == null ? 0 : vorher.nadeln().size());
        s.objekte += t.nadeln().size() + t.formen().size() - (vorher == null ? 0 : vorher.nadeln().size() + vorher.formen().size());
        s.punkte += t.punkte() - (vorher == null ? 0 : vorher.punkte());
        s.verworfen += t.verworfen() - (vorher == null ? 0 : vorher.verworfen());
        if (s.nadeln > MAX_NADELN || s.objekte > MAX_OBJEKTE || s.punkte > MAX_PUNKTE_EBENE || gesamt() > MAX_PUNKTE_GESAMT) {
            LOGGER.warn("Heroic Map: Ebene {} ist zu gross ({} Nadeln, {} Objekte, {} Punkte, über alle Ebenen {}), sie bleibt, wie sie war",
                    t.id(), s.nadeln, s.objekte, s.punkte, gesamt());
            sammlungen.remove(t.id());
            verworfen.put(t.id(), t.version());
        } else if (s.da == s.teile.size()) {
            sammlungen.remove(t.id());
            if (s.verworfen > 0) {
                LOGGER.warn("Heroic Map: Ebene {} ({}): {} Banner oder Formen ungültig, oder Formen ohne Füllung, weil zu aufwendig",
                        t.id(), t.version(), s.verworfen);
            }
            nadeln.put(t.id(), s.teile.stream().flatMap(x -> x.nadeln().stream()).toList());
            formen.put(t.id(), s.teile.stream().flatMap(x -> x.formen().stream()).toList());
            punkte.put(t.id(), s.punkte);
        }
    }

    /** Die Punkte über alle Ebenen, je Ebene die fertige oder die halbe Sammlung, die grössere. */
    private int gesamt() {
        Map<String, Integer> je = new HashMap<>(punkte);
        sammlungen.forEach((id, s) -> je.merge(id, s.punkte, Math::max));
        return je.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Wie {@link #nadeln(String, String, JsonArray, int[])}, ohne die verworfenen Banner zu zählen. */
    static List<Ort> nadeln(String ebene, String version, JsonArray objekte) {
        return nadeln(ebene, version, objekte, new int[1]);
    }

    /**
     * Die Nadeln und Banner aus den Objekten eines Teils, in ihrer Reihenfolge, zusammen höchstens
     * {@link #MAX_NADELN} + 1; anderes und Kaputtes fällt weg. Ein Banner ohne gültiges Bild oder
     * kaputt zählt in {@code verworfen[0]}, damit das Log es nennt, wie das Format verlangt.
     */
    static List<Ort> nadeln(String ebene, String version, JsonArray objekte, int[] verworfen) {
        List<Ort> aus = new ArrayList<>();
        for (JsonElement e : objekte) {
            if (aus.size() > MAX_NADELN) {
                break;
            }
            String typ = "";
            try {
                JsonObject o = e.getAsJsonObject();
                typ = o.has("type") ? o.get("type").getAsString() : "";
                Ort n = typ.equals("pin") ? nadel(o, ebene, version) : typ.equals("banner") ? banner(o, ebene, version) : null;
                if (n != null) {
                    aus.add(n);
                } else if (typ.equals("banner")) {
                    verworfen[0]++;
                }
            } catch (RuntimeException fehler) {
                // Ein kaputtes Objekt fehlt, die übrigen gelten.
                verworfen[0] += typ.equals("banner") ? 1 : 0;
            }
        }
        return List.copyOf(aus);
    }

    /**
     * Eine Nadel; null mit einer Dimension über {@link #MAX_KENNUNG} Zeichen. Ein Name über
     * {@link #MAX_TEXT} fehlt, ebenso ein Feld eines Symbols über {@link #MAX_FELD}.
     */
    private static Nadel nadel(JsonObject o, String ebene, String version) {
        JsonArray at = o.getAsJsonArray("at");
        JsonObject symbol = o.has("symbol") ? o.getAsJsonObject("symbol") : new JsonObject();
        int groesse = switch (o.has("size") ? o.get("size").getAsString() : "medium") {
            case "large" -> 0;
            case "small" -> 2;
            default -> 1;
        };
        String dimension = o.has("dimension") ? text(o, "dimension", MAX_KENNUNG) : UEBERWELT;
        return dimension == null ? null : new Nadel(at.get(0).getAsDouble(), at.get(1).getAsDouble(), dimension,
                text(o, "name", MAX_TEXT), groesse, o.has("color") ? farbe(o.get("color").getAsString()) : FARBE, ebene, version,
                feld(symbol, "large"), feld(symbol, "medium"));
    }

    /** Ein Banner; null ohne Bild unter {@code images/}, mit einem Feld über {@link #MAX_FELD} oder einer zu langen Dimension. */
    private static Banner banner(JsonObject o, String ebene, String version) {
        JsonArray at = o.getAsJsonArray("at");
        String dimension = o.has("dimension") ? text(o, "dimension", MAX_KENNUNG) : UEBERWELT;
        String bild = feld(o, "image");
        return dimension == null || bild == null || !Symbole.FELD.matcher(bild).matches() ? null
                : new Banner(at.get(0).getAsDouble(), at.get(1).getAsDouble(), dimension, text(o, "name", MAX_TEXT), bild, ebene, version);
    }

    /** Das Feld eines Symbols wie es steht, oder null, wenn es fehlt oder länger als {@link #MAX_FELD} ist; prüfen tut {@link Symbole#uri}. */
    private static String feld(JsonObject symbol, String groesse) {
        String f = symbol.has(groesse) ? symbol.get(groesse).getAsString() : null;
        return f == null || f.length() > MAX_FELD ? null : f;
    }

    /**
     * Die Flächen, Kreise und Linien aus den Objekten eines Teils, in ihrer Reihenfolge, höchstens
     * {@link #MAX_OBJEKTE} + 1; anderes fällt weg, Kaputtes zählt als verworfen, ebenso eine Füllung,
     * die zu aufwendig ist. Die Füllung einer Fläche rechnet schon hier {@link Trapeze}, auf dem Thread
     * des Netzes. Null, wenn die Formen mehr als {@link #MAX_PUNKTE_EBENE} Punkte haben; dann taugt der Teil nicht.
     */
    static Gelesen formen(JsonArray objekte) {
        List<Form> aus = new ArrayList<>();
        int punkte = 0, verworfen = 0;
        for (JsonElement e : objekte) {
            if (aus.size() > MAX_OBJEKTE) {
                break;
            }
            Form f;
            try {
                f = form(e.getAsJsonObject());
            } catch (RuntimeException fehler) {
                verworfen++;
                continue;
            }
            if (f != null) {
                punkte += punkte(f);
                if (punkte > MAX_PUNKTE_EBENE) {
                    return null;
                }
                if (f instanceof Flaeche fl && fl.trapeze() == null && sichtbar(fl.fuellung())) {
                    verworfen++;
                }
                aus.add(f);
            }
        }
        return new Gelesen(List.copyOf(aus), punkte, verworfen);
    }

    private static int punkte(Form f) {
        return switch (f) {
            case Flaeche fl -> fl.ringe().stream().mapToInt(r -> r.length / 2).sum();
            case Linie l -> l.punkte().length / 2;
            case Kreis k -> 1;
            case Schrift s -> s.pfad().length / 2;
        };
    }

    /** Hat die Füllung Alpha? {@code #00000000} heisst ohne. */
    static boolean sichtbar(int farbe) {
        return farbe >>> 24 != 0;
    }

    /** Eine Form; null, wenn das Objekt keine sein will. Verletzt es eine Grenze, wirft sie. */
    private static Form form(JsonObject o) {
        String typ = o.has("type") ? o.get("type").getAsString() : "";
        if (!typ.equals("region") && !typ.equals("circle") && !typ.equals("line") && !typ.equals("label")) {
            return null;
        }
        String dimension = o.has("dimension") ? text(o, "dimension", MAX_KENNUNG) : UEBERWELT;
        if (dimension == null) {
            throw new IllegalArgumentException("Grenze");
        }
        if (typ.equals("label")) {
            return schrift(o, dimension);
        }
        String fill = o.has("fill") ? o.get("fill").getAsString() : null;
        boolean gefuellt = fill != null && FARBE_MIT_ALPHA.matcher(fill).matches() && !typ.equals("line");
        int fuellung = gefuellt ? farbeMitAlpha(fill, 0) : 0;
        // Ohne Farbe ist der Rand bei Region und Kreis die Füllung ohne Alpha, auch bei #00000000.
        int vorgabe = gefuellt ? 0xFF000000 | fuellung : RANDFARBE;
        Rand rand = rand(o, vorgabe);
        switch (typ) {
            case "circle" -> {
                JsonArray c = o.getAsJsonArray("center");
                double x = koordinate(c.get(0)), z = koordinate(c.get(1)), r = o.get("radius").getAsDouble();
                if (!(r > 0 && r <= MAX_RADIUS)) {
                    throw new IllegalArgumentException("Radius " + r);
                }
                return new Kreis(dimension, x, z, r, fuellung, rand);
            }
            case "line" -> {
                double[] p = ring(o.getAsJsonArray("points"), 2);
                return new Linie(dimension, p, rand, box(List.of(p)));
            }
            default -> {
                List<double[]> ringe = new ArrayList<>();
                int punkte = 0;
                for (JsonElement e : o.getAsJsonArray("polygons")) {
                    JsonObject poly = e.getAsJsonObject();
                    JsonArray loecher = poly.has("holes") ? poly.getAsJsonArray("holes") : new JsonArray();
                    if (loecher.size() > MAX_LOECHER) {
                        throw new IllegalArgumentException("Grenze");
                    }
                    double[] aussen = ring(poly.getAsJsonArray("outer"), 3);
                    ringe.add(aussen);
                    punkte += aussen.length / 2;
                    for (JsonElement l : loecher) {
                        double[] loch = ring(l.getAsJsonArray(), 3);
                        ringe.add(loch);
                        punkte += loch.length / 2;
                    }
                    // Schon beim Lesen, nicht erst am Ende: Die Punkte über alle Ringe zählen.
                    if (punkte > MAX_PUNKTE) {
                        throw new IllegalArgumentException("Grenze");
                    }
                }
                if (ringe.isEmpty()) {
                    throw new IllegalArgumentException("Grenze");
                }
                double[] trapeze = sichtbar(fuellung) ? Trapeze.von(ringe) : null;
                return new Flaeche(dimension, fuellung, trapeze, rand, List.copyOf(ringe), box(ringe));
            }
        }
    }

    /**
     * Eine Kartenschrift: {@code text} bis {@link #MAX_TEXT} Zeichen, in NFC; {@code path} 1 bis
     * {@link #MAX_PFAD} Punkte; {@code size} Vorgabe 16 Blöcke, auch für 0 und Ungültiges; {@code spacing}
     * Vorgabe 0, gekappt auf 0 bis {@link #MAX_SPERRUNG}. {@code outline} nur als Objekt, ohne {@code width}
     * ohne Kontur, ohne {@code color} in {@link #KONTURFARBE}. Ein Feld mit falschem Typ nimmt die Vorgabe.
     * Wo das Format schweigt, wie die Webkarte.
     */
    private static Schrift schrift(JsonObject o, String dimension) {
        String text = text(o, "text", MAX_TEXT);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Text");
        }
        text = Normalizer.normalize(text, Normalizer.Form.NFC);
        double[] pfad = ring(o.getAsJsonArray("path"), 1);
        if (pfad.length / 2 > MAX_PFAD) {
            throw new IllegalArgumentException("Pfad " + pfad.length / 2);
        }
        float groesse = zahl(o, "size", 16);
        if (!(groesse > 0)) {
            groesse = 16;
        }
        if (groesse > MAX_RADIUS) {
            throw new IllegalArgumentException("Grösse " + groesse);
        }
        float sperrung = zahl(o, "spacing", 0);
        sperrung = Float.isFinite(sperrung) ? Math.max(0, Math.min(sperrung, MAX_SPERRUNG)) : 0;
        int farbe = farbeMitAlpha(o, "color", SCHRIFTFARBE);
        int konturFarbe = KONTURFARBE;
        float konturBreite = 0;
        if (o.has("outline") && o.get("outline").isJsonObject()) {
            JsonObject k = o.getAsJsonObject("outline");
            konturFarbe = farbeMitAlpha(k, "color", KONTURFARBE);
            float b = zahl(k, "width", 0);
            konturBreite = b > 0 ? Math.min(b, MAX_BREITE) : 0;
        }
        return new Schrift(dimension, text, pfad, groesse, sperrung, farbe, konturFarbe, konturBreite, box(List.of(pfad)));
    }

    /** Die Punkte [[x, z], …] als {x0, z0, …}; mit weniger als {@code mindestens} oder mehr als {@link #MAX_PUNKTE} wirft sie. */
    private static double[] ring(JsonArray punkte, int mindestens) {
        if (punkte.size() < mindestens || punkte.size() > MAX_PUNKTE) {
            throw new IllegalArgumentException("Punkte " + punkte.size());
        }
        double[] r = new double[2 * punkte.size()];
        for (int i = 0; i < punkte.size(); i++) {
            JsonArray p = punkte.get(i).getAsJsonArray();
            r[2 * i] = koordinate(p.get(0));
            r[2 * i + 1] = koordinate(p.get(1));
        }
        return r;
    }

    private static double koordinate(JsonElement e) {
        double v = e.getAsDouble();
        if (!(Math.abs(v) <= MAX_KOORDINATE)) {
            throw new IllegalArgumentException("Koordinate " + v);
        }
        return v;
    }

    private static double[] box(List<double[]> ringe) {
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (double[] r : ringe) {
            for (int i = 0; i < r.length; i += 2) {
                b[0] = Math.min(b[0], r[i]);
                b[1] = Math.min(b[1], r[i + 1]);
                b[2] = Math.max(b[2], r[i]);
                b[3] = Math.max(b[3], r[i + 1]);
            }
        }
        return b;
    }

    /**
     * Der Rand aus {@code stroke}: Vorgabe 2 breit in {@code vorgabe}, 0 heisst ohne (null);
     * {@code dashed} mit {@code dash}, Vorgabe [8, 6].
     */
    private static Rand rand(JsonObject o, int vorgabe) {
        JsonObject s = o.has("stroke") ? o.getAsJsonObject("stroke") : new JsonObject();
        float breite = s.has("width") ? s.get("width").getAsFloat() : 2;
        if (!(breite > 0)) {
            return null;
        }
        int farbe = s.has("color") ? farbeMitAlpha(s.get("color").getAsString(), vorgabe) : vorgabe;
        float strich = 0, luecke = 0;
        if (s.has("style") && "dashed".equals(s.get("style").getAsString())) {
            strich = 8;
            luecke = 6;
            if (s.has("dash")) {
                JsonArray d = s.getAsJsonArray("dash");
                float a = d.get(0).getAsFloat(), b = d.get(1).getAsFloat();
                if (a > 0 && b > 0) {
                    // Mindestens eine Einheit: Winzige Striche wären Millionen je Strecke.
                    strich = Math.max(1, Math.min(a, MAX_STRICH));
                    luecke = Math.max(1, Math.min(b, MAX_STRICH));
                }
            }
        }
        return new Rand(farbe, Math.min(breite, MAX_BREITE), strich, luecke);
    }

    private static final Pattern FARBE_MIT_ALPHA = Pattern.compile("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?");

    /** Das Feld als Zahl, nur wenn es eine JSON-Zahl ist; sonst, auch für "12", {@code sonst}. */
    static float zahl(JsonObject o, String feld, float sonst) {
        JsonElement e = o.get(feld);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsFloat() : sonst;
    }

    /** Das Feld als Farbe wie {@link #farbeMitAlpha(String, int)}, nur wenn es ein Text ist; sonst {@code sonst}. */
    static int farbeMitAlpha(JsonObject o, String feld, int sonst) {
        JsonElement e = o.get(feld);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? farbeMitAlpha(e.getAsString(), sonst) : sonst;
    }

    /** {@code #RRGGBB} deckend oder {@code #RRGGBBAA} mit Alpha als ARGB; sonst {@code sonst}. */
    static int farbeMitAlpha(String text, int sonst) {
        if (!FARBE_MIT_ALPHA.matcher(text).matches()) {
            return sonst;
        }
        int rgb = Integer.parseInt(text.substring(1, 7), 16);
        return (text.length() == 9 ? Integer.parseInt(text.substring(7, 9), 16) : 0xFF) << 24 | rgb;
    }

    /** {@code #RRGGBB} oder {@code #RRGGBBAA} als deckendes ARGB; das Alpha wirkt am Schild nicht. */
    static int farbe(String text) {
        if (!text.matches("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?")) {
            return FARBE;
        }
        return 0xFF000000 | Integer.parseInt(text.substring(1, 7), 16);
    }

    /**
     * Ein Text vom Server als schlichter Text, Codes mit § gestrichen; null, wenn er fehlt oder länger
     * als {@code hoechstens} Zeichen ist.
     */
    private static String text(JsonObject o, String feld, int hoechstens) {
        if (!o.has(feld)) {
            return null;
        }
        String t = o.get(feld).getAsString();
        return t.length() > hoechstens ? null : ChatFormatting.stripFormatting(t);
    }

    /** Beim Trennen und bei einem neuen Login: Der Server schickt danach alles neu. */
    void leeren() {
        liste = List.of();
        nadeln.clear();
        formen.clear();
        punkte.clear();
        verworfen.clear();
        sammlungen.clear();
    }

    /** Die Ebenen, die gezeichnet werden, unten zuerst. */
    List<Eintrag> sichtbar() {
        return liste.stream().filter(this::an).toList();
    }

    /** Die Kennungen der Liste. */
    List<String> kennungen() {
        return liste.stream().map(Eintrag::id).toList();
    }

    /** Alle Ebenen für das Menü, oben zuerst. */
    List<Eintrag> alle() {
        return liste.reversed();
    }

    /** Ist die Ebene an: die Wahl des Spielers, sonst {@code visible}. */
    boolean an(Eintrag e) {
        return wahl.getOrDefault(e.id(), e.sichtbar());
    }

    /** Liest die Wahl aus {@code ebenen.properties} im Ordner der Welt; null heisst nur im Speicher. */
    void wechsel(Path ordner) {
        wahl.clear();
        datei = ordner == null ? null : ordner.resolve("ebenen.properties");
        if (datei == null || !Files.exists(datei)) {
            return;
        }
        Properties p = new Properties();
        try (Reader rein = Files.newBufferedReader(datei, StandardCharsets.UTF_8)) {
            p.load(rein);
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.warn("Heroic Map: {} nicht lesbar, alle Ebenen nach visible", datei);
            return;
        }
        p.stringPropertyNames().forEach(id -> wahl.put(id, Boolean.parseBoolean(p.getProperty(id))));
    }

    /** Schaltet eine Ebene an oder aus und schreibt die Wahl gleich. */
    void setze(String id, boolean an) {
        wahl.put(id, an);
        if (datei == null) {
            return;
        }
        Properties p = new Properties();
        wahl.forEach((k, v) -> p.setProperty(k, Boolean.toString(v)));
        try {
            Files.createDirectories(datei.getParent());
            try (Writer raus = Files.newBufferedWriter(datei, StandardCharsets.UTF_8)) {
                p.store(raus, "Heroic Map: Ebenen an oder aus");
            }
        } catch (IOException e) {
            LOGGER.warn("Heroic Map: {} nicht geschrieben", datei, e);
        }
    }

    /** Die Flächen, Kreise und Linien einer Ebene, die schon ganz da ist, sonst keine. */
    List<Form> formen(String id) {
        return formen.getOrDefault(id, List.of());
    }

    /** Die Nadeln einer Ebene, die schon ganz da ist, sonst keine. */
    List<Ort> nadeln(String id) {
        return nadeln.getOrDefault(id, List.of());
    }

    /**
     * Zeichnet den Ort mit dem Fuss bei (x, y), in Einheiten des GUI auf ganzen Pixeln, in fester
     * Grösse, darunter den Namen. Siehe docs/ebenen.md, „Nadeln“,
     * und docs/ebenen.md, „Banner“.
     */
    static void zeichne(GuiGraphicsExtractor g, Font font, float x, float y, Ort o) {
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        switch (o) {
            case Nadel n -> nadel(g, n);
            case Banner b -> {
                Symbole.Textur t = Symbole.INSTANZ.banner(b.ebene(), b.version(), b.bild());
                if (t == null) {
                    pose.popMatrix();
                    return;
                }
                // Pixel auf Pixel; der Fuss ⌊Breite / 2⌋ rechts der linken Kante, wie bei der Nadel.
                g.blit(RenderPipelines.GUI_TEXTURED, t.id(), -t.breite() / 2, -t.hoehe(), 0, 0, t.breite(), t.hoehe(), t.breite(), t.hoehe());
            }
        }
        if (o.name() != null) {
            name(g, font, o.name());
        }
        pose.popMatrix();
    }

    /** Wie breit der Kasten um den Namen ist, in Einheiten des GUI. */
    static float nameBreite(Font font, String name) {
        return font.width(Component.literal(name).withStyle(Formen.STIL)) * NAME_GROESSE / 16 + 2 * NAME_RAND;
    }

    /**
     * Der Name im Kasten direkt unter dem Fuss, mittig, die Grossbuchstaben mittig im Kasten.
     * Siehe docs/ebenen.md, „Nadeln“.
     */
    private static void name(GuiGraphicsExtractor g, Font font, String name) {
        Component c = Component.literal(name).withStyle(Formen.STIL);
        float m = NAME_GROESSE / 16, breite = font.width(c) * m;
        int halb = (int) Math.ceil(breite / 2 + NAME_RAND);
        g.fill(-halb, 0, halb, Math.round(NAME_ZEILE), NAME_GRUND);
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        // Die Oberkante der Grossbuchstaben liegt KAPPE − GRUNDLINIE Einheiten der Schrift über dem y des Texts.
        pose.translate(-breite / 2, (Math.round(NAME_ZEILE) - Formen.KAPPE * m) / 2 + (Formen.KAPPE - Formen.GRUNDLINIE) * m);
        pose.scale(m);
        g.text(font, c, 0, 0, NAME_SCHRIFT, false);
        pose.popMatrix();
    }

    /** Feld, Symbol und Rahmen der Nadel in ihrer Grösse, der Fuss im Ursprung. */
    private static void nadel(GuiGraphicsExtractor g, Nadel n) {
        int groesse = n.groesse();
        Schild s = SCHILDE[groesse];
        // Das Feld steht in Graustufen; die Grafikkarte multipliziert es mit der Farbe.
        g.blitSprite(RenderPipelines.GUI_TEXTURED, s.feld(), -s.breite() / 2, -s.hoehe(), s.breite(), s.hoehe(), n.farbe());
        int seite = groesse == 0 ? 16 : 9;
        Identifier symbol = groesse == 2 ? null : Symbole.INSTANZ.symbol(n.ebene(), n.version(), groesse == 0 ? n.symbolGross() : n.symbolMittel(), seite);
        if (symbol != null) {
            // Die linke obere Ecke bei (⌊(Breite − Seite) / 2⌋, 3) im Bild des Schilds.
            g.blit(RenderPipelines.GUI_TEXTURED, symbol, -s.breite() / 2 + (s.breite() - seite) / 2, -s.hoehe() + 3, 0, 0, seite, seite, seite, seite);
        }
        g.blitSprite(RenderPipelines.GUI_TEXTURED, s.rahmen(), -s.breite() / 2, -s.hoehe(), s.breite(), s.hoehe());
    }
}
