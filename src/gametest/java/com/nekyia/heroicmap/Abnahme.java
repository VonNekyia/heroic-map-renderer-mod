package com.nekyia.heroicmap;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Abnahme der Live-Ebene gegen {@code top-north} scale 4 des Renderers: legt Regionen der
 * Testwelt in eine neue Welt, öffnet sie wieder, lässt die Live-Ebene jeden Chunk eines
 * Ausschnitts zeichnen, ohne Änderung, und vergleicht Pixel für Pixel mit dem Bild des Renderers. Läuft nur mit
 * -Pabnahme=&lt;ordner&gt;; dort liegen {@code regionen/} mit Regionsdateien der Testwelt und
 * {@code top-north.png}, dorthin schreibt sie {@code bericht.txt} und {@code mod.png}.
 * Siehe docs/live.md, „Abnahme“.
 */
public final class Abnahme implements FabricClientGameTest {

    private static final String ORDNER = System.getProperty("heroicmap.abnahme", "");
    /** Der Ausschnitt in Chunks, wie {@code --center -96 352 --size 768}: Blöcke x −192 bis −1, z 256 bis 447. */
    static final int CX0 = -12, CZ0 = 16, CHUNKS = 12;
    private static final int SEITE = 64;
    /** Bis zu dieser Abweichung je Kanal gilt ein Pixel als nah. */
    static final int NAH = 8;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (ORDNER.isEmpty()) {
            return;
        }
        Path ordner = Path.of(ORDNER);
        TestWorldSave welt;
        try (TestSingleplayerContext spiel = context.worldBuilder().create()) {
            welt = spiel.getWorldSave();
        }
        kopiere(ordner.resolve("regionen"), welt.getSaveDirectory().resolve("dimensions/minecraft/overworld/region"));
        Path baum = FabricLoader.getInstance().getGameDir().resolve(HeroicMap.ID).resolve("test").resolve("abnahme");
        Path ebene = Ebene.ordner(baum);
        legeSatzAn(baum);
        context.runOnClient(mc -> {
            mc.options.renderDistance().set(10);
            Minimap.INSTANZ.setzeSichtbar(false);
        });
        try (TestSingleplayerContext spiel = welt.open()) {
            TestServerContext server = spiel.getServer();
            server.runCommand("time set noon");
            server.runCommand("weather clear");
            // Ohne Zufallsticks ändert sich vor dem Zeichnen nichts, im Ausschnitt nicht und daneben nicht.
            server.runCommand("gamerule random_tick_speed 0");
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a -96 200 352 0 90");
            // Die Chunks aus 26.2 zieht der Server beim Laden hoch; fertig gezeichnet sein muss nichts,
            // nur der Ausschnitt samt Rand geladen.
            context.waitFor(Abnahme::geladen, 6000);
            context.runOnClient(mc -> {
                Live.INSTANZ.satzFuerTest(Satz.lies(baum));
                for (int cz = CZ0; cz < CZ0 + CHUNKS; cz++) {
                    for (int cx = CX0; cx < CX0 + CHUNKS; cx++) {
                        Live.INSTANZ.markiereChunk(cx, cz);
                    }
                }
            });
            context.waitFor(mc -> alleDa(ebene), 6000);
        }
        vergleiche(ordner, ebene);
    }

    /** Liegt das Bild jedes Chunks im Ausschnitt da? Bilder ausserhalb zählen nicht. */
    private static boolean alleDa(Path ebene) {
        for (int cz = CZ0; cz < CZ0 + CHUNKS; cz++) {
            for (int cx = CX0; cx < CX0 + CHUNKS; cx++) {
                if (!Files.exists(Ebene.datei(ebene, cx, cz))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Sind der Ausschnitt und ein Chunk Rand ringsum geladen? */
    private static boolean geladen(Minecraft mc) {
        for (int cz = CZ0 - 1; cz <= CZ0 + CHUNKS; cz++) {
            for (int cx = CX0 - 1; cx <= CX0 + CHUNKS; cx++) {
                if (mc.level == null || mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void legeSatzAn(Path baum) {
        try {
            Laden.loesche(baum);
            Path satz = Files.createDirectories(baum.resolve("4"));
            Files.writeString(satz.resolve("map.json"),
                    "{\"tileSize\":256,\"scale\":4,\"minZoom\":0,\"maxZoom\":2,\"biomeBlend\":2,\"camera\":\"top-north\"}");
            Satz.schreibe(baum, "Testwelt", "minecraft:overworld", 4);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void kopiere(Path von, Path nach) {
        try (Stream<Path> dateien = Files.list(von)) {
            Files.createDirectories(nach);
            for (Path d : dateien.toList()) {
                Files.copy(d, nach.resolve(d.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Zählt je Chunk gleiche und nahe Pixel und schreibt Bericht und Bild des Mods. */
    private static void vergleiche(Path ordner, Path ebene) {
        try {
            BufferedImage renderer = ImageIO.read(ordner.resolve("top-north.png").toFile());
            BufferedImage mod = new BufferedImage(CHUNKS * SEITE, CHUNKS * SEITE, BufferedImage.TYPE_INT_ARGB);
            long gleich = 0, nah = 0, alle = 0;
            List<String> chunks = new ArrayList<>();
            int[] verteilung = new int[256];
            for (int cz = CZ0; cz < CZ0 + CHUNKS; cz++) {
                for (int cx = CX0; cx < CX0 + CHUNKS; cx++) {
                    int[] bild = Ebene.lies(ebene, cx, cz, SEITE);
                    if (bild == null) {
                        chunks.add(String.format(Locale.ROOT, "%d %d fehlt", cx, cz));
                        alle += SEITE * SEITE;
                        continue;
                    }
                    int x0 = (cx - CX0) * SEITE, y0 = (cz - CZ0) * SEITE;
                    mod.setRGB(x0, y0, SEITE, SEITE, bild, 0, SEITE);
                    int g = 0, n = 0;
                    for (int i = 0; i < SEITE * SEITE; i++) {
                        int a = bild[i], b = renderer.getRGB(x0 + i % SEITE, y0 + i / SEITE);
                        int d = abstand(a, b);
                        verteilung[Math.min(d, 255)]++;
                        g += d == 0 ? 1 : 0;
                        n += d <= NAH ? 1 : 0;
                    }
                    gleich += g;
                    nah += n;
                    alle += SEITE * SEITE;
                    chunks.add(String.format(Locale.ROOT, "%d %d gleich %.1f %% nah %.1f %%", cx, cz,
                            100.0 * g / (SEITE * SEITE), 100.0 * n / (SEITE * SEITE)));
                }
            }
            ImageIO.write(mod, "png", ordner.resolve("mod.png").toFile());
            StringBuilder bericht = new StringBuilder();
            bericht.append(String.format(Locale.ROOT, "%d Chunks, %d Pixel: gleich %.2f %%, nah (je Kanal <= %d) %.2f %%%n",
                    CHUNKS * CHUNKS, alle, 100.0 * gleich / alle, NAH, 100.0 * nah / alle));
            bericht.append("Abweichung je Kanal, höchste: Anteil bis\n");
            long summe = 0;
            for (int d : new int[] {0, 1, 2, 4, 8, 16, 32, 64, 128, 255}) {
                summe = 0;
                for (int k = 0; k <= d; k++) {
                    summe += verteilung[k];
                }
                bericht.append(String.format(Locale.ROOT, "  <= %3d: %.2f %%%n", d, 100.0 * summe / alle));
            }
            chunks.forEach(c -> bericht.append(c).append('\n'));
            Files.writeString(ordner.resolve("bericht.txt"), bericht);
            pruefe(verteilung, alle);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Die Schwellen, begründet in docs/live.md, „Abnahme“: bis 1 die Rundung, bis 16 das Laub,
     * das der Renderer mit der dunklen Farbe seiner Löcher mittelt, der Mod nicht.
     */
    private static void pruefe(int[] verteilung, long alle) {
        // Abweichung je Kanal höchstens, nötiger Anteil in Promille.
        int[][] schwellen = {{1, 800}, {16, 990}, {32, 998}};
        for (int[] s : schwellen) {
            long bis = 0;
            for (int k = 0; k <= s[0]; k++) {
                bis += verteilung[k];
            }
            long noetig = alle * s[1] / 1000;
            if (bis < noetig) {
                throw new AssertionError("Abnahme: nur " + bis + " von " + alle + " Pixeln weichen um höchstens " + s[0]
                        + " ab, nötig " + noetig);
            }
        }
    }

    /** Die grösste Abweichung über die Kanäle, Alpha eingeschlossen. */
    static int abstand(int a, int b) {
        int d = 0;
        for (int s = 0; s < 32; s += 8) {
            d = Math.max(d, Math.abs((a >>> s & 0xFF) - (b >>> s & 0xFF)));
        }
        return d;
    }
}
