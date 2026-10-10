package com.nekyia.heroicmap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
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
    /**
     * Die Bilder der Tafeln haben ein eigenes Budget: höchstens so viele Byte an Pixeln und so viele
     * Einträge; darüber gibt der Mod die am längsten nicht gezeigten frei. Siehe docs/ebenen.md, „Grenzen“.
     */
    static final long TAFELBILDER_BYTE = 16L << 20;
    static final int MAX_TAFELBILDER = 1024;
    /** So lange darf ein Abruf dauern, Header und Körper zusammen. */
    static final Duration FRIST = Duration.ofSeconds(10);
    /** Das Bild eines Banners ist höchstens so gross, wie im Format. */
    static final int BANNER_BREITE = 32, BANNER_HOEHE = 64;
    /** Ein Feld wie {@code images/burg_16.png}: ohne Unterordner, ohne Punkt vorn, nur PNG und WebP. */
    static final Pattern FELD = Pattern.compile("images/[a-z0-9_-][a-z0-9_.-]{0,63}\\.(png|webp)");
    private static final Pattern MODNAME = Pattern.compile("[a-z0-9_-][a-z0-9_.-]{0,63}");
    /** Ein Teil einer Kennung, auch der Name eines Entwurfs: 1 bis 64 Zeichen, nicht mit Punkt vorn oder hinten, kein Gerät von Windows. */
    private static final Pattern TEIL = Pattern.compile("[a-z0-9_-]([a-z0-9_.-]{0,62}[a-z0-9_-])?");
    private static final Pattern GERAET = Pattern.compile("(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?");
    /** So gross ist {@code satz.json} der Banner höchstens; heute rund 40 Byte. */
    static final int MAX_SATZ = 4 << 10;
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
    }), r -> Minecraft.getInstance().execute(r), Symbole::lege, t -> Minecraft.getInstance().getTextureManager().release(t),
            () -> System.nanoTime() / 1_000_000);
    /**
     * Ein Symbol oder Banner, das nicht kam, holt der Mod neu: nach 1, nach 5, dann alle 15 Minuten, je
     * Schlüssel und {@code version}, ohne Deckel; so kommt ein Bild, das der Server erst nach der Ebene
     * schreibt oder das ein Proxy erst später durchreicht, ohne neues Login. Entschieden vom Reviewer
     * (mod#61). Siehe docs/ebenen.md, „Symbole“.
     */
    static final long[] WARTEN_MS = {60_000, 300_000, 900_000};

    /** Wie lange nach dem {@code fehlschlaege}-ten Fehlschlag der nächste Versuch wartet. */
    static long warten(long fehlschlaege) {
        return WARTEN_MS[(int) Math.min(fehlschlaege, WARTEN_MS.length) - 1];
    }

    /** Die Symbole einer Ebene in einer {@code version}: je Feld und Seite die Textur, null solange sie lädt oder nach einem Fehler. */
    private static final class Felder {

        final String version;
        final Map<String, Textur> texturen = new HashMap<>();
        /** Je Schlüssel, der nicht kam: {wann zuletzt gescheitert, oder Long.MAX_VALUE, solange ein Versuch läuft; wie oft}. */
        final Map<String, long[]> versuche = new HashMap<>();
        boolean voll;
        /** Gesetzt, sobald die Felder frei sind; ein wartender Auftrag für sie fragt dann nicht mehr. */
        volatile boolean frei;
        /** {@code satz.json} der Banner im Satz oben: der Satz, sobald er da ist; ob ein Abruf läuft; die Fehlschläge und wann zuletzt. */
        Spritesatz satz;
        boolean satzLaeuft, fussGewarnt;
        long satzFehlZeit;
        int satzFehler;

        Felder(String version) {
            this.version = version;
        }
    }

    private final HttpClient client;
    private final ExecutorService holer;
    private final Executor renderThread;
    private final BiFunction<URI, Kacheln.Bild, Identifier> ablage;
    private final Consumer<Identifier> freigabe;
    private final LongSupplier uhr;
    private final Map<String, Felder> ebenen = new HashMap<>();
    /** Zählt bei jedem Leeren weiter; ein Auftrag aus einer älteren Runde fragt nicht mehr und legt nichts ab. */
    private final AtomicInteger runde = new AtomicInteger();
    /** Die Wurzel der Kacheln am Server, aus der Liste, und die Verbindung zum Spielserver; null ohne Adresse. */
    private URI basis;
    private InetAddress spielserver;
    /** Gesetzt, sobald das Log einmal sagte, dass über alle Ebenen kein Bild mehr dazukommt; bis zum Leeren. */
    private boolean voll;
    /** Zählt jedes angekommene Symbol und Banner; die Vollbildkarte sucht ihr Ziel danach neu. */
    private int stand;
    /** Ein Bild einer Tafel in der Grösse, in der die Tafel es zeigt. */
    private record Tafelbild(String ebene, String version, String feld, int breite, int hoehe) {
    }

    /** Die Bilder der Tafeln, das zuletzt gezeigte hinten; null, solange es lädt oder wenn es fehlt. */
    private final LinkedHashMap<Tafelbild, Textur> tafelbilder = new LinkedHashMap<>(16, 0.75f, true);
    private long tafelbilderByte;

    Symbole(HttpClient client, ExecutorService holer, Executor renderThread, BiFunction<URI, Kacheln.Bild, Identifier> ablage,
            Consumer<Identifier> freigabe, LongSupplier uhr) {
        this.client = client;
        this.holer = holer;
        this.renderThread = renderThread;
        this.ablage = ablage;
        this.freigabe = freigabe;
        this.uhr = uhr;
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

    /** Der Satz {@code oben} der Banner einer Ebene: der Fuss im Sprite, auf den Kanten der Pixel, und der Winkel der Unterkante, oben 0. */
    record Spritesatz(int fussX, int fussY, double winkel) {
    }

    /** Ein Sprite eines Banners und sein Fuss in Pixeln des Sprites. */
    record Sprite(Textur textur, int fussX, int fussY) {
    }

    /** Ist {@code s} ein Teil einer Kennung, siehe das Format, „Kennung“? */
    static boolean teil(String s) {
        return s != null && TEIL.matcher(s).matches() && !GERAET.matcher(s).matches();
    }

    /**
     * Wo das Sprite eines Entwurfs im Satz {@code oben} liegt: {@code <basis>/layers/<modname>/banner/<teil>/oben/<entwurf>.png},
     * mit Krone unter {@code oben/krone/}; null, wenn ein Teil nicht taugt. Siehe docs/ebenen.md, „Banner“.
     */
    static URI spriteUri(URI basis, String ebene, String entwurf, boolean krone) {
        return teil(entwurf) ? bannerUri(basis, ebene, (krone ? "krone/" : "") + entwurf + ".png") : null;
    }

    /** Wo {@code satz.json} des Satzes {@code oben} der Ebene liegt; null, wenn ein Teil nicht taugt. */
    static URI satzUri(URI basis, String ebene) {
        return bannerUri(basis, ebene, "satz.json");
    }

    private static URI bannerUri(URI basis, String ebene, String datei) {
        int doppelpunkt = ebene.indexOf(':');
        if (basis == null || doppelpunkt < 0 || !MODNAME.matcher(ebene.substring(0, doppelpunkt)).matches()
                || !teil(ebene.substring(doppelpunkt + 1))) {
            return null;
        }
        String b = basis.toString();
        return URI.create((b.endsWith("/") ? b : b + "/") + "layers/" + ebene.substring(0, doppelpunkt) + "/banner/"
                + ebene.substring(doppelpunkt + 1) + "/oben/" + datei);
    }

    /**
     * Das Sprite des Entwurfs {@code entwurf} der Ebene im Satz {@code oben}, mit oder ohne Krone, und sein Fuss aus
     * {@code satz.json}; null, solange eins von beiden lädt, ohne Adresse, nach einem Fehler oder wenn der Fuss nicht auf
     * der Leinwand liegt. Dann zeichnet die Ansicht {@code image}. Holt beides wie die Bilder, mit ihren Neuversuchen
     * und gegen ihr Budget. Siehe docs/ebenen.md, „Banner“.
     */
    Sprite sprite(String ebene, String version, String entwurf, boolean krone) {
        URI uri = spriteUri(basis, ebene, entwurf, krone);
        if (uri == null) {
            return null;
        }
        Felder f = felder(ebene, version);
        Spritesatz satz = satz(f, ebene);
        Textur t = textur(f, ebene, uri, "banner/oben/" + (krone ? "krone/" : "") + entwurf, BANNER_BREITE, BANNER_HOEHE, true);
        if (satz == null || t == null) {
            return null;
        }
        if (satz.fussX() > t.breite() || satz.fussY() > t.hoehe()) {
            if (!f.fussGewarnt) {
                f.fussGewarnt = true;
                LOGGER.warn("Heroic Map: Der Fuss {},{} aus satz.json der Ebene {} liegt nicht auf dem Sprite {} × {}; die Banner nehmen ihr Bild",
                        satz.fussX(), satz.fussY(), ebene, t.breite(), t.hoehe());
            }
            return null;
        }
        return new Sprite(t, satz.fussX(), satz.fussY());
    }

    /** {@code satz.json} der Ebene in den Felder ihrer version, oder null, solange er lädt oder fehlt; holt ihn beim ersten Fragen. */
    private Spritesatz satz(Felder f, String ebene) {
        if (f.satz != null || f.satzLaeuft || f.satzFehler > 0 && uhr.getAsLong() - f.satzFehlZeit < warten(f.satzFehler)) {
            return f.satz;
        }
        URI uri = satzUri(basis, ebene);
        if (uri == null) {
            return null;
        }
        f.satzLaeuft = true;
        int r = runde.get();
        InetAddress server = spielserver;
        boolean warnen = f.satzFehler == 0;
        holer.execute(() -> {
            if (r != runde.get() || f.frei) {
                return;
            }
            Spritesatz neu = holeSatz(client, uri, server, FRIST, warnen);
            renderThread.execute(() -> {
                if (r != runde.get() || f.frei || ebenen.get(ebene) != f) {
                    return;
                }
                f.satzLaeuft = false;
                if (neu != null) {
                    f.satz = neu;
                    stand++;
                } else {
                    f.satzFehler++;
                    f.satzFehlZeit = uhr.getAsLong();
                }
            });
        });
        return null;
    }

    /** Holt {@code satz.json} wie ein Bild: Adresse geprüft, ohne Weiterleitung, höchstens {@link #MAX_SATZ} Byte. Null bei jedem Fehler. */
    static Spritesatz holeSatz(HttpClient client, URI uri, InetAddress spielserver, Duration frist, boolean warnen) {
        try {
            Adresse.Urteil urteil = Adresse.pruefe(uri, spielserver);
            if (urteil != Adresse.Urteil.GUT) {
                throw new IOException(urteil.name());
            }
            HttpRequest anfrage = HttpRequest.newBuilder(uri).timeout(frist).GET().build();
            HttpResponse<byte[]> antwort = Laden.sende(client, anfrage, MAX_SATZ, Laden.Grund.NETZ, frist, uri.getPath());
            if (antwort.statusCode() != 200) {
                throw new IOException("HTTP " + antwort.statusCode());
            }
            Spritesatz s = spritesatz(new String(antwort.body(), StandardCharsets.UTF_8));
            if (s == null) {
                throw new IOException("kein foot aus zwei ganzen Zahlen ab 0 oder kein endlicher angle");
            }
            return s;
        } catch (IOException | Laden.Fehler | RuntimeException e) {
            if (warnen) {
                LOGGER.warn("Heroic Map: {} nicht geladen: {}", uri, e.getMessage());
            } else {
                LOGGER.debug("Heroic Map: {} wieder nicht geladen: {}", uri, e.getMessage());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /**
     * Liest {@code satz.json}: {@code foot} zwei ganze Zahlen von 0 bis zur grössten Leinwand, 32 × 64, {@code angle} eine
     * endliche Zahl in Grad, ohne sie 0; sonst null. Ob der Fuss auf dem Sprite liegt, prüft {@link #sprite}.
     */
    static Spritesatz spritesatz(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            JsonArray fuss = o.getAsJsonArray("foot");
            if (fuss == null || fuss.size() != 2) {
                return null;
            }
            double x = fuss.get(0).getAsDouble(), y = fuss.get(1).getAsDouble(), winkel = o.has("angle") ? o.get("angle").getAsDouble() : 0;
            boolean gut = x == Math.rint(x) && y == Math.rint(y) && x >= 0 && y >= 0 && x <= BANNER_BREITE && y <= BANNER_HOEHE
                    && Double.isFinite(winkel);
            return gut ? new Spritesatz((int) x, (int) y, winkel) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Ein Bild einer Tafel, höchstens {@link Tafel#MAX_BILD} im Quadrat, für {@code breite} × {@code hoehe}
     * Pixel des Schirms, also die Grösse auf der Tafel mal GUI-Massstab: Ist es grösser, verkleinert der
     * Mod es vorab geglättet auf genau diese Grösse.
     * Eigenes Budget, unabhängig von Symbolen und Bannern; sonst wie {@link #symbol}.
     */
    Textur tafelBild(String ebene, String version, String feld, int breite, int hoehe) {
        Tafelbild schluessel = new Tafelbild(ebene, version, feld, breite, hoehe);
        if (tafelbilder.containsKey(schluessel)) {
            return tafelbilder.get(schluessel);
        }
        URI uri = uri(basis, ebene, feld);
        if (uri == null) {
            return null;
        }
        tafelbilder.put(schluessel, null);
        raeume();
        int r = runde.get();
        InetAddress server = spielserver;
        holer.execute(() -> {
            if (r != runde.get()) {
                return;
            }
            Kacheln.Bild bild = hole(client, uri, server, Tafel.MAX_BILD, Tafel.MAX_BILD, true, FRIST);
            if (bild == null) {
                return;
            }
            Kacheln.Bild fertig = bild.breite() > breite || bild.hoehe() > hoehe ? verkleinert(bild, breite, hoehe) : bild;
            renderThread.execute(() -> {
                if (r == runde.get() && tafelbilder.containsKey(schluessel) && tafelbilder.get(schluessel) == null) {
                    tafelbilder.put(schluessel, new Textur(ablage.apply(uri, fertig), fertig.breite(), fertig.hoehe()));
                    tafelbilderByte += 4L * fertig.breite() * fertig.hoehe();
                    raeume();
                }
            });
        });
        return null;
    }

    int stand() {
        return stand;
    }

    /** Über dem Budget: die am längsten nicht gezeigten Bilder der Tafeln frei, das neueste bleibt. */
    private void raeume() {
        Iterator<Map.Entry<Tafelbild, Textur>> alt = tafelbilder.entrySet().iterator();
        while ((tafelbilderByte > TAFELBILDER_BYTE || tafelbilder.size() > MAX_TAFELBILDER) && tafelbilder.size() > 1) {
            gibBildFrei(alt.next().getValue());
            alt.remove();
        }
    }

    private void gibBildFrei(Textur t) {
        if (t != null) {
            tafelbilderByte -= 4L * t.breite() * t.hoehe();
            freigabe.accept(t.id());
        }
    }

    /** Verkleinert geglättet auf {@code breite} × {@code hoehe}: je Pixel der Mittelwert der Fläche, die es deckt. */
    static Kacheln.Bild verkleinert(Kacheln.Bild b, int breite, int hoehe) {
        BufferedImage quelle = new BufferedImage(b.breite(), b.hoehe(), BufferedImage.TYPE_INT_ARGB);
        quelle.setRGB(0, 0, b.breite(), b.hoehe(), b.argb(), 0, b.breite());
        BufferedImage ziel = new BufferedImage(breite, hoehe, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = ziel.createGraphics();
        g.drawImage(quelle.getScaledInstance(breite, hoehe, Image.SCALE_AREA_AVERAGING), 0, 0, null);
        g.dispose();
        return new Kacheln.Bild(breite, hoehe, ziel.getRGB(0, 0, breite, hoehe, null, 0, breite));
    }

    /** Das Bild eines Banners, höchstens {@link #BANNER_BREITE} × {@link #BANNER_HOEHE}; sonst wie {@link #symbol}. */
    Textur banner(String ebene, String version, String feld) {
        return textur(ebene, version, feld, feld + "@banner", BANNER_BREITE, BANNER_HOEHE, true);
    }

    private Textur textur(String ebene, String version, String feld, String schluessel, int breite, int hoehe, boolean hoechstens) {
        return feld == null ? null : textur(felder(ebene, version), ebene, uri(basis, ebene, feld), schluessel, breite, hoehe, hoechstens);
    }

    /** Die Felder der Ebene in dieser {@code version}; eine neue {@code version} gibt die alten frei. */
    private Felder felder(String ebene, String version) {
        Felder f = ebenen.get(ebene);
        if (f == null || !f.version.equals(version)) {
            if (f != null) {
                gibFrei(f);
            }
            f = new Felder(version);
            ebenen.put(ebene, f);
        }
        return f;
    }

    private Textur textur(Felder f, String ebene, URI uri, String schluessel, int breite, int hoehe, boolean hoechstens) {
        long jetzt = uhr.getAsLong();
        if (f.texturen.containsKey(schluessel)) {
            Textur t = f.texturen.get(schluessel);
            long[] v = f.versuche.get(schluessel);
            // Da, lädt zum ersten Mal, ein neuer Versuch läuft schon, oder noch nicht dran: so lassen.
            if (t != null || v == null || v[0] == Long.MAX_VALUE || jetzt - v[0] < warten(v[1])) {
                return t;
            }
            // Beim Einreihen als laufend markiert, so geht kein zweiter neben ihm hinaus.
            v[0] = Long.MAX_VALUE;
            return hole(f, ebene, uri, schluessel, breite, hoehe, hoechstens, false);
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
        return hole(f, ebene, uri, schluessel, breite, hoehe, hoechstens, true);
    }

    /**
     * Holt das Bild im eigenen Thread; kommt es nicht, zählt der Fehlschlag mit seiner Zeit. Nur der
     * erste steht als WARN im Log ({@code warnen}), die neuen Versuche als DEBUG. Gibt null, es lädt.
     */
    private Textur hole(Felder ziel, String ebene, URI uri, String schluessel, int breite, int hoehe, boolean hoechstens, boolean warnen) {
        if (uri == null) {
            return null;
        }
        int r = runde.get();
        InetAddress server = spielserver;
        holer.execute(() -> {
            // Nach dem Trennen, mit einer neuen Adresse, einer neuen version oder ohne die Ebene fragt ein alter Auftrag nicht mehr.
            if (r != runde.get() || ziel.frei) {
                return;
            }
            Kacheln.Bild bild = hole(client, uri, server, breite, hoehe, hoechstens, FRIST, warnen);
            renderThread.execute(() -> {
                if (r != runde.get() || ziel.frei || ebenen.get(ebene) != ziel || !ziel.texturen.containsKey(schluessel)) {
                    return;
                }
                if (bild != null) {
                    // Nur, wenn noch keine Textur da ist; sonst bliebe eine liegen.
                    if (ziel.texturen.get(schluessel) == null) {
                        ziel.texturen.put(schluessel, new Textur(ablage.apply(uri, bild), bild.breite(), bild.hoehe()));
                        stand++;
                    }
                    ziel.versuche.remove(schluessel);
                } else {
                    long[] v = ziel.versuche.computeIfAbsent(schluessel, k -> new long[2]);
                    v[0] = uhr.getAsLong();
                    v[1]++;
                }
            });
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
        return hole(client, uri, spielserver, breite, hoehe, hoechstens, frist, true);
    }

    /** Wie oben; ohne {@code warnen} steht ein Fehler nur als DEBUG im Log, für die neuen Versuche. */
    static Kacheln.Bild hole(HttpClient client, URI uri, InetAddress spielserver, int breite, int hoehe, boolean hoechstens, Duration frist,
            boolean warnen) {
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
            if (warnen) {
                LOGGER.warn("Heroic Map: Bild {} nicht geladen: {}", uri, e.getMessage());
            } else {
                LOGGER.debug("Heroic Map: Bild {} wieder nicht geladen: {}", uri, e.getMessage());
            }
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
        tafelbilder.entrySet().removeIf(e -> {
            boolean weg = !kennungen.contains(e.getKey().ebene());
            if (weg) {
                gibBildFrei(e.getValue());
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
        tafelbilder.values().forEach(this::gibBildFrei);
        tafelbilder.clear();
    }

    /** Für Tests: wartet, bis der eigene Thread alles Eingereihte abgearbeitet hat. */
    void warte() throws InterruptedException, ExecutionException {
        holer.submit(() -> { }).get();
    }
}
