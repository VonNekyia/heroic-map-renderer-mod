package com.nekyia.heroicmap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Der Kartenausweis eines Weltordners in {@code ausweis.json}: das Netz, also die eingetippte Adresse, der Server, der Hash
 * und je Weltschlüssel, etwa {@code minecraft:overworld}, Besuche und Weltalter. Der Pfad des Ordners bleibt massgeblich;
 * der Ausweis ergänzt, was der Pfad nicht weiss. Siehe docs/download.md, „Ausweis“.
 *
 * @param netz die eingetippte Adresse wie {@link Servername#schluessel}, leer im Einzelspieler oder unbekannt
 * @param server der Name vom Server, leer ohne
 */
record Ausweis(String netz, String server, String hash, Map<String, Welt> welten) {

    static final String DATEI = "ausweis.json";
    private static final int VERSION = 1;

    /**
     * Was der Mod von einer Welt im Ordner weiss: der erste und letzte Besuch, an wie vielen Tagen, die Spielzeit des Servers
     * in Ticks, die erste gesehene, die grösste und die letzte, -1, solange keine kam, die UID vom Plugin, null ohne, und wie
     * oft sie zurückgesetzt wurde. Siehe docs/download.md, „Ausweis“.
     */
    record Welt(Instant erste, Instant zuletzt, int tage, long alterErstes, long alterMax, long alterZuletzt, String uid, int rueckfaelle) {

        /** Ohne Rückfall, wie bis B6. */
        Welt(Instant erste, Instant zuletzt, int tage, long alterErstes, long alterMax, long alterZuletzt, String uid) {
            this(erste, zuletzt, tage, alterErstes, alterMax, alterZuletzt, uid, 0);
        }
    }

    Ausweis {
        // Sortiert, damit die Datei bei gleichem Inhalt gleich aussieht.
        welten = Collections.unmodifiableMap(new TreeMap<>(welten));
    }

    /** Ein leerer Ausweis für den Ordner {@code welt-<hash>}. */
    static Ausweis neu(String hash) {
        return new Ausweis("", "", hash, Map.of());
    }

    /** Mit Netz und Server von jetzt. */
    Ausweis mit(String netz, String server) {
        return new Ausweis(netz, server, hash, welten);
    }

    /** Ein Besuch der Welt {@code schluessel} um {@code jetzt}; ein neuer Tag in der Zone {@code zone} zählt einen Tag mehr. */
    Ausweis besucht(String schluessel, Instant jetzt, ZoneId zone) {
        Welt alt = welten.get(schluessel);
        Welt neu = alt == null ? new Welt(jetzt, jetzt, 1, -1, -1, -1, null)
                : new Welt(alt.erste(), jetzt, alt.tage() + (tag(alt.zuletzt(), zone).equals(tag(jetzt, zone)) ? 0 : 1),
                        alt.alterErstes(), alt.alterMax(), alt.alterZuletzt(), alt.uid(), alt.rueckfaelle());
        return mitWelt(schluessel, neu);
    }

    /** Die Spielzeit {@code alter} der Welt {@code schluessel}, die gerade gesehen wurde; ohne Besuch nichts. */
    Ausweis alter(String schluessel, long alter) {
        Welt w = welten.get(schluessel);
        if (w == null) {
            return this;
        }
        return mitWelt(schluessel, new Welt(w.erste(), w.zuletzt(), w.tage(), w.alterErstes() < 0 ? alter : w.alterErstes(),
                Math.max(w.alterMax(), alter), alter, w.uid(), w.rueckfaelle()));
    }

    /**
     * Eine neue Generation der Welt {@code schluessel} nach einem Reset oder einer neuen Runde: die Spielzeit von vorn mit
     * {@code alter} (-1 ohne), die UID {@code uid}, ein Rückfall mehr; Besuche bleiben. Ohne Besuch nichts. Siehe
     * docs/download.md, „Reset“.
     */
    Ausweis neueGeneration(String schluessel, long alter, String uid) {
        Welt w = welten.get(schluessel);
        return w == null ? this : mitWelt(schluessel, new Welt(w.erste(), w.zuletzt(), w.tage(), alter, alter, alter, uid, w.rueckfaelle() + 1));
    }

    /** Die UID vom Plugin für die Welt {@code schluessel}; ohne Besuch nichts. */
    Ausweis uid(String schluessel, String uid) {
        Welt w = welten.get(schluessel);
        return w == null ? this
                : mitWelt(schluessel, new Welt(w.erste(), w.zuletzt(), w.tage(), w.alterErstes(), w.alterMax(), w.alterZuletzt(), uid,
                        w.rueckfaelle()));
    }

    private Ausweis mitWelt(String schluessel, Welt w) {
        Map<String, Welt> neu = new TreeMap<>(welten);
        neu.put(schluessel, w);
        return new Ausweis(netz, server, hash, neu);
    }

    private static LocalDate tag(Instant t, ZoneId zone) {
        return t.atZone(zone).toLocalDate();
    }

    /** Der Ausweis im Ordner {@code welt}, oder null, wenn er fehlt oder sich nicht lesen lässt. */
    static Ausweis lies(Path welt) {
        Path datei = welt.resolve(DATEI);
        if (!Files.isRegularFile(datei)) {
            return null;
        }
        try {
            JsonObject j = JsonParser.parseString(Files.readString(datei, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, Welt> welten = new TreeMap<>();
            for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("welten").entrySet()) {
                JsonObject w = e.getValue().getAsJsonObject();
                welten.put(e.getKey(), new Welt(Instant.parse(w.get("erste").getAsString()), Instant.parse(w.get("zuletzt").getAsString()),
                        w.get("tage").getAsInt(), w.get("alter_erstes").getAsLong(), w.get("alter_max").getAsLong(),
                        w.get("alter_zuletzt").getAsLong(), w.has("uid") ? w.get("uid").getAsString() : null,
                        w.has("rueckfaelle") ? w.get("rueckfaelle").getAsInt() : 0));
            }
            return new Ausweis(j.get("netz").getAsString(), j.get("server").getAsString(), j.get("hash").getAsString(), welten);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Schreibt den Ausweis in den Ordner {@code welt}, über eine Zwischendatei; nie liegt ein halber da. */
    void schreibe(Path welt) throws IOException {
        JsonObject j = new JsonObject();
        j.addProperty("version", VERSION);
        j.addProperty("netz", netz);
        j.addProperty("server", server);
        j.addProperty("hash", hash);
        JsonObject ws = new JsonObject();
        welten.forEach((schluessel, w) -> {
            JsonObject o = new JsonObject();
            o.addProperty("erste", w.erste().toString());
            o.addProperty("zuletzt", w.zuletzt().toString());
            o.addProperty("tage", w.tage());
            o.addProperty("alter_erstes", w.alterErstes());
            o.addProperty("alter_max", w.alterMax());
            o.addProperty("alter_zuletzt", w.alterZuletzt());
            if (w.uid() != null) {
                o.addProperty("uid", w.uid());
            }
            if (w.rueckfaelle() > 0) {
                o.addProperty("rueckfaelle", w.rueckfaelle());
            }
            ws.add(schluessel, o);
        });
        j.add("welten", ws);
        Path datei = welt.resolve(DATEI), tmp = welt.resolve(DATEI + ".tmp");
        Files.writeString(tmp, j.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, datei, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
