package com.nekyia.heroicmap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongPredicate;
import java.util.regex.Pattern;

/**
 * Der Name der Karte, den das Plugin an der Antwort auf {@code show} schickt, etwa {@code main}: je Sitzung, und je Hash
 * einer Welt, ob er dort gilt. Er setzt im Ordner der Welt {@code karte-<name>} vor {@code welt-<hash>}. Ändern nur
 * auf dem Render-Thread. Siehe docs/download.md, „Ablage“.
 */
final class Kartenname {

    /** Wie das Plugin ihn prüft: klein, a–z, 0–9, _ und -, höchstens 64 Zeichen. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,64}");

    private volatile String karte;
    private final Map<Long, Boolean> gilt = new ConcurrentHashMap<>();

    /** Taugt {@code name} als Name einer Karte? Sonst null. */
    static String gueltig(String name) {
        return name != null && NAME.matcher(name).matches() ? name : null;
    }

    /**
     * Die erste gültige Karte der Sitzung; eine spätere andere gilt erst in der nächsten. Ist sie neu, entscheidet
     * {@code entscheide} für jeden Hash in {@code betreten}, ob sie dort gilt. True, wenn sie jetzt neu gilt.
     */
    boolean setze(String name, Iterable<Long> betreten, LongPredicate entscheide) {
        if (karte != null || gueltig(name) == null) {
            return false;
        }
        karte = name;
        for (long seed : betreten) {
            gilt.put(seed, entscheide.test(seed));
        }
        return true;
    }

    /** Beim Betreten einer Welt: Mit einer Karte entscheidet {@code entscheide} einmal je Hash, ob sie dort gilt. */
    void betreten(long seed, LongPredicate entscheide) {
        if (karte != null && !gilt.containsKey(seed)) {
            gilt.put(seed, entscheide.test(seed));
        }
    }

    /** Die Karte, die jetzt kommen würde oder gilt; null ohne. */
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
        gilt.clear();
    }
}
