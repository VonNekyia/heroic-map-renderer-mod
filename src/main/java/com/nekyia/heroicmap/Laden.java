package com.nekyia.heroicmap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * Lädt einen Satz Kacheln vom Server: {@code map.json}, das Manifest, dann was fehlt oder
 * sich geändert hat, und löscht, was nicht mehr im Manifest steht. Ohne Minecraft, damit es
 * sich gegen einen kleinen Server testen lässt. Siehe docs/download.md.
 */
final class Laden {

    /** Höchstens so gross ist das Manifest, gepackt wie entpackt. */
    static final long MANIFEST_MAX = 64L << 20;
    /** Höchstens so gross ist {@code map.json}. */
    static final long KARTE_MAX = 64L << 10;
    /** Höchstens so gross ist eine Kachel. */
    static final long KACHEL_MAX = 4L << 20;
    /** So viel zählt eine Zeile des Manifests mindestens gegen die Grösse des Satzes, ein Cluster der Platte. */
    static final long ZEILE_MIN = 4L << 10;
    static final int VERBINDUNGEN = 4;
    /** Eine Koordinate einer Kachel: nur eine ganze Zahl, denn sie wird ein Dateiname. */
    private static final Pattern ZAHL = Pattern.compile("-?\\d{1,9}");
    private static final Pattern GROESSE = Pattern.compile("\\d{1,10}");
    private static final Pattern PFAD = Pattern.compile("-?\\d{1,9}/-?\\d{1,9}/-?\\d{1,9}");
    /** Ein ETag ist für den Mod undurchsichtig, aber ohne Leerzeichen und Steuerzeichen. */
    private static final Pattern ETAG = Pattern.compile("[\\x21-\\x7e]{1,256}");
    private static final String INDEX = "etags.txt";
    /** Der Ordner der Zwischendateien im Ziel; was darin liegt, ist halb. */
    private static final String ZWISCHEN = "tmp";

    /**
     * Was die {@code freigabe} nennt, dazu aus dem {@code angebot} Grösse und Zahl der Kacheln
     * des Satzes, und die Adresse der Verbindung zum Spielserver oder null.
     */
    record Auftrag(URI url, String token, String manifestSha256, long bytes, int massstab, boolean abgleich,
            long satzBytes, long kacheln, InetAddress spielserver) {

        /** Höchstens so viel lädt der Mod: beim Abgleich der Deckel des Tokens, sonst der Satz plus 10 %. */
        long grenze() {
            return abgleich ? bytes : bytes + bytes / 10;
        }

        /** Höchstens so viele Zeilen bis zur Stufe: die Kacheln des Angebots plus 10 %, und je Zeile ein Cluster. */
        long zeilen() {
            return Math.min(kacheln + kacheln / 10, (satzBytes + satzBytes / 10) / ZEILE_MIN) + 16;
        }
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
        /** Eine andere Antwort, eine Weiterleitung, keine Antwort in der Zeit oder ein Fehler im Netz. */
        NETZ,
        /** Das Ziel liegt im Heimnetz, der Spielserver nicht. */
        HEIMNETZ,
        /** Der Host lässt sich nicht auflösen. */
        UNBEKANNT
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
    /** So lange darf eine Anfrage dauern, Header und Körper zusammen. */
    private final Duration zeit;

    Laden() {
        this(Duration.ofMinutes(2));
    }

    Laden(Duration zeit) {
        this.zeit = zeit;
    }

    /** Lädt den Satz nach {@code ziel}: {@code map.json}, {@code etags.txt} und {@code z/x/y.webp}. */
    Ergebnis lade(Auftrag a, Path ziel) throws Fehler, IOException, InterruptedException {
        switch (Adresse.pruefe(a.url(), a.spielserver())) {
            case SCHEMA -> throw new Fehler(Grund.NETZ, "Adresse");
            case HEIMNETZ -> throw new Fehler(Grund.HEIMNETZ, a.url().getHost());
            case UNBEKANNT -> throw new Fehler(Grund.UNBEKANNT, a.url().getHost());
            case GUT -> {
            }
        }
        Path zwischen = ziel.resolve(ZWISCHEN);
        // Was ein beendetes Spiel halb geschrieben hat.
        loesche(zwischen);
        Files.createDirectories(zwischen);
        byte[] karte = hole(a, "map.json", KARTE_MAX, Grund.MANIFEST).body();
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
        byte[] gz = hole(a, "manifest", MANIFEST_MAX, Grund.MANIFEST).body();
        if (!sha256(gz).equals(a.manifestSha256().toLowerCase(Locale.ROOT))) {
            throw new Fehler(Grund.PRUEFSUMME, "manifest_sha256");
        }
        List<Eintrag> soll = lies(entpacke(gz), minZoom, maxZoom, stufe, a.zeilen());
        schreibe(zwischen, ziel.resolve("map.json"), karte);

        Map<String, String> ist = new ConcurrentHashMap<>(liesIndex(ziel));
        Set<String> pfade = new HashSet<>();
        soll.forEach(e -> pfade.add(e.pfad()));
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
        long grenze = a.grenze();
        AtomicLong reserviert = new AtomicLong(), geladenBytes = new AtomicLong();
        AtomicInteger geladen = new AtomicInteger();
        AtomicBoolean gekappt = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(VERBINDUNGEN, r -> {
            Thread t = new Thread(r, "Heroic Map Download");
            t.setDaemon(true);
            return t;
        });
        // Der Index als Protokoll: Endet das Spiel mitten im Download, gilt, was schon dasteht.
        OutputStream protokoll = Files.newOutputStream(ziel.resolve(INDEX), StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        try {
            List<Future<?>> arbeit = new ArrayList<>();
            for (int i = 0; i < VERBINDUNGEN; i++) {
                arbeit.add(pool.submit(() -> {
                    try {
                        for (Eintrag e; (e = fehlt.poll()) != null; ) {
                            // Die Summe bleibt unter der Grenze; was darüber läge, holt der nächste Abgleich.
                            long vorher = reserviert.getAndAdd(e.groesse());
                            if (vorher + e.groesse() > grenze) {
                                reserviert.addAndGet(-e.groesse());
                                gekappt.set(true);
                                continue;
                            }
                            HttpResponse<byte[]> antwort = hole(a, e.pfad() + ".webp", KACHEL_MAX, Grund.KACHEL);
                            byte[] bild = antwort.body();
                            String etag = antwort.headers().firstValue("ETag").filter(t -> ETAG.matcher(t).matches())
                                    .orElse(e.etag());
                            // Mit dem ETag des Manifests ist es dieselbe Kachel; eine andere Länge heisst abgerissen.
                            if (etag.equals(e.etag()) && bild.length != e.groesse()) {
                                throw new Fehler(Grund.NETZ, e.pfad() + ": " + bild.length + " statt " + e.groesse() + " Byte");
                            }
                            // Neuer als das Manifest kann eine Kachel sein; dann zählt ihre echte Grösse.
                            reserviert.addAndGet(bild.length - e.groesse());
                            // Erst ohne ETag ins Protokoll, dann schreiben, dann mit: Nie gilt ein ETag für eine alte Datei.
                            zeile(protokoll, e.pfad());
                            schreibe(zwischen, datei(ziel, e.pfad()), bild);
                            zeile(protokoll, e.pfad() + " " + etag);
                            ist.put(e.pfad(), etag);
                            geladen.incrementAndGet();
                            geladenBytes.addAndGet(bild.length);
                        }
                    } catch (Exception f) {
                        // Die anderen hören nach ihrer laufenden Kachel auf.
                        fehlt.clear();
                        throw f;
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
            protokoll.close();
            schreibeIndex(zwischen, ziel, ist);
        }
        return new Ergebnis(geladen.get(), gleich, geloescht, geladenBytes.get(), gekappt.get());
    }

    private static void zeile(OutputStream protokoll, String text) throws IOException {
        // Ohne Puffer: Was write zurückgibt, hat das Betriebssystem, auch wenn das Spiel gleich endet.
        synchronized (protokoll) {
            protokoll.write((text + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void warte(Future<?> f) throws Fehler, IOException, InterruptedException {
        try {
            f.get();
        } catch (ExecutionException e) {
            switch (e.getCause()) {
                case Fehler fehler -> throw fehler;
                case IOException io -> throw io;
                case InterruptedException ie -> throw ie;
                default -> throw new IOException(e.getCause());
            }
        }
    }

    /**
     * Die Zeilen bis zur Stufe des Massstabs, höchstens {@code hoechstens}; jede verletzte Grenze
     * und jede doppelte Kachel bricht ab. Zeilen feinerer Stufen prüft es, behält sie aber nicht.
     */
    static List<Eintrag> lies(String manifest, int minZoom, int maxZoom, int stufe, long hoechstens) throws Fehler {
        List<Eintrag> eintraege = new ArrayList<>();
        Set<String> gesehen = new HashSet<>();
        for (Iterator<String> zeilen = manifest.lines().iterator(); zeilen.hasNext(); ) {
            String zeile = zeilen.next();
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
                Eintrag e = new Eintrag(z, Integer.parseInt(zxy[1]), Integer.parseInt(zxy[2]), groesse, teile[2]);
                // Über den Pfad aus ganzen Zahlen: 7/0/0 und 007/0/0 sind dieselbe Datei.
                if (!gesehen.add(e.pfad())) {
                    throw new Fehler(Grund.MANIFEST, "doppelt: " + e.pfad());
                }
                if (eintraege.size() >= hoechstens) {
                    throw new Fehler(Grund.MANIFEST, "mehr als " + hoechstens + " Kacheln");
                }
                eintraege.add(e);
            }
        }
        return eintraege;
    }

    /** Holt eine Datei unter der Adresse des Baums, höchstens {@code max} Byte, in höchstens {@link #zeit}. */
    private HttpResponse<byte[]> hole(Auftrag a, String pfad, long max, Grund grund)
            throws Fehler, IOException, InterruptedException {
        String basis = a.url().toString();
        URI uri = URI.create(basis.endsWith("/") ? basis + pfad : basis + "/" + pfad);
        HttpRequest anfrage = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + a.token())
                .timeout(zeit)
                .GET()
                .build();
        // Die Zeit der Anfrage gilt im HttpClient nur bis zu den Headern; die Frist für alles setzt get.
        CompletableFuture<HttpResponse<byte[]>> antwort = client.sendAsync(anfrage,
                info -> info.statusCode() == 200 ? hoechstens(max, grund) : HttpResponse.BodySubscribers.replacing(null));
        try {
            HttpResponse<byte[]> fertig = antwort.get(zeit.toMillis(), TimeUnit.MILLISECONDS);
            int status = fertig.statusCode();
            if (status != 200) {
                throw new Fehler(status == 401 || status == 403 || status == 429 ? Grund.ABGELEHNT : Grund.NETZ,
                        pfad + ": " + status);
            }
            return fertig;
        } catch (TimeoutException e) {
            throw new Fehler(Grund.NETZ, pfad + ": nicht fertig in " + zeit.toSeconds() + " s");
        } catch (ExecutionException e) {
            switch (e.getCause()) {
                case Fehler fehler -> throw fehler;
                case IOException io -> throw io;
                default -> throw new IOException(e.getCause());
            }
        } finally {
            // Bricht ab, was noch läuft: nach der Frist, oder wenn der Download endet.
            antwort.cancel(true);
        }
    }

    /** Sammelt den Körper; mehr als {@code max} Byte bricht mit {@code grund} ab, ohne weiterzulesen. */
    private static HttpResponse.BodySubscriber<byte[]> hoechstens(long max, Grund grund) {
        return new HttpResponse.BodySubscriber<>() {
            private final CompletableFuture<byte[]> koerper = new CompletableFuture<>();
            private final ByteArrayOutputStream aus = new ByteArrayOutputStream();
            private Flow.Subscription abo;

            @Override
            public CompletionStage<byte[]> getBody() {
                return koerper;
            }

            @Override
            public void onSubscribe(Flow.Subscription abo) {
                this.abo = abo;
                abo.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(List<ByteBuffer> teile) {
                for (ByteBuffer teil : teile) {
                    if (koerper.isDone()) {
                        return;
                    }
                    if (aus.size() + (long) teil.remaining() > max) {
                        abo.cancel();
                        koerper.completeExceptionally(new Fehler(grund, "mehr als " + max + " Byte"));
                        return;
                    }
                    byte[] stueck = new byte[teil.remaining()];
                    teil.get(stueck);
                    aus.writeBytes(stueck);
                }
            }

            @Override
            public void onError(Throwable fehler) {
                koerper.completeExceptionally(fehler);
            }

            @Override
            public void onComplete() {
                koerper.complete(aus.toByteArray());
            }
        };
    }

    /** Entpackt das Manifest, höchstens {@link #MANIFEST_MAX}, gegen gzip-Bomben. */
    static String entpacke(byte[] gz) throws Fehler, IOException {
        try (InputStream rein = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            ByteArrayOutputStream aus = new ByteArrayOutputStream();
            byte[] puffer = new byte[64 << 10];
            long summe = 0;
            for (int n; (n = rein.read(puffer)) > 0; ) {
                summe += n;
                if (summe > MANIFEST_MAX) {
                    throw new Fehler(Grund.MANIFEST, "entpackt mehr als " + MANIFEST_MAX + " Byte");
                }
                aus.write(puffer, 0, n);
            }
            return aus.toString(StandardCharsets.UTF_8);
        } catch (ZipException e) {
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

    /** Schreibt über eine Zwischendatei in {@code zwischen}, damit nie eine halbe Datei liegen bleibt. */
    private static void schreibe(Path zwischen, Path datei, byte[] inhalt) throws IOException {
        Files.createDirectories(datei.getParent());
        Path tmp = Files.createTempFile(zwischen, "datei", ".tmp");
        Files.write(tmp, inhalt);
        Files.move(tmp, datei, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Liest den Index; die letzte Zeile je Kachel gilt, eine ohne ETag heisst: neu laden. */
    static Map<String, String> liesIndex(Path ziel) throws IOException {
        Path index = ziel.resolve(INDEX);
        Map<String, String> ist = new HashMap<>();
        if (Files.exists(index)) {
            for (String zeile : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                String[] teile = zeile.split(" ");
                // Nur z/x/y aus ganzen Zahlen: Nach diesen Pfaden wird gelöscht.
                if (teile.length == 0 || !PFAD.matcher(teile[0]).matches()) {
                    continue;
                }
                if (teile.length == 1) {
                    ist.put(teile[0], "");
                } else if (teile.length == 2 && ETAG.matcher(teile[1]).matches()) {
                    ist.put(teile[0], teile[1]);
                }
            }
        }
        return ist;
    }

    /** Schreibt den Index neu, eine Zeile je Kachel. */
    private static void schreibeIndex(Path zwischen, Path ziel, Map<String, String> ist) throws IOException {
        StringBuilder text = new StringBuilder();
        ist.forEach((pfad, etag) -> text.append(pfad).append(etag.isEmpty() ? "" : " " + etag).append('\n'));
        schreibe(zwischen, ziel.resolve(INDEX), text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Ein Spieler hat je Baum nur einen Massstab; die anderen Sätze fallen weg. */
    static void behalteNur(Path baum, int massstab) throws IOException {
        for (int m : new int[] {1, 2, 4}) {
            if (m != massstab) {
                loesche(baum.resolve(String.valueOf(m)));
            }
        }
    }

    /** Löscht einen Ordner samt Inhalt; Symlinks folgt es nicht. */
    static void loesche(Path ordner) throws IOException {
        if (!Files.exists(ordner)) {
            return;
        }
        try (Stream<Path> alle = Files.walk(ordner)) {
            for (Path p : alle.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
