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
import net.minecraft.world.level.ChunkPos;

/**
 * Der Knopf „Relief“ auf der Vollbildkarte, mit echten Klicks, auf zwei Basiskacheln der Testwelt von Renderer 0.19.0 mit
 * Schicht und Rest: von Relief reihum Relief + Schatten, Flach, Relief; Flach und Relief + Schatten sehen anders aus als die Kacheln,
 * zurück auf Relief wie vorher. Fehlt der Index der Schicht, trägt der Knopf „…“; ohne Schicht in {@code map.json} ist er grau.
 * Dazu die eigene Karte ({@link #eigeneKarte}). Siehe docs/vollbildkarte.md, „Relief“.
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
            eigeneKarte(context, spiel);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            context.runOnClient(mc -> Minimap.INSTANZ.setzeRelief(vorher));
        }
    }

    /**
     * Die eigene Karte: Mit Flach schreibt sie keine Schicht. Ein Klick auf Relief, und die geladenen Chunks bekommen sie, mit
     * den Codes um eine Säule wie der Renderer; die Kacheln bleiben ohne Relief. Der Knopf ist nicht grau und trägt nie „…“,
     * Relief sieht anders aus als Flach. Ein Turm im Nordwesten wirft danach seinen Schatten in die Schicht des Chunks
     * südöstlich. Siehe docs/selbst.md, „Relief“.
     */
    private static void eigeneKarte(ClientGameTestContext context, TestSingleplayerContext spiel) throws IOException {
        var server = spiel.getServer();
        server.runCommand("gamemode spectator @a");
        // Die Säule wie in Bilder.reliefNah, Oberfläche bei y −47 statt −60.
        server.runCommand("fill 60 -60 60 62 -48 62 stone");
        server.runCommand("tp @a 56.5 -30 56.5");
        context.waitTicks(10);
        spiel.getConnection().waitForChunksRender();
        Path welt = context.computeOnClient(mc -> Downloads.weltOrdner());
        Path ordner = welt.resolve(Selbst.baum("minecraft:overworld")).resolve("4");
        context.runOnClient(mc -> {
            Minimap.INSTANZ.setzeRelief(Relief.Art.OHNE);
            if (Selbst.INSTANZ.waehle(mc, 4) != null) {
                throw new AssertionError("„Selbst“ geht nicht");
            }
        });
        zeichne(context);
        if (Files.exists(ordner.resolve("relief"))) {
            throw new AssertionError("Mit Flach schreibt die eigene Karte eine Schicht");
        }
        int[] kachelFlach = png(ordner.resolve("8/0/0.png"));
        // Die Säule im linken Teil des Bilds, den anders vergleicht.
        oeffneEigene(context, welt);
        if (!context.computeOnClient(mc -> knopf(mc.gui.screen()).active)) {
            throw new AssertionError("Auf der eigenen Karte ist der Knopf „Relief“ grau");
        }
        BufferedImage flach = bild(context, "relief-selbst-ohne");
        klicke(context);
        context.runOnClient(mc -> mc.gui.setScreen(null));
        zeichne(context);
        oeffneEigene(context, welt);
        pruefeText(context, "heroicmap.karte.relief", "slope");
        BufferedImage relief = bild(context, "relief-selbst-slope");
        context.runOnClient(mc -> mc.gui.setScreen(null));
        int[] schicht = png(ordner.resolve("relief/8/0/0.png")), kachel = png(ordner.resolve("8/0/0.png"));
        // Westrand der Säule steigt (8), Ostrand fällt (0), südöstlich daneben Schatten (13), weit weg eben (4).
        int[] codes = {code(schicht, 60, 61), code(schicht, 62, 61), code(schicht, 63, 63), code(schicht, 40, 40)};
        int kachelAnders = 0;
        for (int i = 0; i < kachel.length; i++) {
            kachelAnders += kachel[i] != kachelFlach[i] ? 1 : 0;
        }
        int bildAnders = anders(flach, relief);

        // Der Turm in Chunk (2, 2): Sein Schatten fällt auf (48, 48) in Chunk (3, 3), den nur der Ring neu zeichnet.
        int vorTurm = code(schicht, 48, 48);
        server.runCommand("fill 44 -60 44 45 -41 45 stone");
        zeichne(context);
        int mitTurm = code(png(ordner.resolve("relief/8/0/0.png")), 48, 48);
        context.runOnClient(mc -> Selbst.INSTANZ.leeren());
        LOGGER.info("Relief der eigenen Karte: Codes {} {} {} {}, Kachel {} Pixel anders, Bild {} Pixel anders, Turm {} → {}",
                codes[0], codes[1], codes[2], codes[3], kachelAnders, bildAnders, vorTurm, mitTurm);
        if (codes[0] != 8 || codes[1] != 0 || codes[2] != 13 || codes[3] != 4) {
            throw new AssertionError("Schicht der eigenen Karte: Codes " + java.util.Arrays.toString(codes) + " statt [8, 0, 13, 4]");
        }
        if (kachelAnders != 0) {
            throw new AssertionError("Mit Relief ist die Kachel der eigenen Karte " + kachelAnders + " Pixel anders; sie bleibt ohne Relief");
        }
        if (bildAnders < 100) {
            throw new AssertionError("Eigene Karte mit Relief nur " + bildAnders + " Pixel anders als Flach (mindestens 100)");
        }
        if (vorTurm != 4 || mitTurm != 13) {
            throw new AssertionError("Schatten des Turms in der Schicht der eigenen Karte: " + vorTurm + " → " + mitTurm + " statt 4 → 13");
        }
    }

    /** Bis „Selbst“ um Chunk (3, 3) alles gezeichnet und geschrieben hat. */
    private static void zeichne(ClientGameTestContext context) {
        context.waitTicks(5);
        context.waitFor(mc -> Selbst.INSTANZ.fertig(new ChunkPos(3, 3), 3), 2400);
        context.computeOnClient(mc -> Selbst.INSTANZ.schreibeJetzt()).join();
    }

    /** Die eigene Karte bei (90, 75), die Säule links oben im Bild, bis ihre Kacheln dekodiert sind. */
    private static void oeffneEigene(ClientGameTestContext context, Path welt) {
        context.runOnClient(mc -> mc.gui.setScreen(new Karte(Satz.fuer(welt, "minecraft:overworld", Zeigt.OHNE)).bei(90, 75)));
        context.waitTicks(40);
    }

    /** Die Pixel einer PNG. */
    private static int[] png(Path datei) throws IOException {
        BufferedImage b = ImageIO.read(datei.toFile());
        return b.getRGB(0, 0, b.getWidth(), b.getHeight(), null, 0, b.getWidth());
    }

    /** Der Code der Spalte (x, z) in der Schicht der Kachel (8, 0, 0) mit 4 px je Block. */
    private static int code(int[] schicht, int x, int z) {
        return schicht[z * 4 * 256 + x * 4] >> 16 & 0xFF;
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
