package com.nekyia.heroicmap;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Die Symbole der Nadeln vom Server des Renderers: geholt, wenn eine Nadel sie zum ersten Mal
 * zeichnet, ohne Token, mit derselben Prüfung der Adresse wie jeder Download, genau 16 × 16 oder
 * 9 × 9 Pixel. Ohne Adresse oder nach einem Fehler bleibt das Schild leer. Die Zustände gehören dem
 * Render-Thread, geholt wird in einem eigenen. Siehe docs/ebenen.md, „Symbole“.
 */
final class Symbole {

    static final Symbole INSTANZ = new Symbole();
    /** Höchstens so gross ist ein Bild einer Ebene; ein Symbol ist viel kleiner. */
    static final int MAX = 256 << 10;
    /** Ein Feld wie {@code images/burg_16.png}: ohne Unterordner, ohne Punkt vorn, nur PNG und WebP. */
    private static final Pattern FELD = Pattern.compile("images/[a-z0-9_-][a-z0-9_.-]{0,63}\\.(png|webp)");
    private static final Pattern MODNAME = Pattern.compile("[a-z0-9_-][a-z0-9_.-]{0,63}");
    private static final Logger LOGGER = LoggerFactory.getLogger(HeroicMap.ID);

    /** Was zu einem Feld einer Ebene gehört: seine {@code version} und die Textur, null solange es lädt oder nach einem Fehler. */
    private record Stand(String version, Identifier textur) {
    }

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            // Ein Proxy des Systems ginge an der Prüfung der Adresse vorbei.
            .proxy(HttpClient.Builder.NO_PROXY)
            .build();
    private final ExecutorService holer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "heroicmap-symbole");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Stand> staende = new HashMap<>();
    /** Die Wurzel der Kacheln am Server, aus der Liste, und die Verbindung zum Spielserver; null ohne Adresse. */
    private URI basis;
    private InetAddress spielserver;
    /** Zählt bei jedem Leeren weiter; ein Bild aus einer älteren Runde verfällt. */
    private int runde, zaehler;

    /** Die Adresse aus der Liste {@code ebenen}: {@code url}, sonst {@code port} an der IP der Verbindung. */
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
     * Die Textur des Symbols {@code feld} der Ebene in dieser {@code version}, oder null, solange es
     * lädt, ohne Adresse und nach einem Fehler. Beim ersten Fragen holt es der eigene Thread; eine
     * neue {@code version} holt es neu, denn ein Bild unter gleichem Namen kann neu sein.
     */
    Identifier symbol(String ebene, String version, String feld, int seite) {
        if (feld == null) {
            return null;
        }
        String schluessel = ebene + "/" + feld;
        Stand stand = staende.get(schluessel);
        if (stand != null && stand.version().equals(version)) {
            return stand.textur();
        }
        if (stand != null && stand.textur() != null) {
            Minecraft.getInstance().getTextureManager().release(stand.textur());
        }
        staende.put(schluessel, new Stand(version, null));
        URI uri = uri(basis, ebene, feld);
        if (uri == null) {
            return null;
        }
        int r = runde;
        InetAddress server = spielserver;
        holer.execute(() -> {
            Kacheln.Bild bild = hole(client, uri, server, seite);
            if (bild != null) {
                Minecraft.getInstance().execute(() -> lege(r, schluessel, version, uri, bild));
            }
        });
        return null;
    }

    /** Legt das geholte Bild als Textur ab, wenn seit dem Holen nichts geleert ist und dieselbe version gilt. */
    private void lege(int r, String schluessel, String version, URI uri, Kacheln.Bild bild) {
        Stand stand = staende.get(schluessel);
        if (r != runde || stand == null || !stand.version().equals(version)) {
            return;
        }
        NativeImage pixel = Kacheln.pixel(bild);
        Identifier id = Identifier.fromNamespaceAndPath(HeroicMap.ID, "ebenen/symbol_" + zaehler++);
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "heroicmap " + uri, pixel));
        staende.put(schluessel, new Stand(version, id));
    }

    /**
     * Holt ein Symbol: prüft die Adresse, ohne Weiterleitung, höchstens {@link #MAX} Byte, PNG oder
     * WebP genau {@code seite} × {@code seite}. Null bei jedem Fehler; das Log nennt ihn.
     */
    static Kacheln.Bild hole(HttpClient client, URI uri, InetAddress spielserver, int seite) {
        try {
            Adresse.Urteil urteil = Adresse.pruefe(uri, spielserver);
            if (urteil != Adresse.Urteil.GUT) {
                throw new IOException(urteil.name());
            }
            HttpRequest anfrage = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<InputStream> antwort = client.send(anfrage, HttpResponse.BodyHandlers.ofInputStream());
            byte[] daten;
            // ponytail: Die Frist gilt bis zu den Headern; ein Server, der den Körper zurückhält, hält nur diesen Thread auf.
            try (InputStream rein = antwort.body()) {
                if (antwort.statusCode() != 200) {
                    throw new IOException("HTTP " + antwort.statusCode());
                }
                daten = rein.readNBytes(MAX + 1);
            }
            if (daten.length > MAX) {
                throw new IOException("grösser als " + MAX + " Byte");
            }
            return uri.getPath().endsWith(".png") ? Kacheln.png(daten, seite) : Kacheln.dekodiere(daten, seite);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Heroic Map: Symbol {} nicht geladen: {}", uri, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /** Beim Trennen und mit einer neuen Adresse: alle Texturen frei, späte Bilder verfallen. */
    void leeren() {
        runde++;
        staende.values().stream().map(Stand::textur).filter(Objects::nonNull)
                .forEach(t -> Minecraft.getInstance().getTextureManager().release(t));
        staende.clear();
    }
}
