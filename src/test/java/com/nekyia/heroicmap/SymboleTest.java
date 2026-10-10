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
import java.util.function.LongSupplier;
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
                } else if (pfad.endsWith("zweimal.png")) {
                    // Die erste Anfrage scheitert gleich, jede weitere erst, wenn der Test sie freigibt.
                    if (anfragen.stream().filter(pfad::equals).count() > 1) {
                        frei.await(30, TimeUnit.SECONDS);
                    }
                    t.sendResponseHeaders(404, -1);
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
        return png(seite, seite);
    }

    private static byte[] png(int breite, int hoehe) throws IOException {
        BufferedImage bild = new BufferedImage(breite, hoehe, BufferedImage.TYPE_INT_ARGB);
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

    @Test
    void bannerBisZurGrenze() throws IOException {
        dateien.put(PFAD + "banner.png", png(22, 40));
        dateien.put(PFAD + "voll.png", png(Symbole.BANNER_BREITE, Symbole.BANNER_HOEHE));
        dateien.put(PFAD + "zu_breit.png", png(Symbole.BANNER_BREITE + 1, Symbole.BANNER_HOEHE));
        dateien.put(PFAD + "zu_hoch.png", png(Symbole.BANNER_BREITE, Symbole.BANNER_HOEHE + 1));
        InetAddress hier = InetAddress.getLoopbackAddress();
        Kacheln.Bild b = holeBanner("banner.png", hier);
        assertEquals(22, b.breite());
        assertEquals(40, b.hoehe());
        assertEquals(22 * 40, b.argb().length);
        assertEquals(Symbole.BANNER_HOEHE, holeBanner("voll.png", hier).hoehe());
        assertNull(holeBanner("zu_breit.png", hier));
        assertNull(holeBanner("zu_hoch.png", hier));
        // Ein Symbol bleibt genau so gross: 22 × 40 ist keins.
        assertNull(hole("banner.png", hier, Symbole.FRIST));
    }

    @Test
    void bannerAlsWebP() throws IOException {
        // 22 × 40, nicht quadratisch, nur VP8L: als Banner ja, als Symbol nicht.
        byte[] webp;
        try (var rein = SymboleTest.class.getResourceAsStream("/banner.webp")) {
            webp = rein.readAllBytes();
        }
        Kacheln.Bild b = Kacheln.vp8l(webp, Symbole.BANNER_BREITE, Symbole.BANNER_HOEHE, true);
        assertEquals(22, b.breite());
        assertEquals(40, b.hoehe());
        assertEquals(0xFF2E4A8C, b.argb()[0]);
        dateien.put(PFAD + "banner.webp", webp);
        InetAddress hier = InetAddress.getLoopbackAddress();
        assertEquals(40, holeBanner("banner.webp", hier).hoehe());
        assertNull(hole("banner.webp", hier, Symbole.FRIST));
    }

    @Test
    void bannerTeilenEinBildUndZaehlenMit() throws Exception {
        dateien.put(PFAD + "banner.png", png(22, 40));
        Aufbau a = aufbau();
        Symbole s = a.symbole();
        // Zwei Banner mit demselben Bild: eine Anfrage, eine Textur in 22 × 40.
        int stand = s.stand();
        assertNull(s.banner("beispiel:staedte", "v1", "images/banner.png"));
        assertNull(s.banner("beispiel:staedte", "v1", "images/banner.png"));
        s.warte();
        // Ein angekommenes Bild hebt den Stand; die Vollbildkarte sucht ihr Ziel danach neu.
        assertEquals(stand + 1, s.stand());
        Symbole.Textur t = s.banner("beispiel:staedte", "v1", "images/banner.png");
        assertEquals(t, s.banner("beispiel:staedte", "v1", "images/banner.png"));
        assertEquals(List.of(PFAD + "banner.png"), new ArrayList<>(anfragen));
        assertEquals(1, a.abgelegt().size());
        assertEquals(22, t.breite());
        assertEquals(40, t.hoehe());
        // Banner zählen mit den Symbolen gegen 200: nach 199 Symbolen holt der Mod kein zweites Banner.
        for (int i = 0; i < Symbole.MAX_BILDER - 1; i++) {
            s.symbol("beispiel:staedte", "v1", "images/b" + i + ".png", 16);
        }
        assertNull(s.banner("beispiel:staedte", "v1", "images/zweites.png"));
        s.warte();
        assertEquals(Symbole.MAX_BILDER, anfragen.size());
        assertTrue(anfragen.stream().noneMatch(p -> p.endsWith("zweites.png")));
        // Eine neue version gibt die Textur des Banners frei.
        s.banner("beispiel:staedte", "v2", "images/banner.png");
        assertEquals(List.of(t.id()), a.frei());
    }

    private Kacheln.Bild holeBanner(String name, InetAddress spielserver) {
        return Symbole.hole(Symbole.CLIENT, Symbole.uri(basis, "beispiel:staedte", "images/" + name), spielserver,
                Symbole.BANNER_BREITE, Symbole.BANNER_HOEHE, true, Symbole.FRIST);
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

    /** Mit einer Uhr, die stillsteht: Ein Bild, das nicht kam, holt er dann nie neu, wie vor dem Wiederholen. */
    private Aufbau aufbau() {
        return aufbau(() -> 0);
    }

    private Aufbau aufbau(LongSupplier uhr) {
        List<Identifier> abgelegt = new CopyOnWriteArrayList<>(), freigegeben = new CopyOnWriteArrayList<>();
        Symbole s = new Symbole(Symbole.CLIENT, Executors.newSingleThreadExecutor(), Runnable::run, (uri, bild) -> {
            Identifier id = Identifier.fromNamespaceAndPath("test", "symbol_" + abgelegt.size());
            abgelegt.add(id);
            return id;
        }, freigegeben::add, uhr);
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
    void fehlendesBildNach1Und5DannAlle15Minuten() throws Exception {
        // Wie mod#58: Das Banner fehlt erst (404), etwa weil der Server es nach der Ebene schreibt oder ein Proxy es nicht
        // durchreicht. Nach 1 min holt der Mod es neu und zeigt es, ohne neue version und ohne neues Login.
        long[] jetzt = {0};
        Aufbau a = aufbau(() -> jetzt[0]);
        Symbole s = a.symbole();
        assertNull(s.banner("beispiel:staedte", "v1", "images/spaet.png"));
        s.warte();
        dateien.put(PFAD + "spaet.png", png(22, 40));
        jetzt[0] = 60_000 - 1;
        assertNull(s.banner("beispiel:staedte", "v1", "images/spaet.png"));
        s.warte();
        assertEquals(1, anfragen.size());
        jetzt[0] = 60_000;
        assertNull(s.banner("beispiel:staedte", "v1", "images/spaet.png"));
        s.warte();
        assertEquals(2, anfragen.size());
        assertEquals(22, s.banner("beispiel:staedte", "v1", "images/spaet.png").breite());
        // Ein Bild, das nie kommt: nach 1, nach 5, dann alle 15 min, ohne Deckel; je eine Minute früher nichts.
        long t = 10_000_000;
        jetzt[0] = t;
        s.banner("beispiel:staedte", "v1", "images/nie.png");
        s.warte();
        int vorher = anfragen.size();
        for (long abstand : new long[] {60_000, 300_000, 900_000, 900_000, 900_000}) {
            jetzt[0] = t + abstand - 1;
            s.banner("beispiel:staedte", "v1", "images/nie.png");
            s.warte();
            assertEquals(vorher, anfragen.size(), "zu früh nach " + abstand);
            t += abstand;
            jetzt[0] = t;
            s.banner("beispiel:staedte", "v1", "images/nie.png");
            s.warte();
            assertEquals(++vorher, anfragen.size(), "nicht neu nach " + abstand);
        }
        // Eine neue version fängt von vorn an.
        s.banner("beispiel:staedte", "v2", "images/nie.png");
        s.warte();
        assertEquals(vorher + 1, anfragen.size());
    }

    @Test
    void keinZweiterVersuchNebenEinemLaufenden() throws Exception {
        // Der erste scheitert gleich; der neue Versuch nach 1 min hängt am Server. Solange er läuft, geht keiner neben ihm hinaus.
        long[] jetzt = {0};
        Symbole s = aufbau(() -> jetzt[0]).symbole();
        s.banner("beispiel:staedte", "v1", "images/zweimal.png");
        s.warte();
        jetzt[0] = 60_000;
        s.banner("beispiel:staedte", "v1", "images/zweimal.png");
        for (int i = 0; i < 100 && anfragen.size() < 2; i++) {
            Thread.sleep(20);
        }
        jetzt[0] = 60_000 + 3_600_000;
        assertNull(s.banner("beispiel:staedte", "v1", "images/zweimal.png"));
        frei.countDown();
        s.warte();
        assertEquals(2, anfragen.size());
    }

    @Test
    void hoechstens1000BilderUeberAlleEbenen() throws Exception {
        // Sechs Ebenen mit je 200 Bildern: 1000 Anfragen, dann keine mehr; nach dem Leeren wieder.
        Symbole s = aufbau().symbole();
        for (int e = 0; e < 6; e++) {
            for (int i = 0; i < Symbole.MAX_BILDER; i++) {
                s.symbol("beispiel:e" + e, "v1", "images/b" + i + ".png", 16);
            }
        }
        s.warte();
        assertEquals(Symbole.MAX_BILDER_GESAMT, anfragen.size());
        s.leeren();
        s.symbol("beispiel:e0", "v1", "images/neu.png", 16);
        s.warte();
        assertEquals(Symbole.MAX_BILDER_GESAMT + 1, anfragen.size());
    }

    @Test
    void tafelbilderEigenesBudget() throws Exception {
        // Je 512 × 512, also 1 MiB: 16 passen in 16 MiB, das 17. gibt das am längsten nicht gezeigte frei.
        byte[] gross = png(Tafel.MAX_BILD);
        for (int i = 0; i < 17; i++) {
            dateien.put(PFAD + "t" + i + ".png", gross);
        }
        Aufbau a = aufbau();
        Symbole s = a.symbole();
        // Die Deckel der Symbole und Banner gelten nicht: 200 Symbole voll, Tafelbilder kommen trotzdem.
        for (int i = 0; i < Symbole.MAX_BILDER; i++) {
            s.symbol("beispiel:staedte", "v1", "images/b" + i + ".png", 16);
        }
        s.warte();
        // Je Bild warten: Die Ablage läuft im Test auf dem Thread des Holers, nie neben einer neuen Frage.
        for (int i = 0; i < 16; i++) {
            s.tafelBild("beispiel:staedte", "v1", "images/t" + i + ".png", Tafel.MAX_BILD, Tafel.MAX_BILD);
            s.warte();
        }
        assertEquals(16, a.abgelegt().size());
        assertEquals(List.of(), a.frei());
        // t0 gerade gezeigt; t1 ist jetzt das älteste und geht frei, sobald t16 da ist.
        assertNotNull(s.tafelBild("beispiel:staedte", "v1", "images/t0.png", Tafel.MAX_BILD, Tafel.MAX_BILD));
        s.tafelBild("beispiel:staedte", "v1", "images/t16.png", Tafel.MAX_BILD, Tafel.MAX_BILD);
        s.warte();
        assertEquals(List.of(a.abgelegt().get(1)), a.frei());
        // Beim Leeren gehen alle übrigen frei.
        s.leeren();
        assertEquals(17, a.frei().size());
    }

    @Test
    void tafelbildVerkleinertGeglaettet() throws Exception {
        // 4 × 2, links schwarz, rechts weiss: auf 2 × 1 links schwarz, rechts weiss; auf 1 × 1 Grau.
        int[] argb = {0xFF000000, 0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF000000, 0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF};
        Kacheln.Bild b = new Kacheln.Bild(4, 2, argb);
        Kacheln.Bild halb = Symbole.verkleinert(b, 2, 1);
        assertEquals(0xFF000000, halb.argb()[0]);
        assertEquals(0xFFFFFFFF, halb.argb()[1]);
        int grau = Symbole.verkleinert(b, 1, 1).argb()[0];
        assertTrue((grau & 0xFF) > 0x60 && (grau & 0xFF) < 0xA0, Integer.toHexString(grau));
        // Über den Server: 32 × 32 auf der Tafel 16 × 16 kommt als 16 × 16.
        dateien.put(PFAD + "gross.png", png(32));
        Symbole s = aufbau().symbole();
        s.tafelBild("beispiel:staedte", "v1", "images/gross.png", 16, 16);
        s.warte();
        Symbole.Textur t = s.tafelBild("beispiel:staedte", "v1", "images/gross.png", 16, 16);
        assertEquals(16, t.breite());
        assertEquals(16, t.hoehe());
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
