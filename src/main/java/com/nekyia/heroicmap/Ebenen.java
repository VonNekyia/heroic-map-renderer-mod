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
 * {@code ebene}, dazu die Wahl des Spielers, welche an ist. Vorerst nur Nadeln. Alles auf dem
 * Render-Thread. Siehe docs/ebenen.md.
 */
final class Ebenen {

    static final Ebenen INSTANZ = new Ebenen();
    /** Grenzen aus dem Format, siehe docs/ebenen.md, „Grenzen“. */
    static final int MAX_EBENEN = 64, MAX_NADELN = 1000;
    /** Eine eigene Grenze des Mods: Für eine Datei von 4 MiB braucht ein ehrliches Plugin rund 130 Teile. */
    static final int MAX_TEILE = 256;
    /** So lang ist höchstens ein Name, eine Kennung oder eine Dimension; länger übergeht der Mod. */
    static final int MAX_TEXT = 64, MAX_KENNUNG = 129;
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

    /** Eine Nadel mit dem Fuss bei (x, z); {@code groesse} 0 ist {@code large}, 1 {@code medium}, 2 {@code small}. */
    record Nadel(double x, double z, String dimension, String name, int groesse, int farbe) {
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
    record Teil(String id, String version, int teil, int teile, List<Nadel> nadeln) {

        /** Liest eine Nachricht auf dem Thread des Netzes; null, wenn sie keine {@code ebene} ist oder nicht taugt. */
        static Teil lies(String text) {
            try {
                JsonObject json = JsonParser.parseString(text).getAsJsonObject();
                if (json.get("v").getAsInt() != 1 || !"ebene".equals(json.get("typ").getAsString())) {
                    return null;
                }
                String id = text(json, "id", MAX_KENNUNG), version = text(json, "version", MAX_KENNUNG);
                return id == null || version == null ? null
                        : new Teil(id, version, json.get("teil").getAsInt(), json.get("teile").getAsInt(), Ebenen.nadeln(json.getAsJsonArray("objects")));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** Die Teile einer {@code version}, die noch nicht alle da sind, und wie viele Nadeln sie schon haben. */
    private static final class Sammlung {

        final String version;
        final List<List<Nadel>> teile;
        int da, nadeln;

        Sammlung(String version, int teile) {
            this.version = version;
            this.teile = new ArrayList<>(Collections.nCopies(teile, null));
        }
    }

    private List<Eintrag> liste = List.of();
    private final Map<String, List<Nadel>> nadeln = new HashMap<>();
    private final Map<String, Sammlung> sammlungen = new HashMap<>();
    /** Die Wahl des Spielers je Kennung und wo sie liegt; null heisst nur im Speicher. */
    private final Map<String, Boolean> wahl = new HashMap<>();
    private Path datei;

    /** Die Liste {@code ebenen}; eine kaputte ändert nichts. */
    void empfange(JsonObject json) {
        try {
            liste(json);
        } catch (RuntimeException e) {
            LOGGER.warn("Heroic Map: Liste der Ebenen nicht lesbar: {}", e.toString());
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
        // Halbe Teile gelten nur, solange die Liste ihre version nennt.
        sammlungen.entrySet().removeIf(s -> liste.stream().noneMatch(e -> e.id().equals(s.getKey()) && e.version().equals(s.getValue().version)));
    }

    /**
     * Ein Teil {@code ebene}. Er gilt nur mit der {@code version}, die die Liste nennt; das Plugin
     * schickt die Liste vor den Teilen. Sind alle Teile da, ersetzen ihre Nadeln die der Ebene, bis
     * dahin bleibt die alte. Kommt eine Sammlung über {@link #MAX_NADELN}, ist sie verworfen.
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
        s.da += vorher == null ? 1 : 0;
        s.nadeln += t.nadeln().size() - (vorher == null ? 0 : vorher.size());
        if (s.nadeln > MAX_NADELN) {
            LOGGER.warn("Heroic Map: Ebene {} hat mehr als {} Nadeln, sie bleibt, wie sie war", t.id(), MAX_NADELN);
            sammlungen.remove(t.id());
        } else if (s.da == s.teile.size()) {
            sammlungen.remove(t.id());
            nadeln.put(t.id(), s.teile.stream().flatMap(List::stream).toList());
        }
    }

    /** Die Nadeln aus den Objekten eines Teils, in ihrer Reihenfolge, höchstens {@link #MAX_NADELN} + 1; anderes und Kaputtes fällt weg. */
    static List<Nadel> nadeln(JsonArray objekte) {
        List<Nadel> aus = new ArrayList<>();
        for (JsonElement e : objekte) {
            if (aus.size() > MAX_NADELN) {
                break;
            }
            try {
                JsonObject o = e.getAsJsonObject();
                if ("pin".equals(o.has("type") ? o.get("type").getAsString() : null)) {
                    Nadel n = nadel(o);
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

    /** Eine Nadel; null mit einer Dimension über {@link #MAX_KENNUNG} Zeichen. Ein Name über {@link #MAX_TEXT} fehlt. */
    private static Nadel nadel(JsonObject o) {
        JsonArray at = o.getAsJsonArray("at");
        int groesse = switch (o.has("size") ? o.get("size").getAsString() : "medium") {
            case "large" -> 0;
            case "small" -> 2;
            default -> 1;
        };
        String dimension = o.has("dimension") ? text(o, "dimension", MAX_KENNUNG) : UEBERWELT;
        return dimension == null ? null : new Nadel(at.get(0).getAsDouble(), at.get(1).getAsDouble(), dimension,
                text(o, "name", MAX_TEXT), groesse, o.has("color") ? farbe(o.get("color").getAsString()) : FARBE);
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
        sammlungen.clear();
    }

    /** Die Ebenen, die gezeichnet werden, unten zuerst. */
    List<Eintrag> sichtbar() {
        return liste.stream().filter(this::an).toList();
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
     * {@code stufen} Grössen kleiner; den Namen nur in ihrer Grundgrösse. Siehe docs/ebenen.md, „Nadeln“.
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
        g.blitSprite(RenderPipelines.GUI_TEXTURED, s.rahmen(), -s.breite() / 2, -s.hoehe(), s.breite(), s.hoehe());
        if (stufen == 0 && n.name() != null) {
            g.centeredText(font, n.name(), 0, 2, TEXT);
        }
        pose.popMatrix();
    }
}
