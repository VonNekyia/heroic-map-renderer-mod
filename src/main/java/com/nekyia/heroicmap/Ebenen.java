package com.nekyia.heroicmap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Die Ebenen vom Server: die Liste aus {@code ebenen}, die Objekte jeder Ebene aus den Teilen von
 * {@code ebene}. Vorerst nur Nadeln. Alles auf dem Render-Thread. Siehe docs/ebenen.md.
 */
final class Ebenen {

    static final Ebenen INSTANZ = new Ebenen();
    /** Grenzen aus dem Format, siehe docs/ebenen.md, „Grenzen“. */
    static final int MAX_EBENEN = 64, MAX_NADELN = 1000, MAX_TEILE = 10_000;
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

    /** Die Teile einer {@code version}, die noch nicht alle da sind. */
    private record Sammlung(String version, JsonArray[] teile) {
    }

    private List<Eintrag> liste = List.of();
    private final Map<String, List<Nadel>> nadeln = new HashMap<>();
    private final Map<String, Sammlung> sammlungen = new HashMap<>();

    /** Eine Nachricht {@code ebenen} oder {@code ebene}; eine kaputte ändert nichts. */
    void empfange(JsonObject json) {
        try {
            if ("ebenen".equals(json.get("typ").getAsString())) {
                liste(json);
            } else {
                teil(json);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Heroic Map: Ebenen nicht lesbar: {}", e.toString());
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
            neu.add(new Eintrag(o.get("id").getAsString(), text(name, "de"), text(name, "en"),
                    !o.has("visible") || o.get("visible").getAsBoolean(), o.has("order") ? o.get("order").getAsInt() : 0,
                    o.get("version").getAsString()));
        }
        // Oben liegt, was später gezeichnet wird: aufsteigend nach order, bei Gleichstand nach id absteigend.
        neu.sort(Comparator.comparingInt(Eintrag::order).thenComparing(Eintrag::id, Comparator.reverseOrder()));
        liste = List.copyOf(neu);
        nadeln.keySet().retainAll(liste.stream().map(Eintrag::id).toList());
        // Halbe Teile gelten nur, solange die Liste ihre version nennt.
        sammlungen.entrySet().removeIf(s -> liste.stream().noneMatch(e -> e.id().equals(s.getKey()) && e.version().equals(s.getValue().version())));
    }

    /**
     * Ein Teil {@code ebene}. Er gilt nur mit der {@code version}, die die Liste nennt; das Plugin
     * schickt die Liste vor den Teilen. Sind alle Teile da, ersetzen ihre Nadeln die der Ebene, bis
     * dahin bleibt die alte.
     */
    void teil(JsonObject json) {
        String id = json.get("id").getAsString(), version = json.get("version").getAsString();
        int teil = json.get("teil").getAsInt(), teile = json.get("teile").getAsInt();
        if (teile < 1 || teile > MAX_TEILE || teil < 1 || teil > teile
                || liste.stream().noneMatch(e -> e.id().equals(id) && e.version().equals(version))) {
            return;
        }
        Sammlung s = sammlungen.get(id);
        if (s == null || s.teile().length != teile) {
            s = new Sammlung(version, new JsonArray[teile]);
            sammlungen.put(id, s);
        }
        s.teile()[teil - 1] = json.getAsJsonArray("objects");
        for (JsonArray a : s.teile()) {
            if (a == null) {
                return;
            }
        }
        sammlungen.remove(id);
        nadeln.put(id, nadeln(s.teile()));
    }

    /** Die Nadeln aus den Objekten aller Teile, in ihrer Reihenfolge; anderes und Kaputtes fällt weg. */
    static List<Nadel> nadeln(JsonArray[] teile) {
        List<Nadel> aus = new ArrayList<>();
        for (JsonArray teil : teile) {
            for (JsonElement e : teil) {
                if (aus.size() == MAX_NADELN) {
                    return List.copyOf(aus);
                }
                try {
                    JsonObject o = e.getAsJsonObject();
                    if ("pin".equals(o.has("type") ? o.get("type").getAsString() : null)) {
                        aus.add(nadel(o));
                    }
                } catch (RuntimeException fehler) {
                    // Ein kaputtes Objekt fehlt, die übrigen gelten.
                }
            }
        }
        return List.copyOf(aus);
    }

    private static Nadel nadel(JsonObject o) {
        JsonArray at = o.getAsJsonArray("at");
        int groesse = switch (o.has("size") ? o.get("size").getAsString() : "medium") {
            case "large" -> 0;
            case "small" -> 2;
            default -> 1;
        };
        return new Nadel(at.get(0).getAsDouble(), at.get(1).getAsDouble(),
                o.has("dimension") ? o.get("dimension").getAsString() : UEBERWELT, o.has("name") ? o.get("name").getAsString() : null,
                groesse, o.has("color") ? farbe(o.get("color").getAsString()) : FARBE);
    }

    /** {@code #RRGGBB} oder {@code #RRGGBBAA} als deckendes ARGB; das Alpha wirkt am Schild nicht. */
    static int farbe(String text) {
        if (!text.matches("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?")) {
            return FARBE;
        }
        return 0xFF000000 | Integer.parseInt(text.substring(1, 7), 16);
    }

    private static String text(JsonObject o, String feld) {
        return o.has(feld) ? o.get(feld).getAsString() : null;
    }

    /** Beim Trennen und bei einem neuen Login: Der Server schickt danach alles neu. */
    void leeren() {
        liste = List.of();
        nadeln.clear();
        sammlungen.clear();
    }

    /** Die Ebenen, die gezeichnet werden, unten zuerst. */
    List<Eintrag> sichtbar() {
        return liste.stream().filter(Eintrag::sichtbar).toList();
    }

    /** Die Nadeln einer Ebene, die schon ganz da ist, sonst keine. */
    List<Nadel> nadeln(String id) {
        return nadeln.getOrDefault(id, List.of());
    }

    /**
     * Um wie viele Grössen eine Nadel kleiner wird, wenn ein Block {@code p} Einheiten breit ist; ab 3
     * fällt jede weg. Siehe docs/ebenen.md, „Grösse“.
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
