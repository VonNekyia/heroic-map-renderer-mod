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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
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
     * docs/wegpunkte.md, „Todespunkt“.
     */
    record Punkt(int id, String dimension, int x, int z, int farbe, boolean angeheftet, boolean tod, int ebene) {
    }

    /** Eine Ecke einer eigenen Form auf dem Block (x, z). */
    record Ecke(int x, int z) {
    }

    /**
     * Eine eigene Form, ein Marker mit eigenen Ecken in Reihenfolge: zwei sind eine Linie, drei und mehr eine
     * Region. Sie verweist auf keinen Wegpunkt. {@code name} wie aus {@link #name}, null ohne; {@code hex} eine eigene Farbe
     * als RGB, {@link #KEIN_HEX} ohne, dann gilt {@code farbe}; {@code ebene} die id ihrer eigenen Ebene. Siehe docs/wegpunkte.md,
     * „Eigene Formen“.
     */
    record EigeneForm(String dimension, List<Ecke> ecken, int farbe, boolean angeheftet, String name, int hex, int ebene, double schrift,
            int biegung, int gewicht) {

        /** Eine Linie biegt ihren Namen nicht; er folgt ihr schon. Siehe docs/wegpunkte.md, „Namen“. */
        EigeneForm {
            ecken = List.copyOf(ecken);
            biegung = ecken.size() >= 3 ? biegung : 0;
        }

        /** Mit Schrift 1, ohne Biegung und mit Gewicht 0, wie vor mod#150. */
        EigeneForm(String dimension, List<Ecke> ecken, int farbe, boolean angeheftet, String name, int hex, int ebene) {
            this(dimension, ecken, farbe, angeheftet, name, hex, ebene, 1, 0, 0);
        }

        /** Mit Gewicht 0, wie vor mod#163. */
        EigeneForm(String dimension, List<Ecke> ecken, int farbe, boolean angeheftet, String name, int hex, int ebene, double schrift,
                int biegung) {
            this(dimension, ecken, farbe, angeheftet, name, hex, ebene, schrift, biegung, 0);
        }

        /** Die Farbe der Form, deckend: die eigene, sonst die aus {@link #FARBEN}. Siehe docs/wegpunkte.md, „Farbe“. */
        int argb() {
            return hex == KEIN_HEX ? FARBEN[farbe] : 0xFF000000 | hex;
        }

        boolean region() {
            return ecken.size() >= 3;
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
    private EigeneForm gezogen, gebautGezogen;
    private List<List<Ebenen.Form>> fuerKarte = List.of(), fuerMinimap = List.of();
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
                        Math.floorMod(o.get("farbe").getAsInt(), FARBEN.length), o.get("minimap").getAsBoolean(), false, ebeneAus(o));
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
                Punkt p = new Punkt(0, o.get("dimension").getAsString(), o.get("x").getAsInt(), o.get("z").getAsInt(), 0,
                        o.get("minimap").getAsBoolean(), true, BASE);
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
                    for (JsonElement e : o.getAsJsonArray("ecken")) {
                        ecken.add(new Ecke(e.getAsJsonArray().get(0).getAsInt(), e.getAsJsonArray().get(1).getAsInt()));
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
                    // Ein Name, der kein Text ist, fehlt; die Form bleibt.
                    String name = o.has("name") && o.get("name").isJsonPrimitive() ? name(o.get("name").getAsString()) : null;
                    int hex = o.has("hex") && o.get("hex").isJsonPrimitive() ? hex(o.get("hex").getAsString()) : KEIN_HEX;
                    // Schrift und Biegung ausserhalb der Grenzen oder ohne Zahl: die Vorgabe; die Form bleibt.
                    double schrift = o.get("schrift") instanceof JsonPrimitive s && s.isNumber() && schriftGilt(s.getAsDouble())
                            ? schrift(s.getAsDouble()) : 1;
                    int biegung = o.get("biegung") instanceof JsonPrimitive b && b.isNumber() && Math.abs(b.getAsDouble()) <= MAX_BIEGUNG
                            ? biegung(b.getAsDouble()) : 0;
                    int gewicht = o.get("gewicht") instanceof JsonPrimitive g && g.isNumber() && gewichtGilt(g.getAsDouble()) ? g.getAsInt() : 0;
                    eigene.add(new EigeneForm(dimension, gueltig, Math.floorMod(o.get("farbe").getAsInt(), FARBEN.length),
                            o.get("minimap").getAsBoolean() && angeheftet() < MAX_ANGEHEFTET, name, hex, ebeneAus(o), schrift, biegung, gewicht));
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
                tode.add(tod);
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
                punkte.add(new Punkt(naechsteId++, o.dimension(), o.x(), o.z(), o.farbe(), false, false, id));
                n++;
            }
        }
        for (EbenenCode.Form f : inhalt.formen()) {
            eigene.add(new EigeneForm(f.dimension(), f.ecken(), f.farbe(), false, f.name(), f.hex(), id, f.schrift(), f.biegung(), f.gewicht()));
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
        return new Punkt(p.id(), p.dimension(), p.x(), p.z(), p.farbe(), p.angeheftet(), p.tod(), ebene);
    }

    private static Region mitEbene(Region r, int ebene) {
        return new Region(r.dimension(), r.x0(), r.z0(), r.x1(), r.z1(), r.farbe(), r.angeheftet(), ebene);
    }

    private static EigeneForm mitEbene(EigeneForm f, int ebene) {
        return new EigeneForm(f.dimension(), f.ecken(), f.farbe(), f.angeheftet(), f.name(), f.hex(), ebene, f.schrift(), f.biegung(), f.gewicht());
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
        punkte.removeIf(p -> p.tod() && p.dimension().equals(dimension) && p.x() == x && p.z() == z);
        boolean frei = finde(dimension, x, z) < 0;
        if (frei) {
            Punkt voriger = tod();
            if (voriger != null && voriger.angeheftet()) {
                umschalten(voriger);
            }
            punkte.add(new Punkt(naechsteId++, dimension, x, z, 0, true, true, BASE));
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
     * fällt die Form weg; mehr als {@link #MAX_PUNKTE_FORM} nimmt sie nicht. False, wenn sich nichts änderte.
     */
    boolean ersetze(EigeneForm f, List<Ecke> ecken) {
        int i = eigene.indexOf(f);
        if (i < 0 || ecken.size() > MAX_PUNKTE_FORM) {
            return false;
        }
        List<Ecke> gueltig = gueltig(ecken);
        if (gueltig == null) {
            eigene.remove(i);
        } else {
            eigene.set(i, new EigeneForm(f.dimension(), gueltig, f.farbe(), f.angeheftet(), f.name(), f.hex(), f.ebene(), f.schrift(),
                    f.biegung(), f.gewicht()));
        }
        schreibe();
        return true;
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
                f.biegung(), f.gewicht()));
        schreibe();
        return true;
    }

    /** Gibt der eigenen Form {@code f} den Namen {@code name}, bereinigt wie in {@link #name}; was leer bleibt, nimmt ihn weg. */
    void benenne(EigeneForm f, String name) {
        int i = eigene.indexOf(f);
        if (i >= 0) {
            eigene.set(i, new EigeneForm(f.dimension(), f.ecken(), f.farbe(), f.angeheftet(), name(name), f.hex(), f.ebene(), f.schrift(),
                    f.biegung(), f.gewicht()));
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
                gewicht);
        eigene.set(i, neu);
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
            eigene.set(i, new EigeneForm(f.dimension(), f.ecken(), f.farbe(), f.angeheftet(), f.name(), hex, f.ebene(), f.schrift(), f.biegung(), f.gewicht()));
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
     * Die Ecken ohne einen Block zweimal hintereinander; null, wenn weniger als zwei bleiben oder es mehr als
     * {@link #MAX_PUNKTE_FORM} sind.
     */
    static List<Ecke> gueltig(List<Ecke> ecken) {
        List<Ecke> aus = new ArrayList<>();
        for (Ecke e : ecken) {
            if (aus.isEmpty() || !aus.getLast().equals(e)) {
                aus.add(e);
            }
        }
        // Eine letzte Ecke gleich der ersten schliesst nur, sie zählt nicht doppelt.
        if (aus.size() > 2 && aus.getLast().equals(aus.getFirst())) {
            aus.removeLast();
        }
        return aus.size() < 2 || aus.size() > MAX_PUNKTE_FORM ? null : aus;
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
                punkte.set(i, new Punkt(naechsteId++, p.dimension(), p.x(), p.z(), p.farbe(), p.angeheftet(), p.tod(), p.ebene()));
            }
        }
    }

    /**
     * Legt den Wegpunkt auf den Block (x, z) seiner Dimension, mit Farbe und Anheften; false, wenn es ihn
     * nicht gibt oder dort schon einer liegt, dann bleibt alles.
     */
    boolean verschiebe(Punkt p, int x, int z) {
        int i = finde(p.dimension(), p.x(), p.z());
        if (i < 0 || finde(p.dimension(), x, z) >= 0) {
            return false;
        }
        Punkt alt = punkte.get(i);
        punkte.set(i, new Punkt(alt.id(), alt.dimension(), x, z, alt.farbe(), alt.angeheftet(), alt.tod(), alt.ebene()));
        schreibe();
        return true;
    }

    /** Heftet den Wegpunkt an die Minimap oder löst ihn. */
    void umschalten(Punkt p) {
        int i = finde(p.dimension(), p.x(), p.z());
        if (i >= 0) {
            Punkt alt = punkte.get(i);
            punkte.set(i, new Punkt(alt.id(), alt.dimension(), alt.x(), alt.z(), alt.farbe(), !alt.angeheftet(), alt.tod(), alt.ebene()));
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

    /** Lässt die eigene Form {@code f} aus den Formen der Vollbildkarte weg, solange sie gezogen wird; null zeigt wieder alle. */
    void ziehe(EigeneForm f) {
        gezogen = f;
    }

    private void baue(Ebenen e) {
        if (e == gebautAus && e.stand() == gebautEbenen && stand == gebautStand && gezogen == gebautGezogen) {
            return;
        }
        gebautAus = e;
        gebautEbenen = e.stand();
        gebautStand = stand;
        gebautGezogen = gezogen;
        Map<String, Set<String>> je = new HashMap<>();
        formen.forEach(a -> je.computeIfAbsent(a.ebene(), k -> new HashSet<>()).add(a.id()));
        List<List<Ebenen.Form>> karte = new ArrayList<>(), minimap = new ArrayList<>();
        for (Ebenen.Eintrag eintrag : e.sichtbar()) {
            List<Ebenen.Form> alle = e.formen(eintrag.id());
            Set<String> ids = je.getOrDefault(eintrag.id(), Set.of());
            List<Ebenen.Form> breit = null, an = new ArrayList<>();
            for (int i = 0; i < alle.size() && !ids.isEmpty(); i++) {
                Ebenen.Form f = alle.get(i);
                if (ids.contains(Ebenen.id(f))) {
                    breit = breit == null ? new ArrayList<>(alle) : breit;
                    breit.set(i, breiter(f));
                    an.add(f);
                }
            }
            karte.add(breit == null ? alle : List.copyOf(breit));
            if (!an.isEmpty()) {
                minimap.add(List.copyOf(an));
            }
        }
        List<Ebenen.Ort> orte = new ArrayList<>();
        for (Ebenen.Eintrag eintrag : e.sichtbar()) {
            for (Ebenen.Ort o : e.nadeln(eintrag.id())) {
                if (o.id() != null && nadeln.contains(new Anheftung(eintrag.id(), o.id()))) {
                    orte.add(o);
                }
            }
        }
        fuerNadeln = List.copyOf(orte);
        angeheftetOrte = Collections.newSetFromMap(new IdentityHashMap<>());
        angeheftetOrte.addAll(orte);
        // Je Gewicht eine Liste, leicht zuerst: Formen zeichnet jede ganz, Fläche, Rand und Name, so liegt die schwerere mit allem
        // oben. Alle 0 gibt die eine Liste wie vor mod#163. Siehe docs/wegpunkte.md, „Gewicht“.
        TreeMap<Integer, List<Ebenen.Form>> aufKarteJe = new TreeMap<>(), aufMinimapJe = new TreeMap<>();
        IdentityHashMap<Ebenen.Form, Integer> gewichtJe = new IdentityHashMap<>();
        regionen.stream().filter(r -> r.angeheftet() && an(r.ebene())).map(Wegpunkte::flaeche)
                .forEach(fl -> aufMinimapJe.computeIfAbsent(0, g -> new ArrayList<>()).add(fl));
        for (EigeneForm f : this.eigene) {
            if (!an(f.ebene())) {
                continue;
            }
            double[] p = mitten(f);
            Ebenen.Form form = form(f, p);
            Ebenen.Schrift name = schrift(f, p);
            List<Ebenen.Form> aufKarte = aufKarteJe.computeIfAbsent(f.gewicht(), g -> new ArrayList<>());
            List<Ebenen.Form> aufMinimap = aufMinimapJe.computeIfAbsent(f.gewicht(), g -> new ArrayList<>());
            // Die Form, die gerade gezogen wird, zeichnet die Vollbildkarte selbst, wo sie an der Maus steht.
            if (f != gezogen) {
                aufKarte.add(f.angeheftet() ? breiter(form) : form);
                if (name != null) {
                    aufKarte.add(name);
                }
            }
            if (f.angeheftet()) {
                aufMinimap.add(form);
                gewichtJe.put(form, f.gewicht());
                if (name != null) {
                    aufMinimap.add(name);
                }
            }
        }
        eigeneAufMinimap = Collections.newSetFromMap(new IdentityHashMap<>());
        for (List<Ebenen.Form> l : aufKarteJe.values()) {
            if (!l.isEmpty()) {
                karte.add(List.copyOf(l));
            }
        }
        for (List<Ebenen.Form> l : aufMinimapJe.values()) {
            if (!l.isEmpty()) {
                minimap.add(List.copyOf(l));
                eigeneAufMinimap.addAll(l);
            }
        }
        gewichte = gewichtJe;
        fuerKarte = List.copyOf(karte);
        fuerMinimap = List.copyOf(minimap);
    }

    /**
     * Die eigene Form als Form zum Zeichnen, durch die Mitten der Blöcke ihrer Ecken: eine Region als Fläche in ihrer
     * Farbe zu 25 % mit 1 Einheit Rand, eine Linie 2 Einheiten breit.
     */
    Ebenen.Form form(EigeneForm f) {
        return form(f, mitten(f));
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
                Math.toRadians(f.biegung()));
    }

    /**
     * Wie hoch die Grossbuchstaben eines Namens stehen, in Blöcken: {@code d}, gekappt auf {@link #NAME_KLEINSTE} bis
     * {@link #NAME_GROESSE}. Für eine Region die Hälfte der kürzeren Seite ihres Kastens, für eine Linie ein Viertel ihrer Länge.
     */
    private static float groesse(double d) {
        return (float) Math.clamp(d, NAME_KLEINSTE, NAME_GROESSE);
    }

    /** Die Mitten der Blöcke der Ecken, x und z abwechselnd. */
    static double[] mitten(EigeneForm f) {
        double[] p = new double[2 * f.ecken().size()];
        for (int i = 0; i < f.ecken().size(); i++) {
            p[2 * i] = f.ecken().get(i).x() + 0.5;
            p[2 * i + 1] = f.ecken().get(i).z() + 0.5;
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

    /** Nach jeder Änderung; über eine Zwischendatei, so liegt nie eine halbe Datei da. */
    private void schreibe() {
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
