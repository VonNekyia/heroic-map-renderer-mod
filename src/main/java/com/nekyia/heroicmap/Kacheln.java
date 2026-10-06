package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.NativeImage;
import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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

    /** Ein dekodiertes Bild, ARGB Zeile für Zeile. */
    record Bild(int breite, int hoehe, int[] argb) {
    }

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
    /** Kacheln, die es nicht gibt oder die sich nicht lesen lassen; der Mod fragt nicht wieder. */
    private final Set<String> leer = new HashSet<>();
    private boolean geschlossen;
    private int naechste;

    /** {@code seite} ist die Kachelgrösse aus {@code map.json}; jede Kachel muss genau so gross sein. */
    Kacheln(Path ordner, int seite) {
        this.ordner = ordner;
        this.seite = seite;
    }

    /** Die Textur der Kachel, oder null, solange sie lädt oder es sie nicht gibt. */
    Identifier textur(int z, int x, int y) {
        String pfad = z + "/" + x + "/" + y;
        Identifier id = texturen.get(pfad);
        if (id != null || leer.contains(pfad) || !laeuft.add(pfad)) {
            return id;
        }
        Path datei = ordner.resolve(String.valueOf(z)).resolve(String.valueOf(x)).resolve(y + ".webp");
        dekoder.execute(() -> {
            NativeImage pixel = null;
            try {
                if (Files.exists(datei)) {
                    pixel = pixel(dekodiere(Files.readAllBytes(datei), seite));
                }
            } catch (IOException | RuntimeException e) {
                pixel = null;
            } finally {
                // Auch nach einem Error, sonst bliebe die Kachel für immer in laeuft.
                zurueck(pfad, pixel);
            }
        });
        return null;
    }

    private void zurueck(String pfad, NativeImage pixel) {
        Minecraft.getInstance().execute(() -> uebernimm(pfad, pixel));
    }

    /** Auf dem Render-Thread: nur noch hochladen, die Pixel sind schon im {@code NativeImage}. */
    private void uebernimm(String pfad, NativeImage pixel) {
        laeuft.remove(pfad);
        if (geschlossen) {
            if (pixel != null) {
                pixel.close();
            }
            return;
        }
        if (pixel == null) {
            leer.add(pfad);
            return;
        }
        Identifier id = Identifier.fromNamespaceAndPath(HeroicMap.ID, "kachel/" + naechste++);
        DynamicTexture textur = new DynamicTexture(() -> "heroicmap " + pfad, pixel);
        textur.upload();
        Minecraft.getInstance().getTextureManager().register(id, textur);
        texturen.put(pfad, id);
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
     * Dekodiert eine WebP-Kachel mit TwelveMonkeys; das Spiel selbst liest nur PNG. Die Grösse
     * aus dem Kopf muss {@code seite} × {@code seite} sein, sonst wirft es, bevor es Speicher
     * dafür anlegt.
     */
    static Bild dekodiere(byte[] webp, int seite) throws IOException {
        ImageReader leser = new WebPImageReaderSpi().createReaderInstance();
        try (MemoryCacheImageInputStream rein = new MemoryCacheImageInputStream(new ByteArrayInputStream(webp))) {
            leser.setInput(rein);
            int breite = leser.getWidth(0), hoehe = leser.getHeight(0);
            if (breite != seite || hoehe != seite) {
                throw new IOException("Grösse " + breite + " × " + hoehe + " statt " + seite + " × " + seite);
            }
            BufferedImage bild = leser.read(0);
            int w = bild.getWidth(), h = bild.getHeight();
            return new Bild(w, h, bild.getRGB(0, 0, w, h, null, 0, w));
        } finally {
            leser.dispose();
        }
    }

    @Override
    public void close() {
        geschlossen = true;
        dekoder.shutdownNow();
        texturen.values().forEach(Minecraft.getInstance().getTextureManager()::release);
        texturen.clear();
    }
}
