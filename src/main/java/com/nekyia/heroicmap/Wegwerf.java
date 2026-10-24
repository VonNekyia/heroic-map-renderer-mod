package com.nekyia.heroicmap;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * Wegwerf- und Rundenkarten: eigene Karten von Welten eines Servers, die nur an einem Tag besucht oder schon mindestens
 * zweimal zurückgesetzt wurden. Nach 7 Tagen ohne Besuch gehen sie, und alle zusammen höchstens 500 MB, die ältesten
 * zuerst. Nie Downloads, nie im Einzelspieler. Siehe docs/selbst.md, „Wegwerfkarten“.
 */
final class Wegwerf {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** So lange ohne Besuch bleibt eine Wegwerfkarte. */
    static final Duration FRIST = Duration.ofDays(7);
    /** So viel dürfen alle Wegwerfkarten zusammen belegen, in Bytes. */
    static final long GRENZE = 500_000_000L;
    /** Ab so vielen Rückfällen ist eine Welt eine Runde. */
    static final int RUNDE = 2;

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
     * Die Wegwerfkarten im {@code bestand} unter {@code wurzel}: eigene Bäume ({@link Selbst#selbst}), deren Welt nach dem
     * Ausweis ihres Ordners {@link #wegwerf} ist; nicht im Einzelspieler.
     */
    static List<Karte> sammle(Path wurzel, List<Laden.AufPlatte> bestand) {
        List<Karte> aus = new ArrayList<>();
        for (Laden.AufPlatte e : bestand) {
            Path baum = e.ordner(), welt = baum.getParent();
            if (e.satz() == null || !Selbst.selbst(baum) || welt == null || einzelspieler(wurzel, welt)) {
                continue;
            }
            Ausweis a = Ausweis.lies(welt);
            Ausweis.Welt w = a == null ? null : a.welten().get(e.satz().dimension());
            if (wegwerf(w)) {
                aus.add(new Karte(baum, w.zuletzt(), e.bytes()));
            }
        }
        return aus;
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
     * Einmal nach dem Start im Hintergrund: zählt den Bestand, wählt die Wegwerfkarten, die weg sollen, und löscht sie im Worker
     * von „Selbst“, nie die, in die er gerade zeichnet. Eine Zeile im Log.
     */
    static void raeume(BooleanSupplier abbruch) {
        Path wurzel = Downloads.wurzel();
        List<Path> weg;
        try {
            weg = weg(sammle(wurzel, Laden.bestand(wurzel, abbruch).karten()), Instant.now());
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
