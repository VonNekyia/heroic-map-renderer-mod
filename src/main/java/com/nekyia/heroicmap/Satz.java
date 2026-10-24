package com.nekyia.heroicmap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
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

    /**
     * Der Satz, den {@code server} für {@code dimension} unter dem Ordner einer Welt zeigt, oder null: seine Wahl in
     * {@link Zeigt}, sonst der erste Download nach dem Namen, dann einer im Ziel des {@link Zeiger}s, dann seine eigene
     * Karte. Ein Server ohne Namen nimmt wie bisher seine eigene vor dem Download. Die eigene Karte eines anderen
     * Servers zeigt er nie, auch nicht im Ziel. Siehe docs/selbst.md, „Wahl“.
     */
    static Satz fuer(Path welt, String dimension, String server) {
        if (welt == null || !Files.isDirectory(welt)) {
            return null;
        }
        Path ziel = Zeiger.ziel(welt);
        String gewaehlt = Zeigt.baum(welt, server, dimension);
        if (gewaehlt != null) {
            boolean dort = gewaehlt.startsWith(Zeiger.DORT);
            Path baum = !dort ? welt.resolve(gewaehlt) : ziel == null ? null : ziel.resolve(gewaehlt.substring(Zeiger.DORT.length()));
            Satz satz = baum == null ? null : lies(baum);
            if (satz != null && dimension.equals(satz.dimension())
                    && (!Selbst.selbst(baum) || !dort && Selbst.gehoert(baum, server))) {
                return satz;
            }
        }
        Satz[] hier = baeume(welt, dimension, server);
        if (hier == null) {
            return null;
        }
        Satz dort = hier[0] == null && ziel != null ? baeume(ziel, dimension, null)[0] : null;
        if (Zeigt.OHNE.equals(server)) {
            return hier[1] != null ? hier[1] : hier[0] != null ? hier[0] : dort;
        }
        return hier[0] != null ? hier[0] : dort != null ? dort : hier[1];
    }

    /**
     * Im Ordner einer Welt der erste Download der Dimension und die eigene Karte von {@code server}, ohne Server keine;
     * null, wenn der Ordner nicht zu lesen ist, und im Ziel eines Zeigers dann keiner.
     */
    private static Satz[] baeume(Path welt, String dimension, String server) {
        Satz download = null, eigene = null;
        // Nach dem Namen als Zeichenkette, nicht in der Folge des Dateisystems und nicht nach Path.compareTo,
        // das unter Windows Gross und Klein gleich nimmt: So ist das Ergebnis überall gleich.
        try (Stream<Path> baeume = Files.list(welt)) {
            for (Path baum : baeume.sorted(Comparator.comparing((Path p) -> p.getFileName().toString())).toList()) {
                Satz satz = lies(baum);
                if (satz == null || !dimension.equals(satz.dimension())) {
                    continue;
                }
                if (!Selbst.selbst(baum)) {
                    download = download == null ? satz : download;
                } else if (eigene == null && server != null && Selbst.gehoert(baum, server)) {
                    eigene = satz;
                }
            }
        } catch (IOException e) {
            return server == null ? new Satz[2] : null;
        }
        return new Satz[] {download, eigene};
    }

    /** Der Server, dem ein selbst gezeichneter Baum gehört, aus {@code satz.json}; {@link Zeigt#OHNE} ohne Angabe. */
    static String besitzer(Path baum) {
        try {
            JsonObject satz = json(baum.resolve("satz.json"));
            return satz.has("server") ? satz.get("server").getAsString() : Zeigt.OHNE;
        } catch (IOException | RuntimeException e) {
            return Zeigt.OHNE;
        }
    }

    /**
     * Liest den Satz eines Baums, oder null, wenn keiner vollständig daliegt oder {@code map.json}
     * vom Server eine Grenze verletzt: {@code tileSize} eine Zweierpotenz von 64 bis 1024,
     * {@code 0 ≤ minZoom ≤ stufe ≤ maxZoom ≤ 30}, {@code scale} ab 1.
     */
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
            Satz s = new Satz(ordner, satz.get("name").getAsString(), satz.get("dimension").getAsString(), massstab,
                    karte.get("tileSize").getAsInt(), karte.get("minZoom").getAsInt(), karte.get("maxZoom").getAsInt(),
                    karte.get("scale").getAsInt());
            boolean gut = (massstab == 1 || massstab == 2 || massstab == 4)
                    && s.kachel >= 64 && s.kachel <= 1024 && Integer.bitCount(s.kachel) == 1
                    && s.minZoom >= 0 && s.minZoom <= s.stufe() && s.maxZoom <= 30 && s.scale >= 1;
            return gut ? s : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Schreibt {@code satz.json} eines Baums: Name, Dimension und Massstab. */
    static void schreibe(Path baum, String name, String dimension, int massstab) throws IOException {
        schreibe(baum, name, dimension, massstab, Zeigt.OHNE);
    }

    /** Wie oben, mit dem Server, dem eine eigene Karte gehört; ohne Namen ohne das Feld. */
    static void schreibe(Path baum, String name, String dimension, int massstab, String server) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        json.addProperty("dimension", dimension);
        json.addProperty("massstab", massstab);
        if (!Zeigt.OHNE.equals(server)) {
            json.addProperty("server", server);
        }
        Files.createDirectories(baum);
        Files.writeString(baum.resolve("satz.json"), json.toString(), StandardCharsets.UTF_8);
    }

    private static JsonObject json(Path datei) throws IOException {
        return JsonParser.parseString(Files.readString(datei, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
