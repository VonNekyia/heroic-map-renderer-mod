package com.nekyia.heroicmap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

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
    /** So viele Kacheln lädt der Mod zugleich, je über eine eigene Verbindung. Siehe docs/download.md, „Ablauf“. */
    static final int VERBINDUNGEN = 8;
    /** Eine Koordinate einer Kachel: nur eine ganze Zahl, denn sie wird ein Dateiname. */
    private static final Pattern ZAHL = Pattern.compile("-?\\d{1,9}");
    private static final Pattern GROESSE = Pattern.compile("\\d{1,10}");
    private static final Pattern PFAD = Pattern.compile("-?\\d{1,9}/-?\\d{1,9}/-?\\d{1,9}");
    /** Der Name einer Kachel im Strom: nur ganze Zahlen, also nie {@code ..}, nie absolut. */
    private static final Pattern KACHEL = Pattern.compile("(-?\\d{1,9})/(-?\\d{1,9})/(-?\\d{1,9})\\.webp");
    /** {@code X-Heroic-Bytes}, die erwartete Grösse des Stroms. */
    private static final Pattern BYTES = Pattern.compile("\\d{1,15}");
    /** Ein ETag ist für den Mod undurchsichtig, aber ohne Leerzeichen und Steuerzeichen. */
    private static final Pattern ETAG = Pattern.compile("[\\x21-\\x7e]{1,256}");
    private static final String INDEX = "etags.txt";
    /** Der Ordner der Zwischendateien im Ziel; was darin liegt, ist halb. */
    private static final String ZWISCHEN = "tmp";
    /** Liegt im Ordner des Massstabs, solange ein Strom nicht fertig ist; darin der letzte abgelegte Pfad. */
    static final String STROM = "strom.txt";
    /** So viel zählt eine Datei im Strom höchstens über ihren Inhalt hinaus, für Kopf und Name. */
    static final long KOPF_MAX = 1L << 10;
    /** So oft fragt der Mod den Strom an, solange der Server 429 sagt, mit so vielen ms Pause dazwischen. */
    static final int VERSUCHE_429 = 3;
    static final long PAUSE_429_MS = 1500;
    /** Höchstens so viele Versuche hat ein Strom, auch wenn jeder etwas Neues bringt; so endet er immer. */
    static final int VERSUCHE_STROM = 100;

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
        /** Der Server lehnt das Token ab, 401 oder 403. */
        ABGELEHNT,
        /** Die Bytes dieses Tokens sind aufgebraucht, 429; dasselbe Token hilft nicht mehr. */
        BUDGET,
        /** Eine andere Antwort, eine Weiterleitung, keine Antwort in der Zeit oder ein Fehler im Netz. */
        NETZ,
        /** Das Ziel liegt im Heimnetz, der Spielserver nicht. */
        HEIMNETZ,
        /** Der Host lässt sich nicht auflösen. */
        UNBEKANNT,
        /** Der Strom verletzt eine Regel: Name, Reihenfolge, Methode oder Grösse. */
        STROM
    }

    static class Fehler extends Exception {
        final Grund grund;
        /** Der Status der Antwort, oder 0, wenn keine kam. */
        final int status;

        Fehler(Grund grund, String text) {
            this(grund, text, null);
        }

        Fehler(Grund grund, String text, Throwable ursache) {
            this(grund, 0, text, ursache);
        }

        Fehler(Grund grund, int status, String text, Throwable ursache) {
            super(grund + ": " + text, ursache);
            this.grund = grund;
            this.status = status;
        }
    }

    /** Ein Strom brach nach der Antwort 200 im Körper ab; {@link #strom} setzt dann fort. */
    static final class Abbruch extends Fehler {
        /** Endete der Körper sauber, nur ohne {@code ende}: am Deckel des Tokens oder nach einem Lesefehler des Servers. */
        final boolean sauber;

        Abbruch(boolean sauber, String text, Throwable ursache) {
            super(Grund.NETZ, text, ursache);
            this.sauber = sauber;
        }
    }

    /**
     * Bytes empfangen und erwartet, für die Anzeige auf der Vollbildkarte. Der Download zählt,
     * ein anderer Thread liest. Siehe docs/vollbildkarte.md, „Fortschritt“.
     */
    static final class Fortschritt {
        /** Über so viele ms mittelt das Tempo. */
        static final long FENSTER_MS = 5000;
        final AtomicLong empfangen = new AtomicLong();
        volatile long gesamt;
        /** Je Aufruf von {@link #tempo} die Zeit in ms und die Bytes bis dahin; nur der lesende Thread. */
        private final ArrayDeque<long[]> proben = new ArrayDeque<>();

        Fortschritt(long gesamt) {
            this.gesamt = gesamt;
        }

        /** Der Anteil von 0 bis 1. */
        double anteil() {
            long g = gesamt;
            return g <= 0 ? 0 : Math.min(1, empfangen.get() / (double) g);
        }

        /** Bytes je Sekunde über die letzten {@link #FENSTER_MS}, oder -1, solange keine Sekunde gemessen ist. */
        double tempo(long jetztMs) {
            long bytes = empfangen.get();
            proben.addLast(new long[] {jetztMs, bytes});
            while (jetztMs - proben.getFirst()[0] > FENSTER_MS) {
                proben.removeFirst();
            }
            long dauer = jetztMs - proben.getFirst()[0];
            return dauer < 1000 ? -1 : (bytes - proben.getFirst()[1]) * 1000.0 / dauer;
        }
    }

    private final HttpClient client = client();
    /** So lange darf eine Anfrage dauern, Header und Körper zusammen; beim Strom nur bis zum Header. */
    private final Duration zeit;
    /** So lange darf im Strom kein Byte kommen. */
    private final Duration leerlauf;

    Laden() {
        this(Duration.ofMinutes(2));
    }

    Laden(Duration zeit) {
        this(zeit, Duration.ofSeconds(30));
    }

    Laden(Duration zeit, Duration leerlauf) {
        this.zeit = zeit;
        this.leerlauf = leerlauf;
    }

    private static HttpClient client() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                // Kein dritter Host bekommt das Token.
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                // Ein Proxy des Systems ginge an der Prüfung der Adresse vorbei.
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
    }

    /** Lädt den Satz nach {@code ziel}: {@code map.json}, {@code etags.txt} und {@code z/x/y.webp}. */
    Ergebnis lade(Auftrag a, Path ziel) throws Fehler, IOException, InterruptedException {
        Path zwischen = ziel.resolve(ZWISCHEN);
        // Was ein beendetes Spiel halb geschrieben hat.
        loesche(zwischen);
        Files.createDirectories(zwischen);
        byte[] karte = hole(a, "map.json", KARTE_MAX, Grund.MANIFEST).body();
        int[] zoom = zoom(karte, a.massstab());
        byte[] gz = hole(a, "manifest", MANIFEST_MAX, Grund.MANIFEST).body();
        Plan plan = plane(a, karte, zoom, gz, ziel, zwischen);
        List<Eintrag> soll = plan.soll();
        Map<String, String> ist = plan.ist();
        int geloescht = plan.geloescht();
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
            ExecutorCompletionService<Void> arbeit = new ExecutorCompletionService<>(pool);
            for (int i = 0; i < VERBINDUNGEN; i++) {
                arbeit.submit(() -> {
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
                        fehlt.clear();
                        throw f;
                    }
                    return null;
                });
            }
            // In der Reihenfolge des Fertigwerdens: Der erste Fehler bricht über shutdownNow die anderen ab.
            for (int i = 0; i < VERBINDUNGEN; i++) {
                warte(arbeit.take());
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
            protokoll.close();
            schreibeIndex(zwischen, ziel, ist);
        }
        if (!gekappt.get()) {
            // Ein früherer Strom ist so auch fertig.
            Files.deleteIfExists(ziel.resolve(STROM));
        }
        return new Ergebnis(geladen.get(), gleich, geloescht, geladenBytes.get(), gekappt.get());
    }

    /** minZoom, maxZoom und die Stufe des Massstabs aus {@code map.json}. */
    private static int[] zoom(byte[] karte, int massstab) throws Fehler {
        int minZoom, maxZoom;
        try {
            JsonObject json = JsonParser.parseString(new String(karte, StandardCharsets.UTF_8)).getAsJsonObject();
            minZoom = json.get("minZoom").getAsInt();
            maxZoom = json.get("maxZoom").getAsInt();
        } catch (RuntimeException e) {
            throw new Fehler(Grund.MANIFEST, "map.json nicht lesbar");
        }
        int stufe = maxZoom - switch (massstab) {
            case 4 -> 0;
            case 2 -> 1;
            case 1 -> 2;
            default -> throw new Fehler(Grund.MANIFEST, "Massstab " + massstab);
        };
        if (stufe < minZoom) {
            throw new Fehler(Grund.MANIFEST, "keine Stufe für " + massstab + " px");
        }
        return new int[] {minZoom, maxZoom, stufe};
    }

    /** Die Kacheln laut Manifest, der Index, wie er nach dem Löschen ist, und wie viele gelöscht sind. */
    private record Plan(List<Eintrag> soll, Map<String, String> ist, int geloescht) {
    }

    /** Prüft und liest das Manifest, legt {@code map.json} ab und löscht, was nicht mehr im Manifest steht. */
    private static Plan plane(Auftrag a, byte[] karte, int[] zoom, byte[] gz, Path ziel, Path zwischen) throws Fehler, IOException {
        if (!sha256(gz).equals(a.manifestSha256().toLowerCase(Locale.ROOT))) {
            throw new Fehler(Grund.PRUEFSUMME, "manifest_sha256");
        }
        List<Eintrag> soll = lies(entpacke(gz), zoom[0], zoom[1], zoom[2], a.zeilen());
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
        return new Plan(soll, ist, geloescht);
    }

    /**
     * Lädt den Satz als einen Strom nach {@code ziel}: ein ZIP ohne Kompression mit
     * {@code map.json}, {@code manifest}, den Kacheln von grob nach fein und zuletzt {@code ende}.
     * Legt jede Datei einzeln ab; {@code stufe} bekommt jede Stufe, die ganz daliegt. Nach einem
     * Abbruch setzt der nächste Strom hinter dem letzten abgelegten Pfad fort. Gibt null, wenn der
     * Server den Strom nicht kennt (404). Siehe docs/download.md, „Strom“.
     */
    Ergebnis strom(Auftrag a, Path ziel, Fortschritt fortschritt, IntConsumer stufe) throws Fehler, IOException, InterruptedException {
        Summe summe = new Summe();
        // Nach einem Abbruch weiter, solange der letzte Versuch eine neue Datei brachte; zweimal ohne, dann Schluss.
        for (int versuch = 1, ohneNeues = 0; ; versuch++) {
            String vorher = marke(ziel);
            try {
                return einStrom(a, ziel, fortschritt, stufe, summe)
                        ? new Ergebnis(summe.geladen, summe.gleich, summe.geloescht, summe.bytes, false) : null;
            } catch (Abbruch f) {
                ohneNeues = marke(ziel).equals(vorher) ? ohneNeues + 1 : 0;
                if (ohneNeues >= 2 || versuch >= VERSUCHE_STROM) {
                    // Sauber ohne ende und ohne neue Datei: der Deckel des Tokens; dasselbe Token bringt nichts mehr.
                    throw f.sauber ? new Fehler(Grund.BUDGET, "Strom: zweimal ohne neue Datei", f) : f;
                }
            }
        }
    }

    /** Der letzte abgelegte Pfad aus {@code strom.txt}, oder leer. */
    private static String marke(Path ziel) throws IOException {
        Path marke = ziel.resolve(STROM);
        return Files.exists(marke) ? Files.readString(marke, StandardCharsets.UTF_8).strip() : "";
    }

    /** Was die Versuche eines Stroms zusammen abgelegt haben. */
    private static final class Summe {
        int geladen, gleich, geloescht;
        long bytes;
    }

    /** Ein Versuch des Stroms, hinter {@code strom.txt}; false, wenn der Server ihn nicht kennt (404). */
    private boolean einStrom(Auftrag a, Path ziel, Fortschritt fortschritt, IntConsumer stufe, Summe summe)
            throws Fehler, IOException, InterruptedException {
        pruefe(a);
        String nach = marke(ziel);
        String basis = a.url().toString().replaceAll("/+$", "");
        URI uri = URI.create(basis + ".zip" + (PFAD.matcher(nach).matches() ? "?nach=" + nach : ""));
        HttpRequest anfrage = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + a.token())
                .timeout(zeit)
                .GET()
                .build();
        for (int versuch = 1; ; versuch++) {
            if (versuch > 1) {
                Thread.sleep(PAUSE_429_MS);
            }
            // Ein eigener Client: Bleibt der Strom stehen, beendet ihn die Wache samt Verbindung.
            HttpClient strom = client();
            try {
                HttpResponse<InputStream> antwort;
                try {
                    antwort = strom.sendAsync(anfrage, HttpResponse.BodyHandlers.ofInputStream()).get(zeit.toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    throw new Fehler(Grund.NETZ, "Strom: keine Antwort in " + zeit.toSeconds() + " s");
                } catch (ExecutionException e) {
                    throw new Fehler(Grund.NETZ, "Strom", e.getCause());
                }
                try (InputStream koerper = antwort.body()) {
                    int status = antwort.statusCode();
                    if (status == 404) {
                        return false;
                    }
                    // Den Platz eines abgebrochenen Stroms gibt der Server erst frei, wenn er den Abbruch merkt.
                    if (status == 429 && versuch < VERSUCHE_429) {
                        continue;
                    }
                    if (status != 200) {
                        throw new Fehler(grund(status), status, "Strom: " + status, null);
                    }
                    // Beim Fortsetzen nennt der Header den Rest; gesamt zählt das schon Empfangene mit.
                    long schon = fortschritt.empfangen.get();
                    antwort.headers().firstValue("X-Heroic-Bytes").filter(b -> BYTES.matcher(b).matches())
                            .ifPresent(b -> fortschritt.gesamt = schon + Long.parseLong(b));
                    entpackeStrom(a, ziel, new Zaehler(koerper, fortschritt), strom, stufe, summe, schon);
                    return true;
                }
            } finally {
                strom.shutdownNow();
            }
        }
    }

    /** Liest einen Versuch des Stroms ab dem ersten Eintrag und zählt in {@code summe}; siehe {@link #strom}. */
    private void entpackeStrom(Auftrag a, Path ziel, Zaehler zaehler, HttpClient strom, IntConsumer stufe, Summe summe, long schon)
            throws Fehler, IOException, InterruptedException {
        Path zwischen = ziel.resolve(ZWISCHEN), marke = ziel.resolve(STROM);
        loesche(zwischen);
        Files.createDirectories(zwischen);
        AtomicBoolean still = new AtomicBoolean();
        Thread wache = Thread.ofPlatform().daemon().name("Heroic Map Strom").start(() -> {
            try {
                while (System.nanoTime() - zaehler.letztes <= leerlauf.toNanos()) {
                    Thread.sleep(Math.min(1000, leerlauf.toMillis()));
                }
                still.set(true);
                strom.shutdownNow();
                zaehler.close();
            } catch (InterruptedException | IOException e) {
                // Der Strom ist zu Ende.
            }
        });
        ZipInputStream zip = new ZipInputStream(zaehler);
        Plan plan = null;
        String letzte = null;
        boolean fertig = false;
        OutputStream protokoll = null;
        try {
            byte[] karte = erster(zip, "map.json", KARTE_MAX);
            int[] zoom = zoom(karte, a.massstab());
            byte[] gz = erster(zip, "manifest", MANIFEST_MAX);
            plan = plane(a, karte, zoom, gz, ziel, zwischen);
            summe.geloescht += plan.geloescht();
            Map<String, Eintrag> erwartet = new HashMap<>();
            plan.soll().forEach(e -> erwartet.put(e.pfad(), e));
            long max = a.grenze() + (plan.soll().size() + 3) * KOPF_MAX + karte.length + gz.length;
            zaehler.fortschritt.gesamt = Math.min(zaehler.fortschritt.gesamt, schon + max);
            if (!Files.exists(marke)) {
                Files.writeString(marke, "", StandardCharsets.UTF_8);
            }
            protokoll = Files.newOutputStream(ziel.resolve(INDEX), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Set<String> gesehen = new HashSet<>();
            Set<Path> ordner = new HashSet<>();
            Path tmp = zwischen.resolve("strom.tmp");
            int z = -1;
            boolean ende = false;
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (ende) {
                    throw new Fehler(Grund.STROM, "nach ende: " + e.getName());
                }
                if (e.getName().equals("ende")) {
                    inhalt(zip, e, 0, Grund.STROM);
                    ende = true;
                    continue;
                }
                Matcher m = KACHEL.matcher(e.getName());
                if (!m.matches()) {
                    throw new Fehler(Grund.STROM, "Name " + e.getName().substring(0, Math.min(e.getName().length(), 80)));
                }
                // Über den Pfad aus ganzen Zahlen, wie im Manifest: 07/0/0 ist 7/0/0.
                String pfad = Integer.parseInt(m.group(1)) + "/" + Integer.parseInt(m.group(2)) + "/" + Integer.parseInt(m.group(3));
                Eintrag soll = erwartet.get(pfad);
                if (soll == null) {
                    throw new Fehler(Grund.STROM, pfad + " nicht im Manifest bis zur Stufe");
                }
                if (!gesehen.add(pfad)) {
                    throw new Fehler(Grund.STROM, "doppelt: " + pfad);
                }
                byte[] bild = inhalt(zip, e, KACHEL_MAX, Grund.KACHEL);
                if (zaehler.anzahl > max) {
                    throw new Fehler(Grund.STROM, "mehr als " + max + " Byte");
                }
                if (soll.z() != z) {
                    if (z >= 0) {
                        stufe.accept(z);
                    }
                    z = soll.z();
                }
                Path datei = datei(ziel, pfad);
                if (soll.etag().equals(plan.ist().get(pfad)) && Files.exists(datei)) {
                    summe.gleich++;
                } else {
                    if (ordner.add(datei.getParent())) {
                        Files.createDirectories(datei.getParent());
                    }
                    // Über die Zwischendatei, ohne fsync: Nie liegt eine halbe Kachel da.
                    Files.write(tmp, bild);
                    Files.move(tmp, datei, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    // Das ETag des Manifests; ist die Kachel neuer, holt sie der nächste Abgleich.
                    zeile(protokoll, pfad + " " + soll.etag());
                    plan.ist().put(pfad, soll.etag());
                    summe.geladen++;
                    summe.bytes += bild.length;
                }
                letzte = pfad;
            }
            if (!ende) {
                throw new Abbruch(true, "Strom ohne ende", null);
            }
            if (zaehler.read() != -1) {
                throw new Fehler(Grund.STROM, "Bytes nach ende");
            }
            if (z >= 0) {
                stufe.accept(z);
            }
            fertig = true;
        } catch (ZipException | EOFException | Abriss f) {
            if (still.get()) {
                throw new Abbruch(false, "Strom: " + leerlauf.toSeconds() + " s ohne Byte", f);
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            throw new Abbruch(false, "Strom", f);
        } catch (IllegalArgumentException f) {
            // ZipInputStream: ein Name, der kein UTF-8 ist.
            throw new Fehler(Grund.STROM, "Name nicht lesbar", f);
        } finally {
            wache.interrupt();
            if (protokoll != null) {
                protokoll.close();
            }
            if (plan != null) {
                schreibeIndex(zwischen, ziel, plan.ist());
                if (fertig) {
                    Files.deleteIfExists(marke);
                } else if (letzte != null) {
                    Files.writeString(marke, letzte, StandardCharsets.UTF_8);
                }
            }
        }
    }

    /** Der nächste Eintrag des Stroms, der {@code name} heissen muss, und sein Inhalt. */
    private static byte[] erster(ZipInputStream zip, String name, long max) throws Fehler, IOException {
        ZipEntry e = zip.getNextEntry();
        if (e == null || !e.getName().equals(name)) {
            throw new Fehler(Grund.STROM, name + " fehlt am Anfang");
        }
        return inhalt(zip, e, max, Grund.MANIFEST);
    }

    /** Der Inhalt eines Eintrags: nur ohne Kompression und mit der Grösse im Kopf, höchstens {@code max} Byte. */
    private static byte[] inhalt(ZipInputStream zip, ZipEntry e, long max, Grund grund) throws Fehler, IOException {
        if (e.getMethod() != ZipEntry.STORED || e.getSize() < 0) {
            throw new Fehler(Grund.STROM, e.getName() + ": gepackt oder ohne Grösse");
        }
        if (e.getSize() > max) {
            throw new Fehler(grund, e.getName() + " hat " + e.getSize() + " Byte");
        }
        byte[] inhalt = zip.readNBytes((int) e.getSize());
        if (inhalt.length != e.getSize()) {
            throw new EOFException(e.getName());
        }
        return inhalt;
    }

    /**
     * Zählt die Bytes des Körpers für Fortschritt, Grenze und Wache. Ein Fehler darunter wird
     * ein {@link Abriss}, damit er sich von Fehlern der Platte trennen lässt.
     */
    private static final class Zaehler extends FilterInputStream {
        final Fortschritt fortschritt;
        /** System.nanoTime des letzten Bytes, oder des Anfangs. */
        volatile long letztes = System.nanoTime();
        long anzahl;

        Zaehler(InputStream koerper, Fortschritt fortschritt) {
            super(koerper);
            this.fortschritt = fortschritt;
        }

        @Override
        public int read() throws IOException {
            int b;
            try {
                b = super.read();
            } catch (IOException e) {
                throw new Abriss(e);
            }
            if (b >= 0) {
                zaehle(1);
            }
            return b;
        }

        @Override
        public int read(byte[] puffer, int ab, int laenge) throws IOException {
            int n;
            try {
                n = super.read(puffer, ab, laenge);
            } catch (IOException e) {
                throw new Abriss(e);
            }
            if (n > 0) {
                zaehle(n);
            }
            return n;
        }

        private void zaehle(int n) {
            anzahl += n;
            fortschritt.empfangen.addAndGet(n);
            letztes = System.nanoTime();
        }
    }

    /** Ein Fehler beim Lesen des Stroms aus dem Netz. */
    private static final class Abriss extends IOException {
        Abriss(IOException ursache) {
            super(ursache);
        }
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

    /**
     * Holt eine Datei unter der Adresse des Baums, höchstens {@code max} Byte, in höchstens
     * {@link #zeit}. Prüft vorher die Adresse, denn jede neue Verbindung löst den Namen neu auf.
     */
    private HttpResponse<byte[]> hole(Auftrag a, String pfad, long max, Grund grund) throws Fehler, InterruptedException {
        pruefe(a);
        String basis = a.url().toString();
        URI uri = URI.create(basis.endsWith("/") ? basis + pfad : basis + "/" + pfad);
        HttpRequest anfrage = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + a.token())
                .timeout(zeit)
                .GET()
                .build();
        HttpResponse<byte[]> fertig = sende(client, anfrage, max, grund, zeit, pfad);
        int status = fertig.statusCode();
        if (status != 200) {
            throw new Fehler(grund(status), status, pfad + ": " + status, null);
        }
        return fertig;
    }

    /** Warum eine Antwort mit diesem Status, nicht 200, scheitert. */
    private static Grund grund(int status) {
        return switch (status) {
            case 401, 403 -> Grund.ABGELEHNT;
            case 429 -> Grund.BUDGET;
            default -> Grund.NETZ;
        };
    }

    /** Prüft die Adresse vor jeder Verbindung, denn jede löst den Namen neu auf. */
    private static void pruefe(Auftrag a) throws Fehler {
        switch (Adresse.pruefe(a.url(), a.spielserver())) {
            case SCHEMA -> throw new Fehler(Grund.NETZ, "Adresse");
            case HEIMNETZ -> throw new Fehler(Grund.HEIMNETZ, a.url().getHost());
            case UNBEKANNT -> throw new Fehler(Grund.UNBEKANNT, a.url().getHost());
            case GUT -> {
            }
        }
    }

    /**
     * Schickt {@code anfrage} und sammelt den Körper einer Antwort 200, höchstens {@code max} Byte,
     * darüber {@code grund}. Header und Körper zusammen dauern höchstens {@code zeit}; danach bricht
     * ab, was noch läuft. Eine andere Antwort kommt mit ihrem Status und ohne Körper. Für Kacheln und
     * Symbole; {@code was} steht in den Fehlern.
     */
    static HttpResponse<byte[]> sende(HttpClient client, HttpRequest anfrage, long max, Grund grund, Duration zeit, String was)
            throws Fehler, InterruptedException {
        // Die Zeit der Anfrage gilt nicht in jeder JDK-Version auch für den Körper; die Frist für alles setzt get.
        CompletableFuture<HttpResponse<byte[]>> antwort = client.sendAsync(anfrage,
                info -> info.statusCode() == 200 ? hoechstens(max, grund) : HttpResponse.BodySubscribers.replacing(null));
        try {
            return antwort.get(zeit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new Fehler(Grund.NETZ, was + ": nicht fertig in " + zeit.toSeconds() + " s");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Fehler fehler) {
                throw fehler;
            }
            // Verbindung, Frist des HttpClient, abgerissener Körper: alles Netz; die Ursache bleibt fürs Log.
            throw new Fehler(Grund.NETZ, was, e.getCause());
        } finally {
            // Bricht ab, was noch läuft: nach der Frist, oder wenn der Abruf endet.
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

    /** Gibt es im Ziel einen Stand zum Fortsetzen? Den gibt es, sobald eine Kachel im Index steht. */
    static boolean hatStand(Path ziel) {
        return Files.exists(ziel.resolve(INDEX));
    }

    /** Ist im Ziel ein Strom nicht fertig? Dann ist der Satz unvollständig, auch wenn die Karte ihn schon zeigt. */
    static boolean imStrom(Path ziel) {
        return Files.exists(ziel.resolve(STROM));
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

    /**
     * Löscht {@code <wurzel>/<server>/<baum>/overlay}, den Ordner der früheren Live-Ebene, in jedem
     * Baum. Was bleibt, geht beim nächsten Start. Siehe docs/download.md, „Ablage“.
     */
    static void loescheOverlays(Path wurzel) throws IOException {
        if (!Files.isDirectory(wurzel)) {
            return;
        }
        try (Stream<Path> server = Files.list(wurzel)) {
            for (Path s : server.filter(Files::isDirectory).toList()) {
                try (Stream<Path> baeume = Files.list(s)) {
                    for (Path baum : baeume.toList()) {
                        loesche(baum.resolve("overlay"));
                    }
                }
            }
        }
    }

    /** Ein Baum auf der Platte: sein Ordner, der Pfad unter {@code heroicmap/}, der Satz oder null, solange er unvollständig ist, und seine Bytes. */
    record AufPlatte(Path ordner, String pfad, Satz satz, long bytes) {
    }

    /** Die Bäume und die Bytes aller Dateien unter {@code heroicmap/}. Siehe docs/download.md, „Kartenliste“. */
    record Bestand(List<AufPlatte> karten, long bytes) {
    }

    /**
     * Zählt den Bestand unter {@code wurzel} in einem Durchlauf. Ein Baum ist ein Ordner mit
     * {@code massstab.txt} oder {@code satz.json}, bis zu drei Ebenen tief: {@code <welt>/<baum>},
     * {@code <server>/<welt>/<baum>} und {@code <server>/<baum>} aus der Ablage vor dem Hash des
     * Seeds. Was sich nicht lesen lässt, fällt weg; einer Verbindung folgt er nicht. Gibt
     * {@code abbruch} wahr, endet er und gibt null.
     */
    static Bestand bestand(Path wurzel, BooleanSupplier abbruch) throws IOException {
        if (!Files.isDirectory(wurzel)) {
            return new Bestand(List.of(), 0);
        }
        // Ist heroicmap/ selbst eine Verbindung, etwa auf ein anderes Laufwerk, läuft der Durchlauf über ihr Ziel;
        // die Pfade rechnet er auf die Wurzel zurück, wie Downloads sie kennt.
        Path echt = wurzel.toRealPath();
        // Bytes je Ordner der ersten drei Ebenen; ein Baum liegt höchstens dort.
        Map<Path, long[]> jeOrdner = new HashMap<>();
        Set<Path> baeume = new HashSet<>();
        long[] summe = {0};
        Files.walkFileTree(echt, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path ordner, BasicFileAttributes a) throws IOException {
                if (abbruch.getAsBoolean()) {
                    return FileVisitResult.TERMINATE;
                }
                return verbindung(echt, echt, ordner, a) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path datei, BasicFileAttributes a) {
                if (a.isSymbolicLink() || a.isOther()) {
                    // Ein Link zählt nicht, auch nicht, worauf er zeigt.
                    return FileVisitResult.CONTINUE;
                }
                Path relativ = echt.relativize(datei);
                String name = datei.getFileName().toString();
                if (relativ.getNameCount() >= 2 && relativ.getNameCount() <= 4 && (name.equals("massstab.txt") || name.equals("satz.json"))) {
                    baeume.add(wurzel.resolve(relativ.getParent().toString()));
                }
                summe[0] += a.size();
                for (int i = 1; i < relativ.getNameCount() && i <= 3; i++) {
                    jeOrdner.computeIfAbsent(wurzel.resolve(relativ.subpath(0, i).toString()), k -> new long[1])[0] += a.size();
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path datei, IOException e) {
                // Unlesbar, oder verschwindet gerade, etwa in tmp/: zählt nicht.
                return FileVisitResult.CONTINUE;
            }
        });
        if (abbruch.getAsBoolean()) {
            return null;
        }
        List<AufPlatte> karten = new ArrayList<>();
        for (Path ordner : new TreeSet<>(baeume)) {
            long[] bytes = jeOrdner.getOrDefault(ordner, new long[1]);
            karten.add(new AufPlatte(ordner, wurzel.relativize(ordner).toString().replace('\\', '/'), Satz.lies(ordner), bytes[0]));
        }
        return new Bestand(List.copyOf(karten), summe[0]);
    }

    /**
     * Ist der Ordner unter {@code wurzel} eine Verbindung, ein Symlink oder eine Junction? Eine
     * Junction ist für das JDK unter Windows ein Ordner, kein Link; erkennbar daran, dass ihr echter
     * Pfad nicht dort liegt, wo der Durchlauf ist. {@code echt} ist der echte Pfad der Wurzel.
     */
    static boolean verbindung(Path wurzel, Path echt, Path ordner, BasicFileAttributes a) throws IOException {
        if (ordner.equals(wurzel)) {
            return false;
        }
        return a.isSymbolicLink() || a.isOther() || !ordner.toRealPath().equals(echt.resolve(wurzel.relativize(ordner)));
    }

    /** Verschiebt {@code alt} nach {@code neu}, wenn es {@code alt} gibt und {@code neu} noch nicht. Siehe docs/download.md, „Ablage“. */
    static boolean zieheUm(Path alt, Path neu) throws IOException {
        if (alt.equals(neu) || !Files.exists(alt) || Files.exists(neu)) {
            return false;
        }
        Files.createDirectories(neu.getParent());
        Files.move(alt, neu);
        return true;
    }

    /** Wie {@link #zieheUm}, für einen Baum: Ein übriger Ordner {@code overlay/} der früheren Live-Ebene zieht nicht mit. */
    static boolean zieheBaumUm(Path alt, Path neu) throws IOException {
        if (alt.equals(neu) || !Files.exists(alt) || Files.exists(neu)) {
            return false;
        }
        loesche(alt.resolve("overlay"));
        return zieheUm(alt, neu);
    }

    /**
     * Löscht einen Ordner samt Inhalt. Einer Verbindung, Symlink oder Junction, folgt es nicht; es
     * entfernt nur sie, nie, worauf sie zeigt.
     */
    static void loesche(Path ordner) throws IOException {
        if (!Files.exists(ordner, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path eltern = ordner.toAbsolutePath().getParent(), echt = ordner.toRealPath();
        if (eltern != null && !echt.equals(eltern.toRealPath().resolve(ordner.getFileName()))) {
            // Der Ordner selbst ist eine Verbindung: nur sie geht.
            Files.delete(ordner);
            return;
        }
        Files.walkFileTree(ordner, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                if (verbindung(ordner, echt, dir, a)) {
                    Files.delete(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path datei, BasicFileAttributes a) throws IOException {
                Files.delete(datei);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
