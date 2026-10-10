package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.joml.Matrix3x2f;

/**
 * Die Vollbildkarte aus dem geladenen Satz: Ziehen verschiebt, das Mausrad zoomt über die
 * Stufen des Satzes und danach mit der Lupe; Knöpfe laden und gleichen ab. Wegpunkte, der
 * Spieler und Mitspieler stehen als Marken darauf, ausserhalb am Rand. Siehe docs/vollbildkarte.md
 * und docs/wegpunkte.md.
 */
final class Karte extends Screen {

    private static final int HINTERGRUND = 0xFF101010;
    private static final int TEXT = 0xFFFFFFFF;
    /** Breite der Knöpfe in Einheiten des GUI. */
    private static final int KNOPF = 90;
    /** Höhe eines Eintrags im Menü nach Rechtsklick. */
    private static final int ZEILE = 14;
    /** Abstand der Marken vom Rand des Schirms; Platz für den Namen über einem Kopf. */
    private static final int RAND = 14;
    /** So lange dauert ein Durchlauf der Farben um angeheftete Marken, in ms. */
    private static final long BUNT_MS = 2000;
    /** Weiter gezogen, in Einheiten des GUI, ist es kein Klick auf eine Marke mehr. */
    private static final double ZUG = 3;
    /** Die halbe Breite des Punkts unter einer angehefteten Nadel, in Einheiten des GUI. */
    private static final int PUNKT = 2;
    /** Die Vorschau einer Region, die der Spieler setzt. */
    private static final int VORSCHAU = 0xCCFFFFFF;

    private final Satz satz;
    private final Kacheln kacheln;
    private final Kartenblick blick;
    private final Formen.Speicher formenSpeicher = new Formen.Speicher();
    private final Tafeln.Zeigen zeigen = new Tafeln.Zeigen();
    /** Die offene Tafel: ihr Ziel, wo der Zeiger war, als sie aufging, wie weit sie gescrollt ist, und wo sie und ihr Knopf zuletzt lagen. */
    private Tafeln.Ziel tafelOffen;
    private int tafelX, tafelY, tafelScroll;
    private long tafelSeit;
    private int[] tafelKasten;
    /** Ist die Tafel höher als ihr Platz? Nur dann scrollt das Rad sie statt die Karte zu zoomen. */
    private boolean tafelZuHoch;
    /** Der Klick schloss oder traf die Tafel oder das Menü; sein Loslassen hält keine neue, sein Ziehen schiebt nicht. */
    private boolean klickVerbraucht;
    /** Ist die linke Taste gedrückt? Beim Ziehen zeigt die Karte keine Tafel. */
    private boolean taste;
    /** Der Satz der offenen Tafel, gespeichert je Tafel, GUI-Massstab und Schrift. */
    private Tafel satzVon;
    private int satzGs, satzGeneration;
    private Tafel.Satz tafelSatz;
    /** Wonach das Ziel unter dem Zeiger zuletzt gesucht wurde, und was gefunden. */
    private double[] suche;
    private Tafeln.Ziel gefunden;
    /** Was beim letzten Knopf schiefging, oder null. */
    private Component hinweis;
    /** Wo die Knöpfe rechts oben beginnen und enden; Marken und Namen weichen ihnen aus. */
    private int knopfX, knopfUnten;
    /** Der Knopf für den Abgleich, oder null ohne Satz vom Server; er ist aus, bis ein Abgleich wieder geht. */
    private Button abgleich;
    private String baum;
    /** Das Menü nach Rechtsklick: der Block, oder null, seine Einträge und wo es steht. */
    private int[] ziel;
    private List<Eintrag> eintraege = List.of();
    private int menueX, menueY;
    /** Die Marken des letzten Frames, in der Reihenfolge, in der sie gezeichnet sind. */
    private final List<Marke> marken = new ArrayList<>();
    /** Die Marke des letzten Klicks, wenn es einer auf eine Marke war; ein Doppelklick heftet sie an. */
    private Marke letzte;
    /** Die Marke unter dem Drücken der linken Taste, oder null; ein Zug macht daraus keinen Klick. */
    private Marke gedrueckt;
    /** War das Drücken der zweite Klick eines Doppelklicks? */
    private boolean doppelklick;
    /** Die Marke eines Klicks, die in die Mitte kommt, wenn kein zweiter Klick folgt, und seit wann sie wartet. */
    private Marke wartend;
    private long wartendSeit;
    /** Die Nadel, die Fläche oder der Kreis unter dem letzten Klick ohne Marke; ein Doppelklick darauf heftet an. */
    private Tafeln.Ziel letztesZiel;
    /** Wie weit seit dem Drücken gezogen ist, in Einheiten des GUI. */
    private double gezogen;

    /** Ein Eintrag im Menü nach Rechtsklick. */
    private record Eintrag(Component text, Runnable tut) {
    }

    /**
     * Was auf der Karte steht und sich anklicken lässt: die Mitte auf dem Schirm, die halbe Seite,
     * der Ort in der Welt und was es ist; für den eigenen Spieler sind {@code spieler}, {@code punkt}
     * und {@code region} null.
     */
    record Marke(float x, float y, float halb, double weltX, double weltZ, UUID spieler, Wegpunkte.Punkt punkt, Wegpunkte.Region region) {
    }

    /** Die erste Ecke einer Region, die der Spieler gerade setzt, und ihre Dimension; sonst null. */
    private int[] regionVon;
    private String regionDimension;

    /** {@code satz} ist null, wenn für diese Dimension nichts geladen ist. */
    Karte(Satz satz) {
        super(Component.translatable("heroicmap.karte.titel"));
        this.satz = satz;
        this.kacheln = satz == null ? null : new Kacheln(satz);
        this.blick = satz == null ? null : new Kartenblick(satz.kachel(), satz.minZoom(), satz.maxZoom(), satz.stufe());
    }

    @Override
    protected void init() {
        LocalPlayer spieler = minecraft.player;
        if (blick != null && spieler != null && blick.mx == 0 && blick.mz == 0) {
            blick.mx = Projektion.zuPixel(spieler.getX(), satz.scale());
            blick.mz = Projektion.zuPixel(spieler.getZ(), satz.scale());
        }
        int x = width - KNOPF - 4;
        Button unterster = addRenderableWidget(Button.builder(Component.translatable("heroicmap.karte.laden"),
                b -> minecraft.gui.setScreen(new Auswahl(this))).bounds(x, 4, KNOPF, 20).build());
        // Eine selbst gezeichnete Karte hat keinen Abgleich. Siehe docs/selbst.md, „Wahl“.
        if (satz != null && !Selbst.selbst(satz.ordner().getParent())) {
            // Der Baum ist der Ordner über dem Massstab.
            baum = satz.ordner().getParent().getFileName().toString();
            abgleich = addRenderableWidget(Button.builder(Component.translatable("heroicmap.karte.abgleich"),
                    b -> hinweis = Downloads.INSTANZ.frageAbgleich(baum)).bounds(x, 28, KNOPF, 20).build());
            unterster = abgleich;
        }
        knopfX = x;
        knopfUnten = unterster.getY() + unterster.getHeight();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
        g.fill(0, 0, width, height, HINTERGRUND);
        if (satz == null) {
            g.centeredText(font, Component.translatable("heroicmap.karte.keine"), width / 2, height / 2, TEXT);
            super.extractRenderState(g, mausX, mausY, delta);
            return;
        }
        // Ein Klick auf eine Marke zentriert erst, wenn kein zweiter folgt; so bewegt ein Doppelklick die Karte nicht.
        if (wartend != null && Util.getMillis() - wartendSeit >= MouseHandler.DOUBLE_CLICK_THRESHOLD_MS) {
            zentriere(wartend.weltX(), wartend.weltZ());
            wartend = null;
        }
        int[] k = blick.kacheln(width, height);
        int seite = satz.kachel() * blick.lupe;
        for (int ty = k[2]; ty <= k[3]; ty++) {
            for (int tx = k[0]; tx <= k[1]; tx++) {
                Identifier id = kacheln.textur(blick.zoom, tx, ty);
                // Von einer ganzzahligen Kante aus, wie Marken und Klicks. Siehe docs/wegpunkte.md, „Am Rand“.
                int sx = blick.kachelX(tx, width), sy = blick.kachelY(ty, height);
                if (id != null) {
                    g.blit(RenderPipelines.GUI_TEXTURED, id, sx, sy, 0, 0, seite, seite, satz.kachel(), satz.kachel(),
                            satz.kachel(), satz.kachel());
                } else {
                    platzhalter(g, tx, ty, sx, sy, seite);
                }
            }
        }
        if (Minimap.INSTANZ.chunklinien() && blick.chunklinien(satz.scale(), minecraft.getWindow().getGuiScale())) {
            linien(g);
        }
        formen(g);
        regionen(g, mausX, mausY);
        marken(g);
        int[] block = block(mausX, mausY);
        g.text(font, Component.literal(satz.name() + "   ").append(Component.translatable("heroicmap.koordinaten", block[0], block[1])),
                4, height - 12, TEXT);
        if (hinweis != null) {
            g.text(font, hinweis, 4, height - 24, TEXT);
        }
        if (regionVon != null) {
            g.text(font, Component.translatable("heroicmap.karte.region_hinweis"), 4, height - (hinweis != null ? 36 : 24), TEXT);
        }
        // Höchstens ein Abgleich je Tag: Nach einer Ablehnung mit wieder ist der Knopf bis dahin aus.
        if (abgleich != null) {
            long ab = Downloads.INSTANZ.abgleichAb(baum);
            abgleich.active = ab == 0;
            abgleich.setMessage(ab == 0 ? Component.translatable("heroicmap.karte.abgleich")
                    : Component.translatable("heroicmap.karte.abgleich_ab", Downloads.uhr(ab)));
        }
        super.extractRenderState(g, mausX, mausY, delta);
        tafel(g, mausX, mausY);
        if (ziel != null) {
            int b = menueBreite();
            g.fill(menueX, menueY, menueX + b, menueY + eintraege.size() * ZEILE, 0xE0000000);
            g.outline(menueX, menueY, b, eintraege.size() * ZEILE, TEXT);
            for (int i = 0; i < eintraege.size(); i++) {
                g.text(font, eintraege.get(i).text(), menueX + 4, menueY + i * ZEILE + 3, TEXT);
            }
        }
    }

    /**
     * Die Flächen, Kreise und Linien der sichtbaren Ebenen in der Dimension des Spielers, auf dem
     * Raster der Kacheln. Siehe docs/ebenen.md, „Flächen, Kreise und Linien“.
     */
    private void formen(GuiGraphicsExtractor g) {
        if (minecraft.player == null) {
            return;
        }
        String dimension = minecraft.player.level().dimension().identifier().toString();
        int scale = satz.scale();
        Matrix3x2f pose = new Matrix3x2f(g.pose());
        Formen.Ansicht a = new Formen.Ansicht(blick.abbild(scale, width, height), blick.chunkAbstand(scale) / 16, 1, minecraft.getWindow().getGuiScale(), Drehung.rechteck(0, 0, width, height),
                new double[] {blick.basisRasterX(0, width) / scale, blick.basisRasterZ(0, height) / scale,
                        blick.basisRasterX(width, width) / scale, blick.basisRasterZ(height, height) / scale},
                pose, new ScreenRectangle(0, 0, width, height).transformMaxBounds(pose), Double.POSITIVE_INFINITY);
        Formen.zeichne(g, a, dimension, Wegpunkte.INSTANZ.karte(Ebenen.INSTANZ), formenSpeicher, font, true);
    }

    /** Chunklinien je 16 Blöcke als ein Element des GUI. Siehe docs/minimap.md, „Chunklinien“. */
    private void linien(GuiGraphicsExtractor g) {
        Gitter.zeichne(g, 0, 0, 1, Minimap.LINIE, blick.linien(satz.scale(), width, height));
    }

    /**
     * Wegpunkte, Mitspieler und der Spieler, in der Dimension des Spielers; was ausserhalb liegt,
     * am Rand in seiner Richtung. Angeheftete haben einen Ring in wechselnder Farbe.
     * Siehe docs/wegpunkte.md.
     */
    private void marken(GuiGraphicsExtractor g) {
        marken.clear();
        LocalPlayer spieler = minecraft.player;
        if (spieler == null) {
            return;
        }
        String dimension = spieler.level().dimension().identifier().toString();
        int bunt = Mth.hsvToArgb((System.currentTimeMillis() % BUNT_MS) / (float) BUNT_MS, 1f, 1f, 255);
        nadeln(g, dimension, bunt);
        // Eine eigene Region steht wie ein Wegpunkt als Raute in ihrer Mitte. Siehe docs/wegpunkte.md, „Regionen“.
        for (Wegpunkte.Region r : Wegpunkte.INSTANZ.regionen()) {
            if (r.dimension().equals(dimension)) {
                Marke m = marke((r.x0() + r.x1() + 1) / 2.0, (r.z0() + r.z1() + 1) / 2.0, null, null, r);
                Minimap.wegpunkt(g, m.x(), m.y(), Minimap.KOPF, Wegpunkte.FARBEN[r.farbe()], r.angeheftet() ? bunt : 0);
            }
        }
        for (Wegpunkte.Punkt p : Wegpunkte.INSTANZ.punkte()) {
            if (p.dimension().equals(dimension)) {
                Marke m = marke(p.x() + 0.5, p.z() + 0.5, null, p);
                Minimap.wegpunkt(g, m.x(), m.y(), Minimap.KOPF, Wegpunkte.FARBEN[p.farbe()], p.angeheftet() ? bunt : 0);
            }
        }
        // Mitspieler mit Namen. Siehe docs/minimap.md, „Mitspieler“.
        for (Mitspieler.Eintrag e : Mitspieler.INSTANZ.sichtbar(System.currentTimeMillis())) {
            if (!e.dimension().equals(dimension) || e.uuid().equals(spieler.getUUID())) {
                continue;
            }
            double[] lage = Mitspieler.lage(minecraft, e, 1f);
            Marke m = marke(lage[0], lage[1], e.uuid(), null);
            Mitspieler.kopf(g, minecraft, e.uuid(), m.x(), m.y(), Minimap.KOPF, Wegpunkte.INSTANZ.angeheftet(e.uuid()) ? bunt : 0);
            int[] name = Kartenblick.name(m.x(), m.y(), m.halb(), width, font.width(e.name()), knopfX, knopfUnten);
            g.text(font, e.name(), name[0], name[1], TEXT);
        }
        Marke ich = marke(spieler.getX(), spieler.getZ(), null, null);
        Minimap.avatar(g, spieler, ich.x(), ich.y(), 1f, Minimap.KOPF, false);
    }

    /**
     * Die Nadeln und Banner der sichtbaren Ebenen in dieser Dimension, auf dem Raster der Kacheln, in
     * fester Grösse auf jeder Stufe; angeheftete mit einem Punkt unter dem Fuss in der Farbe {@code bunt}.
     * Siehe docs/ebenen.md, „Nadeln“.
     */
    private void nadeln(GuiGraphicsExtractor g, String dimension, int bunt) {
        int k = minecraft.getWindow().getGuiScale();
        int[] namen = {Ebenen.MAX_NAMEN};
        for (Ebenen.Eintrag e : Ebenen.INSTANZ.sichtbar()) {
            for (Ebenen.Ort n : Ebenen.INSTANZ.nadeln(e.id())) {
                double x = blick.rasterX(Projektion.zuPixel(n.x(), satz.scale()), width);
                double y = blick.rasterY(Projektion.zuPixel(n.z(), satz.scale()), height);
                // Erst die Höhe: Unter dem Fuss reicht der Name NAME_UNTEN Einheiten, über ihm höchstens ein Banner 64. Dann
                // der Kasten ohne Holen; er misst den Namen, holt aber kein Bild.
                if (!n.dimension().equals(dimension) || y <= -Ebenen.NAME_UNTEN || y >= height + 65) {
                    continue;
                }
                float[] r = Ebenen.kastenOhneHolen(font, n);
                if (x + r[2] > 0 && x + r[0] < width) {
                    float px = aufPixel(x, k), py = aufPixel(y, k);
                    Ebenen.zeichne(g, font, px, py, n, namen, k, 1);
                    if (Wegpunkte.INSTANZ.angeheftet(Ebenen.INSTANZ, n)) {
                        // Zwischen Fuss und Name, der erst NAME_OBEN darunter beginnt.
                        g.pose().pushMatrix();
                        g.pose().translate(px, py);
                        g.fill(-PUNKT, 0, PUNKT, (int) Ebenen.NAME_OBEN, bunt);
                        g.pose().popMatrix();
                    }
                }
            }
        }
    }

    /** Auf ganze Pixel bei GUI-Massstab {@code k}, wie Nadeln und Banner gezeichnet werden. */
    private static float aufPixel(double v, int k) {
        return Math.round(v * k) / (float) k;
    }

    /**
     * Die Marke für den Ort (x, z) der Welt, auf dem Raster der Kacheln; liegt er ausserhalb des
     * Schirms, am Rand in seiner Richtung, nicht unter den Knöpfen.
     */
    private Marke marke(double x, double z, UUID uuid, Wegpunkte.Punkt punkt) {
        return marke(x, z, uuid, punkt, null);
    }

    private Marke marke(double x, double z, UUID uuid, Wegpunkte.Punkt punkt, Wegpunkte.Region region) {
        float halb = Minimap.KOPF / 2f + 1;
        double[] p = Kartenblick.marke(blick.rasterX(Projektion.zuPixel(x, satz.scale()), width),
                blick.rasterY(Projektion.zuPixel(z, satz.scale()), height), width, height, RAND, halb,
                knopfX, knopfUnten);
        // Auf ganze Pixel wie die Kacheln, deren Kanten auf ganzen Einheiten liegen.
        int k = minecraft.getWindow().getGuiScale();
        Marke m = new Marke(Math.round(p[0] * k) / (float) k, Math.round(p[1] * k) / (float) k, halb, x, z, uuid, punkt, region);
        marken.add(m);
        return m;
    }

    /**
     * Die oberste Marke unter (x, y), oder null; ein Wegpunkt oder Mitspieler geht dem eigenen Kopf
     * vor, sonst liesse sich ein Wegpunkt am eigenen Standort nicht greifen.
     */
    private Marke treffer(double x, double y) {
        Marke eigen = null;
        for (int i = marken.size() - 1; i >= 0; i--) {
            Marke m = marken.get(i);
            if (Math.abs(x - m.x()) <= m.halb() && Math.abs(y - m.y()) <= m.halb()) {
                if (m.punkt() != null || m.spieler() != null || m.region() != null) {
                    return m;
                }
                eigen = eigen == null ? m : eigen;
            }
        }
        return eigen;
    }

    /** Legt den Ort (x, z) der Welt in die Mitte des Schirms. */
    private void zentriere(double x, double z) {
        blick.mx = Projektion.zuPixel(x, satz.scale());
        blick.mz = Projektion.zuPixel(z, satz.scale());
    }

    private int menueBreite() {
        int b = 0;
        for (Eintrag e : eintraege) {
            b = Math.max(b, font.width(e.text()));
        }
        return b + 8;
    }

    /** Für die Gametests: Steht eine Tafel auf dem Schirm? */
    boolean tafelOffen() {
        return tafelKasten != null;
    }

    /** Für den Gametest Bedienung: die Mitte des Blicks in Pixeln der Basis, das offene Menü, oder null, und die Marken. */
    double[] blickMitte() {
        return blick == null ? null : new double[] {blick.mx, blick.mz};
    }

    int[] ziel() {
        return ziel;
    }

    /** Die erste Ecke der Region, die der Spieler gerade setzt, oder null. */
    int[] regionVon() {
        return regionVon;
    }

    Satz satz() {
        return satz;
    }

    List<Marke> marken() {
        return List.copyOf(marken);
    }

    List<Component> eintraege() {
        return eintraege.stream().map(Eintrag::text).toList();
    }

    /**
     * Die eigenen Regionen dieser Dimension: die Fläche in ihrer Farbe zu 25 %, 1 Einheit Rand deckend,
     * angeheftet {@link Wegpunkte#BREITER} breiter, auf ganzen Pixeln wie die Kacheln; dazu die Vorschau,
     * solange der Spieler eine setzt, gestrichelt von der ersten Ecke bis zum Block unter der Maus.
     * Siehe docs/wegpunkte.md, „Regionen“.
     */
    private void regionen(GuiGraphicsExtractor g, int mausX, int mausY) {
        if (minecraft.player == null) {
            return;
        }
        String dimension = minecraft.player.level().dimension().identifier().toString();
        if (regionVon != null && !dimension.equals(regionDimension)) {
            regionVon = null;
        }
        int gs = minecraft.getWindow().getGuiScale();
        g.pose().pushMatrix();
        g.pose().scale(1f / gs);
        for (Wegpunkte.Region r : Wegpunkte.INSTANZ.regionen()) {
            int[] k = r.dimension().equals(dimension) ? kasten(r.x0(), r.z0(), r.x1(), r.z1(), gs) : null;
            if (k != null) {
                int farbe = Wegpunkte.FARBEN[r.farbe()], b = Math.round((r.angeheftet() ? 1 + Wegpunkte.BREITER : 1) * gs);
                g.fill(k[0], k[1], k[2], k[3], farbe & 0x00FFFFFF | 0x40000000);
                g.fill(k[0], k[1], k[2], k[1] + b, farbe);
                g.fill(k[0], k[3] - b, k[2], k[3], farbe);
                g.fill(k[0], k[1], k[0] + b, k[3], farbe);
                g.fill(k[2] - b, k[1], k[2], k[3], farbe);
            }
        }
        if (regionVon != null) {
            int[] b = block(mausX, mausY);
            int[] k = kasten(Math.min(regionVon[0], b[0]), Math.min(regionVon[1], b[1]), Math.max(regionVon[0], b[0]),
                    Math.max(regionVon[1], b[1]), gs);
            if (k != null) {
                // Striche von 4 Einheiten mit 2 Lücke, in Weiss, das auf jeder Karte zu sehen ist.
                for (int x = k[0]; x < k[2]; x += 6 * gs) {
                    g.fill(x, k[1], Math.min(x + 4 * gs, k[2]), k[1] + gs, VORSCHAU);
                    g.fill(x, k[3] - gs, Math.min(x + 4 * gs, k[2]), k[3], VORSCHAU);
                }
                for (int y = k[1]; y < k[3]; y += 6 * gs) {
                    g.fill(k[0], y, k[0] + gs, Math.min(y + 4 * gs, k[3]), VORSCHAU);
                    g.fill(k[2] - gs, y, k[2], Math.min(y + 4 * gs, k[3]), VORSCHAU);
                }
            }
        }
        g.pose().popMatrix();
    }

    /**
     * Die Blöcke von (x0, z0) bis (x1, z1) samt beiden als Kasten {links, oben, rechts, unten} in Pixeln
     * des Schirms, gekappt knapp ausserhalb; null, wenn er den Schirm nicht berührt.
     */
    private int[] kasten(int x0, int z0, int x1, int z1, int gs) {
        int scale = satz.scale();
        double l = blick.rasterX(Projektion.zuPixel(x0, scale), width) * gs, r = blick.rasterX(Projektion.zuPixel(x1 + 1, scale), width) * gs;
        double o = blick.rasterY(Projektion.zuPixel(z0, scale), height) * gs, u = blick.rasterY(Projektion.zuPixel(z1 + 1, scale), height) * gs;
        if (r <= 0 || l >= width * gs || u <= 0 || o >= height * gs) {
            return null;
        }
        int rand = 2 * gs;
        return new int[] {(int) Math.max(-rand, Math.round(l)), (int) Math.max(-rand, Math.round(o)),
            (int) Math.min(width * gs + rand, Math.round(r)), (int) Math.min(height * gs + rand, Math.round(u))};
    }

    /** Der Block unter (x, y) des Schirms, so wie die Kacheln ihn zeichnen. */
    private int[] block(double x, double y) {
        return new int[] {Mth.floor(blick.basisRasterX(x, width) / satz.scale()), Mth.floor(blick.basisRasterZ(y, height) / satz.scale())};
    }

    /**
     * Linksklick auf eine Marke, beim Loslassen ohne Zug, legt sie in die Mitte, sobald kein zweiter
     * Klick mehr folgen kann; ein Doppelklick heftet sie an die Minimap oder löst sie, ohne die Karte zu bewegen, ebenso auf eine Nadel, ein Banner, eine Fläche oder einen Kreis vom Server.
     * Rechtsklick öffnet das Menü: „Hierher teleportieren“,
     * nur mit execute und tp im Befehlsbaum und nicht unter einer Decke, sonst landete man auf dem
     * Dach; darunter „Wegpunkt setzen“, auf einem Wegpunkt „Wegpunkt löschen“. Erst ein Klick auf
     * einen Eintrag tut etwas. Nach „Region von hier“ setzt ein Linksklick ohne Zug die zweite Ecke. Siehe docs/vollbildkarte.md, „Bedienung“;
     * Marken: siehe docs/wegpunkte.md, „Bedienung“.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doppelt) {
        // Jeder Klick, der true gibt, zählt für den nächsten Doppelklick; gemerkt bleibt nur ein Klick auf eine Marke oder ein Ziel mit id.
        Marke vorige = letzte;
        Tafeln.Ziel voriges = letztesZiel;
        letzte = null;
        letztesZiel = null;
        gedrueckt = null;
        taste = e.button() == InputConstants.MOUSE_BUTTON_LEFT;
        gezogen = 0;
        if (ziel != null) {
            // Mit der linken wie der rechten Taste: Wer rechts klickt, um zu öffnen, klickt oft auch rechts darauf.
            int zeile = Mth.floor((e.y() - menueY) / ZEILE);
            boolean treffer = e.x() >= menueX && e.x() < menueX + menueBreite() && e.y() >= menueY && zeile < eintraege.size();
            List<Eintrag> offen = eintraege;
            ziel = null;
            if (treffer) {
                offen.get(zeile).tut().run();
            }
            // Der Klick, der das Menü schliesst, tut sonst nichts, hält also auch keine Tafel.
            klickVerbraucht = true;
            return true;
        }
        // Ein Klick in die Tafel wirkt nicht auf die Karte.
        if (drin(tafelKasten, e.x(), e.y())) {
            klickVerbraucht = true;
            return true;
        }
        // Der zweite Klick eines Doppelklicks auf dasselbe Ziel heftet an oder löst, eine Nadel oder eine Fläche; die Tafel bleibt.
        if (doppelt && voriges != null && taste && getChildAt(e.x(), e.y()).isEmpty() && voriges.equals(tafelUnter(e.x(), e.y()))) {
            if (voriges.equals(nadelUnter(e.x(), e.y()))) {
                anheften(Wegpunkte.INSTANZ.umschaltenNadel(voriges.ebene(), voriges.id()), "heroicmap.karte.nadeln_voll",
                        Wegpunkte.MAX_NADELN_ANGEHEFTET);
            } else {
                anheften(Wegpunkte.INSTANZ.umschalten(voriges.ebene(), voriges.id()), "heroicmap.karte.angeheftet_voll", Wegpunkte.MAX_ANGEHEFTET);
            }
            klickVerbraucht = true;
            return true;
        }
        if (super.mouseClicked(e, doppelt)) {
            return true;
        }
        if (blick == null || minecraft.level == null) {
            return false;
        }
        Marke m = treffer(e.x(), e.y());
        if (e.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            // Der zweite zählt für die Marke des ersten, die noch nicht in die Mitte kam, und hebt das auf.
            doppelklick = doppelt && vorige != null;
            gedrueckt = doppelklick ? vorige : m;
            if (doppelklick) {
                wartend = null;
            }
            // true, sonst zählt das Spiel den nächsten Klick nicht als doppelt, auch auf einer Fläche; ziehen geht trotzdem.
            return true;
        }
        if (e.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            menue(e.x(), e.y(), m != null ? m.punkt() : null, m != null ? m.region() : null);
            return true;
        }
        return false;
    }

    /** Erst das Loslassen ohne Zug ist ein Klick auf die Marke: Wer auf ihr zu ziehen beginnt, zieht die Karte. */
    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        boolean knopf = super.mouseReleased(e);
        Marke m = gedrueckt;
        gedrueckt = null;
        taste = false;
        if (klickVerbraucht) {
            klickVerbraucht = false;
            return true;
        }
        // Solange die Vorschau läuft, setzt ein Linksklick ohne Zug die zweite Ecke, auch auf einer Marke; ziehen verschiebt weiter.
        if (regionVon != null && e.button() == InputConstants.MOUSE_BUTTON_LEFT && gezogen <= ZUG && blick != null
                && !drin(tafelKasten, e.x(), e.y())) {
            int[] b = block(e.x(), e.y());
            Wegpunkte.INSTANZ.setze(regionDimension, regionVon[0], regionVon[1], b[0], b[1]);
            regionVon = null;
            return true;
        }
        // Ein Klick ohne Zug auf ein Ziel ohne Marke merkt es für einen Doppelklick; eine Tafel hält er nicht.
        if (m == null && e.button() == InputConstants.MOUSE_BUTTON_LEFT && gezogen <= ZUG && ziel == null && blick != null
                && !drin(tafelKasten, e.x(), e.y())) {
            letztesZiel = tafelUnter(e.x(), e.y());
        }
        if (m == null || e.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return knopf;
        }
        if (!doppelklick) {
            wartend = m;
            wartendSeit = Util.getMillis();
            letzte = m;
        } else if (m.punkt() != null) {
            Wegpunkte.INSTANZ.umschalten(m.punkt());
        } else if (m.region() != null) {
            anheften(Wegpunkte.INSTANZ.umschalten(m.region()), "heroicmap.karte.angeheftet_voll", Wegpunkte.MAX_ANGEHEFTET);
        } else if (m.spieler() != null) {
            Wegpunkte.INSTANZ.umschalten(m.spieler());
        }
        return true;
    }

    /** Sagt unten links mit {@code text}, wenn schon {@code grenze} angeheftet sind und nichts geschah. */
    private void anheften(boolean geschehen, String text, int grenze) {
        if (!geschehen) {
            hinweis = Component.translatable(text, grenze);
        }
    }

    /**
     * Die Tafel des Ziels unter dem Zeiger: Zeigen je Frame, dann, wenn eine offen ist, die Tafel neben
     * der Stelle, an der sie aufging, wie die Tooltips des Spiels; solange die Antwort aussteht, eine
     * kleine Tafel „lädt …“. Siehe docs/ebenen.md, „Infotafel“.
     */
    private void tafel(GuiGraphicsExtractor g, int mausX, int mausY) {
        long ms = Util.getMillis();
        boolean ueber = drin(tafelKasten, mausX, mausY);
        zeigen.zeiger(ziel == null && !ueber && !(taste && gezogen > ZUG) ? zielUnter(mausX, mausY) : null, ueber, ms);
        if (zeigen.offen() != null && !Tafeln.gilt(Ebenen.INSTANZ, zeigen.offen())) {
            zeigen.zu();
        }
        Optional<Tafel> t = zeigen.offen() == null ? null : Tafeln.INSTANZ.tafel(zeigen.offen(), ms);
        zeigen.antwort(t);
        Tafeln.Ziel offen = zeigen.offen();
        if (!Objects.equals(offen, tafelOffen)) {
            tafelOffen = offen;
            tafelX = mausX;
            tafelY = mausY;
            tafelScroll = 0;
            tafelSeit = ms;
        }
        tafelKasten = null;
        tafelZuHoch = false;
        if (offen == null || t == null && ms - tafelSeit < Tafeln.LAEDT_MS) {
            return;
        }
        Tafel.Satz s = t == null ? laedt() : satz(t.get());
        int w = s.breite() + 2 * Tafel.INNEN, voll = s.hoehe() + 2 * Tafel.INNEN, h = Math.min(voll, height - 8);
        // Rechts unter dem Zeiger; ist dort kein Platz, links von ihm oder über ihm, so rutscht sie nicht unter ihn.
        int x = tafelX + 12 + w > width - 4 ? tafelX - 12 - w : tafelX + 12;
        int y = tafelY + 12 + h > height - 4 ? tafelY - 12 - h : tafelY + 12;
        x = Math.max(4, Math.min(x, width - w - 4));
        y = Math.max(4, Math.min(y, height - h - 4));
        tafelZuHoch = voll > h;
        tafelScroll = Math.max(0, Math.min(tafelScroll, voll - h));
        g.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath(HeroicMap.ID, "rahmen/" + tafelSkin() + "/tafel"), x, y, w, h);
        g.enableScissor(x + Tafel.INNEN, y + Tafel.INNEN, x + w - Tafel.INNEN, y + h - Tafel.INNEN);
        for (Tafel.Stueck stueck : s.stuecke()) {
            switch (stueck) {
                case Tafel.Text text -> g.text(font, stil(text.text(), text.fett()), x + Tafel.INNEN + text.x(),
                        y + Tafel.INNEN + text.y() - tafelScroll, text.farbe(), false);
                case Tafel.Punkt p -> {
                    int px = x + Tafel.INNEN + p.x(), py = y + Tafel.INNEN + p.y() - tafelScroll;
                    g.fill(px, py, px + p.seite(), py + p.seite(), p.farbe());
                }
                case Tafel.Bildstueck b -> {
                    int bx = x + Tafel.INNEN + b.x(), by = y + Tafel.INNEN + b.y() - tafelScroll;
                    // In Pixeln des Schirms: Grösser als sein Kasten ist das Bild schon auf ihn verkleinert, Pixel auf Pixel;
                    // kleiner vergrössert der Mod es um einen ganzen Faktor, abgerundet, so bleibt es im Kasten.
                    int gs = minecraft.getWindow().getGuiScale(), pw = b.breite() * gs, ph = b.hoehe() * gs;
                    Symbole.Textur bild = Symbole.INSTANZ.tafelBild(offen.ebene(), offen.version(), b.bild().feld(), pw, ph);
                    if (bild != null) {
                        int f = Tafel.faktor(bild.breite(), bild.hoehe(), pw, ph);
                        g.pose().pushMatrix();
                        g.pose().translate(bx, by);
                        g.pose().scale(1f / gs);
                        g.blit(RenderPipelines.GUI_TEXTURED, bild.id(), 0, 0, 0, 0, bild.breite() * f, bild.hoehe() * f, bild.breite(),
                                bild.hoehe(), bild.breite(), bild.hoehe());
                        g.pose().popMatrix();
                    } else if (b.bild().alt() != null) {
                        // Umbrochen in der Breite, gekappt auf die Höhe des Bilds.
                        int zy = by;
                        for (FormattedCharSequence zeile : font.split(Component.literal(b.bild().alt()), b.breite())) {
                            if (zy + font.lineHeight > by + b.hoehe()) {
                                break;
                            }
                            g.text(font, zeile, bx, zy, Tafel.SCHRIFT, false);
                            zy += font.lineHeight;
                        }
                    }
                }
            }
        }
        g.disableScissor();
        tafelKasten = new int[] {x, y, w, h};
    }

    /** Die kleine Tafel, solange die Antwort des Plugins aussteht. */
    private Tafel.Satz laedt() {
        String text = Component.translatable("heroicmap.tafel.laedt").getString();
        return new Tafel.Satz(List.of(new Tafel.Text(0, 0, text, Tafel.SCHRIFT, false)), font.width(text), font.lineHeight + 1);
    }

    /** Der Satz der offenen Tafel, neu gesetzt nur, wenn sich Tafel, GUI-Massstab oder Schrift ändern. */
    private Tafel.Satz satz(Tafel t) {
        int gs = minecraft.getWindow().getGuiScale();
        if (t != satzVon || gs != satzGs || Formen.generation != satzGeneration) {
            satzVon = t;
            satzGs = gs;
            satzGeneration = Formen.generation;
            tafelSatz = Tafel.setze(t, masse());
        }
        return tafelSatz;
    }

    /** Das Ziel unter dem Zeiger, neu gesucht nur, wenn sich Zeiger, Ansicht, Ebenen, Bilder oder Schrift ändern. */
    private Tafeln.Ziel zielUnter(double mx, double my) {
        if (blick == null) {
            return null;
        }
        double[] jetzt = {mx, my, blick.mx, blick.mz, blick.zoom, blick.lupe, width, height, minecraft.getWindow().getGuiScale(),
            Ebenen.INSTANZ.stand(), Symbole.INSTANZ.stand(), Formen.generation};
        if (!Arrays.equals(jetzt, suche)) {
            suche = jetzt;
            gefunden = tafelUnter(mx, my);
        }
        return gefunden;
    }

    /** Das Ziel unter dem Zeiger: eine Nadel oder ein Banner, die spätere über der früheren, sonst die oberste Fläche oder der oberste Kreis. */
    private Tafeln.Ziel tafelUnter(double mx, double my) {
        Tafeln.Ziel nadel = nadelUnter(mx, my);
        return nadel != null ? nadel : formUnter(mx, my);
    }

    /** Die Nadel oder das Banner mit {@code id} unter dem Zeiger, die spätere über der früheren; sonst null. */
    private Tafeln.Ziel nadelUnter(double mx, double my) {
        if (minecraft.player == null || satz == null) {
            return null;
        }
        String dimension = minecraft.player.level().dimension().identifier().toString();
        int scale = satz.scale(), gs = minecraft.getWindow().getGuiScale();
        Tafeln.Ziel treffer = null;
        for (Ebenen.Eintrag e : Ebenen.INSTANZ.sichtbar()) {
            for (Ebenen.Ort o : Ebenen.INSTANZ.nadeln(e.id())) {
                if (o.id() == null || !o.dimension().equals(dimension)) {
                    continue;
                }
                // Der Fuss wie gezeichnet; erst der Kasten ohne Holen, dann der genaue, denn der holt das Bild eines Banners.
                float x = aufPixel(blick.rasterX(Projektion.zuPixel(o.x(), scale), width), gs);
                float y = aufPixel(blick.rasterY(Projektion.zuPixel(o.z(), scale), height), gs);
                if (!drin(Ebenen.kastenOhneHolen(font, o), x, y, mx, my)) {
                    continue;
                }
                float[] m = Ebenen.kasten(font, o, gs);
                if (m != null && drin(m, x, y, mx, my)) {
                    // Die version der gezeichneten Daten, nicht die der Liste; die kann schon neuer sein.
                    treffer = Tafeln.ziel(e.id(), o);
                }
            }
        }
        return treffer;
    }

    /** Die oberste Fläche oder der oberste Kreis mit {@code id} unter dem Zeiger; sonst null. */
    private Tafeln.Ziel formUnter(double mx, double my) {
        if (minecraft.player == null || satz == null) {
            return null;
        }
        String dimension = minecraft.player.level().dimension().identifier().toString();
        int scale = satz.scale();
        double wx = blick.basisRasterX(mx, width) / scale, wz = blick.basisRasterZ(my, height) / scale;
        List<Ebenen.Eintrag> ebenen = Ebenen.INSTANZ.sichtbar();
        for (int i = ebenen.size() - 1; i >= 0; i--) {
            List<Ebenen.Form> formen = Ebenen.INSTANZ.formen(ebenen.get(i).id());
            for (int j = formen.size() - 1; j >= 0; j--) {
                Ebenen.Form f = formen.get(j);
                String id = Ebenen.id(f);
                if (id != null && f.dimension().equals(dimension) && Tafeln.trifft(f, wx, wz)) {
                    return Tafeln.ziel(Ebenen.INSTANZ, ebenen.get(i).id(), id);
                }
            }
        }
        return null;
    }

    /** Der 9-Slice der Tafel zum Rahmen der Minimap; „biom“ und „ohne“ nehmen den schlichten. */
    private static String tafelSkin() {
        String skin = Minimap.INSTANZ.skin();
        return Skin.NAMEN.contains(skin) && !"biom".equals(skin) ? skin : Skin.OHNE;
    }

    private static Component stil(String text, boolean fett) {
        return fett ? Component.literal(text).withStyle(ChatFormatting.BOLD) : Component.literal(text);
    }

    /** Die Masse der Schrift des Spiels für {@link Tafel#setze}. */
    private Tafel.Masse masse() {
        return new Tafel.Masse() {
            @Override
            public int breite(String text, boolean fett) {
                return font.width(stil(text, fett));
            }

            @Override
            public List<String> umbruch(String text, int breite, boolean fett) {
                return font.getSplitter().splitLines(text, breite, fett ? Style.EMPTY.withBold(true) : Style.EMPTY).stream()
                        .map(FormattedText::getString).toList();
            }

            @Override
            public int zeile() {
                return font.lineHeight + 1;
            }
        };
    }

    /** Liegt (mx, my) im Kasten {links, oben, rechts, unten} relativ zum Fuss (x, y)? */
    private static boolean drin(float[] k, float x, float y, double mx, double my) {
        return mx >= x + k[0] && mx < x + k[2] && my >= y + k[1] && my < y + k[3];
    }

    private static boolean drin(int[] kasten, double x, double y) {
        return kasten != null && x >= kasten[0] && x < kasten[0] + kasten[2] && y >= kasten[1] && y < kasten[1] + kasten[3];
    }

    /** Öffnet das Menü an (x, y) für den Block dort, oder für den Wegpunkt {@code punkt}. */
    private void menue(double x, double y, Wegpunkte.Punkt punkt, Wegpunkte.Region marke) {
        int[] z = punkt != null ? new int[] {punkt.x(), punkt.z()} : block(x, y);
        String dimension = minecraft.level.dimension().identifier().toString();
        Wegpunkte.Region region = marke != null ? marke : Wegpunkte.INSTANZ.region(dimension, z[0], z[1]);
        List<Eintrag> neu = new ArrayList<>();
        if (minecraft.getConnection() != null && !minecraft.level.dimensionType().hasCeiling()
                && Teleport.erlaubt(minecraft.getConnection().getCommands())) {
            neu.add(new Eintrag(Component.translatable("heroicmap.karte.teleport", z[0], z[1]), () -> {
                minecraft.getConnection().sendCommand(Teleport.befehl(dimension, z[0], z[1]));
                onClose();
            }));
        }
        if (regionVon != null) {
            // Die zweite Ecke: Erst ein Klick auf den Eintrag setzt die Region, Escape bricht ab.
            int[] von = regionVon;
            neu.add(new Eintrag(Component.translatable("heroicmap.karte.region_bis"), () -> {
                Wegpunkte.INSTANZ.setze(dimension, von[0], von[1], z[0], z[1]);
                regionVon = null;
            }));
            neu.add(new Eintrag(Component.translatable("heroicmap.karte.region_abbrechen"), () -> regionVon = null));
        } else {
            neu.add(punkt != null
                    ? new Eintrag(Component.translatable("heroicmap.karte.wegpunkt_loeschen"), () -> Wegpunkte.INSTANZ.loesche(punkt))
                    : new Eintrag(Component.translatable("heroicmap.karte.wegpunkt"), () -> Wegpunkte.INSTANZ.setze(dimension, z[0], z[1])));
            neu.add(new Eintrag(Component.translatable("heroicmap.karte.region_von"), () -> {
                regionVon = z;
                regionDimension = dimension;
            }));
            if (region != null) {
                neu.add(new Eintrag(Component.translatable("heroicmap.karte.region_loeschen"), () -> Wegpunkte.INSTANZ.loesche(region)));
            }
        }
        ziel = z;
        eintraege = neu;
        // Das Menü bleibt ganz auf dem Schirm, auch bei grossem GUI-Massstab.
        menueX = Math.max(0, Math.min(Mth.floor(x), width - menueBreite()));
        menueY = Math.max(0, Math.min(Mth.floor(y), height - neu.size() * ZEILE));
    }

    /**
     * Solange eine Kachel lädt, ihr Ausschnitt aus der nächsten gröberen Stufe, die schon geladen
     * ist, vergrössert; nichts wird dafür angefragt. Siehe docs/vollbildkarte.md, „Kacheln“.
     */
    private void platzhalter(GuiGraphicsExtractor g, int tx, int ty, int sx, int sy, int seite) {
        for (int k = 1; blick.zoom - k >= satz.minZoom(); k++) {
            int[] grob = Kartenblick.grob(tx, ty, k, satz.kachel());
            if (grob == null) {
                return;
            }
            Identifier id = kacheln.vorhanden(blick.zoom - k, grob[0], grob[1]);
            if (id != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, id, sx, sy, grob[2], grob[3], seite, seite, grob[4], grob[4],
                        satz.kachel(), satz.kachel());
                return;
            }
        }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent ereignis, double dx, double dy) {
        // Ein Zug, der in der Tafel beginnt oder mit dem Druck, der sie schloss, schiebt die Karte nicht.
        if (klickVerbraucht) {
            return true;
        }
        // Die Tasten zählen wie in SDL, links ist 1. Siehe docs/entwicklung.md, „Maustasten“.
        if (blick != null && ereignis.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            gezogen += Math.abs(dx) + Math.abs(dy);
            if (gezogen > ZUG) {
                gedrueckt = null;
            }
            blick.schiebe(dx, dy);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double weitX, double weitY) {
        if (drin(tafelKasten, x, y) && tafelZuHoch) {
            tafelScroll -= (int) Math.round(weitY * 10);
            return true;
        }
        if (blick == null) {
            return false;
        }
        if (weitY > 0) {
            blick.naeher();
        } else if (weitY < 0) {
            blick.ferner();
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent ereignis) {
        // Escape bricht zuerst eine Region ab, die der Spieler setzt.
        if (ereignis.key() == InputConstants.KEY_ESCAPE && regionVon != null) {
            regionVon = null;
            return true;
        }
        if (HeroicMap.karte != null && HeroicMap.karte.matches(ereignis)) {
            onClose();
            return true;
        }
        return super.keyPressed(ereignis);
    }

    @Override
    public void onClose() {
        if (kacheln != null) {
            kacheln.close();
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
