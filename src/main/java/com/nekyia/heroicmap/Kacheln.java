package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.NativeImage;
import com.nekyia.heroicmap.webp.VP8LDecoder;
import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Die Kacheln eines Satzes als Texturen: im Hintergrund dekodiert, auf dem Render-Thread
 * hochgeladen, die zuletzt gezeigten behalten. Siehe docs/vollbildkarte.md.
 */
final class Kacheln implements AutoCloseable {

    /** So viele Texturen bleiben; bei 256² Pixeln rund 48 MiB. */
    private static final int MAX = 192;

    /** Ein dekodiertes Bild, ARGB Zeile für Zeile. */
    record Bild(int breite, int hoehe, int[] argb) {
    }

    /** Die offene Karte, der ein fertiger Download gemeldet wird; nur auf dem Render-Thread. */
    private static Kacheln offen;

    private final Path ordner;
    private final int seite;
    private final ExecutorService dekoder = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Heroic Map Kacheln");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Identifier> texturen = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Identifier> aeltester) {
            if (size() > MAX) {
                Minecraft.getInstance().getTextureManager().release(aeltester.getValue());
                return true;
            }
            return false;
        }
    };
    private final Set<String> laeuft = new HashSet<>();
    /** Kacheln, die es nicht gibt oder die sich nicht lesen lassen; der Mod fragt erst nach einem Download wieder. */
    private final Set<String> leer = new HashSet<>();
    /** Je Kachel zählt jeder Download; ein Ergebnis aus einer älteren Zählung ist veraltet. */
    private final Map<String, Integer> generation = new HashMap<>();
    /** Kacheln mit einer älteren Textur: Sie bleibt sichtbar, bis die neue da ist. */
    private final Set<String> veraltet = new HashSet<>();
    private boolean geschlossen;
    private int naechste;

    /** Die Kacheln des Satzes; jede muss genau {@code tileSize} gross sein. Auf dem Render-Thread. */
    Kacheln(Satz satz) {
        this.ordner = satz.ordner();
        this.seite = satz.kachel();
        offen = this;
    }

    /** Eine Kachel der selbst gezeichneten Karte in {@code ordner} ist neu geschrieben; ist sie offen, lädt die Kachel neu. */
    static void geaendert(Path ordner, int z, int x, int y) {
        if (offen != null && offen.ordner.equals(ordner)) {
            String pfad = z + "/" + x + "/" + y;
            offen.leer.remove(pfad);
            if (offen.texturen.containsKey(pfad) || offen.laeuft.contains(pfad)) {
                offen.generation.merge(pfad, 1, Integer::sum);
                offen.veraltet.add(pfad);
            }
        }
    }

    /** Nach einem vollständigen Download: Alle Kacheln laden neu. Auf dem Render-Thread. */
    static void satzGeladen() {
        if (offen != null) {
            offen.allesNeu();
        }
    }

    private void allesNeu() {
        leer.clear();
        for (String pfad : texturen.keySet()) {
            generation.merge(pfad, 1, Integer::sum);
            veraltet.add(pfad);
        }
        for (String pfad : laeuft) {
            generation.merge(pfad, 1, Integer::sum);
        }
    }

    /** Die Textur der Kachel, wenn sie schon geladen ist, ohne sie anzufragen; sonst null. */
    Identifier vorhanden(int z, int x, int y) {
        return texturen.get(z + "/" + x + "/" + y);
    }

    /** Die Textur der Kachel, oder null, solange sie das erste Mal lädt oder es sie nicht gibt. */
    Identifier textur(int z, int x, int y) {
        String pfad = z + "/" + x + "/" + y;
        Identifier id = texturen.get(pfad);
        if ((id != null && !veraltet.contains(pfad)) || leer.contains(pfad) || laeuft.contains(pfad)) {
            return id;
        }
        laeuft.add(pfad);
        veraltet.remove(pfad);
        int gen = generation.getOrDefault(pfad, 0);
        Path spalte = ordner.resolve(String.valueOf(z)).resolve(String.valueOf(x));
        Path datei = spalte.resolve(y + ".webp"), png = spalte.resolve(y + ".png");
        dekoder.execute(() -> {
            NativeImage pixel = null;
            try {
                int[] argb = null;
                try {
                    if (Files.exists(datei)) {
                        argb = dekodiere(Files.readAllBytes(datei), seite).argb();
                    } else if (Files.exists(png)) {
                        // Die selbst gezeichnete Karte schreibt PNG. Siehe docs/selbst.md, „Kacheln“.
                        argb = png(Files.readAllBytes(png), seite).argb();
                    }
                } catch (IOException e) {
                    argb = null;
                }
                if (argb != null) {
                    pixel = pixel(new Bild(seite, seite, argb));
                }
            } catch (RuntimeException e) {
                pixel = null;
            } finally {
                // Auch nach einem Error, sonst bliebe die Kachel für immer in laeuft.
                zurueck(pfad, gen, pixel);
            }
        });
        return id;
    }

    private void zurueck(String pfad, int gen, NativeImage pixel) {
        Minecraft.getInstance().execute(() -> uebernimm(pfad, gen, pixel));
    }

    /** Auf dem Render-Thread: nur noch hochladen, die Pixel sind schon im {@code NativeImage}. */
    private void uebernimm(String pfad, int gen, NativeImage pixel) {
        laeuft.remove(pfad);
        if (geschlossen) {
            if (pixel != null) {
                pixel.close();
            }
            return;
        }
        if (gen != generation.getOrDefault(pfad, 0)) {
            // Während des Dekodierens kam ein Download: Das Ergebnis ist trotzdem
            // neuer als das gezeigte, also hoch damit, und gleich noch einmal.
            veraltet.add(pfad);
        }
        Identifier alt;
        if (pixel == null) {
            leer.add(pfad);
            alt = texturen.remove(pfad);
        } else {
            Identifier id = Identifier.fromNamespaceAndPath(HeroicMap.ID, "kachel/" + naechste++);
            DynamicTexture textur = new DynamicTexture(() -> "heroicmap " + pfad, pixel);
            textur.upload();
            Minecraft.getInstance().getTextureManager().register(id, textur);
            // Die alte Textur geht erst, wenn die neue da ist: Die Karte blinkt nicht.
            alt = texturen.put(pfad, id);
        }
        if (alt != null) {
            Minecraft.getInstance().getTextureManager().release(alt);
        }
    }

    /** Füllt ein {@code NativeImage} mit der Kachel, im Faden des Dekoders. */
    static NativeImage pixel(Bild bild) {
        NativeImage pixel = new NativeImage(bild.breite(), bild.hoehe(), false);
        try {
            for (int y = 0; y < bild.hoehe(); y++) {
                for (int x = 0; x < bild.breite(); x++) {
                    pixel.setPixel(x, y, bild.argb()[y * bild.breite() + x]);
                }
            }
            return pixel;
        } catch (Throwable fehler) {
            pixel.close();
            throw fehler;
        }
    }

    /**
     * Dekodiert eine WebP-Kachel; das Spiel selbst liest nur PNG. Einfache verlustfreie WebP liest
     * die Kopie des Dekoders in {@code webp/}, alle anderen TwelveMonkeys. Die Grösse aus dem Kopf
     * muss {@code seite} × {@code seite} sein, sonst wirft es, bevor es Speicher dafür anlegt.
     * Siehe docs/vollbildkarte.md, „Farbindex hinter der Palette“.
     */
    static Bild dekodiere(byte[] webp, int seite) throws IOException {
        BufferedImage bild = verlustfrei(webp, seite);
        if (bild == null) {
            ImageReader leser = new WebPImageReaderSpi().createReaderInstance();
            try (MemoryCacheImageInputStream rein = new MemoryCacheImageInputStream(new ByteArrayInputStream(webp))) {
                leser.setInput(rein);
                pruefe(leser.getWidth(0), leser.getHeight(0), seite, seite, false);
                bild = leser.read(0);
            } finally {
                leser.dispose();
            }
        }
        return bild(bild);
    }

    /**
     * Nur eine einfache verlustfreie WebP, genau {@code seite} × {@code seite}, sonst null; ohne den Weg
     * über TwelveMonkeys. Für die Symbole der Ebenen, siehe docs/ebenen.md, „Symbole“.
     */
    static Bild vp8l(byte[] webp, int seite) throws IOException {
        return vp8l(webp, seite, seite, false);
    }

    /** Wie {@link #vp8l(byte[], int)}, genau {@code breite} × {@code hoehe} oder mit {@code hoechstens} bis dahin. */
    static Bild vp8l(byte[] webp, int breite, int hoehe, boolean hoechstens) throws IOException {
        BufferedImage bild = verlustfrei(webp, breite, hoehe, hoechstens);
        return bild == null ? null : bild(bild);
    }

    private static Bild bild(BufferedImage bild) {
        int b = bild.getWidth(), h = bild.getHeight();
        return new Bild(b, h, bild.getRGB(0, 0, b, h, null, 0, b));
    }

    /**
     * Eine einfache verlustfreie WebP, {@code RIFF} mit nur dem Chunk {@code VP8L}, mit der Kopie
     * des Dekoders, ins selbe Bildformat wie TwelveMonkeys; jede andere WebP gibt null.
     */
    private static BufferedImage verlustfrei(byte[] webp, int seite) throws IOException {
        return verlustfrei(webp, seite, seite, false);
    }

    private static BufferedImage verlustfrei(byte[] webp, int maxBreite, int maxHoehe, boolean hoechstens) throws IOException {
        // RIFF, Länge, WEBPVP8L, Länge, Signatur 0x2F, dann LSB zuerst 14 + 14 Bit Grösse − 1,
        // 1 Bit Alpha, 3 Bit Version.
        if (webp.length < 25 || webp[20] != 0x2F) {
            return null;
        }
        String kennung = new String(webp, 0, 16, StandardCharsets.ISO_8859_1);
        int kopf = ByteBuffer.wrap(webp, 21, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (!kennung.startsWith("RIFF") || !kennung.startsWith("WEBPVP8L", 8) || kopf >>> 29 != 0) {
            return null;
        }
        int breite = (kopf & 0x3FFF) + 1, hoehe = (kopf >>> 14 & 0x3FFF) + 1;
        pruefe(breite, hoehe, maxBreite, maxHoehe, hoechstens);
        BufferedImage bild = new BufferedImage(breite, hoehe,
                (kopf >>> 28 & 1) == 1 ? BufferedImage.TYPE_4BYTE_ABGR : BufferedImage.TYPE_3BYTE_BGR);
        try (MemoryCacheImageInputStream rein = new MemoryCacheImageInputStream(
                new ByteArrayInputStream(webp, 20, webp.length - 20))) {
            // Wie WebPImageReader: Der Bitleser liest ganze long aus dem Strom.
            rein.setByteOrder(ByteOrder.LITTLE_ENDIAN);
            new VP8LDecoder(rein, false).readVP8Lossless(bild.getRaster(), true, null, breite, hoehe);
        }
        return bild;
    }

    /** Dekodiert eine PNG-Kachel; die Grösse aus dem Kopf muss {@code seite} × {@code seite} sein, wie bei WebP. */
    static Bild png(byte[] png, int seite) throws IOException {
        return png(png, seite, seite, false);
    }

    /** Wie {@link #png(byte[], int)}, genau {@code breite} × {@code hoehe} oder mit {@code hoechstens} bis dahin; geprüft vor dem Dekodieren. */
    static Bild png(byte[] png, int breite, int hoehe, boolean hoechstens) throws IOException {
        ImageReader leser = ImageIO.getImageReadersByFormatName("png").next();
        try (MemoryCacheImageInputStream rein = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            leser.setInput(rein);
            pruefe(leser.getWidth(0), leser.getHeight(0), breite, hoehe, hoechstens);
            return bild(leser.read(0));
        } finally {
            leser.dispose();
        }
    }

    private static void pruefe(int breite, int hoehe, int maxBreite, int maxHoehe, boolean hoechstens) throws IOException {
        boolean gut = hoechstens ? breite >= 1 && hoehe >= 1 && breite <= maxBreite && hoehe <= maxHoehe
                : breite == maxBreite && hoehe == maxHoehe;
        if (!gut) {
            throw new IOException("Grösse " + breite + " × " + hoehe + (hoechstens ? " über " : " statt ") + maxBreite + " × " + maxHoehe);
        }
    }

    @Override
    public void close() {
        geschlossen = true;
        if (offen == this) {
            offen = null;
        }
        dekoder.shutdownNow();
        texturen.values().forEach(Minecraft.getInstance().getTextureManager()::release);
        texturen.clear();
    }
}
