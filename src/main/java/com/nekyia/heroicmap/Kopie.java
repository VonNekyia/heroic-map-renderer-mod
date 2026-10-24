package com.nekyia.heroicmap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * „Karte von … kopieren“: Ein Baum einer anderen Karte derselben Welt wird die eigene Karte eines Servers, und „Selbst“
 * zeichnet darüber. Je Kachel ein Hardlink: Die Kopie kostet kaum Platz, bis eine Kachel neu gezeichnet ist. Beide Schreiber
 * ersetzen eine Kachel über eine Zwischendatei mit {@code ATOMIC_MOVE}; der Link bricht so je Kachel auf, und die Karte, aus der
 * sie stammt, bleibt. Siehe docs/selbst.md, „Kopie“.
 */
final class Kopie {

    /** Eine Kachel unter dem Ordner des Massstabs: {@code z/x/y.webp} oder {@code .png}. */
    private static final Pattern KACHEL = Pattern.compile("-?\\d+/-?\\d+/-?\\d+\\.(webp|png)");

    private Kopie() {
    }

    /**
     * Legt die eigene Karte {@code ziel} von {@code server} als Kopie des Baums {@code quelle} an: die Kacheln des gezeigten
     * Massstabs, je Hardlink ({@code hart}) oder als Datei, {@code map.json} mit der Pyramide ohne Neubau, {@code satz.json}
     * mit Name, Dimension und Massstab der Quelle, und die Marke einer eigenen Karte. Nie auf Bestehendes; scheitert ein
     * Schritt, ist {@code ziel} wieder weg.
     */
    static void kopiere(Path quelle, Path ziel, String server, boolean hart) throws IOException {
        Satz satz = Satz.lies(quelle);
        if (satz == null || Files.exists(ziel, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Kopie: " + quelle + " nach " + ziel + " geht nicht");
        }
        Path von = satz.ordner(), nach = ziel.resolve(von.getFileName().toString());
        try {
            Files.createDirectories(nach);
            JsonObject karte = JsonParser.parseString(Files.readString(von.resolve("map.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            // Die Stufen der Quelle sind schon da; ohne den Eintrag baute das Kachelwerk sie neu, aus Kacheln, die es nicht kennt.
            karte.addProperty(Kachelwerk.DOWNSCALE, Kachelwerk.NEAREST);
            Files.writeString(nach.resolve("map.json"), karte.toString(), StandardCharsets.UTF_8);
            for (Path datei : kacheln(von)) {
                Path hin = nach.resolve(von.relativize(datei).toString());
                Files.createDirectories(hin.getParent());
                if (hart) {
                    Files.createLink(hin, datei);
                } else {
                    Files.copy(datei, hin);
                }
            }
            Files.writeString(ziel.resolve(Selbst.MARKE), "Heroic Map: selbst gezeichnet, " + satz.dimension() + ", Kopie\n",
                    StandardCharsets.UTF_8);
            Satz.schreibe(ziel, satz.name(), satz.dimension(), satz.massstab(), server);
        } catch (IOException | RuntimeException e) {
            IOException fehler = e instanceof IOException io ? io : new IOException(e);
            try {
                // Ein Link ist nur ein Name; Löschen trifft nie die Quelle.
                Laden.loesche(ziel);
            } catch (IOException | RuntimeException x) {
                fehler.addSuppressed(x);
            }
            throw fehler;
        }
    }

    /** Die Kacheln unter dem Ordner {@code massstab}, ohne Zwischendateien, Stand des Downloads und Ordner {@code tmp}. */
    private static List<Path> kacheln(Path massstab) throws IOException {
        try (Stream<Path> alle = Files.walk(massstab)) {
            return alle.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> KACHEL.matcher(massstab.relativize(p).toString().replace('\\', '/')).matches()).toList();
        }
    }

    /** Kann das Laufwerk unter {@code ordner} Hardlinks? Ein Versuch mit einer leeren Datei, die gleich wieder geht. */
    static boolean hartMoeglich(Path ordner) {
        Path probe = ordner.resolve(".heroicmap-link-probe"), link = ordner.resolve(".heroicmap-link-probe-2");
        try {
            Files.deleteIfExists(link);
            Files.deleteIfExists(probe);
            Files.createFile(probe);
            Files.createLink(link, probe);
            return Files.isSameFile(link, probe);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return false;
        } finally {
            try {
                Files.deleteIfExists(link);
                Files.deleteIfExists(probe);
            } catch (IOException e) {
                // Zwei leere Dateien stören nicht; die Kartenliste zählt sie nicht als Karte.
            }
        }
    }
}
