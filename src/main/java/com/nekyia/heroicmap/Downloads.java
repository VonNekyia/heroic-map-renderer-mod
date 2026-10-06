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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
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
    /** Uhr des Servers minus Uhr des Spielers, in ms, aus {@code jetzt} der letzten Nachricht. */
    private long versatz;
    /** Ob dieser Server schon {@code jetzt} geschickt hat; ohne Plugin nie. */
    private boolean uhrBekannt;

    private Downloads() {
    }

    /** Eine Nachricht vom Plugin; der Kanal ruft das auf dem Render-Thread. */
    void empfange(String text) {
        JsonObject json;
        try {
            JsonElement element = JsonParser.parseString(text);
            json = element.getAsJsonObject();
            if (json.get("v").getAsInt() != 1) {
                return;
            }
            if (json.has("jetzt")) {
                versatz = json.get("jetzt").getAsLong() * 1000 - System.currentTimeMillis();
                uhrBekannt = true;
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Heroic Map: Nachricht nicht lesbar");
            return;
        }
        switch (json.has("typ") ? json.get("typ").getAsString() : "") {
            case "angebot" -> angebot = json;
            case "freigabe" -> freigabe(json);
            case "abgelehnt" -> abgelehnt(json);
            default -> {
            }
        }
    }

    /**
     * Jetzt in Serverzeit, ms: Die Live-Ebene stempelt damit ihre Bilder, damit sie zu
     * {@code abdeckt_bis} passen. Genau auf die Laufzeit einer Nachricht. Siehe docs/live.md, „Abgleich“.
     */
    long serverzeit() {
        return System.currentTimeMillis() + versatz;
    }

    /** Kennt der Mod die Uhr dieses Servers? Ohne sie legt die Live-Ebene nichts ab. */
    boolean uhrBekannt() {
        return uhrBekannt;
    }

    /** Vergisst Angebot, Bestätigungen und Dialog und bricht die Downloads ab, etwa beim Trennen. */
    void leeren() {
        angebot = null;
        neuGefragt = false;
        uhrBekannt = false;
        versatz = 0;
        bestaetigt.clear();
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

    /** Der Massstab, den das Plugin für den Baum gespeichert hat, so wie der Mod ihn zuletzt voll lud, oder 0. */
    int aktiv(String baum) {
        Freigabe.Stand stand = stand(baum);
        return stand == null ? 0 : stand.massstab();
    }

    /** Der gespeicherte Stand des Baums auf diesem Server, oder null. */
    private static Freigabe.Stand stand(String baum) {
        Path ordner = ordner(baum);
        return ordner == null ? null : Freigabe.Stand.lies(ordner.resolve(STAND));
    }

    /**
     * Der Befehl {@code laden}: Erst bestätigt der Spieler Grösse und Massstab aus dem Angebot,
     * dann geht die {@code anfrage} hinaus; ein Nein kostet keinen vollen Download. Gibt den
     * Fehler zurück, oder null.
     */
    Component frageVoll(String baum, int massstab) {
        JsonObject eintrag = eintrag(baum);
        if (!Kanal.offen() || eintrag == null) {
            return Component.translatable("heroicmap.befehl.unbekannt", baum);
        }
        long bytes = feld(eintrag, massstab, "bytes"), kacheln = feld(eintrag, massstab, "kacheln");
        if (bytes < 0 || kacheln < 0) {
            return Component.translatable("heroicmap.befehl.massstab_fehlt", massstab);
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
            Kanal.frage(baum, massstab, "voll");
        });
        return gezeigt ? null : Component.translatable("heroicmap.befehl.dialog");
    }

    private void freigabe(JsonObject json) {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        if (server == null) {
            return;
        }
        Freigabe f;
        try {
            f = Freigabe.lies(json);
        } catch (RuntimeException e) {
            melde(Component.translatable("heroicmap.download.unlesbar"));
            return;
        }
        long kacheln = feld(eintrag(f.baum()), f.massstab(), "kacheln");
        if (kacheln < 0) {
            melde(Component.translatable("heroicmap.download.nicht_angeboten", f.baum()));
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
        try {
            if (json.has("wieder") && json.has("jetzt")) {
                // Die Uhr des Servers kann anders gehen; es zählt der Abstand.
                long lokal = Instant.now().getEpochSecond() + json.get("wieder").getAsLong() - json.get("jetzt").getAsLong();
                String wann = UHR.format(LocalDateTime.ofInstant(Instant.ofEpochSecond(lokal), ZoneId.systemDefault()));
                melde(Component.translatable("heroicmap.download.abgelehnt_wieder", grund, wann));
                return;
            }
        } catch (RuntimeException e) {
            // Ohne lesbare Zeit nur der Grund.
        }
        melde(Component.translatable("heroicmap.download.abgelehnt", grund));
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
        if (!reihe.reihe(ordner.toString(), () -> lade(f, auftrag, ordner, name, dimension))) {
            melde(Component.translatable("heroicmap.download.verfaellt_belegt", f.baum()));
            return;
        }
        melde(Component.translatable("heroicmap.download.beginnt", groesse(f.bytes())));
    }

    /** Im Thread der Reihe. Das Ergebnis geht in jedem Fall zurück, auch bei einem Error. */
    private void lade(Freigabe f, Laden.Auftrag auftrag, Path ordner, String name, String dimension) {
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
            Satz vorher = Satz.lies(ordner);
            ergebnis = laden.lade(auftrag, ordner.resolve(String.valueOf(f.massstab())));
            if (!ergebnis.gekappt()) {
                Laden.behalteNur(ordner, f.massstab());
                if (name != null && dimension != null) {
                    // Erst jetzt zeigt die Vollbildkarte den Satz, siehe docs/vollbildkarte.md, „Welcher Satz“.
                    Satz.schreibe(ordner, name, dimension, f.massstab());
                }
                try {
                    raeumeEbene(ordner, vorher, f.abdecktBis());
                } catch (IOException e) {
                    // Die Kacheln sind da; nur die Live-Ebene ist nicht ganz geräumt.
                    LOGGER.warn("Heroic Map: Live-Ebene nach dem Download nicht geräumt", e);
                }
            }
        } catch (Exception e) {
            fehler = e;
        } finally {
            zurueck(f, auftrag, ergebnis, fehler);
        }
    }

    /**
     * Nach einem vollständigen Download: Bilder der Live-Ebene, die die Kacheln schon enthalten,
     * fallen weg; bei einem anderen Massstab passen sich die übrigen an. Ohne {@code abdeckt_bis}
     * bleiben alle. Siehe docs/live.md, „Abgleich“.
     */
    private static void raeumeEbene(Path ordner, Satz vorher, long abdecktBis) throws IOException {
        Path ebene = Ebene.ordner(ordner);
        Satz jetzt = Satz.lies(ordner);
        IOException fehler = null;
        try {
            if (vorher != null && jetzt != null && vorher.chunk() != jetzt.chunk()) {
                Ebene.wechsle(ebene, vorher.chunk(), jetzt.chunk());
            }
        } catch (IOException e) {
            fehler = e;
        }
        // Auch wenn der Wechsel scheiterte: Alte Bilder deckten sonst neuere Kacheln zu.
        try {
            if (abdecktBis > 0) {
                Ebene.raeume(ebene, abdecktBis * 1000);
            }
        } catch (IOException e) {
            if (fehler == null) {
                fehler = e;
            } else {
                fehler.addSuppressed(e);
            }
        }
        if (fehler != null) {
            throw fehler;
        }
    }

    private void zurueck(Freigabe f, Laden.Auftrag auftrag, Laden.Ergebnis ergebnis, Exception fehler) {
        Minecraft.getInstance().execute(() -> beende(f, auftrag, ergebnis, fehler));
    }

    private void beende(Freigabe f, Laden.Auftrag auftrag, Laden.Ergebnis ergebnis, Exception fehler) {
        if (ergebnis != null) {
            if (fehler != null) {
                LOGGER.warn("Heroic Map: Kacheln geladen, Satz nicht fertig angelegt", fehler);
            }
            if (!ergebnis.gekappt()) {
                Kacheln.ebeneGeraeumt();
            }
            neuGefragt = false;
            // Der Satz kann neu sein oder einen anderen Massstab haben.
            Live.INSTANZ.satzNeu();
            melde(Component.translatable(ergebnis.gekappt() ? "heroicmap.download.gekappt" : "heroicmap.download.fertig",
                    ergebnis.geladen(), ergebnis.gleich(), ergebnis.geloescht(), groesse(ergebnis.bytesGeladen())));
            return;
        }
        if (fehler instanceof Laden.Fehler lf && lf.grund == Laden.Grund.PRUEFSUMME && !neuGefragt && Kanal.offen()) {
            // Zwischen freigabe und Abruf endete ein Lauf; dasselbe Token kommt mit dem neuen Manifest.
            neuGefragt = true;
            if (!f.abgleich()) {
                bestaetigt.put(f.baum(), new Freigabe.Stand(f.massstab(), auftrag.satzBytes(), auftrag.kacheln()));
            }
            Kanal.frage(f.baum(), f.massstab(), f.art());
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

    /** Der Schlüssel in der Reihe: der Ordner des Baums, also je Server und Baum. */
    private static String schluessel(String baum) {
        Path ordner = ordner(baum);
        return ordner == null ? baum : ordner.toString();
    }

    /** Der Ordner eines Baums auf diesem Server, oder null im Einzelspieler. */
    private static Path ordner(String baum) {
        Path server = serverOrdner();
        return server == null || !Freigabe.baum(baum) ? null : server.resolve(baum);
    }

    /** Der Ordner dieses Servers, oder null im Einzelspieler. */
    static Path serverOrdner() {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        return server == null ? null : wurzel().resolve(name(server.ip));
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

    private static Path wurzel() {
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

    private static void melde(Component text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(text);
        }
    }

    /** Für den Befehl: Bäume und Grössen des Angebots, je Zeile. */
    List<Component> zeilen() {
        if (angebot == null || !angebot.has("baeume")) {
            return List.of(Component.translatable("heroicmap.angebot.keins"));
        }
        try {
            List<Component> zeilen = new java.util.ArrayList<>();
            for (JsonElement e : angebot.getAsJsonArray("baeume")) {
                JsonObject baum = e.getAsJsonObject();
                JsonObject massstaebe = baum.getAsJsonObject("massstaebe");
                StringBuilder groessen = new StringBuilder();
                for (String m : massstaebe.keySet()) {
                    groessen.append(m).append(" px ").append(groesse(massstaebe.getAsJsonObject(m).get("bytes").getAsLong()))
                            .append("  ");
                }
                zeilen.add(Component.translatable("heroicmap.angebot.baum", baum.get("id").getAsString(),
                        baum.get("name").getAsString(), baum.get("dimension").getAsString(), groessen.toString().trim()));
            }
            return zeilen;
        } catch (RuntimeException e) {
            return List.of(Component.translatable("heroicmap.angebot.unlesbar"));
        }
    }
}
