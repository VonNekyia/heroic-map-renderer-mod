package com.nekyia.heroicmap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.InetAddress;
import java.net.URI;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLMisc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * „Im Browser öffnen“: holt beim Plugin ein Token zur Anmeldung, baut den Link aus der eigenen Basis der Kacheln und öffnet
 * ihn ohne Bestätigungsschirm. Das Token steht nie im Log. Siehe docs/vollbildkarte.md, „Im Browser öffnen“.
 */
final class Webkarte {

    static final Webkarte INSTANZ = new Webkarte();
    /** Das Token der Fassung 2 des Plugins: zwei Teile in Base64url, durch einen Punkt getrennt. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    static final int MAX_TOKEN = 256;
    /** So lange, 10 s, wartet eine Frage auf die Antwort; danach geht der Knopf wieder. */
    static final long FRIST_MS = 10_000;
    private static final Logger LOGGER = LoggerFactory.getLogger(HeroicMap.ID);

    /** Hört der Server auf dem Kanal? Im Einzelspieler nie; der Gametest setzt es hier. */
    static BooleanSupplier kanal = Kanal::offen;
    /** Schickt die Frage {@code web}; true, wenn sie hinausging. Der Gametest fängt sie hier ab. */
    static BooleanSupplier fragen = Kanal::frageWeb;
    /** Öffnet den Link, auf einem Thread für Ein- und Ausgabe. Der Gametest fängt ihn hier, statt einen Browser zu öffnen. */
    static Consumer<URI> oeffnen = Webkarte::sdl;

    /** Das Feld {@code web} der Antwort auf {@code show}; null, solange sie fehlt oder es nicht hat, also bei einem alten Plugin. */
    private Boolean kann;
    /** Seit wann, nach {@code Util.getMillis}, eine Frage offen ist; 0 ohne. */
    private long gefragt;

    private Webkarte() {
    }

    /** Beim Trennen: Was der Server konnte, gilt beim nächsten nicht. */
    void leeren() {
        kann = null;
        gefragt = 0;
    }

    /** Liest das Feld {@code web} der Antwort auf {@code show}. */
    void show(JsonObject json) {
        kann = web(json);
    }

    /** Das Feld {@code web}: true oder false; null, wenn es fehlt oder kein Wahrheitswert ist, also bei einem alten Plugin. */
    static Boolean web(JsonObject json) {
        JsonElement web = json.get("web");
        return web != null && web.isJsonPrimitive() && web.getAsJsonPrimitive().isBoolean() ? web.getAsBoolean() : null;
    }

    /** Warum der Knopf aus ist, oder null, wenn er geht. */
    Component aus(URI basis, long jetzt) {
        String grund = grund(kanal.getAsBoolean(), kann, wurzel(basis) != null, offen(jetzt));
        return grund == null ? null : Component.translatable(grund);
    }

    /**
     * Der Schlüssel des Grunds, warum es nicht geht, oder null: ohne Plugin, mit einem alten, nicht freigegeben, ohne Basis
     * der Kacheln, oder eine Frage ist noch offen.
     */
    static String grund(boolean kanal, Boolean kann, boolean basis, boolean offen) {
        if (!kanal) {
            return "heroicmap.web.kein_plugin";
        }
        if (kann == null) {
            return "heroicmap.web.alt";
        }
        if (!kann) {
            return "heroicmap.web.nicht_frei";
        }
        if (!basis) {
            return "heroicmap.web.ohne_basis";
        }
        return offen ? "heroicmap.web.laeuft" : null;
    }

    /** Ein Klick auf den Knopf oder {@code /hmap web}: fragt das Plugin, wenn es geht; was der Spieler dazu liest. */
    Component klick(URI basis, long jetzt) {
        Component aus = aus(basis, jetzt);
        if (aus != null) {
            return aus;
        }
        if (!fragen.getAsBoolean()) {
            return Component.translatable("heroicmap.web.kein_plugin");
        }
        gefragt = jetzt;
        return Component.translatable("heroicmap.web.laeuft");
    }

    private boolean offen(long jetzt) {
        return gefragt != 0 && jetzt - gefragt < FRIST_MS;
    }

    /**
     * Die Antwort {@code web}, nur auf eine offene Frage. Mit Token prüft der Mod die Wurzel wie die Bilder der Ebenen
     * ({@link Adresse#pruefe}, ohne Fragment) und öffnet sie mit dem Token im Fragment; beides nicht auf dem Render-Thread.
     */
    void antwort(JsonObject json, URI basis, InetAddress spielserver, long jetzt) {
        if (!offen(jetzt)) {
            return;
        }
        gefragt = 0;
        JsonElement erlaubt = json.get("erlaubt");
        if (erlaubt == null || !erlaubt.isJsonPrimitive() || !erlaubt.getAsBoolean()) {
            melde(absage(json));
            return;
        }
        JsonElement t = json.get("token");
        String token = t != null && t.isJsonPrimitive() ? t.getAsString() : null;
        URI wurzel = wurzel(basis);
        if (wurzel == null || !token(token)) {
            melde(Component.translatable(wurzel == null ? "heroicmap.web.ohne_basis" : "heroicmap.web.grund.sonst"));
            return;
        }
        melde(Component.translatable("heroicmap.web.oeffnet"));
        Util.nonCriticalIoPool().execute(() -> {
            if (Adresse.pruefe(wurzel, spielserver) != Adresse.Urteil.GUT) {
                Minecraft.getInstance().execute(() -> melde(Component.translatable("heroicmap.web.adresse")));
                return;
            }
            oeffnen.accept(link(wurzel, token));
        });
    }

    /** Was der Spieler bei einer Absage liest; bei {@code rate} mit {@code wieder}, den Sekunden bis zum nächsten Link. */
    static Component absage(JsonObject json) {
        JsonElement g = json.get("grund");
        String grund = g != null && g.isJsonPrimitive() ? g.getAsString() : "";
        return switch (grund) {
            case "permission" -> Component.translatable("heroicmap.web.grund.permission");
            case "webserver" -> Component.translatable("heroicmap.web.grund.webserver");
            case "rate" -> {
                try {
                    yield Component.translatable("heroicmap.web.grund.rate", Math.max(1, json.get("wieder").getAsLong()));
                } catch (RuntimeException e) {
                    yield Component.translatable("heroicmap.web.grund.rate_ohne");
                }
            }
            default -> Component.translatable("heroicmap.web.grund.sonst");
        };
    }

    /** Taugt das Token: Fassung 2, höchstens {@link #MAX_TOKEN} Zeichen; so kommt nichts anderes in den Link. */
    static boolean token(String token) {
        return token != null && token.length() <= MAX_TOKEN && TOKEN.matcher(token).matches();
    }

    /**
     * Die Wurzel der Webkarte: die Basis der Kacheln ohne das letzte {@code /tiles}, mit dem Schrägstrich davor; null ohne
     * Basis oder wenn sie nicht auf {@code /tiles} endet. Die Karte liegt dort, wo {@code /tiles} beginnt, auch hinter einem Präfix.
     */
    static URI wurzel(URI basis) {
        if (basis == null || basis.getRawPath() == null || basis.getRawAuthority() == null) {
            return null;
        }
        String pfad = basis.getRawPath();
        if (pfad.endsWith("/")) {
            pfad = pfad.substring(0, pfad.length() - 1);
        }
        if (!pfad.endsWith("/tiles")) {
            return null;
        }
        return URI.create(basis.getScheme() + "://" + basis.getRawAuthority() + pfad.substring(0, pfad.length() - "tiles".length()));
    }

    /** Der Link: die Wurzel und das Token im Fragment, nie in Pfad oder Query; so steht es in keinem Log eines Servers. */
    static URI link(URI wurzel, String token) {
        return URI.create(wurzel + "#anmelden=" + token);
    }

    /** Zeigt den Text auf der offenen Karte unten links, sonst im Chat. */
    private static void melde(Component text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof Karte karte) {
            karte.zeige(text);
        } else if (mc.player != null) {
            mc.player.sendSystemMessage(text);
        }
    }

    /**
     * Öffnet den Link wie das Spiel mit {@code SDL_OpenURL}, aber nicht über {@code Blaze3D.openUri}: Das schreibt bei einem
     * Fehler den ganzen Link ins Log (per javap am Client 26.3). Hier steht dort nur der Fehler, ohne Fragment.
     */
    private static void sdl(URI link) {
        if (SDLMisc.SDL_OpenURL(link.toString())) {
            return;
        }
        String fehler = String.valueOf(SDLError.SDL_GetError());
        String fragment = link.getRawFragment();
        LOGGER.warn("Heroic Map: Browser nicht geöffnet: {}", fragment == null ? fehler : fehler.replace(fragment, "…"));
        Minecraft.getInstance().execute(() -> melde(Component.translatable("heroicmap.web.browser")));
    }
}
