package com.nekyia.heroicmap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.world.level.ChunkPos;

/**
 * Die Stufen der selbst gezeichneten Karte als Bilder, nur mit -PselbstZoom=<ordner>: eine Welt mit Gelände, 4 px, über
 * ein Raster erkundet und ganz geschrieben, dann die Vollbildkarte Stufe für Stufe heraus, je ein Bild, dazu die
 * Kacheln der Platte unter kacheln/. Siehe docs/selbst.md, „Pyramide“ (mod#132).
 */
public final class SelbstZoom implements FabricClientGameTest {

    private static final String AUSGABE = System.getProperty("heroicmap.selbstzoom", "");
    /** Die Mitte des Rasters und sein Abstand in Blöcken. */
    private static final int MITTE = 0, ABSTAND = 96, RASTER = 2;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (AUSGABE.isEmpty()) {
            return;
        }
        try (TestSingleplayerContext spiel = context.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> {
            s.setWorldType(s.getNormalPresetList().getFirst());
            s.setSeed("heroicmap-132");
        }).create(); var _ = Abschluss.langsamerTakt(context, spiel)) {
            var server = spiel.getServer();
            server.runCommand("gamemode spectator @a");
            server.runCommand("time set noon");
            server.runCommand("tp @a " + MITTE + " 160 " + MITTE);
            spiel.getConnection().waitForChunksRender();
            Path welt = context.computeOnClient(mc -> Downloads.weltOrdner());
            context.runOnClient(mc -> {
                if (Selbst.INSTANZ.waehle(mc, 4) != null) {
                    throw new AssertionError("Selbst geht nicht");
                }
            });
            for (int dz = -RASTER; dz <= RASTER; dz++) {
                for (int dx = -RASTER; dx <= RASTER; dx++) {
                    int x = MITTE + dx * ABSTAND, z = MITTE + dz * ABSTAND;
                    server.runCommand("tp @a " + x + " 160 " + z);
                    context.waitTicks(10);
                    spiel.getConnection().waitForChunksRender();
                    context.waitFor(mc -> Selbst.INSTANZ.fertig(new ChunkPos(x >> 4, z >> 4), 3), 2400);
                }
            }
            server.runCommand("tp @a " + MITTE + " 160 " + MITTE);
            context.computeOnClient(mc -> Selbst.INSTANZ.schreibeJetzt()).join();
            Path baum = welt.resolve(Selbst.baum("minecraft:overworld"));
            context.runOnClient(mc -> mc.gui.setScreen(new Karte(Satz.fuer(welt, "minecraft:overworld", Zeigt.OHNE)).bei(MITTE + 0.5, MITTE + 0.5)));
            context.waitTicks(60);
            int k = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
            int breite = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
            int hoehe = context.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
            context.getInput().setCursorPos(breite / 2.0 * k, hoehe / 2.0 * k);
            Path aus = Path.of(AUSGABE);
            Files.createDirectories(aus);
            for (int schritt = 0; schritt <= 4; schritt++) {
                int stufe = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).stufe()[0]);
                Path bild = context.takeScreenshot(TestScreenshotOptions.of("selbstzoom-" + stufe).disableCounterPrefix());
                Files.copy(bild, aus.resolve("selbst-zoom-" + stufe + ".png"), StandardCopyOption.REPLACE_EXISTING);
                context.getInput().scroll(-1);
                context.waitTicks(60);
            }
            context.runOnClient(mc -> mc.gui.setScreen(null));
            kopiere(baum, aus.resolve("kacheln"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void kopiere(Path quelle, Path ziel) throws IOException {
        try (var alle = Files.walk(quelle)) {
            for (Path p : alle.filter(Files::isRegularFile).toList()) {
                Path z = ziel.resolve(quelle.relativize(p).toString());
                Files.createDirectories(z.getParent());
                Files.copy(p, z, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
