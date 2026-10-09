package com.nekyia.heroicmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Was {@link Kachelwerk#schreibe} kostet, ms je Durchlauf und Dateien je Stunde, für eine Farm und
 * für einen Flug; nur mit {@code -Pkachelwerk=<datei>}, dorthin gehen die Zahlen.
 * Siehe docs/selbst.md, „Kosten“.
 */
@EnabledIfSystemProperty(named = "heroicmap.kachelwerk", matches = ".+")
class KachelwerkMessung {

    /** Eine Stunde in Runden zu 5 s; jede zwölfte schreibt auch die groben Stufen. */
    private static final int RUNDEN = 720, GROB_ALLE = 12;
    /** Beim Flug mit 20 Blöcken/s und Sichtweite 12 kommen je 5 s rund 6 Reihen zu 25 Chunks dazu. */
    private static final int FLUG_REIHEN = 6, FLUG_BREITE = 25;

    @Test
    void farmUndFlug(@TempDir Path ordner) throws IOException {
        List<String> zeilen = new ArrayList<>();
        zeilen.add(lauf("Farm", ordner.resolve("farm"), (werk, runde) -> werk.lege(3, 3, chunk(runde))));
        zeilen.add(lauf("Flug", ordner.resolve("flug"), (werk, runde) -> {
            for (int r = 0; r < FLUG_REIHEN; r++) {
                for (int i = 0; i < FLUG_BREITE; i++) {
                    werk.lege(runde * FLUG_REIHEN + r, i - FLUG_BREITE / 2, chunk(runde * 1000 + r * 31 + i));
                }
            }
        }));
        Files.write(Path.of(System.getProperty("heroicmap.kachelwerk")), zeilen);
    }

    private interface Runde {
        void lege(Kachelwerk werk, int runde);
    }

    /** Eine Stunde: je Runde legen und schreiben; gezählt werden die Dateien und die Zeit je Durchlauf. */
    private static String lauf(String name, Path ordner, Runde runde) throws IOException {
        Kachelwerk werk = new Kachelwerk(ordner, 256, 64, 0, 8);
        List<Long> fein = new ArrayList<>(), grob = new ArrayList<>();
        int dateien = 0;
        for (int i = 1; i <= RUNDEN; i++) {
            runde.lege(werk, i);
            boolean alle = i % GROB_ALLE == 0;
            List<Kachelwerk.Kachel> fertig = new ArrayList<>();
            long start = System.nanoTime();
            werk.schreibe(alle, fertig);
            (alle ? grob : fein).add(System.nanoTime() - start);
            dateien += fertig.size();
        }
        return String.format(Locale.ROOT, "%s: %d Dateien je Stunde; fein ms Median %.2f p95 %.2f; grob ms Median %.2f p95 %.2f",
                name, dateien, ms(fein, 0.5), ms(fein, 0.95), ms(grob, 0.5), ms(grob, 0.95));
    }

    private static double ms(List<Long> ns, double anteil) {
        long[] werte = ns.stream().mapToLong(Long::longValue).sorted().toArray();
        return werte[Math.min(werte.length - 1, (int) (anteil * werte.length))] / 1e6;
    }

    /** Ein Chunk wie Gelände: je Block eine von 16 Farben, 4 × 4 Pixel gleich. */
    private static int[] chunk(int samen) {
        Random zufall = new Random(samen);
        int[] farben = new int[16];
        for (int i = 0; i < farben.length; i++) {
            farben[i] = 0xFF000000 | zufall.nextInt(0x1000000);
        }
        int[] pixel = new int[64 * 64];
        for (int bz = 0; bz < 16; bz++) {
            for (int bx = 0; bx < 16; bx++) {
                int farbe = farben[zufall.nextInt(farben.length)];
                for (int y = 0; y < 4; y++) {
                    Arrays.fill(pixel, (bz * 4 + y) * 64 + bx * 4, (bz * 4 + y) * 64 + bx * 4 + 4, farbe);
                }
            }
        }
        return pixel;
    }
}
