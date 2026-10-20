package com.nekyia.heroicmap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.VersionParsingException;
import net.minecraft.SharedConstants;
import org.slf4j.Logger;

/**
 * Fragt einmal je Start Modrinth nach der neuesten Version des Mods für dieses Spiel und Fabric. Ist sie neuer als die
 * eigene, zeigt die Vollbildkarte oben links einen Hinweis. Vorabversionen zählen nicht; jeder Fehler gibt keinen Hinweis.
 * Siehe docs/vollbildkarte.md, „Neue Version“.
 */
final class NeueVersion {

    static final URI API = URI.create("https://api.modrinth.com/v2/project/heroic-map/version");
    /** Hierhin führt der Hinweis; die Adresse ist fest, nichts aus der Antwort kommt in den Link. */
    static final URI SEITE = URI.create("https://modrinth.com/mod/heroic-map/versions");
    /** Der Kontakt im User-Agent: die Seite des Repos, keine Mailadresse. */
    static final String REPO = "https://github.com/VonNekyia/heroic-map-renderer-mod";
    /** So gross ist die Antwort höchstens; ohne Changelogs heute rund 0,8 KiB je Version. */
    static final int MAX = 256 << 10;
    /** Längere Versionen zählen nicht; der Hinweis zeigt sie als Text. */
    static final int MAX_VERSION = 32;
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Wohin die Frage geht; null fragt nie. Die Gametests starten so und setzen selbst einen Server auf loopback. */
    static volatile URI basis = Boolean.getBoolean("heroicmap.ohne_modrinth") ? null : API;
    /** Die neuere Version auf Modrinth, sonst null. */
    static volatile String neu;
    /** Gefragt wird einmal je Start, auch wenn die Frage scheiterte. */
    static final AtomicBoolean gefragt = new AtomicBoolean();

    private NeueVersion() {
    }

    /** Fragt im Hintergrund, wenn der Schalter an ist und in diesem Start noch nicht gefragt wurde. */
    static void frage() {
        URI b = basis;
        if (b == null || !Minimap.INSTANZ.neueVersion() || !gefragt.compareAndSet(false, true)) {
            return;
        }
        String eigene = eigene();
        URI adresse = adresse(b, SharedConstants.getCurrentVersion().name());
        Thread.ofPlatform().daemon().name("Heroic Map Neue Version").start(() -> {
            try {
                neu = neuere(eigene, hole(adresse, eigene, Symbole.FRIST));
            } catch (Laden.Fehler | RuntimeException e) {
                // Ohne die Antwort selbst.
                LOGGER.info("Heroic Map: keine neueste Version von Modrinth ({})", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Die eigene Version, wie in fabric.mod.json. */
    static String eigene() {
        return FabricLoader.getInstance().getModContainer(HeroicMap.ID).orElseThrow().getMetadata().getVersion().getFriendlyString();
    }

    /** Die Frage: nur Versionen für Fabric und die Version {@code spiel} des Spiels, ohne Changelogs. */
    static URI adresse(URI basis, String spiel) {
        return URI.create(basis + "?loaders=" + kodiert("[\"fabric\"]") + "&game_versions=" + kodiert("[\"" + spiel + "\"]")
                + "&include_changelog=false");
    }

    private static String kodiert(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String agent(String eigene) {
        return "VonNekyia/heroic-map-renderer-mod/" + eigene + " (+" + REPO + ")";
    }

    /** Der Körper einer Antwort 200, höchstens {@link #MAX} Byte, Header und Körper in {@code frist}; sonst ein Fehler. */
    static byte[] hole(URI adresse, String eigene, Duration frist) throws Laden.Fehler, InterruptedException {
        HttpRequest anfrage = HttpRequest.newBuilder(adresse).timeout(frist).header("User-Agent", agent(eigene)).GET().build();
        HttpResponse<byte[]> antwort = Laden.sende(Symbole.CLIENT, anfrage, MAX, Laden.Grund.NETZ, frist, "Modrinth");
        if (antwort.statusCode() != 200) {
            throw new Laden.Fehler(Laden.Grund.NETZ, "Modrinth: " + antwort.statusCode());
        }
        return antwort.body();
    }

    /**
     * Die höchste Version der Antwort nach Semver, wenn sie neuer ist als {@code eigene}, sonst null. Vorabversionen wie
     * {@code 0.3.0-rc.1} und Einträge ohne lesbare Version zählen nicht; den Kanal auf Modrinth liest der Mod nicht, denn
     * dort liegt jede Version als {@code beta}.
     */
    static String neuere(String eigene, byte[] json) {
        SemanticVersion beste;
        try {
            beste = SemanticVersion.parse(eigene);
        } catch (VersionParsingException e) {
            return null;
        }
        JsonElement liste = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
        if (!liste.isJsonArray()) {
            return null;
        }
        String neuere = null;
        for (JsonElement e : liste.getAsJsonArray()) {
            if (!(e instanceof JsonObject o) || !(o.get("version_number") instanceof JsonPrimitive p) || !p.isString()
                    || p.getAsString().length() > MAX_VERSION) {
                continue;
            }
            try {
                SemanticVersion v = SemanticVersion.parse(p.getAsString());
                if (v.getPrereleaseKey().isEmpty() && v.compareTo(beste) > 0) {
                    beste = v;
                    neuere = p.getAsString();
                }
            } catch (VersionParsingException ignoriert) {
                // Zählt nicht.
            }
        }
        return neuere;
    }
}
