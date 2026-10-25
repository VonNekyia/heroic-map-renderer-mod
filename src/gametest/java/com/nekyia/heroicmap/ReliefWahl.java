package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.InputConstants;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Der Knopf „Relief“ auf der Vollbildkarte, mit echten Klicks, auf zwei Basiskacheln der Testwelt von Renderer 0.19.0 mit
 * Schicht und Rest: von Relief reihum Relief + Schatten, Flach, Relief; Flach und Relief + Schatten sehen anders aus als die Kacheln,
 * zurück auf Relief wie vorher. Fehlt der Index der Schicht, trägt der Knopf „…“; ohne Schicht in {@code map.json} ist er grau.
 * Siehe docs/vollbildkarte.md, „Relief“.
 */
public final class ReliefWahl implements FabricClientGameTest {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final String BAUM = "reliefwahl";
    private static final String[] DATEIEN = {"map.json", "8/-44/-52.webp", "8/-45/-53.webp", "relief/8/-44/-52.webp",
        "relief/8/-45/-53.webp", "relief/rest/8/-44/-52.webp", "relief/rest/8/-45/-53.webp"};

    @Override
    public void runTest(ClientGameTestContext context) {
        Relief.Art vorher = context.computeOnClient(mc -> Minimap.INSTANZ.relief());
        try (TestSingleplayerContext spiel = context.worldBuilder().create(); var _ = Abschluss.langsamerTakt(context, spiel)) {
            spiel.getConnection().waitForChunksRender();
            Path baum = context.computeOnClient(mc -> Downloads.weltOrdner()).resolve(BAUM), ordner = baum.resolve("4");
            Laden.loesche(baum);
            for (String datei : DATEIEN) {
                Path ziel = ordner.resolve(datei);
                Files.createDirectories(ziel.getParent());
                try (InputStream rein = ReliefWahl.class.getResourceAsStream("/relief/4/" + datei)) {
                    Files.write(ziel, rein.readAllBytes());
                }
            }
            // Ein Index ohne offenen Strom heisst: Schicht und Rest liegen ganz da.
            Files.writeString(ordner.resolve("relief/etags.txt"), "");
            Files.writeString(ordner.resolve("relief/rest/etags.txt"), "");
            Satz.schreibe(baum, "Relief", "minecraft:overworld", 4);
            context.runOnClient(mc -> Minimap.INSTANZ.setzeRelief(Relief.Art.SLOPE));
            oeffne(context, baum);
            BufferedImage relief = bild(context, "relief-slope");
            pruefeText(context, "heroicmap.karte.relief", "slope");

            // Reihum mit Klicks, wie gewählt Flach → Relief → Relief + Schatten → Flach: von Relief also Relief + Schatten, Flach,
            // wieder Relief.
            klicke(context);
            BufferedImage schatten = bild(context, "relief-shadows");
            pruefeText(context, "heroicmap.karte.relief", "shadows");
            klicke(context);
            BufferedImage flach = bild(context, "relief-ohne");
            pruefeText(context, "heroicmap.karte.relief", "ohne");
            klicke(context);
            BufferedImage zurueck = bild(context, "relief-slope-2");
            pruefeText(context, "heroicmap.karte.relief", "slope");
            int ohneRelief = anders(relief, flach), mitSchatten = anders(relief, schatten), gleich = anders(relief, zurueck);
            LOGGER.info("Relief auf der Vollbildkarte, Pixel anders als mit slope: Flach {}, Relief + Schatten {}, zurück {}",
                    ohneRelief, mitSchatten, gleich);
            if (ohneRelief < 10000 || mitSchatten < 5000 || gleich != 0) {
                throw new AssertionError("Relief auf der Vollbildkarte anders als mit slope: Flach " + ohneRelief + " (mindestens 10000), Relief + Schatten "
                        + mitSchatten + " (mindestens 5000), zurück " + gleich + " (0)");
            }

            // Ohne Index fehlt die Schicht: Relief + Schatten trägt „…“.
            Files.delete(ordner.resolve("relief/etags.txt"));
            klicke(context);
            pruefeText(context, "heroicmap.karte.relief_fehlt", "shadows");

            // Ohne Schicht in map.json: grau.
            String karte = Files.readString(ordner.resolve("map.json")).replace("\"reliefTiles\"", "\"reliefTilesAlt\"");
            Files.writeString(ordner.resolve("map.json"), karte);
            oeffne(context, baum);
            if (context.computeOnClient(mc -> knopf(mc.gui.screen()).active)) {
                throw new AssertionError("Ohne Schicht in map.json ist der Knopf „Relief“ nicht grau");
            }
            context.runOnClient(mc -> mc.gui.setScreen(null));
            Laden.loesche(baum);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            context.runOnClient(mc -> Minimap.INSTANZ.setzeRelief(vorher));
        }
    }

    /** Die Vollbildkarte auf der Kachel (−44, −52), bis ihre Kacheln dekodiert sind. */
    private static void oeffne(ClientGameTestContext context, Path baum) {
        context.runOnClient(mc -> mc.gui.setScreen(new Karte(Satz.lies(baum)).bei(-2784, -3296)));
        context.waitTicks(40);
    }

    /** Der Knopf „Relief“ der offenen Karte. */
    private static Button knopf(net.minecraft.client.gui.screens.Screen schirm) {
        String anfang = Component.translatable("heroicmap.karte.relief", "").getString();
        return schirm.children().stream().filter(w -> w instanceof Button b && b.getMessage().getString().startsWith(anfang.strip()))
                .map(w -> (Button) w).findFirst().orElseThrow(() -> new AssertionError("Kein Knopf „Relief“ auf der Karte"));
    }

    /** Ein echter Klick mit der linken Taste auf den Knopf „Relief“, danach Zeit zum Dekodieren. */
    private static void klicke(ClientGameTestContext context) {
        int[] mitte = context.computeOnClient(mc -> {
            Button b = knopf(mc.gui.screen());
            int k = mc.getWindow().getGuiScale();
            return new int[] {(b.getX() + b.getWidth() / 2) * k, (b.getY() + b.getHeight() / 2) * k};
        });
        TestInput maus = context.getInput();
        maus.setCursorPos(mitte[0], mitte[1]);
        context.waitTick();
        maus.pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(40);
    }

    private static void pruefeText(ClientGameTestContext context, String schluessel, String art) {
        String ist = context.computeOnClient(mc -> knopf(mc.gui.screen()).getMessage().getString());
        String soll = context.computeOnClient(mc -> Component.translatable(schluessel, Component.translatable("heroicmap.relief." + art)).getString());
        if (!ist.equals(soll)) {
            throw new AssertionError("Knopf „Relief“: „" + ist + "“ statt „" + soll + "“");
        }
    }

    private static BufferedImage bild(ClientGameTestContext context, String name) throws IOException {
        context.getInput().setCursorPos(0, 0);
        context.waitTicks(2);
        return ImageIO.read(context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix()).toFile());
    }

    /** Wie viele Pixel in der linken Hälfte, ohne die Knöpfe rechts und ohne die Zeile unten, anders sind. */
    private static int anders(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < a.getHeight() * 3 / 4; y++) {
            for (int x = 0; x < a.getWidth() / 2; x++) {
                n += (a.getRGB(x, y) & 0xFFFFFF) != (b.getRGB(x, y) & 0xFFFFFF) ? 1 : 0;
            }
        }
        return n;
    }
}
