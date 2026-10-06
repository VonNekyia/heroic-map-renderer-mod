package com.nekyia.heroicmap;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads nacheinander in einem Thread, je Baum höchstens einer, wartend oder laufend.
 * Siehe docs/download.md, „Reihe“.
 */
final class Reihe {

    private final Set<String> baeume = ConcurrentHashMap.newKeySet();
    /** Der Thread entsteht erst mit dem ersten Download; beim Trennen wäre es zu früh. */
    private ExecutorService thread;

    /** Reiht die Arbeit für {@code baum} ein; false, wenn für ihn schon eine wartet oder läuft. Nur ein Thread ruft das. */
    boolean reihe(String baum, Runnable arbeit) {
        if (!baeume.add(baum)) {
            return false;
        }
        if (thread == null) {
            thread = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Heroic Map Laden");
                t.setDaemon(true);
                return t;
            });
        }
        thread.execute(() -> {
            try {
                arbeit.run();
            } finally {
                baeume.remove(baum);
            }
        });
        return true;
    }

    /** Wartet oder läuft für {@code baum} gerade ein Download? */
    boolean belegt(String baum) {
        return baeume.contains(baum);
    }
}
