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
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import org.joml.Matrix3x2fStack;
import org.slf4j.Logger;

/**
 * Ein Rahmen der Minimap: Bänder aus {@code palette.txt}, Ornamente als Sprites im Atlas des GUI,
 * je Skin ein Ordner unter {@code textures/gui/sprites/rahmen/<name>/}. „ohne“ ist kein Skin,
 * sondern der Umriss wie bisher. Siehe docs/rahmen.md.
 */
final class Skin {

    /** Die Wahl im Untermenü, „ohne“ zuerst; ein neuer Skin ist ein Ordner und ein Eintrag hier. */
    static final List<String> NAMEN = List.of("ohne", "biom", "grau", "holz", "papier", "kompass", "uhr", "kartograph");
    static final String OHNE = "ohne";
    /** Der Rahmen nach dem Biom unter dem Spieler, die Vorgabe; je Kategorie ein Ordner unter rahmen/biom/, siehe {@link Biom}. */
    static final String BIOM = "biom";
    /** Die Ordner unter rahmen/, die {@link #von} lädt: je fester Skin einer, für „biom“ einer je Kategorie. */
    static final List<String> ORDNER = Stream.concat(NAMEN.stream().filter(n -> !OHNE.equals(n) && !BIOM.equals(n)),
            Biom.KATEGORIEN.stream().map(k -> BIOM + "/" + k)).toList();
    /** Der Schatten eines Ornaments: Schwarz zu 50 %, um (+1, +1) versetzt. */
    static final int SCHATTEN = 0x80000000;
    /** Die Ornamente: der Griff im Menü und die Marken, je mit {@code _aktiv} eins dahinter. */
    static final int GRIFF = 0, NORDEN = 2, MARKE = 4, MARKE_QUER = 6;
    /** Das Bild der Marke je Richtung N, O, S, W, fest; gedreht dreht es mit. Siehe docs/rahmen.md, „Marken“. */
    static final int[] MARKE_JE_RICHTUNG = {NORDEN, MARKE_QUER, MARKE, MARKE_QUER};
    private static final String[] TEILE = {"griff", "griff_aktiv", "norden", "norden_aktiv", "marke", "marke_aktiv",
        "marke_quer", "marke_quer_aktiv"};
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
    /** Zeigt das Bild jeder Marke von der Mitte weg, statt alle gleich mit der Karte nach Norden? Siehe docs/rahmen.md, „Nach aussen“. */
    final boolean nachAussen;
    /** Die längere Seite der grössten Marke, in Pixeln. */
    final int groesste;
    /**
     * Mit {@code buendig=ja}: Breite und Höhe von {@code marke} und der Versatz des Schattens; O, S und W liegen dann samt Schatten
     * innen an der äusseren Kante ({@link #anliegend}). Sonst null. Siehe docs/rahmen.md, „Nach aussen“.
     */
    final int[] auflage;
    private final Identifier[] sprites = new Identifier[TEILE.length];
    private Identifier ring;
    private int ringSeite;

    private Skin(String name, int[] licht, int[] schatten, boolean mitSchatten, boolean nachAussen, int groesste, int[] auflage) {
        this.name = name;
        this.licht = licht;
        this.schatten = schatten;
        this.mitSchatten = mitSchatten;
        this.nachAussen = nachAussen;
        this.groesste = groesste;
        this.auflage = auflage;
        for (int i = 0; i < TEILE.length; i++) {
            sprites[i] = sprite(name, TEILE[i]);
        }
    }

    /** Der Skin aus diesem Ordner ({@link #ORDNER}), oder null für „ohne“, „biom“, einen unbekannten oder unlesbaren. */
    static Skin von(String name) {
        if (!ORDNER.contains(name)) {
            return null;
        }
        TextureAtlas atlas = gui();
        if (atlas.sprites != atlasStand) {
            // F3+T oder ein Ressourcenpaket: Paletten und Ringe neu.
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
            int groesste = 0;
            for (String teil : new String[] {"norden", "marke", "marke_quer"}) {
                TextureAtlasSprite s = gui().getSprite(sprite(name, teil));
                groesste = Math.max(groesste, Math.max(s.contents().width(), s.contents().height()));
            }
            TextureAtlasSprite marke = gui().getSprite(sprite(name, "marke"));
            return lies(name, text(name, "palette.txt"), text(name, "info.txt"), groesste,
                    new int[] {marke.contents().width(), marke.contents().height()});
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
     * Licht und Schatten; {@code //} beginnt einen Kommentar. Aus {@code info.txt} {@code schatten=ja}, {@code aussen=ja} und
     * {@code buendig=ja}. {@code groesste} ist die längere Seite der grössten Marke in Pixeln, {@code marke} Breite und Höhe
     * von {@code marke}, nur für {@code buendig=ja} nötig. Mindestens zwei Bänder, siehe docs/minimap.md, „Form“.
     */
    static Skin lies(String name, String palette, String info, int groesste) {
        return lies(name, palette, info, groesste, null);
    }

    static Skin lies(String name, String palette, String info, int groesste, int[] marke) {
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
        List<String> schalter = info.lines().map(String::trim).toList();
        boolean mitSchatten = schalter.contains("schatten=ja");
        int[] auflage = schalter.contains("buendig=ja") ? new int[] {marke[0], marke[1], mitSchatten ? 1 : 0} : null;
        return new Skin(name, licht, schatten, mitSchatten, schalter.contains("aussen=ja"), groesste, auflage);
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
        return einrueckung(groesste);
    }

    /**
     * Die halbe Diagonale der grössten Marke, aufgerundet: So bleibt sie in jeder Drehung ganz auf dem
     * Schirm, und die Minimap springt beim Umschalten nicht. Siehe docs/rahmen.md, „Abstand zum Rand“.
     */
    static int einrueckung(int groesste) {
        return (int) Math.ceil(groesste * Math.sqrt(2) / 2);
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

    /** Zeichnet die Bänder um das Rechteck (x, y, w, h), in Einheiten des GUI, zu {@code deckung} deckend. */
    void baender(GuiGraphicsExtractor g, int x, int y, int w, int h, float deckung) {
        baender(x, y, w, h, baender(), (xa, ya, xb, yb, i, l) -> g.fill(xa, ya, xb, yb, ARGB.multiplyAlpha(l ? licht[i] : schatten[i], deckung)));
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
     * Wo der Griff an den Ecken sitzt, in Einheiten des GUI, auf der Mitte der Bänder: eckig in der
     * Ecke, rund bei 45°; oben links, oben rechts, unten links, unten rechts.
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
     * Wo eine Marke sitzt, in Einheiten des GUI: von der Mitte der Minimap (x, y, Seite s) in
     * Richtung (ux, uy), auf der Mitte der Bänder; rund auf dem Kreis, eckig auf dem Quadrat. Mit
     * {@code auflage} ({@link #auflage}) liegt sie samt Schatten innen an der äusseren Kante ({@link #anliegend}).
     */
    static double[] marke(double x, double y, double s, int baender, boolean rund, double ux, double uy, int[] auflage) {
        double laenge = Math.hypot(ux, uy), t;
        if (auflage == null) {
            double h = s / 2 - baender / 2.0;
            t = rund ? h / laenge : h / Math.max(Math.abs(ux), Math.abs(uy));
        } else {
            t = anliegend(s, rund, ux / laenge, uy / laenge, auflage) / laenge;
        }
        return new double[] {x + s / 2 + ux * t, y + s / 2 + uy * t};
    }

    /**
     * Wie weit von der Mitte der Minimap (Seite s) die Mitte eines Bilds {Breite, Höhe, Schatten} liegt, das von ihr weg in
     * Richtung (nx, ny) der Länge 1 zeigt, damit es samt Schatten um (+Schatten, +Schatten) gerade innen an der äusseren Kante
     * anliegt: rund am Kreis, eckig an beiden Seiten, die es erreichen kann; gedreht mit dem Kasten des gedrehten Bilds.
     * Siehe docs/rahmen.md, „Nach aussen“.
     */
    static double anliegend(double s, boolean rund, double nx, double ny, int[] auflage) {
        double r = s / 2, w = auflage[0] / 2.0, h = auflage[1] / 2.0, schatten = auflage[2];
        if (rund) {
            // Jede Ecke a des Bilds und seines Schattens im Kreis: |d · n + a| ≤ r.
            double d = Double.MAX_VALUE;
            for (int j = 0; j < 8; j++) {
                double eh = (j & 1) == 0 ? h : -h, ew = (j & 2) == 0 ? w : -w, s0 = j < 4 ? 0 : schatten;
                double ax = eh * nx - ew * ny + s0, ay = eh * ny + ew * nx + s0, p = ax * nx + ay * ny;
                d = Math.min(d, Math.sqrt(p * p - (ax * ax + ay * ay) + r * r) - p);
            }
            return d;
        }
        double[] n = {nx, ny};
        double d = Double.MAX_VALUE;
        for (int a = 0; a < 2; a++) {
            double u = Math.abs(n[a]);
            if (u > 1e-12) {
                // Die halbe Ausdehnung des gedrehten Bilds auf dieser Achse; der Schatten liegt rechts und unten.
                double weit = h * u + w * Math.abs(n[1 - a]) + (n[a] > 0 ? schatten : 0);
                d = Math.min(d, (r - weit) / u);
            }
        }
        return d;
    }

    /** Die linke obere Ecke eines Bilds der Breite w, dessen Mitte auf p liegen soll. */
    static int lage(double p, int w) {
        return (int) Math.floor(p - w / 2.0 + 0.5);
    }

    /** Spiegelt das Sprite für die Ecke e (0 oben links bis 3 unten rechts) waagrecht? griff ist für unten rechts gezeichnet, ohne griff Ecke 0 nie. */
    static boolean spiegeltX(int e, boolean griff) {
        return ((e ^ (griff ? 3 : 0)) & 1) != 0;
    }

    static boolean spiegeltY(int e, boolean griff) {
        return ((e ^ (griff ? 3 : 0)) & 2) != 0;
    }

    /**
     * Zeichnet das Ornament {@code teil} (etwa {@link #NORDEN} oder {@link #GRIFF}, plus 1 für aktiv) der
     * Ecke e mit der Mitte auf (px, py), um {@code winkel} gedreht, zu {@code deckung} deckend, mit
     * Schatten, wenn der Skin ihn will; der Schatten bleibt auf dem Schirm um (+1, +1). Gespiegelt über
     * vertauschte UV: Die Ecken des Quads bleiben in derselben Reihenfolge, das GUI verwirft es nicht.
     */
    void ornament(GuiGraphicsExtractor g, int teil, int e, double px, double py, float deckung, double winkel) {
        TextureAtlasSprite s = gui().getSprite(sprites[teil]);
        boolean griff = teil == GRIFF || teil == GRIFF + 1, sx = spiegeltX(e, griff), sy = spiegeltY(e, griff);
        float u0 = sx ? s.getU1() : s.getU0(), u1 = sx ? s.getU0() : s.getU1();
        float v0 = sy ? s.getV1() : s.getV0(), v1 = sy ? s.getV0() : s.getV1();
        if (mitSchatten) {
            blit(g, s, px + 1, py + 1, winkel, u0, u1, v0, v1, ARGB.multiplyAlpha(SCHATTEN, deckung));
        }
        blit(g, s, px, py, winkel, u0, u1, v0, v1, ARGB.multiplyAlpha(-1, deckung));
    }

    /**
     * Das Bild mit der Mitte auf (px, py): ungedreht und um ganze Vierteldrehungen auf ganzen Einheiten
     * ({@link #lage}), sonst starr um seine Mitte gedreht; dann werden die Kanten treppig wie die der gedrehten Karte.
     */
    private static void blit(GuiGraphicsExtractor g, TextureAtlasSprite s, double px, double py, double winkel,
            float u0, float u1, float v0, float v1, int farbe) {
        int w = s.contents().width(), h = s.contents().height();
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        long viertel = Math.round(winkel / (Math.PI / 2));
        if (Math.abs(winkel - viertel * Math.PI / 2) < 1e-9) {
            // Quer gedreht liegt der Kasten des Bildes mit vertauschten Seiten auf ganzen Einheiten, dann dreht es um dessen Mitte.
            boolean quer = (viertel & 1) != 0;
            int bw = quer ? h : w, bh = quer ? w : h;
            pose.translate(lage(px, bw) + bw / 2f, lage(py, bh) + bh / 2f).rotate((float) (viertel * Math.PI / 2)).translate(-w / 2f, -h / 2f);
        } else {
            pose.translate((float) px, (float) py).rotate((float) winkel).translate(-w / 2f, -h / 2f);
        }
        g.innerBlit(RenderPipelines.GUI_TEXTURED, s.atlasLocation(), 0, w, 0, h, u0, u1, v0, v1, farbe);
        pose.popMatrix();
    }
}
