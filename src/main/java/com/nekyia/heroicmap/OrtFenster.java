package com.nekyia.heroicmap;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.slf4j.Logger;

/**
 * Das Fenster „Ort ändern“ zu einer Karte der Kartenliste: Adresse, Server und Hash für den ganzen Ordner ihrer Welt, die
 * Vorgabe, oder nur für diese Karte. Was den Umzug verhindert, steht rot, und „Umziehen“ ist grau. Der Umzug läuft im Worker
 * von „Selbst“, nach allem, was er noch schreibt. Siehe docs/download.md, „Ort ändern“.
 */
final class OrtFenster extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int TEXT = 0xFFFFFFFF, GRAU = 0xFFB0B0B0, GRUEN = 0xFF55FF55, ROT = 0xFFFF5555;
    /** Feld höchstens, Platz für die Namen links und die Hinweise rechts, die Zeilen der Felder, in Einheiten des GUI. */
    private static final int FELD = 220, NAMEN = 90, HINWEISE = 86, OBEN = 94, ZEILE = 22;

    private final Kartenliste zurueck;
    private final Laden.AufPlatte karte;
    private final Path wurzel = Downloads.wurzel(), welt;
    private final Ort.Teile jetzt;
    /** Die Bäume im Ordner der Welt, beim Öffnen gelesen. */
    private final List<Path> baeume;
    /** Gehört ein Baum im Ordner dem Server des Ordners als eigene Karte? Dann bekommt sie mit ihm einen neuen Server. */
    private final boolean eigene;
    /** Die Speicherstände in {@code saves/}: Ordner unter {@code heroicmap/} → Name in {@code saves/}; beim Öffnen gelesen. */
    private final Map<String, String> spielstaende = new HashMap<>();
    private boolean ganz = true, laeuft;
    /** Liegt der neue Ort im Einzelspieler? Dann statt Adresse und Server der Speicherstand. */
    private boolean einzel;
    private String adresse, server, hash, speicherstand;
    private Button umziehen, abbrechen;
    /**
     * Nach {@link #pruefe}: der neue Ort, oder null; der Schlüssel des Grunds dagegen, oder null; was mitzieht; im Einzelspieler,
     * ob es den Speicherstand gibt.
     */
    private Ort.Teile neu;
    private String grund;
    private Component mit, fund;
    private Path ziel;
    /** Der Baum, den Minimap und Vollbildkarte zeigen, oder null; gelesen beim Aufbau und vor dem Umzug. */
    private Path gezeigt;

    OrtFenster(Kartenliste zurueck, Laden.AufPlatte karte, Ort.Teile jetzt) {
        super(Component.translatable("heroicmap.ort.titel"));
        this.zurueck = zurueck;
        this.karte = karte;
        this.welt = karte.ordner().getParent();
        this.jetzt = jetzt;
        einzel = jetzt.einzelspieler();
        adresse = einzel ? "" : jetzt.adresse();
        server = jetzt.server();
        hash = jetzt.hash();
        speicherstand = jetzt.speicherstand();
        try (Stream<Path> alle = Files.list(Minecraft.getInstance().getLevelSource().getBaseDir())) {
            alle.filter(Files::isDirectory).forEach(p -> spielstaende.put(Ort.speicherordner(p.getFileName().toString()), p.getFileName().toString()));
        } catch (IOException e) {
            // Ohne saves/ findet der Hinweis nichts; umziehen geht trotzdem.
        }
        List<Path> b = List.of();
        try (Stream<Path> alle = Files.list(welt)) {
            b = alle.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList();
        } catch (IOException e) {
            LOGGER.warn("Heroic Map: {} nicht gelesen", welt, e);
        }
        baeume = b;
        eigene = baeume.stream().anyMatch(p -> Selbst.gehoert(p, jetzt.wer()));
    }

    @Override
    protected void init() {
        int mitte = width / 2;
        wahl("heroicmap.ort.ganz", ganz, mitte - 154, 48, () -> ganz = true);
        wahl("heroicmap.ort.karte", !ganz, mitte + 4, 48, () -> ganz = false);
        wahl("heroicmap.ort.ziel.server", !einzel, mitte - 154, 70, () -> einzel = false);
        wahl("heroicmap.ort.ziel.einzelspieler", einzel, mitte + 4, 70, () -> einzel = true);
        if (einzel) {
            feld(0, "heroicmap.ort.speicherstand", speicherstand, 255, t -> speicherstand = t);
            feld(1, "heroicmap.ort.hash", hash, 16, t -> hash = t);
        } else {
            feld(0, "heroicmap.ort.adresse", adresse, 253, t -> adresse = t);
            feld(1, "heroicmap.ort.server", server, 64, t -> server = t);
            feld(2, "heroicmap.ort.hash", hash, 16, t -> hash = t);
        }
        umziehen = addRenderableWidget(Button.builder(Component.translatable("heroicmap.ort.umziehen"), b -> umziehe())
                .bounds(mitte - 104, height - 28, 100, 20).build());
        gezeigt = gezeigt();
        abbrechen = addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(mitte + 4, height - 28, 100, 20).build());
        pruefe();
    }

    /** Ein Knopf einer Wahl aus zwei: gewählt grün und grau, sonst ein Klick, der wählt und neu aufbaut. */
    private void wahl(String name, boolean gewaehlt, int x, int y, Runnable waehle) {
        Button b = Button.builder(Component.translatable(name).withStyle(gewaehlt ? ChatFormatting.GREEN : ChatFormatting.WHITE), k -> {
            waehle.run();
            rebuildWidgets();
        }).bounds(x, y, 150, 20).build();
        b.active = !gewaehlt;
        addRenderableWidget(b);
    }

    private void feld(int i, String name, String wert, int laenge, Consumer<String> setze) {
        EditBox f = addRenderableWidget(new EditBox(font, links() + NAMEN, OBEN + i * ZEILE, feld(), 20, Component.translatable(name)));
        f.setMaxLength(laenge);
        f.setValue(wert);
        f.setResponder(t -> {
            setze.accept(t);
            pruefe();
        });
    }

    /** Download und „Selbst“ ändern sich, während das Fenster offen ist. */
    @Override
    public void tick() {
        if (!laeuft) {
            pruefe();
        }
    }

    /** Wo die Namen der Felder beginnen; schmal rücken sie an den Rand. */
    private int links() {
        return Math.max(8, width / 2 - 200);
    }

    /** Die Breite der Felder; schmal werden sie schmaler, damit die Hinweise rechts auf den Schirm passen. */
    private int feld() {
        return Math.min(FELD, width - links() - NAMEN - HINWEISE);
    }

    /** Der Name des Baums am neuen Ort: eine eigene Karte heisst nach ihrem neuen Server, sonst wie bisher. */
    private String baumName(Ort.Teile t) {
        Path baum = karte.ordner();
        return Selbst.selbst(baum) && karte.satz() != null ? Selbst.baum(karte.satz().dimension(), t.wer()) : baum.getFileName().toString();
    }

    /** Liest die Felder und setzt {@link #neu}, {@link #ziel}, {@link #grund}, {@link #mit} und {@link #fund}; „Umziehen“ nur ohne Grund. */
    private void pruefe() {
        String[] fehler = new String[1];
        neu = einzel ? Ort.einzelspieler(speicherstand, hash, fehler) : Ort.eingabe(adresse, server, hash, fehler);
        ziel = null;
        grund = null;
        mit = null;
        fund = null;
        if (neu == null) {
            grund = fehler[0];
        } else {
            if (einzel) {
                String name = spielstaende.get(neu.adresse());
                fund = name == null ? Component.translatable("heroicmap.ort.nicht_gefunden")
                        : Component.translatable("heroicmap.ort.gefunden", name).withStyle(ChatFormatting.GREEN);
            }
            Path neuWelt = Ort.ordner(wurzel, neu);
            ziel = ganz ? neuWelt : neuWelt.resolve(baumName(neu));
            grund = Ort.grund(welt, ganz ? welt : karte.ordner(), neuWelt, ziel, Downloads.weltOrdner(), Selbst.INSTANZ::zeichnetIn,
                    gezeigt, halten().stream().anyMatch(Downloads.INSTANZ::belegt));
            boolean eigen = ganz ? eigene : Selbst.selbst(karte.ordner());
            Component wem = !eigen || neu.wer().equals(jetzt.wer()) ? Component.empty()
                    : Component.literal(" ").append(neu.einzelspieler() ? Component.translatable("heroicmap.ort.eigene.einzelspieler")
                            : Zeigt.OHNE.equals(neu.wer()) ? Component.translatable("heroicmap.ort.eigene.ohne")
                            : Component.translatable("heroicmap.ort.eigene", neu.wer()));
            mit = Component.translatable(ganz ? "heroicmap.ort.mit" : "heroicmap.ort.bleiben", baeume.size()).append(wem);
        }
        if (umziehen != null) {
            umziehen.active = grund == null && !laeuft;
        }
    }

    /** Für den Gametest: der Schlüssel des Grunds gegen den Umzug, oder null. */
    String grund() {
        return grund;
    }

    /** Der Baum der Dimension des Spielers, den Minimap und Vollbildkarte zeigen, wie sie ihn suchen; sonst null. */
    private Path gezeigt() {
        Path hier = Downloads.weltOrdner();
        Satz s = hier == null || minecraft.level == null ? null
                : Satz.fuer(hier, minecraft.level.dimension().identifier().toString(), Downloads.INSTANZ.wer());
        return s == null ? null : s.ordner().getParent();
    }

    /** Die Schlüssel, die der Umzug in der Reihe hält: alle Bäume des Ordners, oder diese Karte und ihr neuer Ort. */
    private List<Path> halten() {
        return ganz ? baeume : List.of(karte.ordner(), ziel);
    }

    /** Hält die Schlüssel, zieht im Worker um, gibt sie danach frei und kehrt in die Kartenliste zurück, die neu zählt. */
    private void umziehe() {
        gezeigt = gezeigt();
        pruefe();
        if (grund != null || laeuft) {
            return;
        }
        List<Path> gehalten = new ArrayList<>();
        for (Path p : halten()) {
            if (!Downloads.INSTANZ.halte(p)) {
                gehalten.forEach(Downloads.INSTANZ::gibFrei);
                grund = "heroicmap.ort.belegt";
                umziehen.active = false;
                return;
            }
            gehalten.add(p);
        }
        laeuft = true;
        umziehen.active = false;
        abbrechen.active = false;
        boolean ganzerOrdner = ganz;
        Path baum = karte.ordner(), neuWelt = Ort.ordner(wurzel, neu);
        String werAlt = jetzt.wer(), werNeu = neu.wer();
        Selbst.INSTANZ.nachArbeit(() -> {
            boolean ok = true;
            try {
                if (ganzerOrdner) {
                    Ort.zieheOrdner(wurzel, welt, neuWelt, werAlt, werNeu);
                } else {
                    Ort.zieheBaum(baum, neuWelt, werNeu);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Heroic Map: {} nicht umgezogen", ganzerOrdner ? welt : baum, e);
                ok = false;
            }
            boolean fertig = ok;
            Minecraft.getInstance().execute(() -> {
                gehalten.forEach(Downloads.INSTANZ::gibFrei);
                Selbst.INSTANZ.neuGewaehlt(Minecraft.getInstance());
                zurueck.umgezogen(fertig);
                if (minecraft.gui.screen() == this) {
                    minecraft.gui.setScreen(zurueck);
                }
            });
        });
    }

    /** Ein Pfad unter {@code heroicmap/} mit „ / “ zwischen den Teilen. */
    private String zeige(Path p) {
        return String.join(" / ", wurzel.relativize(p).toString().replace('\\', '/').split("/"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
        g.fill(0, 0, width, height, 0xFF101010);
        int mitte = width / 2;
        g.centeredText(font, title, mitte, 12, TEXT);
        Satz s = karte.satz();
        String was = s == null ? Component.translatable("heroicmap.liste.unvollstaendig").getString()
                : s.name() + " · " + s.dimension() + " · " + s.massstab() + " px";
        g.centeredText(font, font.plainSubstrByWidth(was + " · " + Downloads.groesse(karte.bytes()), width - 16), mitte, 24, GRAU);
        g.centeredText(font, font.plainSubstrByWidth(Component.translatable("heroicmap.ort.jetzt", zeige(ganz ? welt : karte.ordner())).getString(),
                width - 16), mitte, 36, GRAU);
        String[] namen = einzel ? new String[] {"heroicmap.ort.speicherstand", "heroicmap.ort.hash"}
                : new String[] {"heroicmap.ort.adresse", "heroicmap.ort.server", "heroicmap.ort.hash"};
        String[] hinweise = einzel ? new String[] {"heroicmap.ort.speicherstand.hinweis", "heroicmap.ort.hash.hinweis"}
                : new String[] {null, "heroicmap.ort.server.hinweis", "heroicmap.ort.hash.hinweis"};
        for (int i = 0; i < namen.length; i++) {
            int y = OBEN + i * ZEILE + 6;
            g.text(font, Component.translatable(namen[i]), links(), y, TEXT);
            if (hinweise[i] != null) {
                g.text(font, Component.translatable(hinweise[i]), links() + NAMEN + feld() + 6, y, GRAU);
            }
        }
        int y = OBEN + namen.length * ZEILE + 4;
        if (fund != null) {
            y = zeilen(g, fund, y, GRAU);
        }
        if (grund != null) {
            // Der jetzige Ort ist kein Fehler, nur nichts zu tun.
            zeilen(g, Component.translatable(grund), y, neu != null && Ort.ordner(wurzel, neu).equals(welt) ? GRAU : ROT);
        } else if (ziel != null) {
            y = zeilen(g, Component.translatable("heroicmap.ort.neu", zeige(ziel)), y, GRUEN);
            zeilen(g, mit, y, GRAU);
        }
        super.extractRenderState(g, mausX, mausY, delta);
    }

    /** Mittig, umbrochen auf die Breite des Schirms; gibt die Höhe unter der letzten Zeile zurück. */
    private int zeilen(GuiGraphicsExtractor g, Component text, int y, int farbe) {
        for (FormattedCharSequence zeile : font.split(text, width - 16)) {
            g.centeredText(font, zeile, width / 2, y, farbe);
            y += font.lineHeight + 2;
        }
        return y;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !laeuft;
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
