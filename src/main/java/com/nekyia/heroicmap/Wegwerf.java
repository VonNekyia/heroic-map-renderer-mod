package com.nekyia.heroicmap;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * Wegwerf- und Rundenkarten: eigene Karten, die „Selbst“ ohne Klick des Spielers in einer neuen Welt eines Servers begonnen
 * hat ({@link Selbst#OHNE_KLICK}), wenn die Welt nur an einem Tag besucht oder schon mindestens zweimal zurückgesetzt wurde.
 * Nach 7 Tagen ohne Besuch gehen sie, und alle zusammen höchstens 500 MB, die ältesten zuerst. Nie Downloads, nie von Hand
 * begonnene Karten, nie im Einzelspieler. Siehe docs/selbst.md, „Wegwerfkarten“.
 */
final class Wegwerf {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** So lange ohne Besuch bleibt eine Wegwerfkarte. */
    static final Duration FRIST = Duration.ofDays(7);
    /** So viel dürfen alle Wegwerfkarten zusammen belegen, in Bytes. */
    static final long GRENZE = 500_000_000L;
    /** Ab so vielen Rückfällen ist eine Welt eine Runde. */
    static final int RUNDE = 2;
    private static final String WELT = "welt-";

    /** Eine Wegwerfkarte: der Baum, der letzte Besuch seiner Welt und seine Grösse. */
    record Karte(Path baum, Instant zuletzt, long bytes) {
    }

    private Wegwerf() {
    }

    /** Ist die Welt eine zum Wegwerfen: nur an einem Tag besucht, oder eine Runde? Ohne Ausweis nie. */
    static boolean wegwerf(Ausweis.Welt w) {
        return w != null && (w.tage() <= 1 || w.rueckfaelle() >= RUNDE);
    }

    /**
     * Die Wegwerfkarten unter {@code wurzel}: in jedem Ordner {@code welt-…} bis zur Tiefe der Bäume die eigenen Bäume mit der
     * Marke {@link Selbst#OHNE_KLICK}, deren Welt nach dem Ausweis {@link #wegwerf} ist; nicht im Einzelspieler. Nur ihre
     * Dateien zählt es, nie die Kacheln der Downloads.
     */
    static List<Karte> sammle(Path wurzel) throws IOException {
        List<Karte> aus = new ArrayList<>();
        List<Path> welten;
        try (Stream<Path> alle = Files.walk(wurzel, 3)) {
            welten = alle.filter(p -> p.getFileName().toString().startsWith(WELT) && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
                    && !einzelspieler(wurzel, p)).toList();
        }
        for (Path welt : welten) {
            List<Path> baeume;
            try (Stream<Path> alle = Files.list(welt)) {
                baeume = alle.filter(b -> b.getFileName().toString().startsWith(Selbst.PRAEFIX) && Files.exists(b.resolve(Selbst.OHNE_KLICK))).toList();
            }
            Ausweis a = baeume.isEmpty() ? null : Ausweis.lies(welt);
            for (Path baum : baeume) {
                Satz satz = Satz.lies(baum);
                Ausweis.Welt w = a == null || satz == null ? null : a.welten().get(satz.dimension());
                if (wegwerf(w)) {
                    aus.add(new Karte(baum, w.zuletzt(), bytes(baum)));
                }
            }
        }
        return aus;
    }

    /** Die Bytes aller Dateien im Baum. */
    private static long bytes(Path baum) throws IOException {
        try (Stream<Path> alle = Files.walk(baum)) {
            return alle.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        }
    }

    private static boolean einzelspieler(Path wurzel, Path welt) {
        Path rel = wurzel.relativize(welt);
        return rel.getNameCount() > 0 && rel.getName(0).toString().startsWith(Ort.EINZELSPIELER);
    }

    /**
     * Welche der {@code karten} weg sollen, zur Zeit {@code jetzt}: jede, deren Welt länger als {@link #FRIST} nicht besucht
     * wurde; dann, solange die übrigen zusammen über {@link #GRENZE} liegen, die am längsten nicht besuchten.
     */
    static List<Path> weg(List<Karte> karten, Instant jetzt) {
        List<Path> weg = new ArrayList<>();
        List<Karte> bleiben = new ArrayList<>();
        for (Karte k : karten) {
            if (k.zuletzt().isBefore(jetzt.minus(FRIST))) {
                weg.add(k.baum());
            } else {
                bleiben.add(k);
            }
        }
        bleiben.sort(Comparator.comparing(Karte::zuletzt));
        long summe = bleiben.stream().mapToLong(Karte::bytes).sum();
        for (Karte k : bleiben) {
            if (summe <= GRENZE) {
                break;
            }
            weg.add(k.baum());
            summe -= k.bytes();
        }
        return weg;
    }

    /**
     * Einmal nach dem Start im Hintergrund: sammelt die Wegwerfkarten, wählt die, die weg sollen, und löscht sie im Worker
     * von „Selbst“, nie die, in die er gerade zeichnet. Eine Zeile im Log.
     */
    static void raeume() {
        Path wurzel = Downloads.wurzel();
        List<Path> weg;
        try {
            weg = Files.isDirectory(wurzel) ? weg(sammle(wurzel), Instant.now()) : List.of();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Heroic Map: Wegwerfkarten nicht gezählt", e);
            return;
        }
        if (weg.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            int n = 0;
            for (Path baum : weg) {
                if (Selbst.INSTANZ.zeichnetIn(baum.getParent())) {
                    continue;
                }
                n++;
                Selbst.INSTANZ.loesche(baum, ok -> {
                    if (!ok) {
                        LOGGER.warn("Heroic Map: Wegwerfkarte {} nicht ganz gelöscht", baum);
                    }
                });
            }
            LOGGER.info("Heroic Map: Wegwerfkarten gelöscht: {}", n);
        });
    }
}
