package com.nekyia.heroicmap;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import javax.imageio.ImageIO;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

/**
 * Bilder der Minimap zum Ansehen, nicht zum Vergleichen: baut eine Szene in einer flachen
 * Welt und nimmt die Minimap bei 1, 2 und 4 Pixeln je Block auf, danach Vollbildkarte und
 * Live-Ebene. Mit -Pbilder=&lt;ordner&gt; landen die Bilder dort. Siehe docs/minimap.md, „Bilder“.
 */
public final class Bilder implements FabricClientGameTest {

    private static final String AUSGABE = System.getProperty("heroicmap.bilder", "");

    /** Die Szene um (0, 0); die flache Welt hat Gras auf y = -61. */
    private static final String[] SZENE = {
        "time set noon",
        "weather clear",
        "gamemode spectator @a",
        // Ein Becken, nach Osten tiefer: Wasser von 1 bis 6 Blöcken über Sand.
        "fill -15 -61 -14 -2 -55 -3 stone_bricks",
        "fill -14 -60 -13 -3 -55 -4 air",
        "fill -14 -60 -13 -13 -56 -4 sand",
        "fill -12 -60 -13 -11 -57 -4 sand",
        "fill -10 -60 -13 -9 -58 -4 sand",
        "fill -8 -60 -13 -7 -59 -4 sand",
        "fill -6 -60 -13 -5 -60 -4 sand",
        "fill -14 -60 -13 -3 -55 -4 water replace air",
        // Was aus dem Wasser ragt, liegt vor der Oberfläche: Mangrovenwurzeln und obere Stufen.
        "fill -14 -55 -13 -13 -55 -10 mangrove_roots[waterlogged=true]",
        "fill -14 -55 -7 -13 -55 -4 smooth_stone_slab[type=top,waterlogged=true]",
        // Ein Haus mit Dach aus Platten und Treppen.
        "fill 3 -60 -13 9 -57 -7 oak_planks hollow",
        "fill 3 -56 -13 9 -56 -7 spruce_slab",
        "fill 3 -56 -13 9 -56 -13 spruce_stairs[facing=south]",
        "fill 3 -56 -7 9 -56 -7 spruce_stairs[facing=north]",
        "setblock 6 -55 -10 lantern",
        // Bäume.
        "place feature minecraft:oak 10 -60 6",
        "place feature minecraft:birch 5 -60 11",
        "place feature minecraft:spruce -9 -60 9",
        // Ein Weg und Kleinkram.
        "fill -16 -61 0 15 -61 1 dirt_path",
        "fill -4 -60 4 -1 -60 7 short_grass",
        "fill 0 -60 4 2 -60 6 poppy",
        "setblock -2 -60 -1 chest",
        "setblock 0 -60 -1 glass",
        "setblock 1 -60 -1 light_blue_stained_glass",
        "fill 12 -61 -4 14 -61 -2 ice",
        "fill 12 -60 -14 15 -60 -10 snow[layers=3]",
        "fill 12 -62 8 14 -62 10 stone_bricks",
        "fill 13 -61 9 13 -61 9 lava",
        "fill -14 -61 12 -10 -61 15 farmland",
        "fill -14 -60 12 -10 -60 15 wheat[age=7]",
        "fill 3 -60 2 6 -60 2 white_carpet",
        // Versatz und Form: Bambus, ein Kopf und ein Schild an der Wand des Hauses.
        "fill 8 -60 2 11 -59 3 bamboo",
        "setblock 8 -60 -1 player_head",
        "setblock 6 -58 -6 oak_wall_sign[facing=south]",
    };

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext spiel = context.worldBuilder().create()) {
            TestServerContext server = spiel.getServer();
            for (String befehl : SZENE) {
                server.runCommand(befehl);
            }
            server.runCommand("tp @a 0.5 -30 0.5 0 90");
            spiel.getConnection().waitForChunksRender();
            for (int scale : new int[] {1, 2, 4}) {
                context.runOnClient(mc -> {
                    while (Minimap.INSTANZ.scale() != scale) {
                        Minimap.INSTANZ.naechsterMassstab();
                    }
                });
                context.waitFor(mc -> Minimap.INSTANZ.fertig(), 1200);
                context.waitTicks(2);
                Path bild = context.takeScreenshot(TestScreenshotOptions.of("minimap-" + scale + "px").disableCounterPrefix());
                if (!AUSGABE.isEmpty()) {
                    schneide(context, bild, Path.of(AUSGABE, "minimap-" + scale + "px.png"));
                }
            }
            vollbildkarte(context);
            live(context, server);
        }
    }

    /**
     * Die Live-Ebene über dem Testsatz: Gold quer über die Grenze von Chunk -1 und 0, dann die
     * Vollbildkarte. Im Einzelspieler gibt es keinen Satz vom Server, der Test setzt ihn.
     * Siehe docs/live.md.
     */
    private static void live(ClientGameTestContext context, TestServerContext server) {
        Path baum = FabricLoader.getInstance().getGameDir().resolve(HeroicMap.ID).resolve("test").resolve("beispiel");
        Path ebene = Ebene.ordner(baum);
        context.runOnClient(mc -> Live.INSTANZ.satzFuerTest(Satz.lies(baum)));
        server.runCommand("fill -3 -61 2 3 -61 3 gold_block");
        context.waitFor(mc -> Files.exists(ebene.resolve("0.0.png")) && Files.exists(ebene.resolve("-1.0.png")), 1200);
        context.runOnClient(mc -> mc.gui.setScreen(new Karte(Satz.lies(baum))));
        context.waitTicks(40);
        Path bild = context.takeScreenshot(TestScreenshotOptions.of("live").disableCounterPrefix());
        if (!AUSGABE.isEmpty()) {
            try {
                Files.copy(bild, Path.of(AUSGABE, "live.png"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        context.runOnClient(mc -> mc.gui.setScreen(null));
    }

    /**
     * Öffnet die Vollbildkarte mit einem kleinen Satz gemalter Testkacheln um den Ursprung,
     * scale 4, Stufen 0 bis 2, und nimmt sie auf der feinsten Stufe auf.
     */
    private static void vollbildkarte(ClientGameTestContext context) {
        Path baum = FabricLoader.getInstance().getGameDir().resolve(HeroicMap.ID).resolve("test").resolve("beispiel");
        try {
            for (String datei : liste()) {
                Path ziel = baum.resolve("4").resolve(datei);
                Files.createDirectories(ziel.getParent());
                try (InputStream rein = Bilder.class.getResourceAsStream("/satz/4/" + datei)) {
                    Files.copy(rein, ziel, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Satz.schreibe(baum, "Beispiel", "minecraft:overworld", 4);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        context.runOnClient(mc -> mc.gui.setScreen(new Karte(Satz.lies(baum))));
        context.waitTicks(40);
        Path bild = context.takeScreenshot(TestScreenshotOptions.of("vollbildkarte").disableCounterPrefix());
        if (!AUSGABE.isEmpty()) {
            try {
                Files.copy(bild, Path.of(AUSGABE, "vollbildkarte.png"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        context.runOnClient(mc -> mc.gui.setScreen(null));
    }

    private static java.util.List<String> liste() throws IOException {
        try (InputStream rein = Bilder.class.getResourceAsStream("/satz/liste.txt")) {
            return new String(rein.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().filter(z -> !z.isBlank()).toList();
        }
    }

    /** Der Ausschnitt der Minimap samt Rand, in Pixeln des Fensters. */
    private static void schneide(ClientGameTestContext context, Path bild, Path ziel) {
        int[] fenster = context.computeOnClient(mc -> new int[] {
            mc.getWindow().getGuiScale(), mc.getWindow().getGuiScaledWidth()});
        int gs = fenster[0];
        int x = (fenster[1] - Minimap.GROESSE - 4 - 1) * gs, y = 3 * gs, seite = (Minimap.GROESSE + 2) * gs;
        try {
            BufferedImage ganz = ImageIO.read(bild.toFile());
            ImageIO.write(ganz.getSubimage(x, y, seite, seite), "png", ziel.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
