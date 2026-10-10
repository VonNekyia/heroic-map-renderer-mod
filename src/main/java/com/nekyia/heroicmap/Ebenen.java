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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import org.joml.Matrix3x2fStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Die Ebenen vom Server: die Liste aus {@code ebenen}, die Objekte jeder Ebene aus den Teilen von
 * {@code ebene}, dazu die Wahl des Spielers, welche an ist: Nadeln, Flächen, Kreise und Linien. Ein Teil
 * liest schon der Thread des Netzes ({@link Teil#lies}), alles andere läuft auf dem Render-Thread.
 * Siehe docs/ebenen.md.
 */
public final class Ebenen {

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
    /**
     * Der Name unter dem Fuss wie die Kartenschrift, in ihren Vorgaben {@link #SCHRIFTFARBE} mit Kontur
     * {@link #KONTURFARBE}: 10 Einheiten je Geviert wie 16 px auf der Webkarte, im Verhältnis 200 zu 320;
     * die Kontur 1,25 Einheiten wie 2 px dort, gekappt wie bei der Kartenschrift; die Oberkante der
     * Grossbuchstaben 2 unter dem Fuss.
     */
    static final float NAME_GROESSE = 10, NAME_OBEN = 2;
    /** Massstab von der Schrift (16 je Geviert) aufs GUI und die Breite der Kontur in Einheiten des GUI. */
    private static final double NAME_MASSSTAB = NAME_GROESSE / 16.0, NAME_KONTUR = Formen.kontur(1.25, Formen.KAPPE * NAME_MASSSTAB);
    /**
     * Der Name eines Banners im Bogen, in Einheiten des GUI: Sperrung 0,125 · s, der tiefste Punkt 0,75 · s unter dem
     * Fuss, höchstens 120° offen; ein Zeichen reicht über und unter seiner Mitte je 0,55 · s, die Grossbuchstaben samt
     * Akzenten und Unterlängen. Siehe docs/ebenen.md, „Banner“.
     */
    static final double BOGEN_SPERRUNG = 0.125 * NAME_GROESSE, BOGEN_TIEF = 0.75 * NAME_GROESSE, BOGEN_OEFFNUNG = 2 * Math.PI / 3,
            BOGEN_HALB = 0.55 * NAME_GROESSE;
    /** So weit reicht der gerade Name unter den Fuss: Oberkante, ein Geviert der Schrift, die Kontur. */
    static final int NAME_UNTEN = (int) Math.ceil(NAME_OBEN + NAME_GROESSE + NAME_KONTUR);
    /** So weit reicht kein Name unter den Fuss, auch keiner im Bogen; zum Vorfiltern vor dem Kasten. */
    static final int UNTEN_HOECHSTENS = 32;
    /**
     * So viele Namen zeichnet eine Ansicht je Frame, je neun Texte; ein Name im Bogen zählt je Zeichen einen, denn er
     * zeichnet je Zeichen neun. Die übrigen fehlen, das Log sagt es einmal.
     */
    static final int MAX_NAMEN = 500;
    /**
     * So hoch steht ein Banner höchstens, in Einheiten des GUI, und halb so breit, wie bisher 32 × 64:
     * gezeichnet mit so vielen ganzen Pixeln des Schirms je Pixel des Bilds, wie hineinpassen, mindestens
     * einem. Siehe docs/ebenen.md, „Banner“.
     */
    static final int BANNER_HOEHE = 32;
    private static boolean namenGewarnt;
    private static final Logger LOGGER = LoggerFactory.getLogger(HeroicMap.ID);
    /** Die Zeichen der Namen im Bogen, gemessen mit der Schrift von {@link #zeichenGeneration}; nur der Render-Thread. */
    private static final Map<String, Zeichen> ZEICHEN = new HashMap<>();
    private static int zeichenGeneration = -1;

    /** Die Zeichen eines Namens einzeln in der Kartenschrift und ihre Vorschübe in Einheiten des GUI. */
    record Zeichen(Component[] zeichen, double[] breiten) {
    }

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

        /** Die Kennung in der Ebene, für die Tafel; null ohne. */
        String id();

        /** Die {@code version} der Daten, aus denen der Ort kommt. */
        String version();
    }

    /**
     * Eine Nadel mit dem Fuss bei (x, z); {@code groesse} 0 ist {@code large}, 1 {@code medium}, 2
     * {@code small}. Dazu ihre Ebene und deren {@code version} und die Felder ihrer Symbole oder null.
     */
    record Nadel(double x, double z, String dimension, String name, int groesse, int farbe, String ebene, String version,
            String symbolGross, String symbolMittel, String id) implements Ort {
    }

    /**
     * Ein Banner, Pixel auf Pixel: mit {@code design} das Sprite des Entwurfs im Satz {@code oben}, mit {@code krone} das
     * aus {@code krone/}, der Fuss aus {@code satz.json}; sonst und solange das Sprite fehlt das Bild {@code bild} der
     * Ebene, der Fuss unten mittig. Darunter der Name. Siehe docs/ebenen.md, „Banner“.
     */
    record Banner(double x, double z, String dimension, String name, String bild, String ebene, String version, String id,
            String design, boolean krone) implements Ort {
    }

    /** Ein Rand in Einheiten des GUI: Farbe mit Alpha, Breite, gestrichelt Strich und Lücke, sonst beide 0. */
    record Rand(int farbe, float breite, float strich, float luecke) {
    }

    /** Eine Fläche, ein Kreis oder eine Linie einer Ebene, flach gezeichnet. */
    sealed interface Form permits Flaeche, Kreis, Linie, Schrift {

        String dimension();
    }

    /** Die {@code id} einer Fläche oder eines Kreises, oder null; nur sie haben eine Tafel und lassen sich anheften. */
    static String id(Form f) {
        return switch (f) {
            case Flaeche fl -> fl.id();
            case Kreis k -> k.id();
            default -> null;
        };
    }

    /**
     * Eine Region: die Füllung mit Alpha als Trapeze aus {@link Trapeze}, null ohne Füllung oder wenn sie
     * zu aufwendig ist; der Rand (null ohne) um alle Ringe {x0, z0, …}; {@code box} {x0, z0, x1, z1}.
     */
    record Flaeche(String dimension, int fuellung, double[] trapeze, Rand rand, List<double[]> ringe, double[] box, String id) implements Form {
    }

    /** Ein Kreis um (x, z) mit {@code radius} Blöcken, Füllung mit Alpha (Alpha 0 ohne) und Rand (null ohne). */
    record Kreis(String dimension, double x, double z, double radius, int fuellung, Rand rand, String id) implements Form {
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
    /** Die Liste vom Server und die Ebenen der Client-Mods mit ihrem Teil; {@link #liste} mischt beide. Siehe docs/api.md. */
    private List<Eintrag> vomServer = List.of();
    private final Map<String, Eintrag> vonMods = new LinkedHashMap<>();
    private final Map<String, Teil> teileVonMods = new HashMap<>();
    /** Zählt die Ebenen der Mods hoch, ihre version; „mod-“ davor, damit sie nie die einer Ebene vom Server ist. */
    private int modVersion;
    /** Ebenen der Mods, die nicht zu sehen sind, verdeckt vom Server oder über {@link #MAX_EBENEN}; jede steht einmal im Log. */
    private final Set<String> verdeckt = new HashSet<>();
    /** Je Ebene die {@code version} der Daten, die gezeichnet werden; die Liste kann schon eine neuere nennen. */
    private final Map<String, String> versionen = new HashMap<>();
    /** Zählt jede Änderung an Liste, Daten oder Wahl; die Vollbildkarte sucht ihr Ziel nur danach neu. */
    private int stand;
    private final Map<String, List<Ort>> nadeln = new HashMap<>();
    private final Map<String, List<Form>> formen = new HashMap<>();
    /** Die Punkte der fertigen Formen je Ebene. */
    private final Map<String, Integer> punkte = new HashMap<>();
    /** Je Ebene die version einer verworfenen Sammlung; ihre übrigen Teile übergeht der Mod. */
    private final Map<String, String> verworfen = new HashMap<>();
    private final Map<String, Sammlung> sammlungen = new HashMap<>();
    /** Die Kennungen der Ebenen mit {@code "secret": true} in der Liste; ihre Banner kommen über den Kanal. */
    private Set<String> geheim = Set.of();
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
        Set<String> geheimNeu = new HashSet<>();
        for (JsonElement e : json.getAsJsonArray("ebenen")) {
            if (neu.size() == MAX_EBENEN) {
                LOGGER.warn("Heroic Map: mehr als {} Ebenen, die übrigen fehlen", MAX_EBENEN);
                break;
            }
            JsonObject o = e.getAsJsonObject();
            String id = text(o, "id", MAX_KENNUNG), version = text(o, "version", MAX_KENNUNG);
            if (id == null || version == null) {
                throw new IllegalArgumentException("id oder version");
            }
            if (neu.stream().anyMatch(x -> x.id().equals(id))) {
                // Eine Kennung zweimal: Es gilt der erste Eintrag.
                continue;
            }
            neu.add(eintrag(o, id, version));
            if (o.has("secret") && o.get("secret").getAsBoolean()) {
                geheimNeu.add(id);
            }
        }
        geheim = Set.copyOf(geheimNeu);
        vomServer = List.copyOf(neu);
        mische();
    }

    private static Eintrag eintrag(JsonObject o, String id, String version) {
        JsonObject name = o.has("name") ? o.getAsJsonObject("name") : new JsonObject();
        return new Eintrag(id, text(name, "de", MAX_TEXT), text(name, "en", MAX_TEXT),
                !o.has("visible") || o.get("visible").getAsBoolean(), o.has("order") ? o.get("order").getAsInt() : 0, version);
    }

    /**
     * Mischt die Liste vom Server mit den Ebenen der Mods; bei gleicher Kennung gilt die vom Server. Was fehlt, ist weg,
     * samt seinen Nadeln und halben Teilen; eine Ebene eines Mods, die wieder zu sehen ist, bekommt ihren Teil wieder.
     */
    private void mische() {
        Set<String> server = new HashSet<>(vomServer.stream().map(Eintrag::id).toList());
        List<Eintrag> neu = new ArrayList<>(vomServer);
        for (Eintrag e : vonMods.values()) {
            if (!server.contains(e.id()) && neu.size() >= MAX_EBENEN) {
                // Dieselbe Grenze wie für den Server, über alle Ebenen; die zuletzt angelegten fehlen.
                if (verdeckt.add(e.id())) {
                    LOGGER.warn("Heroic Map: mehr als {} Ebenen, die Ebene {} eines Mods fehlt", MAX_EBENEN, e.id());
                }
            } else if (!server.contains(e.id())) {
                neu.add(e);
            } else if (verdeckt.add(e.id())) {
                LOGGER.warn("Heroic Map: Der Server hat eine Ebene {}, sie verdeckt die gleichnamige eines Mods", e.id());
            }
        }
        // Wieder zu sehen oder weg: Ein neues Verdecken meldet das Log wieder.
        verdeckt.removeIf(id -> !vonMods.containsKey(id) || neu.contains(vonMods.get(id)));
        // Oben liegt, was später gezeichnet wird: aufsteigend nach order, bei Gleichstand nach id absteigend.
        neu.sort(Comparator.comparingInt(Eintrag::order).thenComparing(Eintrag::id, Comparator.reverseOrder()));
        liste = List.copyOf(neu);
        stand++;
        versionen.keySet().retainAll(liste.stream().map(Eintrag::id).toList());
        nadeln.keySet().retainAll(liste.stream().map(Eintrag::id).toList());
        formen.keySet().retainAll(liste.stream().map(Eintrag::id).toList());
        punkte.keySet().retainAll(liste.stream().map(Eintrag::id).toList());
        verworfen.entrySet().removeIf(v -> liste.stream().noneMatch(e -> e.id().equals(v.getKey()) && e.version().equals(v.getValue())));
        // Halbe Teile gelten nur, solange die Liste ihre version nennt.
        sammlungen.entrySet().removeIf(s -> liste.stream().noneMatch(e -> e.id().equals(s.getKey()) && e.version().equals(s.getValue().version)));
        // Daten eines Mods unter einer Kennung, die jetzt der Server hat, gehen; bis zu seinem Teil steht dort nichts.
        for (Eintrag e : vomServer) {
            Teil vomMod = teileVonMods.get(e.id());
            if (vomMod != null && vomMod.version().equals(versionen.get(e.id()))) {
                versionen.remove(e.id());
                nadeln.remove(e.id());
                formen.remove(e.id());
                punkte.remove(e.id());
            }
        }
        for (Eintrag e : liste) {
            Teil t = teileVonMods.get(e.id());
            if (t != null && t.version().equals(e.version()) && !t.version().equals(versionen.get(e.id()))) {
                teil(t);
            }
        }
    }

    /**
     * Legt die Ebene eines Client-Mods an oder ersetzt sie: {@code eintrag} wie ein Eintrag der Liste,
     * {@code objekte} wie {@code objects} eines Teils. Gelesen auf dem Thread des Aufrufs, übernommen auf dem
     * Render-Thread. False, wenn nicht lesbar oder die Kennung nicht taugt. Nur für {@code HeroicMapClientApi}.
     */
    public static boolean vonMod(String eintrag, String objekte) {
        Eintrag e;
        Teil t;
        try {
            JsonObject o = JsonParser.parseString(eintrag).getAsJsonObject();
            String id = text(o, "id", MAX_KENNUNG);
            if (!kennungEinesMods(id)) {
                return false;
            }
            String version;
            synchronized (INSTANZ) {
                version = "mod-" + ++INSTANZ.modVersion;
            }
            // Bilder von Bannern und Symbolen kommen nur vom Server; eine Ebene eines Mods fragte ihn sonst vergeblich (v1).
            JsonArray ohneBilder = new JsonArray();
            for (JsonElement x : JsonParser.parseString(objekte).getAsJsonArray()) {
                if (x.isJsonObject() && "banner".equals(text(x.getAsJsonObject(), "type", MAX_TEXT))) {
                    continue;
                }
                if (x.isJsonObject()) {
                    x = x.deepCopy();
                    x.getAsJsonObject().remove("symbol");
                }
                ohneBilder.add(x);
            }
            JsonObject teil = new JsonObject();
            teil.addProperty("v", 1);
            teil.addProperty("typ", "ebene");
            teil.addProperty("id", id);
            teil.addProperty("version", version);
            teil.addProperty("teil", 1);
            teil.addProperty("teile", 1);
            teil.add("objects", ohneBilder);
            t = Teil.lies(teil.toString());
            e = eintrag(o, id, version);
        } catch (RuntimeException kaputt) {
            return false;
        }
        if (t == null) {
            return false;
        }
        Minecraft.getInstance().execute(() -> {
            INSTANZ.vonMods.put(e.id(), e);
            INSTANZ.teileVonMods.put(e.id(), t);
            INSTANZ.mische();
        });
        return true;
    }

    /** Nimmt die Ebene eines Client-Mods weg; eine vom Server mit derselben Kennung bleibt. Nur für {@code HeroicMapClientApi}. */
    public static void ohneMod(String id) {
        Minecraft.getInstance().execute(() -> {
            if (INSTANZ.vonMods.remove(id) != null) {
                INSTANZ.teileVonMods.remove(id);
                INSTANZ.mische();
            }
        });
    }

    /** Taugt {@code id} für die Ebene eines Mods? Mit Namensraum wie ein Identifier, nicht {@code heroicmap:}. Siehe docs/api.md, „Kennungen“. */
    static boolean kennungEinesMods(String id) {
        return id != null && id.indexOf(':') > 0 && !id.startsWith(HeroicMap.ID + ":") && Identifier.tryParse(id) != null;
    }

    /**
     * Ein Teil {@code ebene}. Er gilt nur mit der {@code version}, die die Liste nennt; das Plugin
     * schickt die Liste vor den Teilen. Sind alle Teile da, ersetzen ihre Nadeln die der Ebene, bis
     * dahin bleibt die alte. Kommt eine Sammlung über {@link #MAX_NADELN} Nadeln, {@link #MAX_OBJEKTE}
     * Objekte oder {@link #MAX_PUNKTE_EBENE} Punkte, ist sie verworfen. Verworfene Formen und Banner meldet
     * das Log einmal je Ebene und {@code version}, wenn sie fertig ist. True, wenn die Ebene damit ganz da ist.
     */
    boolean teil(Teil t) {
        if (t.teile() < 1 || t.teile() > MAX_TEILE || t.teil() < 1 || t.teil() > t.teile()
                || liste.stream().noneMatch(e -> e.id().equals(t.id()) && e.version().equals(t.version()))
                || t.version().equals(verworfen.get(t.id()))) {
            return false;
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
            versionen.put(t.id(), t.version());
            stand++;
            nadeln.put(t.id(), s.teile.stream().flatMap(x -> x.nadeln().stream()).toList());
            formen.put(t.id(), s.teile.stream().flatMap(x -> x.formen().stream()).toList());
            punkte.put(t.id(), s.punkte);
            return true;
        }
        return false;
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
                feld(symbol, "large"), feld(symbol, "medium"), text(o, "id", MAX_TEXT));
    }

    /**
     * Ein Banner; {@code design} nur als Teil einer Kennung, {@code capital} nur mit ihm. Null ohne gültiges Bild unter
     * {@code images/}, wenn es keinen Entwurf nennt, und mit einer zu langen Dimension; ein Bild über {@link #MAX_FELD}
     * oder ausserhalb von {@code images/} zählt nicht.
     */
    private static Banner banner(JsonObject o, String ebene, String version) {
        JsonArray at = o.getAsJsonArray("at");
        String dimension = o.has("dimension") ? text(o, "dimension", MAX_KENNUNG) : UEBERWELT;
        String bild = feld(o, "image");
        bild = bild != null && Symbole.FELD.matcher(bild).matches() ? bild : null;
        String design = o.has("design") ? o.get("design").getAsString() : null;
        design = Symbole.teil(design) ? design : null;
        boolean krone = design != null && o.has("capital") && o.get("capital").getAsBoolean();
        return dimension == null || bild == null && design == null ? null
                : new Banner(at.get(0).getAsDouble(), at.get(1).getAsDouble(), dimension, text(o, "name", MAX_TEXT), bild, ebene, version,
                        text(o, "id", MAX_TEXT), design, krone);
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
                return new Kreis(dimension, x, z, r, fuellung, rand, text(o, "id", MAX_TEXT));
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
                return new Flaeche(dimension, fuellung, trapeze, rand, List.copyOf(ringe), box(ringe), text(o, "id", MAX_TEXT));
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
    static String text(JsonObject o, String feld, int hoechstens) {
        if (!o.has(feld)) {
            return null;
        }
        String t = o.get(feld).getAsString();
        return t.length() > hoechstens ? null : ChatFormatting.stripFormatting(t);
    }

    /** Beim Trennen und bei einem neuen Login: Der Server schickt danach alles neu. */
    void leeren() {
        vomServer = List.of();
        geheim = Set.of();
        versionen.clear();
        nadeln.clear();
        formen.clear();
        punkte.clear();
        verworfen.clear();
        sammlungen.clear();
        // Die Ebenen der Mods hängen am Client, nicht am Server; sie bleiben.
        mische();
    }

    /** Die Ebenen, die gezeichnet werden, unten zuerst. */
    List<Eintrag> sichtbar() {
        return liste.stream().filter(this::an).toList();
    }

    /** Ist die Ebene geheim, mit {@code "secret": true} in der Liste? Ihre Banner holt der Mod über den Kanal, siehe docs/ebenen.md, „Geheime Banner“. */
    boolean geheim(String id) {
        return geheim.contains(id);
    }

    /**
     * Das Sprite eines Banners mit Entwurf: in einer geheimen Ebene über den Kanal, sonst per HTTP; null ohne Entwurf
     * oder solange es fehlt.
     */
    static Symbole.Sprite sprite(Banner b) {
        if (b.design() == null) {
            return null;
        }
        return INSTANZ.geheim(b.ebene()) ? Geheimbanner.INSTANZ.sprite(b.ebene(), b.version(), b.design(), b.krone())
                : Symbole.INSTANZ.sprite(b.ebene(), b.version(), b.design(), b.krone());
    }

    /** Das Bild eines Banners; eine geheime Ebene hat keine Bilder auf dem Server, dort null. */
    static Symbole.Textur bild(Banner b) {
        return INSTANZ.geheim(b.ebene()) ? null : Symbole.INSTANZ.banner(b.ebene(), b.version(), b.bild());
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
        stand++;
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

    /** Die {@code version} der Daten einer Ebene, die gezeichnet werden; null, solange keine ganz da ist. */
    String version(String id) {
        return versionen.get(id);
    }

    int stand() {
        return stand;
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
     * Grösse, darunter den Namen; ein Banner bei GUI-Massstab {@code gs} mit der Deckung {@code deckung},
     * der Name bleibt deckend. Siehe docs/ebenen.md, „Nadeln“,
     * und docs/ebenen.md, „Banner“.
     */
    static void zeichne(GuiGraphicsExtractor g, Font font, float x, float y, Ort o, int[] namen, int gs, float deckung) {
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        double hoehe = 0;
        switch (o) {
            case Nadel n -> nadel(g, n);
            case Banner b -> {
                // Mit Entwurf das Sprite um seinen Fuss; solange es fehlt, das Bild, wie das Format sagt.
                Symbole.Sprite sp = sprite(b);
                Symbole.Textur t = sp != null ? sp.textur() : bild(b);
                if (t == null) {
                    pose.popMatrix();
                    return;
                }
                int f = faktor(t.breite(), t.hoehe(), gs);
                bild(g, t, f, sp == null ? t.breite() * f / 2 : sp.fussX() * f, sp == null ? t.hoehe() * f : sp.fussY() * f, gs, deckung);
                hoehe = t.hoehe() * f / (double) gs;
            }
        }
        // Eine Nadel trägt ihren Namen gerade, ein Banner im Bogen, so wählte es der User (0102 des Renderers).
        Zeichen z = o instanceof Banner && o.name() != null ? zeichen(font, o.name()) : null;
        int kosten = z == null ? 1 : Math.max(1, z.zeichen().length);
        if (o.name() != null && namen[0] >= kosten) {
            namen[0] -= kosten;
            if (z == null) {
                name(g, font, o.name());
            } else {
                bogenName(g, font, z, hoehe);
            }
        } else if (o.name() != null && !namenGewarnt) {
            namenGewarnt = true;
            LOGGER.warn("Heroic Map: mehr als {} Namen auf einmal; die übrigen fehlen", MAX_NAMEN);
        }
        pose.popMatrix();
    }

    /**
     * Der Kasten von Bild und Name relativ zum Fuss, {links, oben, rechts, unten} in Einheiten des GUI,
     * so wie {@link #zeichne} ihn bei GUI-Massstab {@code gs} füllt; null bei einem Banner, dessen Bild
     * noch fehlt. Misst den Namen und holt das Bild eines Banners, also erst die Lage prüfen.
     */
    static float[] kasten(Font font, Ort o, int gs) {
        return switch (o) {
            case Nadel n -> kasten(SCHILDE[n.groesse()].breite(), SCHILDE[n.groesse()].hoehe(), o.name() == null ? 0 : nameBreite(font, o.name()));
            case Banner b -> {
                Symbole.Sprite sp = sprite(b);
                if (sp != null) {
                    yield spriteKasten(sp.textur().breite(), sp.textur().hoehe(), sp.fussX(), sp.fussY(), gs, breiten(font, o));
                }
                Symbole.Textur t = bild(b);
                yield t == null ? null : bannerKasten(t.breite(), t.hoehe(), gs, breiten(font, o));
            }
        };
    }

    /**
     * Das Bild eines Banners mit dem Fuss im Ursprung, in Pixeln des Schirms, ganze je Pixel des Bilds
     * ({@link #faktor}), höchstens {@code hoechstens} Einheiten hoch; der Fuss ⌊Breite / 2⌋ rechts der
     * linken Kante, wie bei der Nadel.
     */
    static void banner(GuiGraphicsExtractor g, Symbole.Textur t, int gs, int hoechstens, float deckung) {
        int f = faktor(t.breite(), t.hoehe(), gs, hoechstens);
        bild(g, t, f, t.breite() * f / 2, t.hoehe() * f, gs, deckung);
    }

    /**
     * Das Bild oder Sprite {@code t} mit {@code f} Pixeln des Schirms je Pixel, seine linke obere Ecke {@code links} und
     * {@code oben} Pixel des Schirms links über dem Ursprung, also dem Fuss.
     */
    private static void bild(GuiGraphicsExtractor g, Symbole.Textur t, int f, int links, int oben, int gs, float deckung) {
        int w = t.breite() * f, h = t.hoehe() * f;
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.scale(1f / gs);
        g.blit(RenderPipelines.GUI_TEXTURED, t.id(), -links, -oben, 0, 0, w, h, t.breite(), t.hoehe(), t.breite(), t.hoehe(), ARGB.white(deckung));
        pose.popMatrix();
    }

    /** {@link #faktor(int, int, int, int)} mit {@link #BANNER_HOEHE}. */
    static int faktor(int breite, int hoehe, int gs) {
        return faktor(breite, hoehe, gs, BANNER_HOEHE);
    }

    /**
     * Wie viele Pixel des Schirms ein Pixel eines Banners von {@code breite} × {@code hoehe} bei
     * GUI-Massstab {@code gs} bekommt: so viele ganze, wie in {@code hoechstens} / 2 × {@code hoechstens}
     * Einheiten passen, mindestens einer. Kleiner wird es nie, sonst wären die Pixel nicht mehr sauber.
     */
    static int faktor(int breite, int hoehe, int gs, int hoechstens) {
        return Math.max(1, Math.min(hoechstens / 2 * gs / breite, hoechstens * gs / hoehe));
    }

    /**
     * Der Kasten eines Banners wie gezeichnet, in Einheiten des GUI: links ⌊Breite / 2⌋ Pixel des Schirms; dazu der
     * Name im Bogen mit den Vorschüben {@code name}, leer heisst ohne Namen.
     */
    static float[] bannerKasten(int breite, int hoehe, int gs, double[] name) {
        int f = faktor(breite, hoehe, gs), w = breite * f;
        float[] bild = kasten(-(w / 2) / (float) gs, w / (float) gs, hoehe * f / (float) gs, 0);
        return name.length == 0 ? bild : vereint(bild, bogenKasten(name, radius(name, hoehe * f / (double) gs)));
    }

    /**
     * Der Kasten eines Sprites wie gezeichnet, in Einheiten des GUI: seine linke obere Ecke {@code fussX}, {@code fussY}
     * Pixel des Sprites links über dem Ort; dazu der Name im Bogen mit den Vorschüben {@code name}, leer heisst ohne.
     */
    static float[] spriteKasten(int breite, int hoehe, int fussX, int fussY, int gs, double[] name) {
        int f = faktor(breite, hoehe, gs);
        float[] bild = {-fussX * f / (float) gs, -fussY * f / (float) gs, (breite - fussX) * f / (float) gs, (hoehe - fussY) * f / (float) gs};
        return name.length == 0 ? bild : vereint(bild, bogenKasten(name, radius(name, hoehe * f / (double) gs)));
    }

    /**
     * Wie {@link #kasten(Font, Ort, int)}, ohne das Bild eines Banners zu holen: mit der grössten Grösse eines
     * Bilds, ein Pixel je Einheit. Zum Wegschneiden und als Vorprüfung beim Treffer; der genaue Kasten liegt
     * bei jedem GUI-Massstab darin.
     */
    static float[] kastenOhneHolen(Font font, Ort o) {
        return switch (o) {
            case Nadel n -> kasten(SCHILDE[n.groesse()].breite(), SCHILDE[n.groesse()].hoehe(), o.name() == null ? 0 : nameBreite(font, o.name()));
            case Banner b -> grob(breiten(font, o), b.design() != null);
        };
    }

    /**
     * Der grobe Kasten eines Banners mit dem Namen im Bogen aus den Vorschüben {@code name}; siehe {@link #kastenOhneHolen}.
     * Für jeden Radius: Die Mitte eines Zeichens liegt auf dem Bogen höchstens L / 2 vom tiefsten Punkt, also waagrecht
     * höchstens so weit, und höchstens so hoch über ihm wie das Ende des engsten Bogens, der 120° öffnet, r / 2. Jede
     * Ecke liegt höchstens die halbe Diagonale ihres Zeichens von seiner Mitte.
     */
    static float[] grob(double[] name) {
        return grob(name, false);
    }

    /**
     * Wie {@link #grob(double[])}; mit {@code sprite} für ein Banner mit Entwurf: Sein Fuss liegt irgendwo auf einer
     * Leinwand bis 32 × 64 Einheiten, wie gezeichnet bei jedem GUI-Massstab, also reicht das Sprite so weit zu jeder Seite.
     */
    static float[] grob(double[] name, boolean sprite) {
        float[] bild = sprite ? new float[] {-Symbole.BANNER_BREITE, -Symbole.BANNER_HOEHE, Symbole.BANNER_BREITE, Symbole.BANNER_HOEHE}
                : kasten(Symbole.BANNER_BREITE, Symbole.BANNER_HOEHE, 0);
        if (name.length == 0) {
            return bild;
        }
        double laenge = laenge(name), rho = 0;
        for (double b : name) {
            rho = Math.max(rho, Math.hypot(b / 2 + NAME_KONTUR, BOGEN_HALB + NAME_KONTUR));
        }
        double steigt = laenge / BOGEN_OEFFNUNG / 2;
        return vereint(bild, new float[] {(float) Math.floor(-laenge / 2 - rho), (float) Math.floor(BOGEN_TIEF - steigt - rho),
            (float) Math.ceil(laenge / 2 + rho), (float) Math.ceil(BOGEN_TIEF + rho)});
    }

    private static float[] vereint(float[] a, float[] b) {
        return new float[] {Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3])};
    }

    /** Die Vorschübe der Zeichen im Namen eines Orts, leer ohne Namen. */
    private static double[] breiten(Font font, Ort o) {
        return o.name() == null ? new double[0] : zeichen(font, o.name()).breiten();
    }

    /** Die Zeichen eines Namens, einmal gemessen je Name und Schrift; nach einem Wechsel der Schrift neu (Formen.generation). */
    static Zeichen zeichen(Font font, String name) {
        if (zeichenGeneration != Formen.generation || ZEICHEN.size() > 4 * MAX_NAMEN) {
            ZEICHEN.clear();
            zeichenGeneration = Formen.generation;
        }
        return ZEICHEN.computeIfAbsent(name, n -> {
            int[] codes = n.codePoints().toArray();
            Component[] zeichen = new Component[codes.length];
            double[] breiten = new double[codes.length];
            for (int i = 0; i < codes.length; i++) {
                zeichen[i] = Component.literal(Character.toString(codes[i])).withStyle(Formen.STIL);
                breiten[i] = font.getSplitter().stringWidth(zeichen[i]) * NAME_MASSSTAB;
            }
            return new Zeichen(zeichen, breiten);
        });
    }

    /** Die Länge eines Namens auf dem Bogen: die Vorschübe aller Zeichen und die Sperrung dazwischen. */
    static double laenge(double[] breiten) {
        double l = Math.max(0, breiten.length - 1) * BOGEN_SPERRUNG;
        for (double b : breiten) {
            l += b;
        }
        return l;
    }

    /** Der Radius des Bogens unter einem Banner {@code h} Einheiten hoch: 2 · h, für lange Namen mehr, so öffnet er höchstens 120°. */
    static double radius(double[] breiten, double h) {
        return Math.max(2 * h, laenge(breiten) / BOGEN_OEFFNUNG);
    }

    /**
     * Je Zeichen {x, y, winkel} auf dem Bogen mit Radius {@code r} unter dem Fuss im Ursprung, y nach unten: nach unten
     * gewölbt, der Mittelpunkt (0, 0,75 · s − r), die Mitte des Namens auf dem tiefsten Punkt, jedes Zeichen aufrecht zum
     * Bogen. {@code breiten} sind die Vorschübe in Einheiten des GUI.
     */
    static double[] bogen(double[] breiten, double r) {
        double[] aus = new double[3 * breiten.length];
        double s = -laenge(breiten) / 2, mitte = BOGEN_TIEF - r;
        for (int i = 0; i < breiten.length; i++) {
            double phi = (s + breiten[i] / 2) / r;
            aus[3 * i] = r * Math.sin(phi);
            aus[3 * i + 1] = mitte + r * Math.cos(phi);
            aus[3 * i + 2] = -phi;
            s += breiten[i] + BOGEN_SPERRUNG;
        }
        return aus;
    }

    /** Der Kasten des Namens im Bogen mit Radius {@code r}, {links, oben, rechts, unten}: je Zeichen sein Rechteck samt Kontur, gedreht. */
    static float[] bogenKasten(double[] breiten, double r) {
        double[] lage = bogen(breiten, r);
        double[] k = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        double hy = BOGEN_HALB + NAME_KONTUR;
        for (int i = 0; i < breiten.length; i++) {
            double hx = breiten[i] / 2 + NAME_KONTUR, c = Math.cos(lage[3 * i + 2]), sn = Math.sin(lage[3 * i + 2]);
            for (int ecke = 0; ecke < 4; ecke++) {
                double u = (ecke & 1) == 0 ? -hx : hx, v = (ecke & 2) == 0 ? -hy : hy;
                double x = lage[3 * i] + u * c - v * sn, y = lage[3 * i + 1] + u * sn + v * c;
                k[0] = Math.min(k[0], x);
                k[1] = Math.min(k[1], y);
                k[2] = Math.max(k[2], x);
                k[3] = Math.max(k[3], y);
            }
        }
        return new float[] {(float) Math.floor(k[0]), (float) Math.floor(k[1]), (float) Math.ceil(k[2]), (float) Math.ceil(k[3])};
    }

    /**
     * Der Kasten eines Bilds von {@code breite} × {@code hoehe} über dem Fuss, links ⌊Breite / 2⌋ wie
     * gezeichnet, und eines Namens mit dem Kasten {@code name} breit darunter; 0 heisst ohne Namen.
     */
    static float[] kasten(int breite, int hoehe, float name) {
        return kasten(-(breite / 2), breite, hoehe, name);
    }

    private static float[] kasten(float links, float breite, float hoehe, float name) {
        float rechts = links + breite;
        if (name <= 0) {
            return new float[] {links, -hoehe, rechts, 0};
        }
        float halb = (float) Math.ceil(name / 2);
        return new float[] {Math.min(links, -halb), -hoehe, Math.max(rechts, halb), NAME_UNTEN};
    }

    /** Wie breit der Name samt Kontur ist, in Einheiten des GUI. */
    static float nameBreite(Font font, String name) {
        return (float) (font.width(Component.literal(name).withStyle(Formen.STIL)) * NAME_MASSSTAB + 2 * NAME_KONTUR);
    }

    /**
     * Der Name mittig unter dem Fuss, wie die Kartenschrift: erst die Kontur als acht versetzte Kopien,
     * dann die Schrift. Siehe docs/ebenen.md, „Nadeln“.
     */
    private static void name(GuiGraphicsExtractor g, Font font, String name) {
        Component c = Component.literal(name).withStyle(Formen.STIL);
        float m = (float) NAME_MASSSTAB, breite = font.width(c) * m;
        double r = NAME_KONTUR / m;
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        // Die Oberkante der Grossbuchstaben liegt KAPPE − GRUNDLINIE Einheiten der Schrift über dem y des Texts.
        pose.translate(-breite / 2, NAME_OBEN + (Formen.KAPPE - Formen.GRUNDLINIE) * m);
        pose.scale(m);
        for (int k = 0; k < 8; k++) {
            pose.pushMatrix();
            pose.translate((float) Formen.versatzX(k, r), (float) Formen.versatzY(k, r));
            g.text(font, c, 0, 0, KONTURFARBE, false);
            pose.popMatrix();
        }
        g.text(font, c, 0, 0, SCHRIFTFARBE, false);
        pose.popMatrix();
    }

    /**
     * Der Name eines Banners im Bogen unter einem Banner {@code h} Einheiten hoch, Zeichen für Zeichen gedreht wie die
     * Kartenschrift: erst die Kontur aller Zeichen als acht versetzte Kopien, dann die Zeichen, so deckt keine Kontur ein
     * Zeichen. Siehe docs/ebenen.md, „Banner“.
     */
    private static void bogenName(GuiGraphicsExtractor g, Font font, Zeichen z, double h) {
        double[] lage = bogen(z.breiten(), radius(z.breiten(), h));
        float m = (float) NAME_MASSSTAB;
        double r = NAME_KONTUR / m;
        Matrix3x2fStack pose = g.pose();
        for (int k = 0; k <= 8; k++) {
            // k 0 bis 7: die Kopien der Kontur rundum; 8: die Zeichen.
            float dx = k < 8 ? (float) Formen.versatzX(k, r) : 0, dy = k < 8 ? (float) Formen.versatzY(k, r) : 0;
            for (int i = 0; i < z.zeichen().length; i++) {
                pose.pushMatrix();
                pose.translate((float) lage[3 * i], (float) lage[3 * i + 1]);
                pose.rotate((float) lage[3 * i + 2]);
                pose.scale(m);
                // Die Mitte des Zeichens auf dem Punkt, die Mitte der Grossbuchstaben auf dem Bogen.
                pose.translate((float) (dx - z.breiten()[i] / m / 2), dy + Formen.KAPPE / 2 - Formen.GRUNDLINIE);
                g.text(font, z.zeichen()[i], 0, 0, k < 8 ? KONTURFARBE : SCHRIFTFARBE, false);
                pose.popMatrix();
            }
        }
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
