package com.nekyia.heroicmap;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.slf4j.Logger;

/**
 * Die selbst gezeichnete Karte, die Wahl „Selbst“: Für die Dimension zeichnet ein Worker jeden
 * Chunk, den der Client lädt oder ändert, mit dem Maler der Minimap, 4 Pixel je Block, in die
 * Kacheln eines eigenen Baums; die Vollbildkarte zeigt dann nur diesen. Siehe docs/selbst.md.
 */
public final class Selbst {

    public static final Selbst INSTANZ = new Selbst();
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Pixel je Block, Kachelgrösse und Stufen des Baums. */
    static final int SCALE = 4, KACHEL = 256, MIN_ZOOM = 0, MAX_ZOOM = 8;
    /** So beginnt der Name jedes selbst gezeichneten Baums. */
    static final String PRAEFIX = "selbst-";
    /** Je Chunk höchstens so oft: Wasser fliesst, Getreide wächst. */
    static final long PAUSE_MS = 5000;
    /** So oft schreibt der Worker geänderte Kacheln der zwei feinsten Stufen, und so oft die gröberen. */
    static final long SCHREIBEN_MS = 5000, GROB_MS = 60_000;
    /** Die Datei, die einen Baum als selbst gezeichnet ausweist. */
    static final String MARKE = "selbst.txt";
    /** So viele Chunks hat der Worker höchstens vor sich. */
    private static final int IN_ARBEIT = 4;
    /** Zeit je Tick auf dem Render-Thread für Abzüge. */
    private static final long BUDGET_NS = 1_000_000L;

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Heroic Map Selbst");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    /** Gehören dem Worker. */
    private ChunkMaler maler;
    private Kachelwerk werk;
    private boolean gemeldet;
    /** Wann die gröberen Stufen zuletzt geschrieben wurden, in ms. */
    private long grobGeschrieben;

    /** Chunks, die zu zeichnen sind. */
    private final LongLinkedOpenHashSet offen = new LongLinkedOpenHashSet();
    /** Wann jeder Chunk zuletzt gezeichnet wurde, in ms. */
    private final Long2LongOpenHashMap zuletzt = new Long2LongOpenHashMap();
    private final AtomicInteger inArbeit = new AtomicInteger();
    /** Der selbst gezeichnete Satz dieser Welt und Dimension, oder null; gesucht beim ersten Bedarf. */
    private Satz satz;
    private boolean gesucht;
    private Licht licht;
    /** Im Gametest der Ordner der Welt; sonst null, dann gilt {@link Downloads#weltOrdner()}. */
    private Path testWelt;

    private Selbst() {
        worker.scheduleWithFixedDelay(() -> schreibe(false), SCHREIBEN_MS, SCHREIBEN_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Der Name des Baums einer Dimension: {@link #PRAEFIX}, die Kennung, andere Zeichen als a–z,
     * 0–9, _ und - als _, gekürzt, dazu 8 Stellen hex aus CRC32 der Kennung. So ergeben
     * {@code mod:a/b} und {@code mod:a_b} zwei Bäume.
     */
    static String baum(String dimension) {
        String lesbar = dimension.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        CRC32 crc = new CRC32();
        crc.update(dimension.getBytes(StandardCharsets.UTF_8));
        return PRAEFIX + lesbar.substring(0, Math.min(lesbar.length(), 47)) + "-" + String.format(Locale.ROOT, "%08x", crc.getValue());
    }

    /**
     * Ist der Baum selbst gezeichnet? Am Präfix und an {@link #MARKE}; einen Baum mit dem Präfix
     * lädt der Mod nie vom Server ({@code Freigabe.baum}).
     */
    static boolean selbst(Path baum) {
        return baum.getFileName().toString().startsWith(PRAEFIX) && Files.exists(baum.resolve(MARKE));
    }

    /** Wird die Dimension in der Welt {@code welt} selbst gezeichnet? Die Dimension aus {@code satz.json} muss passen. */
    static boolean an(Path welt, String dimension) {
        Path baum = welt.resolve(baum(dimension));
        Satz satz = Satz.lies(baum);
        return satz != null && dimension.equals(satz.dimension()) && selbst(baum);
    }

    /** Legt den Baum der Dimension in der Welt an, {@code satz.json} und {@code map.json}, und gibt ihn zurück. */
    static Path anlegen(Path welt, String dimension) throws IOException {
        Path baum = welt.resolve(baum(dimension)), ordner = baum.resolve(String.valueOf(SCALE));
        Files.createDirectories(ordner);
        JsonObject karte = new JsonObject();
        karte.addProperty("tileSize", KACHEL);
        karte.addProperty("minZoom", MIN_ZOOM);
        karte.addProperty("maxZoom", MAX_ZOOM);
        karte.addProperty("scale", SCALE);
        Files.writeString(ordner.resolve("map.json"), karte.toString(), StandardCharsets.UTF_8);
        Files.writeString(baum.resolve(MARKE), "Heroic Map: selbst gezeichnet, " + dimension + "\n", StandardCharsets.UTF_8);
        Satz.schreibe(baum, "Selbst", dimension, SCALE);
        return baum;
    }

    /** Der Ordner der Welt, in dem der Mod zeichnen kann, oder null, etwa im Einzelspieler; im Gametest der Testordner. */
    Path weltOrdner() {
        return testWelt != null ? testWelt : Downloads.weltOrdner();
    }

    /** Geht „Selbst“ hier? Nicht ohne Ordner der Welt, etwa im Einzelspieler, und nicht unter einer Decke. */
    boolean moeglich(Minecraft mc) {
        return weltOrdner() != null && mc.level != null && !mc.level.dimensionType().hasCeiling();
    }

    /** Wird die Dimension des Spielers schon selbst gezeichnet? */
    boolean an(Minecraft mc) {
        return moeglich(mc) && an(weltOrdner(), dimension(mc.level));
    }

    /** Die Wahl „Selbst“: legt den Baum an und zeichnet die geladenen Chunks. Gibt den Fehler zurück, oder null. */
    Component waehle(Minecraft mc) {
        if (!moeglich(mc) || mc.player == null) {
            return Component.translatable("heroicmap.selbst.geht_nicht");
        }
        try {
            anlegen(weltOrdner(), dimension(mc.level));
        } catch (IOException e) {
            LOGGER.warn("Heroic Map: Karte zum selbst Zeichnen nicht angelegt", e);
            return Component.translatable("heroicmap.selbst.fehler");
        }
        satz = null;
        gesucht = false;
        // Was schon geladen ist, meldet der Mixin nicht noch einmal.
        int r = mc.options.getEffectiveRenderDistance() + 1;
        ChunkPos m = mc.player.chunkPosition();
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (mc.level.getChunkSource().getChunk(m.x() + dx, m.z() + dz, ChunkStatus.FULL, false) != null) {
                    offen.add(ChunkPos.pack(m.x() + dx, m.z() + dz));
                }
            }
        }
        return null;
    }

    private static String dimension(ClientLevel level) {
        return level.dimension().identifier().toString();
    }

    /** Vom Mixin: Der Chunk (x, z) ist neu zu zeichnen, wenn die Dimension selbst gezeichnet wird. */
    public void markiere(int x, int z) {
        if (satz != null || !gesucht) {
            offen.add(ChunkPos.pack(x, z));
        }
    }

    /** Vergisst alles beim Wechsel der Welt oder beim Trennen; was noch nicht geschrieben ist, schreibt der Worker vorher. */
    void leeren() {
        offen.clear();
        zuletzt.clear();
        satz = null;
        gesucht = false;
        licht = null;
        worker.execute(() -> {
            schreibe(true);
            werk = null;
        });
    }

    /**
     * Löscht einen selbst gezeichneten Baum im Worker, nach allem, was dort noch wartet, und ruft
     * dann {@code danach} auf dem Render-Thread, mit wahr, wenn alles weg ist. Wird gerade in ihn
     * gezeichnet, endet das bis zum nächsten Wechsel der Welt.
     */
    void loesche(Path baum, Consumer<Boolean> danach) {
        if (satz != null && satz.ordner().getParent().equals(baum)) {
            satz = null;
            gesucht = true;
            offen.clear();
        }
        worker.execute(() -> {
            boolean ganz = false;
            try {
                if (werk != null && werk.ordner().getParent().equals(baum)) {
                    werk = null;
                }
                Laden.loesche(baum);
                ganz = true;
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Heroic Map: {} nicht ganz gelöscht", baum, e);
            } finally {
                boolean ok = ganz;
                Minecraft.getInstance().execute(() -> danach.accept(ok));
            }
        });
    }

    private Satz satz(ClientLevel level) {
        if (!gesucht) {
            gesucht = true;
            Satz s = level.dimensionType().hasCeiling() ? null : Satz.fuer(weltOrdner(), dimension(level));
            satz = s != null && selbst(s.ordner().getParent()) ? s : null;
            if (satz == null) {
                offen.clear();
            }
        }
        return satz;
    }

    /**
     * Je Tick: Chunks, die bereit sind, abziehen und dem Worker geben. Die Minimap geht vor: Hat sie
     * zu tun, höchstens einer je Tick, so wartet die eigene Karte nie ganz.
     */
    void arbeite(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || satz(level) == null || offen.isEmpty()) {
            return;
        }
        int hoechstens = Minimap.INSTANZ.beschaeftigt() ? 1 : IN_ARBEIT, gestartet = 0;
        long jetzt = System.currentTimeMillis(), ende = System.nanoTime() + BUDGET_NS;
        for (LongIterator it = offen.iterator(); it.hasNext() && gestartet < hoechstens && inArbeit.get() < IN_ARBEIT
                && System.nanoTime() < ende; ) {
            long k = it.nextLong();
            int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
            LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk == null) {
                it.remove();
                continue;
            }
            if (jetzt - zuletzt.getOrDefault(k, Long.MIN_VALUE / 2) < PAUSE_MS || !bereit(level, cx, cz)) {
                continue;
            }
            it.remove();
            zuletzt.put(k, jetzt);
            starte(mc, level, chunk);
            gestartet++;
        }
    }

    /**
     * Schatten, Licht und Biomübergang am Rand brauchen alle 8 Nachbarn, geladen und mit Licht. Der
     * Client setzt das Licht eines neuen Chunks erst später ({@code setLightEnabled}); vorher läse
     * der Maler zu dunkle oder zu helle Spalten. Siehe docs/selbst.md, „Wann gezeichnet wird“.
     */
    private static boolean bereit(ClientLevel level, int cx, int cz) {
        LevelLightEngine licht = level.getChunkSource().getLightEngine();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (level.getChunkSource().getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false) == null
                        || !licht.lightOnInColumn(SectionPos.getZeroNode(cx + dx, cz + dz))) {
                    return false;
                }
            }
        }
        return true;
    }

    private void starte(Minecraft mc, ClientLevel level, LevelChunk chunk) {
        if (licht == null) {
            licht = Licht.von(level.dimensionType());
        }
        ChunkMaler.Auftrag auftrag = ChunkMaler.abziehen(level, chunk, SCALE, Integer.MAX_VALUE, licht, Minimap.INSTANZ.texel(mc), 0);
        Satz s = satz;
        inArbeit.incrementAndGet();
        worker.execute(() -> {
            try {
                if (maler == null) {
                    maler = new ChunkMaler();
                }
                werk(s).lege(auftrag.cx(), auftrag.cz(), maler.male(auftrag));
            } catch (RuntimeException e) {
                melde(e);
            } finally {
                inArbeit.decrementAndGet();
            }
        });
    }

    /** Im Worker: das Werk für den Satz; ein anderes schreibt vorher, was es noch hat. */
    private Kachelwerk werk(Satz s) {
        if (werk == null || !werk.ordner().equals(s.ordner())) {
            schreibe(true);
            werk = new Kachelwerk(s.ordner(), s.kachel(), 16 * SCALE, s.minZoom(), s.maxZoom());
        }
        return werk;
    }

    /**
     * Im Worker: geänderte Kacheln schreiben, die gröberen Stufen höchstens alle {@link #GROB_MS}
     * oder mit {@code alles}, und der offenen Karte melden, was geschrieben ist, auch nach einem
     * Fehler. Wirft nie, sonst endete die Wiederholung.
     */
    private void schreibe(boolean alles) {
        if (werk == null) {
            return;
        }
        Path ordner = werk.ordner();
        List<Kachelwerk.Kachel> fertig = new ArrayList<>();
        long jetzt = System.currentTimeMillis();
        boolean grob = alles || jetzt - grobGeschrieben >= GROB_MS;
        try {
            werk.schreibe(grob, fertig);
            if (grob) {
                grobGeschrieben = jetzt;
            }
        } catch (IOException | RuntimeException e) {
            melde(e);
        } finally {
            if (!fertig.isEmpty()) {
                Minecraft.getInstance().execute(() -> fertig.forEach(k -> Kacheln.geaendert(ordner, k.z(), k.x(), k.y())));
            }
        }
    }

    /** Der erste Fehler steht mit Stacktrace im Log, jeder weitere nicht; im Worker. */
    private void melde(Exception e) {
        if (!gemeldet) {
            gemeldet = true;
            LOGGER.warn("Heroic Map: Chunk für die selbst gezeichnete Karte nicht gezeichnet oder geschrieben", e);
        }
    }

    /** Für den Gametest: zeichnet in {@code welt} statt in den Ordner der Welt des Servers. */
    void fuerTest(Path welt) {
        testWelt = welt;
        satz = null;
        gesucht = false;
    }

    /** Für den Gametest: Ist um den Chunk {@code mitte} im Radius {@code r} alles gezeichnet? */
    boolean fertig(ChunkPos mitte, int r) {
        for (LongIterator it = offen.iterator(); it.hasNext(); ) {
            long k = it.nextLong();
            if (Math.abs(ChunkPos.getX(k) - mitte.x()) <= r && Math.abs(ChunkPos.getZ(k) - mitte.z()) <= r) {
                return false;
            }
        }
        return inArbeit.get() == 0;
    }

    /** Für den Gametest: schreibt jetzt, was geändert ist. */
    CompletableFuture<Void> schreibeJetzt() {
        return CompletableFuture.runAsync(() -> schreibe(true), worker);
    }
}
