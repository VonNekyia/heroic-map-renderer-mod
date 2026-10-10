package com.nekyia.heroicmap;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Die Füllung einer Fläche als Trapeze in der Welt: Bänder zwischen den z der Ecken und der
 * Kreuzungen von Kanten, je Band die Spannen zwischen zwei Kanten nach der Regel gerade/ungerade über
 * alle Ringe. Eine Spanne läuft über Bänder weiter, solange sie dieselben zwei Kanten hat. Genau, ohne
 * Treppen, jedes Stück nur einmal, so doppelt sich das Alpha nicht; jedes Trapez ist konvex. Siehe
 * docs/ebenen.md, „Flächen, Kreise und Linien“.
 */
final class Trapeze {

    /** So viele Trapeze je Punkt höchstens; eine ehrliche Fläche braucht rund zwei je Ecke, Kreuzungen etwas mehr. */
    static final int JE_PUNKT = 3;
    /** So viel Arbeit je Punkt höchstens, gezählt als Kanten je Band; darüber ist die Fläche ohne Füllung. */
    static final int ARBEIT_JE_PUNKT = 256;

    private Trapeze() {
    }

    /**
     * Die Trapeze {z0, xl0, xr0, z1, xl1, xr1, …} für die Ringe {x0, z0, x1, z1, …}: oben bei z0 von
     * xl0 bis xr0, unten bei z1 von xl1 bis xr1. Null, wenn es mehr als {@link #JE_PUNKT} je Punkt
     * würden oder die Arbeit {@link #ARBEIT_JE_PUNKT} je Punkt übersteigt.
     */
    static double[] von(List<double[]> ringe) {
        int punkte = 0, kanten = 0;
        for (double[] r : ringe) {
            punkte += r.length / 2;
        }
        // Kante i: x = b + s·z für z von z0 bis z1, z0 < z1; waagrechte fallen weg.
        double[] z0 = new double[punkte], z1 = new double[punkte], b = new double[punkte], s = new double[punkte];
        double[] ecken = new double[punkte];
        int e = 0;
        for (double[] r : ringe) {
            int n = r.length / 2;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                double xa = r[2 * i], za = r[2 * i + 1], xb = r[2 * j], zb = r[2 * j + 1];
                ecken[e++] = za;
                if (za == zb) {
                    continue;
                }
                if (za > zb) {
                    double t = xa;
                    xa = xb;
                    xb = t;
                    t = za;
                    za = zb;
                    zb = t;
                }
                z0[kanten] = za;
                z1[kanten] = zb;
                s[kanten] = (xb - xa) / (zb - za);
                b[kanten] = xa - s[kanten] * za;
                kanten++;
            }
        }
        Integer[] folge = new Integer[kanten];
        for (int i = 0; i < kanten; i++) {
            folge[i] = i;
        }
        Arrays.sort(folge, (p, q) -> Double.compare(z0[p], z0[q]));
        Arrays.sort(ecken);
        Zerlegung z = new Zerlegung(b, s, JE_PUNKT * punkte + 16, (long) ARBEIT_JE_PUNKT * punkte + 4096);
        int[] aktiv = new int[kanten];
        int na = 0, naechste = 0;
        for (int k = 0; k < ecken.length; k++) {
            double za = ecken[k];
            if (k + 1 < ecken.length && ecken[k + 1] == za) {
                continue;
            }
            int m = 0;
            for (int i = 0; i < na; i++) {
                if (z1[aktiv[i]] > za) {
                    aktiv[m++] = aktiv[i];
                }
            }
            na = m;
            while (naechste < kanten && z0[folge[naechste]] <= za) {
                aktiv[na++] = folge[naechste++];
            }
            double zb = Double.NaN;
            for (int i = k + 1; i < ecken.length; i++) {
                if (ecken[i] > za) {
                    zb = ecken[i];
                    break;
                }
            }
            if (Double.isNaN(zb)) {
                break;
            }
            if (!z.band(aktiv, na, za, zb)) {
                return null;
            }
        }
        return z.schliesse();
    }

    /** Die Spannen von Band zu Band, offen je linker Kante, und die fertigen Trapeze. */
    private static final class Zerlegung {

        private final double[] b, s;
        private final int deckel;
        private long arbeit;
        private Map<Integer, double[]> offen = new HashMap<>(), weiter = new HashMap<>();
        private double[] aus = new double[48];
        private int n;
        private double zuletzt;

        Zerlegung(double[] b, double[] s, int deckel, long arbeit) {
            this.b = b;
            this.s = s;
            this.deckel = deckel;
            this.arbeit = arbeit;
        }

        private double x(int kante, double z) {
            return b[kante] + s[kante] * z;
        }

        /** Das Band [za, zb] mit diesen Kanten, an Kreuzungen geteilt; false über einem Deckel. */
        boolean band(int[] aktiv, int na, double za, double zb) {
            double z = za;
            while (z < zb) {
                double ende = zb;
                // Kürzen, bis im Band keine zwei Kanten mehr die Reihenfolge tauschen.
                while (true) {
                    arbeit -= na + 1;
                    if (arbeit < 0) {
                        return false;
                    }
                    sortiere(aktiv, na, (z + ende) / 2);
                    double kreuz = ende;
                    for (int i = 0; i + 1 < na; i++) {
                        int l = aktiv[i], r = aktiv[i + 1];
                        if (s[l] != s[r] && (vertauscht(l, r, z) || vertauscht(l, r, ende))) {
                            double zk = (b[r] - b[l]) / (s[l] - s[r]);
                            double eps = 1e-9 * (1 + Math.abs(zk));
                            if (zk > z + eps && zk < kreuz - eps) {
                                kreuz = zk;
                            }
                        }
                    }
                    if (kreuz == ende) {
                        break;
                    }
                    ende = kreuz;
                }
                if (!spannen(aktiv, na, z)) {
                    return false;
                }
                z = ende;
                zuletzt = ende;
            }
            return true;
        }

        private boolean vertauscht(int l, int r, double z) {
            double xl = x(l, z), xr = x(r, z);
            return xl > xr + 1e-9 * (1 + Math.abs(xl));
        }

        /** Nach x in der Mitte des Bands; Einfügen, denn von Band zu Band ändert sich die Reihenfolge kaum. */
        private void sortiere(int[] aktiv, int na, double zm) {
            for (int i = 1; i < na; i++) {
                int k = aktiv[i];
                double xk = x(k, zm);
                int j = i - 1;
                while (j >= 0 && x(aktiv[j], zm) > xk) {
                    aktiv[j + 1] = aktiv[j];
                    j--;
                }
                aktiv[j + 1] = k;
            }
        }

        /** Die Spannen ab z: gleiche Kanten laufen weiter, die übrigen enden bei z als Trapez. */
        private boolean spannen(int[] aktiv, int na, double z) {
            weiter.clear();
            for (int i = 0; i + 1 < na; i += 2) {
                double[] o = offen.remove(aktiv[i]);
                if (o == null || o[0] != aktiv[i + 1]) {
                    if (o != null) {
                        offen.put(aktiv[i], o);
                    }
                    o = new double[] {aktiv[i + 1], z};
                }
                weiter.put(aktiv[i], o);
            }
            for (Map.Entry<Integer, double[]> o : offen.entrySet()) {
                if (!trapez(o.getKey(), (int) o.getValue()[0], o.getValue()[1], z)) {
                    return false;
                }
            }
            Map<Integer, double[]> t = offen;
            offen = weiter;
            weiter = t;
            return true;
        }

        private boolean trapez(int l, int r, double za, double zb) {
            if (zb <= za) {
                return true;
            }
            if (n / 6 >= deckel) {
                return false;
            }
            if (n + 6 > aus.length) {
                aus = Arrays.copyOf(aus, 2 * aus.length);
            }
            aus[n++] = za;
            aus[n++] = x(l, za);
            aus[n++] = x(r, za);
            aus[n++] = zb;
            aus[n++] = x(l, zb);
            aus[n++] = x(r, zb);
            return true;
        }

        double[] schliesse() {
            for (Map.Entry<Integer, double[]> o : offen.entrySet()) {
                if (!trapez(o.getKey(), (int) o.getValue()[0], o.getValue()[1], zuletzt)) {
                    return null;
                }
            }
            return Arrays.copyOf(aus, n);
        }
    }
}
