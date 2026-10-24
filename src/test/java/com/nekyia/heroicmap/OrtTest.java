package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Der Ort einer Karte: Teile lesen, Eingabe prüfen, Umzug ganz oder gar nicht. Siehe docs/download.md, „Ort ändern“. */
class OrtTest {

    private static final String HASH = "3fa1c2d4e5f60718", DIM = "minecraft:overworld";

    @Test
    void teileUndOrdner(@TempDir Path w) {
        assertEquals(new Ort.Teile("play.example.net", "main", HASH), Ort.teile(w, w.resolve("play.example.net/karte-main/welt-" + HASH)));
        assertEquals(new Ort.Teile("play.example.net", "", HASH), Ort.teile(w, w.resolve("play.example.net/welt-" + HASH)));
        assertEquals(new Ort.Teile("", "", HASH), Ort.teile(w, w.resolve("welt-" + HASH)), "Ablage Hash");
        assertEquals(new Ort.Teile("", "build", HASH), Ort.teile(w, w.resolve("karte-build/welt-" + HASH)));
        // Keine Welt unter einer Adresse: Einzelspieler, alte Ablage, zu tief, Hash gross oder kurz.
        assertNull(Ort.teile(w, w.resolve("einzelspieler_neu/welt-" + HASH)));
        assertNull(Ort.teile(w, w.resolve("play.example.net/hauptwelt")));
        assertNull(Ort.teile(w, w.resolve("a/b/karte-c/welt-" + HASH)));
        assertNull(Ort.teile(w, w.resolve("a/welt-3FA1C2D4E5F60718")));
        assertNull(Ort.teile(w, w.resolve("a/welt-3fa1c2d4e5f6071")));
        for (String p : new String[] {"play.example.net/karte-main/welt-" + HASH, "welt-" + HASH, "karte-build/welt-" + HASH}) {
            assertEquals(w.resolve(p), Ort.ordner(w, Ort.teile(w, w.resolve(p))), p);
        }
    }

    @Test
    void eingabePruefen() {
        String[] f = new String[1];
        assertEquals(new Ort.Teile("play.example.net", "build", HASH), Ort.eingabe(" Play.Example.NET ", "build", HASH, f));
        assertEquals(new Ort.Teile("", "", HASH), Ort.eingabe("", "", HASH, f));
        assertNull(Ort.eingabe("a", "", "3FA1C2D4E5F60718", f), "der Hash klein");
        assertEquals("heroicmap.ort.fehler.hash", f[0]);
        assertNull(Ort.eingabe("a", "", HASH + "0", f));
        assertNull(Ort.eingabe("a", "Main!", HASH, f));
        assertEquals("heroicmap.ort.fehler.server", f[0]);
        assertNull(Ort.eingabe("einzelspieler_welt", "", HASH, f), "nicht in den Einzelspieler");
        assertEquals("heroicmap.ort.fehler.adresse", f[0]);
        assertNull(Ort.eingabe("karte-x", "", HASH, f));
    }

    @Test
    void gruende(@TempDir Path w) throws IOException {
        Path welt = w.resolve("a/welt-" + HASH), neu = w.resolve("a/karte-build/welt-" + HASH), hier = w.resolve("b/welt-" + HASH);
        Path baum = welt.resolve("baum");
        assertNull(Ort.grund(welt, welt, neu, neu, hier, p -> false, null, false));
        assertEquals("heroicmap.ort.gleich", Ort.grund(welt, welt, welt, welt, hier, p -> false, null, false));
        Files.createDirectories(neu);
        assertEquals("heroicmap.ort.da", Ort.grund(welt, welt, neu, neu, hier, p -> false, null, false));
        assertNull(Ort.grund(welt, baum, neu, neu.resolve("baum"), hier, p -> false, null, false), "nur diese Karte: der Ordner dort stört nicht");
        Files.createDirectories(neu.resolve("baum"));
        assertEquals("heroicmap.ort.da", Ort.grund(welt, baum, neu, neu.resolve("baum"), hier, p -> false, null, false));
        Path frei = w.resolve("a/karte-x/welt-" + HASH), hierBaum = hier.resolve("baum");
        // Die Welt, in der der Spieler ist, nur bei „Ganzer Ordner“, als alter wie als neuer Ort.
        assertEquals("heroicmap.ort.hier", Ort.grund(hier, hier, frei, frei, hier, p -> false, null, false));
        assertEquals("heroicmap.ort.hier", Ort.grund(welt, welt, hier, hier, hier, p -> false, null, false));
        assertNull(Ort.grund(hier, hierBaum, frei, frei.resolve("baum"), hier, p -> false, null, false));
        assertEquals("heroicmap.ort.selbst", Ort.grund(welt, welt, frei, frei, hier, welt::equals, null, false));
        assertEquals("heroicmap.ort.selbst", Ort.grund(welt, baum, frei, frei.resolve("baum"), hier, frei::equals, null, false));
        // Was Minimap und Vollbildkarte zeigen, zieht nicht unter ihnen weg: der Baum selbst, oder ein Ordner mit ihm, etwa
        // das Ziel eines Zeigers. Ein anderer Baum, auch einer, dessen Name mit dem gezeigten beginnt, zieht um.
        assertEquals("heroicmap.ort.gezeigt", Ort.grund(hier, hierBaum, frei, frei.resolve("baum"), hier, p -> false, hierBaum, false));
        assertNull(Ort.grund(hier, hierBaum, frei, frei.resolve("baum"), hier, p -> false, hier.resolve("anderer"), false));
        assertNull(Ort.grund(hier, hierBaum, frei, frei.resolve("baum"), hier, p -> false, hier.resolve("baum2"), false));
        assertEquals("heroicmap.ort.gezeigt", Ort.grund(welt, welt, frei, frei, hier, p -> false, baum, false));
        assertEquals("heroicmap.ort.belegt", Ort.grund(welt, welt, frei, frei, hier, p -> false, null, true));
    }

    @Test
    void kurz() {
        String pfad = "play.example.net/karte-main/welt-" + HASH + "/hauptwelt", ende = "welt-" + HASH + "/hauptwelt";
        assertEquals(pfad, Ort.kurz(pfad, pfad.length(), String::length), "passt, bleibt");
        // Vorn die Adresse, so viel passt, hinten Welt und Baum ganz; die Breite ganz genutzt.
        String s = Ort.kurz(pfad, 50, String::length);
        assertEquals("play.example.net/k…" + ende, s);
        assertEquals("play.exam…" + ende, Ort.kurz(pfad, 41, String::length));
        assertEquals("…" + ende, Ort.kurz(pfad, ende.length() + 1, String::length));
        // Schmaler als Welt und Baum: das Ende mit dem Baum.
        assertEquals("…" + HASH.substring(10) + "/hauptwelt", Ort.kurz(pfad, 17, String::length));
        assertEquals("…", Ort.kurz(pfad, 0, String::length), "nie leer");
        // Jede Breite: ein Anfang des Pfads, „…“, ein Ende des Pfads; ab 11 Zeichen mit dem ganzen Baum.
        for (int b = 1; b < pfad.length(); b++) {
            String k = Ort.kurz(pfad, b, String::length);
            int m = k.indexOf('…');
            assertTrue(k.length() <= b && m >= 0 && pfad.startsWith(k.substring(0, m)) && pfad.endsWith(k.substring(m + 1)), b + ": " + k);
            assertTrue(b < 11 || k.endsWith("/hauptwelt"), "der Baum bleibt: " + k);
        }
        // Zwei Teile, etwa die Ablage nach dem Hash: nur das Ende.
        assertEquals("…" + HASH + "/baum", Ort.kurz("welt-" + HASH + "/baum", 22, String::length));
    }

    /** Eine Welt unter play.example.net ohne Server: ein Download, eine eigene Karte ohne Server, Wegpunkte und zeigt.txt. */
    private static Path welt(Path w) throws IOException {
        Path welt = w.resolve("play.example.net/welt-" + HASH);
        Satz.schreibe(welt.resolve("hauptwelt"), "Hauptwelt", DIM, 4);
        Path selbst = Selbst.anlegen(welt, DIM, 4);
        Files.writeString(welt.resolve("wegpunkte.json"), "{\"wegpunkte\":[]}");
        Files.writeString(welt.resolve(Zeigt.DATEI), "- " + DIM + " " + selbst.getFileName() + "\nmain " + DIM + " hauptwelt\n");
        return welt;
    }

    /** Alle Dateien unter {@code w} mit ihrem Inhalt, zum Vergleich vorher und nachher. */
    private static Map<String, String> stand(Path w) throws IOException {
        Map<String, String> aus = new TreeMap<>();
        try (Stream<Path> alle = Files.walk(w)) {
            for (Path p : alle.toList()) {
                aus.put(w.relativize(p).toString().replace('\\', '/'), Files.isDirectory(p) ? "/" : Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        return aus;
    }

    @Test
    void ganzerOrdnerZiehtUm(@TempDir Path w) throws IOException {
        Path alt = welt(w), neu = w.resolve("play.example.net/karte-build/welt-" + HASH);
        Ort.zieheOrdner(w, alt, neu, Zeigt.OHNE, "build");
        assertFalse(Files.exists(alt));
        assertTrue(Files.exists(neu.resolve("wegpunkte.json")), "Wegpunkte ziehen mit");
        assertTrue(Files.exists(neu.resolve("hauptwelt/satz.json")), "der Download zieht mit");
        // Die eigene Karte gehört danach build: neuer Name, server in satz.json, und zeigt.txt sagt es für build.
        Path selbst = neu.resolve(Selbst.baum(DIM, "build"));
        assertTrue(Selbst.gehoert(selbst, "build"));
        assertFalse(Files.exists(neu.resolve(Selbst.baum(DIM, Zeigt.OHNE))));
        assertEquals(selbst.getFileName().toString(), Zeigt.baum(neu, "build", DIM));
        assertEquals("hauptwelt", Zeigt.baum(neu, "main", DIM), "Zeilen anderer Server bleiben");
        assertNull(Zeigt.baum(neu, Zeigt.OHNE, DIM));
        // Nur Adresse und Hash anders: Der Server bleibt, keine Umbenennung.
        Path dritt = w.resolve("andere.example.net/karte-build/welt-0123456789abcdef");
        Ort.zieheOrdner(w, neu, dritt, "build", "build");
        assertTrue(Files.exists(dritt.resolve(selbst.getFileName())));
        assertFalse(Files.exists(w.resolve("play.example.net")), "leere Ordner darüber gehen mit");
    }

    @Test
    void ganzOderGarNicht(@TempDir Path w) throws IOException {
        Path alt = welt(w), neu = w.resolve("play.example.net/karte-build/welt-" + HASH);
        // zeigt.txt lässt sich nicht lesen: Der Umzug scheitert nach dem Verschieben und nach dem Umbenennen der eigenen Karte.
        Files.delete(alt.resolve(Zeigt.DATEI));
        Files.createDirectories(alt.resolve(Zeigt.DATEI).resolve("x"));
        Map<String, String> vorher = stand(w);
        assertThrows(IOException.class, () -> Ort.zieheOrdner(w, alt, neu, Zeigt.OHNE, "build"));
        assertEquals(vorher, stand(w), "alles wie vorher, auch kein Ordner karte-build");
        // Auf etwas, das schon da ist, nie; nichts ändert sich.
        Path voll = w.resolve("play.example.net/karte-main/welt-" + HASH);
        Files.createDirectories(voll);
        Map<String, String> davor = stand(w);
        assertThrows(IOException.class, () -> Ort.zieheOrdner(w, alt, voll, Zeigt.OHNE, "main"));
        assertEquals(davor, stand(w));
    }

    @Test
    void nurDieseKarte(@TempDir Path w) throws IOException {
        Path alt = welt(w), neuWelt = w.resolve("play.example.net/karte-build/welt-0123456789abcdef");
        Ort.zieheBaum(alt.resolve("hauptwelt"), neuWelt, "build");
        assertTrue(Files.exists(neuWelt.resolve("hauptwelt/satz.json")));
        assertFalse(Files.exists(alt.resolve("hauptwelt")));
        assertNull(Zeigt.baum(alt, "main", DIM), "die Zeile auf den Baum fällt weg");
        assertTrue(Files.exists(alt.resolve("wegpunkte.json")), "Wegpunkte bleiben");
        // Eine eigene Karte gehört danach dem Server des neuen Ordners.
        Ort.zieheBaum(alt.resolve(Selbst.baum(DIM, Zeigt.OHNE)), neuWelt, "build");
        assertTrue(Selbst.gehoert(neuWelt.resolve(Selbst.baum(DIM, "build")), "build"));
        // Auf einen Baum, der schon da ist, nie.
        Satz.schreibe(alt.resolve("hauptwelt"), "Hauptwelt", DIM, 2);
        Map<String, String> vorher = stand(w);
        assertThrows(IOException.class, () -> Ort.zieheBaum(alt.resolve("hauptwelt"), neuWelt, "build"));
        assertEquals(vorher, stand(w));
    }
}
