package com.nekyia.heroicmap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Ein geladener Satz auf der Platte: sein Ordner, Name, Dimension und Massstab aus
 * {@code satz.json}, Kachelgrösse, Stufen und scale aus {@code map.json}. Siehe docs/vollbildkarte.md.
 */
record Satz(Path ordner, String name, String dimension, int massstab, int kachel, int minZoom, int maxZoom, int scale) {

    /** Die feinste Stufe des Satzes: 4 px ist {@code maxZoom}, 2 px eine gröber, 1 px zwei. */
    int stufe() {
        return maxZoom - switch (massstab) {
            case 4 -> 0;
            case 2 -> 1;
            default -> 2;
        };
    }

    /** Der Satz für {@code dimension} unter dem Ordner eines Servers, oder null. */
    static Satz fuer(Path server, String dimension) {
        if (server == null || !Files.isDirectory(server)) {
            return null;
        }
        try (Stream<Path> baeume = Files.list(server)) {
            for (Path baum : baeume.toList()) {
                Satz satz = lies(baum);
                if (satz != null && dimension.equals(satz.dimension())) {
                    return satz;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /** Liest den Satz eines Baums, oder null, wenn keiner vollständig daliegt. */
    static Satz lies(Path baum) {
        try {
            Path datei = baum.resolve("satz.json");
            if (!Files.exists(datei)) {
                return null;
            }
            JsonObject satz = json(datei);
            int massstab = satz.get("massstab").getAsInt();
            Path ordner = baum.resolve(String.valueOf(massstab));
            JsonObject karte = json(ordner.resolve("map.json"));
            return new Satz(ordner, satz.get("name").getAsString(), satz.get("dimension").getAsString(), massstab,
                    karte.get("tileSize").getAsInt(), karte.get("minZoom").getAsInt(), karte.get("maxZoom").getAsInt(),
                    karte.get("scale").getAsInt());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Schreibt {@code satz.json} eines Baums: Name, Dimension und Massstab. */
    static void schreibe(Path baum, String name, String dimension, int massstab) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        json.addProperty("dimension", dimension);
        json.addProperty("massstab", massstab);
        Files.createDirectories(baum);
        Files.writeString(baum.resolve("satz.json"), json.toString(), StandardCharsets.UTF_8);
    }

    private static JsonObject json(Path datei) throws IOException {
        return JsonParser.parseString(Files.readString(datei, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
