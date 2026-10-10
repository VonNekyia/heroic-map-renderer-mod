package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.joml.Matrix3x2fStack;

/**
 * Das Menü hinter {@code /hmap}: Minimap an oder aus, Zoom, Mitspieler, die Karten des Servers und die
 * auf der Platte, dazu das Untermenü „Einstellungen …“ für Vorlieben der Anzeige ({@link Anzeige}).
 * Die Minimap im HUD bleibt sichtbar; Ziehen verschiebt sie, der Griff an der Ecke zur Mitte des
 * Schirms zieht sie grösser oder kleiner, mit der linken wie der rechten Taste. Die Knöpfe passen
 * sich dem Platz an. Gespeichert wird beim Schliessen.
 * Siehe docs/minimap.md, „Bedienung“.
 */
final class Einstellungen extends Screen {

    private static final int TEXT = 0xFFFFFFFF;
    /** Halbe Seite des Griffs in Einheiten des GUI. */
    private static final int GRIFF = 3;
    /** Breite der Knöpfe, höchstens; schmaler, wenn neben der Minimap weniger Platz ist. */
    private static final int BREITE = 200;

    private enum Zug { KEINER, LAGE, GROESSE }

    private Zug zug = Zug.KEINER;
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
                .create(x, y, halb, 20, Component.translatable("heroicmap.menue.minimap"), (b, an) -> m.setzeSichtbar(an)));
        addRenderableWidget(CycleButton.builder((Integer z) -> Component.translatable("heroicmap.menue.fach", z), m.zoom())
                .withValues(1, 2, 4, 8)
                .create(x + breite - halb, y, halb, 20, Component.translatable("heroicmap.menue.zoom"), (b, z) -> m.setzeZoom(z)));
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
     * Linker Rand und Breite der Knöpfe: mittig im grösseren freien Platz neben der Minimap, nicht
     * auf ihr; bei grossem GUI-Massstab ist der Schirm schmal, dann werden die Knöpfe schmaler.
     */
    static int[] spalte(int width, int height) {
        Minimap m = Minimap.INSTANZ;
        Minimap.Rahmen r = m.rahmen(width, height);
        int links = r.x(), rechts = width - r.x() - r.seite();
        int platz = !m.sichtbar() ? width : Math.max(links, rechts);
        int breite = Math.max(120, Math.min(BREITE, platz - 8));
        int spalte = !m.sichtbar() ? (width - breite) / 2
                : links >= rechts ? (links - breite) / 2 : r.x() + r.seite() + (rechts - breite) / 2;
        return new int[] {Math.max(4, Math.min(spalte, width - breite - 4)), breite};
    }

    /** Ohne Unschärfe und Abdunkeln, damit die Minimap im HUD zu sehen ist. */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
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
    }

    /** Über der Minimap die Koordinaten des Blocks unter der Maus, fest unten links, genau wie gezeichnet. */
    private void koordinaten(GuiGraphicsExtractor g, Minimap.Rahmen r, int mausX, int mausY) {
        Minimap m = Minimap.INSTANZ;
        double h = r.seite() / 2.0, dx = mausX - (r.x() + h), dz = mausY - (r.y() + h);
        if (zug != Zug.KEINER || minecraft.player == null || (m.rund() ? dx * dx + dz * dz > h * h : !r.enthaelt(mausX, mausY))) {
            return;
        }
        // In Pixeln des Schirms wie Minimap.zeichne.
        LocalPlayer p = minecraft.player;
        float a = Minimap.anteil(minecraft.level, p, minecraft.getDeltaTracker());
        int k = minecraft.getWindow().getGuiScale(), n = r.seite() * k, block = m.zoom() * k;
        int links = Minimap.ecke(p.xo, p.getX(), a, m.zoom(), k, n), oben = Minimap.ecke(p.zo, p.getZ(), a, m.zoom(), k, n);
        double ix = (mausX - r.x()) * k, iy = (mausY - r.y()) * k;
        if (m.drehen()) {
            // Gedreht zurück ins Bild, wie Minimap.zeichne es dreht. Siehe docs/minimap.md, „Drehen“.
            Drehung.Lage lage = Minimap.lage(r, Mth.lerp(a, p.xo, p.getX()), Mth.lerp(a, p.zo, p.getZ()), p.getViewYRot(a), m.zoom(), k, links, oben);
            ix = lage.bildX(mausX * k, mausY * k);
            iy = lage.bildY(mausX * k, mausY * k);
        }
        int bx = Mth.floor((links + ix) / block), bz = Mth.floor((oben + iy) / block);
        g.text(font, Component.translatable("heroicmap.koordinaten", bx, bz), 4, height - 12, TEXT);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doppelt) {
        if (super.mouseClicked(e, doppelt)) {
            return true;
        }
        if (!Minimap.INSTANZ.sichtbar()) {
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
        if (r.enthaelt(e.x(), e.y())) {
            zug = Zug.LAGE;
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
        zug = Zug.KEINER;
        return super.mouseReleased(e);
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
        Minimap.INSTANZ.schreibe(HeroicMap.einstellungen());
        // Eine andere Ablage heisst ein anderer Ordner der Welt, auch für die Wegpunkte.
        Wegpunkte.INSTANZ.wechsel(Downloads.weltOrdner());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
