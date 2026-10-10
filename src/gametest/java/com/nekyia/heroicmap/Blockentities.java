package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;

/**
 * Blöcke mit Blockentity, gesetzt und abgebaut wie ein Spieler: Truhe, Tür, Schild und Kopf. Die
 * Minimap zeigt sie ohne weiteres Zutun und nach dem Abbauen wieder den Boden; geprüft am Bild auf
 * dem Schirm. Siehe docs/minimap.md, „Neu zeichnen“ (mod#84).
 */
public final class Blockentities implements FabricClientGameTest {

    private static final int LINKS = InputConstants.MOUSE_BUTTON_LEFT, RECHTS = InputConstants.MOUSE_BUTTON_RIGHT;
    /** Gegenstand und Block, wie das Spiel sie nennt. */
    private static final String[][] FAELLE = {{"chest", "chest"}, {"oak_door", "oak_door"}, {"oak_sign", "oak_sign"},
        {"skeleton_skull", "skeleton_skull"}};
    /** Einheiten des GUI je Block: ein Block ist bei GUI-Massstab 2 sechzehn Pixel gross. */
    private static final int ZOOM = 8;
    /** Ab so vielen Kanälen Unterschied gilt ein Pixel als anders. */
    private static final int SCHWELLE = 24;
    /** Mit {@code -Peula=<datei>}: eine angenommene eula.txt für den Server-Fall. Siehe docs/entwicklung.md, „Gametests“. */
    private static final String EULA = System.getProperty("heroicmap.eula", "");
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext spiel = context.worldBuilder().adjustSettings(s -> s.setAllowCommands(true)).create()) {
            spiel.getConnection().waitForChunksRender();
            alle(context, spiel.getServer(), "einzelspieler");
        }
        // Auf einem Server, mit dem der Client übers Netz spricht wie mit dem des Users: nur mit einer
        // eula.txt, die ein Mensch angenommen hat (-Peula); der Test kopiert sie unverändert, er schreibt nie eine.
        Path eula = EULA.isEmpty() ? null : Path.of(EULA);
        if (eula == null || !Files.isRegularFile(eula)) {
            LOGGER.info("Blockentities: ohne -Peula kein Server-Fall");
            return;
        }
        try {
            // Dort liest der Server des Tests sie, im Arbeitsordner des Spiels.
            Files.copy(eula, Path.of("eula.txt"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (TestDedicatedServerContext server = context.worldBuilder().createServer();
                TestDedicatedServerConnection verbindung = server.connect()) {
            verbindung.waitForChunksRender();
            alle(context, server, "server");
        }
    }

    private static void alle(ClientGameTestContext context, TestServerContext server, String wo) {
        server.runCommand("gamemode creative @a");
        server.runCommand("time set noon");
        context.runOnClient(mc -> {
            Minimap.INSTANZ.setzeSkin(Skin.OHNE);
            Minimap.INSTANZ.setzeDrehen(false);
            Minimap.INSTANZ.setzeScale(4);
            Minimap.INSTANZ.setzeZoom(ZOOM);
            mc.player.getInventory().setSelectedSlot(0);
        });
        for (String[] fall : FAELLE) {
            pruefe(context, server, fall[0], fall[1], wo);
        }
    }

    /**
     * Setzt {@code gegenstand} mit einem Rechtsklick zwei Blöcke vor den Spieler und baut ihn mit einem
     * Linksklick wieder ab; dazwischen nur warten, bis die Minimap fertig ist.
     */
    private static void pruefe(ClientGameTestContext context, TestServerContext server, String gegenstand, String block, String wo) {
        // Blick nach Süden, 45° hinab: Das Ziel liegt zwei Blöcke vor dem Spieler, weg vom Kopf auf der Minimap.
        server.runCommand("tp @a 0.5 -60 0.5 0 45");
        server.runCommand("item replace entity @a hotbar.0 with minecraft:" + gegenstand);
        context.waitTicks(5);
        context.waitFor(mc -> Minimap.INSTANZ.fertig(), 1200);
        BlockPos ziel = context.computeOnClient(mc -> mc.hitResult instanceof BlockHitResult h && h.getType() == HitResult.Type.BLOCK
                ? h.getBlockPos().relative(h.getDirection()) : null);
        if (ziel == null) {
            throw new AssertionError(wo + ", " + gegenstand + ": der Spieler blickt auf keinen Block");
        }
        int[] vorher = farben(context, ziel, wo + "-" + gegenstand + "-vorher");

        context.getInput().pressMouse(RECHTS);
        context.waitFor(mc -> block.equals(BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(ziel).getBlock()).getPath()), 100);
        // Das Schild öffnet einen Tick später zum Beschriften; zu, ohne Text.
        context.waitTicks(10);
        context.runOnClient(mc -> {
            if (mc.gui.screen() != null) {
                mc.gui.screen().onClose();
            }
        });
        context.waitFor(mc -> Minimap.INSTANZ.fertig(), 200);
        int gesetzt = anders(vorher, farben(context, ziel, wo + "-" + gegenstand + "-gesetzt"));
        if (gesetzt < 16) {
            throw new AssertionError(wo + ", " + gegenstand + " gesetzt, die Minimap zeigt ihn nicht: " + gesetzt + " Pixel anders");
        }

        // Abbauen wie ein Spieler, mit dem Blick auf das untere Viertel des Blocks; das trifft auch Tür, Schild und Kopf.
        double dx = ziel.getX() + 0.5 - 0.5, dy = ziel.getY() + 0.25 - (-60 + 1.62), dz = ziel.getZ() + 0.5 - 0.5;
        float gier = (float) Math.toDegrees(Math.atan2(-dx, dz)), neigung = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        server.runCommand("tp @a 0.5 -60 0.5 " + gier + " " + neigung);
        context.waitTicks(5);
        context.getInput().pressMouse(LINKS);
        context.waitTicks(20);
        String stand = context.computeOnClient(mc -> mc.level.getBlockState(ziel).isAir() ? null
                : "Ziel " + (mc.hitResult instanceof BlockHitResult h ? h.getType() + " " + h.getBlockPos() : String.valueOf(mc.hitResult))
                + ", Maus gefangen " + mc.mouseHandler.isMouseGrabbed() + ", Schirm " + mc.gui.screen());
        if (stand != null) {
            throw new AssertionError(wo + ", " + gegenstand + " liess sich nicht abbauen: " + stand + ", Block " + ziel);
        }
        context.waitTicks(10);
        context.waitFor(mc -> Minimap.INSTANZ.fertig(), 200);
        int rest = anders(vorher, farben(context, ziel, wo + "-" + gegenstand + "-abgebaut"));
        if (rest > 4) {
            throw new AssertionError(wo + ", " + gegenstand + " abgebaut, die Minimap zeigt ihn noch: " + rest + " Pixel anders");
        }
    }

    /**
     * Die Pixel des Blocks {@code ziel} auf der Minimap im Bildschirmfoto, ohne den Rand des Blocks:
     * wo die Karte ihn zeichnet ({@link Minimap#marke}), ein Block Zoom mal GUI-Massstab Pixel.
     */
    private static int[] farben(ClientGameTestContext context, BlockPos ziel, String name) {
        float[] wo = context.computeOnClient(mc -> {
            int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight(), k = mc.getWindow().getGuiScale();
            Minimap.Rahmen r = Minimap.INSTANZ.rahmen(w, h);
            int n = r.seite() * k;
            int links = Minimap.ecke(mc.player.xo, mc.player.getX(), 1f, ZOOM, k, n);
            int oben = Minimap.ecke(mc.player.zo, mc.player.getZ(), 1f, ZOOM, k, n);
            float[] m = Minimap.marke(r, ziel.getX() + 0.5, ziel.getZ() + 0.5, links, oben, k, ZOOM, false, Double.MAX_VALUE, false, null);
            return new float[] {m[0] * k, m[1] * k, ZOOM * k};
        });
        Path datei = context.takeScreenshot(TestScreenshotOptions.of("blockentities-" + name).disableCounterPrefix());
        try {
            BufferedImage bild = ImageIO.read(datei.toFile());
            int halb = (int) wo[2] / 2 - 1, x0 = Math.round(wo[0]) - halb, y0 = Math.round(wo[1]) - halb;
            return bild.getRGB(x0, y0, 2 * halb, 2 * halb, null, 0, 2 * halb);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Wie viele Pixel sich in einem Kanal um mehr als {@link #SCHWELLE} unterscheiden. */
    private static int anders(int[] a, int[] b) {
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            for (int s = 0; s < 24; s += 8) {
                if (Math.abs(((a[i] >> s) & 0xFF) - ((b[i] >> s) & 0xFF)) > SCHWELLE) {
                    n++;
                    break;
                }
            }
        }
        return n;
    }
}
