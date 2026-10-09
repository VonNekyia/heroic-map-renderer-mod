package com.nekyia.heroicmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Die Füllung einer Fläche als Blöcke: je Reihe von Blöcken die Spannen, deren Mitten nach der Regel
 * gerade/ungerade innen liegen, über alle Ringe; gleiche Spannen übereinander zu Rechtecken
 * zusammengefasst. Eckig an Blockkanten, also genau für Flächen auf ganzen Zahlen, und jedes Stück
 * nur einmal, so doppelt sich das Alpha nicht. Siehe docs/ebenen.md, „Flächen, Kreise und Linien“.
 */
final class Raster {

    private Raster() {
    }

    /**
     * Die Rechtecke {x0, z0, x1, z1, …} in Blöcken, Enden ausschliesslich, für die Ringe
     * {x0, z0, x1, z1, …}; null, wenn die Fläche mehr als {@code maxReihen} Reihen hoch ist oder
     * mehr als {@code maxRechtecke} Rechtecke braucht.
     */
    static int[] rechtecke(List<double[]> ringe, int maxReihen, int maxRechtecke) {
        double zmin = Double.MAX_VALUE, zmax = -Double.MAX_VALUE;
        List<double[]> kanten = new ArrayList<>();
        for (double[] r : ringe) {
            int n = r.length / 2;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                double z1 = r[2 * i + 1], z2 = r[2 * j + 1];
                zmin = Math.min(zmin, z1);
                zmax = Math.max(zmax, z1);
                if (z1 != z2) {
                    kanten.add(new double[] {r[2 * i], z1, r[2 * j], z2});
                }
            }
        }
        if (kanten.isEmpty()) {
            return new int[0];
        }
        // Reihe z hat ihre Mitten bei z + 0.5.
        long reiheVon = (long) Math.ceil(zmin - 0.5), reiheBis = (long) Math.ceil(zmax - 0.5);
        if (reiheBis - reiheVon > maxReihen) {
            return null;
        }
        kanten.sort((a, b) -> Double.compare(Math.min(a[1], a[3]), Math.min(b[1], b[3])));
        List<double[]> aktiv = new ArrayList<>();
        Map<Long, int[]> offen = new HashMap<>(), weiter = new HashMap<>();
        List<int[]> fertig = new ArrayList<>();
        int naechste = 0;
        double[] xs = new double[16];
        for (long z = reiheVon; z < reiheBis; z++) {
            double mitte = z + 0.5;
            while (naechste < kanten.size() && Math.min(kanten.get(naechste)[1], kanten.get(naechste)[3]) <= mitte) {
                aktiv.add(kanten.get(naechste++));
            }
            aktiv.removeIf(k -> Math.max(k[1], k[3]) <= mitte);
            int n = 0;
            for (double[] k : aktiv) {
                // Halboffen: eine Kante zählt, wenn die Mitte zwischen ihren Enden liegt, das untere eingeschlossen.
                if ((k[1] <= mitte) != (k[3] <= mitte)) {
                    if (n == xs.length) {
                        xs = Arrays.copyOf(xs, 2 * n);
                    }
                    xs[n++] = k[0] + (mitte - k[1]) * (k[2] - k[0]) / (k[3] - k[1]);
                }
            }
            Arrays.sort(xs, 0, n);
            weiter.clear();
            for (int i = 0; i + 1 < n; i += 2) {
                // Die Blöcke, deren Mitte x + 0.5 zwischen den Schnitten liegt.
                long a = (long) Math.ceil(xs[i] - 0.5), b = (long) Math.ceil(xs[i + 1] - 0.5);
                if (a >= b) {
                    continue;
                }
                long schluessel = a << 32 ^ (b & 0xFFFFFFFFL);
                int[] r = offen.remove(schluessel);
                if (r == null) {
                    r = new int[] {(int) a, (int) z, (int) b, (int) z + 1};
                    if (fertig.size() + offen.size() + weiter.size() >= maxRechtecke) {
                        return null;
                    }
                } else {
                    r[3] = (int) z + 1;
                }
                weiter.put(schluessel, r);
            }
            fertig.addAll(offen.values());
            Map<Long, int[]> tausch = offen;
            offen = weiter;
            weiter = tausch;
        }
        fertig.addAll(offen.values());
        int[] aus = new int[4 * fertig.size()];
        for (int i = 0; i < fertig.size(); i++) {
            System.arraycopy(fertig.get(i), 0, aus, 4 * i, 4);
        }
        return aus;
    }
}
