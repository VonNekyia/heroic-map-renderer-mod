package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.joml.Matrix3x2fStack;

/**
 * Das Menü hinter {@code /hmap}: Minimap an oder aus, Mitspieler, die Karten des Servers und die
 * auf der Platte, dazu das Untermenü „Einstellungen …“ für Vorlieben der Anzeige ({@link Anzeige}).
 * Die Minimap im HUD bleibt sichtbar; Ziehen verschiebt sie, der Griff an der Ecke zur Mitte des
 * Schirms zieht sie grösser oder kleiner, mit der linken wie der rechten Taste. Neben ihr steht der
 * Zoom als senkrechter Regler ({@link ZoomRegler}), darunter die Auflösung ({@link AufloesungRegler}), im L um sie
 * dazu Nordpfeil, Form und Raster ({@link Symbol}, {@link #lLage}); ein Klick auf die Koordinaten wechselt ihren Stil. Ein Klick auf den Spieler oder eine Marke stellt ihr Aussehen ein
 * ({@link MinimapKlick}). Die Knöpfe passen sich dem Platz an und gehen nach dem
 * Ziehen auf die freie Seite. Gespeichert wird beim Schliessen.
 * Siehe docs/minimap.md, „Bedienung“.
 */
final class Einstellungen extends Screen {

    private static final int TEXT = 0xFFFFFFFF;
    /** Halbe Seite des Griffs in Einheiten des GUI. */
    private static final int GRIFF = 3;
    /** Breite der Knöpfe, höchstens; schmaler, wenn neben der Minimap weniger Platz ist. */
    private static final int BREITE = 200;
    /** Breite des senkrechten Reglers „Zoom“, so breit wie ein Regler des Spiels hoch ist. */
    static final int ZOOM_BREITE = 20;
    /** Höhe des Reglers „Auflösung“, wie ein Regler des Spiels. */
    static final int REGLER_HOEHE = 20;
    /** Seite eines Symbols an der Minimap, so breit wie der Regler „Zoom“. */
    static final int SYMBOL = 20;
    /** Die Höhe einer Zeile in der Liste „Widget hinzufügen“. */
    static final int LISTE_ZEILE = 12;

    /**
     * KLICK: gedrückt auf Spieler oder Marke; wandert die Maus weiter als {@link MinimapKlick#WEG}, wird daraus LAGE. WIDGET:
     * links gedrückt auf ein Widget; wandert die Maus weiter als {@link MinimapKlick#WEG}, zieht sie es.
     */
    private enum Zug { KEINER, KLICK, LAGE, GROESSE, WIDGET }

    private Zug zug = Zug.KEINER;
    /** Der Wähler der Rahmen über dem Menü; offen nimmt er alle Klicks und Esc. Siehe docs/rahmen.md, „Wähler“. */
    private final Waehler waehler = new Waehler();
    private final MinimapKlick klick = new MinimapKlick(waehler);

    /** Für die Gametests. */
    Waehler waehler() {
        return waehler;
    }

    /** Für die Gametests: die offene Liste „Widget hinzufügen“, {x, y, Breite, Höhe}, oder null. */
    int[] liste() {
        return liste == null ? null : liste.clone();
    }
    /** Beim Verschieben: wo die Maus die Minimap gegriffen hat. Beim Ziehen: die feste Ecke. */
    private int festX, festY;
    /** Beim Ziehen: liegt der Griff links oder oben? */
    private boolean griffLinks, griffOben;
    /** Beim Ziehen: Seite und Maus beim Greifen. */
    private int startSeite, startX, startY;
    /** Das Widget unter der Maus, mit seinem ×, oder -1; beim Ziehen eines Widgets seine Stelle in der Liste. */
    private int ueberWidget = -1, widgetZug = -1;
    /** Beim Ziehen eines Widgets: die Liste davor und seine Stelle darin, wo die Maus es griff, und ob sie schon zog. */
    private List<Widgets.Widget> vorZug = List.of();
    private int vorStelle, widgetGriffX, widgetGriffY;
    private double widgetStartX, widgetStartY;
    private boolean widgetGezogen;
    /** Warum Mitspieler hier nicht gehen, beim Bauen der Knöpfe, oder null ({@link Mitspieler#grund}). */
    private String grund;
    /** Linker Rand, Oberkante und Breite der Knöpfe. */
    private int spalte, oben, breite;
    private ZoomRegler zoom;
    private AufloesungRegler aufloesung;
    private Symbol nord, form, raster;
    /** Das „+“ im L unter der Form: öffnet die Liste der Arten; die neue kommt unten in den Stapel. */
    private Button plus;
    /**
     * Die Liste „Widget hinzufügen“, offen {x, y, Breite, Höhe}, sonst null; offen nimmt sie alle Klicks und Esc. Dazu, wo der
     * Rechtsklick sie öffnete, denn die neue fällt dorthin; null vom „+“. Siehe docs/widgets.md, „Hinzufügen“.
     */
    private int[] liste;
    private int[] listeMaus;

    /** Wohin „Fertig“ und Escape führen: die Vollbildkarte, von der das Menü kam, oder null fürs Spiel. */
    private final Screen zurueck;

    Einstellungen() {
        this(null);
    }

    Einstellungen(Screen zurueck) {
        super(Component.translatable("heroicmap.menue.titel"));
        this.zurueck = zurueck;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(zurueck);
    }

    @Override
    protected void init() {
        Minimap m = Minimap.INSTANZ;
        int[] s = spalte(width, height);
        spalte = s[0];
        breite = s[1];
        oben = Math.max(50, height / 2 - 48);
        int x = spalte, y = oben, halb = (breite - 4) / 2;
        // Das L zuerst gezeichnet und zuletzt gefragt: Ist neben der Minimap zu wenig Platz, liegen die Knöpfe über ihm und
        // bekommen den Klick, wie vorher über der Minimap. Siehe docs/minimap.md, „Bedienung“.
        int[] z = zoomLage(m.rahmen(width, height), m.rand(), width);
        zoom = addRenderableOnly(new ZoomRegler(z[0], z[1], z[2], m));
        int[] a = aufloesungLage(m.rahmen(width, height), m.stapelKasten(), m.rand(), height);
        aufloesung = addRenderableOnly(new AufloesungRegler(a[0], a[1], a[2], m));
        nord = addRenderableOnly(new Symbol(Symbol.NORD));
        form = addRenderableOnly(new Symbol(Symbol.FORM));
        raster = addRenderableOnly(new Symbol(Symbol.RASTER));
        plus = addRenderableOnly(Button.builder(Component.literal("+"), b -> oeffneListe(b.getX(), b.getY() + SYMBOL + 2, b.getY() - 2, null))
                .size(SYMBOL, SYMBOL).tooltip(Tooltip.create(Component.translatable("heroicmap.menue.widget.neu"))).build());
        lege();
        // Je zwei Knöpfe in einer Zeile; Vorlieben der Anzeige stehen im Untermenü.
        addRenderableWidget(CycleButton.onOffBuilder(m.sichtbar())
                .create(x, y, breite, 20, Component.translatable("heroicmap.menue.minimap"), (b, an) -> m.setzeSichtbar(an)));
        // Gehen Mitspieler hier nicht, steht der Knopf rot und trägt den Grund als Tooltip.
        grund = Mitspieler.grund(Kanal.offen(), Mitspieler.INSTANZ.verweigert());
        CycleButton<Boolean> show = addRenderableWidget(CycleButton.booleanBuilder(rot(Component.translatable("heroicmap.menue.show.simplevoicechat")),
                        rot(Component.translatable("heroicmap.menue.show.hidden")), m.show())
                .create(x, y + 24, breite, 20, rot(Component.translatable("heroicmap.menue.show")), (b, an) -> {
                    m.setzeShow(an);
                    Kanal.sendeShow();
                }));
        show.setTooltip(grund == null ? null : Tooltip.create(Component.translatable("heroicmap.menue.show.grund." + grund)));
        addRenderableWidget(Button.builder(Component.translatable("heroicmap.karte.laden"),
                b -> minecraft.gui.setScreen(new Auswahl(this))).bounds(x, y + 48, halb, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("heroicmap.menue.liste"),
                b -> minecraft.gui.setScreen(new Kartenliste(this))).bounds(x + breite - halb, y + 48, halb, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("heroicmap.menue.einstellungen"),
                b -> minecraft.gui.setScreen(new Anzeige(this))).bounds(x, y + 76, halb, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(x + breite - halb, y + 76, halb, 20).build());
        for (AbstractWidget w : List.<AbstractWidget>of(zoom, aufloesung, nord, form, raster, plus)) {
            addWidget(w);
        }
    }

    private MutableComponent rot(MutableComponent text) {
        return grund == null ? text : text.withStyle(ChatFormatting.RED);
    }

    /** Meldet der Server den Kanal oder antwortet auf show erst nach dem Öffnen, baut das Menü die Knöpfe neu. */
    @Override
    public void tick() {
        if (!Objects.equals(grund, Mitspieler.grund(Kanal.offen(), Mitspieler.INSTANZ.verweigert()))) {
            rebuildWidgets();
        }
    }

    /**
     * Linker Rand und Breite der Knöpfe: mittig im grösseren freien Platz neben der Minimap und ihrem
     * Regler „Zoom“, nicht auf ihnen; bei grossem GUI-Massstab ist der Schirm schmal, dann werden die
     * Knöpfe schmaler.
     */
    static int[] spalte(int width, int height) {
        Minimap m = Minimap.INSTANZ;
        Minimap.Rahmen r = m.rahmen(width, height);
        int zx = zoomLage(r, m.rand(), width)[0];
        int von = Math.min(r.x(), zx), bis = Math.max(r.x() + r.seite(), zx + ZOOM_BREITE);
        int links = von, rechts = width - bis;
        int platz = !m.sichtbar() ? width : Math.max(links, rechts);
        int breite = Math.max(120, Math.min(BREITE, platz - 8));
        int spalte = !m.sichtbar() ? (width - breite) / 2
                : links >= rechts ? (links - breite) / 2 : bis + (rechts - breite) / 2;
        return new int[] {Math.max(4, Math.min(spalte, width - breite - 4)), breite};
    }

    /**
     * Wo der Regler „Auflösung“ steht, {x, y, Breite}: so breit wie die Minimap {@code r}, auf der Seite der Zeile der
     * Koordinaten {@code kasten} hinter ihr, 2 Einheiten Abstand; ohne Zeile unter der Minimap im Abstand {@code abstand}.
     * Passt er dort nicht auf den Schirm, steht er auf der anderen Seite; passt er auch dort nicht, innen an ihrem unteren
     * Rand, über der Zeile, wenn diese auf ihr liegt. Siehe docs/minimap.md, „Bedienung“.
     */
    static int[] aufloesungLage(Minimap.Rahmen r, int[] kasten, int abstand, int hoehe) {
        boolean zeileOben = kasten != null && kasten[1] + kasten[3] <= r.y();
        boolean zeileUnten = kasten != null && kasten[1] >= r.y() + r.seite();
        int unten = zeileUnten ? kasten[1] + kasten[3] + 2 : r.y() + r.seite() + abstand + 2;
        int oben = (zeileOben ? kasten[1] : r.y() - abstand) - 2 - REGLER_HOEHE;
        int innen = (kasten != null && !zeileOben && !zeileUnten ? Math.min(kasten[1] - 2, r.y() + r.seite()) : r.y() + r.seite())
                - REGLER_HOEHE;
        boolean untenPasst = unten + REGLER_HOEHE <= hoehe, obenPasst = oben >= 0;
        int y = zeileOben ? (obenPasst ? oben : untenPasst ? unten : innen) : untenPasst ? unten : obenPasst ? oben : innen;
        return new int[] {r.x(), y, r.seite()};
    }

    /** Legt das L um die Minimap ({@link #lLage}); ist sie aus, fehlt es. */
    private void lege() {
        Minimap m = Minimap.INSTANZ;
        int[][] l = lLage(m.rahmen(width, height), m.stapelKasten(), m.rand(), width, height);
        AbstractWidget[] w = {nord, zoom, form, aufloesung, raster, plus};
        for (int i = 0; i < w.length; i++) {
            w[i].setRectangle(l[i][2], l[i][3], l[i][0], l[i][1]);
            w[i].visible = m.sichtbar();
        }
        plus.active = m.widgets().size() < Widgets.MAX;
    }

    /**
     * Das L um die Minimap {@code r} im Menü, je {x, y, Breite, Höhe}: Nordpfeil, Zoom, Form, Auflösung, Raster, „+“. Die
     * Spalte des Zooms ({@link #zoomLage}) trägt an ihrem Ende fern der Reihe den Nordpfeil, die Reihe der Auflösung
     * ({@link #aufloesungLage}) an ihrem Ende fern der Spalte das Raster; in der Ecke zwischen beiden die Form. Liegt die
     * Spalte innen in der Reihe oder die Reihe innen in der Spalte, rückt die andere um die Ecke weiter. Das „+“ steht hinter
     * der Form, fern der Spalte; passt es dort nicht auf den Schirm, neben ihr, fern der Minimap, sonst davor, und die Spalte
     * wird um es kürzer. Siehe docs/minimap.md, „Symbole an der Minimap“.
     */
    static int[][] lLage(Minimap.Rahmen r, int[] kasten, int rand, int width, int height) {
        int[] z = zoomLage(r, rand, width), a = aufloesungLage(r, kasten, rand, height);
        int s = SYMBOL + 2;
        // Waagrecht: die Reihe ohne die Ecke, wenn die Spalte in ihr liegt; das Raster am Ende fern der Spalte.
        boolean eckeInReihe = z[0] >= a[0] && z[0] < a[0] + a[2];
        int von = eckeInReihe ? a[0] + s : a[0], bis = a[0] + a[2];
        boolean rasterRechts = z[0] <= a[0];
        // Senkrecht ebenso: die Spalte ohne die Ecke, wenn die Reihe in ihr liegt; der Nordpfeil am Ende fern der Reihe.
        int oben = z[1], unten = z[1] + z[2];
        boolean nordOben = a[1] + REGLER_HOEHE / 2 >= z[1] + z[2] / 2;
        if (a[1] >= z[1] && a[1] < z[1] + z[2]) {
            if (nordOben) {
                unten = a[1] - 2;
            } else {
                oben = a[1] + REGLER_HOEHE + 2;
            }
        }
        // Das „+“ hinter der Form, fern der Spalte; sonst neben ihr, fern der Minimap; sonst davor, und die Spalte wird kürzer.
        int plusX = z[0], plusY = nordOben ? a[1] + s : a[1] - s;
        if (plusY < 0 || plusY + SYMBOL > height) {
            plusX = z[0] > r.x() ? z[0] + s : z[0] - s;
            plusY = a[1];
            if (plusX < 0 || plusX + SYMBOL > width) {
                plusX = z[0];
                plusY = nordOben ? a[1] - s : a[1] + s;
                unten = nordOben ? Math.min(unten, plusY - 2) : unten;
                oben = nordOben ? oben : Math.max(oben, plusY + SYMBOL + 2);
            }
        }
        return new int[][] {
            {z[0], nordOben ? oben : unten - SYMBOL, SYMBOL, SYMBOL},
            {z[0], nordOben ? oben + s : oben, ZOOM_BREITE, unten - oben - s},
            {z[0], a[1], SYMBOL, SYMBOL},
            {rasterRechts ? von : von + s, a[1], bis - von - s, REGLER_HOEHE},
            {rasterRechts ? bis - SYMBOL : von, a[1], SYMBOL, SYMBOL},
            {plusX, plusY, SYMBOL, SYMBOL},
        };
    }

    /**
     * Das Widget unter (x, y), oder -1: zuerst das × des Widgets unter der Maus, dann das zuletzt gezeichnete, frei über oben
     * über unten. Siehe docs/widgets.md, „Bedienung“.
     */
    private int widgetUnter(double x, double y) {
        int[][] k = Minimap.INSTANZ.widgetKaesten();
        if (ueberWidget >= 0 && ueberWidget < k.length && k[ueberWidget] != null && imKasten(Widgets.kreuz(k[ueberWidget]), x, y)) {
            return ueberWidget;
        }
        for (int i = k.length - 1; i >= 0; i--) {
            if (imKasten(k[i], x, y)) {
                return i;
            }
        }
        return -1;
    }

    /** Liegt (x, y) auf dem × des Widgets unter der Maus? */
    private boolean aufKreuz(double x, double y) {
        int[][] k = Minimap.INSTANZ.widgetKaesten();
        return ueberWidget >= 0 && ueberWidget < k.length && k[ueberWidget] != null && imKasten(Widgets.kreuz(k[ueberWidget]), x, y);
    }

    /** Das ×: dunkel mit hellem Rand, darin ein rotes Kreuz. */
    private static void kreuz(GuiGraphicsExtractor g, int[] k) {
        g.fill(k[0], k[1], k[0] + k[2], k[1] + k[3], 0xFF282828);
        g.outline(k[0], k[1], k[2], k[3], TEXT);
        for (int i = 2; i < k[2] - 2; i++) {
            g.fill(k[0] + i, k[1] + i, k[0] + i + 1, k[1] + i + 1, 0xFFFF5A5A);
            g.fill(k[0] + k[2] - 1 - i, k[1] + i, k[0] + k[2] - i, k[1] + i + 1, 0xFFFF5A5A);
        }
    }

    /**
     * Zieht das gegriffene Widget mit der Maus bei (mx, my): wohin es fällt ({@link Widgets#ziel}), die Liste neu aus der vor dem
     * Ziehen, so zeigt der Stapel die Lücke schon jetzt. Siehe docs/widgets.md, „Ziehen“.
     */
    private void ziehe(int mx, int my) {
        Minimap m = Minimap.INSTANZ;
        int[][] k = m.widgetKaesten();
        if (widgetZug < 0 || widgetZug >= k.length || k[widgetZug] == null) {
            return;
        }
        Widgets.Ziel ziel = zielBei(mx - widgetGriffX, my - widgetGriffY, k[widgetZug][2], k[widgetZug][3], widgetZug);
        Widgets.Gesetzt neu = Widgets.setze(vorZug, vorStelle, ziel);
        m.widgets(neu.liste());
        widgetZug = neu.stelle();
    }

    /** Wohin ein Widget mit der linken oberen Ecke bei (x, y) fällt ({@link Widgets#ziel}), die Stapel ohne das Widget {@code ohne}. */
    private Widgets.Ziel zielBei(int x, int y, int breite, int hoehe, int ohne) {
        Minimap m = Minimap.INSTANZ;
        int[][] k = m.widgetKaesten();
        List<Widgets.Widget> jetzt = m.widgets();
        List<int[]> unten = new ArrayList<>(), oben = new ArrayList<>();
        for (int i = 0; i < jetzt.size() && i < k.length; i++) {
            if (i != ohne && k[i] != null && jetzt.get(i).anker() == Widgets.Anker.UNTEN) {
                unten.add(k[i]);
            } else if (i != ohne && k[i] != null && jetzt.get(i).anker() == Widgets.Anker.OBEN) {
                oben.add(k[i]);
            }
        }
        Minimap.Rahmen r = m.rahmen(width, height);
        return Widgets.ziel(x, y, breite, hoehe, new int[] {r.x(), r.y(), r.seite()}, unten, oben, width, height, m.rand());
    }

    /**
     * Öffnet die Liste der Arten bei {@code x}, unter {@code unten} oder, passt sie dort nicht auf den Schirm, über {@code oben};
     * {@code maus}: wo die neue hin soll, null unten in den Stapel.
     */
    private void oeffneListe(int x, int unten, int oben, int[] maus) {
        int b = 0, h = Widgets.Art.values().length * LISTE_ZEILE + 4;
        for (Widgets.Art a : Widgets.Art.values()) {
            b = Math.max(b, font.width(artName(a)) + 8);
        }
        liste = new int[] {Math.clamp(x, 0, Math.max(0, width - b)), unten + h <= height ? unten : Math.max(0, oben - h), b, h};
        listeMaus = maus;
    }

    /** Die Zeile der offenen Liste bei (x, y), also die Stelle der Art in {@link Widgets.Art}, oder -1. */
    private int listeZeile(double x, double y) {
        if (liste == null || x < liste[0] || x >= liste[0] + liste[2] || y < liste[1] + 2) {
            return -1;
        }
        int i = Mth.floor((y - liste[1] - 2) / LISTE_ZEILE);
        return i < Widgets.Art.values().length ? i : -1;
    }

    /**
     * Ein Klick bei offener Liste schliesst sie; auf einer Art kommt ein Widget dieser Art in der ersten Version dazu: vom „+“
     * unten hinter den Stapel, vom Rechtsklick mit der Mitte dort, wo die Maus war, nahe der Minimap eingerastet wie beim Ziehen.
     */
    private void klickListe(double x, double y) {
        int i = listeZeile(x, y);
        int[] maus = listeMaus;
        liste = null;
        Minimap m = Minimap.INSTANZ;
        if (i < 0 || m.widgets().size() >= Widgets.MAX) {
            return;
        }
        Widgets.Widget w = new Widgets.Widget(Widgets.Art.values()[i], 0, Widgets.Anker.UNTEN);
        List<Widgets.Widget> neu = new ArrayList<>(m.widgets());
        neu.add(w);
        if (maus != null && minecraft.level != null && minecraft.player != null) {
            int breite = font.width(m.text(w, minecraft.level, minecraft.player)), hoehe = font.lineHeight;
            neu = Widgets.setze(neu, neu.size() - 1, zielBei(maus[0] - breite / 2, maus[1] - hoehe / 2, breite, hoehe, -1)).liste();
        }
        m.widgets(neu);
    }

    private static Component artName(Widgets.Art a) {
        return Component.translatable("heroicmap.menue." + a.name);
    }

    private static boolean imKasten(int[] kasten, double x, double y) {
        return kasten != null && x >= kasten[0] && x < kasten[0] + kasten[2] && y >= kasten[1] && y < kasten[1] + kasten[3];
    }

    /**
     * Wo der Regler „Zoom“ neben der Minimap {@code r} steht, {x, y, Höhe}: so hoch wie sie, links im Abstand {@code rand}
     * wie ihre Koordinaten; passt er dort nicht auf den Schirm, ebenso rechts; passt er auf keiner Seite, innen an ihrem
     * linken Rand. Siehe docs/minimap.md, „Bedienung“.
     */
    static int[] zoomLage(Minimap.Rahmen r, int rand, int width) {
        int links = r.x() - rand - ZOOM_BREITE, rechts = r.x() + r.seite() + rand;
        int x = links >= rand ? links : rechts + ZOOM_BREITE <= width - rand ? rechts : r.x();
        return new int[] {x, r.y(), r.seite()};
    }

    /** Ohne Unschärfe und Abdunkeln, damit die Minimap im HUD zu sehen ist. */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
        // Ist der Wähler offen, gilt die Maus nur ihm: das Menü darunter ohne Hover und Tooltip.
        int echtX = mausX, echtY = mausY;
        if (waehler.offen() || liste != null) {
            mausX = -1;
            mausY = -1;
        }
        // Das L folgt der Minimap schon beim Ziehen; die Knöpfe erst nach dem Loslassen.
        Minimap m = Minimap.INSTANZ;
        lege();
        zoom.folge(m.zoom());
        aufloesung.folge(m.aufloesung());
        // Das Widget unter der Maus steht gelb, ab dem nächsten Frame, mit seinem ×; ein Tooltip nennt die nächste Version.
        ueberWidget = m.sichtbar() && zug == Zug.KEINER ? widgetUnter(mausX, mausY) : zug == Zug.WIDGET ? widgetZug : -1;
        List<Widgets.Widget> widgets = m.widgets();
        m.hell(ueberWidget);
        if (zug == Zug.KEINER && ueberWidget >= 0 && ueberWidget < widgets.size() && !aufKreuz(mausX, mausY)) {
            Widgets.Widget w = widgets.get(ueberWidget);
            g.setTooltipForNextFrame(Component.translatable("heroicmap.menue.symbol.klick", artName(w.art()), stil(w), stil(w.naechsteVersion())),
                    mausX, mausY);
        }
        super.extractRenderState(g, mausX, mausY, delta);
        // Titel und Hinweis über den Knöpfen; unten läge der Chat darüber.
        int mitte = spalte + breite / 2;
        List<FormattedCharSequence> hinweis = font.split(Component.translatable("heroicmap.menue.ziehen"), breite);
        int y = oben - 6 - hinweis.size() * (font.lineHeight + 1);
        g.centeredText(font, title, mitte, y - 14, TEXT);
        for (FormattedCharSequence zeile : hinweis) {
            g.centeredText(font, zeile, mitte, y, TEXT);
            y += font.lineHeight + 1;
        }
        // Der Grund steht auch unter den Knöpfen, soweit der Schirm reicht.
        if (grund != null) {
            int zeileY = oben + 100;
            for (FormattedCharSequence zeile : font.split(Component.translatable("heroicmap.menue.show.grund." + grund), breite)) {
                if (zeileY + font.lineHeight > height) {
                    break;
                }
                g.centeredText(font, zeile, mitte, zeileY, TEXT);
                zeileY += font.lineHeight + 1;
            }
        }
        if (Minimap.INSTANZ.sichtbar()) {
            Minimap.Rahmen r = Minimap.INSTANZ.rahmen(width, height);
            // Mit Skin zeichnet die Minimap den Griff statt der zier dieser Ecke, ab dem nächsten Frame; der Umriss entfällt. Siehe docs/rahmen.md.
            Minimap.INSTANZ.griff(Minimap.griffEcke(r, width, height), zug == Zug.GROESSE || imGriff(mausX, mausY, r));
            if (Minimap.INSTANZ.skinJetzt() == null) {
                umriss(g, r);
                int gx = griffX(r), gy = griffY(r);
                g.fill(gx - GRIFF, gy - GRIFF, gx + GRIFF, gy + GRIFF, TEXT);
            }
            koordinaten(g, r, mausX, mausY);
        }
        // Zuletzt, über dem Umriss: halbe Marken, Zeiger und Tooltip; am Griff gilt der Griff.
        boolean frei = (zug == Zug.KEINER || zug == Zug.KLICK)
                && !(Minimap.INSTANZ.sichtbar() && imGriff(mausX, mausY, Minimap.INSTANZ.rahmen(width, height)));
        klick.zeichne(g, font, mausX, mausY, frei);
        // Das × über allem, auch über Knöpfen, nur solange die Maus auf dem Widget oder dem × ist.
        int[][] kaesten = Minimap.INSTANZ.widgetKaesten();
        if (zug == Zug.KEINER && ueberWidget >= 0 && ueberWidget < kaesten.length && kaesten[ueberWidget] != null) {
            kreuz(g, Widgets.kreuz(kaesten[ueberWidget]));
        }
        if (liste != null) {
            g.fill(liste[0], liste[1], liste[0] + liste[2], liste[1] + liste[3], 0xF0181818);
            g.outline(liste[0], liste[1], liste[2], liste[3], 0xFF5A5A5A);
            int hell = listeZeile(echtX, echtY);
            for (int i = 0; i < Widgets.Art.values().length; i++) {
                g.text(font, artName(Widgets.Art.values()[i]), liste[0] + 4, liste[1] + 4 + i * LISTE_ZEILE, i == hell ? 0xFFFFFF55 : TEXT, true);
            }
        }
        if (waehler.offen()) {
            waehler.zeichne(g, font, echtX, echtY, width, height);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent ereignis) {
        // Offen nimmt der Wähler jede Taste; Esc schliesst nur ihn, nicht das Menü.
        if (waehler.offen()) {
            if (ereignis.key() == InputConstants.KEY_ESCAPE) {
                waehler.schliesse();
            }
            return true;
        }
        // Ebenso die Liste „Widget hinzufügen“.
        if (liste != null) {
            if (ereignis.key() == InputConstants.KEY_ESCAPE) {
                liste = null;
            }
            return true;
        }
        // Esc beim Ziehen legt das Widget zurück; Entf oder Rücktaste löscht das unter der Maus. Siehe docs/widgets.md, „Bedienung“.
        if (zug == Zug.WIDGET && ereignis.key() == InputConstants.KEY_ESCAPE) {
            Minimap.INSTANZ.widgets(vorZug);
            zug = Zug.KEINER;
            widgetZug = -1;
            return true;
        }
        if (zug == Zug.KEINER && (ereignis.key() == InputConstants.KEY_DELETE || ereignis.key() == InputConstants.KEY_BACKSPACE)
                && ueberWidget >= 0 && ueberWidget < Minimap.INSTANZ.widgets().size()) {
            Minimap.INSTANZ.loescheWidget(ueberWidget);
            ueberWidget = -1;
            return true;
        }
        return super.keyPressed(ereignis);
    }

    /** Über der Minimap die Koordinaten des Blocks unter der Maus, fest unten links, genau wie gezeichnet: im Menü immer genordet. */
    private void koordinaten(GuiGraphicsExtractor g, Minimap.Rahmen r, int mausX, int mausY) {
        Minimap m = Minimap.INSTANZ;
        if (zug != Zug.KEINER || minecraft.player == null || !auf(r, mausX, mausY)) {
            return;
        }
        // In Pixeln des Schirms wie Minimap.zeichne.
        LocalPlayer p = minecraft.player;
        float a = Minimap.anteil(minecraft.level, p, minecraft.getDeltaTracker());
        int k = minecraft.getWindow().getGuiScale(), n = r.seite() * k;
        double block = m.zoom() * k;
        int links = Minimap.ecke(p.xo, p.getX(), a, m.zoom(), k, n), oben = Minimap.ecke(p.zo, p.getZ(), a, m.zoom(), k, n);
        double ix = (mausX - r.x()) * k, iy = (mausY - r.y()) * k;
        int bx = Mth.floor((links + ix) / block), bz = Mth.floor((oben + iy) / block);
        g.text(font, Component.translatable("heroicmap.koordinaten", bx, bz), 4, height - 12, TEXT);
    }

    private static Component stil(Widgets.Widget w) {
        return Component.translatable("heroicmap.menue." + w.art().name + "." + w.versionName());
    }

    /** Liegt (x, y) auf der Minimap {@code r}, rund im Kreis? */
    private static boolean auf(Minimap.Rahmen r, double x, double y) {
        double h = r.seite() / 2.0, dx = x - (r.x() + h), dz = y - (r.y() + h);
        return Minimap.INSTANZ.rund() ? dx * dx + dz * dz <= h * h : r.enthaelt(x, y);
    }

    /** Das Mausrad über der Minimap oder dem Regler „Zoom“: je Schritt eine Raste, nach vorn näher; über „Auflösung“ ebenso. */
    @Override
    public boolean mouseScrolled(double x, double y, double weitX, double weitY) {
        if (waehler.offen() || liste != null) {
            return true;
        }
        // Über einem Widget eine Stufe grösser oder kleiner, in ganzen Pixeln des Geräts je Pixel der Schrift.
        int w = weitY != 0 && zug == Zug.KEINER && Minimap.INSTANZ.sichtbar() ? widgetUnter(x, y) : -1;
        if (w >= 0) {
            Minimap.INSTANZ.setzeWidget(w, Minimap.INSTANZ.widgets().get(w).stufe(minecraft.getWindow().getGuiScale(), weitY > 0 ? 1 : -1));
            return true;
        }
        if (weitY != 0 && zug == Zug.KEINER && aufloesung.isMouseOver(x, y)) {
            aufloesung.schritt(weitY > 0 ? 1 : -1);
            return true;
        }
        if (weitY != 0 && zug == Zug.KEINER && Minimap.INSTANZ.sichtbar()
                && (zoom.isMouseOver(x, y) || auf(Minimap.INSTANZ.rahmen(width, height), x, y))) {
            zoom.schritt(weitY > 0 ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(x, y, weitX, weitY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doppelt) {
        if (waehler.offen()) {
            waehler.klick(e.x(), e.y(), width, height);
            return true;
        }
        if (liste != null) {
            klickListe(e.x(), e.y());
            return true;
        }
        // Vor den Knöpfen: das × des Widgets unter der Maus, dann das Widget, rechts die nächste Version, links greifen.
        // Siehe docs/widgets.md, „Bedienung“.
        if (Minimap.INSTANZ.sichtbar() && (e.button() == InputConstants.MOUSE_BUTTON_LEFT || e.button() == InputConstants.MOUSE_BUTTON_RIGHT)) {
            Minimap m = Minimap.INSTANZ;
            if (e.button() == InputConstants.MOUSE_BUTTON_LEFT && aufKreuz(e.x(), e.y()) && ueberWidget < m.widgets().size()) {
                m.loescheWidget(ueberWidget);
                ueberWidget = -1;
                return true;
            }
            int w = widgetUnter(e.x(), e.y());
            if (w >= 0 && w < m.widgets().size()) {
                if (e.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
                    m.setzeWidget(w, m.widgets().get(w).naechsteVersion());
                    return true;
                }
                int[] k = m.widgetKaesten()[w];
                zug = Zug.WIDGET;
                widgetZug = w;
                vorZug = m.widgets();
                vorStelle = w;
                widgetGriffX = Mth.floor(e.x()) - k[0];
                widgetGriffY = Mth.floor(e.y()) - k[1];
                widgetStartX = e.x();
                widgetStartY = e.y();
                widgetGezogen = false;
                return true;
            }
        }
        if (super.mouseClicked(e, doppelt)) {
            return true;
        }
        if (!Minimap.INSTANZ.sichtbar()) {
            return false;
        }
        // Die mittlere Taste nur auf eine Marke: durchsichtig oder zurück, beim Loslassen.
        if (e.button() == InputConstants.MOUSE_BUTTON_MIDDLE) {
            if (klick.druecke(e)) {
                zug = Zug.KLICK;
                return true;
            }
            return false;
        }
        Minimap.Rahmen r = Minimap.INSTANZ.rahmen(width, height);
        // Links wie rechts: Ziehen greift die ganze Minimap, am Griff ihre Grösse.
        if (e.button() != InputConstants.MOUSE_BUTTON_LEFT && e.button() != InputConstants.MOUSE_BUTTON_RIGHT) {
            return false;
        }
        if (imGriff(e.x(), e.y(), r)) {
            zug = Zug.GROESSE;
            griffLinks = links(r);
            griffOben = oben(r);
            festX = griffLinks ? r.x() + r.seite() : r.x();
            festY = griffOben ? r.y() + r.seite() : r.y();
            startSeite = r.seite();
            startX = Mth.floor(e.x());
            startY = Mth.floor(e.y());
            return true;
        }
        // Auf Spieler oder Marke entscheidet erst das Loslassen, ob es ein Klick war; Ziehen verschiebt wie sonst.
        boolean ziel = klick.druecke(e);
        if (ziel || r.enthaelt(e.x(), e.y())) {
            zug = ziel ? Zug.KLICK : Zug.LAGE;
            festX = Mth.floor(e.x()) - r.x();
            festY = Mth.floor(e.y()) - r.y();
            return true;
        }
        // Rechts auf freie Fläche: die Liste der Arten an der Maus; die neue fällt dorthin.
        if (e.button() == InputConstants.MOUSE_BUTTON_RIGHT && Minimap.INSTANZ.widgets().size() < Widgets.MAX) {
            int mx = Mth.floor(e.x()), my = Mth.floor(e.y());
            oeffneListe(mx, my, my, new int[] {mx, my});
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (waehler.offen()) {
            return true;
        }
        Minimap m = Minimap.INSTANZ;
        int mx = Mth.floor(e.x()), my = Mth.floor(e.y());
        switch (zug) {
            case KLICK -> {
                if (klick.gezogen(e)) {
                    zug = Zug.LAGE;
                    m.verschiebe(mx - festX, my - festY, width, height);
                }
            }
            case LAGE -> m.verschiebe(mx - festX, my - festY, width, height);
            case WIDGET -> {
                widgetGezogen |= Math.hypot(e.x() - widgetStartX, e.y() - widgetStartY) > MinimapKlick.WEG;
                if (widgetGezogen) {
                    ziehe(mx, my);
                }
            }
            case GROESSE -> {
                // Relativ zum Griff: Der Griff sitzt rund nicht in der Ecke, die Seite springt so nicht.
                int platz = Math.min(width, height) - 2 * m.rand();
                int weg = Math.max(griffLinks ? startX - mx : mx - startX, griffOben ? startY - my : my - startY);
                int s = Mth.clamp(startSeite + weg,
                        Minimap.KLEINSTE, Math.max(Minimap.KLEINSTE, Math.min(Minimap.GROESSTE, platz)));
                m.stelle(griffLinks ? festX - s : festX, griffOben ? festY - s : festY, s, width, height);
            }
            case KEINER -> {
                return super.mouseDragged(e, dx, dy);
            }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (waehler.offen() && zug == Zug.KEINER) {
            return true;
        }
        // Links geklickt ohne Ziehen: die nächste Version, wie rechts.
        if (zug == Zug.WIDGET) {
            List<Widgets.Widget> widgets = Minimap.INSTANZ.widgets();
            if (!widgetGezogen && widgetZug >= 0 && widgetZug < widgets.size()) {
                Minimap.INSTANZ.setzeWidget(widgetZug, widgets.get(widgetZug).naechsteVersion());
            }
            zug = Zug.KEINER;
            widgetZug = -1;
            return true;
        }
        boolean geklickt = zug == Zug.KLICK && klick.lasse(e);
        boolean gezogen = zug == Zug.LAGE || zug == Zug.GROESSE;
        zug = Zug.KEINER;
        if (gezogen) {
            // Die Knöpfe auf die freie Seite, neben Minimap und Regler.
            rebuildWidgets();
            return true;
        }
        return geklickt || super.mouseReleased(e);
    }

    /** Der Griff sitzt an der Ecke, die zur Mitte des Schirms zeigt ({@link Minimap#griffEcke}); so hat er dort Platz zum Ziehen. */
    private boolean links(Minimap.Rahmen r) {
        return (Minimap.griffEcke(r, width, height) & 1) == 0;
    }

    private boolean oben(Minimap.Rahmen r) {
        return (Minimap.griffEcke(r, width, height) & 2) == 0;
    }

    /**
     * Die Mitte des Griffs: eckig in der Ecke, rund auf dem Umriss in der Diagonale dorthin; mit
     * Skin auf der Mitte der Bänder, wo sonst die zier sitzt.
     */
    private double[] griff(Minimap.Rahmen r) {
        Skin skin = Minimap.INSTANZ.skinJetzt();
        if (skin != null) {
            return Minimap.INSTANZ.ecken(skin, r)[Minimap.griffEcke(r, width, height)];
        }
        double h = r.seite() / 2.0, weg = griffWeg(r);
        return new double[] {r.x() + h + (links(r) ? -weg : weg), r.y() + h + (oben(r) ? -weg : weg)};
    }

    private int griffX(Minimap.Rahmen r) {
        return (int) Math.round(griff(r)[0]);
    }

    private int griffY(Minimap.Rahmen r) {
        return (int) Math.round(griff(r)[1]);
    }

    private boolean imGriff(double x, double y, Minimap.Rahmen r) {
        double[] p = griff(r);
        return Minimap.imGriff(x, y, p[0], p[1]);
    }

    private static double griffWeg(Minimap.Rahmen r) {
        return Minimap.INSTANZ.rund() ? (r.seite() / 2.0 + 1.5) / Math.sqrt(2) : r.seite() / 2.0;
    }

    /** Der Umriss 2 Einheiten um die Minimap, eine breit; rund als Ring, in Pixeln des Schirms. */
    private void umriss(GuiGraphicsExtractor g, Minimap.Rahmen r) {
        if (!Minimap.INSTANZ.rund()) {
            g.outline(r.x() - 2, r.y() - 2, r.seite() + 4, r.seite() + 4, TEXT);
            return;
        }
        int k = minecraft.getWindow().getGuiScale();
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.scale(1f / k);
        int n = (r.seite() + 4) * k, x0 = (r.x() - 2) * k, y0 = (r.y() - 2) * k, innen = n - 2 * k;
        for (int y = 0; y < n; y++) {
            int a = Minimap.sehne(n, y);
            if (a >= n - a) {
                continue;
            }
            int yi = y - k, ai = yi >= 0 && yi < innen ? Minimap.sehne(innen, yi) : innen;
            if (ai >= innen - ai) {
                g.fill(x0 + a, y0 + y, x0 + n - a, y0 + y + 1, TEXT);
            } else {
                g.fill(x0 + a, y0 + y, x0 + k + ai, y0 + y + 1, TEXT);
                g.fill(x0 + n - k - ai, y0 + y, x0 + n - a, y0 + y + 1, TEXT);
            }
        }
        pose.popMatrix();
    }

    @Override
    public void removed() {
        Minimap.INSTANZ.griff(-1, false);
        Minimap.INSTANZ.hervor(Minimap.KEIN_ZIEL);
        Minimap.INSTANZ.hell(-1);
        Minimap.INSTANZ.schreibe(HeroicMap.einstellungen());
        // Eine andere Ablage heisst ein anderer Ordner der Welt, auch für die Wegpunkte.
        Wegpunkte.INSTANZ.wechsel(Downloads.weltOrdner());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Ein Symbol an der Minimap im Menü: ein Knopf des Spiels, {@link #SYMBOL} breit und hoch, darin ein Sprite aus
     * {@code menue/}, weiss oder zum Zustand gefärbt. Der Tooltip nennt Name, Zustand und was ein Klick tut. Siehe
     * docs/minimap.md, „Symbole an der Minimap“.
     */
    static final class Symbol extends AbstractButton {

        /** Was ein Symbol zeigt und schaltet. */
        interface Art {
            /** Das Sprite, gedreht um {@link #winkel} und gefärbt mit {@link #farbe}. */
            Identifier sprite(Minimap m);

            default float winkel(Minimap m) {
                return 0;
            }

            default int farbe(Minimap m) {
                return 0xFFFFFFFF;
            }

            Component name();

            Component zustand(Minimap m);

            Component naechster(Minimap m);

            void schalte(Minimap m);

            /** Eine Zeile mehr im Tooltip, die sagt, was das Symbol schaltet; null ohne. */
            default Component beschreibung() {
                return null;
            }
        }

        private final Art art;

        Symbol(Art art) {
            super(0, 0, SYMBOL, SYMBOL, art.name());
            this.art = art;
            tooltip();
        }

        Art art() {
            return art;
        }

        private void tooltip() {
            Minimap m = Minimap.INSTANZ;
            Component klick = Component.translatable("heroicmap.menue.symbol.klick", art.name(), art.zustand(m), art.naechster(m));
            setTooltip(Tooltip.create(art.beschreibung() == null ? klick : klick.copy().append("\n").append(art.beschreibung())));
        }

        @Override
        public void onPress(InputWithModifiers e) {
            art.schalte(Minimap.INSTANZ);
            tooltip();
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
            extractDefaultSprite(g);
            Minimap m = Minimap.INSTANZ;
            // 16 × 16 in der Mitte, um seine Mitte gedreht.
            g.pose().pushMatrix();
            g.pose().translate(getX() + SYMBOL / 2f, getY() + SYMBOL / 2f);
            g.pose().rotate(art.winkel(m));
            g.blitSprite(RenderPipelines.GUI_TEXTURED, art.sprite(m), -8, -8, 16, 16, art.farbe(m));
            g.pose().popMatrix();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput ausgabe) {
            defaultButtonNarrationText(ausgabe);
        }

        private static Identifier bild(String name) {
            return Identifier.fromNamespaceAndPath(HeroicMap.ID, "menue/" + name);
        }

        private static Component an(boolean an) {
            return an ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
        }

        /** Die Form, ◻ oder ◯ wie jetzt; ein Klick wechselt. */
        static final Art FORM = new Art() {
            public Identifier sprite(Minimap m) {
                return bild(m.rund() ? "form_rund" : "form_eckig");
            }

            public Component name() {
                return Component.translatable("heroicmap.menue.form");
            }

            public Component zustand(Minimap m) {
                return Component.translatable(m.rund() ? "heroicmap.menue.rund" : "heroicmap.menue.eckig");
            }

            public Component naechster(Minimap m) {
                return Component.translatable(m.rund() ? "heroicmap.menue.eckig" : "heroicmap.menue.rund");
            }

            public void schalte(Minimap m) {
                m.setzeRund(!m.rund());
            }
        };

        /** Der Nordpfeil: genordet fest nach oben, drehend dreht er sich mit dem Blick, auch im Menü. */
        static final Art NORD = new Art() {
            public Identifier sprite(Minimap m) {
                return bild("nord");
            }

            public float winkel(Minimap m) {
                LocalPlayer p = Minecraft.getInstance().player;
                // Wie die Marken des Rahmens, starr mit der gedrehten Karte (Drehung.winkel).
                return m.drehen() && p != null ? (float) Drehung.winkel(p.getYRot()) : 0;
            }

            public Component name() {
                return Component.translatable("heroicmap.menue.drehen");
            }

            public Component zustand(Minimap m) {
                return an(m.drehen());
            }

            public Component naechster(Minimap m) {
                return an(!m.drehen());
            }

            public void schalte(Minimap m) {
                m.setzeDrehen(!m.drehen());
            }
        };

        /** Das Raster der Chunklinien: hell an, dunkel aus. */
        static final Art RASTER = new Art() {
            public Identifier sprite(Minimap m) {
                return bild("raster");
            }

            public int farbe(Minimap m) {
                return m.chunklinien() ? 0xFFFFFFFF : 0xFF404040;
            }

            public Component name() {
                return Component.translatable("heroicmap.menue.chunklinien");
            }

            public Component zustand(Minimap m) {
                return an(m.chunklinien());
            }

            public Component naechster(Minimap m) {
                return an(!m.chunklinien());
            }

            public void schalte(Minimap m) {
                m.setzeChunklinien(!m.chunklinien());
            }
        };

        /** Effekte in der Welt, an der Region im Untermenü: hell an, dunkel aus. Siehe docs/wegpunkte.md, „Strahl“. */
        static final Art EFFEKTE = new Art() {
            public Identifier sprite(Minimap m) {
                return bild("effekte");
            }

            public int farbe(Minimap m) {
                return m.effekte() ? 0xFFFFFFFF : 0xFF404040;
            }

            public Component name() {
                return Component.translatable("heroicmap.menue.effekte");
            }

            public Component zustand(Minimap m) {
                return an(m.effekte());
            }

            public Component naechster(Minimap m) {
                return an(!m.effekte());
            }

            public void schalte(Minimap m) {
                m.setzeEffekte(!m.effekte());
            }

            public Component beschreibung() {
                return Component.translatable("heroicmap.menue.effekte.beschreibung");
            }
        };
    }

    /**
     * Die Auflösung der Minimap als Schieberegler unter ihr, eine Raste je Stufe aus {@link #STUFEN}; kurz beschriftet,
     * „2 px“, den vollen Namen nennt der Tooltip. Die Pfeiltasten und das Mausrad gehen je eine Raste. Siehe
     * docs/minimap.md, „Bedienung“.
     */
    static final class AufloesungRegler extends AbstractSliderButton {

        /** Pixel je Block, die die Minimap höchstens zeichnet. */
        static final int[] STUFEN = {1, 2, 4, 8, 16};
        private static final int RASTEN = STUFEN.length - 1;
        private final Minimap minimap;

        AufloesungRegler(int x, int y, int breite, Minimap minimap) {
            super(x, y, breite, REGLER_HOEHE, Component.empty(), (double) Math.max(0, stufe(minimap.aufloesung())) / RASTEN);
            this.minimap = minimap;
            updateMessage();
        }

        private static int stufe(int px) {
            for (int i = 0; i < STUFEN.length; i++) {
                if (STUFEN[i] == px) {
                    return i;
                }
            }
            return -1;
        }

        private int raste() {
            return (int) Math.round(value * RASTEN);
        }

        @Override
        protected void updateMessage() {
            Component px = Component.translatable("heroicmap.menue.px_kurz", STUFEN[raste()]);
            setMessage(px);
            setTooltip(Tooltip.create(Component.empty().append(CommonComponents.optionNameValue(Component.translatable("heroicmap.menue.aufloesung"), px))
                    .append(CommonComponents.NEW_LINE).append(Component.translatable("heroicmap.menue.aufloesung.beschreibung"))));
        }

        @Override
        protected void applyValue() {
            // Nur auf den Rasten: Dazwischen gibt es keine Auflösung.
            value = (double) raste() / RASTEN;
            minimap.setzeScale(STUFEN[raste()]);
        }

        /** Stellt den Knauf auf {@code px}, ohne ihn anzuwenden. */
        void folge(int px) {
            double v = (double) Math.max(0, stufe(px)) / RASTEN;
            if (v != value) {
                value = v;
                updateMessage();
            }
        }

        /** Eine Raste feiner (+1) oder gröber (−1), am Ende bleibt er stehen. */
        void schritt(int schritt) {
            setValue((double) Math.clamp(raste() + schritt, 0, RASTEN) / RASTEN);
        }

        @Override
        public boolean keyPressed(KeyEvent ereignis) {
            int schritt = ereignis.key() == InputConstants.KEY_LEFT ? -1 : ereignis.key() == InputConstants.KEY_RIGHT ? 1 : 0;
            if (schritt != 0 && active) {
                schritt(schritt);
                return true;
            }
            return super.keyPressed(ereignis);
        }
    }

    /**
     * Der Zoom der Minimap als senkrechter Schieberegler neben ihr, eine Raste je Stufe aus {@link Minimap#ZOOMS}, oben die
     * nächste. Gezeichnet mit den Sprites der Regler des Spiels, quer; den Wert zeigt der Tooltip. Die Pfeiltasten und das
     * Mausrad gehen je eine Raste, hoch, rechts und nach vorn näher. Siehe docs/minimap.md, „Bedienung“.
     */
    static final class ZoomRegler extends AbstractSliderButton {

        private static final int RASTEN = Minimap.ZOOMS.length - 1;
        /** Höhe des Knaufs, wie im Spiel seine Breite. */
        private static final int KNAUF = 8;
        private static final Identifier SPUR = Identifier.withDefaultNamespace("widget/slider");
        private static final Identifier SPUR_HELL = Identifier.withDefaultNamespace("widget/slider_highlighted");
        private static final Identifier GRIFF = Identifier.withDefaultNamespace("widget/slider_handle");
        private static final Identifier GRIFF_HELL = Identifier.withDefaultNamespace("widget/slider_handle_highlighted");
        private final Minimap minimap;
        private boolean zieht;

        ZoomRegler(int x, int y, int hoehe, Minimap minimap) {
            super(x, y, ZOOM_BREITE, hoehe, Component.empty(), (double) Math.max(0, Minimap.stufe(minimap.zoom())) / RASTEN);
            this.minimap = minimap;
            updateMessage();
        }

        private int raste() {
            return (int) Math.round(value * RASTEN);
        }

        @Override
        protected void updateMessage() {
            setMessage(CommonComponents.optionNameValue(Component.translatable("heroicmap.menue.zoom"), Minimap.fach(Minimap.ZOOMS[raste()])));
            setTooltip(Tooltip.create(getMessage()));
        }

        @Override
        protected void applyValue() {
            // Nur auf den Rasten: zwischen zwei Stufen gibt es keinen Zoom.
            value = (double) raste() / RASTEN;
            minimap.setzeZoom(Minimap.ZOOMS[raste()]);
        }

        /** Wie der Regler des Spiels, nur quer: Spur und Knauf, ohne Text. */
        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
            int farbe = ARGB.white(alpha);
            g.blitSprite(RenderPipelines.GUI_TEXTURED, isActive() && isFocused() && !canChangeValue ? SPUR_HELL : SPUR,
                    getX(), getY(), getWidth(), getHeight(), farbe);
            int knauf = getY() + (int) Math.round((1 - value) * (getHeight() - KNAUF));
            g.blitSprite(RenderPipelines.GUI_TEXTURED, isActive() && (isHovered || canChangeValue) ? GRIFF_HELL : GRIFF,
                    getX(), knauf, getWidth(), KNAUF, farbe);
            if (isHovered()) {
                g.requestCursor(!isActive() ? CursorTypes.NOT_ALLOWED : zieht ? CursorTypes.RESIZE_NS : CursorTypes.POINTING_HAND);
            }
        }

        @Override
        public void onClick(MouseButtonEvent e, boolean doppelt) {
            zieht = active;
            vonMaus(e.y());
        }

        @Override
        protected void onDrag(MouseButtonEvent e, double dx, double dy) {
            vonMaus(e.y());
        }

        @Override
        public void onRelease(MouseButtonEvent e) {
            zieht = false;
            super.onRelease(e);
        }

        /** Die Mitte des Knaufs liegt bei y + 4 + (1 − Wert) × (Höhe − 8), wie im Spiel quer. */
        private void vonMaus(double y) {
            setValue(1 - (y - getY() - KNAUF / 2.0) / (getHeight() - KNAUF));
        }

        @Override
        public boolean keyPressed(KeyEvent ereignis) {
            int taste = ereignis.key();
            int schritt = taste == InputConstants.KEY_UP || taste == InputConstants.KEY_RIGHT ? 1
                    : taste == InputConstants.KEY_DOWN || taste == InputConstants.KEY_LEFT ? -1 : 0;
            if (schritt != 0 && active) {
                schritt(schritt);
                return true;
            }
            return super.keyPressed(ereignis);
        }

        /** Stellt den Knauf auf {@code zoom}, ohne ihn anzuwenden: Der Zoom kann sich auch ohne den Regler ändern. */
        void folge(double zoom) {
            double v = (double) Math.max(0, Minimap.stufe(zoom)) / RASTEN;
            if (v != value) {
                value = v;
                updateMessage();
            }
        }

        /** Eine Raste näher (+1) oder weiter (−1), am Ende bleibt er stehen. */
        void schritt(int schritt) {
            setValue((double) Math.clamp(raste() + schritt, 0, RASTEN) / RASTEN);
        }
    }
}
