package com.nekyia.heroicmap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.slf4j.Logger;

/**
 * Die Wegpunkte, eigenen Formen und alten Rechtecke des Spielers und was er auf der Minimap angeheftet hat,
 * Wegpunkte, Mitspieler, eigene Formen und Rechtecke und Flächen, Kreise, Nadeln und Banner vom Server. Je Welt in
 * {@code wegpunkte.json} im Ordner der Welt, auch im Einzelspieler; ohne Ordner nur im Speicher. Nur der
 * Render-Thread liest und ändert sie. Siehe docs/wegpunkte.md.
 */
final class Wegpunkte {

    static final Wegpunkte INSTANZ = new Wegpunkte();
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Die Farben der Wegpunkte, der Reihe nach. */
    static final int[] FARBEN = {0xFFE04040, 0xFF4090F0, 0xFF40C040, 0xFFF0C020, 0xFFC050F0, 0xFFF08020, 0xFF40D0D0, 0xFFF070B0};

    /**
     * Ein Wegpunkt auf dem Block (x, z), mit einer festen {@code id}; {@code farbe} ist ein Index in {@link #FARBEN}. Mit
     * {@code tod} ein Todespunkt, ohne eigene Farbe; ihre Folge in der Liste ist die der Tode, der letzte der neueste. Siehe
     * docs/wegpunkte.md, „Todespunkt“. {@code bund} über 0 verbindet ihn mit den Ecken desselben Bunds, 0 heisst lose; siehe
     * docs/wegpunkte.md, „Verbinden“. {@code zeit} ist bei einem Todespunkt die Zeit des Todes in ms der Uhr, 0 unbekannt; siehe
     * docs/wegpunkte.md, „Todespunkt“. {@code name} wie aus {@link #name}, null ohne, mit {@code schrift} und {@code minecraft}
     * wie an einer eigenen Form; siehe docs/wegpunkte.md, „Namen an Wegpunkten“.
     */
    record Punkt(int id, String dimension, int x, int z, int farbe, boolean angeheftet, boolean tod, int ebene, int bund, long zeit,
            String name, double schrift, boolean minecraft) {

        Punkt(int id, String dimension, int x, int z, int farbe, boolean angeheftet, boolean tod, int ebene) {
            this(id, dimension, x, z, farbe, angeheftet, tod, ebene, 0, 0);
        }

        /** Ohne Namen, mit Schrift 1 in der Kartenschrift, wie vor 0.2.69. */
        Punkt(int id, String dimension, int x, int z, int farbe, boolean angeheftet, boolean tod, int ebene, int bund, long zeit) {
            this(id, dimension, x, z, farbe, angeheftet, tod, ebene, bund, zeit, null, 1, false);
        }

        /** Derselbe Wegpunkt mit Name, Schrift und Schriftart. */
        Punkt stil(String name, double schrift, boolean minecraft) {
            return new Punkt(id, dimension, x, z, farbe, angeheftet, tod, ebene, bund, zeit, name, schrift, minecraft);
        }
    }

    /**
     * Eine Ecke einer eigenen Form auf dem Block (x, z). {@code bund} über 0 verbindet sie mit allen Punkten desselben Bunds, 0
     * heisst lose. Siehe docs/wegpunkte.md, „Verbinden“.
     */
    record Ecke(int x, int z, int bund) {

        Ecke(int x, int z) {
            this(x, z, 0);
        }

        /** Dieselbe Ecke auf dem Block (x, z), mit ihrem Bund. */
        Ecke auf(int x, int z) {
            return new Ecke(x, z, bund);
        }
    }

    /**
     * Ein Punkt, der sich verbinden lässt: ein Wegpunkt, oder mit {@code punkt} null die Ecke {@code index} der eigenen Form
     * {@code form}. Siehe docs/wegpunkte.md, „Verbinden“.
     */
    record Glied(Punkt punkt, EigeneForm form, int index) {

        static Glied von(Punkt p) {
            return new Glied(p, null, -1);
        }

        static Glied von(EigeneForm f, int index) {
            return new Glied(null, f, index);
        }

        String dimension() {
            return punkt != null ? punkt.dimension() : form.dimension();
        }

        int x() {
            return punkt != null ? punkt.x() : form.ecken().get(index).x();
        }

        int z() {
            return punkt != null ? punkt.z() : form.ecken().get(index).z();
        }

        int bund() {
            return punkt != null ? punkt.bund() : form.ecken().get(index).bund();
        }
    }

    /**
     * Eine eigene Form, ein Marker mit eigenen Ecken in Reihenfolge: zwei sind eine Linie, drei und mehr eine
     * Region. Sie verweist auf keinen Wegpunkt. {@code name} wie aus {@link #name}, null ohne; {@code hex} eine eigene Farbe
     * als RGB, {@link #KEIN_HEX} ohne, dann gilt {@code farbe}; {@code ebene} die id ihrer eigenen Ebene. Siehe docs/wegpunkte.md,
     * „Eigene Formen“.
     */
    record EigeneForm(String dimension, List<Ecke> ecken, int farbe, boolean angeheftet, String name, int hex, int ebene, double schrift,
            int biegung, int gewicht, boolean minecraft) {

        /** Eine Linie biegt ihren Namen nicht; er folgt ihr schon. Siehe docs/wegpunkte.md, „Namen“. */
        EigeneForm {
            ecken = List.copyOf(ecken);
            biegung = ecken.size() >= 3 ? biegung : 0;
        }

        /** Mit Schrift 1, ohne Biegung und mit Gewicht 0, wie vor mod#150. */
        EigeneForm(String dimension, List<Ecke> ecken, int farbe, boolean angeheftet, String name, int hex, int ebene) {
            this(dimension, ecken, farbe, angeheftet, name, hex, ebene, 1, 0, 0, false);
        }

        /** Mit Gewicht 0, wie vor mod#163. */
        EigeneForm(String dimension, List<Ecke> ecken, int farbe, boolean angeheftet, String name, int hex, int ebene, double schrift,
                int biegung) {
            this(dimension, ecken, farbe, angeheftet, name, hex, ebene, schrift, biegung, 0, false);
        }

        /** Die Farbe der Form, deckend: die eigene, sonst die aus {@link #FARBEN}. Siehe docs/wegpunkte.md, „Farbe“. */
        int argb() {
            return hex == KEIN_HEX ? FARBEN[farbe] : 0xFF000000 | hex;
        }

        boolean region() {
            return ecken.size() >= 3;
        }

        /** Dieselbe Form mit anderen Ecken, alle übrigen Felder gleich; so trägt sich jedes neue Feld selbst mit (Review von #168). */
        EigeneForm mitEcken(List<Ecke> ecken) {
            return new EigeneForm(dimension, ecken, farbe, angeheftet, name, hex, ebene, schrift, biegung, gewicht, minecraft);
        }
    }

    /**
     * Ein altes Rechteck aus „Region von hier“, die Blöcke von (x0, z0) bis (x1, z1) samt beiden, x0 ≤ x1 und
     * z0 ≤ z1; {@code farbe} ist ein Index in {@link #FARBEN}. Siehe docs/wegpunkte.md, „Regionen“.
     */
    record Region(String dimension, int x0, int z0, int x1, int z1, int farbe, boolean angeheftet, int ebene) {

        boolean enthaelt(String d, int x, int z) {
            return x >= x0 && x <= x1 && z >= z0 && z <= z1 && dimension.equals(d);
        }
    }

    /**
     * Eine eigene Ebene des Spielers; aus heisst nirgends gezeichnet. {@link #BASE} heisst fest „Base“ und lässt sich weder
     * umbenennen noch löschen. Siehe docs/wegpunkte.md, „Eigene Ebenen“.
     */
    record EigeneEbene(int id, String name, boolean an) {
    }

    /** Ein angeheftetes Objekt vom Server, über die Kennungen von Ebene und Objekt; hält so über neue {@code version}s. */
    record Anheftung(String ebene, String id) {
    }

    /** So viele alte Rechtecke je Welt liest der Mod. */
    static final int MAX_REGIONEN = 256;
    /** So viele eigene Formen je Welt, und so viele Ecken je Form. */
    static final int MAX_EIGENE_FORMEN = 256, MAX_PUNKTE_FORM = 64;
    /** So weit reicht eine Koordinate, die der Spieler eintippt, in Blöcken: der Rand der Welt. */
    static final int MAX_KOORDINATE = 30_000_000;
    /** Die id von Base, der Ebene, in die alles Neue kommt. */
    static final int BASE = 0;
    /** So viele eigene Ebenen je Welt neben Base. */
    static final int MAX_EBENEN = 32;
    /** So viele Zeichen hat der Name einer eigenen Form höchstens. */
    static final int MAX_NAME = 32;
    /** Eine eigene Form ohne eigene Farbe; dann gilt ihr Index in {@link #FARBEN}. */
    static final int KEIN_HEX = -1;
    /**
     * Der Name einer eigenen Form als Kartenschrift: die Grossbuchstaben je nach Form so hoch in Blöcken, höchstens wie die
     * Vorgabe vom Server; die Kontur in Einheiten des GUI wie beim Namen einer Nadel. Siehe docs/wegpunkte.md, „Namen“.
     */
    static final float NAME_KLEINSTE = 4, NAME_GROESSE = 16, NAME_KONTUR = 1.25f;
    /** Die Rasten des Reglers „Schrift“: ein Faktor auf die Höhe des Namens, Vorgabe 1. Siehe docs/wegpunkte.md, „Namen“. */
    static final double[] SCHRIFTEN = {0.25, 1.0 / 3, 0.5, 2.0 / 3, 1, 1.5, 2, 3, 4};
    /** Die Biegung eines Namens in Grad, ±{@link #MAX_BIEGUNG} in Schritten zu {@link #BIEGUNG_SCHRITT}; + nach oben gewölbt. */
    static final int MAX_BIEGUNG = 180, BIEGUNG_SCHRITT = 15;
    /** Das Gewicht einer Form, ganzzahlig von −{@link #MAX_GEWICHT} bis {@link #MAX_GEWICHT}, Vorgabe 0; schwerer liegt oben. Siehe docs/wegpunkte.md, „Gewicht“. */
    static final int MAX_GEWICHT = 9;
    /** Der Wert von {@code schriftart} in Datei und Code für die Minecraft-Schrift; ohne das Feld gilt die Kartenschrift. */
    static final String SCHRIFTART_MINECRAFT = "minecraft";
    /** So viele Regionen und Kreise je Welt angeheftet, eigene und vom Server zusammen; darüber heftet der Mod keine an. */
    static final int MAX_ANGEHEFTET = 64;
    /** So viele Nadeln und Banner je Welt angeheftet, eine eigene Grenze; darüber heftet der Mod keine an. */
    static final int MAX_NADELN_ANGEHEFTET = 64;
    /** So viele Einheiten breiter ist auf der Vollbildkarte der Rand angehefteter Regionen und Kreise. */
    static final float BREITER = 2;

    private final List<Punkt> punkte = new ArrayList<>();
    private final List<Region> regionen = new ArrayList<>();
    /** Die Sicht von aussen, einmal angelegt: Der Strahl liest sie je Frame ohne Allokation. */
    private final List<Punkt> punkteSicht = Collections.unmodifiableList(punkte);
    private final List<Region> regionenSicht = Collections.unmodifiableList(regionen);
    private final List<EigeneForm> eigene = new ArrayList<>();
    /** Die eigenen Ebenen, Base zuerst, und ein Zähler für jede Änderung an ihnen. */
    private final List<EigeneEbene> ebenen = new ArrayList<>(List.of(base(true)));
    private int ebenenStand;
    private final List<EigeneEbene> ebenenSicht = Collections.unmodifiableList(ebenen);
    /** Die nächste freie id eines Wegpunkts. */
    private int naechsteId = 1;
    /** Die Mitspieler, die auf der Minimap angeheftet sind. */
    private final Set<UUID> spieler = new LinkedHashSet<>();
    /** Die angehefteten Flächen und Kreise vom Server. */
    private final Set<Anheftung> formen = new LinkedHashSet<>();
    /** Die angehefteten Nadeln und Banner vom Server. */
    private final Set<Anheftung> nadeln = new LinkedHashSet<>();
    /** Zählt jede Änderung; die Listen für Karte und Minimap baut der Mod danach neu. */
    private int stand;
    /** Die Listen für Karte und Minimap und woraus sie gebaut sind. */
    private Ebenen gebautAus;
    private int gebautEbenen, gebautStand;
    /** Die eigene Form, die die Vollbildkarte gerade zieht, oder null; und die, für die zuletzt gebaut wurde. */
    private List<EigeneForm> gezogen = List.of(), gebautGezogen = List.of();
    /** Der Stand der {@link Ebenenreihe}, nach dem zuletzt gebaut wurde. */
    private int gebautReihe = -1;
    private List<List<Ebenen.Form>> fuerKarte = List.of(), fuerMinimap = List.of();
    /** Je Liste in {@link #fuerKarte} die Zoomgrenze ihrer Ebene, 0 für eigene, und was davon angeheftet ist. */
    private double[] karteGrenzen = {};
    private List<List<Ebenen.Form>> karteAngeheftet = List.of();
    /** Das Gewicht je eigener Form auf der Minimap, nach Identität; für den Schleier. */
    private Map<Ebenen.Form, Integer> gewichte = new IdentityHashMap<>();
    private List<Ebenen.Ort> fuerNadeln = List.of();
    /** Dieselben Nadeln und Banner nach Identität, so prüft die Vollbildkarte je Nadel ohne Allokation. */
    private Set<Ebenen.Ort> angeheftetOrte = Set.of();
    /** Die eigenen Formen und alten Rechtecke für die Minimap nach Identität; der Schleier gibt ihnen die Höhe des Reglers. */
    private Set<Ebenen.Form> eigeneAufMinimap = Set.of();
    /** Die Datei, oder null ohne Ordner der Welt. */
    private Path datei;
    /** Ist gelesen, seit dem letzten Leeren? */
    private boolean geladen;

    /** Beim Wechsel der Welt: liest die Wegpunkte aus {@code ordner}, wenn es ein anderer ist als bisher. Siehe docs/wegpunkte.md, „Ablage“. */
    void wechsel(Path ordner) {
        Path neu = ordner == null ? null : ordner.resolve("wegpunkte.json");
        if (!geladen || !Objects.equals(neu, datei)) {
            lies(ordner);
        }
    }

    /** Liest die Wegpunkte des Servers in {@code ordner}; null heisst nur im Speicher. Ein unlesbarer Eintrag fällt weg. */
    void lies(Path ordner) {
        leeren();
        geladen = true;
        datei = ordner == null ? null : ordner.resolve("wegpunkte.json");
        if (datei == null || !Files.exists(datei)) {
            return;
        }
        try {
            lies(JsonParser.parseString(Files.readString(datei, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException | RuntimeException e) {
            // Sonst überschriebe die nächste Änderung die Datei; so bleibt ihr Inhalt.
            Path kaputt = datei.resolveSibling("wegpunkte.json.kaputt");
            LOGGER.warn("Heroic Map: Wegpunkte {} nicht lesbar, gesichert als {}", datei, kaputt, e);
            try {
                Files.move(datei, kaputt, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException f) {
                LOGGER.warn("Heroic Map: {} nicht gesichert; die Wegpunkte bleiben nur im Speicher", datei, f);
                datei = null;
            }
        }
    }

    /** Der Name eines Eintrags; einer, der kein Text ist, fehlt, der Eintrag bleibt. */
    private static String nameAus(JsonObject o) {
        return o.has("name") && o.get("name").isJsonPrimitive() ? name(o.get("name").getAsString()) : null;
    }

    /** Die Schrift eines Eintrags; ausserhalb der Grenzen oder ohne Zahl die Vorgabe 1, der Eintrag bleibt. */
    private static double schriftAus(JsonObject o) {
        return o.get("schrift") instanceof JsonPrimitive s && s.isNumber() && schriftGilt(s.getAsDouble()) ? schrift(s.getAsDouble()) : 1;
    }

    /** Die Schriftart eines Eintrags; eine andere als „minecraft“, auch eine spätere, heisst die Kartenschrift. */
    private static boolean minecraftAus(JsonObject o) {
        return o.get("schriftart") instanceof JsonPrimitive a && a.isString() && SCHRIFTART_MINECRAFT.equals(a.getAsString());
    }

    void lies(JsonObject json) {
        stand++;
        ebenenStand++;
        // Ohne „ebenen“ gibt es nur Base; so ziehen ältere Dateien ohne Schritt um. Siehe docs/wegpunkte.md, „Eigene Ebenen“.
        for (JsonElement element : liste(json, "ebenen")) {
            try {
                JsonObject o = element.getAsJsonObject();
                int id = o.get("id").getAsInt();
                boolean an = !o.has("an") || o.get("an").getAsBoolean();
                String name = o.has("name") && o.get("name").isJsonPrimitive() ? name(o.get("name").getAsString()) : null;
                if (id == BASE) {
                    ebenen.set(0, base(an));
                } else if (id > 0 && name != null && ebene(id) == null && ebenen.size() <= MAX_EBENEN) {
                    ebenen.add(new EigeneEbene(id, name, an));
                }
            } catch (RuntimeException kaputt) {
                // Nur diese Ebene fällt weg; was in ihr lag, kommt nach Base.
            }
        }
        for (JsonElement element : liste(json, "wegpunkte")) {
            try {
                JsonObject o = element.getAsJsonObject();
                // Eine Datei von vor mod#79 nennt keine id; dann vergibt der Mod sie unten.
                int id = o.has("id") ? o.get("id").getAsInt() : 0;
                Punkt p = new Punkt(id, o.get("dimension").getAsString(), o.get("x").getAsInt(), o.get("z").getAsInt(),
                        Math.floorMod(o.get("farbe").getAsInt(), FARBEN.length), o.get("minimap").getAsBoolean(), false, ebeneAus(o),
                        bund(o.get("bund")), 0).stil(nameAus(o), schriftAus(o), minecraftAus(o));
                if (finde(p.dimension(), p.x(), p.z()) < 0) {
                    punkte.add(p);
                }
            } catch (RuntimeException kaputt) {
                // Nur dieser Eintrag fällt weg.
            }
        }
        // Die Todespunkte stehen eigens, der älteste zuerst, so zählt eine ältere Version sie nicht zu den Wegpunkten. Ohne
        // tode gilt das alte tod mit einem; eine id bekommen sie unten.
        JsonArray tode = liste(json, "tode");
        if (tode.isEmpty() && json.has("tod") && json.get("tod").isJsonObject()) {
            tode.add(json.getAsJsonObject("tod"));
        }
        for (JsonElement e : tode) {
            try {
                JsonObject o = e.getAsJsonObject();
                // Ohne zeit, etwa aus tod oder von vor mod#166, ist sie unbekannt; dann steht keine Zeit unter ihm.
                Punkt p = new Punkt(0, o.get("dimension").getAsString(), o.get("x").getAsInt(), o.get("z").getAsInt(), 0,
                        o.get("minimap").getAsBoolean(), true, BASE, 0, zeit(o.get("zeit")));
                if (finde(p.dimension(), p.x(), p.z()) < 0) {
                    punkte.add(p);
                }
            } catch (RuntimeException kaputt) {
                // Nur dieser fällt weg.
            }
        }
        vergibIds();
        for (JsonElement element : liste(json, "regionen")) {
            try {
                JsonObject o = element.getAsJsonObject();
                Region r = region(o.get("dimension").getAsString(), o.get("x0").getAsInt(), o.get("z0").getAsInt(), o.get("x1").getAsInt(),
                        o.get("z1").getAsInt(), Math.floorMod(o.get("farbe").getAsInt(), FARBEN.length),
                        o.get("minimap").getAsBoolean() && angeheftet() < MAX_ANGEHEFTET, ebeneAus(o));
                if (regionen.size() < MAX_REGIONEN && finde(r) < 0) {
                    regionen.add(r);
                }
            } catch (RuntimeException kaputt) {
                // Nur dieser Eintrag fällt weg.
            }
        }
        for (JsonElement element : liste(json, "spieler")) {
            try {
                spieler.add(UUID.fromString(element.getAsString()));
            } catch (RuntimeException kaputt) {
                // Nur dieser Eintrag fällt weg.
            }
        }
        // Eine Datei von vor mod#36 hat keine Liste; dann ist nichts vom Server angeheftet.
        for (JsonElement element : liste(json, "formen")) {
            try {
                JsonObject o = element.getAsJsonObject();
                if (angeheftet() < MAX_ANGEHEFTET) {
                    formen.add(new Anheftung(o.get("ebene").getAsString(), o.get("id").getAsString()));
                }
            } catch (RuntimeException kaputt) {
                // Nur dieser Eintrag fällt weg.
            }
        }
        // Eine Datei von vor mod#79 hat keine eigenen Formen. Eine von vor mod#112 nennt statt Ecken die ids von
        // Wegpunkten; sie werden zu Ecken auf deren Blöcken, fehlende fallen weg.
        for (JsonElement element : liste(json, "eigene_formen")) {
            try {
                JsonObject o = element.getAsJsonObject();
                String dimension = o.has("dimension") ? o.get("dimension").getAsString() : null;
                List<Ecke> ecken = new ArrayList<>();
                if (o.has("ecken")) {
                    // Die dritte Zahl ist der Bund, nur an verbundenen Ecken; ältere Versionen lesen nur die ersten zwei.
                    for (JsonElement e : o.getAsJsonArray("ecken")) {
                        JsonArray xz = e.getAsJsonArray();
                        ecken.add(new Ecke(xz.get(0).getAsInt(), xz.get(1).getAsInt(), xz.size() > 2 ? bund(xz.get(2)) : 0));
                    }
                } else {
                    for (JsonElement id : o.getAsJsonArray("punkte")) {
                        Punkt q = punkt(id.getAsInt());
                        if (q != null && (dimension == null || dimension.equals(q.dimension()))) {
                            dimension = q.dimension();
                            ecken.add(new Ecke(q.x(), q.z()));
                        }
                    }
                }
                List<Ecke> gueltig = gueltig(ecken);
                if (dimension != null && gueltig != null && eigene.size() < MAX_EIGENE_FORMEN) {
                    String name = nameAus(o);
                    int hex = o.has("hex") && o.get("hex").isJsonPrimitive() ? hex(o.get("hex").getAsString()) : KEIN_HEX;
                    double schrift = schriftAus(o);
                    // Biegung ausserhalb der Grenzen oder ohne Zahl: die Vorgabe; die Form bleibt.
                    int biegung = o.get("biegung") instanceof JsonPrimitive b && b.isNumber() && Math.abs(b.getAsDouble()) <= MAX_BIEGUNG
                            ? biegung(b.getAsDouble()) : 0;
                    int gewicht = o.get("gewicht") instanceof JsonPrimitive g && g.isNumber() && gewichtGilt(g.getAsDouble()) ? g.getAsInt() : 0;
                    boolean minecraft = minecraftAus(o);
                    eigene.add(new EigeneForm(dimension, gueltig, Math.floorMod(o.get("farbe").getAsInt(), FARBEN.length),
                            o.get("minimap").getAsBoolean() && angeheftet() < MAX_ANGEHEFTET, name, hex, ebeneAus(o), schrift, biegung, gewicht,
                            minecraft));
                }
            } catch (RuntimeException kaputt) {
                // Nur dieser Eintrag fällt weg.
            }
        }
        // Eine Datei von vor mod#71 hat keine Liste; dann ist keine Nadel angeheftet.
        for (JsonElement element : liste(json, "nadeln")) {
            try {
                JsonObject o = element.getAsJsonObject();
                if (nadeln.size() < MAX_NADELN_ANGEHEFTET) {
                    nadeln.add(new Anheftung(o.get("ebene").getAsString(), o.get("id").getAsString()));
                }
            } catch (RuntimeException kaputt) {
                // Nur dieser Eintrag fällt weg.
            }
        }
        ordneBuende();
    }

    /**
     * Der Bund aus der Datei: eine ganze Zahl über 0; was fehlt oder keine ist, heisst lose, und der Punkt bleibt (Review von
     * #168). Siehe docs/wegpunkte.md, „Ablage“.
     */
    private static int bund(JsonElement e) {
        if (!(e instanceof JsonPrimitive p) || !p.isNumber()) {
            return 0;
        }
        double d = p.getAsDouble();
        return d == Math.rint(d) && d > 0 && d <= Integer.MAX_VALUE ? (int) d : 0;
    }

    /**
     * Die Zeit eines Todes aus der Datei, in ms: eine ganze Zahl über 0; was fehlt oder keine ist, heisst unbekannt, und der
     * Todespunkt bleibt, wie beim Bund. Siehe docs/wegpunkte.md, „Todespunkt“.
     */
    private static long zeit(JsonElement e) {
        if (!(e instanceof JsonPrimitive p) || !p.isNumber()) {
            return 0;
        }
        double d = p.getAsDouble();
        return d == Math.rint(d) && d > 0 && d < Long.MAX_VALUE ? (long) d : 0;
    }

    /** Die Ebene aus dem Feld {@code ebene} eines Eintrags, wenn es sie gibt; sonst Base. */
    private int ebeneAus(JsonObject o) {
        // Nur eine Zahl; was sonst dasteht, heisst Base, und der Eintrag bleibt.
        int id = o.has("ebene") && o.get("ebene").isJsonPrimitive() && o.get("ebene").getAsJsonPrimitive().isNumber() ? o.get("ebene").getAsInt() : BASE;
        return ebene(id) != null ? id : BASE;
    }

    private static EigeneEbene base(boolean an) {
        return new EigeneEbene(BASE, "Base", an);
    }

    private static JsonArray liste(JsonObject json, String name) {
        return json.has(name) && json.get(name).isJsonArray() ? json.getAsJsonArray(name) : new JsonArray();
    }

    JsonObject json() {
        JsonArray liste = new JsonArray(), tode = new JsonArray();
        JsonObject tod = null;
        for (Punkt p : punkte) {
            if (p.tod()) {
                tod = new JsonObject();
                tod.addProperty("dimension", p.dimension());
                tod.addProperty("x", p.x());
                tod.addProperty("z", p.z());
                tod.addProperty("minimap", p.angeheftet());
                // tod bleibt, wie es war; die Zeit steht nur im Eintrag von tode.
                JsonObject eintrag = tod.deepCopy();
                if (p.zeit() > 0) {
                    eintrag.addProperty("zeit", p.zeit());
                }
                tode.add(eintrag);
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("id", p.id());
            o.addProperty("dimension", p.dimension());
            o.addProperty("x", p.x());
            o.addProperty("z", p.z());
            o.addProperty("farbe", p.farbe());
            o.addProperty("minimap", p.angeheftet());
            o.addProperty("ebene", p.ebene());
            if (p.bund() != 0) {
                o.addProperty("bund", p.bund());
            }
            // Nur ohne Vorgabe, wie an Formen; ältere Versionen überlesen die Felder.
            if (p.name() != null) {
                o.addProperty("name", p.name());
            }
            if (p.schrift() != 1) {
                o.addProperty("schrift", p.schrift());
            }
            if (p.minecraft()) {
                o.addProperty("schriftart", SCHRIFTART_MINECRAFT);
            }
            liste.add(o);
        }
        JsonArray rechtecke = new JsonArray();
        for (Region r : regionen) {
            JsonObject o = new JsonObject();
            o.addProperty("dimension", r.dimension());
            o.addProperty("x0", r.x0());
            o.addProperty("z0", r.z0());
            o.addProperty("x1", r.x1());
            o.addProperty("z1", r.z1());
            o.addProperty("farbe", r.farbe());
            o.addProperty("minimap", r.angeheftet());
            o.addProperty("ebene", r.ebene());
            rechtecke.add(o);
        }
        JsonArray uuids = new JsonArray();
        spieler.forEach(u -> uuids.add(u.toString()));
        JsonObject json = new JsonObject();
        JsonArray schichten = new JsonArray();
        for (EigeneEbene e : ebenen) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id());
            o.addProperty("name", e.name());
            o.addProperty("an", e.an());
            schichten.add(o);
        }
        json.add("ebenen", schichten);
        json.add("wegpunkte", liste);
        // Dazu der neueste als tod, so behält eine ältere Version ihren einen.
        if (tod != null) {
            json.add("tode", tode);
            json.add("tod", tod);
        }
        json.add("regionen", rechtecke);
        json.add("spieler", uuids);
        json.add("formen", json(formen));
        json.add("nadeln", json(nadeln));
        JsonArray zuege = new JsonArray();
        for (EigeneForm f : eigene) {
            JsonObject o = new JsonObject();
            o.addProperty("dimension", f.dimension());
            JsonArray ecken = new JsonArray();
            for (Ecke e : f.ecken()) {
                JsonArray xz = new JsonArray();
                xz.add(e.x());
                xz.add(e.z());
                if (e.bund() != 0) {
                    xz.add(e.bund());
                }
                ecken.add(xz);
            }
            o.add("ecken", ecken);
            o.addProperty("farbe", f.farbe());
            o.addProperty("minimap", f.angeheftet());
            if (f.name() != null) {
                o.addProperty("name", f.name());
            }
            if (f.hex() != KEIN_HEX) {
                o.addProperty("hex", hex(f.hex()));
            }
            // Nur ohne Vorgabe; so bleiben alte Dateien gleich, und ältere Versionen überlesen die Felder.
            if (f.schrift() != 1) {
                o.addProperty("schrift", f.schrift());
            }
            if (f.biegung() != 0) {
                o.addProperty("biegung", f.biegung());
            }
            if (f.gewicht() != 0) {
                o.addProperty("gewicht", f.gewicht());
            }
            if (f.minecraft()) {
                o.addProperty("schriftart", SCHRIFTART_MINECRAFT);
            }
            o.addProperty("ebene", f.ebene());
            zuege.add(o);
        }
        json.add("eigene_formen", zuege);
        return json;
    }

    private static JsonArray json(Set<Anheftung> anheftungen) {
        JsonArray liste = new JsonArray();
        for (Anheftung a : anheftungen) {
            JsonObject o = new JsonObject();
            o.addProperty("ebene", a.ebene());
            o.addProperty("id", a.id());
            liste.add(o);
        }
        return liste;
    }

    void leeren() {
        punkte.clear();
        regionen.clear();
        eigene.clear();
        ebenen.clear();
        ebenen.add(base(true));
        ebenenStand++;
        naechsteId = 1;
        spieler.clear();
        formen.clear();
        nadeln.clear();
        stand++;
        datei = null;
        geladen = false;
    }

    List<Punkt> punkte() {
        return punkteSicht;
    }

    /** Setzt einen Wegpunkt auf den Block; steht dort schon einer, bleibt er. */
    void setze(String dimension, int x, int z) {
        if (finde(dimension, x, z) < 0) {
            punkte.add(new Punkt(naechsteId++, dimension, x, z, farbe(dimension), false, false, BASE));
            schreibe();
        }
    }

    /** Die eigenen Ebenen, Base zuerst. */
    List<EigeneEbene> ebenen() {
        return ebenenSicht;
    }

    /** Zählt jede Änderung an den eigenen Ebenen: anlegen, umbenennen, schalten, löschen, lesen. */
    int ebenenStand() {
        return ebenenStand;
    }

    /** Die eigene Ebene mit dieser id, oder null. */
    EigeneEbene ebene(int id) {
        for (EigeneEbene e : ebenen) {
            if (e.id() == id) {
                return e;
            }
        }
        return null;
    }

    /** Ist die eigene Ebene {@code id} an? Eine, die es nicht gibt, gilt als Base. */
    boolean an(int id) {
        EigeneEbene e = ebene(id);
        return e != null ? e.an() : ebenen.getFirst().an();
    }

    /** Wird der Wegpunkt gezeichnet? Nur, wenn seine Ebene an ist. */
    boolean sichtbar(Punkt p) {
        return an(p.ebene());
    }

    /**
     * Legt eine eigene Ebene an, bereinigt wie ein Name, siehe {@link #name}, an; ihre id, oder -1 ohne Namen oder bei schon
     * {@link #MAX_EBENEN}.
     */
    int neueEbene(String roh) {
        String name = name(roh);
        if (name == null || ebenen.size() > MAX_EBENEN) {
            return -1;
        }
        int id = ebenen.stream().mapToInt(EigeneEbene::id).max().orElse(BASE) + 1;
        ebenen.add(new EigeneEbene(id, name, true));
        ebenenStand++;
        schreibe();
        return id;
    }

    /** Benennt die eigene Ebene um; Base nicht, und ohne Namen nicht. False, wenn nichts geschah. */
    boolean benenneEbene(int id, String roh) {
        String name = name(roh);
        int i = ebenen.indexOf(ebene(id));
        if (id == BASE || i < 0 || name == null) {
            return false;
        }
        ebenen.set(i, new EigeneEbene(id, name, ebenen.get(i).an()));
        ebenenStand++;
        schreibe();
        return true;
    }

    /** Schaltet die eigene Ebene an oder aus. */
    void schalteEbene(int id, boolean an) {
        int i = ebenen.indexOf(ebene(id));
        if (i >= 0) {
            ebenen.set(i, new EigeneEbene(id, ebenen.get(i).name(), an));
            ebenenStand++;
            schreibe();
        }
    }

    /** Löscht die eigene Ebene; was in ihr lag, kommt nach Base. Base nicht. */
    void loescheEbene(int id) {
        EigeneEbene e = ebene(id);
        if (id == BASE || e == null) {
            return;
        }
        ebenen.remove(e);
        ebenenStand++;
        punkte.replaceAll(p -> p.ebene() == id ? mitEbene(p, BASE) : p);
        regionen.replaceAll(r -> r.ebene() == id ? mitEbene(r, BASE) : r);
        eigene.replaceAll(f -> f.ebene() == id ? mitEbene(f, BASE) : f);
        schreibe();
    }

    /** Was „Code einfügen“ tat: die neue Ebene und wie viele Wegpunkte und Formen hineinkamen, oder der Schlüssel der Meldung. */
    record Eingefuegt(int ebene, int punkte, int formen, String fehler) {
    }

    /**
     * Legt den Inhalt eines Codes als neue eigene Ebene an, an, nichts angeheftet; bei gleichem Namen „Name (2)“. Ein Wegpunkt
     * auf einem Block, auf dem schon einer liegt, kommt nicht dazu. Passt die Ebene oder passen die Formen nicht mehr, nichts.
     * Siehe docs/wegpunkte.md, „Ebenen teilen“.
     */
    Eingefuegt fuegeEin(EbenenCode.Inhalt inhalt) {
        if (ebenen.size() > MAX_EBENEN) {
            return new Eingefuegt(-1, 0, 0, "ebenen_voll");
        }
        if (eigene.size() + inhalt.formen().size() > MAX_EIGENE_FORMEN) {
            return new Eingefuegt(-1, 0, 0, "formen_voll");
        }
        int id = ebenen.stream().mapToInt(EigeneEbene::id).max().orElse(BASE) + 1;
        ebenen.add(new EigeneEbene(id, freierName(inhalt.name()), true));
        ebenenStand++;
        int n = 0;
        for (EbenenCode.Ort o : inhalt.orte()) {
            if (finde(o.dimension(), o.x(), o.z()) < 0) {
                punkte.add(new Punkt(naechsteId++, o.dimension(), o.x(), o.z(), o.farbe(), false, false, id).stil(o.name(), o.schrift(),
                        o.minecraft()));
                n++;
            }
        }
        for (EbenenCode.Form f : inhalt.formen()) {
            eigene.add(new EigeneForm(f.dimension(), f.ecken(), f.farbe(), false, f.name(), f.hex(), id, f.schrift(), f.biegung(), f.gewicht(),
                    f.minecraft()));
        }
        schreibe();
        return new Eingefuegt(id, n, inhalt.formen().size(), null);
    }

    /** {@code name}, oder ist er vergeben, „name (2)“, „name (3)“ …, vorn gekürzt auf höchstens {@link #MAX_NAME} Zeichen. */
    private String freierName(String name) {
        Set<String> vergeben = ebenen.stream().map(EigeneEbene::name).collect(Collectors.toSet());
        if (!vergeben.contains(name)) {
            return name;
        }
        for (int i = 2; ; i++) {
            String zusatz = " (" + i + ")", kopf = name;
            int platz = MAX_NAME - zusatz.length();
            if (kopf.codePointCount(0, kopf.length()) > platz) {
                kopf = kopf.substring(0, kopf.offsetByCodePoints(0, platz)).strip();
            }
            if (!vergeben.contains(kopf + zusatz)) {
                return kopf + zusatz;
            }
        }
    }

    /** Legt den Wegpunkt in die eigene Ebene {@code ebene}, wenn es sie gibt. */
    void ordne(Punkt p, int ebene) {
        int i = punkte.indexOf(p);
        if (i >= 0 && ebene(ebene) != null) {
            punkte.set(i, mitEbene(p, ebene));
            schreibe();
        }
    }

    /** Legt das alte Rechteck in die eigene Ebene {@code ebene}, wenn es sie gibt. */
    void ordne(Region r, int ebene) {
        int i = regionen.indexOf(r);
        if (i >= 0 && ebene(ebene) != null) {
            regionen.set(i, mitEbene(r, ebene));
            schreibe();
        }
    }

    /** Legt die eigene Form in die eigene Ebene {@code ebene}, wenn es sie gibt. */
    void ordne(EigeneForm f, int ebene) {
        int i = eigene.indexOf(f);
        if (i >= 0 && ebene(ebene) != null) {
            eigene.set(i, mitEbene(f, ebene));
            schreibe();
        }
    }

    private static Punkt mitEbene(Punkt p, int ebene) {
        return new Punkt(p.id(), p.dimension(), p.x(), p.z(), p.farbe(), p.angeheftet(), p.tod(), ebene, p.bund(), p.zeit())
                .stil(p.name(), p.schrift(), p.minecraft());
    }

    private static Region mitEbene(Region r, int ebene) {
        return new Region(r.dimension(), r.x0(), r.z0(), r.x1(), r.z1(), r.farbe(), r.angeheftet(), ebene);
    }

    private static EigeneForm mitEbene(EigeneForm f, int ebene) {
        return new EigeneForm(f.dimension(), f.ecken(), f.farbe(), f.angeheftet(), f.name(), f.hex(), ebene, f.schrift(), f.biegung(), f.gewicht(),
                f.minecraft());
    }

    /** Der Wegpunkt auf dem Block (x, z) in {@code dimension}, sichtbar oder nicht, oder null. */
    Punkt punktAuf(String dimension, int x, int z) {
        int i = finde(dimension, x, z);
        return i < 0 ? null : punkte.get(i);
    }

    /** Die Todespunkte, der älteste zuerst. */
    List<Punkt> tode() {
        return punkte.stream().filter(Punkt::tod).toList();
    }

    /** Der neueste Todespunkt, oder null. */
    Punkt tod() {
        List<Punkt> t = tode();
        return t.isEmpty() ? null : t.getLast();
    }

    /**
     * Setzt einen Todespunkt auf den Block (x, z) in {@code dimension}, angeheftet, als neuesten; der vorige neueste wird
     * gelöst, so führt ein Strahl zum letzten Tod. Lag dort schon ein Todespunkt, fällt er weg. Liegt dort ein Wegpunkt, gibt
     * es keinen, denn der Block ist schon markiert; dann false. Über {@code hoechstens} fallen die ältesten weg. Siehe
     * docs/wegpunkte.md, „Todespunkt“.
     */
    boolean setzeTod(String dimension, int x, int z, int hoechstens) {
        return setzeTod(dimension, x, z, hoechstens, System.currentTimeMillis());
    }

    /** Wie {@link #setzeTod(String, int, int, int)}, mit der Zeit des Todes in ms der Uhr. Siehe docs/wegpunkte.md, „Todespunkt“. */
    boolean setzeTod(String dimension, int x, int z, int hoechstens, long zeit) {
        punkte.removeIf(p -> p.tod() && p.dimension().equals(dimension) && p.x() == x && p.z() == z);
        boolean frei = finde(dimension, x, z) < 0;
        if (frei) {
            Punkt voriger = tod();
            if (voriger != null && voriger.angeheftet()) {
                umschalten(voriger);
            }
            punkte.add(new Punkt(naechsteId++, dimension, x, z, 0, true, true, BASE, 0, zeit));
            kappe(hoechstens);
        }
        schreibe();
        return frei;
    }

    /** Behält höchstens {@code hoechstens} Todespunkte, die neuesten; 0 nimmt alle weg. */
    void kappeTode(int hoechstens) {
        if (kappe(hoechstens)) {
            schreibe();
        }
    }

    private boolean kappe(int hoechstens) {
        List<Punkt> weg = new ArrayList<>(tode());
        weg = weg.subList(0, Math.max(0, weg.size() - Math.max(0, hoechstens)));
        return punkte.removeAll(weg);
    }

    /** Die Formatierer je Muster aus der Sprache, einmal gebaut statt in jedem Frame. */
    private static final Map<String, DateTimeFormatter> FORMATE = new ConcurrentHashMap<>();

    /**
     * Die Zeit des Todes {@code zeit} unter dem Todespunkt, in der Zeitzone {@code zone}, nach dem Muster {@code muster} aus der
     * Sprache des Spiels; taugt es nicht, nach {@code fest}. Siehe docs/wegpunkte.md, „Todespunkt“.
     */
    static String todZeit(long zeit, ZoneId zone, String muster, String fest) {
        return format(muster, fest).format(Instant.ofEpochMilli(zeit).atZone(zone));
    }

    /**
     * Der Formatierer für {@code muster}, einmal gebaut. Ein Ressourcenpaket, auch vom Server, kann die Sprache überschreiben; ein
     * Muster, das nicht geht oder ein Feld der Zeit nicht kennt, gibt das feste {@code fest}, statt beim Zeichnen zu werfen
     * (Review von #172).
     */
    private static DateTimeFormatter format(String muster, String fest) {
        return FORMATE.computeIfAbsent(muster + "\n" + fest, k -> {
            try {
                DateTimeFormatter f = DateTimeFormatter.ofPattern(muster);
                f.format(ZonedDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC));
                return f;
            } catch (IllegalArgumentException | DateTimeException kaputt) {
                return DateTimeFormatter.ofPattern(fest);
            }
        });
    }

    /** „Todespunkt gesetzt bei x z“ für den Chat; ein Klick darauf öffnet die Karte dort über {@code /hmap karte}. */
    static Component todMeldung(int x, int z) {
        return Component.translatable("heroicmap.tod.gesetzt", x, z).withStyle(s -> s.withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand("/hmap karte " + x + " " + z))
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable("heroicmap.tod.klick"))));
    }

    /**
     * „x z“, „x, z“ oder „x y z“ als ganze Blöcke, y überlesen, denn ein Wegpunkt hat nur x und z; jede Zahl nur aus Ziffern
     * in ASCII, x und z höchstens {@link #MAX_KOORDINATE} weit. Sonst null.
     */
    static int[] koordinaten(String roh) {
        String[] t = roh == null ? new String[0] : roh.strip().split("[\\s,;]+");
        if (t.length != 2 && t.length != 3 || !Arrays.stream(t).allMatch(s -> s.matches("[-+]?[0-9]{1,9}"))) {
            return null;
        }
        int x = Integer.parseInt(t[0]), z = Integer.parseInt(t[t.length - 1]);
        return Math.abs(x) <= MAX_KOORDINATE && Math.abs(z) <= MAX_KOORDINATE ? new int[] {x, z} : null;
    }

    List<Region> regionen() {
        return regionenSicht;
    }

    /** Die oberste eigene Region, die den Block enthält, die zuletzt gesetzte; sonst null. */
    Region region(String dimension, int x, int z) {
        for (int i = regionen.size() - 1; i >= 0; i--) {
            if (regionen.get(i).enthaelt(dimension, x, z)) {
                return regionen.get(i);
            }
        }
        return null;
    }

    /**
     * Setzt eine Region über die Blöcke von (ax, az) bis (bx, bz) samt beiden, gleich in welcher Folge
     * die Ecken kamen; gibt es sie schon oder schon {@link #MAX_REGIONEN}, bleibt es, wie es ist.
     * Nur noch Tests setzen so ein altes Rechteck; der Spieler baut eigene Formen.
     */
    void setze(String dimension, int ax, int az, int bx, int bz) {
        Region r = region(dimension, ax, az, bx, bz, farbe(dimension), false, BASE);
        if (regionen.size() < MAX_REGIONEN && finde(r) < 0) {
            regionen.add(r);
            schreibe();
        }
    }

    void loesche(Region r) {
        int i = finde(r);
        if (i >= 0) {
            regionen.remove(i);
            schreibe();
        }
    }

    private static Region region(String dimension, int ax, int az, int bx, int bz, int farbe, boolean angeheftet, int ebene) {
        return new Region(dimension, Math.min(ax, bx), Math.min(az, bz), Math.max(ax, bx), Math.max(az, bz), farbe, angeheftet, ebene);
    }

    /** Dieselbe Region heisst: dieselbe Dimension und dieselben Ecken; Farbe und Anheften zählen nicht. */
    private int finde(Region r) {
        for (int i = 0; i < regionen.size(); i++) {
            Region q = regionen.get(i);
            if (q.x0() == r.x0() && q.z0() == r.z0() && q.x1() == r.x1() && q.z1() == r.z1() && q.dimension().equals(r.dimension())) {
                return i;
            }
        }
        return -1;
    }

    /** Die erste Farbe, die in der Dimension unter Wegpunkten und Regionen noch frei ist; sind alle vergeben, reihum. */
    private int farbe(String dimension) {
        boolean[] belegt = new boolean[FARBEN.length];
        int anzahl = 0;
        for (Punkt p : punkte) {
            if (!p.tod() && p.dimension().equals(dimension)) {
                belegt[p.farbe()] = true;
                anzahl++;
            }
        }
        for (Region r : regionen) {
            if (r.dimension().equals(dimension)) {
                belegt[r.farbe()] = true;
                anzahl++;
            }
        }
        for (EigeneForm e : eigene) {
            if (dimension.equals(e.dimension())) {
                belegt[e.farbe()] = true;
                anzahl++;
            }
        }
        for (int f = 0; f < FARBEN.length; f++) {
            if (!belegt[f]) {
                return f;
            }
        }
        return anzahl % FARBEN.length;
    }

    /** Löscht den Wegpunkt; eigene Formen bleiben, wie sie sind. */
    void loesche(Punkt p) {
        int i = finde(p.dimension(), p.x(), p.z());
        if (i >= 0) {
            punkte.remove(i);
            schreibe();
        }
    }

    List<EigeneForm> eigeneFormen() {
        return Collections.unmodifiableList(eigene);
    }

    /** Der Wegpunkt mit dieser id, oder null. */
    Punkt punkt(int id) {
        for (Punkt p : punkte) {
            if (p.id() == id) {
                return p;
            }
        }
        return null;
    }

    /**
     * Setzt eine eigene Form in {@code dimension} mit diesen Ecken in Reihenfolge; false, wenn weniger als zwei
     * bleiben, es mehr als {@link #MAX_PUNKTE_FORM} sind oder es schon {@link #MAX_EIGENE_FORMEN} gibt.
     */
    boolean setzeForm(String dimension, List<Ecke> ecken) {
        List<Ecke> gueltig = gueltig(ecken);
        if (gueltig == null || eigene.size() >= MAX_EIGENE_FORMEN) {
            return false;
        }
        eigene.add(new EigeneForm(dimension, gueltig, farbe(dimension), false, null, KEIN_HEX, BASE));
        schreibe();
        return true;
    }

    /**
     * Gibt der eigenen Form {@code f} neue Ecken, an ihrer Stelle, mit Farbe und Anheften. Bleiben weniger als zwei,
     * fällt die Form weg; mehr als {@link #MAX_PUNKTE_FORM} nimmt sie nicht. Eine verbundene Ecke, die nun anderswo liegt,
     * zieht ihren Bund mit (siehe docs/wegpunkte.md, „Verbinden“). False, wenn sich nichts änderte, auch wenn ein Wegpunkt des
     * Bunds auf einen anderen träfe.
     */
    boolean ersetze(EigeneForm f, List<Ecke> ecken) {
        int i = eigene.indexOf(f);
        if (i < 0 || ecken.size() > MAX_PUNKTE_FORM) {
            return false;
        }
        List<Ecke> gueltig = gueltig(ecken);
        List<Punkt> punkteVorher = List.copyOf(punkte);
        List<EigeneForm> eigeneVorher = List.copyOf(eigene);
        if (gueltig == null) {
            eigene.remove(i);
        } else {
            eigene.set(i, f.mitEcken(gueltig));
            // Eine verbundene Ecke, die nun anderswo liegt, nimmt die übrigen Glieder ihres Bunds um denselben Weg mit.
            for (Ecke c : gueltig) {
                Ecke a = c.bund() > 0 ? f.ecken().stream().filter(e -> e.bund() == c.bund()).findFirst().orElse(null) : null;
                if (a != null && (a.x() != c.x() || a.z() != c.z()) && !schiebeBund(c.bund(), f.dimension(), c.x() - a.x(), c.z() - a.z(), i)) {
                    zurueck(punkteVorher, eigeneVorher);
                    return false;
                }
            }
        }
        schreibe();
        return true;
    }

    /** Setzt Wegpunkte und eigene Formen auf einen früheren Stand, wenn eine Änderung mit Bünden nicht ganz ging. */
    private void zurueck(List<Punkt> punkteVorher, List<EigeneForm> eigeneVorher) {
        punkte.clear();
        punkte.addAll(punkteVorher);
        eigene.clear();
        eigene.addAll(eigeneVorher);
    }

    /**
     * Schiebt alle Glieder des Bunds {@code b} um (dx, dz) Blöcke, ausser denen der Form Nummer {@code ohne} (-1: keine); auf
     * der Kreuzung einer Nachbarin wird eine Ecke mit ihr eine. So bleiben die Ecken auf einem Punkt und der Wegpunkt an derselben
     * Ecke seines Blocks (K3). False, sobald ein Wegpunkt des Bunds auf einen anderen träfe oder eine Form unter zwei Ecken fiele;
     * dann ist ein Teil schon geschoben, und der Rufer setzt mit {@link #zurueck} zurück. Siehe docs/wegpunkte.md, „Verbinden“.
     */
    private boolean schiebeBund(int b, String dimension, int dx, int dz, int ohne) {
        for (int i = 0; i < punkte.size(); i++) {
            Punkt p = punkte.get(i);
            if (p.bund() == b) {
                Punkt dort = punktAuf(dimension, p.x() + dx, p.z() + dz);
                if (dort != null && !dort.equals(p)) {
                    return false;
                }
                punkte.set(i, new Punkt(p.id(), p.dimension(), p.x() + dx, p.z() + dz, p.farbe(), p.angeheftet(), p.tod(), p.ebene(), b, p.zeit())
                        .stil(p.name(), p.schrift(), p.minecraft()));
            }
        }
        for (int i = 0; i < eigene.size(); i++) {
            EigeneForm f = eigene.get(i);
            if (i != ohne && f.ecken().stream().anyMatch(c -> c.bund() == b)) {
                List<Ecke> neu = gueltig(f.ecken().stream().map(c -> c.bund() == b ? c.auf(c.x() + dx, c.z() + dz) : c).toList());
                if (neu == null) {
                    return false;
                }
                eigene.set(i, f.mitEcken(neu));
            }
        }
        return true;
    }

    /**
     * Die Ecke des Blocks (bx, bz), die dem Punkt (x, z) der Welt am nächsten liegt: eine der vier Kreuzungen seiner Kanten.
     * Siehe docs/wegpunkte.md, „Eigene Formen“.
     */
    static int[] eckeDesBlocks(int bx, int bz, double x, double z) {
        return new int[] {x >= bx + 0.5 ? bx + 1 : bx, z >= bz + 0.5 ? bz + 1 : bz};
    }

    /** Der der vier Blöcke an der Kreuzung (x, z), der dem Punkt (wx, wz) der Welt am nächsten liegt (K2). */
    static int[] blockAnEcke(int x, int z, double wx, double wz) {
        return new int[] {wx < x ? x - 1 : x, wz < z ? z - 1 : z};
    }

    /** Die Kreuzung, auf der die Ecken des Glieds {@code g} und seines Bunds liegen, oder null bei einem losen Wegpunkt. */
    private int[] kreuzung(Glied g) {
        if (g.punkt() == null) {
            return new int[] {g.x(), g.z()};
        }
        return glieder(g).stream().filter(e -> e.form() != null).findFirst().map(e -> new int[] {e.x(), e.z()}).orElse(null);
    }

    /**
     * Um wie viele Blöcke {@code von} samt Bund zieht, wenn es sich mit {@code ziel} verbindet: Ecken auf die Kreuzung des Ziels;
     * auf einen losen Wegpunkt auf die Ecke seines Blocks, die {@code nahe} am nächsten liegt; ein loser Wegpunkt auf den der vier
     * Blöcke an der Kreuzung des Ziels, der {@code nahe} am nächsten liegt (K2). {@code nahe} ist beim Ziehen die Maus, sonst
     * null, dann der alte Ort von {@code von}. Null, wenn beide lose Wegpunkte sind.
     */
    private int[] weg(Glied von, Glied ziel, double[] nahe) {
        int[] a = kreuzung(von), z = kreuzung(ziel);
        if (a != null && z != null) {
            return new int[] {z[0] - a[0], z[1] - a[1]};
        }
        if (a != null) {
            int[] e = nahe != null ? eckeDesBlocks(ziel.x(), ziel.z(), nahe[0], nahe[1]) : eckeDesBlocks(ziel.x(), ziel.z(), a[0], a[1]);
            return new int[] {e[0] - a[0], e[1] - a[1]};
        }
        if (z != null) {
            int[] blk = nahe != null ? blockAnEcke(z[0], z[1], nahe[0], nahe[1]) : blockAnEcke(z[0], z[1], von.x() + 0.5, von.z() + 0.5);
            return new int[] {blk[0] - von.x(), blk[1] - von.z()};
        }
        return null;
    }

    /** Darf {@code von} mit {@code ziel} verbunden werden? Wenn {@link #warumNicht} keinen Grund findet. */
    boolean kannVerbinden(Glied von, Glied ziel) {
        return warumNicht(von, ziel) == null;
    }

    /**
     * Warum sich {@code von} nicht mit {@code ziel} verbinden lässt, als Schlüssel der Meldung, oder null, wenn es geht: kein
     * Todespunkt; nur in einer Dimension und nicht, was schon verbunden ist; keine Form zweimal im neuen Bund; höchstens ein
     * Wegpunkt darin, und der nicht auf dem Block eines anderen. Siehe docs/wegpunkte.md, „Verbinden“.
     */
    String warumNicht(Glied von, Glied ziel) {
        return warumNicht(von, ziel, null);
    }

    /** Wie {@link #warumNicht(Glied, Glied)}, beim Ziehen mit der Maus bei {@code nahe} in der Welt (siehe {@link #weg}). */
    String warumNicht(Glied von, Glied ziel, double[] nahe) {
        if (von.punkt() != null && von.punkt().tod() || ziel.punkt() != null && ziel.punkt().tod()) {
            return "heroicmap.karte.verbinden_tod";
        }
        if (!von.dimension().equals(ziel.dimension()) || von.bund() > 0 && von.bund() == ziel.bund()) {
            return "heroicmap.karte.verbinden_nicht";
        }
        List<Glied> a = glieder(von), b = glieder(ziel);
        Set<EigeneForm> formen = new HashSet<>();
        a.stream().filter(g -> g.form() != null).forEach(g -> formen.add(g.form()));
        if (b.stream().anyMatch(g -> g.form() != null && formen.contains(g.form()))) {
            return "heroicmap.karte.verbinden_form";
        }
        List<Punkt> wegpunkte = new ArrayList<>();
        a.stream().filter(g -> g.punkt() != null).forEach(g -> wegpunkte.add(g.punkt()));
        b.stream().filter(g -> g.punkt() != null).forEach(g -> wegpunkte.add(g.punkt()));
        // Höchstens ein Wegpunkt je Bund, denn zwei liegen nie auf einem Block; das schliesst auch zwei Wegpunkte miteinander aus.
        if (wegpunkte.size() > 1) {
            return "heroicmap.karte.verbinden_wegpunkte";
        }
        // Nur ein Wegpunkt, der mitzieht, kann auf einen anderen treffen; eine Ecke darf auf dem Block eines fremden liegen.
        Punkt mit = a.stream().filter(g -> g.punkt() != null).map(Glied::punkt).findFirst().orElse(null);
        int[] w = weg(von, ziel, nahe);
        if (mit == null || w == null) {
            return null;
        }
        Punkt dort = punktAuf(ziel.dimension(), mit.x() + w[0], mit.z() + w[1]);
        return dort == null || dort.equals(mit) ? null : "heroicmap.karte.koordinaten_belegt";
    }

    /** Das Glied und alle anderen seines Bunds; lose nur es selbst. */
    private List<Glied> glieder(Glied g) {
        if (g.bund() == 0) {
            return List.of(g);
        }
        List<Glied> aus = new ArrayList<>();
        punkte.stream().filter(p -> p.bund() == g.bund()).forEach(p -> aus.add(Glied.von(p)));
        for (EigeneForm f : eigene) {
            for (int i = 0; i < f.ecken().size(); i++) {
                if (f.ecken().get(i).bund() == g.bund()) {
                    aus.add(Glied.von(f, i));
                }
            }
        }
        return aus;
    }

    /**
     * Verbindet {@code von} mit {@code ziel}: {@code von} zieht samt seinem Bund um den {@link #weg} zum Ziel, und beide Bünde
     * werden einer. False, wenn es eins nicht mehr gibt oder sie sich nicht verbinden lassen ({@link #kannVerbinden}). Siehe
     * docs/wegpunkte.md, „Verbinden“.
     */
    boolean verbinde(Glied von, Glied ziel) {
        return verbinde(von, ziel, null);
    }

    /** Wie {@link #verbinde(Glied, Glied)}, beim Ziehen mit der Maus bei {@code nahe} in der Welt (siehe {@link #weg}). */
    boolean verbinde(Glied von, Glied ziel, double[] nahe) {
        if (!da(von) || !da(ziel) || warumNicht(von, ziel, nahe) != null) {
            return false;
        }
        int[] w = weg(von, ziel, nahe);
        List<Punkt> punkteVorher = List.copyOf(punkte);
        List<EigeneForm> eigeneVorher = List.copyOf(eigene);
        // Erst bekommt der eigene Bund eine Zahl, dann zieht er; das Ziel bleibt dabei, wie es ist.
        int eigener = von.bund() > 0 ? von.bund() : neuerBund();
        binde(von, eigener);
        if (!schiebeBund(eigener, von.dimension(), w[0], w[1], -1)) {
            zurueck(punkteVorher, eigeneVorher);
            return false;
        }
        if (ziel.bund() > 0) {
            nenneUm(eigener, ziel.bund());
        } else {
            binde(ziel, eigener);
        }
        schreibe();
        return true;
    }

    /** Gibt es das Glied so noch? */
    private boolean da(Glied g) {
        return g.punkt() != null ? punkte.contains(g.punkt()) : eigene.contains(g.form()) && g.index() >= 0 && g.index() < g.form().ecken().size();
    }

    /** Gibt allen Gliedern des Bunds {@code alt} die Zahl {@code b}. */
    private void nenneUm(int alt, int b) {
        punkte.replaceAll(p -> p.bund() == alt ? mitBund(p, b) : p);
        eigene.replaceAll(f -> f.ecken().stream().noneMatch(c -> c.bund() == alt) ? f
                : f.mitEcken(f.ecken().stream().map(c -> c.bund() == alt ? new Ecke(c.x(), c.z(), b) : c).toList()));
    }

    /** Legt das Glied in den Bund {@code b}, mit seinem ganzen bisherigen Bund. */
    private void binde(Glied g, int b) {
        int alt = g.bund();
        if (alt == b) {
            return;
        }
        if (alt > 0) {
            nenneUm(alt, b);
        } else if (g.punkt() != null) {
            punkte.set(punkte.indexOf(g.punkt()), mitBund(g.punkt(), b));
        } else {
            List<Ecke> ecken = new ArrayList<>(g.form().ecken());
            Ecke c = ecken.get(g.index());
            ecken.set(g.index(), new Ecke(c.x(), c.z(), b));
            eigene.set(eigene.indexOf(g.form()), g.form().mitEcken(ecken));
        }
    }

    private static Punkt mitBund(Punkt p, int b) {
        return new Punkt(p.id(), p.dimension(), p.x(), p.z(), p.farbe(), p.angeheftet(), p.tod(), p.ebene(), b, p.zeit())
                .stil(p.name(), p.schrift(), p.minecraft());
    }

    private int neuerBund() {
        int hoechster = punkte.stream().mapToInt(Punkt::bund).max().orElse(0);
        for (EigeneForm f : eigene) {
            hoechster = Math.max(hoechster, f.ecken().stream().mapToInt(Ecke::bund).max().orElse(0));
        }
        return hoechster + 1;
    }

    /** Löst den Bund {@code b}: Alle seine Glieder werden lose und bleiben, wo sie sind. Siehe docs/wegpunkte.md, „Verbinden“. */
    void loese(int b) {
        if (b <= 0) {
            return;
        }
        punkte.replaceAll(p -> p.bund() == b ? mitBund(p, 0) : p);
        eigene.replaceAll(f -> f.ecken().stream().noneMatch(c -> c.bund() == b) ? f
                : f.mitEcken(f.ecken().stream().map(c -> c.bund() == b ? new Ecke(c.x(), c.z()) : c).toList()));
        schreibe();
    }

    /**
     * Ordnet die Bünde: Die Ecken eines Bunds liegen auf einer Kreuzung, ein Wegpunkt darin auf einem der vier Blöcke daran (K3).
     * Liegen Ecken auf mehreren Kreuzungen, behält die erste die Zahl, jede weitere bekommt eine neue; ein Wegpunkt ohne Kreuzung
     * daneben und wer allein bleibt, wird lose. Sonst bleiben die Zahlen, so trifft ein „Lösen“ aus dem offenen Menü den richtigen
     * Bund. So hält die Invariante nach jedem Löschen und nach einer älteren Version, die ein Glied verschob. Siehe
     * docs/wegpunkte.md, „Verbinden“.
     */
    private void ordneBuende() {
        Map<List<Object>, Integer> zahl = new LinkedHashMap<>();
        for (EigeneForm f : eigene) {
            f.ecken().stream().filter(c -> c.bund() > 0).forEach(c -> zahl.merge(List.of(c.bund(), f.dimension(), c.x(), c.z()), 1, Integer::sum));
        }
        punkte.stream().filter(p -> p.bund() > 0).forEach(p -> zahl.merge(schluessel(p, zahl.keySet()), 1, Integer::sum));
        Map<List<Object>, Integer> neu = new HashMap<>();
        Set<Integer> vergeben = new HashSet<>();
        int naechster = neuerBund();
        boolean gleich = true;
        for (Map.Entry<List<Object>, Integer> e : zahl.entrySet()) {
            int b = (Integer) e.getKey().getFirst();
            int n = e.getValue() < 2 ? 0 : vergeben.add(b) ? b : naechster++;
            neu.put(e.getKey(), n);
            gleich &= n == b;
        }
        if (gleich) {
            return;
        }
        Set<List<Object>> kreuzungen = Set.copyOf(zahl.keySet());
        punkte.replaceAll(p -> p.bund() == 0 ? p : mitBund(p, neu.getOrDefault(schluessel(p, kreuzungen), 0)));
        eigene.replaceAll(f -> f.ecken().stream().allMatch(c -> c.bund() == 0) ? f : f.mitEcken(f.ecken().stream()
                .map(c -> c.bund() == 0 ? c : new Ecke(c.x(), c.z(), neu.getOrDefault(List.of(c.bund(), f.dimension(), c.x(), c.z()), 0)))
                .toList()));
    }

    /**
     * Der Schlüssel eines verbundenen Wegpunkts in {@link #ordneBuende}: die Kreuzung seines Bunds an einer Ecke seines Blocks,
     * wenn es eine unter {@code kreuzungen} gibt; sonst einer nur für ihn, der allein bleibt.
     */
    private static List<Object> schluessel(Punkt p, Set<List<Object>> kreuzungen) {
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                List<Object> k = List.of(p.bund(), p.dimension(), p.x() + dx, p.z() + dz);
                if (kreuzungen.contains(k)) {
                    return k;
                }
            }
        }
        return List.of(p.bund(), p.dimension(), "wegpunkt", p.x(), p.z());
    }

    void loesche(EigeneForm f) {
        if (eigene.remove(f)) {
            schreibe();
        }
    }

    /** Heftet die eigene Form an die Minimap oder löst sie; false, wenn schon {@link #MAX_ANGEHEFTET} angeheftet sind. */
    boolean umschalten(EigeneForm f) {
        int i = eigene.indexOf(f);
        if (i < 0) {
            return true;
        }
        if (!f.angeheftet() && angeheftet() >= MAX_ANGEHEFTET) {
            return false;
        }
        eigene.set(i, new EigeneForm(f.dimension(), f.ecken(), f.farbe(), !f.angeheftet(), f.name(), f.hex(), f.ebene(), f.schrift(),
                f.biegung(), f.gewicht(), f.minecraft()));
        schreibe();
        return true;
    }

    /** Gibt der eigenen Form {@code f} den Namen {@code name}, bereinigt wie in {@link #name}; was leer bleibt, nimmt ihn weg. */
    void benenne(EigeneForm f, String name) {
        int i = eigene.indexOf(f);
        if (i >= 0) {
            eigene.set(i, new EigeneForm(f.dimension(), f.ecken(), f.farbe(), f.angeheftet(), name(name), f.hex(), f.ebene(), f.schrift(),
                    f.biegung(), f.gewicht(), f.minecraft()));
            schreibe();
        }
    }

    /**
     * Gibt der eigenen Form {@code f} Schrift und Biegung, nur im Speicher: Der Regler am Namensfeld zeigt sie gleich auf Karte
     * und Minimap, gespeichert wird mit dem Namen ({@link #benenne}). Gibt die Form zurück, wie sie danach ist, ohne Fund
     * {@code f}. Siehe docs/wegpunkte.md, „Namen“.
     */
    EigeneForm stil(EigeneForm f, double schrift, int biegung, int gewicht) {
        int i = eigene.indexOf(f);
        if (i < 0) {
            return f;
        }
        EigeneForm neu = new EigeneForm(f.dimension(), f.ecken(), f.farbe(), f.angeheftet(), f.name(), f.hex(), f.ebene(), schrift, biegung,
                gewicht, f.minecraft());
        eigene.set(i, neu);
        stand++;
        return neu;
    }

    /**
     * Gibt der eigenen Form {@code f} die Minecraft-Schrift oder die Kartenschrift, nur im Speicher wie {@link #stil}; gespeichert
     * wird mit dem Namen. Gibt die Form zurück, wie sie danach ist. Siehe docs/wegpunkte.md, „Namen“.
     */
    EigeneForm schriftart(EigeneForm f, boolean minecraft) {
        int i = eigene.indexOf(f);
        if (i < 0 || f.minecraft() == minecraft) {
            return f;
        }
        EigeneForm neu = new EigeneForm(f.dimension(), f.ecken(), f.farbe(), f.angeheftet(), f.name(), f.hex(), f.ebene(), f.schrift(),
                f.biegung(), f.gewicht(), minecraft);
        eigene.set(i, neu);
        stand++;
        return neu;
    }

    /** Gibt dem Wegpunkt {@code p} den Namen {@code name} wie {@link #benenne(EigeneForm, String)}; ein Todespunkt bleibt ohne. */
    void benenne(Punkt p, String name) {
        int i = punkte.indexOf(p);
        if (i >= 0 && !p.tod()) {
            punkte.set(i, p.stil(name(name), p.schrift(), p.minecraft()));
            schreibe();
        }
    }

    /**
     * Gibt dem Wegpunkt {@code p} Schrift und Schriftart, nur im Speicher wie {@link #stil(EigeneForm, double, int, int)};
     * gespeichert wird mit dem Namen. Gibt den Wegpunkt zurück, wie er danach ist, ohne Fund {@code p}.
     */
    Punkt stil(Punkt p, double schrift, boolean minecraft) {
        int i = punkte.indexOf(p);
        if (i < 0) {
            return p;
        }
        Punkt neu = p.stil(p.name(), schrift, minecraft);
        punkte.set(i, neu);
        stand++;
        return neu;
    }

    /** Ist {@code g} ein Gewicht: eine ganze Zahl von −{@link #MAX_GEWICHT} bis {@link #MAX_GEWICHT}? */
    static boolean gewichtGilt(double g) {
        return g == Math.rint(g) && Math.abs(g) <= MAX_GEWICHT;
    }

    /**
     * Trifft der Griff oder die Ecke im Abstand {@code d} mit Gewicht {@code gewicht} eher als die beste bisher, im Abstand
     * {@code abstand} mit {@code gewichtBeste}? Näher gewinnt; bei gleichem Abstand das schwerere, bei gleichem Gewicht die
     * spätere, wie vor mod#163. Ohne beste ist {@code gewichtBeste} {@link Integer#MIN_VALUE}.
     */
    static boolean trifftEher(double d, int gewicht, double abstand, int gewichtBeste) {
        return d < abstand || d == abstand && gewicht >= gewichtBeste;
    }

    /** Das Gewicht einer Form aus {@link #minimap}: das der eigenen Form, sonst 0. */
    int gewicht(Ebenen.Form f) {
        return gewichte.getOrDefault(f, 0);
    }

    /** Die Raste von {@link #SCHRIFTEN} nächst {@code f}. */
    static double schrift(double f) {
        double beste = SCHRIFTEN[0];
        for (double s : SCHRIFTEN) {
            if (Math.abs(s - f) < Math.abs(beste - f)) {
                beste = s;
            }
        }
        return beste;
    }

    /** Liegt {@code f} zwischen der kleinsten und der grössten Raste von {@link #SCHRIFTEN}? */
    static boolean schriftGilt(double f) {
        return f >= SCHRIFTEN[0] && f <= SCHRIFTEN[SCHRIFTEN.length - 1];
    }

    /** Die Biegung {@code g} in Grad auf dem nächsten Schritt von {@link #BIEGUNG_SCHRITT}. */
    static int biegung(double g) {
        return (int) Math.round(g / BIEGUNG_SCHRITT) * BIEGUNG_SCHRITT;
    }

    /** Gibt der eigenen Form {@code f} die Farbe {@code hex} als RGB; {@link #KEIN_HEX} nimmt sie weg. */
    void faerbe(EigeneForm f, int hex) {
        int i = eigene.indexOf(f);
        if (i >= 0) {
            eigene.set(i, new EigeneForm(f.dimension(), f.ecken(), f.farbe(), f.angeheftet(), f.name(), hex, f.ebene(), f.schrift(), f.biegung(), f.gewicht(),
                    f.minecraft()));
            schreibe();
        }
    }

    /** Eine Farbe als „#RRGGBB“ oder „RRGGBB“, gross oder klein, aussen ohne Leerraum: ihr RGB; sonst {@link #KEIN_HEX}. */
    static int hex(String roh) {
        String t = roh == null ? "" : roh.strip();
        t = t.startsWith("#") ? t.substring(1) : t;
        // Nur ASCII: Integer.parseInt nähme auch Ziffern anderer Schriften.
        return t.matches("[0-9A-Fa-f]{6}") ? Integer.parseInt(t, 16) : KEIN_HEX;
    }

    /** Die Farbe {@code rgb} als „#RRGGBB“. */
    static String hex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    /**
     * Ein Name als schlichter Text: Codes mit § gestrichen, NFC, ohne Steuer- und Formatzeichen, aussen ohne Leerraum,
     * höchstens {@link #MAX_NAME} Zeichen; null, wenn nichts bleibt.
     */
    static String name(String roh) {
        if (roh == null) {
            return null;
        }
        String t = Normalizer.normalize(ChatFormatting.stripFormatting(roh), Normalizer.Form.NFC).codePoints()
                .filter(c -> !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().strip();
        if (t.codePointCount(0, t.length()) > MAX_NAME) {
            t = t.substring(0, t.offsetByCodePoints(0, MAX_NAME)).strip();
        }
        return t.isEmpty() ? null : t;
    }

    /**
     * Die Ecken ohne einen Block zweimal hintereinander; von zweien auf einem Block bleibt eine verbundene. Null, wenn weniger
     * als zwei bleiben oder es mehr als {@link #MAX_PUNKTE_FORM} sind.
     */
    static List<Ecke> gueltig(List<Ecke> ecken) {
        List<Ecke> aus = new ArrayList<>();
        for (Ecke e : ecken) {
            if (aus.isEmpty() || !aufEinemBlock(aus.getLast(), e)) {
                aus.add(e);
            } else if (aus.getLast().bund() == 0) {
                aus.set(aus.size() - 1, e);
            }
        }
        // Eine letzte Ecke auf dem Block der ersten schliesst nur, sie zählt nicht doppelt.
        if (aus.size() > 2 && aufEinemBlock(aus.getLast(), aus.getFirst())) {
            Ecke letzte = aus.removeLast();
            if (aus.getFirst().bund() == 0) {
                aus.set(0, letzte);
            }
        }
        return aus.size() < 2 || aus.size() > MAX_PUNKTE_FORM ? null : aus;
    }

    private static boolean aufEinemBlock(Ecke a, Ecke b) {
        return a.x() == b.x() && a.z() == b.z();
    }

    /** Vergibt jedem Wegpunkt ohne gültige oder mit doppelter id eine neue; danach die nächste freie. */
    private void vergibIds() {
        Set<Integer> belegt = new HashSet<>();
        int hoechste = 0;
        for (Punkt p : punkte) {
            if (p.id() > 0 && belegt.add(p.id())) {
                hoechste = Math.max(hoechste, p.id());
            }
        }
        naechsteId = hoechste + 1;
        Set<Integer> gesehen = new HashSet<>();
        for (int i = 0; i < punkte.size(); i++) {
            Punkt p = punkte.get(i);
            if (p.id() <= 0 || !gesehen.add(p.id())) {
                punkte.set(i, new Punkt(naechsteId++, p.dimension(), p.x(), p.z(), p.farbe(), p.angeheftet(), p.tod(), p.ebene(), p.bund(),
                        p.zeit()).stil(p.name(), p.schrift(), p.minecraft()));
            }
        }
    }

    /**
     * Legt den Wegpunkt auf den Block (x, z) seiner Dimension, mit Farbe und Anheften, verbunden samt seinem Bund (siehe
     * docs/wegpunkte.md, „Verbinden“); false, wenn es ihn nicht gibt, dort schon einer liegt oder eine Form des Bunds unter zwei
     * Ecken fiele, dann bleibt alles.
     */
    boolean verschiebe(Punkt p, int x, int z) {
        int i = finde(p.dimension(), p.x(), p.z());
        if (i < 0 || finde(p.dimension(), x, z) >= 0) {
            return false;
        }
        Punkt alt = punkte.get(i);
        if (alt.bund() > 0) {
            List<Punkt> punkteVorher = List.copyOf(punkte);
            List<EigeneForm> eigeneVorher = List.copyOf(eigene);
            if (!schiebeBund(alt.bund(), alt.dimension(), x - alt.x(), z - alt.z(), -1)) {
                zurueck(punkteVorher, eigeneVorher);
                return false;
            }
        } else {
            punkte.set(i, new Punkt(alt.id(), alt.dimension(), x, z, alt.farbe(), alt.angeheftet(), alt.tod(), alt.ebene(), 0, alt.zeit())
                    .stil(alt.name(), alt.schrift(), alt.minecraft()));
        }
        schreibe();
        return true;
    }

    /** Heftet den Wegpunkt an die Minimap oder löst ihn. */
    void umschalten(Punkt p) {
        int i = finde(p.dimension(), p.x(), p.z());
        if (i >= 0) {
            Punkt alt = punkte.get(i);
            punkte.set(i, new Punkt(alt.id(), alt.dimension(), alt.x(), alt.z(), alt.farbe(), !alt.angeheftet(), alt.tod(), alt.ebene(), alt.bund(),
                    alt.zeit()).stil(alt.name(), alt.schrift(), alt.minecraft()));
            schreibe();
        }
    }

    /**
     * Heftet die eigene Region an die Minimap oder löst sie; false, wenn schon {@link #MAX_ANGEHEFTET}
     * angeheftet sind und nichts geschah.
     */
    boolean umschalten(Region r) {
        int i = finde(r);
        if (i < 0) {
            return true;
        }
        Region alt = regionen.get(i);
        if (!alt.angeheftet() && angeheftet() >= MAX_ANGEHEFTET) {
            return false;
        }
        regionen.set(i, new Region(alt.dimension(), alt.x0(), alt.z0(), alt.x1(), alt.z1(), alt.farbe(), !alt.angeheftet(), alt.ebene()));
        schreibe();
        return true;
    }

    /**
     * Gibt dem Wegpunkt die Farbe {@code farbe} aus {@link #FARBEN} und speichert; ein Todespunkt bleibt. Gibt den Wegpunkt zurück,
     * wie er danach ist, ohne Fund {@code p}. Siehe docs/verwaltung.md, „Spalten“.
     */
    Punkt faerbe(Punkt p, int farbe) {
        int i = punkte.indexOf(p);
        if (i < 0 || p.tod()) {
            return p;
        }
        Punkt neu = new Punkt(p.id(), p.dimension(), p.x(), p.z(), Math.floorMod(farbe, FARBEN.length), p.angeheftet(), p.tod(), p.ebene(),
                p.bund(), p.zeit()).stil(p.name(), p.schrift(), p.minecraft());
        punkte.set(i, neu);
        schreibe();
        return neu;
    }

    /** Ebenso für ein altes Rechteck. */
    Region faerbe(Region r, int farbe) {
        int i = finde(r);
        if (i < 0) {
            return r;
        }
        Region alt = regionen.get(i);
        Region neu = new Region(alt.dimension(), alt.x0(), alt.z0(), alt.x1(), alt.z1(), Math.floorMod(farbe, FARBEN.length), alt.angeheftet(),
                alt.ebene());
        regionen.set(i, neu);
        schreibe();
        return neu;
    }

    /** Schreibt, was {@code stil} nur im Speicher geändert hat; die Schnellverwaltung speichert so jeden Klick. */
    void speichere() {
        schreibe();
    }

    /** Wie oben für die Fläche oder den Kreis {@code id} der Ebene {@code ebene} vom Server. */
    boolean umschalten(String ebene, String id) {
        Anheftung a = new Anheftung(ebene, id);
        if (!formen.remove(a)) {
            if (angeheftet() >= MAX_ANGEHEFTET) {
                return false;
            }
            formen.add(a);
        }
        schreibe();
        return true;
    }

    boolean angeheftet(String ebene, String id) {
        return formen.contains(new Anheftung(ebene, id));
    }

    /** Heftet die Nadel oder das Banner {@code id} der Ebene {@code ebene} an die Minimap oder löst es; false, wenn schon {@link #MAX_NADELN_ANGEHEFTET} angeheftet sind. */
    boolean umschaltenNadel(String ebene, String id) {
        Anheftung a = new Anheftung(ebene, id);
        if (!nadeln.remove(a)) {
            if (nadeln.size() >= MAX_NADELN_ANGEHEFTET) {
                return false;
            }
            nadeln.add(a);
        }
        schreibe();
        return true;
    }

    /** Zählt jede Änderung; wer etwas aus den Wegpunkten rechnet, rechnet neu, wenn er sich ändert. */
    int stand() {
        return stand;
    }

    boolean nadelAngeheftet(String ebene, String id) {
        return nadeln.contains(new Anheftung(ebene, id));
    }

    /**
     * Ist alles, was sich von der Ebene {@code ebene} anheften lässt, angeheftet: Flächen, Kreise, Nadeln
     * und Banner mit {@code id}; false, wenn sie nichts davon hat. Siehe docs/vollbildkarte.md, „Ebenen“.
     */
    boolean ganzAngeheftet(Ebenen e, String ebene) {
        boolean etwas = false;
        for (Ebenen.Form f : e.formen(ebene)) {
            String id = Ebenen.id(f);
            if (id != null) {
                if (!formen.contains(new Anheftung(ebene, id))) {
                    return false;
                }
                etwas = true;
            }
        }
        for (Ebenen.Ort o : e.nadeln(ebene)) {
            if (o.id() != null) {
                if (!nadeln.contains(new Anheftung(ebene, o.id()))) {
                    return false;
                }
                etwas = true;
            }
        }
        return etwas;
    }

    /**
     * Ist alles Anheftbare der Ebene angeheftet, löst es alles; sonst heftet es an, was noch fehlt, so weit
     * {@link #MAX_ANGEHEFTET} und {@link #MAX_NADELN_ANGEHEFTET} reichen. Gibt zurück, wie viele nicht mehr passten.
     */
    int alleUmschalten(Ebenen e, String ebene) {
        boolean loesen = ganzAngeheftet(e, ebene);
        int frei = MAX_ANGEHEFTET - angeheftet(), uebrig = 0;
        for (Ebenen.Form f : e.formen(ebene)) {
            String id = Ebenen.id(f);
            if (id == null) {
                continue;
            }
            Anheftung a = new Anheftung(ebene, id);
            if (loesen) {
                formen.remove(a);
            } else if (!formen.contains(a) && frei > 0) {
                formen.add(a);
                frei--;
            } else if (!formen.contains(a)) {
                uebrig++;
            }
        }
        for (Ebenen.Ort o : e.nadeln(ebene)) {
            if (o.id() == null) {
                continue;
            }
            Anheftung a = new Anheftung(ebene, o.id());
            if (loesen) {
                nadeln.remove(a);
            } else if (!nadeln.contains(a) && nadeln.size() < MAX_NADELN_ANGEHEFTET) {
                nadeln.add(a);
            } else if (!nadeln.contains(a)) {
                uebrig++;
            }
        }
        schreibe();
        return uebrig;
    }

    /** Wie viele Regionen und Kreise angeheftet sind, eigene und vom Server. */
    int angeheftet() {
        int n = formen.size();
        for (Region r : regionen) {
            n += r.angeheftet() ? 1 : 0;
        }
        for (EigeneForm f : eigene) {
            n += f.angeheftet() ? 1 : 0;
        }
        return n;
    }

    /**
     * Ist die Ebene {@code ebene} eben ganz angekommen: vergisst, was von ihr angeheftet ist und sie
     * nicht mehr hat; sonst füllten tote Einträge die {@link #MAX_ANGEHEFTET}. Andere Ebenen bleiben,
     * sie können noch vom vorigen Server sein.
     */
    void pruefe(Ebenen e, String ebene) {
        Set<String> ids = e.formen(ebene).stream().map(Ebenen::id).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> orte = e.nadeln(ebene).stream().map(Ebenen.Ort::id).filter(Objects::nonNull).collect(Collectors.toSet());
        boolean weg = formen.removeIf(a -> a.ebene().equals(ebene) && !ids.contains(a.id()));
        if (nadeln.removeIf(a -> a.ebene().equals(ebene) && !orte.contains(a.id())) || weg) {
            schreibe();
        }
    }

    /**
     * Die Formen der sichtbaren Ebenen für die Vollbildkarte: Angeheftetes mit einem Rand, der
     * {@link #BREITER} Einheiten breiter ist, sonst die Listen der Ebenen selbst. Dieselben Listen,
     * bis sich Ebenen oder Wegpunkte ändern; so bleibt der {@link Formen.Speicher} gültig.
     */
    List<List<Ebenen.Form>> karte(Ebenen e) {
        baue(e);
        return fuerKarte;
    }

    /**
     * Ebenso auf einer Karte mit {@code block} Einheiten je Block: von einer Ebene jenseits ihrer Zoomgrenze nur das Angeheftete.
     * Ohne solche Ebene dieselbe Liste wie oben; sonst eine neue aus denselben Listen. Siehe docs/ebenen.md, „Zoomgrenze“.
     */
    List<List<Ebenen.Form>> karte(Ebenen e, double block) {
        baue(e);
        List<List<Ebenen.Form>> nah = null;
        for (int i = 0; i < fuerKarte.size(); i++) {
            boolean drin = Ebenen.imZoom(karteGrenzen[i], block);
            if (!drin && nah == null) {
                nah = new ArrayList<>(fuerKarte.subList(0, i));
            }
            if (nah != null && (drin || !karteAngeheftet.get(i).isEmpty())) {
                nah.add(drin ? fuerKarte.get(i) : karteAngeheftet.get(i));
            }
        }
        return nah == null ? fuerKarte : nah;
    }

    /**
     * Für die Minimap nur Angeheftetes: je sichtbare Ebene ihre angehefteten Flächen und Kreise,
     * zuletzt die angehefteten eigenen Regionen. Dieselben Listen wie oben.
     */
    List<List<Ebenen.Form>> minimap(Ebenen e) {
        baue(e);
        return fuerMinimap;
    }

    /** Ist {@code f} aus {@link #minimap} eine eigene Form oder ein altes Rechteck, nicht vom Server? */
    boolean eigen(Ebenen.Form f) {
        return eigeneAufMinimap.contains(f);
    }

    /** Die angehefteten Nadeln und Banner der sichtbaren Ebenen für die Minimap, unten zuerst; dieselbe Liste wie oben. */
    List<Ebenen.Ort> nadeln(Ebenen e) {
        baue(e);
        return fuerNadeln;
    }

    /** Ist die Nadel oder das Banner {@code o} einer sichtbaren Ebene angeheftet? Ohne Allokation. */
    boolean angeheftet(Ebenen e, Ebenen.Ort o) {
        baue(e);
        return angeheftetOrte.contains(o);
    }

    /**
     * Lässt die eigenen Formen {@code fs} aus den Formen der Vollbildkarte weg, solange sie gezogen werden oder einer gezogenen
     * folgen; leer zeigt wieder alle.
     */
    void ziehe(List<EigeneForm> fs) {
        gezogen = List.copyOf(fs);
    }

    private void baue(Ebenen e) {
        Ebenenreihe reihe = Ebenenreihe.INSTANZ;
        if (e == gebautAus && e.stand() == gebautEbenen && stand == gebautStand && gezogen.equals(gebautGezogen)
                && reihe.stand() == gebautReihe) {
            return;
        }
        gebautAus = e;
        gebautEbenen = e.stand();
        gebautStand = stand;
        gebautGezogen = gezogen;
        gebautReihe = reihe.stand();
        Map<String, Set<String>> je = new HashMap<>();
        formen.forEach(a -> je.computeIfAbsent(a.ebene(), k -> new HashSet<>()).add(a.id()));
        // Je sichtbare Ebene vom Server ihre Liste, angeheftete Formen und Nadeln, von unten nach oben.
        Map<String, List<Ebenen.Form>> karteServer = new LinkedHashMap<>(), minimapServer = new LinkedHashMap<>(), karteAn = new HashMap<>();
        Map<String, Double> grenzen = new HashMap<>();
        Map<String, List<Ebenen.Ort>> orteServer = new LinkedHashMap<>();
        for (Ebenen.Eintrag eintrag : e.sichtbar()) {
            List<Ebenen.Form> alle = e.formen(eintrag.id());
            Set<String> ids = je.getOrDefault(eintrag.id(), Set.of());
            List<Ebenen.Form> breit = null, an = new ArrayList<>(), anBreit = new ArrayList<>();
            for (int i = 0; i < alle.size() && !ids.isEmpty(); i++) {
                Ebenen.Form f = alle.get(i);
                if (ids.contains(Ebenen.id(f))) {
                    breit = breit == null ? new ArrayList<>(alle) : breit;
                    breit.set(i, breiter(f));
                    an.add(f);
                    anBreit.add(breit.get(i));
                }
            }
            karteServer.put(eintrag.id(), breit == null ? alle : List.copyOf(breit));
            karteAn.put(eintrag.id(), List.copyOf(anBreit));
            grenzen.put(eintrag.id(), eintrag.maxBlocksPerPixel());
            if (!an.isEmpty()) {
                minimapServer.put(eintrag.id(), List.copyOf(an));
            }
            List<Ebenen.Ort> orte = new ArrayList<>();
            for (Ebenen.Ort o : e.nadeln(eintrag.id())) {
                if (o.id() != null && nadeln.contains(new Anheftung(eintrag.id(), o.id()))) {
                    orte.add(o);
                }
            }
            orteServer.put(eintrag.id(), orte);
        }
        // Eigene Formen je Gewicht eine Liste, leicht zuerst: Formen zeichnet jede ganz, Fläche, Rand und Name, so liegt die schwerere
        // mit allem oben. Ohne Umstellen alle eigenen zusammen, alle 0 gibt die eine Liste wie vor mod#163; umgestellt je eigene Ebene.
        // Siehe docs/wegpunkte.md, „Gewicht“.
        // Siehe docs/vollbildkarte.md, „Reihe der Ebenen“.
        boolean jeEbene = reihe.umgestellt();
        Map<Integer, TreeMap<Integer, List<Ebenen.Form>>> karteEigen = new HashMap<>(), minimapEigen = new HashMap<>();
        IdentityHashMap<Ebenen.Form, Integer> gewichtJe = new IdentityHashMap<>();
        regionen.stream().filter(r -> r.angeheftet() && an(r.ebene()))
                .forEach(r -> liste(minimapEigen, jeEbene ? r.ebene() : BASE, 0).add(flaeche(r)));
        for (EigeneForm f : this.eigene) {
            if (!an(f.ebene())) {
                continue;
            }
            double[] p = kanten(f);
            Ebenen.Form form = form(f, p);
            Ebenen.Schrift name = schrift(f, p);
            int schicht = jeEbene ? f.ebene() : BASE;
            // Die Form, die gerade gezogen wird, zeichnet die Vollbildkarte selbst, wo sie an der Maus steht.
            if (!gezogen.contains(f)) {
                List<Ebenen.Form> aufKarte = liste(karteEigen, schicht, f.gewicht());
                aufKarte.add(f.angeheftet() ? breiter(form) : form);
                if (name != null) {
                    aufKarte.add(name);
                }
            }
            if (f.angeheftet()) {
                List<Ebenen.Form> aufMinimap = liste(minimapEigen, schicht, f.gewicht());
                aufMinimap.add(form);
                gewichtJe.put(form, f.gewicht());
                if (name != null) {
                    aufMinimap.add(name);
                }
            }
        }
        // Von unten nach oben: ohne Umstellen die vom Server nach order, darüber alle eigenen; umgestellt in der Reihe des Spielers.
        List<Ebenenreihe.Schicht> schichten = new ArrayList<>();
        if (jeEbene) {
            schichten.addAll(reihe.wirksam(e.alle().reversed(), ebenen.stream().map(EigeneEbene::id).toList()).reversed());
        } else {
            karteServer.keySet().forEach(id -> schichten.add(Ebenenreihe.Schicht.vomServer(id)));
            schichten.add(Ebenenreihe.Schicht.eigene(BASE));
        }
        List<List<Ebenen.Form>> karte = new ArrayList<>(), minimap = new ArrayList<>(), karteNurAn = new ArrayList<>();
        List<Double> karteGrenze = new ArrayList<>();
        List<Ebenen.Ort> orte = new ArrayList<>();
        eigeneAufMinimap = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Ebenenreihe.Schicht s : schichten) {
            if (s.server() != null) {
                if (karteServer.containsKey(s.server())) {
                    karte.add(karteServer.get(s.server()));
                    karteNurAn.add(karteAn.get(s.server()));
                    karteGrenze.add(grenzen.get(s.server()));
                    orte.addAll(orteServer.get(s.server()));
                }
                if (minimapServer.containsKey(s.server())) {
                    minimap.add(minimapServer.get(s.server()));
                }
                continue;
            }
            for (List<Ebenen.Form> l : karteEigen.getOrDefault(s.eigen(), new TreeMap<>()).values()) {
                karte.add(List.copyOf(l));
                karteNurAn.add(List.of());
                karteGrenze.add(0.0);
            }
            for (List<Ebenen.Form> l : minimapEigen.getOrDefault(s.eigen(), new TreeMap<>()).values()) {
                minimap.add(List.copyOf(l));
                eigeneAufMinimap.addAll(l);
            }
        }
        fuerNadeln = List.copyOf(orte);
        angeheftetOrte = Collections.newSetFromMap(new IdentityHashMap<>());
        angeheftetOrte.addAll(orte);
        gewichte = gewichtJe;
        fuerKarte = List.copyOf(karte);
        karteAngeheftet = List.copyOf(karteNurAn);
        karteGrenzen = karteGrenze.stream().mapToDouble(Double::doubleValue).toArray();
        fuerMinimap = List.copyOf(minimap);
    }

    /** Die Liste der eigenen Formen der Schicht {@code schicht} mit dem Gewicht {@code gewicht}, neu, wenn es sie noch nicht gibt. */
    private static List<Ebenen.Form> liste(Map<Integer, TreeMap<Integer, List<Ebenen.Form>>> je, int schicht, int gewicht) {
        return je.computeIfAbsent(schicht, k -> new TreeMap<>()).computeIfAbsent(gewicht, g -> new ArrayList<>());
    }

    /**
     * Die eigene Form als Form zum Zeichnen, durch die Mitten der Blöcke ihrer Ecken: eine Region als Fläche in ihrer
     * Farbe zu 25 % mit 1 Einheit Rand, eine Linie 2 Einheiten breit.
     */
    Ebenen.Form form(EigeneForm f) {
        return form(f, kanten(f));
    }

    /**
     * Wie {@link #form(EigeneForm)}, wie auf der Vollbildkarte, angeheftet breiter, durch die Punkte {@code p} statt der Ecken,
     * mit dem Namen; fürs Ziehen.
     */
    List<Ebenen.Form> aufKarte(EigeneForm f, double[] p) {
        Ebenen.Form form = form(f, p);
        form = f.angeheftet() ? breiter(form) : form;
        Ebenen.Schrift name = schrift(f, p);
        return name == null ? List.of(form) : List.of(form, name);
    }

    /**
     * Der Name der Form durch die Punkte {@code p} als Kartenschrift in den Vorgaben vom Server, eine Schrifthöhe neben dem
     * Griff, so deckt der Griff keinen Buchstaben: unter einer Region waagrecht, an einer Linie entlang auf ihrer Nordseite,
     * an einer Linie von Nord nach Süd auf ihrer Westseite. Die Höhe mal dem Faktor {@code schrift} der Form, bei einer Region
     * mit ihrer Biegung. Null ohne Namen. Siehe docs/wegpunkte.md, „Namen“.
     */
    static Ebenen.Schrift schrift(EigeneForm f, double[] p) {
        if (f.name() == null) {
            return null;
        }
        double[] pfad;
        float groesse;
        if (f.region()) {
            double[] b = box(p), g = griff(p, true);
            groesse = (float) (groesse(Math.min(b[2] - b[0], b[3] - b[1]) / 2) * f.schrift());
            pfad = new double[] {g[0], g[1] + groesse};
        } else {
            // Eine Linie hat genau zwei Ecken. Die Normale (dz, −dx) zeigt für dx > 0 nach Norden, für dx = 0 und dz < 0 nach Westen.
            double dx = p[2] - p[0], dz = p[3] - p[1], laenge = Math.hypot(dx, dz);
            groesse = (float) (groesse(laenge / 4) * f.schrift());
            double s = (dx > 0 || dx == 0 && dz < 0 ? 1 : -1) * groesse / laenge;
            pfad = new double[] {p[0] + s * dz, p[1] - s * dx, p[2] + s * dz, p[3] - s * dx};
        }
        return new Ebenen.Schrift(f.dimension(), f.name(), pfad, groesse, 0, Ebenen.SCHRIFTFARBE, Ebenen.KONTURFARBE, NAME_KONTUR, box(pfad),
                Math.toRadians(f.biegung()), f.minecraft());
    }

    /**
     * Wie hoch die Grossbuchstaben eines Namens stehen, in Blöcken: {@code d}, gekappt auf {@link #NAME_KLEINSTE} bis
     * {@link #NAME_GROESSE}. Für eine Region die Hälfte der kürzeren Seite ihres Kastens, für eine Linie ein Viertel ihrer Länge.
     */
    private static float groesse(double d) {
        return (float) Math.clamp(d, NAME_KLEINSTE, NAME_GROESSE);
    }

    /** Die Mitten der Blöcke der Ecken, x und z abwechselnd. */
    static double[] kanten(EigeneForm f) {
        double[] p = new double[2 * f.ecken().size()];
        for (int i = 0; i < f.ecken().size(); i++) {
            p[2 * i] = f.ecken().get(i).x();
            p[2 * i + 1] = f.ecken().get(i).z();
        }
        return p;
    }

    /**
     * Wo der Griff einer Form durch die Punkte {@code p} sitzt: bei einer Linie auf halber Länge, bei einer Region
     * im Schwerpunkt ihrer Fläche, ohne Fläche in der Mitte ihres Kastens. Siehe docs/wegpunkte.md, „Eigene Formen“.
     */
    static double[] griff(double[] p, boolean region) {
        int n = p.length / 2;
        if (!region) {
            double laenge = 0;
            for (int i = 1; i < n; i++) {
                laenge += Math.hypot(p[2 * i] - p[2 * i - 2], p[2 * i + 1] - p[2 * i - 1]);
            }
            double rest = laenge / 2;
            for (int i = 1; i < n; i++) {
                double s = Math.hypot(p[2 * i] - p[2 * i - 2], p[2 * i + 1] - p[2 * i - 1]);
                if (rest <= s && s > 0) {
                    double t = rest / s;
                    return new double[] {p[2 * i - 2] + t * (p[2 * i] - p[2 * i - 2]), p[2 * i - 1] + t * (p[2 * i + 1] - p[2 * i - 1])};
                }
                rest -= s;
            }
            return new double[] {p[0], p[1]};
        }
        double flaeche = 0, cx = 0, cz = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double k = p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1];
            flaeche += k;
            cx += (p[2 * i] + p[2 * j]) * k;
            cz += (p[2 * i + 1] + p[2 * j + 1]) * k;
        }
        if (Math.abs(flaeche) < 1e-9) {
            double[] b = box(p);
            return new double[] {(b[0] + b[2]) / 2, (b[1] + b[3]) / 2};
        }
        return new double[] {cx / (3 * flaeche), cz / (3 * flaeche)};
    }

    private Ebenen.Form form(EigeneForm f, double[] p) {
        String dimension = f.dimension();
        int farbe = f.argb();
        double[] box = box(p);
        if (!f.region()) {
            return new Ebenen.Linie(dimension, p, new Ebenen.Rand(farbe, 2, 0, 0), box);
        }
        return new Ebenen.Flaeche(dimension, farbe & 0x00FFFFFF | 0x40000000, Trapeze.von(List.of(p)), new Ebenen.Rand(farbe, 1, 0, 0),
                List.of(p), box, null);
    }

    private static double[] box(double[] p) {
        double[] b = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int i = 0; i < p.length; i += 2) {
            b[0] = Math.min(b[0], p[i]);
            b[1] = Math.min(b[1], p[i + 1]);
            b[2] = Math.max(b[2], p[i]);
            b[3] = Math.max(b[3], p[i + 1]);
        }
        return b;
    }

    /** Die Form mit einem Rand {@link #BREITER} breiter; ohne Rand einer in der Füllung ohne Alpha, wie die Vorgabe des Formats. */
    static Ebenen.Form breiter(Ebenen.Form f) {
        return switch (f) {
            case Ebenen.Linie l -> new Ebenen.Linie(l.dimension(), l.punkte(), breiter(l.rand(), 0), l.box());
            case Ebenen.Flaeche fl -> new Ebenen.Flaeche(fl.dimension(), fl.fuellung(), fl.trapeze(), breiter(fl.rand(), fl.fuellung()), fl.ringe(),
                    fl.box(), fl.id());
            case Ebenen.Kreis k -> new Ebenen.Kreis(k.dimension(), k.x(), k.z(), k.radius(), k.fuellung(), breiter(k.rand(), k.fuellung()), k.id());
            default -> f;
        };
    }

    private static Ebenen.Rand breiter(Ebenen.Rand r, int fuellung) {
        return r == null ? new Ebenen.Rand(0xFF000000 | fuellung, BREITER, 0, 0)
                : new Ebenen.Rand(r.farbe(), Math.min(r.breite() + BREITER, Ebenen.MAX_BREITE), r.strich(), r.luecke());
    }

    /** Die eigene Region als Fläche, wie die Vollbildkarte sie zeichnet: in ihrer Farbe zu 25 %, 1 Einheit Rand deckend. */
    static Ebenen.Flaeche flaeche(Region r) {
        double[] ring = {r.x0(), r.z0(), r.x1() + 1, r.z0(), r.x1() + 1, r.z1() + 1, r.x0(), r.z1() + 1};
        int farbe = FARBEN[r.farbe()];
        return new Ebenen.Flaeche(r.dimension(), farbe & 0x00FFFFFF | 0x40000000, Trapeze.von(List.of(ring)), new Ebenen.Rand(farbe, 1, 0, 0),
                List.of(ring), new double[] {r.x0(), r.z0(), r.x1() + 1, r.z1() + 1}, null);
    }

    boolean angeheftet(UUID uuid) {
        return spieler.contains(uuid);
    }

    /** Heftet den Mitspieler an die Minimap oder löst ihn. */
    void umschalten(UUID uuid) {
        if (!spieler.remove(uuid)) {
            spieler.add(uuid);
        }
        schreibe();
    }

    private int finde(String dimension, int x, int z) {
        for (int i = 0; i < punkte.size(); i++) {
            Punkt p = punkte.get(i);
            if (p.x() == x && p.z() == z && p.dimension().equals(dimension)) {
                return i;
            }
        }
        return -1;
    }

    /** Nach jeder Änderung; ordnet erst die Bünde, dann über eine Zwischendatei, so liegt nie eine halbe Datei da. */
    private void schreibe() {
        ordneBuende();
        stand++;
        if (datei == null) {
            return;
        }
        try {
            Files.createDirectories(datei.getParent());
            Path tmp = datei.resolveSibling("wegpunkte.json.tmp");
            Files.writeString(tmp, json().toString(), StandardCharsets.UTF_8);
            Files.move(tmp, datei, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn("Heroic Map: Wegpunkte {} nicht geschrieben", datei, e);
        }
    }
}
