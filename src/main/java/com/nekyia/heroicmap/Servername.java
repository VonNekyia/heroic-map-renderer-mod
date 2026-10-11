package com.nekyia.heroicmap;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;

/**
 * Der Name, den ein Server im {@code angebot} meldet, etwa {@code play.example.net}: je eingetippter Adresse in
 * {@code heroicmap/namen.txt}, eine Zeile „Adresse Name“. Er ersetzt in den Ablagen IP und IP:Port die Adresse.
 * Siehe docs/download.md, „Ablage“.
 */
final class Servername {

    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * Nur ein echter Hostname, wie das Plugin ihn schickt: Teile aus a–z, 0–9 und Bindestrich, nicht am Rand, je
     * höchstens 63 Zeichen, durch einzelne Punkte getrennt; zusammen höchstens 253. Nie {@code .}, {@code ..} oder {@code a..b}.
     */
    private static final Pattern NAME = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*");
    static final String DATEI = "namen.txt";

    private Servername() {
    }

    /** Taugt {@code name} als Name eines Servers? Nur ein Hostname, sonst null. */
    static String gueltig(String name) {
        return name != null && name.length() <= 253 && NAME.matcher(name).matches() ? name : null;
    }

    /** Der gemerkte Name für die eingetippte {@code adresse}, oder null. */
    static String lies(Path wurzel, String adresse) {
        String schluessel = schluessel(adresse);
        for (String zeile : zeilen(wurzel)) {
            int leer = zeile.indexOf(' ');
            if (leer > 0 && zeile.substring(0, leer).equals(schluessel)) {
                return gueltig(zeile.substring(leer + 1));
            }
        }
        return null;
    }

    /** Merkt {@code name} für die {@code adresse}; null nimmt ihn heraus. Nur der Render-Thread. */
    static void merke(Path wurzel, String adresse, String name) {
        String schluessel = schluessel(adresse);
        List<String> neu = new ArrayList<>();
        for (String zeile : zeilen(wurzel)) {
            if (!zeile.startsWith(schluessel + " ")) {
                neu.add(zeile);
            }
        }
        if (name != null) {
            neu.add(schluessel + " " + name);
        }
        try {
            Files.createDirectories(wurzel);
            Files.write(wurzel.resolve(DATEI), neu, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Heroic Map: {} nicht geschrieben", wurzel.resolve(DATEI), e);
        }
    }

    /**
     * Zieht den Ordner einer Welt von {@code alt} nach {@code neu}, mit allem darin, als ein Umbenennen; nur wenn
     * {@code alt} da ist und {@code neu} noch nicht. Ein leerer Ordner der Adresse darüber geht mit. True, wenn umgezogen.
     */
    static boolean zieheUm(Path alt, Path neu) throws IOException {
        if (alt.equals(neu) || !Files.isDirectory(alt) || Files.exists(neu)) {
            return false;
        }
        Files.createDirectories(neu.getParent());
        Files.move(alt, neu, StandardCopyOption.ATOMIC_MOVE);
        Path oben = alt.getParent();
        try (Stream<Path> rest = Files.list(oben)) {
            if (rest.findAny().isEmpty()) {
                Files.delete(oben);
            }
        }
        return true;
    }

    /** Die Adresse, wie der Spieler sie eingetippt hat, ohne Gross und Klein und ohne Leerzeichen. */
    private static String schluessel(String adresse) {
        return adresse.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
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
