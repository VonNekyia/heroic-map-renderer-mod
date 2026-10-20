package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Wegpunkte setzen, anheften, löschen und über den Neustart behalten; Regionen und Kreise anheften. Siehe docs/wegpunkte.md. */
class WegpunkteTest {

    private static final String WELT = "minecraft:overworld";
    private static final UUID SAM = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void ueberstehenDenNeustart(@TempDir Path ordner) {
        Wegpunkte vorher = new Wegpunkte();
        vorher.lies(ordner);
        vorher.setze(WELT, 12, -40);
        vorher.setze("minecraft:the_nether", 1, 2);
        vorher.umschalten(vorher.punkte().getFirst());
        vorher.umschalten(SAM);

        Wegpunkte nachher = new Wegpunkte();
        nachher.lies(ordner);
        assertEquals(List.of(new Wegpunkte.Punkt(1, WELT, 12, -40, 0, true, false, Wegpunkte.BASE), new Wegpunkte.Punkt(2, "minecraft:the_nether", 1, 2, 0, false, false, Wegpunkte.BASE)),
                nachher.punkte());
        assertTrue(nachher.angeheftet(SAM));
    }

    @Test
    void setzenLoeschenLoesen(@TempDir Path ordner) {
        Wegpunkte w = new Wegpunkte();
        w.lies(ordner);
        w.setze(WELT, 5, 5);
        // Auf demselben Block bleibt es einer.
        w.setze(WELT, 5, 5);
        assertEquals(1, w.punkte().size());
        w.umschalten(SAM);
        w.umschalten(SAM);
        assertFalse(w.angeheftet(SAM));
        w.loesche(w.punkte().getFirst());
        assertTrue(w.punkte().isEmpty());
        assertTrue(Files.exists(ordner.resolve("wegpunkte.json")));
        assertFalse(Files.exists(ordner.resolve("wegpunkte.json.tmp")));
    }

    @Test
    void regionenSetzenLoeschenBehalten(@TempDir Path ordner) {
        Wegpunkte w = new Wegpunkte();
        w.lies(ordner);
        // Die Ecken in beliebiger Folge: ein Rechteck der Blöcke samt beiden Ecken.
        w.setze(WELT, 10, -5, 2, 3);
        assertEquals(List.of(new Wegpunkte.Region(WELT, 2, -5, 10, 3, 0, false, Wegpunkte.BASE)), w.regionen());
        // Dieselbe Region noch einmal bleibt eine; ein Wegpunkt daneben bekommt die nächste Farbe.
        w.setze(WELT, 2, 3, 10, -5);
        w.setze(WELT, 0, 0);
        assertEquals(1, w.regionen().size());
        assertEquals(1, w.punkte().getFirst().farbe());
        // Der Block in der Ecke gehört dazu, der daneben nicht; eine andere Dimension nie.
        assertEquals(w.regionen().getFirst(), w.region(WELT, 10, 3));
        assertEquals(null, w.region(WELT, 11, 3));
        assertEquals(null, w.region("minecraft:the_nether", 5, 0));
        // Überlappen zwei, gilt die zuletzt gesetzte.
        w.setze(WELT, 4, 0, 6, 1);
        assertEquals(new Wegpunkte.Region(WELT, 4, 0, 6, 1, 2, false, Wegpunkte.BASE), w.region(WELT, 5, 0));

        Wegpunkte nachher = new Wegpunkte();
        nachher.lies(ordner);
        assertEquals(w.regionen(), nachher.regionen());
        nachher.loesche(nachher.regionen().getFirst());
        assertEquals(List.of(new Wegpunkte.Region(WELT, 4, 0, 6, 1, 2, false, Wegpunkte.BASE)), nachher.regionen());
    }

    @Test
    void hoechstens256Regionen() {
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        for (int i = 0; i < Wegpunkte.MAX_REGIONEN + 5; i++) {
            w.setze(WELT, i, 0, i, 0);
        }
        assertEquals(Wegpunkte.MAX_REGIONEN, w.regionen().size());
        // Auch aus der Datei nicht mehr; kaputte Einträge fallen weg.
        StringBuilder viele = new StringBuilder("{\"regionen\":[{\"dimension\":5},");
        for (int i = 0; i < Wegpunkte.MAX_REGIONEN + 5; i++) {
            viele.append(i == 0 ? "" : ",").append("{\"dimension\":\"").append(WELT).append("\",\"x0\":").append(i)
                    .append(",\"z0\":0,\"x1\":").append(i).append(",\"z1\":0,\"farbe\":3,\"minimap\":false}");
        }
        Wegpunkte gelesen = new Wegpunkte();
        gelesen.lies(JsonParser.parseString(viele.append("]}").toString()).getAsJsonObject());
        assertEquals(Wegpunkte.MAX_REGIONEN, gelesen.regionen().size());
    }

    @Test
    void verschiebenBehaeltFarbeUndAnheften(@TempDir Path ordner) {
        Wegpunkte w = new Wegpunkte();
        w.lies(ordner);
        w.setze(WELT, 1, 1);
        w.setze(WELT, 5, 5);
        w.umschalten(w.punkte().getFirst());
        Wegpunkte.Punkt erster = w.punkte().getFirst();
        assertTrue(w.verschiebe(erster, 10, -3));
        assertEquals(new Wegpunkte.Punkt(erster.id(), WELT, 10, -3, erster.farbe(), true, false, Wegpunkte.BASE), w.punkte().getFirst());
        // Auf einen besetzten Block nicht, und einen, den es nicht mehr gibt, auch nicht.
        assertFalse(w.verschiebe(w.punkte().getFirst(), 5, 5));
        assertFalse(w.verschiebe(erster, 20, 20));
        Wegpunkte nachher = new Wegpunkte();
        nachher.lies(ordner);
        assertEquals(10, nachher.punkte().getFirst().x());
    }

    @Test
    void festeIdsAuchAusAltenDateien() {
        // Ohne id, mit doppelter id: Der Mod vergibt neue, nach der höchsten.
        Wegpunkte w = new Wegpunkte();
        w.lies(JsonParser.parseString("""
                {"wegpunkte":[
                  {"id":7,"dimension":"minecraft:overworld","x":1,"z":1,"farbe":0,"minimap":false},
                  {"dimension":"minecraft:overworld","x":2,"z":2,"farbe":0,"minimap":false},
                  {"id":7,"dimension":"minecraft:overworld","x":3,"z":3,"farbe":0,"minimap":false}]}
                """).getAsJsonObject());
        assertEquals(List.of(7, 8, 9), w.punkte().stream().map(Wegpunkte.Punkt::id).toList());
        w.setze(WELT, 4, 4);
        assertEquals(10, w.punkte().getLast().id());
    }

    private static Wegpunkte.Ecke e(int x, int z) {
        return new Wegpunkte.Ecke(x, z);
    }

    @Test
    void formenMitEigenenEckenBleiben(@TempDir Path ordner) {
        Wegpunkte w = new Wegpunkte();
        w.lies(ordner);
        w.setze(WELT, 0, 0);
        // Zwei Ecken sind eine Linie, drei eine Region; die erste am Ende schliesst nur; eine allein ist keine Form.
        assertTrue(w.setzeForm(WELT, List.of(e(0, 0), e(10, 0))));
        assertTrue(w.setzeForm(WELT, List.of(e(0, 0), e(10, 0), e(10, 10), e(0, 0))));
        assertFalse(w.setzeForm(WELT, List.of(e(3, 3))));
        assertFalse(w.setzeForm(WELT, List.of(e(3, 3), e(3, 3))));
        assertFalse(w.eigeneFormen().get(0).region());
        assertTrue(w.eigeneFormen().get(1).region());
        assertEquals(List.of(e(0, 0), e(10, 0), e(10, 10)), w.eigeneFormen().get(1).ecken());
        Ebenen.Flaeche f = (Ebenen.Flaeche) w.form(w.eigeneFormen().get(1));
        assertArrayEquals(new double[] {0.5, 0.5, 10.5, 10.5}, f.box());
        // Ein Wegpunkt auf einer Ecke: verschoben oder gelöscht, die Form bleibt, wie sie ist.
        w.verschiebe(w.punkte().getFirst(), 20, 20);
        w.loesche(w.punkte().getFirst());
        assertEquals(List.of(e(0, 0), e(10, 0), e(10, 10)), w.eigeneFormen().get(1).ecken());
        // Angeheftet zählt in die gemeinsamen 64; über den Neustart bleibt alles, mit der Dimension.
        assertTrue(w.umschalten(w.eigeneFormen().get(1)));
        assertEquals(1, w.angeheftet());
        Wegpunkte nachher = new Wegpunkte();
        nachher.lies(ordner);
        assertEquals(w.eigeneFormen(), nachher.eigeneFormen());
        assertEquals(WELT, nachher.eigeneFormen().get(1).dimension());
    }

    @Test
    void eckeLoeschenUndEinfuegen() {
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        w.setzeForm(WELT, List.of(e(0, 0), e(10, 0), e(10, 10)));
        w.setzeForm(WELT, List.of(e(0, 20), e(10, 20)));
        Wegpunkte.EigeneForm region = w.eigeneFormen().get(0), linie = w.eigeneFormen().get(1);
        assertTrue(w.umschalten(region));
        region = w.eigeneFormen().get(0);
        // Eine Ecke zwischen der zweiten und dritten; Farbe, Anheften und Stelle bleiben.
        assertTrue(w.ersetze(region, List.of(e(0, 0), e(10, 0), e(15, 5), e(10, 10))));
        Wegpunkte.EigeneForm vier = w.eigeneFormen().get(0);
        assertEquals(List.of(e(0, 0), e(10, 0), e(15, 5), e(10, 10)), vier.ecken());
        assertEquals(region.farbe(), vier.farbe());
        assertTrue(vier.angeheftet());
        // Aus der Region mit drei Ecken wird beim Löschen einer Ecke eine Linie.
        assertTrue(w.ersetze(vier, List.of(e(0, 0), e(10, 0), e(10, 10))));
        assertTrue(w.ersetze(w.eigeneFormen().get(0), List.of(e(0, 0), e(10, 10))));
        assertFalse(w.eigeneFormen().get(0).region());
        // Die Linie mit zwei Ecken fällt beim Löschen einer Ecke weg.
        assertTrue(w.ersetze(linie, List.of(e(0, 20))));
        assertEquals(1, w.eigeneFormen().size());
        // Über 64 Ecken nimmt sie nicht; eine Form, die es nicht mehr gibt, ändert nichts.
        List<Wegpunkte.Ecke> viele = new java.util.ArrayList<>();
        for (int i = 0; i <= Wegpunkte.MAX_PUNKTE_FORM; i++) {
            viele.add(e(i, 0));
        }
        assertFalse(w.ersetze(w.eigeneFormen().get(0), viele));
        assertFalse(w.ersetze(linie, List.of(e(1, 1), e(2, 2))));
        assertEquals(List.of(e(0, 0), e(10, 10)), w.eigeneFormen().get(0).ecken());
    }

    private static Wegpunkte.Glied g(Wegpunkte w, int form, int index) {
        return Wegpunkte.Glied.von(w.eigeneFormen().get(form), index);
    }

    private static int bund(Wegpunkte w, int form, int index) {
        return w.eigeneFormen().get(form).ecken().get(index).bund();
    }

    /**
     * Verbinden (mod#162): Ecke mit der Ecke einer anderen Form und mit einem Wegpunkt; der angeklickte springt samt seinem Bund
     * auf den Block des Ziels, die Bünde werden einer. Nicht: zwei Wegpunkte, die eigene Form, ein Todespunkt, eine andere
     * Dimension, schon verbunden, eine Form zweimal in einem Bund. Lösen löst den ganzen Bund, alle bleiben. Siehe
     * docs/wegpunkte.md, „Verbinden“.
     */
    @Test
    void verbindenUndLoesen() {
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        w.setzeForm(WELT, List.of(e(0, 0), e(10, 0), e(10, 10)));
        w.setzeForm(WELT, List.of(e(11, 1), e(20, 1)));
        w.setzeForm("minecraft:the_nether", List.of(e(0, 0), e(5, 5)));
        w.setze(WELT, 30, 30);
        w.setze(WELT, 40, 40);
        w.setzeTod(WELT, 50, 50, 1);
        Wegpunkte.Glied p = Wegpunkte.Glied.von(w.punkte().get(0)), q = Wegpunkte.Glied.von(w.punkte().get(1));
        assertFalse(w.kannVerbinden(g(w, 0, 0), g(w, 0, 1)));
        assertFalse(w.kannVerbinden(p, q));
        assertFalse(w.kannVerbinden(g(w, 0, 0), Wegpunkte.Glied.von(w.tod())));
        assertFalse(w.kannVerbinden(g(w, 0, 0), g(w, 2, 0)));
        // Ecke mit Ecke: Die angeklickte springt auf den Block des Ziels.
        assertTrue(w.verbinde(g(w, 0, 1), g(w, 1, 0)));
        assertEquals(List.of(11, 1), List.of(w.eigeneFormen().get(0).ecken().get(1).x(), w.eigeneFormen().get(0).ecken().get(1).z()));
        int b1 = bund(w, 0, 1);
        assertTrue(b1 > 0);
        assertEquals(b1, bund(w, 1, 0));
        assertEquals(0, bund(w, 0, 0));
        assertFalse(w.kannVerbinden(g(w, 0, 1), g(w, 1, 0)));
        // Ecke mit Wegpunkt: Die Ecke springt auf seinen Block, er bleibt.
        assertTrue(w.verbinde(g(w, 1, 1), p));
        int b2 = w.punkte().get(0).bund();
        assertTrue(b2 > 0 && b2 != b1);
        assertEquals(new Wegpunkte.Ecke(30, 30, b2), w.eigeneFormen().get(1).ecken().get(1));
        // Ein Wegpunkt springt auf den Block einer Ecke; der Bund des Ziels nimmt ihn auf. Zwei Wegpunkte in einem Bund nicht.
        assertFalse(w.kannVerbinden(Wegpunkte.Glied.von(w.punkte().get(1)), g(w, 1, 1)));
        // Die Ecke (10, 10) von A in den Bund von P: Die Formen sind verschieden, also geht es.
        assertTrue(w.verbinde(g(w, 0, 2), Wegpunkte.Glied.von(w.punkte().get(0))));
        assertEquals(b2, bund(w, 0, 2));
        assertEquals(List.of(30, 30), List.of(w.eigeneFormen().get(0).ecken().get(2).x(), w.eigeneFormen().get(0).ecken().get(2).z()));
        // Nun liegen A und B in beiden Bünden: Sie zu vereinen hiesse eine Form zweimal in einem Bund.
        assertFalse(w.kannVerbinden(g(w, 0, 1), Wegpunkte.Glied.von(w.punkte().get(0))));
        // Lösen löst den ganzen Bund; alle bleiben, wo sie sind.
        w.loese(b2);
        assertEquals(0, w.punkte().get(0).bund());
        assertEquals(0, bund(w, 0, 2));
        assertEquals(0, bund(w, 1, 1));
        assertEquals(new Wegpunkte.Ecke(30, 30), w.eigeneFormen().get(1).ecken().get(1));
        assertEquals(b1, bund(w, 1, 0));
    }

    /**
     * Folgen (mod#162): Verschieben eines Wegpunkts, einer Ecke, am Griff und „Koordinaten setzen…“ ziehen alle Glieder des Bunds
     * auf denselben Block; nur die gemeinsame Ecke einer anderen Form folgt, ihre übrigen bleiben (D1). Träfe ein Wegpunkt des
     * Bunds auf einen anderen, bleibt alles (D7). Löschen nimmt nur dieses; wer allein bleibt, wird lose. Siehe docs/wegpunkte.md,
     * „Verbinden“.
     */
    @Test
    void verbundenesFolgt() {
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        w.setzeForm(WELT, List.of(e(0, 0), e(10, 0), e(10, 10)));
        w.setzeForm(WELT, List.of(e(10, 0), e(20, 0)));
        w.setze(WELT, 10, 10);
        w.setze(WELT, 40, 40);
        assertTrue(w.verbinde(g(w, 1, 0), g(w, 0, 1)));
        assertTrue(w.verbinde(g(w, 0, 2), Wegpunkte.Glied.von(w.punkte().get(0))));
        // Den Wegpunkt verschoben: Die Ecke (10, 10) von A folgt, die übrigen bleiben.
        assertTrue(w.verschiebe(w.punkte().get(0), 12, 12));
        assertEquals(List.of(e(0, 0), w.eigeneFormen().get(0).ecken().get(1), new Wegpunkte.Ecke(12, 12, bund(w, 0, 2))),
                w.eigeneFormen().get(0).ecken());
        assertEquals(List.of(12, 12), List.of(w.eigeneFormen().get(0).ecken().get(2).x(), w.eigeneFormen().get(0).ecken().get(2).z()));
        // Die Ecke (10, 0) von B verschoben: Die von A folgt, das Ende von B bleibt.
        List<Wegpunkte.Ecke> b = new java.util.ArrayList<>(w.eigeneFormen().get(1).ecken());
        b.set(0, b.get(0).auf(5, -5));
        assertTrue(w.ersetze(w.eigeneFormen().get(1), b));
        assertEquals(List.of(5, -5), List.of(w.eigeneFormen().get(0).ecken().get(1).x(), w.eigeneFormen().get(0).ecken().get(1).z()));
        assertEquals(List.of(20, 0), List.of(w.eigeneFormen().get(1).ecken().get(1).x(), w.eigeneFormen().get(1).ecken().get(1).z()));
        // Am Griff ganz A um (1, 1): Die Glieder folgen, B mit einer Ecke, der Wegpunkt mit seiner.
        assertTrue(w.ersetze(w.eigeneFormen().get(0), w.eigeneFormen().get(0).ecken().stream().map(c -> c.auf(c.x() + 1, c.z() + 1)).toList()));
        assertEquals(List.of(6, -4), List.of(w.eigeneFormen().get(1).ecken().get(0).x(), w.eigeneFormen().get(1).ecken().get(0).z()));
        assertEquals(List.of(13, 13), List.of(w.punkte().get(0).x(), w.punkte().get(0).z()));
        // D7: Die Ecke des Wegpunkts auf den anderen Wegpunkt; der Bund bleibt ganz, wo er war. Ebenso der Wegpunkt selbst.
        List<Wegpunkte.EigeneForm> vorher = List.copyOf(w.eigeneFormen());
        List<Wegpunkte.Ecke> a = new java.util.ArrayList<>(w.eigeneFormen().get(0).ecken());
        a.set(2, a.get(2).auf(40, 40));
        assertFalse(w.ersetze(w.eigeneFormen().get(0), a));
        assertFalse(w.verschiebe(w.punkte().get(0), 40, 40));
        assertEquals(vorher, w.eigeneFormen());
        assertEquals(List.of(13, 13), List.of(w.punkte().get(0).x(), w.punkte().get(0).z()));
        // Den Wegpunkt gelöscht: Die Ecke bleibt und ist allein, also lose. Ebenso nach dem Löschen von B die Ecke von A.
        w.loesche(w.punkte().get(0));
        assertEquals(0, bund(w, 0, 2));
        w.loesche(w.eigeneFormen().get(1));
        assertEquals(0, bund(w, 0, 1));
        assertEquals(List.of(e(1, 1), e(6, -4), e(13, 13)), w.eigeneFormen().get(0).ecken());
    }

    /**
     * Bünde in der Datei (mod#162): die dritte Zahl einer Ecke und {@code bund} am Wegpunkt, nur wenn verbunden, so bleibt eine
     * Datei ohne Verbindungen Byte für Byte, wie sie war. Beim Lesen gilt ein Bund nur für Glieder auf einem Block: Ein Glied, das
     * eine ältere Version verschob, wird lose, und zwei Gruppen auf zwei Blöcken bekommen zwei Bünde. Siehe docs/wegpunkte.md,
     * „Verbinden“ und „Ablage“.
     */
    @Test
    void buendeInDerDatei(@TempDir Path ordner) throws Exception {
        Wegpunkte w = new Wegpunkte();
        w.lies(ordner);
        w.setzeForm(WELT, List.of(e(0, 0), e(10, 0)));
        w.setzeForm(WELT, List.of(e(10, 0), e(20, 0)));
        w.setze(WELT, 30, 30);
        String ohne = Files.readString(ordner.resolve("wegpunkte.json"));
        assertFalse(ohne.contains("bund"), ohne);
        assertTrue(ohne.contains("[[0,0],[10,0]]"), ohne);
        assertTrue(w.verbinde(g(w, 1, 0), g(w, 0, 1)));
        assertTrue(w.verbinde(g(w, 1, 1), Wegpunkte.Glied.von(w.punkte().getFirst())));
        int b1 = bund(w, 0, 1), b2 = w.punkte().getFirst().bund();
        String mit = Files.readString(ordner.resolve("wegpunkte.json"));
        assertTrue(mit.contains("[[0,0],[10,0," + b1 + "]]"), mit);
        assertTrue(mit.contains("\"bund\":" + b2), mit);
        Wegpunkte nachher = new Wegpunkte();
        nachher.lies(ordner);
        assertEquals(w.eigeneFormen(), nachher.eigeneFormen());
        assertEquals(w.punkte(), nachher.punkte());
        // Wieder gelöst, steht die Datei wie ohne Bünde.
        w.loese(b1);
        w.loese(b2);
        assertFalse(Files.readString(ordner.resolve("wegpunkte.json")).contains("bund"));
        // Eine ältere Version verschob ein Glied: Es wird lose, und das andere auch, denn es bleibt allein. Zwei Gruppen eines Bunds
        // auf zwei Blöcken bekommen zwei Bünde.
        Wegpunkte alt = new Wegpunkte();
        alt.lies(JsonParser.parseString("{\"eigene_formen\":["
                + "{\"dimension\":\"" + WELT + "\",\"ecken\":[[0,0,7],[5,5,4]],\"farbe\":0,\"minimap\":false},"
                + "{\"dimension\":\"" + WELT + "\",\"ecken\":[[1,1,7],[5,5,4],[9,9,4]],\"farbe\":0,\"minimap\":false},"
                + "{\"dimension\":\"" + WELT + "\",\"ecken\":[[9,9,4],[8,8]],\"farbe\":0,\"minimap\":false}],"
                + "\"wegpunkte\":[{\"id\":1,\"dimension\":\"" + WELT + "\",\"x\":3,\"z\":3,\"farbe\":0,\"minimap\":false,\"bund\":9}]}")
                .getAsJsonObject());
        assertEquals(0, bund(alt, 0, 0));
        assertEquals(0, bund(alt, 1, 0));
        assertEquals(0, alt.punkte().getFirst().bund());
        assertEquals(4, bund(alt, 0, 1));
        assertEquals(4, bund(alt, 1, 1));
        assertTrue(bund(alt, 1, 2) > 0 && bund(alt, 1, 2) != 4);
        assertEquals(bund(alt, 1, 2), bund(alt, 2, 0));
        // Ein bund, das keine ganze Zahl über 0 ist, macht lose; Wegpunkt und Form bleiben (Review von #168).
        Wegpunkte kaputt = new Wegpunkte();
        kaputt.lies(JsonParser.parseString("{\"eigene_formen\":[{\"dimension\":\"" + WELT + "\",\"ecken\":[[0,0,\"x\"],[5,5,[1]],[9,0,1.5]],"
                + "\"farbe\":0,\"minimap\":false}],\"wegpunkte\":["
                + "{\"id\":1,\"dimension\":\"" + WELT + "\",\"x\":0,\"z\":0,\"farbe\":0,\"minimap\":false,\"bund\":\"zwei\"},"
                + "{\"id\":2,\"dimension\":\"" + WELT + "\",\"x\":5,\"z\":5,\"farbe\":0,\"minimap\":false,\"bund\":1e30},"
                + "{\"id\":3,\"dimension\":\"" + WELT + "\",\"x\":9,\"z\":0,\"farbe\":0,\"minimap\":false,\"bund\":-3}]}").getAsJsonObject());
        assertEquals(1, kaputt.eigeneFormen().size());
        assertEquals(List.of(0, 0, 0), kaputt.eigeneFormen().getFirst().ecken().stream().map(Wegpunkte.Ecke::bund).toList());
        assertEquals(List.of(0, 0, 0), kaputt.punkte().stream().map(Wegpunkte.Punkt::bund).toList());
    }

    /**
     * Folgt eine Ecke einem Bund, behält ihre Form alle übrigen Felder: Gewicht, Schrift, Biegung, Name und Farbe; ebenso beim
     * Verbinden, beim Lösen und wenn ein Glied gelöscht wird (Review von #168, `EigeneForm.mitEcken`). Siehe docs/wegpunkte.md,
     * „Verbinden“.
     */
    @Test
    void folgendeFormBehaeltIhreFelder() {
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        w.setzeForm(WELT, List.of(e(0, 0), e(10, 0), e(10, 10)));
        w.setzeForm(WELT, List.of(e(11, 1), e(20, 1), e(20, 10)));
        w.benenne(w.eigeneFormen().get(1), "Hof");
        w.faerbe(w.eigeneFormen().get(1), 0x3A7FD5);
        w.stil(w.eigeneFormen().get(1), 2, 45, 7);
        Wegpunkte.EigeneForm vorher = w.eigeneFormen().get(1);
        assertEquals(7, vorher.gewicht());
        assertTrue(w.verbinde(g(w, 1, 0), g(w, 0, 1)));
        felder(vorher, w.eigeneFormen().get(1));
        List<Wegpunkte.Ecke> a = new java.util.ArrayList<>(w.eigeneFormen().get(0).ecken());
        a.set(1, a.get(1).auf(12, -3));
        assertTrue(w.ersetze(w.eigeneFormen().get(0), a));
        assertEquals(List.of(12, -3), List.of(w.eigeneFormen().get(1).ecken().get(0).x(), w.eigeneFormen().get(1).ecken().get(0).z()));
        felder(vorher, w.eigeneFormen().get(1));
        w.loese(bund(w, 1, 0));
        felder(vorher, w.eigeneFormen().get(1));
        assertTrue(w.verbinde(g(w, 1, 0), g(w, 0, 1)));
        w.loesche(w.eigeneFormen().get(0));
        assertEquals(0, bund(w, 0, 0));
        felder(vorher, w.eigeneFormen().get(0));
    }

    private static void felder(Wegpunkte.EigeneForm soll, Wegpunkte.EigeneForm ist) {
        assertEquals(List.of(soll.name(), soll.hex(), soll.schrift(), soll.biegung(), soll.gewicht(), soll.farbe(), soll.ebene()),
                List.of(ist.name(), ist.hex(), ist.schrift(), ist.biegung(), ist.gewicht(), ist.farbe(), ist.ebene()));
    }

    @Test
    void formenGrenzenUndAlteDatei() {
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        List<Wegpunkte.Ecke> viele = new java.util.ArrayList<>();
        for (int i = 0; i <= Wegpunkte.MAX_PUNKTE_FORM; i++) {
            viele.add(e(i, 0));
        }
        assertFalse(w.setzeForm(WELT, viele));
        assertTrue(w.setzeForm(WELT, viele.subList(0, Wegpunkte.MAX_PUNKTE_FORM)));
        // Eine Datei von vor mod#112 nennt die ids von Wegpunkten: Sie werden zu Ecken auf deren Blöcken, Verweise ins
        // Leere und Punkte einer anderen Dimension fallen weg, unter zwei Ecken die Form.
        Wegpunkte alt = new Wegpunkte();
        alt.lies(JsonParser.parseString("""
                {"wegpunkte":[{"id":1,"dimension":"minecraft:overworld","x":1,"z":1,"farbe":0,"minimap":false},
                  {"id":2,"dimension":"minecraft:overworld","x":2,"z":2,"farbe":0,"minimap":false},
                  {"id":3,"dimension":"minecraft:the_nether","x":3,"z":3,"farbe":0,"minimap":false}],
                 "eigene_formen":[{"punkte":[1,99,3,2],"farbe":1,"minimap":true},{"punkte":[99,1],"farbe":1,"minimap":false}]}
                """).getAsJsonObject());
        assertEquals(List.of(new Wegpunkte.EigeneForm(WELT, List.of(e(1, 1), e(2, 2)), 1, true, null, Wegpunkte.KEIN_HEX, Wegpunkte.BASE)), alt.eigeneFormen());
        // Geschrieben wird nur das neue Format, und so liest es sich zurück.
        String json = alt.json().toString();
        assertFalse(json.contains("\"punkte\""), json);
        Wegpunkte zurueck = new Wegpunkte();
        zurueck.lies(JsonParser.parseString(json).getAsJsonObject());
        assertEquals(alt.eigeneFormen(), zurueck.eigeneFormen());
        // Eine Datei von vor mod#79 ohne „eigene_formen“ liest der Mod ohne Fehler.
        Wegpunkte ohne = new Wegpunkte();
        ohne.lies(JsonParser.parseString("{\"wegpunkte\":[]}").getAsJsonObject());
        assertTrue(ohne.eigeneFormen().isEmpty());
    }

    @Test
    void ohneOrdnerNurImSpeicher() {
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        w.setze(WELT, 1, 1);
        assertEquals(1, w.punkte().size());
    }

    @Test
    void wechselLiestNurEinenAnderenOrdner(@TempDir Path wurzel) throws Exception {
        Wegpunkte w = new Wegpunkte();
        w.wechsel(wurzel.resolve("welt-01"));
        w.setze(WELT, 1, 1);
        // Die Datei ändert sich von aussen: Derselbe Ordner liest sie nicht neu, ein Wechsel hin und zurück schon.
        Files.writeString(wurzel.resolve("welt-01/wegpunkte.json"),
                "{\"wegpunkte\":[{\"dimension\":\"minecraft:overworld\",\"x\":9,\"z\":9,\"farbe\":0,\"minimap\":false}]}");
        w.wechsel(wurzel.resolve("welt-01"));
        assertEquals(1, w.punkte().getFirst().x());
        w.wechsel(wurzel.resolve("welt-02"));
        assertTrue(w.punkte().isEmpty());
        w.wechsel(wurzel.resolve("welt-01"));
        assertEquals(9, w.punkte().getFirst().x());
        // Im Einzelspieler bleibt beim Wechsel der Dimension, was nur im Speicher liegt.
        w.leeren();
        w.wechsel(null);
        w.setze(WELT, 1, 1);
        w.wechsel(null);
        assertEquals(1, w.punkte().size());
    }

    @Test
    void kaputteEintraegeFallenWeg() {
        Wegpunkte w = new Wegpunkte();
        w.lies(JsonParser.parseString("""
                {"wegpunkte":[
                  {"dimension":"minecraft:overworld","x":1,"z":2,"farbe":99,"minimap":false},
                  {"dimension":"minecraft:overworld","x":"eins","z":2,"farbe":0,"minimap":false},
                  {"x":1,"z":2},
                  42],
                 "spieler":["keine-uuid","00000000-0000-0000-0000-000000000001"]}
                """).getAsJsonObject());
        // Die Farbe läuft in die Liste der Farben.
        // Ohne id in der Datei vergibt der Mod die erste freie.
        assertEquals(List.of(new Wegpunkte.Punkt(1, WELT, 1, 2, 99 % Wegpunkte.FARBEN.length, false, false, Wegpunkte.BASE)), w.punkte());
        assertTrue(w.angeheftet(SAM));
    }

    @Test
    void unlesbareDateiBleibtGesichert(@TempDir Path ordner) throws Exception {
        Files.writeString(ordner.resolve("wegpunkte.json"), "{kaputt");
        Wegpunkte w = new Wegpunkte();
        w.lies(ordner);
        assertTrue(w.punkte().isEmpty());
        // Die nächste Änderung schreibt eine neue Datei; die alte liegt daneben.
        w.setze(WELT, 1, 1);
        assertEquals("{kaputt", Files.readString(ordner.resolve("wegpunkte.json.kaputt")));
        Wegpunkte neu = new Wegpunkte();
        neu.lies(ordner);
        assertEquals(1, neu.punkte().size());
    }

    @Test
    void farbenBleibenVerschieden() {
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        w.setze(WELT, 1, 1);
        w.setze(WELT, 2, 2);
        w.setze(WELT, 3, 3);
        w.loesche(w.punkte().getFirst());
        w.setze(WELT, 4, 4);
        // Ein Wegpunkt einer anderen Dimension nimmt keiner Farbe den Platz.
        w.setze("minecraft:the_nether", 5, 5);
        assertEquals(java.util.Set.of(0, 1, 2), w.punkte().stream().filter(p -> p.dimension().equals(WELT))
                .map(Wegpunkte.Punkt::farbe).collect(java.util.stream.Collectors.toSet()));
        assertEquals(0, w.punkte().getLast().farbe());
    }

    /** Eine Ebene vom Server mit den Flächen und Kreisen {@code objekte}, ganz angekommen. */
    private static Ebenen ebene(String id, String version, String objekte) {
        Ebenen e = new Ebenen();
        e.empfange(JsonParser.parseString("{\"v\":1,\"typ\":\"ebenen\",\"jetzt\":1,\"ebenen\":[{\"id\":\"" + id
                + "\",\"visible\":true,\"version\":\"" + version + "\"}]}").getAsJsonObject());
        assertTrue(e.teil(Ebenen.Teil.lies("{\"v\":1,\"typ\":\"ebene\",\"jetzt\":1,\"id\":\"" + id + "\",\"version\":\"" + version
                + "\",\"teil\":1,\"teile\":1,\"objects\":[" + objekte + "]}")));
        return e;
    }

    private static final String FLAECHE = "{\"type\":\"region\",\"id\":\"wald\",\"fill\":\"#3060E080\",\"polygons\":[{\"outer\":[[0,0],[8,0],[8,8]]}]}";
    private static final String KREIS = "{\"type\":\"circle\",\"id\":\"see\",\"center\":[20,20],\"radius\":4,\"fill\":\"#40C04060\","
            + "\"stroke\":{\"width\":1}}";
    private static final String OHNE_ID = "{\"type\":\"circle\",\"center\":[-20,0],\"radius\":4}";
    private static final String LINIE = "{\"type\":\"line\",\"points\":[[0,0],[5,5]]}";

    @Test
    void alteDateiOhneListeLaedt(@TempDir Path ordner) throws Exception {
        // Eine wegpunkte.json von vor mod#36: ohne „formen“, gelesen ohne Fehler und ohne Verlust.
        Files.writeString(ordner.resolve("wegpunkte.json"), """
                {"wegpunkte":[{"dimension":"minecraft:overworld","x":1,"z":2,"farbe":0,"minimap":true}],
                 "regionen":[{"dimension":"minecraft:overworld","x0":0,"z0":0,"x1":3,"z1":3,"farbe":1,"minimap":false}],
                 "spieler":[]}""");
        Wegpunkte w = new Wegpunkte();
        w.lies(ordner);
        assertEquals(1, w.punkte().size());
        assertEquals(1, w.regionen().size());
        assertEquals(0, w.angeheftet());
        assertFalse(Files.exists(ordner.resolve("wegpunkte.json.kaputt")));
    }

    @Test
    void anheftenUeberstehtDenNeustart(@TempDir Path ordner) {
        Wegpunkte vorher = new Wegpunkte();
        vorher.lies(ordner);
        vorher.setze(WELT, 0, 0, 3, 3);
        assertTrue(vorher.umschalten(vorher.regionen().getFirst()));
        assertTrue(vorher.umschalten("b:wald", "wald"));
        assertEquals(2, vorher.angeheftet());

        Wegpunkte nachher = new Wegpunkte();
        nachher.lies(ordner);
        assertTrue(nachher.regionen().getFirst().angeheftet());
        assertTrue(nachher.angeheftet("b:wald", "wald"));
        // Noch einmal löst; das hält ebenso.
        nachher.umschalten("b:wald", "wald");
        nachher.umschalten(nachher.regionen().getFirst());
        Wegpunkte zuletzt = new Wegpunkte();
        zuletzt.lies(ordner);
        assertEquals(0, zuletzt.angeheftet());
    }

    @Test
    void hoechstens64Angeheftet() {
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        w.setze(WELT, 0, 0, 1, 1);
        w.setze(WELT, 5, 5, 6, 6);
        assertTrue(w.umschalten(w.regionen().getFirst()));
        for (int i = 1; i < Wegpunkte.MAX_ANGEHEFTET; i++) {
            assertTrue(w.umschalten("b:viele", "r" + i));
        }
        // Eigene und vom Server zählen zusammen: Die 65. geht nicht, weder vom Server noch eigen.
        assertFalse(w.umschalten("b:viele", "zuviel"));
        assertFalse(w.umschalten(w.regionen().getLast()));
        assertFalse(w.angeheftet("b:viele", "zuviel"));
        assertEquals(Wegpunkte.MAX_ANGEHEFTET, w.angeheftet());
        // Lösen geht immer, danach ist wieder Platz.
        assertTrue(w.umschalten("b:viele", "r1"));
        assertTrue(w.umschalten("b:viele", "zuviel"));

        // Aus der Datei auch nicht mehr; kaputte Einträge fallen weg.
        StringBuilder viele = new StringBuilder("{\"formen\":[{\"ebene\":5},");
        for (int i = 0; i < Wegpunkte.MAX_ANGEHEFTET + 5; i++) {
            viele.append(i == 0 ? "" : ",").append("{\"ebene\":\"b:viele\",\"id\":\"r").append(i).append("\"}");
        }
        Wegpunkte gelesen = new Wegpunkte();
        gelesen.lies(JsonParser.parseString(viele.append("]}").toString()).getAsJsonObject());
        assertEquals(Wegpunkte.MAX_ANGEHEFTET, gelesen.angeheftet());
    }

    @Test
    void toteEintraegeFallenWeg() {
        Ebenen e = ebene("b:wald", "v1", FLAECHE + "," + KREIS);
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        w.umschalten("b:wald", "wald");
        w.umschalten("b:wald", "gerodet");
        w.umschalten("b:anderswo", "gerodet");
        // Nur die Ebene, die eben ganz ankam; eine andere kann noch vom vorigen Server sein.
        w.pruefe(e, "b:wald");
        assertTrue(w.angeheftet("b:wald", "wald"));
        assertFalse(w.angeheftet("b:wald", "gerodet"));
        assertTrue(w.angeheftet("b:anderswo", "gerodet"));
    }

    @Test
    void listenFuerKarteUndMinimap() {
        Ebenen e = ebene("b:wald", "v1", FLAECHE + "," + KREIS + "," + OHNE_ID + "," + LINIE);
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        List<Ebenen.Form> alle = e.formen("b:wald");
        // Nichts angeheftet: die Liste der Ebene selbst, die Minimap leer.
        assertTrue(w.karte(e).getFirst() == alle);
        assertEquals(List.of(), w.minimap(e));

        w.umschalten("b:wald", "see");
        w.setze(WELT, 0, 0, 3, 3);
        w.setze(WELT, 9, 9, 9, 9);
        w.umschalten(w.regionen().getFirst());
        List<List<Ebenen.Form>> karte = w.karte(e), minimap = w.minimap(e);
        // Dieselben Listen, solange sich nichts ändert; so bleibt der Speicher der Formen gültig.
        assertTrue(karte == w.karte(e) && minimap == w.minimap(e));
        assertTrue(karte.getFirst().get(0) == alle.get(0));
        Ebenen.Kreis breit = (Ebenen.Kreis) karte.getFirst().get(1);
        assertEquals(1 + Wegpunkte.BREITER, breit.rand().breite());
        assertTrue(karte.getFirst().get(2) == alle.get(2) && karte.getFirst().get(3) == alle.get(3));
        // Auf der Minimap nur der angeheftete Kreis, wie er ist, und die angeheftete eigene Region als Fläche.
        assertEquals(2, minimap.size());
        assertEquals(List.of(alle.get(1)), minimap.get(0));
        Ebenen.Flaeche eigen = (Ebenen.Flaeche) minimap.get(1).getFirst();
        assertEquals(1, minimap.get(1).size());
        org.junit.jupiter.api.Assertions.assertArrayEquals(new double[] {0, 0, 4, 4}, eigen.box());
        assertEquals(Wegpunkte.FARBEN[0] & 0x00FFFFFF | 0x40000000, eigen.fuellung());

        // Eine Änderung baut neu; eine ausgeblendete Ebene fehlt auf beiden.
        w.umschalten("b:wald", "see");
        assertTrue(w.karte(e).getFirst() == alle);
        assertEquals(1, w.minimap(e).size());
        e.setze("b:wald", false);
        assertEquals(List.of(), w.karte(e));
    }

    private static final String NADEL = "{\"type\":\"pin\",\"id\":\"hafen\",\"at\":[1,2],\"name\":\"Hafen\"}";
    private static final String BANNER = "{\"type\":\"banner\",\"id\":\"mark\",\"at\":[3,4],\"name\":\"Mark\",\"image\":\"images/b.png\"}";
    private static final String NADEL_OHNE_ID = "{\"type\":\"pin\",\"at\":[5,6],\"name\":\"Furt\"}";

    @Test
    void nadelnAnheftenUndBehalten(@TempDir Path ordner) throws Exception {
        Wegpunkte vorher = new Wegpunkte();
        vorher.lies(ordner);
        assertTrue(vorher.umschaltenNadel("b:orte", "mark"));
        Wegpunkte nachher = new Wegpunkte();
        nachher.lies(ordner);
        assertTrue(nachher.nadelAngeheftet("b:orte", "mark"));
        // Eine Nadel ist keine Fläche: dieselbe Kennung als Fläche ist nicht angeheftet.
        assertFalse(nachher.angeheftet("b:orte", "mark"));
        nachher.umschaltenNadel("b:orte", "mark");
        assertFalse(nachher.nadelAngeheftet("b:orte", "mark"));
        // Eine Datei von vor mod#71 ohne „nadeln“ liest der Mod ohne Fehler.
        Files.writeString(ordner.resolve("wegpunkte.json"), "{\"formen\":[{\"ebene\":\"b:wald\",\"id\":\"wald\"}]}");
        Wegpunkte alt = new Wegpunkte();
        alt.lies(ordner);
        assertTrue(alt.angeheftet("b:wald", "wald"));
        assertFalse(Files.exists(ordner.resolve("wegpunkte.json.kaputt")));
    }

    @Test
    void hoechstens64NadelnEigeneGrenze() {
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        for (int i = 0; i < Wegpunkte.MAX_ANGEHEFTET; i++) {
            assertTrue(w.umschalten("b:viele", "r" + i));
        }
        // Die 64 Regionen nehmen den Nadeln keinen Platz: eine eigene Grenze.
        for (int i = 0; i < Wegpunkte.MAX_NADELN_ANGEHEFTET; i++) {
            assertTrue(w.umschaltenNadel("b:viele", "n" + i));
        }
        assertFalse(w.umschaltenNadel("b:viele", "zuviel"));
        assertTrue(w.umschaltenNadel("b:viele", "n0"));
        assertTrue(w.umschaltenNadel("b:viele", "zuviel"));
        // Auch aus der Datei nicht mehr.
        StringBuilder viele = new StringBuilder("{\"nadeln\":[");
        for (int i = 0; i < Wegpunkte.MAX_NADELN_ANGEHEFTET + 5; i++) {
            viele.append(i == 0 ? "" : ",").append("{\"ebene\":\"b:viele\",\"id\":\"n").append(i).append("\"}");
        }
        Wegpunkte gelesen = new Wegpunkte();
        gelesen.lies(JsonParser.parseString(viele.append("]}").toString()).getAsJsonObject());
        assertTrue(gelesen.nadelAngeheftet("b:viele", "n63"));
        assertFalse(gelesen.nadelAngeheftet("b:viele", "n64"));
    }

    @Test
    void nadelnAufDerMinimapNurAngeheftet() {
        Ebenen e = ebene("b:orte", "v1", NADEL + "," + BANNER + "," + NADEL_OHNE_ID);
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        assertEquals(List.of(), w.nadeln(e));
        w.umschaltenNadel("b:orte", "mark");
        w.umschaltenNadel("b:orte", "weg");
        List<Ebenen.Ort> orte = e.nadeln("b:orte");
        assertEquals(List.of(orte.get(1)), w.nadeln(e));
        assertTrue(w.nadeln(e) == w.nadeln(e));
        // Für den Punkt auf der Vollbildkarte: nach Identität, ohne Allokation.
        assertTrue(w.angeheftet(e, orte.get(1)));
        assertFalse(w.angeheftet(e, orte.get(0)));
        // Tote Einträge fallen weg, wenn die Ebene ganz ankommt.
        w.pruefe(e, "b:orte");
        assertTrue(w.nadelAngeheftet("b:orte", "mark"));
        assertFalse(w.nadelAngeheftet("b:orte", "weg"));
        // Ausgeblendet fehlt sie auch angeheftet.
        e.setze("b:orte", false);
        assertEquals(List.of(), w.nadeln(e));
    }

    @Test
    void ganzeEbeneAnheftenUndLoesen() {
        Ebenen e = ebene("b:alles", "v1", FLAECHE + "," + KREIS + "," + OHNE_ID + "," + LINIE + "," + NADEL + "," + BANNER + "," + NADEL_OHNE_ID);
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        assertFalse(w.ganzAngeheftet(e, "b:alles"));
        // Eins schon angeheftet: Der Doppelklick heftet den Rest an, ohne es zu lösen.
        assertTrue(w.umschalten("b:alles", "see"));
        assertEquals(0, w.alleUmschalten(e, "b:alles"));
        assertTrue(w.angeheftet("b:alles", "wald") && w.angeheftet("b:alles", "see"));
        assertTrue(w.nadelAngeheftet("b:alles", "hafen") && w.nadelAngeheftet("b:alles", "mark"));
        assertEquals(2, w.angeheftet());
        assertTrue(w.ganzAngeheftet(e, "b:alles"));
        // Ganz angeheftet: Der nächste löst alles.
        assertEquals(0, w.alleUmschalten(e, "b:alles"));
        assertEquals(0, w.angeheftet());
        assertFalse(w.nadelAngeheftet("b:alles", "hafen"));
        // Eine Ebene ohne etwas mit id ist nie ganz angeheftet.
        assertFalse(w.ganzAngeheftet(ebene("b:leer", "v1", OHNE_ID + "," + LINIE), "b:leer"));
    }

    @Test
    void ganzeEbeneHaeltDieGrenzen() {
        Ebenen e = ebene("b:alles", "v1", FLAECHE + "," + KREIS + "," + NADEL + "," + BANNER);
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        for (int i = 1; i < Wegpunkte.MAX_ANGEHEFTET; i++) {
            assertTrue(w.umschalten("b:viele", "r" + i));
        }
        for (int i = 1; i < Wegpunkte.MAX_NADELN_ANGEHEFTET; i++) {
            assertTrue(w.umschaltenNadel("b:viele", "n" + i));
        }
        // Je Grenze ist noch eins frei: Eine Fläche und eine Nadel passen, ein Kreis und ein Banner nicht.
        assertEquals(2, w.alleUmschalten(e, "b:alles"));
        assertEquals(Wegpunkte.MAX_ANGEHEFTET, w.angeheftet());
        assertTrue(w.angeheftet("b:alles", "wald"));
        assertFalse(w.angeheftet("b:alles", "see"));
        assertTrue(w.nadelAngeheftet("b:alles", "hafen"));
        assertFalse(w.nadelAngeheftet("b:alles", "mark"));
        // Nicht ganz angeheftet: Der nächste löst nicht, sondern versucht wieder anzuheften.
        assertFalse(w.ganzAngeheftet(e, "b:alles"));
        assertEquals(2, w.alleUmschalten(e, "b:alles"));
        assertTrue(w.angeheftet("b:alles", "wald"));
    }

    @Test
    void breiterOhneRandNimmtDieFuellung() {
        Ebenen.Kreis k = new Ebenen.Kreis(WELT, 0, 0, 3, 0x8040C040, null, "k");
        Ebenen.Rand r = ((Ebenen.Kreis) Wegpunkte.breiter(k)).rand();
        assertEquals(new Ebenen.Rand(0xFF40C040, Wegpunkte.BREITER, 0, 0), r);
        // Breiter als das Format erlaubt wird es nicht.
        Ebenen.Kreis dick = new Ebenen.Kreis(WELT, 0, 0, 3, 0, new Ebenen.Rand(0xFF000000, Ebenen.MAX_BREITE, 0, 0), "d");
        assertEquals(Ebenen.MAX_BREITE, ((Ebenen.Kreis) Wegpunkte.breiter(dick)).rand().breite());
    }

    @Test
    void griffAufHalberLaengeOderImSchwerpunkt() {
        // Eine Linie über zwei Stücke, 6 und 4 lang: die Hälfte, 5, liegt auf dem ersten.
        assertArrayEquals(new double[] {5, 0}, Wegpunkte.griff(new double[] {0, 0, 6, 0, 6, 4}, false), 1e-12);
        // Auf dem zweiten, wenn das erste kürzer ist: 2 und 6 lang, die Hälfte, 4, liegt 2 weit auf dem zweiten.
        assertArrayEquals(new double[] {2, 2}, Wegpunkte.griff(new double[] {0, 0, 2, 0, 2, 6}, false), 1e-12);
        // Eine Region: der Schwerpunkt der Fläche, nicht der Mittelwert der Ecken; im Quadrat wie in einem Dreieck, gleich in welchem Umlauf.
        assertArrayEquals(new double[] {2, 2}, Wegpunkte.griff(new double[] {0, 0, 4, 0, 4, 4, 0, 4}, true), 1e-12);
        assertArrayEquals(new double[] {2, 2}, Wegpunkte.griff(new double[] {0, 0, 0, 4, 4, 4, 4, 0}, true), 1e-12);
        assertArrayEquals(new double[] {2, 1}, Wegpunkte.griff(new double[] {0, 0, 6, 0, 0, 3}, true), 1e-12);
        // Ein L aus 4 × 2 unten (Fläche 8, Mitte (2, 1)) und 2 × 2 darüber (Fläche 4, Mitte (1, 3)): (20/12, 20/12).
        // Der Mittelwert der sechs Ecken wäre (2, 2).
        assertArrayEquals(new double[] {5.0 / 3, 5.0 / 3}, Wegpunkte.griff(new double[] {0, 0, 4, 0, 4, 2, 2, 2, 2, 4, 0, 4}, true), 1e-12);
        // Ohne Fläche, alle Ecken auf einer Geraden: die Mitte des Kastens.
        assertArrayEquals(new double[] {3, 0}, Wegpunkte.griff(new double[] {0, 0, 6, 0, 3, 0}, true), 1e-12);
    }

    @Test
    void nameBereinigtUndGekappt() {
        assertEquals("Burg Nord", Wegpunkte.name("  §cBurg\u0000 Nord\u200B  "));
        // NFC: e mit einem Akzent als eigenem Zeichen wird ein Zeichen.
        assertEquals("Caf\u00e9", Wegpunkte.name("Cafe\u0301"));
        assertEquals("a".repeat(Wegpunkte.MAX_NAME), Wegpunkte.name("a".repeat(Wegpunkte.MAX_NAME + 8)));
        // Gezählt in Zeichen, nicht in Hälften eines Paars: 33 Zeichen ausserhalb der BMP werden 32.
        String pilz = "\uD83C\uDF44";
        assertEquals(pilz.repeat(Wegpunkte.MAX_NAME), Wegpunkte.name(pilz.repeat(Wegpunkte.MAX_NAME + 1)));
        assertEquals(null, Wegpunkte.name("   "));
        assertEquals(null, Wegpunkte.name("§a"));
        assertEquals(null, Wegpunkte.name(null));
    }

    @Test
    void namenUeberstehenDenNeustartUndAenderungen(@TempDir Path ordner) {
        Wegpunkte w = new Wegpunkte();
        w.lies(ordner);
        w.setzeForm(WELT, List.of(e(0, 0), e(10, 0), e(10, 10)));
        w.setzeForm(WELT, List.of(e(0, 20), e(10, 20)));
        w.benenne(w.eigeneFormen().get(0), " Hof ");
        assertEquals("Hof", w.eigeneFormen().get(0).name());
        // Ecken ändern und Anheften lassen den Namen.
        w.ersetze(w.eigeneFormen().get(0), List.of(e(0, 0), e(12, 0), e(10, 10)));
        w.umschalten(w.eigeneFormen().get(0));
        assertEquals("Hof", w.eigeneFormen().get(0).name());
        Wegpunkte nachher = new Wegpunkte();
        nachher.lies(ordner);
        assertEquals(w.eigeneFormen(), nachher.eigeneFormen());
        // Leer nimmt den Namen weg; geschrieben wird dann kein Feld.
        nachher.benenne(nachher.eigeneFormen().get(0), "  ");
        assertEquals(null, nachher.eigeneFormen().get(0).name());
        assertFalse(nachher.json().getAsJsonArray("eigene_formen").get(0).getAsJsonObject().has("name"), nachher.json().toString());
        // Ein Name, der kein Text ist, fehlt; die Form bleibt. Einer aus der Datei wird bereinigt.
        Wegpunkte fremd = new Wegpunkte();
        fremd.lies(JsonParser.parseString("""
                {"eigene_formen":[{"dimension":"minecraft:overworld","ecken":[[0,0],[1,1]],"farbe":0,"minimap":false,"name":{"a":1}},
                  {"dimension":"minecraft:overworld","ecken":[[0,0],[2,2]],"farbe":0,"minimap":false,"name":"§cRot"}]}
                """).getAsJsonObject());
        assertEquals(2, fremd.eigeneFormen().size());
        assertEquals(null, fremd.eigeneFormen().get(0).name());
        assertEquals("Rot", fremd.eigeneFormen().get(1).name());
    }

    @Test
    void namenEineSchrifthoeheNebenDemGriff() {
        // Region: waagrecht, ein Punkt eine Schrifthöhe unter dem Griff im Schwerpunkt; so hoch wie die halbe kürzere Seite, hier 5.
        Wegpunkte.EigeneForm region = new Wegpunkte.EigeneForm(WELT, List.of(e(0, 0), e(10, 0), e(10, 10), e(0, 10)), 0, false, "Hof", Wegpunkte.KEIN_HEX, Wegpunkte.BASE);
        Ebenen.Schrift s = Wegpunkte.schrift(region, Wegpunkte.mitten(region));
        assertArrayEquals(new double[] {5.5, 5.5 + 5}, s.pfad(), 1e-9);
        assertEquals(5, s.groesse());
        assertEquals("Hof", s.text());
        assertEquals(Ebenen.SCHRIFTFARBE, s.farbe());
        assertEquals(Ebenen.KONTURFARBE, s.konturFarbe());
        assertEquals(Wegpunkte.NAME_KONTUR, s.konturBreite());
        // Gekappt auf 4 bis 16 Blöcke: eine kleine und eine grosse Region; eine Linie ein Viertel ihrer Länge.
        assertEquals(Wegpunkte.NAME_KLEINSTE, groesse(List.of(e(0, 0), e(4, 0), e(4, 4))));
        assertEquals(Wegpunkte.NAME_GROESSE, groesse(List.of(e(0, 0), e(100, 0), e(100, 100))));
        assertEquals(10, groesse(List.of(e(0, 0), e(40, 0))));
        // Linie: parallel daneben auf der Nordseite, in beiden Richtungen; 10 Blöcke lang, also 4 hoch und 4 weg.
        double g = Wegpunkte.NAME_KLEINSTE;
        assertArrayEquals(new double[] {0.5, 0.5 - g, 10.5, 0.5 - g}, pfad(List.of(e(0, 0), e(10, 0))), 1e-9);
        assertArrayEquals(new double[] {10.5, 0.5 - g, 0.5, 0.5 - g}, pfad(List.of(e(10, 0), e(0, 0))), 1e-9);
        // Schräg nach Nordosten: senkrecht zur Linie, nach Nordwesten.
        double d = g / Math.sqrt(2);
        assertArrayEquals(new double[] {0.5 - d, 0.5 - d, 10.5 - d, -9.5 - d}, pfad(List.of(e(0, 0), e(10, -10))), 1e-9);
        // Von Nord nach Süd und zurück: auf der Westseite.
        assertArrayEquals(new double[] {0.5 - g, 0.5, 0.5 - g, 10.5}, pfad(List.of(e(0, 0), e(0, 10))), 1e-9);
        assertArrayEquals(new double[] {0.5 - g, 10.5, 0.5 - g, 0.5}, pfad(List.of(e(0, 10), e(0, 0))), 1e-9);
        // Ohne Namen keine Schrift.
        Wegpunkte.EigeneForm ohne = new Wegpunkte.EigeneForm(WELT, List.of(e(0, 0), e(10, 0)), 0, false, null, Wegpunkte.KEIN_HEX, Wegpunkte.BASE);
        assertEquals(null, Wegpunkte.schrift(ohne, Wegpunkte.mitten(ohne)));
    }

    @Test
    void schriftUndBiegungJeForm() {
        // Faktor und Biegung (mod#150): Eine Region 10 × 10 heisst 5 hoch, mal 2 also 10, eine Schrifthöhe unter dem Griff, die
        // Biegung im Bogenmass. Eine Linie biegt nicht; der Faktor gilt auch dort.
        Wegpunkte.EigeneForm region = new Wegpunkte.EigeneForm(WELT, List.of(e(0, 0), e(10, 0), e(10, 10), e(0, 10)), 0, false, "Hof",
                Wegpunkte.KEIN_HEX, Wegpunkte.BASE, 2, 90);
        Ebenen.Schrift s = Wegpunkte.schrift(region, Wegpunkte.mitten(region));
        assertEquals(10, s.groesse());
        assertArrayEquals(new double[] {5.5, 5.5 + 10}, s.pfad(), 1e-9);
        assertEquals(Math.PI / 2, s.biegung(), 1e-12);
        Wegpunkte.EigeneForm linie = new Wegpunkte.EigeneForm(WELT, List.of(e(0, 0), e(40, 0)), 0, false, "Weg", Wegpunkte.KEIN_HEX,
                Wegpunkte.BASE, 0.5, 90);
        assertEquals(0, linie.biegung());
        assertEquals(5, Wegpunkte.schrift(linie, Wegpunkte.mitten(linie)).groesse());
        assertEquals(0, Wegpunkte.schrift(linie, Wegpunkte.mitten(linie)).biegung());
        // Gespeichert nur ohne Vorgabe, und so gelesen; die Vorschau des Reglers allein speichert nicht.
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        w.setzeForm(WELT, region.ecken());
        w.setzeForm(WELT, List.of(e(0, 20), e(10, 20), e(10, 30)));
        w.benenne(w.stil(w.eigeneFormen().get(0), 2, -45, 0), "Hof");
        String json = w.json().toString();
        assertTrue(json.contains("\"schrift\":2.0") && json.contains("\"biegung\":-45"), json);
        assertEquals(1, json.split("\"schrift\"", -1).length - 1, json);
        assertEquals(1, json.split("\"biegung\"", -1).length - 1, json);
        Wegpunkte zurueck = new Wegpunkte();
        zurueck.lies(JsonParser.parseString(json).getAsJsonObject());
        assertEquals(w.eigeneFormen(), zurueck.eigeneFormen());
        // Ausserhalb der Grenzen oder ohne Zahl: die Vorgabe, die Form bleibt; Zwischenwerte auf die nächste Raste.
        Wegpunkte kaputt = new Wegpunkte();
        kaputt.lies(JsonParser.parseString("""
                {"eigene_formen":[
                  {"dimension":"minecraft:overworld","ecken":[[0,0],[9,0],[9,9]],"farbe":0,"minimap":false,"schrift":5,"biegung":200},
                  {"dimension":"minecraft:overworld","ecken":[[0,20],[9,20],[9,29]],"farbe":0,"minimap":false,"schrift":"2","biegung":"90"},
                  {"dimension":"minecraft:overworld","ecken":[[0,40],[9,40],[9,49]],"farbe":0,"minimap":false,"schrift":0.3,"biegung":37},
                  {"dimension":"minecraft:overworld","ecken":[[0,60],[9,60]],"farbe":0,"minimap":false,"schrift":1.5,"biegung":90}]}
                """).getAsJsonObject());
        List<Wegpunkte.EigeneForm> k = kaputt.eigeneFormen();
        assertEquals(List.of(1.0, 1.0, 1.0 / 3, 1.5), k.stream().map(Wegpunkte.EigeneForm::schrift).toList());
        assertEquals(List.of(0, 0, 30, 0), k.stream().map(Wegpunkte.EigeneForm::biegung).toList());
    }

    @Test
    void gewichtLegtFormenUebereinander() {
        // Je Gewicht eine Liste, leicht zuerst, nach den Ebenen vom Server; in einer Liste die Reihenfolge wie bisher (mod#163).
        Ebenen e = ebene("b:wald", "v1", FLAECHE);
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        for (int i = 0; i < 4; i++) {
            w.setzeForm(WELT, List.of(e(10 * i, 0), e(10 * i + 5, 0), e(10 * i + 5, 5)));
        }
        // Alle 0: eine Liste wie vor mod#163, nach der Ebene vom Server.
        assertEquals(2, w.karte(e).size());
        assertEquals(4, w.karte(e).get(1).size());
        List<Wegpunkte.EigeneForm> f = w.eigeneFormen();
        w.stil(f.get(0), 1, 0, 2);
        w.stil(f.get(2), 1, 0, -1);
        List<List<Ebenen.Form>> karte = w.karte(e);
        // Ebene vom Server, dann −1, 0 (zwei, in ihrer Reihenfolge), 2.
        assertEquals(4, karte.size());
        assertEquals(List.of(1, 2, 1), karte.subList(1, 4).stream().map(List::size).toList());
        assertArrayEquals(((Ebenen.Flaeche) w.form(w.eigeneFormen().get(2))).box(), ((Ebenen.Flaeche) karte.get(1).getFirst()).box(), 1e-9);
        assertArrayEquals(((Ebenen.Flaeche) w.form(w.eigeneFormen().get(1))).box(), ((Ebenen.Flaeche) karte.get(2).get(0)).box(), 1e-9);
        assertArrayEquals(((Ebenen.Flaeche) w.form(w.eigeneFormen().get(3))).box(), ((Ebenen.Flaeche) karte.get(2).get(1)).box(), 1e-9);
        assertArrayEquals(((Ebenen.Flaeche) w.form(w.eigeneFormen().get(0))).box(), ((Ebenen.Flaeche) karte.get(3).getFirst()).box(), 1e-9);
        // Auf der Minimap ebenso, mit dem Gewicht je Form für den Schleier.
        w.umschalten(w.eigeneFormen().get(0));
        w.umschalten(w.eigeneFormen().get(2));
        List<List<Ebenen.Form>> minimap = w.minimap(e);
        assertEquals(2, minimap.size());
        assertEquals(-1, w.gewicht(minimap.get(0).getFirst()));
        assertEquals(2, w.gewicht(minimap.get(1).getFirst()));
        // Gespeichert nur ohne Vorgabe; gelesen ganzzahlig bis ±9, sonst 0.
        w.faerbe(w.eigeneFormen().get(0), Wegpunkte.KEIN_HEX);
        String json = w.json().toString();
        assertEquals(2, json.split("\"gewicht\"", -1).length - 1, json);
        Wegpunkte zurueck = new Wegpunkte();
        zurueck.lies(JsonParser.parseString(json).getAsJsonObject());
        assertEquals(List.of(2, 0, -1, 0), zurueck.eigeneFormen().stream().map(Wegpunkte.EigeneForm::gewicht).toList());
        Wegpunkte kaputt = new Wegpunkte();
        kaputt.lies(JsonParser.parseString("""
                {"eigene_formen":[
                  {"dimension":"minecraft:overworld","ecken":[[0,0],[9,0],[9,9]],"farbe":0,"minimap":false,"gewicht":10},
                  {"dimension":"minecraft:overworld","ecken":[[0,20],[9,20],[9,29]],"farbe":0,"minimap":false,"gewicht":1.5},
                  {"dimension":"minecraft:overworld","ecken":[[0,40],[9,40],[9,49]],"farbe":0,"minimap":false,"gewicht":"3"},
                  {"dimension":"minecraft:overworld","ecken":[[0,60],[9,60]],"farbe":0,"minimap":false,"gewicht":-9}]}
                """).getAsJsonObject());
        assertEquals(List.of(0, 0, 0, -9), kaputt.eigeneFormen().stream().map(Wegpunkte.EigeneForm::gewicht).toList());
    }

    @Test
    void trefferNachGewicht() {
        // Näher gewinnt; bei gleichem Abstand das schwerere, bei gleichem Gewicht die spätere wie bisher; ohne beste bis zur Grenze.
        assertTrue(Wegpunkte.trifftEher(3, -9, 4, 9));
        assertFalse(Wegpunkte.trifftEher(4, 9, 3, -9));
        assertTrue(Wegpunkte.trifftEher(3, 2, 3, 1));
        assertFalse(Wegpunkte.trifftEher(3, 1, 3, 2));
        assertTrue(Wegpunkte.trifftEher(3, 0, 3, 0));
        assertTrue(Wegpunkte.trifftEher(4, -9, 4, Integer.MIN_VALUE));
        assertFalse(Wegpunkte.trifftEher(4.1, 9, 4, Integer.MIN_VALUE));
    }

    private static float groesse(List<Wegpunkte.Ecke> ecken) {
        Wegpunkte.EigeneForm f = new Wegpunkte.EigeneForm(WELT, ecken, 0, false, "Weg", Wegpunkte.KEIN_HEX, Wegpunkte.BASE);
        return Wegpunkte.schrift(f, Wegpunkte.mitten(f)).groesse();
    }

    private static double[] pfad(List<Wegpunkte.Ecke> ecken) {
        Wegpunkte.EigeneForm f = new Wegpunkte.EigeneForm(WELT, ecken, 0, false, "Weg", Wegpunkte.KEIN_HEX, Wegpunkte.BASE);
        return Wegpunkte.schrift(f, Wegpunkte.mitten(f)).pfad();
    }

    @Test
    void hexFarbeLesenSchreibenUndUeberall() {
        assertEquals(0x3A7FD5, Wegpunkte.hex("#3a7fd5"));
        assertEquals(0x3A7FD5, Wegpunkte.hex(" 3A7FD5 "));
        assertEquals("#3A7FD5", Wegpunkte.hex(0x3A7FD5));
        // Fünf oder sieben Stellen, kein Hex, Ziffern einer anderen Schrift, leer: keine Farbe.
        for (String falsch : new String[] {"#3A7FD", "#3A7FD55", "#3A7FDG", "\uFF13A7FD5", "", "#", null}) {
            assertEquals(Wegpunkte.KEIN_HEX, Wegpunkte.hex(falsch), falsch);
        }
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        w.setzeForm(WELT, List.of(e(0, 0), e(10, 0), e(10, 10)));
        Wegpunkte.EigeneForm ohne = w.eigeneFormen().getFirst();
        assertEquals(Wegpunkte.FARBEN[ohne.farbe()], ohne.argb());
        w.faerbe(ohne, 0x3A7FD5);
        Wegpunkte.EigeneForm mit = w.eigeneFormen().getFirst();
        assertEquals(0xFF3A7FD5, mit.argb());
        assertEquals(ohne.farbe(), mit.farbe());
        // Die Form zum Zeichnen nimmt sie, für Rand und Füllung; Ecken ändern, Anheften und Name lassen sie.
        Ebenen.Flaeche f = (Ebenen.Flaeche) w.form(mit);
        assertEquals(0xFF3A7FD5, f.rand().farbe());
        assertEquals(0x403A7FD5, f.fuellung());
        w.ersetze(mit, List.of(e(0, 0), e(12, 0), e(10, 10)));
        w.umschalten(w.eigeneFormen().getFirst());
        w.benenne(w.eigeneFormen().getFirst(), "Hof");
        assertEquals(0x3A7FD5, w.eigeneFormen().getFirst().hex());
        // Über die Datei; der Index bleibt für ältere Versionen daneben.
        String json = w.json().toString();
        assertTrue(json.contains("\"hex\":\"#3A7FD5\""), json);
        assertTrue(json.contains("\"farbe\":" + ohne.farbe()), json);
        Wegpunkte zurueck = new Wegpunkte();
        zurueck.lies(JsonParser.parseString(json).getAsJsonObject());
        assertEquals(w.eigeneFormen(), zurueck.eigeneFormen());
        // Weggenommen gilt wieder der Index, und geschrieben wird kein Feld.
        zurueck.faerbe(zurueck.eigeneFormen().getFirst(), Wegpunkte.KEIN_HEX);
        assertEquals(Wegpunkte.FARBEN[ohne.farbe()], zurueck.eigeneFormen().getFirst().argb());
        assertFalse(zurueck.json().toString().contains("\"hex\""));
        // Eine Farbe, die keine ist, fehlt; die Form bleibt.
        Wegpunkte fremd = new Wegpunkte();
        fremd.lies(JsonParser.parseString("""
                {"eigene_formen":[{"dimension":"minecraft:overworld","ecken":[[0,0],[1,1]],"farbe":3,"minimap":false,"hex":"rot"},
                  {"dimension":"minecraft:overworld","ecken":[[0,0],[2,2]],"farbe":3,"minimap":false,"hex":[1]}]}
                """).getAsJsonObject());
        assertEquals(2, fremd.eigeneFormen().size());
        assertTrue(fremd.eigeneFormen().stream().allMatch(x -> x.hex() == Wegpunkte.KEIN_HEX));
    }

    @Test
    void eigeneFormenFuerDenSchleierErkannt() {
        Ebenen e = ebene("b:alles", "v1", FLAECHE);
        Wegpunkte w = new Wegpunkte();
        w.lies((Path) null);
        assertTrue(w.umschalten("b:alles", "wald"));
        w.setzeForm(WELT, List.of(e(0, 0), e(10, 0), e(10, 10)));
        w.umschalten(w.eigeneFormen().getFirst());
        List<Ebenen.Form> alle = w.minimap(e).stream().flatMap(List::stream).toList();
        // Die Fläche vom Server und die eigene Region sind angeheftet; nur die eigene ist eigen.
        assertEquals(2, alle.size());
        assertEquals(1, alle.stream().filter(w::eigen).count());
        assertTrue(alle.stream().filter(w::eigen).allMatch(f -> f instanceof Ebenen.Flaeche fl && fl.id() == null));
    }

    /**
     * Mehrere Todespunkte (mod#151): höchstens so viele wie eingestellt, die ältesten fallen weg; nur der neueste ist angeheftet;
     * derselbe Block frischt einen auf; weniger einstellen nimmt die ältesten weg. In der Datei als tode, der älteste zuerst, und
     * der neueste als tod für ältere Versionen; eine alte Datei mit nur tod gibt einen. Siehe docs/wegpunkte.md, „Todespunkt“.
     */
    @Test
    void mehrereTodespunkte(@TempDir Path ordner) {
        Wegpunkte w = new Wegpunkte();
        w.lies(ordner);
        w.setzeTod(WELT, 1, 1, 3);
        w.setzeTod(WELT, 2, 2, 3);
        w.setzeTod(WELT, 3, 3, 3);
        assertEquals(List.of(1, 2, 3), w.tode().stream().map(Wegpunkte.Punkt::x).toList());
        assertEquals(List.of(false, false, true), w.tode().stream().map(Wegpunkte.Punkt::angeheftet).toList());
        // Ein vierter: Der älteste fällt weg.
        w.setzeTod(WELT, 4, 4, 3);
        assertEquals(List.of(2, 3, 4), w.tode().stream().map(Wegpunkte.Punkt::x).toList());
        // Wieder auf dem Block des ältesten: Er wird der neueste und angeheftet, der vorige neueste gelöst.
        w.setzeTod(WELT, 2, 2, 3);
        assertEquals(List.of(3, 4, 2), w.tode().stream().map(Wegpunkte.Punkt::x).toList());
        assertEquals(List.of(false, false, true), w.tode().stream().map(Wegpunkte.Punkt::angeheftet).toList());
        // In der Datei: tode in dieser Folge, tod der neueste; und so zurück.
        String json = w.json().toString();
        assertTrue(json.contains("\"tod\":{\"dimension\":\"" + WELT + "\",\"x\":2,\"z\":2,\"minimap\":true}"), json);
        assertEquals(3, w.json().getAsJsonArray("tode").size());
        Wegpunkte nachher = new Wegpunkte();
        nachher.lies(ordner);
        assertEquals(List.of(3, 4, 2), nachher.tode().stream().map(Wegpunkte.Punkt::x).toList());
        assertEquals(List.of(false, false, true), nachher.tode().stream().map(Wegpunkte.Punkt::angeheftet).toList());
        // Weniger einstellen nimmt die ältesten weg, 0 alle.
        nachher.kappeTode(1);
        assertEquals(List.of(2), nachher.tode().stream().map(Wegpunkte.Punkt::x).toList());
        nachher.kappeTode(0);
        assertEquals(List.of(), nachher.tode());
        // Eine alte Datei hat nur tod: ein Todespunkt.
        Wegpunkte alt = new Wegpunkte();
        alt.lies(JsonParser.parseString("{\"tod\":{\"dimension\":\"" + WELT + "\",\"x\":9,\"z\":9,\"minimap\":true}}").getAsJsonObject());
        assertEquals(List.of(9), alt.tode().stream().map(Wegpunkte.Punkt::x).toList());
        // Stehen beide da, gilt tode; tod ist dort nur der neueste noch einmal.
        Wegpunkte beide = new Wegpunkte();
        beide.lies(JsonParser.parseString("{\"tode\":[{\"dimension\":\"" + WELT + "\",\"x\":5,\"z\":5,\"minimap\":false},"
                + "{\"dimension\":\"" + WELT + "\",\"x\":6,\"z\":6,\"minimap\":true}],"
                + "\"tod\":{\"dimension\":\"" + WELT + "\",\"x\":6,\"z\":6,\"minimap\":true}}").getAsJsonObject());
        assertEquals(List.of(5, 6), beide.tode().stream().map(Wegpunkte.Punkt::x).toList());
    }

    /**
     * Die Zeit unter dem Todespunkt (mod#166): am selben Tag nur die Uhrzeit, an einem früheren mit Datum, nach den Mustern der
     * Sprache, auch über Mitternacht. In der Datei je Eintrag von tode als zeit, tod bleibt ohne; über den Neustart und das
     * Anheften bleibt sie; die Folge kommt aus der Liste, nicht aus der Zeit; eine alte Datei ohne zeit gibt 0. Siehe
     * docs/wegpunkte.md, „Todespunkt“.
     */
    @Test
    void todesZeit(@TempDir Path ordner) throws Exception {
        ZoneId zone = ZoneId.of("Europe/Berlin");
        long jetzt = ZonedDateTime.of(2026, 10, 20, 14, 40, 0, 0, zone).toInstant().toEpochMilli();
        long heute = ZonedDateTime.of(2026, 10, 20, 14, 32, 0, 0, zone).toInstant().toEpochMilli();
        long frueher = ZonedDateTime.of(2026, 10, 18, 14, 32, 0, 0, zone).toInstant().toEpochMilli();
        assertEquals("14:32", Wegpunkte.todZeit(heute, jetzt, zone, "HH:mm", "dd.MM. HH:mm"));
        assertEquals("18.10. 14:32", Wegpunkte.todZeit(frueher, jetzt, zone, "HH:mm", "dd.MM. HH:mm"));
        long spaet = ZonedDateTime.of(2026, 10, 19, 23, 59, 0, 0, zone).toInstant().toEpochMilli();
        long frueh = ZonedDateTime.of(2026, 10, 20, 0, 1, 0, 0, zone).toInstant().toEpochMilli();
        assertEquals("19.10. 23:59", Wegpunkte.todZeit(spaet, frueh, zone, "HH:mm", "dd.MM. HH:mm"));
        // In der Datei: zeit im Eintrag von tode, tod wie bisher ohne.
        Wegpunkte w = new Wegpunkte();
        w.lies(ordner);
        w.setzeTod(WELT, 1, 1, 2, heute);
        w.setzeTod(WELT, 2, 2, 2, frueher);
        String json = Files.readString(ordner.resolve("wegpunkte.json"));
        assertTrue(json.contains("\"x\":1,\"z\":1,\"minimap\":false,\"zeit\":" + heute + "}"), json);
        assertTrue(json.contains("\"tod\":{\"dimension\":\"" + WELT + "\",\"x\":2,\"z\":2,\"minimap\":true}"), json);
        Wegpunkte nachher = new Wegpunkte();
        nachher.lies(ordner);
        assertEquals(List.of(heute, frueher), nachher.tode().stream().map(Wegpunkte.Punkt::zeit).toList());
        // Der zweite ist der neueste, obwohl seine Zeit früher ist: Die Folge kommt aus der Liste.
        assertEquals(2, nachher.tod().x());
        nachher.umschalten(nachher.tod());
        assertEquals(frueher, nachher.tode().getLast().zeit());
        // Eine alte Datei ohne zeit: unbekannt, also keine Schrift.
        Wegpunkte alt = new Wegpunkte();
        alt.lies(JsonParser.parseString("{\"tode\":[{\"dimension\":\"" + WELT + "\",\"x\":5,\"z\":5,\"minimap\":true}]}").getAsJsonObject());
        assertEquals(0, alt.tod().zeit());
    }

    @Test
    void todespunktNurEinerAngeheftetUndEigensGespeichert(@TempDir Path ordner) {
        Wegpunkte w = new Wegpunkte();
        w.lies(ordner);
        assertTrue(w.setzeTod(WELT, 10, -5, 1));
        Wegpunkte.Punkt tod = w.tod();
        assertTrue(tod.tod() && tod.angeheftet() && tod.x() == 10 && tod.z() == -5 && tod.dimension().equals(WELT), tod.toString());
        // Er nimmt keine Farbe: Der erste Wegpunkt bekommt die erste.
        w.setze(WELT, 1, 1);
        assertEquals(0, w.punkte().getLast().farbe());
        // Mit höchstens einem verschiebt ein neuer Tod ihn; es bleibt einer.
        assertTrue(w.setzeTod("minecraft:the_nether", 3, 4, 1));
        assertEquals(1, w.punkte().stream().filter(Wegpunkte.Punkt::tod).count());
        assertEquals("minecraft:the_nether", w.tod().dimension());
        // In der Datei eigens, nicht in der Liste der Wegpunkte, und so zurück.
        String json = w.json().toString();
        assertTrue(json.contains("\"tod\":{\"dimension\":\"minecraft:the_nether\",\"x\":3,\"z\":4,\"minimap\":true}"), json);
        assertEquals(1, w.json().getAsJsonArray("wegpunkte").size());
        Wegpunkte nachher = new Wegpunkte();
        nachher.lies(ordner);
        Wegpunkte.Punkt zurueck = nachher.tod();
        assertTrue(zurueck.tod() && zurueck.angeheftet() && zurueck.x() == 3 && zurueck.z() == 4, zurueck.toString());
        assertEquals(2, nachher.punkte().size());
        // Lösen und Verschieben lassen ihn Todespunkt.
        nachher.umschalten(nachher.tod());
        assertTrue(nachher.verschiebe(nachher.tod(), 7, 7));
        assertTrue(nachher.tod().tod() && !nachher.tod().angeheftet() && nachher.tod().x() == 7);
        // Auf einem Wegpunkt gibt es keinen; der Block ist schon markiert.
        assertFalse(nachher.setzeTod(WELT, 1, 1, 1));
        assertEquals(7, nachher.tod().x());
        nachher.setzeTod(WELT, 2, 2, 1);
        nachher.kappeTode(0);
        assertNull(nachher.tod());
        // Eine Datei ohne Todespunkt oder mit einem kaputten liest der Mod ohne Fehler.
        Wegpunkte alt = new Wegpunkte();
        alt.lies(JsonParser.parseString("{\"tod\":{\"x\":1}}").getAsJsonObject());
        assertNull(alt.tod());
    }

    @Test
    void todMeldungOeffnetDieKarteDort() {
        net.minecraft.network.chat.Component m = Wegpunkte.todMeldung(12, -40);
        assertEquals(new net.minecraft.network.chat.ClickEvent.RunCommand("/hmap karte 12 -40"), m.getStyle().getClickEvent());
        assertTrue(m.getStyle().getHoverEvent() instanceof net.minecraft.network.chat.HoverEvent.ShowText);
        assertEquals(List.of(12, -40), List.of(((net.minecraft.network.chat.contents.TranslatableContents) m.getContents()).getArgs()));
    }

    @Test
    void koordinatenEingetippt() {
        assertArrayEquals(new int[] {12, -40}, Wegpunkte.koordinaten("12 -40"));
        assertArrayEquals(new int[] {12, -40}, Wegpunkte.koordinaten(" 12, -40 "));
        // Drei Zahlen sind x y z; y zählt nicht.
        assertArrayEquals(new int[] {12, -40}, Wegpunkte.koordinaten("12 64 -40"));
        assertArrayEquals(new int[] {Wegpunkte.MAX_KOORDINATE, -Wegpunkte.MAX_KOORDINATE}, Wegpunkte.koordinaten("+30000000 -30000000"));
        for (String falsch : new String[] {"12", "1 2 3 4", "12 a", "12.5 3", "30000001 0", "0 -30000001", "", "  ", null, "\uFF112 3",
            "9999999999 0"}) {
            assertNull(Wegpunkte.koordinaten(falsch), falsch);
        }
    }

    @Test
    void eigeneEbenenAnlegenSchaltenLoeschenUndDatei(@TempDir Path ordner) {
        Wegpunkte w = new Wegpunkte();
        w.lies(ordner);
        assertEquals(List.of(new Wegpunkte.EigeneEbene(Wegpunkte.BASE, "Base", true)), w.ebenen());
        int farmen = w.neueEbene(" Farmen ");
        assertEquals(1, farmen);
        assertEquals("Farmen", w.ebene(farmen).name());
        // Base heisst fest so; ohne Namen geht nichts.
        assertFalse(w.benenneEbene(Wegpunkte.BASE, "Haupt"));
        assertFalse(w.benenneEbene(farmen, "  "));
        assertEquals(-1, w.neueEbene("§a"));
        assertTrue(w.benenneEbene(farmen, "Höfe"));
        // Neues landet in Base; eingeordnet zählt die Ebene, angeheftet oder nicht.
        w.setze(WELT, 1, 1);
        w.setzeForm(WELT, List.of(e(0, 0), e(10, 0), e(10, 10)));
        w.setze(WELT, 20, 20, 30, 30);
        assertTrue(w.punkte().getFirst().ebene() == Wegpunkte.BASE && w.eigeneFormen().getFirst().ebene() == Wegpunkte.BASE);
        w.ordne(w.punkte().getFirst(), farmen);
        w.ordne(w.eigeneFormen().getFirst(), farmen);
        w.ordne(w.regionen().getFirst(), farmen);
        w.umschalten(w.eigeneFormen().getFirst());
        w.umschalten(w.regionen().getFirst());
        Ebenen leer = new Ebenen();
        assertEquals(2, w.minimap(leer).stream().mapToLong(List::size).sum());
        // Aus: nirgends gezeichnet, auch angeheftet nicht.
        w.schalteEbene(farmen, false);
        assertFalse(w.an(farmen));
        assertFalse(w.sichtbar(w.punkte().getFirst()));
        assertEquals(0, w.minimap(leer).stream().mapToLong(List::size).sum());
        assertEquals(0, w.karte(leer).stream().mapToLong(List::size).sum());
        // Über die Datei, mit den Ebenen oben und der Ebene je Eintrag.
        String json = w.json().toString();
        assertTrue(json.contains("\"ebenen\":[{\"id\":0,\"name\":\"Base\",\"an\":true},{\"id\":1,\"name\":\"Höfe\",\"an\":false}]"), json);
        Wegpunkte nachher = new Wegpunkte();
        nachher.lies(ordner);
        assertEquals(w.ebenen(), nachher.ebenen());
        assertEquals(farmen, nachher.punkte().getFirst().ebene());
        assertEquals(farmen, nachher.eigeneFormen().getFirst().ebene());
        assertEquals(farmen, nachher.regionen().getFirst().ebene());
        // Gelöscht kommt alles nach Base; Base lässt sich nicht löschen.
        nachher.loescheEbene(Wegpunkte.BASE);
        nachher.loescheEbene(farmen);
        assertEquals(1, nachher.ebenen().size());
        assertTrue(nachher.punkte().getFirst().ebene() == Wegpunkte.BASE && nachher.eigeneFormen().getFirst().ebene() == Wegpunkte.BASE
                && nachher.regionen().getFirst().ebene() == Wegpunkte.BASE);
        // Höchstens 32 neben Base.
        for (int i = 0; i < Wegpunkte.MAX_EBENEN; i++) {
            assertTrue(nachher.neueEbene("E" + i) > 0);
        }
        assertEquals(-1, nachher.neueEbene("zu viel"));
    }

    @Test
    void eigeneEbenenAlteDateiUndKaputteEintraege() {
        // Ohne „ebenen“ gibt es nur Base, und alles liegt darin.
        Wegpunkte alt = new Wegpunkte();
        alt.lies(JsonParser.parseString("""
                {"wegpunkte":[{"id":1,"dimension":"minecraft:overworld","x":1,"z":1,"farbe":0,"minimap":false}]}
                """).getAsJsonObject());
        assertEquals(1, alt.ebenen().size());
        assertEquals(Wegpunkte.BASE, alt.punkte().getFirst().ebene());
        // Eine Ebene, die es nicht gibt, heisst Base; doppelte ids, leere Namen und negative ids fallen weg; Base nimmt nur „an“.
        Wegpunkte w = new Wegpunkte();
        w.lies(JsonParser.parseString("""
                {"ebenen":[{"id":0,"name":"Anders","an":false},{"id":4,"name":"Wald"},{"id":4,"name":"Doppelt"},{"id":5,"name":" "},
                   {"id":-2,"name":"Minus"},{"name":"ohne id"}],
                 "wegpunkte":[{"id":1,"dimension":"minecraft:overworld","x":1,"z":1,"farbe":0,"minimap":false,"ebene":9},
                   {"id":2,"dimension":"minecraft:overworld","x":2,"z":2,"farbe":0,"minimap":false,"ebene":4},
                   {"id":3,"dimension":"minecraft:overworld","x":3,"z":3,"farbe":0,"minimap":false,"ebene":"Wald"}]}
                """).getAsJsonObject());
        assertEquals(List.of(new Wegpunkte.EigeneEbene(Wegpunkte.BASE, "Base", false), new Wegpunkte.EigeneEbene(4, "Wald", true)), w.ebenen());
        assertEquals(Wegpunkte.BASE, w.punkte().get(0).ebene());
        assertEquals(4, w.punkte().get(1).ebene());
        // Eine Ebene, die keine Zahl ist, heisst Base; der Eintrag bleibt (Review von #143).
        assertEquals(3, w.punkte().size());
        assertEquals(Wegpunkte.BASE, w.punkte().get(2).ebene());
        // Eine neue bekommt die nächste freie id.
        assertEquals(5, w.neueEbene("Neu"));
    }
}
