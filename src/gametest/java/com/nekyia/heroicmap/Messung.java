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
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
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
 * Was die Minimap kostet: die Zeit je Chunk, die Frametime mit und ohne Minimap, eckig und rund,
 * und die Zeit im HUD-Element der Minimap, bei
 * Sichtweite 12 in einer erzeugten Welt. Läuft nur mit -Pmessung=&lt;datei&gt; und schreibt
 * das Ergebnis dorthin. Siehe docs/minimap.md, „Kosten“.
 */
public final class Messung implements FabricClientGameTest {

    private static final String AUSGABE = System.getProperty("heroicmap.messung", "");
    /** Mit {@code -PmessungDrehen=true} nur die Läufe zum Drehen, siehe docs/minimap.md, „Drehen“. */
    private static final boolean NUR_DREHEN = Boolean.getBoolean("heroicmap.messung.drehen");
    /** Mit {@code -PmessungRahmen=<skin>} misst sie mit diesem Rahmen, sonst ohne, siehe docs/rahmen.md, „Kosten“. */
    private static final String RAHMEN = System.getProperty("heroicmap.messung.rahmen", Skin.OHNE);
    /** Mit {@code -PmessungEffekte=true} nur die Läufe zu den Effekten in der Welt, siehe docs/wegpunkte.md, „Strahl“. */
    private static final boolean NUR_EFFEKTE = Boolean.getBoolean("heroicmap.messung.effekte");
    /** Mit {@code -PmessungRegionen=<n>} so viele angeheftete Regionen für den Schleier, gleich verteilt bis 188 Blöcke halbe Seite. */
    private static final int REGIONEN = Integer.getInteger("heroicmap.messung.regionen", 47);
    private static final int SICHTWEITE = 12;
    private static final int RUNDEN = 3;
    private static final int STAND_TICKS = 100;
    private static final int FLUG_TICKS = 200;
    private static final int HOEHE = 160;

    private final LongArrayList frames = new LongArrayList();
    /** Zeit je Frame im HUD-Element der Minimap. */
    private final LongArrayList hud = new LongArrayList();
    private long hudVor;
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
        // Zwei Elemente um das der Minimap stoppen, was sie im HUD kostet: arbeiten und zeichnen.
        Identifier minimap = Identifier.fromNamespaceAndPath(HeroicMap.ID, "minimap");
        HudElementRegistry.attachElementBefore(minimap, Identifier.fromNamespaceAndPath(HeroicMap.ID, "messung_vor"),
                (g, t) -> hudVor = System.nanoTime());
        HudElementRegistry.attachElementAfter(minimap, Identifier.fromNamespaceAndPath(HeroicMap.ID, "messung_nach"),
                (g, t) -> {
                    if (aufnehmen) {
                        hud.add(System.nanoTime() - hudVor);
                    }
                });
        context.runOnClient(mc -> {
            // Wie die Messreihen davor ohne Rahmen, ausser -PmessungRahmen sagt einen.
            Minimap.INSTANZ.setzeSkin(RAHMEN);
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

            if (NUR_DREHEN) {
                drehen(context, server);
                schreibe();
                return;
            }
            if (NUR_EFFEKTE) {
                effekte(context);
                schreibe();
                return;
            }
            atlas(context);
            for (int scale : new int[] {1, 2, 4, 8, 16}) {
                for (int runde = 1; runde <= RUNDEN; runde++) {
                    chunks(context, scale, runde);
                }
            }

            region(context, 512);
            region(context, 2048);
            // Einmal hin und zurück, ungemessen: Danach erzeugt der Server im Flug nichts mehr,
            // und dieselbe Strecke liegt geladen da.
            flug(context, server, true);
            flug(context, server, false);

            // Bildrate ohne Grenze, 144 und 60, dazu die Massstäbe und rund gleich nach eckig;
            // 260 heisst ohne Grenze.
            boolean hin = true;
            // Bildrate, Auflösung, rund, Zoom; 16 px nur mit Zoom 8, sonst hat der Schirm die Pixel nicht.
            for (int[] lauf : new int[][] {{260, 2, 0, 2}, {260, 4, 0, 4}, {260, 4, 1, 4}, {260, 16, 0, 8},
                    {144, 4, 0, 4}, {144, 4, 1, 4}, {144, 16, 0, 8}, {60, 4, 0, 4}}) {
                int fps = lauf[0], scale = lauf[1], zoom = lauf[3];
                boolean rund = lauf[2] == 1;
                context.runOnClient(mc -> {
                    mc.options.framerateLimit().set(fps);
                    Minimap.INSTANZ.setzeScale(scale);
                    Minimap.INSTANZ.setzeZoom(zoom);
                    Minimap.INSTANZ.setzeRund(rund);
                    // Wie die Messreihen davor: genordet.
                    Minimap.INSTANZ.setzeDrehen(false);
                });
                zeige(context, true);
                String art = "fps=" + (fps == 260 ? "frei" : fps) + " scale=" + scale + " zoom=" + zoom + " form=" + (rund ? "rund" : "eckig");
                warteAufFreieFrames(context, art);
                for (int runde = 1; runde <= RUNDEN; runde++) {
                    for (boolean an : new boolean[] {false, true}) {
                        zeige(context, an);
                        frames(context, art + " stand", an, runde, () -> context.waitTicks(STAND_TICKS));
                    }
                }
                for (int runde = 1; runde <= RUNDEN; runde++) {
                    for (boolean an : new boolean[] {false, true}) {
                        zeige(context, an);
                        boolean richtung = hin;
                        frames(context, art + " flug", an, runde, () -> flug(context, server, richtung));
                        hin = !hin;
                    }
                }
            }

        }
        schreibe();
    }

    private void schreibe() {
        try {
            Files.writeString(Path.of(AUSGABE), bericht);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Wartet, bis das Spiel nicht mehr im Takt der Ticks zeichnet, einen Frame je Tick: mehr als
     * 2 Frames je Tick über 20 Ticks. So lief der Gametest zeitweise, nach dem Laden der Welt
     * und für einige Minuten auch später.
     */
    private void warteAufFreieFrames(ClientGameTestContext context, String art) {
        for (int versuch = 0; versuch < 30; versuch++) {
            context.runOnClient(mc -> {
                frames.clear();
                aufnehmen = true;
            });
            context.waitTicks(20);
            int n = context.computeOnClient(mc -> {
                aufnehmen = false;
                return frames.size();
            });
            if (n > 40) {
                zeile("frei %s versuche=%d frames_je_20_ticks=%d", art, versuch, n);
                return;
            }
            context.waitTicks(200);
        }
        zeile("takt %s blieb bei einem Frame je Tick", art);
    }

    /**
     * Was das Drehen je Frame kostet: 4 px, Zoom 4, freie Bildrate, eckig und rund, je ohne und mit
     * Drehen; Frametime ohne und mit Minimap und die Zeit im HUD. Im Stand bei Gier 30. Im Flug setzt
     * {@link #flug} die Gier auf ±90, dort dreht die Karte um eine Vierteldrehung; schräg 30 daneben.
     */
    private void drehen(ClientGameTestContext context, TestServerContext server) {
        flug(context, server, true);
        flug(context, server, false);
        boolean hin = true;
        for (int[] lauf : new int[][] {{0, 0}, {0, 1}, {1, 0}, {1, 1}}) {
            boolean rund = lauf[0] == 1, drehen = lauf[1] == 1;
            context.runOnClient(mc -> {
                Minimap.INSTANZ.setzeScale(4);
                Minimap.INSTANZ.setzeZoom(4);
                Minimap.INSTANZ.setzeRund(rund);
                Minimap.INSTANZ.setzeDrehen(drehen);
                mc.player.setYRot(30);
            });
            zeige(context, true);
            String art = "fps=frei scale=4 zoom=4 form=" + (rund ? "rund" : "eckig") + " drehen=" + (drehen ? "an" : "aus") + " rahmen=" + RAHMEN;
            warteAufFreieFrames(context, art);
            for (int runde = 1; runde <= RUNDEN; runde++) {
                for (boolean an : new boolean[] {false, true}) {
                    zeige(context, an);
                    frames(context, art + " stand", an, runde, () -> context.waitTicks(STAND_TICKS));
                }
            }
            for (int schraeg : new int[] {0, 30}) {
                for (int runde = 1; runde <= RUNDEN; runde++) {
                    for (boolean an : new boolean[] {false, true}) {
                        zeige(context, an);
                        boolean richtung = hin;
                        frames(context, art + (schraeg == 0 ? " flug" : " flug-schraeg"), an, runde,
                                () -> flug(context, server, richtung, schraeg));
                        hin = !hin;
                    }
                }
            }
        }
        context.runOnClient(mc -> Minimap.INSTANZ.setzeDrehen(false));
    }

    /**
     * Was die Effekte in der Welt je Frame kosten: erst {@link Strahlen#MAX_STRAHLEN} angeheftete
     * Wegpunkte auf einer Spirale um den Spieler, 16 bis 142 Blöcke weit, also alle in Sichtweite; dann
     * dazu 47 angeheftete eigene Regionen als Quadrate um den Spieler, halbe Seite 4 bis 188 Blöcke, so
     * hat der Schleier mehr als {@link Schleier#MAX_VIERECKE} Stücke, die Spitze. 4 px, Zoom 4, freie
     * Bildrate, im Stand; Frametime ohne und mit Effekten im Wechsel, und wie lange ein Bau des Schleiers
     * dauert.
     */
    private void effekte(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            String welt = mc.level.dimension().identifier().toString();
            for (int i = 0; i < Strahlen.MAX_STRAHLEN; i++) {
                double w = 2 * Math.PI * i / Strahlen.MAX_STRAHLEN, r = 16 + 2 * i;
                Wegpunkte.INSTANZ.setze(welt, (int) Math.round(r * Math.cos(w)), (int) Math.round(r * Math.sin(w)));
            }
            for (Wegpunkte.Punkt p : java.util.List.copyOf(Wegpunkte.INSTANZ.punkte())) {
                Wegpunkte.INSTANZ.umschalten(p);
            }
            Minimap.INSTANZ.setzeScale(4);
            Minimap.INSTANZ.setzeZoom(4);
            Minimap.INSTANZ.setzeRund(false);
            Minimap.INSTANZ.setzeDrehen(false);
        });
        int n = context.computeOnClient(mc -> (int) Wegpunkte.INSTANZ.punkte().stream().filter(Wegpunkte.Punkt::angeheftet).count());
        zeile("strahlen angeheftet=%d", n);
        zeige(context, true);
        String art = "fps=frei scale=4 zoom=4 strahlen=" + n;
        warteAufFreieFrames(context, art);
        for (int runde = 1; runde <= RUNDEN; runde++) {
            for (boolean an : new boolean[] {false, true}) {
                context.runOnClient(mc -> Minimap.INSTANZ.setzeEffekte(an));
                frames(context, art + " effekte=" + (an ? "an" : "aus") + " stand", true, runde, () -> context.waitTicks(STAND_TICKS));
            }
        }

        context.runOnClient(mc -> {
            String welt = mc.level.dimension().identifier().toString();
            // Bei 47 halbe Seiten 4, 8, … 188 Blöcke: mehr Stücke als die Grenze des Schleiers, die Spitze.
            for (int i = 1; i <= REGIONEN; i++) {
                int s = Math.round(188f * i / REGIONEN);
                Wegpunkte.INSTANZ.setze(welt, -s, -s, s - 1, s - 1);
                Wegpunkte.INSTANZ.umschalten(Wegpunkte.INSTANZ.regionen().getLast());
            }
            Minimap.INSTANZ.setzeEffekte(true);
        });
        context.waitTicks(20);
        int vierecke = context.computeOnClient(mc -> Schleier.INSTANZ.vierecke());
        // Der Bau allein, zehnmal hintereinander auf dem Render-Thread.
        long[] bau = context.computeOnClient(mc -> {
            long[] z = new long[10];
            int weit = mc.options.getEffectiveRenderDistance() * 16;
            for (int i = 0; i < z.length; i++) {
                long t0 = System.nanoTime();
                Schleier.INSTANZ.baue(mc.level, mc.player.getBlockX(), mc.player.getBlockZ(), weit, System.currentTimeMillis());
                z[i] = System.nanoTime() - t0;
            }
            return z;
        });
        Arrays.sort(bau);
        zeile("schleier regionen=%d vierecke=%d bau n=%d median=%.3f ms max=%.3f ms", REGIONEN, vierecke, bau.length, ms(quantil(bau, 0.5)),
                ms(bau[bau.length - 1]));
        String mitSchleier = art + " schleier=" + vierecke;
        for (int runde = 1; runde <= RUNDEN; runde++) {
            for (boolean an : new boolean[] {false, true}) {
                context.runOnClient(mc -> Minimap.INSTANZ.setzeEffekte(an));
                frames(context, mitSchleier + " effekte=" + (an ? "an" : "aus") + " stand", true, runde, () -> context.waitTicks(STAND_TICKS));
            }
        }
        context.runOnClient(mc -> {
            Minimap.INSTANZ.setzeEffekte(true);
            Wegpunkte.INSTANZ.leeren();
        });
    }

    /** Fliegt 20 Blöcke/s über die Strecke x = 0 bis FLUG_TICKS, hin nach Osten oder zurück. */
    private static void flug(ClientGameTestContext context, TestServerContext server, boolean hin) {
        flug(context, server, hin, 0);
    }

    /** Wie {@link #flug(ClientGameTestContext, TestServerContext, boolean)}, der Blick {@code schraeg} Grad neben der Flugrichtung. */
    private static void flug(ClientGameTestContext context, TestServerContext server, boolean hin, int schraeg) {
        for (int t = 1; t <= FLUG_TICKS; t++) {
            int x = hin ? t : FLUG_TICKS - t;
            server.runCommand("tp @a " + x + " " + HOEHE + " 0 " + ((hin ? -90 : 90) + schraeg) + " 20");
            context.waitTick();
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

    /** Was eine neue Region auf dem Render-Thread kostet: anlegen, leer hochladen, anmelden, freigeben; 512 ist 4 px, 2048 ist 16 px. */
    private void region(ClientGameTestContext context, int seite) {
        long[] zeiten = context.computeOnClient(mc -> {
            long[] z = new long[5];
            for (int i = 0; i < z.length; i++) {
                Identifier id = Identifier.fromNamespaceAndPath(HeroicMap.ID, "messung/" + i);
                long t0 = System.nanoTime();
                DynamicTexture textur = new DynamicTexture(() -> "messung", seite, seite, true);
                textur.upload();
                mc.getTextureManager().register(id, textur);
                z[i] = System.nanoTime() - t0;
                mc.getTextureManager().release(id);
            }
            return z;
        });
        Arrays.sort(zeiten);
        zeile("region seite=%d n=%d median=%.3f ms max=%.3f ms", seite, zeiten.length, ms(quantil(zeiten, 0.5)), ms(zeiten[zeiten.length - 1]));
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
            hud.clear();
            aufnehmen = true;
        });
        long[] gcVorher = gc();
        long chunksVorher = context.computeOnClient(mc -> Minimap.INSTANZ.uebernommen());
        lauf.run();
        long[] gcNachher = gc();
        long chunks = context.computeOnClient(mc -> Minimap.INSTANZ.uebernommen()) - chunksVorher;
        long[][] beide = context.computeOnClient(mc -> {
            aufnehmen = false;
            return new long[][] {frames.toLongArray(), hud.toLongArray()};
        });
        long[] zeiten = beide[0], imHud = beide[1];
        Arrays.sort(zeiten);
        Arrays.sort(imHud);
        long summe = 0;
        for (long z : zeiten) {
            summe += z;
        }
        zeile("frames %s minimap=%s runde=%d n=%d mittel=%.3f ms p50=%.3f ms p95=%.3f ms p99=%.3f ms max=%.3f ms gc=%d gc_ms=%d chunks=%d",
                art, an ? "an" : "aus", runde, zeiten.length, ms(summe / zeiten.length),
                ms(quantil(zeiten, 0.5)), ms(quantil(zeiten, 0.95)), ms(quantil(zeiten, 0.99)),
                ms(zeiten[zeiten.length - 1]), gcNachher[0] - gcVorher[0], gcNachher[1] - gcVorher[1], chunks);
        if (imHud.length > 0) {
            zeile("hud %s runde=%d n=%d p50=%.3f ms p95=%.3f ms p99=%.3f ms", art, runde, imHud.length,
                    ms(quantil(imHud, 0.5)), ms(quantil(imHud, 0.95)), ms(quantil(imHud, 0.99)));
        }
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
