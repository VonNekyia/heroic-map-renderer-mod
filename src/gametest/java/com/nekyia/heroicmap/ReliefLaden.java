package com.nekyia.heroicmap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Schicht und Rest des Reliefs gegen einen Server auf 127.0.0.1: Mit „slope“ gewählt holt der Mod nichts unter
 * {@code relief/}; mit „ohne“ nach dem Baum Schicht und Rest als Ströme, ohne {@code map.json}, je mit Stand; zurück auf
 * „slope“ löscht der nächste Download sie. Siehe docs/download.md, „Relief“.
 */
public final class ReliefLaden implements FabricClientGameTest {

    private static final String TOKEN = "relief.test";
    private static final String DIMENSION = "minecraft:overworld";
    private static final String BAUM = "hang";

    @Override
    public void runTest(ClientGameTestContext context) {
        List<Strom.Kachel> kacheln = Strom.kacheln();
        // Der Testsatz mit Relief: map.json nennt slope und die Schicht. Schicht und Rest tragen hier einfach Bilder des Satzes,
        // es geht ums Laden; der Rest nur die feinste Stufe.
        JsonObject json = JsonParser.parseString(new String(Strom.datei("4/map.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        json.addProperty("relief", "slope");
        json.addProperty("reliefTiles", Relief.SCHICHT);
        byte[] karte = json.toString().getBytes(StandardCharsets.UTF_8);
        int fein = kacheln.stream().mapToInt(Strom.Kachel::z).max().orElseThrow();
        List<Strom.Kachel> rest = kacheln.stream().filter(k -> k.z() == fein).toList();
        byte[] manifest = Strom.manifest(kacheln), restManifest = Strom.manifest(rest);
        Map<String, byte[]> antworten = Map.of(
                "/download/" + BAUM + ".zip", strom(karte, manifest, kacheln),
                "/download/" + BAUM + "/relief.zip", strom(null, manifest, kacheln),
                "/download/" + BAUM + "/relief/rest.zip", strom(null, restManifest, rest));
        Map<String, Integer> abrufe = new ConcurrentHashMap<>();
        HttpServer http;
        try {
            http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        http.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "Relief Server");
            t.setDaemon(true);
            return t;
        }));
        http.createContext("/download/", t -> antworte(t, antworten, abrufe));
        http.start();
        String basis = "http://127.0.0.1:" + http.getAddress().getPort() + "/download/";
        long bytes = kacheln.stream().mapToLong(k -> k.inhalt().length).sum(), restBytes = rest.stream().mapToLong(k -> k.inhalt().length).sum();
        Relief.Art vorher = context.computeOnClient(mc -> Minimap.INSTANZ.relief());
        try (TestSingleplayerContext spiel = context.worldBuilder().create(); var _ = Abschluss.langsamerTakt(context, spiel)) {
            spiel.getConnection().waitForChunksRender();
            Path welt = context.computeOnClient(mc -> Downloads.weltOrdner()), ordner = welt.resolve(BAUM), schicht = ordner.resolve("4/relief");
            Laden.loesche(ordner);
            Freigabe.Teil teilSchicht = new Freigabe.Teil(Laden.sha256(manifest), bytes), teilRest = new Freigabe.Teil(Laden.sha256(restManifest), restBytes);

            // slope wie gebacken: nur der Baum.
            lade(context, basis, ordner, manifest, bytes, kacheln.size(), Relief.Art.SLOPE, teilSchicht, teilRest);
            if (abrufe.keySet().stream().anyMatch(p -> p.contains("/relief")) || Files.exists(schicht)) {
                throw new AssertionError("Mit slope geladen: " + abrufe + ", Schicht da " + Files.exists(schicht));
            }

            // ohne: nach dem Baum Schicht und Rest, je einmal, ohne map.json, mit Stand.
            lade(context, basis, ordner, manifest, bytes, kacheln.size(), Relief.Art.OHNE, teilSchicht, teilRest);
            for (String pfad : new String[] {"/download/" + BAUM + "/relief.zip", "/download/" + BAUM + "/relief/rest.zip"}) {
                if (abrufe.getOrDefault(pfad, 0) != 1) {
                    throw new AssertionError("Mit ohne: " + pfad + " " + abrufe.getOrDefault(pfad, 0) + " mal statt einmal: " + abrufe);
                }
            }
            for (Strom.Kachel k : kacheln) {
                pruefe(schicht.resolve(k.name()), k.inhalt());
            }
            for (Strom.Kachel k : rest) {
                pruefe(schicht.resolve("rest").resolve(k.name()), k.inhalt());
            }
            if (Files.exists(schicht.resolve("map.json")) || Files.exists(schicht.resolve("rest/map.json"))
                    || !Files.exists(schicht.resolve("stand.txt")) || !Files.exists(schicht.resolve("rest/stand.txt"))) {
                throw new AssertionError("Schicht und Rest: map.json da oder Stand fehlt");
            }

            // Noch einmal mit ohne, gleich gross: Mit Stand lädt er still wieder. Doppelt so gross als der Stand: übergangen, die
            // Schicht bleibt, wie sie ist.
            lade(context, basis, ordner, manifest, bytes, kacheln.size(), Relief.Art.OHNE, teilSchicht, teilRest);
            Freigabe.Teil gross = new Freigabe.Teil(teilSchicht.manifestSha256(), 2 * bytes);
            lade(context, basis, ordner, manifest, bytes, kacheln.size(), Relief.Art.OHNE, gross, teilRest);
            if (abrufe.getOrDefault("/download/" + BAUM + "/relief.zip", 0) != 2 || !Files.exists(schicht.resolve(kacheln.getFirst().name()))) {
                throw new AssertionError("Mit Stand: gleich gross 2 Abrufe erwartet, doppelt so gross keiner mehr: " + abrufe);
            }

            // Zurück auf slope: Schicht und Rest bleiben bis zum nächsten Download, der löscht sie.
            context.runOnClient(mc -> Minimap.INSTANZ.setzeRelief(Relief.Art.SLOPE));
            if (!Files.exists(schicht)) {
                throw new AssertionError("Die Schicht ging schon beim Wechsel, nicht erst beim nächsten Download");
            }
            lade(context, basis, ordner, manifest, bytes, kacheln.size(), Relief.Art.SLOPE, teilSchicht, teilRest);
            if (Files.exists(schicht)) {
                throw new AssertionError("Nach dem nächsten Download mit slope liegt die Schicht noch");
            }
            Laden.loesche(ordner);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            context.runOnClient(mc -> Minimap.INSTANZ.setzeRelief(vorher));
            http.stop(0);
        }
    }

    /** Ein voller Download mit Relief in der {@code freigabe}, mit {@code art} gewählt; wartet, bis er fertig ist. */
    private static void lade(ClientGameTestContext context, String basis, Path ordner, byte[] manifest, long bytes, int zahl,
            Relief.Art art, Freigabe.Teil schicht, Freigabe.Teil rest) {
        context.runOnClient(mc -> Minimap.INSTANZ.setzeRelief(art));
        URI url = URI.create(basis + BAUM);
        Freigabe f = new Freigabe(BAUM, "voll", 4, bytes, url, TOKEN, Laden.sha256(manifest), schicht, rest);
        Laden.Auftrag a = new Laden.Auftrag(url, TOKEN, Laden.sha256(manifest), bytes, 4, false, bytes, zahl, InetAddress.getLoopbackAddress());
        context.runOnClient(mc -> Downloads.INSTANZ.reiheEin(f, a, ordner, "Relief", DIMENSION));
        context.waitFor(mc -> !Downloads.INSTANZ.belegt(ordner), 1200);
    }

    private static void pruefe(Path datei, byte[] inhalt) throws IOException {
        if (!Files.exists(datei) || !java.util.Arrays.equals(Files.readAllBytes(datei), inhalt)) {
            throw new AssertionError("Fehlt oder anders: " + datei);
        }
    }

    /** Ein Strom wie vom Renderer; ohne {@code karte} wie Schicht und Rest, mit dem Manifest zuerst. */
    private static byte[] strom(byte[] karte, byte[] manifest, List<Strom.Kachel> kacheln) {
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        try {
            if (karte != null) {
                Strom.eintrag(aus, "map.json", karte);
            }
            Strom.eintrag(aus, "manifest", manifest);
            for (Strom.Kachel k : kacheln) {
                Strom.eintrag(aus, k.name(), k.inhalt());
            }
            Strom.eintrag(aus, "ende", new byte[0]);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return aus.toByteArray();
    }

    private static void antworte(HttpExchange t, Map<String, byte[]> antworten, Map<String, Integer> abrufe) throws IOException {
        String pfad = t.getRequestURI().getPath();
        abrufe.merge(pfad, 1, Integer::sum);
        byte[] inhalt = ("Bearer " + TOKEN).equals(t.getRequestHeaders().getFirst("Authorization")) ? antworten.get(pfad) : null;
        if (inhalt == null) {
            t.sendResponseHeaders(404, -1);
            t.close();
            return;
        }
        t.getResponseHeaders().add("X-Heroic-Bytes", String.valueOf(inhalt.length));
        t.sendResponseHeaders(200, 0);
        try (OutputStream aus = t.getResponseBody()) {
            aus.write(inhalt);
        }
    }
}
