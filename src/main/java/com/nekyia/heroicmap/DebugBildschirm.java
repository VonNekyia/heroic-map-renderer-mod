package com.nekyia.heroicmap;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * Ist der Debug-Bildschirm offen, wie „Bei F3“ es braucht? Wie das Spiel es sagt, aber nicht, solange BetterF3 ihn
 * nach dem Schliessen noch ausblendet: Es lässt den Schalter des Spiels bis zum Ende seiner Animation an. Nur der
 * Render-Thread. Siehe docs/minimap.md, „Bei F3“.
 */
final class DebugBildschirm {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Das Feld, das bei BetterF3 sagt, dass es gerade schliesst; einmal aufgelöst, nur gelesen, null ohne BetterF3. */
    private static Field schliesst = feld("me.cominixo.betterf3.utils.Utils", "closingAnimation");

    private DebugBildschirm() {
    }

    static boolean offen(Minecraft mc) {
        return mc.debugEntries.isOverlayVisible() && !schliesst();
    }

    private static boolean schliesst() {
        Field f = schliesst;
        if (f == null) {
            return false;
        }
        try {
            return f.getBoolean(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Heroic Map: BetterF3 nicht lesbar, „Bei F3“ folgt dem Spiel", e);
            schliesst = null;
            return false;
        }
    }

    /**
     * Das öffentliche statische boolean-Feld {@code name} der Klasse {@code klasse}, oder null: still, wenn es die Klasse
     * nicht gibt, mit einer Zeile im Log, wenn sie da ist, das Feld aber nicht passt.
     */
    static Field feld(String klasse, String name) {
        Class<?> k;
        try {
            k = Class.forName(klasse, false, DebugBildschirm.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
        try {
            Field f = k.getField(name);
            if (f.getType() == boolean.class && Modifier.isStatic(f.getModifiers())) {
                return f;
            }
        } catch (NoSuchFieldException | SecurityException e) {
            // Unten mit einer Zeile im Log.
        }
        LOGGER.warn("Heroic Map: {} ohne passendes Feld {}, „Bei F3“ folgt dem Spiel", klasse, name);
        return null;
    }
}
