package com.nekyia.heroicmap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;

/**
 * Das Menü mit echten Eingaben der Maus: links ziehen verschiebt die Minimap, rechts ziehen
 * schaut sich in ihr um, beim Schliessen steht wieder der Spieler in der Mitte.
 * Siehe docs/minimap.md, „Bedienung“.
 */
public final class Bedienung implements FabricClientGameTest {

    private static final int LINKS = 0, RECHTS = 1;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext spiel = context.worldBuilder().create()) {
            spiel.getConnection().waitForChunksRender();
            context.runOnClient(mc -> mc.gui.setScreen(new Einstellungen()));
            context.waitTicks(2);
            int k = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
            TestInput maus = context.getInput();

            Minimap.Rahmen vorher = rahmen(context);
            ziehe(context, maus, LINKS, vorher, -10 * k, 5 * k);
            Minimap.Rahmen nachher = rahmen(context);
            if (Math.abs(nachher.x() - (vorher.x() - 100)) > 1 || Math.abs(nachher.y() - (vorher.y() + 50)) > 1) {
                throw new AssertionError("Links ziehen: " + vorher + " → " + nachher);
            }

            ziehe(context, maus, RECHTS, nachher, 8 * k, 0);
            double erwartet = -80.0 / context.computeOnClient(mc -> Minimap.INSTANZ.zoom());
            double versatz = context.computeOnClient(mc -> Minimap.INSTANZ.versatzX());
            if (Math.abs(versatz - erwartet) > 1) {
                throw new AssertionError("Rechts ziehen: Versatz " + versatz + " statt " + erwartet);
            }

            context.runOnClient(mc -> mc.gui.setScreen(null));
            if (context.computeOnClient(mc -> Minimap.INSTANZ.versatzX()) != 0) {
                throw new AssertionError("Nach dem Schliessen nicht zentriert");
            }
        } finally {
            // Das Menü speichert beim Schliessen; danach wieder die Vorgabe.
            try {
                Files.deleteIfExists(HeroicMap.einstellungen());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            context.runOnClient(mc -> Minimap.INSTANZ.lies(HeroicMap.einstellungen()));
        }
    }

    private static Minimap.Rahmen rahmen(ClientGameTestContext context) {
        return context.computeOnClient(mc -> Minimap.INSTANZ.rahmen(
                mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()));
    }

    /** Greift die Minimap in ihrer Mitte und zieht sie zehnmal um (dx, dy) Pixel des Fensters. */
    private static void ziehe(ClientGameTestContext context, TestInput maus, int taste, Minimap.Rahmen r, int dx, int dy) {
        int k = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
        maus.setCursorPos((r.x() + r.seite() / 2.0) * k, (r.y() + r.seite() / 2.0) * k);
        context.waitTick();
        maus.holdMouse(taste);
        context.waitTick();
        for (int i = 0; i < 10; i++) {
            maus.moveCursor(dx, dy);
            context.waitTick();
        }
        maus.releaseMouse(taste);
        context.waitTick();
    }
}
