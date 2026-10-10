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
 * Wo die Vollbildkarte zuletzt stand, je Dimension: die Mitte in Blöcken, die Stufe und die Lupe; dazu,
 * ob die Liste der Ebenen offen ist. Je Welt in {@code karte.properties} im Ordner der Welt, im
 * Einzelspieler nur im Speicher. Nur der Render-Thread. Siehe docs/vollbildkarte.md, „Lage merken“.
 */
final class Kartenlage {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Ohne Ordner, im Einzelspieler: je Dimension nur im Speicher. */
    private static final Map<String, Lage> SPEICHER = new HashMap<>();
    /** Ohne Ordner: ob die Liste der Ebenen offen ist. */
    private static boolean ebenenImSpeicher;

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
        speichere(ordner, p);
    }

    /** Ist die Liste der Ebenen offen? Ohne Eintrag zu, wie beim ersten Öffnen. */
    static boolean ebenenOffen(Path ordner) {
        return ordner == null ? ebenenImSpeicher : Boolean.parseBoolean(datei(ordner).getProperty("ebenen"));
    }

    /** Merkt, ob die Liste der Ebenen offen ist; die Lagen bleiben. */
    static void ebenenOffen(Path ordner, boolean offen) {
        if (ordner == null) {
            ebenenImSpeicher = offen;
            return;
        }
        Properties p = datei(ordner);
        p.setProperty("ebenen", Boolean.toString(offen));
        speichere(ordner, p);
    }

    private static void speichere(Path ordner, Properties p) {
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
        ebenenImSpeicher = false;
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
