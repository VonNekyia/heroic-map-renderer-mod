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
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
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
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Holder;
import net.minecraft.data.AtlasIds;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
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
    /** Ziele im Menü ({@link #ziel}): 0 bis 3 die Marken N, O, S, W, dazu der Spieler; oder keins. */
    static final int SPIELER = 4, KEIN_ZIEL = -1;
    /** Farbe der Chunklinien: Schwarz, zu 30 % deckend, so bleibt die Karte darunter lesbar. */
    static final int LINIE = 0x73000000;
    private static final int TEXT = 0xFFFFFFFF;
    /** Ein Widget im Menü gelb unter der Maus wie ein Verweis im Chat; der Punkt des Bioms in einer Höhle grau. */
    private static final int WIDGET_HELL = 0xFFFFFF55, GRAU = 0xFFA0A0A0;
    /** Chunks je Seite einer Region, einer Textur. */
    static final int CHUNKS_JE_REGION = 8;
    /** Zeit je Frame auf dem Render-Thread für Abzüge und fertige Bilder. */
    static final long BUDGET_NS = 2_000_000L;
    /** So viele Chunks hat der Worker höchstens vor sich. */
    private static final int IN_ARBEIT = 4;
    /**
     * Seite der Minimap in Einheiten des GUI: Vorgabe im ersten Schirm, kleinste beim Ziehen,
     * grösste immer. Ein Pixel der Minimap ist eine Einheit.
     */
    static final int GROESSE = 128, KLEINSTE = 64, GROESSTE = 256;
    /** Chunks je Richtung über den sichtbaren Bereich hinaus. */
    static final int VORRAT = 2;
    static final int RAND = 4;
    /**
     * Seite eines Kopfes oder Wegpunkts in Einheiten des GUI: so auf der Vollbildkarte und auf
     * der Minimap mit der Vorgabe von 128 Einheiten; auf der Minimap wächst sie mit deren Seite.
     */
    static final int KOPF = 6;
    /** Der Kopf in der Darstellung {@link Darstellung#DURCHSICHTIG}: Alpha 50 %. */
    static final int HALB_SCHWARZ = 0x80000000;
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
    /** Der Satz der Dimension und seine Kacheln im Kachelmodus, oder null; wann zuletzt gesucht. Nur auf dem Render-Thread. */
    private Satz satz;
    private Kacheln kacheln;
    private long satzGesucht;
    /** Im Kachelmodus: wie viele sichtbare Kacheln im letzten Frame noch luden; die es nicht gibt, zählen nicht. */
    private int kachelnLaden;
    /** Die Sichtweite des Clients in Chunks, je Frame neu; bis zum ersten Frame unbegrenzt. */
    private int sichtweite = Integer.MAX_VALUE / 2;

    private boolean sichtbar = true;
    /** Auflösung, die der Spieler gewählt hat: höchstens so viele Pixel je Block, 1, 2, 4, 8 oder 16. */
    private int aufloesung = 2;
    /** Pixel je Block in den Texturen, wie gezeichnet: siehe {@link #effektiv}. */
    private int scale = 2;
    /** Die Stufen des Zooms, Einheiten des GUI je Block, der Reihe nach. Siehe docs/minimap.md, „Bedienung“. */
    static final double[] ZOOMS = {1.0 / 32, 1.0 / 16, 1.0 / 8, 1.0 / 4, 1.0 / 2, 1, 2, 4, 8};
    /** Ab diesem Zoom und weiter heraus zeichnet die Minimap die Kacheln des Satzes, wenn es einen gibt. Siehe docs/minimap.md, „Weit heraus“. */
    static final double KACHELN_AB = 1.0 / 4;
    /** So viele Kacheln behält die Minimap; bei 256² Pixeln rund 16 MiB. */
    static final int KACHELN_MAX = 64;
    /** So oft, alle 2 s, sucht die Minimap im Kachelmodus den Satz der Dimension neu, etwa nach einem Download. */
    private static final long SATZ_MS = 2000;
    /** Zoom: Einheiten des GUI je Block, einer aus {@link #ZOOMS}; wie viel Gegend die Minimap zeigt. */
    private double zoom = 2;
    private boolean rund;
    /** Die Wahl {@code show}: Mitspieler über Simple Voice Chat zeigen und gezeigt werden, oder versteckt. */
    private boolean show = true;
    /** Chunklinien auf Minimap und Vollbildkarte, eine Vorliebe aus dem Untermenü. Siehe docs/minimap.md, „Chunklinien“. */
    private boolean chunklinien;
    /** Effekte in der Welt, Vorgabe an: der Strahl über angehefteten Wegpunkten. Siehe docs/wegpunkte.md, „Strahl“. */
    /** Der Todespunkt: die Raute und ihr Strahl dunkel, das ✕ hell. Siehe docs/wegpunkte.md, „Todespunkt“. */
    static final int TOD = 0xFF3A3A3A, TOD_KREUZ = 0xFFE0E0E0;
    /** Wie viele Todespunkte der Mod behält, 0 für keinen. */
    private int todespunkte = 1;
    /** So viele Todespunkte höchstens; so stellt es der Regler ein. */
    static final int MAX_TODESPUNKTE = 16;
    /** Fragt der Mod beim Start Modrinth nach einer neuen Version? Siehe docs/vollbildkarte.md, „Neue Version“. */
    private boolean neueVersion = true;
    private boolean effekte = true;
    /** Wie hoch der Schleier eigener Regionen steht, in Blöcken. Siehe docs/wegpunkte.md, „Schleier“. */
    private int schleier = Schleier.HOEHE;
    private final Formen.Speicher formenSpeicher = new Formen.Speicher(), angeheftetSpeicher = new Formen.Speicher();
    /** Koordinaten unter der Minimap. Siehe docs/minimap.md, „Koordinaten“. */
    enum Koordinaten { AUS, XZ, XYZ }

    /**
     * Die Zeit unter einem Todespunkt auf der Vollbildkarte: Datum und Uhrzeit, nur Datum, nur Uhrzeit oder keine; je Stil der
     * Schlüssel des Musters in der Sprache und ein festes Muster, wenn das der Sprache nicht taugt. Ein Klick im Untermenü geht
     * reihum. Siehe docs/wegpunkte.md, „Todespunkt“.
     */
    enum TodZeit {
        DATUM_UHRZEIT("heroicmap.karte.tod_zeit", "dd.MM. HH:mm"), DATUM("heroicmap.karte.tod_datum", "dd.MM."),
        UHRZEIT("heroicmap.karte.tod_uhrzeit", "HH:mm"), AUS(null, null);

        final String schluessel, fest;

        TodZeit(String schluessel, String fest) {
            this.schluessel = schluessel;
            this.fest = fest;
        }

        TodZeit naechste() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private TodZeit todZeit = TodZeit.DATUM_UHRZEIT;

    /** Die Widgets an der Minimap, siehe docs/widgets.md; die Zeile der Koordinaten ist eins. */
    private List<Widgets.Widget> widgets = Widgets.ausKoordinaten(null);
    /** Was die Minimap tut, solange der Debug-Bildschirm offen ist. Siehe docs/minimap.md, „Bei F3“. */
    enum BeiF3 { BLEIBT, AUS, MITTE }

    private BeiF3 beiF3 = BeiF3.BLEIBT;
    /** Wo die Minimap im letzten Frame stand, oder null, wenn sie nicht zu sehen war; der Gametest fragt es ab. */
    private Rahmen zuletzt;
    /** Wo die Zeile der Koordinaten im letzten Frame stand: x, y, Breite, Höhe; null ohne Zeile. Für die Gametests. */
    private int[] koordinatenKasten;
    /** Wo der Balken des Fortschritts zuletzt stand: x, y, Breite, Höhe der Zeile, Länge des Balkens; null ohne. Für Gametests. */
    private int[] balkenKasten;
    /** Der Kasten um den ganzen Stapel unter der Minimap, Balken und Widgets, {x, y, Breite, Höhe}; null ohne. Daran hängt das L. */
    private int[] stapelKasten;
    /** Das Widget unter der Maus im Menü, gelb; -1 für keins. */
    private int hell = -1;
    /** Wo jedes Widget zuletzt stand, {x, y, Breite, Höhe}, in der Reihe der Liste; null, solange nicht gezeichnet. */
    private int[][] widgetKaesten = new int[0][];
    /** Wie der eigene Spieler aussieht, auf Minimap und Vollbildkarte. Siehe docs/minimap.md, „Spieler“. */
    enum Darstellung { KOPF, PFEIL, DURCHSICHTIG }

    private Darstellung darstellung = Darstellung.KOPF;
    /** Dreht die Minimap mit der Blickrichtung, die oben liegt. Siehe docs/minimap.md, „Drehen“. */
    private boolean drehen = true;
    /** Hat der Spieler Drehen selbst gewählt? Nur dann steht es in der Datei, sonst gilt die Vorgabe. */
    private boolean drehenGewaehlt;
    /** Der Rahmen, eine Familie aus {@link Skin#FAMILIEN}; „ohne“ ist der Umriss. Siehe docs/rahmen.md. */
    private String skin = Skin.BIOM;
    private boolean skinGewaehlt;
    /** Die gewählte Variante je Familie, bei „biom“ je Kategorie {@code biom/<kategorie>}; ohne Eintrag gilt die erste. */
    private final Map<String, String> varianten = new HashMap<>();
    /** Die Marken N, O, S, W des Rahmens, die durchsichtig sind: Bit i für Richtung i, 0 N bis 3 W. Siehe docs/rahmen.md, „Verzierungen“. */
    private int verzierungenAus;
    /** Alle vier Marken. */
    static final int ALLE_MARKEN = 0b1111;
    /** Die Gruppe „Technik“ im Untermenü ist aufgeklappt. Siehe docs/minimap.md, „Bedienung“. */
    private boolean technikOffen;
    private Biom biom = new Biom();
    /** Solange das Menü offen ist: die Ecke des Griffs, 0 bis 3, sonst -1; und ob die Maus auf ihm liegt. */
    private int griffEcke = -1;
    private boolean griffAktiv;
    /** Was im Menü unter der Maus liegt ({@link #ziel}), vom Menü je Frame gesetzt; eine Marke darunter nimmt ihr aktives Bild. */
    private int hervor = KEIN_ZIEL;
    /** Im Menü, aus diesem Frame: Mitte und halbe Seite des Spielers als Ziel, sonst -1; die Marken N, O, S, W als Ziele, ihr Skin, und ob das Menü sie halb zeichnet. */
    private float spielerX, spielerY, spielerHalb = -1;
    private List<Ornament> menueMarken = List.of();
    private Skin menueSkin;
    /** Im Menü: alle Marken halb, denn der Rahmen ist „ohne“; sonst nur die durchsichtigen. */
    private boolean menueHalb;
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
    /** Die Seite als Anteil der kürzeren Seite des Schirms; NaN, bis der erste Schirm ihn aus {@link #groesse} ableitet. Siehe docs/minimap.md, „Bedienung“. */
    private float anteil = Float.NaN;
    /** Die Seite in Einheiten, solange der Anteil fehlt: die Vorgabe oder {@code groesse} aus einer älteren Datei. */
    private int groesse = GROESSE;
    /** Die Seite aus dem letzten {@link #rahmen(int, int)}, nach ihr richtet sich die Reichweite. */
    private int seite = GROESSE;
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
    /** Das Biom unter dem Spieler, wie das Widget es zuletzt las, sein Name und seine Kategorie, null in einer Höhle. */
    private Holder<Biome> widgetBiom;
    private Component biomName = Component.literal("–");
    private String biomKategorie;
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

    /** Die nächste Stufe aus {@link #ZOOMS}, nach der grössten wieder die kleinste. */
    void naechsterZoom() {
        setzeZoom(ZOOMS[(stufe(zoom) + 1) % ZOOMS.length]);
    }

    /** Ein anderer Zoom ändert nur den Bereich; die Texturen behalten ihre Auflösung. Nur Stufen aus {@link #ZOOMS}. */
    void setzeZoom(double zoom) {
        if (zoom != this.zoom && stufe(zoom) >= 0) {
            this.zoom = zoom;
            mitte = null;
        }
    }

    double zoom() {
        return zoom;
    }

    /** Die Stelle von {@code zoom} in {@link #ZOOMS}, oder -1. */
    static int stufe(double zoom) {
        for (int i = 0; i < ZOOMS.length; i++) {
            if (ZOOMS[i] == zoom) {
                return i;
            }
        }
        return -1;
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
     * So wird nie verkleinert, also nie unscharf, und jeder Texel ist gleich gross; nur bei Zoom 0,5 und
     * ungeradem GUI-Massstab ist ein Block 1,5 oder 0,5 Pixel, dann 1 px je Block. Siehe docs/minimap.md, „Bedienung“.
     */
    static int effektiv(int aufloesung, double zoom, int guiMassstab) {
        double schirm = zoom * guiMassstab;
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

    boolean neueVersion() {
        return neueVersion;
    }

    void setzeNeueVersion(boolean neueVersion) {
        this.neueVersion = neueVersion;
    }

    void setzeChunklinien(boolean chunklinien) {
        this.chunklinien = chunklinien;
    }

    boolean effekte() {
        return effekte;
    }

    void setzeEffekte(boolean effekte) {
        this.effekte = effekte;
    }

    int schleier() {
        return schleier;
    }

    int todespunkte() {
        return todespunkte;
    }

    /**
     * Wie viele Todespunkte der Mod behält, 0 bis {@link #MAX_TODESPUNKTE}. Nur die Zahl: Die ältesten nimmt erst das Schliessen
     * des Untermenüs weg ({@link Anzeige#removed}), so löscht der Regler beim Ziehen keinen. Siehe docs/wegpunkte.md, „Todespunkt“.
     */
    void setzeTodespunkte(int n) {
        todespunkte = Math.clamp(n, 0, MAX_TODESPUNKTE);
    }

    /** Setzt die Höhe des Schleiers eigener Regionen, gekappt auf {@link Schleier#HOEHE} bis {@link Schleier#MAX_HOEHE}. */
    void setzeSchleier(int hoehe) {
        schleier = Math.clamp(hoehe, Schleier.HOEHE, Schleier.MAX_HOEHE);
    }

    /** Der Stil des ersten Widgets der Koordinaten, ohne eins {@link Koordinaten#AUS}. */
    Koordinaten koordinaten() {
        for (Widgets.Widget w : widgets) {
            if (w.art() == Widgets.Art.KOORDINATEN) {
                return w.version() == 0 ? Koordinaten.XZ : Koordinaten.XYZ;
            }
        }
        return Koordinaten.AUS;
    }

    List<Widgets.Widget> widgets() {
        return List.copyOf(widgets);
    }

    /** Ersetzt die Widgets, etwa beim Ziehen im Menü; höchstens {@link Widgets#MAX}. */
    void widgets(List<Widgets.Widget> neu) {
        widgets = new ArrayList<>(neu.subList(0, Math.min(neu.size(), Widgets.MAX)));
    }

    void setzeWidget(int i, Widgets.Widget w) {
        widgets.set(i, w);
    }

    void loescheWidget(int i) {
        widgets.remove(i);
    }

    int[][] widgetKaesten() {
        return widgetKaesten;
    }

    TodZeit todZeit() {
        return todZeit;
    }

    void setzeTodZeit(TodZeit todZeit) {
        this.todZeit = todZeit;
    }

    BeiF3 beiF3() {
        return beiF3;
    }

    int[] koordinatenKasten() {
        return koordinatenKasten;
    }

    int[] balkenKasten() {
        return balkenKasten;
    }

    int[] stapelKasten() {
        return stapelKasten;
    }

    void hell(int widget) {
        hell = widget;
    }

    Rahmen zuletzt() {
        return zuletzt;
    }

    void setzeBeiF3(BeiF3 beiF3) {
        this.beiF3 = beiF3;
    }

    /**
     * Stellt die Koordinaten: aus nimmt ihre Widgets weg, sonst bekommt das erste die Version, und fehlt es, kommt eins unten
     * dazu, hinter die übrigen.
     */
    void setzeKoordinaten(Koordinaten koordinaten) {
        if (koordinaten == Koordinaten.AUS) {
            widgets.removeIf(w -> w.art() == Widgets.Art.KOORDINATEN);
            return;
        }
        int version = koordinaten == Koordinaten.XZ ? 0 : 1;
        for (int i = 0; i < widgets.size(); i++) {
            if (widgets.get(i).art() == Widgets.Art.KOORDINATEN) {
                widgets.set(i, new Widgets.Widget(Widgets.Art.KOORDINATEN, version, widgets.get(i).anker()));
                return;
            }
        }
        widgets.add(new Widgets.Widget(Widgets.Art.KOORDINATEN, version, Widgets.Anker.UNTEN));
    }

    Darstellung darstellung() {
        return darstellung;
    }

    void setzeDarstellung(Darstellung darstellung) {
        this.darstellung = darstellung;
    }

    String skin() {
        return skin;
    }

    void setzeSkin(String skin) {
        this.skin = Skin.FAMILIEN.contains(skin) ? skin : Skin.BIOM;
        skinGewaehlt = true;
    }

    /** Die gewählte Variante der Familie, bei „biom“ je Kategorie {@code biom/<kategorie>}; null ohne Wahl. */
    String variante(String familie) {
        return varianten.get(familie);
    }

    /** Setzt die Variante der Familie; null nimmt die Wahl zurück, dann gilt die erste. */
    void setzeVariante(String familie, String variante) {
        if (variante == null) {
            varianten.remove(familie);
        } else {
            varianten.put(familie, variante);
        }
    }

    /** Die Kategorie, deren Rahmen „biom“ gerade zeigt. */
    String kategorie() {
        return biom.gezeigt();
    }

    /** Der Skin der gewählten Variante dieser Familie, sonst ihrer ersten; null für „ohne“. */
    Skin skin(String familie) {
        return Skin.von(familie, varianten.get(familie));
    }

    /** Sind alle vier Marken zu sehen? */
    boolean verzierungen() {
        return verzierungenAus == 0;
    }

    /** Alle vier Marken an oder durchsichtig. */
    void setzeVerzierungen(boolean an) {
        verzierungenAus = an ? 0 : ALLE_MARKEN;
    }

    /** Ist die Marke in Richtung {@code marke} (0 N bis 3 W) durchsichtig? */
    boolean verzierungAus(int marke) {
        return (verzierungenAus & 1 << marke) != 0;
    }

    /** Ein Rechtsklick im Menü: die Marke durchsichtig oder zurück. Siehe docs/rahmen.md, „Verzierungen“. */
    void schalteVerzierung(int marke) {
        verzierungenAus ^= 1 << marke;
    }

    boolean technikOffen() {
        return technikOffen;
    }

    void setzeTechnikOffen(boolean offen) {
        technikOffen = offen;
    }

    /** Der Skin, den die Minimap gerade zeichnet, bei „biom“ der der gezeigten Kategorie; null ohne Rahmen. */
    Skin skinJetzt() {
        return skin(Skin.BIOM.equals(skin) ? Biom.ORDNER.get(biom.gezeigt()) : skin);
    }

    /** Vom Menü je Frame: wo der Griff liegt, -1 ohne Menü. Mit Skin zeichnet die Minimap ihn über den Bändern. */
    void griff(int ecke, boolean aktiv) {
        griffEcke = ecke;
        griffAktiv = aktiv;
    }

    /** Vom Menü je Frame: das Ziel unter der Maus, {@link #KEIN_ZIEL} ohne Menü. */
    void hervor(int ziel) {
        hervor = ziel;
    }

    /**
     * Was im Menü unter (x, y) liegt, wie zuletzt gezeichnet: eine Marke 0 bis 3 (N, O, S, W), der Spieler
     * {@link #SPIELER} oder {@link #KEIN_ZIEL}. Eine Marke trifft man mindestens 9 × 9 Einheiten um ihre Mitte.
     * Siehe docs/minimap.md, „Aussehen an der Minimap“.
     */
    int ziel(double x, double y) {
        if (spielerHalb >= 0 && Math.abs(x - spielerX) <= spielerHalb && Math.abs(y - spielerY) <= spielerHalb) {
            return SPIELER;
        }
        double h = menueSkin == null ? 0 : Math.max(4, menueSkin.groesste / 2.0 + 1);
        for (int j = 0; j < menueMarken.size(); j++) {
            Ornament o = menueMarken.get(j);
            if (Math.abs(x - o.x()) <= h && Math.abs(y - o.y()) <= h) {
                return MARKEN[j];
            }
        }
        return KEIN_ZIEL;
    }

    /**
     * Vom Menü über allem: die durchsichtigen Marken halb deckend, bei „ohne“ alle, die von „grau“ auf dem Umriss; die
     * unter der Maus ganz und aktiv. So trifft ein Rechtsklick sie zum Zurückholen. Siehe docs/rahmen.md, „Im Menü“.
     */
    void geister(GuiGraphicsExtractor g) {
        if (menueSkin == null) {
            return;
        }
        for (int j = 0; j < menueMarken.size(); j++) {
            if (!menueHalb && !verzierungAus(MARKEN[j])) {
                continue;
            }
            Ornament o = menueMarken.get(j);
            boolean an = MARKEN[j] == hervor;
            menueSkin.ornament(g, o.teil() + (an ? 1 : 0), o.ecke(), o.x(), o.y(), an ? 1 : 0.5f, o.winkel());
        }
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

    /**
     * Lage und Seite auf diesem Schirm: Die Seite ist ein Anteil seiner kürzeren Seite, so folgt sie
     * dem Fenster; höchstens {@link #GROESSTE}, das hält die Kosten. Siehe docs/entscheidungen/0013-groesse-als-anteil-des-schirms.md.
     */
    Rahmen rahmen(int breite, int hoehe) {
        int kurz = Math.min(breite, hoehe);
        if (Float.isNaN(anteil) && kurz > 0) {
            // Ohne Anteil gilt die Seite im ersten Schirm; so springt nichts.
            anteil = groesse / (float) kurz;
        }
        Rahmen r = rahmen(breite, hoehe, Float.isNaN(anteil) ? groesse : Math.min(GROESSTE, Math.round(anteil * kurz)), lageX, lageY, rand());
        if (r.seite() != seite) {
            // Gezogen oder ein anderes Fenster: Die Reichweite ändert sich, der nächste Frame passt den Bereich an.
            seite = r.seite();
            mitte = null;
        }
        return r;
    }

    /** Der Abstand zum Rand des Schirms: {@link #RAND}, mit Rahmen mindestens dessen Einrückung, so bleiben die Marken ganz auf dem Schirm. */
    int rand() {
        // Der grösste Abstand aller Varianten, bei „biom“ aller Kategorien: So springt die Minimap beim Wechsel nicht.
        int rand = RAND;
        for (String familie : Skin.BIOM.equals(skin) ? Biom.ORDNER.values() : List.of(skin)) {
            for (Skin.Variante v : Skin.varianten(familie)) {
                rand = Math.max(rand, rand(Skin.von(familie, v.name())));
            }
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

    /** Gibt der Minimap die Seite auf diesem Schirm, als Anteil gemerkt, und legt sie dann wie {@link #verschiebe}. */
    void stelle(int x, int y, int seite, int breite, int hoehe) {
        anteil = Mth.clamp(seite, KLEINSTE, GROESSTE) / (float) Math.min(breite, hoehe);
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
        double z = dezimal(p.getProperty("zoom"), aufloesung);
        zoom = stufe(z) >= 0 ? z : 2;
        rund = "rund".equals(p.getProperty("form"));
        show = !"hidden".equals(p.getProperty("show"));
        chunklinien = "true".equals(p.getProperty("chunklinien"));
        effekte = !"false".equals(p.getProperty("effekte"));
        setzeSchleier(zahl(p.getProperty("schleier"), Schleier.HOEHE));
        // Bis 0.2.57 ein Schalter todespunkt: aus heisst keiner, an einer.
        todespunkte = Math.clamp(zahl(p.getProperty("todespunkte"), "false".equals(p.getProperty("todespunkt")) ? 0 : 1), 0, MAX_TODESPUNKTE);
        neueVersion = !"false".equals(p.getProperty("neue_version"));
        todZeit = switch (String.valueOf(p.getProperty("tod_zeit")).trim()) {
            case "datum" -> TodZeit.DATUM;
            case "uhrzeit" -> TodZeit.UHRZEIT;
            case "aus" -> TodZeit.AUS;
            default -> TodZeit.DATUM_UHRZEIT;
        };
        // Ohne widgets aus der alten Zeile der Koordinaten. Siehe docs/widgets.md, „Gespeichert“.
        String w = p.getProperty("widgets");
        widgets = w != null ? Widgets.lies(w) : Widgets.ausKoordinaten(p.getProperty("koordinaten"));
        beiF3 = switch (String.valueOf(p.getProperty("bei_f3")).trim()) {
            case "aus" -> BeiF3.AUS;
            case "mitte" -> BeiF3.MITTE;
            default -> BeiF3.BLEIBT;
        };
        darstellung = switch (String.valueOf(p.getProperty("spieler")).trim()) {
            case "pfeil" -> Darstellung.PFEIL;
            case "durchsichtig" -> Darstellung.DURCHSICHTIG;
            default -> Darstellung.KOPF;
        };
        // Vorgabe an. Ein altes drehen=true war gewählt, denn die Vorgabe war aus; ein altes drehen=false nicht unterscheidbar.
        String wahl = p.getProperty("drehen_wahl");
        drehenGewaehlt = wahl != null || "true".equals(p.getProperty("drehen"));
        drehen = wahl == null || "true".equals(wahl);
        // Vorgabe biom. Ein alter rahmen ausser „ohne“ war gewählt, denn die Vorgabe war ohne; ein altes rahmen=ohne nicht unterscheidbar.
        String rahmenWahl = p.getProperty("rahmen_wahl");
        String rahmen = String.valueOf(rahmenWahl != null ? rahmenWahl : p.getProperty("rahmen")).trim();
        // Bis 0.2.68 hiessen die Rahmen grau, holz und so weiter; sie ziehen in ihre Familie und Variante um.
        varianten.clear();
        List<String> alt = Skin.ALT.get(rahmen);
        if (alt != null) {
            rahmen = alt.getFirst();
            varianten.put(rahmen, alt.getLast());
        }
        skinGewaehlt = Skin.FAMILIEN.contains(rahmen) && (rahmenWahl != null || !Skin.OHNE.equals(rahmen));
        skin = skinGewaehlt ? rahmen : Skin.BIOM;
        for (String familie : Skin.MIT_VARIANTEN) {
            String v = p.getProperty("rahmen_variante." + familie);
            if (v != null) {
                varianten.put(familie, v.trim());
            }
        }
        // Vor mod#175 galt ein Schalter für alle vier; aus heisst alle vier durchsichtig, so springt nichts.
        String aus = p.getProperty("verzierungen_aus");
        verzierungenAus = aus != null ? marken(aus) : "false".equals(p.getProperty("verzierungen")) ? ALLE_MARKEN : 0;
        technikOffen = "auf".equals(p.getProperty("technik"));
        ablage = switch (String.valueOf(p.getProperty("ablage")).trim()) {
            case "hash" -> Downloads.Ablage.HASH;
            case "ip_port" -> Downloads.Ablage.IP_PORT;
            default -> Downloads.Ablage.IP;
        };
        // Vor dem Anteil stand die Seite in Einheiten; sie gilt im ersten Schirm, siehe rahmen.
        groesse = Mth.clamp(zahl(p.getProperty("groesse"), GROESSE), KLEINSTE, GROESSTE);
        float a = bruch(p.getProperty("groesse_anteil"), Float.NaN);
        anteil = a > 0 ? a : Float.NaN;
        lageX = bruch(p.getProperty("lage_x"), 1);
        lageY = bruch(p.getProperty("lage_y"), 0);
    }

    void schreibe(Path datei) {
        Properties p = new Properties();
        p.setProperty("minimap", Boolean.toString(sichtbar));
        p.setProperty("massstab", Integer.toString(aufloesung));
        // Ganze Stufen wie bisher als ganze Zahl; eine ältere Version liest 0.5 nicht und nimmt 2.
        p.setProperty("zoom", zoom == Math.rint(zoom) ? Integer.toString((int) zoom) : Double.toString(zoom));
        p.setProperty("form", rund ? "rund" : "eckig");
        p.setProperty("show", show ? "simplevoicechat" : "hidden");
        p.setProperty("chunklinien", Boolean.toString(chunklinien));
        p.setProperty("effekte", Boolean.toString(effekte));
        p.setProperty("schleier", Integer.toString(schleier));
        p.setProperty("todespunkte", Integer.toString(todespunkte));
        p.setProperty("tod_zeit", todZeit.name().toLowerCase(Locale.ROOT));
        p.setProperty("neue_version", Boolean.toString(neueVersion));
        p.setProperty("widgets", Widgets.schreibe(widgets));
        p.setProperty("bei_f3", beiF3.name().toLowerCase(Locale.ROOT));
        p.setProperty("spieler", darstellung.name().toLowerCase(Locale.ROOT));
        if (drehenGewaehlt) {
            p.setProperty("drehen_wahl", Boolean.toString(drehen));
        }
        if (skinGewaehlt) {
            p.setProperty("rahmen_wahl", skin);
        }
        varianten.forEach((familie, v) -> p.setProperty("rahmen_variante." + familie, v));
        p.setProperty("verzierungen_aus", marken(verzierungenAus));
        p.setProperty("technik", technikOffen ? "auf" : "zu");
        p.setProperty("ablage", ablage.name().toLowerCase(Locale.ROOT));
        if (Float.isNaN(anteil)) {
            p.setProperty("groesse", Integer.toString(groesse));
        } else {
            p.setProperty("groesse_anteil", Float.toString(anteil));
        }
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

    private static double dezimal(String text, double vorgabe) {
        try {
            return text == null ? vorgabe : Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return vorgabe;
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
        return kacheln != null ? kachelnLaden == 0 : mitte != null && offen.isEmpty() && laufend.isEmpty();
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

    /** Chunks je Richtung um den Spieler, die die Minimap zeichnet: sichtbar plus Vorrat, höchstens die Sichtweite plus Vorrat. */
    int reichweite() {
        return reichweite(zoom, sicht(seite, drehen, rund), sichtweite);
    }

    /** Wie {@link #reichweite(double, int)}, höchstens die Sichtweite plus Vorrat: Weiter hat der Client keine Chunks, bei 1/32 wären es sonst ±130. */
    static int reichweite(double zoom, int groesse, int sichtweite) {
        return Math.min(reichweite(zoom, groesse), sichtweite + VORRAT);
    }

    /** Wie viel Gegend die Minimap braucht, als Seite eines Quadrats: eckig und gedreht reicht sie in den Ecken √2 weiter. */
    static int sicht(int groesse, boolean drehen, boolean rund) {
        return drehen && !rund ? (int) Math.ceil(groesse * Math.sqrt(2)) : groesse;
    }

    static int reichweite(double zoom, int groesse) {
        return Mth.ceil(groesse / 2.0 / (16.0 * zoom)) + VORRAT;
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
        gibKachelnFrei();
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
        // Ziele im Menü gelten nur für diesen Frame.
        menueMarken = List.of();
        menueSkin = null;
        spielerHalb = -1;
        zuletzt = null;
        if (!sichtbar || spieler == null || level == null) {
            gibUmrissFrei();
            return;
        }
        // Vor arbeite: Die Seite bestimmt die Reichweite.
        Rahmen r = rahmen(g.guiWidth(), g.guiHeight());
        boolean imMenue = mc.gui.screen() instanceof Einstellungen || mc.gui.screen() instanceof Anzeige;
        // Im Menü steht sie, wo man sie hinzieht; nur im Spiel weicht sie dem Debug-Bildschirm. Siehe docs/minimap.md, „Bei F3“.
        if (!imMenue && DebugBildschirm.offen(mc)) {
            r = beiF3(r, beiF3, g.guiWidth());
            if (r == null) {
                gibUmrissFrei();
                return;
            }
        }
        zuletzt = r;
        sichtweite = mc.options.getEffectiveRenderDistance();
        String dimension = level.dimension().identifier().toString();
        // Ab 1/4 die Kacheln des Satzes, wenn es einen gibt; dann ruhen die Chunks. Siehe docs/minimap.md, „Weit heraus“.
        Satz kachelSatz = zoom <= KACHELN_AB ? satz(dimension) : null;
        if (kachelSatz == null) {
            gibKachelnFrei();
            arbeite(mc, level, spieler);
        }

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
        // Im Menü genordet: Gedreht wanderten die Marken über den Griff. Siehe docs/minimap.md, „Drehen“.
        Drehung.Lage lage = drehen && !imMenue ? lage(r, Mth.lerp(a, spieler.xo, spieler.getX()), Mth.lerp(a, spieler.zo, spieler.getZ()),
                spieler.getViewYRot(a), zoom, k, links, oben) : null;
        // Ungedreht liegt das Bild auf dem Raster der Karte, gedreht dreht es um den Spieler; der Weg ist derselbe.
        Drehung.Lage bild = lage != null ? lage : Drehung.Lage.von(0, r.x() * k, r.y() * k, 0, 0);
        double weit = weit(n, k);
        double[] bereich = lage != null ? new double[] {lage.px() - weit, lage.py() - weit, lage.px() + weit, lage.py() + weit}
                : new double[] {0, 0, n, n};
        float[] form = schnitt(r, k, rahmen);
        if (kachelSatz != null) {
            int z = kachelStufe(kachelSatz.scale(), kachelSatz.minZoom(), kachelSatz.maxZoom(), kachelSatz.stufe(), zoom, k);
            kachelnLaden = 0;
            male(g, r, links, oben, k, bild, bereich, form, rahmen == null, kachelSeite(kachelSatz, z, zoom, k), (tx, ty) -> {
                Identifier id = kacheln.textur(z, tx, ty);
                if (id == null && !kacheln.fehlt(z, tx, ty)) {
                    kachelnLaden++;
                }
                return id;
            });
        } else {
            male(g, r, links, oben, k, bild, bereich, form, rahmen == null, (int) (CHUNKS_JE_REGION * 16 * zoom * k), (rx, rz) -> {
                Region region = regionen.get(ChunkPos.pack(rx, rz));
                return region == null ? null : region.id;
            });
        }
        // Unter 1/2 stünden die Linien dichter als ein Pixel. Siehe docs/minimap.md, „Chunklinien“.
        if (chunklinien && zoom >= 0.5) {
            linien(g, r, links, oben, k, bild, bereich, form);
        }
        formen(g, r, links, oben, k, bild, bereich, form, dimension);
        // Vor Ring und Rahmen: Was am Rand über sie ragt, decken sie.
        nadeln(g, mc.font, r, dimension, links, oben, k, lage, rahmen == null ? 0 : rahmen.baender(), spieler.getX(), spieler.getZ());
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
            Skin alt = vorher == null ? null : skin(Biom.ORDNER.get(vorher));
            float t = alt == null ? 1 : biom.anteil(ms);
            if (alt != null) {
                zeichneRahmen(g, alt, r, lage, k, 1, 1 - t);
            }
            zeichneRahmen(g, rahmen, r, lage, k, t, t);
        }
        float kopf = kopf(r.seite());
        wegpunkte(g, r, dimension, links, oben, k, kopf, lage);
        mitspieler(g, mc, r, spieler, dimension, links, oben, a, k, kopf, lage);
        // In der Mitte des Bildes, auf dem Pixel, den ecke dafür nimmt; gedreht genau in der Mitte, der Pfeil nach oben.
        float sx = lage == null ? (r.x() * k + n / 2) / (float) k : (float) (lage.cx() / k);
        float sy = lage == null ? (r.y() * k + n / 2) / (float) k : (float) (lage.cy() / k);
        avatar(g, spieler, sx, sy, a, kopf, lage != null, darstellung);
        if (imMenue) {
            // Ziele für Klicks im Menü. Ohne Marken am Rahmen zeichnet das Menü sie halb, bei „ohne“ die von schild/grau auf dem Umriss.
            spielerX = sx;
            spielerY = sy;
            spielerHalb = kopf / 2 + 2;
            menueHalb = rahmen == null;
            menueSkin = rahmen != null ? rahmen : Skin.von("schild", "grau");
            if (menueSkin != null) {
                menueMarken = ornamente(rahmen != null ? r : new Rahmen(r.x() - 2, r.y() - 2, r.seite() + 4),
                        rahmen != null ? rahmen.baender() : 1, rund, menueSkin.marken, lage, k, 0, -1, false, KEIN_ZIEL);
            }
        }
        // Solange ein Download oder Abgleich läuft, steht der Balken in der ersten Zeile unter der Minimap, die Koordinaten eine
        // Zeile weiter. Siehe docs/minimap.md, „Fortschritt“.
        int abstand = rand(), ueberstand = rahmen == null ? 1 : abstand;
        Laden.Fortschritt laeuft = Downloads.INSTANZ.fortschritt();
        balkenKasten = null;
        if (laeuft != null && laeuft.zeigen()) {
            int zeile = mc.font.lineHeight;
            int[] wo = koordinatenLage(r, ueberstand, abstand, g.guiWidth(), g.guiHeight(), r.seite(), zeile);
            balkenKasten = balken(g, mc.font, wo[0], wo[1], r.seite(), zeile, laeuft);
            ueberstand += zeile + 2;
        }
        // Die Widgets: unten im Stapel nach dem Balken, oben über der Minimap oder neben dem Nordpfeil, frei, wo sie liegen.
        // Siehe docs/widgets.md.
        koordinatenKasten = null;
        stapelKasten = balkenKasten;
        boolean klickbar = mc.gui.screen() instanceof Einstellungen;
        int sw = g.guiWidth(), sh = g.guiHeight(), anzahl = widgets.size();
        Component[] texte = new Component[anzahl];
        float[] massstab = new float[anzahl];
        int obenHoehe = 0;
        for (int i = 0; i < anzahl; i++) {
            Widgets.Widget w = widgets.get(i);
            texte[i] = text(w, level, spieler);
            // In ganzen Pixeln des Geräts je Pixel der Schrift; kleiner nur, wenn es nicht zwischen die Ränder passt.
            massstab[i] = Math.min(w.pixel(k) / (float) k, (sw - 2f * abstand) / Math.max(1, mc.font.width(texte[i])));
            obenHoehe += w.anker() == Widgets.Anker.OBEN ? Mth.ceil(mc.font.lineHeight * massstab[i]) + 2 : 0;
        }
        // Passt der obere Stapel ganz über die Minimap, steht er dort, sonst neben dem Nordpfeil, bündig mit ihrer Oberkante.
        int obenWeg = rahmen == null ? 1 : abstand, nebenY = r.y();
        boolean darueber = r.y() - obenWeg - obenHoehe >= 0;
        int zoomX = Einstellungen.zoomLage(r, abstand, sw)[0];
        widgetKaesten = new int[anzahl][];
        for (int i = 0; i < anzahl; i++) {
            Widgets.Widget w = widgets.get(i);
            int breite = Mth.ceil(mc.font.width(texte[i]) * massstab[i]), zeile = Mth.ceil(mc.font.lineHeight * massstab[i]);
            int[] wo = switch (w.anker()) {
                case UNTEN -> {
                    int[] p = koordinatenLage(r, ueberstand, abstand, sw, sh, breite, zeile);
                    ueberstand += zeile + 2;
                    yield p;
                }
                case OBEN -> {
                    int[] p = darueber ? new int[] {waagrecht(r.x() + (r.seite() - breite) / 2, abstand, sw, breite), r.y() - obenWeg - 2 - zeile}
                            : new int[] {waagrecht(zoomX < r.x() ? zoomX - 2 - breite : zoomX + Einstellungen.ZOOM_BREITE + 2, abstand, sw, breite), nebenY};
                    obenWeg += darueber ? zeile + 2 : 0;
                    nebenY += darueber ? 0 : zeile + 2;
                    yield p;
                }
                case FREI -> new int[] {abstand + Math.round(w.x() * Math.max(0, sw - breite - 2 * abstand)),
                        abstand + Math.round(w.y() * Math.max(0, sh - zeile - 2 * abstand))};
            };
            int[] kasten = zeile(g, mc.font, wo, texte[i], massstab[i], breite, zeile, klickbar && hell == i ? WIDGET_HELL : TEXT);
            widgetKaesten[i] = kasten;
            if (w.anker() == Widgets.Anker.UNTEN) {
                stapelKasten = vereint(stapelKasten, kasten);
            }
            koordinatenKasten = koordinatenKasten == null && w.art() == Widgets.Art.KOORDINATEN ? kasten : koordinatenKasten;
        }
    }

    /** Der Text eines Widgets. Siehe docs/widgets.md, „Arten“. */
    Component text(Widgets.Widget w, ClientLevel level, LocalPlayer spieler) {
        return switch (w.art()) {
            case KOORDINATEN -> {
                Object[] werte = werte(w.version() == 0 ? Koordinaten.XZ : Koordinaten.XYZ, spieler.getX(), spieler.getY(), spieler.getZ());
                yield Component.translatable(werte.length == 3 ? "heroicmap.koordinaten_xyz" : "heroicmap.koordinaten", werte);
            }
            // Die Uhr der Oberwelt, auch in Nether und End.
            case ZEIT -> switch (w.versionName()) {
                case "tag" -> Component.translatable("heroicmap.widget.tag", Widgets.tag(level.getOverworldClockTime()));
                case "echt" -> {
                    LocalTime t = LocalTime.now();
                    yield Component.literal(String.format(Locale.ROOT, "%d:%02d", t.getHour(), t.getMinute()));
                }
                default -> Component.literal(Widgets.uhr(level.getOverworldClockTime(), w.versionName().equals("12h")));
            };
            case BIOM -> {
                // Wie beim Rahmen „biom“: Ein Chunk, den der Client noch nicht hat, läse sich als Ebene; dann bleibt das letzte.
                if (level.hasChunkAt(spieler.blockPosition())) {
                    Holder<Biome> jetzt = level.getBiome(spieler.blockPosition());
                    if (jetzt != widgetBiom) {
                        widgetBiom = jetzt;
                        Identifier id = jetzt.unwrapKey().map(k -> k.identifier()).orElse(Identifier.withDefaultNamespace("plains"));
                        biomName = Component.translatableWithFallback("biome." + id.getNamespace() + "." + id.getPath(), id.getPath());
                        biomKategorie = biom.kategorie(jetzt);
                    }
                }
                if (w.versionName().equals("name")) {
                    yield biomName;
                }
                // Der Punkt in der Farbe des Rahmens „biom“ dieser Kategorie, wie im Wähler; in Höhlen grau.
                Skin s = biomKategorie == null ? null : skin(Biom.ORDNER.get(biomKategorie));
                yield Component.empty().append(Component.literal("■ ").withColor(s == null ? GRAU : s.licht[s.licht.length - 1])).append(biomName);
            }
        };
    }

    /** Links bei {@code x}, ganz auf dem Schirm im Abstand {@code abstand} vom Rand. */
    private static int waagrecht(int x, int abstand, int schirmBreite, int breite) {
        return Math.clamp(x, abstand, Math.max(abstand, schirmBreite - abstand - breite));
    }

    /** Eine Zeile Text bei {@code wo}, im Massstab {@code f}; gibt ihren Kasten zurück. */
    private static int[] zeile(GuiGraphicsExtractor g, Font font, int[] wo, Component text, float f, int breite, int zeile, int farbe) {
        g.pose().pushMatrix();
        g.pose().translate(wo[0], wo[1]);
        g.pose().scale(f);
        g.text(font, text, 0, 0, farbe, true);
        g.pose().popMatrix();
        return new int[] {wo[0], wo[1], breite, zeile};
    }

    /** Der Kasten um zwei Kästen {x, y, Breite, Höhe}; einer darf null sein. */
    static int[] vereint(int[] a, int[] b) {
        if (a == null || b == null) {
            return a == null ? b : a;
        }
        int x = Math.min(a[0], b[0]), y = Math.min(a[1], b[1]);
        return new int[] {x, y, Math.max(a[0] + a[2], b[0] + b[2]) - x, Math.max(a[1] + a[3], b[1] + b[3]) - y};
    }

    /**
     * Wo die Minimap steht, solange der Debug-Bildschirm offen ist: wie {@code r}, null für ausgeblendet,
     * oder waagrecht in der Mitte des Schirms auf ihrer Höhe. Siehe docs/minimap.md, „Bei F3“.
     */
    static Rahmen beiF3(Rahmen r, BeiF3 wahl, int breite) {
        return switch (wahl) {
            case BLEIBT -> r;
            case AUS -> null;
            case MITTE -> new Rahmen((breite - r.seite()) / 2, r.y(), r.seite());
        };
    }

    /** Die durchsichtigen Marken als Text, etwa „N,S“, und zurück; unbekannte Zeichen zählen nicht. */
    static String marken(int bits) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if ((bits & 1 << i) != 0) {
                text.append(text.isEmpty() ? "" : ",").append("NOSW".charAt(i));
            }
        }
        return text.toString();
    }

    static int marken(String text) {
        int bits = 0;
        for (String teil : text.split(",")) {
            int i = teil.trim().length() == 1 ? "NOSW".indexOf(teil.trim().charAt(0)) : -1;
            bits |= i >= 0 ? 1 << i : 0;
        }
        return bits;
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
     * Der Balken des Fortschritts in einer Zeile ab (x, y), {@code breite} Einheiten: 2 Einheiten hoch auf dunklem Grund, weiss
     * gefüllt nach dem Anteil, rechts die Prozent. Gibt x, y, Breite, Höhe und die Länge des Balkens zurück. Siehe
     * docs/minimap.md, „Fortschritt“.
     */
    private static int[] balken(GuiGraphicsExtractor g, Font font, int x, int y, int breite, int zeile, Laden.Fortschritt f) {
        Component prozent = Component.literal(f.prozent());
        int text = font.width(prozent), lang = Math.max(0, breite - text - 3);
        g.fill(x, y + 3, x + lang, y + 5, HALB_SCHWARZ);
        g.fill(x, y + 3, x + (int) Math.round(lang * f.anteil()), y + 5, TEXT);
        g.text(font, prozent, x + breite - text, y, TEXT, true);
        return new int[] {x, y, breite, zeile, lang};
    }

    /**
     * Wo der Text der Koordinaten links oben beginnt: mittig unter der Minimap, {@code ueberstand} Einheiten unter ihrem
     * Rand und 2 Abstand; passt er dort nicht auf den Schirm, ebenso darüber, sonst unten am Schirm auf der Minimap.
     * Waagrecht zum Schirm hin verschoben, bis {@code abstand} vor seinen Rand; so endet er in einer Ecke bündig mit der
     * Minimap. Siehe docs/minimap.md, „Koordinaten“.
     */
    static int[] koordinatenLage(Rahmen r, int ueberstand, int abstand, int schirmBreite, int schirmHoehe, int textBreite, int zeile) {
        int x = Math.clamp(r.x() + (r.seite() - textBreite) / 2, abstand, Math.max(abstand, schirmBreite - abstand - textBreite));
        int unter = r.y() + r.seite() + ueberstand + 2, ueber = r.y() - ueberstand - 2 - zeile;
        return new int[] {x, unter + zeile <= schirmHoehe ? unter : ueber >= 0 ? ueber : schirmHoehe - zeile};
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
     * Die angehefteten Flächen und Kreise samt eigenen Regionen, darüber die Kartenschrift der sichtbaren
     * Ebenen in dieser Dimension, in Pixeln des Schirms mit {@code lage} wie die Karte und mit ihrer Form
     * geschnitten; {@code bereich} ist, was vom Bild zu sehen sein kann.
     * Siehe docs/ebenen.md, „Flächen, Kreise und Linien“,
     * und docs/wegpunkte.md, „Anheften“.
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
        Font font = Minecraft.getInstance().font;
        // Flächen, Kreise und Linien nur auf der Vollbildkarte; auf die Minimap kommen Flächen und Kreise erst angeheftet (mod#59, mod#36).
        Formen.zeichne(g, a, dimension, Wegpunkte.INSTANZ.minimap(Ebenen.INSTANZ), angeheftetSpeicher, font, true);
        List<List<Ebenen.Form>> ebenen = Ebenen.INSTANZ.sichtbar().stream().map(e -> Ebenen.INSTANZ.formen(e.id())).toList();
        Formen.zeichne(g, a, dimension, ebenen, formenSpeicher, font, false);
        pose.popMatrix();
    }

    /** Wie die Formen einen Punkt der Welt in die Pixel der Minimap legen: wie die Karte, mit {@code lage} gedreht. */
    static Formen.Abbild abbild(double zoom, int k, int links, int oben, Drehung.Lage lage) {
        return (wx, wz, aus) -> {
            double bx = Projektion.zuPixel(wx, zoom) * k - links, by = Projektion.zuPixel(wz, zoom) * k - oben;
            aus[0] = lage.x(bx, by);
            aus[1] = lage.y(bx, by);
        };
    }

    /**
     * Die angehefteten Nadeln und Banner der sichtbaren Ebenen in dieser Dimension, deren Fuss auf der
     * sichtbaren Karte liegt, innerhalb der {@code baender} eines Rahmens; Schild, Banner und Name bleiben
     * im Quadrat der Minimap. Ein Banner nah am Spieler bei (sx, sz) wird durchsichtig.
     * Siehe docs/ebenen.md, „Nadeln“,
     * und docs/ebenen.md, „Banner“.
     */
    private void nadeln(GuiGraphicsExtractor g, Font font, Rahmen r, String dimension, int links, int oben, int k, Drehung.Lage lage,
            int baender, double sx, double sz) {
        g.enableScissor(r.x(), r.y(), r.x() + r.seite(), r.y() + r.seite());
        int[] namen = {Ebenen.MAX_NAMEN};
        for (Ebenen.Ort n : Wegpunkte.INSTANZ.nadeln(Ebenen.INSTANZ)) {
            if (n.dimension().equals(dimension)) {
                float[] m = marke(r, n.x(), n.z(), links, oben, k, zoom, rund, r.seite() / 2.0 - baender, false, lage);
                if (m != null) {
                    float d = n instanceof Ebenen.Banner ? deckung(Math.hypot(n.x() - sx, n.z() - sz)) : 1;
                    Ebenen.zeichne(g, font, m[0], m[1], n, namen, k, d);
                }
            }
        }
        g.disableScissor();
    }

    /** Ab so vielen Blöcken waagrechtem Abstand wird ein angeheftetes Banner auf der Minimap durchsichtig, bis {@link #NAH_DECKUNG} bei {@link #NAH_BIS}. */
    static final double NAH_AB = 24, NAH_BIS = 8;
    static final float NAH_DECKUNG = 0.35f;

    /** Die Deckung eines Banners im Abstand {@code abstand} vom Spieler: 1 ab {@link #NAH_AB}, linear bis {@link #NAH_DECKUNG} bei {@link #NAH_BIS} und darunter. */
    static float deckung(double abstand) {
        double t = Mth.clamp((abstand - NAH_BIS) / (NAH_AB - NAH_BIS), 0, 1);
        return (float) (NAH_DECKUNG + (1 - NAH_DECKUNG) * t);
    }

    /** Die angehefteten Wegpunkte dieser Dimension, ausserhalb der Form an ihrem Rand. Siehe docs/wegpunkte.md. */
    private void wegpunkte(GuiGraphicsExtractor g, Rahmen r, String dimension, int links, int oben, int k, float kopf, Drehung.Lage lage) {
        double innen = r.seite() / 2.0 - kopf / 2 - 1;
        for (Wegpunkte.Punkt p : Wegpunkte.INSTANZ.punkte()) {
            if (p.angeheftet() && p.dimension().equals(dimension) && Wegpunkte.INSTANZ.sichtbar(p)) {
                float[] m = marke(r, p.x() + 0.5, p.z() + 0.5, links, oben, k, zoom, rund, innen, true, lage);
                wegpunkt(g, m[0], m[1], kopf, p, 0);
            }
        }
    }

    /**
     * Wo eine Marke für den Ort (x, z) der Welt steht, in Einheiten des GUI: auf dem Pixel, auf dem
     * die Karte den Ort zeichnet. Liegt er weiter als {@code innen} von der Mitte, mit
     * {@code klemmen} am Rand der Form in seiner Richtung, sonst null. Mit {@code lage} gedreht um den
     * Spieler, dann ohne Raster. Siehe docs/wegpunkte.md, „Am Rand“.
     */
    static float[] marke(Rahmen r, double x, double z, int links, int oben, int k, double zoom, boolean rund, double innen, boolean klemmen,
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
    /**
     * Der Satz der Dimension für den Kachelmodus, die eigene Karte vor einem Download, wie die Vollbildkarte; alle
     * {@link #SATZ_MS} neu gesucht. Ein anderer Satz bekommt neue Kacheln. Siehe docs/minimap.md, „Weit heraus“.
     */
    private Satz satz(String dimension) {
        long jetzt = Util.getMillis();
        if (kacheln == null || jetzt - satzGesucht >= SATZ_MS) {
            satzGesucht = jetzt;
            Satz neu = Satz.fuer(Downloads.weltOrdner(), dimension, Downloads.INSTANZ.wer());
            if (neu == null || satz == null || !neu.ordner().equals(satz.ordner())) {
                gibKachelnFrei();
                satz = neu;
                kacheln = neu == null ? null : new Kacheln(neu, KACHELN_MAX);
            }
        }
        return satz;
    }

    /** Für den Gametest: zeichnet die Minimap gerade die Kacheln eines Satzes? */
    boolean kachelmodus() {
        return kacheln != null;
    }

    /** Gibt die Kacheln des Kachelmodus frei, etwa wieder ab 1/2 oder in einer anderen Welt. */
    private void gibKachelnFrei() {
        if (kacheln != null) {
            kacheln.close();
            kacheln = null;
        }
        satz = null;
    }

    /**
     * Die Stufe des Satzes für den Kachelmodus: die gröbste, deren Pixel auf dem Schirm noch höchstens einen Pixel decken,
     * Zoom mal GUI-Massstab Pixel je Block; so verkleinert die Minimap höchstens 2× und vergrössert nie. Nie feiner als die
     * feinste Stufe des Massstabs, nie gröber als {@code minZoom}. Siehe docs/minimap.md, „Weit heraus“.
     */
    static int kachelStufe(int scale, int minZoom, int maxZoom, int feinste, double zoom, int k) {
        // Stufe z hat scale / 2^(maxZoom - z) Pixel je Block.
        int groeber = (int) Math.floor(Math.log(scale / (zoom * k)) / Math.log(2) + 1e-9);
        return Math.clamp(maxZoom - groeber, minZoom, feinste);
    }

    /** Die Seite einer Kachel der Stufe {@code z} auf dem Schirm, in Pixeln; ganz, denn Zoom und Stufen sind Zweierpotenzen. */
    static int kachelSeite(Satz satz, int z, double zoom, int k) {
        return (int) Math.round(Math.scalb((double) satz.kachel(), satz.maxZoom() - z) / satz.scale() * zoom * k);
    }

    /** Die Beschriftung einer Stufe: unter 1 als Bruch „1/32×“ bis „1/2×“, sonst „1×“ bis „8×“. */
    static Component fach(double zoom) {
        return zoom < 1 ? Component.translatable("heroicmap.menue.fach_bruch", (int) Math.round(1 / zoom))
                : Component.translatable("heroicmap.menue.fach", (int) zoom);
    }

    static int pixel(double welt, double zoom, int k, int links) {
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
    static int ecke(double alt, double neu, float a, double zoom, int k, int n) {
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
    static Drehung.Lage lage(Rahmen r, double x, double z, float gier, double zoom, int k, int links, int oben) {
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
     * Die Regionen oder die Kacheln: je Quadrat von {@code s} Pixeln seine Textur aus {@code textur} mit {@code lage} auf den
     * Schirm, mit der Form geschnitten und als ein Element gezeichnet; ohne Textur nichts. {@code bereich} ist, was im Bild zu
     * sehen sein kann. Ohne Rahmen eckig davor der Umriss; rund zeichnet ihn {@link #zeichne} danach als Ring. Siehe
     * docs/minimap.md, „Form“.
     */
    private void male(GuiGraphicsExtractor g, Rahmen r, int links, int oben, int k, Drehung.Lage lage, double[] bereich, float[] form,
            boolean umriss, int s, BiFunction<Integer, Integer, Identifier> textur) {
        int n = r.seite() * k, x0 = r.x() * k, y0 = r.y() * k;
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
                Identifier id = textur.apply(rx, rz);
                if (id == null) {
                    continue;
                }
                int anzahl = Drehung.region(lage, rx * s - links, rz * s - oben, s, form, puffer);
                if (anzahl < 3) {
                    continue;
                }
                // Das Element zeichnet später; Ecken und UV gehören ihm.
                float[] ecken = Arrays.copyOf(puffer.ecken(), 2 * anzahl), uv = Arrays.copyOf(puffer.uv(), 2 * anzahl);
                AbstractTexture bild = Minecraft.getInstance().getTextureManager().getTexture(id);
                g.guiRenderState.addGuiElement(new Drehung.Bild(kopie, TextureSetup.singleTexture(bild.getTextureView(), bild.getSampler()),
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
        Gitter.Linien l = linien(seite, links + bx, oben + by, (int) (16 * zoom * k), 1);
        // Ein Pixel des Geräts breit, bei jedem GUI-Massstab. Siehe docs/minimap.md, „Chunklinien“.
        Gitter.zeichne(g, bx, by, new ScreenRectangle(r.x() * k, r.y() * k, n, n), 1, LINIE, l, lage, form);
        pose.popMatrix();
        // Ungedreht liegt Spalte x des Bilds auf Pixel cx + x des Schirms.
        zuletztLinien = lage.sin() == 0 && lage.cos() == 1 ? l : null;
        zuletztX = (int) lage.cx() + bx;
    }

    /** Die zuletzt ungedreht gezeichneten Chunklinien und wo Spalte 0 auf dem Schirm lag, für den Gametest. */
    private Gitter.Linien zuletztLinien;
    private int zuletztX;

    /** Für den Gametest: die Spalten der senkrechten Chunklinien in Pixeln des Schirms, zuletzt ungedreht gezeichnet; sonst leer. */
    int[] chunklinienSpalten() {
        Gitter.Linien l = zuletztLinien;
        if (l == null) {
            return new int[0];
        }
        int[] spalten = new int[l.nx()];
        for (int i = 0; i < spalten.length; i++) {
            spalten[i] = zuletztX + l.xs()[i];
        }
        return spalten;
    }

    /**
     * Der Rahmen eines Skins über Karte und Linien, in Einheiten des GUI: die Bänder samt Schatten
     * ({@link Skin#baender}); darüber die Ornamente aus {@link #ornamente}. Die Bänder zu
     * {@code baender}, die Ornamente zu {@code deckung} deckend. Siehe docs/rahmen.md.
     */
    private void zeichneRahmen(GuiGraphicsExtractor g, Skin skin, Rahmen r, Drehung.Lage lage, int k, float baender, float deckung) {
        skin.baender(g, r.x(), r.y(), r.seite(), k, rund, baender);
        for (Ornament o : ornamente(r, skin.baender(), rund, skin.marken, lage, k, verzierungenAus, griffEcke, griffAktiv, hervor)) {
            skin.ornament(g, o.teil(), o.ecke(), o.x(), o.y(), deckung, o.winkel());
        }
    }

    /** Ein Bild über den Bändern: Teil aus {@link Skin}, Ecke zum Spiegeln, Mitte in Einheiten des GUI, Winkel. */
    record Ornament(int teil, int ecke, double x, double y, double winkel) {
    }

    /**
     * Was der Rahmen über den Bändern zeichnet, in dieser Reihenfolge: die Marken O, S, W, N ausser denen in
     * {@code ohne} (Bit i für Richtung i) auf der Mitte der Bänder, ungedreht fest rechts, unten, links und oben, gedreht starr
     * mit der Karte, die Marke {@code hervor} (0 bis 3, N, O, S, W) aktiv; im Menü zuletzt der Griff an seiner
     * Ecke, nie gedreht. In den Ecken sonst nichts. Das Bild steht nach {@link Skin.Marken#stellung}: starr mit der Karte, von
     * der Mitte weg (O, S und W nehmen {@code marke}, um eine, zwei und drei Vierteldrehungen weiter) oder aufrecht, mit eigenen
     * Bildern je Richtung, wenn es sie gibt. Jede Marke liegt nach ihrem {@link Skin.Platz}, ohne auf der Mitte der Bänder.
     * Siehe docs/rahmen.md, „Marken“.
     */
    static List<Ornament> ornamente(Rahmen r, int baender, boolean rund, Skin.Marken marken, Drehung.Lage lage, int k,
            int ohne, int griffEcke, boolean griffAktiv, int hervor) {
        List<Ornament> aus = new ArrayList<>(5);
        double winkel = lage == null ? 0 : lage.winkel();
        for (int i : MARKEN) {
            if ((ohne & 1 << i) == 0) {
                Skin.Platz platz = marken.platz(i);
                double[] p = lage == null ? Skin.marke(r.x(), r.y(), r.seite(), baender, rund, RICHTUNGEN[i][0], RICHTUNGEN[i][1], platz)
                        : verzierung(r, baender, rund, lage, RICHTUNGEN[i], k, platz);
                int teil = marken.eigene() ? Skin.EIGENE_JE_RICHTUNG[i]
                        : marken.stellung() == Skin.Stellung.AUSSEN && i != 0 ? Skin.MARKE : Skin.MARKE_JE_RICHTUNG[i];
                double w = switch (marken.stellung()) {
                    case KARTE -> winkel;
                    case AUSSEN -> winkel + i * Math.PI / 2;
                    case AUFRECHT -> 0;
                };
                aus.add(new Ornament(teil + (i == hervor ? 1 : 0), 0, p[0], p[1], w));
            }
        }
        if (griffEcke >= 0) {
            double[] p = Skin.ecken(r.x(), r.y(), r.seite(), r.seite(), baender, rund)[griffEcke];
            aus.add(new Ornament(Skin.GRIFF + (griffAktiv ? 1 : 0), griffEcke, p[0], p[1], 0));
        }
        return aus;
    }

    /**
     * Wo eine Marke in {@code richtung} des Kartenbilds gedreht sitzt, auf der Mitte der Bänder
     * ({@link Skin#marke}), die Mitte auf ganzen Pixeln des Schirms, sonst zitterte sie beim Laufen.
     * Siehe docs/rahmen.md, „Drehen“.
     */
    static double[] verzierung(Rahmen r, int baender, boolean rund, Drehung.Lage lage, double[] richtung, int k, Skin.Platz platz) {
        double[] p = Skin.marke(r.x(), r.y(), r.seite(), baender, rund,
                lage.richtungX(richtung[0], richtung[1]), lage.richtungY(richtung[0], richtung[1]), platz);
        return new double[] {Math.round(p[0] * k) / (double) k, Math.round(p[1] * k) / (double) k};
    }

    /** Wo der Griff an den Ecken sitzen kann ({@link Skin#ecken}), für das Menü; neu gerechnet nur, wenn sich Skin, Lage oder Form ändern. */
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
     * beginnt auf dem Pixel, auf dem die Karte Block 16·c zeichnet ({@link #pixel}), dem ersten östlich und südlich der
     * Kante, alle {@code schritt} Pixel, und ist {@code dicke} breit; nur Linien ganz im Quadrat, über seine ganze Seite.
     * Die Form schneidet danach {@link Gitter}.
     */
    static Gitter.Linien linien(int n, int links, int oben, int schritt, int dicke) {
        int m = n / schritt + 1, nx = 0, ny = 0;
        int[] xs = new int[m], ys = new int[m];
        for (int px = Math.ceilDiv(links, schritt) * schritt - links; px + dicke <= n; px += schritt) {
            xs[nx++] = px;
        }
        for (int pz = Math.ceilDiv(oben, schritt) * schritt - oben; pz + dicke <= n; pz += schritt) {
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
        // Die Deckel der Truhen kommen aus ihrem eigenen Atlas. Siehe docs/minimap.md, „Blockentities“.
        texel.putAll(ChunkMaler.Texel.vomAtlas(mc.getAtlasManager().getAtlasOrThrow(AtlasIds.CHESTS)));
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
     * Der eigene Spieler in der {@code art}: sein Kopf aus dem Skin, deckend oder halb durchsichtig, mit einem
     * kleinen Pfeil in Blickrichtung darüber, oder nur der Pfeil, doppelt so gross; {@code groesse} Einheiten
     * gross, mit {@code oben} nach oben. Gezeichnet in Achteln, so wachsen Rand und Pfeil mit.
     * Siehe docs/minimap.md, „Spieler“.
     */
    static void avatar(GuiGraphicsExtractor g, AbstractClientPlayer spieler, float x, float y, float a, float groesse, boolean oben,
            Darstellung art) {
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(groesse / 8f);
        if (art == Darstellung.KOPF) {
            g.fill(-5, -5, 5, 5, 0xFF000000);
            PlayerFaceExtractor.extractRenderState(g, spieler.getSkin(), -4, -4, 8);
        } else if (art == Darstellung.DURCHSICHTIG) {
            // Halb: nur der Rand, nicht unter dem Gesicht, sonst schiene Schwarz statt der Karte durch.
            g.fill(-5, -5, 5, -4, HALB_SCHWARZ);
            g.fill(-5, 4, 5, 5, HALB_SCHWARZ);
            g.fill(-5, -4, -4, 4, HALB_SCHWARZ);
            g.fill(4, -4, 5, 4, HALB_SCHWARZ);
            PlayerFaceExtractor.extractRenderState(g, spieler.getSkin(), -4, -4, 8, HALB_SCHWARZ | 0xFFFFFF);
        }
        // Der Pfeil kreist um den Kopf; bei Gier 0 blickt der Spieler nach Süden, auf der Karte nach unten. Dreht die Karte, zeigt er nach oben.
        if (!oben) {
            pose.rotate((float) Math.toRadians(spieler.getViewYRot(a) + 180));
        }
        // Die Zeilen des Pfeils sind 3, 5 und 7 Achtel breit, ab -i - 1: Ihre Mitte liegt ein halbes Achtel rechts.
        if (art == Darstellung.PFEIL) {
            // Ohne Kopf seine Mitte auf dem Spieler; die vier Zeilen reichen von -3 bis 1.
            pose.scale(2);
            pose.translate(-0.5f, 1);
        } else {
            pose.translate(-0.5f, -5);
        }
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
    /** Wie {@link #wegpunkt(GuiGraphicsExtractor, float, float, float, int, int)} für den Wegpunkt {@code p}, ein Todespunkt dunkel mit hellem ✕. */
    static void wegpunkt(GuiGraphicsExtractor g, float x, float y, float groesse, Wegpunkte.Punkt p, int hervor) {
        wegpunkt(g, x, y, groesse, p.tod() ? TOD : Wegpunkte.FARBEN[p.farbe()], hervor, p.tod());
    }

    static void wegpunkt(GuiGraphicsExtractor g, float x, float y, float groesse, int farbe, int hervor) {
        wegpunkt(g, x, y, groesse, farbe, hervor, false);
    }

    /** Ein Todespunkt ohne Wegpunkt dahinter, etwa im Untermenü. */
    static void todespunkt(GuiGraphicsExtractor g, float x, float y, float groesse) {
        wegpunkt(g, x, y, groesse, TOD, 0, true);
    }

    private static void wegpunkt(GuiGraphicsExtractor g, float x, float y, float groesse, int farbe, int hervor, boolean kreuz) {
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
        if (kreuz) {
            // Gedreht wird das Plus zum ✕; in Vierteln der Sechzehntel, Arme 5 lang und 0,75 dick: dicker liefe es bei GS 2 zu
            // einem Fleck zusammen. Siehe docs/wegpunkte.md, „Todespunkt“.
            pose.scale(0.25f);
            g.fill(-20, -3, 20, 3, TOD_KREUZ);
            g.fill(-3, -20, 3, 20, TOD_KREUZ);
        }
        pose.popMatrix();
    }
}
