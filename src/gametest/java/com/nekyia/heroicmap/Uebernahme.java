package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Was die Übernahme einer Kachel der Vollbildkarte den Render-Thread kostet: früher Pixel
 * kopieren und hochladen, jetzt nur hochladen. Dazu Dekodieren und Füllen im Faden des
 * Dekoders. Läuft nur mit -Puebernahme=&lt;datei&gt; und schreibt das Ergebnis dorthin.
 * Siehe docs/messungen/2026-10-06-vollbildkarte-uebernahme.md.
 */
public final class Uebernahme implements FabricClientGameTest {

    private static final String AUSGABE = System.getProperty("heroicmap.uebernahme", "");
    private static final int RUNDEN = 10;
    private static final int SEITE = 256;

    private int zaehler;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (AUSGABE.isEmpty()) {
            return;
        }
        List<byte[]> kacheln = kacheln();
        LongArrayList dekodieren = new LongArrayList(), fuellen = new LongArrayList();
        LongArrayList alt = new LongArrayList(), neu = new LongArrayList();
        // Runde 0 wärmt auf und zählt nicht; danach wechselt die Reihenfolge von alt und neu je Runde.
        for (int runde = 0; runde <= RUNDEN; runde++) {
            for (byte[] webp : kacheln) {
                long t0 = System.nanoTime();
                Kacheln.Bild bild;
                try {
                    bild = Kacheln.dekodiere(webp, SEITE);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                long t1 = System.nanoTime();
                NativeImage gefuellt = Kacheln.pixel(bild);
                long t2 = System.nanoTime();
                long a, n;
                if (runde % 2 == 0) {
                    a = altesUebernehmen(context, bild);
                    n = neuesUebernehmen(context, gefuellt);
                } else {
                    n = neuesUebernehmen(context, gefuellt);
                    a = altesUebernehmen(context, bild);
                }
                if (runde > 0) {
                    dekodieren.add(t1 - t0);
                    fuellen.add(t2 - t1);
                    alt.add(a);
                    neu.add(n);
                }
            }
        }
        StringBuilder bericht = new StringBuilder();
        bericht.append(String.format(Locale.ROOT, "%d Kacheln %d x %d, %d Runden%n", kacheln.size(), SEITE, SEITE, RUNDEN));
        zeile(bericht, "Faden: dekodieren", dekodieren);
        zeile(bericht, "Faden: NativeImage füllen", fuellen);
        zeile(bericht, "Render-Thread alt: füllen, hochladen", alt);
        zeile(bericht, "Render-Thread neu: hochladen", neu);
        try {
            Files.writeString(Path.of(AUSGABE), bericht);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Wie früher in {@code Kacheln.uebernimm}: Pixel auf dem Render-Thread kopieren, dann hochladen. */
    private long altesUebernehmen(ClientGameTestContext context, Kacheln.Bild bild) {
        return context.computeOnClient(mc -> {
            long t = System.nanoTime();
            Identifier id = hochladen(mc, Kacheln.pixel(bild));
            t = System.nanoTime() - t;
            mc.getTextureManager().release(id);
            return t;
        });
    }

    /** Wie jetzt in {@code Kacheln.uebernimm}: nur hochladen. */
    private long neuesUebernehmen(ClientGameTestContext context, NativeImage gefuellt) {
        return context.computeOnClient(mc -> {
            long t = System.nanoTime();
            Identifier id = hochladen(mc, gefuellt);
            t = System.nanoTime() - t;
            mc.getTextureManager().release(id);
            return t;
        });
    }

    /** Dieselben Aufrufe wie {@code Kacheln.uebernimm}; die Textur gehört danach dem TextureManager. */
    private Identifier hochladen(Minecraft mc, NativeImage pixel) {
        Identifier id = Identifier.fromNamespaceAndPath(HeroicMap.ID, "messung/" + zaehler++);
        DynamicTexture textur = new DynamicTexture(() -> "heroicmap messung", pixel);
        textur.upload();
        mc.getTextureManager().register(id, textur);
        return id;
    }

    /** Die Kacheln der feinsten Stufe des Testsatzes. */
    private static List<byte[]> kacheln() {
        List<byte[]> kacheln = new ArrayList<>();
        try (InputStream liste = Uebernahme.class.getResourceAsStream("/satz/liste.txt")) {
            for (String datei : new String(liste.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                if (datei.startsWith("2/")) {
                    try (InputStream rein = Uebernahme.class.getResourceAsStream("/satz/4/" + datei)) {
                        kacheln.add(rein.readAllBytes());
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return kacheln;
    }

    private static void zeile(StringBuilder bericht, String was, LongArrayList werte) {
        long[] w = werte.toLongArray();
        Arrays.sort(w);
        long summe = Arrays.stream(w).sum();
        bericht.append(String.format(Locale.ROOT, "%-40s n=%d  p50=%.3f ms  p95=%.3f ms  max=%.3f ms  Summe=%.1f ms%n", was,
                w.length, w[w.length / 2] / 1e6, w[(int) (w.length * 0.95)] / 1e6, w[w.length - 1] / 1e6, summe / 1e6));
    }
}
