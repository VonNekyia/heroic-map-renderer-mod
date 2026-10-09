package com.nekyia.heroicmap;

import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Die Reihenfolge beim Login, auf die {@link Downloads#server} baut: Wenn das erste Level kommt,
 * gibt es den Spieler noch nicht und damit keinen Server über {@code getCurrentServer}, wohl aber
 * die Verbindung des Levels. Siehe docs/download.md, „Ablage“.
 */
public final class Anmeldung implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        AtomicReference<String> erstes = new AtomicReference<>();
        // Ereignisse lassen sich nicht abmelden; der Zuhörer merkt sich nur das erste Level.
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> erstes.compareAndSet(null,
                "Spieler " + (client.player != null) + ", Verbindung über den Spieler " + (client.getConnection() != null)
                        + ", Verbindung des Levels " + (level.connection != null)
                        + ", Server gleich " + (Downloads.server() == level.connection.getServerData())));
        try (TestSingleplayerContext spiel = context.worldBuilder().create()) {
            spiel.getConnection().waitForChunksRender();
        }
        String soll = "Spieler false, Verbindung über den Spieler false, Verbindung des Levels true, Server gleich true";
        if (!soll.equals(erstes.get())) {
            throw new AssertionError("Beim ersten Level: " + erstes.get() + ", erwartet: " + soll);
        }
    }
}
