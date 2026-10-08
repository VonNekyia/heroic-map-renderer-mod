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
import net.minecraft.network.chat.Component;

/**
 * Bilder der Minimap zum Ansehen, nicht zum Vergleichen: baut eine Szene in einer flachen
 * Welt und nimmt die Minimap bei 1, 2 und 4 Pixeln je Block auf, danach das Menü und die
 * Vollbildkarte mit zwei Wegpunkten, zuletzt die selbst gezeichnete Karte der Szene. Mit -Pbilder=&lt;ordner&gt; landen die Bilder dort. Siehe docs/minimap.md, „Bilder“.
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
                    Minimap.INSTANZ.setzeScale(scale);
                    Minimap.INSTANZ.setzeZoom(scale);
                });
                context.waitFor(mc -> Minimap.INSTANZ.fertig(), 1200);
                context.waitTicks(2);
                Path bild = context.takeScreenshot(TestScreenshotOptions.of("minimap-" + scale + "px").disableCounterPrefix());
                if (!AUSGABE.isEmpty()) {
                    schneide(context, bild, Path.of(AUSGABE, "minimap-" + scale + "px.png"));
                }
            }
            menue(context);
            vollbildkarte(context);
            selbst(context);
        }
    }

    /** Die Minimap rund bei 4 px, dann das Menü über der Szene. Siehe docs/minimap.md, „Bedienung“. */
    private static void menue(ClientGameTestContext context) {
        context.runOnClient(mc -> Minimap.INSTANZ.setzeRund(true));
        context.waitTicks(2);
        Path rund = context.takeScreenshot(TestScreenshotOptions.of("minimap-rund").disableCounterPrefix());
        context.runOnClient(mc -> {
            // Die Meldung zum Spielmodus läge sonst über den Knöpfen.
            mc.gui.hud.getChat().clearMessages(false);
            mc.gui.setScreen(new Einstellungen());
        });
        context.waitTicks(5);
        Path menue = context.takeScreenshot(TestScreenshotOptions.of("menue").disableCounterPrefix());
        context.runOnClient(mc -> {
            Minimap.INSTANZ.setzeRund(false);
            mc.gui.setScreen(null);
        });
        try {
            // Das Menü speichert beim Schliessen; spätere Gametests sollen mit der Vorgabe beginnen.
            Files.deleteIfExists(HeroicMap.einstellungen());
            if (!AUSGABE.isEmpty()) {
                schneide(context, rund, Path.of(AUSGABE, "minimap-rund.png"));
                Files.copy(menue, Path.of(AUSGABE, "menue.png"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Legt den Testsatz aus den Ressourcen unter heroicmap/test/beispiel an und gibt den Baum zurück. */
    static Path testsatz() {
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
        return baum;
    }

    /**
     * Öffnet die Vollbildkarte mit einem kleinen Satz gemalter Testkacheln um den Ursprung,
     * scale 4, Stufen 0 bis 2, und nimmt sie auf der feinsten Stufe auf; dazu ein angehefteter
     * Wegpunkt auf der Karte und einer am Rand. Siehe docs/wegpunkte.md.
     */
    private static void vollbildkarte(ClientGameTestContext context) {
        Path baum = testsatz();
        context.runOnClient(mc -> {
            Wegpunkte.INSTANZ.setze("minecraft:overworld", 20, -12);
            Wegpunkte.INSTANZ.setze("minecraft:overworld", 900, 300);
            Wegpunkte.INSTANZ.umschalten(Wegpunkte.INSTANZ.punkte().getFirst());
            mc.gui.setScreen(new Karte(Satz.lies(baum)));
        });
        context.waitTicks(40);
        Path bild = context.takeScreenshot(TestScreenshotOptions.of("vollbildkarte").disableCounterPrefix());
        if (!AUSGABE.isEmpty()) {
            try {
                Files.copy(bild, Path.of(AUSGABE, "vollbildkarte.png"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        context.runOnClient(mc -> {
            mc.gui.setScreen(null);
            Wegpunkte.INSTANZ.leeren();
        });
    }

    /**
     * Die selbst gezeichnete Karte der Szene: „Selbst“ wählen, warten, bis um den Spieler alles
     * gezeichnet ist, schreiben und auf der feinsten Stufe aufnehmen. Siehe docs/selbst.md, „Bild“.
     */
    private static void selbst(ClientGameTestContext context) {
        Path welt = FabricLoader.getInstance().getGameDir().resolve(HeroicMap.ID).resolve("test").resolve("selbst");
        try {
            Laden.loesche(welt);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        context.runOnClient(mc -> {
            Selbst.INSTANZ.fuerTest(welt);
            Component fehler = Selbst.INSTANZ.waehle(mc);
            if (fehler != null) {
                throw new AssertionError("Selbst: " + fehler.getString());
            }
        });
        context.waitFor(mc -> Selbst.INSTANZ.fertig(mc.player.chunkPosition(), 2), 1200);
        context.computeOnClient(mc -> Selbst.INSTANZ.schreibeJetzt()).join();
        Path baum = welt.resolve(Selbst.baum("minecraft:overworld"));
        context.runOnClient(mc -> mc.gui.setScreen(new Karte(Satz.lies(baum))));
        context.waitTicks(40);
        Path bild = context.takeScreenshot(TestScreenshotOptions.of("selbst").disableCounterPrefix());
        try {
            if (!AUSGABE.isEmpty()) {
                Files.copy(bild, Path.of(AUSGABE, "selbst.png"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        context.runOnClient(mc -> {
            mc.gui.setScreen(null);
            Selbst.INSTANZ.fuerTest(null);
            Selbst.INSTANZ.leeren();
        });
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
