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
 * Der Zeiger eines Servers auf die Karte eines anderen mit derselben Welt: in {@code zeiger.txt} im eigenen Ordner der
 * Welt der Name der Karte des Ziels unter derselben Adresse, {@link Zeigt#OHNE} für den Ordner ohne Karte, oder unter
 * einer anderen Adresse {@code @<adresse>} oder {@code @<adresse>/karte-<name>}. Das Ziel hat so immer denselben Hash.
 * Seine Downloads zählen hier wie eigene, nur zum Lesen; eine eigene Karte des Ziels nie. Siehe docs/download.md, „Ablage“.
 */
final class Zeiger {

    static final String DATEI = "zeiger.txt";
    /** Vor einem Baum in zeigt.txt: Er liegt im Ziel des Zeigers. */
    static final String DORT = "@";
    /** Vor einem Ziel in zeiger.txt: Es liegt unter einer anderen Adresse. */
    static final String FREMD = "@";
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
            return Zeigt.OHNE.equals(name) || Kartenname.gueltig(name) != null || fremd(welt, name) ? name : null;
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

    /** {@code heroicmap/} über der Adresse von {@code welt}; null, wenn {@code welt} unter keiner Adresse liegt, etwa bei der Ablage Hash. */
    private static Path wurzel(Path welt) {
        Path w = adresse(welt).getParent();
        return w != null && w.getFileName() != null && HeroicMap.ID.equals(w.getFileName().toString()) ? w : null;
    }

    /** Der Host eines Adressordners ohne {@code _25565}, wie ihn {@link Ort#doppelt} vergleicht. */
    private static String host(String adresse) {
        String standard = "_" + Downloads.STANDARDPORT;
        return adresse.endsWith(standard) ? adresse.substring(0, adresse.length() - standard.length()) : adresse;
    }

    /**
     * Taugt {@code name} als Ziel unter einer anderen Adresse: {@code @<adresse>} oder {@code @<adresse>/karte-<name>}, die
     * Adresse ein Ordnername, wie {@link Downloads#name} ihn bildet, mit einem anderen Host als dem eigenen und kein
     * Einzelspieler. Nur, wenn auch {@code welt} unter einer Adresse liegt. So kommt der Zeiger nie aus {@code heroicmap/}.
     */
    static boolean fremd(Path welt, String name) {
        if (!name.startsWith(FREMD) || wurzel(welt) == null) {
            return false;
        }
        String[] teile = name.substring(FREMD.length()).split("/", -1);
        String a = teile[0];
        return teile.length <= 2 && Downloads.name(a).equals(a) && !a.startsWith(Ort.EINZELSPIELER)
                && !host(a).equals(host(adresse(welt).getFileName().toString()))
                && (teile.length == 1 || teile[1].startsWith(KARTE) && Kartenname.gueltig(teile[1].substring(KARTE.length())) != null);
    }

    /** Der Ordner der Welt mit demselben Hash für die Karte {@code name}, {@link Zeigt#OHNE} ohne Karte, {@link #fremd} unter einer anderen Adresse. */
    static Path ordner(Path welt, String name) {
        if (name.startsWith(FREMD)) {
            return wurzel(welt).resolve(name.substring(FREMD.length())).resolve(welt.getFileName().toString());
        }
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
     * für das JDK unter Windows ein Ordner; ihr echter Pfad liegt dann nicht unter dem der Adresse, bei einer anderen
     * Adresse nicht unter dem von {@code heroicmap/}.
     */
    private static boolean taugt(Path welt, Path ziel) {
        try {
            if (ziel.equals(welt) || !Files.isDirectory(ziel, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            Path a = adresse(welt), basis = ziel.startsWith(a) ? a : a.getParent();
            return ziel.toRealPath().equals(basis.toRealPath().resolve(basis.relativize(ziel)));
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

    /**
     * Die Ordner derselben Welt unter anderen Adressen als Ziel {@link #fremd}, nach Namen: nur mit einem Download der Welt
     * {@code schluessel}, nicht unter derselben eingetippten Adresse und, kennen beide Ausweise eine UID, mit derselben.
     * Nur ein Vorschlag; derselbe Seed kann eine andere Welt sein. Siehe docs/download.md, „Kartenliste“.
     */
    static List<String> fremde(Path welt, String schluessel) {
        Path w = wurzel(welt);
        List<String> namen = new ArrayList<>();
        if (w == null) {
            return namen;
        }
        Ausweis eigen = Ausweis.lies(welt);
        for (Path a : unterordner(w)) {
            String n = FREMD + a.getFileName();
            List<String> ziele = new ArrayList<>(List.of(n));
            for (Path k : unterordner(a)) {
                ziele.add(n + "/" + k.getFileName());
            }
            for (String z : ziele) {
                if (fremd(welt, z) && passt(welt, ordner(welt, z), eigen, schluessel)) {
                    namen.add(z);
                }
            }
        }
        return namen;
    }

    /** Die Ordner in {@code ordner} nach Namen, ohne Links; leer, wenn er nicht zu lesen ist. */
    private static List<Path> unterordner(Path ordner) {
        try (Stream<Path> alle = Files.list(ordner)) {
            return alle.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString())).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean passt(Path welt, Path ziel, Ausweis eigen, String schluessel) {
        if (!taugt(welt, ziel) || Reset.baeume(ziel, schluessel).stream().allMatch(Selbst::selbst)) {
            return false;
        }
        Ausweis dort = Ausweis.lies(ziel);
        if (eigen == null || dort == null) {
            return true;
        }
        Ausweis.Welt e = eigen.welten().get(schluessel), d = dort.welten().get(schluessel);
        return (eigen.netz().isEmpty() || !eigen.netz().equals(dort.netz()))
                && (e == null || d == null || e.uid() == null || d.uid() == null || e.uid().equals(d.uid()));
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
