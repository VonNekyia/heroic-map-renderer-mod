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
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
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
    /** Antwortet der Server auf Kacheln mit 429, wie über dem Deckel des Tokens? */
    private volatile boolean erschoepft;
    private final AtomicInteger abrufe = new AtomicInteger();
    /** Hält Kacheln zurück, bis so viele zugleich laufen; null heisst nicht. */
    private volatile CountDownLatch zugleich;
    private final AtomicInteger laufen = new AtomicInteger(), hoechstens = new AtomicInteger();
    private final AtomicInteger fremd = new AtomicInteger();
    /** Was der Server statt des Stroms des Baums schickt, oder null. */
    private volatile byte[] zip;
    /** Die Anfragen an den Strom, je ihr Query oder „“. */
    private final List<String> fragen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void starte() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        threads = Executors.newCachedThreadPool();
        server.setExecutor(threads);
        server.createContext("/baum/", this::antworte);
        server.createContext("/baum.zip", t -> {
            if (!("Bearer " + TOKEN).equals(t.getRequestHeaders().getFirst("Authorization"))) {
                sende(t, 401, new byte[0], null);
                return;
            }
            String query = t.getRequestURI().getQuery();
            fragen.add(query == null ? "" : query);
            byte[] inhalt = zip != null ? zip : strom(query == null ? null : query.substring("nach=".length()), true);
            t.getResponseHeaders().add("X-Heroic-Bytes", String.valueOf(inhalt.length));
            // Ohne Länge, also chunked wie beim Renderer.
            t.sendResponseHeaders(200, 0);
            try (OutputStream aus = t.getResponseBody()) {
                aus.write(inhalt);
            }
        });
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

    /** Ein Eintrag des Stroms wie vom Renderer: nur der lokale Kopf, ohne Data Descriptor. */
    private static void eintrag(ByteArrayOutputStream aus, String name, byte[] inhalt, int methode) {
        CRC32 crc = new CRC32();
        crc.update(inhalt);
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        ByteBuffer kopf = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x04034b50).putShort((short) 10).putShort((short) 0).putShort((short) methode)
                // 1980-01-01 00:00
                .putShort((short) 0).putShort((short) 0x21)
                .putInt((int) crc.getValue()).putInt(inhalt.length).putInt(inhalt.length)
                .putShort((short) n.length).putShort((short) 0);
        aus.writeBytes(kopf.array());
        aus.writeBytes(n);
        aus.writeBytes(inhalt);
    }

    /** Der Strom des Baums: map.json, manifest, die Kacheln von grob nach fein, hinter {@code nach}, und ende. */
    private byte[] strom(String nach, boolean ende) {
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        eintrag(aus, "map.json", karte.getBytes(StandardCharsets.UTF_8), 0);
        eintrag(aus, "manifest", manifest, 0);
        boolean senden = nach == null;
        for (String pfad : grobNachFein()) {
            if (senden) {
                eintrag(aus, pfad + ".webp", kacheln.get(pfad), 0);
            }
            senden |= pfad.equals(nach);
        }
        if (ende) {
            eintrag(aus, "ende", new byte[0], 0);
        }
        return aus.toByteArray();
    }

    private List<String> grobNachFein() {
        return kacheln.keySet().stream().sorted(Comparator.comparingInt(p -> Integer.parseInt(p.split("/")[0]))).toList();
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
                CountDownLatch z = zugleich;
                if (z != null) {
                    hoechstens.accumulateAndGet(laufen.incrementAndGet(), Math::max);
                    z.countDown();
                    try {
                        z.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    laufen.decrementAndGet();
                }
                if (erschoepft) {
                    sende(t, 429, new byte[0], null);
                } else if (kacheln.containsKey(zxy)) {
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
    void teilDesReliefsOhneMapJson() throws Exception {
        // Schicht und Rest bringen keine map.json; die Stufen kommen aus der des Baums, und keine landet im Ordner.
        byte[] desBaums = karte.getBytes(StandardCharsets.UTF_8);
        karte = "kaputt";
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/baum");
        Laden.Auftrag a = new Laden.Auftrag(url, TOKEN, Laden.sha256(manifest), summe(), 4, false, 1L << 30, kacheln.size(),
                InetAddress.getLoopbackAddress(), desBaums);
        assertEquals(5, new Laden().lade(a, ziel).geladen());
        assertFalse(Files.exists(ziel.resolve("map.json")));
        // Als Strom ebenso: das Manifest zuerst.
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        eintrag(aus, "manifest", manifest, 0);
        for (String pfad : grobNachFein()) {
            eintrag(aus, pfad + ".webp", kacheln.get(pfad), 0);
        }
        eintrag(aus, "ende", new byte[0], 0);
        zip = aus.toByteArray();
        Path strom = ziel.resolve("strom");
        assertEquals(5, new Laden().strom(a, strom, new Laden.Fortschritt(summe()), z -> { }).geladen());
        assertFalse(Files.exists(strom.resolve("map.json")));
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
    void fortschrittEinesAbgleichs() throws Exception {
        new Laden().lade(auftrag(4, summe()), ziel);
        byte[] neu = "neu und laenger".getBytes(StandardCharsets.UTF_8), anders = "anders".getBytes(StandardCharsets.UTF_8);
        setze("2/1/1", neu, "\"neu\"");
        setze("1/0/0", anders, "\"anders\"");
        manifest = baueManifest();
        // Bis das Manifest verglichen ist, steht gesamt nicht fest; der Kasten zeigt dann „…“.
        Laden.Fortschritt f = new Laden.Fortschritt(Laden.Fortschritt.UNBEKANNT, true);
        assertFalse(f.bekannt());
        assertTrue(f.zeigen());
        assertEquals(0.0, f.anteil());
        // Dann sind es die zwei Kacheln, die laut Manifest fehlen, nicht der Deckel; empfangen jedes ihrer Bytes.
        new Laden().lade(auftrag(4, 1 << 20, true, InetAddress.getLoopbackAddress()), ziel, f);
        assertTrue(f.bekannt());
        assertEquals(neu.length + anders.length, f.gesamt);
        assertEquals(neu.length + anders.length, f.empfangen.get());
        assertEquals(1.0, f.anteil());
        // Fehlt nach dem Vergleich nichts, zeigt die Karte keinen Kasten.
        Laden.Fortschritt leer = new Laden.Fortschritt(Laden.Fortschritt.UNBEKANNT, true);
        new Laden().lade(auftrag(4, 1 << 20, true, InetAddress.getLoopbackAddress()), ziel, leer);
        assertEquals(0, leer.gesamt);
        assertFalse(leer.zeigen());
        // Fehlt mehr als der Deckel, ist gesamt der Deckel: eine Kachel von 15 Byte bei 10 Byte Deckel, die der Abgleich liegen lässt.
        setze("1/0/0", "nochmals anders".getBytes(StandardCharsets.UTF_8), "\"anders2\"");
        manifest = baueManifest();
        Laden.Fortschritt gedeckelt = new Laden.Fortschritt(Laden.Fortschritt.UNBEKANNT, true);
        assertTrue(new Laden().lade(auftrag(4, 10, true, InetAddress.getLoopbackAddress()), ziel, gedeckelt).gekappt());
        assertEquals(10, gedeckelt.gesamt);
        assertEquals(0, gedeckelt.empfangen.get());
    }

    @Test
    void prozentAbgerundetSonstPunkte() {
        // Wie Karte und Minimap sie zeigen: abgerundet, nie über 100, ohne gesamt „…“.
        Laden.Fortschritt f = new Laden.Fortschritt(1000);
        assertEquals("0 %", f.prozent());
        f.empfangen.set(499);
        assertEquals("49 %", f.prozent());
        f.empfangen.set(1000);
        assertEquals("100 %", f.prozent());
        f.empfangen.set(1500);
        assertEquals("100 %", f.prozent());
        assertEquals("…", new Laden.Fortschritt(Laden.Fortschritt.UNBEKANNT, true).prozent());
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
    void budgetErschoepft() {
        erschoepft = true;
        assertEquals(Laden.Grund.BUDGET, assertThrows(Laden.Fehler.class, () -> new Laden().lade(auftrag(4, summe()), ziel)).grund);
    }

    @Test
    void standGibtEsAbDemErstenDownload() throws Exception {
        assertFalse(Laden.hatStand(ziel));
        new Laden().lade(auftrag(4, summe()), ziel);
        assertTrue(Laden.hatStand(ziel));
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
    void achtVerbindungenZugleich() throws Exception {
        // 32 Kacheln; der Server hält jede zurück, bis 8 zugleich laufen. Mit weniger Verbindungen kämen nie 8. Siehe docs/download.md, „Ablauf“.
        for (int i = 0; i < 32; i++) {
            setze("2/" + i + "/9", ("bild " + i).getBytes(StandardCharsets.UTF_8), "\"z" + i + "\"");
        }
        manifest = baueManifest();
        zugleich = new CountDownLatch(8);
        Laden.Ergebnis e = new Laden().lade(auftrag(4, 1 << 30), ziel);
        assertEquals(kacheln.size(), e.geladen());
        assertEquals(8, hoechstens.get());
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
    void abgleichOhneStandWirdVoll() throws Exception {
        // Ein leeres Ziel hat keinen Stand: Statt des Abgleichs fragt der Mod voll an, eine anfrage voll bleibt voll.
        assertTrue(Downloads.vollStattAbgleich(true, ziel));
        assertFalse(Downloads.vollStattAbgleich(false, ziel));
        // Ein Abgleich dorthin brächte nur seinen Deckel, ein Zehntel; voll kommt alles.
        assertEquals(kacheln.size(), new Laden().lade(auftrag(4, 1L << 30), ziel).geladen());
        // Mit Stand bleibt der Abgleich ein Abgleich.
        assertFalse(Downloads.vollStattAbgleich(true, ziel));
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

    @Test
    void loeschtAlteOverlays() throws IOException {
        Path baum = ziel.resolve("server").resolve("baum");
        Files.createDirectories(baum.resolve("overlay"));
        Files.writeString(baum.resolve("overlay").resolve("1.-2.png"), "alt");
        Files.createDirectories(baum.resolve("4"));
        Files.writeString(baum.resolve("4").resolve("map.json"), "{}");
        Files.writeString(ziel.resolve("zustimmung.txt"), "");
        Laden.loescheOverlays(ziel);
        assertFalse(Files.exists(baum.resolve("overlay")));
        assertTrue(Files.exists(baum.resolve("4").resolve("map.json")));
        // Ein zweiter Start findet nichts mehr und scheitert nicht.
        Laden.loescheOverlays(ziel);
    }

    private Laden.Ergebnis strome(Laden.Auftrag a) throws Exception {
        return new Laden().strom(a, ziel, new Laden.Fortschritt(1), z -> {
        });
    }

    private void alleKachelnDa() throws IOException {
        for (Map.Entry<String, byte[]> k : kacheln.entrySet()) {
            assertArrayEquals(k.getValue(), Files.readAllBytes(datei(k.getKey())), k.getKey());
        }
        assertEquals(etags, new TreeMap<>(Laden.liesIndex(ziel)));
    }

    @Test
    void stromLaedtAllesInEinemAbruf() throws Exception {
        List<Integer> stufen = new ArrayList<>();
        Laden.Fortschritt f = new Laden.Fortschritt(1);
        Laden.Ergebnis e = new Laden().strom(auftrag(4, summe()), ziel, f, stufen::add);
        assertEquals(5, e.geladen());
        alleKachelnDa();
        assertTrue(Files.exists(ziel.resolve("map.json")));
        assertEquals(0, abrufe.get());
        assertFalse(Laden.imStrom(ziel));
        // Jede Stufe meldet sich, sobald sie ganz daliegt, die gröbste zuerst.
        assertEquals(List.of(0, 1, 2), stufen);
        // Der Fortschritt nur aus Bytes: am Ende so viele, wie X-Heroic-Bytes ankündigte.
        assertEquals(strom(null, true).length, f.gesamt);
        assertEquals(f.gesamt, f.empfangen.get());
        assertEquals(1.0, f.anteil());

        Laden.Ergebnis dann = strome(auftrag(4, summe()));
        assertEquals(0, dann.geladen());
        assertEquals(5, dann.gleich());
    }

    @Test
    void ohneStromEinzeln() throws Exception {
        // Ein Renderer vor dem Strom antwortet 404; dann lädt Downloads einzeln.
        server.removeContext("/baum.zip");
        assertEquals(null, strome(auftrag(4, summe())));
        assertFalse(Files.exists(ziel.resolve("map.json")));
    }

    /** Ein Strom mit {@code map.json}, {@code manifest}, dann was {@code mitte} schreibt, und {@code ende}. */
    private byte[] stromMit(java.util.function.Consumer<ByteArrayOutputStream> mitte) {
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        eintrag(aus, "map.json", karte.getBytes(StandardCharsets.UTF_8), 0);
        eintrag(aus, "manifest", manifest, 0);
        mitte.accept(aus);
        eintrag(aus, "ende", new byte[0], 0);
        return aus.toByteArray();
    }

    @Test
    void stromNurRelativePfadeAusZahlen() throws Exception {
        byte[] boese = "boese".getBytes(StandardCharsets.UTF_8);
        // Zwei Ebenen unter dem Ordner des Tests: Was mit .. hinaus wollte, landete noch darin.
        Path innen = ziel.resolve("welt").resolve("baum");
        for (String name : new String[] {"../x.webp", "0/0/../../../x.webp", "../../x/0/0.webp", "/0/0/0.webp", "0\\0\\0.webp",
            "C:/0/0.webp", "0/0/0.png", "0/0/0.webp/", "0/0/", "0/0/0.webp\u0000"}) {
            zip = stromMit(aus -> eintrag(aus, name, boese, 0));
            assertEquals(Laden.Grund.STROM, assertThrows(Laden.Fehler.class,
                    () -> new Laden().strom(auftrag(4, summe()), innen, new Laden.Fortschritt(1), z -> {
                    })).grund, name);
        }
        try (var alle = Files.walk(ziel)) {
            assertEquals(List.of(), alle.filter(p -> p.getFileName().toString().startsWith("x") || p.toString().endsWith(".webp")).toList());
        }
    }

    @Test
    void stromRegeln() throws Exception {
        byte[] bild = kacheln.get("0/0/0");
        ByteArrayOutputStream nachEnde = new ByteArrayOutputStream(), manifestZuerst = new ByteArrayOutputStream();
        nachEnde.writeBytes(strom(null, true));
        eintrag(nachEnde, "0/0/0.webp", bild, 0);
        eintrag(manifestZuerst, "manifest", manifest, 0);
        eintrag(manifestZuerst, "map.json", karte.getBytes(StandardCharsets.UTF_8), 0);
        Map<String, byte[]> faelle = Map.of(
                "gepackt", stromMit(aus -> eintrag(aus, "0/0/0.webp", bild, 8)),
                "doppelt", stromMit(aus -> {
                    eintrag(aus, "0/0/0.webp", bild, 0);
                    eintrag(aus, "0/0/0.webp", bild, 0);
                }),
                "nicht im Manifest", stromMit(aus -> eintrag(aus, "0/9/9.webp", bild, 0)),
                "nach ende", nachEnde.toByteArray(),
                "manifest zuerst", manifestZuerst.toByteArray());
        for (Map.Entry<String, byte[]> fall : faelle.entrySet()) {
            zip = fall.getValue();
            assertEquals(Laden.Grund.STROM, assertThrows(Laden.Fehler.class, () -> strome(auftrag(4, summe()))).grund, fall.getKey());
        }
    }

    @Test
    void stromHoechstensSeineGrenze() throws IOException {
        // Der Satz plus 10 %, dazu je Datei 1 KiB für Kopf und Name: Ein Strom für zwei Kacheln bringt nicht fünf.
        int eine = 8 << 10;
        for (String pfad : List.copyOf(kacheln.keySet())) {
            setze(pfad, new byte[eine], etags.get(pfad));
        }
        manifest = baueManifest();
        assertEquals(Laden.Grund.STROM, assertThrows(Laden.Fehler.class, () -> strome(auftrag(4, 2L * eine))).grund);
    }

    @Test
    void stromOhneEndeIstAbgebrochen() throws Exception {
        // Der Körper endet sauber, aber ohne ende, und der Server bringt beim Fortsetzen nichts Neues, wie am Deckel des Tokens.
        zip = strom(null, false);
        assertEquals(Laden.Grund.BUDGET, assertThrows(Laden.Fehler.class, () -> strome(auftrag(4, summe()))).grund);
        // Der erste Versuch brachte alle Kacheln, die zwei danach nichts Neues: dann Schluss.
        assertEquals(3, fragen.size());
        // Die Kacheln liegen ganz da, der Satz gilt trotzdem als unvollständig.
        assertTrue(Laden.imStrom(ziel));
        assertEquals("2/1/1", Files.readString(ziel.resolve(Laden.STROM)));
    }

    @Test
    void stromOhneFortschrittKeineSchleife() {
        // Nur map.json und manifest, ohne Kachel und ohne ende, bei jedem Versuch.
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        eintrag(aus, "map.json", karte.getBytes(StandardCharsets.UTF_8), 0);
        eintrag(aus, "manifest", manifest, 0);
        zip = aus.toByteArray();
        Laden.Fehler f = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertThrows(Laden.Fehler.class, () -> strome(auftrag(4, summe()))));
        assertEquals(Laden.Grund.BUDGET, f.grund);
        assertEquals(2, fragen.size());
    }

    @Test
    void stromSetztVonSelbstFort() throws Exception {
        // Der Server bricht nach jeder Kachel ab, sauber und ohne ende; erst wenn keine mehr fehlt, kommt ende.
        server.removeContext("/baum.zip");
        server.createContext("/baum.zip", t -> {
            String query = t.getRequestURI().getQuery();
            fragen.add(query == null ? "" : query);
            String nach = query == null ? null : query.substring("nach=".length());
            List<String> reihe = grobNachFein();
            ByteArrayOutputStream aus = new ByteArrayOutputStream();
            eintrag(aus, "map.json", karte.getBytes(StandardCharsets.UTF_8), 0);
            eintrag(aus, "manifest", manifest, 0);
            int naechste = nach == null ? 0 : reihe.indexOf(nach) + 1;
            if (naechste < reihe.size()) {
                eintrag(aus, reihe.get(naechste) + ".webp", kacheln.get(reihe.get(naechste)), 0);
            } else {
                eintrag(aus, "ende", new byte[0], 0);
            }
            t.getResponseHeaders().add("X-Heroic-Bytes", String.valueOf(aus.size()));
            t.sendResponseHeaders(200, 0);
            try (OutputStream o = t.getResponseBody()) {
                o.write(aus.toByteArray());
            }
        });
        Laden.Fortschritt f = new Laden.Fortschritt(1);
        Laden.Ergebnis e = new Laden().strom(auftrag(4, summe()), ziel, f, z -> {
        });
        assertEquals(5, e.geladen());
        alleKachelnDa();
        assertFalse(Laden.imStrom(ziel));
        assertEquals(6, fragen.size());
        assertEquals("nach=2/1/1", fragen.getLast());
        // Über alle Versuche zählt der Fortschritt bis 100 %.
        assertEquals(f.gesamt, f.empfangen.get());
    }

    /**
     * Ein Server, der den Kopf und {@code teil} chunked schickt und dann die Verbindung schliesst,
     * ohne das letzte Stück; so reisst ein Strom im Netz ab.
     */
    private URI reisstAb(byte[] teil, long gesamt) throws IOException {
        ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        threads.execute(() -> {
            try (s; Socket c = s.accept()) {
                // Die Anfrage bis zur leeren Zeile.
                int gelesen = 0;
                for (int b; gelesen != 0x0d0a0d0a && (b = c.getInputStream().read()) >= 0; ) {
                    gelesen = gelesen << 8 | b;
                }
                OutputStream aus = c.getOutputStream();
                aus.write(("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nX-Heroic-Bytes: " + gesamt + "\r\n\r\n"
                        + Integer.toHexString(teil.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                aus.write(teil);
                aus.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                aus.flush();
            } catch (IOException e) {
                // Der Test sieht es am Ergebnis.
            }
        });
        return URI.create("http://127.0.0.1:" + s.getLocalPort() + "/baum");
    }

    @Test
    void abgerissenerStromHinterlaesstNichtsHalbes() throws Exception {
        byte[] ganz = strom(null, true);
        ByteArrayOutputStream bis = new ByteArrayOutputStream();
        eintrag(bis, "map.json", karte.getBytes(StandardCharsets.UTF_8), 0);
        eintrag(bis, "manifest", manifest, 0);
        eintrag(bis, "0/0/0.webp", kacheln.get("0/0/0"), 0);
        eintrag(bis, "1/-1/0.webp", kacheln.get("1/-1/0"), 0);
        // Mitten im Inhalt der dritten Kachel, 1/0/0.
        int schnitt = bis.size() + 30 + "1/0/0.webp".length() + 3;
        Laden.Auftrag a = new Laden.Auftrag(reisstAb(Arrays.copyOf(ganz, schnitt), ganz.length), TOKEN, Laden.sha256(manifest),
                summe(), 4, false, 1L << 30, kacheln.size(), InetAddress.getLoopbackAddress());
        assertEquals(Laden.Grund.NETZ, assertThrows(Laden.Fehler.class, () -> strome(a)).grund);
        // Was vor dem Abbruch ankam, aber noch nicht gelesen war, verwirft der Client mit dem Fehler: Ganz da liegen die
        // ersten k der zwei Kacheln davor, keine halbe, die abgerissene gar nicht, und keine Zwischendatei bleibt.
        List<String> reihe = grobNachFein();
        int ganze = 0;
        while (ganze < 2 && Files.exists(datei(reihe.get(ganze)))) {
            assertArrayEquals(kacheln.get(reihe.get(ganze)), Files.readAllBytes(datei(reihe.get(ganze))));
            ganze++;
        }
        for (int i = ganze; i < kacheln.size(); i++) {
            assertFalse(Files.exists(datei(reihe.get(i))), reihe.get(i));
        }
        try (var tmp = Files.list(ziel.resolve("tmp"))) {
            assertEquals(0, tmp.count());
        }
        String nach = ganze == 0 ? "" : reihe.get(ganze - 1);
        assertEquals(nach, Files.readString(ziel.resolve(Laden.STROM)));

        // Der nächste Strom setzt hinter der letzten Kachel fort und macht den Satz fertig.
        Laden.Ergebnis rest = strome(auftrag(4, summe()));
        assertEquals(List.of(ganze == 0 ? "" : "nach=" + nach), fragen);
        assertEquals(5 - ganze, rest.geladen());
        alleKachelnDa();
        assertFalse(Laden.imStrom(ziel));
    }

    @Test
    void stromDerStehenBleibt() {
        server.removeContext("/baum.zip");
        server.createContext("/baum.zip", t -> {
            // Header und 100 Bytes, dann nichts mehr.
            t.sendResponseHeaders(200, 0);
            t.getResponseBody().write(strom(null, true), 0, 100);
            t.getResponseBody().flush();
            warte();
        });
        Laden laden = new Laden(Duration.ofSeconds(5), Duration.ofSeconds(1));
        Laden.Fehler f = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(Laden.Fehler.class,
                () -> laden.strom(auftrag(4, summe()), ziel, new Laden.Fortschritt(1), z -> {
                })));
        assertEquals(Laden.Grund.NETZ, f.grund);
        assertTrue(f.getMessage().contains("ohne Byte"), f.getMessage());
    }

    @Test
    void stromStatusSteht() {
        for (int[] fall : new int[][] {{502, 0}, {429, 1}, {401, 2}}) {
            server.removeContext("/baum.zip");
            server.createContext("/baum.zip", t -> sende(t, fall[0], new byte[0], null));
            Laden.Fehler f = assertThrows(Laden.Fehler.class, () -> strome(auftrag(4, summe())));
            assertEquals(new Laden.Grund[] {Laden.Grund.NETZ, Laden.Grund.BUDGET, Laden.Grund.ABGELEHNT}[fall[1]], f.grund);
            assertEquals(fall[0], f.status);
        }
    }

    @Test
    void stromNach429NeuVersucht() throws Exception {
        // Der Platz eines abgebrochenen Stroms ist beim Server noch belegt: erst 429, dann der Strom.
        AtomicInteger anfragen = new AtomicInteger();
        server.removeContext("/baum.zip");
        server.createContext("/baum.zip", t -> {
            if (anfragen.incrementAndGet() == 1) {
                sende(t, 429, new byte[0], null);
                return;
            }
            byte[] inhalt = strom(null, true);
            t.sendResponseHeaders(200, 0);
            try (OutputStream aus = t.getResponseBody()) {
                aus.write(inhalt);
            }
        });
        assertEquals(5, strome(auftrag(4, summe())).geladen());
        assertEquals(2, anfragen.get());
    }

    @Test
    void tempoUeberFuenfSekunden() {
        Laden.Fortschritt f = new Laden.Fortschritt(1000);
        assertEquals(-1, f.tempo(0));
        f.empfangen.set(100);
        // Vor der ersten Sekunde kein Tempo.
        assertEquals(-1, f.tempo(500));
        f.empfangen.set(200);
        assertEquals(200.0, f.tempo(1000));
        f.empfangen.set(1200);
        // Gemessen ab der Probe vor 5 s: 1000 Byte in 5 s.
        assertEquals(200.0, f.tempo(6000));
        // Über 100 % geht der Anteil nicht.
        assertEquals(1.0, f.anteil());
    }
}
