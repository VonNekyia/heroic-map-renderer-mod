package com.nekyia.heroicmap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntPredicate;
import java.util.zip.Deflater;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.MouseHandler;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.util.Mth;

/**
 * Das Menü und die Vollbildkarte mit echten Eingaben der Maus: Ziehen mit der linken wie der
 * rechten Taste verschiebt die ganze Minimap; ein Klick auf Spieler oder Marke stellt ihr Aussehen ein; auf der Karte verschiebt links ziehen den Inhalt,
 * rechts klicken öffnet das Menü mit Teleport und Wegpunkt; auf einer Marke am Rand ziehen zieht
 * die Karte, ein Klick zentriert sie, ein Doppelklick heftet sie an, ebenso einen Kreis vom Server und
 * ein altes Rechteck; eigene Formen bauen, auch auf leerer Karte, ihre Ecken treffen, löschen, einfügen und verschieben, anheften und löschen. Siehe docs/minimap.md, „Bedienung“, und docs/wegpunkte.md.
 */
public final class Bedienung implements FabricClientGameTest {

    /** Die Tasten, wie das Spiel sie zählt; TestInput reicht die Zahl unverändert durch. Siehe docs/entwicklung.md, „Maustasten“. */
    private static final int LINKS = InputConstants.MOUSE_BUTTON_LEFT, RECHTS = InputConstants.MOUSE_BUTTON_RIGHT;
    /** Das Zittern einer Hand, die die Taste hält: Versatz vom Druckpunkt in Pixeln des Fensters, je 1 bis 2. */
    private static final int[][] ZITTERN = {{1, 0}, {2, 1}, {1, 2}, {0, 1}, {1, 1}, {2, 0}};

    @Override
    public void runTest(ClientGameTestContext context) {
        // Mit Befehlen, sonst schickt der Server execute und tp nicht, und das Teleport-Menü fehlt.
        try (TestSingleplayerContext spiel = context.worldBuilder().adjustSettings(s -> s.setAllowCommands(true)).create(); var _ = Abschluss.langsamerTakt(context, spiel)) {
            spiel.getConnection().waitForChunksRender();
            Bilder.leereWelt(context);
            kleinerSchirm(context);
            context.runOnClient(mc -> mc.gui.setScreen(new Einstellungen()));
            context.waitTicks(2);
            int k = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
            TestInput maus = context.getInput();

            // Gegriffen in der Mitte, auf dem Spieler: Wer zieht, verschiebt, statt die Darstellung zu schalten.
            Minimap.Darstellung darstellung = context.computeOnClient(mc -> Minimap.INSTANZ.darstellung());
            Minimap.Rahmen vorher = rahmen(context);
            ziehe(context, maus, LINKS, vorher, -10 * k, 5 * k);
            Minimap.Rahmen nachher = rahmen(context);
            if (Math.abs(nachher.x() - (vorher.x() - 100)) > 1 || Math.abs(nachher.y() - (vorher.y() + 50)) > 1) {
                throw new AssertionError("Links ziehen: " + vorher + " → " + nachher);
            }

            // Rechts ziehen verschiebt ebenso die ganze Minimap.
            ziehe(context, maus, RECHTS, nachher, 8 * k, 0);
            Minimap.Rahmen rechts = rahmen(context);
            if (Math.abs(rechts.x() - (nachher.x() + 80)) > 1 || rechts.y() != nachher.y()) {
                throw new AssertionError("Rechts ziehen: " + nachher + " → " + rechts);
            }
            if (context.computeOnClient(mc -> Minimap.INSTANZ.darstellung()) != darstellung) {
                throw new AssertionError("Ziehen vom Spieler aus schaltete die Darstellung");
            }
            aussehen(context, maus, k);
            context.runOnClient(mc -> mc.gui.setScreen(null));
            beiF3(context, maus);
            schleierRegler(context, maus, k);

            karte(context, maus, k);
            // Vor tod: Der lässt den Spieler am Ende fallen, und ein toter Spieler hat statt der Karte den Todesschirm.
            knoepfeGross(context, maus);
            teleport(context, spiel.getServer(), maus, k);
            // Vor tod: Der lässt den Spieler am Ende in der Luft, er fällt.
            imBrowser(context, maus, k);
            neueVersion(context, maus, k);
            tod(context, spiel.getServer(), maus, k);
        } finally {
            context.runOnClient(mc -> Wegpunkte.INSTANZ.leeren());
            // Das Menü speichert beim Schliessen; danach wieder die Vorgabe.
            try {
                Files.deleteIfExists(HeroicMap.einstellungen());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            context.runOnClient(mc -> Minimap.INSTANZ.lies(HeroicMap.einstellungen()));
        }
    }

    /**
     * Die Vollbildkarte: links ziehen verschiebt sie, links klicken öffnet kein Menü, rechts klicken
     * öffnet „Hierher teleportieren“, ein Klick darauf teleportiert. Siehe docs/vollbildkarte.md, „Bedienung“.
     */
    private static void karte(ClientGameTestContext context, TestInput maus, int k) {
        Path baum = Bilder.testsatz();
        context.runOnClient(mc -> mc.gui.setScreen(new Karte(Satz.lies(baum))));
        context.waitTicks(5);
        int breite = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
        int hoehe = context.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
        // Links unten in der Karte, fern von den Knöpfen rechts oben.
        double x = breite / 3.0, y = hoehe * 2 / 3.0;

        double[] vorher = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).blickMitte());
        maus.setCursorPos(x * k, y * k);
        context.waitTick();
        maus.holdMouse(LINKS);
        context.waitTick();
        for (int i = 0; i < 10; i++) {
            maus.moveCursor(5 * k, 3 * k);
            context.waitTick();
        }
        maus.releaseMouse(LINKS);
        context.waitTick();
        double[] nachher = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).blickMitte());
        // Der Inhalt folgt der Maus: nach rechts unten ziehen rückt die Mitte nach links oben.
        if (!(nachher[0] < vorher[0] && nachher[1] < vorher[1])) {
            throw new AssertionError("Karte links ziehen: Mitte " + vorher[0] + "," + vorher[1] + " → " + nachher[0] + "," + nachher[1]);
        }

        maus.setCursorPos(x * k, y * k);
        context.waitTick();
        maus.pressMouse(LINKS);
        context.waitTicks(2);
        if (context.computeOnClient(mc -> ((Karte) mc.gui.screen()).ziel()) != null) {
            throw new AssertionError("Links klicken öffnete das Teleport-Menü");
        }

        wegpunkt(context, maus, k, x, y);
        anheften(context, maus, k);
        form(context, maus, k);
        formImTempo(context, maus, k);
        zweiMarkenImTempo(context, maus, k);
        formAufLeererKarte(context, maus, k);
        eckenBearbeiten(context, maus, k);
        formenZiehen(context, maus, k);
        schweben(context, maus, k);
        namen(context, maus, k);
        farbe(context, maus, k);
        hinweisWeg(context, maus, k);
        wegpunktBei(context, maus, k);
        menueJeObjekt(context, maus, k);
        einrasten(context, maus, k);
        rechtsklickImBau(context, maus, k);
        entfernen(context, maus, k);
        eigeneEbenen(context, maus, k);
        ebenenTeilen(context, maus, k);
        knoepfe(context, maus, k);
        ebenen(context, maus, k);
        zumSpieler(context, maus, k);
        optionen(context, maus, k);
        verschieben(context, maus, k);
        lageGemerkt(context, maus, k);
        maus.setCursorPos(x * k, y * k);
        context.waitTick();

        maus.pressMouse(RECHTS);
        context.waitTicks(2);
        int[] ziel = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).ziel());
        if (ziel == null) {
            throw new AssertionError("Rechts klicken öffnete kein Teleport-Menü");
        }

        // Das Menü steht an der Maus; ein Klick wenige Einheiten rechts unten trifft den Eintrag.
        maus.moveCursor(6 * k, 6 * k);
        context.waitTick();
        maus.pressMouse(LINKS);
        context.waitFor(mc -> mc.player != null && Math.floor(mc.player.getX()) == ziel[0] && Math.floor(mc.player.getZ()) == ziel[1], 200);
        context.waitFor(mc -> mc.gui.screen() == null, 40);
    }

    /**
     * Rechts klicken, dann der zweite Eintrag „Wegpunkt setzen“; die Karte so weit ziehen, dass
     * der Wegpunkt am Rand steht; ein Klick darauf legt ihn in die Mitte, ein Doppelklick heftet ihn an.
     * Siehe docs/wegpunkte.md.
     */
    private static void wegpunkt(ClientGameTestContext context, TestInput maus, int k, double x, double y) {
        maus.setCursorPos(x * k, y * k);
        context.waitTick();
        maus.pressMouse(RECHTS);
        context.waitTicks(2);
        int[] ziel = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).ziel());
        // Der zweite Eintrag liegt eine Zeile unter dem ersten.
        maus.moveCursor(6 * k, 20 * k);
        context.waitTick();
        maus.pressMouse(LINKS);
        context.waitTicks(2);
        Wegpunkte.Punkt punkt = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().isEmpty() ? null : Wegpunkte.INSTANZ.punkte().getFirst());
        if (punkt == null || punkt.x() != ziel[0] || punkt.z() != ziel[1]) {
            throw new AssertionError("Wegpunkt setzen: " + punkt + " statt " + ziel[0] + ", " + ziel[1]);
        }

        // Den Inhalt um 400 Einheiten nach rechts ziehen: Der Wegpunkt liegt dann rechts ausserhalb.
        maus.setCursorPos(10 * k, y * k);
        context.waitTick();
        maus.holdMouse(LINKS);
        context.waitTick();
        for (int i = 0; i < 10; i++) {
            maus.moveCursor(40 * k, 0);
            context.waitTick();
        }
        maus.releaseMouse(LINKS);
        context.waitTicks(2);
        Karte.Marke rand = marke(context, punkt);
        int breite = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
        if (Math.abs(rand.x() - (breite - 14)) > 1) {
            throw new AssertionError("Wegpunkt nicht am rechten Rand: " + rand);
        }

        // Auf der Marke drücken und ziehen zieht die Karte, ohne zur Marke zu springen.
        double[] vorZug = mitte(context);
        maus.setCursorPos(rand.x() * k, rand.y() * k);
        context.waitTick();
        maus.holdMouse(LINKS);
        context.waitTick();
        for (int i = 0; i < 5; i++) {
            maus.moveCursor(-10 * k, 0);
            context.waitTick();
        }
        maus.releaseMouse(LINKS);
        context.waitTicks(2);
        double[] nachZug = mitte(context);
        // Stufe 2 von 2, Lupe 1: eine Einheit des GUI ist ein Pixel der Basis.
        if (Math.abs(nachZug[0] - vorZug[0] - 50) > 1 || Math.abs(nachZug[1] - vorZug[1]) > 1) {
            throw new AssertionError("Ziehen auf der Marke: Mitte " + vorZug[0] + "," + vorZug[1] + " → " + nachZug[0] + "," + nachZug[1]);
        }
        warte250();
        context.waitTicks(2);
        if (!Arrays.equals(mitte(context), nachZug)) {
            throw new AssertionError("Das Ziehen auf der Marke zählte als Klick und legte sie in die Mitte");
        }

        warte250();
        rand = marke(context, punkt);
        maus.setCursorPos(rand.x() * k, rand.y() * k);
        context.waitTick();
        long vorKlick = System.currentTimeMillis();
        maus.pressMouse(LINKS);
        context.waitTicks(2);
        double[] gleich = mitte(context);
        // Erst wenn kein zweiter Klick mehr kommen kann, legt der Klick die Marke in die Mitte (mod#75). Das gilt nach der
        // Uhr, nicht nach Ticks: Unter Last dauern zwei Ticks länger als das Fenster, dann sagt die Prüfung nichts.
        long vergangen = System.currentTimeMillis() - vorKlick;
        if (vergangen >= MouseHandler.DOUBLE_CLICK_THRESHOLD_MS) {
            System.out.println("[heroicmap-bedienung] Prüfung „zentriert erst nach dem Fenster“ übersprungen: " + vergangen
                    + " ms bis zur Prüfung, das Fenster ist " + MouseHandler.DOUBLE_CLICK_THRESHOLD_MS + " ms");
        } else if (!Arrays.equals(gleich, nachZug)) {
            throw new AssertionError("Der Klick zentrierte, bevor ein Doppelklick ausgeschlossen war");
        }
        warte250();
        context.waitTicks(2);
        double[] mitte = mitte(context);
        // Der Testsatz hat scale 4: die Mitte des Blocks in Pixeln der Basis.
        if (mitte[0] != (punkt.x() + 0.5) * 4 || mitte[1] != (punkt.z() + 0.5) * 4) {
            throw new AssertionError("Klick auf den Wegpunkt zentriert nicht: " + mitte[0] + "," + mitte[1]);
        }
        if (angeheftet(context, punkt)) {
            throw new AssertionError("Ein Klick heftete den Wegpunkt an");
        }

        // Ein Klick auf einen Eintrag des Menüs zählt für den Doppelklick, aber nicht für die Marke davor.
        warte250();
        maus.setCursorPos(x * k, (y - 40) * k);
        context.waitTick();
        maus.pressMouse(RECHTS);
        context.waitTicks(2);
        maus.moveCursor(6 * k, 20 * k);
        context.waitTick();
        maus.pressMouse(LINKS);
        context.waitTick();
        maus.moveCursor(0, 30 * k);
        maus.pressMouse(LINKS);
        context.waitTicks(2);
        if (context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size()) != 2 || angeheftet(context, punkt)) {
            throw new AssertionError("Nach „Wegpunkt setzen“ heftete ein schneller Klick den Wegpunkt davor an");
        }

        // Den Wegpunkt aus der Mitte schieben, dann ein Doppelklick: angeheftet, und die Karte bleibt stehen (mod#75).
        maus.setCursorPos(x * k, y * k);
        context.waitTick();
        maus.holdMouse(LINKS);
        context.waitTick();
        maus.moveCursor(30 * k, 20 * k);
        context.waitTick();
        maus.releaseMouse(LINKS);
        warte250();
        Karte.Marke mittig = marke(context, punkt);
        double[] vorDoppel = mitte(context);
        maus.setCursorPos(mittig.x() * k, mittig.y() * k);
        context.waitTick();
        doppelklick(maus);
        context.waitTicks(2);
        warte250();
        context.waitTicks(2);
        if (!angeheftet(context, punkt)) {
            throw new AssertionError("Doppelklick heftet den Wegpunkt nicht an");
        }
        if (!Arrays.equals(mitte(context), vorDoppel)) {
            throw new AssertionError("Der Doppelklick bewegte die Karte");
        }

        // Ein Wegpunkt am eigenen Standort: Ein Klick auf den eigenen Kopf am Rand holt den Spieler zurück,
        // ein Rechtsklick dort greift den Wegpunkt, nicht den Kopf.
        context.runOnClient(mc -> Wegpunkte.INSTANZ.setze(mc.level.dimension().identifier().toString(),
                Mth.floor(mc.player.getX()), Mth.floor(mc.player.getZ())));
        context.waitTick();
        warte250();
        Karte.Marke ich = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).marken().stream()
                .filter(m -> m.punkt() == null && m.spieler() == null).findFirst().orElseThrow());
        maus.setCursorPos(ich.x() * k, ich.y() * k);
        context.waitTick();
        maus.pressMouse(LINKS);
        context.waitTicks(2);
        ich = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).marken().stream()
                .filter(m -> m.punkt() == null && m.spieler() == null).findFirst().orElseThrow());
        maus.setCursorPos(ich.x() * k, ich.y() * k);
        context.waitTick();
        maus.pressMouse(RECHTS);
        context.waitTicks(2);
        List<String> eintraege = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eintraege().stream()
                .map(Component::getString).toList());
        String loeschen = context.computeOnClient(mc -> Component.translatable("heroicmap.karte.wegpunkt_loeschen").getString());
        if (!eintraege.contains(loeschen)) {
            throw new AssertionError("Rechtsklick am eigenen Standort: " + eintraege);
        }
        // Ein Klick daneben schliesst das Menü.
        maus.moveCursor(0, -40 * k);
        context.waitTick();
        maus.pressMouse(LINKS);
        context.waitTicks(2);
    }

    /**
     * Ein Kreis und eine Nadel vom Server und ein altes Rechteck: Ein Klick auf den Kreis heftet nichts
     * an, ein Doppelklick heftet ihn an, ein zweiter löst ihn; ein Doppelklick auf die Nadel oder die Raute
     * des Rechtecks heftet sie an. Siehe docs/wegpunkte.md, „Anheften“.
     */
    private static void anheften(ClientGameTestContext context, TestInput maus, int k) {
        int breite = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
        int hoehe = context.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
        double[] mitte = mitte(context);
        // Rechts und links unter der Mitte, fern von den Knöpfen. Stufe 2 von 2, Lupe 1, scale 4: ein Block sind 4 Einheiten.
        double kx = breite / 2.0 + 60, ky = hoehe / 2.0 + 30;
        long cx = Math.round((mitte[0] + 60) / 4), cz = Math.round((mitte[1] + 30) / 4);
        int rx = (int) Math.round((mitte[0] - 70) / 4), rz = (int) Math.round((mitte[1] + 30) / 4);
        // Die Nadel unter der Mitte; geklickt wird 8 Einheiten über ihrem Fuss, auf dem Schild.
        double nx = mitte[0] / 4, nz = (mitte[1] + 50) / 4, ny = hoehe / 2.0 + 50 - 8;
        boolean frei = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).marken().stream()
                .noneMatch(m -> Math.abs(m.x() - kx) < 12 && Math.abs(m.y() - ky) < 12
                        || Math.abs(m.x() - breite / 2.0) < 12 && Math.abs(m.y() - ny) < 12));
        if (!frei) {
            throw new AssertionError("Eine Marke liegt auf dem Kreis; der Test braucht dort freie Karte");
        }
        context.runOnClient(mc -> {
            Ebenen.INSTANZ.empfange(JsonParser.parseString("""
                    {"v":1,"typ":"ebenen","jetzt":1,"ebenen":[{"id":"test:anheften","visible":true,"version":"1"}]}""").getAsJsonObject());
            Ebenen.INSTANZ.teil(Ebenen.Teil.lies("""
                    {"v":1,"typ":"ebene","jetzt":1,"id":"test:anheften","version":"1","teil":1,"teile":1,"objects":[
                      {"type":"circle","id":"see","center":[%d,%d],"radius":6,"fill":"#40C04060"},
                      {"type":"pin","id":"hafen","at":[%s,%s],"name":"Hafen"}]}""".formatted(cx, cz, nx, nz)));
            Wegpunkte.INSTANZ.setze(mc.level.dimension().identifier().toString(), rx, rz, rx + 2, rz + 2);
        });
        context.waitTick();

        warte250();
        maus.setCursorPos(kx * k, ky * k);
        context.waitTick();
        maus.pressMouse(LINKS);
        context.waitTicks(2);
        if (kreisAngeheftet(context)) {
            throw new AssertionError("Ein Klick heftete den Kreis an");
        }
        // Zwei Klicks mit 300 ms Abstand nach der Uhr sind keiner: Den Abstand hält der Test selbst.
        warte250();
        maus.pressMouse(LINKS);
        schlafe(300);
        maus.pressMouse(LINKS);
        context.waitTicks(2);
        if (kreisAngeheftet(context)) {
            throw new AssertionError("Zwei Klicks 300 ms auseinander hefteten den Kreis an");
        }
        for (boolean an : new boolean[] {true, false}) {
            warte250();
            doppelklick(maus);
            context.waitTicks(2);
            if (kreisAngeheftet(context) != an) {
                throw new AssertionError("Doppelklick auf den Kreis: angeheftet " + !an + " statt " + an);
            }
        }

        warte250();
        maus.setCursorPos(breite / 2.0 * k, ny * k);
        context.waitTick();
        doppelklick(maus);
        context.waitTicks(2);
        if (!context.computeOnClient(mc -> Wegpunkte.INSTANZ.nadelAngeheftet("test:anheften", "hafen"))) {
            throw new AssertionError("Doppelklick auf die Nadel heftet sie nicht an");
        }

        warte250();
        Karte.Marke raute = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).marken().stream()
                .filter(m -> m.region() != null).findFirst().orElseThrow(() -> new AssertionError("Keine Raute der Region")));
        maus.setCursorPos(raute.x() * k, raute.y() * k);
        context.waitTick();
        doppelklick(maus);
        context.waitTicks(2);
        if (!context.computeOnClient(mc -> Wegpunkte.INSTANZ.regionen().getFirst().angeheftet())) {
            throw new AssertionError("Doppelklick auf die Raute heftet die Region nicht an");
        }
        context.runOnClient(mc -> Ebenen.INSTANZ.leeren());
        warte250();
    }

    /** Die Tafel der Stadt, wie das Plugin sie schickt: Titel, eine Zeile und dahinter die Knöpfe „Schiff“ und „Zug“ mit Zustand. */
    private static String stadtTafel(String version, boolean schiff, boolean zug) {
        return """
                {"v":1,"typ":"tafel","jetzt":1,"ebene":"test:knopf","version":"%s","id":"stadt","panel":{"blocks":[
                  {"type":"title","text":"Hafenstadt"},
                  {"type":"lines","lines":["Routen per Schiff"]},
                  {"type":"button","id":"beispiel:schiff","label":{"de":"Schiff","en":"Ship"},"on":%s},
                  {"type":"button","id":"beispiel:zug","label":{"de":"Zug","en":"Train"},"on":%s}]}}""".formatted(version, schiff, zug);
    }

    /** Die Ebene test:knopf mit den Nadeln „stadt“, „dorf“ und „voll“, je mit dem Fuss an der Stelle des Schirms; ein Block sind 4 Einheiten. */
    private static void knopfEbene(ClientGameTestContext context, String version, double[]... fuesse) {
        int breite = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
        int hoehe = context.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
        double[] mitte = mitte(context);
        String[] ids = {"stadt", "dorf", "voll"};
        StringBuilder nadeln = new StringBuilder();
        for (int i = 0; i < fuesse.length; i++) {
            nadeln.append(i == 0 ? "" : ",").append("{\"type\":\"pin\",\"id\":\"%s\",\"at\":[%s,%s],\"name\":\"%s\"}".formatted(ids[i],
                    (mitte[0] + fuesse[i][0] - breite / 2.0) / 4, (mitte[1] + fuesse[i][1] - hoehe / 2.0) / 4, ids[i]));
        }
        context.runOnClient(mc -> {
            Ebenen.INSTANZ.empfange(JsonParser.parseString("""
                    {"v":1,"typ":"ebenen","jetzt":1,"ebenen":[{"id":"test:knopf","visible":true,"version":"%s"}]}""".formatted(version))
                    .getAsJsonObject());
            Ebenen.INSTANZ.teil(Ebenen.Teil.lies("""
                    {"v":1,"typ":"ebene","jetzt":1,"id":"test:knopf","version":"%s","teil":1,"teile":1,"objects":[%s]}"""
                    .formatted(version, nadeln)));
        });
        context.waitTick();
    }

    /**
     * Fährt wie die Hand auf die Nadel mit dem Fuss bei {@code fuss}, bis der Mod ihre Tafel fragt, und antwortet wie das
     * Plugin; dann steht die Tafel mit ihren Knöpfen. Wo das Schild liegt, sagt die Karte selbst.
     */
    private static double[] oeffne(ClientGameTestContext context, TestInput maus, int k, double[] fuss, Tafeln.Ziel ziel,
            List<Tafeln.Ziel> fragen, String antwort) {
        double[] auf = null;
        for (int dy = 0; dy <= 24 && auf == null; dy += 2) {
            double y = fuss[1] - dy;
            if (ziel.equals(context.computeOnClient(mc -> ((Karte) mc.gui.screen()).zielBei(fuss[0], y)))) {
                auf = new double[] {fuss[0], y};
            }
        }
        if (auf == null) {
            throw new AssertionError("Keine Nadel „" + ziel.id() + "“ über " + fuss[0] + "," + fuss[1]);
        }
        // Stand der Zeiger schon auf ihr, als die Ebene kam, ging die Frage schon hinaus; darum jede Frage nach diesem Ziel.
        fahre(context, maus, k, auf[0], auf[1]);
        for (int i = 0; i < 40 && !fragen.contains(ziel); i++) {
            context.waitTick();
        }
        if (!fragen.contains(ziel)) {
            double[] ort = auf;
            throw new AssertionError("Keine Frage nach " + ziel + ": " + context.computeOnClient(mc -> {
                Karte karte = (Karte) mc.gui.screen();
                double x = mc.mouseHandler.xpos() / mc.getWindow().getGuiScale(), y = mc.mouseHandler.ypos() / mc.getWindow().getGuiScale();
                return "GS " + mc.getWindow().getGuiScale() + ", Fenster " + mc.getWindow().getWidth() + " × " + mc.getWindow().getHeight()
                        + ", Zeiger " + x + "," + y + " statt " + ort[0] + "," + ort[1] + ", Ziel dort " + karte.zielBei(x, y) + ", Tafel "
                        + karte.tafelOffen() + ", Menü " + Arrays.toString(karte.ziel()) + ", Knopf " + karte.getChildAt(x, y).isPresent()
                        + ", Fragen " + fragen;
            }));
        }
        antworte(context, antwort);
        context.waitFor(mc -> !((Karte) mc.gui.screen()).knopfStuecke().isEmpty(), 20);
        return auf;
    }

    /**
     * Ein schneller Schwung des Zeigers in die offene Tafel, 4 Einheiten in ihre linke obere Ecke, in einem Frame wie eine
     * Hand, die zielt: So geht sie auch auf einem langsamen Runner nicht zu, weil der Zeiger zu lange über keinem Ziel steht.
     */
    private static void rein(ClientGameTestContext context, TestInput maus, int k) {
        int[] t = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).tafelKasten());
        maus.setCursorPos((t[0] + 4) * k, (t[1] + 4) * k);
        context.waitTick();
    }

    private static void antworte(ClientGameTestContext context, String antwort) {
        context.runOnClient(mc -> Tafeln.INSTANZ.antwort(Tafeln.Antwort.lies(antwort)));
        context.waitTicks(2);
    }

    /** Der Zustand des Knopfs {@code id} in der offenen Tafel, wie gezeichnet, oder null ohne ihn. */
    private static Boolean knopfAn(ClientGameTestContext context, String id) {
        return context.computeOnClient(mc -> ((Karte) mc.gui.screen()).knopfStuecke().stream().filter(s -> s.knopf().id().equals(id))
                .map(s -> s.knopf().an()).findFirst().orElse(null));
    }

    private static double[] mitteDes(ClientGameTestContext context, String id) {
        int[] b = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).knopfKasten(id));
        if (b == null) {
            throw new AssertionError("Kein Knopf " + id + " in der Tafel");
        }
        return new double[] {b[0] + b[2] / 2.0, b[1] + b[3] / 2.0};
    }

    /**
     * Knöpfe in der Tafel (#294 Teil 6), mit Klicks wie von Hand. Das Plugin spielt der Test: Er merkt jede Frage und
     * jeden Klick und antwortet wie das Plugin. Siehe docs/ebenen.md, „Knöpfe“.
     */
    private static void knoepfe(ClientGameTestContext context, TestInput maus, int k) {
        List<Tafeln.Ziel> fragen = new CopyOnWriteArrayList<>();
        List<String> klicks = new CopyOnWriteArrayList<>();
        Tafeln.fragen = fragen::add;
        Tafeln.druecken = (z, knopf) -> klicks.add(z.id() + "@" + z.version() + " " + knopf);
        try {
            knoepfeSchritte(context, maus, k, fragen, klicks);
        } finally {
            Tafeln.fragen = Kanal::frageTafel;
            Tafeln.druecken = Kanal::drueckeKnopf;
            context.runOnClient(mc -> {
                Ebenen.INSTANZ.leeren();
                Tafeln.INSTANZ.leeren();
            });
        }
    }

    private static void knoepfeSchritte(ClientGameTestContext context, TestInput maus, int k, List<Tafeln.Ziel> fragen, List<String> klicks) {
        int breite = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
        int hoehe = context.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
        double cx = breite / 2.0, cy = hoehe / 2.0;
        // Links oben und links unten, damit die Tafeln rechts davon aufgehen; die volle rechts unten.
        double[] stadt = {cx - 150, cy - 70}, dorf = {cx - 150, cy + 40}, voll = {cx + 40, cy + 60};
        for (double[] o : new double[][] {stadt, dorf, voll}) {
            boolean frei = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).marken().stream()
                    .noneMatch(m -> Math.abs(m.x() - o[0]) < 16 && Math.abs(m.y() - o[1]) < 16));
            if (!frei) {
                throw new AssertionError("Eine Marke liegt bei " + o[0] + "," + o[1] + "; der Test braucht dort freie Karte");
            }
        }
        knopfEbene(context, "1", stadt, dorf, voll);
        int punkte = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size());
        Tafeln.Ziel ziel = new Tafeln.Ziel("test:knopf", "1", "stadt");
        String schiff = "beispiel:schiff", zug = "beispiel:zug";

        // Die Tafel mit zwei Knöpfen unter dem Inhalt: Schiff aus, Zug an.
        double[] aufStadt = oeffne(context, maus, k, stadt, ziel, fragen, stadtTafel("1", false, true));
        if (!Boolean.FALSE.equals(knopfAn(context, schiff)) || !Boolean.TRUE.equals(knopfAn(context, zug))) {
            throw new AssertionError("Tafel der Stadt: Schiff " + knopfAn(context, schiff) + ", Zug " + knopfAn(context, zug));
        }
        context.takeScreenshot(TestScreenshotOptions.of("knoepfe").disableCounterPrefix());

        // Ein Klick auf „Schiff“: genau ein knopf mit Objekt, version und Knopf; danach ist er gesperrt, ein Doppelklick schickt nichts.
        rein(context, maus, k);
        double[] s = mitteDes(context, schiff);
        spielerKlick(context, maus, k, s[0], s[1]);
        if (!klicks.equals(List.of("stadt@1 " + schiff)) || !context.computeOnClient(mc -> Tafeln.INSTANZ.gesperrt(ziel, schiff))) {
            throw new AssertionError("Klick auf „Schiff“: " + klicks + ", gesperrt "
                    + context.computeOnClient(mc -> Tafeln.INSTANZ.gesperrt(ziel, schiff)));
        }
        doppelklick(maus);
        context.waitTicks(2);
        if (klicks.size() != 1) {
            throw new AssertionError("Doppelklick auf den gesperrten Knopf: " + klicks);
        }
        // Nach dem Ereignis schickt das Plugin die Tafel, hier noch mit dem alten Zustand: gesperrt, der Mod fragt neu.
        int vorher = fragen.size();
        antworte(context, stadtTafel("1", false, true));
        context.waitFor(mc -> fragen.size() > vorher, 40);
        if (!context.computeOnClient(mc -> Tafeln.INSTANZ.gesperrt(ziel, schiff))) {
            throw new AssertionError("Die Tafel mit dem alten Zustand gab den Knopf frei");
        }
        // Die Antwort auf die Nachfrage mit dem neuen Zustand: das Quadrat gefüllt, der Knopf frei.
        antworte(context, stadtTafel("1", true, true));
        if (!Boolean.TRUE.equals(knopfAn(context, schiff)) || context.computeOnClient(mc -> Tafeln.INSTANZ.gesperrt(ziel, schiff))) {
            throw new AssertionError("Neuer Zustand: Schiff " + knopfAn(context, schiff) + ", gesperrt "
                    + context.computeOnClient(mc -> Tafeln.INSTANZ.gesperrt(ziel, schiff)));
        }
        context.takeScreenshot(TestScreenshotOptions.of("knoepfe-an").disableCounterPrefix());

        // Ohne Antwort: Klick auf „Zug“. Der Mod fragt nach 0,3, 0,8, 1,5 und 3 s, nach 3,5 s ist der Knopf frei, mit Hinweis.
        double[] z = mitteDes(context, zug);
        int vorZug = fragen.size();
        spielerKlick(context, maus, k, z[0], z[1]);
        long seit = Util.getMillis();
        context.waitFor(mc -> !Tafeln.INSTANZ.gesperrt(ziel, zug), 400);
        long dauer = Util.getMillis() - seit;
        String hinweis = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).hinweis() == null ? null
                : ((Karte) mc.gui.screen()).hinweis().getString());
        String erwartet = context.computeOnClient(mc -> Component.translatable("heroicmap.tafel.keine_antwort").getString());
        if (klicks.size() != 2 || fragen.size() - vorZug != Tafeln.NACHFRAGEN.length || dauer < 3000 || !erwartet.equals(hinweis)
                || !Boolean.TRUE.equals(knopfAn(context, zug))) {
            throw new AssertionError("Ohne Antwort: " + klicks + ", " + (fragen.size() - vorZug) + " Nachfragen, frei nach " + dauer
                    + " ms, Hinweis „" + hinweis + "“, Zug " + knopfAn(context, zug));
        }

        // Kein Klick in die Tafel wirkt auf die Karte: kein Wegpunkt, nichts angeheftet, kein Menü.
        if (context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size()) != punkte
                || context.computeOnClient(mc -> Wegpunkte.INSTANZ.nadelAngeheftet("test:knopf", "stadt"))
                || context.computeOnClient(mc -> ((Karte) mc.gui.screen()).ziel()) != null) {
            throw new AssertionError("Ein Klick auf einen Knopf wirkte auf die Karte");
        }

        // Wieder aufgehen: Eine Tafel mit Knöpfen fragt der Mod neu und zeigt bis zur Antwort die gemerkte; ein Zustand von
        // anderswo, etwa von der Webkarte, steht danach.
        tafelZu(context, maus, k);
        int vorAuf = fragen.size();
        fahre(context, maus, k, aufStadt[0], aufStadt[1]);
        for (int i = 0; i < 40 && !(fragen.size() > vorAuf && context.computeOnClient(mc -> ((Karte) mc.gui.screen()).tafelOffen())); i++) {
            context.waitTick();
        }
        if (fragen.size() <= vorAuf || !fragen.getLast().equals(ziel) || !Boolean.TRUE.equals(knopfAn(context, schiff))) {
            throw new AssertionError("Wieder aufgegangen: gefragt " + fragen.subList(vorAuf, fragen.size()) + ", Schiff " + knopfAn(context, schiff));
        }
        antworte(context, stadtTafel("1", false, true));
        if (!Boolean.FALSE.equals(knopfAn(context, schiff))) {
            throw new AssertionError("Der Zustand von anderswo steht nicht: Schiff " + knopfAn(context, schiff));
        }
        // Rechts auf einen Knopf: kein knopf, kein Menü.
        rein(context, maus, k);
        double[] rechts = mitteDes(context, schiff);
        fahre(context, maus, k, rechts[0], rechts[1]);
        maus.pressMouse(RECHTS);
        context.waitTicks(2);
        if (klicks.size() != 2 || context.computeOnClient(mc -> ((Karte) mc.gui.screen()).ziel()) != null) {
            throw new AssertionError("Rechtsklick auf einen Knopf: " + klicks);
        }

        // Eine neue version der Ebene, ehe die Tafel zugeht: Ein Klick im selben Augenblick schickt nichts (Review),
        // und nach dem Zugehen auch einer mit der Hand nicht.
        rein(context, maus, k);
        double[] alt = mitteDes(context, schiff);
        int[] kasten = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).tafelKasten());
        context.runOnClient(mc -> {
            Ebenen.INSTANZ.empfange(JsonParser.parseString("""
                    {"v":1,"typ":"ebenen","jetzt":1,"ebenen":[{"id":"test:knopf","visible":true,"version":"2"}]}""").getAsJsonObject());
            Ebenen.INSTANZ.teil(Ebenen.Teil.lies("""
                    {"v":1,"typ":"ebene","jetzt":1,"id":"test:knopf","version":"2","teil":1,"teile":1,"objects":[]}"""));
            mc.gui.screen().mouseClicked(new MouseButtonEvent(alt[0], alt[1], new MouseButtonInfo(LINKS, 0)), false);
        });
        context.waitTicks(2);
        if (kasten == null || klicks.size() != 2) {
            throw new AssertionError("Klick auf die Tafel einer alten version: Tafel " + Arrays.toString(kasten) + ", " + klicks);
        }
        if (context.computeOnClient(mc -> ((Karte) mc.gui.screen()).tafelOffen())) {
            throw new AssertionError("Die Tafel der alten version blieb offen");
        }
        spielerKlick(context, maus, k, alt[0], alt[1]);
        if (klicks.size() != 2) {
            throw new AssertionError("Klick mit der Hand nach der neuen version: " + klicks);
        }

        // Ein Objekt ohne Tafel mit einem Knopf: eine Tafel nur mit dem Knopf.
        knopfEbene(context, "3", stadt, dorf, voll);
        Tafeln.Ziel dorfZiel = new Tafeln.Ziel("test:knopf", "3", "dorf");
        oeffne(context, maus, k, dorf, dorfZiel, fragen, """
                {"v":1,"typ":"tafel","jetzt":1,"ebene":"test:knopf","version":"3","id":"dorf","panel":{"blocks":[
                  {"type":"button","id":"beispiel:schiff","label":{"de":"Schiff","en":"Ship"},"on":false}]}}""");
        int[] nurTafel = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).tafelKasten());
        int[] nurKnopf = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).knopfKasten(schiff));
        if (nurTafel == null || nurKnopf == null || nurTafel[3] > nurKnopf[3] + 2 * Tafel.INNEN + 2) {
            throw new AssertionError("Tafel nur mit dem Knopf: Tafel " + Arrays.toString(nurTafel) + ", Knopf " + Arrays.toString(nurKnopf));
        }
        tafelZu(context, maus, k);

        // Eine volle Tafel, 64 Bausteine, und dahinter 5 Knöpfe: Die ersten 4 stehen darunter, gescrollt ist der letzte zu sehen.
        StringBuilder bloecke = new StringBuilder();
        for (int i = 0; i < Tafel.MAX_BAUSTEINE; i++) {
            bloecke.append("{\"type\":\"lines\",\"lines\":[\"Zeile ").append(i).append("\"]},");
        }
        for (int i = 0; i < 5; i++) {
            bloecke.append(i == 0 ? "" : ",").append("{\"type\":\"button\",\"id\":\"beispiel:k").append(i)
                    .append("\",\"label\":{\"de\":\"Knopf ").append(i).append("\"},\"on\":false}");
        }
        oeffne(context, maus, k, voll, new Tafeln.Ziel("test:knopf", "3", "voll"), fragen,
                "{\"v\":1,\"typ\":\"tafel\",\"jetzt\":1,\"ebene\":\"test:knopf\",\"version\":\"3\",\"id\":\"voll\",\"panel\":{\"blocks\":["
                + bloecke + "]}}");
        List<String> vier = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).knopfStuecke().stream().map(x -> x.knopf().id()).toList());
        if (!vier.equals(List.of("beispiel:k0", "beispiel:k1", "beispiel:k2", "beispiel:k3"))) {
            throw new AssertionError("Volle Tafel: Knöpfe " + vier);
        }
        int[] tafel = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).tafelKasten());
        rein(context, maus, k);
        fahre(context, maus, k, tafel[0] + tafel[2] / 2.0, tafel[1] + tafel[3] / 2.0);
        maus.scroll(-200);
        context.waitTicks(2);
        int[] letzter = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).knopfKasten("beispiel:k3"));
        if (letzter == null || letzter[1] < tafel[1] || letzter[1] + letzter[3] > tafel[1] + tafel[3]) {
            throw new AssertionError("Volle Tafel gescrollt: letzter Knopf " + Arrays.toString(letzter) + ", Tafel " + Arrays.toString(tafel));
        }
        // Gescrollt trifft ein Klick den Knopf, der dort zu sehen ist.
        spielerKlick(context, maus, k, letzter[0] + letzter[2] / 2.0, letzter[1] + letzter[3] / 2.0);
        if (!klicks.getLast().equals("voll@3 beispiel:k3")) {
            throw new AssertionError("Klick auf den letzten Knopf der gescrollten Tafel: " + klicks);
        }
        // Review von #147: Blendet der Spieler die Ebene aus, während der Knopf wartet, fällt er still weg, ohne
        // Nachfrage und ohne „Noch keine Antwort“; das Plugin antwortet darauf ohnehin nicht.
        Tafeln.Ziel vollZiel = new Tafeln.Ziel("test:knopf", "3", "voll");
        context.runOnClient(mc -> Ebenen.INSTANZ.setze("test:knopf", false));
        int nachAus = fragen.size();
        long bis = Util.getMillis() + Tafeln.GESPERRT_MS + 500;
        while (Util.getMillis() < bis) {
            context.waitTick();
        }
        String danach = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).hinweis() == null ? null
                : ((Karte) mc.gui.screen()).hinweis().getString());
        boolean wartet = context.computeOnClient(mc -> Tafeln.INSTANZ.gesperrt(vollZiel, "beispiel:k3"));
        context.runOnClient(mc -> Ebenen.INSTANZ.setze("test:knopf", true));
        if (fragen.size() != nachAus || wartet || erwartet.equals(danach)) {
            throw new AssertionError("Ebene ausgeblendet, während der Knopf wartet: " + (fragen.size() - nachAus) + " Nachfragen, wartet "
                    + wartet + ", Hinweis „" + danach + "“");
        }
        tafelZu(context, maus, k);
    }

    /**
     * Bei GUI-Massstab 3 und 4 steht die Tafel mit Knöpfen ganz auf dem Schirm, jeder Knopf in ihr, mit vollem Label. Die
     * Tafel hat ihren eigenen Massstab (0020); davon bleiben die Bilder. Siehe docs/ebenen.md, „Knöpfe“.
     */
    private static void knoepfeGross(ClientGameTestContext context, TestInput maus) {
        int[] vorher = context.computeOnClient(mc -> new int[] {mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        List<Tafeln.Ziel> fragen = new CopyOnWriteArrayList<>();
        Tafeln.fragen = fragen::add;
        String fehler = null;
        try {
            for (int gs : new int[] {3, 4}) {
                fragen.clear();
                context.getInput().resizeWindow(1280, 240 * gs);
                context.runOnClient(mc -> {
                    mc.options.guiScale().set(gs);
                    mc.resizeGui();
                    mc.gui.setScreen(new Karte(Satz.lies(Bilder.testsatz())));
                });
                context.waitTicks(5);
                int breite = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
                int hoehe = context.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
                double[] stadt = {breite / 2.0 - 80, hoehe / 2.0 - 50};
                knopfEbene(context, "1", stadt);
                oeffne(context, maus, gs, stadt, new Tafeln.Ziel("test:knopf", "1", "stadt"), fragen, stadtTafel("1", true, false));
                context.takeScreenshot(TestScreenshotOptions.of("knoepfe-gs" + gs).disableCounterPrefix());
                String f = context.computeOnClient(mc -> {
                    Karte karte = (Karte) mc.gui.screen();
                    int[] t = karte.tafelKasten();
                    if (t[0] < 0 || t[1] < 0 || t[0] + t[2] > breite || t[1] + t[3] > hoehe) {
                        return "Tafel " + Arrays.toString(t) + " nicht ganz auf dem Schirm " + breite + " × " + hoehe;
                    }
                    for (Tafel.Knopfstueck s : karte.knopfStuecke()) {
                        int[] b = karte.knopfKasten(s.knopf().id());
                        if (b[0] < t[0] || b[1] < t[1] || b[0] + b[2] > t[0] + t[2] || b[1] + b[3] > t[1] + t[3]) {
                            return s.knopf().id() + " " + Arrays.toString(b) + " nicht in der Tafel " + Arrays.toString(t);
                        }
                        if (!s.label().equals(s.knopf().label(mc.getLanguageManager().getSelected().startsWith("de")))) {
                            return "Label gekürzt: „" + s.label() + "“";
                        }
                    }
                    return karte.knopfStuecke().size() == 2 ? null : karte.knopfStuecke().size() + " Knöpfe";
                });
                fehler = fehler != null ? fehler : f == null ? null : "GS " + gs + ": " + f;
                context.runOnClient(mc -> {
                    Ebenen.INSTANZ.leeren();
                    Tafeln.INSTANZ.leeren();
                });
            }
        } finally {
            Tafeln.fragen = Kanal::frageTafel;
            context.getInput().resizeWindow(vorher[0], vorher[1]);
            context.runOnClient(mc -> {
                mc.gui.setScreen(null);
                mc.options.guiScale().set(vorher[2]);
                mc.resizeGui();
            });
            context.waitTicks(2);
        }
        if (fehler != null) {
            throw new AssertionError("Knöpfe: " + fehler);
        }
    }

    /** Die Antwort des Plugins auf die Frage nach der Tafel der grossen Region unter den Wegpunkten. */
    private static final String LAND = """
            {"v":1,"typ":"tafel","ebene":"test:land","version":"1","id":"land","panel":{"blocks":[
              {"type":"title","text":"Grosses Land"},
              {"type":"lines","lines":["Eine Region vom Server","unter allen Wegpunkten","wie ein Land auf dem Server","mit einer langen Tafel"]}
            ]}}""";

    /**
     * Eigene Formen auf Wegpunkten wie ein Spieler, über einer grossen Region vom Server mit Tafel, wie auf einem
     * Server mit Ländern: drei Wegpunkte über „Wegpunkt setzen“, auf dem ersten „Linie/Region von hier“, dann
     * Linksklicks auf den dritten, den zweiten und den ersten: eine Region. Zwei weitere, „Punkt
     * von hier“, Linksklick, „Form fertig“: eine Linie. Ein Doppelklick in die Region heftet sie an, nicht
     * die Region vom Server darunter; „Form löschen“ löscht sie. Jeder Klick fährt wie die Hand in
     * Schritten hin, ruht, drückt, hält und lässt los (`spielerKlick`); so geht nach „Linie/Region von hier“ die
     * Tafel der Region auf, wo das Menü war. Siehe docs/wegpunkte.md, „Eigene Formen“.
     */
    private static void form(ClientGameTestContext context, TestInput maus, int k) {
        Tafeln.fragen = z -> true;
        try {
            formSchritte(context, maus, k);
        } finally {
            Tafeln.fragen = Kanal::frageTafel;
            context.runOnClient(mc -> Ebenen.INSTANZ.leeren());
        }
    }

    private static void formSchritte(ClientGameTestContext context, TestInput maus, int k) {
        int breite = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
        int hoehe = context.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
        double mx = breite / 2.0, my = hoehe / 2.0;
        // Der dritte und der fünfte liegen rechts unter dem Menü am ersten und vierten, dort, wo die Tafel der Region aufgeht.
        double[][] orte = {{mx - 90, my - 60}, {mx - 30, my - 60}, {mx - 50, my + 10}, {mx + 30, my - 60}, {mx + 80, my}};
        for (double[] o : orte) {
            boolean frei = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).marken().stream()
                    .noneMatch(m -> Math.abs(m.x() - o[0]) < 10 && Math.abs(m.y() - o[1]) < 10));
            if (!frei) {
                throw new AssertionError("Eine Marke liegt bei " + o[0] + "," + o[1] + "; der Test braucht dort freie Karte");
            }
        }
        // Eine grosse Region vom Server mit id unter allen Wegpunkten, 40 Blöcke um die Mitte; ein Block sind 4 Einheiten.
        double[] mitte = mitte(context);
        long bx = Math.round(mitte[0] / 4), bz = Math.round(mitte[1] / 4);
        context.runOnClient(mc -> {
            Ebenen.INSTANZ.empfange(JsonParser.parseString("""
                    {"v":1,"typ":"ebenen","jetzt":1,"ebenen":[{"id":"test:land","visible":true,"version":"1"}]}""").getAsJsonObject());
            Ebenen.INSTANZ.teil(Ebenen.Teil.lies("""
                    {"v":1,"typ":"ebene","jetzt":1,"id":"test:land","version":"1","teil":1,"teile":1,"objects":[
                      {"type":"region","id":"land","fill":"#40A0602A","polygons":[{"outer":[[%d,%d],[%d,%d],[%d,%d],[%d,%d]]}]}]}"""
                    .formatted(bx - 40, bz - 40, bx + 40, bz - 40, bx + 40, bz + 40, bx - 40, bz + 40)));
        });
        context.waitTick();
        int vorher = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size());
        for (double[] o : orte) {
            eintrag(context, maus, k, o[0], o[1], "heroicmap.karte.wegpunkt");
        }
        List<Wegpunkte.Punkt> neu = context.computeOnClient(mc -> List.copyOf(Wegpunkte.INSTANZ.punkte().subList(vorher, vorher + 5)));
        if (neu.size() != 5) {
            throw new AssertionError("Fünf Wegpunkte gesetzt, da sind " + neu.size());
        }
        int formen = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().size());

        // Region aus drei Punkten: auf dem ersten „Linie/Region von hier“; die Tafel der Region geht auf, wo das Menü war.
        // Dann Linksklicks auf den dritten, rechts unter dem Menü, den zweiten und den ersten.
        Karte.Marke a = marke(context, neu.get(0));
        eintrag(context, maus, k, a.x(), a.y(), "heroicmap.karte.form_von_hier");
        tafelDerRegion(context);
        int[] reihe = {2, 1, 0};
        for (int j = 0; j < reihe.length; j++) {
            Karte.Marke m = marke(context, neu.get(reihe[j]));
            spielerKlick(context, maus, k, m.x(), m.y());
            List<Wegpunkte.Ecke> zug = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).zug());
            if (j < 2 && (zug == null || zug.size() != j + 2)) {
                throw new AssertionError("Linksklick auf einen Wegpunkt fügte ihn nicht an: im Bau " + zug + ", Tafel offen "
                        + context.computeOnClient(mc -> ((Karte) mc.gui.screen()).tafelOffen()));
            }
        }
        Wegpunkte.EigeneForm region = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().size() == formen + 1
                ? Wegpunkte.INSTANZ.eigeneFormen().getLast() : null);
        if (region == null || !region.region() || region.ecken().size() != 3
                || context.computeOnClient(mc -> ((Karte) mc.gui.screen()).zug()) != null) {
            throw new AssertionError("Linksklick auf den ersten Punkt schloss keine Region: " + region);
        }

        // Linie aus zwei Punkten: „Linie/Region von hier“, Linksklick auf den zweiten, dann ein Rechtsklick (mod#149).
        warte250();
        Karte.Marke d = marke(context, neu.get(3));
        eintrag(context, maus, k, d.x(), d.y(), "heroicmap.karte.form_von_hier");
        warte250();
        tafelDerRegion(context);
        Karte.Marke e = marke(context, neu.get(4));
        spielerKlick(context, maus, k, e.x(), e.y());
        rechtsKlick(context, maus, k, mx + 60, my + 40);
        Wegpunkte.EigeneForm linie = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().getLast());
        if (linie.region() || linie.ecken().size() != 2) {
            throw new AssertionError("Rechtsklick mit zwei Punkten gab keine Linie: " + linie);
        }

        // Ein Doppelklick in die Region heftet sie an; „Form löschen“ löscht sie. Erst geht die Tafel der Region vom Server zu,
        // die nach dem Abschliessen am Ort des Rechtsklicks aufging; sonst nähme sie die Klicks.
        tafelZu(context, maus, k);
        warte250();
        double ix = (orte[0][0] + orte[1][0] + orte[2][0]) / 3, iy = (orte[0][1] + orte[1][1] + orte[2][1]) / 3;
        fahre(context, maus, k, ix, iy);
        doppelklick(maus);
        context.waitTicks(2);
        if (!context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().get(formen).angeheftet())
                || context.computeOnClient(mc -> Wegpunkte.INSTANZ.angeheftet("test:land", "land"))) {
            int[] tafelDa = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).tafelKasten());
            int[] menueDa = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).ziel());
            throw new AssertionError("Doppelklick in die eigene Region heftete nicht sie an: eigene "
                    + context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().get(formen).angeheftet()) + ", vom Server "
                    + context.computeOnClient(mc -> Wegpunkte.INSTANZ.angeheftet("test:land", "land")) + "; bei " + ix + "," + iy + " Tafel "
                    + Arrays.toString(tafelDa) + ", Menü " + Arrays.toString(menueDa) + ", im Bau "
                    + context.computeOnClient(mc -> ((Karte) mc.gui.screen()).zug()));
        }
        // Ein Doppelklick von Hand auf einen Wegpunkt in der Region heftet ihn an, die Karte bleibt stehen (mod#75).
        double[] stand = mitte(context);
        Karte.Marke b = marke(context, neu.get(1));
        warte250();
        fahre(context, maus, k, b.x(), b.y());
        // Halten und Pause nach der Uhr, nicht nach Ticks; siehe doppelklick.
        for (int i = 0; i < 2; i++) {
            maus.holdMouse(LINKS);
            schlafe(DOPPELKLICK_MS / 2);
            maus.releaseMouse(LINKS);
            schlafe(DOPPELKLICK_MS / 2);
        }
        warte250();
        context.waitTicks(2);
        double[] danach = mitte(context);
        if (!angeheftet(context, neu.get(1)) || Math.abs(danach[0] - stand[0]) > 1e-6 || Math.abs(danach[1] - stand[1]) > 1e-6) {
            throw new AssertionError("Doppelklick von Hand auf den Wegpunkt: angeheftet " + angeheftet(context, neu.get(1)) + ", Mitte "
                    + Arrays.toString(stand) + " -> " + Arrays.toString(danach));
        }
        // Ein Rechtsklick auf die Fläche, ein Drittel vom Griff zur ersten Ecke, zeigt nur das allgemeine Menü (mod#128);
        // auf dem Griff in der Mitte, wie gezeichnet, das der Form mit „Form löschen“.
        double[] griff = griff(context, formen), erste = ecke(context, formen, 0);
        double fx = (2 * griff[0] + erste[0]) / 3, fy = (2 * griff[1] + erste[1]) / 3;
        List<String> flaeche = eintraegeBei(context, maus, k, fx, fy);
        String loeschen = context.computeOnClient(mc -> Component.translatable("heroicmap.karte.form_loeschen").getString());
        if (flaeche.contains(loeschen) || !flaeche.contains(context.computeOnClient(mc -> Component.translatable("heroicmap.karte.wegpunkt").getString()))) {
            throw new AssertionError("Rechtsklick auf die Fläche der Region zeigt nicht nur das allgemeine Menü: " + flaeche);
        }
        // Die Tafel der Region vom Server, die beim Rechtsklick auf die Fläche aufging, läge sonst über dem Griff.
        tafelZu(context, maus, k);
        eintrag(context, maus, k, griff[0], griff[1], "heroicmap.karte.form_loeschen");
        if (context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().size()) != formen + 1) {
            throw new AssertionError("„Form löschen“ am Griff löschte die Region nicht");
        }
        warte250();
    }

    /**
     * Punkte im Tempo eines Spielers (mod#112): „Linie/Region von hier“ auf dem ersten Wegpunkt, dann Linksklicks auf drei
     * weitere, je 150 bis 300 ms nach dem vorigen Drücken, der erste davon nah am vorigen Punkt. Das Spiel meldet einen
     * Doppelklick nach Zeit und Taste, nicht nach dem Ort; jeder Klick muss trotzdem anfügen. Siehe docs/wegpunkte.md,
     * „Eigene Formen“.
     */
    private static void formImTempo(ClientGameTestContext context, TestInput maus, int k) {
        int breite = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
        int hoehe = context.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
        double mx = breite / 2.0, my = hoehe / 2.0;
        // Der zweite 12 Einheiten neben dem ersten, die Marken berühren sich fast.
        double[][] orte = {{mx - 110, my + 60}, {mx - 98, my + 60}, {mx - 70, my + 80}, {mx - 40, my + 60}};
        for (double[] o : orte) {
            boolean frei = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).marken().stream()
                    .noneMatch(m -> Math.abs(m.x() - o[0]) < 10 && Math.abs(m.y() - o[1]) < 10));
            if (!frei) {
                throw new AssertionError("Eine Marke liegt bei " + o[0] + "," + o[1] + "; der Test braucht dort freie Karte");
            }
        }
        int vorher = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size());
        for (double[] o : orte) {
            eintrag(context, maus, k, o[0], o[1], "heroicmap.karte.wegpunkt");
        }
        List<Wegpunkte.Punkt> neu = context.computeOnClient(mc -> List.copyOf(Wegpunkte.INSTANZ.punkte().subList(vorher, vorher + 4)));
        try {
            List<Karte.Marke> marken = neu.stream().map(p -> marke(context, p)).toList();
            long druck = eintrag(context, maus, k, marken.get(0).x(), marken.get(0).y(), "heroicmap.karte.form_von_hier");
            // Der erste nach dem Klick auf den Eintrag, dem zwei Ticks folgen; die nächsten schneller.
            long[] pausen = {300, 220, 200};
            for (int i = 1; i < 4; i++) {
                Karte.Marke m = marken.get(i);
                long jetzt = tempoKlick(context, maus, k, m.x(), m.y(), druck, pausen[i - 1]);
                List<Wegpunkte.Ecke> zug = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).zug());
                if (zug == null || zug.size() != i + 1) {
                    throw new AssertionError("Linksklick " + (jetzt - druck) + " ms nach dem vorigen fügte Punkt " + (i + 1)
                            + " nicht an: im Bau " + zug + ", angeheftet " + neu.stream().filter(p -> angeheftet(context, p)).count());
                }
                druck = jetzt;
            }
        } finally {
            warte250();
            maus.pressKey(InputConstants.KEY_ESCAPE);
            context.waitTick();
            context.runOnClient(mc -> neu.forEach(Wegpunkte.INSTANZ::loesche));
            context.waitTicks(2);
        }
    }

    /**
     * Der Weg des Users (mod#112): „Linie/Region von hier“ auf leerer Karte, dann drei Linksklicks auf leere Stellen im
     * Tempo eines Spielers, 300, 220 und 200 ms nach dem vorigen Drücken, und einer auf die erste Ecke: eine Region mit
     * vier eigenen Ecken, ohne neuen Wegpunkt. Siehe docs/wegpunkte.md, „Eigene Formen“.
     */
    private static void formAufLeererKarte(ClientGameTestContext context, TestInput maus, int k) {
        int breite = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
        int hoehe = context.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
        double mx = breite / 2.0, my = hoehe / 2.0;
        double[][] orte = {{mx - 120, my + 40}, {mx - 60, my + 40}, {mx - 60, my + 90}, {mx - 120, my + 90}};
        frei(context, orte);
        int punkte = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size());
        int formen = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().size());
        try {
            long druck = eintrag(context, maus, k, orte[0][0], orte[0][1], "heroicmap.karte.form_von_hier");
            long[] pausen = {300, 220, 200};
            for (int i = 1; i < 4; i++) {
                long jetzt = tempoKlick(context, maus, k, orte[i][0], orte[i][1], druck, pausen[i - 1]);
                List<Wegpunkte.Ecke> zug = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).zug());
                if (zug == null || zug.size() != i + 1) {
                    throw new AssertionError("Linksklick auf leere Karte " + (jetzt - druck) + " ms nach dem vorigen setzte Ecke " + (i + 1)
                            + " nicht: im Bau " + zug);
                }
                druck = jetzt;
            }
            // Ein Klick nah an der ersten Ecke schliesst die Region.
            tempoKlick(context, maus, k, orte[0][0] + 1, orte[0][1] + 1, druck, 300);
            context.waitTicks(2);
            List<Wegpunkte.EigeneForm> alle = context.computeOnClient(mc -> List.copyOf(Wegpunkte.INSTANZ.eigeneFormen()));
            if (alle.size() != formen + 1 || !alle.getLast().region() || alle.getLast().ecken().size() != 4
                    || context.computeOnClient(mc -> ((Karte) mc.gui.screen()).zug()) != null) {
                throw new AssertionError("Vier Klicks auf leere Karte gaben keine Region mit vier Ecken: " + (alle.size() > formen ? alle.getLast() : null));
            }
            if (context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size()) != punkte) {
                throw new AssertionError("Ecken auf leerer Karte legten Wegpunkte an");
            }
        } finally {
            warte250();
            // Escape ohne Form im Bau schlösse die Karte.
            if (context.computeOnClient(mc -> ((Karte) mc.gui.screen()).zug()) != null) {
                maus.pressKey(InputConstants.KEY_ESCAPE);
                context.waitTick();
            }
            context.runOnClient(mc -> List.copyOf(Wegpunkte.INSTANZ.eigeneFormen()).subList(formen, Wegpunkte.INSTANZ.eigeneFormen().size())
                    .forEach(Wegpunkte.INSTANZ::loesche));
            context.waitTicks(2);
        }
    }

    /**
     * Ecken einer eigenen Form bearbeiten (mod#112): Zwei Ecken einen Block, also 4 Einheiten, auseinander; ein Rechtsklick
     * zwischen ihnen, eine Einheit von der zweiten, trifft die zweite, nicht die erste, und „Eckpunkt löschen“ nimmt sie heraus. „Eckpunkt
     * hinzufügen“ an der ersten und ein Linksklick setzen die neue Ecke zwischen die erste und die nächste. 700 ms gehalten
     * hängt eine Ecke an der Maus und liegt nach dem Loslassen dort. Wird die Form im Bau gelöscht, speichert „Form fertig“
     * nichts und sagt es unten links. Eine Linie mit zwei Ecken fällt beim Löschen einer weg.
     * Siehe docs/wegpunkte.md, „Eigene Formen“.
     */
    private static void eckenBearbeiten(ClientGameTestContext context, TestInput maus, int k) {
        int formen = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().size());
        // Links unter der Mitte, wo sonst nichts liegt; ein Block sind 4 Einheiten.
        int[] mitte = context.computeOnClient(mc -> {
            double[] m = ((Karte) mc.gui.screen()).blickMitte();
            return new int[] {(int) Math.floor(m[0] / 4), (int) Math.floor(m[1] / 4)};
        });
        int bx = mitte[0] - 30, bz = mitte[1] + 12;
        Wegpunkte.Ecke a = new Wegpunkte.Ecke(bx, bz), b = new Wegpunkte.Ecke(bx + 1, bz), c = new Wegpunkte.Ecke(bx + 8, bz + 6);
        try {
            context.runOnClient(mc -> {
                String dimension = mc.level.dimension().identifier().toString();
                Wegpunkte.INSTANZ.setzeForm(dimension, List.of(a, b, c));
                Wegpunkte.INSTANZ.setzeForm(dimension, List.of(new Wegpunkte.Ecke(bx, bz + 14), new Wegpunkte.Ecke(bx + 10, bz + 14)));
            });
            context.waitTicks(2);
            frei(context, new double[][] {ecke(context, formen, 0), ecke(context, formen, 2)});

            // Zwischen beiden, eine Einheit von der zweiten und drei von der ersten, beide in Reichweite: Die nähere zählt.
            double[] erste0 = ecke(context, formen, 0), zweite = ecke(context, formen, 1);
            if (Math.abs(zweite[0] - erste0[0] - 4) > 0.5 || Math.abs(zweite[1] - erste0[1]) > 0.5) {
                throw new AssertionError("Die Ecken liegen nicht 4 Einheiten nebeneinander: " + Arrays.toString(erste0) + ", " + Arrays.toString(zweite));
            }
            eintrag(context, maus, k, zweite[0] - 1, zweite[1], "heroicmap.karte.ecke_loeschen");
            context.waitTicks(2);
            List<Wegpunkte.Ecke> nachLoeschen = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().get(formen).ecken());
            if (!nachLoeschen.equals(List.of(a, c))) {
                throw new AssertionError("„Eckpunkt löschen“ eine Einheit neben der zweiten Ecke: " + nachLoeschen + " statt " + List.of(a, c));
            }

            // An der ersten einfügen: Die neue kommt zwischen sie und die nächste, also an die zweite Stelle.
            double[] erste = ecke(context, formen, 0);
            eintrag(context, maus, k, erste[0], erste[1], "heroicmap.karte.ecke_hinzu");
            double[] neuOrt = {erste[0] + 12, erste[1] + 40};
            frei(context, new double[][] {neuOrt});
            tempoKlick(context, maus, k, neuOrt[0], neuOrt[1], 0, 0);
            rechtsKlick(context, maus, k, neuOrt[0] + 30, neuOrt[1]);
            context.waitTicks(2);
            List<Wegpunkte.Ecke> nachEinfuegen = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().get(formen).ecken());
            if (nachEinfuegen.size() != 3 || !nachEinfuegen.get(0).equals(a) || !nachEinfuegen.get(2).equals(c)) {
                throw new AssertionError("„Eckpunkt hinzufügen“ an der ersten Ecke: " + nachEinfuegen + ", die neue gehört zwischen " + a + " und " + c);
            }

            // Die letzte Ecke 700 ms halten, 10 Einheiten nach rechts ziehen, loslassen: 2 bis 3 Blöcke nach Osten.
            warte250();
            double[] letzte = ecke(context, formen, 2);
            maus.setCursorPos(letzte[0] * k, letzte[1] * k);
            context.waitTick();
            long druck = Util.getMillis();
            maus.holdMouse(LINKS);
            zittereBis(context, maus, letzte[0] * k, letzte[1] * k, druck + 700);
            if (context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eckeHaengt()) == null) {
                maus.releaseMouse(LINKS);
                throw new AssertionError("Nach " + (Util.getMillis() - druck) + " ms gehalten hängt die Ecke nicht an der Maus");
            }
            for (int i = 0; i < 4; i++) {
                maus.moveCursor(2.5 * k, 0);
                context.waitTick();
            }
            maus.releaseMouse(LINKS);
            context.waitTicks(2);
            Wegpunkte.Ecke verschoben = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().get(formen).ecken().get(2));
            if (verschoben.x() - c.x() < 2 || verschoben.x() - c.x() > 3 || verschoben.z() != c.z()) {
                throw new AssertionError("Gehaltene Ecke verschoben nach " + verschoben + " statt 2 bis 3 Blöcke östlich von " + c);
            }

            // Im Bau gelöscht: Der Rechtsklick, der abschliesst, speichert nichts und sagt es, statt die neuen Ecken still zu verwerfen.
            double[] start = ecke(context, formen, 0);
            eintrag(context, maus, k, start[0], start[1], "heroicmap.karte.ecke_hinzu");
            tempoKlick(context, maus, k, neuOrt[0], neuOrt[1] + 20, 0, 0);
            // „Form löschen“ in der Region, an der Mitte ihrer drei Ecken.
            double[] e1 = ecke(context, formen, 1), e2 = ecke(context, formen, 2);
            eintrag(context, maus, k, (start[0] + e1[0] + e2[0]) / 3, (start[1] + e1[1] + e2[1]) / 3, "heroicmap.karte.form_loeschen");
            long fertig = rechtsKlick(context, maus, k, neuOrt[0] + 30, neuOrt[1]);
            context.waitTicks(2);
            String hinweis = context.computeOnClient(mc -> {
                Component h = ((Karte) mc.gui.screen()).hinweis();
                return h == null ? null : h.getString();
            });
            if (!Component.translatable("heroicmap.karte.form_weg").getString().equals(hinweis)
                    || context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().size()) != formen + 1) {
                throw new AssertionError("„Form fertig“ nach „Form löschen“ im Bau: Hinweis „" + hinweis + "“, Formen "
                        + context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().size()) + " statt " + (formen + 1));
            }
            // Ohne Klick und Taste steht er nach 4 s noch und ist nach gut 5 s weg (docs/vollbildkarte.md, „Bedienung“).
            while (Util.getMillis() < fertig + Karte.HINWEIS_MS - 1000) {
                context.waitTick();
            }
            String nach4 = hinweis(context);
            while (Util.getMillis() < fertig + Karte.HINWEIS_MS + 300) {
                context.waitTick();
            }
            if (!nach4.equals(hinweis) || !"null".equals(hinweis(context))) {
                throw new AssertionError("Hinweis nach 4 s „" + nach4 + "“, nach 5,3 s „" + hinweis(context) + "“");
            }

            // Eine Linie mit zwei Ecken fällt beim Löschen einer weg; sie ist jetzt die erste neue Form.
            warte250();
            double[] linie = ecke(context, formen, 0);
            eintrag(context, maus, k, linie[0], linie[1], "heroicmap.karte.ecke_loeschen");
            context.waitTicks(2);
            if (context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().size()) != formen) {
                throw new AssertionError("Die Linie mit zwei Ecken blieb nach „Eckpunkt löschen“");
            }
        } finally {
            warte250();
            context.runOnClient(mc -> List.copyOf(Wegpunkte.INSTANZ.eigeneFormen()).subList(formen, Wegpunkte.INSTANZ.eigeneFormen().size())
                    .forEach(Wegpunkte.INSTANZ::loesche));
            context.waitTicks(2);
        }
    }

    /**
     * Formen live ziehen (mod#128): Eine gehaltene Ecke einer Linie folgt der Maus, und die Linie mit ihr, schon vor dem
     * Loslassen; im Bildschirmfoto liegt die Farbe der Linie mitten auf ihrem neuen Weg, nicht mehr auf dem alten. Am Griff
     * in der Mitte einer Region hängt nach 700 ms die ganze Form an der Maus, der Griff folgt, und nach dem Loslassen liegen
     * alle Ecken um ganze Blöcke verschoben. Escape lässt sie, wo sie war. Siehe docs/wegpunkte.md, „Eigene Formen“.
     */
    private static void formenZiehen(ClientGameTestContext context, TestInput maus, int k) {
        int formen = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().size());
        int[] mitte = context.computeOnClient(mc -> {
            double[] m = ((Karte) mc.gui.screen()).blickMitte();
            return new int[] {(int) Math.floor(m[0] / 4), (int) Math.floor(m[1] / 4)};
        });
        // Rechts unter der Mitte, wo sonst nichts liegt; ein Block sind 4 Einheiten.
        int bx = mitte[0] + 12, bz = mitte[1] + 8;
        List<Wegpunkte.Ecke> linie = List.of(new Wegpunkte.Ecke(bx, bz), new Wegpunkte.Ecke(bx + 10, bz));
        List<Wegpunkte.Ecke> region = List.of(new Wegpunkte.Ecke(bx, bz + 12), new Wegpunkte.Ecke(bx + 6, bz + 12),
                new Wegpunkte.Ecke(bx + 6, bz + 18), new Wegpunkte.Ecke(bx, bz + 18));
        try {
            context.runOnClient(mc -> {
                String dimension = mc.level.dimension().identifier().toString();
                Wegpunkte.INSTANZ.setzeForm(dimension, linie);
                Wegpunkte.INSTANZ.setzeForm(dimension, region);
            });
            context.waitTicks(2);
            double[] a = ecke(context, formen, 0), b = ecke(context, formen, 1);
            frei(context, new double[][] {a, b, griff(context, formen + 1)});

            // Die zweite Ecke der Linie halten und 20 Einheiten nach unten ziehen, ohne loszulassen.
            warte250();
            maus.setCursorPos(b[0] * k, b[1] * k);
            context.waitTick();
            long druck = Util.getMillis();
            maus.holdMouse(LINKS);
            zittereBis(context, maus, b[0] * k, b[1] * k, druck + 700);
            if (context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eckeHaengt()) == null) {
                maus.releaseMouse(LINKS);
                throw new AssertionError("Nach " + (Util.getMillis() - druck) + " ms gehalten hängt die Ecke der Linie nicht an der Maus");
            }
            for (int i = 0; i < 5; i++) {
                maus.moveCursor(0, 4 * k);
                context.waitTick();
            }
            context.waitTicks(2);
            double[] an = ecke(context, formen, 1);
            Path bild = context.takeScreenshot(TestScreenshotOptions.of("form-live").disableCounterPrefix());
            int farbe = context.computeOnClient(mc -> Wegpunkte.FARBEN[Wegpunkte.INSTANZ.eigeneFormen().get(formen).farbe()]);
            if (Math.abs(an[0] - b[0]) > 1 || Math.abs(an[1] - (b[1] + 20)) > 1) {
                maus.releaseMouse(LINKS);
                throw new AssertionError("Die gehaltene Ecke folgt der Maus nicht: " + Arrays.toString(an) + ", die Maus bei "
                        + b[0] + "," + (b[1] + 20));
            }
            // Auf einem Viertel des Wegs, nicht der Hälfte: Dort sitzt der Griff. Alter und neuer Weg liegen dort 5 Einheiten auseinander.
            boolean neuDa = linieDa(bild, k, a[0] + (an[0] - a[0]) / 4, a[1] + (an[1] - a[1]) / 4, farbe);
            boolean altDa = linieDa(bild, k, a[0] + (b[0] - a[0]) / 4, a[1] + (b[1] - a[1]) / 4, farbe);
            if (!neuDa || altDa) {
                maus.releaseMouse(LINKS);
                throw new AssertionError("Die Linie folgt der gehaltenen Ecke nicht vor dem Loslassen: auf dem neuen Weg " + neuDa
                        + ", auf dem alten " + altDa);
            }
            maus.releaseMouse(LINKS);
            context.waitTicks(2);
            Wegpunkte.Ecke zweite = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().get(formen).ecken().get(1));
            if (zweite.x() != bx + 10 || zweite.z() != bz + 5) {
                throw new AssertionError("Nach dem Loslassen liegt die Ecke auf " + zweite + " statt " + new Wegpunkte.Ecke(bx + 10, bz + 5));
            }

            // Am Griff der Region halten und gut 8 Einheiten nach rechts und unten ziehen: Der Griff folgt der Maus, wie das Spiel sie sieht,
            // samt dem Zittern beim Halten; nach dem Loslassen liegt alles um den Weg der Maus in ganzen Blöcken weiter.
            warte250();
            double[] gr = griff(context, formen + 1);
            maus.setCursorPos(gr[0] * k, gr[1] * k);
            context.waitTick();
            druck = Util.getMillis();
            maus.holdMouse(LINKS);
            zittereBis(context, maus, gr[0] * k, gr[1] * k, druck + 700);
            if (context.computeOnClient(mc -> ((Karte) mc.gui.screen()).griffHaengt()) == null) {
                maus.releaseMouse(LINKS);
                throw new AssertionError("Nach " + (Util.getMillis() - druck) + " ms gehalten hängt die Region nicht am Griff");
            }
            for (int i = 0; i < 4; i++) {
                maus.moveCursor(2 * k, 2 * k);
                context.waitTick();
            }
            context.waitTicks(2);
            double[] grAn = griff(context, formen + 1), jetzt = mausImGui(context);
            if (Math.hypot(jetzt[0] - gr[0], jetzt[1] - gr[1]) < 8 || Math.abs(grAn[0] - Math.floor(jetzt[0])) > 1
                    || Math.abs(grAn[1] - Math.floor(jetzt[1])) > 1) {
                maus.releaseMouse(LINKS);
                throw new AssertionError("Der Griff folgt der Maus nicht: " + Arrays.toString(grAn) + ", die Maus bei " + Arrays.toString(jetzt)
                        + ", gedrückt bei " + Arrays.toString(gr));
            }
            maus.releaseMouse(LINKS);
            context.waitTicks(2);
            // Ein Block sind 4 Einheiten.
            int dx = (int) Math.round((jetzt[0] - gr[0]) / 4), dz = (int) Math.round((jetzt[1] - gr[1]) / 4);
            List<Wegpunkte.Ecke> verschoben = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().get(formen + 1).ecken());
            List<Wegpunkte.Ecke> soll = region.stream().map(c -> new Wegpunkte.Ecke(c.x() + dx, c.z() + dz)).toList();
            if (!verschoben.equals(soll)) {
                throw new AssertionError("Am Griff verschoben: " + verschoben + " statt " + soll);
            }

            // Noch einmal am Griff, aber Escape: Die Region bleibt, wo sie war.
            warte250();
            double[] gr2 = griff(context, formen + 1);
            maus.setCursorPos(gr2[0] * k, gr2[1] * k);
            context.waitTick();
            druck = Util.getMillis();
            maus.holdMouse(LINKS);
            zittereBis(context, maus, gr2[0] * k, gr2[1] * k, druck + 700);
            maus.moveCursor(12 * k, 0);
            context.waitTick();
            maus.pressKey(InputConstants.KEY_ESCAPE);
            context.waitTick();
            maus.releaseMouse(LINKS);
            context.waitTicks(2);
            if (!context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().get(formen + 1).ecken()).equals(soll)
                    || !(context.computeOnClient(mc -> mc.gui.screen()) instanceof Karte)) {
                throw new AssertionError("Escape am Griff liess die Region nicht, wo sie war, oder schloss die Karte");
            }
            // Bei noch gehaltener Taste hing sie bis hier im nächsten Frame wieder an der Maus, und das Loslassen liess sie dort.
            if (context.computeOnClient(mc -> ((Karte) mc.gui.screen()).griffHaengt()) != null) {
                throw new AssertionError("Nach Escape und Loslassen hängt die Region noch am Griff");
            }
        } finally {
            warte250();
            context.runOnClient(mc -> List.copyOf(Wegpunkte.INSTANZ.eigeneFormen()).subList(formen, Wegpunkte.INSTANZ.eigeneFormen().size())
                    .forEach(Wegpunkte.INSTANZ::loesche));
            context.waitTicks(2);
        }
    }

    /**
     * Hervorheben unter der Maus (mod#128): Steht die Maus auf einem Wegpunkt, auf einer Ecke oder auf dem Griff einer Form, hat
     * er einen hellen Rand; steht sie 20 Einheiten daneben, nicht. Gezählt werden im Bildschirmfoto die weissen Pixel um die Stelle.
     * Siehe docs/wegpunkte.md, „Bedienung“.
     */
    private static void schweben(ClientGameTestContext context, TestInput maus, int k) {
        int punkte = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size());
        int formen = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().size());
        int[] mitte = context.computeOnClient(mc -> {
            double[] m = ((Karte) mc.gui.screen()).blickMitte();
            return new int[] {(int) Math.floor(m[0] / 4), (int) Math.floor(m[1] / 4)};
        });
        // Links über der Mitte, wo sonst nichts liegt; ein Block sind 4 Einheiten.
        int bx = mitte[0] - 30, bz = mitte[1] - 12;
        try {
            context.runOnClient(mc -> {
                String dimension = mc.level.dimension().identifier().toString();
                Wegpunkte.INSTANZ.setze(dimension, bx, bz);
                Wegpunkte.INSTANZ.setzeForm(dimension, List.of(new Wegpunkte.Ecke(bx + 6, bz), new Wegpunkte.Ecke(bx + 16, bz)));
            });
            context.waitTicks(2);
            Wegpunkte.Punkt neu = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().get(punkte));
            Karte.Marke wp = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).marken().stream()
                    .filter(m -> neu.equals(m.punkt())).findFirst().orElseThrow());
            double[][] ziele = {{wp.x(), wp.y()}, ecke(context, formen, 0), griff(context, formen)};
            String[] namen = {"Wegpunkt", "Ecke", "Griff"};
            for (int i = 0; i < ziele.length; i++) {
                double[] z = ziele[i];
                double[] daneben = {z[0], z[1] - 20};
                frei(context, new double[][] {daneben});
                maus.setCursorPos(z[0] * k, z[1] * k);
                context.waitTicks(2);
                String unter = context.computeOnClient(mc -> {
                    Karte karte = (Karte) mc.gui.screen();
                    return "hervorgehoben " + karte.schwebt() + ", " + karte.schwebeGrund((int) z[0], (int) z[1]) + ", Maus "
                            + mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth() + ","
                            + mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
                });
                int an = weisse(context.takeScreenshot(TestScreenshotOptions.of("schweben-" + i).disableCounterPrefix()), k, z, 6);
                maus.setCursorPos(daneben[0] * k, daneben[1] * k);
                context.waitTicks(2);
                int aus = weisse(context.takeScreenshot(TestScreenshotOptions.of("schweben-" + i + "-aus").disableCounterPrefix()), k, z, 6);
                if (an < aus + 8) {
                    throw new AssertionError(namen[i] + " unter der Maus ohne hellen Rand: " + an + " weisse Pixel, daneben " + aus + "; " + unter);
                }
            }
        } finally {
            context.runOnClient(mc -> {
                List.copyOf(Wegpunkte.INSTANZ.punkte()).subList(punkte, Wegpunkte.INSTANZ.punkte().size()).forEach(Wegpunkte.INSTANZ::loesche);
                List.copyOf(Wegpunkte.INSTANZ.eigeneFormen()).subList(formen, Wegpunkte.INSTANZ.eigeneFormen().size())
                        .forEach(Wegpunkte.INSTANZ::loesche);
            });
            context.waitTicks(2);
        }
    }

    /** Die fast weissen Pixel im Quadrat ±{@code r} Einheiten um (x, y) im Bildschirmfoto. */
    private static int weisse(Path bild, int k, double[] mitte, int r) {
        return pixel(bild, k, mitte, r, p -> (p >> 16 & 0xFF) >= 0xF0 && (p >> 8 & 0xFF) >= 0xF0 && (p & 0xFF) >= 0xF0);
    }

    /** Die Pixel in der Farbe der Kartenschrift, auf 16 je Kanal genau, im Quadrat ±{@code r} Einheiten um (x, y). */
    private static int dunkle(Path bild, int k, double[] mitte, int r) {
        return pixel(bild, k, mitte, r, p -> {
            for (int s = 0; s < 24; s += 8) {
                if (Math.abs((p >> s & 0xFF) - (Ebenen.SCHRIFTFARBE >> s & 0xFF)) > 16) {
                    return false;
                }
            }
            return true;
        });
    }

    /** Die Pixel im Quadrat ±{@code r} Einheiten um (x, y) im Bildschirmfoto, auf die {@code passt} zutrifft. */
    private static int pixel(Path bild, int k, double[] mitte, int r, IntPredicate passt) {
        try {
            BufferedImage b = ImageIO.read(bild.toFile());
            int n = 0;
            for (int y = (int) ((mitte[1] - r) * k); y < (mitte[1] + r) * k; y++) {
                for (int x = (int) ((mitte[0] - r) * k); x < (mitte[0] + r) * k; x++) {
                    n += passt.test(b.getRGB(x, y)) ? 1 : 0;
                }
            }
            return n;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Namen wie ein Spieler (mod#128): Rechtsklick auf den Griff einer Region, „Name…“, und das Feld ist leer. Getippt, dann
     * die Kartentaste und Escape: Die Karte bleibt offen, Escape schliesst nur das Feld, ohne Namen. Wieder „Name…“, tippen,
     * Enter: Die Form heisst so, und eine Schrifthöhe unter dem Griff steht die Kartenschrift, wo vorher keine stand. Am Griff
     * gezogen behält sie ihn. „Name ändern…“ zeigt den Namen; ein Klick daneben lässt ihn; leer gemacht und Enter nimmt ihn weg.
     * Der Hinweis unten links bleibt dabei, wie er war. Siehe docs/wegpunkte.md, „Namen“.
     */
    private static void namen(ClientGameTestContext context, TestInput maus, int k) {
        int formen = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().size());
        int[] mitte = context.computeOnClient(mc -> {
            double[] m = ((Karte) mc.gui.screen()).blickMitte();
            return new int[] {(int) Math.floor(m[0] / 4), (int) Math.floor(m[1] / 4)};
        });
        // Über der Mitte, so liegt der Name darunter auf dem Schirm. Ein Block sind 4 Einheiten; die Region ist 16 Blöcke breit,
        // ihr Name also 8 Blöcke hoch.
        int bx = mitte[0] - 8, bz = mitte[1] - 24;
        List<Wegpunkte.Ecke> region = List.of(new Wegpunkte.Ecke(bx, bz), new Wegpunkte.Ecke(bx + 16, bz),
                new Wegpunkte.Ecke(bx + 16, bz + 16), new Wegpunkte.Ecke(bx, bz + 16));
        try {
            context.runOnClient(mc -> Wegpunkte.INSTANZ.setzeForm(mc.level.dimension().identifier().toString(), region));
            context.waitTicks(2);
            double[] g = griff(context, formen);
            frei(context, new double[][] {g});
            double[] ort = {g[0], g[1] + 8 * 4};
            int vorher = dunkle(context.takeScreenshot(TestScreenshotOptions.of("namen-vorher").disableCounterPrefix()), k, ort, 12);

            eintrag(context, maus, k, g[0], g[1], "heroicmap.karte.name");
            String leer = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eingabe());
            if (!"".equals(leer)) {
                throw new AssertionError("„Name…“ öffnet kein leeres Feld: " + leer);
            }
            maus.typeChars("Mühle");
            maus.pressKey(HeroicMap.karte);
            context.waitTicks(2);
            maus.pressKey(InputConstants.KEY_ESCAPE);
            context.waitTicks(2);
            if (!(context.computeOnClient(mc -> mc.gui.screen()) instanceof Karte k2) || k2.eingabe() != null
                    || name(context, formen) != null) {
                throw new AssertionError("Kartentaste und Escape im Feld: Karte offen "
                        + (context.computeOnClient(mc -> mc.gui.screen()) instanceof Karte) + ", Name " + name(context, formen));
            }

            eintrag(context, maus, k, g[0], g[1], "heroicmap.karte.name");
            maus.typeChars("Mühle M");
            maus.pressKey(InputConstants.KEY_RETURN);
            context.waitTicks(2);
            if (!"Mühle M".equals(name(context, formen)) || context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eingabe()) != null) {
                throw new AssertionError("Enter speicherte den Namen nicht: " + name(context, formen));
            }
            int nachher = dunkle(context.takeScreenshot(TestScreenshotOptions.of("namen").disableCounterPrefix()), k, ort, 12);
            if (nachher < vorher + 100) {
                throw new AssertionError("Keine Kartenschrift unter dem Griff: " + nachher + " Pixel in ihrer Farbe, vorher " + vorher);
            }

            // Am Griff halten und 8 Einheiten nach rechts ziehen: Die Region liegt 2 Blöcke weiter und heisst weiter so.
            warte250();
            maus.setCursorPos(g[0] * k, g[1] * k);
            context.waitTick();
            long druck = Util.getMillis();
            maus.holdMouse(LINKS);
            zittereBis(context, maus, g[0] * k, g[1] * k, druck + 700);
            maus.setCursorPos((g[0] + 8) * k, g[1] * k);
            context.waitTicks(2);
            maus.releaseMouse(LINKS);
            context.waitTicks(2);
            List<Wegpunkte.Ecke> soll = region.stream().map(c -> new Wegpunkte.Ecke(c.x() + 2, c.z())).toList();
            List<Wegpunkte.Ecke> ist = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().get(formen).ecken());
            if (!ist.equals(soll) || !"Mühle M".equals(name(context, formen))) {
                throw new AssertionError("Am Griff gezogen: " + ist + " statt " + soll + ", Name " + name(context, formen));
            }
            g = griff(context, formen);

            eintrag(context, maus, k, g[0], g[1], "heroicmap.karte.name_aendern");
            String voll = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eingabe());
            if (!"Mühle M".equals(voll)) {
                throw new AssertionError("„Name ändern…“ zeigt nicht den Namen: " + voll);
            }
            maus.typeChars("X");
            // Links neben dem Feld, das am Griff beginnt.
            spielerKlick(context, maus, k, g[0] - 30, g[1] + 30);
            if (!"Mühle M".equals(name(context, formen)) || context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eingabe()) != null) {
                throw new AssertionError("Ein Klick neben das Feld: Name " + name(context, formen));
            }

            eintrag(context, maus, k, g[0], g[1], "heroicmap.karte.name_aendern");
            for (int i = 0; i < 10; i++) {
                maus.pressKey(InputConstants.KEY_BACKSPACE);
            }
            maus.pressKey(InputConstants.KEY_RETURN);
            context.waitTicks(2);
            if (name(context, formen) != null) {
                throw new AssertionError("Leer und Enter liess den Namen: " + name(context, formen));
            }
            // Umbenennen und Ziehen melden nichts unten links.
            if (!"null".equals(hinweis(context))) {
                throw new AssertionError("Nach Umbenennen und Ziehen steht unten links „" + hinweis(context) + "“");
            }
        } finally {
            context.runOnClient(mc -> List.copyOf(Wegpunkte.INSTANZ.eigeneFormen()).subList(formen, Wegpunkte.INSTANZ.eigeneFormen().size())
                    .forEach(Wegpunkte.INSTANZ::loesche));
            context.waitTicks(2);
        }
    }

    /** Der Hinweis unten links als Text, „null“ ohne. */
    private static String hinweis(ClientGameTestContext context) {
        return context.computeOnClient(mc -> {
            Component h = ((Karte) mc.gui.screen()).hinweis();
            return String.valueOf(h == null ? null : h.getString());
        });
    }

    /**
     * Eine eigene Farbe wie ein Spieler: Rechtsklick auf den Griff einer Region, „Farbe…“, das Feld zeigt ihre Farbe als #RRGGBB.
     * Was keine Farbe ist, nimmt Enter nicht, das Feld bleibt offen; „3a7fd5“ und Enter färben die Region, und ihr Rand steht im
     * Bildschirmfoto in dieser Farbe; leer und Enter gibt ihr die Farbe aus der Liste zurück. Siehe docs/wegpunkte.md, „Farbe“.
     */
    private static void farbe(ClientGameTestContext context, TestInput maus, int k) {
        int formen = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().size());
        int[] mitte = context.computeOnClient(mc -> {
            double[] m = ((Karte) mc.gui.screen()).blickMitte();
            return new int[] {(int) Math.floor(m[0] / 4), (int) Math.floor(m[1] / 4)};
        });
        int bx = mitte[0] - 8, bz = mitte[1] - 24;
        List<Wegpunkte.Ecke> region = List.of(new Wegpunkte.Ecke(bx, bz), new Wegpunkte.Ecke(bx + 16, bz),
                new Wegpunkte.Ecke(bx + 16, bz + 16), new Wegpunkte.Ecke(bx, bz + 16));
        try {
            context.runOnClient(mc -> Wegpunkte.INSTANZ.setzeForm(mc.level.dimension().identifier().toString(), region));
            context.waitTicks(2);
            double[] g = griff(context, formen);
            frei(context, new double[][] {g});
            String liste = context.computeOnClient(mc -> Wegpunkte.hex(Wegpunkte.INSTANZ.eigeneFormen().get(formen).argb()));

            eintrag(context, maus, k, g[0], g[1], "heroicmap.karte.farbe");
            String feld = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eingabe());
            if (!liste.equals(feld)) {
                throw new AssertionError("„Farbe…“ zeigt nicht die Farbe der Region: " + feld + " statt " + liste);
            }
            leeren(maus, 7);
            maus.typeChars("#3A7FDG");
            maus.pressKey(InputConstants.KEY_RETURN);
            context.waitTicks(2);
            if (context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eingabe()) == null || hex(context, formen) != Wegpunkte.KEIN_HEX) {
                throw new AssertionError("Enter nahm „#3A7FDG“: Farbe " + hex(context, formen));
            }
            leeren(maus, 7);
            maus.typeChars("3a7fd5");
            maus.pressKey(InputConstants.KEY_RETURN);
            context.waitTicks(2);
            if (context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eingabe()) != null || hex(context, formen) != 0x3A7FD5) {
                throw new AssertionError("Enter färbte die Region nicht: " + hex(context, formen));
            }
            // Auf der Mitte der oberen Kante, zwischen den ersten beiden Ecken, wie gezeichnet.
            double[] a = ecke(context, formen, 0), b = ecke(context, formen, 1);
            Path bild = context.takeScreenshot(TestScreenshotOptions.of("farbe").disableCounterPrefix());
            if (!linieDa(bild, k, (a[0] + b[0]) / 2, (a[1] + b[1]) / 2, 0xFF3A7FD5)) {
                throw new AssertionError("Der Rand der Region steht nicht in #3A7FD5");
            }

            eintrag(context, maus, k, g[0], g[1], "heroicmap.karte.farbe");
            if (!"#3A7FD5".equals(context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eingabe()))) {
                throw new AssertionError("„Farbe…“ zeigt nicht die eigene Farbe");
            }
            leeren(maus, 7);
            maus.pressKey(InputConstants.KEY_RETURN);
            context.waitTicks(2);
            if (hex(context, formen) != Wegpunkte.KEIN_HEX) {
                throw new AssertionError("Leer und Enter liess die eigene Farbe: " + hex(context, formen));
            }
        } finally {
            context.runOnClient(mc -> List.copyOf(Wegpunkte.INSTANZ.eigeneFormen()).subList(formen, Wegpunkte.INSTANZ.eigeneFormen().size())
                    .forEach(Wegpunkte.INSTANZ::loesche));
            context.waitTicks(2);
        }
    }

    /**
     * Ein Hinweis unten links geht beim nächsten Klick auf die Karte und bei der nächsten Taste weg, nicht erst nach 5 s. Siehe
     * docs/vollbildkarte.md, „Bedienung“.
     */
    private static void hinweisWeg(ClientGameTestContext context, TestInput maus, int k) {
        double[] ort = context.computeOnClient(mc -> new double[] {60, mc.getWindow().getGuiScaledHeight() - 60});
        frei(context, new double[][] {ort});
        context.runOnClient(mc -> ((Karte) mc.gui.screen()).zeige(Component.literal("Probe")));
        spielerKlick(context, maus, k, ort[0], ort[1]);
        if (!"null".equals(hinweis(context))) {
            throw new AssertionError("Ein Klick auf die Karte liess den Hinweis stehen: " + hinweis(context));
        }
        context.runOnClient(mc -> ((Karte) mc.gui.screen()).zeige(Component.literal("Probe")));
        context.waitTicks(2);
        maus.pressKey(InputConstants.KEY_LSHIFT);
        context.waitTicks(2);
        if (!"null".equals(hinweis(context)) || !(context.computeOnClient(mc -> mc.gui.screen()) instanceof Karte)) {
            throw new AssertionError("Eine Taste liess den Hinweis stehen oder schloss die Karte: " + hinweis(context));
        }
    }

    /**
     * Ein Wegpunkt an eingetippten Koordinaten wie ein Spieler (mod#129): Rechtsklick auf freie Karte, „Wegpunkt bei …“, das
     * Feld zeigt `x z` des Blocks; „12“ nimmt Enter nicht; `x 64 z` 30 Blöcke weiter setzt dort einen Wegpunkt, y zählt nicht,
     * und er steht in der Mitte. Siehe docs/wegpunkte.md, „Bedienung“.
     */
    private static void wegpunktBei(ClientGameTestContext context, TestInput maus, int k) {
        int punkte = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size());
        double[] ort = context.computeOnClient(mc -> new double[] {60, mc.getWindow().getGuiScaledHeight() - 60});
        frei(context, new double[][] {ort});
        int[] block = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).blockUnter(ort[0], ort[1]));
        try {
            eintrag(context, maus, k, ort[0], ort[1], "heroicmap.karte.wegpunkt_bei");
            String feld = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eingabe());
            if (!(block[0] + " " + block[1]).equals(feld)) {
                throw new AssertionError("„Wegpunkt bei …“ zeigt „" + feld + "“ statt " + block[0] + " " + block[1]);
            }
            leeren(maus, feld.length());
            maus.typeChars("12");
            maus.pressKey(InputConstants.KEY_RETURN);
            context.waitTicks(2);
            if (context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eingabe()) == null
                    || context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size()) != punkte) {
                throw new AssertionError("Enter nahm „12“");
            }
            leeren(maus, 2);
            int x = block[0] + 30, z = block[1] - 7;
            maus.typeChars(x + " 64 " + z);
            maus.pressKey(InputConstants.KEY_RETURN);
            context.waitTicks(2);
            Wegpunkte.Punkt neu = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().getLast());
            if (context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size()) != punkte + 1 || neu.x() != x || neu.z() != z) {
                throw new AssertionError("„" + x + " 64 " + z + "“ setzte " + neu);
            }
            Karte.Marke m = marke(context, neu);
            double[] mitte = context.computeOnClient(mc -> new double[] {mc.getWindow().getGuiScaledWidth() / 2.0,
                mc.getWindow().getGuiScaledHeight() / 2.0});
            if (Math.abs(m.x() - mitte[0]) > 1 || Math.abs(m.y() - mitte[1]) > 1) {
                throw new AssertionError("Der neue Wegpunkt steht bei " + m.x() + "," + m.y() + ", nicht in der Mitte " + Arrays.toString(mitte));
            }
        } finally {
            context.runOnClient(mc -> List.copyOf(Wegpunkte.INSTANZ.punkte()).subList(punkte, Wegpunkte.INSTANZ.punkte().size())
                    .forEach(Wegpunkte.INSTANZ::loesche));
            context.waitTicks(2);
        }
    }

    /** Ein Rechtsklick wie von Hand bei (x, y), ohne einen Eintrag zu wählen; gibt die Zeit des Drückens. */
    private static long rechtsKlick(ClientGameTestContext context, TestInput maus, int k, double x, double y) {
        warte250();
        maus.setCursorPos(x * k, y * k);
        context.waitTick();
        long druck = Util.getMillis();
        maus.pressMouse(RECHTS);
        context.waitTicks(2);
        return druck;
    }

    /**
     * Ein Rechtsklick im Bau schliesst die Form ab (mod#149): mit einer Ecke nur der Hinweis, mit zweien eine Linie, auch auf
     * einem Wegpunkt; mit vier eine Region. Am Griff einer Form kommt ihr Menü, mit einer Ecke im Bau mit „Form abbrechen“ und
     * ohne „Form fertig“, und „Form abbrechen“ verwirft die Form im Bau. Im Bau sonst kein Menü. Siehe docs/wegpunkte.md,
     * „Eigene Formen“.
     */
    private static void rechtsklickImBau(ClientGameTestContext context, TestInput maus, int k) {
        int punkte = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size());
        int formen = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().size());
        int breite = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
        int hoehe = context.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
        double mx = breite / 2.0, my = hoehe / 2.0;
        double[][] orte = {{mx - 120, my + 30}, {mx - 80, my + 30}, {mx - 80, my + 70}, {mx - 120, my + 70}, {mx - 40, my + 50}};
        frei(context, orte);
        try {
            // Ein Wegpunkt als Ziel des zweiten Rechtsklicks.
            context.runOnClient(mc -> {
                int[] b = ((Karte) mc.gui.screen()).blockUnter(orte[4][0], orte[4][1]);
                Wegpunkte.INSTANZ.setze(mc.level.dimension().identifier().toString(), b[0], b[1]);
            });
            context.waitTicks(2);
            Karte.Marke wegpunkt = marke(context, context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().get(punkte)));

            // Eine Ecke: Der Rechtsklick sagt es und lässt die Form im Bau.
            eintrag(context, maus, k, orte[0][0], orte[0][1], "heroicmap.karte.form_von_hier");
            rechtsKlick(context, maus, k, orte[2][0], orte[2][1]);
            String hinweis = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).hinweis() == null ? null
                    : ((Karte) mc.gui.screen()).hinweis().getString());
            List<Wegpunkte.Ecke> zug = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).zug());
            if (!texte(context, "heroicmap.karte.form_zwei").getFirst().equals(hinweis) || zug == null || zug.size() != 1
                    || context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eintraege()).size() != 0
                    || context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().size()) != formen) {
                throw new AssertionError("Rechtsklick mit einer Ecke: Hinweis „" + hinweis + "“, im Bau " + zug);
            }
            // Eine zweite Ecke, dann der Rechtsklick auf den Wegpunkt: eine Linie, kein Menü.
            spielerKlick(context, maus, k, orte[1][0], orte[1][1]);
            rechtsKlick(context, maus, k, wegpunkt.x(), wegpunkt.y());
            List<Wegpunkte.EigeneForm> alle = context.computeOnClient(mc -> List.copyOf(Wegpunkte.INSTANZ.eigeneFormen()));
            if (alle.size() != formen + 1 || alle.getLast().region() || alle.getLast().ecken().size() != 2
                    || context.computeOnClient(mc -> ((Karte) mc.gui.screen()).zug()) != null
                    || !context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eintraege()).isEmpty()) {
                throw new AssertionError("Rechtsklick auf einen Wegpunkt mit zwei Ecken: " + (alle.size() > formen ? alle.getLast() : null));
            }
            context.runOnClient(mc -> Wegpunkte.INSTANZ.loesche(Wegpunkte.INSTANZ.eigeneFormen().getLast()));
            context.waitTicks(2);

            // Vier Ecken, dann der Rechtsklick auf freie Karte: eine Region mit allen vier.
            eintrag(context, maus, k, orte[0][0], orte[0][1], "heroicmap.karte.form_von_hier");
            for (int i = 1; i < 4; i++) {
                spielerKlick(context, maus, k, orte[i][0], orte[i][1]);
            }
            rechtsKlick(context, maus, k, mx + 40, my - 40);
            alle = context.computeOnClient(mc -> List.copyOf(Wegpunkte.INSTANZ.eigeneFormen()));
            if (alle.size() != formen + 1 || !alle.getLast().region() || alle.getLast().ecken().size() != 4) {
                throw new AssertionError("Rechtsklick mit vier Ecken gab keine Region mit vieren: " + (alle.size() > formen ? alle.getLast() : null));
            }

            // Im Bau mit einer Ecke am Griff dieser Region: ihr Menü mit „Form abbrechen“, ohne „Form fertig“, das es erst ab zwei Ecken
            // gibt; „Form abbrechen“ verwirft den Bau.
            double[] griff = griff(context, formen);
            eintrag(context, maus, k, orte[4][0] + 30, orte[4][1] + 30, "heroicmap.karte.form_von_hier");
            List<String> menue = eintraegeBei(context, maus, k, griff[0], griff[1]);
            if (!menue.contains(texte(context, "heroicmap.karte.form_abbrechen").getFirst())
                    || menue.contains(texte(context, "heroicmap.karte.form_fertig").getFirst())
                    || !menue.contains(texte(context, "heroicmap.karte.form_loeschen").getFirst())) {
                throw new AssertionError("Rechtsklick am Griff im Bau: " + menue);
            }
            eintrag(context, maus, k, griff[0], griff[1], "heroicmap.karte.form_abbrechen");
            if (context.computeOnClient(mc -> ((Karte) mc.gui.screen()).zug()) != null
                    || context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().size()) != formen + 1) {
                throw new AssertionError("„Form abbrechen“ am Griff liess die Form im Bau");
            }
        } finally {
            if (context.computeOnClient(mc -> ((Karte) mc.gui.screen()).zug()) != null) {
                maus.pressKey(InputConstants.KEY_ESCAPE);
                context.waitTick();
            }
            context.runOnClient(mc -> {
                List.copyOf(Wegpunkte.INSTANZ.punkte()).subList(punkte, Wegpunkte.INSTANZ.punkte().size()).forEach(Wegpunkte.INSTANZ::loesche);
                List.copyOf(Wegpunkte.INSTANZ.eigeneFormen()).subList(formen, Wegpunkte.INSTANZ.eigeneFormen().size()).forEach(Wegpunkte.INSTANZ::loesche);
            });
            context.waitTicks(2);
        }
    }

    /**
     * Hält wie die Hand 0,7 s still auf {@code von}, fährt dann in acht Schritten nach {@code nach}, auf Wunsch mit gehaltener
     * Strg, wartet und lässt los. Gibt den Ort der gezogenen Ecke der Form {@code form} an der Stelle {@code index}, wie er
     * kurz vor dem Loslassen gezeichnet stand, oder null, wenn {@code form} kleiner 0.
     */
    private static double[] halteUndZiehe(ClientGameTestContext context, TestInput maus, int k, double[] von, double[] nach, boolean strg,
            int form, int index) {
        warte250();
        maus.setCursorPos(von[0] * k, von[1] * k);
        context.waitTick();
        long druck = Util.getMillis();
        maus.holdMouse(LINKS);
        zittereBis(context, maus, von[0] * k, von[1] * k, druck + 700);
        if (strg) {
            maus.holdControl();
        }
        for (int i = 1; i <= 8; i++) {
            maus.setCursorPos((von[0] + (nach[0] - von[0]) * i / 8) * k, (von[1] + (nach[1] - von[1]) * i / 8) * k);
            context.waitTick();
        }
        context.waitTicks(2);
        double[] gezeichnet = form < 0 ? null : ecke(context, form, index);
        maus.releaseMouse(LINKS);
        context.waitTicks(2);
        if (strg) {
            maus.releaseControl();
        }
        return gezeichnet;
    }

    /**
     * Einrasten mit Strg (mod#149): Eine Ecke, 0,7 s gehalten und mit Strg 6 Einheiten neben die Ecke einer anderen Form
     * gezogen, liegt nach dem Loslassen auf deren Block und stand schon beim Ziehen dort; ohne Strg auf dem Block unter der
     * Maus. Ebenso auf einen Wegpunkt. Ein Wegpunkt rastet auf eine Ecke ein. 12 Einheiten weg rastet nichts ein, die
     * Ecken der eigenen Form zählen nicht, und neben der Marke eines fernen Wegpunkts am Rand rastet nichts ein. Siehe
     * docs/wegpunkte.md, „Einrasten“.
     */
    private static void einrasten(ClientGameTestContext context, TestInput maus, int k) {
        int punkte = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size());
        int formen = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().size());
        int[] mitte = context.computeOnClient(mc -> {
            double[] m = ((Karte) mc.gui.screen()).blickMitte();
            return new int[] {(int) Math.floor(m[0] / 4), (int) Math.floor(m[1] / 4)};
        });
        int bx = mitte[0] - 34, bz = mitte[1] + 6;
        // Form A, ein Viereck, links; Form B, eine Linie, rechts davon; ein Wegpunkt darunter. Ein Block sind 4 Einheiten.
        List<Wegpunkte.Ecke> a = List.of(new Wegpunkte.Ecke(bx, bz), new Wegpunkte.Ecke(bx + 8, bz), new Wegpunkte.Ecke(bx + 8, bz + 8),
                new Wegpunkte.Ecke(bx, bz + 8));
        Wegpunkte.Ecke b0 = new Wegpunkte.Ecke(bx + 20, bz + 2);
        try {
            context.runOnClient(mc -> {
                String dimension = mc.level.dimension().identifier().toString();
                Wegpunkte.INSTANZ.setzeForm(dimension, a);
                Wegpunkte.INSTANZ.setzeForm(dimension, List.of(b0, new Wegpunkte.Ecke(bx + 26, bz + 2)));
                Wegpunkte.INSTANZ.setze(dimension, bx + 20, bz + 12);
            });
            context.waitTicks(2);
            Wegpunkte.Punkt w = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().get(punkte));
            double[] zielB = ecke(context, formen + 1, 0);
            frei(context, new double[][] {ecke(context, formen, 1), zielB});

            // Ecke 1 von A mit Strg 6 Einheiten neben B0: auf B0, schon beim Ziehen dort gezeichnet.
            double[] a1 = ecke(context, formen, 1);
            double[] daneben = {zielB[0] - 6, zielB[1]};
            double[] gezeichnet = halteUndZiehe(context, maus, k, a1, daneben, true, formen, 1);
            // Das Zittern beim Halten kann die Karte um eine Einheit schieben; verglichen wird mit der Lage von B0 danach.
            double[] zielJetzt = ecke(context, formen + 1, 0);
            Wegpunkte.Ecke ist = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().get(formen).ecken().get(1));
            if (!ist.equals(b0) || Math.abs(gezeichnet[0] - zielJetzt[0]) > 0.5 || Math.abs(gezeichnet[1] - zielJetzt[1]) > 0.5) {
                throw new AssertionError("Ecke mit Strg neben die Ecke einer anderen Form: " + ist + " statt " + b0 + ", gezeichnet bei "
                        + Arrays.toString(gezeichnet) + " statt " + Arrays.toString(zielJetzt));
            }
            // Zurück, dann ohne Strg dasselbe: auf dem Block unter der Maus, nicht auf B0.
            context.runOnClient(mc -> Wegpunkte.INSTANZ.ersetze(Wegpunkte.INSTANZ.eigeneFormen().get(formen), a));
            context.waitTicks(2);
            // Die Lage von B0 von jetzt; das Halten schiebt die Karte dann noch um bis zu eine Einheit, also höchstens einen Block.
            a1 = ecke(context, formen, 1);
            double[] b0Ohne = ecke(context, formen + 1, 0);
            double[] nebenOhne = {b0Ohne[0] - 6, b0Ohne[1]};
            int[] unter = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).blockUnter(nebenOhne[0], nebenOhne[1]));
            halteUndZiehe(context, maus, k, a1, nebenOhne, false, -1, 0);
            ist = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().get(formen).ecken().get(1));
            if (ist.equals(b0) || Math.abs(ist.x() - unter[0]) > 1 || Math.abs(ist.z() - unter[1]) > 1) {
                throw new AssertionError("Ecke ohne Strg: " + ist + " statt bei " + unter[0] + " " + unter[1] + ", nicht auf " + b0);
            }

            // Ecke 2 von A mit Strg 6 Einheiten neben den Wegpunkt: auf seinem Block.
            context.runOnClient(mc -> Wegpunkte.INSTANZ.ersetze(Wegpunkte.INSTANZ.eigeneFormen().get(formen), a));
            context.waitTicks(2);
            Karte.Marke wm = marke(context, w);
            halteUndZiehe(context, maus, k, ecke(context, formen, 2), new double[] {wm.x() - 6, wm.y()}, true, -1, 0);
            ist = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().get(formen).ecken().get(2));
            if (!ist.equals(new Wegpunkte.Ecke(w.x(), w.z()))) {
                throw new AssertionError("Ecke mit Strg neben einen Wegpunkt: " + ist + " statt " + w.x() + " " + w.z());
            }

            // 12 Einheiten neben B0: kein Einrasten. Ecke 0 von A mit Strg neben ihre eigene Ecke 2: kein Einrasten.
            context.runOnClient(mc -> Wegpunkte.INSTANZ.ersetze(Wegpunkte.INSTANZ.eigeneFormen().get(formen), a));
            context.waitTicks(2);
            double[] b0Jetzt = ecke(context, formen + 1, 0);
            halteUndZiehe(context, maus, k, ecke(context, formen, 1), new double[] {b0Jetzt[0] - 12, b0Jetzt[1]}, true, -1, 0);
            ist = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().get(formen).ecken().get(1));
            if (ist.equals(b0)) {
                throw new AssertionError("Ecke mit Strg 12 Einheiten neben B0 rastete ein");
            }
            context.runOnClient(mc -> Wegpunkte.INSTANZ.ersetze(Wegpunkte.INSTANZ.eigeneFormen().get(formen), a));
            context.waitTicks(2);
            double[] a2 = ecke(context, formen, 2);
            halteUndZiehe(context, maus, k, ecke(context, formen, 0), new double[] {a2[0] - 6, a2[1]}, true, -1, 0);
            ist = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().get(formen).ecken().get(0));
            if (ist.equals(a.get(2))) {
                throw new AssertionError("Ecke mit Strg rastete auf eine Ecke der eigenen Form ein");
            }

            // Der Wegpunkt mit Strg 6 Einheiten neben B0: auf B0. Ein Bild beim Ziehen zeigt ihn dort. Jedes Halten mit Zittern
            // schiebt die Karte um bis zu eine Einheit; darum gilt die Lage von B0 von jetzt.
            context.runOnClient(mc -> Wegpunkte.INSTANZ.ersetze(Wegpunkte.INSTANZ.eigeneFormen().get(formen), a));
            context.waitTicks(2);
            wm = marke(context, w);
            double[] b0Neu = ecke(context, formen + 1, 0);
            double[] nebenB0 = {b0Neu[0] - 6, b0Neu[1]};
            warte250();
            maus.setCursorPos(wm.x() * k, wm.y() * k);
            context.waitTick();
            long druck = Util.getMillis();
            maus.holdMouse(LINKS);
            zittereBis(context, maus, wm.x() * k, wm.y() * k, druck + 700);
            maus.holdControl();
            for (int i = 1; i <= 8; i++) {
                maus.setCursorPos((wm.x() + (nebenB0[0] - wm.x()) * i / 8) * k, (wm.y() + (nebenB0[1] - wm.y()) * i / 8) * k);
                context.waitTick();
            }
            context.waitTicks(2);
            context.takeScreenshot(TestScreenshotOptions.of("einrasten").disableCounterPrefix());
            maus.releaseMouse(LINKS);
            context.waitTicks(2);
            maus.releaseControl();
            Wegpunkte.Punkt nach = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().get(punkte));
            if (nach.x() != b0.x() || nach.z() != b0.z()) {
                throw new AssertionError("Wegpunkt mit Strg neben die Ecke einer Form: " + nach + " statt " + b0);
            }

            // Ein Wegpunkt 400 Blöcke links steht als Marke am Rand. Eine Ecke mit Strg 6 Einheiten neben dieser Marke rastet nicht
            // auf ihn ein, denn gemessen wird an seinem Block (Review von #161).
            context.runOnClient(mc -> {
                Wegpunkte.INSTANZ.ersetze(Wegpunkte.INSTANZ.eigeneFormen().get(formen), a);
                Wegpunkte.INSTANZ.setze(mc.level.dimension().identifier().toString(), bx - 400, bz + 2);
            });
            context.waitTicks(2);
            Wegpunkte.Punkt fern = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().getLast());
            Karte.Marke rand = marke(context, fern);
            double[] nebenRand = {rand.x() + 6, rand.y()};
            int[] unterRand = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).blockUnter(nebenRand[0], nebenRand[1]));
            halteUndZiehe(context, maus, k, ecke(context, formen, 1), nebenRand, true, -1, 0);
            ist = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().get(formen).ecken().get(1));
            if (ist.x() == fern.x() || Math.abs(ist.x() - unterRand[0]) > 1 || Math.abs(ist.z() - unterRand[1]) > 1) {
                throw new AssertionError("Ecke mit Strg neben der Marke eines fernen Wegpunkts am Rand (" + rand.x() + "," + rand.y() + "): "
                        + ist + " statt bei " + unterRand[0] + " " + unterRand[1]);
            }
        } finally {
            maus.releaseControl();
            context.runOnClient(mc -> {
                List.copyOf(Wegpunkte.INSTANZ.punkte()).subList(punkte, Wegpunkte.INSTANZ.punkte().size()).forEach(Wegpunkte.INSTANZ::loesche);
                List.copyOf(Wegpunkte.INSTANZ.eigeneFormen()).subList(formen, Wegpunkte.INSTANZ.eigeneFormen().size()).forEach(Wegpunkte.INSTANZ::loesche);
            });
            context.waitTicks(2);
        }
    }

    /** Die Texte der Einträge mit diesen Schlüsseln, ohne Argumente, wie das Menü sie zeigt. */
    private static List<String> texte(ClientGameTestContext context, String... schluessel) {
        return context.computeOnClient(mc -> Arrays.stream(schluessel).map(s -> Component.translatable(s).getString()).toList());
    }

    /**
     * Das Menü je Objekt (mod#149) mit Rechtsklicks wie von Hand: an einem Wegpunkt nur seine Einträge, auch wenn er auf einer
     * Ecke liegt; an einer Ecke, an der Raute eines alten Rechtecks und auf freier Karte je ihre; im Bau das Menü zum Bauen.
     * Dann „Koordinaten setzen…“: an einer Ecke, an einem Wegpunkt auf einen belegten Block und auf einen freien, mit Text, der
     * keine Koordinaten sind. Siehe docs/wegpunkte.md, „Menü je Objekt“.
     */
    private static void menueJeObjekt(ClientGameTestContext context, TestInput maus, int k) {
        int punkte = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size());
        int formen = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().size());
        int rechtecke = context.computeOnClient(mc -> Wegpunkte.INSTANZ.regionen().size());
        // Links unter der Mitte, wo sonst nichts liegt; ein Block sind 4 Einheiten.
        int[] mitte = context.computeOnClient(mc -> {
            double[] m = ((Karte) mc.gui.screen()).blickMitte();
            return new int[] {(int) Math.floor(m[0] / 4), (int) Math.floor(m[1] / 4)};
        });
        int bx = mitte[0] - 30, bz = mitte[1] + 8;
        List<Wegpunkte.Ecke> ecken = List.of(new Wegpunkte.Ecke(bx, bz), new Wegpunkte.Ecke(bx + 10, bz), new Wegpunkte.Ecke(bx + 10, bz + 8),
                new Wegpunkte.Ecke(bx, bz + 8));
        try {
            context.runOnClient(mc -> {
                String dimension = mc.level.dimension().identifier().toString();
                Wegpunkte.INSTANZ.setzeForm(dimension, ecken);
                // Ein Wegpunkt auf der ersten Ecke, einer frei daneben, ein altes Rechteck darunter.
                Wegpunkte.INSTANZ.setze(dimension, bx, bz);
                Wegpunkte.INSTANZ.setze(dimension, bx + 16, bz + 4);
                Wegpunkte.INSTANZ.setze(dimension, bx + 2, bz + 14, bx + 4, bz + 16);
            });
            context.waitTicks(2);
            Wegpunkte.Punkt aufEcke = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().get(punkte));
            Wegpunkte.Punkt frei = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().get(punkte + 1));
            Karte.Marke raute = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).marken().stream()
                    .filter(m -> m.region() != null && m.region().x0() == bx + 2).findFirst().orElseThrow(() -> new AssertionError("Keine Raute")));
            double[] zweite = ecke(context, formen, 1);
            frei(context, new double[][] {zweite});

            String teleport = context.computeOnClient(mc -> Component.translatable("heroicmap.karte.teleport", frei.x(), frei.z()).getString());
            List<String> punktSoll = new java.util.ArrayList<>(List.of(teleport));
            punktSoll.addAll(texte(context, "heroicmap.karte.koordinaten", "heroicmap.karte.form_von_hier", "heroicmap.karte.ebene",
                    "heroicmap.karte.wegpunkt_loeschen"));
            Karte.Marke m = marke(context, frei);
            List<String> ist = eintraegeBei(context, maus, k, m.x(), m.y());
            if (!ist.equals(punktSoll)) {
                throw new AssertionError("Menü am Wegpunkt: " + ist + " statt " + punktSoll);
            }
            // Ein Wegpunkt auf einer Ecke: nur seine Einträge, wie beim Klick.
            Karte.Marke aufE = marke(context, aufEcke);
            ist = eintraegeBei(context, maus, k, aufE.x(), aufE.y());
            if (!ist.contains(texte(context, "heroicmap.karte.wegpunkt_loeschen").getFirst())
                    || ist.contains(texte(context, "heroicmap.karte.ecke_loeschen").getFirst())) {
                throw new AssertionError("Menü am Wegpunkt auf einer Ecke: " + ist);
            }
            ist = eintraegeBei(context, maus, k, zweite[0], zweite[1]);
            List<String> eckSoll = texte(context, "heroicmap.karte.koordinaten", "heroicmap.karte.ecke_hinzu", "heroicmap.karte.ecke_loeschen");
            if (!ist.equals(eckSoll)) {
                throw new AssertionError("Menü an der Ecke: " + ist + " statt " + eckSoll);
            }
            ist = eintraegeBei(context, maus, k, raute.x(), raute.y());
            List<String> rautenSoll = texte(context, "heroicmap.karte.ebene", "heroicmap.karte.region_loeschen");
            if (!ist.equals(rautenSoll)) {
                throw new AssertionError("Menü an der Raute: " + ist + " statt " + rautenSoll);
            }
            // Freie Karte, mitten in der Region: das allgemeine Menü, ohne Einträge der Form.
            double[] innen = {zweite[0] - 8, zweite[1] + 16};
            frei(context, new double[][] {innen});
            int[] block = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).blockUnter(innen[0], innen[1]));
            ist = eintraegeBei(context, maus, k, innen[0], innen[1]);
            String hier = context.computeOnClient(mc -> Component.translatable("heroicmap.karte.teleport", block[0], block[1]).getString());
            List<String> allgemeinSoll = new java.util.ArrayList<>(List.of(hier));
            allgemeinSoll.addAll(texte(context, "heroicmap.karte.wegpunkt", "heroicmap.karte.wegpunkt_bei", "heroicmap.karte.form_von_hier"));
            if (!ist.equals(allgemeinSoll)) {
                throw new AssertionError("Menü auf freier Karte: " + ist + " statt " + allgemeinSoll);
            }

            // „Koordinaten setzen…“ an der zweiten Ecke: Das Feld zeigt sie; was keine Koordinaten sind, nimmt Enter nicht.
            eintrag(context, maus, k, zweite[0], zweite[1], "heroicmap.karte.koordinaten");
            String feld = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eingabe());
            if (!((bx + 10) + " " + bz).equals(feld)) {
                throw new AssertionError("„Koordinaten setzen…“ an der Ecke zeigt „" + feld + "“");
            }
            leeren(maus, feld.length());
            maus.typeChars("Burg");
            maus.pressKey(InputConstants.KEY_RETURN);
            context.waitTicks(2);
            if (context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eingabe()) == null) {
                throw new AssertionError("Enter nahm „Burg“ als Koordinaten");
            }
            leeren(maus, 4);
            maus.typeChars((bx + 12) + ", " + (bz - 3));
            maus.pressKey(InputConstants.KEY_RETURN);
            context.waitTicks(2);
            List<Wegpunkte.Ecke> nachher = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().get(formen).ecken());
            if (!nachher.get(1).equals(new Wegpunkte.Ecke(bx + 12, bz - 3)) || nachher.size() != 4) {
                throw new AssertionError("„Koordinaten setzen…“ an der Ecke: " + nachher);
            }

            // Am freien Wegpunkt auf den Block des anderen: Enter nimmt es nicht und sagt warum; auf einen freien Block: dort.
            m = marke(context, frei);
            eintrag(context, maus, k, m.x(), m.y(), "heroicmap.karte.koordinaten");
            leeren(maus, context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eingabe()).length());
            maus.typeChars(bx + " " + bz);
            maus.pressKey(InputConstants.KEY_RETURN);
            context.waitTicks(2);
            String hinweis = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).hinweis() == null ? null
                    : ((Karte) mc.gui.screen()).hinweis().getString());
            if (context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eingabe()) == null
                    || !texte(context, "heroicmap.karte.koordinaten_belegt").getFirst().equals(hinweis)) {
                throw new AssertionError("Wegpunkt auf einen belegten Block: Feld " + context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eingabe())
                        + ", Hinweis „" + hinweis + "“");
            }
            leeren(maus, (bx + " " + bz).length());
            maus.typeChars((bx + 20) + " 70 " + (bz + 1));
            maus.pressKey(InputConstants.KEY_RETURN);
            context.waitTicks(2);
            Wegpunkte.Punkt verschoben = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().get(punkte + 1));
            if (verschoben.x() != bx + 20 || verschoben.z() != bz + 1 || context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size()) != punkte + 2) {
                throw new AssertionError("„Koordinaten setzen…“ am Wegpunkt: " + verschoben);
            }
            // Danach in der Mitte, wie bei „Wegpunkt bei …“.
            Karte.Marke neu = marke(context, verschoben);
            double[] schirm = context.computeOnClient(mc -> new double[] {mc.getWindow().getGuiScaledWidth() / 2.0, mc.getWindow().getGuiScaledHeight() / 2.0});
            if (Math.abs(neu.x() - schirm[0]) > 1 || Math.abs(neu.y() - schirm[1]) > 1) {
                throw new AssertionError("Der verschobene Wegpunkt steht bei " + neu.x() + "," + neu.y() + ", nicht in der Mitte");
            }
        } finally {
            context.runOnClient(mc -> {
                List.copyOf(Wegpunkte.INSTANZ.punkte()).subList(punkte, Wegpunkte.INSTANZ.punkte().size()).forEach(Wegpunkte.INSTANZ::loesche);
                List.copyOf(Wegpunkte.INSTANZ.eigeneFormen()).subList(formen, Wegpunkte.INSTANZ.eigeneFormen().size()).forEach(Wegpunkte.INSTANZ::loesche);
                List.copyOf(Wegpunkte.INSTANZ.regionen()).subList(rechtecke, Wegpunkte.INSTANZ.regionen().size()).forEach(Wegpunkte.INSTANZ::loesche);
            });
            context.waitTicks(2);
        }
    }

    /**
     * `Entf` und `⌫` (mod#149): Der Zeiger fährt wie die Hand auf das Ziel, dann die Taste. Ein Wegpunkt, eine Ecke, am Griff
     * die ganze Form und die Raute eines alten Rechtecks gehen; auf freier Karte, im offenen Feld und im Bau geht nichts.
     * Siehe docs/wegpunkte.md, „Bedienung“.
     */
    private static void entfernen(ClientGameTestContext context, TestInput maus, int k) {
        int punkte = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size());
        int formen = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().size());
        int rechtecke = context.computeOnClient(mc -> Wegpunkte.INSTANZ.regionen().size());
        int[] mitte = context.computeOnClient(mc -> {
            double[] m = ((Karte) mc.gui.screen()).blickMitte();
            return new int[] {(int) Math.floor(m[0] / 4), (int) Math.floor(m[1] / 4)};
        });
        int bx = mitte[0] - 30, bz = mitte[1] + 8;
        try {
            context.runOnClient(mc -> {
                String dimension = mc.level.dimension().identifier().toString();
                Wegpunkte.INSTANZ.setzeForm(dimension, List.of(new Wegpunkte.Ecke(bx, bz), new Wegpunkte.Ecke(bx + 10, bz),
                        new Wegpunkte.Ecke(bx + 10, bz + 8), new Wegpunkte.Ecke(bx, bz + 8)));
                Wegpunkte.INSTANZ.setze(dimension, bx + 16, bz + 4);
                Wegpunkte.INSTANZ.setze(dimension, bx + 2, bz + 14, bx + 4, bz + 16);
            });
            context.waitTicks(2);
            Wegpunkte.Punkt p = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().get(punkte));
            Karte.Marke m = marke(context, p);

            // Auf freier Karte: nichts.
            double[] leer = {m.x() + 30, m.y() + 30};
            frei(context, new double[][] {leer});
            fahre(context, maus, k, leer[0], leer[1]);
            maus.pressKey(InputConstants.KEY_DELETE);
            context.waitTicks(2);
            if (context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size()) != punkte + 1
                    || context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().size()) != formen + 1) {
                throw new AssertionError("Entf auf freier Karte löschte etwas");
            }
            // Bei offenem Menü: nichts, auch wenn die Maus danach über dem Wegpunkt steht (Review von #153).
            warte250();
            maus.setCursorPos(leer[0] * k, leer[1] * k);
            context.waitTick();
            maus.pressMouse(RECHTS);
            context.waitTicks(2);
            fahre(context, maus, k, m.x(), m.y());
            boolean menueOffen = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).ziel()) != null;
            maus.pressKey(InputConstants.KEY_DELETE);
            context.waitTicks(2);
            boolean nachMenue = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size()) == punkte + 1;
            // Ein Klick neben das Menü schliesst es und tut sonst nichts.
            warte250();
            maus.setCursorPos((leer[0] - 10) * k, (leer[1] - 10) * k);
            context.waitTick();
            maus.pressMouse(LINKS);
            context.waitTicks(2);
            if (!menueOffen || !nachMenue) {
                throw new AssertionError("Entf bei offenem Menü: Menü offen " + menueOffen + ", Wegpunkt noch da " + nachMenue);
            }
            // Im Bau: nichts, auch auf dem Wegpunkt.
            eintrag(context, maus, k, leer[0], leer[1], "heroicmap.karte.form_von_hier");
            fahre(context, maus, k, m.x(), m.y());
            maus.pressKey(InputConstants.KEY_DELETE);
            context.waitTicks(2);
            boolean nochDa = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size()) == punkte + 1;
            maus.pressKey(InputConstants.KEY_ESCAPE);
            context.waitTicks(2);
            if (!nochDa) {
                throw new AssertionError("Entf im Bau löschte den Wegpunkt");
            }
            // Im offenen Feld gehört die Taste dem Feld.
            eintrag(context, maus, k, m.x(), m.y(), "heroicmap.karte.koordinaten");
            String vorher = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eingabe());
            maus.pressKey(InputConstants.KEY_BACKSPACE);
            context.waitTicks(2);
            String danach = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eingabe());
            maus.pressKey(InputConstants.KEY_ESCAPE);
            context.waitTicks(2);
            if (danach == null || danach.length() != vorher.length() - 1 || context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size()) != punkte + 1) {
                throw new AssertionError("⌫ im Feld: „" + vorher + "“ → „" + danach + "“, Wegpunkte " + context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size()));
            }

            // Der Wegpunkt mit Entf.
            warte250();
            fahre(context, maus, k, m.x(), m.y());
            maus.pressKey(InputConstants.KEY_DELETE);
            context.waitTicks(2);
            if (context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size()) != punkte) {
                throw new AssertionError("Entf auf dem Wegpunkt liess ihn stehen");
            }
            // Eine Ecke mit ⌫: aus vier werden drei.
            double[] zweite = ecke(context, formen, 1);
            fahre(context, maus, k, zweite[0], zweite[1]);
            maus.pressKey(InputConstants.KEY_BACKSPACE);
            context.waitTicks(2);
            List<Wegpunkte.Ecke> rest = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().get(formen).ecken());
            if (rest.size() != 3 || rest.contains(new Wegpunkte.Ecke(bx + 10, bz))) {
                throw new AssertionError("⌫ auf der zweiten Ecke: " + rest);
            }
            // Am Griff die ganze Form.
            double[] griff = griff(context, formen);
            fahre(context, maus, k, griff[0], griff[1]);
            maus.pressKey(InputConstants.KEY_DELETE);
            context.waitTicks(2);
            if (context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().size()) != formen) {
                throw new AssertionError("Entf am Griff liess die Form stehen");
            }
            // Die Raute eines alten Rechtecks.
            Karte.Marke raute = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).marken().stream()
                    .filter(r -> r.region() != null && r.region().x0() == bx + 2).findFirst().orElseThrow(() -> new AssertionError("Keine Raute")));
            fahre(context, maus, k, raute.x(), raute.y());
            maus.pressKey(InputConstants.KEY_DELETE);
            context.waitTicks(2);
            if (context.computeOnClient(mc -> Wegpunkte.INSTANZ.regionen().size()) != rechtecke) {
                throw new AssertionError("Entf auf der Raute liess das Rechteck stehen");
            }
        } finally {
            context.runOnClient(mc -> {
                List.copyOf(Wegpunkte.INSTANZ.punkte()).subList(Math.min(punkte, Wegpunkte.INSTANZ.punkte().size()), Wegpunkte.INSTANZ.punkte().size())
                        .forEach(Wegpunkte.INSTANZ::loesche);
                List.copyOf(Wegpunkte.INSTANZ.eigeneFormen()).subList(Math.min(formen, Wegpunkte.INSTANZ.eigeneFormen().size()),
                        Wegpunkte.INSTANZ.eigeneFormen().size()).forEach(Wegpunkte.INSTANZ::loesche);
                List.copyOf(Wegpunkte.INSTANZ.regionen()).subList(Math.min(rechtecke, Wegpunkte.INSTANZ.regionen().size()),
                        Wegpunkte.INSTANZ.regionen().size()).forEach(Wegpunkte.INSTANZ::loesche);
            });
            context.waitTicks(2);
        }
    }

    /**
     * Der Todespunkt (mod#129): Stirbt der Spieler, steht auf seinem Block ein angehefteter Todespunkt. Nach dem Respawn öffnet
     * der Weg des Klicks im Chat, `sendUnattendedCommand` mit `hmap karte x z`, die Karte mit ihm als Ziel; mit dem Testsatz
     * steht er dort in der Mitte, dunkel mit hellem ✕, und ein Rechtsklick auf ihn bietet „Todespunkt löschen“, der ihn
     * löscht. Mit dem Schalter aus setzt ein Tod keinen. Siehe docs/wegpunkte.md, „Todespunkt“.
     */
    private static void tod(ClientGameTestContext context, TestServerContext server, TestInput maus, int k) {
        server.runCommand("tp @a 40.5 -60 -30.5");
        context.waitFor(mc -> mc.player != null && Math.abs(mc.player.getX() - 40.5) < 0.1, 200);
        try {
            stirb(context, server);
            Wegpunkte.Punkt tod = context.computeOnClient(mc -> Wegpunkte.INSTANZ.tod());
            if (tod == null || tod.x() != 40 || tod.z() != -31 || !tod.angeheftet()) {
                throw new AssertionError("Nach dem Tod bei 40 -31: Todespunkt " + tod);
            }
            context.runOnClient(mc -> mc.player.connection.sendUnattendedCommand("hmap karte 40 -31", null));
            context.waitFor(mc -> mc.gui.screen() instanceof Karte, 100);
            double[] ziel = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).start());
            if (ziel == null || ziel[0] != 40.5 || ziel[1] != -30.5) {
                throw new AssertionError("hmap karte 40 -31 öffnete die Karte mit dem Ziel " + Arrays.toString(ziel));
            }
            // Die Welt des Tests hat keinen geladenen Satz; mit dem Testsatz wie die übrigen Schritte steht das Ziel in der Mitte.
            Path baum = Bilder.testsatz();
            context.runOnClient(mc -> mc.gui.setScreen(new Karte(Satz.lies(baum)).bei(40.5, -30.5)));
            context.waitTicks(5);
            Karte.Marke m = marke(context, tod);
            double[] mitte = context.computeOnClient(mc -> new double[] {mc.getWindow().getGuiScaledWidth() / 2.0,
                mc.getWindow().getGuiScaledHeight() / 2.0});
            if (Math.abs(m.x() - mitte[0]) > 1 || Math.abs(m.y() - mitte[1]) > 1) {
                throw new AssertionError("Karte bei 40 -31: der Todespunkt steht bei " + m.x() + "," + m.y() + ", nicht in der Mitte");
            }
            // Mitten im ✕ die helle Farbe, auf 12 je Kanal genau; das ✕ ist bei GS 2 nur gut einen Pixel breit, also ±1 Pixel.
            Path bild = context.takeScreenshot(TestScreenshotOptions.of("tod").disableCounterPrefix());
            boolean kreuz = false;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    kreuz |= linieDa(bild, k, m.x() + dx / (double) k, m.y() + dy / (double) k, Minimap.TOD_KREUZ);
                }
            }
            if (!kreuz) {
                throw new AssertionError("Mitten im Todespunkt fehlt das helle ✕");
            }
            // Am Todespunkt nur Teleport und Löschen: kein „Ebene …“, er bleibt in Base (Review von #143), und keine Koordinaten, er
            // bleibt, wo er ist (mod#151).
            List<String> menue = eintraegeBei(context, maus, k, m.x(), m.y());
            List<String> soll = context.computeOnClient(mc -> List.of(Component.translatable("heroicmap.karte.teleport", tod.x(), tod.z()).getString(),
                    Component.translatable("heroicmap.karte.tod_loeschen").getString()));
            if (!menue.equals(soll)) {
                throw new AssertionError("Menü am Todespunkt: " + menue + " statt " + soll);
            }
            eintrag(context, maus, k, m.x(), m.y(), "heroicmap.karte.tod_loeschen");
            if (context.computeOnClient(mc -> Wegpunkte.INSTANZ.tod()) != null) {
                throw new AssertionError("„Todespunkt löschen“ liess ihn stehen");
            }
            context.runOnClient(mc -> mc.gui.setScreen(null));
            // Mit dem Schalter aus kein Todespunkt. Erst an eine freie Stelle: Am Ort des Respawns kann ein Wegpunkt eines
            // früheren Schritts liegen, und dort gäbe es auch ohne Schalter keinen.
            context.runOnClient(mc -> Minimap.INSTANZ.setzeTodespunkt(false));
            server.runCommand("tp @a 44.5 -60 -36.5");
            context.waitFor(mc -> mc.player != null && Math.abs(mc.player.getX() - 44.5) < 0.1, 200);
            stirb(context, server);
            if (context.computeOnClient(mc -> Wegpunkte.INSTANZ.tod()) != null) {
                throw new AssertionError("Mit „Todespunkt“ aus setzte ein Tod einen");
            }
        } finally {
            context.runOnClient(mc -> {
                Minimap.INSTANZ.setzeTodespunkt(true);
                Wegpunkte.INSTANZ.loescheTod();
                mc.gui.setScreen(null);
            });
            server.runCommand("tp @a 0.5 -30 0.5 0 90");
            context.waitTicks(5);
        }
    }

    /** Der Spieler stirbt und steht wieder auf; danach ist kein Schirm offen. */
    private static void stirb(ClientGameTestContext context, TestServerContext server) {
        server.runCommand("kill @a");
        context.waitFor(mc -> mc.player != null && mc.player.isDeadOrDying(), 200);
        context.waitTicks(2);
        context.runOnClient(mc -> mc.player.respawn());
        context.waitFor(mc -> mc.player != null && !mc.player.isDeadOrDying(), 200);
        context.runOnClient(mc -> mc.gui.setScreen(null));
        context.waitTicks(5);
    }

    /**
     * Eigene Ebenen wie ein Spieler (mod#130): Rechtsklick auf einen Wegpunkt, „Ebene …“, die Wahl zeigt „• Base“ und „Neue
     * Ebene …“; darüber eine Ebene „Farmen“ angelegt, liegt er in ihr. In der Liste der Karte schaltet ihr Knopf sie aus, und
     * der Wegpunkt fehlt auf der Karte, wieder an steht er da. Im Menü „Ebenen …“ benennt ✎ sie um, ✕ löscht sie, und der
     * Wegpunkt liegt wieder in Base. Siehe docs/wegpunkte.md, „Eigene Ebenen“.
     */
    private static void eigeneEbenen(ClientGameTestContext context, TestInput maus, int k) {
        int punkte = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size());
        double[] ort = context.computeOnClient(mc -> new double[] {60, mc.getWindow().getGuiScaledHeight() - 60});
        frei(context, new double[][] {ort});
        int[] block = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).blockUnter(ort[0], ort[1]));
        try {
            context.runOnClient(mc -> Wegpunkte.INSTANZ.setze(mc.level.dimension().identifier().toString(), block[0], block[1]));
            context.waitTicks(2);
            Wegpunkte.Punkt p = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().getLast());
            Karte.Marke m = marke(context, p);
            eintrag(context, maus, k, m.x(), m.y(), "heroicmap.karte.ebene");
            String neue = context.computeOnClient(mc -> Component.translatable("heroicmap.karte.ebene_neu").getString());
            List<String> wahl = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eintraege().stream().map(Component::getString).toList());
            if (!wahl.equals(List.of("• Base", neue))) {
                throw new AssertionError("„Ebene …“ zeigt " + wahl);
            }
            klickeEintrag(context, maus, k, neue);
            maus.typeChars("Farmen");
            maus.pressKey(InputConstants.KEY_RETURN);
            context.waitTicks(2);
            int id = context.computeOnClient(mc -> Wegpunkte.INSTANZ.ebenen().stream().filter(e -> e.name().equals("Farmen"))
                    .mapToInt(Wegpunkte.EigeneEbene::id).findFirst().orElse(-1));
            if (id < 0 || context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().getLast().ebene()) != id) {
                throw new AssertionError("„Neue Ebene …“ legte keine Ebene „Farmen“ mit dem Wegpunkt an: " + id);
            }

            // Der Knopf der Ebene in der Liste der Karte; ist sie zu, erst „Ebenen +“.
            context.waitTicks(2);
            if (context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eigenerSchalter("Farmen")) == null) {
                String knoepfe = context.computeOnClient(mc -> mc.gui.screen().children().stream()
                        .filter(c -> c instanceof AbstractWidget).map(c -> ((AbstractWidget) c).getMessage().getString()).toList()
                        + " Ebenen " + Wegpunkte.INSTANZ.ebenen());
                double[] e;
                try {
                    e = knopf(context, "heroicmap.karte.ebenen");
                } catch (AssertionError f) {
                    throw new AssertionError("Kein Schalter „Farmen“ und kein „Ebenen +“: " + knoepfe);
                }
                spielerKlick(context, maus, k, e[0], e[1]);
            }
            for (boolean an : new boolean[] {false, true}) {
                double[] s = context.computeOnClient(mc -> {
                    AbstractWidget w = ((Karte) mc.gui.screen()).eigenerSchalter("Farmen");
                    if (w == null) {
                        throw new AssertionError("Kein Schalter „Farmen“ in der Liste der Karte");
                    }
                    return new double[] {w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0};
                });
                spielerKlick(context, maus, k, s[0], s[1]);
                context.waitTicks(2);
                boolean da = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).marken().stream()
                        .anyMatch(x -> x.punkt() != null && x.punkt().x() == block[0] && x.punkt().z() == block[1]));
                if (context.computeOnClient(mc -> Wegpunkte.INSTANZ.an(id)) != an || da != an) {
                    throw new AssertionError("Schalter „Farmen“ auf " + (an ? "an" : "aus") + ": Ebene an " + context.computeOnClient(
                            mc -> Wegpunkte.INSTANZ.an(id)) + ", Wegpunkt auf der Karte " + da);
                }
            }

            // Die Liste wieder zu, wie die folgenden Schritte sie erwarten; offen merkt sie sich die Karte.
            double[] zu = knopf(context, "heroicmap.karte.ebenen_zu");
            spielerKlick(context, maus, k, zu[0], zu[1]);

            // Im Menü „Ebenen …“: ✎ benennt um, ✕ löscht.
            context.runOnClient(mc -> mc.gui.setScreen(new EbenenMenue(mc.gui.screen())));
            context.waitTicks(2);
            context.takeScreenshot(TestScreenshotOptions.of("ebenen-menue").disableCounterPrefix());
            spielerKlick(context, maus, k, mitteVon(context, "✎")[0], mitteVon(context, "✎")[1]);
            String feld = context.computeOnClient(mc -> ((EbenenMenue) mc.gui.screen()).feld());
            if (!"Farmen".equals(feld)) {
                throw new AssertionError("✎ zeigt „" + feld + "“");
            }
            leeren(maus, 6);
            maus.typeChars("Höfe");
            maus.pressKey(InputConstants.KEY_RETURN);
            context.waitTicks(2);
            if (!"Höfe".equals(context.computeOnClient(mc -> Wegpunkte.INSTANZ.ebene(id).name()))) {
                throw new AssertionError("Umbenannt heisst die Ebene " + context.computeOnClient(mc -> Wegpunkte.INSTANZ.ebene(id).name()));
            }
            double[] x = mitteVon(context, "✕");
            spielerKlick(context, maus, k, x[0], x[1]);
            if (context.computeOnClient(mc -> Wegpunkte.INSTANZ.ebene(id)) != null
                    || context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().getLast().ebene()) != Wegpunkte.BASE) {
                throw new AssertionError("✕ liess die Ebene stehen oder den Wegpunkt nicht in Base");
            }
            context.runOnClient(mc -> mc.gui.screen().onClose());
            context.waitTicks(2);

            // Base aus (Review von #143): Sie steht in der Liste der Karte, auch allein. „Wegpunkt setzen“ sagt, dass Neues in der
            // ausgeschalteten Base landet; noch einmal auf demselben Block sagt es, dass dort ein verborgener Wegpunkt liegt.
            context.runOnClient(mc -> Wegpunkte.INSTANZ.schalteEbene(Wegpunkte.BASE, false));
            context.waitTicks(2);
            if (context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eigenerSchalter("Base")) == null) {
                double[] e = knopf(context, "heroicmap.karte.ebenen");
                spielerKlick(context, maus, k, e[0], e[1]);
            }
            if (context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eigenerSchalter("Base")) == null) {
                throw new AssertionError("Base aus steht nicht in der Liste der Karte");
            }
            double[] zweiter = {ort[0] + 30, ort[1]};
            frei(context, new double[][] {zweiter});
            int vorher = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size());
            eintrag(context, maus, k, zweiter[0], zweiter[1], "heroicmap.karte.wegpunkt");
            String hinweisBase = hinweis(context);
            eintrag(context, maus, k, zweiter[0], zweiter[1], "heroicmap.karte.wegpunkt");
            String hinweisVerborgen = hinweis(context);
            int nachher = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size());
            if (nachher != vorher + 1
                    || !hinweisBase.equals(context.computeOnClient(mc -> Component.translatable("heroicmap.karte.base_aus").getString()))
                    || !hinweisVerborgen.equals(context.computeOnClient(mc -> Component.translatable("heroicmap.karte.wegpunkt_verborgen").getString()))) {
                throw new AssertionError("Base aus: " + (nachher - vorher) + " neue Wegpunkte, Hinweise „" + hinweisBase + "“ und „" + hinweisVerborgen + "“");
            }
            // Die Liste wieder zu, solange es den Knopf noch gibt, dann Base wieder an.
            double[] zu2 = knopf(context, "heroicmap.karte.ebenen_zu");
            spielerKlick(context, maus, k, zu2[0], zu2[1]);
            context.runOnClient(mc -> Wegpunkte.INSTANZ.schalteEbene(Wegpunkte.BASE, true));
            context.waitTicks(2);
        } finally {
            context.runOnClient(mc -> {
                Wegpunkte.INSTANZ.schalteEbene(Wegpunkte.BASE, true);
                List.copyOf(Wegpunkte.INSTANZ.punkte()).subList(punkte, Wegpunkte.INSTANZ.punkte().size()).forEach(Wegpunkte.INSTANZ::loesche);
                Wegpunkte.INSTANZ.ebenen().stream().skip(1).map(Wegpunkte.EigeneEbene::id).toList().forEach(Wegpunkte.INSTANZ::loescheEbene);
                if (!(mc.gui.screen() instanceof Karte)) {
                    mc.gui.screen().onClose();
                }
            });
            context.waitTicks(2);
        }
    }

    /**
     * Eine eigene Ebene als Code teilen (mod#130): Im Menü „Ebenen …“ legt „⧉“ in der Zeile von „Farmen“ ihren Code mit einem
     * Wegpunkt in die Zwischenablage und meldet es. Ebene und Wegpunkt gelöscht, legt „Code einfügen“ sie wieder an, mit dem
     * Wegpunkt auf demselben Block. Steht etwas anderes in der Zwischenablage, sagt es das und legt nichts an. Siehe
     * docs/wegpunkte.md, „Ebenen teilen“.
     */
    private static void ebenenTeilen(ClientGameTestContext context, TestInput maus, int k) {
        int punkte = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size());
        Object karte = context.computeOnClient(mc -> mc.gui.screen());
        // Die Zwischenablage ist die echte des Rechners; was dort stand, kommt am Ende zurück (Review von #146).
        String vorher = context.computeOnClient(mc -> mc.keyboardHandler.getClipboard());
        try {
            context.runOnClient(mc -> {
                int id = Wegpunkte.INSTANZ.neueEbene("Farmen");
                Wegpunkte.INSTANZ.setze(mc.level.dimension().identifier().toString(), 77, -88);
                Wegpunkte.INSTANZ.ordne(Wegpunkte.INSTANZ.punkte().getLast(), id);
                mc.gui.setScreen(new EbenenMenue((net.minecraft.client.gui.screens.Screen) karte));
            });
            context.waitTicks(2);
            // „⧉“ steht in jeder Zeile einer eigenen Ebene; der in der Zeile von „Farmen“.
            double[] kopieren = context.computeOnClient(mc -> {
                AbstractWidget zeile = mc.gui.screen().children().stream().filter(c -> c instanceof AbstractWidget w
                        && w.getMessage().getString().startsWith("Farmen")).map(c -> (AbstractWidget) c).findFirst()
                        .orElseThrow(() -> new AssertionError("Keine Zeile „Farmen“"));
                return mc.gui.screen().children().stream().filter(c -> c instanceof AbstractWidget w && w.getMessage().getString().equals("⧉")
                        && w.getY() == zeile.getY()).map(c -> (AbstractWidget) c).map(w -> new double[] {w.getX() + w.getWidth() / 2.0,
                            w.getY() + w.getHeight() / 2.0}).findFirst().orElseThrow(() -> new AssertionError("Kein „⧉“ bei „Farmen“"));
            });
            spielerKlick(context, maus, k, kopieren[0], kopieren[1]);
            String code = context.computeOnClient(mc -> mc.keyboardHandler.getClipboard());
            String meldung = context.computeOnClient(mc -> ((EbenenMenue) mc.gui.screen()).meldung());
            if (code == null || !code.startsWith(EbenenCode.VORSILBE) || meldung == null || !meldung.contains("Farmen")) {
                throw new AssertionError("„⧉“: Zwischenablage „" + code + "“, Meldung „" + meldung + "“");
            }

            // Ebene und Wegpunkt weg, dann „Code einfügen“.
            context.runOnClient(mc -> {
                Wegpunkte.INSTANZ.ebenen().stream().filter(e -> e.name().equals("Farmen")).map(Wegpunkte.EigeneEbene::id).toList()
                        .forEach(Wegpunkte.INSTANZ::loescheEbene);
                Wegpunkte.INSTANZ.loesche(Wegpunkte.INSTANZ.punkte().getLast());
                mc.gui.setScreen(new EbenenMenue((net.minecraft.client.gui.screens.Screen) karte));
            });
            context.waitTicks(2);
            double[] einfuegen = mitteVon(context, context.computeOnClient(mc -> Component.translatable("heroicmap.ebenen.einfuegen").getString()));
            spielerKlick(context, maus, k, einfuegen[0], einfuegen[1]);
            String wieder = context.computeOnClient(mc -> {
                Wegpunkte.EigeneEbene e = Wegpunkte.INSTANZ.ebenen().stream().filter(x -> x.name().equals("Farmen")).findFirst().orElse(null);
                Wegpunkte.Punkt p = Wegpunkte.INSTANZ.punkte().getLast();
                return e == null ? "keine Ebene „Farmen“" : p.x() == 77 && p.z() == -88 && p.ebene() == e.id() && !p.angeheftet() ? null : "Wegpunkt " + p;
            });
            if (wieder != null) {
                throw new AssertionError("„Code einfügen“: " + wieder + "; Meldung „"
                        + context.computeOnClient(mc -> ((EbenenMenue) mc.gui.screen()).meldung()) + "“");
            }

            // Kein Code in der Zwischenablage: eine Meldung, keine Ebene.
            int ebenen = context.computeOnClient(mc -> Wegpunkte.INSTANZ.ebenen().size());
            context.runOnClient(mc -> mc.keyboardHandler.setClipboard("Hallo"));
            context.waitTicks(2);
            einfuegen = mitteVon(context, context.computeOnClient(mc -> Component.translatable("heroicmap.ebenen.einfuegen").getString()));
            spielerKlick(context, maus, k, einfuegen[0], einfuegen[1]);
            String keiner = context.computeOnClient(mc -> ((EbenenMenue) mc.gui.screen()).meldung());
            if (!context.computeOnClient(mc -> Component.translatable("heroicmap.ebenen.code.kein_code").getString()).equals(keiner)
                    || context.computeOnClient(mc -> Wegpunkte.INSTANZ.ebenen().size()) != ebenen) {
                throw new AssertionError("„Code einfügen“ ohne Code: Meldung „" + keiner + "“");
            }
            context.takeScreenshot(TestScreenshotOptions.of("ebenen-teilen").disableCounterPrefix());
        } finally {
            context.runOnClient(mc -> {
                List.copyOf(Wegpunkte.INSTANZ.punkte()).subList(punkte, Wegpunkte.INSTANZ.punkte().size()).forEach(Wegpunkte.INSTANZ::loesche);
                Wegpunkte.INSTANZ.ebenen().stream().skip(1).map(Wegpunkte.EigeneEbene::id).toList().forEach(Wegpunkte.INSTANZ::loescheEbene);
                mc.gui.setScreen((net.minecraft.client.gui.screens.Screen) karte);
                mc.keyboardHandler.setClipboard(vorher == null ? "" : vorher);
            });
            context.waitTicks(2);
        }
    }

    /** Klickt im offenen Menü der Karte den Eintrag mit diesem Text, ohne es neu zu öffnen. */
    private static void klickeEintrag(ClientGameTestContext context, TestInput maus, int k, String text) {
        List<String> alle = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eintraege().stream().map(Component::getString).toList());
        int zeile = alle.indexOf(text);
        if (zeile < 0) {
            throw new AssertionError("Kein Eintrag „" + text + "“ in " + alle);
        }
        int[] lage = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).menueLage());
        warte250();
        maus.setCursorPos((lage[0] + 6) * k, (lage[1] + zeile * 14 + 6) * k);
        context.waitTick();
        maus.pressMouse(LINKS);
        context.waitTicks(2);
    }

    /** Die Mitte des Knopfs mit genau diesem Text auf dem Schirm. */
    private static double[] mitteVon(ClientGameTestContext context, String text) {
        return context.computeOnClient(mc -> mc.gui.screen().children().stream()
                .filter(c -> c instanceof AbstractWidget w && w.getMessage().getString().equals(text)).map(c -> (AbstractWidget) c)
                .map(w -> new double[] {w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0}).findFirst()
                .orElseThrow(() -> new AssertionError("Kein Knopf „" + text + "“")));
    }

    /** Löscht {@code n} Zeichen vor dem Cursor im Eingabefeld, mit der Rücktaste. */
    private static void leeren(TestInput maus, int n) {
        for (int i = 0; i < n; i++) {
            maus.pressKey(InputConstants.KEY_BACKSPACE);
        }
    }

    private static int hex(ClientGameTestContext context, int form) {
        return context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().get(form).hex());
    }

    /**
     * Der Regler „Schleier“ im Untermenü „Einstellungen …“ wie von Hand: ein Klick ans rechte Ende gibt 16 Blöcke, einer ans
     * linke 4, einer in die Mitte 10; „Fertig“ speichert ihn. Siehe docs/wegpunkte.md, „Schleier“.
     */
    private static void schleierRegler(ClientGameTestContext context, TestInput maus, int k) {
        context.runOnClient(mc -> mc.gui.setScreen(new Anzeige(null)));
        context.waitTicks(2);
        int[] r = context.computeOnClient(mc -> {
            for (Object kind : mc.gui.screen().children()) {
                if (kind instanceof Anzeige.SchleierRegler w) {
                    return new int[] {w.getX(), w.getY(), w.getWidth(), w.getHeight()};
                }
            }
            throw new AssertionError("Kein Regler „Schleier“ im Untermenü");
        });
        double y = r[1] + r[3] / 2.0;
        for (double[] klick : new double[][] {{r[0] + r[2] - 2, Schleier.MAX_HOEHE}, {r[0] + 2, Schleier.HOEHE}, {r[0] + r[2] / 2.0, 10}}) {
            spielerKlick(context, maus, k, klick[0], y);
            int ist = context.computeOnClient(mc -> Minimap.INSTANZ.schleier());
            if (ist != (int) klick[1]) {
                throw new AssertionError("Klick auf den Regler bei " + klick[0] + ": " + ist + " Blöcke statt " + (int) klick[1]);
            }
        }
        context.runOnClient(mc -> {
            mc.gui.setScreen(null);
            Minimap neu = new Minimap();
            neu.lies(HeroicMap.einstellungen());
            if (neu.schleier() != 10) {
                throw new AssertionError("Beim Schliessen nicht gespeichert: " + neu.schleier());
            }
            Minimap.INSTANZ.setzeSchleier(Schleier.HOEHE);
        });
    }

    private static String name(ClientGameTestContext context, int form) {
        return context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().get(form).name());
    }

    /** Wo die Maus für das Spiel steht, in Einheiten des GUI, nicht gerundet. */
    private static double[] mausImGui(ClientGameTestContext context) {
        return context.computeOnClient(mc -> new double[] {
            mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth(),
            mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight()});
    }

    /** Der Griff der eigenen Form Nummer {@code form} auf dem Schirm, wie zuletzt gezeichnet. */
    private static double[] griff(ClientGameTestContext context, int form) {
        return context.computeOnClient(mc -> {
            Wegpunkte.EigeneForm f = Wegpunkte.INSTANZ.eigeneFormen().get(form);
            Karte.GriffAn g = ((Karte) mc.gui.screen()).griffe().stream().filter(x -> x.form().equals(f)).findFirst()
                    .orElseThrow(() -> new AssertionError("Kein Griff von " + f + " auf dem Schirm"));
            return new double[] {g.x(), g.y()};
        });
    }

    /** Liegt im Bildschirmfoto bei (x, y) in Einheiten des GUI die Farbe einer eigenen Linie, auf 12 je Kanal genau? */
    private static boolean linieDa(Path bild, int k, double x, double y, int farbe) {
        try {
            int p = ImageIO.read(bild.toFile()).getRGB((int) Math.round(x * k), (int) Math.round(y * k));
            for (int s = 0; s < 24; s += 8) {
                if (Math.abs((p >> s & 0xFF) - (farbe >> s & 0xFF)) > 12) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Die Ecke {@code index} der eigenen Form Nummer {@code form} auf dem Schirm, wie zuletzt gezeichnet. */
    private static double[] ecke(ClientGameTestContext context, int form, int index) {
        return context.computeOnClient(mc -> {
            Wegpunkte.EigeneForm f = Wegpunkte.INSTANZ.eigeneFormen().get(form);
            Karte.EckeAn e = ((Karte) mc.gui.screen()).ecken().stream().filter(x -> x.form().equals(f) && x.index() == index).findFirst()
                    .orElseThrow(() -> new AssertionError("Keine Ecke " + index + " von " + f + " auf dem Schirm"));
            return new double[] {e.x(), e.y()};
        });
    }

    /** Wirft, wenn bei einem der Orte eine Marke liegt; der Test braucht dort freie Karte. */
    private static void frei(ClientGameTestContext context, double[][] orte) {
        for (double[] o : orte) {
            boolean frei = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).marken().stream()
                    .noneMatch(m -> Math.abs(m.x() - o[0]) < 10 && Math.abs(m.y() - o[1]) < 10));
            if (!frei) {
                throw new AssertionError("Eine Marke liegt bei " + o[0] + "," + o[1] + "; der Test braucht dort freie Karte");
            }
        }
    }

    /**
     * Zwei Wegpunkte im Tempo eines Spielers (mod#112): ein Klick auf A, 220 ms später einer auf B. Das Spiel meldet den
     * zweiten als Doppelklick, nach Zeit und Taste; die Karte zählt ihn nur nah am vorigen Druckpunkt. So kommt B in
     * die Mitte, und A wird nicht angeheftet. Siehe docs/wegpunkte.md, „Bedienung“.
     */
    private static void zweiMarkenImTempo(ClientGameTestContext context, TestInput maus, int k) {
        int breite = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
        int hoehe = context.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
        double mx = breite / 2.0, my = hoehe / 2.0;
        double[][] orte = {{mx - 110, my + 60}, {mx - 50, my + 60}};
        int vorher = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().size());
        for (double[] o : orte) {
            eintrag(context, maus, k, o[0], o[1], "heroicmap.karte.wegpunkt");
        }
        List<Wegpunkte.Punkt> neu = context.computeOnClient(mc -> List.copyOf(Wegpunkte.INSTANZ.punkte().subList(vorher, vorher + 2)));
        try {
            warte250();
            Karte.Marke a = marke(context, neu.get(0)), b = marke(context, neu.get(1));
            long druck = tempoKlick(context, maus, k, a.x(), a.y(), 0, 0);
            long zweiter = tempoKlick(context, maus, k, b.x(), b.y(), druck, 220);
            warte250();
            context.waitTicks(2);
            Karte.Marke mitte = marke(context, neu.get(1));
            if (angeheftet(context, neu.get(0)) || Math.abs(mitte.x() - mx) > 1 || Math.abs(mitte.y() - my) > 1) {
                throw new AssertionError("Klick auf B " + (zweiter - druck) + " ms nach A: A angeheftet " + angeheftet(context, neu.get(0))
                        + ", B bei " + mitte.x() + "," + mitte.y() + " statt in der Mitte " + mx + "," + my);
            }
        } finally {
            context.runOnClient(mc -> neu.forEach(Wegpunkte.INSTANZ::loesche));
            context.waitTicks(2);
        }
    }

    /**
     * Ein schneller Linksklick im Tempo eines Spielers auf (x, y): hinfahren, {@code pauseMs} nach dem vorigen Drücken
     * {@code vorig} drücken, 1 bis 2 Pixel zittern und im selben Frame loslassen. Gibt die Zeit des Drückens. Der Zeiger
     * kommt einen Tick vor dem Drücken an: Die Bewegung verbucht das Spiel erst im nächsten Frame, mit gedrückter Taste
     * als Ziehen. Nur dieser eine Tick liegt zwischen zwei Klicks, so reicht die Zeit auch auf einem langsamen Runner.
     * Mit {@code pauseMs} 0, oder wenn die Pause schon um ist, drückt er gleich nach dem Ankommen.
     */
    private static long tempoKlick(ClientGameTestContext context, TestInput maus, int k, double x, double y, long vorig, long pauseMs) {
        maus.setCursorPos(x * k, y * k);
        context.waitTick();
        long jetzt = Util.getMillis();
        // Kommt der Zeiger auf einem langsamen Rechner später an, drückt er gleich; anfügen muss der Klick trotzdem.
        schlafe(Math.max(0, vorig + pauseMs - jetzt));
        long druck = Util.getMillis();
        maus.holdMouse(LINKS);
        maus.setCursorPos(x * k + ZITTERN[1][0], y * k + ZITTERN[1][1]);
        maus.releaseMouse(LINKS);
        return druck;
    }

    /** Lässt die Tafel der grossen Region aufgehen, wo der Zeiger steht: Frage als gesendet, Antwort abgelegt. */
    /** Fährt den Zeiger auf „Optionen“ unten rechts, wo er auf kein Ziel zeigt, bis eine offene Tafel zu ist. */
    private static void tafelZu(ClientGameTestContext context, TestInput maus, int k) {
        int[] schirm = context.computeOnClient(mc -> new int[] {mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
        fahre(context, maus, k, schirm[0] - 30, schirm[1] - 14);
        for (int i = 0; i < 100 && context.computeOnClient(mc -> ((Karte) mc.gui.screen()).tafelOffen()); i++) {
            context.waitTick();
        }
        if (context.computeOnClient(mc -> ((Karte) mc.gui.screen()).tafelOffen())) {
            throw new AssertionError("Die Tafel ging über „Optionen“ nach 5 s nicht zu");
        }
    }

    private static void tafelDerRegion(ClientGameTestContext context) {
        context.waitTicks(3);
        context.runOnClient(mc -> Tafeln.INSTANZ.antwort(Tafeln.Antwort.lies(LAND)));
        context.waitTicks(3);
    }

    /** Fährt den Zeiger wie die Hand in acht Schritten von dort, wo er steht, nach (x, y) in Einheiten des GUI, dann ruht er. */
    private static void fahre(ClientGameTestContext context, TestInput maus, int k, double x, double y) {
        double[] von = context.computeOnClient(mc -> new double[] {mc.mouseHandler.xpos(), mc.mouseHandler.ypos()});
        for (int i = 1; i <= 8; i++) {
            maus.setCursorPos(von[0] + (x * k - von[0]) * i / 8, von[1] + (y * k - von[1]) * i / 8);
            context.waitTick();
        }
        context.waitTicks(2);
    }

    /**
     * Ein Linksklick wie von Hand: hinfahren, ruhen, drücken, zwei Ticks halten und dabei 1 bis 2 Pixel
     * zittern ({@link #zittere}), loslassen; danach eine Pause, die keinen Doppelklick mit dem nächsten zulässt.
     * Zwei Ticks bleiben auch auf einem langsamen Runner unter den 500 ms, ab denen ein Wegpunkt an der Maus hängt.
     */
    private static void spielerKlick(ClientGameTestContext context, TestInput maus, int k, double x, double y) {
        warte250();
        fahre(context, maus, k, x, y);
        maus.holdMouse(LINKS);
        zittere(context, maus, x * k, y * k, 2);
        maus.releaseMouse(LINKS);
        context.waitTicks(2);
    }

    /** Zittert wie {@link #zittere} um (px, py), Tick um Tick, bis die Uhr {@code bis} zeigt, in ms von {@code Util.getMillis}. */
    private static void zittereBis(ClientGameTestContext context, TestInput maus, double px, double py, long bis) {
        while (Util.getMillis() < bis) {
            zittere(context, maus, px, py, 1);
        }
    }

    /**
     * Zittert {@code ticks} Ticks lang um (px, py) in Pixeln des Fensters, zwei Bewegungen je Tick, nie weiter als
     * 2 Pixel weg ({@link #ZITTERN}): Der Weg summiert sich, der Abstand zum Druckpunkt nicht. Siehe docs/wegpunkte.md, „Bedienung“.
     */
    private static void zittere(ClientGameTestContext context, TestInput maus, double px, double py, int ticks) {
        for (int i = 0; i < 2 * ticks; i++) {
            int[] z = ZITTERN[i % ZITTERN.length];
            maus.setCursorPos(px + z[0], py + z[1]);
            if (i % 2 == 1) {
                context.waitTick();
            }
        }
    }

    /**
     * Rechtsklick bei (x, y), dann ein Klick auf den Eintrag mit diesem Text; die Einträge stehen 14 Einheiten untereinander.
     * Gibt die Zeit des Klicks auf den Eintrag.
     */
    /**
     * Die Einträge des Menüs nach einem Rechtsklick wie von Hand bei (x, y). Ein Linksklick 10 Einheiten links darüber, ausserhalb
     * des Menüs, das dort beginnt, schliesst es wieder und tut sonst nichts.
     */
    private static List<String> eintraegeBei(ClientGameTestContext context, TestInput maus, int k, double x, double y) {
        warte250();
        maus.setCursorPos(x * k, y * k);
        context.waitTick();
        maus.pressMouse(RECHTS);
        context.waitTicks(2);
        List<String> alle = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eintraege().stream().map(Component::getString).toList());
        warte250();
        maus.setCursorPos((x - 10) * k, (y - 10) * k);
        context.waitTick();
        maus.pressMouse(LINKS);
        context.waitTicks(2);
        return alle;
    }

    private static long eintrag(ClientGameTestContext context, TestInput maus, int k, double x, double y, String schluessel) {
        warte250();
        maus.setCursorPos(x * k, y * k);
        context.waitTick();
        maus.pressMouse(RECHTS);
        context.waitTicks(2);
        String text = context.computeOnClient(mc -> Component.translatable(schluessel).getString());
        List<String> alle = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eintraege().stream().map(Component::getString).toList());
        int zeile = alle.indexOf(text);
        if (zeile < 0) {
            throw new AssertionError("Kein Eintrag „" + text + "“ in " + alle);
        }
        // Am Rand des Schirms rückt das Menü nach innen; geklickt wird, wo es steht.
        int[] lage = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).menueLage());
        maus.setCursorPos((lage[0] + 6) * k, (lage[1] + zeile * 14 + 6) * k);
        context.waitTick();
        long druck = Util.getMillis();
        maus.pressMouse(LINKS);
        context.waitTicks(2);
        return druck;
    }

    /**
     * Die Liste der Ebenen wie ein Spieler: Kommen Ebenen, während die Karte offen ist, erscheint der Knopf
     * „Ebenen“; ein Klick klappt die Liste auf. Ein Klick auf „An“/„Aus“ schaltet die Ebene aus, ein zweiter
     * wieder an, und ein Doppelklick darauf schaltet nur zweimal; angeheftet wird dabei nichts. Ein Doppelklick
     * auf die Überschrift heftet alles mit id an, ein zweiter löst es; die Ebene bleibt an. Zuletzt klappt der
     * Knopf die Liste wieder zu. Siehe docs/vollbildkarte.md, „Ebenen“ (mod#103).
     */
    private static void ebenen(ClientGameTestContext context, TestInput maus, int k) {
        context.runOnClient(mc -> {
            Ebenen.INSTANZ.empfange(JsonParser.parseString("""
                    {"v":1,"typ":"ebenen","jetzt":1,"ebenen":[{"id":"test:liste","name":{"de":"Liste","en":"Liste"},"visible":true,"version":"1"},
                      {"id":"test:leer","name":{"de":"Leer","en":"Leer"},"visible":false,"version":"1"}]}""").getAsJsonObject());
            Ebenen.INSTANZ.teil(Ebenen.Teil.lies("""
                    {"v":1,"typ":"ebene","jetzt":1,"id":"test:liste","version":"1","teil":1,"teile":1,"objects":[
                      {"type":"circle","id":"see","center":[0,0],"radius":2,"fill":"#40C04060"},
                      {"type":"circle","center":[6,0],"radius":2},
                      {"type":"pin","id":"hafen","at":[0,6],"name":"Hafen"}]}"""));
        });
        context.waitTicks(3);
        klicke(context, maus, k, knopf(context, "heroicmap.karte.ebenen"), false);
        if (!context.computeOnClient(mc -> Kartenlage.ebenenOffen(Downloads.weltOrdner()))) {
            throw new AssertionError("Der Knopf „Ebenen“ merkte die offene Liste nicht");
        }
        double[] zeile = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).zeile("test:liste"));
        if (zeile == null) {
            throw new AssertionError("Keine Zeile der Ebene in der Liste");
        }
        double[] knopf = {zeile[0], zeile[1]}, ueberschrift = {zeile[2], zeile[3]};
        for (boolean an : new boolean[] {false, true}) {
            klicke(context, maus, k, knopf, false);
            if (ebeneAn(context) != an || !keinsAngeheftet(context)) {
                throw new AssertionError("Ein Klick auf „An/Aus“: Ebene an " + ebeneAn(context) + " statt " + an
                        + ", nichts angeheftet " + keinsAngeheftet(context));
            }
        }
        // Ein Doppelklick auf den Knopf schaltet nur, aus und wieder an; er heftet nichts an (mod#103).
        klicke(context, maus, k, knopf, true);
        if (!ebeneAn(context) || !keinsAngeheftet(context)) {
            throw new AssertionError("Doppelklick auf „An/Aus“: Ebene an " + ebeneAn(context) + ", nichts angeheftet " + keinsAngeheftet(context));
        }
        for (boolean ganz : new boolean[] {true, false}) {
            klicke(context, maus, k, ueberschrift, true);
            boolean alles = context.computeOnClient(mc -> Wegpunkte.INSTANZ.angeheftet("test:liste", "see")
                    && Wegpunkte.INSTANZ.nadelAngeheftet("test:liste", "hafen"));
            if (!ebeneAn(context) || (ganz ? !alles : !keinsAngeheftet(context))) {
                throw new AssertionError("Doppelklick auf die Überschrift: an " + ebeneAn(context) + ", Kreis und Nadel angeheftet "
                        + alles + " statt " + ganz);
            }
        }
        klicke(context, maus, k, knopf(context, "heroicmap.karte.ebenen_zu"), false);
        if (context.computeOnClient(mc -> Kartenlage.ebenenOffen(Downloads.weltOrdner()))) {
            throw new AssertionError("Der Knopf klappte die Liste nicht zu");
        }
        context.runOnClient(mc -> Ebenen.INSTANZ.leeren());
        context.waitTicks(2);
    }

    private static boolean keinsAngeheftet(ClientGameTestContext context) {
        return context.computeOnClient(mc -> !Wegpunkte.INSTANZ.angeheftet("test:liste", "see")
                && !Wegpunkte.INSTANZ.nadelAngeheftet("test:liste", "hafen"));
    }

    private static boolean ebeneAn(ClientGameTestContext context) {
        return context.computeOnClient(mc -> Ebenen.INSTANZ.alle().stream().filter(e -> e.id().equals("test:liste")).allMatch(Ebenen.INSTANZ::an));
    }

    /** Ein Klick oder ein Doppelklick bei (x, y) in Einheiten des GUI, nach einer Pause, die keinen Doppelklick mit dem vorigen zulässt. */
    private static void klicke(ClientGameTestContext context, TestInput maus, int k, double[] ort, boolean doppelt) {
        warte250();
        maus.setCursorPos(ort[0] * k, ort[1] * k);
        context.waitTick();
        if (doppelt) {
            doppelklick(maus);
        } else {
            maus.pressMouse(LINKS);
        }
        context.waitTicks(2);
    }

    /** Abstand der zwei Drücke eines Doppelklicks nach der Uhr: deutlich unter den 250 ms des Spiels. */
    static final long DOPPELKLICK_MS = 50;

    /**
     * Ein Doppelklick an der Stelle der Maus: zwei Drücke {@link #DOPPELKLICK_MS} nach der Uhr auseinander, ohne Tick dazwischen;
     * der Client steht, solange der Test schläft. Ein Tick dazwischen dauerte unter Last auch länger als 250 ms, dann war es keiner.
     */
    private static void doppelklick(TestInput maus) {
        maus.pressMouse(LINKS);
        schlafe(DOPPELKLICK_MS);
        maus.pressMouse(LINKS);
    }

    /** Die Mitte des Knopfs mit dieser Aufschrift auf dem offenen Schirm, in Einheiten des GUI. */
    private static double[] knopf(ClientGameTestContext context, String schluessel) {
        return context.computeOnClient(mc -> {
            String text = Component.translatable(schluessel).getString();
            for (Object kind : mc.gui.screen().children()) {
                if (kind instanceof AbstractWidget w && w.getMessage().getString().equals(text)) {
                    return new double[] {w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0};
                }
            }
            throw new AssertionError("Kein Knopf „" + text + "“");
        });
    }

    /** Die Karte weit wegziehen, dann „Zum Spieler“: Der Spieler liegt in der Mitte. Siehe docs/vollbildkarte.md, „Bedienung“. */
    private static void zumSpieler(ClientGameTestContext context, TestInput maus, int k) {
        int breite = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
        int hoehe = context.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
        warte250();
        maus.setCursorPos(breite / 2.0 * k, hoehe / 2.0 * k);
        context.waitTick();
        maus.holdMouse(LINKS);
        context.waitTick();
        for (int i = 0; i < 5; i++) {
            maus.moveCursor(-15 * k, 10 * k);
            context.waitTick();
        }
        maus.releaseMouse(LINKS);
        warte250();
        double[] k2 = knopf(context, "heroicmap.karte.zum_spieler");
        maus.setCursorPos(k2[0] * k, k2[1] * k);
        context.waitTick();
        maus.pressMouse(LINKS);
        context.waitTicks(2);
        double[] mitte = mitte(context);
        double[] soll = context.computeOnClient(mc -> {
            int scale = ((Karte) mc.gui.screen()).satz().scale();
            return new double[] {mc.player.getX() * scale, mc.player.getZ() * scale};
        });
        if (Math.abs(mitte[0] - soll[0]) > 1e-6 || Math.abs(mitte[1] - soll[1]) > 1e-6) {
            throw new AssertionError("„Zum Spieler“: Mitte " + mitte[0] + "," + mitte[1] + " statt " + soll[0] + "," + soll[1]);
        }
    }

    /** „Optionen …“ öffnet das Menü von /hmap, „Fertig“ führt zurück auf die Karte an dieselbe Stelle. */
    private static void optionen(ClientGameTestContext context, TestInput maus, int k) {
        double[] vorher = mitte(context);
        warte250();
        double[] o = knopf(context, "heroicmap.karte.optionen");
        maus.setCursorPos(o[0] * k, o[1] * k);
        context.waitTick();
        maus.pressMouse(LINKS);
        context.waitTicks(2);
        if (!context.computeOnClient(mc -> mc.gui.screen() instanceof Einstellungen)) {
            throw new AssertionError("„Optionen …“ öffnete das Menü nicht");
        }
        double[] fertig = context.computeOnClient(mc -> {
            String text = net.minecraft.network.chat.CommonComponents.GUI_DONE.getString();
            for (Object kind : mc.gui.screen().children()) {
                if (kind instanceof AbstractWidget w && w.getMessage().getString().equals(text)) {
                    return new double[] {w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0};
                }
            }
            throw new AssertionError("Kein Knopf „Fertig“ im Menü");
        });
        maus.setCursorPos(fertig[0] * k, fertig[1] * k);
        context.waitTick();
        maus.pressMouse(LINKS);
        context.waitTicks(2);
        if (!context.computeOnClient(mc -> mc.gui.screen() instanceof Karte) || !Arrays.equals(mitte(context), vorher)) {
            throw new AssertionError("„Fertig“ führte nicht an dieselbe Stelle der Karte zurück");
        }
    }

    /**
     * Linke Taste auf einem Wegpunkt, mit einer Hand, die 1 bis 2 Pixel zittert: Nach 250 ms hängt er noch nicht, nach
     * 700 ms an der Maus, nach der Uhr gemessen; nach dem Loslassen liegt er auf dem Block darunter. Ein zweites Mal mit
     * Escape bleibt er, wo er war. Siehe docs/wegpunkte.md, „Bedienung“.
     */
    private static void verschieben(ClientGameTestContext context, TestInput maus, int k) {
        // Ein Wegpunkt links über der Mitte, wo nichts anderes liegt: 40 und 32 Einheiten, bei scale 4 also 10 und 8 Blöcke.
        Wegpunkte.Punkt punkt = context.computeOnClient(mc -> {
            double[] m = ((Karte) mc.gui.screen()).blickMitte();
            int x = (int) Math.floor(m[0] / 4) - 10, z = (int) Math.floor(m[1] / 4) - 8;
            Wegpunkte.INSTANZ.setze(mc.level.dimension().identifier().toString(), x, z);
            return Wegpunkte.INSTANZ.punkte().getLast();
        });
        context.waitTick();
        warte250();
        Karte.Marke m = marke(context, punkt);
        maus.setCursorPos(m.x() * k, m.y() * k);
        context.waitTick();
        long druck = Util.getMillis();
        maus.holdMouse(LINKS);
        zittereBis(context, maus, m.x() * k, m.y() * k, druck + 250);
        if (context.computeOnClient(mc -> ((Karte) mc.gui.screen()).haengt()) != null) {
            maus.releaseMouse(LINKS);
            throw new AssertionError("Nach " + (Util.getMillis() - druck) + " ms gehalten hängt der Wegpunkt schon an der Maus");
        }
        zittereBis(context, maus, m.x() * k, m.y() * k, druck + 700);
        if (context.computeOnClient(mc -> ((Karte) mc.gui.screen()).haengt()) == null) {
            maus.releaseMouse(LINKS);
            throw new AssertionError("Nach " + (Util.getMillis() - druck) + " ms gehalten, mit 1 bis 2 Pixeln Zittern, hängt der Wegpunkt nicht an der Maus");
        }
        for (int i = 0; i < 4; i++) {
            maus.moveCursor(10 * k, 0);
            context.waitTick();
        }
        maus.releaseMouse(LINKS);
        context.waitTicks(2);
        Wegpunkte.Punkt neu = context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().getLast());
        if (Math.abs(neu.x() - (punkt.x() + 10)) > 1 || Math.abs(neu.z() - punkt.z()) > 1 || neu.farbe() != punkt.farbe()) {
            throw new AssertionError("Verschoben: " + neu + " statt rund 10 Blöcke östlich von " + punkt);
        }
        // Noch einmal, aber mit Escape: Er bleibt, und das Loslassen danach tut nichts.
        warte250();
        Karte.Marke n = marke(context, neu);
        maus.setCursorPos(n.x() * k, n.y() * k);
        context.waitTick();
        long zweiter = Util.getMillis();
        maus.holdMouse(LINKS);
        zittereBis(context, maus, n.x() * k, n.y() * k, zweiter + 700);
        // Unter Last kommt der Frame, der ihn anhängt, später; ohne ihn schlösse Escape die Karte. Wie lange das Halten
        // dauert, prüft schon der erste Durchgang.
        for (int i = 0; i < 40 && context.computeOnClient(mc -> ((Karte) mc.gui.screen()).haengt()) == null; i++) {
            zittere(context, maus, n.x() * k, n.y() * k, 1);
        }
        if (context.computeOnClient(mc -> ((Karte) mc.gui.screen()).haengt()) == null) {
            maus.releaseMouse(LINKS);
            throw new AssertionError("Nach " + (Util.getMillis() - zweiter) + " ms gehalten hängt der Wegpunkt für Escape nicht an der Maus");
        }
        maus.moveCursor(0, 30 * k);
        context.waitTick();
        maus.pressKey(InputConstants.KEY_ESCAPE);
        context.waitTick();
        maus.releaseMouse(LINKS);
        context.waitTicks(2);
        if (!context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().getLast()).equals(neu)
                || !context.computeOnClient(mc -> mc.gui.screen() instanceof Karte)) {
            throw new AssertionError("Escape beim Verschieben liess den Wegpunkt nicht, wo er war, oder schloss die Karte");
        }
        warte250();
    }

    /** Eine Stufe gröber, schliessen und wieder öffnen: Mitte, Stufe und Lupe wie vorher. Siehe docs/vollbildkarte.md, „Lage merken“. */
    private static void lageGemerkt(ClientGameTestContext context, TestInput maus, int k) {
        int breite = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
        int hoehe = context.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
        maus.setCursorPos(breite / 2.0 * k, hoehe / 2.0 * k);
        context.waitTick();
        maus.scroll(-1);
        context.waitTicks(2);
        double[] mitte = mitte(context);
        int[] stufe = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).stufe());
        maus.pressKey(InputConstants.KEY_ESCAPE);
        context.waitTicks(2);
        Path baum = Bilder.testsatz();
        context.runOnClient(mc -> mc.gui.setScreen(new Karte(Satz.lies(baum))));
        context.waitTicks(5);
        int[] nachher = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).stufe());
        if (!Arrays.equals(mitte(context), mitte) || !Arrays.equals(nachher, stufe)) {
            throw new AssertionError("Wieder geöffnet: Mitte " + Arrays.toString(mitte(context)) + " Stufe " + Arrays.toString(nachher)
                    + " statt " + Arrays.toString(mitte) + " " + Arrays.toString(stufe));
        }
        // Zurück auf die feinste Stufe für die Schritte danach.
        maus.scroll(1);
        context.waitTicks(2);
    }

    private static boolean kreisAngeheftet(ClientGameTestContext context) {
        return context.computeOnClient(mc -> Wegpunkte.INSTANZ.angeheftet("test:anheften", "see"));
    }

    private static double[] mitte(ClientGameTestContext context) {
        return context.computeOnClient(mc -> ((Karte) mc.gui.screen()).blickMitte());
    }

    private static boolean angeheftet(ClientGameTestContext context, Wegpunkte.Punkt punkt) {
        return context.computeOnClient(mc -> Wegpunkte.INSTANZ.punkte().stream()
                .anyMatch(p -> p.x() == punkt.x() && p.z() == punkt.z() && p.angeheftet()));
    }

    /**
     * Das Aussehen an der Minimap im Menü, mit Handklicks: Ein Klick auf den Spieler schaltet die Darstellung, einer auf
     * eine Marke den Rahmen, auch mit Drehen an, wo das Menü genordet bleibt, mit Verzierungen aus und ohne Rahmen; ein Klick daneben nichts. Ziehen von
     * einer Marke verschiebt nur, der Griff ändert nur die Grösse, der Zoom nur den Zoom. Im Untermenü dasselbe.
     * Siehe docs/minimap.md, „Aussehen an der Minimap“.
     */
    private static void aussehen(ClientGameTestContext context, TestInput maus, int k) {
        context.runOnClient(mc -> {
            Minimap.INSTANZ.setzeDrehen(false);
            Minimap.INSTANZ.setzeVerzierungen(true);
            Minimap.INSTANZ.setzeSkin("holz");
            Minimap.INSTANZ.setzeDarstellung(Minimap.Darstellung.KOPF);
        });
        context.waitTicks(2);
        Minimap.Rahmen r = rahmen(context);
        double cx = r.x() + r.seite() / 2.0, cy = r.y() + r.seite() / 2.0;
        spielerKlick(context, maus, k, cx, cy);
        aussehenIst(context, Minimap.Darstellung.PFEIL, "holz", "Klick auf den Spieler");
        if (!rahmen(context).equals(r)) {
            throw new AssertionError("Der Klick auf den Spieler verschob die Minimap: " + r + " → " + rahmen(context));
        }
        // Zwischen Spieler und Marke liegt nur die Karte.
        spielerKlick(context, maus, k, cx, r.y() + r.seite() / 4.0);
        aussehenIst(context, Minimap.Darstellung.PFEIL, "holz", "Klick auf die Karte daneben");
        // N oben auf der Mitte der Bänder.
        spielerKlick(context, maus, k, cx, r.y() + baender(context) / 2.0);
        aussehenIst(context, Minimap.Darstellung.PFEIL, "papier", "Klick auf N");

        // Drehen an, mit Blick nach Süden: Im Menü steht die Minimap genordet still, oben bleibt N. Gedreht wanderten die Marken über den Griff.
        context.runOnClient(mc -> {
            Minimap.INSTANZ.setzeDrehen(true);
            mc.player.setYRot(0);
            mc.player.yRotO = 0;
        });
        context.waitTicks(2);
        Minimap.Rahmen g = rahmen(context);
        double[] oben = {g.x() + g.seite() / 2.0, g.y() + baender(context) / 2.0};
        int ziel = context.computeOnClient(mc -> Minimap.INSTANZ.ziel(oben[0], oben[1]));
        if (ziel != 0) {
            throw new AssertionError("Im Menü dreht die Minimap: Mit Blick nach Süden ist oben nicht N, sondern " + ziel);
        }
        spielerKlick(context, maus, k, oben[0], oben[1]);
        aussehenIst(context, Minimap.Darstellung.PFEIL, "kompass", "Klick auf N, Drehen an, im Menü genordet");

        // Verzierungen aus: Das Menü zeigt die Marken halb, W links.
        context.runOnClient(mc -> {
            Minimap.INSTANZ.setzeDrehen(false);
            Minimap.INSTANZ.setzeVerzierungen(false);
        });
        context.waitTicks(2);
        Minimap.Rahmen v = rahmen(context);
        spielerKlick(context, maus, k, v.x() + baender(context) / 2.0, v.y() + v.seite() / 2.0);
        aussehenIst(context, Minimap.Darstellung.PFEIL, "uhr", "Klick auf die halbe Marke W, Verzierungen aus");

        // Ohne Rahmen: die Marken von „grau“ halb auf dem Umriss, 1,5 Einheiten ausserhalb; O rechts.
        context.runOnClient(mc -> Minimap.INSTANZ.setzeSkin(Skin.OHNE));
        context.waitTicks(2);
        Minimap.Rahmen o = rahmen(context);
        spielerKlick(context, maus, k, o.x() + o.seite() + 1.5, o.y() + o.seite() / 2.0);
        aussehenIst(context, Minimap.Darstellung.PFEIL, Skin.BIOM, "Klick auf die halbe Marke O, ohne Rahmen");

        // Von N aus gezogen: Die Minimap folgt um 20 Einheiten, der Rahmen bleibt.
        context.runOnClient(mc -> Minimap.INSTANZ.setzeVerzierungen(true));
        context.waitTicks(2);
        Minimap.Rahmen z = rahmen(context);
        warte250();
        ziehe(context, maus, LINKS, z.x() + z.seite() / 2.0, z.y() + baender(context) / 2.0, 0, 2 * k);
        Minimap.Rahmen gezogen = rahmen(context);
        aussehenIst(context, Minimap.Darstellung.PFEIL, Skin.BIOM, "Von N aus gezogen");
        if (gezogen.x() != z.x() || Math.abs(gezogen.y() - (z.y() + 20)) > 1) {
            throw new AssertionError("Von N aus gezogen: " + z + " → " + gezogen);
        }

        // Der Griff ändert nur die Grösse.
        int[] schirm = context.computeOnClient(mc -> new int[] {mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()});
        int ecke = Minimap.griffEcke(gezogen, schirm[0], schirm[1]);
        double[] griff = context.computeOnClient(mc -> Minimap.INSTANZ.ecken(Minimap.INSTANZ.skinJetzt(), gezogen)[ecke]);
        warte250();
        ziehe(context, maus, LINKS, griff[0], griff[1], (ecke & 1) == 0 ? -k : k, (ecke & 2) == 0 ? -k : k);
        Minimap.Rahmen groesser = rahmen(context);
        aussehenIst(context, Minimap.Darstellung.PFEIL, Skin.BIOM, "Am Griff gezogen");
        if (Math.abs(groesser.seite() - (gezogen.seite() + 10)) > 1) {
            throw new AssertionError("Am Griff gezogen: " + gezogen + " → " + groesser);
        }

        // Der Zoom schaltet nur den Zoom.
        int zoom = context.computeOnClient(mc -> Minimap.INSTANZ.zoom());
        double[] knopf = context.computeOnClient(mc -> {
            String text = Component.translatable("heroicmap.menue.zoom").getString();
            for (Object kind : mc.gui.screen().children()) {
                if (kind instanceof AbstractWidget w && w.getMessage().getString().startsWith(text)) {
                    return new double[] {w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0};
                }
            }
            throw new AssertionError("Kein Knopf „Zoom“ im Menü");
        });
        spielerKlick(context, maus, k, knopf[0], knopf[1]);
        aussehenIst(context, Minimap.Darstellung.PFEIL, Skin.BIOM, "Klick auf Zoom");
        if (context.computeOnClient(mc -> Minimap.INSTANZ.zoom()) == zoom) {
            throw new AssertionError("Der Klick auf Zoom schaltete den Zoom nicht");
        }

        // Im Untermenü „Einstellungen …“ ebenso, ohne die Knöpfe „Spieler“ und „Rahmen“.
        context.runOnClient(mc -> mc.gui.setScreen(new Anzeige(mc.gui.screen())));
        context.waitTicks(2);
        Minimap.Rahmen u = rahmen(context);
        spielerKlick(context, maus, k, u.x() + u.seite() / 2.0, u.y() + u.seite() / 2.0);
        aussehenIst(context, Minimap.Darstellung.DURCHSICHTIG, Skin.BIOM, "Untermenü, Klick auf den Spieler");
        spielerKlick(context, maus, k, u.x() + u.seite() / 2.0, u.y() + u.seite() - baender(context) / 2.0);
        aussehenIst(context, Minimap.Darstellung.DURCHSICHTIG, "grau", "Untermenü, Klick auf S");
        warte250();
    }

    /** Darstellung und Rahmen wie erwartet, sonst ein Fehler mit {@code was}. */
    private static void aussehenIst(ClientGameTestContext context, Minimap.Darstellung darstellung, String skin, String was) {
        Object[] ist = context.computeOnClient(mc -> new Object[] {Minimap.INSTANZ.darstellung(), Minimap.INSTANZ.skin()});
        if (ist[0] != darstellung || !skin.equals(ist[1])) {
            throw new AssertionError(was + ": " + ist[0] + ", " + ist[1] + " statt " + darstellung + ", " + skin);
        }
    }

    /** Die Breite der Bänder des Rahmens, den die Minimap gerade zeichnet. */
    private static int baender(ClientGameTestContext context) {
        return context.computeOnClient(mc -> Minimap.INSTANZ.skinJetzt().baender());
    }

    /**
     * „Hierher teleportieren“, siehe docs/vollbildkarte.md, „Teleportieren“: über Wasser in einem Chunk, den der Client
     * hat, auf die Oberfläche, nicht auf den Grund; in einen Chunk, den weder Client noch Server geladen haben, mit dem
     * Boden der Karte, den der Mod genau einmal per HTTP holt, auf den Boden der flachen Welt. Dazu die Korrektur: mit
     * veralteten Höhen, ohne Daten in einen Chunk, den der Server hat, und einen, den er nicht hat, und im Nether.
     */
    private static void teleport(ClientGameTestContext context, TestServerContext server, TestInput maus, int k) {
        // Ein Becken aus Glas, das Wasser darin 6 hoch: der Grund bei y = -61, die Oberfläche im Block -55.
        server.runCommand("fill 20 -60 20 26 -55 26 glass");
        server.runCommand("fill 21 -60 21 25 -55 25 water");
        context.waitTicks(5);
        // Der Boden der Karte: überall -61, der oberste Block der flachen Welt, für die Region um (2000, 2000).
        byte[] werte = new byte[2 * 512 * 512];
        for (int i = 0; i < werte.length; i += 2) {
            werte[i] = (byte) -61;
            werte[i + 1] = (byte) (-61 >> 8);
        }
        byte[] gepackt = pack(werte);
        // Veraltet für die Region um (4000, 4000): -63 nennt den Boden zwei Blöcke zu tief.
        for (int i = 0; i < werte.length; i += 2) {
            werte[i] = (byte) -63;
            werte[i + 1] = (byte) (-63 >> 8);
        }
        byte[] veraltet = pack(werte);
        AtomicInteger abrufe = new AtomicInteger();
        HttpServer http;
        try {
            http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        http.createContext("/tiles/", austausch -> {
            abrufe.incrementAndGet();
            String pfad = austausch.getRequestURI().getPath();
            byte[] antwort = pfad.equals("/tiles/ground/3.3.bin") ? gepackt : pfad.equals("/tiles/ground/7.7.bin") ? veraltet : null;
            austausch.sendResponseHeaders(antwort != null ? 200 : 404, antwort != null ? antwort.length : -1);
            if (antwort != null) {
                austausch.getResponseBody().write(antwort);
            }
            austausch.close();
        });
        http.start();
        Path baum = Bilder.testsatz();
        try {
            // Der Testsatz nennt den Boden in der Ablage einer Wurzel, wie der Renderer ab 0.9.0.
            Path datei = baum.resolve("4").resolve("map.json");
            JsonObject karte = JsonParser.parseString(Files.readString(datei, StandardCharsets.UTF_8)).getAsJsonObject();
            karte.addProperty("ground", "../ground/{x}.{z}.bin");
            karte.addProperty("groundCell", 1);
            karte.addProperty("minY", -64);
            karte.addProperty("maxY", 319);
            Files.writeString(datei, karte.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            context.runOnClient(mc -> Symbole.INSTANZ.basis(JsonParser.parseString("{\"url\":\"http://127.0.0.1:" + http.getAddress().getPort()
                    + "/tiles\"}").getAsJsonObject(), InetAddress.getLoopbackAddress()));
            // Über Wasser: der erste freie Block über der Oberfläche, -54, nicht über dem Grund, -60.
            teleportiere(context, maus, k, baum, 23, 23);
            context.waitFor(mc -> Math.abs(mc.player.getX() - 23.5) < 0.01 && Math.abs(mc.player.getZ() - 23.5) < 0.01, 200);
            double wasser = context.computeOnClient(mc -> mc.player.getY());
            if (Math.abs(wasser - -54) > 0.5) {
                throw new AssertionError("Teleport über Wasser: y " + wasser + " statt rund -54, der Oberfläche");
            }
            // In die Ferne: Den Chunk hat der Client nicht, die Höhe kommt aus der Karte.
            // Den Cache direkt, nicht Teleport.chunk: Die Probe soll nicht am geprüften Code hängen.
            if (context.computeOnClient(mc -> mc.level.getChunkSource().getChunk(2000 >> 4, 2000 >> 4, ChunkStatus.FULL, false) != null)) {
                throw new AssertionError("Der Chunk bei (2000, 2000) ist schon geladen; der Test braucht einen fremden");
            }
            teleportiere(context, maus, k, baum, 2000, 2000);
            for (int i = 0; !context.computeOnClient(mc -> Math.abs(mc.player.getX() - 2000.5) < 0.01
                    && mc.level.getChunkSource().getChunk(2000 >> 4, 2000 >> 4, ChunkStatus.FULL, false) != null); i++) {
                if (i == 600) {
                    throw new AssertionError("Teleport in die Ferne: nicht angekommen, der Spieler steht bei "
                            + context.computeOnClient(mc -> mc.player.position()));
                }
                context.waitTick();
            }
            context.waitTicks(20);
            double y = context.computeOnClient(mc -> mc.player.getY());
            boolean steht = context.computeOnClient(mc -> mc.player.onGround() && mc.level.getBlockState(new BlockPos(2000, -60, 2000)).isAir()
                    && mc.level.getBlockState(new BlockPos(2000, -61, 2000)).is(Blocks.GRASS_BLOCK));
            if (Math.abs(y - -60) > 0.01 || !steht || abrufe.get() != 1) {
                throw new AssertionError("Teleport in die Ferne: y " + y + " statt -60, auf dem Gras mit Luft an den Füssen " + steht
                        + ", Abrufe " + abrufe.get() + " statt 1");
            }
            korrektur(context, server, maus, k, baum);
            nether(context, server, maus, k, baum);
        } finally {
            http.stop(0);
            context.runOnClient(mc -> mc.gui.setScreen(null));
        }
    }

    /**
     * Die Korrektur nach dem Landen ohne Decke. Der Spieler steht vorher in einem Loch auf dem Grundgestein, bei y = -63;
     * diese Höhe nimmt der Mod ohne Daten, und dort steckt er am Ziel in der Erde.
     */
    private static void korrektur(ClientGameTestContext context, TestServerContext server, TestInput maus, int k, Path baum) {
        // Veraltete Höhen der Karte: Landung in der Erde, dann auf den Boden.
        double[] veraltet = landet(context, maus, k, baum, 4000, "Veraltete Höhen");
        if (veraltet[1] > -61.9) {
            throw new AssertionError("Veraltete Höhen: nie in der Erde gelandet, tiefstes y " + veraltet[1]);
        }
        // Ohne Daten, den Chunk hat der Server: Er sucht den Boden, ohne Schaden und ohne die feste Höhe -63. Bis der Chunk
        // beim Client ist, fällt der Spieler dort einen Tick, 0,08 Blöcke.
        server.runCommand("forceload add 1000 1000");
        server.waitFor(s -> s.overworld().isLoaded(new BlockPos(1000, -60, 1000)), 600);
        insLoch(context, server);
        float leben = context.computeOnClient(mc -> mc.player.getHealth());
        double[] geladen = landet(context, maus, k, baum, 1000, "Ohne Daten, Chunk beim Server");
        if (geladen[0] < leben || geladen[1] < -61) {
            throw new AssertionError("Ohne Daten, Chunk beim Server: Leben " + leben + " → " + geladen[0] + ", tiefstes y " + geladen[1]);
        }
        server.runCommand("forceload remove all");
        // Ohne Daten, den Chunk hat der Server nicht: Er lehnt ab, nach 5 Ticks die Höhe des Spielers, dann die Korrektur.
        insLoch(context, server);
        leben = context.computeOnClient(mc -> mc.player.getHealth());
        double[] fremd = landet(context, maus, k, baum, 3000, "Ohne Daten, Chunk nicht beim Server");
        if (fremd[1] > -62.9) {
            throw new AssertionError("Ohne Daten, Chunk nicht beim Server: nie auf der festen Höhe -63, tiefstes y " + fremd[1]);
        }
        System.out.println("Heroic Map Gametest: Teleport ohne Daten, Chunk nicht beim Server, Schaden " + (leben - fremd[0]));
    }

    /** Ein Loch unter dem Spieler bis auf das Grundgestein, er steht darin bei y = -63. */
    private static void insLoch(ClientGameTestContext context, TestServerContext server) {
        server.runCommand("execute as @p at @s run fill ~ -63 ~ ~ -61 ~ air");
        server.runCommand("execute as @p at @s run tp @s ~ -63 ~");
        context.waitFor(mc -> Math.abs(mc.player.getY() - -63) < 0.01 && mc.player.onGround(), 100);
    }

    /**
     * Teleportiert über die Karte nach (x, x), in einen Chunk, den der Client nicht hat, und wartet, bis der Spieler dort
     * auf dem Gras steht, Luft an den Füssen, und 20 Ticks so bleibt. Gibt das geringste Leben und das tiefste y am Ziel
     * zurück.
     */
    private static double[] landet(ClientGameTestContext context, TestInput maus, int k, Path baum, int x, String fall) {
        if (context.computeOnClient(mc -> mc.level.getChunkSource().getChunk(x >> 4, x >> 4, ChunkStatus.FULL, false) != null)) {
            throw new AssertionError(fall + ": Der Chunk ist schon beim Client; der Test braucht einen fremden");
        }
        teleportiere(context, maus, k, baum, x, x);
        double[] min = {Double.MAX_VALUE, Double.MAX_VALUE};
        for (int i = 0, ruhig = 0; ruhig < 20; i++) {
            double[] jetzt = context.computeOnClient(mc -> new double[] {mc.player.getHealth(), mc.player.getY(),
                    Math.abs(mc.player.getX() - (x + 0.5)) < 0.01 && Math.abs(mc.player.getZ() - (x + 0.5)) < 0.01 ? 1 : 0,
                    mc.player.onGround() && mc.level.getBlockState(new BlockPos(x, -60, x)).isAir()
                            && mc.level.getBlockState(new BlockPos(x, -61, x)).is(Blocks.GRASS_BLOCK) ? 1 : 0});
            min[0] = Math.min(min[0], jetzt[0]);
            if (jetzt[2] == 1) {
                min[1] = Math.min(min[1], jetzt[1]);
            }
            ruhig = jetzt[2] == 1 && jetzt[3] == 1 && Math.abs(jetzt[1] - -60) < 0.01 ? ruhig + 1 : 0;
            if (i == 600) {
                throw new AssertionError(fall + ": nicht auf dem Boden, der Spieler steht bei " + context.computeOnClient(mc -> mc.player.position()));
            }
            context.waitTick();
        }
        return min;
    }

    /**
     * Im Nether: Ohne Daten sucht der Server den obersten Block, das Dach aus Grundgestein; die Korrektur setzt den
     * Spieler unter die Decke, auf den freien Platz nahe seiner Höhe. Start und Ziel sind Hohlräume aus Luft auf
     * Obsidian, 3 × 3 und 3 hoch, die Füsse bei y = 60.
     */
    private static void nether(ClientGameTestContext context, TestServerContext server, TestInput maus, int k, Path baum) {
        int ziel = 600;
        for (int a : new int[] {0, ziel}) {
            server.runCommand("execute in minecraft:the_nether run forceload add " + a + " " + a);
        }
        server.waitFor(s -> s.getLevel(Level.NETHER).isLoaded(new BlockPos(0, 60, 0)) && s.getLevel(Level.NETHER).isLoaded(new BlockPos(ziel, 60, ziel)), 1200);
        for (int a : new int[] {0, ziel}) {
            server.runCommand("execute in minecraft:the_nether run fill " + (a - 1) + " 59 " + (a - 1) + " " + (a + 1) + " 59 " + (a + 1) + " obsidian");
            server.runCommand("execute in minecraft:the_nether run fill " + (a - 1) + " 60 " + (a - 1) + " " + (a + 1) + " 62 " + (a + 1) + " air");
        }
        server.runCommand("execute in minecraft:the_nether run tp @p 0.5 60 0.5");
        context.waitFor(mc -> mc.level.dimension() == Level.NETHER && Math.abs(mc.player.getY() - 60) < 0.01 && mc.player.onGround(), 600);
        if (context.computeOnClient(mc -> mc.level.getChunkSource().getChunk(ziel >> 4, ziel >> 4, ChunkStatus.FULL, false) != null)) {
            throw new AssertionError("Nether: Der Chunk des Ziels ist schon beim Client");
        }
        teleportiere(context, maus, k, baum, ziel, ziel);
        for (int i = 0, ruhig = 0; ruhig < 20; i++) {
            boolean steht = context.computeOnClient(mc -> Math.abs(mc.player.getX() - (ziel + 0.5)) < 0.01 && Math.abs(mc.player.getZ() - (ziel + 0.5)) < 0.01
                    && Math.abs(mc.player.getY() - 60) < 0.01 && mc.player.onGround());
            ruhig = steht ? ruhig + 1 : 0;
            if (i == 600) {
                throw new AssertionError("Nether: nicht unter der Decke auf dem Obsidian, der Spieler steht bei " + context.computeOnClient(mc -> mc.player.position()));
            }
            context.waitTick();
        }
        server.runCommand("execute in minecraft:the_nether run forceload remove all");
        // Zurück in die Oberwelt, auf das Gras; die Schritte danach rechnen dort.
        server.runCommand("execute in minecraft:overworld run tp @p 0.5 -60 0.5");
        context.waitFor(mc -> mc.level.dimension() == Level.OVERWORLD && Math.abs(mc.player.getY() - -60) < 0.01 && mc.player.onGround(), 600);
    }

    /**
     * „Im Browser öffnen“, siehe docs/vollbildkarte.md, „Im Browser öffnen“. Der Einzelspieler hat keinen Kanal zum Plugin; den
     * setzt der Test, ebenso die Frage und das Öffnen, so geht kein Browser auf. Die Antworten des Plugins gibt er wie das Netz
     * über {@code Downloads.empfange}. Geprüft: grau mit Grund bei altem Plugin und ohne Freigabe; mit Freigabe ein Klick von
     * Hand, die Frage, der Link aus der eigenen Basis mit dem Token im Fragment; eine Absage wegen der Rate; und das Token steht
     * nicht im Log.
     */
    private static void imBrowser(ClientGameTestContext context, TestInput maus, int k) {
        String token = "eyJ2IjoyfQ.Gametest_Token-0123456789";
        AtomicInteger fragen = new AtomicInteger();
        AtomicReference<URI> geoeffnet = new AtomicReference<>();
        var kanalVorher = Webkarte.kanal;
        var fragenVorher = Webkarte.fragen;
        var oeffnenVorher = Webkarte.oeffnen;
        Webkarte.kanal = () -> true;
        Webkarte.fragen = () -> {
            fragen.incrementAndGet();
            return true;
        };
        Webkarte.oeffnen = geoeffnet::set;
        try {
            context.runOnClient(mc -> {
                Webkarte.INSTANZ.leeren();
                Symbole.INSTANZ.basis(JsonParser.parseString("{\"url\":\"http://127.0.0.1:8082/welt/tiles\"}").getAsJsonObject(),
                        InetAddress.getLoopbackAddress());
                mc.gui.setScreen(new Karte(Satz.lies(Bilder.testsatz())));
            });
            context.waitTicks(3);
            // Ohne Antwort auf show mit dem Feld web: ein altes Plugin, grau.
            pruefeWeb(context, false, "heroicmap.web.alt");
            context.runOnClient(mc -> Downloads.INSTANZ.empfange("{\"v\":1,\"typ\":\"show\",\"erlaubt\":true,\"web\":false}"));
            context.waitTicks(2);
            pruefeWeb(context, false, "heroicmap.web.nicht_frei");
            context.runOnClient(mc -> Downloads.INSTANZ.empfange("{\"v\":1,\"typ\":\"show\",\"erlaubt\":true,\"web\":true}"));
            context.waitTicks(2);
            pruefeWeb(context, true, null);
            // Ein Klick von Hand auf den Knopf: die Frage geht hinaus, der Knopf wartet auf die Antwort.
            int[] knopf = context.computeOnClient(mc -> {
                AbstractWidget w = ((Karte) mc.gui.screen()).webKnopf();
                return new int[] {w.getX() + w.getWidth() / 2, w.getY() + w.getHeight() / 2};
            });
            spielerKlick(context, maus, k, knopf[0], knopf[1]);
            if (fragen.get() != 1) {
                throw new AssertionError("Im Browser öffnen: " + fragen.get() + " Fragen statt 1 nach dem Klick");
            }
            pruefeWeb(context, false, "heroicmap.web.laeuft");
            context.runOnClient(mc -> Downloads.INSTANZ.empfange("{\"v\":1,\"typ\":\"web\",\"erlaubt\":true,\"token\":\"" + token + "\"}"));
            for (int i = 0; geoeffnet.get() == null; i++) {
                if (i == 100) {
                    throw new AssertionError("Im Browser öffnen: nach der Antwort kein Link geöffnet");
                }
                context.waitTick();
            }
            String link = geoeffnet.get().toString();
            if (!link.equals("http://127.0.0.1:8082/welt/#anmelden=" + token)) {
                throw new AssertionError("Im Browser öffnen: Link " + link.replace(token, "<token>"));
            }
            pruefeWeb(context, true, null);
            // Noch einmal, und der Server sagt: erst in 12 s. Das steht unten links.
            spielerKlick(context, maus, k, knopf[0], knopf[1]);
            context.runOnClient(mc -> Downloads.INSTANZ.empfange(
                    "{\"v\":1,\"typ\":\"web\",\"erlaubt\":false,\"grund\":\"rate\",\"jetzt\":1760000000,\"wieder\":12}"));
            context.waitTicks(2);
            String hinweis = context.computeOnClient(mc -> {
                Component h = ((Karte) mc.gui.screen()).hinweis();
                return h == null ? "" : h.getString();
            });
            String erwartet = context.computeOnClient(mc -> Component.translatable("heroicmap.web.grund.rate", 12L).getString());
            if (fragen.get() != 2 || !hinweis.equals(erwartet)) {
                throw new AssertionError("Im Browser öffnen, Rate: " + fragen.get() + " Fragen, Hinweis „" + hinweis + "“ statt „" + erwartet + "“");
            }
            context.runOnClient(mc -> mc.gui.setScreen(null));
            context.waitTicks(5);
            // Das Token steht nirgends im Log.
            Path log = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");
            if (Files.readString(log, StandardCharsets.UTF_8).contains(token)) {
                throw new AssertionError("Im Browser öffnen: Das Token steht in latest.log");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            Webkarte.kanal = kanalVorher;
            Webkarte.fragen = fragenVorher;
            Webkarte.oeffnen = oeffnenVorher;
            context.runOnClient(mc -> Webkarte.INSTANZ.leeren());
        }
    }

    /**
     * Neue Version: Beim Start fragte der Gametest niemanden. Mit Schalter aus fragt der Mod nicht und zeigt nichts. Von Hand
     * im Untermenü angeschaltet fragt er einmal einen Server auf loopback, mit Spielversion und User-Agent; ein zweites An im
     * selben Start fragt nicht wieder. Dann steht oben links der Hinweis; ein Klick von Hand öffnet den Bestätigungsschirm mit
     * der festen Adresse, Abbrechen führt zurück, kein Browser. Siehe docs/vollbildkarte.md, „Neue Version“.
     */
    private static void neueVersion(ClientGameTestContext context, TestInput maus, int k) {
        List<String> anfragen = new CopyOnWriteArrayList<>();
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", t -> {
            anfragen.add(t.getRequestURI().getRawQuery() + " " + t.getRequestHeaders().getFirst("User-Agent"));
            byte[] koerper = "[{\"version_number\":\"100.0.0-rc.1\"},{\"version_number\":\"99.0.0\"}]".getBytes(StandardCharsets.UTF_8);
            t.sendResponseHeaders(200, koerper.length);
            try (OutputStream aus = t.getResponseBody()) {
                aus.write(koerper);
            }
        });
        server.start();
        try {
            String beimStart = context.computeOnClient(mc -> NeueVersion.basis + " " + NeueVersion.neu + " " + NeueVersion.gefragt.get());
            if (!beimStart.equals("null null false")) {
                throw new AssertionError("Neue Version beim Start der Gametests: " + beimStart);
            }
            context.runOnClient(mc -> {
                NeueVersion.basis = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v2/project/heroic-map/version");
                Minimap.INSTANZ.setzeNeueVersion(false);
                NeueVersion.frage();
                mc.gui.setScreen(new Karte(Satz.lies(Bilder.testsatz())));
            });
            context.waitTicks(20);
            boolean knopf = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).neueVersionKnopf() != null);
            if (!anfragen.isEmpty() || NeueVersion.gefragt.get() || knopf) {
                throw new AssertionError("Neue Version mit Schalter aus: " + anfragen.size() + " Fragen, Knopf " + knopf);
            }
            // Von Hand an im Untermenü; dann aus und wieder an, das fragt nicht noch einmal.
            context.runOnClient(mc -> mc.gui.setScreen(new Anzeige(null)));
            context.waitTicks(2);
            int[] schalter = context.computeOnClient(mc -> {
                String name = Component.translatable("heroicmap.menue.neue_version").getString();
                AbstractWidget w = mc.gui.screen().children().stream().filter(c -> c instanceof CycleButton<?> b
                        && b.getMessage().getString().startsWith(name)).map(c -> (AbstractWidget) c).findFirst().orElseThrow();
                return new int[] {w.getX() + w.getWidth() / 2, w.getY() + w.getHeight() / 2};
            });
            spielerKlick(context, maus, k, schalter[0], schalter[1]);
            for (int i = 0; NeueVersion.neu == null; i++) {
                if (i == 200) {
                    throw new AssertionError("Neue Version: keine Antwort nach dem Anschalten, " + anfragen.size() + " Fragen");
                }
                context.waitTick();
            }
            spielerKlick(context, maus, k, schalter[0], schalter[1]);
            spielerKlick(context, maus, k, schalter[0], schalter[1]);
            context.waitTicks(20);
            String erwartet = context.computeOnClient(mc -> NeueVersion.adresse(URI.create(""), SharedConstants.getCurrentVersion().name())
                    .getRawQuery() + " " + NeueVersion.agent(NeueVersion.eigene()));
            if (!anfragen.equals(List.of(erwartet)) || !"99.0.0".equals(NeueVersion.neu) || !Minimap.INSTANZ.neueVersion()) {
                throw new AssertionError("Neue Version: Fragen " + anfragen + " statt [" + erwartet + "], neu " + NeueVersion.neu);
            }
            // Oben links der Hinweis; ein Klick von Hand öffnet den Bestätigungsschirm mit der festen Adresse.
            context.runOnClient(mc -> mc.gui.setScreen(new Karte(Satz.lies(Bilder.testsatz()))));
            context.waitTicks(3);
            int[] hinweis = context.computeOnClient(mc -> {
                AbstractWidget w = ((Karte) mc.gui.screen()).neueVersionKnopf();
                if (w == null || w.getX() != 4 || w.getY() != 4
                        || !w.getMessage().getString().equals(Component.translatable("heroicmap.karte.neue_version", "99.0.0").getString())) {
                    throw new AssertionError("Neue Version: Hinweis " + (w == null ? "fehlt" : w.getMessage().getString() + " bei " + w.getX() + ", " + w.getY()));
                }
                return new int[] {w.getX() + w.getWidth() / 2, w.getY() + w.getHeight() / 2};
            });
            spielerKlick(context, maus, k, hinweis[0], hinweis[1]);
            context.waitFor(mc -> mc.gui.screen() instanceof ConfirmLinkScreen, 100);
            int[] abbrechen = context.computeOnClient(mc -> {
                if (!(mc.gui.screen() instanceof ConfirmLinkScreen s) || !s.getNarrationMessage().getString().contains(NeueVersion.SEITE.toString())) {
                    throw new AssertionError("Neue Version: nach dem Klick " + mc.gui.screen());
                }
                AbstractWidget w = s.children().stream().filter(c -> c instanceof AbstractWidget a
                        && (a.getMessage().getString().equals(CommonComponents.GUI_CANCEL.getString())
                        || a.getMessage().getString().equals(CommonComponents.GUI_NO.getString()))).map(c -> (AbstractWidget) c).findFirst().orElseThrow();
                return new int[] {w.getX() + w.getWidth() / 2, w.getY() + w.getHeight() / 2};
            });
            spielerKlick(context, maus, k, abbrechen[0], abbrechen[1]);
            context.waitFor(mc -> mc.gui.screen() instanceof Karte, 100);
            // Mit Schalter aus kein Hinweis, auch wenn die Antwort da ist.
            boolean zurueck = context.computeOnClient(mc -> mc.gui.screen() instanceof Karte);
            context.runOnClient(mc -> {
                Minimap.INSTANZ.setzeNeueVersion(false);
                mc.gui.setScreen(new Karte(Satz.lies(Bilder.testsatz())));
            });
            context.waitTicks(3);
            boolean ohne = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).neueVersionKnopf() == null);
            if (!zurueck || !ohne || anfragen.size() != 1) {
                throw new AssertionError("Neue Version: zurück auf der Karte " + zurueck + ", ohne Hinweis bei aus " + ohne + ", " + anfragen.size() + " Fragen");
            }
        } finally {
            server.stop(0);
            context.runOnClient(mc -> {
                mc.gui.setScreen(null);
                Minimap.INSTANZ.setzeNeueVersion(true);
                NeueVersion.basis = null;
                NeueVersion.neu = null;
            });
        }
    }

    /** Der Knopf „Im Browser öffnen“ geht oder ist aus mit dem Grund {@code schluessel}. */
    private static void pruefeWeb(ClientGameTestContext context, boolean geht, String schluessel) {
        // Den Knopf stellt erst der nächste Tick der Karte (Karte.webStand); darauf warten, höchstens 5 s, statt ihn sofort zu lesen.
        String[] stand = null;
        for (int i = 0; i <= 100; i++) {
            if (i > 0) {
                context.waitTick();
            }
            stand = context.computeOnClient(mc -> {
                Karte karte = (Karte) mc.gui.screen();
                Component aus = Webkarte.INSTANZ.aus(Symbole.INSTANZ.basis(), Util.getMillis());
                return new String[] {String.valueOf(karte.webKnopf() != null && karte.webKnopf().active), aus == null ? "" : aus.getString(),
                    schluessel == null ? "" : Component.translatable(schluessel).getString()};
            });
            if (Boolean.parseBoolean(stand[0]) == geht && stand[1].equals(stand[2])) {
                return;
            }
        }
        throw new AssertionError("Im Browser öffnen: an " + stand[0] + " statt " + geht + ", Grund „" + stand[1] + "“ statt „" + stand[2] + "“");
    }

    /** Öffnet die Karte über (x, z), Rechtsklick in die Mitte, ein Klick auf „Hierher teleportieren“; sie schliesst sich. */
    private static void teleportiere(ClientGameTestContext context, TestInput maus, int k, Path baum, int x, int z) {
        context.runOnClient(mc -> mc.gui.setScreen(new Karte(Satz.lies(baum))));
        context.waitTicks(5);
        context.runOnClient(mc -> ((Karte) mc.gui.screen()).zeige(x, z));
        context.waitTicks(2);
        int breite = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
        int hoehe = context.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
        warte250();
        fahre(context, maus, k, breite / 2.0, hoehe / 2.0);
        maus.pressMouse(RECHTS);
        context.waitTicks(2);
        int[] ziel = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).ziel());
        if (ziel == null || ziel[0] != x || ziel[1] != z) {
            throw new AssertionError("Das Menü steht nicht über (" + x + ", " + z + "): " + Arrays.toString(ziel));
        }
        // Das Menü steht an der Maus; der erste Eintrag liegt wenige Einheiten rechts unter ihr.
        maus.moveCursor(6 * k, 6 * k);
        context.waitTick();
        maus.pressMouse(LINKS);
        context.waitFor(mc -> mc.gui.screen() == null, 40);
    }

    private static byte[] pack(byte[] daten) {
        Deflater deflater = new Deflater();
        deflater.setInput(daten);
        deflater.finish();
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        byte[] puffer = new byte[8192];
        while (!deflater.finished()) {
            aus.write(puffer, 0, deflater.deflate(puffer));
        }
        deflater.end();
        return aus.toByteArray();
    }

    /** Das Spiel zählt einen Klick bis 250 ms nach dem letzten als Doppelklick, nach der Uhr, nicht nach Ticks. */
    private static void warte250() {
        schlafe(300);
    }

    /** Wartet {@code ms} nach der Uhr; der Client steht solange, er tickt nur im Takt mit dem Test. */
    private static void schlafe(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Die Marke des Wegpunkts auf der offenen Karte. */
    private static Karte.Marke marke(ClientGameTestContext context, Wegpunkte.Punkt punkt) {
        return context.computeOnClient(mc -> ((Karte) mc.gui.screen()).marken().stream()
                .filter(m -> m.punkt() != null && m.punkt().x() == punkt.x() && m.punkt().z() == punkt.z())
                .findFirst().orElseThrow(() -> new AssertionError("Keine Marke für " + punkt)));
    }

    /**
     * Bei 1280 × 720 und GUI-Massstab 3, 240 Einheiten hoch, liegt jeder Knopf des Menüs ganz auf
     * dem Schirm. Siehe docs/minimap.md, „Bedienung“.
     */
    private static void kleinerSchirm(ClientGameTestContext context) {
        int[] vorher = context.computeOnClient(mc -> new int[] {mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.options.guiScale().get()});
        // Die Wahl allein rechnet den Massstab nicht neu; das tut erst resizeGui, wie das Menü der Optionen.
        context.getInput().resizeWindow(1280, 720);
        context.runOnClient(mc -> {
            mc.options.guiScale().set(3);
            mc.resizeGui();
        });
        context.waitTicks(2);
        String fehler = null;
        for (boolean unter : new boolean[] {false, true}) {
            context.runOnClient(mc -> mc.gui.setScreen(unter ? new Anzeige(new Einstellungen()) : new Einstellungen()));
            context.waitTicks(2);
            String f = passt(context);
            fehler = fehler != null ? fehler : f == null ? null : (unter ? "Untermenü: " : "") + f;
        }
        // Bei GS 3 stehen „Auflösung“ und „Todespunkt“, je halb breit, ganz in ihrem Knopf (mod#129). Bei GS 4 sind die halben
        // Knöpfe schmaler, auch die übrigen laufen dort; davon bleibt das Bild.
        for (int gs : new int[] {3, 4}) {
            context.getInput().resizeWindow(1280, 240 * gs);
            context.runOnClient(mc -> {
                mc.options.guiScale().set(gs);
                mc.resizeGui();
                mc.gui.setScreen(new Anzeige(new Einstellungen()));
            });
            context.waitTicks(2);
            context.takeScreenshot(TestScreenshotOptions.of("anzeige-gs" + gs).disableCounterPrefix());
            String f = context.computeOnClient(mc -> {
                for (String schluessel : gs == 3 ? new String[] {"heroicmap.menue.aufloesung", "heroicmap.menue.todespunkt"} : new String[0]) {
                    String name = Component.translatable(schluessel).getString();
                    AbstractWidget w = mc.gui.screen().children().stream().filter(c -> c instanceof AbstractWidget a
                            && a.getMessage().getString().startsWith(name)).map(c -> (AbstractWidget) c).findFirst().orElse(null);
                    if (w == null || mc.font.width(w.getMessage()) > w.getWidth() - 8) {
                        return name + (w == null ? " fehlt" : ": " + mc.font.width(w.getMessage()) + " breit, Knopf " + w.getWidth());
                    }
                }
                return null;
            });
            fehler = fehler != null ? fehler : f == null ? null : "GS " + gs + ": " + f;
        }
        context.getInput().resizeWindow(vorher[0], vorher[1]);
        context.runOnClient(mc -> {
            mc.gui.setScreen(null);
            mc.options.guiScale().set(vorher[2]);
            mc.resizeGui();
        });
        context.waitTicks(2);
        if (fehler != null) {
            throw new AssertionError("Menü bei 1280 × 720, GUI-Massstab 3: " + fehler);
        }
    }

    /** Endet jeder Knopf des offenen Schirms innerhalb der Höhe 240? Sonst was nicht passt. */
    private static String passt(ClientGameTestContext context) {
        return context.computeOnClient(mc -> {
            int hoehe = mc.getWindow().getGuiScaledHeight();
            if (hoehe != 240) {
                return "Höhe " + hoehe + " statt 240";
            }
            for (Object kind : mc.gui.screen().children()) {
                if (kind instanceof AbstractWidget w && w.getY() + w.getHeight() > hoehe) {
                    return w.getMessage().getString() + " endet bei " + (w.getY() + w.getHeight()) + " von " + hoehe;
                }
            }
            return null;
        });
    }

    /**
     * Mit F3 wie von Hand geöffnet: „Bei F3“ auf Mitte rückt die Minimap waagrecht in die Mitte, weg blendet sie aus,
     * bleibt lässt sie stehen; F3 noch einmal, und sie steht wieder, wo sie hingehört. Siehe docs/minimap.md, „Bei F3“.
     */
    private static void beiF3(ClientGameTestContext context, TestInput maus) {
        context.runOnClient(mc -> Minimap.INSTANZ.setzeBeiF3(Minimap.BeiF3.MITTE));
        context.waitTicks(2);
        Minimap.Rahmen r = rahmen(context);
        Minimap.Rahmen ohne = context.computeOnClient(mc -> Minimap.INSTANZ.zuletzt());
        if (!r.equals(ohne)) {
            throw new AssertionError("Ohne F3 steht die Minimap nicht an ihrem Platz: " + ohne + " statt " + r);
        }
        maus.pressKey(InputConstants.KEY_F3);
        context.waitTicks(2);
        if (!context.computeOnClient(mc -> DebugBildschirm.offen(mc))) {
            throw new AssertionError("F3 öffnete den Debug-Bildschirm nicht");
        }
        int breite = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
        Minimap.Rahmen mitte = context.computeOnClient(mc -> Minimap.INSTANZ.zuletzt());
        if (!new Minimap.Rahmen((breite - r.seite()) / 2, r.y(), r.seite()).equals(mitte)) {
            throw new AssertionError("Bei F3 auf Mitte steht die Minimap nicht waagrecht in der Mitte: " + mitte + ", ohne F3 " + r);
        }
        context.takeScreenshot(TestScreenshotOptions.of("f3-mitte").disableCounterPrefix());
        context.runOnClient(mc -> Minimap.INSTANZ.setzeBeiF3(Minimap.BeiF3.AUS));
        context.waitTicks(2);
        if (context.computeOnClient(mc -> Minimap.INSTANZ.zuletzt()) != null) {
            throw new AssertionError("Bei F3 auf weg zeichnet die Minimap noch");
        }
        context.runOnClient(mc -> Minimap.INSTANZ.setzeBeiF3(Minimap.BeiF3.BLEIBT));
        context.waitTicks(2);
        if (!r.equals(context.computeOnClient(mc -> Minimap.INSTANZ.zuletzt()))) {
            throw new AssertionError("Bei F3 auf bleibt rückt die Minimap");
        }
        // Zu und wieder Mitte: Ohne F3 gilt die Wahl nicht, gleich nach dem Tastendruck, auch mit BetterF3, das noch ausblendet.
        maus.pressKey(InputConstants.KEY_F3);
        context.runOnClient(mc -> Minimap.INSTANZ.setzeBeiF3(Minimap.BeiF3.MITTE));
        context.waitTicks(2);
        if (context.computeOnClient(mc -> DebugBildschirm.offen(mc)) || !r.equals(context.computeOnClient(mc -> Minimap.INSTANZ.zuletzt()))) {
            throw new AssertionError("Nach F3 zu steht die Minimap nicht wieder an ihrem Platz");
        }
        context.runOnClient(mc -> Minimap.INSTANZ.setzeBeiF3(Minimap.BeiF3.BLEIBT));
    }

    private static Minimap.Rahmen rahmen(ClientGameTestContext context) {
        return context.computeOnClient(mc -> Minimap.INSTANZ.rahmen(
                mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()));
    }

    /** Greift die Minimap in ihrer Mitte und zieht sie zehnmal um (dx, dy) Pixel des Fensters. */
    private static void ziehe(ClientGameTestContext context, TestInput maus, int taste, Minimap.Rahmen r, int dx, int dy) {
        ziehe(context, maus, taste, r.x() + r.seite() / 2.0, r.y() + r.seite() / 2.0, dx, dy);
    }

    /** Greift bei (x, y) in Einheiten des GUI und zieht zehnmal um (dx, dy) Pixel des Fensters. */
    private static void ziehe(ClientGameTestContext context, TestInput maus, int taste, double x, double y, int dx, int dy) {
        int k = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
        maus.setCursorPos(x * k, y * k);
        context.waitTick();
        maus.holdMouse(taste);
        context.waitTick();
        for (int i = 0; i < 10; i++) {
            maus.moveCursor(dx, dy);
            context.waitTick();
        }
        maus.releaseMouse(taste);
        context.waitTick();
    }
}
