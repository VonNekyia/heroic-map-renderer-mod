package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.joml.Matrix3x2fStack;
import org.slf4j.Logger;

/**
 * Die Minimap im HUD: eckig oder rund, genordet, der Spieler in der Mitte. Gezeichnet und behalten wird
 * der sichtbare Bereich plus {@link #VORRAT} Chunks. Chunks, die sich ändern, zeichnet ein
 * Worker nach, die nächsten zuerst; der Render-Thread zieht sie ab und übernimmt die Bilder,
 * je Frame höchstens {@link #BUDGET_NS}.
 * Siehe docs/minimap.md.
 */
public final class Minimap {

    public static final Minimap INSTANZ = new Minimap();
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Chunks je Seite einer Region, einer Textur. */
    static final int CHUNKS_JE_REGION = 8;
    /** Zeit je Frame auf dem Render-Thread für Abzüge und fertige Bilder. */
    static final long BUDGET_NS = 2_000_000L;
    /** So viele Chunks hat der Worker höchstens vor sich. */
    private static final int IN_ARBEIT = 4;
    /** Seite der Minimap in Einheiten des GUI: Vorgabe, kleinste, grösste. Ein Pixel der Minimap ist eine Einheit. */
    static final int GROESSE = 128, KLEINSTE = 64, GROESSTE = 256;
    /** Chunks je Richtung über den sichtbaren Bereich hinaus. */
    static final int VORRAT = 2;
    static final int RAND = 4;
    /** Seite eines Kopfes der Mitspieler in Einheiten des GUI. */
    static final int KOPF = 8;
    /** Ändert sich die Höhe des Kopfes unter einer Decke um so viele Blöcke, wird neu gezeichnet. */
    private static final int DECKE_SCHRITT = 2;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Heroic Map Minimap");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    /** Gehört dem Worker; nur er legt ihn an und ruft ihn. */
    private ChunkMaler maler;

    private boolean sichtbar = true;
    /** Auflösung, die der Spieler gewählt hat: höchstens so viele Pixel je Block, 1, 2, 4, 8 oder 16. */
    private int aufloesung = 2;
    /** Pixel je Block in den Texturen, wie gezeichnet: siehe {@link #effektiv}. */
    private int scale = 2;
    /** Zoom: Einheiten des GUI je Block, 1, 2, 4 oder 8; wie viel Gegend die Minimap zeigt. */
    private int zoom = 2;
    private boolean rund;
    /** Die Wahl {@code show}: Mitspieler über Simple Voice Chat zeigen und gezeigt werden, oder versteckt. */
    private boolean show = true;
    private int groesse = GROESSE;
    /** Die Lage im freien Platz des Schirms: 0 links oder oben, 1 rechts oder unten. */
    private float lageX = 1, lageY = 0;
    final LongLinkedOpenHashSet offen = new LongLinkedOpenHashSet();
    /** Chunks im Bereich, deren Bild in der Textur steht. */
    private final LongOpenHashSet gezeichnet = new LongOpenHashSet();
    /** Zahl der übernommenen Bilder, für die Messung. */
    private long uebernommen;
    private final Long2ObjectMap<Region> regionen = new Long2ObjectOpenHashMap<>();
    private final ArrayDeque<CompletableFuture<Bild>> laufend = new ArrayDeque<>();
    /** Zählt jedes Leeren mit; ein Bild aus einem älteren Stand fällt weg. */
    private long stand;
    /** Der Chunk des Spielers, um den der Bereich liegt; null, bis er wieder bekannt ist. */
    ChunkPos mitte;
    private Licht licht;
    private int decke = Integer.MAX_VALUE;
    /** Der Biomübergang, mit dem gezeichnet ist; -1, solange keiner. */
    private int mischung = -1;
    /** Die Texel des Block-Atlas und die Liste der Sprites, aus der sie stammen. */
    private Map<TextureAtlasSprite, ChunkMaler.Texel> texel;
    private List<TextureAtlasSprite> atlasStand;

    Minimap() {
    }

    /** Ein fertiges Bild des Workers. */
    private record Bild(int cx, int cz, int scale, long stand, int[] pixel) {
    }

    /** Eine Textur über {@link #CHUNKS_JE_REGION}² Chunks. */
    private static final class Region {
        final Identifier id;
        final DynamicTexture textur;

        Region(int rx, int rz, int scale) {
            int seite = CHUNKS_JE_REGION * 16 * scale;
            id = Identifier.fromNamespaceAndPath(HeroicMap.ID, "region/" + rx + "_" + rz);
            textur = new DynamicTexture(() -> "heroicmap " + id, seite, seite, true);
            // Einmal ganz und leer; danach nur noch die Bilder einzelner Chunks.
            textur.upload();
            Minecraft.getInstance().getTextureManager().register(id, textur);
        }

        void schliessen() {
            Minecraft.getInstance().getTextureManager().release(id);
        }
    }

    /** Der Chunk (x, z) ist neu zu zeichnen, wenn er im Bereich liegt. */
    public void markiere(int x, int z) {
        if (mitte == null || imBereich(x, z)) {
            offen.add(ChunkPos.pack(x, z));
        }
    }

    void umschalten() {
        setzeSichtbar(!sichtbar);
    }

    /**
     * Zeigt oder verbirgt die Minimap. Verborgen läuft {@code arbeite} nicht, die Mitte veraltet;
     * beim Zeigen ist sie deshalb unbekannt, bis der nächste Frame den Bereich anpasst.
     */
    void setzeSichtbar(boolean sichtbar) {
        if (sichtbar && !this.sichtbar) {
            mitte = null;
        }
        this.sichtbar = sichtbar;
    }

    boolean sichtbar() {
        return sichtbar;
    }

    /** Zoom 1, 2, 4 oder 8 Einheiten je Block, der Reihe nach. */
    void naechsterZoom() {
        setzeZoom(zoom == 8 ? 1 : zoom * 2);
    }

    /** Ein anderer Zoom ändert nur den Bereich; die Texturen behalten ihre Auflösung. */
    void setzeZoom(int zoom) {
        if (zoom != this.zoom) {
            this.zoom = zoom;
            mitte = null;
        }
    }

    int zoom() {
        return zoom;
    }

    /**
     * Die Obergrenze der Auflösung. Eine andere Wahl leert gleich, damit {@link #fertig} bis zum
     * nächsten Frame nicht wahr bleibt; passt danach die wirkliche nicht, leert {@code arbeite} noch einmal.
     */
    void setzeScale(int aufloesung) {
        if (aufloesung != this.aufloesung) {
            this.aufloesung = aufloesung;
            leeren();
        }
    }

    int aufloesung() {
        return aufloesung;
    }

    int scale() {
        return scale;
    }

    /**
     * Die Auflösung, mit der gezeichnet wird: die grösste Zweierpotenz von Pixeln je Block bis zur
     * gewählten, die in die Pixel eines Blocks auf dem Schirm, Zoom mal GUI-Massstab, ganz aufgeht.
     * So wird nie verkleinert, also nie unscharf, und jeder Texel ist gleich gross.
     * Siehe docs/minimap.md, „Bedienung“.
     */
    static int effektiv(int aufloesung, int zoom, int guiMassstab) {
        int schirm = zoom * guiMassstab;
        for (int r = aufloesung; r > 1; r /= 2) {
            if (schirm % r == 0) {
                return r;
            }
        }
        return 1;
    }

    boolean show() {
        return show;
    }

    void setzeShow(boolean show) {
        this.show = show;
    }

    boolean rund() {
        return rund;
    }

    void setzeRund(boolean rund) {
        this.rund = rund;
    }

    /** Lage und Seite auf dem Schirm, in Einheiten des GUI. */
    record Rahmen(int x, int y, int seite) {

        boolean enthaelt(double mx, double my) {
            return mx >= x && mx < x + seite && my >= y && my < y + seite;
        }
    }

    Rahmen rahmen(int breite, int hoehe) {
        return rahmen(breite, hoehe, groesse, lageX, lageY);
    }

    /** Die Seite höchstens so gross, wie der Schirm erlaubt; die Lage verteilt den freien Platz. */
    static Rahmen rahmen(int breite, int hoehe, int groesse, float lageX, float lageY) {
        int seite = Math.max(1, Math.min(groesse, Math.min(breite, hoehe) - 2 * RAND));
        return new Rahmen(RAND + Math.round(lageX * (breite - seite - 2 * RAND)),
                RAND + Math.round(lageY * (hoehe - seite - 2 * RAND)), seite);
    }

    /** Gibt der Minimap die Seite und legt sie dann wie {@link #verschiebe}. */
    void stelle(int x, int y, int seite, int breite, int hoehe) {
        int neu = Mth.clamp(seite, KLEINSTE, GROESSTE);
        if (neu != groesse) {
            groesse = neu;
            // Die Reichweite ändert sich; der nächste Frame passt den Bereich an.
            mitte = null;
        }
        verschiebe(x, y, breite, hoehe);
    }

    /** Legt die Minimap mit der linken oberen Ecke auf (x, y), soweit sie auf den Schirm passt. */
    void verschiebe(int x, int y, int breite, int hoehe) {
        int s = rahmen(breite, hoehe).seite();
        lageX = anteil(x - RAND, breite - s - 2 * RAND);
        lageY = anteil(y - RAND, hoehe - s - 2 * RAND);
    }

    private static float anteil(int wert, int platz) {
        return platz <= 0 ? 0 : Mth.clamp(wert / (float) platz, 0, 1);
    }

    /** Liest die Einstellungen beim Start; fehlt die Datei oder ein Wert, gilt die Vorgabe. */
    void lies(Path datei) {
        Properties p = new Properties();
        if (Files.exists(datei)) {
            try (Reader rein = Files.newBufferedReader(datei)) {
                p.load(rein);
            } catch (IOException | IllegalArgumentException e) {
                LOGGER.warn("Heroic Map: Einstellungen {} nicht lesbar, es gilt die Vorgabe", datei, e);
            }
        }
        sichtbar = !"false".equals(p.getProperty("minimap"));
        int s = zahl(p.getProperty("massstab"), 2);
        aufloesung = s == 1 || s == 4 || s == 8 || s == 16 ? s : 2;
        // Vor dem Zoom galt der Massstab für beides; ohne zoom bleibt der Ausschnitt so.
        int z = zahl(p.getProperty("zoom"), aufloesung);
        zoom = z == 1 || z == 4 || z == 8 ? z : 2;
        rund = "rund".equals(p.getProperty("form"));
        show = !"hidden".equals(p.getProperty("show"));
        groesse = Mth.clamp(zahl(p.getProperty("groesse"), GROESSE), KLEINSTE, GROESSTE);
        lageX = bruch(p.getProperty("lage_x"), 1);
        lageY = bruch(p.getProperty("lage_y"), 0);
    }

    void schreibe(Path datei) {
        Properties p = new Properties();
        p.setProperty("minimap", Boolean.toString(sichtbar));
        p.setProperty("massstab", Integer.toString(aufloesung));
        p.setProperty("zoom", Integer.toString(zoom));
        p.setProperty("form", rund ? "rund" : "eckig");
        p.setProperty("show", show ? "simplevoicechat" : "hidden");
        p.setProperty("groesse", Integer.toString(groesse));
        p.setProperty("lage_x", Float.toString(lageX));
        p.setProperty("lage_y", Float.toString(lageY));
        try {
            Files.createDirectories(datei.getParent());
            try (Writer raus = Files.newBufferedWriter(datei)) {
                p.store(raus, "Heroic Map");
            }
        } catch (IOException e) {
            LOGGER.warn("Heroic Map: Einstellungen {} nicht geschrieben", datei, e);
        }
    }

    private static int zahl(String text, int vorgabe) {
        try {
            return text == null ? vorgabe : Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return vorgabe;
        }
    }

    private static float bruch(String text, float vorgabe) {
        try {
            float f = text == null ? vorgabe : Float.parseFloat(text.trim());
            return Float.isNaN(f) ? vorgabe : Mth.clamp(f, 0, 1);
        } catch (NumberFormatException e) {
            return vorgabe;
        }
    }

    long uebernommen() {
        return uebernommen;
    }

    /** Ist nichts mehr nachzuzeichnen? */
    boolean fertig() {
        return mitte != null && offen.isEmpty() && laufend.isEmpty();
    }

    /** Chunks je Richtung um den Spieler, die die Minimap zeichnet: sichtbar plus Vorrat. */
    int reichweite() {
        return reichweite(zoom, groesse);
    }

    static int reichweite(int zoom, int groesse) {
        return Mth.ceil(groesse / 2f / (16f * zoom)) + VORRAT;
    }

    private boolean imBereich(int x, int z) {
        int r = reichweite();
        return Math.abs(x - mitte.x()) <= r && Math.abs(z - mitte.z()) <= r;
    }

    /**
     * Vergisst alles, etwa beim Wechsel der Welt oder beim Trennen. Bilder, die noch laufen,
     * fallen weg; mit der nächsten Mitte wird der Bereich neu markiert.
     */
    void leeren() {
        regionen.values().forEach(Region::schliessen);
        regionen.clear();
        offen.clear();
        gezeichnet.clear();
        mitte = null;
        licht = null;
        stand++;
    }

    /** Zeichnet den Bereich neu, ohne die Texturen zu leeren. */
    private void neuZeichnen() {
        gezeichnet.clear();
        mitte = null;
    }

    void zeichne(GuiGraphicsExtractor g, DeltaTracker zeit) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer spieler = mc.player;
        ClientLevel level = mc.level;
        if (!sichtbar || spieler == null || level == null) {
            return;
        }
        arbeite(mc, level, spieler);

        Rahmen r = rahmen(g.guiWidth(), g.guiHeight());
        int links = Mth.floor(Projektion.zuPixel(spieler.getX(), zoom)) - r.seite() / 2;
        int oben = Mth.floor(Projektion.zuPixel(spieler.getZ(), zoom)) - r.seite() / 2;
        male(g, r, links, oben, mc.getWindow().getGuiScale());
        mitspieler(g, mc, r, spieler, level);
        avatar(g, spieler, r.x() + r.seite() / 2, r.y() + r.seite() / 2);
    }

    /** Die Mitspieler als Köpfe, in derselben Dimension und innerhalb der Form. Siehe docs/minimap.md, „Mitspieler“. */
    private void mitspieler(GuiGraphicsExtractor g, Minecraft mc, Rahmen r, LocalPlayer spieler, ClientLevel level) {
        String dimension = level.dimension().identifier().toString();
        double h = r.seite() / 2.0, mx = spieler.getX(), mz = spieler.getZ();
        for (Mitspieler.Eintrag e : Mitspieler.INSTANZ.sichtbar(System.currentTimeMillis())) {
            if (!e.dimension().equals(dimension) || e.uuid().equals(spieler.getUUID())) {
                continue;
            }
            double[] lage = Mitspieler.lage(mc, e);
            double px = (lage[0] - mx) * zoom, pz = (lage[1] - mz) * zoom;
            if (rund ? px * px + pz * pz <= h * h : Math.abs(px) <= h && Math.abs(pz) <= h) {
                Mitspieler.kopf(g, mc, e, (int) Math.round(r.x() + h + px), (int) Math.round(r.y() + h + pz), KOPF);
            }
        }
    }

    /**
     * Zeichnet Rand und Regionen in Pixeln des Schirms, Lauf für Lauf der Form; eckig ist das ein
     * einziger Lauf. Siehe docs/minimap.md, „Form“.
     */
    private void male(GuiGraphicsExtractor g, Rahmen r, int links, int oben, int k) {
        // Eine Region in Einheiten des GUI nach dem Zoom; die Textur trifft sie über die Koordinaten 0 bis 1.
        int seite = CHUNKS_JE_REGION * 16 * zoom;
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.scale(1f / k);
        int x0 = r.x() * k, y0 = r.y() * k, n = r.seite() * k;
        for (int[] l : laeufe(n + 2 * k, rund)) {
            g.fill(x0 - k + l[2], y0 - k + l[0], x0 - k + l[3], y0 - k + l[1], 0xFF000000);
        }
        List<int[]> form = laeufe(n, rund);
        int s = seite * k;
        for (int rz = Math.floorDiv(oben, seite); rz <= Math.floorDiv(oben + r.seite() - 1, seite); rz++) {
            for (int rx = Math.floorDiv(links, seite); rx <= Math.floorDiv(links + r.seite() - 1, seite); rx++) {
                Region region = regionen.get(ChunkPos.pack(rx, rz));
                if (region == null) {
                    continue;
                }
                int qx = x0 + (rx * seite - links) * k, qy = y0 + (rz * seite - oben) * k;
                // Je Region alle Läufe nacheinander, so bleibt es ein Stapel je Textur.
                for (int[] l : form) {
                    int ya = Math.max(y0 + l[0], qy), yb = Math.min(y0 + l[1], qy + s);
                    int xa = Math.max(x0 + l[2], qx), xb = Math.min(x0 + l[3], qx + s);
                    if (ya < yb && xa < xb) {
                        g.blit(region.id, xa, ya, xb, yb, (xa - qx) / (float) s, (xb - qx) / (float) s,
                                (ya - qy) / (float) s, (yb - qy) / (float) s);
                    }
                }
            }
        }
        pose.popMatrix();
    }

    /**
     * Die Zeilen einer Form mit der Seite n, zu Läufen gleicher Breite zusammengefasst, je Lauf
     * {y0, y1, x0, x1}, Ende ausschliesslich. Rund ist es der Kreis in das Quadrat, sonst das Quadrat.
     */
    static List<int[]> laeufe(int n, boolean rund) {
        List<int[]> laeufe = new ArrayList<>();
        if (!rund) {
            laeufe.add(new int[] {0, n, 0, n});
            return laeufe;
        }
        int[] lauf = null;
        for (int y = 0; y < n; y++) {
            int a = sehne(n, y);
            if (a >= n - a) {
                lauf = null;
            } else if (lauf != null && lauf[2] == a) {
                lauf[1] = y + 1;
            } else {
                lauf = new int[] {y, y + 1, a, n - a};
                laeufe.add(lauf);
            }
        }
        return laeufe;
    }

    /** Linker Rand der Zeile y im Kreis in ein Quadrat mit der Seite n, auf ganze Pixel; der rechte ist n minus dieser. */
    static int sehne(int n, int y) {
        double h = n / 2.0, dy = y + 0.5 - h;
        return (int) Math.round(h - Math.sqrt(Math.max(0, h * h - dy * dy)));
    }

    /**
     * Übernimmt fertige Bilder und gibt dem Worker neue Chunks, die nächsten zuerst, bis das
     * Budget des Frames aufgebraucht ist.
     */
    private void arbeite(Minecraft mc, ClientLevel level, LocalPlayer spieler) {
        pruefeAtlas(mc);
        int soll = effektiv(aufloesung, zoom, mc.getWindow().getGuiScale());
        if (soll != scale) {
            scale = soll;
            leeren();
        }
        int radius = mc.options.biomeBlendRadius().get();
        if (radius != mischung) {
            // Ein anderer Biomübergang läuft über allChanged, nicht über setSectionDirty.
            if (mischung != -1) {
                neuZeichnen();
            }
            mischung = radius;
        }
        if (licht == null) {
            licht = Licht.von(level.dimensionType());
        }
        if (level.dimensionType().hasCeiling()) {
            int kopf = Mth.floor(spieler.getEyeY());
            if (Math.abs(kopf - decke) >= DECKE_SCHRITT) {
                decke = kopf;
                neuZeichnen();
            }
        } else {
            decke = Integer.MAX_VALUE;
        }
        ChunkPos jetzt = spieler.chunkPosition();
        if (!jetzt.equals(mitte)) {
            mitte = jetzt;
            nachBereich();
        }
        long ende = System.nanoTime() + BUDGET_NS;
        while (!laufend.isEmpty() && laufend.peek().isDone() && System.nanoTime() < ende) {
            uebernimm(laufend.poll());
        }
        while (laufend.size() < IN_ARBEIT && !offen.isEmpty() && System.nanoTime() < ende) {
            long naechster = naechster();
            offen.remove(naechster);
            LevelChunk chunk = level.getChunkSource()
                    .getChunk(ChunkPos.getX(naechster), ChunkPos.getZ(naechster), ChunkStatus.FULL, false);
            if (chunk == null) {
                continue;
            }
            ChunkMaler.Auftrag auftrag = ChunkMaler.abziehen(level, chunk, scale, decke, licht, texel, stand);
            laufend.add(CompletableFuture.supplyAsync(() -> {
                if (maler == null) {
                    maler = new ChunkMaler();
                }
                return new Bild(auftrag.cx(), auftrag.cz(), auftrag.scale(), auftrag.stand(), maler.male(auftrag));
            }, worker));
        }
    }

    /**
     * Passt alles an die neue Mitte an: Was den Bereich verlassen hat, fällt weg; was neu in
     * ihm liegt und kein Bild hat, wird markiert.
     */
    private void nachBereich() {
        int r = reichweite();
        offen.removeIf((long k) -> !imBereich(ChunkPos.getX(k), ChunkPos.getZ(k)));
        gezeichnet.removeIf((long k) -> !imBereich(ChunkPos.getX(k), ChunkPos.getZ(k)));
        regionen.long2ObjectEntrySet().removeIf(e -> {
            int rx = ChunkPos.getX(e.getLongKey()), rz = ChunkPos.getZ(e.getLongKey());
            boolean draussen = rx * CHUNKS_JE_REGION > mitte.x() + r || (rx + 1) * CHUNKS_JE_REGION - 1 < mitte.x() - r
                    || rz * CHUNKS_JE_REGION > mitte.z() + r || (rz + 1) * CHUNKS_JE_REGION - 1 < mitte.z() - r;
            if (draussen) {
                e.getValue().schliessen();
            }
            return draussen;
        });
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                long k = ChunkPos.pack(mitte.x() + dx, mitte.z() + dz);
                if (!gezeichnet.contains(k)) {
                    offen.add(k);
                }
            }
        }
    }

    /** Kopiert die Texel neu, wenn der Block-Atlas neu geladen ist, und zeichnet alles neu. */
    private void pruefeAtlas(Minecraft mc) {
        TextureAtlas atlas = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        if (atlas.sprites == atlasStand) {
            return;
        }
        boolean nachgeladen = atlasStand != null;
        texel = ChunkMaler.Texel.vomAtlas(atlas);
        atlasStand = atlas.sprites;
        if (nachgeladen) {
            leeren();
        }
    }

    /** Schreibt das Bild eines Chunks in seine Region, nur diesen Ausschnitt auf die GPU. */
    private void uebernimm(CompletableFuture<Bild> fertig) {
        Bild bild;
        try {
            bild = fertig.join();
        } catch (CompletionException e) {
            LOGGER.warn("Minimap: Chunk nicht gezeichnet", e.getCause());
            return;
        }
        if (bild.stand() != stand || bild.scale() != scale || mitte == null || !imBereich(bild.cx(), bild.cz())) {
            return;
        }
        Region region = regionen.computeIfAbsent(ChunkPos.pack(bild.cx() >> 3, bild.cz() >> 3),
                k -> new Region(ChunkPos.getX(k), ChunkPos.getZ(k), scale));
        int seite = 16 * scale, ox = (bild.cx() & 7) * seite, oy = (bild.cz() & 7) * seite;
        try (NativeImage stueck = new NativeImage(seite, seite, false)) {
            for (int y = 0; y < seite; y++) {
                for (int x = 0; x < seite; x++) {
                    stueck.setPixel(x, y, bild.pixel()[y * seite + x]);
                }
            }
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(region.textur.getTexture(), stueck, 0, 0, ox, oy);
        }
        gezeichnet.add(ChunkPos.pack(bild.cx(), bild.cz()));
        uebernommen++;
    }

    // ponytail: sucht linear, im Bereich liegen höchstens 169 Chunks; sonst ein Heap.
    private long naechster() {
        long bester = offen.firstLong();
        int besteWeite = Integer.MAX_VALUE;
        for (LongIterator it = offen.iterator(); it.hasNext(); ) {
            long k = it.nextLong();
            int dx = ChunkPos.getX(k) - mitte.x(), dz = ChunkPos.getZ(k) - mitte.z();
            int w = dx * dx + dz * dz;
            if (w < besteWeite) {
                besteWeite = w;
                bester = k;
            }
        }
        return bester;
    }

    /** Der eigene Spieler: sein Kopf aus dem Skin, daneben ein kleiner Pfeil in Blickrichtung. Siehe docs/minimap.md, „Bedienung“. */
    static void avatar(GuiGraphicsExtractor g, AbstractClientPlayer spieler, int x, int y) {
        int h = KOPF / 2;
        g.fill(x - h - 1, y - h - 1, x + h + 1, y + h + 1, 0xFF000000);
        PlayerFaceExtractor.extractRenderState(g, spieler.getSkin(), x - h, y - h, KOPF);
        // Der Pfeil kreist um den Kopf; bei Gier 0 blickt der Spieler nach Süden, auf der Karte nach unten.
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.rotate((float) Math.toRadians(spieler.getYRot() + 180));
        pose.translate(0, -(h + 1));
        for (int i = 0; i < 3; i++) {
            g.fill(-i - 1, -3 + i, i + 2, -1 + i, 0xFF000000);
        }
        for (int i = 0; i < 3; i++) {
            g.fill(-i, -2 + i, i + 1, -1 + i, 0xFFFFFFFF);
        }
        pose.popMatrix();
    }
}
