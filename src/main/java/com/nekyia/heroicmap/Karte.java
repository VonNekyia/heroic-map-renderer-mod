package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

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

    private final Satz satz;
    private final Kacheln kacheln;
    private final Kartenblick blick;
    /** Was beim letzten Knopf schiefging, oder null. */
    private Component hinweis;
    /** Der Knopf für den Abgleich, oder null ohne Satz; er ist aus, bis ein Abgleich wieder geht. */
    private Button abgleich;
    private String baum;
    /** Das Menü nach Rechtsklick: der Block, oder null, seine Einträge und wo es steht. */
    private int[] ziel;
    private List<Eintrag> eintraege = List.of();
    private int menueX, menueY;
    /** Die Marken des letzten Frames, in der Reihenfolge, in der sie gezeichnet sind. */
    private final List<Marke> marken = new ArrayList<>();
    /** Die Marke unter dem letzten Linksklick; ein Doppelklick heftet sie an. */
    private Marke letzte;

    /** Ein Eintrag im Menü nach Rechtsklick. */
    private record Eintrag(Component text, Runnable tut) {
    }

    /**
     * Was auf der Karte steht und sich anklicken lässt: die Mitte auf dem Schirm, die halbe Seite,
     * der Ort in der Welt und was es ist; für den eigenen Spieler sind {@code spieler} und
     * {@code punkt} null.
     */
    record Marke(float x, float y, float halb, double weltX, double weltZ, UUID spieler, Wegpunkte.Punkt punkt) {
    }

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
        addRenderableWidget(Button.builder(Component.translatable("heroicmap.karte.laden"),
                b -> minecraft.gui.setScreen(new Auswahl(this))).bounds(x, 4, KNOPF, 20).build());
        if (satz != null) {
            // Der Baum ist der Ordner über dem Massstab.
            baum = satz.ordner().getParent().getFileName().toString();
            abgleich = addRenderableWidget(Button.builder(Component.translatable("heroicmap.karte.abgleich"),
                    b -> hinweis = Downloads.INSTANZ.frageAbgleich(baum)).bounds(x, 28, KNOPF, 20).build());
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
        g.fill(0, 0, width, height, HINTERGRUND);
        if (satz == null) {
            g.centeredText(font, Component.translatable("heroicmap.karte.keine"), width / 2, height / 2, TEXT);
            super.extractRenderState(g, mausX, mausY, delta);
            return;
        }
        int[] k = blick.kacheln(width, height);
        int seite = satz.kachel() * blick.lupe;
        double basis = satz.kachel() * blick.teiler();
        for (int ty = k[2]; ty <= k[3]; ty++) {
            for (int tx = k[0]; tx <= k[1]; tx++) {
                Identifier id = kacheln.textur(blick.zoom, tx, ty);
                int sx = Mth.floor(blick.schirmX(tx * basis, width)), sy = Mth.floor(blick.schirmY(ty * basis, height));
                if (id != null) {
                    g.blit(RenderPipelines.GUI_TEXTURED, id, sx, sy, 0, 0, seite, seite, satz.kachel(), satz.kachel(),
                            satz.kachel(), satz.kachel());
                } else {
                    platzhalter(g, tx, ty, sx, sy, seite);
                }
            }
        }
        marken(g);
        int[] block = block(mausX, mausY);
        g.text(font, Component.literal(satz.name() + "   ").append(Component.translatable("heroicmap.koordinaten", block[0], block[1])),
                4, height - 12, TEXT);
        if (hinweis != null) {
            g.text(font, hinweis, 4, height - 24, TEXT);
        }
        // Höchstens ein Abgleich je Tag: Nach einer Ablehnung mit wieder ist der Knopf bis dahin aus.
        long ab = Downloads.INSTANZ.abgleichAb(baum);
        abgleich.active = ab == 0;
        abgleich.setMessage(ab == 0 ? Component.translatable("heroicmap.karte.abgleich")
                : Component.translatable("heroicmap.karte.abgleich_ab", Downloads.uhr(ab)));
        super.extractRenderState(g, mausX, mausY, delta);
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
            g.centeredText(font, e.name(), Mth.floor(m.x()), Mth.floor(m.y() - m.halb()) - 10, TEXT);
        }
        Marke ich = marke(spieler.getX(), spieler.getZ(), null, null);
        Minimap.avatar(g, spieler, ich.x(), ich.y(), 1f, Minimap.KOPF);
    }

    /** Die Marke für den Ort (x, z) der Welt; liegt er ausserhalb des Schirms, am Rand in seiner Richtung. */
    private Marke marke(double x, double z, UUID uuid, Wegpunkte.Punkt punkt) {
        double px = blick.schirmX(Projektion.zuPixel(x, satz.scale()), width) - width / 2.0;
        double pz = blick.schirmY(Projektion.zuPixel(z, satz.scale()), height) - height / 2.0;
        double f = Minimap.rand(px, pz, width / 2.0 - RAND, height / 2.0 - RAND, false);
        // Auf ganze Pixel, sonst sind die Texel des Kopfes ungleich breit.
        int k = minecraft.getWindow().getGuiScale();
        Marke m = new Marke(Math.round((width / 2.0 + px * f) * k) / (float) k, Math.round((height / 2.0 + pz * f) * k) / (float) k,
                Minimap.KOPF / 2f + 1, x, z, uuid, punkt);
        marken.add(m);
        return m;
    }

    /** Die oberste Marke unter (x, y), oder null. */
    private Marke treffer(double x, double y) {
        for (int i = marken.size() - 1; i >= 0; i--) {
            Marke m = marken.get(i);
            if (Math.abs(x - m.x()) <= m.halb() && Math.abs(y - m.y()) <= m.halb()) {
                return m;
            }
        }
        return null;
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

    /** Für den Gametest Bedienung: die Mitte des Blicks in Pixeln der Basis, das offene Menü, oder null, und die Marken. */
    double[] blickMitte() {
        return blick == null ? null : new double[] {blick.mx, blick.mz};
    }

    int[] ziel() {
        return ziel;
    }

    List<Marke> marken() {
        return List.copyOf(marken);
    }

    /** Der Block unter (x, y) des Schirms. */
    private int[] block(double x, double y) {
        return new int[] {Mth.floor(blick.basisX(x, width) / satz.scale()), Mth.floor(blick.basisZ(y, height) / satz.scale())};
    }

    /**
     * Linksklick auf eine Marke legt sie in die Mitte, ein Doppelklick heftet sie an die Minimap
     * oder löst sie. Rechtsklick öffnet das Menü: „Hierher teleportieren“, nur mit execute und tp im
     * Befehlsbaum und nicht unter einer Decke, sonst landete man auf dem Dach; darunter „Wegpunkt
     * setzen“, auf einem Wegpunkt „Wegpunkt löschen“. Erst ein Klick auf einen Eintrag tut etwas.
     * Siehe docs/vollbildkarte.md, „Bedienung“, und docs/wegpunkte.md.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doppelt) {
        if (ziel != null) {
            // Mit der linken wie der rechten Taste: Wer rechts klickt, um zu öffnen, klickt oft auch rechts darauf.
            int zeile = Mth.floor((e.y() - menueY) / ZEILE);
            boolean treffer = e.x() >= menueX && e.x() < menueX + menueBreite() && e.y() >= menueY && zeile < eintraege.size();
            List<Eintrag> offen = eintraege;
            ziel = null;
            if (treffer) {
                offen.get(zeile).tut().run();
                return true;
            }
        }
        if (super.mouseClicked(e, doppelt)) {
            return true;
        }
        if (blick == null || minecraft.level == null) {
            return false;
        }
        Marke m = treffer(e.x(), e.y());
        if (e.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            // Der erste Klick hat die Marke schon in die Mitte gelegt; der zweite zählt für dieselbe.
            if (doppelt && letzte != null) {
                if (letzte.punkt() != null) {
                    Wegpunkte.INSTANZ.umschalten(letzte.punkt());
                } else if (letzte.spieler() != null) {
                    Wegpunkte.INSTANZ.umschalten(letzte.spieler());
                }
                letzte = null;
                return true;
            }
            letzte = m;
            if (m != null) {
                zentriere(m.weltX(), m.weltZ());
                return true;
            }
            return false;
        }
        if (e.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            menue(e.x(), e.y(), m != null ? m.punkt() : null);
            return true;
        }
        return false;
    }

    /** Öffnet das Menü an (x, y) für den Block dort, oder für den Wegpunkt {@code punkt}. */
    private void menue(double x, double y, Wegpunkte.Punkt punkt) {
        int[] z = punkt != null ? new int[] {punkt.x(), punkt.z()} : block(x, y);
        String dimension = minecraft.level.dimension().identifier().toString();
        List<Eintrag> neu = new ArrayList<>();
        if (minecraft.getConnection() != null && !minecraft.level.dimensionType().hasCeiling()
                && Teleport.erlaubt(minecraft.getConnection().getCommands())) {
            neu.add(new Eintrag(Component.translatable("heroicmap.karte.teleport", z[0], z[1]), () -> {
                minecraft.getConnection().sendCommand(Teleport.befehl(dimension, z[0], z[1]));
                onClose();
            }));
        }
        neu.add(punkt != null
                ? new Eintrag(Component.translatable("heroicmap.karte.wegpunkt_loeschen"), () -> Wegpunkte.INSTANZ.loesche(punkt))
                : new Eintrag(Component.translatable("heroicmap.karte.wegpunkt"), () -> Wegpunkte.INSTANZ.setze(dimension, z[0], z[1])));
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
        // Die Tasten zählen wie in SDL, links ist 1. Siehe docs/entwicklung.md, „Maustasten“.
        if (blick != null && ereignis.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            blick.schiebe(dx, dy);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double weitX, double weitY) {
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
