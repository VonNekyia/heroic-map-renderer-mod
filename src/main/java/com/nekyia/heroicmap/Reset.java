package com.nekyia.heroicmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Ob eine Welt zurückgesetzt wurde, aus Ausweis, UID vom Plugin und Spielzeit, und was dann mit ihren Bäumen geschieht: Bei
 * einem sicheren Reset löscht der Mod sie, bei einem unsicheren ruhen sie. Siehe docs/download.md, „Reset“.
 */
final class Reset {

    /** Wie sicher ein Reset ist. */
    enum Art { GLEICH, SICHER, UNSICHER }

    /** Die Marke eines ruhenden Baums; {@link Satz#fuer} zeigt ihn nie. */
    static final String RUHT = "ruht.txt";
    /** Unsicher erst, wenn die Spielzeit um mehr als eine Stunde fällt, in Ticks; an echten Servern nachzuschärfen. */
    static final long STUNDE = 72_000;
    /** Und unter diesen Teil der letzten fällt; an echten Servern nachzuschärfen. */
    static final int TEIL = 4;
    private static final Pattern UID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final DateTimeFormatter TAG = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int MAX_NAME = 64;

    private Reset() {
    }

    /** Taugt {@code uid} als UID einer Welt, klein mit Bindestrichen? */
    static boolean uid(String uid) {
        return uid != null && UID.matcher(uid).matches();
    }

    /**
     * Der Entscheid für eine Welt beim Betreten: {@code alt}, wie der Ausweis sie kennt, oder null; die UID vom Plugin oder
     * null; die Spielzeit jetzt oder -1. Mit beiden UIDs entscheidet nur die UID. Ohne UID vom Plugin ist ein Reset unsicher,
     * wenn die Spielzeit unter ein Viertel der letzten fällt und um mehr als eine Stunde.
     */
    static Art pruefe(Ausweis.Welt alt, String uid, long alter) {
        if (alt == null) {
            return Art.GLEICH;
        }
        if (uid != null) {
            return alt.uid() == null || alt.uid().equals(uid) ? Art.GLEICH : Art.SICHER;
        }
        long zuletzt = alt.alterZuletzt();
        return alter >= 0 && zuletzt > 0 && alter < zuletzt / TEIL && zuletzt - alter > STUNDE ? Art.UNSICHER : Art.GLEICH;
    }

    /**
     * Die Ordner, deren Welt {@code schluessel} durch den Ordner {@code welt} mit dem Ausweis {@code jetzt} ersetzt ist: derselbe
     * Server, also dieselbe eingetippte Adresse und derselbe Name, dieselbe Welt, ein anderer Hash, und der alte Ordner war
     * zuletzt besucht, bevor der neue zum ersten Mal. Nur Geschwister unter {@code karte-<server>}; ohne Netz oder Namen
     * keine. Sonst löschte etwa ein Spiegel mit gleichem Namen die Karte des Servers, den er spiegelt, und umgekehrt.
     */
    static List<Path> veraltet(Path welt, Ausweis jetzt, String schluessel) {
        Ausweis.Welt neu = jetzt.welten().get(schluessel);
        Path oben = welt.getParent();
        if (neu == null || jetzt.netz().isEmpty() || jetzt.server().isEmpty() || oben == null
                || !oben.getFileName().toString().equals("karte-" + jetzt.server())) {
            return List.of();
        }
        List<Path> aus = new ArrayList<>();
        try (Stream<Path> geschwister = Files.list(oben)) {
            for (Path g : geschwister.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList()) {
                Ausweis a = g.equals(welt) ? null : Ausweis.lies(g);
                Ausweis.Welt w = a == null ? null : a.welten().get(schluessel);
                if (w != null && a.netz().equals(jetzt.netz()) && a.server().equals(jetzt.server()) && !a.hash().equals(jetzt.hash())
                        && w.zuletzt().isBefore(neu.erste())) {
                    aus.add(g);
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return aus;
    }

    /** Die Bäume der Welt {@code schluessel} im Ordner {@code welt}, Downloads und eigene Karten, ohne ruhende. */
    static List<Path> baeume(Path welt, String schluessel) {
        try (Stream<Path> alle = Files.list(welt)) {
            return alle.filter(b -> Files.isDirectory(b, LinkOption.NOFOLLOW_LINKS) && !Files.exists(b.resolve(RUHT)))
                    .filter(b -> {
                        Satz s = Satz.lies(b);
                        return s != null && schluessel.equals(s.dimension());
                    }).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Der Name, unter dem ein Baum ruht: {@code <baum>-bis-<jjjjmmtt>}, höchstens 64 Zeichen, mit {@code -2}, {@code -3} …,
     * wenn {@code belegt} ihn schon kennt, etwa ein Ordner oder ein Baum aus dem Angebot. Ein Download besteht damit
     * {@link Freigabe#baum}; eine eigene Karte behält {@code selbst-}, das kein Baum vom Server haben kann.
     */
    static String ruhename(String baum, LocalDate tag, Set<String> belegt) {
        for (int n = 1; ; n++) {
            String ende = "-bis-" + TAG.format(tag) + (n == 1 ? "" : "-" + n);
            String name = baum.substring(0, Math.min(baum.length(), MAX_NAME - ende.length())) + ende;
            if (!belegt.contains(name)) {
                return name;
            }
        }
    }
}
