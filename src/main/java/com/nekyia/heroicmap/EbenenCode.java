package com.nekyia.heroicmap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Eine eigene Ebene als Code zum Teilen: „HMAP1:“, dahinter Base64url ohne Auffüllen von Deflate ohne Kopf von UTF-8-JSON
 * {@code {v:1, name, wegpunkte, formen}}. Nicht darin: Angeheftetes, Mitspieler, Formen vom Server, der Todespunkt und alte
 * Rechtecke. Lesen prüft alles oder nichts. Siehe docs/wegpunkte.md, „Ebenen teilen“.
 */
final class EbenenCode {

    static final String VORSILBE = "HMAP1:";
    /** So lang darf der Text höchstens sein, und so gross das Entpackte; mehr wäre eine Bombe. */
    static final int MAX_TEXT = 64 * 1024, MAX_ENTPACKT = 256 * 1024;
    /** So viele Wegpunkte und so viele Formen je Code. */
    static final int MAX_JE_CODE = 256;
    private static final int MAX_DIMENSION = 64;
    private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /** Ein Wegpunkt im Code; {@code name}, {@code schrift} und {@code minecraft} wie bei {@link Wegpunkte.Punkt}. */
    record Ort(String dimension, int x, int z, int farbe, String name, double schrift, boolean minecraft) {

        /** Ohne Namen, wie vor 0.2.69. */
        Ort(String dimension, int x, int z, int farbe) {
            this(dimension, x, z, farbe, null, 1, false);
        }
    }

    /** Eine Form im Code; {@code hex}, {@code schrift} und {@code biegung} wie bei {@link Wegpunkte.EigeneForm}, {@code name} null ohne. */
    record Form(String dimension, List<Wegpunkte.Ecke> ecken, int farbe, int hex, String name, double schrift, int biegung, int gewicht,
            boolean minecraft) {
    }

    /** Was ein Code enthält. */
    record Inhalt(String name, List<Ort> orte, List<Form> formen) {
    }

    /** Gelesen: der Inhalt, oder der Schlüssel der Meldung, warum nicht; dann ist {@code inhalt} null. */
    record Gelesen(Inhalt inhalt, String fehler) {
    }

    private EbenenCode() {
    }

    /** Der Code der eigenen Ebene {@code ebene} aus {@code w}, ohne Angeheftetes und ohne den Todespunkt. */
    static String schreibe(Wegpunkte w, int ebene) {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("name", w.ebene(ebene).name());
        JsonArray orte = new JsonArray();
        for (Wegpunkte.Punkt p : w.punkte()) {
            if (p.ebene() == ebene && !p.tod()) {
                JsonObject q = new JsonObject();
                q.addProperty("dimension", p.dimension());
                q.addProperty("x", p.x());
                q.addProperty("z", p.z());
                q.addProperty("farbe", p.farbe());
                // Wie an Formen nur ohne Vorgabe; ältere Versionen überlesen sie.
                if (p.name() != null) {
                    q.addProperty("name", p.name());
                }
                if (p.schrift() != 1) {
                    q.addProperty("schrift", p.schrift());
                }
                if (p.minecraft()) {
                    q.addProperty("schriftart", Wegpunkte.SCHRIFTART_MINECRAFT);
                }
                orte.add(q);
            }
        }
        JsonArray formen = new JsonArray();
        for (Wegpunkte.EigeneForm f : w.eigeneFormen()) {
            if (f.ebene() != ebene) {
                continue;
            }
            JsonObject q = new JsonObject();
            q.addProperty("dimension", f.dimension());
            JsonArray ecken = new JsonArray();
            for (Wegpunkte.Ecke e : f.ecken()) {
                JsonArray xz = new JsonArray();
                xz.add(e.x());
                xz.add(e.z());
                ecken.add(xz);
            }
            q.add("ecken", ecken);
            // Die eigene Farbe als „#RRGGBB“, sonst der Index in der Liste.
            if (f.hex() == Wegpunkte.KEIN_HEX) {
                q.addProperty("farbe", f.farbe());
            } else {
                q.addProperty("farbe", Wegpunkte.hex(f.hex()));
            }
            if (f.name() != null) {
                q.addProperty("name", f.name());
            }
            // Nur ohne Vorgabe; ältere Versionen überlesen sie. Siehe docs/wegpunkte.md, „Ebenen teilen“.
            if (f.schrift() != 1) {
                q.addProperty("schrift", f.schrift());
            }
            if (f.biegung() != 0) {
                q.addProperty("biegung", f.biegung());
            }
            if (f.gewicht() != 0) {
                q.addProperty("gewicht", f.gewicht());
            }
            if (f.minecraft()) {
                q.addProperty("schriftart", Wegpunkte.SCHRIFTART_MINECRAFT);
            }
            formen.add(q);
        }
        o.add("wegpunkte", orte);
        o.add("formen", formen);
        return packe(o.toString());
    }

    /** Packt den Text {@code json} in einen Code. */
    static String packe(String json) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
        d.setInput(json.getBytes(StandardCharsets.UTF_8));
        d.finish();
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        byte[] puffer = new byte[4096];
        while (!d.finished()) {
            aus.write(puffer, 0, d.deflate(puffer));
        }
        d.end();
        return VORSILBE + Base64.getUrlEncoder().withoutPadding().encodeToString(aus.toByteArray());
    }

    /**
     * Liest einen Code, alles oder nichts: die Vorsilbe, höchstens {@link #MAX_TEXT} Zeichen und entpackt {@link #MAX_ENTPACKT}
     * Bytes, {@code v} 1, ein Name wie bei {@link Wegpunkte#name}, höchstens {@link #MAX_JE_CODE} Wegpunkte und Formen, ganze
     * Koordinaten bis {@link Wegpunkte#MAX_KOORDINATE}, eine Dimension wie {@code ns:pfad} bis 64 Zeichen, eine Farbe 0–7 oder
     * „#RRGGBB“ und 2 bis {@link Wegpunkte#MAX_PUNKTE_FORM} Ecken. Unbekannte Felder überliest es.
     */
    static Gelesen lies(String text) {
        if (text == null) {
            return fehler("kein_code");
        }
        String t = text.strip();
        if (t.length() > MAX_TEXT) {
            return fehler("zu_gross");
        }
        if (!t.startsWith(VORSILBE)) {
            return fehler("kein_code");
        }
        byte[] gepackt;
        try {
            gepackt = Base64.getUrlDecoder().decode(t.substring(VORSILBE.length()));
        } catch (IllegalArgumentException kaputt) {
            return fehler("kaputt");
        }
        String json;
        Inflater i = new Inflater(true);
        try {
            // Ohne Kopf braucht der Inflater ein Byte mehr am Ende.
            i.setInput(Arrays.copyOf(gepackt, gepackt.length + 1));
            ByteArrayOutputStream aus = new ByteArrayOutputStream();
            byte[] puffer = new byte[4096];
            while (!i.finished()) {
                int n = i.inflate(puffer);
                if (n == 0 && (i.needsInput() || i.needsDictionary())) {
                    return fehler("kaputt");
                }
                aus.write(puffer, 0, n);
                if (aus.size() > MAX_ENTPACKT) {
                    return fehler("zu_gross");
                }
            }
            json = aus.toString(StandardCharsets.UTF_8);
        } catch (DataFormatException kaputt) {
            return fehler("kaputt");
        } finally {
            i.end();
        }
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (ganz(o.get("v")) != 1) {
                return fehler("version");
            }
            String name = o.get("name").isJsonPrimitive() ? Wegpunkte.name(o.get("name").getAsString()) : null;
            if (name == null) {
                return fehler("kaputt");
            }
            JsonArray w = o.has("wegpunkte") ? o.getAsJsonArray("wegpunkte") : new JsonArray();
            JsonArray f = o.has("formen") ? o.getAsJsonArray("formen") : new JsonArray();
            if (w.size() > MAX_JE_CODE || f.size() > MAX_JE_CODE) {
                return fehler("zu_viele");
            }
            List<Ort> orte = new ArrayList<>();
            for (JsonElement e : w) {
                JsonObject q = e.getAsJsonObject();
                orte.add(new Ort(dimension(q), koordinate(q.get("x")), koordinate(q.get("z")), index(q.get("farbe")), name(q), schrift(q),
                        minecraft(q)));
            }
            List<Form> formen = new ArrayList<>();
            for (JsonElement e : f) {
                formen.add(form(e.getAsJsonObject()));
            }
            return new Gelesen(new Inhalt(name, orte, formen), null);
        } catch (RuntimeException kaputt) {
            // Ein Feld fehlt, hat den falschen Typ oder liegt ausserhalb der Grenzen.
            return fehler("kaputt");
        }
    }

    private static Form form(JsonObject q) {
        JsonArray roh = q.getAsJsonArray("ecken");
        if (roh.size() < 2 || roh.size() > Wegpunkte.MAX_PUNKTE_FORM) {
            throw new IllegalArgumentException("Ecken " + roh.size());
        }
        List<Wegpunkte.Ecke> ecken = new ArrayList<>();
        for (JsonElement e : roh) {
            JsonArray xz = e.getAsJsonArray();
            if (xz.size() != 2) {
                throw new IllegalArgumentException("Ecke");
            }
            ecken.add(new Wegpunkte.Ecke(koordinate(xz.get(0)), koordinate(xz.get(1))));
        }
        List<Wegpunkte.Ecke> gueltig = Wegpunkte.gueltig(ecken);
        if (gueltig == null) {
            throw new IllegalArgumentException("Ecken");
        }
        JsonElement farbe = q.get("farbe");
        int index = 0, hex = Wegpunkte.KEIN_HEX;
        if (farbe != null && farbe.isJsonPrimitive() && farbe.getAsJsonPrimitive().isString()) {
            hex = Wegpunkte.hex(farbe.getAsString());
            if (hex == Wegpunkte.KEIN_HEX) {
                throw new IllegalArgumentException("Farbe");
            }
        } else {
            index = index(farbe);
        }
        int biegung = 0;
        if (q.has("biegung")) {
            int b = ganz(q.get("biegung"));
            if (Math.abs(b) > Wegpunkte.MAX_BIEGUNG) {
                throw new IllegalArgumentException("Biegung " + b);
            }
            biegung = Wegpunkte.biegung(b);
        }
        int gewicht = 0;
        if (q.has("gewicht")) {
            gewicht = ganz(q.get("gewicht"));
            if (!Wegpunkte.gewichtGilt(gewicht)) {
                throw new IllegalArgumentException("Gewicht " + gewicht);
            }
        }
        return new Form(dimension(q), gueltig, index, hex, name(q), schrift(q), biegung, gewicht, minecraft(q));
    }

    /** Der Name eines Wegpunkts oder einer Form, null ohne; was kein Text ist, ist kaputt. */
    private static String name(JsonObject q) {
        if (!q.has("name") || q.get("name").isJsonNull()) {
            return null;
        }
        if (!q.get("name").isJsonPrimitive() || !q.get("name").getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Name");
        }
        return Wegpunkte.name(q.get("name").getAsString());
    }

    /** Die Schrift, ohne 1; ausserhalb der Rasten oder ohne Zahl kaputt. */
    private static double schrift(JsonObject q) {
        if (!q.has("schrift")) {
            return 1;
        }
        JsonElement s = q.get("schrift");
        if (!s.isJsonPrimitive() || !s.getAsJsonPrimitive().isNumber() || !Wegpunkte.schriftGilt(s.getAsDouble())) {
            throw new IllegalArgumentException("Schrift");
        }
        return Wegpunkte.schrift(s.getAsDouble());
    }

    /** Die Schriftart: eine andere als „minecraft“, auch eine spätere, heisst die Kartenschrift; was kein Text ist, ist kaputt. */
    private static boolean minecraft(JsonObject q) {
        if (!q.has("schriftart")) {
            return false;
        }
        JsonElement a = q.get("schriftart");
        if (!a.isJsonPrimitive() || !a.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Schriftart");
        }
        return Wegpunkte.SCHRIFTART_MINECRAFT.equals(a.getAsString());
    }

    private static Gelesen fehler(String schluessel) {
        return new Gelesen(null, schluessel);
    }

    /** Eine ganze Zahl, nur als Zahl im JSON; 1.5 oder "1" nicht. */
    private static int ganz(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("keine Zahl");
        }
        return e.getAsBigDecimal().intValueExact();
    }

    private static int koordinate(JsonElement e) {
        int k = ganz(e);
        if (Math.abs(k) > Wegpunkte.MAX_KOORDINATE) {
            throw new IllegalArgumentException("Koordinate " + k);
        }
        return k;
    }

    private static int index(JsonElement e) {
        int f = ganz(e);
        if (f < 0 || f >= Wegpunkte.FARBEN.length) {
            throw new IllegalArgumentException("Farbe " + f);
        }
        return f;
    }

    private static String dimension(JsonObject q) {
        String d = q.get("dimension").getAsString();
        if (d.length() > MAX_DIMENSION || !DIMENSION.matcher(d).matches()) {
            throw new IllegalArgumentException("Dimension");
        }
        return d;
    }
}
