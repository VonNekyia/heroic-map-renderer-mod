package com.nekyia.heroicmap;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;

/**
 * Der Name der Karte, den das Plugin an der Antwort auf {@code show} schickt, etwa {@code main}: je Sitzung, und je Hash
 * einer Welt, ob er dort gilt. Er setzt im Ordner der Welt {@code karte-<name>} vor {@code welt-<hash>}. Gemerkt je
 * eingetippter Adresse und Hash in {@code heroicmap/karten.txt}, so gilt er ab der zweiten Sitzung gleich beim
 * Betreten. Ändern nur auf dem Render-Thread. Siehe docs/download.md, „Ablage“.
 */
final class Kartenname {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Wie das Plugin ihn prüft: klein, a–z, 0–9, _ und -, höchstens 64 Zeichen. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,64}");
    static final String DATEI = "karten.txt";

    /** Ob die Karte {@code karte} für die Welt mit dem Hash {@code seed} gilt, siehe {@code Downloads.karteGilt}. */
    interface Gilt {
        boolean test(String karte, long seed);
    }

    private volatile String karte;
    /** Die erste Antwort des Servers in dieser Sitzung kam; danach ändert sich die Karte erst nach einem Login. */
    private volatile boolean bestaetigt;
    private final Map<Long, Boolean> gilt = new ConcurrentHashMap<>();

    /** Taugt {@code name} als Name einer Karte? Sonst null. */
    static String gueltig(String name) {
        return name != null && NAME.matcher(name).matches() ? name : null;
    }

    /**
     * Beim Betreten einer Welt: Vor der ersten Antwort des Servers und ohne Karte gilt die gemerkte {@code gemerkt};
     * mit einer Karte entscheidet {@code entscheide} einmal je Hash, ob sie dort gilt.
     */
    void betreten(long seed, String gemerkt, Gilt entscheide) {
        if (karte == null && !bestaetigt && gueltig(gemerkt) != null) {
            karte = gemerkt;
        }
        if (karte != null && !gilt.containsKey(seed)) {
            gilt.put(seed, entscheide.test(karte, seed));
        }
    }

    /**
     * Die erste Antwort des Servers in der Sitzung, mit Karte oder ohne ({@code null}); spätere gelten erst nach dem
     * nächsten Login. Weicht sie von der gemerkten ab, gilt sie, und {@code entscheide} entscheidet für jeden Hash in
     * {@code betreten} neu. True, wenn sich die Karte änderte.
     */
    boolean vomServer(String name, Iterable<Long> betreten, Gilt entscheide) {
        if (bestaetigt) {
            return false;
        }
        bestaetigt = true;
        if (Objects.equals(name, karte)) {
            return false;
        }
        karte = name;
        gilt.clear();
        if (name != null) {
            for (long seed : betreten) {
                gilt.put(seed, entscheide.test(name, seed));
            }
        }
        return true;
    }

    /** Kam in dieser Sitzung schon eine Antwort des Servers? */
    boolean bestaetigt() {
        return bestaetigt;
    }

    /** Die Karte dieser Sitzung, gemerkt oder vom Server; null ohne. */
    String karte() {
        return karte;
    }

    /** Die Karte für die Welt mit diesem Hash, wenn sie dort in dieser Sitzung gilt; sonst null. */
    String fuer(long seed) {
        return Boolean.TRUE.equals(gilt.get(seed)) ? karte : null;
    }

    /** Ein neuer Login, auch der Wechsel des Backends: Karte und Entscheidungen gehören zum alten. */
    void neueSitzung() {
        karte = null;
        bestaetigt = false;
        gilt.clear();
    }

    /** Die zuletzt gemeldete Karte für die eingetippte {@code adresse} und den Hash {@code seed}, oder null. */
    static String lies(Path wurzel, String adresse, long seed) {
        String vorn = schluessel(adresse, seed) + " ";
        for (String zeile : zeilen(wurzel)) {
            if (zeile.startsWith(vorn)) {
                return gueltig(zeile.substring(vorn.length()));
            }
        }
        return null;
    }

    /** Merkt {@code karte} für die {@code adresse} und jeden Hash in {@code seeds}; null nimmt sie heraus. */
    static void merke(Path wurzel, String adresse, Iterable<Long> seeds, String karte) {
        Set<String> schluessel = new HashSet<>();
        seeds.forEach(s -> schluessel.add(schluessel(adresse, s)));
        List<String> neu = new ArrayList<>();
        for (String zeile : zeilen(wurzel)) {
            int leer = zeile.lastIndexOf(' ');
            if (leer < 0 || !schluessel.contains(zeile.substring(0, leer))) {
                neu.add(zeile);
            }
        }
        if (gueltig(karte) != null) {
            schluessel.stream().sorted().forEach(s -> neu.add(s + " " + karte));
        }
        try {
            Files.createDirectories(wurzel);
            Files.write(wurzel.resolve(DATEI), neu, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Heroic Map: {} nicht geschrieben", wurzel.resolve(DATEI), e);
        }
    }

    /** Die Adresse wie bei {@link Servername}, dazu der Hash, 16 Stellen hex. */
    private static String schluessel(String adresse, long seed) {
        return Servername.schluessel(adresse) + " " + HexFormat.of().toHexDigits(seed);
    }

    private static List<String> zeilen(Path wurzel) {
        Path datei = wurzel.resolve(DATEI);
        try {
            return Files.exists(datei) ? Files.readAllLines(datei, StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) {
            return List.of();
        }
    }
}
