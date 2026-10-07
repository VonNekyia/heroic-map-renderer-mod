package com.nekyia.heroicmap;

import com.mojang.brigadier.CommandDispatcher;
import java.util.Locale;

/**
 * „Hierher teleportieren“ auf der Vollbildkarte: der Befehl dazu, und ob der Server ihn dem
 * Spieler erlaubt. Der Mod umgeht keine Rechte; die prüft der Server.
 * Siehe docs/vollbildkarte.md, „Bedienung“.
 */
final class Teleport {

    private Teleport() {
    }

    /**
     * Auf den obersten Block, der Bewegung aufhält, ohne Laub, in der Mitte des Blocks (x, z). Der
     * Server liest die Höhe selbst, auch in Chunks, die der Client nicht hat.
     */
    static String befehl(String dimension, int x, int z) {
        return String.format(Locale.ROOT,
                "execute in %s positioned %.1f 0 %.1f positioned over motion_blocking_no_leaves run tp @s ~ ~ ~",
                dimension, x + 0.5, z + 0.5);
    }

    /** Der Server schickt nur die Befehle, die der Spieler nutzen darf; fehlt einer, gibt es den Eintrag nicht. */
    static boolean erlaubt(CommandDispatcher<?> befehle) {
        return befehle.getRoot().getChild("execute") != null && befehle.getRoot().getChild("tp") != null;
    }
}
