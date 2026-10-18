package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
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
    /** Die zweite Zeile im Kasten des Downloads. */
    private static final int GRAU = 0xFFC0C0C0;
    /** Breite der Knöpfe in Einheiten des GUI. */
    private static final int KNOPF = 90;
    /** Breite des Knopfs „An“/„Aus“ einer Ebene; ihr Name steht links daneben. */
    private static final int AN_AUS = 30;
    /** Höhe eines Eintrags im Menü nach Rechtsklick. */
    private static final int ZEILE = 14;
    /** Abstand der Marken vom Rand des Schirms; Platz für den Namen über einem Kopf. */
    private static final int RAND = 14;
    /** So lange dauert ein Durchlauf der Farben um angeheftete Marken, in ms. */
    private static final long BUNT_MS = 2000;
    /** Weiter vom Druckpunkt weg, in Einheiten des GUI, ist es kein Klick auf eine Marke, kein Halten und kein Doppelklick mehr. */
    private static final double ZUG = 3;
    /** Die halbe Breite des Punkts unter einer angehefteten Nadel, in Einheiten des GUI. */
    private static final int PUNKT = 2;
    /** Die Vorschau einer Region, die der Spieler setzt. */
    private static final int VORSCHAU = 0xCCFFFFFF;
    /** So weit von der Mitte einer Ecke trifft ein Klick sie noch, in Einheiten des GUI; von zweien die nähere. */
    private static final double ECKE_TREFFER = 3;

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
    /**
     * Was beim letzten Knopf oder Schritt schiefging, oder null, und seit wann es dasteht; nach {@link #HINWEIS_MS} oder beim
     * nächsten Klick oder Tastendruck ist es weg. Siehe docs/vollbildkarte.md, „Bedienung“.
     */
    private Component hinweis;
    private long hinweisSeit;
    static final long HINWEIS_MS = 5000;
    /** Wo die Knöpfe rechts oben beginnen und enden; Marken und Namen weichen ihnen aus. */
    private int knopfX, knopfUnten;
    /** {@link Downloads#stufen()} beim letzten Nachsehen; ohne Satz sieht die Karte nach, wenn es sich ändert. */
    private int stufen = Downloads.INSTANZ.stufen();
    /** Der Knopf für den Abgleich, oder null ohne Satz vom Server; er ist aus, bis ein Abgleich wieder geht. */
    private Button abgleich;
    private String baum;
    /** Das Menü nach Rechtsklick: der Block, oder null, seine Einträge und wo es steht. */
    private int[] ziel;
    private List<Eintrag> eintraege = List.of();
    private int menueX, menueY;
    /** Die Marken des letzten Frames, in der Reihenfolge, in der sie gezeichnet sind. */
    private final List<Marke> marken = new ArrayList<>();
    /** Die Ecken der eigenen Formen im letzten Frame. */
    private final List<EckeAn> ecken = new ArrayList<>();
    private final List<GriffAn> griffe = new ArrayList<>();
    private GriffAn griffGedrueckt, griffHaengt;
    /**
     * Das Eingabefeld am Griff, für Name oder Farbe, oder null; Enter gibt seinen Text an {@code beiEnter}, und false lässt es
     * offen. Unten links steht {@code eingabeHinweis}. Siehe docs/wegpunkte.md, „Namen“.
     * Siehe docs/wegpunkte.md, „Farbe“.
     */
    private EditBox eingabe;
    private Predicate<String> beiEnter;
    private Component eingabeHinweis;
    /** So breit ist das Eingabefeld, in Einheiten des GUI. */
    private static final int EINGABE_BREITE = 160;
    /** Die Schrift im Eingabefeld, wenn Enter den Text nicht nimmt. */
    private static final int UNGUELTIG = 0xFFFF5555;
    /** Was die Maus gerade hervorhebt, höchstens eins davon; siehe {@link #schwebe}. */
    private Marke ueberMarke;
    private EckeAn ueberEcke;
    private GriffAn ueberGriff;
    /** Der helle Rand von dem, was unter der Maus liegt. */
    private static final int HELL = 0xFFFFFFFF;
    private final Formen.Speicher gezogenSpeicher = new Formen.Speicher();
    /** Die Marke des letzten Klicks, wenn es einer auf eine Marke war; ein Doppelklick heftet sie an. */
    private Marke letzte;
    /** Die Marke unter dem Drücken der linken Taste, oder null; ein Zug macht daraus keinen Klick. */
    private Marke gedrueckt;
    /** War das Drücken der zweite Klick eines Doppelklicks? */
    private boolean doppelklick;
    /** So lange hält der Spieler die linke Taste still auf einem Wegpunkt, bis er an der Maus hängt, in ms. */
    private static final long HALTEN_MS = 500;
    /** Seit wann die linke Taste gedrückt ist, und der Wegpunkt, der an der Maus hängt, oder null. */
    private long gedruecktSeit;
    private Wegpunkte.Punkt haengt;
    /** Die Marke eines Klicks, die in die Mitte kommt, wenn kein zweiter Klick folgt, und seit wann sie wartet. */
    private Marke wartend;
    private long wartendSeit;
    /** Die Nadel, die Fläche oder der Kreis unter dem letzten Klick ohne Marke; ein Doppelklick darauf heftet an. */
    private Tafeln.Ziel letztesZiel;
    /**
     * Wie weit die Maus seit dem Drücken höchstens vom Druckpunkt weg war, in Einheiten des GUI; nicht der Weg,
     * sonst summierte sich das Zittern der Hand. Siehe docs/wegpunkte.md, „Bedienung“.
     */
    private double gezogen;
    private double druckX, druckY;

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

    /** Der Griff in der Mitte einer eigenen Form auf dem Schirm, in Einheiten des GUI. */
    record GriffAn(Wegpunkte.EigeneForm form, float x, float y) {
    }

    /** Die Ecke {@code index} der eigenen Form {@code form}, auf dem Schirm bei (x, y) in Einheiten des GUI. */
    record EckeAn(Wegpunkte.EigeneForm form, int index, float x, float y) {
    }

    /** Ist die Lage beim Öffnen gestellt? {@code init} läuft bei jeder Grösse des Fensters und nach jedem Untermenü neu. */
    private boolean gestellt;
    /** Was beim Öffnen in der Mitte steht, in Blöcken, statt der Lage vom letzten Schliessen; sonst null. */
    private double[] start;
    /**
     * Die Ecken einer Form, die der Spieler gerade baut oder ändert, in Reihenfolge, und ihre Dimension; sonst null.
     * {@code zugAus} ist die eigene Form, die sie ändert, null für eine neue; die nächste Ecke kommt an {@code zugStelle}.
     */
    private List<Wegpunkte.Ecke> zug;
    private String zugDimension;
    private Wegpunkte.EigeneForm zugAus;
    private int zugStelle;
    /** Galt das letzte Drücken der linken Taste der Karte selbst, nicht einem Knopf, dem Menü oder der Tafel? */
    private boolean aufKarte;
    /** Die Ecke einer eigenen Form unter dem Drücken der linken Taste, oder null; und die, die an der Maus hängt. */
    private EckeAn eckeGedrueckt, eckeHaengt;
    /** Die eigene Form unter dem letzten Klick ohne Marke; ein Doppelklick darauf heftet sie an. */
    private Wegpunkte.EigeneForm letzteEigene;
    /** Ist die Liste der Ebenen unter dem Knopf „Ebenen“ offen? Siehe docs/vollbildkarte.md, „Ebenen“. */
    private boolean ebenenOffen;
    /** Die Schalter der Liste, die Ebenen, für die sie gebaut ist, und der Stand der Ebenen dabei. */
    private final List<Schalter> schalter = new ArrayList<>();
    private List<String> gebauteEbenen = List.of();
    private int ebenenStand;
    /** Für welche Schalter alles angeheftet ist, gerechnet bei diesem Stand der Ebenen und der Wegpunkte. */
    private boolean[] ganz = new boolean[0];
    private int ganzEbenen = -1, ganzWegpunkte = -1;
    /** Die Ebene unter dem letzten Klick auf eine Überschrift; ein Doppelklick darauf heftet die ganze Ebene an. */
    private Schalter letzterSchalter;

    /** Eine Zeile der Liste: der Knopf „An“/„Aus“, die Kennung seiner Ebene und ihr Name, die Überschrift links davor. */
    private record Schalter(CycleButton<Boolean> knopf, String ebene, Component name) {
    }

    /** Öffnet sich mit (x, z) in Blöcken in der Mitte, Stufe und Lupe wie beim letzten Schliessen; für {@code /hmap karte}. */
    Karte bei(double x, double z) {
        start = new double[] {x, z};
        return this;
    }

    /** {@code satz} ist null, wenn für diese Dimension nichts geladen ist. */
    Karte(Satz satz) {
        super(Component.translatable("heroicmap.karte.titel"));
        this.satz = satz;
        this.kacheln = satz == null ? null : new Kacheln(satz);
        this.blick = satz == null ? null : new Kartenblick(satz.kachel(), satz.minZoom(), satz.maxZoom(), satz.stufe());
        this.ebenenOffen = Kartenlage.ebenenOffen(Downloads.weltOrdner());
    }

    @Override
    protected void init() {
        // Ein neuer Aufbau, etwa bei einer anderen Fenstergrösse, nimmt alle Elemente weg, auch das Eingabefeld.
        eingabe = null;
        beiEnter = null;
        LocalPlayer spieler = minecraft.player;
        // Wie beim letzten Schliessen, je Satz; sonst der Spieler in der Mitte. Siehe docs/vollbildkarte.md, „Lage merken“.
        if (blick != null && spieler != null && !gestellt) {
            gestellt = true;
            Kartenlage.Lage l = Kartenlage.lies(Downloads.weltOrdner(), satz);
            if (l != null) {
                blick.stelle(Projektion.zuPixel(l.x(), satz.scale()), Projektion.zuPixel(l.z(), satz.scale()), l.zoom(), l.lupe());
            } else {
                blick.mx = Projektion.zuPixel(spieler.getX(), satz.scale());
                blick.mz = Projektion.zuPixel(spieler.getZ(), satz.scale());
            }
            if (start != null) {
                zentriere(start[0], start[1]);
            }
        }
        int x = width - KNOPF - 4;
        AbstractWidget unterster = addRenderableWidget(Button.builder(Component.translatable("heroicmap.karte.laden"),
                b -> minecraft.gui.setScreen(new Auswahl(this))).bounds(x, 4, KNOPF, 20).build());
        // Eine selbst gezeichnete Karte hat keinen Abgleich. Siehe docs/selbst.md, „Wahl“.
        if (satz != null && !Selbst.selbst(satz.ordner().getParent())) {
            // Der Baum ist der Ordner über dem Massstab.
            baum = satz.ordner().getParent().getFileName().toString();
            abgleich = addRenderableWidget(Button.builder(Component.translatable("heroicmap.karte.abgleich"),
                    b -> zeige(Downloads.INSTANZ.frageAbgleich(baum))).bounds(x, 28, KNOPF, 20).build());
            unterster = abgleich;
        }
        // Wer sich verirrt hat, kommt zum eigenen Spieler zurück; Stufe und Lupe bleiben. Siehe docs/vollbildkarte.md, „Bedienung“.
        if (blick != null) {
            unterster = addRenderableWidget(Button.builder(Component.translatable("heroicmap.karte.zum_spieler"), b -> {
                if (minecraft.player != null) {
                    zentriere(minecraft.player.getX(), minecraft.player.getZ());
                }
            }).bounds(x, unterster.getY() + 24, KNOPF, 20).build());
        }
        unterster = ebenen(x, unterster);
        knopfX = x;
        knopfUnten = unterster.getY() + unterster.getHeight();
        // Unten rechts das Menü von /hmap; „Fertig“ dort führt zurück auf die Karte. Siehe docs/vollbildkarte.md, „Bedienung“.
        addRenderableWidget(Button.builder(Component.translatable("heroicmap.karte.optionen"),
                b -> minecraft.gui.setScreen(new Einstellungen(this))).bounds(x, height - 24, KNOPF, 20).build());
    }

    /**
     * Der Knopf „Ebenen“ unter {@code ueber}, ist die Liste offen, darunter je Ebene ein Knopf „An“/„Aus“
     * mit dem Namen links davor, die oberste zuerst, bis über „Optionen …“; passen nicht alle, führt der
     * letzte zu „Ebenen …“. Ohne
     * Ebenen kein Knopf. Gibt den untersten Knopf zurück. Siehe docs/vollbildkarte.md, „Ebenen“.
     */
    private AbstractWidget ebenen(int x, AbstractWidget ueber) {
        schalter.clear();
        gebauteEbenen = Ebenen.INSTANZ.kennungen();
        ebenenStand = Ebenen.INSTANZ.stand();
        ganzEbenen = -1;
        if (blick == null || gebauteEbenen.isEmpty()) {
            return ueber;
        }
        AbstractWidget unterster = addRenderableWidget(Button.builder(Component.translatable(ebenenOffen ? "heroicmap.karte.ebenen_zu" : "heroicmap.karte.ebenen"),
                b -> {
                    ebenenOffen = !ebenenOffen;
                    Kartenlage.ebenenOffen(Downloads.weltOrdner(), ebenenOffen);
                    rebuildWidgets();
                }).bounds(x, ueber.getY() + 24, KNOPF, 20).build());
        if (!ebenenOffen) {
            return unterster;
        }
        boolean deutsch = minecraft.getLanguageManager().getSelected().startsWith("de");
        List<Ebenen.Eintrag> alle = Ebenen.INSTANZ.alle();
        // Platz bis über „Optionen …“ unten rechts, je Schalter 22 Einheiten.
        int platz = Math.max(1, (height - 28 - (unterster.getY() + unterster.getHeight())) / 22);
        int n = alle.size() <= platz ? alle.size() : platz - 1;
        for (int i = 0; i < n; i++) {
            Ebenen.Eintrag e = alle.get(i);
            Component name = Component.literal(e.name(deutsch));
            CycleButton<Boolean> k = addRenderableWidget(CycleButton.onOffBuilder(Ebenen.INSTANZ.an(e)).displayOnlyValue()
                    .create(x + KNOPF - AN_AUS, unterster.getY() + 22, AN_AUS, 20, name, (b, an) -> Ebenen.INSTANZ.setze(e.id(), an)));
            schalter.add(new Schalter(k, e.id(), name));
            unterster = k;
        }
        if (n < alle.size()) {
            unterster = addRenderableWidget(Button.builder(Component.translatable("heroicmap.karte.ebenen_mehr"),
                    b -> minecraft.gui.setScreen(new EbenenMenue(this))).bounds(x, unterster.getY() + 22, KNOPF, 20).build());
        }
        return unterster;
    }

    /** Kommen andere Ebenen vom Server, während die Karte offen ist, baut sie die Liste neu. */
    @Override
    public void tick() {
        // Ohne Satz während eines Stroms: Sobald die gröbste Stufe daliegt, zeigt sich die Karte. Siehe docs/vollbildkarte.md, „Fortschritt“.
        if (satz == null && Downloads.INSTANZ.stufen() != stufen && minecraft.level != null) {
            stufen = Downloads.INSTANZ.stufen();
            Satz neu = Satz.fuer(Downloads.weltOrdner(), minecraft.level.dimension().identifier().toString());
            if (neu != null) {
                minecraft.gui.setScreen(new Karte(neu));
                return;
            }
        }
        if (Ebenen.INSTANZ.stand() != ebenenStand) {
            ebenenStand = Ebenen.INSTANZ.stand();
            if (!Ebenen.INSTANZ.kennungen().equals(gebauteEbenen)) {
                rebuildWidgets();
            }
        }
    }

    /**
     * Die Überschriften der Liste, rechtsbündig vor ihrem Knopf; ein langer Name ragt nach links über die Karte.
     * Vor einer Ebene, die ganz angeheftet ist, ein bunter Punkt; neu gerechnet, wenn sich Ebenen oder Wegpunkte ändern.
     */
    private void ueberschriften(GuiGraphicsExtractor g) {
        if (schalter.isEmpty()) {
            return;
        }
        if (ganzEbenen != Ebenen.INSTANZ.stand() || ganzWegpunkte != Wegpunkte.INSTANZ.stand()) {
            ganzEbenen = Ebenen.INSTANZ.stand();
            ganzWegpunkte = Wegpunkte.INSTANZ.stand();
            ganz = new boolean[schalter.size()];
            for (int i = 0; i < ganz.length; i++) {
                ganz[i] = Wegpunkte.INSTANZ.ganzAngeheftet(Ebenen.INSTANZ, schalter.get(i).ebene());
            }
        }
        int bunt = Mth.hsvToArgb((System.currentTimeMillis() % BUNT_MS) / (float) BUNT_MS, 1f, 1f, 255);
        for (int i = 0; i < ganz.length; i++) {
            Schalter s = schalter.get(i);
            int[] r = ueberschrift(s);
            g.text(font, s.name(), r[0] + 6, r[1] + 6, TEXT, true);
            if (ganz[i]) {
                g.fill(r[0], r[1] + 8, r[0] + 4, r[1] + 12, bunt);
            }
        }
    }

    /**
     * Der Kasten des laufenden Stroms links neben „Karte laden …“: Anteil, Bytes, Tempo und
     * Restzeit, nur aus Bytes empfangen und erwartet. Siehe docs/vollbildkarte.md, „Fortschritt“.
     */
    private void fortschritt(GuiGraphicsExtractor g, Laden.Fortschritt f) {
        int b = 200, x = knopfX - 4 - b, y = 4;
        long gesamt = f.gesamt, empfangen = Math.min(f.empfangen.get(), gesamt);
        double anteil = f.anteil(), tempo = f.tempo(Util.getMillis());
        g.fill(x, y, x + b, y + 38, 0xB0000000);
        g.text(font, Component.translatable("heroicmap.download.laeuft"), x + 5, y + 5, TEXT);
        Component prozent = Component.literal((int) (anteil * 100) + " %");
        g.text(font, prozent, x + b - 4 - font.width(prozent), y + 5, TEXT);
        // Wie der Ladebalken des Spiels: weisser Rand, weiss gefüllt.
        g.outline(x + 5, y + 16, b - 10, 7, TEXT);
        g.fill(x + 7, y + 18, x + 7 + (int) Math.round((b - 14) * anteil), y + 21, TEXT);
        g.text(font, Component.translatable("heroicmap.download.stand", Downloads.zahl(empfangen, gesamt), Downloads.groesse(gesamt)),
                x + 5, y + 27, GRAU);
        Component rest = tempo <= 0 ? Component.literal("…") : Component.translatable("heroicmap.download.tempo",
                Downloads.groesse((long) tempo), Downloads.dauer((long) Math.ceil((gesamt - empfangen) / tempo)));
        g.text(font, rest, x + b - 4 - font.width(rest), y + 27, GRAU);
    }

    /** Wo die Überschrift einer Zeile liegt, samt Platz für den Punkt davor: x, y, Breite, Höhe. */
    private int[] ueberschrift(Schalter s) {
        CycleButton<Boolean> k = s.knopf();
        int breite = font.width(s.name()) + 6;
        return new int[] {k.getX() - 4 - breite, k.getY(), breite, k.getHeight()};
    }

    /** Die Zeile, deren Überschrift unter (x, y) liegt, oder null. */
    private Schalter ueberschriftUnter(double x, double y) {
        for (Schalter s : schalter) {
            if (drin(ueberschrift(s), x, y)) {
                return s;
            }
        }
        return null;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
        g.fill(0, 0, width, height, HINTERGRUND);
        Laden.Fortschritt laeuft = Downloads.INSTANZ.fortschritt();
        if (satz == null) {
            // Während eines Downloads sagt der Kasten oben, was los ist.
            if (laeuft == null) {
                g.centeredText(font, Component.translatable("heroicmap.karte.keine"), width / 2, height / 2, TEXT);
            }
            super.extractRenderState(g, mausX, mausY, delta);
            if (laeuft != null) {
                fortschritt(g, laeuft);
            }
            return;
        }
        // Still gehalten auf einem Wegpunkt hängt er nach HALTEN_MS an der Maus. Siehe docs/wegpunkte.md, „Bedienung“.
        if (haengt == null && taste && gedrueckt != null && gedrueckt.punkt() != null && !doppelklick && gezogen <= ZUG
                && Util.getMillis() - gedruecktSeit >= HALTEN_MS) {
            haengt = gedrueckt.punkt();
            wartend = null;
        }
        // Ebenso eine Ecke einer eigenen Form, und am Griff in ihrer Mitte die ganze Form. Siehe docs/wegpunkte.md, „Eigene Formen“.
        if (eckeHaengt == null && taste && eckeGedrueckt != null && !doppelklick && gezogen <= ZUG
                && Util.getMillis() - gedruecktSeit >= HALTEN_MS) {
            eckeHaengt = eckeGedrueckt;
        }
        if (griffHaengt == null && taste && griffGedrueckt != null && !doppelklick && gezogen <= ZUG
                && Util.getMillis() - gedruecktSeit >= HALTEN_MS) {
            griffHaengt = griffGedrueckt;
        }
        // Die gezogene Form zeichnet die Karte selbst an der Maus; an ihrem alten Ort fällt sie solange weg.
        Wegpunkte.INSTANZ.ziehe(gezogeneForm());
        schwebe(mausX, mausY);
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
        formen(g, mausX, mausY);
        regionen(g, mausX, mausY);
        ecken(g, mausX, mausY);
        marken(g);
        int[] block = block(mausX, mausY);
        g.text(font, Component.literal(satz.name() + "   ").append(Component.translatable("heroicmap.koordinaten", block[0], block[1])),
                4, height - 12, TEXT);
        if (hinweis != null && Util.getMillis() - hinweisSeit >= HINWEIS_MS) {
            hinweis = null;
        }
        if (hinweis != null) {
            g.text(font, hinweis, 4, height - 24, TEXT);
        }
        if (eingabe != null || zug != null) {
            g.text(font, eingabe != null ? eingabeHinweis : Component.translatable("heroicmap.karte.form_hinweis"), 4,
                    height - (hinweis != null ? 36 : 24), TEXT);
        }
        // Höchstens ein Abgleich je Tag: Nach einer Ablehnung mit wieder ist der Knopf bis dahin aus.
        if (abgleich != null) {
            long ab = Downloads.INSTANZ.abgleichAb(baum);
            abgleich.active = ab == 0;
            abgleich.setMessage(ab == 0 ? Component.translatable("heroicmap.karte.abgleich")
                    : Component.translatable("heroicmap.karte.abgleich_ab", Downloads.uhr(ab)));
        }
        super.extractRenderState(g, mausX, mausY, delta);
        ueberschriften(g);
        if (laeuft != null) {
            fortschritt(g, laeuft);
        }
        if (haengt != null) {
            // Der Wegpunkt, der an der Maus hängt, unter dem Zeiger.
            Minimap.wegpunkt(g, mausX, mausY, Minimap.KOPF, haengt, 0);
        }
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
    private void formen(GuiGraphicsExtractor g, int mausX, int mausY) {
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
        // Die gezogene Form folgt der Maus in jedem Frame; sie ist eine, ihr Speicher gilt nur bis zur nächsten Bewegung.
        Wegpunkte.EigeneForm f = gezogeneForm();
        if (f != null && f.dimension().equals(dimension)) {
            Formen.zeichne(g, a, dimension, List.of(Wegpunkte.INSTANZ.aufKarte(f, punkte(f, mausX, mausY))), gezogenSpeicher, font, true);
        }
    }

    /**
     * Was unter der Maus hervorgehoben steht, so wie ein Klick es träfe: ein Wegpunkt oder die Raute eines alten Rechtecks vor
     * einer Ecke vor einem Griff, nach den Lagen des letzten Frames. Nichts, solange ein Menü offen ist, etwas gezogen wird
     * oder die Maus über einem Knopf oder der Tafel steht. Siehe docs/wegpunkte.md, „Bedienung“.
     */
    private void schwebe(int mausX, int mausY) {
        boolean frei = ziel == null && eingabe == null && gezogeneForm() == null && haengt == null && !(taste && gezogen > ZUG)
                && getChildAt(mausX, mausY).isEmpty() && !drin(tafelKasten, mausX, mausY);
        Marke m = frei ? treffer(mausX, mausY) : null;
        ueberMarke = m != null && (m.punkt() != null || m.region() != null) ? m : null;
        ueberEcke = frei && m == null ? eckeUnter(mausX, mausY) : null;
        ueberGriff = frei && m == null && ueberEcke == null ? griffUnter(mausX, mausY) : null;
    }

    /** Die eigene Form, die gerade an einer Ecke oder am Griff an der Maus hängt, oder null. */
    private Wegpunkte.EigeneForm gezogeneForm() {
        return eckeHaengt != null ? eckeHaengt.form() : griffHaengt != null ? griffHaengt.form() : null;
    }

    /**
     * Die Punkte (x, z) der eigenen Form, wie sie gerade steht: die Ecke an der Maus genau unter ihr, am Griff die ganze
     * Form um den Weg der Maus seit dem Drücken, nicht auf ganze Blöcke gerundet; sonst die Mitten der Blöcke.
     */
    private double[] punkte(Wegpunkte.EigeneForm f, double mausX, double mausY) {
        double[] p = Wegpunkte.mitten(f);
        if (eckeHaengt != null && eckeHaengt.form() == f) {
            double[] w = welt(mausX, mausY);
            p[2 * eckeHaengt.index()] = w[0];
            p[2 * eckeHaengt.index() + 1] = w[1];
        } else if (griffHaengt != null && griffHaengt.form() == f) {
            double[] w = welt(mausX, mausY), d = welt(druckX, druckY);
            for (int i = 0; i < p.length; i += 2) {
                p[i] += w[0] - d[0];
                p[i + 1] += w[1] - d[1];
            }
        }
        return p;
    }

    /** Der Ort der Welt unter (x, y) in Blöcken, nicht gerundet; {@link #block} rundet ihn ab. */
    private double[] welt(double x, double y) {
        return new double[] {blick.basisRasterX(x, width) / satz.scale(), blick.basisRasterZ(y, height) / satz.scale()};
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
        // Ein altes Rechteck steht wie ein Wegpunkt als Raute in seiner Mitte. Siehe docs/wegpunkte.md, „Regionen“.
        for (Wegpunkte.Region r : Wegpunkte.INSTANZ.regionen()) {
            if (r.dimension().equals(dimension)) {
                Marke m = marke((r.x0() + r.x1() + 1) / 2.0, (r.z0() + r.z1() + 1) / 2.0, null, null, r);
                boolean hell = ueberMarke != null && r.equals(ueberMarke.region());
                Minimap.wegpunkt(g, m.x(), m.y(), Minimap.KOPF, Wegpunkte.FARBEN[r.farbe()], hell ? HELL : r.angeheftet() ? bunt : 0);
            }
        }
        for (Wegpunkte.Punkt p : Wegpunkte.INSTANZ.punkte()) {
            if (p.dimension().equals(dimension) && !p.equals(haengt)) {
                Marke m = marke(p.x() + 0.5, p.z() + 0.5, null, p);
                // Unter der Maus mit hellem Rand, auch angeheftet: Dann weicht der bunte Rand so lange.
                boolean hell = ueberMarke != null && p.equals(ueberMarke.punkt());
                Minimap.wegpunkt(g, m.x(), m.y(), Minimap.KOPF, p, hell ? HELL : p.angeheftet() ? bunt : 0);
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
        Minimap.avatar(g, spieler, ich.x(), ich.y(), 1f, Minimap.KOPF, false, Minimap.INSTANZ.darstellung());
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
                // Erst grob die Höhe: Unter dem Fuss reicht kein Name weiter als UNTEN_HOECHSTENS. Dann der Kasten ohne Holen,
                // samt einem Namen im Bogen, der auch über den Fuss steigt; er misst den Namen, holt aber kein Bild.
                if (!n.dimension().equals(dimension) || y <= -Ebenen.UNTEN_HOECHSTENS) {
                    continue;
                }
                float[] r = Ebenen.kastenOhneHolen(font, n);
                if (x + r[2] > 0 && x + r[0] < width && y + r[3] > 0 && y + r[1] < height) {
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

    /** Für die Gametests: die Mitte des Knopfs „An“/„Aus“ und die der Überschrift der Ebene {@code ebene}, oder null. */
    double[] zeile(String ebene) {
        for (Schalter s : schalter) {
            if (s.ebene().equals(ebene)) {
                int[] u = ueberschrift(s);
                CycleButton<Boolean> k = s.knopf();
                return new double[] {k.getX() + k.getWidth() / 2.0, k.getY() + k.getHeight() / 2.0, u[0] + u[2] / 2.0, u[1] + u[3] / 2.0};
            }
        }
        return null;
    }

    /** Für die Gametests: Stufe und Lupe, oder null ohne Satz. */
    int[] stufe() {
        return blick == null ? null : new int[] {blick.zoom, blick.lupe};
    }

    /** Für die Gametests: der Wegpunkt an der Maus, oder null. */
    Wegpunkte.Punkt haengt() {
        return haengt;
    }

    /** Für die Gametests: Steht eine Tafel auf dem Schirm? */
    boolean tafelOffen() {
        return tafelKasten != null;
    }

    /** Für die Gametests: der Kasten der Tafel {x, y, Breite, Höhe} in Einheiten des GUI, oder null. */
    int[] tafelKasten() {
        return tafelKasten == null ? null : tafelKasten.clone();
    }

    /** Für den Gametest Bedienung: die Mitte des Blicks in Pixeln der Basis, das offene Menü, oder null, und die Marken. */
    double[] blickMitte() {
        return blick == null ? null : new double[] {blick.mx, blick.mz};
    }

    /** Für die Gametests: legt die Mitte des Blicks auf die Mitte des Blocks (x, z); Stufe und Lupe bleiben. */
    void zeige(int x, int z) {
        blick.stelle((x + 0.5) * satz.scale(), (z + 0.5) * satz.scale(), blick.zoom, blick.lupe);
    }

    int[] ziel() {
        return ziel;
    }

    /** Für den Gametest: die linke obere Ecke des offenen Menüs, das am Rand des Schirms nach innen rückt. */
    int[] menueLage() {
        return new int[] {menueX, menueY};
    }

    /** Für den Gametest: der Fuss eines Orts auf dem Schirm, in Einheiten des GUI, wie gezeichnet. */
    float[] fuss(Ebenen.Ort o) {
        int k = minecraft.getWindow().getGuiScale();
        return new float[] {aufPixel(blick.rasterX(Projektion.zuPixel(o.x(), satz.scale()), width), k),
            aufPixel(blick.rasterY(Projektion.zuPixel(o.z(), satz.scale()), height), k)};
    }

    /** Für den Gametest: die Ecken der Form im Bau, oder null. */
    List<Wegpunkte.Ecke> zug() {
        return zug == null ? null : List.copyOf(zug);
    }

    /** Zeigt {@code text} unten links, bis {@link #HINWEIS_MS} um sind oder der Spieler klickt oder eine Taste drückt; auch für den Gametest. */
    void zeige(Component text) {
        hinweis = text;
        hinweisSeit = Util.getMillis();
    }

    /** Für den Gametest: was beim Öffnen in die Mitte kommt, oder null. */
    double[] start() {
        return start == null ? null : start.clone();
    }

    /** Für den Gametest: der Block unter (x, y) in Einheiten des GUI. */
    int[] blockUnter(double x, double y) {
        return block(x, y);
    }

    /** Für den Gametest: der Text im Eingabefeld, oder null, wenn es zu ist. */
    String eingabe() {
        return eingabe == null ? null : eingabe.getValue();
    }

    /** Für den Gametest: der Hinweis unten links, oder null. */
    Component hinweis() {
        return hinweis;
    }

    /** Für den Gametest: die Ecke an der Maus, oder null. */
    EckeAn eckeHaengt() {
        return eckeHaengt;
    }

    /** Für den Gametest: die Ecken der eigenen Formen auf dem Schirm, wie gezeichnet. */
    List<EckeAn> ecken() {
        return List.copyOf(ecken);
    }

    /** Für den Gametest: was unter der Maus hervorgehoben steht, eine Marke, eine Ecke oder ein Griff, oder null. */
    Object schwebt() {
        return ueberMarke != null ? ueberMarke : ueberEcke != null ? ueberEcke : ueberGriff;
    }

    /** Für den Gametest: die Bedingungen von {@link #schwebe} bei (x, y), zum Lesen im Fehler. */
    String schwebeGrund(int x, int y) {
        return "ziel " + (ziel != null) + ", gezogen " + (gezogeneForm() != null) + ", haengt " + (haengt != null) + ", taste " + taste
                + ", weg " + gezogen + ", Knopf " + getChildAt(x, y).isPresent() + ", Tafel " + drin(tafelKasten, x, y)
                + ", treffer " + treffer(x, y) + ", Marken " + marken.size();
    }

    /** Für den Gametest: die Griffe der eigenen Formen auf dem Schirm, wie gezeichnet. */
    List<GriffAn> griffe() {
        return List.copyOf(griffe);
    }

    GriffAn griffHaengt() {
        return griffHaengt;
    }

    Satz satz() {
        return satz;
    }

    List<Marke> marken() {
        return List.copyOf(marken);
    }

    List<Component> eintraege() {
        // Nur, solange ein Menü offen ist: Sonst läse der Test die Einträge des vorigen.
        return ziel == null ? List.of() : eintraege.stream().map(Eintrag::text).toList();
    }

    /**
     * Die eigenen Rechtecke dieser Dimension: die Fläche in ihrer Farbe zu 25 %, 1 Einheit Rand deckend,
     * angeheftet {@link Wegpunkte#BREITER} breiter, auf ganzen Pixeln wie die Kacheln; dazu die Vorschau
     * einer Form, die der Spieler baut, gepunktet von Wegpunkt zu Wegpunkt und vom letzten zur Maus.
     * Siehe docs/wegpunkte.md, „Regionen“,
     * und docs/wegpunkte.md, „Eigene Formen“.
     */
    private void regionen(GuiGraphicsExtractor g, int mausX, int mausY) {
        if (minecraft.player == null) {
            return;
        }
        String dimension = minecraft.player.level().dimension().identifier().toString();
        if (zug != null && !dimension.equals(zugDimension)) {
            zug = null;
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
        g.pose().popMatrix();
        if (zug != null) {
            // Punkte alle 3 Einheiten, in Weiss, das auf jeder Karte zu sehen ist; von der Ecke vor der Stelle zur Maus und
            // von dort zur Ecke danach, wo die nächste hinkommt.
            float[] maus = {mausX, mausY};
            for (int i = 0; i < zug.size(); i++) {
                float[] a = schirm(zug.get(i).x() + 0.5, zug.get(i).z() + 0.5);
                if (i + 1 < zug.size() && i + 1 != zugStelle) {
                    gepunktet(g, a, schirm(zug.get(i + 1).x() + 0.5, zug.get(i + 1).z() + 0.5));
                }
                if (i + 1 == zugStelle) {
                    gepunktet(g, a, maus);
                }
                if (i == zugStelle) {
                    gepunktet(g, maus, a);
                }
                raute(g, a[0], a[1], VORSCHAU);
            }
        }
    }

    /**
     * Die Ecken der eigenen Formen dieser Dimension als kleine Rauten in ihrer Farbe, auch die der Form, die gerade
     * geändert wird, dazu je Form ihr Griff in der Mitte; was gezogen wird, dort, wo es an der Maus steht. Gemerkt für
     * Treffer und Gametests. Siehe docs/wegpunkte.md, „Eigene Formen“.
     */
    private void ecken(GuiGraphicsExtractor g, int mausX, int mausY) {
        ecken.clear();
        griffe.clear();
        if (minecraft.player == null) {
            return;
        }
        String dimension = minecraft.player.level().dimension().identifier().toString();
        for (Wegpunkte.EigeneForm f : Wegpunkte.INSTANZ.eigeneFormen()) {
            if (!f.dimension().equals(dimension)) {
                continue;
            }
            int farbe = f.argb();
            double[] p = punkte(f, mausX, mausY);
            for (int i = 0; i < f.ecken().size(); i++) {
                float[] s = schirm(p[2 * i], p[2 * i + 1]);
                ecken.add(new EckeAn(f, i, s[0], s[1]));
                raute(g, s[0], s[1], farbe, ueberEcke != null && ueberEcke.form() == f && ueberEcke.index() == i ? HELL : 0xFF000000);
            }
            double[] m = Wegpunkte.griff(p, f.region());
            float[] s = schirm(m[0], m[1]);
            griffe.add(new GriffAn(f, s[0], s[1]));
            griff(g, s[0], s[1], farbe, ueberGriff != null && ueberGriff.form() == f ? HELL : 0xFF000000);
        }
    }

    /**
     * Der Griff in der Mitte einer eigenen Form: ein Quadrat in ihrer Farbe mit hellem Kern, grösser als eine Ecke, mit dem
     * Rand {@code rand}, dunkel oder unter der Maus hell.
     */
    private static void griff(GuiGraphicsExtractor g, float x, float y, int farbe, int rand) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.fill(-3, -3, 3, 3, rand);
        g.fill(-2, -2, 2, 2, farbe | 0xFF000000);
        g.fill(-1, -1, 1, 1, 0xFFFFFFFF);
        g.pose().popMatrix();
    }

    /** Der Griff einer eigenen Form unter (x, y): der nächste, höchstens {@link #ECKE_TREFFER} weg, oder null. */
    private GriffAn griffUnter(double x, double y) {
        GriffAn beste = null;
        double abstand = ECKE_TREFFER;
        for (GriffAn gr : griffe) {
            double d = Math.hypot(x - gr.x(), y - gr.y());
            if (d <= abstand) {
                beste = gr;
                abstand = d;
            }
        }
        return beste;
    }

    /** Eine kleine Raute bei (x, y) in {@code farbe}, mit dunklem Rand, der sie auf jeder Karte zeigt. */
    private static void raute(GuiGraphicsExtractor g, float x, float y, int farbe) {
        raute(g, x, y, farbe, 0xFF000000);
    }

    /** Wie {@link #raute(GuiGraphicsExtractor, float, float, int)}, mit dem Rand {@code rand}, unter der Maus hell. */
    private static void raute(GuiGraphicsExtractor g, float x, float y, int farbe, int rand) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().rotate((float) (Math.PI / 4));
        g.fill(-2, -2, 2, 2, rand);
        g.fill(-1, -1, 1, 1, farbe | 0xFF000000);
        g.pose().popMatrix();
    }

    /** Die Ecke einer eigenen Form unter (x, y): die nächste, höchstens {@link #ECKE_TREFFER} weg, oder null. */
    private EckeAn eckeUnter(double x, double y) {
        EckeAn beste = null;
        double abstand = ECKE_TREFFER;
        for (EckeAn e : ecken) {
            double d = Math.hypot(x - e.x(), y - e.y());
            if (d <= abstand) {
                beste = e;
                abstand = d;
            }
        }
        return beste;
    }

    /** Der Ort (x, z) der Welt auf dem Schirm, in Einheiten des GUI. */
    private float[] schirm(double x, double z) {
        return new float[] {(float) blick.rasterX(Projektion.zuPixel(x, satz.scale()), width),
            (float) blick.rasterY(Projektion.zuPixel(z, satz.scale()), height)};
    }

    /** Eine gepunktete Linie von a nach b, ein Punkt von einer Einheit alle 3 Einheiten. */
    private static void gepunktet(GuiGraphicsExtractor g, float[] a, float[] b) {
        double laenge = Math.hypot(b[0] - a[0], b[1] - a[1]);
        for (double t = 0; t <= laenge; t += 3) {
            int x = Math.round(a[0] + (float) ((b[0] - a[0]) * t / Math.max(laenge, 1e-9)));
            int y = Math.round(a[1] + (float) ((b[1] - a[1]) * t / Math.max(laenge, 1e-9)));
            g.fill(x, y, x + 1, y + 1, VORSCHAU);
        }
    }

    /** Beginnt eine neue Form mit einer Ecke auf dem Block (x, z) der Dimension des Spielers. */
    private void beginne(int x, int z) {
        zug = new ArrayList<>(List.of(new Wegpunkte.Ecke(x, z)));
        zugDimension = minecraft.player.level().dimension().identifier().toString();
        zugAus = null;
        zugStelle = 1;
    }

    /** Nimmt die eigene Form der Ecke {@code e} in den Bau; neue Ecken kommen zwischen sie und die nächste. */
    private void bearbeite(EckeAn e) {
        zug = new ArrayList<>(e.form().ecken());
        zugDimension = e.form().dimension();
        zugAus = e.form();
        zugStelle = e.index() + 1;
    }

    /**
     * Setzt eine Ecke auf den Block (x, z) an die Stelle der Form im Bau; ohne Form im Bau beginnt eine. Die erste Ecke
     * schliesst eine neue Form bei drei und mehr zur Region; eine Ecke, die schon dabei ist, oder eine über
     * {@link Wegpunkte#MAX_PUNKTE_FORM} kommt nicht dazu.
     */
    private void hinzu(int x, int z) {
        if (zug == null) {
            beginne(x, z);
            return;
        }
        Wegpunkte.Ecke e = new Wegpunkte.Ecke(x, z);
        if (zugAus == null && zug.size() >= 3 && zug.getFirst().equals(e)) {
            fertig();
        } else if (!zug.contains(e) && zug.size() < Wegpunkte.MAX_PUNKTE_FORM) {
            zug.add(zugStelle++, e);
        }
    }

    /**
     * Speichert die Form im Bau: zwei Ecken eine Linie, drei und mehr eine Region; eine geänderte an ihrer Stelle.
     * Sagt es, wenn keine mehr passt oder es die geänderte so nicht mehr gibt.
     */
    private void fertig() {
        if (zug != null && zug.size() >= 2) {
            if (zugAus != null) {
                // Im Bau gelöscht oder sonst geändert: Dann findet ersetze sie nicht, und die neuen Ecken gingen still verloren.
                if (!Wegpunkte.INSTANZ.ersetze(zugAus, zug)) {
                    zeige(Component.translatable("heroicmap.karte.form_weg"));
                }
            } else if (!Wegpunkte.INSTANZ.setzeForm(zugDimension, zug)) {
                zeige(Component.translatable("heroicmap.karte.formen_voll", Wegpunkte.MAX_EIGENE_FORMEN));
            }
        }
        zug = null;
    }

    /** Der Block für eine Ecke unter (x, y): der eines Wegpunkts dort, sonst der einer Ecke im Bau in Reichweite, sonst der darunter. */
    private int[] eckBlock(double x, double y, Marke m) {
        if (m != null && m.punkt() != null) {
            return new int[] {m.punkt().x(), m.punkt().z()};
        }
        if (zug != null) {
            for (Wegpunkte.Ecke e : zug) {
                float[] s = schirm(e.x() + 0.5, e.z() + 0.5);
                if (Math.hypot(x - s[0], y - s[1]) <= ECKE_TREFFER) {
                    return new int[] {e.x(), e.z()};
                }
            }
        }
        return block(x, y);
    }

    /** Die oberste eigene Form dieser Dimension unter (mx, my): in einer Region, oder höchstens 3 Einheiten neben einer Linie. */
    private Wegpunkte.EigeneForm eigeneUnter(double mx, double my) {
        if (minecraft.player == null || satz == null || blick == null) {
            return null;
        }
        String dimension = minecraft.player.level().dimension().identifier().toString();
        int scale = satz.scale();
        double wx = blick.basisRasterX(mx, width) / scale, wz = blick.basisRasterZ(my, height) / scale;
        // So viele Blöcke sind 3 Einheiten des GUI auf dieser Stufe.
        double nah = 3 / (blick.rasterX(Projektion.zuPixel(1, scale), width) - blick.rasterX(0, width));
        List<Wegpunkte.EigeneForm> alle = Wegpunkte.INSTANZ.eigeneFormen();
        for (int i = alle.size() - 1; i >= 0; i--) {
            Wegpunkte.EigeneForm f = alle.get(i);
            Ebenen.Form form = Wegpunkte.INSTANZ.form(f);
            if (form == null || !dimension.equals(form.dimension())) {
                continue;
            }
            if (form instanceof Ebenen.Linie l ? Tafeln.abstand(l.punkte(), wx, wz) <= nah : Tafeln.trifft(form, wx, wz)) {
                return f;
            }
        }
        return null;
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
     * einen Eintrag tut etwas. Solange eine Form im Bau ist, fügt ein Linksklick ohne Zug auf einen Wegpunkt ihn an. Siehe docs/vollbildkarte.md, „Bedienung“;
     * Marken: siehe docs/wegpunkte.md, „Bedienung“.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doppeltNachZeit) {
        // Der nächste Klick nimmt den Hinweis weg; was er selbst meldet, kommt danach.
        hinweis = null;
        // Das Spiel meldet einen Doppelklick nach Zeit und Taste, nicht nach dem Ort; er zählt nur nah am vorigen Druckpunkt.
        // Siehe docs/wegpunkte.md, „Bedienung“.
        boolean doppelt = doppeltNachZeit && Math.hypot(e.x() - druckX, e.y() - druckY) <= ZUG;
        // Jeder Klick, der true gibt, zählt für den nächsten Doppelklick; gemerkt bleibt nur ein Klick auf eine Marke oder ein Ziel mit id.
        Marke vorige = letzte;
        Tafeln.Ziel voriges = letztesZiel;
        Wegpunkte.EigeneForm vorigeEigene = letzteEigene;
        Schalter vorigerSchalter = letzterSchalter;
        letzte = null;
        letztesZiel = null;
        letzteEigene = null;
        letzterSchalter = null;
        gedrueckt = null;
        eckeGedrueckt = null;
        griffGedrueckt = null;
        aufKarte = false;
        taste = e.button() == InputConstants.MOUSE_BUTTON_LEFT;
        gezogen = 0;
        druckX = e.x();
        druckY = e.y();
        // Ein Klick neben das Eingabefeld bricht die Eingabe ab und tut sonst nichts; einer darauf setzt den Cursor.
        if (eingabe != null) {
            if (eingabe.isMouseOver(e.x(), e.y())) {
                super.mouseClicked(e, doppelt);
            } else {
                schliesseEingabe();
            }
            klickVerbraucht = true;
            return true;
        }
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
        // Ebenso auf eine eigene Form. Siehe docs/wegpunkte.md, „Eigene Formen“.
        if (doppelt && vorigeEigene != null && taste && getChildAt(e.x(), e.y()).isEmpty() && vorigeEigene.equals(eigeneUnter(e.x(), e.y()))) {
            anheften(Wegpunkte.INSTANZ.umschalten(vorigeEigene), "heroicmap.karte.angeheftet_voll", Wegpunkte.MAX_ANGEHEFTET);
            klickVerbraucht = true;
            return true;
        }
        // Ein Doppelklick auf die Überschrift einer Ebene heftet alles von ihr an oder löst es; ein Klick darauf
        // tut nichts und zieht die Karte nicht. Der Knopf daneben schaltet nur. Siehe docs/vollbildkarte.md, „Ebenen“.
        Schalter s = taste ? ueberschriftUnter(e.x(), e.y()) : null;
        if (s != null) {
            if (doppelt && s.equals(vorigerSchalter)) {
                int uebrig = Wegpunkte.INSTANZ.alleUmschalten(Ebenen.INSTANZ, s.ebene());
                if (uebrig > 0) {
                    zeige(Component.translatable("heroicmap.karte.ebene_voll", uebrig, Wegpunkte.MAX_ANGEHEFTET, Wegpunkte.MAX_NADELN_ANGEHEFTET));
                }
            } else {
                letzterSchalter = s;
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
            gedruecktSeit = Util.getMillis();
            aufKarte = true;
            // Eine Ecke lässt sich nur ohne Form im Bau halten, und nur, wo keine Marke liegt; ein Griff nur, wo auch keine Ecke liegt.
            eckeGedrueckt = zug == null && m == null ? eckeUnter(e.x(), e.y()) : null;
            griffGedrueckt = zug == null && m == null && eckeGedrueckt == null ? griffUnter(e.x(), e.y()) : null;
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
        EckeAn ecke = eckeGedrueckt;
        eckeGedrueckt = null;
        griffGedrueckt = null;
        // Der Wegpunkt an der Maus kommt auf den Block unter ihr, mit Farbe und Anheften.
        if (haengt != null) {
            int[] b = block(e.x(), e.y());
            Wegpunkte.INSTANZ.verschiebe(haengt, b[0], b[1]);
            haengt = null;
            return true;
        }
        // Ebenso die Ecke an der Maus; liegt sie dann auf dem Block ihrer Nachbarin, werden beide eine.
        if (eckeHaengt != null) {
            int[] b = block(e.x(), e.y());
            List<Wegpunkte.Ecke> neu = new ArrayList<>(eckeHaengt.form().ecken());
            neu.set(eckeHaengt.index(), new Wegpunkte.Ecke(b[0], b[1]));
            Wegpunkte.INSTANZ.ersetze(eckeHaengt.form(), neu);
            eckeHaengt = null;
            return true;
        }
        // Am Griff die ganze Form, um den Weg der Maus auf ganze Blöcke gerundet.
        if (griffHaengt != null) {
            double[] w = welt(e.x(), e.y()), d = welt(druckX, druckY);
            int dx = (int) Math.round(w[0] - d[0]), dz = (int) Math.round(w[1] - d[1]);
            Wegpunkte.INSTANZ.ersetze(griffHaengt.form(), griffHaengt.form().ecken().stream()
                    .map(c -> new Wegpunkte.Ecke(c.x() + dx, c.z() + dz)).toList());
            griffHaengt = null;
            return true;
        }
        // Solange eine Form im Bau ist, setzt ein Linksklick ohne Zug auf die Karte eine Ecke: auf einem Wegpunkt auf
        // dessen Block, statt ihn zu zentrieren, sonst auf den Block darunter.
        if (zug != null && aufKarte && e.button() == InputConstants.MOUSE_BUTTON_LEFT && gezogen <= ZUG && !doppelklick) {
            int[] b = eckBlock(e.x(), e.y(), m);
            hinzu(b[0], b[1]);
            return true;
        }
        // Ein Klick ohne Zug auf ein Ziel ohne Marke merkt es für einen Doppelklick; eine Tafel hält er nicht. Wie gezeichnet:
        // Eine Nadel geht einer eigenen Form vor, eine eigene Form einer Fläche oder einem Kreis vom Server darunter.
        if (m == null && e.button() == InputConstants.MOUSE_BUTTON_LEFT && gezogen <= ZUG && ziel == null && blick != null
                && !drin(tafelKasten, e.x(), e.y())) {
            Tafeln.Ziel nadel = nadelUnter(e.x(), e.y());
            letzteEigene = nadel == null ? eigeneUnter(e.x(), e.y()) : null;
            letztesZiel = nadel != null ? nadel : letzteEigene == null ? formUnter(e.x(), e.y()) : null;
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
            zeige(Component.translatable(text, grenze));
        }
    }

    /**
     * Die Tafel des Ziels unter dem Zeiger: Zeigen je Frame, dann, wenn eine offen ist, die Tafel neben
     * der Stelle, an der sie aufging, wie die Tooltips des Spiels; solange die Antwort aussteht, eine
     * kleine Tafel „lädt …“. Siehe docs/ebenen.md, „Infotafel“.
     */
    private void tafel(GuiGraphicsExtractor g, int mausX, int mausY) {
        long ms = Util.getMillis();
        // Solange eine Form im Bau ist, geht keine Tafel auf: Sie ginge dort auf, wo das Menü war, und läge über
        // den Stellen, die der Spieler anklickt. Siehe docs/wegpunkte.md, „Eigene Formen“.
        if (zug != null && zeigen.offen() != null) {
            zeigen.zu();
        }
        boolean ueber = zug == null && drin(tafelKasten, mausX, mausY);
        boolean frei = ziel == null && eingabe == null && zug == null && gezogeneForm() == null && haengt == null && !ueber
                && !(taste && gezogen > ZUG)
                && getChildAt(mausX, mausY).isEmpty();
        zeigen.zeiger(frei ? zielUnter(mausX, mausY) : null, ueber, ms);
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
        // In Einheiten der Tafel, gt Pixel je Einheit: ein ganzer Massstab bis zum GUI-Massstab, so bleibt die Schrift scharf.
        int gs = minecraft.getWindow().getGuiScale(), gt = Tafel.massstab(minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight(), gs);
        float m = gt / (float) gs;
        int breite = (int) (width / m), hoehe = (int) (height / m), ax = (int) (tafelX / m), ay = (int) (tafelY / m);
        int w = s.breite() + 2 * Tafel.INNEN, voll = s.hoehe() + 2 * Tafel.INNEN, h = Math.min(voll, hoehe - 8);
        // Rechts unter dem Zeiger; ist dort kein Platz, links von ihm oder über ihm, so rutscht sie nicht unter ihn.
        int x = ax + 12 + w > breite - 4 ? ax - 12 - w : ax + 12;
        int y = ay + 12 + h > hoehe - 4 ? ay - 12 - h : ay + 12;
        x = Math.max(4, Math.min(x, breite - w - 4));
        y = Math.max(4, Math.min(y, hoehe - h - 4));
        tafelZuHoch = voll > h;
        tafelScroll = Math.max(0, Math.min(tafelScroll, voll - h));
        g.pose().pushMatrix();
        g.pose().scale(m);
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
                    int pw = b.breite() * gt, ph = b.hoehe() * gt;
                    Symbole.Textur bild = Symbole.INSTANZ.tafelBild(offen.ebene(), offen.version(), b.bild().feld(), pw, ph);
                    if (bild != null) {
                        int f = Tafel.faktor(bild.breite(), bild.hoehe(), pw, ph);
                        g.pose().pushMatrix();
                        g.pose().translate(bx, by);
                        g.pose().scale(1f / gt);
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
        g.pose().popMatrix();
        // In Einheiten des GUI, wie die Maus: Treffer, Scrollen und Klicks in die Tafel.
        tafelKasten = new int[] {Math.round(x * m), Math.round(y * m), Math.round(w * m), Math.round(h * m)};
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
            Ebenen.INSTANZ.stand(), Symbole.INSTANZ.stand(), Geheimbanner.INSTANZ.stand(), Formen.generation};
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

    /**
     * Öffnet das Menü an (x, y) für den Block dort, oder für den Wegpunkt {@code punkt}; auf dem Griff einer eigenen Form
     * nur ihr Menü. Siehe docs/wegpunkte.md, „Eigene Formen“.
     */
    private void menue(double x, double y, Wegpunkte.Punkt punkt, Wegpunkte.Region marke) {
        int[] z = punkt != null ? new int[] {punkt.x(), punkt.z()} : block(x, y);
        String dimension = minecraft.level.dimension().identifier().toString();
        List<Eintrag> neu = new ArrayList<>();
        GriffAn griff = punkt == null && marke == null && eckeUnter(x, y) == null ? griffUnter(x, y) : null;
        if (griff != null) {
            formMenue(griff.form(), neu);
            if (zug != null) {
                // Im Bau bleibt die Form im Bau zu beenden, auch am Griff einer anderen.
                bauEnde(neu);
            }
        } else {
            allgemein(x, y, z, dimension, punkt, marke, neu);
        }
        ziel = z;
        eintraege = neu;
        // Das Menü bleibt ganz auf dem Schirm, auch bei grossem GUI-Massstab.
        menueX = Math.max(0, Math.min(Mth.floor(x), width - menueBreite()));
        menueY = Math.max(0, Math.min(Mth.floor(y), height - neu.size() * ZEILE));
    }

    /**
     * Das Menü einer eigenen Form, am Griff in ihrer Mitte: Anheften oder Lösen, ohne Form im Bau neue Ecken hinter der letzten,
     * Löschen. Eine Region schliesst danach wieder an die erste an.
     */
    private void formMenue(Wegpunkte.EigeneForm f, List<Eintrag> neu) {
        neu.add(new Eintrag(Component.translatable(f.angeheftet() ? "heroicmap.karte.loesen" : "heroicmap.karte.anheften"),
                () -> anheften(Wegpunkte.INSTANZ.umschalten(f), "heroicmap.karte.angeheftet_voll", Wegpunkte.MAX_ANGEHEFTET)));
        neu.add(new Eintrag(Component.translatable(f.name() == null ? "heroicmap.karte.name" : "heroicmap.karte.name_aendern"),
                () -> oeffne(f.name() == null ? "" : f.name(), Wegpunkte.MAX_NAME, "heroicmap.karte.name_hinweis", t -> {
                    Wegpunkte.INSTANZ.benenne(f, t);
                    return true;
                })));
        // Leer heisst zurück zur Farbe aus der Liste; was keine Farbe ist, nimmt Enter nicht. Siehe docs/wegpunkte.md, „Farbe“.
        neu.add(new Eintrag(Component.translatable("heroicmap.karte.farbe"), () -> oeffne(Wegpunkte.hex(f.argb()), 7,
                "heroicmap.karte.farbe_hinweis", t -> {
                    int hex = Wegpunkte.hex(t);
                    if (hex == Wegpunkte.KEIN_HEX && !t.isBlank()) {
                        eingabe.setTextColor(UNGUELTIG);
                        return false;
                    }
                    Wegpunkte.INSTANZ.faerbe(f, hex);
                    return true;
                })));
        if (zug == null) {
            neu.add(new Eintrag(Component.translatable("heroicmap.karte.ecke_hinzu"), () -> bearbeite(new EckeAn(f, f.ecken().size() - 1, 0, 0))));
        }
        neu.add(new Eintrag(Component.translatable("heroicmap.karte.form_loeschen"), () -> Wegpunkte.INSTANZ.loesche(f)));
    }

    /**
     * Öffnet an der Stelle des Menüs das Eingabefeld mit {@code wert}, höchstens {@code laenge} Zeichen, und unten links den
     * Hinweis {@code hinweis}; Enter gibt den Text an {@code beiEnter}.
     */
    private void oeffne(String wert, int laenge, String hinweis, Predicate<String> beiEnter) {
        int breite = Math.min(EINGABE_BREITE, width);
        eingabe = new EditBox(font, Math.max(0, Math.min(menueX, width - breite)), Math.max(0, Math.min(menueY, height - 20)), breite, 20,
                Component.translatable(hinweis));
        eingabe.setMaxLength(laenge);
        eingabe.setValue(wert);
        // Was Enter nicht nahm, steht rot, bis der Spieler es ändert.
        eingabe.setResponder(t -> eingabe.setTextColor(EditBox.DEFAULT_TEXT_COLOR));
        this.beiEnter = beiEnter;
        eingabeHinweis = Component.translatable(hinweis);
        addRenderableWidget(eingabe);
        setFocused(eingabe);
    }

    private void schliesseEingabe() {
        removeWidget(eingabe);
        eingabe = null;
        beiEnter = null;
    }

    /** „Form fertig“ ab zwei Ecken und „Form abbrechen“ für die Form im Bau. */
    private void bauEnde(List<Eintrag> neu) {
        if (zug.size() >= 2) {
            neu.add(new Eintrag(Component.translatable("heroicmap.karte.form_fertig"), this::fertig));
        }
        neu.add(new Eintrag(Component.translatable("heroicmap.karte.form_abbrechen"), () -> zug = null));
    }

    /**
     * Das allgemeine Menü an (x, y): Teleport, Wegpunkt, Formen beginnen und bauen, die Einträge einer Ecke dort und eines alten
     * Rechtecks, wenn seine Raute {@code marke} getroffen ist. Die Fläche einer eigenen Form hat hier keine Einträge mehr.
     */
    private void allgemein(double x, double y, int[] z, String dimension, Wegpunkte.Punkt punkt, Wegpunkte.Region region, List<Eintrag> neu) {
        if (minecraft.getConnection() != null && !minecraft.level.dimensionType().hasCeiling()
                && Teleport.erlaubt(minecraft.getConnection().getCommands())) {
            neu.add(new Eintrag(Component.translatable("heroicmap.karte.teleport", z[0], z[1]), () -> {
                Teleport.los(minecraft, satz, dimension, z[0], z[1]);
                onClose();
            }));
        }
        neu.add(punkt != null
                ? new Eintrag(Component.translatable(punkt.tod() ? "heroicmap.karte.tod_loeschen" : "heroicmap.karte.wegpunkt_loeschen"),
                        () -> Wegpunkte.INSTANZ.loesche(punkt))
                : new Eintrag(Component.translatable("heroicmap.karte.wegpunkt"), () -> Wegpunkte.INSTANZ.setze(dimension, z[0], z[1])));
        // Ein Wegpunkt an eingetippten Koordinaten, dann in der Mitte. Siehe docs/wegpunkte.md, „Bedienung“.
        neu.add(new Eintrag(Component.translatable("heroicmap.karte.wegpunkt_bei"), () -> oeffne(z[0] + " " + z[1], 40,
                "heroicmap.karte.wegpunkt_bei_hinweis", t -> {
                    int[] k = Wegpunkte.koordinaten(t);
                    if (k == null) {
                        eingabe.setTextColor(UNGUELTIG);
                        return false;
                    }
                    Wegpunkte.INSTANZ.setze(dimension, k[0], k[1]);
                    zentriere(k[0] + 0.5, k[1] + 0.5);
                    return true;
                })));
        // Eigene Formen: ohne Form im Bau beginnt hier eine, auf einer Ecke kommen neue danach oder sie geht; im Bau eine
        // Ecke hier, „Form fertig“ und „Form abbrechen“. Siehe docs/wegpunkte.md, „Eigene Formen“.
        EckeAn ecke = zug == null ? eckeUnter(x, y) : null;
        if (zug == null) {
            neu.add(new Eintrag(Component.translatable("heroicmap.karte.form_von_hier"), () -> beginne(z[0], z[1])));
        } else {
            int[] b = punkt != null ? z : eckBlock(x, y, null);
            neu.add(new Eintrag(Component.translatable("heroicmap.karte.ecke_hinzu"), () -> hinzu(b[0], b[1])));
            bauEnde(neu);
        }
        if (ecke != null) {
            neu.add(new Eintrag(Component.translatable("heroicmap.karte.ecke_hinzu"), () -> bearbeite(ecke)));
            neu.add(new Eintrag(Component.translatable("heroicmap.karte.ecke_loeschen"), () -> {
                List<Wegpunkte.Ecke> rest = new ArrayList<>(ecke.form().ecken());
                rest.remove(ecke.index());
                Wegpunkte.INSTANZ.ersetze(ecke.form(), rest);
            }));
        }
        if (region != null) {
            neu.add(new Eintrag(Component.translatable("heroicmap.karte.region_loeschen"), () -> Wegpunkte.INSTANZ.loesche(region)));
        }
    }

    /**
     * Solange eine Kachel lädt, steht an ihrem Platz, was schon geladen ist: zuerst eine feinere Stufe, verkleinert,
     * denn sie ist schärfer; sonst ihr Ausschnitt aus der nächsten gröberen, vergrössert, und darüber, was von der
     * nächsten feineren schon da ist. Angefragt wird nur eine gröbere, und nur, wenn die Kachel auf der Platte fehlt.
     * Siehe docs/vollbildkarte.md, „Kacheln“.
     */
    private void platzhalter(GuiGraphicsExtractor g, int tx, int ty, int sx, int sy, int seite) {
        for (int k = 1; blick.zoom + k <= blick.stufe && seite >> k > 0; k++) {
            if (fein(g, tx, ty, sx, sy, seite, k, true)) {
                return;
            }
        }
        grob(g, tx, ty, sx, sy, seite);
        if (blick.zoom < blick.stufe) {
            fein(g, tx, ty, sx, sy, seite, 1, false);
        }
    }

    /**
     * Die Teile der Kachel aus der Stufe, die {@code k} feiner ist, je 2 hoch {@code k} mal schmaler, verkleinert; mit
     * {@code ganz} nur, wenn alle geladen sind. True, wenn alle da waren.
     */
    private boolean fein(GuiGraphicsExtractor g, int tx, int ty, int sx, int sy, int seite, int k, boolean ganz) {
        int n = 1 << k, teil = seite >> k, z = blick.zoom + k;
        Identifier[] ids = new Identifier[n * n];
        boolean alle = true;
        for (int i = 0; i < ids.length; i++) {
            ids[i] = kacheln.vorhanden(z, tx * n + i % n, ty * n + i / n);
            alle &= ids[i] != null;
        }
        if (ganz && !alle) {
            return false;
        }
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, ids[i], sx + i % n * teil, sy + i / n * teil, 0, 0, teil, teil, satz.kachel(),
                        satz.kachel(), satz.kachel(), satz.kachel());
            }
        }
        return alle;
    }

    /** Der Ausschnitt der Kachel aus der nächsten gröberen Stufe, die geladen ist, vergrössert; fehlt sie auf der Platte, angefragt. */
    private void grob(GuiGraphicsExtractor g, int tx, int ty, int sx, int sy, int seite) {
        boolean fehlt = kacheln.fehlt(blick.zoom, tx, ty);
        for (int k = 1; blick.zoom - k >= satz.minZoom(); k++) {
            int[] grob = Kartenblick.grob(tx, ty, k, satz.kachel());
            if (grob == null) {
                return;
            }
            Identifier id = fehlt ? kacheln.textur(blick.zoom - k, grob[0], grob[1]) : kacheln.vorhanden(blick.zoom - k, grob[0], grob[1]);
            if (id != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, id, sx, sy, grob[2], grob[3], seite, seite, grob[4], grob[4],
                        satz.kachel(), satz.kachel());
                return;
            }
        }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent ereignis, double dx, double dy) {
        // Ein Zug, der in der Tafel beginnt, oder einer mit einem Wegpunkt an der Maus schiebt die Karte nicht.
        if (klickVerbraucht || haengt != null || eckeHaengt != null || griffHaengt != null) {
            return true;
        }
        // Die Tasten zählen wie in SDL, links ist 1. Siehe docs/entwicklung.md, „Maustasten“.
        if (blick != null && ereignis.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            gezogen = Math.max(gezogen, Math.hypot(ereignis.x() - druckX, ereignis.y() - druckY));
            if (gezogen > ZUG) {
                gedrueckt = null;
                eckeGedrueckt = null;
                griffGedrueckt = null;
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
        hinweis = null;
        // Solange das Eingabefeld offen ist, gehört ihm jede Taste, auch die der Karte: Enter speichert, Escape bricht nur die
        // Eingabe ab. Siehe docs/wegpunkte.md, „Namen“.
        if (eingabe != null) {
            int k = ereignis.key();
            if (k == InputConstants.KEY_ESCAPE) {
                schliesseEingabe();
            } else if (k == InputConstants.KEY_RETURN || k == InputConstants.KEY_NUMPADENTER) {
                if (beiEnter.test(eingabe.getValue())) {
                    schliesseEingabe();
                }
            } else {
                eingabe.keyPressed(ereignis);
            }
            return true;
        }
        // Escape lässt zuerst einen Wegpunkt an der Maus, wo er war; das Loslassen danach tut nichts.
        if (ereignis.key() == InputConstants.KEY_ESCAPE && (haengt != null || eckeHaengt != null || griffHaengt != null)) {
            haengt = null;
            eckeHaengt = null;
            griffHaengt = null;
            // Auch, was gedrückt ist: Sonst hinge es bei gehaltener Taste im nächsten Frame wieder an der Maus, und das Loslassen
            // danach, das hier verbraucht ist, liesse es dort.
            gedrueckt = null;
            eckeGedrueckt = null;
            griffGedrueckt = null;
            klickVerbraucht = true;
            return true;
        }
        // Escape bricht dann eine Region ab, die der Spieler setzt.
        if (ereignis.key() == InputConstants.KEY_ESCAPE && zug != null) {
            zug = null;
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

    /** Beim Schliessen und vor jedem Untermenü: Mitte, Stufe und Lupe merken. */
    @Override
    public void removed() {
        // Was beim Schliessen noch an der Maus hing, steht wieder an seinem Ort.
        Wegpunkte.INSTANZ.ziehe(null);
        if (blick != null && gestellt) {
            Kartenlage.schreibe(Downloads.weltOrdner(), satz,
                    new Kartenlage.Lage(blick.mx / satz.scale(), blick.mz / satz.scale(), blick.zoom, blick.lupe));
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
