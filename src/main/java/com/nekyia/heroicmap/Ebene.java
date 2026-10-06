package com.nekyia.heroicmap;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Die Live-Ebene auf der Platte: je Chunk ein PNG in der Auflösung der feinsten Stufe des
 * Satzes, {@code <baum>/overlay/<cx>.<cz>.png}, die Zeit der Änderung in Serverzeit als mtime.
 * Ohne Minecraft, damit es sich testen lässt. Siehe docs/live.md.
 */
final class Ebene {

    private static final Pattern NAME = Pattern.compile("(-?\\d{1,9})\\.(-?\\d{1,9})\\.png");

    private Ebene() {
    }

    /** Der Ordner der Ebene eines Baums. */
    static Path ordner(Path baum) {
        return baum.resolve("overlay");
    }

    static long schluessel(int cx, int cz) {
        return (long) cx << 32 | (cz & 0xFFFFFFFFL);
    }

    static int cx(long schluessel) {
        return (int) (schluessel >> 32);
    }

    static int cz(long schluessel) {
        return (int) schluessel;
    }

    private static Path datei(Path ordner, int cx, int cz) {
        return ordner.resolve(cx + "." + cz + ".png");
    }

    /** Die Chunks, für die ein Bild daliegt. */
    static Set<Long> liste(Path ordner) {
        Set<Long> chunks = ConcurrentHashMap.newKeySet();
        if (!Files.isDirectory(ordner)) {
            return chunks;
        }
        try (Stream<Path> dateien = Files.list(ordner)) {
            dateien.forEach(d -> {
                Matcher m = NAME.matcher(d.getFileName().toString());
                if (m.matches()) {
                    chunks.add(schluessel(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
                }
            });
        } catch (IOException e) {
            // Ohne lesbaren Ordner keine Ebene.
        }
        return chunks;
    }

    /** Schreibt das Bild eines Chunks über eine Zwischendatei; {@code zeit} in ms Serverzeit. */
    static void schreibe(Path ordner, int cx, int cz, int[] argb, int seite, long zeit) throws IOException {
        Files.createDirectories(ordner);
        BufferedImage bild = new BufferedImage(seite, seite, BufferedImage.TYPE_INT_ARGB);
        bild.setRGB(0, 0, seite, seite, argb, 0, seite);
        Path tmp = Files.createTempFile(ordner, "chunk", ".tmp");
        try {
            ImageIO.write(bild, "png", tmp.toFile());
            Files.setLastModifiedTime(tmp, FileTime.fromMillis(zeit));
            Files.move(tmp, datei(ordner, cx, cz), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Das Bild eines Chunks, oder null, wenn keins lesbar daliegt oder es nicht {@code seite} breit
     * ist. Liest die Datei auf einmal: Unter Windows scheitert das Ersetzen einer Datei, die
     * jemand offen hält, also so kurz wie möglich; ohne Zwischendatei im Temp.
     */
    static int[] lies(Path ordner, int cx, int cz, int seite) {
        try {
            // ImageIO schliesst den Stream selbst; ein zweites close würfe.
            BufferedImage bild = ImageIO.read(new MemoryCacheImageInputStream(
                    new ByteArrayInputStream(Files.readAllBytes(datei(ordner, cx, cz)))));
            if (bild == null || bild.getWidth() != seite || bild.getHeight() != seite) {
                return null;
            }
            return bild.getRGB(0, 0, seite, seite, null, 0, seite);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Löscht jedes Bild, das älter ist als {@code bis} (ms Serverzeit): Die Kacheln des Servers
     * enthalten diese Änderungen schon. Gibt die Zahl der gelöschten zurück.
     */
    static int raeume(Path ordner, long bis) throws IOException {
        int weg = 0;
        IOException fehler = null;
        for (long k : liste(ordner)) {
            Path d = datei(ordner, cx(k), cz(k));
            try {
                if (Files.getLastModifiedTime(d).toMillis() < bis) {
                    Files.delete(d);
                    weg++;
                }
            } catch (IOException e) {
                // Weiter mit den anderen; am Ende zählt der erste Fehler.
                fehler = mit(fehler, e);
            }
        }
        if (fehler != null) {
            throw fehler;
        }
        return weg;
    }

    private static IOException mit(IOException erster, IOException neu) {
        if (erster == null) {
            return neu;
        }
        erster.addSuppressed(neu);
        return erster;
    }

    /** Löscht die Zwischendateien eines abgebrochenen Schreibens; was sich nicht löschen lässt, bleibt. */
    static void raeumeZwischen(Path ordner) {
        if (!Files.isDirectory(ordner)) {
            return;
        }
        try (Stream<Path> dateien = Files.list(ordner)) {
            for (Path d : dateien.toList()) {
                String name = d.getFileName().toString();
                if (name.startsWith("chunk") && name.endsWith(".tmp")) {
                    try {
                        Files.deleteIfExists(d);
                    } catch (IOException e) {
                        // Beim nächsten Mal.
                    }
                }
            }
        } catch (IOException e) {
            // Ohne lesbaren Ordner nichts zu räumen.
        }
    }

    /**
     * Nach dem Wechsel des Massstabs, {@code alt} und {@code neu} die Breite eines Chunks: gröber
     * verkleinert der Mod die Bilder, feiner fallen sie weg, denn feinere Pixel hat er nicht. Die
     * mtime bleibt.
     */
    static void wechsle(Path ordner, int alt, int neu) throws IOException {
        IOException fehler = null;
        for (long k : liste(ordner)) {
            int cx = cx(k), cz = cz(k);
            Path d = datei(ordner, cx, cz);
            try {
                int[] bild = neu < alt ? lies(ordner, cx, cz, alt) : null;
                if (bild == null) {
                    Files.delete(d);
                    continue;
                }
                long zeit = Files.getLastModifiedTime(d).toMillis();
                schreibe(ordner, cx, cz, Pyramide.verkleinere(bild, alt, Integer.numberOfTrailingZeros(alt / neu)), neu, zeit);
            } catch (IOException e) {
                fehler = mit(fehler, e);
            }
        }
        if (fehler != null) {
            throw fehler;
        }
    }

    /**
     * Legt die Bilder der Ebene in eine Kachel der Stufe {@code z}, Ausschnitt ({@code x}, {@code y}).
     * Ein Chunk ist auf der feinsten Stufe {@code stufe} {@code chunk} Pixel breit und auf jeder
     * gröberen halb so breit; unter 1 Pixel mischte ein Pixel fremde Chunks, dort bleibt die
     * Kachel des Servers. {@code kachel} ist null, wenn der Server keine hat; dann entsteht eine
     * durchsichtige, falls ein Bild hineinfällt. {@code bilder} gibt das Bild eines Chunks in der
     * feinsten Stufe, oder null. Gibt die Kachel zurück, oder null.
     */
    static int[] lege(int[] kachel, int seite, int z, int x, int y, int stufe, int chunk, Set<Long> chunks,
            LongFunction<int[]> bilder) {
        int mal = stufe - z;
        if (mal < 0 || mal >= 31 || chunk >> mal < 1) {
            return kachel;
        }
        int breite = chunk >> mal;
        for (long k : chunks) {
            int px = cx(k) * breite - x * seite, py = cz(k) * breite - y * seite;
            if (px < 0 || py < 0 || px >= seite || py >= seite) {
                continue;
            }
            int[] bild = bilder.apply(k);
            if (bild == null) {
                continue;
            }
            bild = Pyramide.verkleinere(bild, chunk, mal);
            if (kachel == null) {
                kachel = new int[seite * seite];
            }
            for (int zeile = 0; zeile < breite; zeile++) {
                System.arraycopy(bild, zeile * breite, kachel, (py + zeile) * seite + px, breite);
            }
        }
        return kachel;
    }
}
