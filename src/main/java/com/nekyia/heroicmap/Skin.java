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
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

/**
 * Ein Rahmen der Minimap: Bänder aus {@code palette.txt}, Ornamente als Sprites im Atlas des GUI,
 * je Skin ein Ordner unter {@code textures/gui/sprites/rahmen/<name>/}. „ohne“ ist kein Skin,
 * sondern der Umriss wie bisher. Siehe docs/rahmen.md.
 */
final class Skin {

    /** Die Wahl im Untermenü, „ohne“ zuerst; ein neuer Skin ist ein Ordner und ein Eintrag hier. */
    static final List<String> NAMEN = List.of("ohne", "grau", "holz", "papier", "kompass", "uhr", "kartograph");
    static final String OHNE = "ohne";
    /** Der Schatten eines Ornaments: Schwarz zu 50 %, um (+1, +1) versetzt. */
    static final int SCHATTEN = 0x80000000;
    /** Die Ornamente: zier, griff und die Marken beim Drehen, je mit {@code _aktiv} eins dahinter. */
    static final int ZIER = 0, GRIFF = 2, NORDEN = 4, MARKE = 6, MARKE_QUER = 8;
    private static final String[] TEILE = {"zier", "zier_aktiv", "griff", "griff_aktiv", "norden", "norden_aktiv",
        "marke", "marke_aktiv", "marke_quer", "marke_quer_aktiv"};
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Geladene Skins, ein Fehlschlag als null, bis der Atlas des GUI neu lädt. */
    private static final Map<String, Skin> GELADEN = new HashMap<>();
    private static List<?> atlasStand;

    /** Ein Rechteck eines Bands, Enden ausschliesslich; licht heisst die Farbe oben und links. */
    interface Band {
        void fill(int xa, int ya, int xb, int yb, int band, boolean licht);
    }

    final String name;
    /** Je Band von aussen nach innen: die Farbe oben und links, die unten und rechts. */
    final int[] licht, schatten;
    final boolean mitSchatten;
    /** Die längere Seite der zier, in Pixeln. */
    final int zier;
    private final Identifier[] sprites = new Identifier[TEILE.length];
    private Identifier ring;
    private int ringSeite;

    private Skin(String name, int[] licht, int[] schatten, boolean mitSchatten, int zier) {
        this.name = name;
        this.licht = licht;
        this.schatten = schatten;
        this.mitSchatten = mitSchatten;
        this.zier = zier;
        for (int i = 0; i < TEILE.length; i++) {
            sprites[i] = sprite(name, TEILE[i]);
        }
    }

    /** Der Skin mit diesem Namen, oder null für „ohne“, einen unbekannten oder unlesbaren. */
    static Skin von(String name) {
        if (OHNE.equals(name) || !NAMEN.contains(name)) {
            return null;
        }
        TextureAtlas atlas = gui();
        if (atlas.sprites != atlasStand) {
            // F3+T oder ein Ressourcenpaket: Paletten, Ringe und Masken neu.
            GELADEN.values().forEach(s -> {
                if (s != null) {
                    s.freigeben();
                }
            });
            GELADEN.clear();
            atlasStand = atlas.sprites;
        }
        return von(name, Skin::laden);
    }

    /** Lädt einen Skin nur einmal; auch ein Fehlschlag bleibt gemerkt, sonst stünde je Frame eine Warnung im Log. */
    static Skin von(String name, Function<String, Skin> lader) {
        if (!GELADEN.containsKey(name)) {
            GELADEN.put(name, lader.apply(name));
        }
        return GELADEN.get(name);
    }

    private static Skin laden(String name) {
        try {
            TextureAtlasSprite zier = gui().getSprite(sprite(name, "zier"));
            return lies(name, text(name, "palette.txt"), text(name, "info.txt"),
                    Math.max(zier.contents().width(), zier.contents().height()));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Heroic Map: Rahmen {} unlesbar", name, e);
            return null;
        }
    }

    private static TextureAtlas gui() {
        return Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.GUI);
    }

    private static Identifier sprite(String name, String teil) {
        return Identifier.fromNamespaceAndPath(HeroicMap.ID, "rahmen/" + name + "/" + teil);
    }

    private static String text(String name, String datei) throws IOException {
        Identifier id = Identifier.fromNamespaceAndPath(HeroicMap.ID, "textures/gui/sprites/rahmen/" + name + "/" + datei);
        try (InputStream rein = Minecraft.getInstance().getResourceManager().open(id)) {
            return new String(rein.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Liest {@code palette.txt}: eine Zeile je Band von aussen nach innen, eine Farbe oder zwei,
     * Licht und Schatten; {@code //} beginnt einen Kommentar. Aus {@code info.txt} nur {@code schatten}.
     * {@code zier} ist die längere Seite der zier in Pixeln. Mindestens zwei Bänder, siehe
     * docs/rahmen.md, „Dateien“.
     */
    static Skin lies(String name, String palette, String info, int zier) {
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
        if (baender.size() < 2) {
            throw new IllegalArgumentException("palette.txt mit weniger als 2 Bändern");
        }
        int[] licht = baender.stream().mapToInt(b -> b[0]).toArray(), schatten = baender.stream().mapToInt(b -> b[1]).toArray();
        return new Skin(name, licht, schatten, info.lines().map(String::trim).anyMatch("schatten=ja"::equals), zier);
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

    /** Wie weit die Minimap mit diesem Rahmen mindestens vom Rand des Schirms bleibt; siehe {@link #einrueckung(int)}. */
    int einrueckung() {
        return einrueckung(zier);
    }

    /** Die halbe Seite der zier, aufgerundet: So bleibt die zier in der Ecke ganz auf dem Schirm. */
    static int einrueckung(int zier) {
        return (zier + 1) / 2;
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
            freigeben();
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

    private void freigeben() {
        if (ring != null) {
            Minecraft.getInstance().getTextureManager().release(ring);
            ring = null;
        }
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

    /**
     * Wo eine Marke beim Drehen sitzt, in Einheiten des GUI: von der Mitte der Minimap (x, y, Seite
     * s) in Richtung (ux, uy), auf der Mitte der Bänder; rund auf dem Kreis, eckig auf dem Quadrat.
     */
    static double[] marke(double x, double y, double s, int baender, boolean rund, double ux, double uy) {
        double h = s / 2 - baender / 2.0, laenge = Math.hypot(ux, uy);
        double t = rund ? h / laenge : h / Math.max(Math.abs(ux), Math.abs(uy));
        return new double[] {x + s / 2 + ux * t, y + s / 2 + uy * t};
    }

    /** Welches Bild eine Marke nimmt: N die Nordmarke, sonst oben und unten marke, links und rechts marke_quer. */
    static int markeFuer(boolean norden, double ux, double uy) {
        return norden ? NORDEN : Math.abs(uy) >= Math.abs(ux) ? MARKE : MARKE_QUER;
    }

    /** Die linke obere Ecke eines Bilds der Breite w, dessen Mitte auf p liegen soll. */
    static int lage(double p, int w) {
        return (int) Math.floor(p - w / 2.0 + 0.5);
    }

    /** Spiegelt das Sprite für die Ecke e (0 oben links bis 3 unten rechts) waagrecht? zier ist für oben links gezeichnet, griff für unten rechts. */
    static boolean spiegeltX(int e, boolean griff) {
        return ((e ^ (griff ? 3 : 0)) & 1) != 0;
    }

    static boolean spiegeltY(int e, boolean griff) {
        return ((e ^ (griff ? 3 : 0)) & 2) != 0;
    }

    /**
     * Zeichnet das Ornament {@code teil} ({@link #ZIER} oder {@link #GRIFF}, plus 1 für aktiv) der
     * Ecke e mit der Mitte auf (px, py), mit Schatten, wenn der Skin ihn will. Gespiegelt über
     * vertauschte UV: Die Ecken des Quads bleiben in derselben Reihenfolge, das GUI verwirft es nicht.
     */
    void ornament(GuiGraphicsExtractor g, int teil, int e, double px, double py) {
        TextureAtlasSprite s = gui().getSprite(sprites[teil]);
        int w = s.contents().width(), h = s.contents().height(), x = lage(px, w), y = lage(py, h);
        boolean griff = teil == GRIFF || teil == GRIFF + 1, sx = spiegeltX(e, griff), sy = spiegeltY(e, griff);
        float u0 = sx ? s.getU1() : s.getU0(), u1 = sx ? s.getU0() : s.getU1();
        float v0 = sy ? s.getV1() : s.getV0(), v1 = sy ? s.getV0() : s.getV1();
        if (mitSchatten) {
            g.innerBlit(RenderPipelines.GUI_TEXTURED, s.atlasLocation(), x + 1, x + 1 + w, y + 1, y + 1 + h, u0, u1, v0, v1, SCHATTEN);
        }
        g.innerBlit(RenderPipelines.GUI_TEXTURED, s.atlasLocation(), x, x + w, y, y + h, u0, u1, v0, v1, -1);
    }
}
