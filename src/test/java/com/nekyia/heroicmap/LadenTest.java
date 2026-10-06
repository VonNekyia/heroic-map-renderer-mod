package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Der Download gegen einen kleinen Server auf loopback. Siehe docs/download.md. */
class LadenTest {

    private static final String TOKEN = "abc.def";

    @TempDir
    Path ziel;

    private HttpServer server;
    private ExecutorService threads;
    /** Gibt frei, was der Server zurückhält. */
    private final CountDownLatch frei = new CountDownLatch(1);
    /** Die Kacheln des Servers, z/x/y → Bild, und ihre ETags. */
    private final Map<String, byte[]> kacheln = new TreeMap<>();
    private final Map<String, String> etags = new TreeMap<>();
    private byte[] manifest;
    private String karte = "{\"tileSize\":256,\"scale\":4,\"minZoom\":0,\"maxZoom\":2}";
    /** Diese Kachel hält der Server zurück, bis {@link #frei}. */
    private String haelt;
    private final AtomicInteger abrufe = new AtomicInteger();
    private final AtomicInteger fremd = new AtomicInteger();

    @BeforeEach
    void starte() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        threads = Executors.newCachedThreadPool();
        server.setExecutor(threads);
        server.createContext("/baum/", this::antworte);
        server.createContext("/fremd/", t -> {
            fremd.incrementAndGet();
            sende(t, 200, new byte[] {1}, null);
        });
        server.start();
        for (String pfad : new String[] {"0/0/0", "1/0/0", "1/-1/0", "2/1/1", "2/-2/3"}) {
            setze(pfad, ("bild " + pfad).getBytes(StandardCharsets.UTF_8), "\"1-" + pfad.hashCode() + "\"");
        }
        manifest = baueManifest();
    }

    @AfterEach
    void stoppe() {
        frei.countDown();
        server.stop(0);
        threads.shutdownNow();
    }

    private void setze(String pfad, byte[] bild, String etag) {
        kacheln.put(pfad, bild);
        etags.put(pfad, etag);
    }

    private byte[] baueManifest() throws IOException {
        StringBuilder text = new StringBuilder();
        kacheln.forEach((pfad, bild) -> text.append(pfad).append(' ').append(bild.length).append(' ').append(etags.get(pfad)).append('\n'));
        return gzip(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] gzip(byte[] roh) throws IOException {
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(aus)) {
            gz.write(roh);
        }
        return aus.toByteArray();
    }

    private void antworte(HttpExchange t) throws IOException {
        if (!("Bearer " + TOKEN).equals(t.getRequestHeaders().getFirst("Authorization"))) {
            sende(t, 401, new byte[0], null);
            return;
        }
        String pfad = t.getRequestURI().getPath().substring("/baum/".length());
        switch (pfad) {
            case "map.json" -> sende(t, 200, karte.getBytes(StandardCharsets.UTF_8), null);
            case "manifest" -> sende(t, 200, manifest, null);
            default -> {
                String zxy = pfad.replace(".webp", "");
                if (zxy.equals(haelt)) {
                    warte();
                }
                if (kacheln.containsKey(zxy)) {
                    abrufe.incrementAndGet();
                    sende(t, 200, kacheln.get(zxy), etags.get(zxy));
                } else {
                    sende(t, 404, new byte[0], null);
                }
            }
        }
    }

    private void warte() {
        try {
            frei.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sende(HttpExchange t, int status, byte[] inhalt, String etag) throws IOException {
        if (etag != null) {
            t.getResponseHeaders().add("ETag", etag);
        }
        t.sendResponseHeaders(status, inhalt.length == 0 ? -1 : inhalt.length);
        try (OutputStream aus = t.getResponseBody()) {
            aus.write(inhalt);
        }
    }

    private Laden.Auftrag auftrag(int massstab, long bytes) {
        return auftrag(massstab, bytes, false, InetAddress.getLoopbackAddress());
    }

    private Laden.Auftrag auftrag(int massstab, long bytes, boolean abgleich, InetAddress spielserver) {
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/baum");
        return new Laden.Auftrag(url, TOKEN, Laden.sha256(manifest), bytes, massstab, abgleich, 1L << 30, kacheln.size(),
                spielserver);
    }

    private long summe() {
        return kacheln.values().stream().mapToLong(b -> b.length).sum();
    }

    private Path datei(String pfad) {
        String[] zxy = pfad.split("/");
        return ziel.resolve(zxy[0]).resolve(zxy[1]).resolve(zxy[2] + ".webp");
    }

    @Test
    void vollerDownloadUndFortsetzen() throws Exception {
        Laden.Ergebnis erst = new Laden().lade(auftrag(4, summe()), ziel);
        assertEquals(5, erst.geladen());
        for (Map.Entry<String, byte[]> k : kacheln.entrySet()) {
            assertArrayEquals(k.getValue(), Files.readAllBytes(datei(k.getKey())));
        }
        assertEquals(etags, new TreeMap<>(Laden.liesIndex(ziel)));
        assertTrue(Files.exists(ziel.resolve("map.json")));

        Laden.Ergebnis dann = new Laden().lade(auftrag(4, summe()), ziel);
        assertEquals(0, dann.geladen());
        assertEquals(5, dann.gleich());
        assertEquals(5, abrufe.get());
    }

    @Test
    void geaenderteUndEntfernteKacheln() throws Exception {
        new Laden().lade(auftrag(4, summe()), ziel);
        setze("2/1/1", "neu".getBytes(StandardCharsets.UTF_8), "\"neu\"");
        kacheln.remove("2/-2/3");
        etags.remove("2/-2/3");
        manifest = baueManifest();

        Laden.Ergebnis ergebnis = new Laden().lade(auftrag(4, summe()), ziel);
        assertEquals(1, ergebnis.geladen());
        assertEquals(1, ergebnis.geloescht());
        assertFalse(Files.exists(datei("2/-2/3")));
        assertEquals("\"neu\"", Laden.liesIndex(ziel).get("2/1/1"));
    }

    @Test
    void nurBisZurStufeDesMassstabs() throws Exception {
        // maxZoom 2: 2 px reicht bis Stufe 1, 1 px bis Stufe 0.
        assertEquals(3, new Laden().lade(auftrag(2, summe()), ziel).geladen());
        assertFalse(Files.exists(ziel.resolve("2")));
        // Ohne Stufe 0 im Manifest fiele 1 px sonst als leerer Satz durch.
        kacheln.remove("0/0/0");
        etags.remove("0/0/0");
        manifest = baueManifest();
        karte = "{\"minZoom\":1,\"maxZoom\":2}";
        assertEquals(Laden.Grund.MANIFEST, assertThrows(Laden.Fehler.class,
                () -> new Laden().lade(auftrag(1, summe()), ziel.resolve("eins"))).grund);
    }

    @Test
    void falschePruefsumme() {
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/baum");
        Laden.Auftrag falsch = new Laden.Auftrag(url, TOKEN, "00".repeat(32), summe(), 4, false, 1L << 30, 5,
                InetAddress.getLoopbackAddress());
        assertEquals(Laden.Grund.PRUEFSUMME, assertThrows(Laden.Fehler.class, () -> new Laden().lade(falsch, ziel)).grund);
    }

    @Test
    void falschesToken() {
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/baum");
        Laden.Auftrag falsch = new Laden.Auftrag(url, "nein", Laden.sha256(manifest), summe(), 4, false, 1L << 30, 5,
                InetAddress.getLoopbackAddress());
        assertEquals(Laden.Grund.ABGELEHNT, assertThrows(Laden.Fehler.class, () -> new Laden().lade(falsch, ziel)).grund);
    }

    @Test
    void heimnetzOhneVerbindungDorthin() throws Exception {
        Laden.Auftrag a = auftrag(4, summe(), false, InetAddress.getByName("1.1.1.1"));
        assertEquals(Laden.Grund.HEIMNETZ, assertThrows(Laden.Fehler.class, () -> new Laden().lade(a, ziel)).grund);
        assertEquals(0, abrufe.get());
    }

    @Test
    void entpackenHatEinenDeckel() throws Exception {
        assertEquals(Laden.MANIFEST_MAX, Laden.entpacke(gzip(new byte[(int) Laden.MANIFEST_MAX])).length());
        // Ein Byte mehr, gepackt nur wenige Kilobyte.
        assertEquals(Laden.Grund.MANIFEST, assertThrows(Laden.Fehler.class,
                () -> Laden.entpacke(gzip(new byte[(int) Laden.MANIFEST_MAX + 1]))).grund);
        assertEquals(Laden.Grund.MANIFEST, assertThrows(Laden.Fehler.class, () -> Laden.entpacke(new byte[] {1, 2, 3})).grund);
    }

    @Test
    void zeilenDieKeineKoordinatenSind() {
        for (String zeile : new String[] {"../0/0 1 \"e\"", "0/0/0x 1 \"e\"", "0/0 1 \"e\"", "0/0/0 1 \"e e\"", "0/0/0 -1 \"e\"",
            "+1/0/0 1 \"e\"", "0/0/0 1"}) {
            assertEquals(Laden.Grund.MANIFEST, assertThrows(Laden.Fehler.class, () -> Laden.lies(zeile + "\n", 0, 2, 2, 100)).grund, zeile);
        }
        assertEquals(Laden.Grund.MANIFEST, assertThrows(Laden.Fehler.class, () -> Laden.lies("3/0/0 1 \"e\"\n", 0, 2, 2, 100)).grund);
        assertEquals(Laden.Grund.KACHEL, assertThrows(Laden.Fehler.class,
                () -> Laden.lies("0/0/0 " + (Laden.KACHEL_MAX + 1) + " \"e\"\n", 0, 2, 2, 100)).grund);
    }

    @Test
    void doppelteUndZuVieleZeilen() throws Exception {
        // 7/0/0 und 007/0/0 wären dieselbe Datei, -0 und 0 auch.
        assertEquals(Laden.Grund.MANIFEST, assertThrows(Laden.Fehler.class,
                () -> Laden.lies("7/0/0 1 \"a\"\n007/0/0 1 \"b\"\n", 0, 9, 9, 100)).grund);
        assertEquals(Laden.Grund.MANIFEST, assertThrows(Laden.Fehler.class,
                () -> Laden.lies("1/-0/0 1 \"a\"\n1/0/0 1 \"b\"\n", 0, 9, 9, 100)).grund);
        String drei = "0/0/0 1 \"a\"\n1/0/0 1 \"b\"\n1/1/0 1 \"c\"\n";
        assertEquals(3, Laden.lies(drei, 0, 9, 9, 3).size());
        assertEquals(Laden.Grund.MANIFEST, assertThrows(Laden.Fehler.class, () -> Laden.lies(drei, 0, 9, 9, 2)).grund);
        // Feinere Stufen zählen nicht.
        assertEquals(1, Laden.lies(drei, 0, 9, 0, 1).size());

        // Die Kacheln des Angebots plus 10 %, und höchstens eine Zeile je 4 KiB des Satzes plus 10 %.
        URI url = URI.create("http://k.example/b");
        assertEquals(110 + 16, new Laden.Auftrag(url, "t", "", 0, 4, false, 1L << 30, 100, null).zeilen());
        assertEquals(11 + 16, new Laden.Auftrag(url, "t", "", 0, 4, false, 10 * Laden.ZEILE_MIN, 1000, null).zeilen());
    }

    @Test
    void mapJsonHoechstens64KiB() {
        karte = "{\"minZoom\":0,\"maxZoom\":2,\"x\":\"" + "a".repeat((int) Laden.KARTE_MAX) + "\"}";
        assertEquals(Laden.Grund.MANIFEST, assertThrows(Laden.Fehler.class, () -> new Laden().lade(auftrag(4, summe()), ziel)).grund);
    }

    @Test
    void kachelGroesserAlsErlaubt() {
        setze("0/0/0", new byte[(int) Laden.KACHEL_MAX + 1], "\"gross\"");
        // Das Manifest nennt noch die alte, kleine Grösse.
        assertEquals(Laden.Grund.KACHEL, assertThrows(Laden.Fehler.class, () -> new Laden().lade(auftrag(4, 1 << 30), ziel)).grund);
    }

    @Test
    void abgerisseneKachel() {
        // Dasselbe ETag wie im Manifest, aber kürzer: Die Antwort riss ab.
        kacheln.put("2/1/1", "bild".getBytes(StandardCharsets.UTF_8));
        assertEquals(Laden.Grund.NETZ, assertThrows(Laden.Fehler.class, () -> new Laden().lade(auftrag(4, 1 << 30), ziel)).grund);
        assertFalse(Files.exists(datei("2/1/1")));
    }

    @Test
    void verbindungsfehlerIstNetz() throws IOException {
        int frei;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            frei = s.getLocalPort();
        }
        // Ein Port, an dem niemand lauscht.
        URI url = URI.create("http://127.0.0.1:" + frei + "/baum");
        Laden.Auftrag a = new Laden.Auftrag(url, TOKEN, Laden.sha256(manifest), summe(), 4, false, 1L << 30, 5,
                InetAddress.getLoopbackAddress());
        Laden.Fehler f = assertThrows(Laden.Fehler.class, () -> new Laden().lade(a, ziel));
        assertEquals(Laden.Grund.NETZ, f.grund);
        assertTrue(f.getCause() instanceof IOException);
    }

    @Test
    void ersterFehlerBrichtDieAnderenAb() throws IOException {
        // Eine Kachel fehlt beim Server, eine andere hält er 30 s zurück: Der Download endet gleich, nicht mit der gehaltenen.
        setze("2/5/5", "weg".getBytes(StandardCharsets.UTF_8), "\"weg\"");
        manifest = baueManifest();
        kacheln.remove("2/5/5");
        haelt = "2/-2/3";
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertEquals(Laden.Grund.NETZ,
                assertThrows(Laden.Fehler.class, () -> new Laden().lade(auftrag(4, 1 << 30), ziel)).grund));
    }

    @Test
    void summeHoechstensBytesPlusZehnProzent() throws Exception {
        long eine = kacheln.get("0/0/0").length;
        Laden.Ergebnis ergebnis = new Laden().lade(auftrag(4, 2 * eine), ziel);
        assertTrue(ergebnis.gekappt());
        assertTrue(ergebnis.bytesGeladen() <= 2 * eine + 2 * eine / 10);
        assertTrue(ergebnis.geladen() < 5);
    }

    @Test
    void abgleichHoechstensSeinDeckel() throws Exception {
        kacheln.clear();
        etags.clear();
        setze("0/0/0", new byte[11], "\"e\"");
        manifest = baueManifest();
        // Voll darf 10 % über bytes, der Abgleich nicht: Dort ist bytes schon der Deckel des Tokens.
        Laden.Ergebnis abgleich = new Laden().lade(auftrag(4, 10, true, InetAddress.getLoopbackAddress()), ziel);
        assertTrue(abgleich.gekappt());
        assertEquals(0, abgleich.geladen());
        assertEquals(1, new Laden().lade(auftrag(4, 10), ziel).geladen());
    }

    @Test
    void keineWeiterleitung() throws IOException {
        // Eine Zeile, deren Kachel der Server weiterleitet.
        manifest = gzip("2/0/0 1 \"w\"\n".getBytes(StandardCharsets.UTF_8));
        server.removeContext("/baum/");
        server.createContext("/baum/", t -> {
            String pfad = t.getRequestURI().getPath();
            if (pfad.endsWith(".webp")) {
                t.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/fremd/x");
                sende(t, 302, new byte[0], null);
            } else {
                antworte(t);
            }
        });
        assertEquals(Laden.Grund.NETZ, assertThrows(Laden.Fehler.class, () -> new Laden().lade(auftrag(4, summe()), ziel)).grund);
        assertEquals(0, fremd.get());
    }

    @Test
    void koerperDerNichtKommt() {
        server.removeContext("/baum/");
        server.createContext("/baum/", t -> {
            // Header und zehn von hundert Bytes, dann nichts mehr, wie bei einem schlafenden Laptop.
            t.sendResponseHeaders(200, 100);
            t.getResponseBody().write(new byte[10]);
            t.getResponseBody().flush();
            warte();
        });
        Laden laden = new Laden(Duration.ofSeconds(1));
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertEquals(Laden.Grund.NETZ,
                assertThrows(Laden.Fehler.class, () -> laden.lade(auftrag(4, summe()), ziel)).grund));
    }

    @Test
    void indexSchonWaehrendDesDownloads() throws Exception {
        // Liegt von einem beendeten Spiel noch eine Zwischendatei da, fällt sie weg.
        Files.createDirectories(ziel.resolve("tmp"));
        Files.writeString(ziel.resolve("tmp").resolve("halb.tmp"), "halb");
        haelt = "2/-2/3";
        CompletableFuture<Laden.Ergebnis> lauf = CompletableFuture.supplyAsync(() -> {
            try {
                return new Laden().lade(auftrag(4, summe()), ziel);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        // Vier Kacheln sind da, die fünfte hält der Server: Der Index nennt die vier schon jetzt,
        // nicht erst am Ende; endet das Spiel hier, lädt der nächste Versuch nur die fünfte.
        // Gewartet wird auf die ETags, nicht nur auf die Pfade: Eine Kachel steht vor dem Schreiben ohne ETag da.
        String[] vier = {"0/0/0", "1/0/0", "1/-1/0", "2/1/1"};
        Map<String, String> ist = Map.of();
        for (int i = 0; i < 500 && !java.util.Arrays.stream(vier).allMatch(p -> etags.get(p).equals(ist(p))); i++) {
            Thread.sleep(10);
        }
        ist = Laden.liesIndex(ziel);
        assertFalse(Files.exists(ziel.resolve("tmp").resolve("halb.tmp")));
        for (String pfad : vier) {
            assertEquals(etags.get(pfad), ist.get(pfad), pfad);
        }
        assertFalse(ist.containsKey("2/-2/3"));
        frei.countDown();
        assertEquals(5, lauf.get(10, TimeUnit.SECONDS).geladen());
        assertEquals(etags, new TreeMap<>(Laden.liesIndex(ziel)));
    }

    /** Das ETag einer Kachel im Index, wie er jetzt auf der Platte steht, oder null. */
    private String ist(String pfad) {
        try {
            return Laden.liesIndex(ziel).get(pfad);
        } catch (IOException e) {
            return null;
        }
    }

    @Test
    void zeileOhneEtagHeisstNeuLaden() throws Exception {
        new Laden().lade(auftrag(4, summe()), ziel);
        // Das Spiel endete, nachdem es 2/1/1 angefangen hatte.
        Files.writeString(ziel.resolve("etags.txt"), "2/1/1\n", StandardOpenOption.APPEND);
        assertEquals(1, new Laden().lade(auftrag(4, summe()), ziel).geladen());
    }

    @Test
    void indexNurMitKoordinaten() throws IOException {
        Files.writeString(ziel.resolve("etags.txt"), "../../boese \"e\"\n0/0/0 \"e\"\n1/0/0 \"a\"\n1/0/0\n");
        assertEquals(Map.of("0/0/0", "\"e\"", "1/0/0", ""), Laden.liesIndex(ziel));
    }

    @Test
    void behaeltNurEinenMassstab() throws IOException {
        for (String m : new String[] {"1", "2", "4"}) {
            Files.createDirectories(ziel.resolve(m).resolve("0").resolve("0"));
            Files.writeString(ziel.resolve(m).resolve("0").resolve("0").resolve("0.webp"), m);
        }
        Laden.behalteNur(ziel, 2);
        assertFalse(Files.exists(ziel.resolve("1")));
        assertFalse(Files.exists(ziel.resolve("4")));
        assertTrue(Files.exists(ziel.resolve("2").resolve("0").resolve("0").resolve("0.webp")));
    }
}
