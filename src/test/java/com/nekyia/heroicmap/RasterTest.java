package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Die Füllung einer Fläche als Rechtecke aus Blöcken. Siehe docs/ebenen.md, „Flächen, Kreise und Linien“. */
class RasterTest {

    /** Die Rechtecke sortiert, die Reihenfolge aus Raster ist beliebig. */
    private static int[][] sortiert(int[] r) {
        List<int[]> l = new ArrayList<>();
        for (int i = 0; i < r.length; i += 4) {
            l.add(new int[] {r[i], r[i + 1], r[i + 2], r[i + 3]});
        }
        l.sort(Comparator.<int[]>comparingInt(a -> a[1]).thenComparingInt(a -> a[0]));
        return l.toArray(int[][]::new);
    }

    private static int[][] raster(double[]... ringe) {
        return sortiert(Raster.rechtecke(List.of(ringe), 1000, 1000));
    }

    @Test
    void quadratIstEinRechteck() {
        assertArrayEquals(new int[][] {{0, 0, 10, 10}}, raster(new double[] {0, 0, 10, 0, 10, 10, 0, 10}));
        // Drehsinn beliebig.
        assertArrayEquals(new int[][] {{0, 0, 10, 10}}, raster(new double[] {0, 10, 10, 10, 10, 0, 0, 0}));
    }

    @Test
    void lFormZweiRechtecke() {
        assertArrayEquals(new int[][] {{0, 0, 10, 5}, {0, 5, 5, 10}}, raster(new double[] {0, 0, 10, 0, 10, 5, 5, 5, 5, 10, 0, 10}));
    }

    @Test
    void lochUndGeradeUngerade() {
        double[] aussen = {0, 0, 10, 0, 10, 10, 0, 10}, loch = {3, 3, 7, 3, 7, 7, 3, 7};
        assertArrayEquals(new int[][] {{0, 0, 10, 3}, {0, 3, 3, 7}, {7, 3, 10, 7}, {0, 7, 10, 10}}, raster(aussen, loch));
        // Zwei Quadrate, die sich überlappen: Wo beide liegen, ist nach gerade/ungerade nichts.
        double[] zweites = {5, 0, 15, 0, 15, 10, 5, 10};
        assertArrayEquals(new int[][] {{0, 0, 5, 10}, {10, 0, 15, 10}}, raster(aussen, zweites));
    }

    @Test
    void mittenDerBloeckeZaehlen() {
        // Ein Dreieck mit schräger Kante: Ein Block zählt, wenn seine Mitte innen liegt.
        assertArrayEquals(new int[][] {{0, 0, 3, 1}, {0, 1, 2, 2}, {0, 2, 1, 3}}, raster(new double[] {0, 0, 4, 0, 0, 4}));
    }

    @Test
    void zuGrossGibtNull() {
        assertNull(Raster.rechtecke(List.<double[]>of(new double[] {0, 0, 1, 0, 1, 200, 0, 200}), 100, 1000));
        assertNull(Raster.rechtecke(List.<double[]>of(new double[] {0, 0, 10, 0, 10, 5, 5, 5, 5, 10, 0, 10}), 1000, 1));
    }
}
