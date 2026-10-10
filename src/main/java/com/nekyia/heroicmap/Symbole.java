package com.nekyia.heroicmap;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Die Symbole der Nadeln und die Bilder der Banner vom Server des Renderers: geholt, wenn eine
 * Nadel oder ein Banner sie zum ersten Mal zeichnet, ohne Token, mit denselben harten Grenzen wie der
 * Download der Karte; Symbole genau 16 × 16 oder 9 × 9 Pixel, Banner höchstens 32 × 64. Ohne
 * Adresse oder nach einem Fehler bleibt das Schild leer, das Banner fehlt. Die Zustände gehören dem
 * Render-Thread, geholt wird in einem eigenen. Siehe docs/ebenen.md, „Symbole“.
 */
final class Symbole {

    /** Höchstens so gross ist ein Bild einer Ebene; ein Symbol ist viel kleiner. */
    static final int MAX = 256 << 10;
    /** Höchstens so viele Bilder hat eine Ebene, siehe das Format; mehr Symbole holt der Mod nicht. */
    static final int MAX_BILDER = 200;
    /** So viele Bilder über alle Ebenen, je bis 8 KiB im Speicher und auf der Grafikkarte. Siehe docs/ebenen.md, „Grenzen“. */
    static final int MAX_BILDER_GESAMT = 1000;
    /** So lange darf ein Abruf dauern, Header und Körper zusammen. */
    static final Duration FRIST = Duration.ofSeconds(10);
    /** Das Bild eines Banners ist höchstens so gross, wie im Format. */
    static final int BANNER_BREITE = 32, BANNER_HOEHE = 64;
    /** Ein Feld wie {@code images/burg_16.png}: ohne Unterordner, ohne Punkt vorn, nur PNG und WebP. */
    static final Pattern FELD = Pattern.compile("images/[a-z0-9_-][a-z0-9_.-]{0,63}\\.(png|webp)");
    private static final Pattern MODNAME = Pattern.compile("[a-z0-9_-][a-z0-9_.-]{0,63}");
    private static final Logger LOGGER = LoggerFactory.getLogger(HeroicMap.ID);
    private static final AtomicInteger ZAEHLER = new AtomicInteger();

    /** Wie beim Download: ohne Weiterleitung, denn sie ginge an der Prüfung der Adresse vorbei, und ohne Proxy. */
    static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(FRIST)
            .proxy(HttpClient.Builder.NO_PROXY)
            .build();

    static final Symbole INSTANZ = new Symbole(CLIENT, Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "heroicmap-symbole");
        t.setDaemon(true);
        return t;
    }), r -> Minecraft.getInstance().execute(r), Symbole::lege, t -> Minecraft.getInstance().getTextureManager().release(t));

    /** Die Symbole einer Ebene in einer {@code version}: je Feld und Seite die Textur, null solange sie lädt oder nach einem Fehler. */
    private static final class Felder {

        final String version;
        final Map<String, Textur> texturen = new HashMap<>();
        boolean voll;
        /** Gesetzt, sobald die Felder frei sind; ein wartender Auftrag für sie fragt dann nicht mehr. */
        volatile boolean frei;

        Felder(String version) {
            this.version = version;
        }
    }

    private final HttpClient client;
    private final ExecutorService holer;
    private final Executor renderThread;
    private final BiFunction<URI, Kacheln.Bild, Identifier> ablage;
    private final Consumer<Identifier> freigabe;
    private final Map<String, Felder> ebenen = new HashMap<>();
    /** Zählt bei jedem Leeren weiter; ein Auftrag aus einer älteren Runde fragt nicht mehr und legt nichts ab. */
    private final AtomicInteger runde = new AtomicInteger();
    /** Die Wurzel der Kacheln am Server, aus der Liste, und die Verbindung zum Spielserver; null ohne Adresse. */
    private URI basis;
    private InetAddress spielserver;
    /** Gesetzt, sobald das Log einmal sagte, dass über alle Ebenen kein Bild mehr dazukommt; bis zum Leeren. */
    private boolean voll;

    Symbole(HttpClient client, ExecutorService holer, Executor renderThread, BiFunction<URI, Kacheln.Bild, Identifier> ablage,
            Consumer<Identifier> freigabe) {
        this.client = client;
        this.holer = holer;
        this.renderThread = renderThread;
        this.ablage = ablage;
        this.freigabe = freigabe;
    }

    /** Die Adresse aus einer gültigen Liste {@code ebenen}: {@code url}, sonst {@code port} an der IP der Verbindung. */
    void basis(JsonObject liste, InetAddress verbindung) {
        URI neu;
        try {
            neu = basis(liste.has("url") ? liste.get("url").getAsString() : null,
                    liste.has("port") ? liste.get("port").getAsInt() : 0, verbindung);
        } catch (RuntimeException e) {
            neu = null;
        }
        if (!Objects.equals(neu, basis)) {
            leeren();
            basis = neu;
        }
        spielserver = verbindung;
    }

    /**
     * {@code url} wie sie ist, oder {@code http://<ip>:<port>/tiles} mit der IP der Verbindung, IPv6 in
     * eckigen Klammern, wie bei der {@code freigabe}; null, wenn beides fehlt oder nicht taugt.
     */
    static URI basis(String url, int port, InetAddress verbindung) {
        try {
            if (url != null) {
                URI u = URI.create(url);
                return Adresse.form(u) ? u : null;
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (verbindung == null || port < 1 || port > 65535) {
            return null;
        }
        String ip = verbindung.getHostAddress();
        return URI.create("http://" + (verbindung instanceof Inet6Address ? "[" + ip + "]" : ip) + ":" + port + "/tiles");
    }

    /** Wo das Bild {@code feld} der Ebene {@code ebene} liegt: {@code <basis>/layers/<modname>/<feld>}; null, wenn ein Teil nicht taugt. */
    static URI uri(URI basis, String ebene, String feld) {
        int doppelpunkt = ebene.indexOf(':');
        String modname = doppelpunkt < 0 ? "" : ebene.substring(0, doppelpunkt);
        if (basis == null || feld == null || !FELD.matcher(feld).matches() || !MODNAME.matcher(modname).matches()) {
            return null;
        }
        String b = basis.toString();
        return URI.create((b.endsWith("/") ? b : b + "/") + "layers/" + modname + "/" + feld);
    }

    /**
     * Die Textur des Symbols {@code feld} der Ebene in dieser {@code version} und Seite, oder null,
     * solange es lädt, ohne Adresse, nach einem Fehler und über {@link #MAX_BILDER} je Ebene. Beim
     * ersten Fragen holt es der eigene Thread. Eine neue {@code version} gibt alle Symbole der Ebene
     * frei, denn unter gleichem Namen kann ein Bild neu sein.
     */
    Identifier symbol(String ebene, String version, String feld, int seite) {
        Textur t = textur(ebene, version, feld, feld + "@" + seite, seite, seite, false);
        return t == null ? null : t.id();
    }

    /** Eine Textur mit der Grösse ihres Bilds. */
    record Textur(Identifier id, int breite, int hoehe) {
    }

    /** Ein Bild einer Tafel, höchstens {@link Tafel#MAX_BILD} im Quadrat; sonst wie {@link #symbol}. */
    Textur tafelBild(String ebene, String version, String feld) {
        return textur(ebene, version, feld, feld + "@tafel", Tafel.MAX_BILD, Tafel.MAX_BILD, true);
    }

    /** Das Bild eines Banners, höchstens {@link #BANNER_BREITE} × {@link #BANNER_HOEHE}; sonst wie {@link #symbol}. */
    Textur banner(String ebene, String version, String feld) {
        return textur(ebene, version, feld, feld + "@banner", BANNER_BREITE, BANNER_HOEHE, true);
    }

    private Textur textur(String ebene, String version, String feld, String schluessel, int breite, int hoehe, boolean hoechstens) {
        if (feld == null) {
            return null;
        }
        Felder f = ebenen.get(ebene);
        if (f == null || !f.version.equals(version)) {
            if (f != null) {
                gibFrei(f);
            }
            f = new Felder(version);
            ebenen.put(ebene, f);
        }
        if (f.texturen.containsKey(schluessel)) {
            return f.texturen.get(schluessel);
        }
        if (f.texturen.size() >= MAX_BILDER) {
            if (!f.voll) {
                f.voll = true;
                LOGGER.warn("Heroic Map: Ebene {} nennt mehr als {} Bilder, die übrigen fehlen", ebene, MAX_BILDER);
            }
            return null;
        }
        if (ebenen.values().stream().mapToInt(e -> e.texturen.size()).sum() >= MAX_BILDER_GESAMT) {
            if (!voll) {
                voll = true;
                LOGGER.warn("Heroic Map: mehr als {} Bilder über alle Ebenen, die übrigen fehlen", MAX_BILDER_GESAMT);
            }
            return null;
        }
        f.texturen.put(schluessel, null);
        URI uri = uri(basis, ebene, feld);
        if (uri == null) {
            return null;
        }
        int r = runde.get();
        InetAddress server = spielserver;
        Felder ziel = f;
        holer.execute(() -> {
            // Nach dem Trennen, mit einer neuen Adresse, einer neuen version oder ohne die Ebene fragt ein alter Auftrag nicht mehr.
            if (r != runde.get() || ziel.frei) {
                return;
            }
            Kacheln.Bild bild = hole(client, uri, server, breite, hoehe, hoechstens, FRIST);
            if (bild != null) {
                renderThread.execute(() -> {
                    if (r == runde.get() && !ziel.frei && ebenen.get(ebene) == ziel && ziel.texturen.containsKey(schluessel)) {
                        ziel.texturen.put(schluessel, new Textur(ablage.apply(uri, bild), bild.breite(), bild.hoehe()));
                    }
                });
            }
        });
        return null;
    }

    /** Legt ein geholtes Bild als Textur ab. */
    private static Identifier lege(URI uri, Kacheln.Bild bild) {
        NativeImage pixel = Kacheln.pixel(bild);
        Identifier id = Identifier.fromNamespaceAndPath(HeroicMap.ID, "ebenen/symbol_" + ZAEHLER.getAndIncrement());
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "heroicmap " + uri, pixel));
        return id;
    }

    /**
     * Holt ein Symbol: prüft die Adresse, ohne Weiterleitung, höchstens {@link #MAX} Byte, alles in
     * höchstens {@code frist} ({@link Laden#sende}); PNG oder einfache WebP mit nur {@code VP8L}, genau
     * {@code seite} × {@code seite}. Null bei jedem Fehler; das Log nennt ihn.
     */
    static Kacheln.Bild hole(HttpClient client, URI uri, InetAddress spielserver, int seite, Duration frist) {
        return hole(client, uri, spielserver, seite, seite, false, frist);
    }

    /** Wie {@link #hole(HttpClient, URI, InetAddress, int, Duration)}, genau {@code breite} × {@code hoehe} oder mit {@code hoechstens} bis dahin. */
    static Kacheln.Bild hole(HttpClient client, URI uri, InetAddress spielserver, int breite, int hoehe, boolean hoechstens, Duration frist) {
        try {
            Adresse.Urteil urteil = Adresse.pruefe(uri, spielserver);
            if (urteil != Adresse.Urteil.GUT) {
                throw new IOException(urteil.name());
            }
            HttpRequest anfrage = HttpRequest.newBuilder(uri).timeout(frist).GET().build();
            HttpResponse<byte[]> antwort = Laden.sende(client, anfrage, MAX, Laden.Grund.NETZ, frist, uri.getPath());
            if (antwort.statusCode() != 200) {
                throw new IOException("HTTP " + antwort.statusCode());
            }
            Kacheln.Bild bild = uri.getPath().endsWith(".png") ? Kacheln.png(antwort.body(), breite, hoehe, hoechstens)
                    : Kacheln.vp8l(antwort.body(), breite, hoehe, hoechstens);
            if (bild == null) {
                throw new IOException("keine einfache WebP mit nur VP8L");
            }
            return bild;
        } catch (IOException | Laden.Fehler | RuntimeException e) {
            LOGGER.warn("Heroic Map: Bild {} nicht geladen: {}", uri, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /** Gibt die Symbole jeder Ebene frei, die die Liste nicht mehr nennt. */
    void behalte(Collection<String> kennungen) {
        ebenen.entrySet().removeIf(e -> {
            boolean weg = !kennungen.contains(e.getKey());
            if (weg) {
                gibFrei(e.getValue());
            }
            return weg;
        });
    }

    private void gibFrei(Felder f) {
        f.frei = true;
        f.texturen.values().stream().filter(Objects::nonNull).map(Textur::id).forEach(freigabe);
    }

    /** Beim Trennen und mit einer neuen Adresse: alle Texturen frei, alte Aufträge fragen nicht mehr. */
    void leeren() {
        runde.incrementAndGet();
        voll = false;
        ebenen.values().forEach(this::gibFrei);
        ebenen.clear();
    }

    /** Für Tests: wartet, bis der eigene Thread alles Eingereihte abgearbeitet hat. */
    void warte() throws InterruptedException, ExecutionException {
        holer.submit(() -> { }).get();
    }
}
