package com.nekyia.heroicmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Was {@link Kachelwerk#schreibe} kostet, ms je Durchlauf, Dateien je Stunde und Platz auf der
 * Platte danach, für eine Farm und einen Flug, je Massstab der eigenen Karte; nur mit
 * {@code -Pkachelwerk=<datei>}, dorthin gehen die Zahlen. Siehe docs/selbst.md, „Kosten“.
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
        for (int massstab : Selbst.MASSSTAEBE) {
            zeilen.add(lauf("Farm", massstab, ordner.resolve("farm-" + massstab), (werk, runde) -> werk.lege(3, 3, chunk(runde, massstab))));
            zeilen.add(lauf("Flug", massstab, ordner.resolve("flug-" + massstab), (werk, runde) -> {
                for (int r = 0; r < FLUG_REIHEN; r++) {
                    for (int i = 0; i < FLUG_BREITE; i++) {
                        werk.lege(runde * FLUG_REIHEN + r, i - FLUG_BREITE / 2, chunk(runde * 1000 + r * 31 + i, massstab));
                    }
                }
            }));
        }
        Files.write(Path.of(System.getProperty("heroicmap.kachelwerk")), zeilen);
    }

    private interface Runde {
        void lege(Kachelwerk werk, int runde);
    }

    /** Eine Stunde: je Runde legen und schreiben; gezählt werden die Dateien, die Zeit je Durchlauf und am Ende der Platz. */
    private static String lauf(String name, int massstab, Path ordner, Runde runde) throws IOException {
        int stufe = Selbst.MAX_ZOOM - Integer.numberOfTrailingZeros(Selbst.SCALE / massstab);
        Kachelwerk werk = new Kachelwerk(ordner, Selbst.KACHEL, 16 * massstab, Selbst.MIN_ZOOM, stufe);
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
        long bytes;
        try (Stream<Path> alle = Files.walk(ordner)) {
            bytes = alle.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        }
        return String.format(Locale.ROOT, "%s %d px: %d Dateien je Stunde; fein ms Median %.2f p95 %.2f; grob ms Median %.2f p95 %.2f; Platte %.1f MiB",
                name, massstab, dateien, ms(fein, 0.5), ms(fein, 0.95), ms(grob, 0.5), ms(grob, 0.95), bytes / 1048576.0);
    }

    private static double ms(List<Long> ns, double anteil) {
        long[] werte = ns.stream().mapToLong(Long::longValue).sorted().toArray();
        return werte[Math.min(werte.length - 1, (int) (anteil * werte.length))] / 1e6;
    }

    /**
     * Ein Chunk wie Gelände mit {@code massstab} Pixeln je Block: je Block eine von 16 Farben, je Pixel
     * leicht anders, wie gemittelte Texel; mit einer Farbe je Block käme 4 px zu klein heraus.
     */
    private static int[] chunk(int samen, int massstab) {
        Random zufall = new Random(samen);
        int[] farben = new int[16];
        for (int i = 0; i < farben.length; i++) {
            farben[i] = zufall.nextInt(0x1000000);
        }
        int seite = 16 * massstab;
        int[] pixel = new int[seite * seite];
        for (int bz = 0; bz < 16; bz++) {
            for (int bx = 0; bx < 16; bx++) {
                int farbe = farben[zufall.nextInt(farben.length)];
                for (int y = 0; y < massstab; y++) {
                    for (int x = 0; x < massstab; x++) {
                        pixel[(bz * massstab + y) * seite + bx * massstab + x] = 0xFF000000 | rauschen(farbe, zufall);
                    }
                }
            }
        }
        return pixel;
    }

    /** Die Farbe, je Kanal um bis zu ±8 verschoben. */
    private static int rauschen(int farbe, Random zufall) {
        int aus = 0;
        for (int k = 0; k < 24; k += 8) {
            aus |= Math.clamp(((farbe >> k) & 0xFF) + zufall.nextInt(17) - 8, 0, 255) << k;
        }
        return aus;
    }
}
