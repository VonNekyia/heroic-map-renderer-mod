package com.nekyia.heroicmap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
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
                baeume().forEach(b -> zieheUm(b.id()));
            }
            case "freigabe" -> freigabe(json);
            case "abgelehnt" -> abgelehnt(json);
            case "spieler" -> Mitspieler.INSTANZ.empfange(json, System.currentTimeMillis());
            case "show" -> Mitspieler.INSTANZ.antwort(json);
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
        angebot = null;
        neuGefragt = false;
        bestaetigt.clear();
        wartend.clear();
        if (welten != null) {
            welten.neueSitzung();
        }
    }

    /** Zählt jeden Login mit; ein Download merkt sich die Sitzung, aus der er stammt. */
    private int sitzung;

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
        return satz != null && satz.massstab() == aktiv(baum) ? satz.massstab() : 0;
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
        if (belegt(f.baum())) {
            melde(Component.translatable("heroicmap.download.verfaellt_belegt", f.baum()));
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
        String name = text(eintrag, "name"), dimension = text(eintrag, "dimension");
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
            if (!f.abgleich()) {
                // Das Plugin speichert den Massstab, sobald es das Token ausstellt; der Abgleich misst an diesem Stand.
                new Freigabe.Stand(f.massstab(), auftrag.satzBytes(), auftrag.kacheln()).schreibe(ordner.resolve(STAND));
            }
            ergebnis = laden.lade(auftrag, ordner.resolve(String.valueOf(f.massstab())));
            if (!ergebnis.gekappt()) {
                Laden.behalteNur(ordner, f.massstab());
                if (name != null && dimension != null) {
                    // Erst jetzt zeigt die Vollbildkarte den Satz, siehe docs/vollbildkarte.md, „Welcher Satz“.
                    Satz.schreibe(ordner, name, dimension, f.massstab());
                }
            }
        } catch (Exception e) {
            fehler = e;
        } finally {
            zurueck(f, auftrag, ergebnis, fehler, meine);
        }
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
        melde(Component.translatable("heroicmap.download.abbruch", Component.translatable("heroicmap.download.grund." + grund)));
    }

    /** Die Adresse der echten Verbindung zum Spielserver, oder null. */
    private static InetAddress verbindung() {
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
        return server == null || dimension == null ? null
                : ordner(wurzel(), Minimap.INSTANZ.ablage(), server.ip, welten(), dimension, baum);
    }

    /** Der Ordner des Baums {@code baum} der Dimension, oder null, solange ihr Hash unbekannt ist. */
    static Path ordner(Path wurzel, Ablage ablage, String adresse, Welten welten, String dimension, String baum) {
        Long hash = welten.hash(Welten.server(adresse), dimension);
        return hash == null || !Freigabe.baum(baum) ? null : wurzel.resolve(weltOrdner(ablage, adresse, hash)).resolve(baum);
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
        welten().merke(Welten.server(server.ip), level.dimension().identifier().toString(), level.getBiomeManager().biomeZoomSeed);
        baeume().forEach(b -> zieheUm(b.id()));
        for (String baum : List.copyOf(wartend.keySet())) {
            if (ordner(baum) != null) {
                freigabe(wartend.remove(baum));
            }
        }
    }

    /**
     * Der Ordner der Welt, in der der Spieler steht, nach der Wahl Ablage, oder null im
     * Einzelspieler. Siehe docs/download.md, „Ablage“.
     */
    static Path weltOrdner() {
        Minecraft mc = Minecraft.getInstance();
        ServerData server = server();
        ClientLevel level = mc.level;
        return server == null || level == null ? null
                : wurzel().resolve(weltOrdner(Minimap.INSTANZ.ablage(), server.ip, level.getBiomeManager().biomeZoomSeed));
    }

    /**
     * Der Pfad einer Welt unter {@code heroicmap/}: {@code welt-<hash>}, davor der Host oder Host
     * und Port. {@code seed} ist der Hash des Seeds, den der Server dem Client schickt.
     */
    static String weltOrdner(Ablage ablage, String adresse, long seed) {
        String welt = "welt-" + HexFormat.of().toHexDigits(seed);
        ServerAddress a = ServerAddress.parseString(adresse);
        return switch (ablage) {
            case HASH -> welt;
            case IP -> name(a.getHost()) + "/" + welt;
            case IP_PORT -> name(a.getHost() + "_" + a.getPort()) + "/" + welt;
        };
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
