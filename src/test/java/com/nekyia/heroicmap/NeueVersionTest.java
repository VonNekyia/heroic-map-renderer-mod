package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Der Hinweis auf eine neue Version: Frage, Antwort, Abruf. Siehe docs/vollbildkarte.md, „Neue Version“. */
class NeueVersionTest {

    private static final String LISTE = """
            [{"version_number":"0.2.54","version_type":"beta"},
             {"version_number":"0.3.0-rc.1","version_type":"beta"},
             {"version_number":"0.2.60","version_type":"beta"},
             {"version_number":"kaputt"}, {"version_number":5}, {}, "x", null,
             {"version_number":"99.0.0+aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
             {"version_number":"0.2.58","version_type":"release"}]""";

    private HttpServer server;
    private final List<String> anfragen = new CopyOnWriteArrayList<>();
    private final CountDownLatch frei = new CountDownLatch(1);

    @BeforeEach
    void starte() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", t -> {
            String pfad = t.getRequestURI().getPath();
            anfragen.add(t.getRequestURI().getRawQuery() + " " + t.getRequestHeaders().getFirst("User-Agent"));
            try (OutputStream aus = t.getResponseBody()) {
                byte[] koerper = switch (pfad) {
                    case "/gut" -> LISTE.getBytes(StandardCharsets.UTF_8);
                    case "/gross" -> new byte[NeueVersion.MAX + 1];
                    default -> null;
                };
                if (pfad.equals("/schweigt")) {
                    frei.await(30, TimeUnit.SECONDS);
                }
                t.sendResponseHeaders(koerper == null ? 404 : 200, koerper == null ? -1 : koerper.length);
                if (koerper != null) {
                    aus.write(koerper);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // Der Client hat abgebrochen.
            }
        });
        server.start();
    }

    @AfterEach
    void stoppe() {
        frei.countDown();
        server.stop(0);
    }

    private URI pfad(String p) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + p);
    }

    @Test
    void frageNurFabricUndDieseSpielversion() {
        assertEquals("https://api.modrinth.com/v2/project/heroic-map/version?loaders=%5B%22fabric%22%5D"
                + "&game_versions=%5B%2226.3%22%5D&include_changelog=false", NeueVersion.adresse(NeueVersion.API, "26.3").toString());
    }

    @Test
    void userAgentMitDemRepoOhneMail() {
        String agent = NeueVersion.agent("0.2.56");
        assertEquals("VonNekyia/heroic-map-renderer-mod/0.2.56 (+https://github.com/VonNekyia/heroic-map-renderer-mod)", agent);
        assertFalse(agent.contains("@"));
    }

    @Test
    void hoechsteVersionOhneVorabversionen() {
        byte[] liste = LISTE.getBytes(StandardCharsets.UTF_8);
        // 0.3.0-rc.1 zählt nicht, Kaputtes und zu Langes auch nicht; der Kanal auf Modrinth zählt nicht.
        assertEquals("0.2.60", NeueVersion.neuere("0.2.56", liste));
        // Gleich oder älter: kein Hinweis.
        assertNull(NeueVersion.neuere("0.2.60", liste));
        assertNull(NeueVersion.neuere("0.2.61", liste));
        assertNull(NeueVersion.neuere("0.2.56", "[{\"version_number\":\"0.3.0-rc.1\"}]".getBytes(StandardCharsets.UTF_8)));
        assertNull(NeueVersion.neuere("0.2.56", "{\"error\":\"not_found\"}".getBytes(StandardCharsets.UTF_8)));
        assertNull(NeueVersion.neuere("0.2.56", "[]".getBytes(StandardCharsets.UTF_8)));
        // Kaputtes JSON wirft; frage fängt es.
        assertThrows(JsonSyntaxException.class, () -> NeueVersion.neuere("0.2.56", "[{".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void holtMitUserAgent() throws Exception {
        URI adresse = NeueVersion.adresse(pfad("/gut"), "26.3");
        assertArrayEquals(LISTE.getBytes(StandardCharsets.UTF_8), NeueVersion.hole(adresse, "0.2.56", Duration.ofSeconds(10)));
        assertEquals(List.of("loaders=%5B%22fabric%22%5D&game_versions=%5B%2226.3%22%5D&include_changelog=false "
                + NeueVersion.agent("0.2.56")), anfragen);
    }

    @Test
    void fehlerGebenKeinenHinweis() {
        Duration frist = Duration.ofMillis(500);
        assertThrows(Laden.Fehler.class, () -> NeueVersion.hole(pfad("/fehlt"), "0.2.56", frist));
        assertThrows(Laden.Fehler.class, () -> NeueVersion.hole(pfad("/gross"), "0.2.56", frist));
        long start = System.nanoTime();
        assertThrows(Laden.Fehler.class, () -> NeueVersion.hole(pfad("/schweigt"), "0.2.56", frist));
        long ms = (System.nanoTime() - start) / 1_000_000;
        if (ms > 5000) {
            throw new AssertionError("Frist nicht gehalten: " + ms + " ms");
        }
    }
}
