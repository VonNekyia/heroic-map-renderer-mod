package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Downloads nacheinander, je Baum höchstens einer. Siehe docs/download.md, „Reihe“. */
class ReiheTest {

    @Test
    void andereBaeumeWartenDerselbeNicht() throws Exception {
        Reihe reihe = new Reihe();
        CountDownLatch los = new CountDownLatch(1), fertig = new CountDownLatch(2);
        List<String> folge = new CopyOnWriteArrayList<>();
        assertTrue(reihe.reihe("welt", () -> {
            warte(los);
            folge.add("welt");
            fertig.countDown();
        }));
        assertTrue(reihe.reihe("nether", () -> {
            folge.add("nether");
            fertig.countDown();
        }));
        // Läuft oder wartet einer für den Baum, kommt kein zweiter dazu.
        assertFalse(reihe.reihe("welt", () -> folge.add("doppelt")));
        assertFalse(reihe.reihe("nether", () -> folge.add("doppelt")));
        assertTrue(reihe.belegt("nether"));
        los.countDown();
        assertTrue(fertig.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("welt", "nether"), folge);
        warteFrei(reihe, "nether");
        assertTrue(reihe.reihe("welt", () -> { }));
    }

    @Test
    void nachEinemErrorFrei() throws Exception {
        Reihe reihe = new Reihe();
        assertTrue(reihe.reihe("welt", () -> {
            throw new OutOfMemoryError("Test");
        }));
        warteFrei(reihe, "welt");
        CountDownLatch lief = new CountDownLatch(1);
        assertTrue(reihe.reihe("welt", lief::countDown));
        assertTrue(lief.await(5, TimeUnit.SECONDS));
    }

    @Test
    void abbrechenUnterbrichtUndVerwirft() throws Exception {
        Reihe reihe = new Reihe();
        CountDownLatch laeuft = new CountDownLatch(1), unterbrochen = new CountDownLatch(1), neu = new CountDownLatch(1);
        List<String> folge = new CopyOnWriteArrayList<>();
        assertTrue(reihe.reihe("server-a/welt", () -> {
            laeuft.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                unterbrochen.countDown();
            }
        }));
        assertTrue(reihe.reihe("server-a/nether", () -> folge.add("alt")));
        assertTrue(laeuft.await(5, TimeUnit.SECONDS));
        // Beim Trennen: Was läuft, bricht ab, was wartet, verfällt; Neues läuft danach ohne Unterbrechung.
        reihe.abbrechen();
        assertTrue(unterbrochen.await(5, TimeUnit.SECONDS));
        warteFrei(reihe, "server-a/nether");
        assertTrue(reihe.reihe("server-b/welt", () -> {
            folge.add(Thread.currentThread().isInterrupted() ? "unterbrochen" : "neu");
            neu.countDown();
        }));
        assertTrue(neu.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("neu"), folge);
    }

    private static void warteFrei(Reihe reihe, String baum) throws InterruptedException {
        for (int i = 0; i < 500 && reihe.belegt(baum); i++) {
            Thread.sleep(10);
        }
        assertFalse(reihe.belegt(baum));
    }

    private static void warte(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
