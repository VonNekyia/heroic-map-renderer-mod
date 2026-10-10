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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.joml.Matrix3x2f;
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

    /** Norden, Osten, Süden, Westen als Richtung im Bild, x nach Osten, y nach Süden; gezeichnet in der Reihenfolge MARKEN, N zuletzt. */
    private static final double[][] RICHTUNGEN = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
    private static final int[] MARKEN = {1, 2, 3, 0};
    /** Farbe der Chunklinien: Schwarz, zu 30 % deckend, so bleibt die Karte darunter lesbar. */
    static final int LINIE = 0x4D000000;
    private static final int TEXT = 0xFFFFFFFF;
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
    /**
     * Seite eines Kopfes oder Wegpunkts in Einheiten des GUI: so auf der Vollbildkarte und auf
     * der Minimap mit der Vorgabe von 128 Einheiten; auf der Minimap wächst sie mit deren Seite.
     */
    static final int KOPF = 6;
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
    /** Chunklinien auf Minimap und Vollbildkarte, eine Vorliebe aus dem Untermenü. Siehe docs/minimap.md, „Chunklinien“. */
    private boolean chunklinien;
    private final Formen.Speicher formenSpeicher = new Formen.Speicher();
    /** Koordinaten unter der Minimap. Siehe docs/minimap.md, „Koordinaten“. */
    enum Koordinaten { AUS, XZ, XYZ }

    private Koordinaten koordinaten = Koordinaten.XZ;
    /** Dreht die Minimap mit der Blickrichtung, die oben liegt. Siehe docs/minimap.md, „Drehen“. */
    private boolean drehen = true;
    /** Hat der Spieler Drehen selbst gewählt? Nur dann steht es in der Datei, sonst gilt die Vorgabe. */
    private boolean drehenGewaehlt;
    /** Der Rahmen, ein Name aus {@link Skin#NAMEN}; „ohne“ ist der Umriss. Siehe docs/rahmen.md. */
    private String skin = Skin.BIOM;
    private boolean skinGewaehlt;
    private Biom biom = new Biom();
    /** Solange das Menü offen ist: die Ecke des Griffs, 0 bis 3, sonst -1; und ob die Maus auf ihm liegt. */
    private int griffEcke = -1;
    private boolean griffAktiv;
    /** Die Form der Karte ({@link #schnitt(Rahmen, int, Skin)}) und wofür. */
    private float[] schnitt;
    private int schnittX, schnittY, schnittSeite, schnittMassstab, schnittBaender;
    private boolean schnittRund;
    /** Zwischenspeicher für die Vielecke der Regionen, auf dem Render-Thread. */
    private final Drehung.Puffer puffer = new Drehung.Puffer();
    /** Der Ring des Umrisses ({@link #umrissRing}) und wofür. */
    private Identifier umrissTextur;
    private int umrissSeite, umrissMassstab;
    /** Die zuletzt gerechneten Ecken der Ornamente und wofür. */
    private Skin eckenSkin;
    private Rahmen eckenRahmen;
    private boolean eckenRund;
    private double[][] ecken;
    private int groesse = GROESSE;
    /** Wie die Ordner der Welten heissen, siehe docs/download.md, „Ablage“. */
    private Downloads.Ablage ablage = Downloads.Ablage.IP;
    /** Die Lage im freien Platz des Schirms: 0 links oder oben, 1 rechts oder unten. */
    private float lageX = 1, lageY = 0;
    final LongLinkedOpenHashSet offen = new LongLinkedOpenHashSet();
    /** Chunks im Bereich, deren Bild in der Textur steht. */
    private final LongOpenHashSet gezeichnet = new LongOpenHashSet();
    /** Zahl der übernommenen Bilder, für die Messung. */
    private long uebernommen;
    /** Wann {@code arbeite} zuletzt lief, in ns; ohne HUD läuft es nicht. */
    private long gearbeitet;
    /** Nur für den Gametest: gilt immer als beschäftigt. Siehe docs/selbst.md, „Bild“. */
    private volatile boolean beschaeftigtFuerTest;
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

    boolean chunklinien() {
        return chunklinien;
    }

    void setzeChunklinien(boolean chunklinien) {
        this.chunklinien = chunklinien;
    }

    Koordinaten koordinaten() {
        return koordinaten;
    }

    void setzeKoordinaten(Koordinaten koordinaten) {
        this.koordinaten = koordinaten;
    }

    String skin() {
        return skin;
    }

    void setzeSkin(String skin) {
        this.skin = Skin.NAMEN.contains(skin) ? skin : Skin.BIOM;
        skinGewaehlt = true;
    }

    /** Der Skin, den die Minimap gerade zeichnet, bei „biom“ der der gezeigten Kategorie; null ohne Rahmen. */
    Skin skinJetzt() {
        return Skin.von(Skin.BIOM.equals(skin) ? Biom.ORDNER.get(biom.gezeigt()) : skin);
    }

    /** Vom Menü je Frame: wo der Griff liegt, -1 ohne Menü. Mit Skin zeichnet die Minimap ihn statt der zier. */
    void griff(int ecke, boolean aktiv) {
        griffEcke = ecke;
        griffAktiv = aktiv;
    }

    void setzeShow(boolean show) {
        this.show = show;
    }

    boolean rund() {
        return rund;
    }

    Downloads.Ablage ablage() {
        return ablage;
    }

    void setzeAblage(Downloads.Ablage ablage) {
        this.ablage = ablage;
    }

    void setzeRund(boolean rund) {
        if (drehen && rund != this.rund) {
            // Die Reichweite ändert sich mit der Form; der nächste Frame passt den Bereich an.
            mitte = null;
        }
        this.rund = rund;
    }

    boolean drehen() {
        return drehen;
    }

    void setzeDrehen(boolean drehen) {
        if (drehen != this.drehen) {
            mitte = null;
        }
        this.drehen = drehen;
        drehenGewaehlt = true;
    }

    /** Lage und Seite auf dem Schirm, in Einheiten des GUI. */
    record Rahmen(int x, int y, int seite) {

        boolean enthaelt(double mx, double my) {
            return mx >= x && mx < x + seite && my >= y && my < y + seite;
        }
    }

    Rahmen rahmen(int breite, int hoehe) {
        return rahmen(breite, hoehe, groesse, lageX, lageY, rand());
    }

    /** Der Abstand zum Rand des Schirms: {@link #RAND}, mit Rahmen mindestens dessen Einrückung, so bleibt die zier ganz auf dem Schirm. */
    int rand() {
        if (!Skin.BIOM.equals(skin)) {
            return rand(Skin.von(skin));
        }
        // Der grösste Abstand aller Kategorien: So springt die Minimap beim Wechsel nicht.
        int rand = RAND;
        for (String ordner : Biom.ORDNER.values()) {
            rand = Math.max(rand, rand(Skin.von(ordner)));
        }
        return rand;
    }

    static int rand(Skin rahmen) {
        return rahmen == null ? RAND : Math.max(RAND, rahmen.einrueckung());
    }

    /** Die Seite höchstens so gross, wie der Schirm erlaubt; die Lage verteilt den freien Platz. */
    static Rahmen rahmen(int breite, int hoehe, int groesse, float lageX, float lageY, int rand) {
        int seite = Math.max(1, Math.min(groesse, Math.min(breite, hoehe) - 2 * rand));
        return new Rahmen(rand + Math.round(lageX * (breite - seite - 2 * rand)),
                rand + Math.round(lageY * (hoehe - seite - 2 * rand)), seite);
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
        int s = rahmen(breite, hoehe).seite(), rand = rand();
        lageX = anteil(x - rand, breite - s - 2 * rand);
        lageY = anteil(y - rand, hoehe - s - 2 * rand);
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
        chunklinien = "true".equals(p.getProperty("chunklinien"));
        koordinaten = switch (String.valueOf(p.getProperty("koordinaten")).trim()) {
            case "aus" -> Koordinaten.AUS;
            case "xyz" -> Koordinaten.XYZ;
            default -> Koordinaten.XZ;
        };
        // Vorgabe an. Ein altes drehen=true war gewählt, denn die Vorgabe war aus; ein altes drehen=false nicht unterscheidbar.
        String wahl = p.getProperty("drehen_wahl");
        drehenGewaehlt = wahl != null || "true".equals(p.getProperty("drehen"));
        drehen = wahl == null || "true".equals(wahl);
        // Vorgabe biom. Ein alter rahmen ausser „ohne“ war gewählt, denn die Vorgabe war ohne; ein altes rahmen=ohne nicht unterscheidbar.
        String rahmenWahl = p.getProperty("rahmen_wahl");
        String rahmen = String.valueOf(rahmenWahl != null ? rahmenWahl : p.getProperty("rahmen")).trim();
        skinGewaehlt = Skin.NAMEN.contains(rahmen) && (rahmenWahl != null || !Skin.OHNE.equals(rahmen));
        skin = skinGewaehlt ? rahmen : Skin.BIOM;
        ablage = switch (String.valueOf(p.getProperty("ablage")).trim()) {
            case "hash" -> Downloads.Ablage.HASH;
            case "ip_port" -> Downloads.Ablage.IP_PORT;
            default -> Downloads.Ablage.IP;
        };
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
        p.setProperty("chunklinien", Boolean.toString(chunklinien));
        p.setProperty("koordinaten", koordinaten.name().toLowerCase(Locale.ROOT));
        if (drehenGewaehlt) {
            p.setProperty("drehen_wahl", Boolean.toString(drehen));
        }
        if (skinGewaehlt) {
            p.setProperty("rahmen_wahl", skin);
        }
        p.setProperty("ablage", ablage.name().toLowerCase(Locale.ROOT));
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

    /**
     * Hat die Minimap zu tun? Dann gibt die selbst gezeichnete Karte höchstens einen Chunk je Tick
     * an ihren Worker. Ohne HUD, etwa mit F1, zeichnet die Minimap nicht; nach einer Sekunde ohne
     * Arbeit gilt sie nicht mehr als beschäftigt.
     */
    boolean beschaeftigt() {
        return beschaeftigtFuerTest || sichtbar && !fertig() && System.nanoTime() - gearbeitet < 1_000_000_000L;
    }

    void fuerTestBeschaeftigt(boolean an) {
        beschaeftigtFuerTest = an;
    }

    /** Die Texel des Block-Atlas, nach einem Neuladen neu kopiert; auf dem Render-Thread. */
    Map<TextureAtlasSprite, ChunkMaler.Texel> texel(Minecraft mc) {
        pruefeAtlas(mc);
        return texel;
    }

    /** Chunks je Richtung um den Spieler, die die Minimap zeichnet: sichtbar plus Vorrat. */
    int reichweite() {
        return reichweite(zoom, sicht(groesse, drehen, rund));
    }

    /** Wie viel Gegend die Minimap braucht, als Seite eines Quadrats: eckig und gedreht reicht sie in den Ecken √2 weiter. */
    static int sicht(int groesse, boolean drehen, boolean rund) {
        return drehen && !rund ? (int) Math.ceil(groesse * Math.sqrt(2)) : groesse;
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
        // Nach einem Wechsel der Welt oder Dimension gilt die erste Kategorie wieder sofort.
        biom = new Biom();
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
            gibUmrissFrei();
            return;
        }
        arbeite(mc, level, spieler);

        Rahmen r = rahmen(g.guiWidth(), g.guiHeight());
        float a = anteil(level, spieler, zeit);
        int k = mc.getWindow().getGuiScale(), n = r.seite() * k;
        int links = ecke(spieler.xo, spieler.getX(), a, zoom, k, n);
        int oben = ecke(spieler.zo, spieler.getZ(), a, zoom, k, n);
        long ms = Util.getMillis();
        // Ein Chunk, den der Client noch nicht hat, läse sich als Ebene; in einer Höhle bleibt die letzte Kategorie.
        if (Skin.BIOM.equals(skin) && level.hasChunkAt(spieler.blockPosition())) {
            String kategorie = biom.kategorie(level.getBiome(spieler.blockPosition()));
            if (kategorie != null) {
                biom.sieh(kategorie, ms);
            }
        }
        Skin rahmen = skinJetzt();
        Drehung.Lage lage = drehen ? lage(r, Mth.lerp(a, spieler.xo, spieler.getX()), Mth.lerp(a, spieler.zo, spieler.getZ()),
                spieler.getViewYRot(a), zoom, k, links, oben) : null;
        // Ungedreht liegt das Bild auf dem Raster der Karte, gedreht dreht es um den Spieler; der Weg ist derselbe.
        Drehung.Lage bild = lage != null ? lage : Drehung.Lage.von(0, r.x() * k, r.y() * k, 0, 0);
        double weit = weit(n, k);
        double[] bereich = lage != null ? new double[] {lage.px() - weit, lage.py() - weit, lage.px() + weit, lage.py() + weit}
                : new double[] {0, 0, n, n};
        float[] form = schnitt(r, k, rahmen);
        male(g, r, links, oben, k, bild, bereich, form, rahmen == null);
        if (chunklinien) {
            linien(g, r, links, oben, k, bild, bereich, form);
        }
        String dimension = level.dimension().identifier().toString();
        formen(g, r, links, oben, k, bild, bereich, form, dimension);
        // Vor Ring und Rahmen: Was am Rand über sie ragt, decken sie.
        nadeln(g, mc.font, r, dimension, links, oben, k, lage, rahmen == null ? 0 : rahmen.baender());
        if (rund && rahmen == null) {
            // Nach Karte und Linien, ihr Vieleck ragt unter den Ring. Siehe docs/minimap.md, „Form“.
            Matrix3x2fStack pose = g.pose();
            pose.pushMatrix();
            pose.scale(1f / k);
            g.blit(umrissRing(n, k), r.x() * k - k, r.y() * k - k, r.x() * k + n + k, r.y() * k + n + k, 0, 1, 0, 1);
            pose.popMatrix();
        } else {
            gibUmrissFrei();
        }
        if (rahmen != null) {
            // Beim Wechsel des Bioms: Die Bänder sind in allen Kategorien gleich gebaut, die neuen decken die alten zu t;
            // nur die alten Ornamente blenden aus. So sinkt die Deckung der Bänder nie.
            String vorher = Skin.BIOM.equals(skin) ? biom.vorher(ms) : null;
            Skin alt = vorher == null ? null : Skin.von(Biom.ORDNER.get(vorher));
            float t = alt == null ? 1 : biom.anteil(ms);
            if (alt != null) {
                zeichneRahmen(g, alt, r, lage, 1, 1 - t);
            }
            zeichneRahmen(g, rahmen, r, lage, t, t);
        }
        float kopf = kopf(r.seite());
        wegpunkte(g, r, dimension, links, oben, k, kopf, lage);
        mitspieler(g, mc, r, spieler, dimension, links, oben, a, k, kopf, lage);
        // In der Mitte des Bildes, auf dem Pixel, den ecke dafür nimmt; gedreht genau in der Mitte, der Pfeil nach oben.
        if (lage == null) {
            avatar(g, spieler, (r.x() * k + n / 2) / (float) k, (r.y() * k + n / 2) / (float) k, a, kopf, false);
        } else {
            avatar(g, spieler, (float) (lage.cx() / k), (float) (lage.cy() / k), a, kopf, true);
        }
        Object[] werte = werte(koordinaten, spieler.getX(), spieler.getY(), spieler.getZ());
        if (werte != null) {
            Component text = Component.translatable(werte.length == 3 ? "heroicmap.koordinaten_xyz" : "heroicmap.koordinaten", werte);
            int breite = mc.font.width(text);
            // Unter dem Ring oder den Ornamenten des Rahmens, die halb über die Ecken ragen.
            int[] wo = koordinatenLage(r, rahmen == null ? 1 : rand(), g.guiHeight(), breite, mc.font.lineHeight);
            g.text(mc.font, text, wo[0], wo[1], TEXT, true);
        }
    }

    /** Die Zahlen unter der Minimap, die Blockkoordinaten des Spielers: keine, x und z, oder x, y und z. */
    static Object[] werte(Koordinaten k, double x, double y, double z) {
        return switch (k) {
            case AUS -> null;
            case XZ -> new Object[] {Mth.floor(x), Mth.floor(z)};
            case XYZ -> new Object[] {Mth.floor(x), Mth.floor(y), Mth.floor(z)};
        };
    }

    /**
     * Wo der Text der Koordinaten links oben beginnt: mittig unter der Minimap, {@code ueberstand}
     * Einheiten unter ihrem Rand und 2 Abstand; passt er dort nicht mehr auf den Schirm, ebenso darüber.
     */
    static int[] koordinatenLage(Rahmen r, int ueberstand, int schirmHoehe, int textBreite, int zeile) {
        int x = r.x() + (r.seite() - textBreite) / 2, unter = r.y() + r.seite() + ueberstand + 2;
        return new int[] {x, unter + zeile <= schirmHoehe ? unter : r.y() - ueberstand - 2 - zeile};
    }

    /** Die Seite eines Kopfes auf der Minimap: {@link #KOPF} bei 128 Einheiten, mit der Seite wachsend, mindestens 4. */
    static float kopf(int seite) {
        return Math.max(4, KOPF * seite / (float) GROESSE);
    }

    /**
     * Wie weit ein Punkt (px, pz) relativ zur Mitte auf seiner Richtung zur Mitte rückt, damit er
     * in der Form liegt: rund im Kreis mit Radius hx, sonst im Rechteck ±hx, ±hz. 1, wenn er
     * schon drinnen liegt. Siehe docs/wegpunkte.md, „Am Rand“.
     */
    static double rand(double px, double pz, double hx, double hz, boolean rund) {
        double f = rund ? hx / Math.hypot(px, pz) : Math.min(hx / Math.abs(px), hz / Math.abs(pz));
        return Math.min(1, f);
    }

    /**
     * Die Flächen, Kreise und Linien der sichtbaren Ebenen in dieser Dimension, in Pixeln des Schirms
     * mit {@code lage} wie die Karte und mit ihrer Form geschnitten; {@code bereich} ist, was vom Bild zu
     * sehen sein kann. Siehe docs/ebenen.md, „Flächen, Kreise und Linien“.
     */
    private void formen(GuiGraphicsExtractor g, Rahmen r, int links, int oben, int k, Drehung.Lage lage, double[] bereich, float[] form,
            String dimension) {
        int n = r.seite() * k;
        double block = (double) zoom * k;
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.scale(1f / k);
        Matrix3x2f kopie = new Matrix3x2f(pose);
        Formen.Ansicht a = new Formen.Ansicht(abbild(zoom, k, links, oben, lage), block, k, 1, form, new double[] {(bereich[0] + links) / block, (bereich[1] + oben) / block, (bereich[2] + links) / block,
                (bereich[3] + oben) / block}, kopie, new ScreenRectangle(r.x() * k, r.y() * k, n, n).transformMaxBounds(kopie),
                // Die Kartenschrift höchstens ein Zehntel der Seite hoch, sonst erschlüge sie die Karte.
                r.seite() / 10.0);
        List<List<Ebenen.Form>> ebenen = Ebenen.INSTANZ.sichtbar().stream().map(e -> Ebenen.INSTANZ.formen(e.id())).toList();
        Formen.zeichne(g, a, dimension, ebenen, formenSpeicher, Minecraft.getInstance().font);
        pose.popMatrix();
    }

    /** Wie die Formen einen Punkt der Welt in die Pixel der Minimap legen: wie die Karte, mit {@code lage} gedreht. */
    static Formen.Abbild abbild(int zoom, int k, int links, int oben, Drehung.Lage lage) {
        return (wx, wz, aus) -> {
            double bx = Projektion.zuPixel(wx, zoom) * k - links, by = Projektion.zuPixel(wz, zoom) * k - oben;
            aus[0] = lage.x(bx, by);
            aus[1] = lage.y(bx, by);
        };
    }

    /**
     * Die Nadeln und Banner der sichtbaren Ebenen in dieser Dimension, deren Fuss auf der sichtbaren
     * Karte liegt, innerhalb der {@code baender} eines Rahmens; Schild, Banner und Name bleiben im
     * Quadrat der Minimap. Siehe docs/ebenen.md, „Nadeln“,
     * und docs/ebenen.md, „Banner“.
     */
    private void nadeln(GuiGraphicsExtractor g, Font font, Rahmen r, String dimension, int links, int oben, int k, Drehung.Lage lage,
            int baender) {
        g.enableScissor(r.x(), r.y(), r.x() + r.seite(), r.y() + r.seite());
        // ponytail: alle Nadeln je Frame, höchstens 64 000; ein Raster nach Regionen, wenn das je zählt.
        for (Ebenen.Eintrag e : Ebenen.INSTANZ.sichtbar()) {
            for (Ebenen.Ort n : Ebenen.INSTANZ.nadeln(e.id())) {
                if (n.dimension().equals(dimension)) {
                    float[] m = marke(r, n.x(), n.z(), links, oben, k, zoom, rund, r.seite() / 2.0 - baender, false, lage);
                    if (m != null) {
                        Ebenen.zeichne(g, font, m[0], m[1], n);
                    }
                }
            }
        }
        g.disableScissor();
    }

    /** Die angehefteten Wegpunkte dieser Dimension, ausserhalb der Form an ihrem Rand. Siehe docs/wegpunkte.md. */
    private void wegpunkte(GuiGraphicsExtractor g, Rahmen r, String dimension, int links, int oben, int k, float kopf, Drehung.Lage lage) {
        double innen = r.seite() / 2.0 - kopf / 2 - 1;
        for (Wegpunkte.Punkt p : Wegpunkte.INSTANZ.punkte()) {
            if (p.angeheftet() && p.dimension().equals(dimension)) {
                float[] m = marke(r, p.x() + 0.5, p.z() + 0.5, links, oben, k, zoom, rund, innen, true, lage);
                wegpunkt(g, m[0], m[1], kopf, Wegpunkte.FARBEN[p.farbe()], 0);
            }
        }
    }

    /**
     * Wo eine Marke für den Ort (x, z) der Welt steht, in Einheiten des GUI: auf dem Pixel, auf dem
     * die Karte den Ort zeichnet. Liegt er weiter als {@code innen} von der Mitte, mit
     * {@code klemmen} am Rand der Form in seiner Richtung, sonst null. Mit {@code lage} gedreht um den
     * Spieler, dann ohne Raster. Siehe docs/wegpunkte.md, „Am Rand“.
     */
    static float[] marke(Rahmen r, double x, double z, int links, int oben, int k, int zoom, boolean rund, double innen, boolean klemmen,
            Drehung.Lage lage) {
        double h = r.seite() / 2.0, mx, my;
        if (lage == null) {
            mx = (r.x() * k + pixel(x, zoom, k, links)) / (double) k;
            my = (r.y() * k + pixel(z, zoom, k, oben)) / (double) k;
        } else {
            double bx = Projektion.zuPixel(x, zoom) * k - links, by = Projektion.zuPixel(z, zoom) * k - oben;
            // Auf ganze Pixel, sonst flimmern die Texel der Köpfe beim Drehen.
            mx = Math.round(lage.x(bx, by)) / (double) k;
            my = Math.round(lage.y(bx, by)) / (double) k;
        }
        double dx = mx - (r.x() + h), dz = my - (r.y() + h);
        double f = rand(dx, dz, innen, innen, rund);
        if (f >= 1) {
            return new float[] {(float) mx, (float) my};
        }
        return klemmen ? new float[] {gerundet(r.x() + h + dx * f, k), gerundet(r.y() + h + dz * f, k)} : null;
    }

    /**
     * Der Pixel im Bild der Minimap, auf dem die Karte den Ort {@code welt} zeichnet; {@code links}
     * ist die Kante des Bildes aus {@link #ecke}. Mit derselben Kante wie die Karte wackelt eine
     * Marke beim Laufen nicht gegen sie.
     */
    static int pixel(double welt, int zoom, int k, int links) {
        return (int) Math.round(Projektion.zuPixel(welt, zoom) * k) - links;
    }

    /** Auf ganze Pixel des Schirms. */
    private static float gerundet(double gui, int k) {
        return Math.round(gui * k) / (float) k;
    }

    /** Wo zwischen zwei Ticks die Minimap den Spieler zeichnet: wie die Kamera. Siehe docs/minimap.md, „Bewegung“. */
    static float anteil(ClientLevel level, LocalPlayer spieler, DeltaTracker zeit) {
        return level.tickRateManager().isEntityFrozen(spieler) ? 1f : zeit.getGameTimeDeltaPartialTick(true);
    }

    /**
     * Links oder oben im Bild der Minimap, in Pixeln des Schirms: die Lage zwischen {@code alt} und
     * {@code neu} beim Anteil {@code a} eines Ticks, auf ganze Pixel. {@code k} ist der GUI-Massstab,
     * {@code n} die Seite in Pixeln.
     */
    static int ecke(double alt, double neu, float a, int zoom, int k, int n) {
        return Mth.floor(Projektion.zuPixel(Mth.lerp(a, alt, neu), zoom) * k) - n / 2;
    }

    /**
     * Die Mitspieler als Köpfe, in derselben Dimension und innerhalb der Form; angeheftete
     * ausserhalb am Rand der Form, nach innen geklemmt. Siehe docs/minimap.md, „Mitspieler“.
     */
    private void mitspieler(GuiGraphicsExtractor g, Minecraft mc, Rahmen r, LocalPlayer spieler, String dimension,
            int links, int oben, float a, int k, float kopf, Drehung.Lage lage) {
        double h = r.seite() / 2.0;
        for (Mitspieler.Eintrag e : Mitspieler.INSTANZ.sichtbar(System.currentTimeMillis())) {
            if (!e.dimension().equals(dimension) || e.uuid().equals(spieler.getUUID())) {
                continue;
            }
            double[] ort = Mitspieler.lage(mc, e, a);
            boolean angeheftet = Wegpunkte.INSTANZ.angeheftet(e.uuid());
            float[] m = marke(r, ort[0], ort[1], links, oben, k, zoom, rund, angeheftet ? h - kopf / 2 - 1 : h, angeheftet, lage);
            if (m != null) {
                Mitspieler.kopf(g, mc, e.uuid(), m[0], m[1], kopf, 0);
            }
        }
    }

    /**
     * Wie das Bild beim Drehen auf den Schirm kommt, in Pixeln des Schirms: der Spieler bei (x, z),
     * genau zwischen zwei Ticks, auf der Mitte der Minimap, die Blickrichtung {@code gier} oben.
     * Siehe docs/minimap.md, „Drehen“.
     */
    static Drehung.Lage lage(Rahmen r, double x, double z, float gier, int zoom, int k, int links, int oben) {
        // Die Mitte auf ganzen Pixeln, wie ungedreht der Kopf.
        int n = r.seite() * k;
        return Drehung.Lage.von(Drehung.winkel(gier), r.x() * k + n / 2, r.y() * k + n / 2,
                Projektion.zuPixel(x, zoom) * k - links, Projektion.zuPixel(z, zoom) * k - oben);
    }

    /** Die Form, an der die Karte geschnitten wird ({@link #schnitt(int, int, int, int, boolean, int)}), gemerkt. */
    private float[] schnitt(Rahmen r, int k, Skin rahmen) {
        int baender = rahmen == null ? 0 : rahmen.baender();
        if (schnitt == null || schnittX != r.x() || schnittY != r.y() || schnittSeite != r.seite() || schnittMassstab != k
                || schnittRund != rund || schnittBaender != baender) {
            schnitt = schnitt(r.x(), r.y(), r.seite(), k, rund, baender);
            schnittX = r.x();
            schnittY = r.y();
            schnittSeite = r.seite();
            schnittMassstab = k;
            schnittRund = rund;
            schnittBaender = baender;
        }
        return schnitt;
    }

    /**
     * Woran die Karte geschnitten wird, in Pixeln des Schirms: eckig das Quadrat, rund ein Vieleck
     * aussen um den Kreis, das unter den Ring reicht; ohne Rahmen n/2 + 1/16 Pixel, mit Rahmen √2/2
     * Einheiten über die Bänder hinaus. Siehe docs/minimap.md, „Form“.
     */
    static float[] schnitt(int x, int y, int seite, int k, boolean rund, int baender) {
        int x0 = x * k, y0 = y * k, n = seite * k;
        if (!rund) {
            return Drehung.rechteck(x0, y0, x0 + n, y0 + n);
        }
        double radius = baender == 0 ? seite / 2.0 * k + 1.0 / 16 : (seite / 2.0 - baender + Math.sqrt(2) / 2) * k;
        return Drehung.kreis(x0 + n / 2.0, y0 + n / 2.0, radius);
    }

    /** Wie weit um den Spieler die gedrehte Karte reicht, in Pixeln des Bilds: rund bis zum Kreis, eckig bis in die Ecken. */
    private double weit(int n, int k) {
        return (rund ? n / 2.0 : n / Math.sqrt(2)) + k;
    }

    /**
     * Die Regionen: je Region ihr Quadrat mit {@code lage} auf den Schirm, mit der Form geschnitten
     * und als ein Element gezeichnet; {@code bereich} ist, was im Bild zu sehen sein kann. Ohne
     * Rahmen eckig davor der Umriss; rund zeichnet ihn {@link #zeichne} danach als Ring. Siehe
     * docs/minimap.md, „Form“.
     */
    private void male(GuiGraphicsExtractor g, Rahmen r, int links, int oben, int k, Drehung.Lage lage, double[] bereich, float[] form,
            boolean umriss) {
        int s = CHUNKS_JE_REGION * 16 * zoom * k, n = r.seite() * k, x0 = r.x() * k, y0 = r.y() * k;
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.scale(1f / k);
        Matrix3x2f kopie = new Matrix3x2f(pose);
        ScreenRectangle flaeche = new ScreenRectangle(x0, y0, n, n).transformMaxBounds(kopie);
        if (umriss && !rund) {
            g.fill(x0 - k, y0 - k, x0 + n + k, y0 + n + k, 0xFF000000);
        }
        int[] regionen = Drehung.regionen(bereich, links, oben, s);
        for (int rz = regionen[2]; rz <= regionen[3]; rz++) {
            for (int rx = regionen[0]; rx <= regionen[1]; rx++) {
                Region region = this.regionen.get(ChunkPos.pack(rx, rz));
                if (region == null) {
                    continue;
                }
                int anzahl = Drehung.region(lage, rx * s - links, rz * s - oben, s, form, puffer);
                if (anzahl < 3) {
                    continue;
                }
                // Das Element zeichnet später; Ecken und UV gehören ihm.
                float[] ecken = Arrays.copyOf(puffer.ecken(), 2 * anzahl), uv = Arrays.copyOf(puffer.uv(), 2 * anzahl);
                AbstractTexture textur = Minecraft.getInstance().getTextureManager().getTexture(region.id);
                g.guiRenderState.addGuiElement(new Drehung.Bild(kopie, TextureSetup.singleTexture(textur.getTextureView(), textur.getSampler()),
                        ecken, uv, anzahl, flaeche));
            }
        }
        pose.popMatrix();
    }

    /**
     * Der schwarze Umriss der runden Minimap ohne Rahmen als Textur, ein Texel je Pixel des Schirms,
     * aus {@link #umrissStuecke}. Nur der für die letzte Seite und den letzten GUI-Massstab bleibt.
     * Speicher siehe docs/minimap.md, „Kosten“.
     */
    private Identifier umrissRing(int n, int k) {
        if (umrissTextur == null || umrissSeite != n || umrissMassstab != k) {
            gibUmrissFrei();
            int m = n + 2 * k;
            Identifier id = Identifier.fromNamespaceAndPath(HeroicMap.ID, "umriss");
            // Neu angelegt ist die Textur ganz durchsichtig; nur die Stücke des Rings werden schwarz.
            DynamicTexture textur = new DynamicTexture(() -> "heroicmap " + id, m, m, true);
            NativeImage pixel = textur.getPixels();
            for (int y = 0; y < m; y++) {
                int[] stuecke = umrissStuecke(n, k, y);
                for (int i = 0; i < stuecke.length; i += 2) {
                    pixel.fillRect(stuecke[i], y, stuecke[i + 1] - stuecke[i], 1, 0xFF000000);
                }
            }
            textur.upload();
            Minecraft.getInstance().getTextureManager().register(id, textur);
            umrissTextur = id;
            umrissSeite = n;
            umrissMassstab = k;
        }
        return umrissTextur;
    }

    private void gibUmrissFrei() {
        if (umrissTextur != null) {
            Minecraft.getInstance().getTextureManager().release(umrissTextur);
            umrissTextur = null;
        }
    }

    /**
     * Die schwarzen Stücke der Zeile y im Umriss mit der Seite n + 2k, {x0, x1} oder {x0, x1, x2, x3},
     * Enden ausschliesslich: der Kreis mit der Seite n + 2k ohne den mit der Seite n in seiner Mitte,
     * je Zeile auf ganze Pixel ({@link #sehne}).
     */
    static int[] umrissStuecke(int n, int k, int y) {
        int m = n + 2 * k, a = sehne(m, y);
        if (y < k || y >= n + k) {
            return new int[] {a, m - a};
        }
        int b = sehne(n, y - k) + k;
        return new int[] {a, b, m - b, m - a};
    }

    /**
     * Die Chunklinien als ein Element des GUI: über den {@code bereich} des Bilds gerechnet, mit
     * {@code lage} auf den Schirm und mit der Form geschnitten. Siehe docs/minimap.md, „Chunklinien“.
     */
    private void linien(GuiGraphicsExtractor g, Rahmen r, int links, int oben, int k, Drehung.Lage lage, double[] bereich, float[] form) {
        int n = r.seite() * k;
        int bx = (int) Math.floor(bereich[0]), by = (int) Math.floor(bereich[1]);
        int seite = (int) Math.ceil(Math.max(bereich[2] - bx, bereich[3] - by));
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.scale(1f / k);
        Gitter.zeichne(g, bx, by, new ScreenRectangle(r.x() * k, r.y() * k, n, n), k, LINIE,
                linien(seite, links + bx, oben + by, 16 * zoom * k, k), lage, form);
        pose.popMatrix();
    }

    /**
     * Die Marken N, O, S, W beim Drehen: wo die Himmelsrichtung von der Mitte aus über den Rahmen
     * zeigt, auf der Mitte der Bänder; N zuletzt, zuoberst. Siehe docs/rahmen.md, „Marken“.
     */
    private void marken(GuiGraphicsExtractor g, Skin skin, Rahmen r, Drehung.Lage lage, float deckung) {
        for (int i : MARKEN) {
            double ux = lage.richtungX(RICHTUNGEN[i][0], RICHTUNGEN[i][1]), uy = lage.richtungY(RICHTUNGEN[i][0], RICHTUNGEN[i][1]);
            double[] p = Skin.marke(r.x(), r.y(), r.seite(), skin.baender(), rund, ux, uy);
            skin.ornament(g, Skin.markeFuer(i == 0, ux, uy) + (griffEcke >= 0 ? 1 : 0), 0, p[0], p[1], deckung);
        }
    }

    /**
     * Der Rahmen eines Skins über Karte und Linien, in Einheiten des GUI: die Bänder, eckig als
     * Rechtecke, rund als Ring; dann die zier an den Ecken, im Menü an der Ecke des Griffs der Griff;
     * gedreht die Marken. Die Bänder zu {@code baender}, die Ornamente zu {@code deckung} deckend. Siehe docs/rahmen.md.
     */
    private void zeichneRahmen(GuiGraphicsExtractor g, Skin skin, Rahmen r, Drehung.Lage lage, float baender, float deckung) {
        if (rund) {
            g.innerBlit(RenderPipelines.GUI_TEXTURED, skin.ring(r.seite()), r.x(), r.x() + r.seite(), r.y(), r.y() + r.seite(),
                    0, 1, 0, 1, ARGB.multiplyAlpha(-1, baender));
        } else {
            skin.baender(g, r.x(), r.y(), r.seite(), r.seite(), baender);
        }
        double[][] ecken = ecken(skin, r);
        for (int e = 0; e < ecken.length; e++) {
            int teil = e == griffEcke ? Skin.GRIFF + (griffAktiv ? 1 : 0) : Skin.ZIER + (griffEcke >= 0 ? 1 : 0);
            skin.ornament(g, teil, e, ecken[e][0], ecken[e][1], deckung);
        }
        if (lage != null) {
            marken(g, skin, r, lage, deckung);
        }
    }

    /** Wo die Ornamente des Rahmens sitzen ({@link Skin#ecken}), neu gerechnet nur, wenn sich Skin, Lage oder Form ändern. */
    double[][] ecken(Skin skin, Rahmen r) {
        if (skin != eckenSkin || !r.equals(eckenRahmen) || rund != eckenRund) {
            ecken = Skin.ecken(r.x(), r.y(), r.seite(), r.seite(), skin.baender(), rund);
            eckenSkin = skin;
            eckenRahmen = r;
            eckenRund = rund;
        }
        return ecken;
    }

    /** Die Ecke des Griffs im Menü, die zur Mitte des Schirms zeigt: 0 oben links bis 3 unten rechts, wie {@link Skin#ecken}. */
    static int griffEcke(Rahmen r, int breite, int hoehe) {
        boolean links = r.x() + r.seite() / 2 > breite / 2, oben = r.y() + r.seite() / 2 > hoehe / 2;
        return (links ? 0 : 1) | (oben ? 0 : 2);
    }

    /** Liegt (x, y) auf dem Griff mit der Mitte (gx, gy), 9 × 9 Einheiten um sie? */
    static boolean imGriff(double x, double y, double gx, double gy) {
        return Math.abs(x - Math.round(gx)) <= 4 && Math.abs(y - Math.round(gy)) <= 4;
    }

    /**
     * Die Chunklinien im Quadrat mit der Seite n, auf dem Raster der Karte: Die Linie von Chunk c
     * beginnt auf dem Pixel, auf dem die Karte Block 16·c zeichnet ({@link #pixel}), alle
     * {@code schritt} Pixel, und ist k breit; nur Linien ganz im Quadrat, über seine ganze Seite.
     * Die Form schneidet danach {@link Gitter}.
     */
    static Gitter.Linien linien(int n, int links, int oben, int schritt, int k) {
        int m = n / schritt + 1, nx = 0, ny = 0;
        int[] xs = new int[m], ys = new int[m];
        for (int px = Math.ceilDiv(links, schritt) * schritt - links; px + k <= n; px += schritt) {
            xs[nx++] = px;
        }
        for (int pz = Math.ceilDiv(oben, schritt) * schritt - oben; pz + k <= n; pz += schritt) {
            ys[ny++] = pz;
        }
        return new Gitter.Linien(xs, nx, ys, ny, n, n);
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
        gearbeitet = System.nanoTime();
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

    /**
     * Der eigene Spieler: sein Kopf aus dem Skin, daneben ein kleiner Pfeil in Blickrichtung,
     * {@code groesse} Einheiten gross, mit {@code oben} nach oben. Gezeichnet in Achteln, so wachsen Rand und Pfeil mit.
     * Siehe docs/minimap.md, „Bedienung“.
     */
    static void avatar(GuiGraphicsExtractor g, AbstractClientPlayer spieler, float x, float y, float a, float groesse, boolean oben) {
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(groesse / 8f);
        g.fill(-5, -5, 5, 5, 0xFF000000);
        PlayerFaceExtractor.extractRenderState(g, spieler.getSkin(), -4, -4, 8);
        // Der Pfeil kreist um den Kopf; bei Gier 0 blickt der Spieler nach Süden, auf der Karte nach unten. Dreht die Karte, zeigt er nach oben.
        if (!oben) {
            pose.rotate((float) Math.toRadians(spieler.getViewYRot(a) + 180));
        }
        pose.translate(0, -5);
        for (int i = 0; i < 3; i++) {
            g.fill(-i - 1, -3 + i, i + 2, -1 + i, 0xFF000000);
        }
        for (int i = 0; i < 3; i++) {
            g.fill(-i, -2 + i, i + 1, -1 + i, 0xFFFFFFFF);
        }
        pose.popMatrix();
    }

    /**
     * Ein Wegpunkt: eine Raute in seiner Farbe mit schwarzem Rand, {@code groesse} Einheiten hoch;
     * mit {@code hervor} ungleich 0 ein Ring in dieser Farbe darum. Siehe docs/wegpunkte.md.
     */
    static void wegpunkt(GuiGraphicsExtractor g, float x, float y, float groesse, int farbe, int hervor) {
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        // In Sechzehnteln, gedreht: ein Quadrat mit halber Seite 5 ist eine Raute von rund 14 Sechzehnteln.
        pose.scale(groesse / 16f);
        pose.rotate((float) (Math.PI / 4));
        if (hervor != 0) {
            g.fill(-8, -8, 8, 8, hervor);
        }
        g.fill(-6, -6, 6, 6, 0xFF000000);
        g.fill(-5, -5, 5, 5, farbe);
        pose.popMatrix();
    }
}
