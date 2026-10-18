package com.nekyia.heroicmap;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

/**
 * Verkleinern statt vergrössern (mod#132): Ein Satz, dem die Stufe 1 auf der Platte fehlt und dessen Stufe 0 ganz rot ist.
 * Geöffnet auf der feinsten Stufe 2, eine Stufe heraus: Wo die Stufe 2 Kacheln hat, in der Mitte, steht statt der roten
 * Stufe 0, vergrössert, die geladene Stufe 2, verkleinert, und nicht der dunkle Grund. Am Rand reicht nur die Stufe 0 so
 * weit, dort bleibt sie. Siehe docs/vollbildkarte.md, „Kacheln“.
 */
public final class Zoomstufen implements FabricClientGameTest {

    private static final int ROT = 0xFFFF0000;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext spiel = context.worldBuilder().create(); var _ = Abschluss.langsamerTakt(context, spiel)) {
            spiel.getConnection().waitForChunksRender();
            Path baum = satzOhneStufe1();
            context.runOnClient(mc -> mc.gui.setScreen(new Karte(Satz.lies(baum))));
            context.waitTicks(20);
            int[] stufe = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).stufe());
            if (stufe[0] != 2) {
                throw new AssertionError("Die Karte öffnet nicht auf der feinsten Stufe 2, sondern auf " + stufe[0]);
            }
            int k = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
            int breite = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
            int hoehe = context.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
            context.getInput().setCursorPos(breite / 2.0 * k, hoehe / 2.0 * k);
            context.getInput().scroll(-1);
            context.waitTicks(20);
            Path bild = context.takeScreenshot(TestScreenshotOptions.of("zoom-heraus-ohne-stufe").disableCounterPrefix());
            context.runOnClient(mc -> mc.gui.setScreen(null));
            long[] mitte = mitte(bild);
            if (mitte[0] > 0 || mitte[1] > mitte[2] / 10) {
                throw new AssertionError("Eine Stufe heraus, ohne Stufe 1 auf der Platte, in der Mitte: " + mitte[0] + " rote und " + mitte[1]
                        + " dunkle von " + mitte[2] + " Pixeln, statt der geladenen Stufe 2");
            }
        }
    }

    /** Der Testsatz, ohne Stufe 1 und mit einer ganz roten Stufe 0 als PNG, die die Karte auch liest. */
    private static Path satzOhneStufe1() {
        Path quelle = Bilder.testsatz();
        Path baum = quelle.resolveSibling("zoomstufen");
        try {
            try (Stream<Path> alle = Files.walk(quelle)) {
                for (Path p : alle.filter(Files::isRegularFile).toList()) {
                    Path rel = quelle.relativize(p);
                    if (rel.getNameCount() > 1 && rel.getName(1).toString().equals("1")) {
                        continue;
                    }
                    Path ziel = baum.resolve(rel.toString());
                    Files.createDirectories(ziel.getParent());
                    if (rel.getNameCount() > 1 && rel.getName(1).toString().equals("0") && p.toString().endsWith(".webp")) {
                        Path png = ziel.resolveSibling(p.getFileName().toString().replace(".webp", ".png"));
                        BufferedImage rot = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
                        for (int y = 0; y < 256; y++) {
                            for (int x = 0; x < 256; x++) {
                                rot.setRGB(x, y, ROT);
                            }
                        }
                        ImageIO.write(rot, "png", png.toFile());
                    } else {
                        Files.copy(p, ziel, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            Satz.schreibe(baum, "Zoomstufen", "minecraft:overworld", 4);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return baum;
    }

    /**
     * In der Mitte des Bilds, wo die Stufe 2 des Testsatzes Kacheln hat, 30 bis 70 % der Breite und 10 bis 90 % der Höhe:
     * wie viele Pixel genau rot sind, wie viele dunkel wie der leere Grund, und wie viele es sind.
     */
    private static long[] mitte(Path bild) {
        try {
            BufferedImage b = ImageIO.read(bild.toFile());
            long rot = 0, dunkel = 0, alle = 0;
            for (int y = b.getHeight() / 10; y < b.getHeight() * 9 / 10; y++) {
                for (int x = b.getWidth() * 3 / 10; x < b.getWidth() * 7 / 10; x++) {
                    int c = b.getRGB(x, y) & 0xFFFFFF;
                    rot += c == 0xFF0000 ? 1 : 0;
                    dunkel += (c >> 16 & 0xFF) + (c >> 8 & 0xFF) + (c & 0xFF) < 90 ? 1 : 0;
                    alle++;
                }
            }
            return new long[] {rot, dunkel, alle};
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
