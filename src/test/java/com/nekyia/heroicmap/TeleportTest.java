package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

/** Der Befehl zum Teleportieren, woher die Höhe kommt und wann es ihn gibt. Siehe docs/vollbildkarte.md, „Teleport“. */
class TeleportTest {

    private static final Teleport.Quelle BODEN = new Teleport.Quelle("ground", 1, -64, 319);
    private static final Teleport.Quelle HOEHEN = new Teleport.Quelle("heights", 4, -64, 319);

    @Test
    void befehlAufDieMitteDesBlocks() {
        assertEquals("execute in minecraft:overworld positioned 12.5 0 -40.5 positioned over motion_blocking_no_leaves run tp @s ~ ~ ~",
                Teleport.befehl("minecraft:overworld", 12, -41));
        assertEquals("execute in minecraft:overworld run tp @s 12.5 70 -40.5", Teleport.befehl("minecraft:overworld", 12, 70, -41));
    }

    @Test
    void nurMitExecuteUndTp() {
        CommandDispatcher<Object> befehle = new CommandDispatcher<>();
        assertFalse(Teleport.erlaubt(befehle));
        befehle.register(LiteralArgumentBuilder.literal("tp"));
        assertFalse(Teleport.erlaubt(befehle));
        befehle.register(LiteralArgumentBuilder.literal("execute"));
        assertTrue(Teleport.erlaubt(befehle));
    }

    private static JsonObject karte(String felder) {
        return JsonParser.parseString("{\"tileSize\":256,\"minY\":-64,\"maxY\":319" + felder + "}").getAsJsonObject();
    }

    @Test
    void quelleBodenVorHoehenNurInDerWurzel() {
        String hoehen = ",\"heights\":\"../heights/{x}.{z}.bin\",\"heightsCell\":4";
        String boden = ",\"ground\":\"../ground/{x}.{z}.bin\",\"groundCell\":1";
        assertEquals(BODEN, Teleport.quelle(karte(hoehen + boden)));
        assertEquals(HOEHEN, Teleport.quelle(karte(hoehen)));
        // Eine andere Zelle oder die Ablage eines Baums ohne Wurzel nimmt der Mod nicht; dann die Höhen, oder keine.
        assertEquals(HOEHEN, Teleport.quelle(karte(hoehen + ",\"ground\":\"../ground/{x}.{z}.bin\",\"groundCell\":2")));
        assertNull(Teleport.quelle(karte(",\"heights\":\"heights/{x}.{z}.bin\",\"heightsCell\":4")));
        assertNull(Teleport.quelle(karte(",\"heights\":\"../../x/{x}.{z}.bin\",\"heightsCell\":4")));
        assertNull(Teleport.quelle(karte("")));
        assertNull(Teleport.quelle(JsonParser.parseString("{\"ground\":\"../ground/{x}.{z}.bin\",\"groundCell\":1}").getAsJsonObject()));
    }

    @Test
    void dateiDerRegion() {
        URI basis = URI.create("http://127.0.0.1:8080/tiles");
        assertEquals(URI.create("http://127.0.0.1:8080/tiles/ground/3.-1.bin"), Teleport.uri(basis, BODEN, 2000, -1));
        assertEquals(URI.create("http://127.0.0.1:8080/tiles/heights/-2.0.bin"), Teleport.uri(URI.create("http://127.0.0.1:8080/tiles/"), HOEHEN, -513, 511));
        assertNull(Teleport.uri(null, BODEN, 0, 0));
    }

    /** Eine Region voller {@code wert}, an (x, z) {@code dort}, wie der Renderer sie schreibt, gepackt. */
    private static byte[] region(Teleport.Quelle q, short wert, int x, int z, short dort) {
        byte[] werte = new byte[2 * q.seite() * q.seite()];
        for (int i = 0; i < werte.length; i += 2) {
            werte[i] = (byte) wert;
            werte[i + 1] = (byte) (wert >> 8);
        }
        int i = 2 * (Math.floorMod(z, 512) / q.zelle() * q.seite() + Math.floorMod(x, 512) / q.zelle());
        werte[i] = (byte) dort;
        werte[i + 1] = (byte) (dort >> 8);
        return pack(werte);
    }

    private static byte[] pack(byte[] daten) {
        Deflater deflater = new Deflater();
        deflater.setInput(daten);
        deflater.finish();
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        byte[] puffer = new byte[8192];
        while (!deflater.finished()) {
            aus.write(puffer, 0, deflater.deflate(puffer));
        }
        deflater.end();
        return aus.toByteArray();
    }

    @Test
    void entpacktGenauDieLaenge() throws IOException {
        byte[] daten = new byte[1000];
        Arrays.fill(daten, (byte) 7);
        assertArrayEquals(daten, Teleport.entpacke(pack(daten), 1000));
        // Kürzer, länger oder kein zlib: ein Fehler, so packt keine Datei mehr aus, als sie darf.
        assertThrows(IOException.class, () -> Teleport.entpacke(pack(daten), 1001));
        assertThrows(IOException.class, () -> Teleport.entpacke(pack(daten), 999));
        assertThrows(IOException.class, () -> Teleport.entpacke(new byte[] {1, 2, 3, 4}, 1000));
    }

    @Test
    void stehenEinsUeberDemWert() throws IOException {
        byte[] boden = Teleport.entpacke(region(BODEN, (short) 62, -1, 5, (short) 70), 2 * 512 * 512);
        assertEquals(71, Teleport.stehen(boden, BODEN, -1, 5));
        assertEquals(63, Teleport.stehen(boden, BODEN, 0, 5));
        // Die Höhen gelten je 4 × 4: (-4, 4) bis (-1, 7) haben denselben Wert.
        byte[] hoehen = Teleport.entpacke(region(HOEHEN, (short) 62, -1, 5, (short) 70), 2 * 128 * 128);
        assertEquals(71, Teleport.stehen(hoehen, HOEHEN, -4, 4));
        assertEquals(63, Teleport.stehen(hoehen, HOEHEN, -5, 4));
        // Ohne Wert oder ausserhalb von minY bis maxY: keine Höhe.
        byte[] ohne = Teleport.entpacke(region(BODEN, Teleport.OHNE, 0, 0, (short) 400), 2 * 512 * 512);
        assertNull(Teleport.stehen(ohne, BODEN, 1, 1));
        assertNull(Teleport.stehen(ohne, BODEN, 0, 0));
    }

    @Test
    void holtEineRegionMitGrenzen() throws IOException {
        byte[] gut = region(BODEN, (short) -61, 2000, 2000, (short) -61);
        AtomicInteger abrufe = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/tiles/", austausch -> {
            abrufe.incrementAndGet();
            String pfad = austausch.getRequestURI().getPath();
            byte[] antwort = pfad.equals("/tiles/ground/3.3.bin") ? gut : pfad.equals("/tiles/ground/4.4.bin") ? new byte[Teleport.MAX_GEPACKT + 1] : null;
            austausch.sendResponseHeaders(antwort == null ? 404 : 200, antwort == null ? -1 : antwort.length);
            if (antwort != null) {
                austausch.getResponseBody().write(antwort);
            }
            austausch.close();
        });
        server.start();
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).build();
        URI basis = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/tiles");
        InetAddress spielserver = InetAddress.getLoopbackAddress();
        Duration frist = Duration.ofSeconds(5);
        try {
            assertEquals(-60, Teleport.hole(client, Teleport.uri(basis, BODEN, 2000, 2000), spielserver, frist, BODEN, 2000, 2000));
            assertEquals(1, abrufe.get());
            // Fehlt die Datei oder ist sie zu gross: keine Höhe, dann sucht der Server den Boden.
            assertNull(Teleport.hole(client, Teleport.uri(basis, BODEN, 0, 0), spielserver, frist, BODEN, 0, 0));
            assertNull(Teleport.hole(client, Teleport.uri(basis, BODEN, 2048, 2048), spielserver, frist, BODEN, 2048, 2048));
            assertEquals(3, abrufe.get());
            // Ein Server auf diesem Rechner, wenn der Spielserver im Netz steht, wird nicht gefragt.
            assertNull(Teleport.hole(client, Teleport.uri(basis, BODEN, 2000, 2000), InetAddress.getByName("203.0.113.5"), frist, BODEN, 2000, 2000));
            assertEquals(3, abrufe.get());
        } finally {
            server.stop(0);
        }
    }
}
