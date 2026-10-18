package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** Welche Modelle Bodenpflanzen sind: die Vorlagen und die Kette über parent. Siehe docs/minimap.md, „Pflanzen von oben“. */
class PflanzenTest {

    private static Identifier id(String text) {
        return Identifier.parse(text);
    }

    @Test
    void vorlagenWieImRenderer() {
        for (String v : new String[] {"block/cross", "block/tinted_cross", "block/cross_emissive", "block/crop",
                "block/template_seagrass", "block/flowerbed_1", "block/template_leaf_litter_4"}) {
            assertTrue(Pflanzen.vorlage(id(v)), v);
        }
        for (String v : new String[] {"block/block", "block/ladder", "block/vine", "block/grass", "beispiel:block/cross",
                "block/crossed"}) {
            assertFalse(Pflanzen.vorlage(id(v)), v);
        }
    }

    @Test
    void erbtUeberDieKetteJedesModellEinmal() {
        Map<Identifier, Identifier> eltern = new HashMap<>(Map.of(
                id("block/poppy"), id("block/cross"),
                id("block/short_grass"), id("block/tinted_cross"),
                id("beispiel:block/blume"), id("block/poppy"),
                id("block/ladder"), id("block/block"),
                id("block/a"), id("block/b"),
                id("block/b"), id("block/a")));
        AtomicInteger gelesen = new AtomicInteger();
        Function<Identifier, Identifier> lies = m -> {
            gelesen.incrementAndGet();
            return eltern.get(m);
        };
        Map<Identifier, Boolean> merker = new HashMap<>();
        assertTrue(Pflanzen.erbt(id("beispiel:block/blume"), lies, merker));
        assertEquals(2, gelesen.get());
        // Der Mohn steht schon im Merker; keine Datei wird ein zweites Mal gelesen.
        assertTrue(Pflanzen.erbt(id("block/poppy"), lies, merker));
        assertEquals(2, gelesen.get());
        assertTrue(Pflanzen.erbt(id("block/short_grass"), lies, merker));
        assertFalse(Pflanzen.erbt(id("block/ladder"), lies, merker));
        // Ohne Datei, und eine Schleife endet an der Tiefe.
        assertFalse(Pflanzen.erbt(id("block/fehlt"), lies, merker));
        assertFalse(Pflanzen.erbt(id("block/a"), lies, merker));
        // Eine Vorlage selbst ohne Lesen.
        int vorher = gelesen.get();
        assertTrue(Pflanzen.erbt(id("block/crop"), lies, merker));
        assertEquals(vorher, gelesen.get());
    }
}
