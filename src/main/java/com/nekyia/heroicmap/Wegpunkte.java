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
import java.text.Normalizer;
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
import net.minecraft.ChatFormatting;
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

    /** Ein Wegpunkt auf dem Block (x, z), mit einer festen {@code id}; {@code farbe} ist ein Index in {@link #FARBEN}. */
    record Punkt(int id, String dimension, int x, int z, int farbe, boolean angeheftet) {
    }

    /** Eine Ecke einer eigenen Form auf dem Block (x, z). */
    record Ecke(int x, int z) {
    }

    /**
     * Eine eigene Form, ein Marker mit eigenen Ecken in Reihenfolge: zwei sind eine Linie, drei und mehr eine
     * Region. Sie verweist auf keinen Wegpunkt. {@code name} wie aus {@link #name}, null ohne. Siehe docs/wegpunkte.md,
     * „Eigene Formen“.
     */
    record EigeneForm(String dimension, List<Ecke> ecken, int farbe, boolean angeheftet, String name) {

        EigeneForm {
            ecken = List.copyOf(ecken);
        }

        boolean region() {
            return ecken.size() >= 3;
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
    /** So viele eigene Formen je Welt, und so viele Ecken je Form. */
    static final int MAX_EIGENE_FORMEN = 256, MAX_PUNKTE_FORM = 64;
    /** So viele Zeichen hat der Name einer eigenen Form höchstens. */
    static final int MAX_NAME = 32;
    /**
     * Der Name einer eigenen Form als Kartenschrift: die Grossbuchstaben so hoch in Blöcken wie die Vorgabe vom Server, die
     * Kontur in Einheiten des GUI wie beim Namen einer Nadel. Siehe docs/wegpunkte.md, „Namen“.
     */
    static final float NAME_GROESSE = 16, NAME_KONTUR = 1.25f;
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
    private List<Ebenen.Ort> fuerNadeln = List.of();
    /** Dieselben Nadeln und Banner nach Identität, so prüft die Vollbildkarte je Nadel ohne Allokation. */
    private Set<Ebenen.Ort> angeheftetOrte = Set.of();
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
                    eigene.add(new EigeneForm(dimension, gueltig, Math.floorMod(o.get("farbe").getAsInt(), FARBEN.length),
                            o.get("minimap").getAsBoolean() && angeheftet() < MAX_ANGEHEFTET, name));
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
        return punkteSicht;
    }

    /** Setzt einen Wegpunkt auf den Block; steht dort schon einer, bleibt er. */
    void setze(String dimension, int x, int z) {
        if (finde(dimension, x, z) < 0) {
            punkte.add(new Punkt(naechsteId++, dimension, x, z, farbe(dimension), false));
            schreibe();
        }
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
        eigene.add(new EigeneForm(dimension, gueltig, farbe(dimension), false, null));
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
            eigene.set(i, new EigeneForm(f.dimension(), gueltig, f.farbe(), f.angeheftet(), f.name()));
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
        eigene.set(i, new EigeneForm(f.dimension(), f.ecken(), f.farbe(), !f.angeheftet(), f.name()));
        schreibe();
        return true;
    }

    /** Gibt der eigenen Form {@code f} den Namen {@code name}, bereinigt wie in {@link #name}; was leer bleibt, nimmt ihn weg. */
    void benenne(EigeneForm f, String name) {
        int i = eigene.indexOf(f);
        if (i >= 0) {
            eigene.set(i, new EigeneForm(f.dimension(), f.ecken(), f.farbe(), f.angeheftet(), name(name)));
            schreibe();
        }
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
        List<Ebenen.Form> aufKarte = new ArrayList<>(), aufMinimap = new ArrayList<>();
        regionen.stream().filter(Region::angeheftet).map(Wegpunkte::flaeche).forEach(aufMinimap::add);
        for (EigeneForm f : this.eigene) {
            double[] p = mitten(f);
            Ebenen.Form form = form(f, p);
            Ebenen.Schrift name = schrift(f, p);
            // Die Form, die gerade gezogen wird, zeichnet die Vollbildkarte selbst, wo sie an der Maus steht.
            if (f != gezogen) {
                aufKarte.add(f.angeheftet() ? breiter(form) : form);
                if (name != null) {
                    aufKarte.add(name);
                }
            }
            if (f.angeheftet()) {
                aufMinimap.add(form);
                if (name != null) {
                    aufMinimap.add(name);
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
     * an einer Linie von Nord nach Süd auf ihrer Westseite. Null ohne Namen. Siehe docs/wegpunkte.md, „Namen“.
     */
    static Ebenen.Schrift schrift(EigeneForm f, double[] p) {
        if (f.name() == null) {
            return null;
        }
        double[] pfad;
        if (f.region()) {
            double[] g = griff(p, true);
            pfad = new double[] {g[0], g[1] + NAME_GROESSE};
        } else {
            // Eine Linie hat genau zwei Ecken. Die Normale (dz, −dx) zeigt für dx > 0 nach Norden, für dx = 0 und dz < 0 nach Westen.
            double dx = p[2] - p[0], dz = p[3] - p[1], s = (dx > 0 || dx == 0 && dz < 0 ? 1 : -1) * NAME_GROESSE / Math.hypot(dx, dz);
            pfad = new double[] {p[0] + s * dz, p[1] - s * dx, p[2] + s * dz, p[3] - s * dx};
        }
        return new Ebenen.Schrift(f.dimension(), f.name(), pfad, NAME_GROESSE, 0, Ebenen.SCHRIFTFARBE, Ebenen.KONTURFARBE, NAME_KONTUR,
                box(pfad));
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
