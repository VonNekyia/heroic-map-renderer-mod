package com.nekyia.heroicmap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * Von Ende zu Ende gegen einen echten Paper-Server mit dem Plugin: verbinden, auf das Angebot
 * warten, den kleinsten Massstab des ersten Baums voll laden, jeden Dialog bestätigen und die
 * Vollbildkarte aufnehmen. Läuft nur mit -Pserver=&lt;adresse&gt;. Der Server braucht dieselbe
 * Version wie der Client und {@code online-mode=false}, denn der Client im Gametest hat keine
 * Mojang-Sitzung, lauscht dann nur auf 127.0.0.1 und lässt den Client an der Whitelist vorbei. Siehe docs/entwicklung.md, „Gametests“.
 */
public final class Server implements FabricClientGameTest {

    private static final String ADRESSE = System.getProperty("heroicmap.server", "");
    /**
     * So lange darf der Download dauern, in Ticks; der Baum eines Testservers ist klein. Bricht
     * der Download ab, endet der Test erst hier, den Grund nennen Chat und Log.
     */
    private static final int DOWNLOAD = 5 * 60 * 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (ADRESSE.isEmpty()) {
            return;
        }
        try {
            // Ein Satz aus einem früheren Lauf liesse den Test vor dem Download enden, auch in der alten Ablage.
            Path wurzel = FabricLoader.getInstance().getGameDir().resolve(HeroicMap.ID);
            Laden.loesche(wurzel.resolve(Downloads.name(ADRESSE)));
            Laden.loesche(wurzel.resolve(Downloads.name(ServerAddress.parseString(ADRESSE).getHost())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // So verbindet auch der Testserver der Fabric API.
        context.runOnClient(mc -> ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(ADRESSE),
                new ServerData("Heroic Map Test", ADRESSE, ServerData.Type.OTHER), false, null));
        context.waitFor(mc -> mc.level != null && mc.player != null || mc.gui.screen() instanceof DisconnectedScreen, 2400);
        if (context.computeOnClient(mc -> mc.gui.screen() instanceof DisconnectedScreen)) {
            // Der Grund steht nur auf dem Schirm, etwa eine Whitelist.
            context.takeScreenshot(TestScreenshotOptions.of("server-abgewiesen").disableCounterPrefix());
            throw new AssertionError("Der Server hat den Client abgewiesen, siehe Bild server-abgewiesen");
        }
        // Das Plugin schickt sein Angebot, sobald der Kanal angemeldet ist.
        context.waitFor(mc -> Kanal.offen() && !Downloads.INSTANZ.baeume().isEmpty(), 2400);
        Downloads.Baum baum = context.computeOnClient(mc -> Downloads.INSTANZ.baeume().getFirst());
        int massstab = baum.bytes().firstKey();
        // Gleich nach dem Login, ohne Wechsel der Dimension: Kennt der Mod den Hash der Welt nicht, kommt hier
        // „Betritt erst …“. Beim Login entsteht das Level vor dem Spieler. Siehe docs/download.md, „Ablage“.
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
        // Meldungen des Servers zum Chat und zu sozialen Interaktionen lägen sonst über der Karte.
        context.runOnClient(mc -> mc.gui.toastManager().clear());
        context.waitTick();
        context.takeScreenshot(TestScreenshotOptions.of("server-karte").disableCounterPrefix());
        context.runOnClient(mc -> {
            mc.gui.setScreen(null);
            mc.level.disconnect(Component.literal("Heroic Map Test"));
            mc.disconnectWithSavingScreen();
            // Ein Gametest endet auf dem Titelbildschirm; nach dem Trennen bliebe die Meldung stehen.
            mc.gui.setScreen(new TitleScreen());
        });
        context.waitFor(mc -> mc.level == null, 1200);
    }

    /** Der vollständig geladene Satz des Baums, oder null, solange der Download läuft. */
    private static Satz satz(Downloads.Baum baum) {
        return Satz.fuer(Downloads.weltOrdner(), baum.dimension());
    }
}
