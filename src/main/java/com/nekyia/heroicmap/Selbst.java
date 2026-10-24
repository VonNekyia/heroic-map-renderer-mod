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
 * Chunk, den der Client lädt oder ändert, mit dem Maler der Minimap, 1, 2 oder 4 Pixel je Block,
 * in die Kacheln eines eigenen Baums; die Vollbildkarte zeigt dann nur diesen. Siehe docs/selbst.md.
 */
public final class Selbst {

    public static final Selbst INSTANZ = new Selbst();
    private static final Logger LOGGER = LogUtils.getLogger();
    /** {@code scale} in {@code map.json}, Kachelgrösse und Stufen des Baums, für jeden Massstab gleich, wie bei einem Satz des Servers. */
    static final int SCALE = 4, KACHEL = 256, MIN_ZOOM = 0, MAX_ZOOM = 8;
    /** Die Massstäbe zur Wahl in Pixeln je Block, wie bei den Karten des Servers, und die Vorgabe. Siehe docs/selbst.md, „Massstab“. */
    static final List<Integer> MASSSTAEBE = List.of(1, 2, 4);
    static final int MASSSTAB = 4;
    /** So beginnt der Name jedes selbst gezeichneten Baums. */
    static final String PRAEFIX = "selbst-";
    /** Je Chunk höchstens so oft: Wasser fliesst, Getreide wächst. */
    static final long PAUSE_MS = 5000;
    /** So oft schreibt der Worker geänderte Kacheln der zwei feinsten Stufen, und so oft die gröberen. */
    static final long SCHREIBEN_MS = 5000, GROB_MS = 60_000;
    /** So oft ein Schritt des Neubaus der gröberen Stufen eines alten Satzes, und so viele Kacheln der feinsten je Schritt. */
    static final long NEUBAU_MS = 250;
    static final int NEUBAU_KACHELN = 16;
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
    /** Hat das Werk aufgegeben, etwa bei voller Platte? Dann zeichnet der Worker bis zum nächsten Wechsel der Welt nicht. */
    private boolean aufgegeben;
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

    private Selbst() {
        worker.scheduleWithFixedDelay(() -> schreibe(false), SCHREIBEN_MS, SCHREIBEN_MS, TimeUnit.MILLISECONDS);
        worker.scheduleWithFixedDelay(this::baueNeu, NEUBAU_MS, NEUBAU_MS, TimeUnit.MILLISECONDS);
    }

    /** Der Name des Baums einer Dimension für einen Server ohne Namen, auch im Einzelspieler; siehe unten. */
    static String baum(String dimension) {
        return baum(dimension, Zeigt.OHNE);
    }

    /**
     * Der Name des Baums einer Dimension für {@code server}: {@link #PRAEFIX}, die Kennung, andere Zeichen als a–z,
     * 0–9, _ und - als _, gekürzt, dazu 8 Stellen hex aus CRC32 der Kennung, mit Namen über Server und Kennung. So
     * ergeben {@code mod:a/b} und {@code mod:a_b} zwei Bäume, ebenso main und build. Ohne Namen wie vor der Karte je
     * Server: So bleibt jeder alte Baum der eines Servers ohne Namen.
     */
    static String baum(String dimension, String server) {
        String lesbar = dimension.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        CRC32 crc = new CRC32();
        crc.update((Zeigt.OHNE.equals(server) ? dimension : server + "\n" + dimension).getBytes(StandardCharsets.UTF_8));
        return PRAEFIX + lesbar.substring(0, Math.min(lesbar.length(), 47)) + "-" + String.format(Locale.ROOT, "%08x", crc.getValue());
    }

    /**
     * Ist der Baum selbst gezeichnet? Am Präfix und an {@link #MARKE}; einen Baum mit dem Präfix
     * lädt der Mod nie vom Server ({@code Freigabe.baum}).
     */
    static boolean selbst(Path baum) {
        return baum.getFileName().toString().startsWith(PRAEFIX) && Files.exists(baum.resolve(MARKE));
    }

    /** Gehört der selbst gezeichnete Baum {@code server}? Siehe docs/selbst.md, „Wahl“. */
    static boolean gehoert(Path baum, String server) {
        return selbst(baum) && server.equals(Satz.besitzer(baum));
    }

    /** Hat ein Server ohne Namen in der Welt {@code welt} eine eigene Karte der Dimension? */
    static boolean an(Path welt, String dimension) {
        return an(welt, dimension, Zeigt.OHNE);
    }

    /**
     * Hat {@code server} in der Welt {@code welt} eine eigene Karte der Dimension, gezeigt oder ruhend? Die Dimension
     * aus {@code satz.json} und der Server müssen passen.
     */
    static boolean an(Path welt, String dimension, String server) {
        Path baum = welt.resolve(baum(dimension, server));
        Satz satz = Satz.lies(baum);
        return satz != null && dimension.equals(satz.dimension()) && gehoert(baum, server);
    }

    /**
     * Legt den Baum der Dimension in der Welt an, mit {@code massstab} Pixeln je Block: {@code satz.json}
     * und {@code map.json} im Ordner des Massstabs; {@code map.json} ist für jeden gleich, die feinste
     * Stufe sagt {@link Satz#stufe}. Gibt den Baum zurück.
     */
    static Path anlegen(Path welt, String dimension, int massstab) throws IOException {
        return anlegen(welt, dimension, massstab, Zeigt.OHNE);
    }

    /** Wie oben, für {@code server}; {@code satz.json} nennt ihn. */
    static Path anlegen(Path welt, String dimension, int massstab, String server) throws IOException {
        Path baum = welt.resolve(baum(dimension, server)), ordner = baum.resolve(String.valueOf(massstab));
        Files.createDirectories(ordner);
        JsonObject karte = new JsonObject();
        karte.addProperty("tileSize", KACHEL);
        karte.addProperty("minZoom", MIN_ZOOM);
        karte.addProperty("maxZoom", MAX_ZOOM);
        karte.addProperty("scale", SCALE);
        karte.addProperty(Kachelwerk.DOWNSCALE, Kachelwerk.NEAREST);
        Files.writeString(ordner.resolve("map.json"), karte.toString(), StandardCharsets.UTF_8);
        Files.writeString(baum.resolve(MARKE), "Heroic Map: selbst gezeichnet, " + dimension + "\n", StandardCharsets.UTF_8);
        Satz.schreibe(baum, "Selbst", dimension, massstab, server);
        return baum;
    }

    /** Der Ordner der Welt, auf einem Server wie im Einzelspieler, oder null ohne Welt. */
    Path weltOrdner() {
        return Downloads.weltOrdner();
    }

    /** Geht „Selbst“ hier? Nicht ohne Ordner der Welt und nicht unter einer Decke. */
    boolean moeglich(Minecraft mc) {
        return weltOrdner() != null && mc.level != null && !mc.level.dimensionType().hasCeiling();
    }

    /** Hat dieser Server eine eigene Karte der Dimension des Spielers, gezeigt oder ruhend? */
    boolean vorhanden(Minecraft mc) {
        return moeglich(mc) && an(weltOrdner(), dimension(mc.level), Downloads.INSTANZ.wer());
    }

    /** Zeigt dieser Server seine eigene Karte der Dimension des Spielers? */
    boolean an(Minecraft mc) {
        if (!vorhanden(mc)) {
            return false;
        }
        Satz gezeigt = Satz.fuer(weltOrdner(), dimension(mc.level), Downloads.INSTANZ.wer());
        return gezeigt != null && gezeigt.ordner().getParent().equals(eigenerBaum(mc));
    }

    /** Der Baum der eigenen Karte dieses Servers in der Dimension des Spielers, ob es ihn gibt oder nicht. */
    Path eigenerBaum(Minecraft mc) {
        return weltOrdner().resolve(baum(dimension(mc.level), Downloads.INSTANZ.wer()));
    }

    /** Der Massstab der eigenen Karte dieser Dimension, oder 0, wenn es keine gibt. */
    int massstab(Minecraft mc) {
        Satz s = vorhanden(mc) ? Satz.lies(eigenerBaum(mc)) : null;
        return s == null ? 0 : s.massstab();
    }

    /** Die eigene Karte, die ruht, wieder an: Dieser Server zeigt sie für die Dimension statt des Downloads. */
    Component wiederAn(Minecraft mc) {
        if (!vorhanden(mc)) {
            return Component.translatable("heroicmap.selbst.geht_nicht");
        }
        try {
            Zeigt.setze(weltOrdner(), Downloads.INSTANZ.wer(), dimension(mc.level), eigenerBaum(mc).getFileName().toString());
        } catch (IOException e) {
            LOGGER.warn("Heroic Map: Wahl der Karte nicht gespeichert", e);
            return Component.translatable("heroicmap.selbst.fehler");
        }
        neuGewaehlt(mc);
        return null;
    }

    /**
     * Nach einer neuen Wahl in zeigt.txt: neu suchen, ob und wohin gezeichnet wird, und alle geladenen Chunks
     * vormerken; was schon geladen ist, meldet der Mixin nicht noch einmal.
     */
    void neuGewaehlt(Minecraft mc) {
        satz = null;
        gesucht = false;
        if (mc.player == null || mc.level == null) {
            return;
        }
        int r = mc.options.getEffectiveRenderDistance() + 1;
        ChunkPos m = mc.player.chunkPosition();
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (mc.level.getChunkSource().getChunk(m.x() + dx, m.z() + dz, ChunkStatus.FULL, false) != null) {
                    offen.add(ChunkPos.pack(m.x() + dx, m.z() + dz));
                }
            }
        }
    }

    /**
     * Die Wahl „Selbst“ mit {@code massstab} Pixeln je Block: legt den Baum an und zeichnet die
     * geladenen Chunks. Gibt den Fehler zurück, oder null.
     */
    Component waehle(Minecraft mc, int massstab) {
        if (!moeglich(mc) || mc.player == null) {
            return Component.translatable("heroicmap.selbst.geht_nicht");
        }
        try {
            String wer = Downloads.INSTANZ.wer();
            Path baum = anlegen(weltOrdner(), dimension(mc.level), massstab, wer);
            // Gewählt ist gewählt: Ab jetzt zeigt dieser Server sie, auch wenn ein Download der Dimension daliegt.
            Zeigt.setze(weltOrdner(), wer, dimension(mc.level), baum.getFileName().toString());
        } catch (IOException e) {
            LOGGER.warn("Heroic Map: Karte zum selbst Zeichnen nicht angelegt", e);
            return Component.translatable("heroicmap.selbst.fehler");
        }
        neuGewaehlt(mc);
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
            aufgegeben = false;
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
            // Erst, wenn feststeht, welcher Server es ist; sonst zeichnete sie vielleicht in die Karte eines anderen.
            if (!Downloads.INSTANZ.serverFest()) {
                return null;
            }
            gesucht = true;
            String wer = Downloads.INSTANZ.wer();
            Satz s = level.dimensionType().hasCeiling() ? null : Satz.fuer(weltOrdner(), dimension(level), wer);
            // Nie in das Ziel eines Zeigers: nur ein Baum im eigenen Ordner der Welt. Siehe docs/selbst.md, „Wahl“.
            satz = s != null && gehoert(s.ordner().getParent(), wer) && s.ordner().getParent().getParent().equals(weltOrdner()) ? s : null;
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
        Satz s = satz;
        ChunkMaler.Auftrag auftrag = ChunkMaler.abziehen(level, chunk, s.massstab(), Integer.MAX_VALUE, licht, Minimap.INSTANZ.texel(mc), 0);
        inArbeit.incrementAndGet();
        worker.execute(() -> {
            try {
                if (aufgegeben) {
                    return;
                }
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

    /**
     * Im Worker: das Werk für den Satz, die feinste Stufe die seines Massstabs; ein anderes schreibt
     * vorher, was es noch hat.
     */
    private Kachelwerk werk(Satz s) {
        if (werk == null || !werk.ordner().equals(s.ordner())) {
            schreibe(true);
            werk = new Kachelwerk(s.ordner(), s.kachel(), 16 * s.massstab(), s.minZoom(), s.stufe());
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
            if (werk.aufgegeben()) {
                gibAuf();
            }
        } finally {
            gemeldet(fertig);
        }
    }

    /**
     * Im Worker, alle {@link #NEUBAU_MS}: ein Schritt des Neubaus der gröberen Stufen eines alten Satzes, nur wenn
     * kein Chunk wartet; das Zeichnen geht vor. Siehe docs/selbst.md, „Pyramide“.
     */
    private void baueNeu() {
        if (werk == null || aufgegeben || inArbeit.get() > 0 || !werk.neubauOffen()) {
            return;
        }
        List<Kachelwerk.Kachel> fertig = new ArrayList<>();
        try {
            werk.baueNeu(NEUBAU_KACHELN, fertig);
        } catch (IOException | RuntimeException e) {
            melde(e);
            if (werk.aufgegeben()) {
                gibAuf();
            }
        } finally {
            gemeldet(fertig);
        }
    }

    /** Der offenen Karte melden, was geschrieben ist; sie lädt es neu. */
    private void gemeldet(List<Kachelwerk.Kachel> fertig) {
        if (!fertig.isEmpty()) {
            Path ordner = werk.ordner();
            Minecraft.getInstance().execute(() -> fertig.forEach(k -> Kacheln.geaendert(ordner, k.z(), k.x(), k.y())));
        }
    }

    /**
     * Im Worker, wenn das Werk aufgegeben hat: nicht mehr zeichnen und es dem Spieler sagen. Bis zum
     * nächsten Wechsel der Welt bleibt es so. Siehe docs/selbst.md, „Kacheln“.
     */
    private void gibAuf() {
        aufgegeben = true;
        werk = null;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            satz = null;
            gesucht = true;
            offen.clear();
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.translatable("heroicmap.selbst.schreibfehler"));
            }
        });
    }

    /** Der erste Fehler steht mit Stacktrace im Log, jeder weitere nicht; im Worker. */
    private void melde(Exception e) {
        if (!gemeldet) {
            gemeldet = true;
            LOGGER.warn("Heroic Map: Chunk für die selbst gezeichnete Karte nicht gezeichnet oder geschrieben", e);
        }
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
