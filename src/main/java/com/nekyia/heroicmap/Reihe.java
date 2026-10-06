package com.nekyia.heroicmap;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads nacheinander in einem Thread, je Schlüssel höchstens einer, wartend oder laufend.
 * Siehe docs/download.md, „Reihe“.
 */
final class Reihe {

    private final Set<String> belegt = ConcurrentHashMap.newKeySet();
    /** Zählt bei jedem Abbruch hoch; was aus einer älteren Runde wartet, läuft nicht mehr. */
    private int runde;
    /** Der Thread, der gerade eine Arbeit dieser Runde tut, oder null. */
    private Thread arbeiter;
    /** Der Thread entsteht erst mit dem ersten Download; beim Trennen wäre es zu früh. */
    private ExecutorService thread;

    /** Reiht die Arbeit ein; false, wenn für den Schlüssel schon eine wartet oder läuft. Nur ein Thread ruft das. */
    boolean reihe(String schluessel, Runnable arbeit) {
        if (!belegt.add(schluessel)) {
            return false;
        }
        if (thread == null) {
            thread = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Heroic Map Laden");
                t.setDaemon(true);
                return t;
            });
        }
        int meine = runde();
        thread.execute(() -> {
            try {
                synchronized (this) {
                    if (runde != meine) {
                        return;
                    }
                    arbeiter = Thread.currentThread();
                }
                arbeit.run();
            } finally {
                synchronized (this) {
                    arbeiter = null;
                }
                belegt.remove(schluessel);
            }
        });
        return true;
    }

    /** Wartet oder läuft für den Schlüssel gerade ein Download? */
    boolean belegt(String schluessel) {
        return belegt.contains(schluessel);
    }

    /** Unterbricht die laufende Arbeit und verwirft, was wartet, etwa beim Trennen. */
    synchronized void abbrechen() {
        runde++;
        if (arbeiter != null) {
            arbeiter.interrupt();
        }
    }

    private synchronized int runde() {
        return runde;
    }
}
