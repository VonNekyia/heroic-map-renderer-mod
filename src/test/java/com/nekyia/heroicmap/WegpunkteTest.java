package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
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
        assertEquals(List.of(new Wegpunkte.Punkt(1, WELT, 12, -40, 0, true), new Wegpunkte.Punkt(2, "minecraft:the_nether", 1, 2, 0, false)),
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
        assertEquals(List.of(new Wegpunkte.Region(WELT, 2, -5, 10, 3, 0, false)), w.regionen());
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
        assertEquals(new Wegpunkte.Region(WELT, 4, 0, 6, 1, 2, false), w.region(WELT, 5, 0));

        Wegpunkte nachher = new Wegpunkte();
        nachher.lies(ordner);
        assertEquals(w.regionen(), nachher.regionen());
        nachher.loesche(nachher.regionen().getFirst());
        assertEquals(List.of(new Wegpunkte.Region(WELT, 4, 0, 6, 1, 2, false)), nachher.regionen());
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
        assertEquals(new Wegpunkte.Punkt(erster.id(), WELT, 10, -3, erster.farbe(), true), w.punkte().getFirst());
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
        assertEquals(List.of(new Wegpunkte.EigeneForm(WELT, List.of(e(1, 1), e(2, 2)), 1, true, null, Wegpunkte.KEIN_HEX)), alt.eigeneFormen());
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
        assertEquals(List.of(new Wegpunkte.Punkt(1, WELT, 1, 2, 99 % Wegpunkte.FARBEN.length, false)), w.punkte());
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
        assertFalse(nachher.json().toString().contains("\"name\""), nachher.json().toString());
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
        Wegpunkte.EigeneForm region = new Wegpunkte.EigeneForm(WELT, List.of(e(0, 0), e(10, 0), e(10, 10), e(0, 10)), 0, false, "Hof", Wegpunkte.KEIN_HEX);
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
        Wegpunkte.EigeneForm ohne = new Wegpunkte.EigeneForm(WELT, List.of(e(0, 0), e(10, 0)), 0, false, null, Wegpunkte.KEIN_HEX);
        assertEquals(null, Wegpunkte.schrift(ohne, Wegpunkte.mitten(ohne)));
    }

    private static float groesse(List<Wegpunkte.Ecke> ecken) {
        Wegpunkte.EigeneForm f = new Wegpunkte.EigeneForm(WELT, ecken, 0, false, "Weg", Wegpunkte.KEIN_HEX);
        return Wegpunkte.schrift(f, Wegpunkte.mitten(f)).groesse();
    }

    private static double[] pfad(List<Wegpunkte.Ecke> ecken) {
        Wegpunkte.EigeneForm f = new Wegpunkte.EigeneForm(WELT, ecken, 0, false, "Weg", Wegpunkte.KEIN_HEX);
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
}
