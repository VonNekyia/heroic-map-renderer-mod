package com.nekyia.heroicmap;

import java.io.IOException;
import java.io.UncheckedIOException;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * Von Ende zu Ende gegen einen echten Paper-Server mit dem Plugin: verbinden, auf das Angebot
 * warten, den kleinsten Massstab des ersten Baums voll laden, jeden Dialog bestätigen und die
 * Vollbildkarte aufnehmen. Läuft nur mit -Pserver=&lt;adresse&gt;. Der Server braucht dieselbe
 * Version wie der Client und {@code online-mode=false}, denn der Client im Gametest hat keine
 * Mojang-Sitzung, und lauscht dann nur auf 127.0.0.1. Siehe docs/entwicklung.md, „Gametests“.
 */
public final class Server implements FabricClientGameTest {

    private static final String ADRESSE = System.getProperty("heroicmap.server", "");
    /** So lange darf der Download dauern, in Ticks. */
    private static final int DOWNLOAD = 30 * 60 * 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (ADRESSE.isEmpty()) {
            return;
        }
        try {
            // Ein Satz aus einem früheren Lauf liesse den Test vor dem Download enden.
            Laden.loesche(FabricLoader.getInstance().getGameDir().resolve(HeroicMap.ID).resolve(Downloads.name(ADRESSE)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // So verbindet auch der Testserver der Fabric API.
        context.runOnClient(mc -> ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(ADRESSE),
                new ServerData("Heroic Map Test", ADRESSE, ServerData.Type.OTHER), false, null));
        context.waitFor(mc -> mc.level != null && mc.player != null, 2400);
        // Das Plugin schickt sein Angebot, sobald der Kanal angemeldet ist.
        context.waitFor(mc -> Kanal.offen() && !Downloads.INSTANZ.baeume().isEmpty(), 2400);
        Downloads.Baum baum = context.computeOnClient(mc -> Downloads.INSTANZ.baeume().getFirst());
        int massstab = baum.bytes().firstKey();
        Component fehler = context.computeOnClient(mc -> Downloads.INSTANZ.frageVoll(baum.id(), massstab));
        if (fehler != null) {
            throw new AssertionError("Kein Download angefragt: " + fehler.getString());
        }
        // Erst der Dialog zur Grösse, nach der freigabe vielleicht der zum Host; jeder bekommt Ja.
        while (context.computeOnClient(mc -> satz(baum)) == null) {
            context.waitFor(mc -> mc.gui.screen() instanceof ConfirmScreen || satz(baum) != null, DOWNLOAD);
            if (context.computeOnClient(mc -> mc.gui.screen() instanceof ConfirmScreen)) {
                context.clickScreenButton("gui.yes");
            }
        }
        context.runOnClient(mc -> mc.gui.setScreen(new Karte(satz(baum))));
        context.waitTicks(100);
        context.takeScreenshot(TestScreenshotOptions.of("server-karte").disableCounterPrefix());
        context.runOnClient(mc -> {
            mc.gui.setScreen(null);
            mc.level.disconnect(Component.literal("Heroic Map Test"));
            mc.disconnectWithSavingScreen();
        });
        context.waitFor(mc -> mc.level == null, 1200);
    }

    /** Der vollständig geladene Satz des Baums, oder null, solange der Download läuft. */
    private static Satz satz(Downloads.Baum baum) {
        return Satz.fuer(Downloads.serverOrdner(), baum.dimension());
    }
}
