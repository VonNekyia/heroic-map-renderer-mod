package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
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
 * Zoom als senkrechter Regler ({@link ZoomRegler}), darunter die Auflösung ({@link AufloesungRegler}); ein Klick
 * auf die Koordinaten wechselt ihren Stil. Ein Klick auf den Spieler oder eine Marke stellt ihr Aussehen ein
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

    /** KLICK: gedrückt auf Spieler oder Marke; wandert die Maus weiter als {@link MinimapKlick#WEG}, wird daraus LAGE. */
    private enum Zug { KEINER, KLICK, LAGE, GROESSE }

    private Zug zug = Zug.KEINER;
    private final MinimapKlick klick = new MinimapKlick();
    /** Beim Verschieben: wo die Maus die Minimap gegriffen hat. Beim Ziehen: die feste Ecke. */
    private int festX, festY;
    /** Beim Ziehen: liegt der Griff links oder oben? */
    private boolean griffLinks, griffOben;
    /** Beim Ziehen: Seite und Maus beim Greifen. */
    private int startSeite, startX, startY;
    /** Warum Mitspieler hier nicht gehen, beim Bauen der Knöpfe, oder null ({@link Mitspieler#grund}). */
    private String grund;
    /** Linker Rand, Oberkante und Breite der Knöpfe. */
    private int spalte, oben, breite;
    private ZoomRegler zoom;
    private AufloesungRegler aufloesung;

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
        // Je zwei Knöpfe in einer Zeile; Vorlieben der Anzeige stehen im Untermenü.
        addRenderableWidget(CycleButton.onOffBuilder(m.sichtbar())
                .create(x, y, breite, 20, Component.translatable("heroicmap.menue.minimap"), (b, an) -> m.setzeSichtbar(an)));
        int[] z = zoomLage(m.rahmen(width, height), m.rand(), width);
        zoom = addRenderableWidget(new ZoomRegler(z[0], z[1], z[2], m));
        zoom.visible = m.sichtbar();
        int[] a = aufloesungLage(m.rahmen(width, height), m.koordinatenKasten(), m.rand(), height);
        aufloesung = addRenderableWidget(new AufloesungRegler(a[0], a[1], a[2], m));
        aufloesung.visible = m.sichtbar();
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

    /** Der nächste Stil der Koordinaten im Kreis: aus, xz, xyz. */
    static Minimap.Koordinaten naechste(Minimap.Koordinaten k) {
        Minimap.Koordinaten[] alle = Minimap.Koordinaten.values();
        return alle[(k.ordinal() + 1) % alle.length];
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
        // Der Regler folgt der Minimap schon beim Ziehen; die Knöpfe erst nach dem Loslassen.
        Minimap m = Minimap.INSTANZ;
        int[] z = zoomLage(m.rahmen(width, height), m.rand(), width);
        zoom.setPosition(z[0], z[1]);
        zoom.setHeight(z[2]);
        zoom.visible = m.sichtbar();
        zoom.folge(m.zoom());
        int[] a = aufloesungLage(m.rahmen(width, height), m.koordinatenKasten(), m.rand(), height);
        aufloesung.setPosition(a[0], a[1]);
        aufloesung.setWidth(a[2]);
        aufloesung.visible = m.sichtbar();
        aufloesung.folge(m.aufloesung());
        // Die Zeile der Koordinaten unter der Maus steht gelb, ab dem nächsten Frame, und nennt ihren Stil.
        boolean zeile = m.sichtbar() && zug == Zug.KEINER && imKasten(m.koordinatenKasten(), mausX, mausY);
        m.koordinatenHell(zeile);
        if (zeile) {
            g.setTooltipForNextFrame(Component.translatable("heroicmap.menue.koordinaten.klick", stil(m.koordinaten()), stil(naechste(m.koordinaten()))),
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

    private static Component stil(Minimap.Koordinaten k) {
        return Component.translatable("heroicmap.menue.koordinaten." + k.name().toLowerCase(Locale.ROOT));
    }

    /** Liegt (x, y) auf der Minimap {@code r}, rund im Kreis? */
    private static boolean auf(Minimap.Rahmen r, double x, double y) {
        double h = r.seite() / 2.0, dx = x - (r.x() + h), dz = y - (r.y() + h);
        return Minimap.INSTANZ.rund() ? dx * dx + dz * dz <= h * h : r.enthaelt(x, y);
    }

    /** Das Mausrad über der Minimap oder dem Regler „Zoom“: je Schritt eine Raste, nach vorn näher; über „Auflösung“ ebenso. */
    @Override
    public boolean mouseScrolled(double x, double y, double weitX, double weitY) {
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
        if (super.mouseClicked(e, doppelt)) {
            return true;
        }
        if (!Minimap.INSTANZ.sichtbar()) {
            return false;
        }
        // Ein Linksklick auf die Zeile der Koordinaten wechselt ihren Stil, auch von „aus“ zurück.
        if (e.button() == InputConstants.MOUSE_BUTTON_LEFT && imKasten(Minimap.INSTANZ.koordinatenKasten(), e.x(), e.y())) {
            Minimap.INSTANZ.setzeKoordinaten(naechste(Minimap.INSTANZ.koordinaten()));
            return true;
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
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
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
        Minimap.INSTANZ.koordinatenHell(false);
        Minimap.INSTANZ.schreibe(HeroicMap.einstellungen());
        // Eine andere Ablage heisst ein anderer Ordner der Welt, auch für die Wegpunkte.
        Wegpunkte.INSTANZ.wechsel(Downloads.weltOrdner());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
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
