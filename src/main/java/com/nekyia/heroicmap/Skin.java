package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

/**
 * Ein Rahmen der Karte: Bänder aus {@code palette.txt}, Ornamente als Bilder, je Skin ein Ordner
 * unter {@code textures/rahmen/<name>/}. „ohne“ ist kein Skin, sondern der Umriss wie bisher.
 * Siehe docs/rahmen.md.
 */
final class Skin {

    /** Die Wahl im Untermenü, „ohne“ zuerst; ein neuer Skin ist ein Ordner und ein Eintrag hier. */
    static final List<String> NAMEN = List.of("ohne", "grau", "holz", "papier", "kompass", "uhr", "kartograph");
    static final String OHNE = "ohne";
    /** Der Schatten eines Ornaments: Schwarz zu 50 %, um (+1, +1) versetzt. */
    static final int SCHATTEN = 0x80000000;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Skin> GELADEN = new HashMap<>();

    /** Ein Rechteck eines Bands, Enden ausschliesslich; licht heisst die Farbe oben und links. */
    interface Band {
        void fill(int xa, int ya, int xb, int yb, int band, boolean licht);
    }

    /** Ein Ornament in einer Ecke, schon gespiegelt. */
    private record Bild(Identifier id, int breite, int hoehe) {
    }

    final String name;
    /** Je Band von aussen nach innen: die Farbe oben und links, die unten und rechts. */
    final int[] licht, schatten;
    final boolean mitSchatten;
    private final Map<String, Bild> bilder = new HashMap<>();
    private Identifier ring;
    private int ringSeite;

    private Skin(String name, int[] licht, int[] schatten, boolean mitSchatten) {
        this.name = name;
        this.licht = licht;
        this.schatten = schatten;
        this.mitSchatten = mitSchatten;
    }

    /** Der Skin mit diesem Namen, oder null für „ohne“, einen unbekannten oder unlesbaren. */
    static Skin von(String name) {
        if (OHNE.equals(name) || !NAMEN.contains(name)) {
            return null;
        }
        return GELADEN.computeIfAbsent(name, Skin::laden);
    }

    private static Skin laden(String name) {
        try {
            return lies(name, text(name, "palette.txt"), text(name, "info.txt"));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Heroic Map: Rahmen {} unlesbar", name, e);
            return null;
        }
    }

    private static String text(String name, String datei) throws IOException {
        try (InputStream rein = Minecraft.getInstance().getResourceManager().open(datei(name, datei))) {
            return new String(rein.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Identifier datei(String name, String datei) {
        return Identifier.fromNamespaceAndPath(HeroicMap.ID, "textures/rahmen/" + name + "/" + datei);
    }

    /**
     * Liest {@code palette.txt}: eine Zeile je Band von aussen nach innen, eine Farbe oder zwei,
     * Licht und Schatten; {@code //} beginnt einen Kommentar. Aus {@code info.txt} nur {@code schatten}.
     */
    static Skin lies(String name, String palette, String info) {
        List<int[]> baender = new ArrayList<>();
        for (String zeile : palette.lines().toList()) {
            int kommentar = zeile.indexOf("//");
            String[] werte = (kommentar < 0 ? zeile : zeile.substring(0, kommentar)).trim().split("\\s+");
            if (werte[0].isEmpty()) {
                continue;
            }
            if (werte.length > 2) {
                throw new IllegalArgumentException("palette.txt: " + zeile);
            }
            int a = farbe(werte[0]);
            baender.add(new int[] {a, werte.length == 2 ? farbe(werte[1]) : a});
        }
        if (baender.isEmpty()) {
            throw new IllegalArgumentException("palette.txt ohne Band");
        }
        int[] licht = baender.stream().mapToInt(b -> b[0]).toArray(), schatten = baender.stream().mapToInt(b -> b[1]).toArray();
        return new Skin(name, licht, schatten, info.lines().map(String::trim).anyMatch("schatten=ja"::equals));
    }

    private static int farbe(String text) {
        if (!text.matches("#[0-9A-Fa-f]{6}")) {
            throw new IllegalArgumentException("palette.txt: " + text);
        }
        return 0xFF000000 | Integer.parseInt(text.substring(1), 16);
    }

    int baender() {
        return licht.length;
    }

    /** Das Band des Pixels (x, y) im Rechteck w × h, von aussen gezählt. */
    static int bandEckig(int x, int y, int w, int h) {
        return Math.min(Math.min(x, y), Math.min(w - 1 - x, h - 1 - y));
    }

    static boolean lichtEckig(int x, int y, int w, int h) {
        return Math.min(x, y) < Math.min(w - 1 - x, h - 1 - y);
    }

    /** Das Band des Pixels (x, y) im Kreis in das Quadrat mit der Seite s: R − d abgerundet, d ab der Mitte des Pixels. */
    static int bandRund(int x, int y, int s) {
        double r = s / 2.0, dx = x + 0.5 - r, dy = y + 0.5 - r;
        return (int) Math.floor(r - Math.sqrt(dx * dx + dy * dy));
    }

    static boolean lichtRund(int x, int y, int s) {
        return x + y + 1 < s;
    }

    /**
     * Die Bänder um das Rechteck (x, y, w, h) als Rechtecke, je Band vier: oben und links in Licht,
     * unten und rechts in Schatten, die Ecke oben rechts und unten links im Schatten.
     */
    static void baender(int x, int y, int w, int h, int anzahl, Band aus) {
        for (int i = 0; i < anzahl; i++) {
            int a = x + i, b = y + i, c = x + w - 1 - i, d = y + h - 1 - i;
            aus.fill(a, b, c, b + 1, i, true);
            aus.fill(a, b + 1, a + 1, d, i, true);
            aus.fill(a, d, c + 1, d + 1, i, false);
            aus.fill(c, b, c + 1, d, i, false);
        }
    }

    /** Zeichnet die Bänder um das Rechteck (x, y, w, h), in Einheiten des GUI. */
    void baender(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        baender(x, y, w, h, baender(), (xa, ya, xb, yb, i, l) -> g.fill(xa, ya, xb, yb, l ? licht[i] : schatten[i]));
    }

    /** Die Farbe des Rings im Quadrat mit der Seite s an (x, y), oder 0 ausserhalb der Bänder. */
    int ringFarbe(int x, int y, int s) {
        int band = bandRund(x, y, s);
        return band < 0 || band >= baender() ? 0 : lichtRund(x, y, s) ? licht[band] : schatten[band];
    }

    /** Der Ring für die runde Minimap mit der Seite s, ein Texel je Einheit des GUI; nur der für die letzte Seite bleibt. */
    Identifier ring(int s) {
        if (ring == null || ringSeite != s) {
            if (ring != null) {
                Minecraft.getInstance().getTextureManager().release(ring);
            }
            Identifier id = Identifier.fromNamespaceAndPath(HeroicMap.ID, "rahmen/" + name + "/ring");
            DynamicTexture textur = new DynamicTexture(() -> "heroicmap " + id, s, s, true);
            NativeImage pixel = textur.getPixels();
            for (int y = 0; y < s; y++) {
                for (int x = 0; x < s; x++) {
                    pixel.setPixel(x, y, ringFarbe(x, y, s));
                }
            }
            textur.upload();
            Minecraft.getInstance().getTextureManager().register(id, textur);
            ring = id;
            ringSeite = s;
        }
        return ring;
    }

    /**
     * Was von der runden Minimap mit der Seite s innerhalb der Bänder liegt, als Läufe wie
     * {@link Minimap#laeufe}, in Pixeln des Schirms beim GUI-Massstab k: dieselbe Rechnung wie
     * der Ring, je Einheit des GUI.
     */
    static List<int[]> maskeRund(int s, int baender, int k) {
        List<int[]> laeufe = new ArrayList<>();
        int[] lauf = null;
        for (int y = 0; y < s; y++) {
            int a = 0;
            while (a < s - a && bandRund(a, y, s) < baender) {
                a++;
            }
            if (a >= s - a) {
                lauf = null;
            } else if (lauf != null && lauf[2] == a * k) {
                lauf[1] = (y + 1) * k;
            } else {
                lauf = new int[] {y * k, (y + 1) * k, a * k, (s - a) * k};
                laeufe.add(lauf);
            }
        }
        return laeufe;
    }

    /**
     * Wo die Ornamente sitzen, in Einheiten des GUI, auf der Mitte der Bänder: eckig an den Ecken,
     * rund bei 45°; oben links, oben rechts, unten links, unten rechts.
     */
    static double[][] ecken(double x, double y, double w, double h, int baender, boolean rund) {
        double m = baender / 2.0;
        if (!rund) {
            return new double[][] {{x + m, y + m}, {x + w - m, y + m}, {x + m, y + h - m}, {x + w - m, y + h - m}};
        }
        double cx = x + w / 2, cy = y + h / 2, q = (w / 2 - m) / Math.sqrt(2);
        return new double[][] {{cx - q, cy - q}, {cx + q, cy - q}, {cx - q, cy + q}, {cx + q, cy + q}};
    }

    /** Wie weit die Vollbildkarte ihren Rahmen einrückt, in Einheiten des GUI; siehe {@link #einrueckung(int)}. */
    int einrueckung() {
        Bild zier = bild("zier", 0);
        return zier == null ? 0 : einrueckung(Math.max(zier.breite(), zier.hoehe()));
    }

    /** Die halbe Seite der zier, aufgerundet: So bleibt die zier in der Ecke ganz auf dem Schirm. */
    static int einrueckung(int zier) {
        return (zier + 1) / 2;
    }

    /** Die linke obere Ecke eines Bilds der Breite w, dessen Mitte auf p liegen soll. */
    static int lage(double p, int w) {
        return (int) Math.floor(p - w / 2.0 + 0.5);
    }

    /**
     * Zeichnet das Ornament {@code teil} der Ecke e (0 oben links, 1 oben rechts, 2 unten links,
     * 3 unten rechts) mit der Mitte auf (px, py), mit Schatten, wenn der Skin ihn will. Gezeichnet
     * ist zier für oben links, griff für unten rechts; die anderen Ecken spiegeln die Pixel, denn
     * das GUI verwirft gespiegelte Flächen.
     */
    void ornament(GuiGraphicsExtractor g, String teil, boolean aktiv, int e, double px, double py) {
        Bild bild = bild(teil + (aktiv ? "_aktiv" : ""), e ^ ("griff".equals(teil) ? 3 : 0));
        if (bild == null) {
            return;
        }
        int x = lage(px, bild.breite()), y = lage(py, bild.hoehe());
        if (mitSchatten) {
            g.blit(RenderPipelines.GUI_TEXTURED, bild.id(), x + 1, y + 1, 0, 0, bild.breite(), bild.hoehe(),
                    bild.breite(), bild.hoehe(), SCHATTEN);
        }
        g.blit(RenderPipelines.GUI_TEXTURED, bild.id(), x, y, 0, 0, bild.breite(), bild.hoehe(), bild.breite(), bild.hoehe());
    }

    private Bild bild(String teil, int e) {
        String schluessel = teil + "/" + e;
        if (!bilder.containsKey(schluessel)) {
            bilder.put(schluessel, lade(teil, e));
        }
        return bilder.get(schluessel);
    }

    private Bild lade(String teil, int e) {
        try (InputStream rein = Minecraft.getInstance().getResourceManager().open(datei(name, teil + ".png"));
                NativeImage quelle = NativeImage.read(rein)) {
            int w = quelle.getWidth(), h = quelle.getHeight();
            boolean rechts = (e & 1) != 0, unten = (e & 2) != 0;
            Identifier id = Identifier.fromNamespaceAndPath(HeroicMap.ID, "rahmen/" + name + "/" + teil + "_" + e);
            DynamicTexture textur = new DynamicTexture(() -> "heroicmap " + id, w, h, true);
            NativeImage pixel = textur.getPixels();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    pixel.setPixel(rechts ? w - 1 - x : x, unten ? h - 1 - y : y, quelle.getPixel(x, y));
                }
            }
            textur.upload();
            Minecraft.getInstance().getTextureManager().register(id, textur);
            return new Bild(id, w, h);
        } catch (IOException | RuntimeException kaputt) {
            LOGGER.warn("Heroic Map: Ornament {}/{} unlesbar", name, teil, kaputt);
            return null;
        }
    }
}
