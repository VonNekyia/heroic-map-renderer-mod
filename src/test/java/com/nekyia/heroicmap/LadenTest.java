package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
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
    /** Die Kacheln des Servers, z/x/y → Bild, und ihre ETags. */
    private final Map<String, byte[]> kacheln = new TreeMap<>();
    private final Map<String, String> etags = new TreeMap<>();
    private byte[] manifest;
    private String karte = "{\"tileSize\":256,\"scale\":4,\"minZoom\":0,\"maxZoom\":2}";
    private final AtomicInteger abrufe = new AtomicInteger();
    private final AtomicInteger fremd = new AtomicInteger();

    @BeforeEach
    void starte() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
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
        server.stop(0);
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
                if (kacheln.containsKey(zxy)) {
                    abrufe.incrementAndGet();
                    sende(t, 200, kacheln.get(zxy), etags.get(zxy));
                } else {
                    sende(t, 404, new byte[0], null);
                }
            }
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
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/baum");
        return new Laden.Auftrag(url, TOKEN, Laden.sha256(manifest), bytes, massstab);
    }

    private long summe() {
        return kacheln.values().stream().mapToLong(b -> b.length).sum();
    }

    @Test
    void vollerDownloadUndFortsetzen() throws Exception {
        Laden.Ergebnis erst = new Laden().lade(auftrag(4, summe()), ziel);
        assertEquals(5, erst.geladen());
        for (Map.Entry<String, byte[]> k : kacheln.entrySet()) {
            String[] zxy = k.getKey().split("/");
            assertArrayEquals(k.getValue(), Files.readAllBytes(ziel.resolve(zxy[0]).resolve(zxy[1]).resolve(zxy[2] + ".webp")));
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
        assertFalse(Files.exists(ziel.resolve("2").resolve("-2").resolve("3.webp")));
        assertEquals("\"neu\"", Laden.liesIndex(ziel).get("2/1/1"));
    }

    @Test
    void nurBisZurStufeDesMassstabs() throws Exception {
        // maxZoom 2: 2 px reicht bis Stufe 1, 1 px bis Stufe 0.
        assertEquals(3, new Laden().lade(auftrag(2, summe()), ziel).geladen());
        assertFalse(Files.exists(ziel.resolve("2")));
        assertThrows(Laden.Fehler.class, () -> {
            karte = "{\"minZoom\":1,\"maxZoom\":2}";
            new Laden().lade(auftrag(1, summe()), ziel.resolve("eins"));
        });
    }

    @Test
    void falschePruefsumme() {
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/baum");
        Laden.Auftrag falsch = new Laden.Auftrag(url, TOKEN, "00".repeat(32), summe(), 4);
        assertEquals(Laden.Grund.PRUEFSUMME, assertThrows(Laden.Fehler.class, () -> new Laden().lade(falsch, ziel)).grund);
    }

    @Test
    void falschesToken() {
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/baum");
        Laden.Auftrag falsch = new Laden.Auftrag(url, "nein", Laden.sha256(manifest), summe(), 4);
        assertEquals(Laden.Grund.ABGELEHNT, assertThrows(Laden.Fehler.class, () -> new Laden().lade(falsch, ziel)).grund);
    }

    @Test
    void gzipBombe() throws IOException {
        // 65 MiB Nullen packt gzip auf wenige Kilobyte.
        manifest = gzip(new byte[(int) Laden.MANIFEST_MAX + (1 << 20)]);
        assertEquals(Laden.Grund.MANIFEST, assertThrows(Laden.Fehler.class, () -> new Laden().lade(auftrag(4, summe()), ziel)).grund);
    }

    @Test
    void zeilenDieKeineKoordinatenSind() {
        for (String zeile : new String[] {"../0/0 1 \"e\"", "0/0/0x 1 \"e\"", "0/0 1 \"e\"", "0/0/0 1 \"e e\"", "0/0/0 -1 \"e\"",
            "+1/0/0 1 \"e\"", "0/0/0 1"}) {
            assertEquals(Laden.Grund.MANIFEST, assertThrows(Laden.Fehler.class, () -> Laden.lies(zeile + "\n", 0, 2, 2)).grund, zeile);
        }
        assertEquals(Laden.Grund.MANIFEST, assertThrows(Laden.Fehler.class, () -> Laden.lies("3/0/0 1 \"e\"\n", 0, 2, 2)).grund);
        assertEquals(Laden.Grund.KACHEL, assertThrows(Laden.Fehler.class,
                () -> Laden.lies("0/0/0 " + (Laden.KACHEL_MAX + 1) + " \"e\"\n", 0, 2, 2)).grund);
    }

    @Test
    void kachelGroesserAlsErlaubt() throws IOException {
        setze("0/0/0", new byte[(int) Laden.KACHEL_MAX + 1], "\"gross\"");
        // Das Manifest nennt noch die alte, kleine Grösse.
        assertEquals(Laden.Grund.KACHEL, assertThrows(Laden.Fehler.class, () -> new Laden().lade(auftrag(4, 1 << 30), ziel)).grund);
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
    void indexNurMitKoordinaten() throws IOException {
        Files.writeString(ziel.resolve("etags.txt"), "../../boese \"e\"\n0/0/0 \"e\"\n");
        assertEquals(Map.of("0/0/0", "\"e\""), Laden.liesIndex(ziel));
    }
}
