package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
 * Ein Rahmen der Minimap: eine Variante einer Familie, Bänder aus {@code palette.txt}, Ornamente als Sprites im Atlas des
 * GUI, je Variante ein Ordner unter {@code textures/gui/sprites/rahmen/<familie>/}. „ohne“ ist kein Skin, sondern der Umriss
 * wie bisher. Siehe docs/rahmen.md.
 */
final class Skin {

    /** Die Familien im Wähler, „ohne“ zuerst. Siehe docs/rahmen.md, „Familien“. */
    static final List<String> FAMILIEN = List.of("ohne", "biom", "schild", "rose", "lettern");
    static final String OHNE = "ohne";
    /** Der Rahmen nach dem Biom unter dem Spieler, die Vorgabe; je Kategorie eine Familie biom/<kategorie>, siehe {@link Biom}. */
    static final String BIOM = "biom";
    /** Die Ordner mit varianten.txt: die festen Familien und je Kategorie von „biom“ einer. */
    static final List<String> MIT_VARIANTEN = Stream.concat(Stream.of("schild", "rose", "lettern"),
            Biom.KATEGORIEN.stream().map(k -> BIOM + "/" + k)).toList();
    /** Die Skins bis 0.2.68 als Familie und Variante; eine gespeicherte Wahl zieht so um. Siehe docs/rahmen.md, „Wahl“. */
    static final Map<String, List<String>> ALT = Map.of("grau", List.of("schild", "grau"), "holz", List.of("schild", "gold"),
            "papier", List.of("schild", "papier"), "kompass", List.of("rose", "silber"), "uhr", List.of("rose", "gold"),
            "kartograph", List.of("rose", "pergament"));
    /** Der Schatten eines Ornaments: Schwarz zu 50 %, um (+1, +1) versetzt. */
    static final int SCHATTEN = 0x80000000;
    /** Die Ornamente: der Griff im Menü und die Marken, je mit {@code _aktiv} eins dahinter. */
    static final int GRIFF = 0, NORDEN = 2, MARKE = 4, MARKE_QUER = 6, OSTEN = 8, SUEDEN = 10, WESTEN = 12;
    /** Das Bild der Marke je Richtung N, O, S, W, fest; gedreht dreht es mit. Siehe docs/rahmen.md, „Marken“. */
    static final int[] MARKE_JE_RICHTUNG = {NORDEN, MARKE_QUER, MARKE, MARKE_QUER};
    /** Mit einem eigenen Bild je Richtung, etwa die Buchstaben von „lettern“. */
    static final int[] EIGENE_JE_RICHTUNG = {NORDEN, OSTEN, SUEDEN, WESTEN};
    private static final String[] TEILE = {"griff", "griff_aktiv", "norden", "norden_aktiv", "marke", "marke_aktiv",
        "marke_quer", "marke_quer_aktiv", "osten", "osten_aktiv", "sueden", "sueden_aktiv", "westen", "westen_aktiv"};
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Geladene Skins, ein Fehlschlag als null, bis der Atlas des GUI neu lädt. */
    private static final Map<String, Skin> GELADEN = new HashMap<>();
    /** Die Varianten je Familie, eine unlesbare leer, bis der Atlas des GUI neu lädt. */
    private static final Map<String, List<Variante>> VARIANTEN = new HashMap<>();
    private static List<?> atlasStand;

    /** Ein Rechteck eines Bands, Enden ausschliesslich; licht heisst die Farbe oben und links. */
    interface Band {
        void fill(int xa, int ya, int xb, int yb, int band, boolean licht);
    }

    /** Wie das Bild einer Marke steht: starr mit der Karte, von der Mitte weg oder immer aufrecht. Siehe docs/rahmen.md, „Drehen“. */
    enum Stellung { KARTE, AUSSEN, AUFRECHT }

    /**
     * Wo eine Marke liegt: auf der Mitte der Bänder, auf ihnen aussen bündig samt Schatten, ganz in der Karte mit einer Einheit
     * Abstand zu den Bändern, oder ganz ausserhalb mit einer Einheit Abstand. Siehe docs/rahmen.md, „Lage“.
     */
    enum Lage { MITTE, KANTE, INNEN, AUSSEN }

    /** Je Richtung: die Lage, Breite und Höhe des Bilds in Einheiten, wie es für oben gezeichnet ist, und der Versatz des Schattens. */
    record Platz(Lage lage, int w, int h, int schatten) {
    }

    /** Was die Marken zum Zeichnen brauchen: ihre Stellung, je Richtung N, O, S, W ihr Platz, null auf der Mitte, und ob O, S, W eigene Bilder haben. */
    record Marken(Stellung stellung, Platz[] plaetze, boolean eigene) {
        static final Marken STARR = new Marken(Stellung.KARTE, null, false), AUSSEN = new Marken(Stellung.AUSSEN, null, false);

        Platz platz(int richtung) {
            return plaetze == null ? null : plaetze[richtung];
        }
    }

    /** Eine Zeile aus varianten.txt: der Name, der Ordner unter der Familie und Schalter, die info.txt dort überschreiben. */
    record Variante(String name, String ordner, List<String> schalter) {
    }

    /** {@code familie/variante}, bei „biom“ {@code biom/<kategorie>/variante}. */
    final String name;
    /** Der Ordner der Bilder unter rahmen/: {@code familie/ordner}. */
    final String ordner;
    /** Je Band von aussen nach innen: die Farbe oben und links, die unten und rechts. */
    final int[] licht, schatten;
    final boolean mitSchatten;
    final Marken marken;
    /** Die längere Seite der grössten Marke, in Pixeln. */
    final int groesste;
    /** Wie weit die Minimap mit diesem Rahmen mindestens vom Rand des Schirms bleibt, siehe {@link #einrueckung(Marken, int, int)}. */
    private final int einrueckung;
    private final Identifier[] sprites = new Identifier[TEILE.length];
    /** Die Ringe für die runde Minimap, zwei Seiten zugleich: die der Minimap und die der Vorschau im Wähler. */
    private final Identifier[] ringe = new Identifier[2];
    private final int[] ringSeiten = {-1, -1};
    private int ringNeu;

    private Skin(String name, String ordner, int[] licht, int[] schatten, boolean mitSchatten, Marken marken, int groesste) {
        this.name = name;
        this.ordner = ordner;
        this.licht = licht;
        this.schatten = schatten;
        this.mitSchatten = mitSchatten;
        this.marken = marken;
        this.groesste = groesste;
        this.einrueckung = einrueckung(marken, licht.length, groesste);
        for (int i = 0; i < TEILE.length; i++) {
            sprites[i] = sprite(ordner, TEILE[i]);
        }
    }

    /** Die Varianten der Familie ({@link #MIT_VARIANTEN}), die erste die Vorgabe; leer für eine andere oder eine unlesbare. */
    static List<Variante> varianten(String familie) {
        if (!MIT_VARIANTEN.contains(familie)) {
            return List.of();
        }
        pruefeAtlas();
        return VARIANTEN.computeIfAbsent(familie, f -> {
            try {
                return liesVarianten(text(f, "varianten.txt"));
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Heroic Map: Varianten von {} unlesbar", f, e);
                return List.of();
            }
        });
    }

    /** Die Variante {@code name} der Familie, sonst ihre erste; null ohne Varianten. */
    static Variante variante(String familie, String name) {
        List<Variante> alle = varianten(familie);
        return alle.stream().filter(v -> v.name().equals(name)).findFirst().orElse(alle.isEmpty() ? null : alle.getFirst());
    }

    /** Der Skin der Variante {@code name} der Familie, sonst ihrer ersten; null für „ohne“, „biom“ ohne Kategorie, eine unbekannte oder unlesbare. */
    static Skin von(String familie, String name) {
        Variante v = variante(familie, name);
        if (v == null) {
            return null;
        }
        return von(familie + "/" + v.name(), n -> laden(n, familie + "/" + v.ordner(), v.schalter()));
    }

    /** Ein Ressourcenpaket oder F3+T lädt den Atlas neu: Paletten, Varianten und Ringe neu. */
    private static void pruefeAtlas() {
        TextureAtlas atlas = gui();
        if (atlas.sprites != atlasStand) {
            GELADEN.values().forEach(s -> {
                if (s != null) {
                    s.freigeben();
                }
            });
            GELADEN.clear();
            VARIANTEN.clear();
            atlasStand = atlas.sprites;
        }
    }

    /** Lädt einen Skin nur einmal; auch ein Fehlschlag bleibt gemerkt, sonst stünde je Frame eine Warnung im Log. */
    static Skin von(String name, Function<String, Skin> lader) {
        if (!GELADEN.containsKey(name)) {
            GELADEN.put(name, lader.apply(name));
        }
        return GELADEN.get(name);
    }

    private static Skin laden(String name, String ordner, List<String> schalter) {
        pruefeAtlas();
        try {
            Map<String, int[]> bilder = new HashMap<>();
            for (String teil : new String[] {"norden", "marke", "marke_quer", "osten", "sueden", "westen"}) {
                Identifier datei = Identifier.fromNamespaceAndPath(HeroicMap.ID, "textures/gui/sprites/rahmen/" + ordner + "/" + teil + ".png");
                if (Minecraft.getInstance().getResourceManager().getResource(datei).isPresent()) {
                    TextureAtlasSprite s = gui().getSprite(sprite(ordner, teil));
                    bilder.put(teil, new int[] {s.contents().width(), s.contents().height()});
                }
            }
            String info = text(ordner, "info.txt") + "\n" + String.join("\n", schalter);
            return lies(name, ordner, text(ordner, "palette.txt"), info, bilder);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Heroic Map: Rahmen {} unlesbar", name, e);
            return null;
        }
    }

    private static TextureAtlas gui() {
        return Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.GUI);
    }

    private static Identifier sprite(String ordner, String teil) {
        return Identifier.fromNamespaceAndPath(HeroicMap.ID, "rahmen/" + ordner + "/" + teil);
    }

    private static String text(String ordner, String datei) throws IOException {
        Identifier id = Identifier.fromNamespaceAndPath(HeroicMap.ID, "textures/gui/sprites/rahmen/" + ordner + "/" + datei);
        try (InputStream rein = Minecraft.getInstance().getResourceManager().open(id)) {
            return new String(rein.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Liest {@code varianten.txt}: eine Variante je Zeile, die erste die Vorgabe, {@code //} beginnt einen Kommentar; der Name
     * aus a–z, 0–9 und _, dann wahlweise {@code ordner=<unterordner>}, sonst wie der Name, und Schalter wie in info.txt.
     */
    static List<Variante> liesVarianten(String text) {
        List<Variante> aus = new ArrayList<>();
        for (String zeile : text.lines().toList()) {
            int kommentar = zeile.indexOf("//");
            String[] teile = (kommentar < 0 ? zeile : zeile.substring(0, kommentar)).trim().split("\\s+");
            if (teile[0].isEmpty()) {
                continue;
            }
            String ordner = teile[0];
            List<String> schalter = new ArrayList<>();
            for (int i = 1; i < teile.length; i++) {
                if (teile[i].startsWith("ordner=")) {
                    ordner = teile[i].substring("ordner=".length());
                } else if (teile[i].indexOf('=') > 0) {
                    schalter.add(teile[i]);
                } else {
                    throw new IllegalArgumentException("varianten.txt: " + zeile);
                }
            }
            if (!teile[0].matches("[a-z0-9_]+") || !ordner.matches("[a-z0-9_]+")) {
                throw new IllegalArgumentException("varianten.txt: " + zeile);
            }
            aus.add(new Variante(teile[0], ordner, List.copyOf(schalter)));
        }
        if (aus.isEmpty()) {
            throw new IllegalArgumentException("varianten.txt ohne Variante");
        }
        return List.copyOf(aus);
    }

    /** Wie {@link #lies(String, String, String, String, Map)} mit nur {@code norden} in der Grösse {@code groesste}, für die Tests. */
    static Skin lies(String name, String palette, String info, int groesste) {
        return lies(name, name, palette, info, Map.of("norden", new int[] {groesste, groesste}));
    }

    /**
     * Liest {@code palette.txt}: eine Zeile je Band von aussen nach innen, eine Farbe oder zwei, Licht und Schatten; {@code //}
     * beginnt einen Kommentar. Aus {@code info.txt}, die letzte Zeile je Schlüssel gilt, so überschreibt eine Variante:
     * {@code schatten=ja}, {@code drehung} ({@link Stellung}, ein altes {@code aussen=ja} heisst {@code aussen}), {@code lage} und
     * {@code lage_norden} ({@link Lage}). {@code kante} nur mit {@code drehung=aussen}, {@code innen} und {@code aussen} nur mit
     * {@code drehung=aufrecht}. {@code bilder} sind Breite und Höhe der Marken, die es gibt, je Teil wie {@code norden} oder
     * {@code osten}. Mindestens zwei Bänder, siehe docs/minimap.md, „Form“.
     */
    static Skin lies(String name, String ordner, String palette, String info, Map<String, int[]> bilder) {
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
        Map<String, String> s = new HashMap<>();
        for (String zeile : info.lines().map(String::trim).toList()) {
            int gleich = zeile.indexOf('=');
            if (gleich > 0) {
                s.put(zeile.substring(0, gleich), zeile.substring(gleich + 1));
            }
        }
        boolean mitSchatten = "ja".equals(s.get("schatten"));
        Stellung stellung = wert(Stellung.class, "drehung", s.getOrDefault("drehung", "ja".equals(s.get("aussen")) ? "aussen" : "karte"));
        Lage lage = wert(Lage.class, "lage", s.getOrDefault("lage", "mitte"));
        Lage lageNorden = wert(Lage.class, "lage_norden", s.getOrDefault("lage_norden", lage.name().toLowerCase(Locale.ROOT)));
        for (Lage l : List.of(lage, lageNorden)) {
            if (l == Lage.KANTE && stellung != Stellung.AUSSEN || (l == Lage.INNEN || l == Lage.AUSSEN) && stellung != Stellung.AUFRECHT) {
                throw new IllegalArgumentException("info.txt: lage=" + l.name().toLowerCase(Locale.ROOT) + " nicht mit drehung="
                        + stellung.name().toLowerCase(Locale.ROOT));
            }
        }
        boolean eigene = stellung == Stellung.AUFRECHT && bilder.containsKey("osten") && bilder.containsKey("sueden") && bilder.containsKey("westen");
        // Je Richtung das Bild, wie es gezeichnet wird: aussen dreht marke mit, aufrecht nimmt das eigene, starr marke und marke_quer.
        String[] teil = eigene ? new String[] {"norden", "osten", "sueden", "westen"}
                : stellung == Stellung.AUSSEN ? new String[] {"norden", "marke", "marke", "marke"} : new String[] {"norden", "marke_quer", "marke", "marke_quer"};
        Platz[] plaetze = null;
        if (lage != Lage.MITTE || lageNorden != Lage.MITTE) {
            plaetze = new Platz[4];
            for (int i = 0; i < 4; i++) {
                Lage l = i == 0 ? lageNorden : lage;
                int[] b = bilder.get(teil[i]);
                if (l != Lage.MITTE && b == null) {
                    throw new IllegalArgumentException("Bild " + teil[i] + " fehlt für lage=" + l.name().toLowerCase(Locale.ROOT));
                }
                plaetze[i] = l == Lage.MITTE ? null : new Platz(l, b[0], b[1], mitSchatten ? 1 : 0);
            }
        }
        int groesste = bilder.values().stream().mapToInt(b -> Math.max(b[0], b[1])).max().orElse(0);
        return new Skin(name, ordner, licht, schatten, mitSchatten, new Marken(stellung, plaetze, eigene), groesste);
    }

    /** Der Wert eines Schlüssels in info.txt als Konstante von {@code art}, klein geschrieben; anderes geht nicht. */
    private static <E extends Enum<E>> E wert(Class<E> art, String schluessel, String text) {
        for (E e : art.getEnumConstants()) {
            if (e.name().toLowerCase(Locale.ROOT).equals(text)) {
                return e;
            }
        }
        throw new IllegalArgumentException("info.txt: " + schluessel + "=" + text);
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

    /** Wie weit die Minimap mit diesem Rahmen mindestens vom Rand des Schirms bleibt; siehe {@link #einrueckung(Marken, int, int)}. */
    int einrueckung() {
        return einrueckung;
    }

    /**
     * Wie {@link #einrueckung(int)}; liegen Marken aussen, mindestens so weit, wie sie samt Schatten in irgendeiner Drehung über
     * die Minimap hinausreichen, eckig wie rund, aufgerundet. Siehe docs/rahmen.md, „Abstand zum Rand“.
     */
    static int einrueckung(Marken marken, int baender, int groesste) {
        double s = 128, weit = 0;
        for (int i = 0; i < 4; i++) {
            Platz p = marken.platz(i);
            if (p == null || p.lage() != Lage.AUSSEN) {
                continue;
            }
            for (boolean rund : new boolean[] {false, true}) {
                for (int grad = 0; grad < 360; grad++) {
                    double a = Math.toRadians(grad);
                    double[] m = marke(-s / 2, -s / 2, s, baender, rund, Math.sin(a), -Math.cos(a), p);
                    weit = Math.max(weit, Math.max(Math.abs(m[0]) + p.w() / 2.0, Math.abs(m[1]) + p.h() / 2.0) + p.schatten() - s / 2);
                }
            }
        }
        // Ein Tausendstel weniger: Die Halbierung in aussen liegt um so viel darüber.
        return Math.max(einrueckung(groesste), (int) Math.ceil(weit - 1e-3));
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
        for (int i = 0; i < ringe.length; i++) {
            if (ringe[i] != null && ringSeiten[i] == s) {
                return ringe[i];
            }
        }
        // Der ältere der zwei weicht; so baut weder die Minimap noch die Vorschau je Frame neu.
        int i = ringNeu;
        ringNeu = (ringNeu + 1) % ringe.length;
        if (ringe[i] != null) {
            Minecraft.getInstance().getTextureManager().release(ringe[i]);
        }
        Identifier id = Identifier.fromNamespaceAndPath(HeroicMap.ID, "rahmen/" + name + "/ring_" + s);
        DynamicTexture textur = new DynamicTexture(() -> "heroicmap " + id, s, s, true);
        NativeImage pixel = textur.getPixels();
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                pixel.setPixel(x, y, ringFarbe(x, y, s));
            }
        }
        textur.upload();
        Minecraft.getInstance().getTextureManager().register(id, textur);
        ringe[i] = id;
        ringSeiten[i] = s;
        return id;
    }

    private void freigeben() {
        for (int i = 0; i < ringe.length; i++) {
            if (ringe[i] != null) {
                Minecraft.getInstance().getTextureManager().release(ringe[i]);
                ringe[i] = null;
                ringSeiten[i] = -1;
            }
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
     * Wo eine Marke sitzt, in Einheiten des GUI: von der Mitte der Minimap (x, y, Seite s) in Richtung (ux, uy), nach ihrem
     * {@code platz}: ohne auf der Mitte der Bänder, rund auf dem Kreis, eckig auf dem Quadrat; sonst nach {@link Lage}. Siehe
     * docs/rahmen.md, „Lage“.
     */
    static double[] marke(double x, double y, double s, int baender, boolean rund, double ux, double uy, Platz platz) {
        double laenge = Math.hypot(ux, uy), nx = ux / laenge, ny = uy / laenge;
        double d = switch (platz == null ? Lage.MITTE : platz.lage()) {
            case MITTE -> {
                double h = s / 2 - baender / 2.0;
                yield rund ? h : h / Math.max(Math.abs(nx), Math.abs(ny));
            }
            case KANTE -> anliegend(s, rund, nx, ny, platz);
            case INNEN -> innen(s / 2 - baender - 1, rund, nx, ny, platz.w() / 2.0, platz.h() / 2.0);
            case AUSSEN -> aussen(s / 2 + 1, rund, nx, ny, platz.w() / 2.0, platz.h() / 2.0);
        };
        return new double[] {x + s / 2 + nx * d, y + s / 2 + ny * d};
    }

    /**
     * Wie weit von der Mitte der Minimap (Seite s) die Mitte eines Bilds liegt, das von ihr weg in Richtung (nx, ny) der Länge 1
     * zeigt, damit es samt Schatten um (+Schatten, +Schatten) gerade innen an der äusseren Kante anliegt: rund am Kreis, eckig
     * an beiden Seiten, die es erreichen kann; gedreht mit dem Kasten des gedrehten Bilds. Siehe docs/rahmen.md, „Lage“.
     */
    static double anliegend(double s, boolean rund, double nx, double ny, Platz platz) {
        double r = s / 2, w = platz.w() / 2.0, h = platz.h() / 2.0, schatten = platz.schatten();
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

    /**
     * Wie weit in Richtung (nx, ny) die Mitte eines aufrechten Bilds mit den halben Seiten (w, h) liegt, das ganz innerhalb von
     * {@code r} um die Mitte bleibt und es berührt: eckig im Quadrat mit der halben Seite r, rund mit jeder Ecke im Kreis.
     */
    static double innen(double r, boolean rund, double nx, double ny, double w, double h) {
        double d = Double.MAX_VALUE;
        if (rund) {
            for (int j = 0; j < 4; j++) {
                double ax = (j & 1) == 0 ? w : -w, ay = (j & 2) == 0 ? h : -h, p = ax * nx + ay * ny;
                d = Math.min(d, Math.sqrt(p * p - (ax * ax + ay * ay) + r * r) - p);
            }
            return d;
        }
        if (Math.abs(nx) > 1e-12) {
            d = Math.min(d, (r - w) / Math.abs(nx));
        }
        if (Math.abs(ny) > 1e-12) {
            d = Math.min(d, (r - h) / Math.abs(ny));
        }
        return d;
    }

    /**
     * Wie weit in Richtung (nx, ny) die Mitte eines aufrechten Bilds mit den halben Seiten (w, h) liegt, das ganz ausserhalb von
     * {@code r} um die Mitte liegt und es berührt: eckig ausserhalb des Quadrats mit der halben Seite r, rund mit seinem
     * nächsten Punkt auf dem Kreis.
     */
    static double aussen(double r, boolean rund, double nx, double ny, double w, double h) {
        if (!rund) {
            double d = Double.MAX_VALUE;
            if (Math.abs(nx) > 1e-12) {
                d = Math.min(d, (r + w) / Math.abs(nx));
            }
            if (Math.abs(ny) > 1e-12) {
                d = Math.min(d, (r + h) / Math.abs(ny));
            }
            return d;
        }
        // Der Abstand des nächsten Punkts wächst mit d; halbiert, bis er auf ein Millionstel stimmt.
        double unten = 0, oben = r + w + h;
        while (oben - unten > 1e-6) {
            double d = (unten + oben) / 2, dx = Math.max(0, Math.abs(d * nx) - w), dy = Math.max(0, Math.abs(d * ny) - h);
            if (Math.hypot(dx, dy) < r) {
                unten = d;
            } else {
                oben = d;
            }
        }
        return oben;
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
