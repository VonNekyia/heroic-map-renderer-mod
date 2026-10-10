package com.nekyia.heroicmap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Die Sprites der Banner geheimer Ebenen: Der Mod fragt sie über den Kanal an, je Ebene, {@code version}, Entwurf und
 * Krone, erst wenn das Banner gezeichnet wird, und das Plugin schickt den Satz {@code oben}, {@code satz} und das PNG als
 * Base64. Einmal je Schlüssel und Verbindung, höchstens {@link #MAX_OFFEN} offen und {@link #MAX_JE_SEKUNDE} je Sekunde;
 * ohne Antwort in {@link #FRIST_MS} oder ohne {@code png} fragt er für diese {@code version} nicht wieder. Nur der
 * Render-Thread. Siehe docs/ebenen.md, „Geheime Banner“.
 */
final class Geheimbanner {

    /** So viele Fragen sind höchstens offen, so viele gehen höchstens je Sekunde hinaus; das Plugin nimmt 20. */
    static final int MAX_OFFEN = 8, MAX_JE_SEKUNDE = 20;
    /** Kommt in dieser Zeit keine Antwort, gilt die Frage als ohne; das Plugin antwortet nicht ohne Rechte. */
    static final long FRIST_MS = 10_000;
    /** So lang ist {@code png} als Base64 höchstens: ein Bild bis {@link Symbole#MAX} Byte, ein Drittel mehr. */
    static final int MAX_BASE64 = (Symbole.MAX + 2) / 3 * 4;
    private static final Logger LOGGER = LoggerFactory.getLogger(HeroicMap.ID);
    private static final AtomicInteger ZAEHLER = new AtomicInteger();

    /** Schickt eine Frage; true, wenn sie hinausging. Im Gametest ersetzt, denn sein Server hört den Kanal nicht. */
    static Predicate<Schluessel> fragen = Kanal::frageBanner;

    static final Geheimbanner INSTANZ = new Geheimbanner(Geheimbanner::lege, t -> Minecraft.getInstance().getTextureManager().release(t),
            () -> System.nanoTime() / 1_000_000);

    /** Wonach der Mod fragt: Ebene, {@code version}, Entwurf, Krone. */
    record Schluessel(String ebene, String version, String entwurf, boolean krone) {
    }

    /** Eine Antwort des Plugins, schon gelesen; ohne {@code png} oder mit einem, das nicht taugt, {@code satz} und {@code bild} null. */
    record Antwort(Schluessel schluessel, Symbole.Spritesatz satz, Kacheln.Bild bild) {

        /**
         * Liest eine Antwort {@code banner}; null, wenn der Text keine ist. Ein {@code png}, das nicht taugt, über
         * {@link #MAX_BASE64} Zeichen, kein PNG bis 32 × 64 oder ohne gültiges {@code satz}, gilt wie keins.
         */
        static Antwort lies(String text) {
            try {
                JsonObject o = JsonParser.parseString(text).getAsJsonObject();
                if (!o.has("typ") || !"banner".equals(o.get("typ").getAsString())) {
                    return null;
                }
                String ebene = o.get("ebene").getAsString(), version = o.get("version").getAsString(), entwurf = o.get("entwurf").getAsString();
                if (ebene.length() > Ebenen.MAX_KENNUNG || version.length() > Ebenen.MAX_KENNUNG || !Symbole.teil(entwurf)) {
                    return null;
                }
                Schluessel k = new Schluessel(ebene, version, entwurf, o.has("krone") && o.get("krone").getAsBoolean());
                Symbole.Spritesatz satz = o.has("satz") && o.get("satz").isJsonObject() ? Symbole.spritesatz(o.get("satz").toString()) : null;
                String png = o.has("png") ? o.get("png").getAsString() : null;
                if (satz == null || png == null || png.length() > MAX_BASE64) {
                    return new Antwort(k, null, null);
                }
                Kacheln.Bild bild = sprite(png);
                return bild == null ? new Antwort(k, null, null) : new Antwort(k, satz, bild);
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** Das PNG aus Base64, höchstens 32 × 64; null, wenn es keins ist. Eine Antwort damit gilt wie eine ohne. */
        private static Kacheln.Bild sprite(String png) {
            try {
                return Kacheln.png(Base64.getDecoder().decode(png), Symbole.BANNER_BREITE, Symbole.BANNER_HOEHE, true);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }

    private final BiFunction<Schluessel, Kacheln.Bild, Identifier> ablage;
    private final Consumer<Identifier> freigabe;
    private final LongSupplier uhr;
    /** Offene Fragen und wann; angekommene Sprites; Schlüssel, nach denen der Mod nicht wieder fragt. */
    private final Map<Schluessel, Long> gefragt = new HashMap<>();
    private final Map<Schluessel, Symbole.Sprite> da = new HashMap<>();
    private final Set<Schluessel> ohne = new HashSet<>();
    /** Wann die Fragen der letzten Sekunde hinausgingen. */
    private final ArrayDeque<Long> letzteSekunde = new ArrayDeque<>();
    /** Je Ebene die {@code version}, nach der zuletzt gefragt wurde; eine neue gibt die alten frei. */
    private final Map<String, String> versionen = new HashMap<>();
    private int stand;

    Geheimbanner(BiFunction<Schluessel, Kacheln.Bild, Identifier> ablage, Consumer<Identifier> freigabe, LongSupplier uhr) {
        this.ablage = ablage;
        this.freigabe = freigabe;
        this.uhr = uhr;
    }

    /**
     * Das Sprite zum Schlüssel samt Fuss, oder null, solange es aussteht oder wenn es keins gibt. Beim ersten Fragen geht
     * eine Frage hinaus, wenn weniger als {@link #MAX_OFFEN} offen sind und in der letzten Sekunde weniger als
     * {@link #MAX_JE_SEKUNDE} hinausgingen; sonst beim nächsten Zeichnen.
     */
    Symbole.Sprite sprite(String ebene, String version, String entwurf, boolean krone) {
        if (!version.equals(versionen.put(ebene, version))) {
            vergiss(ebene, version);
        }
        Schluessel k = new Schluessel(ebene, version, entwurf, krone);
        Symbole.Sprite s = da.get(k);
        if (s != null || ohne.contains(k)) {
            return s;
        }
        long jetzt = uhr.getAsLong();
        // Was in der Frist nicht kam, kommt nicht mehr: nicht wieder fragen, und der Platz unter den offenen wird frei.
        for (Iterator<Map.Entry<Schluessel, Long>> i = gefragt.entrySet().iterator(); i.hasNext(); ) {
            Map.Entry<Schluessel, Long> e = i.next();
            if (jetzt - e.getValue() >= FRIST_MS) {
                ohne.add(e.getKey());
                i.remove();
            }
        }
        while (!letzteSekunde.isEmpty() && jetzt - letzteSekunde.peekFirst() >= 1000) {
            letzteSekunde.pollFirst();
        }
        if (gefragt.containsKey(k) || ohne.contains(k) || gefragt.size() >= MAX_OFFEN || letzteSekunde.size() >= MAX_JE_SEKUNDE) {
            return null;
        }
        if (fragen.test(k)) {
            gefragt.put(k, jetzt);
            letzteSekunde.addLast(jetzt);
        }
        return null;
    }

    /** Eine Antwort; nur auf eine offene Frage. Ohne Sprite oder mit dem Fuss daneben fragt der Mod nicht wieder. */
    void antwort(Antwort a) {
        Schluessel k = a.schluessel();
        if (gefragt.remove(k) == null) {
            return;
        }
        Kacheln.Bild b = a.bild();
        if (b == null || a.satz().fussX() > b.breite() || a.satz().fussY() > b.hoehe()) {
            if (b != null) {
                LOGGER.warn("Heroic Map: Der Fuss des geheimen Banners {} der Ebene {} liegt nicht auf dem Sprite", k.entwurf(), k.ebene());
            }
            ohne.add(k);
            return;
        }
        da.put(k, new Symbole.Sprite(new Symbole.Textur(ablage.apply(k, b), b.breite(), b.hoehe()), a.satz().fussX(), a.satz().fussY()));
        stand++;
    }

    /** Zählt jedes angekommene Sprite; die Vollbildkarte sucht ihr Ziel danach neu. */
    int stand() {
        return stand;
    }

    /** Gibt die Sprites einer Ebene in anderen {@code version}s frei und vergisst ihre Fragen. */
    private void vergiss(String ebene, String version) {
        da.entrySet().removeIf(e -> {
            boolean alt = e.getKey().ebene().equals(ebene) && !e.getKey().version().equals(version);
            if (alt) {
                freigabe.accept(e.getValue().textur().id());
            }
            return alt;
        });
        gefragt.keySet().removeIf(k -> k.ebene().equals(ebene) && !k.version().equals(version));
        ohne.removeIf(k -> k.ebene().equals(ebene) && !k.version().equals(version));
    }

    /** Beim Trennen und bei einem neuen Login: alles frei, nach jeder Verbindung fragt der Mod neu. */
    void leeren() {
        da.values().forEach(s -> freigabe.accept(s.textur().id()));
        da.clear();
        gefragt.clear();
        ohne.clear();
        letzteSekunde.clear();
        versionen.clear();
    }

    /** Legt ein Sprite als Textur ab. */
    private static Identifier lege(Schluessel k, Kacheln.Bild bild) {
        Identifier id = Identifier.fromNamespaceAndPath(HeroicMap.ID, "ebenen/geheim_" + ZAEHLER.getAndIncrement());
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "heroicmap geheim " + k.ebene(), Kacheln.pixel(bild)));
        return id;
    }
}
