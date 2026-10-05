package com.nekyia.heroicmap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * Lädt einen Satz Kacheln vom Server: {@code map.json}, das Manifest, dann was fehlt oder
 * sich geändert hat, und löscht, was nicht mehr im Manifest steht. Ohne Minecraft, damit es
 * sich gegen einen kleinen Server testen lässt. Siehe docs/download.md.
 */
final class Laden {

    /** Höchstens so gross ist das Manifest entpackt, und so gross darf {@code map.json} sein. */
    static final long MANIFEST_MAX = 64L << 20;
    /** Höchstens so gross ist eine Kachel. */
    static final long KACHEL_MAX = 4L << 20;
    static final int VERBINDUNGEN = 4;
    /** Eine Koordinate einer Kachel: nur eine ganze Zahl, denn sie wird ein Dateiname. */
    private static final Pattern ZAHL = Pattern.compile("-?\\d{1,9}");
    private static final Pattern GROESSE = Pattern.compile("\\d{1,10}");
    private static final Pattern PFAD = Pattern.compile("-?\\d{1,9}/-?\\d{1,9}/-?\\d{1,9}");
    /** Ein ETag ist für den Mod undurchsichtig, aber ohne Leerzeichen und Steuerzeichen. */
    private static final Pattern ETAG = Pattern.compile("[\\x21-\\x7e]{1,256}");
    private static final String INDEX = "etags.txt";

    /** Was die {@code freigabe} nennt. */
    record Auftrag(URI url, String token, String manifestSha256, long bytes, int massstab) {
    }

    /** Eine Zeile des Manifests: {@code z/x/y grösse etag}. */
    record Eintrag(int z, int x, int y, long groesse, String etag) {
        String pfad() {
            return z + "/" + x + "/" + y;
        }
    }

    /** Was geladen, schon da und gelöscht ist; gekappt, wenn die Summe die Grenze erreichte. */
    record Ergebnis(int geladen, int gleich, int geloescht, long bytesGeladen, boolean gekappt) {
    }

    /** Warum ein Download nicht ging. */
    enum Grund {
        /** Das Manifest passt nicht zu {@code manifest_sha256}; neu anfragen. */
        PRUEFSUMME,
        /** Manifest oder {@code map.json} verletzen eine Grenze oder lassen sich nicht lesen. */
        MANIFEST,
        /** Eine Kachel ist grösser als {@link #KACHEL_MAX}. */
        KACHEL,
        /** Der Server lehnt das Token ab, 401, 403 oder 429. */
        ABGELEHNT,
        /** Eine andere Antwort, eine Weiterleitung oder ein Fehler im Netz. */
        NETZ
    }

    static final class Fehler extends Exception {
        final Grund grund;

        Fehler(Grund grund, String text) {
            super(grund + ": " + text);
            this.grund = grund;
        }
    }

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            // Kein dritter Host bekommt das Token.
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Lädt den Satz nach {@code ziel}: {@code map.json}, {@code etags.txt} und {@code z/x/y.webp}. */
    Ergebnis lade(Auftrag a, Path ziel) throws Fehler, IOException, InterruptedException {
        Files.createDirectories(ziel);
        byte[] karte = hole(a, "map.json", MANIFEST_MAX);
        int minZoom, maxZoom;
        try {
            JsonObject json = JsonParser.parseString(new String(karte, StandardCharsets.UTF_8)).getAsJsonObject();
            minZoom = json.get("minZoom").getAsInt();
            maxZoom = json.get("maxZoom").getAsInt();
        } catch (RuntimeException e) {
            throw new Fehler(Grund.MANIFEST, "map.json nicht lesbar");
        }
        int stufe = maxZoom - switch (a.massstab()) {
            case 4 -> 0;
            case 2 -> 1;
            case 1 -> 2;
            default -> throw new Fehler(Grund.MANIFEST, "Massstab " + a.massstab());
        };
        if (stufe < minZoom) {
            throw new Fehler(Grund.MANIFEST, "keine Stufe für " + a.massstab() + " px");
        }
        byte[] gz = hole(a, "manifest", MANIFEST_MAX);
        if (!sha256(gz).equals(a.manifestSha256().toLowerCase(Locale.ROOT))) {
            throw new Fehler(Grund.PRUEFSUMME, "manifest_sha256");
        }
        List<Eintrag> soll = lies(entpacke(gz), minZoom, maxZoom, stufe);
        schreibe(ziel.resolve("map.json"), karte);

        Map<String, String> ist = new ConcurrentHashMap<>(liesIndex(ziel));
        Set<String> pfade = soll.stream().map(Eintrag::pfad).collect(Collectors.toSet());
        int geloescht = 0;
        for (String pfad : List.copyOf(ist.keySet())) {
            if (!pfade.contains(pfad)) {
                Files.deleteIfExists(datei(ziel, pfad));
                ist.remove(pfad);
                geloescht++;
            }
        }
        ConcurrentLinkedQueue<Eintrag> fehlt = new ConcurrentLinkedQueue<>();
        for (Eintrag e : soll) {
            if (!e.etag().equals(ist.get(e.pfad())) || !Files.exists(datei(ziel, e.pfad()))) {
                fehlt.add(e);
            }
        }
        int gleich = soll.size() - fehlt.size();
        long grenze = a.bytes() + a.bytes() / 10;
        AtomicLong reserviert = new AtomicLong(), geladenBytes = new AtomicLong();
        AtomicInteger geladen = new AtomicInteger();
        AtomicBoolean gekappt = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(VERBINDUNGEN, r -> {
            Thread t = new Thread(r, "Heroic Map Download");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> arbeit = new ArrayList<>();
            for (int i = 0; i < VERBINDUNGEN; i++) {
                arbeit.add(pool.submit(() -> {
                    for (Eintrag e; (e = fehlt.poll()) != null; ) {
                        // Die Summe bleibt unter bytes plus 10 %; was darüber läge, holt der nächste Abgleich.
                        long vorher = reserviert.getAndAdd(e.groesse());
                        if (vorher + e.groesse() > grenze) {
                            reserviert.addAndGet(-e.groesse());
                            gekappt.set(true);
                            continue;
                        }
                        HttpResponse<InputStream> antwort = sende(a, e.pfad() + ".webp");
                        byte[] bild = lies(antwort.body(), KACHEL_MAX, Grund.KACHEL);
                        // Neuer als das Manifest kann eine Kachel sein; dann zählt ihre echte Grösse.
                        reserviert.addAndGet(bild.length - e.groesse());
                        schreibe(datei(ziel, e.pfad()), bild);
                        ist.put(e.pfad(), antwort.headers().firstValue("ETag").filter(t -> ETAG.matcher(t).matches()).orElse(e.etag()));
                        geladen.incrementAndGet();
                        geladenBytes.addAndGet(bild.length);
                    }
                    return null;
                }));
            }
            for (Future<?> f : arbeit) {
                warte(f);
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
            schreibeIndex(ziel, ist);
        }
        return new Ergebnis(geladen.get(), gleich, geloescht, geladenBytes.get(), gekappt.get());
    }

    private static void warte(Future<?> f) throws Fehler, IOException, InterruptedException {
        try {
            f.get();
        } catch (java.util.concurrent.ExecutionException e) {
            switch (e.getCause()) {
                case Fehler fehler -> throw fehler;
                case IOException io -> throw io;
                case InterruptedException ie -> throw ie;
                default -> throw new IOException(e.getCause());
            }
        }
    }

    /** Die Zeilen bis zur Stufe des Massstabs; jede verletzte Grenze bricht ab. */
    static List<Eintrag> lies(String manifest, int minZoom, int maxZoom, int stufe) throws Fehler {
        List<Eintrag> eintraege = new ArrayList<>();
        for (String zeile : manifest.split("\n")) {
            if (zeile.isEmpty()) {
                continue;
            }
            String[] teile = zeile.split(" ");
            String[] zxy = teile[0].split("/");
            if (teile.length != 3 || zxy.length != 3 || !ZAHL.matcher(zxy[0]).matches() || !ZAHL.matcher(zxy[1]).matches()
                    || !ZAHL.matcher(zxy[2]).matches() || !GROESSE.matcher(teile[1]).matches() || !ETAG.matcher(teile[2]).matches()) {
                throw new Fehler(Grund.MANIFEST, "Zeile nicht lesbar: " + zeile.substring(0, Math.min(zeile.length(), 80)));
            }
            int z = Integer.parseInt(zxy[0]);
            long groesse = Long.parseLong(teile[1]);
            if (z < minZoom || z > maxZoom) {
                throw new Fehler(Grund.MANIFEST, "Stufe " + z + " ausserhalb von map.json");
            }
            if (groesse > KACHEL_MAX) {
                throw new Fehler(Grund.KACHEL, teile[0] + " hat " + groesse + " Byte");
            }
            if (z <= stufe) {
                eintraege.add(new Eintrag(z, Integer.parseInt(zxy[1]), Integer.parseInt(zxy[2]), groesse, teile[2]));
            }
        }
        return eintraege;
    }

    private byte[] hole(Auftrag a, String name, long max) throws Fehler, IOException, InterruptedException {
        return lies(sende(a, name).body(), max, Grund.MANIFEST);
    }

    private HttpResponse<InputStream> sende(Auftrag a, String pfad) throws Fehler, IOException, InterruptedException {
        String basis = a.url().toString();
        URI uri = URI.create(basis.endsWith("/") ? basis + pfad : basis + "/" + pfad);
        HttpRequest anfrage = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + a.token())
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<InputStream> antwort = client.send(anfrage, HttpResponse.BodyHandlers.ofInputStream());
        int status = antwort.statusCode();
        if (status == 200) {
            return antwort;
        }
        antwort.body().close();
        throw new Fehler(status == 401 || status == 403 || status == 429 ? Grund.ABGELEHNT : Grund.NETZ, pfad + ": " + status);
    }

    /** Liest höchstens {@code max} Byte; mehr bricht mit {@code grund} ab. */
    private static byte[] lies(InputStream rein, long max, Grund grund) throws Fehler, IOException {
        try (InputStream in = rein) {
            ByteArrayOutputStream aus = new ByteArrayOutputStream();
            byte[] puffer = new byte[64 << 10];
            long summe = 0;
            for (int n; (n = in.read(puffer)) > 0; ) {
                summe += n;
                if (summe > max) {
                    throw new Fehler(grund, "mehr als " + max + " Byte");
                }
                aus.write(puffer, 0, n);
            }
            return aus.toByteArray();
        }
    }

    /** Entpackt das Manifest, höchstens {@link #MANIFEST_MAX}, gegen gzip-Bomben. */
    static String entpacke(byte[] gz) throws Fehler, IOException {
        try {
            return new String(lies(new GZIPInputStream(new java.io.ByteArrayInputStream(gz)), MANIFEST_MAX, Grund.MANIFEST), StandardCharsets.UTF_8);
        } catch (java.util.zip.ZipException e) {
            throw new Fehler(Grund.MANIFEST, "kein gzip");
        }
    }

    static String sha256(byte[] daten) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(daten));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path datei(Path ziel, String pfad) {
        String[] zxy = pfad.split("/");
        return ziel.resolve(zxy[0]).resolve(zxy[1]).resolve(zxy[2] + ".webp");
    }

    /** Schreibt über eine Zwischendatei, damit nie eine halbe Datei liegen bleibt. */
    private static void schreibe(Path datei, byte[] inhalt) throws IOException {
        Files.createDirectories(datei.getParent());
        Path zwischen = datei.resolveSibling(datei.getFileName() + ".tmp");
        Files.write(zwischen, inhalt);
        Files.move(zwischen, datei, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static Map<String, String> liesIndex(Path ziel) throws IOException {
        Path index = ziel.resolve(INDEX);
        Map<String, String> ist = new java.util.HashMap<>();
        if (Files.exists(index)) {
            for (String zeile : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                String[] teile = zeile.split(" ");
                // Nur z/x/y aus ganzen Zahlen: Nach diesen Pfaden wird gelöscht.
                if (teile.length == 2 && PFAD.matcher(teile[0]).matches()) {
                    ist.put(teile[0], teile[1]);
                }
            }
        }
        return ist;
    }

    private static void schreibeIndex(Path ziel, Map<String, String> ist) throws IOException {
        StringBuilder text = new StringBuilder();
        ist.forEach((pfad, etag) -> text.append(pfad).append(' ').append(etag).append('\n'));
        schreibe(ziel.resolve(INDEX), text.toString().getBytes(StandardCharsets.UTF_8));
    }
}
