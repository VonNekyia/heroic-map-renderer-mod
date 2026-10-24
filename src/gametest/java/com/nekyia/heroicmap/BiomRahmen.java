package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Der Rahmen „biom“ über eine echte Biomgrenze: Die flache Welt ist Grasland, {@code /fillbiome} macht daneben Schnee. Der
 * Spieler geht hinüber, nach gut zwei Sekunden zeigt der Rahmen „schnee“. Im Wähler steht „Automatisch“ vor der ersten
 * Kategorie; mit echten Klicks fest „waelder“ gewählt, bleibt der Rahmen im Schnee bei „waelder“, zurück auf „Automatisch“
 * zeigt er wieder Schnee. Siehe docs/rahmen.md, „Biom“, und 0048.
 */
public final class BiomRahmen implements FabricClientGameTest {

    /** Die Familie „biom“ im Wähler: die zweite Karte nach „ohne“. */
    private static final int BIOM = 1;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext spiel = context.worldBuilder().create(); var _ = Abschluss.langsamerTakt(context, spiel)) {
            var server = spiel.getServer();
            server.runCommand("gamemode spectator @a");
            // Gras liegt auf y = -61; 16 × 32 × 16 Blöcke bleiben unter der Grenze der Befehle.
            server.runCommand("fillbiome 48 -64 -8 63 -33 7 minecraft:snowy_plains");
            server.runCommand("tp @a 0.5 -60 0.5");
            spiel.getConnection().waitForChunksRender();
            context.runOnClient(mc -> {
                Minimap.INSTANZ.setzeSkin(Skin.BIOM);
                Minimap.INSTANZ.setzeBiomFest(null);
            });
            zeigt(context, Biom.GRASLAND, "am Start im Grasland");

            server.runCommand("tp @a 56.5 -60 0.5");
            spiel.getConnection().waitForChunksRender();
            zeigt(context, "schnee", "automatisch, nach dem Gang in den Schnee");

            // Im Wähler: „Automatisch“, rechts davon die erste Kategorie, links von ihr wieder „Automatisch“.
            context.runOnClient(mc -> mc.gui.setScreen(new Einstellungen()));
            context.waitTicks(3);
            context.runOnClient(mc -> ((Einstellungen) mc.gui.screen()).waehler().oeffne());
            context.waitTicks(2);
            vorschau(context, null, "beim Öffnen");
            klicke(context, false, true);
            vorschau(context, Biom.KATEGORIEN.getFirst(), "nach einem Klick nach rechts");
            klicke(context, false, false);
            vorschau(context, null, "zurück nach links");
            klicke(context, false, true);
            // Die Vorschau übernimmt „waelder“ fest; im Schnee bleibt der Rahmen dabei.
            klicke(context, true, false);
            context.runOnClient(mc -> mc.gui.setScreen(null));
            if (!"waelder".equals(context.computeOnClient(mc -> Minimap.INSTANZ.biomFest()))) {
                throw new AssertionError("Fest gewählt ist nicht waelder: " + context.computeOnClient(mc -> Minimap.INSTANZ.biomFest()));
            }
            zeigt(context, "waelder", "fest gewählt, im Schnee");

            // Wieder „Automatisch“: Der Wähler öffnet bei „waelder“, links davon „Automatisch“.
            context.runOnClient(mc -> mc.gui.setScreen(new Einstellungen()));
            context.waitTicks(3);
            context.runOnClient(mc -> ((Einstellungen) mc.gui.screen()).waehler().oeffne());
            context.waitTicks(2);
            vorschau(context, "waelder", "beim Öffnen mit fester Wahl");
            klicke(context, false, false);
            klicke(context, true, false);
            context.runOnClient(mc -> mc.gui.setScreen(null));
            if (context.computeOnClient(mc -> Minimap.INSTANZ.biomFest()) != null) {
                throw new AssertionError("Nach „Automatisch“ noch fest: " + context.computeOnClient(mc -> Minimap.INSTANZ.biomFest()));
            }
            zeigt(context, "schnee", "wieder automatisch, im Schnee");
        }
    }

    /** Wartet gut zwei Sekunden über die Frist des Wechsels hinaus und prüft dann die gezeigte Kategorie samt Skin. */
    private static void zeigt(ClientGameTestContext context, String kategorie, String wann) {
        context.waitTicks((int) (Biom.WARTEN_MS / 50) + 60);
        String[] jetzt = context.computeOnClient(mc -> {
            Skin s = Minimap.INSTANZ.skinJetzt();
            return new String[] {Minimap.INSTANZ.kategorie(), s == null ? null : s.ordner};
        });
        if (!kategorie.equals(jetzt[0]) || jetzt[1] == null || !jetzt[1].startsWith(Biom.ORDNER.get(kategorie) + "/")) {
            throw new AssertionError("Rahmen „biom“ " + wann + ": " + jetzt[0] + " in " + jetzt[1] + " statt " + kategorie);
        }
    }

    /** Prüft die Kategorie von „biom“ in der Vorschau des Wählers, null für „Automatisch“. */
    private static void vorschau(ClientGameTestContext context, String kategorie, String wann) {
        String jetzt = context.computeOnClient(mc -> ((Einstellungen) mc.gui.screen()).waehler().kategorieVorschau());
        if (kategorie == null ? jetzt != null : !kategorie.equals(jetzt)) {
            throw new AssertionError("Wähler " + wann + ": " + (jetzt == null ? "Automatisch" : jetzt) + " statt "
                    + (kategorie == null ? "Automatisch" : kategorie));
        }
    }

    /** Ein Linksklick wie von Hand im Wähler auf die Karte „biom“: auf ihre Vorschau oder links oder rechts in die Zeile darunter. */
    private static void klicke(ClientGameTestContext context, boolean vorschau, boolean rechts) {
        double[] p = context.computeOnClient(mc -> {
            int b = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
            Waehler w = ((Einstellungen) mc.gui.screen()).waehler();
            return vorschau ? w.vorschauMitte(BIOM, b, h) : w.zeileMitte(BIOM, rechts, b, h);
        });
        int k = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
        context.getInput().setCursorPos(p[0] * k, p[1] * k);
        context.waitTick();
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
    }
}
