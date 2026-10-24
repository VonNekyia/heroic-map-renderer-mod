package com.nekyia.heroicmap;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;

/**
 * Welche Karte ein Server je Dimension zeigt, in {@code zeigt.txt} im Ordner der Welt: je Zeile Server, Dimension und
 * Baum, getrennt durch ein Leerzeichen. Ohne Zeile gilt die Regel in {@link Satz#fuer}. Siehe docs/download.md,
 * „Ablage“.
 */
final class Zeigt {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String DATEI = "zeigt.txt";
    /** Der Schlüssel eines Servers ohne Namen, auch im Einzelspieler; kein gültiger Name einer Karte hat ihn. */
    static final String OHNE = "-";

    private Zeigt() {
    }

    /** Der Baum, den {@code server} für {@code dimension} gewählt hat, oder null. */
    static String baum(Path welt, String server, String dimension) {
        for (String[] z : zeilen(welt)) {
            if (z[0].equals(server) && z[1].equals(dimension)) {
                return z[2];
            }
        }
        return null;
    }

    /** Die Server, die {@code baum} gewählt haben, in der Reihenfolge der Datei. */
    static List<String> server(Path welt, String baum) {
        List<String> wer = new ArrayList<>();
        for (String[] z : zeilen(welt)) {
            if (z[2].equals(baum)) {
                wer.add(z[0]);
            }
        }
        return wer;
    }

    /** {@code server} zeigt für {@code dimension} den Baum {@code baum}; eine frühere Wahl fällt weg. */
    static void setze(Path welt, String server, String dimension, String baum) throws IOException {
        List<String> neu = new ArrayList<>();
        for (String[] z : zeilen(welt)) {
            if (!(z[0].equals(server) && z[1].equals(dimension))) {
                neu.add(String.join(" ", z));
            }
        }
        neu.add(server + " " + dimension + " " + baum);
        Files.createDirectories(welt);
        Files.write(welt.resolve(DATEI), neu, StandardCharsets.UTF_8);
    }

    /** Die lesbaren Zeilen; eine mit mehr oder weniger als drei Teilen fällt weg. */
    private static List<String[]> zeilen(Path welt) {
        Path datei = welt.resolve(DATEI);
        List<String[]> zeilen = new ArrayList<>();
        try {
            if (!Files.exists(datei)) {
                return zeilen;
            }
            for (String zeile : Files.readAllLines(datei, StandardCharsets.UTF_8)) {
                String[] teile = zeile.strip().split(" ");
                if (teile.length == 3) {
                    zeilen.add(teile);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Heroic Map: {} nicht gelesen", datei, e);
        }
        return zeilen;
    }
}
