package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Das Feld von BetterF3 nur, wenn es passt; sonst null, und „Bei F3“ folgt dem Spiel. Siehe docs/minimap.md, „Bei F3“. */
class DebugBildschirmTest {

    /** Steht für die Klasse von BetterF3, mit einem passenden Feld und mehreren, die nicht passen. */
    public static final class Fremd {
        public static boolean schliesst = true;
        public static int zahl;
        public boolean nichtStatisch;
    }

    @Test
    void nurEinStatischesBoolean() throws Exception {
        String name = Fremd.class.getName();
        assertEquals(true, DebugBildschirm.feld(name, "schliesst").getBoolean(null));
        assertNull(DebugBildschirm.feld("gibt.es.nicht", "closingAnimation"));
        assertNull(DebugBildschirm.feld(name, "fehlt"));
        assertNull(DebugBildschirm.feld(name, "zahl"));
        assertNull(DebugBildschirm.feld(name, "nichtStatisch"));
    }
}
