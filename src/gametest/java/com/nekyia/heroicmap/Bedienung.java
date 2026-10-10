package com.nekyia.heroicmap;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Das Menü und die Vollbildkarte mit echten Eingaben der Maus: Ziehen mit der linken wie der
 * rechten Taste verschiebt die ganze Minimap; auf der Karte verschiebt links ziehen den Inhalt,
 * rechts klicken öffnet das Menü mit Teleport und Wegpunkt; auf einer Marke am Rand ziehen zieht
 * die Karte, ein Klick zentriert sie, ein Doppelklick heftet sie an, ebenso einen Kreis vom Server und
 * ein altes Rechteck; Formen aus Wegpunkten bauen, anheften und löschen. Siehe docs/minimap.md, „Bedienung“, und docs/wegpunkte.md.
 */
public final class Bedienung implements FabricClientGameTest {

    /** Die Tasten, wie das Spiel sie zählt; TestInput reicht die Zahl unverändert durch. Siehe docs/entwicklung.md, „Maustasten“. */
    private static final int LINKS = InputConstants.MOUSE_BUTTON_LEFT, RECHTS = InputConstants.MOUSE_BUTTON_RIGHT;

    @Override
    public void runTest(ClientGameTestContext context) {
        // Mit Befehlen, sonst schickt der Server execute und tp nicht, und das Teleport-Menü fehlt.
        try (TestSingleplayerContext spiel = context.worldBuilder().adjustSettings(s -> s.setAllowCommands(true)).create()) {
            spiel.getConnection().waitForChunksRender();
            Bilder.leereWelt(context);
            kleinerSchirm(context);
            context.runOnClient(mc -> mc.gui.setScreen(new Einstellungen()));
            context.waitTicks(2);
            int k = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
            TestInput maus = context.getInput();

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
            context.runOnClient(mc -> mc.gui.setScreen(null));

            karte(context, maus, k);
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
     * Formen aus Wegpunkten wie ein Spieler, über einer grossen Region vom Server mit Tafel, wie auf einem
     * Server mit Ländern: drei Wegpunkte über „Wegpunkt setzen“, auf dem ersten „Punkt hinzufügen“, dann
     * Linksklicks auf den dritten, den zweiten und den ersten: eine Region. Zwei weitere, „Punkt
     * hinzufügen“, Linksklick, „Form fertig“: eine Linie. Ein Doppelklick in die Region heftet sie an, nicht
     * die Region vom Server darunter; „Form löschen“ löscht sie. Jeder Klick fährt wie die Hand in
     * Schritten hin, ruht, drückt, hält und lässt los (`spielerKlick`); so geht nach „Punkt hinzufügen“ die
     * Tafel der Region auf, wo das Menü war. Siehe docs/wegpunkte.md, „Formen aus Wegpunkten“.
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

        // Region aus drei Punkten: auf dem ersten „Punkt hinzufügen“; die Tafel der Region geht auf, wo das Menü war.
        // Dann Linksklicks auf den dritten, rechts unter dem Menü, den zweiten und den ersten.
        Karte.Marke a = marke(context, neu.get(0));
        eintrag(context, maus, k, a.x(), a.y(), "heroicmap.karte.punkt_hinzu");
        tafelDerRegion(context);
        int[] reihe = {2, 1, 0};
        for (int j = 0; j < reihe.length; j++) {
            Karte.Marke m = marke(context, neu.get(reihe[j]));
            spielerKlick(context, maus, k, m.x(), m.y());
            List<Integer> zug = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).zug());
            if (j < 2 && (zug == null || zug.size() != j + 2)) {
                throw new AssertionError("Linksklick auf einen Wegpunkt fügte ihn nicht an: im Bau " + zug + ", Tafel offen "
                        + context.computeOnClient(mc -> ((Karte) mc.gui.screen()).tafelOffen()));
            }
        }
        Wegpunkte.EigeneForm region = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().size() == formen + 1
                ? Wegpunkte.INSTANZ.eigeneFormen().getLast() : null);
        if (region == null || !region.region() || region.punkte().size() != 3
                || context.computeOnClient(mc -> ((Karte) mc.gui.screen()).zug()) != null) {
            throw new AssertionError("Linksklick auf den ersten Punkt schloss keine Region: " + region);
        }

        // Linie aus zwei Punkten: „Punkt hinzufügen“, Linksklick auf den zweiten, dann „Form fertig“.
        warte250();
        Karte.Marke d = marke(context, neu.get(3));
        eintrag(context, maus, k, d.x(), d.y(), "heroicmap.karte.punkt_hinzu");
        warte250();
        tafelDerRegion(context);
        Karte.Marke e = marke(context, neu.get(4));
        spielerKlick(context, maus, k, e.x(), e.y());
        eintrag(context, maus, k, mx + 60, my + 40, "heroicmap.karte.form_fertig");
        Wegpunkte.EigeneForm linie = context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().getLast());
        if (linie.region() || linie.punkte().size() != 2) {
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
        warte250();
        eintrag(context, maus, k, ix, iy, "heroicmap.karte.form_loeschen");
        if (context.computeOnClient(mc -> Wegpunkte.INSTANZ.eigeneFormen().size()) != formen + 1) {
            throw new AssertionError("„Form löschen“ löschte die Region nicht");
        }
        warte250();
    }

    /** Lässt die Tafel der grossen Region aufgehen, wo der Zeiger steht: Frage als gesendet, Antwort abgelegt. */
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
     * Ein Linksklick wie von Hand: hinfahren, ruhen, drücken, drei Ticks halten und dabei eine halbe Einheit
     * zittern, loslassen; danach eine Pause, die keinen Doppelklick mit dem nächsten zulässt.
     */
    private static void spielerKlick(ClientGameTestContext context, TestInput maus, int k, double x, double y) {
        warte250();
        fahre(context, maus, k, x, y);
        maus.holdMouse(LINKS);
        context.waitTick();
        maus.moveCursor(0.5 * k, 0);
        context.waitTick();
        maus.moveCursor(-0.5 * k, 0);
        context.waitTick();
        maus.releaseMouse(LINKS);
        context.waitTicks(2);
    }

    /** Rechtsklick bei (x, y), dann ein Klick auf den Eintrag mit diesem Text; die Einträge stehen 14 Einheiten untereinander. */
    private static void eintrag(ClientGameTestContext context, TestInput maus, int k, double x, double y, String schluessel) {
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
        maus.moveCursor(6 * k, (zeile * 14 + 6) * k);
        context.waitTick();
        maus.pressMouse(LINKS);
        context.waitTicks(2);
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
     * Linke Taste 2 s still auf einem Wegpunkt: Er hängt an der Maus und liegt nach dem Loslassen auf dem
     * Block darunter; ein zweites Mal mit Escape bleibt er, wo er war. Siehe docs/wegpunkte.md, „Bedienung“.
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
        maus.holdMouse(LINKS);
        context.waitTicks(50);
        if (context.computeOnClient(mc -> ((Karte) mc.gui.screen()).haengt()) == null) {
            maus.releaseMouse(LINKS);
            throw new AssertionError("Nach 2,5 s still gehalten hängt der Wegpunkt nicht an der Maus");
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
        maus.holdMouse(LINKS);
        context.waitTicks(50);
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

    /** Das Spiel zählt einen Klick bis 250 ms nach dem letzten als Doppelklick, nach der Uhr, nicht nach Ticks. */
    private static void warte250() {
        try {
            Thread.sleep(300);
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

    private static Minimap.Rahmen rahmen(ClientGameTestContext context) {
        return context.computeOnClient(mc -> Minimap.INSTANZ.rahmen(
                mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()));
    }

    /** Greift die Minimap in ihrer Mitte und zieht sie zehnmal um (dx, dy) Pixel des Fensters. */
    private static void ziehe(ClientGameTestContext context, TestInput maus, int taste, Minimap.Rahmen r, int dx, int dy) {
        int k = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
        maus.setCursorPos((r.x() + r.seite() / 2.0) * k, (r.y() + r.seite() / 2.0) * k);
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
