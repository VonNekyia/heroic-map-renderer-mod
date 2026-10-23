package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.google.gson.JsonParser;
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

/** Der Befehl zum Teleportieren, woher die Höhe kommt und wann der Mod korrigiert. Siehe docs/vollbildkarte.md, „Teleportieren“. */
class TeleportTest {

    private static final Teleport.Quelle BODEN = new Teleport.Quelle("ground", 1, -64, 319);
    private static final Teleport.Quelle HOEHEN = new Teleport.Quelle("heights", 4, -64, 319);

    @Test
    void befehlAufDieMitteDesBlocks() {
        assertEquals("tp 12.5 70 -40.5", Teleport.befehl(12, 70, -41));
        assertEquals("tp -0.5 -60 0.5", Teleport.befehl(-1, -60, 0));
        // Zurück an den Start: genau, auch auf einer Stufe.
        assertEquals("tp 12.347 64.500 -40.001", Teleport.befehl(12.3467, 64.5, -40.0009));
    }

    @Test
    void nurMitTpImBefehlsbaum() {
        // Der Server schickt nur Befehle, die der Spieler darf; ohne tp schickt der Mod nichts.
        CommandDispatcher<Object> mit = new CommandDispatcher<>();
        mit.register(LiteralArgumentBuilder.literal("tp"));
        mit.register(LiteralArgumentBuilder.literal("teleport"));
        assertTrue(Teleport.erlaubt(mit.getRoot()));
        CommandDispatcher<Object> ohne = new CommandDispatcher<>();
        ohne.register(LiteralArgumentBuilder.literal("teleport"));
        ohne.register(LiteralArgumentBuilder.literal("execute"));
        assertFalse(Teleport.erlaubt(ohne.getRoot()));
        assertFalse(Teleport.erlaubt(new CommandDispatcher<>().getRoot()));
    }

    /** Eine Säule ab y = 0 aus Zeichen: # fest, . Luft, ~ Flüssigkeit, unten zuerst. */
    private static Integer frei(String saeule, int nahe) {
        boolean[] fest = new boolean[saeule.length()], leer = new boolean[saeule.length()];
        for (int i = 0; i < saeule.length(); i++) {
            fest[i] = saeule.charAt(i) == '#';
            leer[i] = saeule.charAt(i) == '.';
        }
        return Teleport.frei(fest, leer, 0, nahe);
    }

    @Test
    void freierPlatzNaheDerHoehe() {
        // Plätze bei 1 und 6, beide mit festem Boden und zwei freien Blöcken.
        String saeule = "#..###....#";
        assertEquals(1, frei(saeule, 0));
        assertEquals(6, frei(saeule, 5));
        assertEquals(6, frei(saeule, 9));
        // Gleich weit: der untere.
        assertEquals(1, frei("#...#..", 3));
        // Nur ein Block Luft ist kein Platz, Flüssigkeit weder Boden noch Luft.
        assertNull(frei("#.#", 1));
        assertNull(frei("~...", 1));
        assertNull(frei("#~.", 1));
        // Der Kopf muss unter der Decke bleiben, dem Ende der Säule.
        assertNull(frei("#.", 1));
        assertNull(frei("....", 1));
    }

    @Test
    void korrigiertImBlockUeberDerDeckeZuHochOderDarunter() {
        assertFalse(Teleport.korrigieren(-60, -60, false, false));
        assertFalse(Teleport.korrigieren(-57, -60, false, false));
        assertFalse(Teleport.korrigieren(-60.5, -60, false, false));
        assertFalse(Teleport.korrigieren(-61, -60, false, false));
        assertTrue(Teleport.korrigieren(-60, -60, true, false));
        assertTrue(Teleport.korrigieren(-60, -60, false, true));
        assertTrue(Teleport.korrigieren(-56.9, -60, false, false));
        assertTrue(Teleport.korrigieren(-61.1, -60, false, false));
    }

    @Test
    void sichereHoeheInDerLuftUnterDerDeckeDieDesSpielers() {
        // Ohne Decke zwei unter dem obersten Block, egal wo der Spieler steht.
        assertEquals(317, Teleport.sicher(false, 80, 63, -64, 319));
        assertEquals(317, Teleport.sicher(false, -63, 63, -64, 319));
        assertEquals(253, Teleport.sicher(false, 70, 0, 0, 255));
        // Mit Decke die Höhe des Spielers, mindestens der Meeresspiegel, in der Welt.
        assertEquals(80, Teleport.sicher(true, 80, 32, 0, 255));
        assertEquals(32, Teleport.sicher(true, 20, 32, 0, 255));
        assertEquals(254, Teleport.sicher(true, 400, 32, 0, 255));
        assertEquals(10, Teleport.sicher(true, 5, -80, 10, 255));
    }

    @Test
    void grenzenDesOffenenTeleports() {
        // Mit Höhe: unterwegs warten, auch ohne Chunk.
        assertEquals(Teleport.Schritt.WARTEN, Teleport.schritt(1, false, false, 500, false));
        assertEquals(Teleport.Schritt.WARTEN, Teleport.schritt(30, false, true, 0.4, false));
        // Am Ziel mit dem Chunk beim Client prüfen.
        assertEquals(Teleport.Schritt.PRUEFEN, Teleport.schritt(2, false, false, 0.4, true));
        assertEquals(Teleport.Schritt.PRUEFEN, Teleport.schritt(30, false, true, 1.5, true));
        assertEquals(Teleport.Schritt.WARTEN, Teleport.schritt(30, false, false, 1.6, true));
        // Nach der Ankunft bis 4 Blöcke weg warten, weiter weg aufhören; vor der Ankunft zählt der Weg nicht.
        assertEquals(Teleport.Schritt.WARTEN, Teleport.schritt(30, false, true, 4, false));
        assertEquals(Teleport.Schritt.ENDE, Teleport.schritt(30, false, true, 4.1, true));
        assertEquals(Teleport.Schritt.WARTEN, Teleport.schritt(30, false, false, 4.1, true));
        // Nach 10 s hört er auf, auch am Ziel.
        assertEquals(Teleport.Schritt.PRUEFEN, Teleport.schritt(200, false, true, 0, true));
        assertEquals(Teleport.Schritt.ENDE, Teleport.schritt(201, false, true, 0, true));
        assertEquals(Teleport.Schritt.ENDE, Teleport.schritt(201, false, false, 500, false));
    }

    @Test
    void ohneHoeheNachObenBisZumChunkDannZurueck() {
        // Bis der Chunk da ist, alle 10 Ticks zurück auf die sichere Höhe, auch vor der Ankunft.
        assertEquals(Teleport.Schritt.WARTEN, Teleport.schritt(1, true, false, 500, false));
        assertEquals(Teleport.Schritt.WARTEN, Teleport.schritt(9, true, true, 0, false));
        assertEquals(Teleport.Schritt.NACH_OBEN, Teleport.schritt(10, true, true, 0, false));
        assertEquals(Teleport.Schritt.NACH_OBEN, Teleport.schritt(20, true, false, 500, false));
        assertEquals(Teleport.Schritt.WARTEN, Teleport.schritt(21, true, true, 0, false));
        // Mit dem Chunk am Ziel landen, auch genau an der Frist.
        assertEquals(Teleport.Schritt.PRUEFEN, Teleport.schritt(3, true, true, 0, true));
        assertEquals(Teleport.Schritt.PRUEFEN, Teleport.schritt(100, true, true, 0, true));
        // Nach 5 s ohne Chunk zurück an den Start.
        assertEquals(Teleport.Schritt.NACH_OBEN, Teleport.schritt(90, true, true, 0, false));
        assertEquals(Teleport.Schritt.AUFGEBEN, Teleport.schritt(100, true, true, 0, false));
        // Fliegt der Spieler nach der Ankunft weg, hört er auf, ohne ihn zurückzuholen.
        assertEquals(Teleport.Schritt.ENDE, Teleport.schritt(50, true, true, 4.1, false));
        assertEquals(Teleport.Schritt.ENDE, Teleport.schritt(100, true, true, 4.1, false));
    }

    /**
     * Der Server zählt je Befehl 20 und je Tick 1 ab und wirft bei 200 hinaus, ausser Ops
     * ({@code ServerGamePacketListenerImpl.detectCommandRateSpam}, per javap in 26.3, Standard
     * {@code command-spam-threshold-seconds=10}). Ein schwebender Teleport bis zur Frist bleibt mit drei eigenen Befehlen
     * des Spielers davor darunter. Siehe docs/vollbildkarte.md, „Teleportieren“.
     */
    @Test
    void ohneHoeheUnterDerGrenzeFuerBefehle() {
        int zaehler = 3 * 20 + 20, hoechstens = zaehler;
        for (int t = 1; ; t++) {
            zaehler = Math.max(0, zaehler - 1);
            Teleport.Schritt s = Teleport.schritt(t, true, true, 0, false);
            if (s == Teleport.Schritt.NACH_OBEN || s == Teleport.Schritt.AUFGEBEN) {
                zaehler += 20;
                hoechstens = Math.max(hoechstens, zaehler);
            }
            if (s == Teleport.Schritt.AUFGEBEN) {
                break;
            }
        }
        assertTrue(hoechstens < 200, "Zähler " + hoechstens);
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
