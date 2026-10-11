package com.nekyia.heroicmap;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Was vor dem Schliessen einer Einzelspielerwelt geschieht, damit sie sich ohne den Hänger von
 * fabric-client-gametest-api schliesst. Siehe docs/entwicklung.md, „Schliessen der Welt“.
 */
final class Abschluss {

    /** Ein Schritt ohne geprüfte Ausnahme; im {@code try} hinter der Welt, so läuft er vor ihrem {@code close}. */
    interface Schritt extends AutoCloseable {
        @Override
        void close();
    }

    private Abschluss() {
    }

    /**
     * Der Server läuft mit 1 Tick je Sekunde, dem kleinsten Takt von {@code /tick rate}: So hat er nach dem Phaser 1 s
     * statt 50 ms für die Aufgabe, auf die {@code IntegratedServer.halt} wartet. Das mildert nur: Dauert das Aufräumen
     * vor {@code halt} länger, hängt es doch; dann meldet es die Wache, und ein Fix gehört in die Gametest-API.
     */
    static Schritt langsamerTakt(ClientGameTestContext context, TestSingleplayerContext spiel) {
        return () -> {
            spiel.getServer().runCommand("tick rate 1");
            // Erst im nächsten Durchlauf rechnet der Server sein Fenster mit dem neuen Takt.
            context.waitTicks(2);
        };
    }
}
