package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Die Infotafel: Bausteine lesen und setzen. Siehe docs/ebenen.md, „Infotafel“. */
class TafelTest {

    /** Jedes Zeichen 6 breit, fett 7, Zeilen 10 hoch, Umbruch nach Zeichen. */
    private static final Tafel.Masse MASSE = new Tafel.Masse() {
        @Override
        public int breite(String text, boolean fett) {
            return text.length() * (fett ? 7 : 6);
        }

        @Override
        public List<String> umbruch(String text, int breite, boolean fett) {
            int je = Math.max(1, breite / (fett ? 7 : 6));
            List<String> aus = new ArrayList<>();
            for (int i = 0; i < text.length(); i += je) {
                aus.add(text.substring(i, Math.min(text.length(), i + je)));
            }
            return aus.isEmpty() ? List.of("") : aus;
        }

        @Override
        public int zeile() {
            return 10;
        }
    };

    private static Tafel lies(String bloecke) {
        return Tafel.lies(JsonParser.parseString("{\"blocks\":[" + bloecke + "]}").getAsJsonObject());
    }

    @Test
    void beispielDesFormats() {
        Tafel t = lies("{\"type\":\"columns\",\"columns\":[[{\"type\":\"title\",\"text\":\"§a✪ Hafenstadt\",\"color\":\"#40E53F\"},"
                + "{\"type\":\"lines\",\"lines\":[\"Nation: Nordreich\",\"Level: 3\"]}],[{\"type\":\"image\",\"image\":\"images/banner-nord.png\","
                + "\"width\":44,\"height\":80}]]},"
                + "{\"type\":\"section\",\"heading\":{\"image\":\"images/mitglieder.png\",\"width\":200,\"height\":50,\"alt\":\"Mitglieder\"},"
                + "\"blocks\":[{\"type\":\"lines\",\"lines\":[\"Bürgermeister: Anna\"]}]},"
                + "{\"type\":\"section\",\"heading\":{\"text\":\"Statistiken\"},\"blocks\":[{\"type\":\"rating\",\"rows\":["
                + "{\"label\":\"Bergbau\",\"value\":3,\"max\":4,\"color\":\"#E5C33F\"}]}]},"
                + "{\"type\":\"neu\",\"etwas\":1}");
        assertEquals(3, t.bausteine().size());
        Tafel.Spalten s = (Tafel.Spalten) t.bausteine().get(0);
        // Codes mit § sind gestrichen, die Farbe deckend.
        assertEquals(new Tafel.Titel("✪ Hafenstadt", 0xFF40E53F), s.links().get(0));
        assertEquals(new Tafel.Bild("images/banner-nord.png", 44, 80, -1, null), s.rechts().get(0));
        Tafel.Abschnitt a = (Tafel.Abschnitt) t.bausteine().get(1);
        assertEquals("Mitglieder", a.kopfBild().alt());
        assertEquals("Statistiken", ((Tafel.Abschnitt) t.bausteine().get(2)).kopfText());
    }

    @Test
    void grenzenUndTiefe() {
        // Spalten und Abschnitte nur oben; ein Bild über 512, ein Feld ausserhalb von images/ und eine Wertung über 20 Punkte fallen weg.
        Tafel t = lies("{\"type\":\"section\",\"blocks\":[{\"type\":\"columns\",\"columns\":[[],[]]},{\"type\":\"title\",\"text\":\"bleibt\"}]},"
                + "{\"type\":\"image\",\"image\":\"images/riesig.png\",\"width\":513,\"height\":10},"
                + "{\"type\":\"image\",\"image\":\"../x.png\",\"width\":10,\"height\":10},"
                + "{\"type\":\"rating\",\"rows\":[{\"label\":\"zu viel\",\"value\":1,\"max\":21},{\"label\":\"gut\",\"value\":2,\"max\":20}]},"
                + "{\"type\":\"title\",\"text\":\"" + "x".repeat(Tafel.MAX_TITEL + 1) + "\"}");
        assertEquals(2, t.bausteine().size());
        assertEquals(List.of(new Tafel.Titel("bleibt", Tafel.SCHRIFT)), ((Tafel.Abschnitt) t.bausteine().get(0)).inhalt());
        assertEquals(1, ((Tafel.Wertung) t.bausteine().get(1)).reihen().size());
        // Höchstens 64 Bausteine, ein Abschnitt zählt mit seinem Inhalt.
        StringBuilder viele = new StringBuilder("{\"type\":\"section\",\"blocks\":[");
        for (int i = 0; i < 10; i++) {
            viele.append(i == 0 ? "" : ",").append("{\"type\":\"title\",\"text\":\"t\"}");
        }
        viele.append("]}");
        for (int i = 0; i < 100; i++) {
            viele.append(",{\"type\":\"title\",\"text\":\"t\"}");
        }
        Tafel voll = lies(viele.toString());
        assertEquals(1 + 53, voll.bausteine().size());
        assertEquals(10, ((Tafel.Abschnitt) voll.bausteine().getFirst()).inhalt().size());
    }

    @Test
    void setzen() {
        // Titel 7 Zeichen fett 49 breit, eine Zeile 10 Zeichen 60 breit: die Tafel 60 breit; Titel, Abstand 4, Zeile.
        Tafel.Satz s = Tafel.setze(lies("{\"type\":\"title\",\"text\":\"Burgtal\"},{\"type\":\"lines\",\"lines\":[\"0123456789\"]}"), MASSE);
        assertEquals(60, s.breite());
        assertEquals(10 + Tafel.ABSTAND + 10, s.hoehe());
        assertEquals(new Tafel.Text(0, 14, "0123456789", Tafel.SCHRIFT, false), s.stuecke().get(1));
        // Breiter als der Inhalt: umbrochen auf höchstens BREITE.
        Tafel.Satz lang = Tafel.setze(lies("{\"type\":\"lines\",\"lines\":[\"" + "x".repeat(50) + "\"]}"), MASSE);
        assertEquals(Tafel.BREITE, lang.breite());
        assertEquals(2, lang.stuecke().size());
    }

    @Test
    void bilderNieVergroessert() {
        // 400 × 100 auf 200 verkleinert, gleiches Seitenverhältnis; 44 × 80 bleibt, rechtsbündig.
        Tafel.Satz s = Tafel.setze(lies("{\"type\":\"image\",\"image\":\"images/a.png\",\"width\":400,\"height\":100},"
                + "{\"type\":\"image\",\"image\":\"images/b.png\",\"width\":44,\"height\":80,\"align\":\"right\"}"), MASSE);
        Tafel.Bildstueck a = (Tafel.Bildstueck) s.stuecke().get(0), b = (Tafel.Bildstueck) s.stuecke().get(1);
        assertEquals(List.of(0, 0, 200, 50), List.of(a.x(), a.y(), a.breite(), a.hoehe()));
        assertEquals(List.of(200 - 44, 50 + Tafel.ABSTAND, 44, 80), List.of(b.x(), b.y(), b.breite(), b.hoehe()));
    }

    @Test
    void spaltenUndWertung() {
        // Die rechte Spalte so breit wie ihr Bild, die linke daneben; oben bündig.
        Tafel.Satz s = Tafel.setze(lies("{\"type\":\"columns\",\"columns\":[[{\"type\":\"lines\",\"lines\":[\"" + "a".repeat(20)
                + "\"]}],[{\"type\":\"image\",\"image\":\"images/b.png\",\"width\":44,\"height\":80}]]}"), MASSE);
        Tafel.Bildstueck bild = (Tafel.Bildstueck) s.stuecke().stream().filter(x -> x instanceof Tafel.Bildstueck).findFirst().orElseThrow();
        assertEquals(s.breite() - 44, bild.x());
        assertEquals(0, bild.y());
        assertEquals(80, s.hoehe());
        // Wertung: 3 von 4 Punkten in der Farbe, der vierte zu 25 % deckend.
        Tafel.Satz w = Tafel.setze(lies("{\"type\":\"rating\",\"rows\":[{\"label\":\"Bergbau\",\"value\":3,\"max\":4,\"color\":\"#E5C33F\"}]}"), MASSE);
        List<Tafel.Punkt> punkte = w.stuecke().stream().filter(x -> x instanceof Tafel.Punkt).map(x -> (Tafel.Punkt) x).toList();
        assertEquals(4, punkte.size());
        assertEquals(0xFFE5C33F, punkte.get(2).farbe());
        assertEquals(0x40E5C33F, punkte.get(3).farbe());
        // Die Spalte der Labels: „Bergbau“ 7 · 6 und die Lücke.
        assertEquals(7 * 6 + Tafel.LUECKE + 3 * (Tafel.PUNKT + 1), punkte.get(3).x());
        assertTrue(w.breite() <= Tafel.BREITE);
    }

    @Test
    void wertungSpalteNachDemBreitestenLabel() {
        // Masse im Test: 6 je Zeichen. „Wehr“ und „Handel“: Spalte 36 + 4, die Punkte dahinter.
        Tafel.Satz s = Tafel.setze(lies("{\"type\":\"rating\",\"rows\":[{\"label\":\"Wehr\",\"value\":1,\"max\":3},"
                + "{\"label\":\"Handel\",\"value\":2,\"max\":3}]}"), MASSE);
        Tafel.Punkt erster = (Tafel.Punkt) s.stuecke().stream().filter(x -> x instanceof Tafel.Punkt).findFirst().orElseThrow();
        assertEquals(6 * 6 + Tafel.LUECKE, erster.x());
        // Ein langes Label: Spalte höchstens LABEL, das Label abgeschnitten, so läuft es nicht in die Punkte.
        Tafel.Satz lang = Tafel.setze(lies("{\"type\":\"rating\",\"rows\":[{\"label\":\"" + "x".repeat(40)
                + "\",\"value\":1,\"max\":3}]}"), MASSE);
        Tafel.Text label = (Tafel.Text) lang.stuecke().getFirst();
        Tafel.Punkt punkt = (Tafel.Punkt) lang.stuecke().get(1);
        assertEquals(Tafel.LABEL, punkt.x());
        assertTrue(MASSE.breite(label.text(), false) <= Tafel.LABEL - Tafel.LUECKE);
        assertTrue(label.text().length() < 40);
    }

    @Test
    void faktorAbgerundet() {
        // 30 Pixel in einem Kasten von 48: nicht 60 breit, sondern 30; 16 in 48 dreimal; das Seitenverhältnis zählt.
        assertEquals(1, Tafel.faktor(30, 30, 48, 48));
        assertEquals(3, Tafel.faktor(16, 16, 48, 48));
        assertEquals(1, Tafel.faktor(30, 10, 48, 48));
        assertEquals(1, Tafel.faktor(64, 64, 48, 48));
    }

    @Test
    void kuerzenTeiltKeinSurrogatpaar() {
        // Im Test zählt jedes char 6: Ein Emoji ist zwei char, gekürzt bleibt es ganz oder fehlt ganz.
        String t = MASSE.kuerze("\uD83D\uDE00".repeat(10), 6 * 7, false);
        assertEquals(6, t.length());
        assertFalse(Character.isHighSurrogate(t.charAt(t.length() - 1)));
    }

    @Test
    void abschnittOhneUeberschriftOhneAbstand() {
        Tafel.Satz s = Tafel.setze(lies("{\"type\":\"section\",\"blocks\":[{\"type\":\"title\",\"text\":\"A\"}]}"), MASSE);
        assertEquals(0, ((Tafel.Text) s.stuecke().getFirst()).y());
    }

    @Test
    void ohneBausteine() {
        Tafel t = lies("");
        assertEquals(0, t.bausteine().size());
        assertEquals(0, Tafel.setze(t, MASSE).hoehe());
        assertNull(Tafeln.Antwort.lies("kein json"));
    }
}
