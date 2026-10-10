package com.nekyia.heroicmap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Die Tafeln vom Plugin: je Objekt und {@code version} über den Kanal gefragt, ohne Antwort nach
 * {@link #WARTEN_MS} einmal neu, höchstens {@link #MAX} behalten; eine neue {@code version} einer
 * Ebene leert ihre Tafeln. Siehe docs/ebenen.md, „Infotafel“.
 */
final class Tafeln {

    /** Wie der Mod fragt; true, wenn die Frage hinausging. Nur der Gametest setzt einen anderen. */
    static Predicate<Ziel> fragen = Kanal::frageTafel;
    static final Tafeln INSTANZ = new Tafeln(z -> fragen.test(z));
    /** So viele Tafeln behält der Mod, wie mit dem Plugin vereinbart; so viele Fragen merkt er sich höchstens. */
    static final int MAX = 256, MAX_GEFRAGT = 1024;
    /** So lange wartet der Mod auf eine Antwort, bevor er einmal neu fragt, und danach noch einmal, bevor er aufgibt. */
    static final long WARTEN_MS = 5000;
    /** So lange ohne Antwort zeigt die Karte nichts, erst danach „lädt …“; so blitzt es bei einem Ziel ohne Tafel nicht. */
    static final long LAEDT_MS = 200;

    /** Das Ziel eines Orts: die {@code version} der Daten, aus denen er kommt, nicht die der Liste. */
    static Ziel ziel(String ebene, Ebenen.Ort o) {
        return new Ziel(ebene, o.version(), o.id());
    }

    /** Das Ziel einer Fläche oder eines Kreises: die {@code version} der gezeichneten Daten seiner Ebene. */
    static Ziel ziel(Ebenen e, String ebene, String id) {
        return new Ziel(ebene, e.version(ebene), id);
    }

    /** Wird die Ebene des Ziels noch gezeichnet, in der {@code version} des Ziels? Sonst geht seine Tafel zu. */
    static boolean gilt(Ebenen e, Ziel z) {
        return z.version().equals(e.version(z.ebene())) && e.sichtbar().stream().anyMatch(x -> x.id().equals(z.ebene()));
    }

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
                return new Antwort(new Ziel(ebene, version, id), json.has("panel") ? panel(json) : null);
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** Ein unlesbares {@code panel} oder eins ohne gültige Bausteine heisst: ohne Tafel. */
        private static Tafel panel(JsonObject json) {
            try {
                Tafel t = Tafel.lies(json.getAsJsonObject("panel"));
                return t.bausteine().isEmpty() ? null : t;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** Eine offene Frage: wann sie zuletzt hinausging und wie oft. */
    private record Frage(long seit, int mal) {
    }

    private final Predicate<Ziel> frage;
    private final Map<Ziel, Optional<Tafel>> tafeln = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Ziel, Optional<Tafel>> aelteste) {
            return size() > MAX;
        }
    };
    private final Map<Ziel, Frage> gefragt = new LinkedHashMap<>();
    private final Map<String, String> versionen = new HashMap<>();

    Tafeln(Predicate<Ziel> frage) {
        this.frage = frage;
    }

    /**
     * Die Tafel des Ziels: leer, wenn es keine hat; null, solange die Antwort aussteht. Beim ersten
     * Mal fragt sie das Plugin, nach {@link #WARTEN_MS} ohne Antwort einmal neu. Bleibt auch die
     * zweite ohne Antwort, gilt das Ziel als ohne Tafel. Geht keine Frage hinaus, weil der Server den
     * Kanal nicht hört, ist es ohne Tafel, ungemerkt.
     */
    Optional<Tafel> tafel(Ziel z, long ms) {
        neueVersion(z);
        Optional<Tafel> t = tafeln.get(z);
        if (t != null) {
            return t;
        }
        Frage f = gefragt.get(z);
        if (f != null && ms - f.seit() < WARTEN_MS) {
            return null;
        }
        if (f != null && f.mal() >= 2) {
            gefragt.remove(z);
            tafeln.put(z, Optional.empty());
            return Optional.empty();
        }
        if (!frage.test(z)) {
            // Ohne Kanal ohne Tafel, aber nicht gemerkt: Hört der Server später, fragt der Mod dann.
            return Optional.empty();
        }
        gefragt.remove(z);
        gefragt.put(z, new Frage(ms, f == null ? 1 : 2));
        if (gefragt.size() > MAX_GEFRAGT) {
            Iterator<Ziel> aelteste = gefragt.keySet().iterator();
            aelteste.next();
            aelteste.remove();
        }
        return null;
    }

    /** Eine Antwort; nur auf eine Frage, die noch offen ist. */
    void antwort(Antwort a) {
        if (gefragt.remove(a.ziel()) != null) {
            neueVersion(a.ziel());
            tafeln.put(a.ziel(), Optional.ofNullable(a.tafel()));
        }
    }

    private void neueVersion(Ziel z) {
        String alt = versionen.put(z.ebene(), z.version());
        if (alt != null && !alt.equals(z.version())) {
            tafeln.keySet().removeIf(k -> k.ebene().equals(z.ebene()));
            gefragt.keySet().removeIf(k -> k.ebene().equals(z.ebene()) && !k.version().equals(z.version()));
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
     * Ziel hält sie, bis zum Knopf, Escape oder einem Klick daneben. Von Hand geschlossen, öffnet sie
     * erst wieder, wenn der Zeiger ein anderes Ziel berührt hat.
     */
    static final class Zeigen {

        static final long RUHE_MS = 50, NACHLAUF_MS = 300;
        /** Das Ziel unter dem Zeiger, das offene und das unter dem Zeiger, als die Tafel von Hand zuging. */
        private Ziel unter, offen, gesperrt;
        private long seit, weg = -1;
        private boolean gehalten;

        /** Je Frame: das Ziel unter dem Zeiger, oder null, und ob der Zeiger über der offenen Tafel liegt. */
        void zeiger(Ziel z, boolean ueberTafel, long ms) {
            if (!Objects.equals(z, unter)) {
                unter = z;
                seit = ms;
                gesperrt = null;
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
            if (z != null && !gehalten && !ueberTafel && !z.equals(offen) && !z.equals(gesperrt) && ms - seit >= RUHE_MS) {
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

        /**
         * Schliesst die Tafel von Hand, mit Escape, × oder einem Klick; true, wenn eine offen war. So
         * schliessen Escape und ein Klick daneben zuerst nur sie. Das Ziel unter dem Zeiger öffnet sie
         * nicht gleich wieder.
         */
        boolean schliesse() {
            boolean war = offen != null;
            offen = null;
            gesperrt = unter;
            gehalten = false;
            weg = -1;
            return war;
        }

        /** Die Antwort zur offenen Tafel, null, solange sie aussteht: Gibt es keine, geht sie zu, auch gehalten. */
        void antwort(Optional<Tafel> t) {
            if (offen != null && t != null && t.isEmpty()) {
                zu();
            }
        }

        /**
         * Schliesst die Tafel von selbst, ohne Hand: Das geschlossene Ziel öffnet erst wieder, wenn der
         * Zeiger ein anderes berührt hat; ein anderes Ziel unter dem Zeiger öffnet wie sonst.
         */
        void zu() {
            gesperrt = offen;
            offen = null;
            gehalten = false;
            weg = -1;
        }

        Ziel offen() {
            return offen;
        }

        boolean gehalten() {
            return gehalten;
        }
    }
}
