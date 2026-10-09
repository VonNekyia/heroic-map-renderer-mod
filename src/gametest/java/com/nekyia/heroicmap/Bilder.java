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
import net.minecraft.client.gui.screens.ConfirmScreen;

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
            selbst(context, server);
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
     * Die selbst gezeichnete Karte der Szene, gewählt wie ein Spieler: Karte ohne Satz, „Karte
     * laden …“, „Selbst“, Ja, Zurück; die Karte zeigt dann die eigene. Während die Minimap jeden
     * Tick zu tun hat, wird um den Spieler trotzdem alles gezeichnet; dann schreiben und auf der
     * feinsten Stufe aufnehmen. Siehe docs/selbst.md, „Bild“.
     */
    private static void selbst(ClientGameTestContext context, TestServerContext server) {
        Path welt = FabricLoader.getInstance().getGameDir().resolve(HeroicMap.ID).resolve("test").resolve("selbst");
        try {
            Laden.loesche(welt);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        context.runOnClient(mc -> {
            Selbst.INSTANZ.fuerTest(welt);
            Karte ohne = new Karte(null);
            mc.gui.setScreen(ohne);
            mc.gui.setScreen(new Auswahl(ohne));
        });
        context.waitTicks(2);
        context.clickScreenButton("heroicmap.selbst.knopf");
        context.waitFor(mc -> mc.gui.screen() instanceof ConfirmScreen, 100);
        context.clickScreenButton("gui.yes");
        context.waitTicks(2);
        context.clickScreenButton("gui.back");
        context.waitTicks(2);
        String baum = context.computeOnClient(mc -> mc.gui.screen() instanceof Karte k && k.satz() != null
                ? k.satz().ordner().getParent().getFileName().toString() : null);
        if (baum == null || !baum.startsWith(Selbst.PRAEFIX)) {
            throw new AssertionError("Nach „Selbst“ zeigt die Karte " + baum);
        }
        context.runOnClient(mc -> mc.gui.screen().onClose());

        // Ein Block im Bereich der Minimap, ausserhalb des geprüften Radius, wechselt jeden Tick.
        boolean fertig = false;
        int ruhig = 0, ticks = 0;
        for (int i = 0; i < 1200 && !fertig; i++) {
            server.runCommand("setblock 50 -60 0 " + (i % 2 == 0 ? "stone" : "air"));
            context.waitTick();
            ticks++;
            ruhig += context.computeOnClient(mc -> Minimap.INSTANZ.beschaeftigt()) ? 0 : 1;
            fertig = context.computeOnClient(mc -> Selbst.INSTANZ.fertig(mc.player.chunkPosition(), 2));
        }
        // Nur wenn die Minimap jeden Tick zu tun hatte, prüft der Test den Vorrang.
        if (ruhig > 0) {
            throw new AssertionError("Die Minimap war in " + ruhig + " von " + ticks + " Ticks nicht beschäftigt");
        }
        if (!fertig) {
            throw new AssertionError("Die eigene Karte wurde neben der beschäftigten Minimap nicht fertig");
        }
        context.computeOnClient(mc -> Selbst.INSTANZ.schreibeJetzt()).join();
        context.runOnClient(mc -> mc.gui.setScreen(new Karte(Satz.fuer(welt, "minecraft:overworld"))));
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
            mc.gui.screen().onClose();
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
