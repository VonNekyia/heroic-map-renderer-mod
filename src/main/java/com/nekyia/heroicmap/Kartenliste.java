package com.nekyia.heroicmap;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
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
    private static final int ZEILE = 24, OBEN = 44, KNOPF = 60;

    private final Screen zurueck;
    /** Null, solange gezählt wird. */
    private Laden.Bestand bestand;
    private int erste;
    private Component hinweis;

    Kartenliste(Screen zurueck) {
        super(Component.translatable("heroicmap.liste.titel"));
        this.zurueck = zurueck;
        zaehle();
    }

    /** Zählt in einem eigenen Thread und baut die Liste dann neu. */
    private void zaehle() {
        bestand = null;
        Thread.ofPlatform().daemon().name("Heroic Map Kartenliste").start(() -> {
            Laden.Bestand b;
            try {
                b = Laden.bestand(Downloads.wurzel());
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Heroic Map: Karten auf der Platte nicht gezählt", e);
                b = new Laden.Bestand(List.of(), 0);
            }
            Laden.Bestand fertig = b;
            Minecraft.getInstance().execute(() -> {
                bestand = fertig;
                erste = Math.min(erste, Math.max(0, fertig.karten().size() - 1));
                rebuildWidgets();
            });
        });
    }

    /** So viele Zeilen passen zwischen Kopf und den Knopf unten. */
    private int zeilen() {
        return Math.max(1, (height - OBEN - 36) / ZEILE);
    }

    @Override
    protected void init() {
        if (bestand != null) {
            for (int i = erste; i < Math.min(bestand.karten().size(), erste + zeilen()); i++) {
                Laden.AufPlatte e = bestand.karten().get(i);
                addRenderableWidget(Button.builder(Component.translatable("heroicmap.liste.loeschen"), b -> frage(e))
                        .bounds(width - KNOPF - 8, OBEN + (i - erste) * ZEILE, KNOPF, 20).build());
            }
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                .bounds(width / 2 - 50, height - 28, 100, 20).build());
    }

    /** Fragt, ob der Baum weg soll; nicht, solange ein Download in ihn läuft. */
    private void frage(Laden.AufPlatte e) {
        if (Downloads.INSTANZ.belegt(e.ordner())) {
            hinweis = Component.translatable("heroicmap.liste.belegt");
            return;
        }
        minecraft.gui.setScreen(new ConfirmScreen(ja -> {
            minecraft.gui.setScreen(this);
            if (ja) {
                loesche(e);
            }
        }, Component.translatable("heroicmap.liste.frage_titel"),
                Component.translatable("heroicmap.liste.frage", e.pfad(), Downloads.groesse(e.bytes()))));
    }

    private void loesche(Laden.AufPlatte e) {
        bestand = null;
        rebuildWidgets();
        Thread.ofPlatform().daemon().name("Heroic Map Löschen").start(() -> {
            try {
                Laden.loesche(e.ordner());
            } catch (IOException | RuntimeException fehler) {
                LOGGER.warn("Heroic Map: {} nicht ganz gelöscht", e.ordner(), fehler);
                Minecraft.getInstance().execute(() -> hinweis = Component.translatable("heroicmap.liste.fehler"));
            }
            zaehle();
        });
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
            int platz = width - KNOPF - 24;
            for (int i = erste; i < Math.min(bestand.karten().size(), erste + zeilen()); i++) {
                Laden.AufPlatte e = bestand.karten().get(i);
                int y = OBEN + (i - erste) * ZEILE;
                String was = e.satz() == null ? Component.translatable("heroicmap.liste.unvollstaendig").getString()
                        : e.satz().name() + ", " + e.satz().dimension() + ", " + e.satz().massstab() + " px";
                g.text(font, font.plainSubstrByWidth(e.pfad(), platz), 8, y + 1, TEXT);
                g.text(font, font.plainSubstrByWidth(was + " · " + Downloads.groesse(e.bytes()), platz), 8, y + 11, 0xFFB0B0B0);
            }
        }
        if (hinweis != null) {
            g.centeredText(font, hinweis, width / 2, height - 42, TEXT);
        }
        super.extractRenderState(g, mausX, mausY, delta);
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
