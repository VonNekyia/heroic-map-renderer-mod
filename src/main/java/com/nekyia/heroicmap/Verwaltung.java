package com.nekyia.heroicmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Die Schnellverwaltung der Vollbildkarte: eine Zeile je eigenes Objekt in der Dimension des Spielers, alle Einstellungen
 * nebeneinander. Hier steht, was ohne Minecraft geht: die Zeilen, Suche, Filter, Sortierung, Seiten und die Lage der Spalten;
 * die Knöpfe baut {@link Karte}. Siehe docs/verwaltung.md.
 */
final class Verwaltung {

    /** Was eine Zeile zeigt; ein altes Rechteck steht wie eine Region da, nur mit weniger Spalten. */
    enum Art { WEGPUNKT, REGION, LINIE, RECHTECK, TOD }

    /** Der Filter nach Art; Todespunkte zählen zu den Wegpunkten, alte Rechtecke zu den Regionen. */
    enum Filter { ALLE, WEGPUNKTE, REGIONEN, LINIEN }

    enum Sortierung { NAME, NAEHE, NEU }

    /**
     * Eine Zeile: das Objekt ({@link Wegpunkte.Punkt}, {@link Wegpunkte.EigeneForm} oder {@link Wegpunkte.Region}), seine Art, sein
     * Name oder null, seine Mitte in Blöcken, seine Ebene und wie neu es ist, grösser heisst neuer.
     */
    record Zeile(Art art, Object objekt, String name, double x, double z, int ebene, long neu) {
    }

    /** Alle Ebenen im Filter. */
    static final int ALLE_EBENEN = -1;

    /** Die Breiten der Spalten in Einheiten: Art, Farbe, Name, Schrift, Biegung, Schriftart, Ebene, je Knopf, Lücke dazwischen. */
    static final int ART = 10, FARBE = 12, NAME = 92, SCHRIFT = 32, BIEGUNG = 40, SCHRIFTART = 20, EBENE = 48, KNOPF = 14, LUECKE = 2;
    /** Die Spalten in einer Zeile, 314 Einheiten breit; in zwei Zeilen je Objekt 170. */
    static final int BREITE = ART + FARBE + NAME + SCHRIFT + BIEGUNG + SCHRIFTART + EBENE + 3 * KNOPF + 9 * LUECKE, BREITE_ZWEI = 170;
    /** So hoch ist eine Zeile samt Lücke. */
    static final int ZEILE = 22;

    /** Die Spalten in ihrer Folge. */
    enum Spalte { ART, FARBE, NAME, SCHRIFT, BIEGUNG, SCHRIFTART, EBENE, ANHEFTEN, HINSPRINGEN, LOESCHEN }

    /** Wo eine Spalte steht: links und oben in der Zeile des Objekts, in Einheiten, und wie breit. */
    record Lage(int x, int y, int breite) {
    }

    private Verwaltung() {
    }

    /**
     * Die Zeilen aller eigenen Objekte in {@code dimension}: Wegpunkte, Formen und alte Rechtecke, die Todespunkte zuletzt, der
     * neueste zuerst. Wie neu: Wegpunkte nach ihrer id, Formen und Rechtecke nach ihrer Stelle in der Datei.
     */
    static List<Zeile> zeilen(List<Wegpunkte.Punkt> punkte, List<Wegpunkte.EigeneForm> formen, List<Wegpunkte.Region> regionen, String dimension) {
        List<Zeile> aus = new ArrayList<>();
        for (Wegpunkte.Punkt p : punkte) {
            if (p.dimension().equals(dimension)) {
                aus.add(new Zeile(p.tod() ? Art.TOD : Art.WEGPUNKT, p, p.tod() ? null : p.name(), p.x() + 0.5, p.z() + 0.5, p.ebene(),
                        p.tod() ? p.zeit() : p.id()));
            }
        }
        for (int i = 0; i < formen.size(); i++) {
            Wegpunkte.EigeneForm f = formen.get(i);
            if (f.dimension().equals(dimension)) {
                double[] m = mitte(f);
                aus.add(new Zeile(f.region() ? Art.REGION : Art.LINIE, f, f.name(), m[0], m[1], f.ebene(), i));
            }
        }
        for (int i = 0; i < regionen.size(); i++) {
            Wegpunkte.Region r = regionen.get(i);
            if (r.dimension().equals(dimension)) {
                aus.add(new Zeile(Art.RECHTECK, r, null, (r.x0() + r.x1() + 1) / 2.0, (r.z0() + r.z1() + 1) / 2.0, r.ebene(), i));
            }
        }
        return aus;
    }

    /** Die Mitte des Kastens um die Ecken, in Blöcken. */
    static double[] mitte(Wegpunkte.EigeneForm f) {
        double x0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
        for (Wegpunkte.Ecke e : f.ecken()) {
            x0 = Math.min(x0, e.x());
            z0 = Math.min(z0, e.z());
            x1 = Math.max(x1, e.x());
            z1 = Math.max(z1, e.z());
        }
        return new double[] {(x0 + x1 + 1) / 2, (z0 + z1 + 1) / 2};
    }

    /**
     * Was das Panel zeigt: die Zeilen, deren Name {@code suche} enthält, gleich ob gross oder klein, leer heisst alle; nach Art und
     * Ebene gefiltert; sortiert nach Name, Nähe zu (sx, sz) oder neu zuerst. Todespunkte stehen immer am Ende, der neueste zuerst,
     * und eine Suche findet sie nicht. Ohne Namen stehen Objekte beim Namen hinter allen mit Namen.
     */
    static List<Zeile> auswahl(List<Zeile> alle, String suche, Filter art, int ebene, Sortierung sortierung, double sx, double sz) {
        String s = suche == null ? "" : suche.strip().toLowerCase(Locale.ROOT);
        Comparator<Zeile> nach = switch (sortierung) {
            case NAME -> Comparator.comparing((Zeile z) -> z.name() == null).thenComparing(z -> z.name() == null ? "" : z.name().toLowerCase(Locale.ROOT))
                    .thenComparing(Comparator.comparingLong(Zeile::neu).reversed());
            case NAEHE -> Comparator.comparingDouble((Zeile z) -> Math.hypot(z.x() - sx, z.z() - sz));
            case NEU -> Comparator.comparingInt((Zeile z) -> z.art() == Art.WEGPUNKT ? 0 : z.art() == Art.RECHTECK ? 2 : 1)
                    .thenComparing(Comparator.comparingLong(Zeile::neu).reversed());
        };
        nach = Comparator.comparing((Zeile z) -> z.art() == Art.TOD).thenComparing((a, b) -> a.art() == Art.TOD && b.art() == Art.TOD
                ? Long.compare(b.neu(), a.neu()) : 0).thenComparing(nach);
        return alle.stream()
                .filter(z -> s.isEmpty() || z.name() != null && z.name().toLowerCase(Locale.ROOT).contains(s))
                .filter(z -> switch (art) {
                    case ALLE -> true;
                    case WEGPUNKTE -> z.art() == Art.WEGPUNKT || z.art() == Art.TOD;
                    case REGIONEN -> z.art() == Art.REGION || z.art() == Art.RECHTECK;
                    case LINIEN -> z.art() == Art.LINIE;
                })
                .filter(z -> ebene == ALLE_EBENEN || z.ebene() == ebene)
                .sorted(nach)
                .toList();
    }

    /** Wie viele Objekte auf eine Seite passen, die {@code hoehe} Einheiten hoch ist; mindestens eins. */
    static int jeSeite(int hoehe, boolean zwei) {
        return Math.max(1, hoehe / (zwei ? 2 * ZEILE : ZEILE));
    }

    /** Wie viele Seiten es bei {@code anzahl} Objekten gibt; ohne Objekte eine leere. */
    static int seiten(int anzahl, int jeSeite) {
        return Math.max(1, (anzahl + jeSeite - 1) / jeSeite);
    }

    /** Die Seite {@code seite} (ab 0), auf die Seiten begrenzt. */
    static <T> List<T> seite(List<T> alle, int seite, int jeSeite) {
        int s = Math.clamp(seite, 0, seiten(alle.size(), jeSeite) - 1);
        return alle.subList(Math.min(alle.size(), s * jeSeite), Math.min(alle.size(), (s + 1) * jeSeite));
    }

    /** Braucht das Panel zwei Zeilen je Objekt, weil {@code platz} Einheiten für eine nicht reichen? */
    static boolean zweiZeilen(int platz) {
        return platz < BREITE;
    }

    /**
     * Wo jede Spalte steht: in einer Zeile alle nebeneinander; in zwei oben Art, Farbe, Name, Anheften, Hinspringen, Löschen, darunter
     * rechtsbündig Schrift, Biegung, Schriftart, Ebene.
     */
    static Lage[] spalten(boolean zwei) {
        Lage[] aus = new Lage[Spalte.values().length];
        if (!zwei) {
            int[] breiten = {ART, FARBE, NAME, SCHRIFT, BIEGUNG, SCHRIFTART, EBENE, KNOPF, KNOPF, KNOPF};
            int x = 0;
            for (int i = 0; i < breiten.length; i++) {
                aus[i] = new Lage(x, 0, breiten[i]);
                x += breiten[i] + LUECKE;
            }
            return aus;
        }
        int name = BREITE_ZWEI - ART - FARBE - 3 * KNOPF - 5 * LUECKE, x = 0;
        int[][] oben = {{Spalte.ART.ordinal(), ART}, {Spalte.FARBE.ordinal(), FARBE}, {Spalte.NAME.ordinal(), name},
            {Spalte.ANHEFTEN.ordinal(), KNOPF}, {Spalte.HINSPRINGEN.ordinal(), KNOPF}, {Spalte.LOESCHEN.ordinal(), KNOPF}};
        for (int[] s : oben) {
            aus[s[0]] = new Lage(x, 0, s[1]);
            x += s[1] + LUECKE;
        }
        // Die zweite Zeile rechtsbündig, so endet sie wie die erste.
        x = BREITE_ZWEI - (SCHRIFT + BIEGUNG + SCHRIFTART + EBENE + 3 * LUECKE);
        int[][] unten = {{Spalte.SCHRIFT.ordinal(), SCHRIFT}, {Spalte.BIEGUNG.ordinal(), BIEGUNG}, {Spalte.SCHRIFTART.ordinal(), SCHRIFTART},
            {Spalte.EBENE.ordinal(), EBENE}};
        for (int[] s : unten) {
            aus[s[0]] = new Lage(x, ZEILE, s[1]);
            x += s[1] + LUECKE;
        }
        return aus;
    }

    /** Die nächste Stufe von {@link Wegpunkte#SCHRIFTEN} um {@code schritt} von {@code schrift} aus, an den Enden fest. */
    static double schrift(double schrift, int schritt) {
        int i = 0;
        while (i < Wegpunkte.SCHRIFTEN.length - 1 && Wegpunkte.SCHRIFTEN[i] < schrift) {
            i++;
        }
        return Wegpunkte.SCHRIFTEN[Math.clamp(i + schritt, 0, Wegpunkte.SCHRIFTEN.length - 1)];
    }

    /** Die Biegung um {@code schritt} Stufen von {@link Wegpunkte#BIEGUNG_SCHRITT} weiter, bis ±{@link Wegpunkte#MAX_BIEGUNG}. */
    static int biegung(int biegung, int schritt) {
        return Math.clamp(biegung + schritt * Wegpunkte.BIEGUNG_SCHRITT, -Wegpunkte.MAX_BIEGUNG, Wegpunkte.MAX_BIEGUNG);
    }

    /** Die Ebene nach {@code ebene} in der Liste der eigenen Ebenen, nach der letzten die erste. */
    static int naechsteEbene(List<Wegpunkte.EigeneEbene> ebenen, int ebene) {
        for (int i = 0; i < ebenen.size(); i++) {
            if (ebenen.get(i).id() == ebene) {
                return ebenen.get((i + 1) % ebenen.size()).id();
            }
        }
        return ebenen.isEmpty() ? ebene : ebenen.getFirst().id();
    }
}
