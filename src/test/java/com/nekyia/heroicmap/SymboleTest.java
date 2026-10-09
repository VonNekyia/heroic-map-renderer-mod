package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Die Symbole der Nadeln: Adresse, Pfad, Holen mit Prüfung. Siehe docs/ebenen.md, „Symbole“. */
class SymboleTest {

    private HttpServer server;
    private URI basis;

    @BeforeEach
    void starte() throws IOException {
        Map<String, byte[]> dateien = Map.of(
                "/tiles/layers/beispiel/images/burg_16.png", png(16),
                "/tiles/layers/beispiel/images/burg_9.png", png(9),
                "/tiles/layers/beispiel/images/riesig.png", new byte[Symbole.MAX + 1]);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", t -> {
            String pfad = t.getRequestURI().getPath();
            if (pfad.endsWith("weiter.png")) {
                t.getResponseHeaders().add("Location", "/tiles/layers/beispiel/images/burg_16.png");
                t.sendResponseHeaders(302, -1);
            } else if (dateien.containsKey(pfad)) {
                t.sendResponseHeaders(200, dateien.get(pfad).length);
                try (OutputStream aus = t.getResponseBody()) {
                    aus.write(dateien.get(pfad));
                }
            } else {
                t.sendResponseHeaders(404, -1);
            }
            t.close();
        });
        server.start();
        basis = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/tiles");
    }

    @AfterEach
    void stoppe() {
        server.stop(0);
    }

    private static byte[] png(int seite) throws IOException {
        BufferedImage bild = new BufferedImage(seite, seite, BufferedImage.TYPE_INT_ARGB);
        bild.setRGB(0, 0, 0xFF123456);
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        ImageIO.write(bild, "png", aus);
        return aus.toByteArray();
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
    void holenMitPruefung() {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        InetAddress hier = InetAddress.getLoopbackAddress();
        Kacheln.Bild bild = Symbole.hole(client, Symbole.uri(basis, "beispiel:staedte", "images/burg_16.png"), hier, 16);
        assertNotNull(bild);
        assertEquals(16, bild.breite());
        assertEquals(0xFF123456, bild.argb()[0]);
        // Falsche Grösse, fehlt, zu gross, Weiterleitung: alles leer.
        assertNull(Symbole.hole(client, Symbole.uri(basis, "beispiel:staedte", "images/burg_9.png"), hier, 16));
        assertNull(Symbole.hole(client, Symbole.uri(basis, "beispiel:staedte", "images/fehlt.png"), hier, 16));
        assertNull(Symbole.hole(client, Symbole.uri(basis, "beispiel:staedte", "images/riesig.png"), hier, 16));
        assertNull(Symbole.hole(client, Symbole.uri(basis, "beispiel:staedte", "images/weiter.png"), hier, 16));
        // Ein ferner Spielserver darf nicht ins Heimnetz lenken.
        assertNull(Symbole.hole(client, Symbole.uri(basis, "beispiel:staedte", "images/burg_16.png"), null, 16));
    }
}
