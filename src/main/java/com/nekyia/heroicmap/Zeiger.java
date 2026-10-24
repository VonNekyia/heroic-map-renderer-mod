package com.nekyia.heroicmap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Der Zeiger eines Servers auf die Karte eines anderen mit derselben Welt unter derselben Adresse: in {@code zeiger.txt}
 * im eigenen Ordner der Welt nur der Name der Karte des Ziels, {@link Zeigt#OHNE} für den Ordner ohne Karte. Das Ziel
 * liegt so immer neben dem eigenen, mit demselben Hash. Seine Downloads zählen hier wie eigene, nur zum Lesen; eine
 * eigene Karte des Ziels nie. Siehe docs/download.md, „Ablage“.
 */
final class Zeiger {

    static final String DATEI = "zeiger.txt";
    /** Vor einem Baum in zeigt.txt: Er liegt im Ziel des Zeigers. */
    static final String DORT = "@";
    private static final String KARTE = "karte-";

    private Zeiger() {
    }

    /** Der eingetragene Name des Ziels, oder null ohne Zeiger oder mit einem, der nicht taugt. */
    static String name(Path welt) {
        Path datei = welt.resolve(DATEI);
        try {
            if (!Files.isRegularFile(datei, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            String name = Files.readString(datei, StandardCharsets.UTF_8).strip();
            return Zeigt.OHNE.equals(name) || Kartenname.gueltig(name) != null ? name : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Der Ordner der Adresse über {@code welt}: ohne Karte der Elternordner, mit Karte dessen Elternordner. */
    private static Path adresse(Path welt) {
        Path eltern = welt.getParent();
        String name = eltern.getFileName() == null ? "" : eltern.getFileName().toString();
        return name.startsWith(KARTE) && Kartenname.gueltig(name.substring(KARTE.length())) != null ? eltern.getParent() : eltern;
    }

    /** Der Ordner der Welt mit demselben Hash für die Karte {@code name}, {@link Zeigt#OHNE} ohne Karte. */
    static Path ordner(Path welt, String name) {
        Path a = adresse(welt);
        return (Zeigt.OHNE.equals(name) ? a : a.resolve(KARTE + name)).resolve(welt.getFileName().toString());
    }

    /** Das Ziel des Zeigers, wenn es als Ordner daliegt; sonst null, etwa ohne Zeiger oder wenn es fehlt. */
    static Path ziel(Path welt) {
        String name = name(welt);
        if (name == null) {
            return null;
        }
        Path ziel = ordner(welt, name);
        return taugt(welt, ziel) ? ziel : null;
    }

    /**
     * Taugt {@code ziel} als Ziel: ein anderer Ordner als {@code welt}, kein Link und keine Junction. Eine Junction ist
     * für das JDK unter Windows ein Ordner; ihr echter Pfad liegt dann nicht unter dem der Adresse.
     */
    private static boolean taugt(Path welt, Path ziel) {
        try {
            if (ziel.equals(welt) || !Files.isDirectory(ziel, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            Path a = adresse(welt);
            return ziel.toRealPath().equals(a.toRealPath().resolve(a.relativize(ziel)));
        } catch (IOException e) {
            return false;
        }
    }

    /** Die Namen der anderen Ordner derselben Welt unter derselben Adresse: {@link Zeigt#OHNE} zuerst, dann nach Namen. */
    static List<String> kandidaten(Path welt) {
        Path a = adresse(welt);
        String hash = welt.getFileName().toString();
        List<String> namen = new ArrayList<>();
        if (taugt(welt, a.resolve(hash))) {
            namen.add(Zeigt.OHNE);
        }
        try (Stream<Path> ordner = Files.list(a)) {
            for (Path k : ordner.sorted(Comparator.comparing((Path p) -> p.getFileName().toString())).toList()) {
                String n = k.getFileName().toString();
                if (n.startsWith(KARTE) && Kartenname.gueltig(n.substring(KARTE.length())) != null && taugt(welt, k.resolve(hash))) {
                    namen.add(n.substring(KARTE.length()));
                }
            }
        } catch (IOException e) {
            // Ohne lesbaren Ordner der Adresse keine.
        }
        return namen;
    }

    /** Dieser Ordner der Welt zeigt ab jetzt auf die Karte {@code name}. */
    static void setze(Path welt, String name) throws IOException {
        Files.createDirectories(welt);
        Files.writeString(welt.resolve(DATEI), name + "\n", StandardCharsets.UTF_8);
    }

    /** Löst den Zeiger; ohne Zeiger ohne Wirkung. */
    static void loese(Path welt) throws IOException {
        Files.deleteIfExists(welt.resolve(DATEI));
    }
}
