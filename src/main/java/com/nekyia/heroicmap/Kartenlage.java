package com.nekyia.heroicmap;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;

/**
 * Wo die Vollbildkarte zuletzt stand, je Dimension: die Mitte in Blöcken, die Stufe und die Lupe. Je
 * Welt in {@code karte.properties} im Ordner der Welt, im Einzelspieler nur im Speicher. Nur der
 * Render-Thread. Siehe docs/vollbildkarte.md, „Lage merken“.
 */
final class Kartenlage {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Ohne Ordner, im Einzelspieler: je Dimension nur im Speicher. */
    private static final Map<String, Lage> SPEICHER = new HashMap<>();

    /** Die Mitte in Blöcken, die Stufe und die Lupe. */
    record Lage(double x, double z, int zoom, int lupe) {
    }

    private Kartenlage() {
    }

    /** Die Lage für {@code dimension} im Ordner der Welt, oder null, wenn keine da oder lesbar ist. */
    static Lage lies(Path ordner, String dimension) {
        if (ordner == null) {
            return SPEICHER.get(dimension);
        }
        Properties p = datei(ordner);
        try {
            return new Lage(Double.parseDouble(p.getProperty(dimension + ".x")), Double.parseDouble(p.getProperty(dimension + ".z")),
                    Integer.parseInt(p.getProperty(dimension + ".zoom")), Integer.parseInt(p.getProperty(dimension + ".lupe")));
        } catch (RuntimeException fehlt) {
            return null;
        }
    }

    /** Merkt die Lage für {@code dimension}; die anderer Dimensionen bleibt. */
    static void schreibe(Path ordner, String dimension, Lage l) {
        if (ordner == null) {
            SPEICHER.put(dimension, l);
            return;
        }
        Properties p = datei(ordner);
        p.setProperty(dimension + ".x", Double.toString(l.x()));
        p.setProperty(dimension + ".z", Double.toString(l.z()));
        p.setProperty(dimension + ".zoom", Integer.toString(l.zoom()));
        p.setProperty(dimension + ".lupe", Integer.toString(l.lupe()));
        try {
            Files.createDirectories(ordner);
            try (Writer raus = Files.newBufferedWriter(ordner.resolve("karte.properties"), StandardCharsets.UTF_8)) {
                p.store(raus, "Heroic Map: wo die Vollbildkarte zuletzt stand");
            }
        } catch (IOException e) {
            LOGGER.warn("Heroic Map: {} nicht geschrieben", ordner.resolve("karte.properties"), e);
        }
    }

    /** Beim Trennen: Was nur im Speicher lag, gilt für die nächste Welt nicht. */
    static void leeren() {
        SPEICHER.clear();
    }

    private static Properties datei(Path ordner) {
        Properties p = new Properties();
        Path datei = ordner.resolve("karte.properties");
        if (Files.exists(datei)) {
            try (Reader rein = Files.newBufferedReader(datei, StandardCharsets.UTF_8)) {
                p.load(rein);
            } catch (IOException | IllegalArgumentException e) {
                LOGGER.warn("Heroic Map: {} nicht lesbar, die Karte beginnt beim Spieler", datei);
            }
        }
        return p;
    }
}
