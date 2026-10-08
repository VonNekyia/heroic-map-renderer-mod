package com.nekyia.heroicmap;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Die Kacheln der selbst gezeichneten Karte auf der Platte: nimmt die Bilder einzelner Chunks in
 * die Kacheln der feinsten Stufe und schreibt geänderte Kacheln als PNG. Jede geschriebene Kachel
 * verkleinert es in ihr Viertel des Vorfahren, wie die Pyramide des Renderers. Ohne Minecraft;
 * gehört einem Thread. Siehe docs/selbst.md, „Kacheln“.
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

    /** So viele ungeänderte Kacheln bleiben im Speicher; bei 256² Pixeln je 256 KiB, zusammen 16 MiB. */
    private static final int BEHALTEN = 64;
    /** Nach so vielen gescheiterten Durchläufen in Folge gibt das Werk auf, etwa bei voller Platte. */
    static final int VERSUCHE = 3;

    private final Path ordner;
    private final int seite, chunk, minZoom, maxZoom;
    /** Geänderte Kacheln aller Stufen mit ihren Pixeln, noch nicht geschrieben. */
    private final Map<Kachel, int[]> geaendert = new HashMap<>();
    /** Gescheiterte Durchläufe in Folge. */
    private int fehlschlaege;
    /** Ungeänderte Kacheln, zuletzt gelesen oder geschrieben; höchstens {@link #BEHALTEN}. */
    private final Map<Kachel, int[]> speicher = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Kachel, int[]> aelteste) {
            return size() > BEHALTEN;
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
    void lege(int cx, int cz, int[] pixel) {
        if (aufgegeben()) {
            return;
        }
        int n = seite / chunk;
        Kachel k = new Kachel(maxZoom, Math.floorDiv(cx, n), Math.floorDiv(cz, n));
        int[] argb = aendere(k);
        int ox = Math.floorMod(cx, n) * chunk, oy = Math.floorMod(cz, n) * chunk;
        for (int y = 0; y < chunk; y++) {
            System.arraycopy(pixel, y * chunk, argb, (oy + y) * seite + ox, chunk);
        }
    }

    /**
     * Schreibt geänderte Kacheln, die feinste Stufe zuerst; ohne {@code grob} nur die zwei
     * feinsten Stufen, die gröberen warten. Jede geschriebene Kachel kommt nach {@code fertig},
     * auch wenn danach eine scheitert; was nicht geschrieben ist, bleibt geändert.
     */
    void schreibe(boolean grob, List<Kachel> fertig) throws IOException {
        if (aufgegeben()) {
            return;
        }
        try {
            schreibeStufen(grob, fertig);
            fehlschlaege = 0;
        } catch (IOException e) {
            // Sonst wüchse der Speicher mit jeder neuen Kachel, bis zum OutOfMemoryError.
            if (++fehlschlaege >= VERSUCHE) {
                geaendert.clear();
            }
            throw e;
        }
    }

    /**
     * Hat das Werk nach {@link #VERSUCHE} gescheiterten Durchläufen in Folge aufgegeben? Dann nimmt
     * es keine Chunks mehr und hat vergessen, was ungeschrieben war.
     */
    boolean aufgegeben() {
        return fehlschlaege >= VERSUCHE;
    }

    /** Für den Test: wie viele Kacheln geändert und ungeschrieben sind. */
    int geaenderte() {
        return geaendert.size();
    }

    private void schreibeStufen(boolean grob, List<Kachel> fertig) throws IOException {
        for (int z = maxZoom; z >= minZoom && (grob || z >= maxZoom - 1); z--) {
            int stufe = z;
            for (Kachel k : geaendert.keySet().stream().filter(k -> k.z() == stufe).toList()) {
                int[] argb = geaendert.get(k);
                speichere(k, argb);
                geaendert.remove(k);
                speicher.put(k, argb);
                fertig.add(k);
                if (z > minZoom) {
                    viertel(k, argb);
                }
            }
        }
    }

    /** Verkleinert die Kachel in ihr Viertel des Vorfahren; der ist damit geändert. Die anderen drei Viertel bleiben. */
    private void viertel(Kachel k, int[] argb) {
        int[] ziel = aendere(k.eltern());
        int[] klein = Pyramide.halbiere(argb, seite);
        int halb = seite / 2, ox = (k.x() & 1) * halb, oy = (k.y() & 1) * halb;
        for (int y = 0; y < halb; y++) {
            System.arraycopy(klein, y * halb, ziel, (oy + y) * seite + ox, halb);
        }
    }

    /** Die Pixel der Kachel zum Ändern: geändert, aus dem Speicher oder von der Platte; danach gilt sie als geändert. */
    private int[] aendere(Kachel k) {
        int[] argb = geaendert.get(k);
        if (argb == null) {
            argb = speicher.remove(k);
            if (argb == null) {
                argb = vonPlatte(k);
            }
            geaendert.put(k, argb);
        }
        return argb;
    }

    /** Die Kachel von der Platte; fehlt sie oder ist sie unlesbar, auch eine kaputte PNG, durchsichtig. */
    private int[] vonPlatte(Kachel k) {
        int[] argb = new int[seite * seite];
        Path datei = k.datei(ordner);
        try {
            BufferedImage bild = Files.exists(datei) ? ImageIO.read(datei.toFile()) : null;
            if (bild != null && bild.getWidth() == seite && bild.getHeight() == seite) {
                bild.getRGB(0, 0, seite, seite, argb, 0, seite);
            }
        } catch (IOException | RuntimeException kaputt) {
            // Wie fehlend: Die Kachel entsteht neu aus dem, was jetzt kommt.
            return new int[seite * seite];
        }
        return argb;
    }

    /** Für den Test: wie viele ungeänderte Kacheln im Speicher liegen. */
    int imSpeicher() {
        return speicher.size();
    }

    /** Als PNG über eine Zwischendatei; nie liegt eine halbe Kachel da, und eine gescheiterte Zwischendatei geht wieder. */
    private void speichere(Kachel k, int[] argb) throws IOException {
        Path datei = k.datei(ordner), tmp = datei.resolveSibling(k.y() + ".png.tmp");
        Files.createDirectories(datei.getParent());
        BufferedImage bild = new BufferedImage(seite, seite, BufferedImage.TYPE_INT_ARGB);
        bild.setRGB(0, 0, seite, seite, argb, 0, seite);
        try {
            if (!ImageIO.write(bild, "png", tmp.toFile())) {
                throw new IOException("Kein Schreiber für PNG");
            }
            Files.move(tmp, datei, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
