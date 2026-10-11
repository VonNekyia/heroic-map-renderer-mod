package com.nekyia.heroicmap;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Der Strahl eines Leuchtfeuers über jedem angehefteten Wegpunkt dieser Dimension in Sichtweite, in
 * seiner Farbe, vom Boden bis so hoch wie beim Leuchtfeuer. Gezeichnet mit dem Strahl des Spiels; im
 * Mod ohne Allokation je Frame. Siehe docs/wegpunkte.md, „Strahl“.
 */
final class Strahlen {

    /** So viele Strahlen je Frame höchstens: die ersten angehefteten Wegpunkte in Sichtweite. */
    static final int MAX_STRAHLEN = 64;
    /** Ab so vielen Blöcken wird der Strahl breiter, mit dem Abstand, wie beim Leuchtfeuer (BeaconRenderer.extract). */
    static final float BREITER_AB = 96;

    /** Die Indizes der gewählten Wegpunkte des letzten Frames, einmal angelegt. */
    private static final int[] GEWAEHLT = new int[MAX_STRAHLEN];
    /** Der Name der Dimension zu ihrem Schlüssel, nur beim Wechsel neu; sonst gäbe toString je Frame einen String. */
    private static ResourceKey<Level> schluessel;
    private static String dimension;

    private Strahlen() {
    }

    /** Aus {@code LevelRenderEvents.COLLECT_SUBMITS}: reicht die Strahlen an den Sammler, wie ein Leuchtfeuer seine. */
    static void zeichne(LevelRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !Minimap.INSTANZ.effekte()) {
            return;
        }
        if (level.dimension() != schluessel) {
            schluessel = level.dimension();
            dimension = schluessel.identifier().toString();
        }
        LevelRenderState zustand = ctx.levelState();
        Vec3 kamera = zustand.cameraRenderState.pos;
        List<Wegpunkte.Punkt> punkte = Wegpunkte.INSTANZ.punkte();
        int n = waehle(punkte, dimension, kamera.x, kamera.z, mc.options.getEffectiveRenderDistance() * 16.0, GEWAEHLT);
        // Wie das Leuchtfeuer: die Zeit läuft in 40 Ticks einmal durch.
        float zeit = Math.floorMod(zustand.gameTime, 40) + zustand.worldPartialTicks;
        boolean fernrohr = mc.player != null && mc.player.isScoping();
        PoseStack pose = ctx.poseStack();
        for (int i = 0; i < n; i++) {
            Wegpunkte.Punkt p = punkte.get(GEWAEHLT[i]);
            // Der Boden unter dem Laub, auf dem Wasser; ist der Chunk nicht geladen, der Boden der Welt.
            int boden = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.x(), p.z());
            float b = breite(Math.hypot(p.x() + 0.5 - kamera.x, p.z() + 0.5 - kamera.z), fernrohr);
            pose.pushPose();
            pose.translate(p.x() - kamera.x, boden - kamera.y, p.z() - kamera.z);
            BeaconRenderer.submitBeaconBeam(pose, ctx.submitNodeCollector(), BeaconRenderer.BEAM_LOCATION, 1, zeit, 0,
                    BeaconRenderer.MAX_RENDER_Y, Wegpunkte.FARBEN[p.farbe()], BeaconRenderer.SOLID_BEAM_RADIUS * b, BeaconRenderer.BEAM_GLOW_RADIUS * b);
            pose.popPose();
        }
    }

    /**
     * Schreibt die Indizes der angehefteten Wegpunkte in {@code dimension}, deren Block waagrecht
     * höchstens {@code weit} Blöcke von (x, z) liegt, der Reihe nach in {@code aus}, höchstens so viele,
     * wie hineinpassen; gibt ihre Zahl.
     */
    static int waehle(List<Wegpunkte.Punkt> punkte, String dimension, double x, double z, double weit, int[] aus) {
        int n = 0;
        for (int i = 0; i < punkte.size() && n < aus.length; i++) {
            Wegpunkte.Punkt p = punkte.get(i);
            double dx = p.x() + 0.5 - x, dz = p.z() + 0.5 - z;
            if (p.angeheftet() && dx * dx + dz * dz <= weit * weit && p.dimension().equals(dimension)) {
                aus[n++] = i;
            }
        }
        return n;
    }

    /** Wie viel breiter der Strahl im waagrechten Abstand {@code abstand} steht: ab {@link #BREITER_AB} mit ihm, durchs Fernrohr nie. */
    static float breite(double abstand, boolean fernrohr) {
        return fernrohr ? 1 : Math.max(1, (float) abstand / BREITER_AB);
    }
}
