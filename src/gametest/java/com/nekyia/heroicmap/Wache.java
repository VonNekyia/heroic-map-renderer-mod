package com.nekyia.heroicmap;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.Arrays;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;

/**
 * Die Frist der Gametests: Kommt {@value #FRIST_S} s lang kein Tick des Clients, schreibt die Wache alle Threads ins
 * Log und beendet das Spiel mit Fehler. Im Gametest tickt der Client nur im Takt mit dem Test und dem Server; hängt
 * einer der drei, steht alles, und Fabric wartet ohne Frist. Siehe docs/entwicklung.md, „Gametests“.
 */
public final class Wache implements ClientModInitializer {

    static final int FRIST_S = 120;
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Zeit des letzten Ticks in ns; 0 bis zum ersten, so zählt der Start des Spiels nicht mit. */
    private static volatile long letzterTick;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> letzterTick = System.nanoTime());
        Thread t = new Thread(Wache::wache, "Heroic Map Wache");
        t.setDaemon(true);
        t.start();
    }

    private static void wache() {
        while (letzterTick == 0 || System.nanoTime() - letzterTick < FRIST_S * 1_000_000_000L) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
        ThreadInfo[] threads = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true);
        StringBuilder s = new StringBuilder("Gametest hängt: seit ").append(FRIST_S).append(" s kein Tick des Clients, ")
                .append(stelle(threads)).append(". Alle Threads:");
        for (ThreadInfo t : threads) {
            s.append("\n\"").append(t.getThreadName()).append("\" ").append(t.getThreadState());
            if (t.getLockName() != null) {
                s.append(" auf ").append(t.getLockName());
            }
            if (t.getLockOwnerName() != null) {
                s.append(", gehalten von \"").append(t.getLockOwnerName()).append('"');
            }
            for (StackTraceElement e : t.getStackTrace()) {
                s.append("\n    at ").append(e);
            }
        }
        LOGGER.error(s.toString());
        Runtime.getRuntime().halt(1);
    }

    /** Wo der Test steht: der oberste Rahmen aus diesem Mod im Thread, der einen Gametest ausführt. */
    private static String stelle(ThreadInfo[] threads) {
        for (ThreadInfo t : threads) {
            StackTraceElement[] st = t.getStackTrace();
            if (Arrays.stream(st).anyMatch(e -> e.getMethodName().equals("runTest") && e.getClassName().startsWith("com.nekyia.heroicmap."))) {
                return Arrays.stream(st).filter(e -> e.getClassName().startsWith("com.nekyia.heroicmap.")).findFirst()
                        .map(e -> "im Test bei " + e).orElseThrow();
            }
        }
        return "ausserhalb eines Tests";
    }
}
