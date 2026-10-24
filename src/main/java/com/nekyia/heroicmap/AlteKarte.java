package com.nekyia.heroicmap;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.slf4j.Logger;

/**
 * Die Frage, was mit der Karte geschieht, die dieser Server für eine Dimension zeigt, wenn eine andere an ihre Stelle
 * kommt: löschen, behalten und ruhen lassen, oder abbrechen. Drei Knöpfe wie Vanilla beim Öffnen einer alten Welt;
 * Esc wie Abbrechen, und Abbrechen ändert nichts. Siehe docs/download.md, „Ablage“.
 */
final class AlteKarte extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int TEXT = 0xFFFFFFFF, GRAU = 0xFFB0B0B0;

    /** Was der Spieler wählte; Abbrechen ruft {@code wahl} nicht. */
    enum Wahl { LOESCHEN, BEHALTEN }

    private final Screen zurueck;
    private final Satz alt;
    private final String geteiltMit;
    private final Component danach;
    private final Consumer<Wahl> wahl;
    private final String server;
    /** Die Grösse der alten Karte, gezählt in einem eigenen Thread; null, solange gezählt wird. */
    private volatile String groesse;
    private Component hinweis;

    /**
     * {@code alt} ist die Karte, die dieser Server zeigt; {@code geteiltMit} der andere Server, dem ein Download auch
     * gehört, oder null; {@code danach} sagt, was nach der Wahl geschieht. {@code wahl} läuft auf dem Render-Thread,
     * bei „Löschen“ erst, wenn die Karte weg ist.
     */
    AlteKarte(Screen zurueck, Satz alt, String geteiltMit, Component danach, Consumer<Wahl> wahl) {
        super(Component.translatable("heroicmap.alte.titel"));
        this.zurueck = zurueck;
        this.alt = alt;
        this.geteiltMit = Selbst.selbst(alt.ordner().getParent()) ? null : geteiltMit;
        this.danach = danach;
        this.wahl = wahl;
        this.server = Downloads.INSTANZ.wer();
        Path baum = alt.ordner().getParent();
        Thread.ofPlatform().daemon().name("Heroic Map Grösse").start(() -> {
            long bytes = bytes(baum);
            Minecraft.getInstance().execute(() -> groesse = bytes < 0 ? "?" : Downloads.groesse(bytes));
        });
    }

    @Override
    protected void init() {
        boolean ohne = Zeigt.OHNE.equals(server);
        Component loeschen = geteiltMit == null ? Component.translatable("heroicmap.alte.loeschen")
                : Component.translatable("heroicmap.alte.loeschen_geteilt", geteiltMit);
        Component behalten = ohne ? Component.translatable("heroicmap.alte.behalten_ohne")
                : Component.translatable("heroicmap.alte.behalten", server);
        int y = height / 2 + 30, b1 = 130, b2 = 140, b3 = 90, x = width / 2 - (b1 + b2 + b3 + 8) / 2;
        addRenderableWidget(Button.builder(loeschen, b -> loesche()).bounds(x, y, b1, 20).build());
        addRenderableWidget(Button.builder(behalten, b -> waehle(Wahl.BEHALTEN)).bounds(x + b1 + 4, y, b2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(x + b1 + b2 + 8, y, b3, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mausX, int mausY, float delta) {
        g.fill(0, 0, width, height, 0xFF101010);
        int breite = Math.min(width - 40, 360), y = height / 2 - 70;
        g.centeredText(font, title, width / 2, y, TEXT);
        y += 20;
        Component herkunft = Component.translatable(Selbst.selbst(alt.ordner().getParent())
                ? "heroicmap.alte.herkunft.selbst" : "heroicmap.alte.herkunft.server");
        String g2 = groesse == null ? "…" : groesse;
        Component text = Zeigt.OHNE.equals(server)
                ? Component.translatable("heroicmap.alte.text_ohne", alt.name(), alt.dimension(), herkunft, alt.massstab(), g2)
                : Component.translatable("heroicmap.alte.text", server, alt.name(), alt.dimension(), herkunft, alt.massstab(), g2);
        y = zeilen(g, text, breite, y, TEXT);
        if (geteiltMit != null) {
            y = zeilen(g, Component.translatable("heroicmap.alte.geteilt", geteiltMit), breite, y, TEXT);
        }
        y = zeilen(g, Component.translatable("heroicmap.alte.hinweis"), breite, y + 6, GRAU);
        zeilen(g, danach, breite, y, GRAU);
        if (hinweis != null) {
            g.centeredText(font, hinweis, width / 2, height / 2 + 60, TEXT);
        }
        super.extractRenderState(g, mausX, mausY, delta);
    }

    private int zeilen(GuiGraphicsExtractor g, Component text, int breite, int y, int farbe) {
        List<FormattedCharSequence> zeilen = font.split(text, breite);
        for (FormattedCharSequence zeile : zeilen) {
            g.centeredText(font, zeile, width / 2, y, farbe);
            y += font.lineHeight + 2;
        }
        return y;
    }

    /** Löschen nur, wenn kein Download in die Karte wartet oder läuft; wie in der Kartenliste. */
    private void loesche() {
        Path baum = alt.ordner().getParent();
        if (!Downloads.INSTANZ.halte(baum)) {
            hinweis = Component.translatable("heroicmap.liste.belegt");
            return;
        }
        Kartenliste.loescheBaum(baum, ok -> {
            Downloads.INSTANZ.gibFrei(baum);
            if (ok) {
                waehle(Wahl.LOESCHEN);
            } else {
                hinweis = Component.translatable("heroicmap.liste.fehler");
            }
        });
    }

    private void waehle(Wahl w) {
        minecraft.gui.setScreen(zurueck);
        wahl.accept(w);
    }

    /** Abbrechen und Esc: zurück, ohne etwas zu ändern. */
    @Override
    public void onClose() {
        minecraft.gui.setScreen(zurueck);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Die Bytes aller Dateien unter {@code baum}, ohne Verbindungen zu folgen; -1, wenn es nicht geht. */
    static long bytes(Path baum) {
        long[] summe = {0};
        try {
            Files.walkFileTree(baum, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path datei, BasicFileAttributes a) {
                    if (a.isRegularFile()) {
                        summe[0] += a.size();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path datei, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Heroic Map: Grösse von {} nicht gezählt", baum, e);
            return -1;
        }
        return summe[0];
    }
}
