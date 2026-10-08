package com.nekyia.heroicmap;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Die Kacheln der selbst gezeichneten Karte auf der Platte: nimmt die Bilder einzelner Chunks in
 * die Kacheln der feinsten Stufe, schreibt geänderte Kacheln als PNG und rechnet die gröberen
 * Stufen darüber neu, je 2 × 2 Kacheln wie die Pyramide des Renderers. Ohne Minecraft; gehört
 * einem Thread. Siehe docs/selbst.md, „Kacheln“.
 */
final class Kachelwerk {

    /** Eine Kachel: Stufe, x und y. */
    record Kachel(int z, int x, int y) {

        Kachel eltern() {
            return new Kachel(z - 1, x >> 1, y >> 1);
        }

        Path datei(Path ordner) {
            return ordner.resolve(String.valueOf(z)).resolve(String.valueOf(x)).resolve(y + ".png");
        }
    }

    /** So viele Kacheln bleiben im Speicher, geänderte immer; bei 256² Pixeln je 256 KiB. */
    private static final int BEHALTEN = 64;

    private final Path ordner;
    private final int seite, chunk, minZoom, maxZoom;
    /** Geänderte Kacheln der feinsten Stufe, noch nicht geschrieben. */
    private final Set<Kachel> geaendert = new HashSet<>();
    private final Map<Kachel, int[]> speicher = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Kachel, int[]> aelteste) {
            return size() > BEHALTEN && !geaendert.contains(aelteste.getKey());
        }
    };

    /**
     * {@code ordner} ist der Ordner des Massstabs, {@code seite} die Kachelgrösse, {@code chunk}
     * die Seite eines Chunks in Pixeln; die feinste Stufe ist {@code maxZoom}.
     */
    Kachelwerk(Path ordner, int seite, int chunk, int minZoom, int maxZoom) {
        this.ordner = ordner;
        this.seite = seite;
        this.chunk = chunk;
        this.minZoom = minZoom;
        this.maxZoom = maxZoom;
    }

    Path ordner() {
        return ordner;
    }

    /** Legt das Bild des Chunks (cx, cz), {@code chunk}² Pixel ARGB, in seine Kachel; es ersetzt, was dort war. */
    void lege(int cx, int cz, int[] pixel) throws IOException {
        int n = seite / chunk;
        Kachel k = new Kachel(maxZoom, Math.floorDiv(cx, n), Math.floorDiv(cz, n));
        int[] argb = lies(k);
        int ox = Math.floorMod(cx, n) * chunk, oy = Math.floorMod(cz, n) * chunk;
        for (int y = 0; y < chunk; y++) {
            System.arraycopy(pixel, y * chunk, argb, (oy + y) * seite + ox, chunk);
        }
        geaendert.add(k);
    }

    /**
     * Schreibt die geänderten Kacheln und rechnet ihre Vorfahren bis {@code minZoom} neu. Gibt
     * jede geschriebene Kachel zurück. Scheitert das Schreiben, bleibt, was nicht geschrieben ist,
     * geändert.
     */
    List<Kachel> schreibe() throws IOException {
        List<Kachel> fertig = new ArrayList<>();
        Set<Kachel> stufe = new HashSet<>();
        for (Kachel k : List.copyOf(geaendert)) {
            speichere(k, speicher.get(k));
            geaendert.remove(k);
            stufe.add(k);
            fertig.add(k);
        }
        for (int z = maxZoom - 1; z >= minZoom && !stufe.isEmpty(); z--) {
            Set<Kachel> eltern = new HashSet<>();
            for (Kachel k : stufe) {
                eltern.add(k.eltern());
            }
            for (Kachel e : eltern) {
                int[] argb = new int[seite * seite];
                int halb = seite / 2;
                for (int i = 0; i < 4; i++) {
                    int[] kind = lies(new Kachel(z + 1, 2 * e.x() + (i & 1), 2 * e.y() + (i >> 1)));
                    int[] klein = Pyramide.halbiere(kind, seite);
                    int ox = (i & 1) * halb, oy = (i >> 1) * halb;
                    for (int y = 0; y < halb; y++) {
                        System.arraycopy(klein, y * halb, argb, (oy + y) * seite + ox, halb);
                    }
                }
                speicher.put(e, argb);
                speichere(e, argb);
                fertig.add(e);
            }
            stufe = eltern;
        }
        return fertig;
    }

    /** Die Kachel aus dem Speicher, sonst von der Platte; fehlt sie oder ist sie unlesbar, durchsichtig. */
    private int[] lies(Kachel k) throws IOException {
        int[] argb = speicher.get(k);
        if (argb == null) {
            argb = new int[seite * seite];
            Path datei = k.datei(ordner);
            if (Files.exists(datei)) {
                BufferedImage bild = ImageIO.read(datei.toFile());
                if (bild != null && bild.getWidth() == seite && bild.getHeight() == seite) {
                    bild.getRGB(0, 0, seite, seite, argb, 0, seite);
                }
            }
            speicher.put(k, argb);
        }
        return argb;
    }

    /** Als PNG über eine Zwischendatei; nie liegt eine halbe Kachel da. */
    private void speichere(Kachel k, int[] argb) throws IOException {
        Path datei = k.datei(ordner), tmp = datei.resolveSibling(k.y() + ".png.tmp");
        Files.createDirectories(datei.getParent());
        BufferedImage bild = new BufferedImage(seite, seite, BufferedImage.TYPE_INT_ARGB);
        bild.setRGB(0, 0, seite, seite, argb, 0, seite);
        if (!ImageIO.write(bild, "png", tmp.toFile())) {
            throw new IOException("Kein Schreiber für PNG");
        }
        Files.move(tmp, datei, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
