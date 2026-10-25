package com.nekyia.heroicmap;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;
import net.minecraft.world.level.ChunkPos;

/**
 * Mit Relief: welche Chunks neu zu zeichnen sind, wenn ein Chunk abgezogen ist, für die Minimap und die eigene Karte. Je Chunk
 * merkt er sich seine Höhen beim letzten Abziehen und welche Chunks um ihn fehlten. Gehört einem Thread. Siehe
 * docs/minimap.md, „Relief“.
 */
final class Ring {

    /** Die Chunks, deren Codes an den Höhen eines Chunks hängen: im Westen und Norden der Rand, im Osten und Süden bis 2 der Schatten. */
    private static final int[][] VERSATZ = {{-1, 0}, {0, -1}, {1, 0}, {0, 1}, {1, 1}, {2, 1}, {1, 2}, {2, 2}};

    /** Ein abgezogener Chunk: ein Hash seiner 16 × 16 Höhen, 64 Bit, und welche Chunks um ihn fehlten ({@link Relief#fehlende}). */
    private record Gemalt(long hoehen, int fehlten) {
    }

    private final Long2ObjectOpenHashMap<Gemalt> gemalt = new Long2ObjectOpenHashMap<>();

    /**
     * Der Chunk (x, z) ist mit den 16 × 16 Höhen {@code hoehen} (WORLD_SURFACE) abgezogen, und um ihn fehlten die Chunks
     * {@code fehlten}: Haben sich die Höhen seit dem letzten Mal geändert, gehen die Chunks, deren Codes an ihnen hängen, an
     * {@code markiere}. Beim ersten Mal nur die, die ohne ihn abgezogen sind. Eine Änderung unter der Oberfläche markiert so
     * keinen.
     */
    void abgezogen(int x, int z, int[] hoehen, int fehlten, LongConsumer markiere) {
        long hash = hash(hoehen);
        Gemalt vorher = gemalt.put(ChunkPos.pack(x, z), new Gemalt(hash, fehlten));
        if (vorher != null && vorher.hoehen() == hash) {
            return;
        }
        for (int[] r : VERSATZ) {
            long k = ChunkPos.pack(x + r[0], z + r[1]);
            Gemalt nachbar = vorher == null ? gemalt.get(k) : null;
            if (vorher != null || nachbar != null && Relief.fehlte(nachbar.fehlten(), -r[0], -r[1])) {
                markiere.accept(k);
            }
        }
    }

    /** Vergisst alle Chunks. */
    void leeren() {
        gemalt.clear();
    }

    /** Vergisst die Chunks, für die {@code behalten} nicht gilt. */
    void behalte(LongPredicate behalten) {
        gemalt.keySet().removeIf((long k) -> !behalten.test(k));
    }

    /** 64 Bit statt der Höhen: So kostet ein Chunk der eigenen Karte, die sich alle merkt, nur wenige Bytes. */
    private static long hash(int[] hoehen) {
        long h = 0xcbf29ce484222325L;
        for (int v : hoehen) {
            h = (h ^ v) * 0x100000001b3L;
        }
        return 0;
    }
}
