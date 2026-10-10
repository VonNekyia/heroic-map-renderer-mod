package com.nekyia.heroicmap;

import com.google.gson.JsonParser;
import com.nekyia.heroicmap.api.HeroicMapClientApi;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Die API für Client-Mods: Die Ebene, die {@link ProbeEbene} beim Start anlegt, steht in der Liste der
 * Vollbildkarte, mit Nadel und Formen, ohne Banner. Sie übersteht eine Liste vom Server und das Trennen; eine
 * Ebene vom Server mit derselben Kennung verdeckt sie, bis sie wieder geht; {@code remove} nimmt sie weg.
 * Läuft als erster Gametest, und am Ende ist die Ebene weg: Die Bilder der Tests danach zeigen sie nicht. Siehe docs/api.md.
 */
public final class Api implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!Boolean.TRUE.equals(ProbeEbene.angenommen)) {
            throw new AssertionError("Der Entrypoint heroicmap lief nicht oder put nahm die Ebene nicht an: " + ProbeEbene.angenommen);
        }
        pruefe(context, "nach dem Start", true);
        try (TestSingleplayerContext spiel = context.worldBuilder().create()) {
            spiel.getConnection().waitForChunksRender();
            // In der Liste der Vollbildkarte, mit Schalter wie eine Ebene vom Server.
            Path baum = Bilder.testsatz();
            context.runOnClient(mc -> {
                Kartenlage.ebenenOffen(Downloads.weltOrdner(), true);
                mc.gui.setScreen(new Karte(Satz.lies(baum)));
            });
            context.waitTicks(5);
            double[] zeile = context.computeOnClient(mc -> ((Karte) mc.gui.screen()).zeile(ProbeEbene.ID));
            if (zeile == null) {
                throw new AssertionError("Die Ebene des Mods fehlt in der Liste der Vollbildkarte");
            }
            context.runOnClient(mc -> {
                mc.gui.screen().onClose();
                Kartenlage.ebenenOffen(Downloads.weltOrdner(), false);
            });

            // Eine Liste vom Server ersetzt nur ihren Teil.
            context.runOnClient(mc -> Ebenen.INSTANZ.empfange(JsonParser.parseString("""
                    {"v":1,"typ":"ebenen","jetzt":1,"ebenen":[{"id":"test:server","name":{"de":"Server","en":"Server"},"version":"1"}]}""")
                    .getAsJsonObject()));
            context.waitTick();
            pruefe(context, "nach einer Liste vom Server", true);

            // Dieselbe Kennung vom Server verdeckt die Ebene des Mods, bis der Server sie nicht mehr hat.
            context.runOnClient(mc -> Ebenen.INSTANZ.empfange(JsonParser.parseString("""
                    {"v":1,"typ":"ebenen","jetzt":1,"ebenen":[{"id":"heroicmap-tests:probe","name":{"de":"Vom Server","en":"From the server"},
                      "version":"1"}]}""").getAsJsonObject()));
            context.waitTick();
            String verdeckt = context.computeOnClient(mc -> Ebenen.INSTANZ.alle().stream().filter(e -> e.id().equals(ProbeEbene.ID))
                    .map(Ebenen.Eintrag::version).toList().toString());
            if (!verdeckt.equals("[1]") || !context.computeOnClient(mc -> Ebenen.INSTANZ.nadeln(ProbeEbene.ID).isEmpty())) {
                throw new AssertionError("Die Ebene vom Server verdeckt die des Mods nicht: Versionen " + verdeckt);
            }
            context.runOnClient(mc -> Ebenen.INSTANZ.empfange(JsonParser.parseString("""
                    {"v":1,"typ":"ebenen","jetzt":1,"ebenen":[]}""").getAsJsonObject()));
            context.waitTick();
            pruefe(context, "nachdem der Server die gleichnamige Ebene nicht mehr hat", true);
        }
        // Nach dem Trennen: Die Ebenen der Mods hängen am Client.
        context.waitTicks(2);
        pruefe(context, "nach dem Trennen", true);
        // Der Thread der Gametests darf Minecraft nicht anfassen; ein Mod ruft remove von jedem anderen.
        context.runOnClient(mc -> HeroicMapClientApi.remove(ProbeEbene.ID));
        context.waitTicks(2);
        pruefe(context, "nach remove", false);
    }

    /** Steht die Ebene des Mods mit Nadel, Kreis, Linie und Schrift da, und ohne Banner? Oder ist sie ganz weg? */
    private static void pruefe(ClientGameTestContext context, String wann, boolean da) {
        String stand = context.computeOnClient(mc -> {
            boolean inListe = Ebenen.INSTANZ.alle().stream().anyMatch(e -> e.id().equals(ProbeEbene.ID));
            int nadeln = Ebenen.INSTANZ.nadeln(ProbeEbene.ID).size(), formen = Ebenen.INSTANZ.formen(ProbeEbene.ID).size();
            return inListe + " " + nadeln + " " + formen;
        });
        // Eine Nadel, das Banner übergangen; Kreis, Linie und Schrift.
        String soll = da ? "true 1 3" : "false 0 0";
        if (!stand.equals(soll)) {
            throw new AssertionError("Ebene des Mods " + wann + ": in der Liste, Nadeln, Formen „" + stand + "“ statt „" + soll + "“");
        }
    }
}
