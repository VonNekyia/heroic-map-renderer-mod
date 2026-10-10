package com.nekyia.heroicmap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;

/**
 * Die Wegpunkte, eigenen Formen und alten Rechtecke des Spielers und was er auf der Minimap angeheftet hat,
 * Wegpunkte, Mitspieler, eigene Formen und Rechtecke und Flächen, Kreise, Nadeln und Banner vom Server. Je Welt in
 * {@code wegpunkte.json} im Ordner der Welt; im Einzelspieler nur im Speicher. Nur der
 * Render-Thread liest und ändert sie. Siehe docs/wegpunkte.md.
 */
final class Wegpunkte {

    static final Wegpunkte INSTANZ = new Wegpunkte();
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Die Farben der Wegpunkte, der Reihe nach. */
    static final int[] FARBEN = {0xFFE04040, 0xFF4090F0, 0xFF40C040, 0xFFF0C020, 0xFFC050F0, 0xFFF08020, 0xFF40D0D0, 0xFFF070B0};

    /**
     * Ein Wegpunkt auf dem Block (x, z), mit einer festen {@code id}, auf die eigene Formen verweisen;
     * {@code farbe} ist ein Index in {@link #FARBEN}.
     */
    record Punkt(int id, String dimension, int x, int z, int farbe, boolean angeheftet) {
    }

    /**
     * Eine eigene Form aus Wegpunkten, über ihre ids in Reihenfolge: zwei sind eine Linie, drei und mehr
     * eine Region. Verschiebt der Spieler einen Wegpunkt, geht die Form mit. Siehe docs/wegpunkte.md, „Formen aus Wegpunkten“.
     */
    record EigeneForm(List<Integer> punkte, int farbe, boolean angeheftet) {

        EigeneForm {
            punkte = List.copyOf(punkte);
        }

        boolean region() {
            return punkte.size() >= 3;
        }
    }

    /**
     * Ein altes Rechteck aus „Region von hier“, die Blöcke von (x0, z0) bis (x1, z1) samt beiden, x0 ≤ x1 und
     * z0 ≤ z1; {@code farbe} ist ein Index in {@link #FARBEN}. Siehe docs/wegpunkte.md, „Regionen“.
     */
    record Region(String dimension, int x0, int z0, int x1, int z1, int farbe, boolean angeheftet) {

        boolean enthaelt(String d, int x, int z) {
            return x >= x0 && x <= x1 && z >= z0 && z <= z1 && dimension.equals(d);
        }
    }

    /** Ein angeheftetes Objekt vom Server, über die Kennungen von Ebene und Objekt; hält so über neue {@code version}s. */
    record Anheftung(String ebene, String id) {
    }

    /** So viele alte Rechtecke je Welt liest der Mod. */
    static final int MAX_REGIONEN = 256;
    /** So viele eigene Formen aus Wegpunkten je Welt, und so viele Punkte je Form. */
    static final int MAX_EIGENE_FORMEN = 256, MAX_PUNKTE_FORM = 64;
    /** So viele Regionen und Kreise je Welt angeheftet, eigene und vom Server zusammen; darüber heftet der Mod keine an. */
    static final int MAX_ANGEHEFTET = 64;
    /** So viele Nadeln und Banner je Welt angeheftet, eine eigene Grenze; darüber heftet der Mod keine an. */
    static final int MAX_NADELN_ANGEHEFTET = 64;
    /** So viele Einheiten breiter ist auf der Vollbildkarte der Rand angehefteter Regionen und Kreise. */
    static final float BREITER = 2;

    private final List<Punkt> punkte = new ArrayList<>();
    private final List<Region> regionen = new ArrayList<>();
    private final List<EigeneForm> eigene = new ArrayList<>();
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
    private List<List<Ebenen.Form>> fuerKarte = List.of(), fuerMinimap = List.of();
    private List<Ebenen.Ort> fuerNadeln = List.of();
    /** Dieselben Nadeln und Banner nach Identität, so prüft die Vollbildkarte je Nadel ohne Allokation. */
    private Set<Ebenen.Ort> angeheftetOrte = Set.of();
    /** Die Datei, oder null im Einzelspieler. */
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
        for (JsonElement element : liste(json, "wegpunkte")) {
            try {
                JsonObject o = element.getAsJsonObject();
                // Eine Datei von vor mod#79 nennt keine id; dann vergibt der Mod sie unten.
                int id = o.has("id") ? o.get("id").getAsInt() : 0;
                Punkt p = new Punkt(id, o.get("dimension").getAsString(), o.get("x").getAsInt(), o.get("z").getAsInt(),
                        Math.floorMod(o.get("farbe").getAsInt(), FARBEN.length), o.get("minimap").getAsBoolean());
                if (finde(p.dimension(), p.x(), p.z()) < 0) {
                    punkte.add(p);
                }
            } catch (RuntimeException kaputt) {
                // Nur dieser Eintrag fällt weg.
            }
        }
        vergibIds();
        for (JsonElement element : liste(json, "regionen")) {
            try {
                JsonObject o = element.getAsJsonObject();
                Region r = region(o.get("dimension").getAsString(), o.get("x0").getAsInt(), o.get("z0").getAsInt(), o.get("x1").getAsInt(),
                        o.get("z1").getAsInt(), Math.floorMod(o.get("farbe").getAsInt(), FARBEN.length),
                        o.get("minimap").getAsBoolean() && angeheftet() < MAX_ANGEHEFTET);
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
        // Eine Datei von vor mod#79 hat keine eigenen Formen. Verweise auf Wegpunkte, die es nicht gibt, fallen weg.
        for (JsonElement element : liste(json, "eigene_formen")) {
            try {
                JsonObject o = element.getAsJsonObject();
                List<Integer> ids = new ArrayList<>();
                for (JsonElement id : o.getAsJsonArray("punkte")) {
                    ids.add(id.getAsInt());
                }
                List<Integer> gueltig = gueltig(ids);
                if (gueltig != null && eigene.size() < MAX_EIGENE_FORMEN) {
                    eigene.add(new EigeneForm(gueltig, Math.floorMod(o.get("farbe").getAsInt(), FARBEN.length),
                            o.get("minimap").getAsBoolean() && angeheftet() < MAX_ANGEHEFTET));
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

    private static JsonArray liste(JsonObject json, String name) {
        return json.has(name) && json.get(name).isJsonArray() ? json.getAsJsonArray(name) : new JsonArray();
    }

    JsonObject json() {
        JsonArray liste = new JsonArray();
        for (Punkt p : punkte) {
            JsonObject o = new JsonObject();
            o.addProperty("id", p.id());
            o.addProperty("dimension", p.dimension());
            o.addProperty("x", p.x());
            o.addProperty("z", p.z());
            o.addProperty("farbe", p.farbe());
            o.addProperty("minimap", p.angeheftet());
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
            rechtecke.add(o);
        }
        JsonArray uuids = new JsonArray();
        spieler.forEach(u -> uuids.add(u.toString()));
        JsonObject json = new JsonObject();
        json.add("wegpunkte", liste);
        json.add("regionen", rechtecke);
        json.add("spieler", uuids);
        json.add("formen", json(formen));
        json.add("nadeln", json(nadeln));
        JsonArray zuege = new JsonArray();
        for (EigeneForm f : eigene) {
            JsonObject o = new JsonObject();
            JsonArray ids = new JsonArray();
            f.punkte().forEach(ids::add);
            o.add("punkte", ids);
            o.addProperty("farbe", f.farbe());
            o.addProperty("minimap", f.angeheftet());
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
        naechsteId = 1;
        spieler.clear();
        formen.clear();
        nadeln.clear();
        stand++;
        datei = null;
        geladen = false;
    }

    List<Punkt> punkte() {
        return Collections.unmodifiableList(punkte);
    }

    /** Setzt einen Wegpunkt auf den Block; steht dort schon einer, bleibt er. */
    void setze(String dimension, int x, int z) {
        if (finde(dimension, x, z) < 0) {
            punkte.add(new Punkt(naechsteId++, dimension, x, z, farbe(dimension), false));
            schreibe();
        }
    }

    List<Region> regionen() {
        return Collections.unmodifiableList(regionen);
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
     * Nur noch Tests setzen so ein altes Rechteck; der Spieler baut Formen aus Wegpunkten.
     */
    void setze(String dimension, int ax, int az, int bx, int bz) {
        Region r = region(dimension, ax, az, bx, bz, farbe(dimension), false);
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

    private static Region region(String dimension, int ax, int az, int bx, int bz, int farbe, boolean angeheftet) {
        return new Region(dimension, Math.min(ax, bx), Math.min(az, bz), Math.max(ax, bx), Math.max(az, bz), farbe, angeheftet);
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
            if (p.dimension().equals(dimension)) {
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
            if (dimension.equals(dimension(e))) {
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

    /** Löscht den Wegpunkt; aus eigenen Formen fällt er heraus, eine Form mit weniger als zwei Punkten verschwindet. */
    void loesche(Punkt p) {
        int i = finde(p.dimension(), p.x(), p.z());
        if (i >= 0) {
            int id = punkte.remove(i).id();
            for (int k = eigene.size() - 1; k >= 0; k--) {
                EigeneForm f = eigene.get(k);
                if (f.punkte().contains(id)) {
                    List<Integer> rest = gueltig(f.punkte().stream().filter(x -> x != id).toList());
                    if (rest == null) {
                        eigene.remove(k);
                    } else {
                        eigene.set(k, new EigeneForm(rest, f.farbe(), f.angeheftet()));
                    }
                }
            }
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

    /** Die Dimension einer eigenen Form, die ihres ersten Punkts. */
    String dimension(EigeneForm f) {
        Punkt p = punkt(f.punkte().getFirst());
        return p == null ? null : p.dimension();
    }

    /**
     * Setzt eine eigene Form aus den Wegpunkten {@code ids} in dieser Reihenfolge; false, wenn es weniger
     * als zwei verschiedene sind, mehr als {@link #MAX_PUNKTE_FORM}, sie nicht in einer Dimension liegen oder
     * es schon {@link #MAX_EIGENE_FORMEN} gibt.
     */
    boolean setzeForm(List<Integer> ids) {
        // Der erste Punkt am Ende schliesst nur die Region.
        List<Integer> wunsch = ids.size() > 2 && ids.getLast().equals(ids.getFirst()) ? ids.subList(0, ids.size() - 1) : ids;
        List<Integer> gueltig = gueltig(wunsch);
        if (gueltig == null || gueltig.size() != wunsch.size() || eigene.size() >= MAX_EIGENE_FORMEN) {
            return false;
        }
        eigene.add(new EigeneForm(gueltig, farbe(punkt(gueltig.getFirst()).dimension()), false));
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
        eigene.set(i, new EigeneForm(f.punkte(), f.farbe(), !f.angeheftet()));
        schreibe();
        return true;
    }

    /**
     * Die ids, die es als Wegpunkte gibt, alle in einer Dimension, ohne einen Punkt zweimal hintereinander
     * und höchstens {@link #MAX_PUNKTE_FORM}; null, wenn weniger als zwei bleiben.
     */
    private List<Integer> gueltig(List<Integer> ids) {
        List<Integer> aus = new ArrayList<>();
        String dimension = null;
        for (int id : ids) {
            Punkt p = punkt(id);
            if (p == null || dimension != null && !dimension.equals(p.dimension()) || !aus.isEmpty() && aus.getLast() == id) {
                continue;
            }
            dimension = p.dimension();
            aus.add(id);
        }
        // Ein letzter Punkt gleich dem ersten schliesst nur, er zählt nicht doppelt.
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
                punkte.set(i, new Punkt(naechsteId++, p.dimension(), p.x(), p.z(), p.farbe(), p.angeheftet()));
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
        punkte.set(i, new Punkt(alt.id(), alt.dimension(), x, z, alt.farbe(), alt.angeheftet()));
        schreibe();
        return true;
    }

    /** Heftet den Wegpunkt an die Minimap oder löst ihn. */
    void umschalten(Punkt p) {
        int i = finde(p.dimension(), p.x(), p.z());
        if (i >= 0) {
            Punkt alt = punkte.get(i);
            punkte.set(i, new Punkt(alt.id(), alt.dimension(), alt.x(), alt.z(), alt.farbe(), !alt.angeheftet()));
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
        regionen.set(i, new Region(alt.dimension(), alt.x0(), alt.z0(), alt.x1(), alt.z1(), alt.farbe(), !alt.angeheftet()));
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

    boolean nadelAngeheftet(String ebene, String id) {
        return nadeln.contains(new Anheftung(ebene, id));
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

    private void baue(Ebenen e) {
        if (e == gebautAus && e.stand() == gebautEbenen && stand == gebautStand) {
            return;
        }
        gebautAus = e;
        gebautEbenen = e.stand();
        gebautStand = stand;
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
        List<Ebenen.Form> aufKarte = new ArrayList<>(), aufMinimap = new ArrayList<>();
        regionen.stream().filter(Region::angeheftet).map(Wegpunkte::flaeche).forEach(aufMinimap::add);
        for (EigeneForm f : this.eigene) {
            Ebenen.Form form = form(f);
            if (form != null) {
                aufKarte.add(f.angeheftet() ? breiter(form) : form);
                if (f.angeheftet()) {
                    aufMinimap.add(form);
                }
            }
        }
        if (!aufKarte.isEmpty()) {
            karte.add(List.copyOf(aufKarte));
        }
        if (!aufMinimap.isEmpty()) {
            minimap.add(List.copyOf(aufMinimap));
        }
        fuerKarte = List.copyOf(karte);
        fuerMinimap = List.copyOf(minimap);
    }

    /**
     * Die eigene Form als Form zum Zeichnen, an der Mitte ihrer Wegpunkte: eine Region als Fläche in ihrer
     * Farbe zu 25 % mit 1 Einheit Rand, eine Linie 2 Einheiten breit; null, wenn ihre Punkte fehlen.
     */
    Ebenen.Form form(EigeneForm f) {
        double[] p = new double[2 * f.punkte().size()];
        String dimension = null;
        for (int i = 0; i < f.punkte().size(); i++) {
            Punkt q = punkt(f.punkte().get(i));
            if (q == null) {
                return null;
            }
            dimension = q.dimension();
            p[2 * i] = q.x() + 0.5;
            p[2 * i + 1] = q.z() + 0.5;
        }
        int farbe = FARBEN[f.farbe()];
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
