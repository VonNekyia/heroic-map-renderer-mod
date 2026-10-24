package com.nekyia.heroicmap;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.slf4j.Logger;

/**
 * Die Karten auf der Platte unter {@code heroicmap/}: je Baum Pfad, Name, Massstab und Grösse,
 * oben die Summe aller Dateien in GB; ein Knopf löscht einen Baum nach Rückfrage. Zählen und
 * Löschen laufen in einem eigenen Thread. Siehe docs/download.md, „Kartenliste“.
 */
final class Kartenliste extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int TEXT = 0xFFFFFFFF;
    private static final int ZEILE = 24, OBEN = 44, KNOPF = 60, ZUSTAND = 72, ORT = 44, ZEIGER = 36, KOPIE = 44;

    private final Screen zurueck;
    /** Null, solange gezählt wird. */
    private Laden.Bestand bestand;
    /** Je doppeltem Baum die anderen, nach dem Zählen ({@link Ort#doppelt}). */
    private Map<Path, List<Path>> doppelt = Map.of();
    private static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    /** Bricht das laufende Zählen ab, oder null, wenn keins läuft. */
    private AtomicBoolean zaehlt;
    private int erste;
    private Component hinweis;
    /** Steht unten die Zeile des Zeigers? Gesetzt in init, nur mit dem Ordner einer Welt. */
    private boolean zeigerZeile;
    /** Ihr Text, oder null. */
    private Component zeigerText;
    /** Dieselbe Welt unter anderen Adressen ({@link Zeiger#fremde}), mit dem Bestand gezählt, nicht bei jedem Neuaufbau. */
    private List<String> fremde = List.of();

    Kartenliste(Screen zurueck) {
        super(Component.translatable("heroicmap.liste.titel"));
        this.zurueck = zurueck;
    }

    /** Zählt in einem eigenen Thread und baut die Liste dann neu; schliesst die Liste vorher, endet es. */
    private void zaehle() {
        bestand = null;
        AtomicBoolean abbruch = new AtomicBoolean();
        zaehlt = abbruch;
        Path welt = Downloads.weltOrdner();
        String dimension = minecraft.level == null ? null : minecraft.level.dimension().identifier().toString();
        Thread.ofPlatform().daemon().name("Heroic Map Kartenliste").start(() -> {
            Laden.Bestand b;
            List<String> f = List.of();
            try {
                b = Laden.bestand(Downloads.wurzel(), abbruch::get);
                f = welt == null || dimension == null ? List.of() : Zeiger.fremde(welt, dimension);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Heroic Map: Karten auf der Platte nicht gezählt", e);
                b = new Laden.Bestand(List.of(), 0);
            }
            Laden.Bestand fertig = b;
            List<String> gleiche = f;
            Minecraft.getInstance().execute(() -> {
                if (zaehlt != abbruch || fertig == null) {
                    return;
                }
                zaehlt = null;
                bestand = fertig;
                fremde = gleiche;
                doppelt = Ort.doppelt(Downloads.wurzel(), fertig.karten().stream().map(Laden.AufPlatte::ordner).toList());
                erste = Math.min(erste, Math.max(0, fertig.karten().size() - 1));
                rebuildWidgets();
            });
        });
    }

    /** Auch beim Dialog zum Löschen: Das Zählen endet und beginnt beim Zurückkommen neu, falls nötig. */
    @Override
    public void removed() {
        if (zaehlt != null) {
            zaehlt.set(true);
            zaehlt = null;
        }
    }

    /** So viele Zeilen passen zwischen Kopf und den Knopf unten. */
    private int zeilen() {
        return Math.max(1, (height - OBEN - 36 - (zeigerZeile ? ZEIGER : 0)) / ZEILE);
    }

    @Override
    protected void init() {
        if (bestand == null && zaehlt == null) {
            zaehle();
        }
        Path welt = Downloads.weltOrdner();
        String wer = Downloads.INSTANZ.wer();
        zeigerZeile = false;
        zeigerText = null;
        if (welt != null) {
            zeiger(welt, wer);
        }
        if (bestand != null) {
            Path ziel = welt == null ? null : Zeiger.ziel(welt, minecraft.level.dimension().identifier().toString());
            for (int i = erste; i < Math.min(bestand.karten().size(), erste + zeilen()); i++) {
                Laden.AufPlatte e = bestand.karten().get(i);
                int y = OBEN + (i - erste) * ZEILE;
                addRenderableWidget(ort(e, width - KNOPF - ZUSTAND - ORT - 16, y));
                addRenderableWidget(zustand(e, welt, ziel, wer, width - KNOPF - ZUSTAND - 12, y));
                addRenderableWidget(Button.builder(Component.translatable("heroicmap.liste.loeschen"), b -> frage(e))
                        .bounds(width - KNOPF - 8, y, KNOPF, 20).build());
            }
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                .bounds(width / 2 - 50, height - 28, 100, 20).build());
    }

    /**
     * Der Knopf „an“ oder „ruht“ für den Server, auf dem der Spieler ist, nur im Ordner seiner Welt. Die eigene Karte
     * eines Servers ohne Namen übernimmt ein Server mit Namen; die eines anderen zeigt er nie. Siehe docs/download.md,
     * „Kartenliste“.
     */
    private Button zustand(Laden.AufPlatte e, Path welt, Path ziel, String wer, int x, int y) {
        Path baum = e.ordner();
        Button knopf;
        // Ein Baum im Ziel des Zeigers zählt hier wie ein eigener Download, nur zum Lesen.
        boolean dort = ziel != null && baum.getParent().equals(ziel);
        if (e.satz() == null || welt == null || !baum.getParent().equals(welt) && !dort) {
            List<String> wo = e.satz() == null ? List.of() : Zeigt.server(baum.getParent(), baum.getFileName().toString());
            knopf = Button.builder(Component.literal("–"), b -> {
            }).bounds(x, y, ZUSTAND, 20).tooltip(Tooltip.create(wo.isEmpty() ? Component.translatable("heroicmap.liste.nur_hier")
                    : Component.translatable("heroicmap.liste.an_auf", String.join(", ", wo)))).build();
            knopf.active = false;
            return knopf;
        }
        if (Selbst.selbst(baum) && (dort || !Selbst.gehoert(baum, wer))) {
            String besitzer = Satz.besitzer(baum);
            if (!dort && Zeigt.OHNE.equals(besitzer) && !Zeigt.OHNE.equals(wer)) {
                knopf = Button.builder(Component.translatable("heroicmap.liste.uebernehmen"), b -> uebernimm(e, wer))
                        .bounds(x, y, ZUSTAND, 20)
                        .tooltip(Tooltip.create(Component.translatable("heroicmap.liste.uebernehmen.beschreibung", wer))).build();
                knopf.active = !Files.exists(welt.resolve(Selbst.baum(e.satz().dimension(), wer)));
            } else {
                knopf = Button.builder(Component.translatable("heroicmap.liste.von", anzeige(besitzer)), b -> {
                }).bounds(x, y, ZUSTAND, 20).build();
                knopf.active = false;
            }
            return knopf;
        }
        Satz gezeigt = Satz.fuer(welt, e.satz().dimension(), wer);
        boolean an = gezeigt != null && gezeigt.ordner().getParent().equals(baum);
        knopf = Button.builder(Component.translatable(an ? "heroicmap.liste.an" : "heroicmap.liste.ruht")
                .withStyle(an ? ChatFormatting.GREEN : ChatFormatting.YELLOW), b -> zeige(e, welt, wer, dort)).bounds(x, y, ZUSTAND, 20).build();
        knopf.active = !an;
        return knopf;
    }

    /**
     * Der Knopf „Ort …“: Adresse, Server und Hash des Ordners der Welt oder nur dieses Baums ändern, auch zwischen Einzelspieler
     * und Server. Grau mit Grund für Karten ohne Hash, aus der Ablage davor, und solange ein Download in den Baum läuft. Siehe
     * docs/download.md, „Ort ändern“.
     */
    private Button ort(Laden.AufPlatte e, int x, int y) {
        Ort.Teile teile = Ort.teile(Downloads.wurzel(), e.ordner().getParent());
        Button knopf = Button.builder(Component.translatable("heroicmap.liste.ort"), b -> minecraft.gui.setScreen(new OrtFenster(this, e, teile)))
                .bounds(x, y, ORT, 20).build();
        if (teile == null || Downloads.INSTANZ.belegt(e.ordner())) {
            knopf.active = false;
            knopf.setTooltip(Tooltip.create(Component.translatable(teile == null ? "heroicmap.liste.ort.nicht" : "heroicmap.liste.belegt")));
        }
        return knopf;
    }

    /** Nach „Ort ändern“, bevor die Liste wieder aufgeht: Sie zählt neu und nennt einen Fehler. */
    void umgezogen(boolean ok) {
        bestand = null;
        hinweis = ok ? null : Component.translatable("heroicmap.ort.fehler");
    }

    /** Dieser Server zeigt ab jetzt diesen Baum für seine Dimension, {@code dort} im Ziel des Zeigers; der bisherige ruht. */
    private void zeige(Laden.AufPlatte e, Path welt, String wer, boolean dort) {
        try {
            Zeigt.setze(welt, wer, e.satz().dimension(), (dort ? Zeiger.DORT : "") + e.ordner().getFileName().toString());
        } catch (IOException fehler) {
            LOGGER.warn("Heroic Map: Wahl der Karte nicht gespeichert", fehler);
            hinweis = Component.translatable("heroicmap.liste.fehler");
        }
        Selbst.INSTANZ.neuGewaehlt(minecraft);
        rebuildWidgets();
    }

    /**
     * Die eigene Karte eines Servers ohne Namen, etwa von vor der Karte je Server, gehört ab jetzt {@code wer}: Der
     * Baum bekommt seinen Namen für diesen Server, und dieser zeigt ihn. Siehe docs/selbst.md, „Wahl“.
     */
    private void uebernimm(Laden.AufPlatte e, String wer) {
        if (Downloads.INSTANZ.belegt(e.ordner())) {
            hinweis = Component.translatable("heroicmap.liste.belegt");
            return;
        }
        Path alt = e.ordner(), welt = alt.getParent();
        Satz satz = e.satz();
        Path neu = welt.resolve(Selbst.baum(satz.dimension(), wer));
        bestand = null;
        zaehlt = new AtomicBoolean();
        rebuildWidgets();
        Thread.ofPlatform().daemon().name("Heroic Map Übernehmen").start(() -> {
            boolean ok = true;
            try {
                Files.move(alt, neu, StandardCopyOption.ATOMIC_MOVE);
                Satz.schreibe(neu, satz.name(), satz.dimension(), satz.massstab(), wer);
                Zeigt.setze(welt, wer, satz.dimension(), neu.getFileName().toString());
            } catch (IOException | RuntimeException fehler) {
                LOGGER.warn("Heroic Map: {} nicht übernommen", alt, fehler);
                ok = false;
            }
            boolean fertig = ok;
            Minecraft.getInstance().execute(() -> {
                if (!fertig) {
                    hinweis = Component.translatable("heroicmap.liste.fehler");
                }
                Selbst.INSTANZ.neuGewaehlt(Minecraft.getInstance());
                if (minecraft.gui.screen() == this) {
                    zaehle();
                } else {
                    zaehlt = null;
                }
            });
        });
    }

    /**
     * Unten, unauffällig: mit Zeiger sein Ziel, „(fehlt)“, wenn es nicht daliegt, „(andere Welt)“, wenn es unter einer anderen
     * Adresse eine andere UID hat, und „lösen“; ohne Zeiger je anderem
     * Ordner derselben Welt „Karte von … nutzen“, erst unter derselben Adresse, dann unter anderen, so viele, wie in die
     * Zeile passen. Ohne solche Ordner nichts. Kein Dialog. Siehe docs/download.md, „Kartenliste“.
     */
    private void zeiger(Path welt, String wer) {
        String server = Zeigt.OHNE.equals(wer) ? Component.translatable("heroicmap.liste.dieser").getString() : wer;
        String name = Zeiger.name(welt);
        int y = height - 28 - ZEIGER + 12;
        if (name != null) {
            String pfad = Downloads.wurzel().relativize(Zeiger.ordner(welt, name)).toString().replace('\\', '/');
            String gilt = Zeiger.ziel(welt, minecraft.level.dimension().identifier().toString()) != null ? "heroicmap.liste.zeiger"
                    : Zeiger.ziel(welt) != null ? "heroicmap.liste.zeiger_andere" : "heroicmap.liste.zeiger_fehlt";
            zeigerText = Component.translatable(gilt, server, pfad);
            addRenderableWidget(Button.builder(Component.translatable("heroicmap.liste.zeiger_loesen"), b -> {
                try {
                    Zeiger.loese(welt);
                } catch (IOException fehler) {
                    LOGGER.warn("Heroic Map: Zeiger nicht gelöst", fehler);
                    hinweis = Component.translatable("heroicmap.liste.fehler");
                }
                Selbst.INSTANZ.neuGewaehlt(minecraft);
                rebuildWidgets();
            }).bounds(8, y, 60, 20).build());
            kopieKnopf(welt, wer, name, 72, y);
            zeigerZeile = true;
            return;
        }
        List<String> andere = new ArrayList<>(Zeiger.kandidaten(welt));
        andere.addAll(fremde);
        if (andere.isEmpty()) {
            return;
        }
        zeigerText = Component.translatable("heroicmap.liste.gleiche_welt", String.join(", ", andere.stream().map(Kartenliste::anzeige).toList()));
        int x = 8;
        for (String k : andere) {
            Component text = Component.translatable("heroicmap.liste.zeiger_setzen", anzeige(k));
            int breite = Math.min(160, font.width(text) + 16);
            // Unter einer anderen Adresse gibt es kein „Kopie“ daneben.
            if (x + breite + (k.startsWith(Zeiger.FREMD) ? 0 : KOPIE) > width - 8) {
                break;
            }
            addRenderableWidget(Button.builder(text, b -> {
                try {
                    Zeiger.setze(welt, k);
                } catch (IOException fehler) {
                    LOGGER.warn("Heroic Map: Zeiger nicht gesetzt", fehler);
                    hinweis = Component.translatable("heroicmap.liste.fehler");
                }
                Selbst.INSTANZ.neuGewaehlt(minecraft);
                rebuildWidgets();
            }).bounds(x, y, breite, 20).build());
            x += breite + 4;
            x += kopieKnopf(welt, wer, k, x, y);
        }
        zeigerZeile = true;
    }

    /**
     * Neben „Karte von … nutzen“ und „lösen“: „Kopie“ macht die Karte von {@code k} für die Dimension des Spielers zur eigenen
     * Karte dieses Servers, über die „Selbst“ zeichnet. Kein Knopf, wenn es dort keine gibt, „Selbst“ hier nicht geht oder
     * {@code k} unter einer anderen Adresse liegt; grau mit Grund, wenn dieser Server schon eine eigene hat oder ein Download
     * in die Quelle läuft. Gibt die belegte Breite zurück. Siehe docs/selbst.md, „Kopie“.
     */
    private int kopieKnopf(Path welt, String wer, String k, int x, int y) {
        if (!Selbst.INSTANZ.moeglich(minecraft) || k.startsWith(Zeiger.FREMD)) {
            return 0;
        }
        String dimension = minecraft.level.dimension().identifier().toString();
        Satz satz = Satz.fuer(Zeiger.ordner(welt, k), dimension, k);
        if (satz == null) {
            return 0;
        }
        Path quelle = satz.ordner().getParent();
        Button knopf = Button.builder(Component.translatable("heroicmap.liste.kopie"), b -> kopiere(welt, wer, quelle))
                .bounds(x, y, KOPIE, 20).tooltip(Tooltip.create(Component.translatable("heroicmap.liste.kopie.beschreibung", anzeige(k))))
                .build();
        if (Files.exists(welt.resolve(Selbst.baum(dimension, wer)))) {
            knopf.active = false;
            knopf.setTooltip(Tooltip.create(Component.translatable("heroicmap.liste.kopie.da")));
        } else if (Downloads.INSTANZ.belegt(quelle)) {
            knopf.active = false;
            knopf.setTooltip(Tooltip.create(Component.translatable("heroicmap.liste.belegt")));
        }
        addRenderableWidget(knopf);
        return KOPIE + 4;
    }

    /** Kopiert per Hardlink; kann das Laufwerk keine, erst nach Rückfrage mit der Grösse, die das Zählen schon kennt. */
    private void kopiere(Path welt, String wer, Path quelle) {
        if (Kopie.hartMoeglich(welt.getParent())) {
            kopiereJetzt(welt, wer, quelle, true);
            return;
        }
        long bytes = bestand == null ? 0 : bestand.karten().stream().filter(e -> e.ordner().equals(quelle)).mapToLong(Laden.AufPlatte::bytes).sum();
        minecraft.gui.setScreen(new ConfirmScreen(ja -> {
            minecraft.gui.setScreen(this);
            if (ja) {
                kopiereJetzt(welt, wer, quelle, false);
            }
        }, Component.translatable("heroicmap.liste.kopie.frage_titel"), Component.translatable("heroicmap.liste.kopie.frage", Downloads.groesse(bytes))));
    }

    /** Hält die Quelle gegen einen Download, kopiert in einem eigenen Thread; danach zeigt dieser Server die Kopie, die Liste zählt neu. */
    private void kopiereJetzt(Path welt, String wer, Path quelle, boolean hart) {
        Satz satz = Satz.lies(quelle);
        if (satz == null || !Downloads.INSTANZ.halte(quelle)) {
            hinweis = Component.translatable("heroicmap.liste.belegt");
            return;
        }
        Path ziel = welt.resolve(Selbst.baum(satz.dimension(), wer));
        bestand = null;
        zaehlt = new AtomicBoolean();
        rebuildWidgets();
        Thread.ofPlatform().daemon().name("Heroic Map Kopie").start(() -> {
            boolean ok = true;
            try {
                Kopie.kopiere(quelle, ziel, wer, hart);
                Zeigt.setze(welt, wer, satz.dimension(), ziel.getFileName().toString());
            } catch (IOException | RuntimeException fehler) {
                LOGGER.warn("Heroic Map: {} nicht nach {} kopiert", quelle, ziel, fehler);
                ok = false;
            }
            boolean fertig = ok;
            Minecraft.getInstance().execute(() -> {
                Downloads.INSTANZ.gibFrei(quelle);
                if (!fertig) {
                    hinweis = Component.translatable("heroicmap.liste.kopie.fehler");
                }
                Selbst.INSTANZ.neuGewaehlt(Minecraft.getInstance());
                if (minecraft.gui.screen() == this) {
                    zaehle();
                } else {
                    zaehlt = null;
                }
            });
        });
    }

    /** Der Name einer anderen Karte für die Zeile des Zeigers; ohne Namen ein Wort. */
    private static String anzeige(String karte) {
        if (karte.startsWith(Zeiger.FREMD)) {
            // @<adresse> oder @<adresse>/karte-<name>: „<adresse>“ oder „<name> (<adresse>)“.
            String[] teile = karte.substring(Zeiger.FREMD.length()).split("/");
            return teile.length == 1 ? teile[0] : teile[1].substring(Ort.KARTE.length()) + " (" + teile[0] + ")";
        }
        return Zeigt.OHNE.equals(karte) ? Component.translatable("heroicmap.liste.ohne_namen").getString() : karte;
    }

    /** Fragt, ob der Baum weg soll; nicht, solange ein Download in ihn läuft. */
    private void frage(Laden.AufPlatte e) {
        if (Downloads.INSTANZ.belegt(e.ordner())) {
            hinweis = Component.translatable("heroicmap.liste.belegt");
            return;
        }
        minecraft.gui.setScreen(new ConfirmScreen(ja -> {
            minecraft.gui.setScreen(this);
            // Während der Rückfrage kann eine freigabe einen Download in den Baum begonnen haben.
            if (ja && !Downloads.INSTANZ.halte(e.ordner())) {
                hinweis = Component.translatable("heroicmap.liste.belegt");
            } else if (ja) {
                loesche(e);
            }
        }, Component.translatable("heroicmap.liste.frage_titel"),
                Component.translatable(Selbst.selbst(e.ordner()) ? "heroicmap.liste.frage_selbst" : "heroicmap.liste.frage",
                        e.pfad(), Downloads.groesse(e.bytes()))));
    }

    /** Löscht den Baum, den {@link Downloads#halte} hält, und gibt ihn danach frei. */
    private void loesche(Laden.AufPlatte e) {
        bestand = null;
        // Gezählt wird erst nach dem Löschen.
        zaehlt = new AtomicBoolean();
        rebuildWidgets();
        loescheBaum(e.ordner(), ok -> geloescht(e, ok));
    }

    /**
     * Löscht einen Baum in einem eigenen Thread, eine eigene Karte im Worker, der in sie zeichnet, nach allem, was er
     * noch schreibt; dann {@code danach} auf dem Render-Thread mit wahr, wenn alles weg ist. Den Baum hält der Aufrufer
     * vorher mit {@link Downloads#halte} und gibt ihn danach frei.
     */
    static void loescheBaum(Path baum, Consumer<Boolean> danach) {
        if (Selbst.selbst(baum)) {
            Selbst.INSTANZ.loesche(baum, danach);
            return;
        }
        Thread.ofPlatform().daemon().name("Heroic Map Löschen").start(() -> {
            boolean ganz = true;
            try {
                Laden.loesche(baum);
            } catch (IOException | RuntimeException fehler) {
                LOGGER.warn("Heroic Map: {} nicht ganz gelöscht", baum, fehler);
                ganz = false;
            }
            boolean ok = ganz;
            Minecraft.getInstance().execute(() -> danach.accept(ok));
        });
    }

    /** Auf dem Render-Thread nach dem Löschen: den Schlüssel freigeben, einen Fehler nennen, neu zählen. */
    private void geloescht(Laden.AufPlatte e, boolean ok) {
        Downloads.INSTANZ.gibFrei(e.ordner());
        if (!ok) {
            hinweis = Component.translatable("heroicmap.liste.fehler");
        }
        if (minecraft.gui.screen() == this) {
            zaehle();
        } else {
            zaehlt = null;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
        g.fill(0, 0, width, height, 0xFF101010);
        g.centeredText(font, title, width / 2, 12, TEXT);
        if (bestand == null) {
            g.centeredText(font, Component.translatable("heroicmap.liste.zaehle"), width / 2, 26, TEXT);
        } else {
            g.centeredText(font, Component.translatable("heroicmap.liste.summe", Downloads.gb(bestand.bytes()), bestand.karten().size()),
                    width / 2, 26, TEXT);
            if (bestand.karten().isEmpty()) {
                g.centeredText(font, Component.translatable("heroicmap.liste.keine"), width / 2, height / 2, TEXT);
            }
            int platz = platz();
            for (int i = erste; i < Math.min(bestand.karten().size(), erste + zeilen()); i++) {
                Laden.AufPlatte e = bestand.karten().get(i);
                int y = OBEN + (i - erste) * ZEILE;
                String besitzer = e.satz() == null || !Selbst.selbst(e.ordner()) ? Zeigt.OHNE : Satz.besitzer(e.ordner());
                String was = e.satz() == null ? Component.translatable("heroicmap.liste.unvollstaendig").getString()
                        : e.satz().name() + (Zeigt.OHNE.equals(besitzer) ? "" : " (" + besitzer + ")") + ", "
                                + e.satz().dimension() + ", " + e.satz().massstab() + " px";
                String pfad = pfad(e);
                g.text(font, pfad, 8, y + 1, TEXT);
                if (!pfad.equals(e.pfad()) && mausX >= 8 && mausX < 8 + platz && mausY >= y && mausY < y + 10) {
                    g.setTooltipForNextFrame(font.split(Component.literal(e.pfad()), width - 16), mausX, mausY);
                }
                List<Path> andere = doppelt.get(e.ordner());
                boolean genutzt = andere == null || Ort.genutzt(Downloads.wurzel(), e.ordner(), Minimap.INSTANZ.ablage());
                String marke = genutzt ? "" : " · " + Component.translatable("heroicmap.liste.doppelt").getString();
                String info = font.plainSubstrByWidth(was + " · " + Downloads.groesse(e.bytes()), platz - font.width(marke));
                g.text(font, info, 8, y + 11, 0xFFB0B0B0);
                g.text(font, marke, 8 + font.width(info), y + 11, 0xFFFFFF55);
                if (andere != null && mausX >= 8 && mausX < 8 + platz && mausY >= y + 10 && mausY < y + 20) {
                    g.setTooltipForNextFrame(font.split(doppeltText(e, andere, genutzt), Math.min(300, width - 16)), mausX, mausY);
                }
            }
        }
        if (zeigerText != null) {
            g.text(font, font.plainSubstrByWidth(zeigerText.getString(), width - 16), 8, height - 28 - ZEIGER + 1, 0xFFB0B0B0);
        }
        if (hinweis != null) {
            g.centeredText(font, hinweis, width / 2, height - 42 - (zeigerZeile ? ZEIGER : 0), TEXT);
        }
        super.extractRenderState(g, mausX, mausY, delta);
    }

    /**
     * Der Tooltip an einem doppelten Baum: wo die anderen liegen und wann je zuletzt eine Datei geschrieben wurde, und
     * welchen der Mod nach der jetzigen Ablage nutzt. Siehe docs/download.md, „Kartenliste“.
     */
    private Component doppeltText(Laden.AufPlatte e, List<Path> andere, boolean genutzt) {
        Path wurzel = Downloads.wurzel();
        boolean andererGenutzt = andere.stream().anyMatch(p -> Ort.genutzt(wurzel, p, Minimap.INSTANZ.ablage()));
        String liste = String.join("; ", andere.stream().map(p -> {
            long zuletzt = bestand.karten().stream().filter(k -> k.ordner().equals(p)).mapToLong(Laden.AufPlatte::zuletzt).findFirst().orElse(0);
            return wurzel.relativize(p).toString().replace('\\', '/') + " (" + zeit(zuletzt) + ")";
        }).toList());
        return Component.translatable(genutzt ? "heroicmap.liste.doppelt.genutzt" : andererGenutzt ? "heroicmap.liste.doppelt.andere"
                : "heroicmap.liste.doppelt.keine", liste, zeit(e.zuletzt()));
    }

    private static String zeit(long ms) {
        return ms == 0 ? "–" : ZEIT.format(Instant.ofEpochMilli(ms));
    }

    /** Die Breite der Zeilen links der Knöpfe. */
    private int platz() {
        return width - KNOPF - ZUSTAND - ORT - 32;
    }

    /** Der Pfad, wie seine Zeile ihn zeigt, in der Mitte gekürzt, wenn er nicht passt ({@link Ort#kurz}); paketweit für den Gametest. */
    String pfad(Laden.AufPlatte e) {
        return Ort.kurz(e.pfad(), platz(), font::width);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double weitX, double weitY) {
        if (bestand == null || weitY == 0) {
            return false;
        }
        erste = Mth.clamp(erste - (int) Math.signum(weitY), 0, Math.max(0, bestand.karten().size() - zeilen()));
        rebuildWidgets();
        return true;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(zurueck);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
