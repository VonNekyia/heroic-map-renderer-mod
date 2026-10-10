package com.nekyia.heroicmap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Die Tafeln vom Plugin: je Objekt und {@code version} einmal über den Kanal gefragt, höchstens
 * {@link #MAX} behalten; eine neue {@code version} einer Ebene leert ihre Tafeln. Siehe
 * docs/ebenen.md, „Infotafel“.
 */
final class Tafeln {

    static final Tafeln INSTANZ = new Tafeln(Kanal::frageTafel);
    /** So viele Tafeln behält der Mod, wie mit dem Plugin vereinbart; so viele Fragen merkt er sich höchstens. */
    static final int MAX = 256, MAX_GEFRAGT = 1024;

    /** Ein Objekt einer Ebene in einer {@code version}. */
    record Ziel(String ebene, String version, String id) {
    }

    /** Eine Antwort des Plugins: die Tafel, oder null, wenn das Objekt keine hat oder die {@code version} alt ist. */
    record Antwort(Ziel ziel, Tafel tafel) {

        /** Liest eine Nachricht {@code tafel} auf dem Thread des Netzes; null, wenn sie keine ist oder nicht taugt. */
        static Antwort lies(String text) {
            try {
                JsonObject json = JsonParser.parseString(text).getAsJsonObject();
                if (json.get("v").getAsInt() != 1 || !"tafel".equals(json.get("typ").getAsString())) {
                    return null;
                }
                String ebene = Ebenen.text(json, "ebene", Ebenen.MAX_KENNUNG), version = Ebenen.text(json, "version", Ebenen.MAX_KENNUNG);
                String id = Ebenen.text(json, "id", Ebenen.MAX_TEXT);
                if (ebene == null || version == null || id == null) {
                    return null;
                }
                return new Antwort(new Ziel(ebene, version, id), json.has("panel") ? Tafel.lies(json.getAsJsonObject("panel")) : null);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private final Consumer<Ziel> frage;
    private final Map<Ziel, Optional<Tafel>> tafeln = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Ziel, Optional<Tafel>> aelteste) {
            return size() > MAX;
        }
    };
    private final Set<Ziel> gefragt = new LinkedHashSet<>();
    private final Map<String, String> versionen = new HashMap<>();

    Tafeln(Consumer<Ziel> frage) {
        this.frage = frage;
    }

    /**
     * Die Tafel des Ziels: leer, wenn es keine hat; null, solange die Antwort aussteht. Beim ersten
     * Mal fragt sie das Plugin.
     */
    Optional<Tafel> tafel(Ziel z) {
        neueVersion(z);
        Optional<Tafel> t = tafeln.get(z);
        if (t == null && gefragt.add(z)) {
            if (gefragt.size() > MAX_GEFRAGT) {
                Iterator<Ziel> aelteste = gefragt.iterator();
                aelteste.next();
                aelteste.remove();
            }
            frage.accept(z);
        }
        return t;
    }

    /** Eine Antwort; nur auf eine Frage, die noch offen ist. */
    void antwort(Antwort a) {
        if (gefragt.remove(a.ziel())) {
            neueVersion(a.ziel());
            tafeln.put(a.ziel(), Optional.ofNullable(a.tafel()));
        }
    }

    private void neueVersion(Ziel z) {
        String alt = versionen.put(z.ebene(), z.version());
        if (alt != null && !alt.equals(z.version())) {
            tafeln.keySet().removeIf(k -> k.ebene().equals(z.ebene()));
            gefragt.removeIf(k -> k.ebene().equals(z.ebene()) && !k.version().equals(z.version()));
        }
    }

    /**
     * Liegt der Punkt (x, z) der Welt in der Fläche, nach gerade/ungerade über alle Ringe, oder im
     * Kreis? Linien und Schrift haben keine Tafel.
     */
    static boolean trifft(Ebenen.Form f, double x, double z) {
        return switch (f) {
            case Ebenen.Kreis k -> Math.hypot(x - k.x(), z - k.z()) <= k.radius();
            case Ebenen.Flaeche fl -> {
                double[] b = fl.box();
                boolean innen = false;
                if (x >= b[0] && x <= b[2] && z >= b[1] && z <= b[3]) {
                    for (double[] r : fl.ringe()) {
                        int n = r.length / 2;
                        for (int i = 0, j = n - 1; i < n; j = i++) {
                            double xi = r[2 * i], zi = r[2 * i + 1], xj = r[2 * j], zj = r[2 * j + 1];
                            // Eine Kante zählt, wenn sie die Waagrechte durch z kreuzt, rechts vom Punkt.
                            if ((zi > z) != (zj > z) && x < xi + (xj - xi) * (z - zi) / (zj - zi)) {
                                innen = !innen;
                            }
                        }
                    }
                }
                yield innen;
            }
            default -> false;
        };
    }

    int behalten() {
        return tafeln.size();
    }

    /** Beim Trennen und bei jedem neuen Login. */
    void leeren() {
        tafeln.clear();
        gefragt.clear();
        versionen.clear();
    }

    /**
     * Wann die Vollbildkarte eine Tafel zeigt: Ruht der Zeiger {@link #RUHE_MS} auf einem Ziel, geht
     * seine auf. Verlässt er Ziel und Tafel, geht sie nach {@link #NACHLAUF_MS} zu. Ein Klick auf das
     * Ziel hält sie, bis zum Knopf, Escape oder einem Klick daneben.
     */
    static final class Zeigen {

        static final long RUHE_MS = 150, NACHLAUF_MS = 300;
        private Ziel unter, offen;
        private long seit, weg = -1;
        private boolean gehalten;

        /** Je Frame: das Ziel unter dem Zeiger, oder null, und ob der Zeiger über der offenen Tafel liegt. */
        void zeiger(Ziel z, boolean ueberTafel, long ms) {
            if (!Objects.equals(z, unter)) {
                unter = z;
                seit = ms;
            }
            if (offen != null && !gehalten) {
                if (ueberTafel || offen.equals(z)) {
                    weg = -1;
                } else if (weg < 0) {
                    weg = ms;
                } else if (ms - weg >= NACHLAUF_MS) {
                    offen = null;
                    weg = -1;
                }
            }
            if (z != null && !gehalten && !ueberTafel && !z.equals(offen) && ms - seit >= RUHE_MS) {
                offen = z;
                weg = -1;
            }
        }

        /** Ein Klick auf ein Ziel hält seine Tafel offen. */
        void halte(Ziel z) {
            offen = z;
            gehalten = true;
            weg = -1;
        }

        /** Schliesst die Tafel; true, wenn eine offen war. So schliessen Escape und ein Klick daneben zuerst nur sie. */
        boolean schliesse() {
            boolean war = offen != null;
            offen = null;
            gehalten = false;
            weg = -1;
            return war;
        }

        Ziel offen() {
            return offen;
        }

        boolean gehalten() {
            return gehalten;
        }
    }
}
