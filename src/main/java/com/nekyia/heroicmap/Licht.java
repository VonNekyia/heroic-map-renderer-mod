package com.nekyia.heroicmap;

import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeMap;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Vector3fc;

/**
 * Die Lightmap einer Dimension am Tag, wie der Renderer sie rechnet: je Himmels- und
 * Blocklicht die Helligkeit je Farbkanal.
 * Siehe docs/minimap.md, „Licht“.
 */
final class Licht {

    /** {@code BlockFactor} ohne Flackern. */
    static final float BLOCK_FACTOR = 1.4f;
    /** {@code BrightnessFactor} bei der Vorgabe von {@code options.gamma}. */
    static final float BRIGHTNESS_FACTOR = 0.5f;

    /** [Himmelslicht][Blocklicht][Kanal]. */
    private final float[][][] tabelle = new float[16][16][];

    Licht(float[] umgebung, float[] himmel, float himmelsFaktor, float[] blockTint) {
        for (int sky = 0; sky < 16; sky++) {
            for (int block = 0; block < 16; block++) {
                tabelle[sky][block] = hell(umgebung, himmel, himmelsFaktor, blockTint, sky, block);
            }
        }
    }

    /** Die Lightmap aus den Attributen des Dimensionstyps, ohne Tageszeit und Wetter. */
    static Licht von(DimensionType typ) {
        EnvironmentAttributeMap attribute = typ.attributes();
        return new Licht(
                farbe(attribute, EnvironmentAttributes.AMBIENT_LIGHT_COLOR),
                farbe(attribute, EnvironmentAttributes.SKY_LIGHT_COLOR),
                attribute.applyModifier(EnvironmentAttributes.SKY_LIGHT_FACTOR,
                        EnvironmentAttributes.SKY_LIGHT_FACTOR.defaultValue()),
                farbe(attribute, EnvironmentAttributes.BLOCK_LIGHT_TINT));
    }

    private static float[] farbe(EnvironmentAttributeMap attribute, EnvironmentAttribute<Vector3fc> welche) {
        Vector3fc v = attribute.applyModifier(welche, welche.defaultValue());
        return new float[] {v.x(), v.y(), v.z()};
    }

    /** Helligkeit je Kanal bei Himmels- und Blocklicht von 0 bis 15, wie {@code lightmap.fsh}. */
    static float[] hell(float[] umgebung, float[] himmel, float himmelsFaktor, float[] blockTint, int sky, int block) {
        float b = block / 15f;
        float himmelHell = stufe(sky / 15f) * himmelsFaktor;
        float blockHell = stufe(b) * BLOCK_FACTOR;
        float mischung = 0.9f * (2 * b - 1) * (2 * b - 1);
        float[] farbe = new float[3];
        float max = 0;
        for (int c = 0; c < 3; c++) {
            float blockFarbe = blockTint[c] + (1 - blockTint[c]) * mischung;
            farbe[c] = Math.clamp(umgebung[c] + himmel[c] * himmelHell + blockFarbe * blockHell, 0f, 1f);
            max = Math.max(max, farbe[c]);
        }
        if (max == 0) {
            return farbe;
        }
        float rest = 1 - max;
        float skaliert = 1 - rest * rest * rest * rest;
        for (int c = 0; c < 3; c++) {
            farbe[c] += (farbe[c] * (skaliert / max) - farbe[c]) * BRIGHTNESS_FACTOR;
        }
        return farbe;
    }

    private static float stufe(float level) {
        return level / (4 - 3 * level);
    }

    /**
     * Helligkeit zu Lichtkoordinaten wie in {@code QuadInstance}: je Licht in Sechzehnteln
     * einer Stufe, linear zwischen den Stufen daneben wie die gefilterte Lightmap.
     */
    void hell(int lichtKoordinaten, float[] aus) {
        float block = Math.clamp((lichtKoordinaten & 0xFF) / 16f, 0f, 15f);
        float sky = Math.clamp(((lichtKoordinaten >>> 16) & 0xFF) / 16f, 0f, 15f);
        int b0 = (int) block, s0 = (int) sky;
        int b1 = Math.min(b0 + 1, 15), s1 = Math.min(s0 + 1, 15);
        float fb = block - b0, fs = sky - s0;
        for (int c = 0; c < 3; c++) {
            float unten = tabelle[s0][b0][c] + (tabelle[s0][b1][c] - tabelle[s0][b0][c]) * fb;
            float oben = tabelle[s1][b0][c] + (tabelle[s1][b1][c] - tabelle[s1][b0][c]) * fb;
            aus[c] = unten + (oben - unten) * fs;
        }
    }
}
