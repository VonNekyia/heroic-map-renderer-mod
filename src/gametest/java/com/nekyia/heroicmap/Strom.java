package com.nekyia.heroicmap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;

/**
 * Der erste Download als ein Strom gegen einen Server auf 127.0.0.1: Die Vollbildkarte ohne Satz zeigt
 * den Kasten des Fortschritts statt des Hinweises, ab der gröbsten Stufe die Karte; am Ende liegt der
 * Satz ganz da, bei 100 % der angekündigten Bytes. Ein Abgleich danach zeigt denselben Kasten mit eigener
 * Zeile 1; gesamt sind die Bytes der Kacheln, die laut Manifest fehlen. Gegenproben: Ein Name mit {@code ..} bricht ab,
 * ohne etwas ausserhalb abzulegen; ein Abbruch im Netz lässt keine halbe Kachel. Siehe docs/download.md, „Strom“.
 */
public final class Strom implements FabricClientGameTest {

    private static final String TOKEN = "strom.test";
    private static final String DIMENSION = "minecraft:overworld";

    /** Eine Kachel des Testsatzes: ihr Name im Strom und im Ordner des Massstabs, und ihre Bytes. */
    private record Kachel(String name, byte[] inhalt) {
        int z() {
            return Integer.parseInt(name.substring(0, name.indexOf('/')));
        }
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        List<Kachel> kacheln = kacheln();
        byte[] karte = datei("4/map.json");
        byte[] manifest = manifest(kacheln);
        byte[] ganz = strom(karte, manifest, kacheln);
        long bytes = kacheln.stream().mapToLong(k -> k.inhalt().length).sum();
        // Für den Abgleich danach tauschen die zwei letzten Kacheln, die feinsten, ihren Inhalt und bekommen neue ETags.
        int a = kacheln.size() - 1, b = kacheln.size() - 2;
        List<Kachel> neu = new ArrayList<>(kacheln);
        neu.set(a, new Kachel(kacheln.get(a).name(), kacheln.get(b).inhalt()));
        neu.set(b, new Kachel(kacheln.get(b).name(), kacheln.get(a).inhalt()));
        Set<String> geaendert = Set.of(kacheln.get(a).name(), kacheln.get(b).name());
        long fehlen = kacheln.get(a).inhalt().length + kacheln.get(b).inhalt().length;
        Abgleich abgleich = new Abgleich(manifest(neu, geaendert), neu, kacheln.get(b).name(), new CountDownLatch(1));
        // Der Server hält den Strom an, bis das Bild ohne Karte und das mit der gröbsten Stufe aufgenommen sind.
        CountDownLatch ohneKarte = new CountDownLatch(1), mitKarte = new CountDownLatch(1);
        HttpServer http;
        try {
            http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Daemons: Sonst hielte ein wartender Thread das Spiel nach dem letzten Test offen.
        ExecutorService threads = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "Strom Server");
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(threads);
        http.createContext("/download/", t -> antworte(t, karte, manifest, kacheln, ganz.length, ohneKarte, mitKarte, abgleich));
        http.start();
        String basis = "http://127.0.0.1:" + http.getAddress().getPort() + "/download/";
        Path welt = null;
        try (TestSingleplayerContext spiel = context.worldBuilder().create(); var _ = Abschluss.langsamerTakt(context, spiel)) {
            spiel.getConnection().waitForChunksRender();
            welt = context.computeOnClient(mc -> Downloads.weltOrdner());
            raeume(welt);
            context.runOnClient(mc -> mc.gui.setScreen(new Karte(Satz.fuer(Downloads.weltOrdner(), DIMENSION))));
            context.waitTick();
            if (context.computeOnClient(mc -> !(mc.gui.screen() instanceof Karte k) || k.satz() != null)) {
                throw new AssertionError("Die Vollbildkarte hat vor dem Download schon einen Satz");
            }

            // Der erste Download: erst der Kasten ohne Karte, dann die Karte ab der gröbsten Stufe, am Ende alles.
            starte(context, basis, "strom", welt, manifest, bytes, kacheln.size());
            warte(context, "der Kasten des Fortschritts", mc -> Downloads.INSTANZ.fortschritt() != null
                    && Downloads.INSTANZ.fortschritt().empfangen.get() > 0);
            Laden.Fortschritt fortschritt = context.computeOnClient(mc -> Downloads.INSTANZ.fortschritt());
            context.waitTicks(2);
            Path bild = context.takeScreenshot(TestScreenshotOptions.of("strom-ohne-karte").disableCounterPrefix());
            pruefeKasten(context, bild, "ohne Karte");
            pruefeOhneHinweis(context, bild);
            ohneKarte.countDown();
            warte(context, "die Karte nach der gröbsten Stufe", mc -> mc.gui.screen() instanceof Karte k && k.satz() != null);
            // Die feinste Stufe fehlt noch; die Karte fragt die gröberen an und dekodiert sie.
            context.waitTicks(20);
            bild = context.takeScreenshot(TestScreenshotOptions.of("strom-karte").disableCounterPrefix());
            if (context.computeOnClient(mc -> Downloads.INSTANZ.fortschritt() == null)) {
                throw new AssertionError("Der Download ist fertig, bevor der Server die feinste Stufe schickte");
            }
            pruefeKasten(context, bild, "mit Karte");
            pruefeKarteSichtbar(bild);
            mitKarte.countDown();
            Path strom = welt.resolve("strom");
            warte(context, "das Ende des Downloads", mc -> !Downloads.INSTANZ.belegt(strom));
            if (fortschritt.anteil() != 1.0 || fortschritt.empfangen.get() != ganz.length || fortschritt.gesamt != ganz.length) {
                throw new AssertionError("Fortschritt am Ende: " + fortschritt.empfangen.get() + " von " + fortschritt.gesamt
                        + " Byte, erwartet " + ganz.length);
            }
            for (Kachel k : kacheln) {
                Path datei = strom.resolve("4").resolve(k.name());
                if (!Files.exists(datei) || !Arrays.equals(Files.readAllBytes(datei), k.inhalt())) {
                    throw new AssertionError("Kachel fehlt oder ist anders: " + k.name());
                }
            }
            Satz satz = Satz.lies(strom);
            if (Laden.imStrom(strom.resolve("4")) || satz == null || !satz.name().equals("Strom")) {
                throw new AssertionError("Der Satz ist nicht fertig: Strom offen " + Laden.imStrom(strom.resolve("4")) + ", Satz " + satz);
            }

            // Ein Abgleich danach: derselbe Kasten, Zeile 1 anders als beim Strom, gesamt die zwei Kacheln, die fehlen. Der Server
            // hält eine, bis das Bild steht. Siehe docs/vollbildkarte.md, „Fortschritt“.
            starteAbgleich(context, basis, welt, abgleich.manifest(), bytes, kacheln.size());
            warte(context, "der Kasten des Abgleichs", mc -> Downloads.INSTANZ.fortschritt() != null
                    && Downloads.INSTANZ.fortschritt().abgleich && Downloads.INSTANZ.fortschritt().empfangen.get() > 0);
            Laden.Fortschritt imAbgleich = context.computeOnClient(mc -> Downloads.INSTANZ.fortschritt());
            context.waitTicks(2);
            Path bildAbgleich = context.takeScreenshot(TestScreenshotOptions.of("abgleich").disableCounterPrefix());
            pruefeKasten(context, bildAbgleich, "beim Abgleich");
            if (zeileEins(context, bildAbgleich).equals(zeileEins(context, bild))) {
                throw new AssertionError("Zeile 1 des Kastens beim Abgleich wie beim Strom");
            }
            abgleich.gesehen().countDown();
            warte(context, "das Ende des Abgleichs", mc -> !Downloads.INSTANZ.belegt(strom));
            if (imAbgleich.gesamt != fehlen || imAbgleich.empfangen.get() != fehlen) {
                throw new AssertionError("Fortschritt des Abgleichs: " + imAbgleich.empfangen.get() + " von " + imAbgleich.gesamt
                        + " Byte, erwartet " + fehlen);
            }
            for (Kachel k : neu) {
                if (geaendert.contains(k.name()) && !Arrays.equals(Files.readAllBytes(strom.resolve("4").resolve(k.name())), k.inhalt())) {
                    throw new AssertionError("Kachel nach dem Abgleich nicht neu: " + k.name());
                }
            }

            // Gegenprobe: Ein Name mit .. bricht ab; ausserhalb liegt nichts, eine Karte entsteht nicht.
            starte(context, basis, "boese", welt, manifest, bytes, kacheln.size());
            Path boese = welt.resolve("boese");
            warte(context, "das Ende des bösen Stroms", mc -> !Downloads.INSTANZ.belegt(boese));
            try (Stream<Path> alle = Files.walk(welt)) {
                List<Path> raus = alle.filter(p -> p.getFileName().toString().equals("x.webp")).toList();
                if (!raus.isEmpty()) {
                    throw new AssertionError("Der Name mit .. landete auf der Platte: " + raus);
                }
            }
            try (Stream<Path> alle = Files.walk(boese)) {
                if (alle.anyMatch(p -> p.toString().endsWith(".webp")) || Satz.lies(boese) != null) {
                    throw new AssertionError("Der böse Strom hinterliess Kacheln oder einen Satz");
                }
            }

            // Gegenprobe: Der Server bricht jedes Mal mitten in der vierten Kachel ab; der Mod setzt fort, bis zwei Versuche nichts
            // Neues bringen. Was vor einem Abbruch ankam, aber noch nicht gelesen war, verwirft der Client mit dem Fehler; es liegen
            // also die ersten k ≤ 3 Kacheln ganz da, keine halbe, die vierte nicht.
            starte(context, basis, "abbruch", welt, manifest, bytes, kacheln.size());
            Path abbruch = welt.resolve("abbruch").resolve("4");
            warte(context, "das Ende des abgebrochenen Stroms", mc -> !Downloads.INSTANZ.belegt(abbruch.getParent()));
            int ganze = 0;
            for (int i = 0; i < 4; i++) {
                Path datei = abbruch.resolve(kacheln.get(i).name());
                boolean da = Files.exists(datei);
                if (da && (i == 3 || i != ganze || !Arrays.equals(Files.readAllBytes(datei), kacheln.get(i).inhalt()))) {
                    throw new AssertionError("Nach dem Abbruch liegt " + kacheln.get(i).name() + " halb, ausser der Reihe oder trotz Abbruch");
                }
                ganze += da ? 1 : 0;
            }
            try (Stream<Path> tmp = Files.list(abbruch.resolve("tmp"))) {
                if (tmp.findAny().isPresent()) {
                    throw new AssertionError("Nach dem Abbruch liegt eine Zwischendatei");
                }
            }
            String nach = ganze == 0 ? "" : kacheln.get(ganze - 1).name().replace(".webp", "");
            if (!Laden.imStrom(abbruch) || !Files.readString(abbruch.resolve(Laden.STROM)).equals(nach)) {
                throw new AssertionError("Der Strom gilt nach dem Abbruch nicht als offen hinter „" + nach + "“");
            }
            context.runOnClient(mc -> mc.gui.setScreen(null));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            http.stop(0);
            threads.shutdownNow();
            if (welt != null) {
                raeume(welt);
            }
        }
    }

    /** Die Bäume dieses Tests und eine selbst gezeichnete Karte, die vorginge. */
    private static void raeume(Path welt) {
        try {
            for (String baum : new String[] {"strom", "boese", "abbruch", Selbst.baum(DIMENSION)}) {
                Laden.loesche(welt.resolve(baum));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void starte(ClientGameTestContext context, String basis, String baum, Path welt, byte[] manifest, long bytes, int zahl) {
        URI url = URI.create(basis + baum);
        Freigabe f = new Freigabe(baum, "voll", 4, bytes, url, TOKEN, Laden.sha256(manifest));
        Laden.Auftrag a = new Laden.Auftrag(url, TOKEN, Laden.sha256(manifest), bytes, 4, false, bytes, zahl, InetAddress.getLoopbackAddress());
        context.runOnClient(mc -> Downloads.INSTANZ.reiheEin(f, a, welt.resolve(baum), "Strom", DIMENSION));
    }

    /** Ein Abgleich des Baums strom gegen das neue Manifest, mit dem Satz als Deckel. */
    private static void starteAbgleich(ClientGameTestContext context, String basis, Path welt, byte[] manifest, long bytes, int zahl) {
        URI url = URI.create(basis + "strom");
        Freigabe f = new Freigabe("strom", "abgleich", 4, bytes, url, TOKEN, Laden.sha256(manifest));
        Laden.Auftrag a = new Laden.Auftrag(url, TOKEN, Laden.sha256(manifest), bytes, 4, true, bytes, zahl, InetAddress.getLoopbackAddress());
        context.runOnClient(mc -> Downloads.INSTANZ.reiheEin(f, a, welt.resolve("strom"), "Strom", DIMENSION));
    }

    /** Die weissen Pixel der Zeile 1 im Kasten, links der Prozent, als Muster. */
    private static String zeileEins(ClientGameTestContext context, Path bild) throws IOException {
        int[] f = context.computeOnClient(mc -> new int[] {mc.getWindow().getGuiScale(), mc.getWindow().getGuiScaledWidth()});
        int gs = f[0], x = f[1] - 94 - 4 - 200;
        BufferedImage b = ImageIO.read(bild.toFile());
        StringBuilder muster = new StringBuilder();
        for (int y = (4 + 5) * gs; y < (4 + 14) * gs; y++) {
            for (int px = (x + 5) * gs; px < (x + 140) * gs; px++) {
                muster.append((b.getRGB(px, y) & 0xFFFFFF) == 0xFFFFFF ? '#' : '.');
            }
        }
        return muster.toString();
    }

    /** Wartet höchstens 60 s, bis {@code bis} gilt; sonst sagt der Fehler, worauf. */
    private static void warte(ClientGameTestContext context, String was, Predicate<Minecraft> bis) {
        for (int i = 0; i < 1200; i++) {
            if (context.computeOnClient(bis::test)) {
                return;
            }
            context.waitTick();
        }
        throw new AssertionError("Nach 60 s noch nicht da: " + was);
    }

    /** Der Kasten links neben „Karte laden …“: der weisse Rand des Balkens und dunkel darüber. */
    private static void pruefeKasten(ClientGameTestContext context, Path bild, String wann) throws IOException {
        int[] f = context.computeOnClient(mc -> new int[] {mc.getWindow().getGuiScale(), mc.getWindow().getGuiScaledWidth()});
        int gs = f[0], x = f[1] - 94 - 4 - 200;
        BufferedImage b = ImageIO.read(bild.toFile());
        int rand = b.getRGB((x + 5) * gs, (4 + 16) * gs) & 0xFFFFFF, innen = b.getRGB((x + 50) * gs, (4 + 13) * gs) & 0xFFFFFF;
        if (rand != 0xFFFFFF || (innen >> 16 & 0xFF) > 0x60) {
            throw new AssertionError("Kein Kasten des Fortschritts " + wann + ": Rand #" + Integer.toHexString(rand)
                    + ", innen #" + Integer.toHexString(innen));
        }
    }

    /**
     * Vor der feinsten Stufe zeigt die Karte die gröberen: Unter dem Kasten, links der Knöpfe, ist der grösste Teil
     * nicht der dunkle Hintergrund #101010.
     */
    private static void pruefeKarteSichtbar(Path bild) throws IOException {
        BufferedImage b = ImageIO.read(bild.toFile());
        int hell = 0, alle = 0;
        for (int y = b.getHeight() / 4; y < b.getHeight() * 3 / 4; y += 2) {
            for (int x = b.getWidth() / 10; x < b.getWidth() * 7 / 10; x += 2) {
                int p = b.getRGB(x, y);
                alle++;
                hell += Math.max(p >> 16 & 0xFF, Math.max(p >> 8 & 0xFF, p & 0xFF)) > 0x30 ? 1 : 0;
            }
        }
        if (hell * 2 < alle) {
            throw new AssertionError("Die Karte bleibt vor der feinsten Stufe dunkel: " + hell + " von " + alle + " Pixeln hell");
        }
    }

    /** Mitten auf der Karte ohne Satz steht kein Hinweis, solange ein Download läuft: dort nur Hintergrund. */
    private static void pruefeOhneHinweis(ClientGameTestContext context, Path bild) throws IOException {
        int[] f = context.computeOnClient(mc -> new int[] {mc.getWindow().getGuiScale(), mc.getWindow().getGuiScaledHeight()});
        BufferedImage b = ImageIO.read(bild.toFile());
        int y0 = f[1] / 2 * f[0];
        for (int y = y0; y < y0 + 8 * f[0]; y++) {
            for (int x = b.getWidth() / 4; x < b.getWidth() * 3 / 4; x++) {
                if ((b.getRGB(x, y) & 0xFF) > 0x40) {
                    throw new AssertionError("Mitten auf der Karte steht Text, bei " + x + ", " + y);
                }
            }
        }
    }

    /** Der Abgleich danach: das neue Manifest, die Kacheln danach, die Kachel, die der Server hält, bis {@code gesehen}. */
    private record Abgleich(byte[] manifest, List<Kachel> kacheln, String haelt, CountDownLatch gesehen) {
    }

    /**
     * Antwortet wie der Renderer auf {@code /download/<baum>.zip}, je Baum ein anderer Strom, und einzeln auf
     * {@code /download/strom/<datei>} für den Abgleich.
     */
    private static void antworte(HttpExchange t, byte[] karte, byte[] manifest, List<Kachel> kacheln, long gesamt,
            CountDownLatch ohneKarte, CountDownLatch mitKarte, Abgleich abgleich) throws IOException {
        if (!("Bearer " + TOKEN).equals(t.getRequestHeaders().getFirst("Authorization"))) {
            t.sendResponseHeaders(401, -1);
            t.close();
            return;
        }
        String baum = t.getRequestURI().getPath().substring("/download/".length());
        if (!baum.endsWith(".zip")) {
            einzeln(t, baum.substring(baum.indexOf('/') + 1), karte, abgleich);
            return;
        }
        t.getResponseHeaders().add("X-Heroic-Bytes", String.valueOf(gesamt));
        t.sendResponseHeaders(200, 0);
        OutputStream aus = t.getResponseBody();
        eintrag(aus, "map.json", karte);
        eintrag(aus, "manifest", manifest);
        aus.flush();
        switch (baum) {
            case "strom.zip" -> {
                warte(ohneKarte);
                boolean gehalten = false;
                for (Kachel k : kacheln) {
                    if (k.z() == 2 && !gehalten) {
                        aus.flush();
                        warte(mitKarte);
                        gehalten = true;
                    }
                    eintrag(aus, k.name(), k.inhalt());
                }
            }
            case "boese.zip" -> eintrag(aus, "../x.webp", kacheln.getFirst().inhalt());
            case "abbruch.zip" -> {
                // Wie der Renderer: mit nach erst die Kacheln dahinter. Abgebrochen wird immer mitten in der vierten.
                String query = t.getRequestURI().getQuery();
                String nach = query == null ? null : query.substring("nach=".length()) + ".webp";
                int ab = 0;
                while (nach != null && ab < 3 && !kacheln.get(ab).name().equals(nach)) {
                    ab++;
                }
                for (int i = nach == null ? 0 : ab + 1; i < 3; i++) {
                    eintrag(aus, kacheln.get(i).name(), kacheln.get(i).inhalt());
                }
                ByteArrayOutputStream vierte = new ByteArrayOutputStream();
                eintrag(vierte, kacheln.get(3).name(), kacheln.get(3).inhalt());
                aus.write(vierte.toByteArray(), 0, vierte.size() / 2);
                aus.flush();
                // Ohne close: Der Server schliesst die Verbindung ohne das letzte Stück.
                throw new IOException("Abbruch im Test");
            }
            default -> {
            }
        }
        eintrag(aus, "ende", new byte[0]);
        t.close();
    }

    /** Eine Datei für den Abgleich: map.json, das neue Manifest oder eine Kachel danach. */
    private static void einzeln(HttpExchange t, String name, byte[] karte, Abgleich abgleich) throws IOException {
        byte[] inhalt = switch (name) {
            case "map.json" -> karte;
            case "manifest" -> abgleich.manifest();
            default -> abgleich.kacheln().stream().filter(k -> k.name().equals(name)).map(Kachel::inhalt).findFirst().orElse(null);
        };
        if (inhalt == null) {
            t.sendResponseHeaders(404, -1);
            t.close();
            return;
        }
        if (name.equals(abgleich.haelt())) {
            warte(abgleich.gesehen());
        }
        t.sendResponseHeaders(200, inhalt.length);
        try (OutputStream aus = t.getResponseBody()) {
            aus.write(inhalt);
        }
    }

    private static void warte(CountDownLatch frei) {
        try {
            frei.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Ein Eintrag des Stroms wie vom Renderer: lokaler Kopf, ohne Kompression und ohne Data Descriptor. */
    private static void eintrag(OutputStream aus, String name, byte[] inhalt) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(inhalt);
        byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer kopf = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x04034b50).putShort((short) 10).putShort((short) 0).putShort((short) 0)
                .putShort((short) 0).putShort((short) 0x21)
                .putInt((int) crc.getValue()).putInt(inhalt.length).putInt(inhalt.length)
                .putShort((short) n.length).putShort((short) 0);
        aus.write(kopf.array());
        aus.write(n);
        aus.write(inhalt);
    }

    private static byte[] strom(byte[] karte, byte[] manifest, List<Kachel> kacheln) {
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        try {
            eintrag(aus, "map.json", karte);
            eintrag(aus, "manifest", manifest);
            for (Kachel k : kacheln) {
                eintrag(aus, k.name(), k.inhalt());
            }
            eintrag(aus, "ende", new byte[0]);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return aus.toByteArray();
    }

    /** Die Kacheln des Testsatzes von grob nach fein, wie der Renderer sie schickt. */
    private static List<Kachel> kacheln() {
        List<Kachel> kacheln = new ArrayList<>();
        for (String name : new String(datei("liste.txt"), StandardCharsets.UTF_8).lines().filter(z -> z.endsWith(".webp")).toList()) {
            kacheln.add(new Kachel(name, datei("4/" + name)));
        }
        kacheln.sort(Comparator.comparingInt(Kachel::z));
        return kacheln;
    }

    private static byte[] manifest(List<Kachel> kacheln) {
        return manifest(kacheln, Set.of());
    }

    /** Das Manifest; die Kacheln in {@code neu} bekommen einen anderen ETag. */
    private static byte[] manifest(List<Kachel> kacheln, Set<String> neu) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < kacheln.size(); i++) {
            Kachel k = kacheln.get(i);
            text.append(k.name().replace(".webp", "")).append(' ').append(k.inhalt().length).append(neu.contains(k.name()) ? " \"n" : " \"t")
                    .append(i).append("\"\n");
        }
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(aus)) {
            gz.write(text.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return aus.toByteArray();
    }

    private static byte[] datei(String name) {
        try (InputStream rein = Strom.class.getResourceAsStream("/satz/" + name)) {
            return rein.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
