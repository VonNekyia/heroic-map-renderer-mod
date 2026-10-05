package com.nekyia.heroicmap;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Was die Minimap kostet: die Zeit je Chunk und die Frametime mit und ohne Minimap, bei
 * Sichtweite 12 in einer erzeugten Welt. Läuft nur mit -Pmessung=&lt;datei&gt; und schreibt
 * das Ergebnis dorthin. Siehe docs/minimap.md, „Kosten“.
 */
public final class Messung implements FabricClientGameTest {

    private static final String AUSGABE = System.getProperty("heroicmap.messung", "");
    private static final int SICHTWEITE = 12;
    private static final int RUNDEN = 3;
    private static final int STAND_TICKS = 100;
    private static final int FLUG_TICKS = 200;
    private static final int HOEHE = 160;
    private static final int RUHE_TICKS = 1200;

    private final LongArrayList frames = new LongArrayList();
    private boolean aufnehmen;
    private long letzter;
    private final StringBuilder bericht = new StringBuilder();

    @Override
    public void runTest(ClientGameTestContext context) {
        if (AUSGABE.isEmpty()) {
            return;
        }
        LevelRenderEvents.START_MAIN.register(c -> {
            long jetzt = System.nanoTime();
            if (aufnehmen && letzter != 0) {
                frames.add(jetzt - letzter);
            }
            letzter = jetzt;
        });
        context.runOnClient(mc -> {
            mc.options.renderDistance().set(SICHTWEITE);
            mc.options.framerateLimit().set(260);
            mc.options.enableVsync().set(false);
            // Sonst drosselt das Spiel nach 60 s ohne Eingabe auf 30 fps.
            mc.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
        });
        try (TestSingleplayerContext spiel = context.worldBuilder()
                .adjustSettings(s -> s.setWorldType(new WorldCreationUiState.WorldTypeEntry(
                        s.getSettings().worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET)
                                .getOrThrow(WorldPresets.NORMAL))))
                .create()) {
            TestServerContext server = spiel.getServer();
            server.runCommand("time set noon");
            server.runCommand("weather clear");
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a 0 " + HOEHE + " 0 -90 20");
            warteAufChunks(context);

            atlas(context);
            for (int scale : new int[] {1, 2, 4}) {
                for (int runde = 1; runde <= RUNDEN; runde++) {
                    chunks(context, scale, runde);
                }
            }

            region(context);
            // Gleich nach dem Laden lief ein Lauf im Takt der Ticks, ein Frame je Tick.
            context.waitTicks(RUHE_TICKS);

            // Bildrate ohne Grenze, 144 und 60, dazu die Massstäbe; 260 heisst ohne Grenze.
            int x = 0;
            for (int[] lauf : new int[][] {{260, 2}, {260, 4}, {144, 4}, {60, 4}}) {
                int fps = lauf[0], scale = lauf[1];
                context.runOnClient(mc -> {
                    mc.options.framerateLimit().set(fps);
                    while (Minimap.INSTANZ.scale() != scale) {
                        Minimap.INSTANZ.naechsterMassstab();
                    }
                });
                zeige(context, true);
                String art = "fps=" + (fps == 260 ? "frei" : fps) + " scale=" + scale;
                for (int runde = 1; runde <= RUNDEN; runde++) {
                    for (boolean an : new boolean[] {false, true}) {
                        zeige(context, an);
                        frames(context, art + " stand", an, runde, () -> context.waitTicks(STAND_TICKS));
                    }
                }
                for (int runde = 1; runde <= RUNDEN; runde++) {
                    for (boolean an : new boolean[] {false, true}) {
                        zeige(context, an);
                        int start = x;
                        frames(context, art + " flug", an, runde, () -> {
                            for (int t = 1; t <= FLUG_TICKS; t++) {
                                server.runCommand("tp @a " + (start + t) + " " + HOEHE + " 0 -90 20");
                                context.waitTick();
                            }
                        });
                        x += FLUG_TICKS;
                    }
                }
            }
        }
        try {
            Files.writeString(Path.of(AUSGABE), bericht);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Wartet, bis 5 s lang kein Chunk mehr dazukommt und die Minimap fertig ist.
     * waitForChunksRender wurde bei Sichtweite 12 auch nach 5 min nicht fertig.
     */
    private void warteAufChunks(ClientGameTestContext context) {
        int vorher = -1, gleich = 0;
        for (int t = 0; t < 6000 && gleich < 5; t += 20) {
            context.waitTicks(20);
            int jetzt = context.computeOnClient(Messung::geladen);
            boolean fertig = context.computeOnClient(mc -> Minimap.INSTANZ.fertig());
            gleich = jetzt == vorher && fertig ? gleich + 1 : 0;
            vorher = jetzt;
        }
        zeile("geladen n=%d stabil=%s", vorher, gleich >= 5);
    }

    private static int geladen(net.minecraft.client.Minecraft mc) {
        ChunkPos mitte = mc.player.chunkPosition();
        int n = 0;
        for (int dz = -SICHTWEITE; dz <= SICHTWEITE; dz++) {
            for (int dx = -SICHTWEITE; dx <= SICHTWEITE; dx++) {
                if (mc.level.getChunkSource().getChunk(mitte.x() + dx, mitte.z() + dz, ChunkStatus.FULL, false) != null) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Minimap an oder aus; an erst, wenn sie nachgezeichnet hat. */
    private static void zeige(ClientGameTestContext context, boolean an) {
        context.runOnClient(mc -> Minimap.INSTANZ.setzeSichtbar(an));
        if (an) {
            context.waitFor(mc -> Minimap.INSTANZ.fertig(), 6000);
        }
    }

    /** Zahl und Dauer in ms aller Garbage Collections bisher. */
    private static long[] gc() {
        long n = 0, ms = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            n += Math.max(0, gc.getCollectionCount());
            ms += Math.max(0, gc.getCollectionTime());
        }
        return new long[] {n, ms};
    }

    /** Was eine neue Region bei 4 px auf dem Render-Thread kostet: anlegen, leer hochladen, anmelden, freigeben. */
    private void region(ClientGameTestContext context) {
        long[] zeiten = context.computeOnClient(mc -> {
            long[] z = new long[5];
            for (int i = 0; i < z.length; i++) {
                Identifier id = Identifier.fromNamespaceAndPath(HeroicMap.ID, "messung/" + i);
                long t0 = System.nanoTime();
                DynamicTexture textur = new DynamicTexture(() -> "messung", 512, 512, true);
                textur.upload();
                mc.getTextureManager().register(id, textur);
                z[i] = System.nanoTime() - t0;
                mc.getTextureManager().release(id);
            }
            return z;
        });
        Arrays.sort(zeiten);
        zeile("region scale=4 n=%d median=%.3f ms max=%.3f ms", zeiten.length, ms(quantil(zeiten, 0.5)), ms(zeiten[zeiten.length - 1]));
    }

    /** Wie lange die Kopie der Texel des Block-Atlas auf dem Render-Thread dauert, einmal je Neuladen. */
    private void atlas(ClientGameTestContext context) {
        long[] werte = context.computeOnClient(mc -> {
            TextureAtlas atlas = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
            long t0 = System.nanoTime();
            int n = ChunkMaler.Texel.vomAtlas(atlas).size();
            return new long[] {System.nanoTime() - t0, n};
        });
        zeile("atlas sprites=%d kopie=%.3f ms", werte[1], ms(werte[0]));
    }

    /**
     * Zieht jeden geladenen Chunk in Sichtweite einmal ab und zeichnet ihn; misst beides
     * getrennt, den Abzug für den Render-Thread, das Zeichnen für den Worker.
     */
    private void chunks(ClientGameTestContext context, int scale, int runde) {
        long[][] zeiten = context.computeOnClient(mc -> {
            ClientLevel level = mc.level;
            ChunkPos mitte = mc.player.chunkPosition();
            ChunkMaler maler = new ChunkMaler();
            Licht licht = Licht.von(level.dimensionType());
            Map<TextureAtlasSprite, ChunkMaler.Texel> texel = ChunkMaler.Texel.vomAtlas(
                    mc.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS));
            LongArrayList abzug = new LongArrayList(), zeichnen = new LongArrayList();
            for (int dz = -SICHTWEITE; dz <= SICHTWEITE; dz++) {
                for (int dx = -SICHTWEITE; dx <= SICHTWEITE; dx++) {
                    LevelChunk chunk = level.getChunkSource()
                            .getChunk(mitte.x() + dx, mitte.z() + dz, ChunkStatus.FULL, false);
                    if (chunk == null) {
                        continue;
                    }
                    long t0 = System.nanoTime();
                    ChunkMaler.Auftrag auftrag = ChunkMaler.abziehen(level, chunk, scale, Integer.MAX_VALUE, licht, texel, 0);
                    long t1 = System.nanoTime();
                    maler.male(auftrag);
                    abzug.add(t1 - t0);
                    zeichnen.add(System.nanoTime() - t1);
                }
            }
            return new long[][] {abzug.toLongArray(), zeichnen.toLongArray()};
        });
        for (int art = 0; art < 2; art++) {
            long[] z = zeiten[art];
            Arrays.sort(z);
            zeile("chunk %s scale=%d runde=%d n=%d median=%.3f ms p90=%.3f ms max=%.3f ms",
                    art == 0 ? "abzug" : "zeichnen", scale, runde, z.length,
                    ms(quantil(z, 0.5)), ms(quantil(z, 0.9)), ms(z[z.length - 1]));
        }
    }

    private void frames(ClientGameTestContext context, String art, boolean an, int runde, Runnable lauf) {
        context.runOnClient(mc -> {
            frames.clear();
            aufnehmen = true;
        });
        long[] gcVorher = gc();
        lauf.run();
        long[] gcNachher = gc();
        long[] zeiten = context.computeOnClient(mc -> {
            aufnehmen = false;
            return frames.toLongArray();
        });
        Arrays.sort(zeiten);
        long summe = 0;
        for (long z : zeiten) {
            summe += z;
        }
        zeile("frames %s minimap=%s runde=%d n=%d mittel=%.3f ms p50=%.3f ms p95=%.3f ms p99=%.3f ms max=%.3f ms gc=%d gc_ms=%d",
                art, an ? "an" : "aus", runde, zeiten.length, ms(summe / zeiten.length),
                ms(quantil(zeiten, 0.5)), ms(quantil(zeiten, 0.95)), ms(quantil(zeiten, 0.99)),
                ms(zeiten[zeiten.length - 1]), gcNachher[0] - gcVorher[0], gcNachher[1] - gcVorher[1]);
    }

    private static long quantil(long[] sortiert, double q) {
        return sortiert[Math.max(0, (int) Math.ceil(q * sortiert.length) - 1)];
    }

    private static double ms(long ns) {
        return ns / 1e6;
    }

    private void zeile(String format, Object... werte) {
        String text = String.format(java.util.Locale.ROOT, format, werte);
        System.out.println("[heroicmap-messung] " + text);
        bericht.append(text).append('\n');
    }
}
