package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
                "block/template_seagrass", "block/flowerbed_1", "block/template_leaf_litter_4", "block/stem_growth0", "block/stem_growth7",
                "block/stem_fruit"}) {
            assertTrue(Pflanzen.vorlage(id(v)), v);
        }
        for (String v : new String[] {"block/block", "block/ladder", "block/vine", "block/grass", "beispiel:block/cross",
                "block/crossed", "block/stem", "block/stem_fruits"}) {
            assertFalse(Pflanzen.vorlage(id(v)), v);
        }
    }

    @Test
    void jederZustandEingetragenAuchOhneDatei() {
        Map<String, Boolean> ziel = new HashMap<>();
        // Ohne Datei: jeder Zustand keine Pflanze, keiner fehlt im Merker.
        Pflanzen.eintragen(List.of("age=0", "age=1"), Map.of(), id -> true, ziel);
        assertEquals(Map.of("age=0", false, "age=1", false), ziel);
        // Mit Datei: nur, wer Modelle hat, die alle erben; ein Zustand ohne Eintrag ist keine.
        Pflanzen.eintragen(List.of("a", "b", "c", "d"),
                Map.of("a", Set.of(id("block/poppy")), "b", Set.of(id("block/poppy"), id("block/ladder")), "c", Set.of()),
                id -> id.getPath().equals("block/poppy"), ziel);
        assertEquals(true, ziel.get("a"));
        assertEquals(false, ziel.get("b"));
        assertEquals(false, ziel.get("c"));
        assertEquals(false, ziel.get("d"));
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
