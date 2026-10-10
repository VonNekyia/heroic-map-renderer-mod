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
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Das Menü und die Vollbildkarte mit echten Eingaben der Maus: Ziehen mit der linken wie der
 * rechten Taste verschiebt die ganze Minimap; auf der Karte verschiebt links ziehen den Inhalt,
 * rechts klicken öffnet das Menü mit Teleport und Wegpunkt; auf einer Marke am Rand ziehen zieht
 * die Karte, ein Klick zentriert sie, ein Doppelklick heftet sie an, ebenso einen Kreis vom Server und
 * eine eigene Region. Siehe docs/minimap.md, „Bedienung“, und docs/wegpunkte.md.
 */
public final class Bedienung implements FabricClientGameTest {

    /** Die Tasten, wie das Spiel sie zählt; TestInput reicht die Zahl unverändert durch. Siehe docs/entwicklung.md, „Maustasten“. */
    private static final int LINKS = InputConstants.MOUSE_BUTTON_LEFT, RECHTS = InputConstants.MOUSE_BUTTON_RIGHT;

    @Override
    public void runTest(ClientGameTestContext context) {
        // Mit Befehlen, sonst schickt der Server execute und tp nicht, und das Teleport-Menü fehlt.
        try (TestSingleplayerContext spiel = context.worldBuilder().adjustSettings(s -> s.setAllowCommands(true)).create()) {
            spiel.getConnection().waitForChunksRender();
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
        region(context, maus, k);
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
        maus.pressMouse(LINKS);
        context.waitTicks(2);
        // Erst wenn kein zweiter Klick mehr kommen kann, legt der Klick die Marke in die Mitte (mod#75).
        if (!Arrays.equals(mitte(context), nachZug)) {
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
     * Ein Kreis und eine Nadel vom Server und eine eigene Region: Ein Klick auf den Kreis heftet nichts
     * an, ein Doppelklick heftet ihn an, ein zweiter löst ihn; ein Doppelklick auf die Nadel oder die Raute
     * der Region heftet sie an. Siehe docs/wegpunkte.md, „Anheften“.
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

    /**
     * Eine Region wie ein Spieler: Rechtsklick, „Region von hier“, die Karte ziehen setzt keine Region,
     * dann die Maus bewegen und ein Linksklick setzt die zweite Ecke. Siehe docs/wegpunkte.md, „Regionen“.
     */
    private static void region(ClientGameTestContext context, TestInput maus, int k) {
        int breite = context.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
        int hoehe = context.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
        double x = breite / 3.0, y = hoehe / 3.0;
        int vorher = context.computeOnClient(mc -> Wegpunkte.INSTANZ.regionen().size());
        warte250();
        maus.setCursorPos(x * k, y * k);
        context.waitTick();
        maus.pressMouse(RECHTS);
        context.waitTicks(2);
        int[] ecke = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).ziel());
        String von = context.computeOnClient(mc -> Component.translatable("heroicmap.karte.region_von").getString());
        int zeile = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).eintraege().stream().map(Component::getString).toList().indexOf(von));
        if (ecke == null || zeile < 0) {
            throw new AssertionError("Kein Menü mit „Region von hier“");
        }
        // Die Einträge stehen 14 Einheiten untereinander, ab der Maus.
        maus.moveCursor(6 * k, (zeile * 14 + 6) * k);
        context.waitTick();
        maus.pressMouse(LINKS);
        context.waitTicks(2);
        if (context.computeOnClient(mc -> ((Karte) mc.gui.screen()).regionVon()) == null) {
            throw new AssertionError("„Region von hier“ startet keine Vorschau");
        }

        // Ziehen verschiebt die Karte und setzt keine Region; die Vorschau bleibt.
        warte250();
        double[] vorZug = mitte(context);
        maus.setCursorPos(breite / 2.0 * k, hoehe / 2.0 * k);
        context.waitTick();
        maus.holdMouse(LINKS);
        context.waitTick();
        for (int i = 0; i < 5; i++) {
            maus.moveCursor(6 * k, 0);
            context.waitTick();
        }
        maus.releaseMouse(LINKS);
        context.waitTicks(2);
        if (context.computeOnClient(mc -> Wegpunkte.INSTANZ.regionen().size()) != vorher || mitte(context)[0] == vorZug[0]
                || context.computeOnClient(mc -> ((Karte) mc.gui.screen()).regionVon()) == null) {
            throw new AssertionError("Ziehen während der Vorschau setzte eine Region oder verschob die Karte nicht");
        }

        // Die Maus bewegen, dann ein Linksklick ohne Zug: die zweite Ecke. Die erste steht nach dem Zug 30 Einheiten
        // weiter rechts; 4 Einheiten sind ein Block, so wird das Rechteck rund 8 × 10 Blöcke gross.
        warte250();
        maus.setCursorPos((x + 60) * k, (y + 40) * k);
        context.waitTick();
        maus.pressMouse(LINKS);
        context.waitTicks(2);
        Wegpunkte.Region neu = context.computeOnClient(mc -> Wegpunkte.INSTANZ.regionen().size() == vorher + 1
                ? Wegpunkte.INSTANZ.regionen().getLast() : null);
        if (neu == null || !neu.enthaelt(neu.dimension(), ecke[0], ecke[1]) || neu.x1() - neu.x0() < 5 || neu.z1() - neu.z0() < 5
                || context.computeOnClient(mc -> ((Karte) mc.gui.screen()).regionVon()) != null) {
            throw new AssertionError("Linksklick setzt die zweite Ecke nicht: " + neu);
        }
        warte250();
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
