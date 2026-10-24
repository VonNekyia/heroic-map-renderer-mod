package com.nekyia.heroicmap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/**
 * Der Download der Karte auf der Seite des Spiels: merkt sich das Angebot, lässt den Spieler
 * Grösse und Host bestätigen, reiht die Downloads ein und meldet das Ergebnis.
 * Siehe docs/download.md.
 */
final class Downloads {

    static final Downloads INSTANZ = new Downloads();
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Hier steht der Stand des Satzes, siehe {@link Freigabe.Stand}. */
    private static final String STAND = "massstab.txt";
    private static final DateTimeFormatter UHR = DateTimeFormatter.ofPattern("dd.MM. HH:mm");

    private final Reihe reihe = new Reihe();
    /** Nur im Thread der Reihe; der HttpClient entsteht mit dem ersten Download, beim Trennen wäre es zu früh. */
    private Laden laden;
    /** Der laufende Strom, oder null; der Thread der Reihe schreibt, der Render-Thread liest. */
    private volatile Laden.Fortschritt fortschritt;
    /** Nur der Thread der Reihe zählt hoch, siehe {@link #stufen()}. */
    private volatile int stufen;
    /** Das letzte Angebot des Servers, oder null. */
    private JsonObject angebot;
    /** Je Baum, was der Spieler vor einer {@code anfrage} mit {@code voll} bestätigt hat; die {@code freigabe} verbraucht es. */
    private final Map<String, Freigabe.Stand> bestaetigt = new HashMap<>();
    /** Der geplante oder gezeigte Dialog, oder null; es gibt höchstens einen. */
    private ConfirmScreen dialog;
    /** War der Dialog schon auf dem Schirm? Bis dahin ist er geplant und zählt als offen. */
    private boolean dialogGezeigt;
    /** Nach einer falschen Prüfsumme fragt der Mod einmal neu an, dann nicht mehr. */
    private boolean neuGefragt;
    /** Je Baum auf diesem Server, ab wann ein Abgleich wieder geht, in ms Uhr des Spielers. */
    private final Map<String, Long> abgleichAb = new HashMap<>();
    /** Je Baum eine freigabe, deren Dimension der Spieler noch nicht betreten hat; sie wartet auf deren Hash. */
    private final Map<String, JsonObject> wartend = new HashMap<>();
    /** Die Hashes der Seeds je Dimension; entsteht erst beim ersten Bedarf, im Spiel. */
    private Welten welten;

    private Downloads() {
    }

    /** Wie der Ordner einer Welt heisst: nur nach dem Hash des Seeds, mit dem Host oder mit Host und Port davor. */
    enum Ablage { HASH, IP, IP_PORT }

    /** Eine Nachricht vom Plugin; der Kanal ruft das auf dem Render-Thread. */
    void empfange(String text) {
        JsonObject json;
        try {
            JsonElement element = JsonParser.parseString(text);
            json = element.getAsJsonObject();
            if (json.get("v").getAsInt() != 1) {
                return;
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Heroic Map: Nachricht nicht lesbar");
            return;
        }
        switch (json.has("typ") ? json.get("typ").getAsString() : "") {
            case "angebot" -> {
                angebot = json;
                // Der Name gilt erst ab der nächsten Sitzung. Siehe docs/download.md, „Ablage“.
                ServerData server = server();
                if (server != null) {
                    String name = json.has("server") && json.get("server").isJsonPrimitive() ? json.get("server").getAsString() : null;
                    if (name != null && Servername.gueltig(name) == null) {
                        LOGGER.warn("Heroic Map: Name des Servers kein Hostname, übergangen");
                    }
                    Servername.nachAngebot(wurzel(), server.ip, Servername.gueltig(name));
                }
                baeume().forEach(b -> zieheUm(b.id()));
            }
            case "freigabe" -> freigabe(json);
            case "abgelehnt" -> abgelehnt(json);
            case "spieler" -> Mitspieler.INSTANZ.empfange(json, System.currentTimeMillis());
            case "show" -> {
                Mitspieler.INSTANZ.antwort(json);
                Webkarte.INSTANZ.show(json);
                boolean mitNamen = backend(json);
                // Wer der Server ist, entscheidet, welche Karte er zeigt und in welche „Selbst“ zeichnet. Siehe
                // docs/selbst.md, „Wahl“.
                boolean gewechselt = karte(json);
                uids = uidsAus(json);
                Ausweise.INSTANZ.uidsGekommen(Minecraft.getInstance());
                if (uebergang() || !gewechselt && mitNamen) {
                    Minimap.INSTANZ.leeren();
                    Selbst.INSTANZ.leeren();
                    HeroicMap.weltOrdnerGewechselt();
                }
            }
            case "web" -> Webkarte.INSTANZ.antwort(json, Symbole.INSTANZ.basis(), Symbole.INSTANZ.spielserver(), Util.getMillis());
            case "ebenen" -> {
                // Die Adresse nur aus einer Liste, die gilt; Symbole von Ebenen, die fehlen, frei.
                if (Ebenen.INSTANZ.empfange(json)) {
                    Symbole.INSTANZ.basis(json, verbindung());
                    Symbole.INSTANZ.behalte(Ebenen.INSTANZ.kennungen());
                }
            }
            // Einen lesbaren Teil hat schon der Kanal an die Ebenen gegeben.
            case "ebene" -> LOGGER.warn("Heroic Map: Teil einer Ebene nicht lesbar");
            default -> {
            }
        }
    }

    /**
     * Ein neuer Login, auch der Wechsel des Backends hinter einem Proxy, bei dem kein
     * {@code DISCONNECT} kommt: Angebot, Bestätigungen und wartende freigaben gehören zum alten
     * Backend und gelten nicht mehr; welche Hashes gelten, sagt das nächste Level. Ein laufender
     * Download lädt weiter in seinen Ordner. Siehe docs/download.md, „Ablage“.
     */
    void neueSitzung() {
        sitzung++;
        uids = Map.of();
        angebot = null;
        neuGefragt = false;
        bestaetigt.clear();
        wartend.clear();
        if (welten != null) {
            welten.neueSitzung();
        }
        nameGelesen = false;
        servername = null;
        nameGilt.clear();
        KARTE.neueSitzung();
        backend = null;
        beantwortet = false;
        sitzungAb = System.currentTimeMillis();
    }

    /** Der Schlüssel dieses Servers für zeigt.txt und die eigene Karte: sein Name aus show, sonst {@link Zeigt#OHNE}. */
    String wer() {
        String b = backend;
        return b == null ? Zeigt.OHNE : b;
    }

    /**
     * Steht fest, welcher Server es ist? Im Einzelspieler immer; auf einem Server nach der ersten Antwort auf show,
     * oder wenn der Kanal von Heroic Map {@link #OHNE_KANAL_MS} nach dem Login fehlt. Bis dahin zeichnet „Selbst“
     * nicht. Siehe docs/selbst.md, „Wahl“.
     */
    boolean serverFest() {
        return server() == null || beantwortet || !Kanal.offen() && System.currentTimeMillis() - sitzungAb >= OHNE_KANAL_MS;
    }

    /** Kam in dieser Sitzung schon eine Antwort auf show? */
    boolean beantwortet() {
        return beantwortet;
    }

    /**
     * Übergang vom geteilten Ordner zum Zeiger: Zeigt dieser Server die Karte eines anderen, merkt der Mod das je
     * Adresse und Hash. Meldet er später seine eigene, setzt der Mod einmal einen Zeiger auf die andere. True, wenn ein
     * Zeiger dazukam. Siehe docs/download.md, „Ablage“.
     */
    private boolean uebergang() {
        ServerData server = server();
        String b = backend, k = KARTE.karte();
        if (server == null || b == null) {
            return false;
        }
        boolean neu = false;
        for (long hash : nameGilt.keySet()) {
            if (k != null && !k.equals(b)) {
                Uebergang.merke(wurzel(), server.ip, hash, b, k);
                continue;
            }
            String alt = Uebergang.einmal(wurzel(), server.ip, hash, b);
            if (alt != null) {
                Path welt = wurzel().resolve(weltOrdner(Minimap.INSTANZ.ablage(), server.ip, name(hash), KARTE.fuer(hash), hash));
                neu |= zeigeEinmal(welt, alt);
            }
        }
        return neu;
    }

    /** Setzt im Ordner {@code welt} den Zeiger auf die Karte {@code alt}, wenn es sie neben ihm gibt und noch keinen. */
    static boolean zeigeEinmal(Path welt, String alt) {
        if (Zeiger.name(welt) != null || !Files.isDirectory(Zeiger.ordner(welt, alt))) {
            return false;
        }
        try {
            Zeiger.setze(welt, alt);
            return true;
        } catch (IOException e) {
            LOGGER.warn("Heroic Map: Zeiger in {} nicht gesetzt", welt, e);
            return false;
        }
    }

    /** Die erste Antwort auf show in der Sitzung setzt den Namen dieses Servers; true, wenn sie einen nennt. */
    private boolean backend(JsonObject json) {
        if (beantwortet) {
            return false;
        }
        beantwortet = true;
        backend = backendAus(json);
        return backend != null;
    }

    /** {@code backend} aus der Antwort auf show, wenn es als Name taugt und nicht {@link Zeigt#OHNE} ist; sonst null. */
    static String backendAus(JsonObject json) {
        JsonElement feld = json.get("backend");
        String name = feld != null && feld.isJsonPrimitive() && feld.getAsJsonPrimitive().isString()
                ? Kartenname.gueltig(feld.getAsString()) : null;
        return Zeigt.OHNE.equals(name) ? null : name;
    }

    /**
     * Der Server, dessen Karte dieser zeigt, wenn es ein anderer ist, etwa main auf build mit {@code karte.von}; sonst
     * null. Ein Download im Ordner gehört dann beiden.
     */
    String geteiltMit() {
        String karte = KARTE.karte();
        return karte != null && backend != null && !karte.equals(backend) ? karte : null;
    }

    /** Ruht der Baum für diesen Server, zeigt der also für seine Dimension einen anderen? Siehe docs/download.md, „Ablage“. */
    boolean ruht(String baum) {
        return ruht(ordner(baum), wer());
    }

    /** Ruht der Baum im Ordner {@code ordner} für {@code server}? Ein unvollständiger ruht nie. */
    static boolean ruht(Path ordner, String server) {
        Satz satz = ordner == null ? null : Satz.lies(ordner);
        if (satz == null) {
            return false;
        }
        Satz gezeigt = Satz.fuer(ordner.getParent(), satz.dimension(), server);
        return gezeigt != null && !gezeigt.ordner().getParent().equals(ordner);
    }

    /**
     * {@code karte} an der Antwort auf show, oder keine: Die erste Antwort der Sitzung gilt und korrigiert die gemerkte,
     * für jede Welt, die der Spieler schon betrat, mit Umzug aus dem Ordner ohne Karte ({@link #karteGilt}); dann lesen
     * Minimap, eigene Karte, Wegpunkte und Ebenen neu. Gemerkt für den nächsten Login. Siehe docs/download.md, „Ablage“.
     */
    private boolean karte(JsonObject json) {
        ServerData server = server();
        if (server == null || KARTE.bestaetigt()) {
            return false;
        }
        String karte = karteAus(json);
        if (json.has("karte") && karte == null) {
            LOGGER.warn("Heroic Map: Name der Karte ungültig, übergangen");
            return false;
        }
        List<Long> betreten = List.copyOf(nameGilt.keySet());
        boolean anders = KARTE.vomServer(karte, betreten, this::karteGilt);
        Kartenname.merke(wurzel(), server.ip, betreten, karte);
        if (anders) {
            Minimap.INSTANZ.leeren();
            Selbst.INSTANZ.leeren();
            HeroicMap.weltOrdnerGewechselt();
            baeume().forEach(b -> zieheUm(b.id()));
        }
        return anders;
    }

    /** {@link #karteGilt(Path, Ablage, String, String, String, long, Predicate)} für diesen Server. */
    private boolean karteGilt(String karte, long seed) {
        ServerData server = server();
        return server != null
                && karteGilt(wurzel(), Minimap.INSTANZ.ablage(), server.ip, name(seed), karte, seed, reihe::belegtUnter);
    }

    /** {@code karte} aus der Antwort auf show, wenn sie als Name einer Karte taugt; sonst null. */
    static String karteAus(JsonObject json) {
        JsonElement feld = json.get("karte");
        return feld != null && feld.isJsonPrimitive() && feld.getAsJsonPrimitive().isString()
                ? Kartenname.gueltig(feld.getAsString()) : null;
    }

    /**
     * Gilt die Karte {@code karte} für die Welt mit diesem Hash? Ja, wenn es ihren Ordner schon gibt, oder den ohne
     * Karte nicht. Sonst zieht der ohne Karte um, wie beim Namen ({@link Servername#gilt}): nicht, solange ein Download
     * in ihn läuft, oder wenn der Umzug scheitert. Der Ordner ohne Karte bleibt dann für diese Sitzung.
     */
    static boolean karteGilt(Path wurzel, Ablage ablage, String adresse, String name, String karte, long seed,
            Predicate<String> belegt) {
        Path alt = wurzel.resolve(weltOrdner(ablage, adresse, name, null, seed));
        Path neu = wurzel.resolve(weltOrdner(ablage, adresse, name, karte, seed));
        // Die Antwort auf show kommt nach dem Betreten: Was der Mod bis dahin schrieb, liegt ohne Karte und bleibt dort.
        if (Files.isDirectory(neu)) {
            return true;
        }
        return Servername.gilt(alt, neu, belegt.test(alt.toString()));
    }

    /** Die UIDs der Welten vom Plugin, aus der Antwort auf show, je Weltschlüssel; leer ohne. Für die Sitzung. */
    private static volatile Map<String, String> uids = Map.of();

    static Map<String, String> uids() {
        return uids;
    }

    /**
     * {@code welten} aus der Antwort auf show: je Weltschlüssel ein Objekt mit {@code uid}, höchstens 64. Eine UID, die nicht
     * klein mit Bindestrichen ist, übergeht der Mod mit einer Zeile im Log. Ohne das Feld, etwa bei einem älteren Plugin,
     * leer. Siehe docs/download.md, „Reset“.
     */
    static Map<String, String> uidsAus(JsonObject json) {
        if (!json.has("welten") || !json.get("welten").isJsonObject()) {
            return Map.of();
        }
        Map<String, String> aus = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("welten").entrySet()) {
            JsonElement w = e.getValue();
            String uid = w.isJsonObject() && w.getAsJsonObject().has("uid") && w.getAsJsonObject().get("uid").isJsonPrimitive()
                    ? w.getAsJsonObject().get("uid").getAsString() : null;
            if (aus.size() >= 64) {
                break;
            }
            if (Reset.uid(uid)) {
                aus.put(e.getKey(), uid);
            } else {
                LOGGER.warn("Heroic Map: UID der Welt {} ungültig, übergangen", e.getKey());
            }
        }
        return Map.copyOf(aus);
    }

    /** Zählt jeden Login mit; ein Download merkt sich die Sitzung, aus der er stammt. */
    private int sitzung;
    /** Der Name vom Server, einmal je Sitzung gelesen beim ersten Betreten; null ohne. */
    private static volatile String servername;
    private static volatile boolean nameGelesen;
    /** Je Hash einer Welt, ob der Name dort gilt: entschieden beim ersten Betreten der Sitzung, fest bis zur nächsten. */
    private static final Map<Long, Boolean> nameGilt = new ConcurrentHashMap<>();
    /** Der Name der Karte vom Plugin, je Sitzung, und wo er gilt. */
    static final Kartenname KARTE = new Kartenname();
    /** So lange nach dem Login wartet „Selbst“ auf den Kanal von Heroic Map; danach gilt der Server als einer ohne Namen. */
    static final long OHNE_KANAL_MS = 3000;
    /** Der Name dieses Servers aus der Antwort auf show, je Sitzung; null ohne. Siehe docs/selbst.md, „Wahl“. */
    private volatile String backend;
    /** Kam in dieser Sitzung schon eine Antwort auf show? */
    private volatile boolean beantwortet;
    /** Wann die Sitzung begann, in ms. */
    private volatile long sitzungAb;

    /** Hält eine freigabe, bis der Hash ihrer Dimension bekannt ist. */
    void warte(String baum, JsonObject json) {
        wartend.put(baum, json);
    }

    /** Für den Test: wie viele freigaben warten. */
    int wartende() {
        return wartend.size();
    }

    /** Vergisst Angebot, Bestätigungen und Dialog und bricht die Downloads ab, etwa beim Trennen. */
    void leeren() {
        angebot = null;
        neuGefragt = false;
        abgleichAb.clear();
        bestaetigt.clear();
        wartend.clear();
        dialog = null;
        reihe.abbrechen();
    }

    /** Steht der Baum im letzten Angebot? */
    boolean angeboten(String baum) {
        return eintrag(baum) != null;
    }

    /** Wartet oder läuft für den Baum auf diesem Server ein Download? */
    boolean belegt(String baum) {
        return reihe.belegt(schluessel(baum));
    }

    /** Wartet oder läuft ein Download in den Ordner eines Baums, gleich auf welchem Server? */
    boolean belegt(Path ordner) {
        return reihe.belegt(ordner.toString());
    }

    /** Hält den Ordner eines Baums, solange er gelöscht wird; false, wenn ein Download wartet oder läuft. */
    boolean halte(Path ordner) {
        return reihe.halte(ordner.toString());
    }

    void gibFrei(Path ordner) {
        reihe.gibFrei(ordner.toString());
    }

    /** Der Massstab, den das Plugin für den Baum gespeichert hat, so wie der Mod ihn zuletzt voll lud, oder 0. */
    int aktiv(String baum) {
        Freigabe.Stand stand = stand(baum);
        return stand == null ? 0 : stand.massstab();
    }

    /**
     * Der Massstab, den der Spieler vom Baum ganz auf der Platte hat und für den ein Abgleich geht,
     * oder 0: ein vollständiger Satz, und das Plugin misst den Abgleich an demselben Massstab.
     */
    int vollstaendig(String baum) {
        Path ordner = ordner(baum);
        Satz satz = ordner == null ? null : Satz.lies(ordner);
        return satz != null && satz.massstab() == aktiv(baum) && !Laden.imStrom(satz.ordner()) ? satz.massstab() : 0;
    }

    /** Der gespeicherte Stand des Baums auf diesem Server, oder null. */
    private Freigabe.Stand stand(String baum) {
        Path ordner = ordner(baum);
        return ordner == null ? null : Freigabe.Stand.lies(ordner.resolve(STAND));
    }

    /**
     * Der Befehl {@code laden}: Erst bestätigt der Spieler Grösse und Massstab aus dem Angebot,
     * dann geht die {@code anfrage} hinaus; ein Nein kostet keinen vollen Download. Gibt den
     * Fehler zurück, oder null.
     */
    Component frageVoll(String baum, int massstab) {
        if (!Freigabe.baum(baum)) {
            return Component.translatable("heroicmap.befehl.name", baum);
        }
        JsonObject eintrag = eintrag(baum);
        if (!Kanal.offen() || eintrag == null) {
            return Component.translatable("heroicmap.befehl.unbekannt", baum);
        }
        long bytes = feld(eintrag, massstab, "bytes"), kacheln = feld(eintrag, massstab, "kacheln");
        if (bytes < 0 || kacheln < 0) {
            return Component.translatable("heroicmap.befehl.massstab_fehlt", massstab);
        }
        Component unbekannt = weltUnbekannt(baum);
        if (unbekannt != null) {
            return unbekannt;
        }
        if (belegt(baum)) {
            return Component.translatable("heroicmap.download.belegt", baum);
        }
        if (fragtNachSelbst(baum, massstab, text(eintrag, "dimension"))) {
            return null;
        }
        int alt = aktiv(baum);
        String name = eintrag.has("name") ? eintrag.get("name").getAsString() : baum;
        Component frage = alt != 0 && alt != massstab
                ? Component.translatable("heroicmap.befehl.frage_wechsel", name, groesse(bytes), massstab, alt)
                : Component.translatable("heroicmap.befehl.frage", name, groesse(bytes), massstab);
        boolean gezeigt = zeige(Component.translatable("heroicmap.befehl.titel"), frage, () -> {
            // Während des Dialogs kann der Abgleich vom Beitritt begonnen haben; dann zählte die anfrage umsonst.
            if (belegt(baum)) {
                melde(Component.translatable("heroicmap.download.belegt", baum));
                return;
            }
            bestaetigt.put(baum, new Freigabe.Stand(massstab, bytes, kacheln));
            // Ohne Stand gibt es nichts fortzusetzen; ein altes Token könnte schon verbraucht sein.
            Path ordner = ordner(baum);
            Kanal.frage(baum, massstab, "voll", ordner == null || !Laden.hatStand(ordner.resolve(String.valueOf(massstab))));
        });
        return gezeigt ? null : Component.translatable("heroicmap.befehl.dialog");
    }

    /**
     * Vor dem ersten vollen Download eines Baums: Zeigt dieser Server für seine Dimension die eigene Karte, fragt der
     * Mod, was mit ihr geschieht, und danach erst nach dem Download. True, wenn er fragt. Siehe docs/download.md,
     * „Ablage“.
     */
    private boolean fragtNachSelbst(String baum, int massstab, String dimension) {
        Path ordner = ordner(baum);
        if (ordner == null || dimension == null || Satz.lies(ordner) != null) {
            return false;
        }
        Path welt = ordner.getParent();
        String wer = wer();
        Satz gezeigt = Satz.fuer(welt, dimension, wer);
        if (gezeigt == null || !Selbst.gehoert(gezeigt.ordner().getParent(), wer) || baum.equals(Zeigt.baum(welt, wer, dimension))) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setScreen(new AlteKarte(mc.gui.screen(), gezeigt, null,
                Component.translatable("heroicmap.alte.danach_download"), wahl -> {
                    if (wahl == AlteKarte.Wahl.BEHALTEN) {
                        try {
                            Zeigt.setze(welt, wer, dimension, baum);
                        } catch (IOException e) {
                            LOGGER.warn("Heroic Map: Wahl der Karte nicht gespeichert", e);
                            melde(Component.translatable("heroicmap.selbst.fehler"));
                            return;
                        }
                    }
                    Component fehler = frageVoll(baum, massstab);
                    if (fehler != null) {
                        melde(fehler);
                    }
                }));
        return true;
    }

    private void freigabe(JsonObject json) {
        ServerData server = server();
        if (server == null) {
            return;
        }
        Freigabe f;
        try {
            f = Freigabe.lies(json, verbindung());
        } catch (RuntimeException e) {
            melde(Component.translatable("heroicmap.download.unlesbar"));
            return;
        }
        zieheUm(f.baum());
        long kacheln = feld(eintrag(f.baum()), f.massstab(), "kacheln");
        if (kacheln < 0) {
            melde(Component.translatable("heroicmap.download.nicht_angeboten", f.baum()));
            return;
        }
        if (ordner(f.baum()) == null) {
            // Den Hash der Dimension kennt der Mod erst, wenn der Spieler sie betritt. Siehe docs/download.md, „Ablage“.
            warte(f.baum(), json);
            return;
        }
        if (f.abgleich() && ruht(f.baum())) {
            // Eine ruhende Karte bekommt hier keinen Abgleich, sonst wüchse sie unbemerkt. Siehe docs/download.md, „Ablage“.
            LOGGER.info("Heroic Map: Abgleich von {} übergangen, die Karte ruht auf diesem Server", f.baum());
            return;
        }
        if (belegt(f.baum())) {
            melde(Component.translatable("heroicmap.download.verfaellt_belegt", f.baum()));
            return;
        }
        if (vollStattAbgleich(f.abgleich(), ordner(f.baum()).resolve(String.valueOf(f.massstab())))) {
            Component fehler = frageVoll(f.baum(), f.massstab());
            if (fehler != null) {
                melde(fehler);
            }
            return;
        }
        String spielserver = ServerAddress.parseString(server.ip).getHost();
        String host = f.url().getHost();
        // Gemessen wird am gespeicherten oder bestätigten Stand, nie an Zahlen aus dem angebot allein.
        Freigabe.Stand gespeichert = stand(f.baum()), bestaetigt = this.bestaetigt.remove(f.baum());
        Freigabe.Stand mass = f.mass(gespeichert, bestaetigt, kacheln);
        Laden.Auftrag auftrag = new Laden.Auftrag(f.url(), f.token(), f.manifestSha256(), f.bytes(), f.massstab(),
                f.abgleich(), mass.bytes(), mass.kacheln(), verbindung());
        Component frage = switch (f.weg(gespeichert, bestaetigt, zugestimmt(spielserver, host))) {
            case STILL -> null;
            case HOST -> Component.translatable("heroicmap.download.host", spielserver, host);
            case FRAGEN -> Component.translatable("heroicmap.download.frage", spielserver, host, groesse(f.bytes()),
                    f.massstab());
        };
        if (frage == null) {
            starte(f, auftrag);
        } else if (!zeige(Component.translatable("heroicmap.download.titel"), frage, () -> {
            stimmeZu(spielserver, host);
            starte(f, auftrag);
        })) {
            melde(Component.translatable("heroicmap.download.verfaellt_dialog", f.baum()));
        }
    }

    /**
     * Lädt der Mod statt eines Abgleichs voll? Ja, wenn er im Ordner des Massstabs keinen Stand hat,
     * etwa nach dem Löschen der Karte: Ein Abgleich brächte je Tag nur die Hälfte. Dann fragt er wie
     * vor dem ersten Download und schickt {@code anfrage voll} mit {@code neu: true}. Ebenso, solange
     * ein Strom nicht fertig ist; der volle Download setzt ihn fort. Siehe docs/download.md,
     * „Zustimmung und Grösse“.
     */
    static boolean vollStattAbgleich(boolean abgleich, Path massstabOrdner) {
        return abgleich && (!Laden.hatStand(massstabOrdner) || Laden.imStrom(massstabOrdner));
    }

    private void abgelehnt(JsonObject json) {
        String grund = json.has("grund") ? json.get("grund").getAsString() : "?";
        // Höchstens ein Abgleich je Tag: Bis wieder ist der Abgleich des genannten Baums aus.
        Freigabe.Sperre sperre = Freigabe.sperre(json, System.currentTimeMillis());
        if (sperre != null) {
            abgleichAb.put(schluessel(sperre.baum()), sperre.ab());
        }
        try {
            if (json.has("wieder") && json.has("jetzt")) {
                // Die Uhr des Servers kann anders gehen; es zählt der Abstand.
                long lokal = Instant.now().getEpochSecond() + json.get("wieder").getAsLong() - json.get("jetzt").getAsLong();
                melde(Component.translatable("heroicmap.download.abgelehnt_wieder", grund, uhr(lokal * 1000)));
                return;
            }
        } catch (RuntimeException e) {
            // Ohne lesbare Zeit nur der Grund.
        }
        melde(Component.translatable("heroicmap.download.abgelehnt", grund));
    }

    /** Eine Zeit der Uhr des Spielers, in ms, als Datum und Uhrzeit. */
    static String uhr(long ms) {
        return UHR.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()));
    }

    /** Ab wann ein Abgleich des Baums wieder geht, in ms Uhr des Spielers, oder 0, wenn jetzt. */
    long abgleichAb(String baum) {
        Long ab = abgleichAb.get(schluessel(baum));
        return ab == null || ab <= System.currentTimeMillis() ? 0 : ab;
    }

    /** Zeigt einen Dialog; false, wenn schon einer geplant oder offen ist. */
    private boolean zeige(Component titel, Component text, Runnable ja) {
        Minecraft mc = Minecraft.getInstance();
        // Geplant zählt als offen; gezeigt nur, solange er auf dem Schirm ist.
        if (dialog != null && (!dialogGezeigt || mc.gui.screen() == dialog)) {
            return false;
        }
        ConfirmScreen neu = new ConfirmScreen(antwort -> {
            dialog = null;
            mc.gui.setScreen(null);
            if (antwort) {
                ja.run();
            }
        }, titel, text);
        dialog = neu;
        dialogGezeigt = false;
        // Nach einem Befehl schliesst der Chat noch; der Dialog kommt danach.
        mc.schedule(() -> {
            if (dialog == neu) {
                mc.gui.setScreen(neu);
                dialogGezeigt = true;
            }
        });
        return true;
    }

    private void starte(Freigabe f, Laden.Auftrag auftrag) {
        Path ordner = ordner(f.baum());
        if (ordner == null) {
            return;
        }
        JsonObject eintrag = eintrag(f.baum());
        reiheEin(f, auftrag, ordner, text(eintrag, "name"), text(eintrag, "dimension"));
    }

    /** Reiht den Download in den Ordner des Baums ein und meldet den Beginn. Auf dem Render-Thread; der Gametest ruft es direkt. */
    void reiheEin(Freigabe f, Laden.Auftrag auftrag, Path ordner, String name, String dimension) {
        int meine = sitzung;
        if (!reihe.reihe(ordner.toString(), () -> lade(f, auftrag, ordner, name, dimension, meine))) {
            melde(Component.translatable("heroicmap.download.verfaellt_belegt", f.baum()));
            return;
        }
        melde(Component.translatable("heroicmap.download.beginnt", groesse(f.bytes())));
    }

    /** Im Thread der Reihe. Das Ergebnis geht in jedem Fall zurück, auch bei einem Error. */
    private void lade(Freigabe f, Laden.Auftrag auftrag, Path ordner, String name, String dimension, int meine) {
        Laden.Ergebnis ergebnis = null;
        Exception fehler = null;
        try {
            if (laden == null) {
                laden = new Laden();
            }
            Path ziel = ordner.resolve(String.valueOf(f.massstab()));
            if (!f.abgleich()) {
                // Das Plugin speichert den Massstab, sobald es das Token ausstellt; der Abgleich misst an diesem Stand.
                new Freigabe.Stand(f.massstab(), auftrag.satzBytes(), auftrag.kacheln()).schreibe(ordner.resolve(STAND));
                // Voll als ein Strom; kennt der Renderer ihn nicht, einzeln. Siehe docs/download.md, „Strom“.
                fortschritt = new Laden.Fortschritt(f.bytes());
                boolean[] gezeigt = {false};
                ergebnis = laden.strom(auftrag, ziel, fortschritt, stufe -> {
                    if (!gezeigt[0] && name != null && dimension != null) {
                        gezeigt[0] = true;
                        // Ab der gröbsten Stufe zeigt die Vollbildkarte den Satz, siehe docs/vollbildkarte.md, „Welcher Satz“;
                        // ein vollständiger alter Satz in einem anderen Massstab bleibt bis zum Ende.
                        try {
                            if (Satz.lies(ordner) == null) {
                                Satz.schreibe(ordner, name, dimension, f.massstab());
                            }
                        } catch (IOException e) {
                            LOGGER.warn("Heroic Map: satz.json nicht geschrieben", e);
                        }
                    }
                    stufen++;
                    Minecraft.getInstance().execute(Kacheln::satzGeladen);
                });
                fortschritt = null;
            }
            if (ergebnis == null) {
                // Ein Abgleich zeigt den Kasten wie der Strom, der einzelne volle Download nicht. Siehe docs/vollbildkarte.md,
                // „Fortschritt“.
                fortschritt = f.abgleich() ? new Laden.Fortschritt(Laden.Fortschritt.UNBEKANNT, true) : null;
                ergebnis = laden.lade(auftrag, ziel, fortschritt);
            }
            if (!ergebnis.gekappt()) {
                Laden.behalteNur(ordner, f.massstab());
                if (name != null && dimension != null) {
                    // Spätestens jetzt zeigt die Vollbildkarte den Satz, siehe docs/vollbildkarte.md, „Welcher Satz“.
                    Satz.schreibe(ordner, name, dimension, f.massstab());
                }
            }
        } catch (Exception e) {
            fehler = e;
        } finally {
            fortschritt = null;
            zurueck(f, auftrag, ergebnis, fehler, meine);
        }
    }

    /** Der laufende Strom für die Anzeige auf der Vollbildkarte, oder null. */
    Laden.Fortschritt fortschritt() {
        return fortschritt;
    }

    /** Zählt jede Stufe, die ein Strom ganz abgelegt hat; die Vollbildkarte ohne Satz sieht dann nach. */
    int stufen() {
        return stufen;
    }

    private void zurueck(Freigabe f, Laden.Auftrag auftrag, Laden.Ergebnis ergebnis, Exception fehler, int meine) {
        Minecraft.getInstance().execute(() -> beende(f, auftrag, ergebnis, fehler, meine));
    }

    private void beende(Freigabe f, Laden.Auftrag auftrag, Laden.Ergebnis ergebnis, Exception fehler, int meine) {
        if (ergebnis != null) {
            if (fehler != null) {
                LOGGER.warn("Heroic Map: Kacheln geladen, Satz nicht fertig angelegt", fehler);
            }
            if (!ergebnis.gekappt()) {
                Kacheln.satzGeladen();
            }
            neuGefragt = false;
            melde(Component.translatable(ergebnis.gekappt() ? "heroicmap.download.gekappt" : "heroicmap.download.fertig",
                    ergebnis.geladen(), ergebnis.gleich(), ergebnis.geloescht(), groesse(ergebnis.bytesGeladen())));
            return;
        }
        if (fehler instanceof Laden.Fehler lf && neuFragen(lf.grund, neuGefragt, meine, sitzung) && Kanal.offen()) {
            // Zwischen freigabe und Abruf endete ein Lauf; dasselbe Token kommt mit dem neuen Manifest.
            neuGefragt = true;
            if (!f.abgleich()) {
                bestaetigt.put(f.baum(), new Freigabe.Stand(f.massstab(), auftrag.satzBytes(), auftrag.kacheln()));
            }
            Kanal.frage(f.baum(), f.massstab(), f.art(), false);
            return;
        }
        LOGGER.warn("Heroic Map: Download abgebrochen", fehler);
        String grund = fehler instanceof Laden.Fehler lf ? lf.grund.name().toLowerCase(Locale.ROOT) : "anderer";
        Component warum = Component.translatable("heroicmap.download.grund." + grund);
        if (fehler instanceof Laden.Fehler lf && lf.status > 0) {
            // Der Status trennt einen Fehler des Proxys von einem des Netzes.
            warum = Component.translatable("heroicmap.download.grund_status", warum, lf.status);
        }
        melde(Component.translatable("heroicmap.download.abbruch", warum));
    }

    /** Die Adresse der echten Verbindung zum Spielserver, oder null. */
    static InetAddress verbindung() {
        ClientPacketListener verbindung = Minecraft.getInstance().getConnection();
        return verbindung != null && verbindung.getConnection().getRemoteAddress() instanceof InetSocketAddress a
                ? a.getAddress() : null;
    }

    /** Der Schlüssel in der Reihe: der Ordner des Baums, also je Welt und Baum. */
    private String schluessel(String baum) {
        Path ordner = ordner(baum);
        return ordner == null ? baum : ordner.toString();
    }

    /**
     * Der Ordner eines Baums: in der Welt seiner Dimension aus dem Angebot, nicht der, in der der
     * Spieler steht. Null im Einzelspieler, ohne Eintrag im Angebot und solange der Hash der
     * Dimension unbekannt ist. Siehe docs/download.md, „Ablage“.
     */
    private Path ordner(String baum) {
        ServerData server = server();
        String dimension = text(eintrag(baum), "dimension");
        Long hash = server == null || dimension == null ? null : welten().hash(Welten.server(server.ip), dimension);
        return hash == null ? null
                : ordner(wurzel(), Minimap.INSTANZ.ablage(), server.ip, name(hash), KARTE.fuer(hash), welten(), dimension, baum);
    }

    /** Der Name vom Server für die Welt mit diesem Hash, wenn er dort in dieser Sitzung gilt; sonst null, also die Adresse. */
    private static String name(long hash) {
        return Boolean.TRUE.equals(nameGilt.get(hash)) ? servername : null;
    }

    /** Der Ordner des Baums {@code baum} der Dimension, oder null, solange ihr Hash unbekannt ist. */
    static Path ordner(Path wurzel, Ablage ablage, String adresse, String name, String karte, Welten welten, String dimension,
            String baum) {
        Long hash = welten.hash(Welten.server(adresse), dimension);
        return hash == null || !Freigabe.baum(baum) ? null
                : wurzel.resolve(weltOrdner(ablage, adresse, name, karte, hash)).resolve(baum);
    }

    /** Der Fehler, wenn der Spieler die Dimension des Baums auf diesem Backend noch nicht betreten hat, sonst null. */
    private Component weltUnbekannt(String baum) {
        ServerData server = server();
        String dimension = text(eintrag(baum), "dimension");
        Long hash = server == null || dimension == null ? null : welten().hash(Welten.server(server.ip), dimension);
        return server != null && weltUnbekannt(baum, dimension, hash)
                ? Component.translatable("heroicmap.befehl.welt_unbekannt", dimension) : null;
    }

    /**
     * Nach einer falschen Prüfsumme fragt der Mod einmal neu an, aber nur beim Backend, von dem der
     * Download stammt: Nach einem neuen Login gehört die Antwort einem anderen.
     */
    static boolean neuFragen(Laden.Grund grund, boolean schonGefragt, int sitzungDesDownloads, int sitzungJetzt) {
        return grund == Laden.Grund.PRUEFSUMME && !schonGefragt && sitzungDesDownloads == sitzungJetzt;
    }

    /** Fehlt nur der Hash? Ein Baum ohne Dimension oder mit einem Namen, den der Mod ablehnt, ist ein anderer Fehler. */
    static boolean weltUnbekannt(String baum, String dimension, Long hash) {
        return dimension != null && Freigabe.baum(baum) && hash == null;
    }

    private Welten welten() {
        if (welten == null) {
            welten = new Welten(wurzel().resolve("welten.properties"));
        }
        return welten;
    }

    /**
     * Beim Betreten einer Welt: ihren Hash zur Dimension merken; danach ziehen Bäume dieser
     * Dimension aus der alten Ablage um, und wartende freigaben laufen. Auf dem Render-Thread.
     */
    void weltBetreten() {
        Minecraft mc = Minecraft.getInstance();
        ServerData server = server();
        ClientLevel level = mc.level;
        if (server == null || level == null) {
            return;
        }
        // Nicht bei jedem Wechsel der Dimension: Mitten in der Sitzung bliebe sonst die Karte im Ordner der Adresse liegen.
        if (!nameGelesen) {
            nameGelesen = true;
            servername = Servername.lies(wurzel(), server.ip);
        }
        long seed = level.getBiomeManager().biomeZoomSeed;
        if (!nameGilt.containsKey(seed)) {
            ablageNachziehen(server, seed);
            Path alt = wurzel().resolve(weltOrdner(Minimap.INSTANZ.ablage(), server.ip, null, seed));
            Path neu = wurzel().resolve(weltOrdner(Minimap.INSTANZ.ablage(), server.ip, servername, seed));
            nameGilt.put(seed, Servername.gilt(alt, neu, reihe.belegtUnter(alt.toString())));
        }
        // Erst der Name, dann die Karte: Sie liegt im Ordner des Namens. Ab der zweiten Sitzung gilt die gemerkte gleich.
        KARTE.betreten(seed, Kartenname.lies(wurzel(), server.ip, seed), this::karteGilt);
        if (KARTE.bestaetigt()) {
            Kartenname.merke(wurzel(), server.ip, List.of(seed), KARTE.karte());
        }
        welten().merke(Welten.server(server.ip), level.dimension().identifier().toString(), level.getBiomeManager().biomeZoomSeed);
        baeume().forEach(b -> zieheUm(b.id()));
        for (String baum : List.copyOf(wartend.keySet())) {
            if (ordner(baum) != null) {
                freigabe(wartend.remove(baum));
            }
        }
    }

    /**
     * Zieht die Welt aus den Ordnern anderer Ablagen in den der jetzigen, für die Adresse und, falls gemerkt, für den
     * Namen; nicht, solange ein Download in den alten Ordner läuft. Siehe docs/download.md, „Ablage“.
     */
    private void ablageNachziehen(ServerData server, long seed) {
        String hash = "welt-" + HexFormat.of().toHexDigits(seed);
        for (String name : servername == null ? new String[] {null} : new String[] {null, servername}) {
            Path ziel = adressOrdner(wurzel(), Minimap.INSTANZ.ablage(), server.ip, name, seed);
            Ort.zieheAblagen(wurzel(), ziel, andereAblagen(wurzel(), Minimap.INSTANZ.ablage(), server.ip, name, seed), hash,
                    alt -> reihe.belegtUnter(alt + File.separator));
        }
    }

    /**
     * Nach einem Wechsel der Ablage im Menü: Die Welt, in der der Spieler ist, zieht in den Ordner der neuen Ablage, im
     * Worker von „Selbst“ nach allem, was er noch schreibt; dann läuft {@code danach} auf dem Render-Thread. Im
     * Einzelspieler gibt es keine Ablage, dort gleich {@code danach}. Siehe docs/download.md, „Ablage“.
     */
    void ablageGewechselt(Runnable danach) {
        Minecraft mc = Minecraft.getInstance();
        ServerData server = server();
        if (server == null || mc.level == null) {
            danach.run();
            return;
        }
        long seed = mc.level.getBiomeManager().biomeZoomSeed;
        Minimap.INSTANZ.leeren();
        Selbst.INSTANZ.leeren();
        Selbst.INSTANZ.nachArbeit(() -> {
            ablageNachziehen(server, seed);
            mc.execute(danach);
        });
    }

    /**
     * Der Ordner der Welt, in der der Spieler steht: auf einem Server nach der Wahl Ablage, im
     * Einzelspieler nach dem Speicherordner der Welt; null ohne Welt. Siehe docs/download.md, „Ablage“.
     */
    static Path weltOrdner() {
        Minecraft mc = Minecraft.getInstance();
        ServerData server = server();
        ClientLevel level = mc.level;
        IntegratedServer intern = mc.getSingleplayerServer();
        if (level == null || server == null && intern == null) {
            return null;
        }
        long seed = level.getBiomeManager().biomeZoomSeed;
        return wurzel().resolve(server != null ? weltOrdner(Minimap.INSTANZ.ablage(), server.ip, name(seed), KARTE.fuer(seed), seed)
                : einzelspieler(intern.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString(), seed));
    }

    /**
     * Der Pfad einer Einzelspielerwelt unter {@code heroicmap/}: {@code einzelspieler_<speicherordner>/welt-<hash>},
     * so tief wie eine Welt hinter einer Adresse, damit die Kartenliste sie findet.
     */
    static String einzelspieler(String speicherordner, long seed) {
        return name("einzelspieler_" + speicherordner) + "/welt-" + HexFormat.of().toHexDigits(seed);
    }

    /** Wie {@link #weltOrdner(Ablage, String, String, String, long)}, ohne Namen vom Server und ohne Karte. */
    static String weltOrdner(Ablage ablage, String adresse, long seed) {
        return weltOrdner(ablage, adresse, null, null, seed);
    }

    /** Wie {@link #weltOrdner(Ablage, String, String, String, long)}, ohne Karte. */
    static String weltOrdner(Ablage ablage, String adresse, String name, long seed) {
        return weltOrdner(ablage, adresse, name, null, seed);
    }

    /**
     * Der Pfad einer Welt unter {@code heroicmap/}: {@code welt-<hash>}, davor der Host oder Host
     * und Port; meldet der Server einen {@code name}, steht er statt des Hosts. Mit einer {@code karte}
     * vom Plugin steht {@code karte-<karte>} vor {@code welt-<hash>}. {@code seed} ist der Hash des
     * Seeds, den der Server dem Client schickt. Ein Punkt am Ende des Hosts fällt weg; der Standardport
     * hängt bei IP:Port nichts an.
     */
    static String weltOrdner(Ablage ablage, String adresse, String name, String karte, long seed) {
        String welt = (karte != null ? "karte-" + karte + "/" : "") + "welt-" + HexFormat.of().toHexDigits(seed);
        ServerAddress a = ServerAddress.parseString(adresse);
        String host = host(a, name);
        return switch (ablage) {
            case HASH -> welt;
            case IP -> name(host) + "/" + welt;
            // Mit dem Standardport wie bei IP: Ein Wechsel zwischen beiden ändert für die meisten Server nichts.
            case IP_PORT -> name(a.getPort() == STANDARDPORT ? host : host + "_" + a.getPort()) + "/" + welt;
        };
    }

    /** Der Port, den das Spiel ohne Angabe nimmt. */
    static final int STANDARDPORT = 25565;

    /** Der Host für den Ordner: der Name vom Server oder der eingetippte. */
    private static String host(ServerAddress a, String name) {
        // Windows nimmt einem Ordner den Punkt am Ende ohnehin; so liegt example.net. überall bei example.net, auch vor dem Port.
        return (name != null ? name : a.getHost()).replaceAll("\\.+$", "");
    }

    /** Der Ordner der Adresse dieser Welt unter {@code wurzel} nach {@code ablage}; bei der Ablage Hash {@code wurzel} selbst. */
    static Path adressOrdner(Path wurzel, Ablage ablage, String adresse, String name, long seed) {
        return wurzel.resolve(weltOrdner(ablage, adresse, name, null, seed)).getParent();
    }

    /**
     * Die Ordner der Adresse dieser Welt nach den anderen Ablagen, dazu {@code <host>_25565} von vor der Regel zum
     * Standardport; ohne den nach {@code ablage}. Aus ihnen zieht die Welt um ({@link Ort#zieheAblagen}). Siehe
     * docs/download.md, „Ablage“.
     */
    static List<Path> andereAblagen(Path wurzel, Ablage ablage, String adresse, String name, long seed) {
        Set<Path> aus = new LinkedHashSet<>();
        for (Ablage a : Ablage.values()) {
            aus.add(adressOrdner(wurzel, a, adresse, name, seed));
        }
        ServerAddress s = ServerAddress.parseString(adresse);
        if (s.getPort() == STANDARDPORT) {
            aus.add(wurzel.resolve(name(host(s, name) + "_" + STANDARDPORT)));
        }
        aus.remove(adressOrdner(wurzel, ablage, adresse, name, seed));
        return List.copyOf(aus);
    }

    /**
     * Der Server dieser Verbindung, oder null im Einzelspieler; aus der Verbindung des Levels.
     * {@code Minecraft.getCurrentServer} geht über den Spieler, und beim Login entsteht das Level
     * vor ihm: Beim Betreten der ersten Welt wäre der Server sonst unbekannt. Siehe docs/download.md, „Ablage“.
     */
    static ServerData server() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null ? mc.level.connection.getServerData() : mc.getCurrentServer();
    }

    /** Der Ordner dieses Servers in der Ablage vor dem Hash des Seeds, oder null im Einzelspieler. */
    static Path alterOrdner() {
        ServerData server = server();
        return server == null ? null : wurzel().resolve(name(server.ip));
    }

    /**
     * Ein Baum aus der Ablage vor dem Hash des Seeds zieht in den Ordner der Welt seiner
     * Dimension, einmal, sobald deren Hash bekannt ist. Siehe docs/download.md, „Ablage“.
     */
    private void zieheUm(String baum) {
        Path alt = alterOrdner(), neu = ordner(baum);
        if (alt == null || neu == null) {
            return;
        }
        Path vorher = alt.resolve(baum);
        if (Files.exists(vorher.resolve(STAND)) || Files.exists(vorher.resolve("satz.json"))) {
            try {
                if (Laden.zieheBaumUm(vorher, neu)) {
                    LOGGER.info("Heroic Map: {} nach {} umgezogen", vorher, neu);
                }
            } catch (IOException e) {
                LOGGER.warn("Heroic Map: {} nicht nach {} umgezogen", vorher, neu, e);
            }
        }
    }

    /** Der Eintrag eines Baums im letzten Angebot, oder null. */
    private JsonObject eintrag(String baum) {
        try {
            for (JsonElement e : angebot.getAsJsonArray("baeume")) {
                if (baum.equals(e.getAsJsonObject().get("id").getAsString())) {
                    return e.getAsJsonObject();
                }
            }
        } catch (RuntimeException e) {
            // Kein oder ein unlesbares Angebot.
        }
        return null;
    }

    /** Ein Text aus dem Eintrag des Angebots, oder null. */
    private static String text(JsonObject eintrag, String feld) {
        try {
            return eintrag.get(feld).getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Ein Feld eines Massstabs im Eintrag des Angebots, oder -1. */
    private static long feld(JsonObject eintrag, int massstab, String feld) {
        try {
            long wert = eintrag.getAsJsonObject("massstaebe").getAsJsonObject(String.valueOf(massstab)).get(feld).getAsLong();
            return wert < 0 ? -1 : wert;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    static Path wurzel() {
        return FabricLoader.getInstance().getGameDir().resolve(HeroicMap.ID);
    }

    /** Ein Ordnername aus der Adresse des Servers. */
    static String name(String adresse) {
        String name = adresse.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.-]", "_");
        return name.isEmpty() || name.startsWith(".") || Freigabe.reserviert(name) ? "_" + name : name;
    }

    /** Die Zustimmung gilt je Paar aus Spielserver und Host der Karte. */
    private static boolean zugestimmt(String spielserver, String host) {
        return zustimmungen().contains(spielserver.toLowerCase(Locale.ROOT) + " " + host.toLowerCase(Locale.ROOT));
    }

    private static void stimmeZu(String spielserver, String host) {
        Set<String> alle = zustimmungen();
        alle.add(spielserver.toLowerCase(Locale.ROOT) + " " + host.toLowerCase(Locale.ROOT));
        try {
            Files.createDirectories(wurzel());
            Files.write(wurzel().resolve("zustimmung.txt"), alle, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Heroic Map: Zustimmung nicht gespeichert", e);
        }
    }

    private static Set<String> zustimmungen() {
        Path datei = wurzel().resolve("zustimmung.txt");
        try {
            return Files.exists(datei) ? new HashSet<>(Files.readAllLines(datei, StandardCharsets.UTF_8)) : new HashSet<>();
        } catch (IOException e) {
            return new HashSet<>();
        }
    }

    /** Bytes dezimal, wie die Doku sie nennt. */
    static String groesse(long bytes) {
        if (bytes >= 1_000_000_000L) {
            return String.format(Locale.ROOT, "%.1f GB", bytes / 1e9);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1e6);
    }

    /** Bytes als Zahl in der Einheit, die {@link #groesse} für {@code einheit} nimmt: „2.0“ von „4.8 GB“. */
    static String zahl(long bytes, long einheit) {
        return String.format(Locale.ROOT, "%.1f", bytes / (einheit >= 1_000_000_000L ? 1e9 : 1e6));
    }

    /** Eine Dauer in Sekunden als m:ss, ab einer Stunde als h:mm:ss. */
    static String dauer(long sekunden) {
        return sekunden >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", sekunden / 3600, sekunden / 60 % 60, sekunden % 60)
                : String.format(Locale.ROOT, "%d:%02d", sekunden / 60, sekunden % 60);
    }

    /** Die Summe der Kartenliste immer in GB, wie der User es will. */
    static String gb(long bytes) {
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1e9);
    }

    private static void melde(Component text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(text);
        }
    }

    /** Ein Baum des Angebots: Kennung, Name, Dimension und je Massstab die Grösse in Bytes. */
    record Baum(String id, String name, String dimension, SortedMap<Integer, Long> bytes) {
    }

    /** Die Bäume des letzten Angebots; unlesbare fallen weg. */
    List<Baum> baeume() {
        List<Baum> baeume = new java.util.ArrayList<>();
        if (angebot == null || !angebot.has("baeume")) {
            return baeume;
        }
        try {
            for (JsonElement e : angebot.getAsJsonArray("baeume")) {
                try {
                    JsonObject baum = e.getAsJsonObject();
                    SortedMap<Integer, Long> bytes = new TreeMap<>();
                    for (int m : new int[] {1, 2, 4}) {
                        long b = feld(baum, m, "bytes");
                        if (b >= 0) {
                            bytes.put(m, b);
                        }
                    }
                    baeume.add(new Baum(baum.get("id").getAsString(), baum.get("name").getAsString(),
                            baum.get("dimension").getAsString(), bytes));
                } catch (RuntimeException kaputt) {
                    // Dieser Baum ist unlesbar, die anderen nicht.
                }
            }
        } catch (RuntimeException e) {
            // Kein lesbares Angebot.
        }
        return baeume;
    }

    /** Für den Befehl: Bäume und Grössen des Angebots, je Zeile. */
    List<Component> zeilen() {
        List<Baum> baeume = baeume();
        if (baeume.isEmpty()) {
            return List.of(Component.translatable("heroicmap.angebot.keins"));
        }
        List<Component> zeilen = new java.util.ArrayList<>();
        for (Baum baum : baeume) {
            StringBuilder groessen = new StringBuilder();
            baum.bytes().forEach((m, b) -> groessen.append(m).append(" px ").append(groesse(b)).append("  "));
            zeilen.add(Component.translatable("heroicmap.angebot.baum", baum.id(), baum.name(), baum.dimension(),
                    groessen.toString().trim()));
        }
        return zeilen;
    }

    /** Der Befehl {@code abgleich} und der Knopf der Karte: fragt im gespeicherten Massstab an. Gibt den Fehler zurück, oder null. */
    Component frageAbgleich(String baum) {
        if (!Freigabe.baum(baum)) {
            return Component.translatable("heroicmap.befehl.name", baum);
        }
        Component unbekannt = weltUnbekannt(baum);
        if (unbekannt != null) {
            return unbekannt;
        }
        if (ruht(baum)) {
            return Component.translatable("heroicmap.download.ruht", baum);
        }
        int massstab = aktiv(baum);
        if (massstab == 0) {
            return Component.translatable("heroicmap.befehl.kein_satz", baum);
        }
        if (!Kanal.offen() || !angeboten(baum)) {
            return Component.translatable("heroicmap.befehl.unbekannt", baum);
        }
        if (belegt(baum)) {
            return Component.translatable("heroicmap.download.belegt", baum);
        }
        long ab = abgleichAb(baum);
        if (ab > 0) {
            return Component.translatable("heroicmap.download.abgleich_ab", uhr(ab));
        }
        Kanal.frage(baum, massstab, "abgleich", false);
        return null;
    }
}
