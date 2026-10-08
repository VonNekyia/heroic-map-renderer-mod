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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    /** So viele Bilder der Ebene hält die Karte höchstens. */
    private static final int BILDER_MAX = 4096;

    /** Ein dekodiertes Bild, ARGB Zeile für Zeile. */
    record Bild(int breite, int hoehe, int[] argb) {
    }

    /** Die offene Karte, der die Live-Ebene geänderte Chunks meldet; nur auf dem Render-Thread. */
    private static Kacheln offen;

    private final Path ordner;
    private final int seite;
    private final int minZoom, stufe, chunk;
    private final Path ebene;
    /** Die Chunks der Live-Ebene; der Dekoder liest, der Render-Thread ergänzt. */
    private final Set<Long> chunks;
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
    /** Kacheln, die es nicht gibt oder die sich nicht lesen lassen; der Mod fragt erst nach einer Änderung der Ebene wieder. */
    private final Set<String> leer = new HashSet<>();
    /** Je Kachel zählt jede Änderung der Ebene; ein Ergebnis aus einer älteren Zählung ist veraltet. */
    private final Map<String, Integer> generation = new HashMap<>();
    /** Kacheln mit einer älteren Textur: Sie bleibt sichtbar, bis die neue da ist. */
    private final Set<String> veraltet = new HashSet<>();
    /** Die Bilder der Ebene je Chunk, einmal je offener Karte gelesen; der Render-Thread verwirft geänderte. */
    private final Map<Long, int[]> bilder = new ConcurrentHashMap<>();
    private boolean geschlossen;
    private int naechste;

    /** Die Kacheln des Satzes; jede muss genau {@code tileSize} gross sein. Auf dem Render-Thread. */
    Kacheln(Satz satz) {
        this.ordner = satz.ordner();
        this.seite = satz.kachel();
        this.minZoom = satz.minZoom();
        this.stufe = satz.stufe();
        this.chunk = satz.chunk();
        this.ebene = Ebene.ordner(satz.ordner().getParent());
        this.chunks = Ebene.liste(ebene);
        offen = this;
    }

    /** Nach einem Abgleich: Die Ebene ist geräumt, alles über ihr lädt neu. Auf dem Render-Thread. */
    static void ebeneGeraeumt() {
        if (offen != null) {
            offen.allesNeu();
        }
    }

    private void allesNeu() {
        bilder.clear();
        chunks.clear();
        chunks.addAll(Ebene.liste(ebene));
        leer.clear();
        for (String pfad : texturen.keySet()) {
            generation.merge(pfad, 1, Integer::sum);
            veraltet.add(pfad);
        }
        for (String pfad : laeuft) {
            generation.merge(pfad, 1, Integer::sum);
        }
    }

    /** Die Live-Ebene hat einen Chunk neu abgelegt: Die Kacheln über ihm laden neu. Auf dem Render-Thread. */
    static void geaendert(int cx, int cz) {
        if (offen != null) {
            offen.vergiss(cx, cz);
        }
    }

    private void vergiss(int cx, int cz) {
        long k = Ebene.schluessel(cx, cz);
        chunks.add(k);
        bilder.remove(k);
        for (int z = stufe; z >= minZoom && chunk >> (stufe - z) >= 1; z--) {
            int breite = chunk >> (stufe - z);
            String pfad = z + "/" + Math.floorDiv(cx * breite, seite) + "/" + Math.floorDiv(cz * breite, seite);
            generation.merge(pfad, 1, Integer::sum);
            if (texturen.containsKey(pfad) || laeuft.contains(pfad)) {
                veraltet.add(pfad);
            }
            leer.remove(pfad);
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
        Path datei = ordner.resolve(String.valueOf(z)).resolve(String.valueOf(x)).resolve(y + ".webp");
        dekoder.execute(() -> {
            NativeImage pixel = null;
            try {
                int[] argb = null;
                try {
                    if (Files.exists(datei)) {
                        argb = dekodiere(Files.readAllBytes(datei), seite).argb();
                    }
                } catch (IOException e) {
                    argb = null;
                }
                // Darüber die Live-Ebene, auch wo der Server noch keine Kachel hat.
                argb = Ebene.lege(argb, seite, z, x, y, stufe, chunk, chunks, this::bild);
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

    /** Das Bild eines Chunks der Ebene, im Dekoder; je offener Karte einmal gelesen. */
    private int[] bild(long k) {
        // ponytail: leert ganz statt der ältesten; bei 64 × 64 Pixeln sind 4096 Bilder rund 64 MiB.
        if (bilder.size() > BILDER_MAX) {
            bilder.clear();
        }
        return bilder.computeIfAbsent(k, c -> Ebene.lies(ebene, Ebene.cx(c), Ebene.cz(c), chunk));
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
            // Während des Dekodierens legte die Ebene ein neueres Bild ab: Das Ergebnis ist trotzdem
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
                pruefe(leser.getWidth(0), leser.getHeight(0), seite);
                bild = leser.read(0);
            } finally {
                leser.dispose();
            }
        }
        int w = bild.getWidth(), h = bild.getHeight();
        return new Bild(w, h, bild.getRGB(0, 0, w, h, null, 0, w));
    }

    /**
     * Eine einfache verlustfreie WebP, {@code RIFF} mit nur dem Chunk {@code VP8L}, mit der Kopie
     * des Dekoders, ins selbe Bildformat wie TwelveMonkeys; jede andere WebP gibt null.
     */
    private static BufferedImage verlustfrei(byte[] webp, int seite) throws IOException {
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
        pruefe(breite, hoehe, seite);
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

    private static void pruefe(int breite, int hoehe, int seite) throws IOException {
        if (breite != seite || hoehe != seite) {
            throw new IOException("Grösse " + breite + " × " + hoehe + " statt " + seite + " × " + seite);
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
