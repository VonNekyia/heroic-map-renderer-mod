package com.nekyia.heroicmap;

import com.mojang.logging.LogUtils;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import org.slf4j.Logger;

/**
 * Der eigene Spieler auf der Minimap in jeder Darstellung: Der Pfeil liegt waagrecht mittig auf dem
 * Spieler, bei GUI-Massstab 1, 2 und 3, gedreht und ungedreht; halb durchsichtig scheint die Karte
 * durch den Kopf. Geprüft am Bildschirmfoto. Siehe docs/minimap.md, „Spieler“ (mod#101).
 */
public final class Spieler implements FabricClientGameTest {

    /**
     * Höchstens so viele Pixel liegt die Achse des Pfeils neben der Mitte des Spielers. Ein Achtel des
     * Kopfes ist fast nie ein ganzer Pixel; Kopf und Pfeil rundet der Rasterer auf ganze Pixel, ein
     * symmetrischer Pfeil liegt danach höchstens einen halben daneben.
     */
    private static final double SCHWELLE = 0.5;
    /** Ab so vielen Kanälen Unterschied gilt ein Pixel des Kopfes als anders. */
    private static final int ANDERS = 24;
    /** Mit {@code -Pbilder=<ordner>} landen die Bilder der Darstellungen dort. Siehe docs/minimap.md, „Bilder“. */
    private static final String AUSGABE = System.getProperty("heroicmap.bilder", "");
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext spiel = context.worldBuilder().create()) {
            spiel.getConnection().waitForChunksRender();
            spiel.getServer().runCommand("time set noon");
            spiel.getServer().runCommand("weather clear");
            // Blick nach Norden: Ungedreht zeigt der Pfeil dann nach oben, wie gedreht immer.
            spiel.getServer().runCommand("tp @a 0.5 -60 0.5 180 30");
            int[] fenster = context.computeOnClient(mc -> new int[] {mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
            Object[] vorher = context.computeOnClient(mc -> new Object[] {Minimap.INSTANZ.skin(), Minimap.INSTANZ.drehen(),
                Minimap.INSTANZ.darstellung()});
            // 1280 × 720 erlaubt GUI-Massstab 3.
            context.getInput().resizeWindow(1280, 720);
            context.runOnClient(mc -> Minimap.INSTANZ.setzeSkin(Skin.OHNE));
            List<String> fehler = new ArrayList<>();
            for (int gs = 1; gs <= 3; gs++) {
                for (boolean drehen : new boolean[] {false, true}) {
                    pruefe(context, gs, drehen, fehler);
                }
            }
            context.getInput().resizeWindow(fenster[0], fenster[1]);
            context.runOnClient(mc -> {
                mc.options.guiScale().set(fenster[2]);
                mc.resizeGui();
                Minimap.INSTANZ.setzeSkin((String) vorher[0]);
                Minimap.INSTANZ.setzeDrehen((Boolean) vorher[1]);
                Minimap.INSTANZ.setzeDarstellung((Minimap.Darstellung) vorher[2]);
            });
            if (!fehler.isEmpty()) {
                throw new AssertionError("Spieler auf der Minimap: " + String.join("; ", fehler));
            }
        }
    }

    /** Jede Darstellung bei GUI-Massstab {@code gs}, gedreht oder nicht; was nicht stimmt, kommt nach {@code fehler}. */
    private static void pruefe(ClientGameTestContext context, int gs, boolean drehen, List<String> fehler) {
        context.runOnClient(mc -> {
            mc.options.guiScale().set(gs);
            mc.resizeGui();
            Minimap.INSTANZ.setzeDrehen(drehen);
        });
        Map<Minimap.Darstellung, BufferedImage> bilder = new EnumMap<>(Minimap.Darstellung.class);
        double[] mitte = null;
        for (Minimap.Darstellung d : Minimap.Darstellung.values()) {
            context.runOnClient(mc -> Minimap.INSTANZ.setzeDarstellung(d));
            context.waitFor(mc -> Minimap.INSTANZ.fertig(), 1200);
            context.waitTicks(2);
            // Die Mitte des Spielers in Pixeln, wie die Minimap sie rechnet, und ein Achtel des Kopfes.
            mitte = context.computeOnClient(mc -> {
                int k = mc.getWindow().getGuiScale();
                Minimap.Rahmen r = Minimap.INSTANZ.rahmen(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                int n = r.seite() * k;
                return new double[] {r.x() * k + n / 2, r.y() * k + n / 2, Minimap.kopf(r.seite()) * k / 8.0};
            });
            String name = "spieler-gs" + gs + (drehen ? "-gedreht-" : "-") + d.name().toLowerCase(Locale.ROOT);
            BufferedImage bild = lies(context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix()));
            bilder.put(d, bild);
            double[] m = mittig(bild, mitte, d == Minimap.Darstellung.PFEIL);
            String messung = String.format(Locale.ROOT, "Pfeil %+.2f px neben der Mitte in %d Zeilen, %.2f px je Achtel", m[0], (int) m[1], mitte[2]);
            LOGGER.info("Spieler: {}: {}", name, messung);
            if (m[1] < 2 || Math.abs(m[0]) > SCHWELLE) {
                fehler.add(name + ": " + messung);
            }
            if (gs == 2 && !drehen && !AUSGABE.isEmpty()) {
                speichere(bild, mitte, Path.of(AUSGABE, "spieler-" + d.name().toLowerCase(Locale.ROOT) + ".png"));
            }
        }
        // Halb durchsichtig: Durch das Gesicht scheint die Karte, es sieht anders aus als deckend.
        int[] kopf = flaeche(mitte, 4);
        int anders = 0, alle = 0;
        for (int y = kopf[1]; y < kopf[3]; y++) {
            for (int x = kopf[0]; x < kopf[2]; x++) {
                alle++;
                anders += unterschied(bilder.get(Minimap.Darstellung.KOPF).getRGB(x, y),
                        bilder.get(Minimap.Darstellung.DURCHSICHTIG).getRGB(x, y)) > ANDERS ? 1 : 0;
            }
        }
        if (anders * 2 < alle) {
            fehler.add("gs" + gs + (drehen ? " gedreht" : "") + ", halb durchsichtig: nur " + anders + " von " + alle
                    + " Pixeln des Gesichts anders als deckend");
        }
    }

    /**
     * Wie weit liegt der Pfeil waagrecht neben der Mitte? Je Zeile die Spalten seiner schwarzen und weissen
     * Pixel, ihre Mitte gegen die des Spielers; mit Kopf im Band über dem Kopf, ohne Kopf um die Mitte.
     * Die Abweichung der schlimmsten Zeile in Pixeln und wie viele Zeilen den Pfeil zeigen.
     */
    static double[] mittig(BufferedImage bild, double[] mitte, boolean ohneKopf) {
        double cx = mitte[0], cy = mitte[1], s = mitte[2];
        // In Achteln des Kopfes: mit Kopf reicht der Pfeil von -8 bis -4, der Rand des Kopfes beginnt bei -5; ohne Kopf von -4 bis 4.
        double oben = ohneKopf ? cy - 4 * s : cy - 8 * s, unten = ohneKopf ? cy + 4 * s : cy - 5 * s;
        int von = (int) Math.floor(cx - 9 * s), bis = (int) Math.ceil(cx + 9 * s);
        double schlimmste = 0;
        int zeilen = 0;
        for (int y = (int) Math.ceil(oben - 0.5); y + 0.5 < unten; y++) {
            int links = Integer.MAX_VALUE, rechts = Integer.MIN_VALUE;
            for (int x = von; x <= bis; x++) {
                if (pfeil(bild.getRGB(x, y))) {
                    links = Math.min(links, x);
                    rechts = Math.max(rechts, x + 1);
                }
            }
            if (links <= rechts) {
                zeilen++;
                double daneben = (links + rechts) / 2.0 - cx;
                schlimmste = Math.abs(daneben) > Math.abs(schlimmste) ? daneben : schlimmste;
            }
        }
        return new double[] {schlimmste, zeilen};
    }

    /** Schwarz oder Weiss wie der Pfeil; die Karte der Szene hat keins von beiden. */
    private static boolean pfeil(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return Math.max(r, Math.max(g, b)) <= 8 || Math.min(r, Math.min(g, b)) >= 247;
    }

    /** Die Pixel ±{@code achtel} Achtel um die Mitte: links, oben, rechts, unten. */
    private static int[] flaeche(double[] mitte, int achtel) {
        double d = achtel * mitte[2];
        return new int[] {(int) Math.ceil(mitte[0] - d), (int) Math.ceil(mitte[1] - d), (int) Math.floor(mitte[0] + d),
            (int) Math.floor(mitte[1] + d)};
    }

    private static int unterschied(int a, int b) {
        int d = 0;
        for (int s = 0; s < 24; s += 8) {
            d = Math.max(d, Math.abs(((a >> s) & 0xFF) - ((b >> s) & 0xFF)));
        }
        return d;
    }

    private static BufferedImage lies(Path datei) {
        try {
            return ImageIO.read(datei.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Ein Ausschnitt um den Spieler, viermal vergrössert ohne Glätten, damit man die Pixel sieht. */
    private static void speichere(BufferedImage bild, double[] mitte, Path ziel) {
        int[] f = flaeche(mitte, 10);
        BufferedImage teil = bild.getSubimage(f[0], f[1], f[2] - f[0], f[3] - f[1]);
        BufferedImage gross = new BufferedImage(teil.getWidth() * 4, teil.getHeight() * 4, BufferedImage.TYPE_INT_RGB);
        gross.getGraphics().drawImage(teil.getScaledInstance(gross.getWidth(), gross.getHeight(), Image.SCALE_REPLICATE), 0, 0, null);
        try {
            ImageIO.write(gross, "png", ziel.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
