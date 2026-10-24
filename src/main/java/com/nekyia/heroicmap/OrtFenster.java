package com.nekyia.heroicmap;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/**
 * Das Fenster „Ort ändern“ zu einer Karte der Kartenliste: Adresse, Server und Hash für den ganzen Ordner ihrer Welt, die
 * Vorgabe, oder nur für diese Karte. Was den Umzug verhindert, steht rot, und „Umziehen“ ist grau. Der Umzug läuft im Worker
 * von „Selbst“, nach allem, was er noch schreibt. Siehe docs/download.md, „Ort ändern“.
 */
final class OrtFenster extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int TEXT = 0xFFFFFFFF, GRAU = 0xFFB0B0B0, GRUEN = 0xFF55FF55, ROT = 0xFFFF5555;
    private static final int FELD = 220, OBEN = 80, ZEILE = 24;

    private final Kartenliste zurueck;
    private final Laden.AufPlatte karte;
    private final Path wurzel = Downloads.wurzel(), welt;
    private final Ort.Teile jetzt;
    /** Die Bäume im Ordner der Welt, beim Öffnen gelesen. */
    private final List<Path> baeume;
    /** Gehört ein Baum im Ordner dem Server des Ordners als eigene Karte? Dann bekommt sie mit ihm einen neuen Server. */
    private final boolean eigene;
    private boolean ganz = true, laeuft;
    private String adresse, server, hash;
    private Button umziehen, abbrechen;
    /** Nach {@link #pruefe}: der neue Ort, oder null; der Grund dagegen, oder null; ein Hinweis, was mitzieht. */
    private Ort.Teile neu;
    private Component grund, mit;
    private Path ziel;

    OrtFenster(Kartenliste zurueck, Laden.AufPlatte karte, Ort.Teile jetzt) {
        super(Component.translatable("heroicmap.ort.titel"));
        this.zurueck = zurueck;
        this.karte = karte;
        this.welt = karte.ordner().getParent();
        this.jetzt = jetzt;
        adresse = jetzt.adresse();
        server = jetzt.server();
        hash = jetzt.hash();
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
        Button ganzKnopf = Button.builder(Component.translatable("heroicmap.ort.ganz").withStyle(ganz ? ChatFormatting.GREEN : ChatFormatting.WHITE),
                b -> art(true)).bounds(mitte - 154, 52, 150, 20).build();
        Button karteKnopf = Button.builder(Component.translatable("heroicmap.ort.karte").withStyle(ganz ? ChatFormatting.WHITE : ChatFormatting.GREEN),
                b -> art(false)).bounds(mitte + 4, 52, 150, 20).build();
        ganzKnopf.active = !ganz;
        karteKnopf.active = ganz;
        addRenderableWidget(ganzKnopf);
        addRenderableWidget(karteKnopf);
        feld(0, "heroicmap.ort.adresse", adresse, 253, t -> adresse = t);
        feld(1, "heroicmap.ort.server", server, 64, t -> server = t);
        feld(2, "heroicmap.ort.hash", hash, 16, t -> hash = t);
        umziehen = addRenderableWidget(Button.builder(Component.translatable("heroicmap.ort.umziehen"), b -> umziehe())
                .bounds(mitte - 104, OBEN + 3 * ZEILE + 34, 100, 20).build());
        abbrechen = addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(mitte + 4, OBEN + 3 * ZEILE + 34, 100, 20).build());
        pruefe();
    }

    private void feld(int i, String name, String wert, int laenge, Consumer<String> setze) {
        EditBox f = addRenderableWidget(new EditBox(font, width / 2 - 80, OBEN + i * ZEILE, FELD, 20, Component.translatable(name)));
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

    private void art(boolean ganzerOrdner) {
        ganz = ganzerOrdner;
        rebuildWidgets();
    }

    /** Der Name des Baums am neuen Ort: eine eigene Karte heisst nach ihrem neuen Server, sonst wie bisher. */
    private String baumName(Ort.Teile t) {
        Path baum = karte.ordner();
        return Selbst.selbst(baum) && karte.satz() != null ? Selbst.baum(karte.satz().dimension(), t.wer()) : baum.getFileName().toString();
    }

    /** Liest die Felder und setzt {@link #neu}, {@link #ziel}, {@link #grund} und {@link #mit}; „Umziehen“ nur ohne Grund. */
    private void pruefe() {
        String[] fehler = new String[1];
        neu = Ort.eingabe(adresse, server, hash, fehler);
        ziel = null;
        grund = null;
        mit = null;
        if (neu == null) {
            grund = Component.translatable(fehler[0]);
        } else {
            Path neuWelt = Ort.ordner(wurzel, neu);
            ziel = ganz ? neuWelt : neuWelt.resolve(baumName(neu));
            String gegen = Ort.grund(welt, neuWelt, ziel, ganz, Downloads.weltOrdner(), Selbst.INSTANZ::zeichnetIn,
                    halten().stream().anyMatch(Downloads.INSTANZ::belegt));
            grund = gegen == null ? null : Component.translatable(gegen);
            boolean eigen = ganz ? eigene : Selbst.selbst(karte.ordner());
            Component wem = !eigen || neu.wer().equals(jetzt.wer()) ? Component.empty()
                    : Component.literal(" ").append(Zeigt.OHNE.equals(neu.wer()) ? Component.translatable("heroicmap.ort.eigene.ohne")
                            : Component.translatable("heroicmap.ort.eigene", neu.wer()));
            mit = Component.translatable(ganz ? "heroicmap.ort.mit" : "heroicmap.ort.bleiben", baeume.size()).append(wem);
        }
        if (umziehen != null) {
            umziehen.active = grund == null && !laeuft;
        }
    }

    /** Die Schlüssel, die der Umzug in der Reihe hält: alle Bäume des Ordners, oder diese Karte und ihr neuer Ort. */
    private List<Path> halten() {
        return ganz ? baeume : List.of(karte.ordner(), ziel);
    }

    /** Hält die Schlüssel, zieht im Worker um, gibt sie danach frei und kehrt in die Kartenliste zurück, die neu zählt. */
    private void umziehe() {
        pruefe();
        if (grund != null || laeuft) {
            return;
        }
        List<Path> gehalten = new ArrayList<>();
        for (Path p : halten()) {
            if (!Downloads.INSTANZ.halte(p)) {
                gehalten.forEach(Downloads.INSTANZ::gibFrei);
                grund = Component.translatable("heroicmap.ort.belegt");
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
        g.centeredText(font, was + " · " + Downloads.groesse(karte.bytes()), mitte, 24, GRAU);
        g.centeredText(font, Component.translatable("heroicmap.ort.jetzt", zeige(ganz ? welt : karte.ordner())), mitte, 36, GRAU);
        String[] namen = {"heroicmap.ort.adresse", "heroicmap.ort.server", "heroicmap.ort.hash"};
        String[] hinweise = {null, "heroicmap.ort.server.hinweis", "heroicmap.ort.hash.hinweis"};
        for (int i = 0; i < 3; i++) {
            int y = OBEN + i * ZEILE + 6;
            g.text(font, Component.translatable(namen[i]), mitte - 180, y, TEXT);
            if (hinweise[i] != null) {
                g.text(font, Component.translatable(hinweise[i]), mitte - 80 + FELD + 6, y, GRAU);
            }
        }
        int y = OBEN + 3 * ZEILE + 4;
        if (grund != null) {
            // Der jetzige Ort ist kein Fehler, nur nichts zu tun.
            g.centeredText(font, grund, mitte, y, neu != null && Ort.ordner(wurzel, neu).equals(welt) ? GRAU : ROT);
        } else if (ziel != null) {
            g.centeredText(font, Component.translatable("heroicmap.ort.neu", zeige(ziel)), mitte, y, GRUEN);
            g.centeredText(font, mit, mitte, y + 12, GRAU);
        }
        super.extractRenderState(g, mausX, mausY, delta);
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
