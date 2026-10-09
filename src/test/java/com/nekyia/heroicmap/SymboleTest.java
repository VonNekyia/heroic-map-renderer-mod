package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Die Symbole der Nadeln: Adresse, Pfad, Holen mit harten Grenzen, Speicher. Siehe docs/ebenen.md, „Symbole“. */
class SymboleTest {

    private static final String PFAD = "/tiles/layers/beispiel/images/";
    private HttpServer server;
    private URI basis;
    private final Map<String, byte[]> dateien = new ConcurrentHashMap<>();
    private final List<String> anfragen = new CopyOnWriteArrayList<>();
    /** Hält die Antworten auf {@code warte.png} und {@code schweigt.png}, bis der Test sie freigibt. */
    private final CountDownLatch frei = new CountDownLatch(1);

    @BeforeEach
    void starte() throws IOException {
        dateien.put(PFAD + "burg_16.png", png(16));
        dateien.put(PFAD + "burg_9.png", png(9));
        dateien.put(PFAD + "genau.png", aufgefuellt(png(16), Symbole.MAX));
        dateien.put(PFAD + "riesig.png", aufgefuellt(png(16), Symbole.MAX + 1));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", t -> {
            String pfad = t.getRequestURI().getPath();
            anfragen.add(pfad);
            try {
                if (pfad.endsWith("weiter.png")) {
                    t.getResponseHeaders().add("Location", PFAD + "burg_16.png");
                    t.sendResponseHeaders(302, -1);
                } else if (pfad.endsWith("schweigt.png")) {
                    // Header mit 200, dann kein Körper.
                    t.sendResponseHeaders(200, 1000);
                    frei.await(30, TimeUnit.SECONDS);
                } else if (pfad.endsWith("warte.png")) {
                    frei.await(30, TimeUnit.SECONDS);
                    t.sendResponseHeaders(404, -1);
                } else if (dateien.containsKey(pfad)) {
                    t.sendResponseHeaders(200, dateien.get(pfad).length);
                    try (OutputStream aus = t.getResponseBody()) {
                        aus.write(dateien.get(pfad));
                    }
                } else {
                    t.sendResponseHeaders(404, -1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                t.close();
            }
        });
        server.start();
        basis = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/tiles");
    }

    @AfterEach
    void stoppe() {
        frei.countDown();
        server.stop(0);
    }

    private static byte[] png(int seite) throws IOException {
        BufferedImage bild = new BufferedImage(seite, seite, BufferedImage.TYPE_INT_ARGB);
        bild.setRGB(0, 0, 0xFF123456);
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        ImageIO.write(bild, "png", aus);
        return aus.toByteArray();
    }

    /** Dasselbe PNG, mit einem Chunk {@code tEXt} vor {@code IEND} auf genau {@code laenge} Byte aufgefüllt. */
    private static byte[] aufgefuellt(byte[] png, int laenge) {
        int iend = png.length - 12, daten = laenge - png.length - 12;
        byte[] typ = "tEXt".getBytes(StandardCharsets.ISO_8859_1), inhalt = new byte[daten];
        inhalt[0] = 'x';
        CRC32 crc = new CRC32();
        crc.update(typ);
        crc.update(inhalt);
        ByteBuffer aus = ByteBuffer.allocate(laenge);
        aus.put(png, 0, iend).putInt(daten).put(typ).put(inhalt).putInt((int) crc.getValue()).put(png, iend, 12);
        return aus.array();
    }

    @Test
    void basisAusUrlOderPort() throws IOException {
        assertEquals(URI.create("https://karte.example.org/tiles"), Symbole.basis("https://karte.example.org/tiles", 0, null));
        assertNull(Symbole.basis("https://karte.example.org/tiles?x=1", 0, null));
        assertNull(Symbole.basis("ftp://karte.example.org/tiles", 0, null));
        InetAddress v4 = InetAddress.getByName("203.0.113.7"), v6 = InetAddress.getByName("2001:db8::7");
        assertEquals(URI.create("http://203.0.113.7:8080/tiles"), Symbole.basis(null, 8080, v4));
        assertEquals(URI.create("http://[2001:db8:0:0:0:0:0:7]:8080/tiles"), Symbole.basis(null, 8080, v6));
        assertNull(Symbole.basis(null, 0, v4));
        assertNull(Symbole.basis(null, 65536, v4));
        assertNull(Symbole.basis(null, 8080, null));
    }

    @Test
    void nurBilderDerEigenenEbene() {
        URI b = URI.create("https://karte.example.org/tiles");
        assertEquals(URI.create("https://karte.example.org/tiles/layers/beispiel/images/burg_16.png"),
                Symbole.uri(b, "beispiel:staedte", "images/burg_16.png"));
        assertEquals(URI.create("https://karte.example.org/tiles/layers/beispiel/images/burg.9.webp"),
                Symbole.uri(URI.create("https://karte.example.org/tiles/"), "beispiel:staedte", "images/burg.9.webp"));
        for (String feld : new String[] {"../burg.png", "images/../burg.png", "images/unter/burg.png", "images/.burg.png",
                "images/Burg.png", "images/burg.gif", "/images/burg.png", "images/burg.png?x", "burg.png"}) {
            assertNull(Symbole.uri(b, "beispiel:staedte", feld), feld);
        }
        assertNull(Symbole.uri(b, "Beispiel:staedte", "images/burg.png"));
        assertNull(Symbole.uri(b, "staedte", "images/burg.png"));
        assertNull(Symbole.uri(null, "beispiel:staedte", "images/burg.png"));
    }

    private Kacheln.Bild hole(String name, InetAddress spielserver, Duration frist) {
        return Symbole.hole(Symbole.CLIENT, Symbole.uri(basis, "beispiel:staedte", "images/" + name), spielserver, 16, frist);
    }

    @Test
    void holenMitHartenGrenzen() throws IOException {
        InetAddress hier = InetAddress.getLoopbackAddress();
        Kacheln.Bild bild = hole("burg_16.png", hier, Symbole.FRIST);
        assertNotNull(bild);
        assertEquals(0xFF123456, bild.argb()[0]);
        // Genau MAX Byte gehen, ein Byte mehr nicht; das Bild ist in beiden Fällen gültig.
        assertNotNull(hole("genau.png", hier, Symbole.FRIST));
        assertNull(hole("riesig.png", hier, Symbole.FRIST));
        // Falsche Grösse, fehlt, Weiterleitung auf ein gültiges Bild: alles leer, mit dem Client der Produktion.
        assertNull(hole("burg_9.png", hier, Symbole.FRIST));
        assertNull(hole("fehlt.png", hier, Symbole.FRIST));
        assertNull(hole("weiter.png", hier, Symbole.FRIST));
        // Ein ferner Spielserver darf nicht auf loopback lenken.
        assertNull(hole("burg_16.png", InetAddress.getByName("203.0.113.7"), Symbole.FRIST));
    }

    @Test
    void sendeHaeltDieFrist() {
        // Ohne Frist an der Anfrage: Allein get(zeit) in Laden.sende hält den schweigenden Server auf.
        HttpRequest anfrage = HttpRequest.newBuilder(URI.create(basis + "/layers/beispiel/images/schweigt.png")).GET().build();
        long start = System.nanoTime();
        assertThrows(Laden.Fehler.class, () -> Laden.sende(Symbole.CLIENT, anfrage, Symbole.MAX, Laden.Grund.NETZ, Duration.ofMillis(500), "schweigt"));
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(5).toNanos(), "nach der Frist zurück");
    }

    @Test
    void nurEinfacheWebP() throws IOException {
        // Eine einfache VP8L lädt; eine mit VP8X oder verlustbehaftet gibt null, ohne TwelveMonkeys.
        byte[] vp8l;
        try (var rein = SymboleTest.class.getResourceAsStream("/farbindex.webp")) {
            vp8l = rein.readAllBytes();
        }
        assertEquals(8, Kacheln.vp8l(vp8l, 8).breite());
        byte[] vp8x = new byte[30], vp8 = new byte[30];
        System.arraycopy("RIFF\u0016\u0000\u0000\u0000WEBPVP8X".getBytes(StandardCharsets.ISO_8859_1), 0, vp8x, 0, 16);
        System.arraycopy("RIFF\u0016\u0000\u0000\u0000WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1), 0, vp8, 0, 16);
        assertNull(Kacheln.vp8l(vp8x, 16));
        assertNull(Kacheln.vp8l(vp8, 16));
    }

    @Test
    void schweigenderServerHaeltNichtAuf() {
        long start = System.nanoTime();
        assertNull(hole("schweigt.png", InetAddress.getLoopbackAddress(), Duration.ofMillis(500)));
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(5).toNanos(), "nach der Frist zurück");
    }

    /** Symbole mit eigenem Thread, ohne Minecraft: Ablage und Freigabe zählen nur mit. */
    private record Aufbau(Symbole symbole, List<Identifier> abgelegt, List<Identifier> frei) {
    }

    private Aufbau aufbau() {
        List<Identifier> abgelegt = new CopyOnWriteArrayList<>(), freigegeben = new CopyOnWriteArrayList<>();
        Symbole s = new Symbole(Symbole.CLIENT, Executors.newSingleThreadExecutor(), Runnable::run, (uri, bild) -> {
            Identifier id = Identifier.fromNamespaceAndPath("test", "symbol_" + abgelegt.size());
            abgelegt.add(id);
            return id;
        }, freigegeben::add);
        JsonObject liste = new JsonObject();
        liste.addProperty("url", basis.toString());
        s.basis(liste, InetAddress.getLoopbackAddress());
        return new Aufbau(s, abgelegt, freigegeben);
    }

    @Test
    void seiteImSchluesselUndFreigabe() throws Exception {
        Aufbau a = aufbau();
        Symbole s = a.symbole();
        assertNull(s.symbol("beispiel:staedte", "v1", "images/burg_16.png", 16));
        assertNull(s.symbol("beispiel:staedte", "v1", "images/burg_16.png", 9));
        s.warte();
        // Dasselbe Feld als large lädt, als medium nicht, denn es hat 16 × 16 Pixel.
        Identifier gross = s.symbol("beispiel:staedte", "v1", "images/burg_16.png", 16);
        assertNotNull(gross);
        assertNull(s.symbol("beispiel:staedte", "v1", "images/burg_16.png", 9));
        // Eine neue version gibt die alten Texturen frei und holt neu.
        assertNull(s.symbol("beispiel:staedte", "v2", "images/burg_16.png", 16));
        assertEquals(List.of(gross), a.frei());
        s.warte();
        Identifier neu = s.symbol("beispiel:staedte", "v2", "images/burg_16.png", 16);
        assertNotNull(neu);
        // Fällt die Ebene aus der Liste, sind ihre Texturen frei.
        s.behalte(List.of("beispiel:andere"));
        assertEquals(List.of(gross, neu), a.frei());
    }

    @Test
    void hoechstens200Bilder() throws Exception {
        Symbole s = aufbau().symbole();
        for (int i = 0; i < Symbole.MAX_BILDER + 5; i++) {
            s.symbol("beispiel:staedte", "v1", "images/b" + i + ".png", 16);
        }
        s.warte();
        assertEquals(Symbole.MAX_BILDER, anfragen.size());
    }

    @Test
    void nachNeuerVersionKeineAnfrageFuerDieAlte() throws Exception {
        Symbole s = aufbau().symbole();
        // Die erste Anfrage hält den Thread, ein Feld der alten version wartet dahinter.
        s.symbol("beispiel:staedte", "v1", "images/warte.png", 16);
        s.symbol("beispiel:staedte", "v1", "images/alt.png", 16);
        for (int i = 0; i < 100 && anfragen.isEmpty(); i++) {
            Thread.sleep(20);
        }
        s.symbol("beispiel:staedte", "v2", "images/burg_16.png", 16);
        frei.countDown();
        s.warte();
        assertEquals(List.of(PFAD + "warte.png", PFAD + "burg_16.png"), new ArrayList<>(anfragen));
    }

    @Test
    void nachDemLeerenKeineAnfrage() throws Exception {
        Symbole s = aufbau().symbole();
        // Die erste Anfrage hält den Thread, die zweite wartet dahinter.
        s.symbol("beispiel:staedte", "v1", "images/warte.png", 16);
        s.symbol("beispiel:staedte", "v1", "images/danach.png", 16);
        for (int i = 0; i < 100 && anfragen.isEmpty(); i++) {
            Thread.sleep(20);
        }
        s.leeren();
        frei.countDown();
        s.warte();
        assertEquals(List.of(PFAD + "warte.png"), new ArrayList<>(anfragen));
    }
}
