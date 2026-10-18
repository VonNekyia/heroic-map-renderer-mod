package com.nekyia.heroicmap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
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

            karte(context, maus, k);
            teleport(context, spiel.getServer(), maus, k);
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
        maus.pressMouse(LINKS);
        context.waitTick();
        maus.pressMouse(LINKS);
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
        for (boolean an : new boolean[] {true, false}) {
            warte250();
            maus.pressMouse(LINKS);
            context.waitTick();
            maus.pressMouse(LINKS);
            context.waitTicks(2);
            if (kreisAngeheftet(context) != an) {
                throw new AssertionError("Doppelklick auf den Kreis: angeheftet " + !an + " statt " + an);
            }
        }

        warte250();
        maus.setCursorPos(breite / 2.0 * k, ny * k);
        context.waitTick();
        maus.pressMouse(LINKS);
        context.waitTick();
        maus.pressMouse(LINKS);
        context.waitTicks(2);
        if (!context.computeOnClient(mc -> Wegpunkte.INSTANZ.nadelAngeheftet("test:anheften", "hafen"))) {
            throw new AssertionError("Doppelklick auf die Nadel heftet sie nicht an");
        }

        warte250();
        Karte.Marke raute = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).marken().stream()
                .filter(m -> m.region() != null).findFirst().orElseThrow(() -> new AssertionError("Keine Raute der Region")));
        maus.setCursorPos(raute.x() * k, raute.y() * k);
        context.waitTick();
        maus.pressMouse(LINKS);
        context.waitTick();
        maus.pressMouse(LINKS);
        context.waitTicks(2);
        if (!context.computeOnClient(mc -> Wegpunkte.INSTANZ.regionen().getFirst().angeheftet())) {
            throw new AssertionError("Doppelklick auf die Raute heftet die Region nicht an");
        }
        context.runOnClient(mc -> Ebenen.INSTANZ.leeren());
        warte250();
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

        // Linie aus zwei Punkten: „Linie/Region von hier“, Linksklick auf den zweiten, dann „Form fertig“.
        warte250();
        Karte.Marke d = marke(context, neu.get(3));
        eintrag(context, maus, k, d.x(), d.y(), "heroicmap.karte.form_von_hier");
        warte250();
        tafelDerRegion(context);
        Karte.Marke e = marke(context, neu.get(4));
        spielerKlick(context, maus, k, e.x(), e.y());
        eintrag(context, maus, k, mx + 60, my + 40, "heroicmap.karte.form_fertig");
        Wegpunkte.EigeneForm linie = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().getLast());
        if (linie.region() || linie.ecken().size() != 2) {
            throw new AssertionError("„Form fertig“ mit zwei Punkten gab keine Linie: " + linie);
        }

        // Ein Doppelklick in die Region heftet sie an; „Form löschen“ löscht sie.
        warte250();
        double ix = (orte[0][0] + orte[1][0] + orte[2][0]) / 3, iy = (orte[0][1] + orte[1][1] + orte[2][1]) / 3;
        fahre(context, maus, k, ix, iy);
        maus.pressMouse(LINKS);
        context.waitTick();
        maus.pressMouse(LINKS);
        context.waitTicks(2);
        if (!context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().get(formen).angeheftet())
                || context.computeOnClient(mc -> Wegpunkte.INSTANZ.angeheftet("test:land", "land"))) {
            throw new AssertionError("Doppelklick in die eigene Region heftete nicht sie an: eigene "
                    + context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().get(formen).angeheftet()) + ", vom Server "
                    + context.computeOnClient(mc -> Wegpunkte.INSTANZ.angeheftet("test:land", "land")));
        }
        // Ein Doppelklick von Hand auf einen Wegpunkt in der Region heftet ihn an, die Karte bleibt stehen (mod#75).
        double[] stand = mitte(context);
        Karte.Marke b = marke(context, neu.get(1));
        warte250();
        fahre(context, maus, k, b.x(), b.y());
        for (int i = 0; i < 2; i++) {
            maus.holdMouse(LINKS);
            context.waitTick();
            maus.releaseMouse(LINKS);
            context.waitTick();
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
            eintrag(context, maus, k, neuOrt[0] + 30, neuOrt[1], "heroicmap.karte.form_fertig");
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

            // Im Bau gelöscht: „Form fertig“ speichert nichts und sagt es, statt die neuen Ecken still zu verwerfen.
            double[] start = ecke(context, formen, 0);
            eintrag(context, maus, k, start[0], start[1], "heroicmap.karte.ecke_hinzu");
            tempoKlick(context, maus, k, neuOrt[0], neuOrt[1] + 20, 0, 0);
            // „Form löschen“ in der Region, an der Mitte ihrer drei Ecken.
            double[] e1 = ecke(context, formen, 1), e2 = ecke(context, formen, 2);
            eintrag(context, maus, k, (start[0] + e1[0] + e2[0]) / 3, (start[1] + e1[1] + e2[1]) / 3, "heroicmap.karte.form_loeschen");
            eintrag(context, maus, k, neuOrt[0] + 30, neuOrt[1], "heroicmap.karte.form_fertig");
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
        try {
            BufferedImage b = ImageIO.read(bild.toFile());
            int n = 0;
            for (int y = (int) ((mitte[1] - r) * k); y < (mitte[1] + r) * k; y++) {
                for (int x = (int) ((mitte[0] - r) * k); x < (mitte[0] + r) * k; x++) {
                    int p = b.getRGB(x, y);
                    n += (p >> 16 & 0xFF) >= 0xF0 && (p >> 8 & 0xFF) >= 0xF0 && (p & 0xFF) >= 0xF0 ? 1 : 0;
                }
            }
            return n;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
        maus.pressMouse(LINKS);
        if (doppelt) {
            context.waitTick();
            maus.pressMouse(LINKS);
        }
        context.waitTicks(2);
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
     * „Hierher teleportieren“ mit fester Höhe, siehe docs/vollbildkarte.md, „Teleportieren“: über Wasser in einem Chunk, den
     * der Client hat, auf die Oberfläche, nicht auf den Grund; und in einen Chunk, den weder Client noch Server geladen
     * haben, mit dem Boden der Karte, den der Mod genau einmal per HTTP holt, auf den Boden der flachen Welt.
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
        AtomicInteger abrufe = new AtomicInteger();
        HttpServer http;
        try {
            http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        http.createContext("/tiles/", austausch -> {
            abrufe.incrementAndGet();
            boolean da = austausch.getRequestURI().getPath().equals("/tiles/ground/3.3.bin");
            austausch.sendResponseHeaders(da ? 200 : 404, da ? gepackt.length : -1);
            if (da) {
                austausch.getResponseBody().write(gepackt);
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
        } finally {
            http.stop(0);
            context.runOnClient(mc -> mc.gui.setScreen(null));
        }
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
