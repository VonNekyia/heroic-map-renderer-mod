package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * „Hierher teleportieren“ ohne Recht auf {@code tp}, in einer Welt ohne Befehle: Der Server schickt {@code tp} nicht im
 * Befehlsbaum, der Mod schickt nichts, unten links steht ein Hinweis, die Karte bleibt offen und der Spieler, wo er ist. Siehe
 * docs/vollbildkarte.md, „Teleportieren“.
 */
public final class OhneTp implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext spiel = context.worldBuilder().create(); var _ = Abschluss.langsamerTakt(context, spiel)) {
            spiel.getConnection().waitForChunksRender();
            if (context.computeOnClient(mc -> mc.getConnection().getCommands().getRoot().getChild("tp") != null)) {
                throw new AssertionError("Ohne Befehle steht tp doch im Befehlsbaum");
            }
            Vec3 vorher = context.computeOnClient(mc -> mc.player.position());
            Path baum = Bilder.testsatz();
            context.runOnClient(mc -> mc.gui.setScreen(new Karte(Satz.lies(baum))));
            context.waitTicks(5);
            context.runOnClient(mc -> ((Karte) mc.gui.screen()).zeige(1000, 1000));
            context.waitTicks(2);
            int[] f = context.computeOnClient(mc -> new int[] {mc.getWindow().getGuiScale(), mc.getWindow().getGuiScaledWidth(),
                    mc.getWindow().getGuiScaledHeight()});
            int k = f[0];
            // Rechts klicken öffnet das Menü an der Maus; der erste Eintrag, der Teleport, liegt wenige Einheiten rechts unter ihr.
            TestInput maus = context.getInput();
            maus.setCursorPos(f[1] / 2.0 * k, f[2] / 2.0 * k);
            context.waitTick();
            maus.pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
            context.waitTicks(2);
            maus.moveCursor(6 * k, 6 * k);
            context.waitTick();
            maus.pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
            context.waitTicks(20);
            String erwartet = context.computeOnClient(mc -> Component.translatable("heroicmap.teleport.kein_recht").getString());
            String hinweis = context.computeOnClient(mc -> mc.gui.screen() instanceof Karte karte && karte.hinweis() != null
                    ? karte.hinweis().getString() : null);
            Vec3 nachher = context.computeOnClient(mc -> mc.player.position());
            if (!erwartet.equals(hinweis) || nachher.distanceTo(vorher) > 0.01) {
                throw new AssertionError("Ohne tp: Hinweis „" + hinweis + "“ statt „" + erwartet + "“, Spieler von " + vorher + " nach " + nachher);
            }
            context.runOnClient(mc -> mc.gui.setScreen(null));
        }
    }
}
