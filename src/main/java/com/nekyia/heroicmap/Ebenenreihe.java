package com.nekyia.heroicmap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Die Reihe der Ebenen, wie der Spieler sie umgestellt hat, von oben nach unten: Ebenen vom Server und von Mods mit ihrer
 * Kennung, eigene mit ihrer id. Ohne Umstellen gibt es keine; dann liegen die eigenen oben wie vorher, darunter die vom
 * Server nach {@code order}. Je Welt eines Servers in {@value #DATEI}. Siehe docs/vollbildkarte.md, „Reihe der Ebenen“.
 */
final class Ebenenreihe {

    /** Eine Schicht: eine Ebene vom Server oder eines Mods, {@code server} ihre Kennung; sonst eine eigene mit der id {@code eigen}. */
    record Schicht(String server, int eigen) {

        static Schicht vomServer(String id) {
            return new Schicht(id, 0);
        }

        static Schicht eigene(int id) {
            return new Schicht(null, id);
        }
    }

    static final String DATEI = "ebenen-reihe.json";
    static final Ebenenreihe INSTANZ = new Ebenenreihe();
    private static final Logger LOGGER = LoggerFactory.getLogger(HeroicMap.ID);

    /** Die umgestellte Reihe, von oben nach unten, oder null ohne Umstellen. */
    private List<Schicht> gemerkt;
    private Path datei;
    private int stand;

    /** Steigt bei jeder Änderung; so baut {@link Wegpunkte} seine Listen neu. */
    int stand() {
        return stand;
    }

    boolean umgestellt() {
        return gemerkt != null;
    }

    /** Liest die Reihe aus dem Ordner der Welt; null heisst nur im Speicher und ohne Umstellen. */
    void wechsel(Path ordner) {
        datei = ordner == null ? null : ordner.resolve(DATEI);
        gemerkt = datei == null ? null : lies(datei);
        stand++;
    }

    /** Die Reihe aus {@code datei}; ohne Datei oder unlesbar null, dann gilt {@code order}. Doppelte zählen einmal. */
    static List<Schicht> lies(Path datei) {
        if (!Files.exists(datei)) {
            return null;
        }
        try {
            List<Schicht> aus = new ArrayList<>();
            for (JsonElement e : JsonParser.parseString(Files.readString(datei, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("reihe")) {
                JsonObject o = e.getAsJsonObject();
                Schicht s = o.has("server") ? Schicht.vomServer(o.get("server").getAsString()) : Schicht.eigene(o.get("eigen").getAsInt());
                if (!aus.contains(s)) {
                    aus.add(s);
                }
            }
            return aus;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Heroic Map: {} nicht lesbar, die Ebenen liegen nach order", datei);
            return null;
        }
    }

    /** Die Schichten, die es gibt, von oben nach unten; siehe {@link #wirksam(List, List, List)}. */
    List<Schicht> wirksam(List<Ebenen.Eintrag> server, List<Integer> eigene) {
        return wirksam(gemerkt, server, eigene);
    }

    /**
     * Die Schichten, die es gibt, von oben nach unten. Ohne Umstellen die {@code eigene} oben in ihrer Reihenfolge, darunter die
     * vom {@code server}, die aufsteigend nach {@code order} kommen, also von hinten. Umgestellt die {@code gemerkt}e Reihe ohne
     * die, die es nicht mehr gibt; eine neue kommt direkt über die oberste bekannte, deren {@code order} nicht höher ist, sonst
     * ganz unten. Eine eigene zählt dabei als {@code order} 0. Mehrere neue nacheinander, erst die eigenen, dann die vom
     * Server von oben nach unten; jede zählt die vor ihr eingereihten mit.
     */
    static List<Schicht> wirksam(List<Schicht> gemerkt, List<Ebenen.Eintrag> server, List<Integer> eigene) {
        Map<Schicht, Integer> order = new LinkedHashMap<>();
        for (int id : eigene) {
            order.put(Schicht.eigene(id), 0);
        }
        for (Ebenen.Eintrag e : server.reversed()) {
            order.put(Schicht.vomServer(e.id()), e.order());
        }
        if (gemerkt == null) {
            return List.copyOf(order.keySet());
        }
        List<Schicht> aus = new ArrayList<>(gemerkt.stream().filter(order::containsKey).toList());
        for (Map.Entry<Schicht, Integer> neu : order.entrySet()) {
            if (aus.contains(neu.getKey())) {
                continue;
            }
            int i = 0;
            while (i < aus.size() && order.get(aus.get(i)) > neu.getValue()) {
                i++;
            }
            aus.add(i, neu.getKey());
        }
        return List.copyOf(aus);
    }

    /** Die Ebenen vom Server und von Mods in der Reihe, die oberste zuerst; ohne Umstellen wie {@code alle}. Für das Untermenü. */
    List<Ebenen.Eintrag> vomServer(List<Ebenen.Eintrag> alle, List<Integer> eigene) {
        Map<String, Ebenen.Eintrag> je = new LinkedHashMap<>();
        alle.forEach(e -> je.put(e.id(), e));
        return wirksam(alle.reversed(), eigene).stream().filter(s -> s.server() != null).map(s -> je.get(s.server())).toList();
    }

    /**
     * Rückt die Schicht {@code s} in {@code alle} um eine gezeigte Zeile hoch ({@code schritt} −1) oder runter (+1), über
     * Schichten hinweg, die die Liste nicht zeigt; diese bleiben, wo sie sind. Danach ist die Reihe umgestellt und gleich
     * geschrieben. Am Rand nichts.
     */
    void verschiebe(List<Schicht> alle, Set<Schicht> gezeigt, Schicht s, int schritt) {
        List<Schicht> neu = verschoben(alle, gezeigt, s, schritt);
        if (neu != null) {
            gemerkt = neu;
            stand++;
            schreibe();
        }
    }

    /** Wie {@link #verschiebe}, ohne zu merken: die neue Reihe, am Rand null. */
    static List<Schicht> verschoben(List<Schicht> alle, Set<Schicht> gezeigt, Schicht s, int schritt) {
        int i = alle.indexOf(s), j = i + schritt;
        while (i >= 0 && j >= 0 && j < alle.size() && !gezeigt.contains(alle.get(j))) {
            j += schritt;
        }
        if (i < 0 || j < 0 || j >= alle.size()) {
            return null;
        }
        List<Schicht> neu = new ArrayList<>(alle);
        neu.remove(i);
        neu.add(j, s);
        return List.copyOf(neu);
    }

    /** „Reihe des Servers“: Die eigene Reihe fällt weg, wieder gilt {@code order}, die eigenen oben. */
    void zuruecksetzen() {
        gemerkt = null;
        stand++;
        if (datei != null) {
            try {
                Files.deleteIfExists(datei);
            } catch (IOException e) {
                LOGGER.warn("Heroic Map: {} nicht gelöscht", datei, e);
            }
        }
    }

    private void schreibe() {
        if (datei == null) {
            return;
        }
        JsonArray reihe = new JsonArray();
        for (Schicht s : gemerkt) {
            JsonObject o = new JsonObject();
            if (s.server() != null) {
                o.addProperty("server", s.server());
            } else {
                o.addProperty("eigen", s.eigen());
            }
            reihe.add(o);
        }
        JsonObject json = new JsonObject();
        json.add("reihe", reihe);
        try {
            Files.createDirectories(datei.getParent());
            Path tmp = datei.resolveSibling(DATEI + ".tmp");
            Files.writeString(tmp, json.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, datei, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn("Heroic Map: {} nicht geschrieben", datei, e);
        }
    }
}
