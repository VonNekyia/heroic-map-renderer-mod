package com.nekyia.heroicmap;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import com.sun.net.httpserver.HttpServer;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;
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
            // Die Bilder zeigen die Minimap genordet und ohne Rahmen; gedreht nur in drehen(), Rahmen nur in rahmen().
            context.runOnClient(mc -> {
                Minimap.INSTANZ.setzeDrehen(false);
                Minimap.INSTANZ.setzeSkin(Skin.OHNE);
            });
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
            rahmen(context);
            umriss(context);
            drehen(context, server);
            vollbildkarte(context);
            formen(context, server);
            orte(context, server);
            selbst(context);
        }
    }

    /** Eine Ebene mit Flächen, Kreis, Linie und Kartenschrift um den Spieler; der Teil wie vom Plugin. */
    private static final String FORMEN = """
            {"v":1,"typ":"ebene","id":"test:formen","version":"1","teil":1,"teile":1,"objects":[
              {"type":"region","fill":"#3060E080","polygons":[{"outer":[[-12,-12],[4,-12],[4,-4],[-4,-4],[-4,4],[-12,4]],
                "holes":[[[-10,-10],[-6,-10],[-6,-6],[-10,-6]]]}]},
              {"type":"region","fill":"#E0403080","stroke":{"color":"#FFFFFF","width":1,"style":"dashed"},
                "polygons":[{"outer":[[2,2],[14,2],[2,14]]}]},
              {"type":"circle","center":[9,-8],"radius":5,"fill":"#40C04060","stroke":{"style":"dashed"}},
              {"type":"line","points":[[-15,10],[0,8],[15,14]],"stroke":{"color":"#FFD700","width":3,"style":"dashed","dash":[6,4]}},
              {"type":"label","text":"Westmeer","path":[[-14,13],[0,9],[14,12]],"size":3,"spacing":0.2,"color":"#2B3A55",
                "outline":{"color":"#F2E8D0CC","width":1}}
            ]}""";

    /** Nadeln in drei Grössen, ein Banner und eine Kartenschrift; die Bilder holt der Mod von einem Server im Test. */
    private static final String ORTE = """
            {"v":1,"typ":"ebene","id":"test:orte","version":"1","teil":1,"teile":1,"objects":[
              {"type":"pin","id":"nordhafen","at":[0.5,0.5],"size":"large","name":"Nordhafen","color":"#3A6EA5",
                "symbol":{"large":"images/anker.png","medium":"images/anker-m.png"}},
              {"type":"pin","at":[-9,-7],"name":"Eichenfeld","symbol":{"medium":"images/anker-m.png"}},
              {"type":"pin","at":[9,9],"size":"small","name":"Furt"},
              {"type":"banner","id":"westmark","at":[10,-9],"name":"Westmark","image":"images/banner.png"},
              {"type":"label","text":"Nordland","path":[[-14,-12],[14,-14]],"size":3,"outline":{"width":1}}
            ]}""";

    /**
     * Nadeln und Banner mit ihren Namen in der Kartenschrift unter dem Fuss, in fester Grösse: die Minimap
     * bei 4 px und Zoom 4, dann die Vollbildkarte; dort einmal mit „Unicode-Schrift erzwingen“.
     * Siehe docs/ebenen.md, „Nadeln“,
     * und docs/ebenen.md, „Banner“.
     */
    private static void orte(ClientGameTestContext context, TestServerContext server) {
        HttpServer bilder = bilderServer(Map.of("anker.png", bild(16, 16, ANKER), "anker-m.png", bild(9, 9, ANKER), "banner.png",
                bild(21, 40, BANNER)));
        try {
            context.runOnClient(mc -> {
                Ebenen.INSTANZ.empfange(JsonParser.parseString("""
                        {"v":1,"typ":"ebenen","jetzt":1,"ebenen":[{"id":"test:orte","name":{"de":"Orte","en":"Places"},
                          "visible":true,"order":1,"version":"1"}]}""").getAsJsonObject());
                Symbole.INSTANZ.basis(JsonParser.parseString("{\"url\":\"http://127.0.0.1:" + bilder.getAddress().getPort() + "/tiles\"}")
                        .getAsJsonObject(), InetAddress.getLoopbackAddress());
                Ebenen.Teil t = Ebenen.Teil.lies(ORTE);
                if (t == null || t.nadeln().size() != 4) {
                    throw new AssertionError("Teil der Orte nicht lesbar");
                }
                Ebenen.INSTANZ.teil(t);
                Minimap.INSTANZ.setzeScale(4);
                Minimap.INSTANZ.setzeZoom(4);
            });
            context.waitFor(mc -> Symbole.INSTANZ.banner("test:orte", "1", "images/banner.png") != null
                    && Symbole.INSTANZ.symbol("test:orte", "1", "images/anker.png", 16) != null && Minimap.INSTANZ.fertig(), 600);
            context.waitTicks(2);
            BufferedImage minimap = mitRand(context, context.takeScreenshot(TestScreenshotOptions.of("orte").disableCounterPrefix()));
            Path baum = testsatz();
            context.runOnClient(mc -> mc.gui.setScreen(new Karte(Satz.lies(baum))));
            context.waitTicks(40);
            Path karte = context.takeScreenshot(TestScreenshotOptions.of("orte-karte").disableCounterPrefix());
            gleichGrossAufZweiStufen(context, karte);
            // Die Option tauscht die Schriften ohne Neuladen; die gespeicherte Kartenschrift baut neu.
            int vorher = context.computeOnClient(mc -> Formen.generation);
            context.runOnClient(mc -> mc.options.forceUnicodeFont().set(true));
            context.waitTicks(10);
            if (context.computeOnClient(mc -> Formen.generation) <= vorher) {
                throw new AssertionError("„Unicode-Schrift erzwingen“ hob die Generation der Kartenschrift nicht");
            }
            Path unicode = context.takeScreenshot(TestScreenshotOptions.of("orte-unicode").disableCounterPrefix());
            context.runOnClient(mc -> mc.options.forceUnicodeFont().set(false));
            context.waitTicks(10);
            tafel(context);
            if (!AUSGABE.isEmpty()) {
                try {
                    ImageIO.write(minimap, "png", Path.of(AUSGABE, "orte.png").toFile());
                    Files.copy(karte, Path.of(AUSGABE, "orte-karte.png"), StandardCopyOption.REPLACE_EXISTING);
                    Files.copy(unicode, Path.of(AUSGABE, "orte-unicode.png"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            context.runOnClient(mc -> {
                mc.gui.setScreen(null);
                Ebenen.INSTANZ.leeren();
                Symbole.INSTANZ.leeren();
            });
        } finally {
            bilder.stop(0);
        }
    }

    /** Die Antwort des Plugins auf die Frage nach der Tafel von „Nordhafen“. */
    private static final String TAFEL = """
            {"v":1,"typ":"tafel","ebene":"test:orte","version":"1","id":"nordhafen","panel":{"blocks":[
              {"type":"title","text":"Nordhafen"},
              {"type":"lines","lines":["Hafen am Nordufer","Gegründet im Frühling"]},
              {"type":"rating","rows":[{"label":"Handel","value":4,"max":5},{"label":"Wehr","value":2,"max":5}]}
            ]}}""";

    /**
     * Auf der offenen Vollbildkarte: die Tafel von „Nordhafen“ beim Zeigen, dann per Klick gehalten, während
     * der Zeiger woanders steht; Escape schliesst erst die Tafel, die Karte bleibt. Die Antwort des Plugins
     * legt der Test selbst ab. Siehe docs/ebenen.md, „Infotafel“.
     */
    private static void tafel(ClientGameTestContext context) {
        int k = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
        int breite = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
        int hoehe = context.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
        // Der Fuss der grossen Nadel liegt beim Spieler in der Mitte; der Zeiger aufs Schild, über dem eigenen Kopf.
        context.getInput().setCursorPos(breite / 2.0 * k, (hoehe / 2.0 - 24) * k);
        context.waitTicks(6);
        context.runOnClient(mc -> Tafeln.INSTANZ.antwort(Tafeln.Antwort.lies(TAFEL)));
        context.waitTicks(10);
        Path zeigen = context.takeScreenshot(TestScreenshotOptions.of("tafel-zeigen").disableCounterPrefix());
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.getInput().setCursorPos(10 * k, (hoehe - 10) * k);
        context.waitTicks(20);
        Path gehalten = context.takeScreenshot(TestScreenshotOptions.of("tafel-gehalten").disableCounterPrefix());
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitTicks(5);
        if (!context.computeOnClient(mc -> mc.gui.screen() instanceof Karte)) {
            throw new AssertionError("Escape schloss die Karte statt erst der Tafel");
        }
        if (!AUSGABE.isEmpty()) {
            try {
                Files.copy(zeigen, Path.of(AUSGABE, "tafel-zeigen.png"), StandardCopyOption.REPLACE_EXISTING);
                Files.copy(gehalten, Path.of(AUSGABE, "tafel-gehalten.png"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Die Farben der Bilder im Test; das Banner hat eine eigene, so findet es der Test im Bild. */
    private static final int ANKER = 0x2E4A8C, BANNER = 0x8C2E4A;

    /**
     * Eine Stufe gröber steht das Banner woanders, aber gleich gross: feste Grösse nach 0097. Danach
     * wieder die Stufe von vorher.
     */
    private static void gleichGrossAufZweiStufen(ClientGameTestContext context, Path vorher) {
        int k = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
        int breite = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
        int hoehe = context.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
        context.getInput().setCursorPos(breite / 2.0 * k, hoehe / 2.0 * k);
        context.getInput().scroll(-1);
        context.waitTicks(20);
        Path grob = context.takeScreenshot(TestScreenshotOptions.of("orte-karte-grob").disableCounterPrefix());
        context.getInput().scroll(1);
        context.waitTicks(20);
        int[] a = kasten(vorher, BANNER), b = kasten(grob, BANNER);
        if (a == null || b == null || a[2] - a[0] != b[2] - b[0] || a[3] - a[1] != b[3] - b[1] || a[0] == b[0] && a[1] == b[1]) {
            throw new AssertionError("Banner nicht gleich gross auf zwei Stufen: " + Arrays.toString(a) + " " + Arrays.toString(b));
        }
    }

    /** Der Kasten {x0, y0, x1, y1} aller Pixel genau in der Farbe {@code rgb}; null, wenn keins sie hat. */
    private static int[] kasten(Path bild, int rgb) {
        try {
            BufferedImage b = ImageIO.read(bild.toFile());
            int[] k = {Integer.MAX_VALUE, Integer.MAX_VALUE, -1, -1};
            for (int y = 0; y < b.getHeight(); y++) {
                for (int x = 0; x < b.getWidth(); x++) {
                    if ((b.getRGB(x, y) & 0xFFFFFF) == rgb) {
                        k[0] = Math.min(k[0], x);
                        k[1] = Math.min(k[1], y);
                        k[2] = Math.max(k[2], x + 1);
                        k[3] = Math.max(k[3], y + 1);
                    }
                }
            }
            return k[2] < 0 ? null : k;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Ein Server auf 127.0.0.1, der die Bilder unter /tiles/layers/test/images/ ausliefert. */
    private static HttpServer bilderServer(Map<String, byte[]> dateien) {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            s.createContext("/tiles/layers/test/images/", austausch -> {
                String pfad = austausch.getRequestURI().getPath();
                byte[] inhalt = dateien.get(pfad.substring(pfad.lastIndexOf('/') + 1));
                if (inhalt == null) {
                    austausch.sendResponseHeaders(404, -1);
                } else {
                    austausch.getResponseHeaders().set("Content-Type", "image/png");
                    austausch.sendResponseHeaders(200, inhalt.length);
                    austausch.getResponseBody().write(inhalt);
                }
                austausch.close();
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Ein PNG: ein Feld in der Farbe {@code rgb} mit gelbem Rand und Querstreifen, etwa ein Banner. */
    private static byte[] bild(int breite, int hoehe, int rgb) {
        BufferedImage b = new BufferedImage(breite, hoehe, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = b.createGraphics();
        g.setColor(new Color(rgb));
        g.fillRect(0, 0, breite, hoehe);
        g.setColor(new Color(0xE8C547));
        g.drawRect(0, 0, breite - 1, hoehe - 1);
        g.fillRect(0, hoehe / 3, breite, Math.max(1, hoehe / 8));
        g.dispose();
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        try {
            ImageIO.write(b, "png", aus);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return aus.toByteArray();
    }

    /**
     * Flächen, Kreis und Linie einer Ebene: die Minimap genordet und gedreht mit „uhr“ bei 4 px und Zoom 4,
     * dann die Vollbildkarte. Siehe docs/ebenen.md, „Flächen, Kreise und Linien“.
     */
    private static void formen(ClientGameTestContext context, TestServerContext server) {
        context.runOnClient(mc -> {
            Ebenen.INSTANZ.empfange(JsonParser.parseString("""
                    {"v":1,"typ":"ebenen","jetzt":1,"ebenen":[{"id":"test:formen","name":{"de":"Formen","en":"Shapes"},
                      "visible":true,"order":1,"version":"1"}]}""").getAsJsonObject());
            Ebenen.Teil t = Ebenen.Teil.lies(FORMEN);
            if (t == null || t.formen().size() != 5) {
                throw new AssertionError("Teil der Formen nicht lesbar");
            }
            Ebenen.INSTANZ.teil(t);
            Minimap.INSTANZ.setzeScale(4);
            Minimap.INSTANZ.setzeZoom(4);
        });
        BufferedImage[] teile = new BufferedImage[2];
        for (int i = 0; i < teile.length; i++) {
            boolean gedreht = i == 1;
            server.runCommand("tp @a 0.5 -30 0.5 " + (gedreht ? 30 : 0) + " 90");
            context.runOnClient(mc -> {
                Minimap.INSTANZ.setzeDrehen(gedreht);
                Minimap.INSTANZ.setzeSkin(gedreht ? "uhr" : Skin.OHNE);
                Minimap.INSTANZ.setzeRund(gedreht);
            });
            context.waitFor(mc -> mc.player != null && Math.abs(mc.player.getYRot() - (gedreht ? 30 : 0)) < 0.1f && Minimap.INSTANZ.fertig(), 1200);
            context.waitTicks(2);
            teile[i] = mitRand(context, context.takeScreenshot(TestScreenshotOptions.of("formen-" + i).disableCounterPrefix()));
        }
        Path baum = testsatz();
        context.runOnClient(mc -> mc.gui.setScreen(new Karte(Satz.lies(baum))));
        context.waitTicks(40);
        Path karte = context.takeScreenshot(TestScreenshotOptions.of("formen-karte").disableCounterPrefix());
        if (!AUSGABE.isEmpty()) {
            BufferedImage beide = new BufferedImage(teile[0].getWidth() + teile[1].getWidth(),
                    Math.max(teile[0].getHeight(), teile[1].getHeight()), BufferedImage.TYPE_INT_RGB);
            beide.getGraphics().drawImage(teile[0], 0, 0, null);
            beide.getGraphics().drawImage(teile[1], teile[0].getWidth(), 0, null);
            try {
                ImageIO.write(beide, "png", Path.of(AUSGABE, "formen.png").toFile());
                Files.copy(karte, Path.of(AUSGABE, "formen-karte.png"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        server.runCommand("tp @a 0.5 -30 0.5 0 90");
        context.runOnClient(mc -> {
            mc.gui.setScreen(null);
            Ebenen.INSTANZ.leeren();
            Minimap.INSTANZ.setzeDrehen(false);
            Minimap.INSTANZ.setzeSkin(Skin.OHNE);
            Minimap.INSTANZ.setzeRund(false);
        });
        context.waitFor(mc -> mc.player != null && Math.abs(mc.player.getYRot()) < 0.1f && Minimap.INSTANZ.fertig(), 1200);
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
        // Das Untermenü „Einstellungen …“, mit Chunklinien an, so zeigt die Minimap daneben die Linien.
        context.runOnClient(mc -> {
            Minimap.INSTANZ.setzeChunklinien(true);
            mc.gui.setScreen(new Anzeige(mc.gui.screen()));
        });
        context.waitTicks(5);
        Path anzeige = context.takeScreenshot(TestScreenshotOptions.of("anzeige").disableCounterPrefix());
        context.runOnClient(mc -> {
            Minimap.INSTANZ.setzeChunklinien(false);
            Minimap.INSTANZ.setzeRund(false);
            mc.gui.setScreen(null);
        });
        try {
            // Das Menü speichert beim Schliessen; spätere Gametests sollen mit der Vorgabe beginnen.
            Files.deleteIfExists(HeroicMap.einstellungen());
            if (!AUSGABE.isEmpty()) {
                schneide(context, rund, Path.of(AUSGABE, "minimap-rund.png"));
                Files.copy(menue, Path.of(AUSGABE, "menue.png"), StandardCopyOption.REPLACE_EXISTING);
                Files.copy(anzeige, Path.of(AUSGABE, "anzeige.png"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Jeder Rahmen eckig und rund nebeneinander, bei 4 px und Zoom 4. Siehe docs/rahmen.md. */
    private static void rahmen(ClientGameTestContext context) {
        for (String skin : Skin.NAMEN.subList(1, Skin.NAMEN.size())) {
            BufferedImage[] teile = new BufferedImage[2];
            for (int i = 0; i < teile.length; i++) {
                boolean rund = i == 1;
                context.runOnClient(mc -> {
                    Minimap.INSTANZ.setzeSkin(skin);
                    Minimap.INSTANZ.setzeRund(rund);
                });
                context.waitTicks(2);
                teile[i] = mitRand(context, context.takeScreenshot(TestScreenshotOptions.of("rahmen-" + skin + "-" + i).disableCounterPrefix()));
            }
            if (!AUSGABE.isEmpty()) {
                BufferedImage beide = new BufferedImage(teile[0].getWidth() + teile[1].getWidth(),
                        Math.max(teile[0].getHeight(), teile[1].getHeight()), BufferedImage.TYPE_INT_RGB);
                beide.getGraphics().drawImage(teile[0], 0, 0, null);
                beide.getGraphics().drawImage(teile[1], teile[0].getWidth(), 0, null);
                try {
                    ImageIO.write(beide, "png", Path.of(AUSGABE, "rahmen-" + skin + ".png").toFile());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        // Das Menü mit „uhr“: an der Ecke zur Mitte der Griff statt der zier, ohne den weissen Umriss.
        context.runOnClient(mc -> {
            Minimap.INSTANZ.setzeSkin("uhr");
            Minimap.INSTANZ.setzeRund(false);
            mc.gui.setScreen(new Einstellungen());
        });
        context.waitTicks(5);
        Path menue = context.takeScreenshot(TestScreenshotOptions.of("rahmen-menue").disableCounterPrefix());
        context.runOnClient(mc -> {
            mc.gui.setScreen(null);
            Minimap.INSTANZ.setzeSkin(Skin.OHNE);
        });
        try {
            // Das Menü speichert beim Schliessen; spätere Gametests sollen mit der Vorgabe beginnen.
            Files.deleteIfExists(HeroicMap.einstellungen());
            if (!AUSGABE.isEmpty()) {
                Files.copy(menue, Path.of(AUSGABE, "rahmen-menue.png"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Die runde Minimap ohne Rahmen bei GUI-Massstab 1 und 2, ungedreht: der Rand, Pixel für Pixel.
     * Siehe docs/minimap.md, „Form“.
     */
    private static void umriss(ClientGameTestContext context) {
        int vorher = context.computeOnClient(mc -> mc.options.guiScale().get());
        for (int gs : new int[] {1, 2}) {
            context.runOnClient(mc -> {
                Minimap.INSTANZ.setzeRund(true);
                mc.options.guiScale().set(gs);
                mc.resizeGui();
            });
            context.waitFor(mc -> Minimap.INSTANZ.fertig(), 1200);
            context.waitTicks(2);
            BufferedImage bild = mitRand(context, context.takeScreenshot(TestScreenshotOptions.of("rund-gs" + gs).disableCounterPrefix()));
            if (!AUSGABE.isEmpty()) {
                try {
                    ImageIO.write(bild, "png", Path.of(AUSGABE, "rund-gs" + gs + ".png").toFile());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        context.runOnClient(mc -> {
            Minimap.INSTANZ.setzeRund(false);
            mc.options.guiScale().set(vorher);
            mc.resizeGui();
        });
        context.waitFor(mc -> Minimap.INSTANZ.fertig(), 1200);
    }

    /**
     * Die drehende Minimap bei Gier 30, mit Chunklinien: eckig ohne Rahmen, rund mit „uhr“, eckig mit
     * „kompass“, nebeneinander. Siehe docs/minimap.md, „Drehen“.
     */
    private static void drehen(ClientGameTestContext context, TestServerContext server) {
        server.runCommand("tp @a 0.5 -30 0.5 30 90");
        String[][] arten = {{Skin.OHNE, "eckig"}, {"uhr", "rund"}, {"kompass", "eckig"}};
        BufferedImage[] teile = new BufferedImage[arten.length];
        for (int i = 0; i < arten.length; i++) {
            String skin = arten[i][0];
            boolean rund = arten[i][1].equals("rund");
            context.runOnClient(mc -> {
                Minimap.INSTANZ.setzeDrehen(true);
                Minimap.INSTANZ.setzeChunklinien(true);
                Minimap.INSTANZ.setzeSkin(skin);
                Minimap.INSTANZ.setzeRund(rund);
            });
            context.waitFor(mc -> mc.player != null && Math.abs(mc.player.getYRot() - 30) < 0.1f && Minimap.INSTANZ.fertig(), 1200);
            context.waitTicks(2);
            teile[i] = mitRand(context, context.takeScreenshot(TestScreenshotOptions.of("drehen-" + i).disableCounterPrefix()));
        }
        if (!AUSGABE.isEmpty()) {
            int breite = 0, hoehe = 0;
            for (BufferedImage t : teile) {
                breite += t.getWidth();
                hoehe = Math.max(hoehe, t.getHeight());
            }
            BufferedImage alle = new BufferedImage(breite, hoehe, BufferedImage.TYPE_INT_RGB);
            int x = 0;
            for (BufferedImage t : teile) {
                alle.getGraphics().drawImage(t, x, 0, null);
                x += t.getWidth();
            }
            try {
                ImageIO.write(alle, "png", Path.of(AUSGABE, "drehen.png").toFile());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        server.runCommand("tp @a 0.5 -30 0.5 0 90");
        context.runOnClient(mc -> {
            Minimap.INSTANZ.setzeDrehen(false);
            Minimap.INSTANZ.setzeChunklinien(false);
            Minimap.INSTANZ.setzeSkin(Skin.OHNE);
            Minimap.INSTANZ.setzeRund(false);
        });
        context.waitFor(mc -> mc.player != null && Math.abs(mc.player.getYRot()) < 0.1f && Minimap.INSTANZ.fertig(), 1200);
    }

    /** Die Minimap samt Ornamenten: ihr Rahmen und so viel darum, wie sie Abstand zum Rand hält. */
    private static BufferedImage mitRand(ClientGameTestContext context, Path bild) {
        int[] r = context.computeOnClient(mc -> {
            Minimap.Rahmen ra = Minimap.INSTANZ.rahmen(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
            return new int[] {mc.getWindow().getGuiScale(), ra.x(), ra.y(), ra.seite(), Minimap.INSTANZ.rand()};
        });
        int gs = r[0], m = r[4];
        try {
            return ImageIO.read(bild.toFile()).getSubimage((r[1] - m) * gs, (r[2] - m) * gs, (r[3] + 2 * m) * gs, (r[3] + 2 * m) * gs);
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
     * laden …“, „Selbst“, Ja, Zurück; die Karte zeigt dann die eigene. Auch wenn die Minimap als
     * beschäftigt gilt, wird um den Spieler alles gezeichnet; dann schreiben und auf der feinsten
     * Stufe aufnehmen. Siehe docs/selbst.md, „Bild“.
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
            // Die Minimap gilt ab der Wahl als beschäftigt: Die eigene Karte bekommt höchstens einen Chunk je Tick.
            Minimap.INSTANZ.fuerTestBeschaeftigt(true);
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

        context.waitFor(mc -> Selbst.INSTANZ.fertig(mc.player.chunkPosition(), 2), 1200);
        context.runOnClient(mc -> Minimap.INSTANZ.fuerTestBeschaeftigt(false));
        context.computeOnClient(mc -> Selbst.INSTANZ.schreibeJetzt()).join();
        context.runOnClient(mc -> mc.gui.setScreen(new Karte(Satz.fuer(welt, "minecraft:overworld"))));
        context.waitTicks(40);
        Path bild = context.takeScreenshot(TestScreenshotOptions.of("selbst").disableCounterPrefix());
        // Dieselbe Karte mit Chunklinien, dann die Minimap im HUD mit ihnen. Siehe docs/minimap.md, „Chunklinien“.
        context.runOnClient(mc -> Minimap.INSTANZ.setzeChunklinien(true));
        context.waitTicks(2);
        Path linien = context.takeScreenshot(TestScreenshotOptions.of("chunklinien").disableCounterPrefix());
        context.runOnClient(mc -> {
            mc.gui.screen().onClose();
            Minimap.INSTANZ.setzeZoom(2);
        });
        context.waitFor(mc -> Minimap.INSTANZ.fertig(), 1200);
        context.waitTicks(2);
        Path minimapLinien = context.takeScreenshot(TestScreenshotOptions.of("minimap-chunklinien").disableCounterPrefix());
        try {
            if (!AUSGABE.isEmpty()) {
                Files.copy(bild, Path.of(AUSGABE, "selbst.png"), StandardCopyOption.REPLACE_EXISTING);
                Files.copy(linien, Path.of(AUSGABE, "chunklinien.png"), StandardCopyOption.REPLACE_EXISTING);
                schneide(context, minimapLinien, Path.of(AUSGABE, "minimap-chunklinien.png"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        context.runOnClient(mc -> {
            Minimap.INSTANZ.setzeChunklinien(false);
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
