package com.nekyia.heroicmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Was eine Kachel im Faden des Dekoders kostet, mit und ohne Relief: je Kachel eines Baums Lesen und Dekodieren, mit
 * {@code -PreliefArt=} einer anderen Art als gebacken dazu Schicht und Rest lesen und {@link Relief#rechne}. Das Füllen des
 * {@code NativeImage} ist in beiden gleich und fehlt. Nur mit {@code -PreliefMessung=<datei>} und
 * {@code -PreliefBaum=<ordner des Massstabs>}. Siehe docs/vollbildkarte.md, „Relief“.
 */
@EnabledIfSystemProperty(named = "heroicmap.relief.messung", matches = ".+")
class ReliefMessung {

    /** Runden über alle Kacheln; die erste wärmt nur auf. */
    private static final int RUNDEN = 11;

    @Test
    void jeKachel() throws IOException {
        Path baum = Path.of(System.getProperty("heroicmap.relief.baum"));
        Relief.Satzrelief relief = Relief.von(baum);
        Relief.Art art = Relief.Art.von(System.getProperty("heroicmap.relief.art", ""), relief.gebacken());
        int seite = com.google.gson.JsonParser.parseString(Files.readString(baum.resolve("map.json"))).getAsJsonObject().get("tileSize").getAsInt();
        List<Path> kacheln;
        try (Stream<Path> alle = Files.walk(baum)) {
            kacheln = alle.filter(p -> p.toString().endsWith(".webp") && !baum.relativize(p).startsWith("relief")).sorted().toList();
        }
        List<String> zeilen = new ArrayList<>();
        zeilen.add(String.format(Locale.ROOT, "baum %s gebacken=%s art=%s rechnet=%b kacheln=%d seite=%d", baum.getFileName(),
                relief.gebacken().name, art.name, relief.noetig(art), kacheln.size(), seite));
        List<Long> alle = new ArrayList<>();
        for (int runde = 0; runde < RUNDEN; runde++) {
            List<Long> ns = new ArrayList<>();
            for (Path k : kacheln) {
                long start = System.nanoTime();
                int[] argb = Kacheln.dekodiere(Files.readAllBytes(k), seite).argb();
                if (relief.noetig(art)) {
                    Path rel = baum.relativize(k);
                    Relief.rechne(argb, lies(baum.resolve("relief").resolve(rel), seite), lies(baum.resolve("relief/rest").resolve(rel), seite),
                            relief.gebacken(), art);
                }
                ns.add(System.nanoTime() - start);
            }
            if (runde > 0) {
                alle.addAll(ns);
                zeilen.add(String.format(Locale.ROOT, "runde=%d median=%.3f ms p90=%.3f ms", runde, ms(ns, 0.5), ms(ns, 0.9)));
            }
        }
        zeilen.add(String.format(Locale.ROOT, "gesamt n=%d median=%.3f ms p90=%.3f ms", alle.size(), ms(alle, 0.5), ms(alle, 0.9)));
        Files.write(Path.of(System.getProperty("heroicmap.relief.messung")), zeilen);
    }

    private static int[] lies(Path webp, int seite) throws IOException {
        return Files.exists(webp) ? Kacheln.dekodiere(Files.readAllBytes(webp), seite).argb() : null;
    }

    private static double ms(List<Long> ns, double anteil) {
        long[] werte = ns.stream().mapToLong(Long::longValue).sorted().toArray();
        return werte[Math.min(werte.length - 1, (int) (anteil * werte.length))] / 1e6;
    }
}
