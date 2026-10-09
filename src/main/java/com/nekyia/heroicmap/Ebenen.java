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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
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
    /** Eigene Grenzen des Mods für die Füllung als Blöcke: Reihen und Rechtecke je Fläche, Rechtecke je Ebene. */
    static final int MAX_REIHEN = 65_536, MAX_RECHTECKE = 100_000, MAX_RECHTECKE_EBENE = 500_000;
    /** Ein Rand ist höchstens so breit, Strich und Lücke höchstens so lang, in Einheiten des GUI. */
    static final float MAX_BREITE = 64, MAX_STRICH = 1000;
    /** Weiter draussen liegt kein Punkt einer Welt. */
    static final double MAX_KOORDINATE = 30_000_000;
    /** Die Farbe eines Rands ohne Farbe und ohne Füllung. */
    static final int RANDFARBE = 0xFF2B2B2B;
    static final String UEBERWELT = "minecraft:overworld";
    /** Die Farbe des Schilds ohne {@code color}. */
    static final int FARBE = 0xFFD9443A;
    private static final int TEXT = 0xFFFFFFFF;
    private static final Logger LOGGER = LoggerFactory.getLogger(HeroicMap.ID);

    /** Ein Eintrag der Liste; {@code order} höher liegt oben. */
    record Eintrag(String id, String nameDe, String nameEn, boolean sichtbar, int order, String version) {

        /** Der Name in der Sprache des Spiels, sonst in der anderen. */
        String name(boolean deutsch) {
            String erst = deutsch ? nameDe : nameEn, sonst = deutsch ? nameEn : nameDe;
            return erst != null ? erst : sonst != null ? sonst : id;
        }
    }

    /**
     * Eine Nadel mit dem Fuss bei (x, z); {@code groesse} 0 ist {@code large}, 1 {@code medium}, 2
     * {@code small}. Dazu ihre Ebene und deren {@code version} und die Felder ihrer Symbole oder null.
     */
    record Nadel(double x, double z, String dimension, String name, int groesse, int farbe, String ebene, String version,
            String symbolGross, String symbolMittel) {
    }

    /** Ein Rand in Einheiten des GUI: Farbe mit Alpha, Breite, gestrichelt Strich und Lücke, sonst beide 0. */
    record Rand(int farbe, float breite, float strich, float luecke) {
    }

    /** Eine Fläche, ein Kreis oder eine Linie einer Ebene, flach gezeichnet. */
    sealed interface Form permits Flaeche, Kreis, Linie {

        String dimension();
    }

    /**
     * Eine Region: die Füllung (0 ohne) als Rechtecke in Blöcken aus {@link Raster}, null, wenn sie zu
     * gross ist; der Rand (null ohne) um alle Ringe {x0, z0, …}; {@code box} {x0, z0, x1, z1}.
     */
    record Flaeche(String dimension, int fuellung, int[] rechtecke, Rand rand, List<double[]> ringe, double[] box) implements Form {
    }

    /** Ein Kreis um (x, z) mit {@code radius} Blöcken, Füllung (0 ohne) und Rand (null ohne). */
    record Kreis(String dimension, double x, double z, double radius, int fuellung, Rand rand) implements Form {
    }

    /** Eine Linie durch {@code punkte} {x0, z0, …}; {@code box} {x0, z0, x1, z1}. */
    record Linie(String dimension, double[] punkte, Rand rand, double[] box) implements Form {
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
     * Ein Teil {@code ebene}, schon gelesen: nur seine Nadeln, höchstens {@link #MAX_NADELN} + 1, damit
     * der Render-Thread kein JSON bekommt und keins liegen bleibt.
     */
    record Teil(String id, String version, int teil, int teile, List<Nadel> nadeln, List<Form> formen) {

        /** Liest eine Nachricht auf dem Thread des Netzes; null, wenn sie keine {@code ebene} ist oder nicht taugt. */
        static Teil lies(String text) {
            try {
                JsonObject json = JsonParser.parseString(text).getAsJsonObject();
                if (json.get("v").getAsInt() != 1 || !"ebene".equals(json.get("typ").getAsString())) {
                    return null;
                }
                String id = text(json, "id", MAX_KENNUNG), version = text(json, "version", MAX_KENNUNG);
                return id == null || version == null ? null
                        : new Teil(id, version, json.get("teil").getAsInt(), json.get("teile").getAsInt(),
                                Ebenen.nadeln(id, version, json.getAsJsonArray("objects")), Ebenen.formen(json.getAsJsonArray("objects")));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** Die Teile einer {@code version}, die noch nicht alle da sind, und wie viele Nadeln sie schon haben. */
    private static final class Sammlung {

        final String version;
        final List<List<Nadel>> teile;
        final List<List<Form>> formTeile;
        int da, nadeln, objekte, rechtecke;

        Sammlung(String version, int teile) {
            this.version = version;
            this.teile = new ArrayList<>(Collections.nCopies(teile, null));
            this.formTeile = new ArrayList<>(Collections.nCopies(teile, null));
        }
    }

    private List<Eintrag> liste = List.of();
    private final Map<String, List<Nadel>> nadeln = new HashMap<>();
    private final Map<String, List<Form>> formen = new HashMap<>();
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
        // Halbe Teile gelten nur, solange die Liste ihre version nennt.
        sammlungen.entrySet().removeIf(s -> liste.stream().noneMatch(e -> e.id().equals(s.getKey()) && e.version().equals(s.getValue().version)));
    }

    /**
     * Ein Teil {@code ebene}. Er gilt nur mit der {@code version}, die die Liste nennt; das Plugin
     * schickt die Liste vor den Teilen. Sind alle Teile da, ersetzen ihre Nadeln die der Ebene, bis
     * dahin bleibt die alte. Kommt eine Sammlung über {@link #MAX_NADELN} Nadeln, {@link #MAX_OBJEKTE}
     * Objekte oder {@link #MAX_RECHTECKE_EBENE} Rechtecke, ist sie verworfen.
     */
    void teil(Teil t) {
        if (t.teile() < 1 || t.teile() > MAX_TEILE || t.teil() < 1 || t.teil() > t.teile()
                || liste.stream().noneMatch(e -> e.id().equals(t.id()) && e.version().equals(t.version()))) {
            return;
        }
        Sammlung s = sammlungen.get(t.id());
        if (s == null || s.teile.size() != t.teile()) {
            s = new Sammlung(t.version(), t.teile());
            sammlungen.put(t.id(), s);
        }
        List<Nadel> vorher = s.teile.set(t.teil() - 1, t.nadeln());
        List<Form> vorherFormen = s.formTeile.set(t.teil() - 1, t.formen());
        s.da += vorher == null ? 1 : 0;
        s.nadeln += t.nadeln().size() - (vorher == null ? 0 : vorher.size());
        s.objekte += t.nadeln().size() + t.formen().size() - (vorher == null ? 0 : vorher.size() + vorherFormen.size());
        s.rechtecke += rechtecke(t.formen()) - (vorherFormen == null ? 0 : rechtecke(vorherFormen));
        if (s.nadeln > MAX_NADELN || s.objekte > MAX_OBJEKTE || s.rechtecke > MAX_RECHTECKE_EBENE) {
            LOGGER.warn("Heroic Map: Ebene {} ist zu gross ({} Nadeln, {} Objekte, {} Rechtecke), sie bleibt, wie sie war",
                    t.id(), s.nadeln, s.objekte, s.rechtecke);
            sammlungen.remove(t.id());
        } else if (s.da == s.teile.size()) {
            sammlungen.remove(t.id());
            nadeln.put(t.id(), s.teile.stream().flatMap(List::stream).toList());
            formen.put(t.id(), s.formTeile.stream().flatMap(List::stream).toList());
        }
    }

    private static int rechtecke(List<Form> formen) {
        int n = 0;
        for (Form f : formen) {
            if (f instanceof Flaeche fl && fl.rechtecke() != null) {
                n += fl.rechtecke().length / 4;
            }
        }
        return n;
    }

    /** Die Nadeln aus den Objekten eines Teils, in ihrer Reihenfolge, höchstens {@link #MAX_NADELN} + 1; anderes und Kaputtes fällt weg. */
    static List<Nadel> nadeln(String ebene, String version, JsonArray objekte) {
        List<Nadel> aus = new ArrayList<>();
        for (JsonElement e : objekte) {
            if (aus.size() > MAX_NADELN) {
                break;
            }
            try {
                JsonObject o = e.getAsJsonObject();
                if ("pin".equals(o.has("type") ? o.get("type").getAsString() : null)) {
                    Nadel n = nadel(o, ebene, version);
                    if (n != null) {
                        aus.add(n);
                    }
                }
            } catch (RuntimeException fehler) {
                // Ein kaputtes Objekt fehlt, die übrigen gelten.
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

    /** Das Feld eines Symbols wie es steht, oder null, wenn es fehlt oder länger als {@link #MAX_FELD} ist; prüfen tut {@link Symbole#uri}. */
    private static String feld(JsonObject symbol, String groesse) {
        String f = symbol.has(groesse) ? symbol.get(groesse).getAsString() : null;
        return f == null || f.length() > MAX_FELD ? null : f;
    }

    /**
     * Die Flächen, Kreise und Linien aus den Objekten eines Teils, in ihrer Reihenfolge, höchstens
     * {@link #MAX_OBJEKTE} + 1; anderes und Kaputtes fällt weg. Die Füllung einer Fläche rechnet
     * schon hier {@link Raster}, auf dem Thread des Netzes.
     */
    static List<Form> formen(JsonArray objekte) {
        List<Form> aus = new ArrayList<>();
        for (JsonElement e : objekte) {
            if (aus.size() > MAX_OBJEKTE) {
                break;
            }
            try {
                Form f = form(e.getAsJsonObject());
                if (f != null) {
                    aus.add(f);
                }
            } catch (RuntimeException fehler) {
                // Ein kaputtes Objekt fehlt, die übrigen gelten.
            }
        }
        return List.copyOf(aus);
    }

    /** Eine Form, oder null, wenn das Objekt keine ist oder eine Grenze verletzt. */
    private static Form form(JsonObject o) {
        String typ = o.has("type") ? o.get("type").getAsString() : "";
        if (!typ.equals("region") && !typ.equals("circle") && !typ.equals("line")) {
            return null;
        }
        String dimension = o.has("dimension") ? text(o, "dimension", MAX_KENNUNG) : UEBERWELT;
        if (dimension == null) {
            return null;
        }
        int fuellung = o.has("fill") ? farbeMitAlpha(o.get("fill").getAsString(), 0) : 0;
        // Ohne Farbe ist der Rand bei Region und Kreis die Füllung ohne Alpha.
        int vorgabe = fuellung != 0 && !typ.equals("line") ? 0xFF000000 | fuellung : RANDFARBE;
        Rand rand = rand(o, vorgabe);
        switch (typ) {
            case "circle" -> {
                JsonArray c = o.getAsJsonArray("center");
                double x = koordinate(c.get(0)), z = koordinate(c.get(1)), r = o.get("radius").getAsDouble();
                return r > 0 && r <= MAX_RADIUS ? new Kreis(dimension, x, z, r, fuellung, rand) : null;
            }
            case "line" -> {
                double[] p = ring(o.getAsJsonArray("points"), 2);
                return p == null || p.length / 2 > MAX_PUNKTE ? null : new Linie(dimension, p, rand, box(List.of(p)));
            }
            default -> {
                List<double[]> ringe = new ArrayList<>();
                int punkte = 0;
                for (JsonElement e : o.getAsJsonArray("polygons")) {
                    JsonObject poly = e.getAsJsonObject();
                    JsonArray loecher = poly.has("holes") ? poly.getAsJsonArray("holes") : new JsonArray();
                    if (loecher.size() > MAX_LOECHER) {
                        return null;
                    }
                    double[] aussen = ring(poly.getAsJsonArray("outer"), 3);
                    if (aussen == null) {
                        return null;
                    }
                    ringe.add(aussen);
                    for (JsonElement l : loecher) {
                        double[] loch = ring(l.getAsJsonArray(), 3);
                        if (loch == null) {
                            return null;
                        }
                        ringe.add(loch);
                    }
                }
                for (double[] r : ringe) {
                    punkte += r.length / 2;
                }
                if (ringe.isEmpty() || punkte > MAX_PUNKTE) {
                    return null;
                }
                int[] rechtecke = fuellung == 0 ? null : Raster.rechtecke(ringe, MAX_REIHEN, MAX_RECHTECKE);
                return new Flaeche(dimension, rechtecke == null ? 0 : fuellung, rechtecke, rand, List.copyOf(ringe), box(ringe));
            }
        }
    }

    /** Die Punkte [[x, z], …] als {x0, z0, …}; null mit weniger als {@code mindestens} oder mehr als {@link #MAX_PUNKTE}. */
    private static double[] ring(JsonArray punkte, int mindestens) {
        if (punkte.size() < mindestens || punkte.size() > MAX_PUNKTE) {
            return null;
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
                    strich = Math.min(a, MAX_STRICH);
                    luecke = Math.min(b, MAX_STRICH);
                }
            }
        }
        return new Rand(farbe, Math.min(breite, MAX_BREITE), strich, luecke);
    }

    /** {@code #RRGGBB} deckend oder {@code #RRGGBBAA} mit Alpha als ARGB; sonst {@code sonst}. */
    static int farbeMitAlpha(String text, int sonst) {
        if (!text.matches("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?")) {
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
    List<Nadel> nadeln(String id) {
        return nadeln.getOrDefault(id, List.of());
    }

    /**
     * Um wie viele Grössen eine Nadel kleiner wird, wenn ein Block {@code p} Einheiten breit ist; ab 3
     * fällt jede weg. Siehe docs/ebenen.md, „Nadeln“.
     */
    static int stufen(double p) {
        return p >= 1 / 2.0 ? 0 : p >= 1 / 8.0 ? 1 : p >= 1 / 32.0 ? 2 : 3;
    }

    /**
     * Zeichnet die Nadel mit dem Fuss bei (x, y), in Einheiten des GUI auf ganzen Pixeln, um
     * {@code stufen} Grössen kleiner: Feld, Symbol der gezeichneten Grösse, Rahmen; den Namen nur in
     * ihrer Grundgrösse. Siehe docs/ebenen.md, „Nadeln“.
     */
    static void zeichne(GuiGraphicsExtractor g, Font font, float x, float y, Nadel n, int stufen) {
        int groesse = n.groesse() + stufen;
        if (groesse >= SCHILDE.length) {
            return;
        }
        Schild s = SCHILDE[groesse];
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        // Das Feld steht in Graustufen; die Grafikkarte multipliziert es mit der Farbe.
        g.blitSprite(RenderPipelines.GUI_TEXTURED, s.feld(), -s.breite() / 2, -s.hoehe(), s.breite(), s.hoehe(), n.farbe());
        int seite = groesse == 0 ? 16 : 9;
        Identifier symbol = groesse == 2 ? null : Symbole.INSTANZ.symbol(n.ebene(), n.version(), groesse == 0 ? n.symbolGross() : n.symbolMittel(), seite);
        if (symbol != null) {
            // Die linke obere Ecke bei (⌊(Breite − Seite) / 2⌋, 3) im Bild des Schilds.
            g.blit(RenderPipelines.GUI_TEXTURED, symbol, -s.breite() / 2 + (s.breite() - seite) / 2, -s.hoehe() + 3, 0, 0, seite, seite, seite, seite);
        }
        g.blitSprite(RenderPipelines.GUI_TEXTURED, s.rahmen(), -s.breite() / 2, -s.hoehe(), s.breite(), s.hoehe());
        if (stufen == 0 && n.name() != null) {
            g.centeredText(font, n.name(), 0, 2, TEXT);
        }
        pose.popMatrix();
    }
}
