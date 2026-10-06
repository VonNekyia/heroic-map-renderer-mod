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
    /** Hier steht der Massstab, den das Plugin für den Baum gespeichert hat. */
    private static final String AKTIV = "massstab.txt";
    private static final DateTimeFormatter UHR = DateTimeFormatter.ofPattern("dd.MM. HH:mm");

    /** Was der Spieler vor einer {@code anfrage} mit {@code voll} bestätigt hat. */
    private record Bestaetigt(int massstab, long bytes) {
    }

    private final Reihe reihe = new Reihe();
    /** Nur im Thread der Reihe; der HttpClient entsteht mit dem ersten Download, beim Trennen wäre es zu früh. */
    private Laden laden;
    /** Das letzte Angebot des Servers, oder null. */
    private JsonObject angebot;
    /** Je Baum, was der Spieler zuletzt bestätigt hat; die {@code freigabe} verbraucht es. */
    private final Map<String, Bestaetigt> bestaetigt = new HashMap<>();
    /** Der offene Dialog, oder null; es gibt höchstens einen. */
    private ConfirmScreen dialog;
    /** Nach einer falschen Prüfsumme fragt der Mod einmal neu an, dann nicht mehr. */
    private boolean neuGefragt;

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

    /** Vergisst Angebot, Bestätigungen und Dialog, etwa beim Trennen. Laufende Downloads enden für sich. */
    void leeren() {
        angebot = null;
        neuGefragt = false;
        bestaetigt.clear();
        dialog = null;
    }

    /** Steht der Baum im letzten Angebot? */
    boolean angeboten(String baum) {
        return eintrag(baum) != null;
    }

    /** Wartet oder läuft für den Baum ein Download? */
    boolean belegt(String baum) {
        return reihe.belegt(baum);
    }

    /** Der Massstab, den das Plugin für den Baum gespeichert hat, so wie der Mod ihn zuletzt voll lud, oder 0. */
    int aktiv(String baum) {
        Path ordner = ordner(baum);
        try {
            int m = ordner == null ? 0 : Integer.parseInt(Files.readString(ordner.resolve(AKTIV)).trim());
            return m == 1 || m == 2 || m == 4 ? m : 0;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
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
        long bytes = feld(eintrag, massstab, "bytes");
        if (bytes < 0) {
            return Component.translatable("heroicmap.befehl.massstab_fehlt", massstab);
        }
        if (reihe.belegt(baum)) {
            return Component.translatable("heroicmap.download.belegt", baum);
        }
        int alt = aktiv(baum);
        String name = eintrag.has("name") ? eintrag.get("name").getAsString() : baum;
        Component frage = alt != 0 && alt != massstab
                ? Component.translatable("heroicmap.befehl.frage_wechsel", name, groesse(bytes), massstab, alt)
                : Component.translatable("heroicmap.befehl.frage", name, groesse(bytes), massstab);
        boolean gezeigt = zeige(Component.translatable("heroicmap.befehl.titel"), frage, () -> {
            bestaetigt.put(baum, new Bestaetigt(massstab, bytes));
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
        JsonObject eintrag = eintrag(f.baum());
        long satzBytes = feld(eintrag, f.massstab(), "bytes"), kacheln = feld(eintrag, f.massstab(), "kacheln");
        if (satzBytes < 0 || kacheln < 0) {
            melde(Component.translatable("heroicmap.download.nicht_angeboten", f.baum()));
            return;
        }
        if (reihe.belegt(f.baum())) {
            melde(Component.translatable("heroicmap.download.verfaellt_belegt", f.baum()));
            return;
        }
        String spielserver = ServerAddress.parseString(server.ip).getHost();
        String host = f.url().getHost();
        Bestaetigt b = bestaetigt.remove(f.baum());
        long ok = b != null && b.massstab() == f.massstab() ? b.bytes() : 0;
        Laden.Auftrag auftrag = new Laden.Auftrag(f.url(), f.token(), f.manifestSha256(), f.bytes(), f.massstab(),
                f.abgleich(), satzBytes, kacheln, verbindung());
        Component frage = switch (f.weg(aktiv(f.baum()), satzBytes, ok, zugestimmt(spielserver, host))) {
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

    /** Zeigt einen Dialog; false, wenn schon einer offen ist. */
    private boolean zeige(Component titel, Component text, Runnable ja) {
        Minecraft mc = Minecraft.getInstance();
        if (dialog != null && mc.gui.screen() == dialog) {
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
        // Nach einem Befehl schliesst der Chat noch; der Dialog kommt danach.
        mc.schedule(() -> {
            if (dialog == neu) {
                mc.gui.setScreen(neu);
            }
        });
        return true;
    }

    private void starte(Freigabe f, Laden.Auftrag auftrag) {
        Path ordner = ordner(f.baum());
        if (ordner == null) {
            return;
        }
        if (!reihe.reihe(f.baum(), () -> lade(f, auftrag, ordner))) {
            melde(Component.translatable("heroicmap.download.verfaellt_belegt", f.baum()));
            return;
        }
        melde(Component.translatable("heroicmap.download.beginnt", groesse(f.bytes())));
    }

    /** Im Thread der Reihe. Das Ergebnis geht in jedem Fall zurück, auch bei einem Error. */
    private void lade(Freigabe f, Laden.Auftrag auftrag, Path ordner) {
        Laden.Ergebnis ergebnis = null;
        Exception fehler = null;
        try {
            if (laden == null) {
                laden = new Laden();
            }
            if (!f.abgleich()) {
                // Das Plugin speichert den Massstab, sobald es das Token ausstellt.
                Files.createDirectories(ordner);
                Files.writeString(ordner.resolve(AKTIV), String.valueOf(f.massstab()));
            }
            ergebnis = laden.lade(auftrag, ordner.resolve(String.valueOf(f.massstab())));
            if (!ergebnis.gekappt()) {
                Laden.behalteNur(ordner, f.massstab());
            }
        } catch (Exception e) {
            fehler = e;
        } finally {
            zurueck(f, ergebnis, fehler);
        }
    }

    private void zurueck(Freigabe f, Laden.Ergebnis ergebnis, Exception fehler) {
        Minecraft.getInstance().execute(() -> beende(f, ergebnis, fehler));
    }

    private void beende(Freigabe f, Laden.Ergebnis ergebnis, Exception fehler) {
        if (ergebnis != null) {
            neuGefragt = false;
            melde(Component.translatable(ergebnis.gekappt() ? "heroicmap.download.gekappt" : "heroicmap.download.fertig",
                    ergebnis.geladen(), ergebnis.gleich(), ergebnis.geloescht(), groesse(ergebnis.bytesGeladen())));
            return;
        }
        if (fehler instanceof Laden.Fehler lf && lf.grund == Laden.Grund.PRUEFSUMME && !neuGefragt && Kanal.offen()) {
            // Zwischen freigabe und Abruf endete ein Lauf; dasselbe Token kommt mit dem neuen Manifest.
            neuGefragt = true;
            if (!f.abgleich()) {
                bestaetigt.put(f.baum(), new Bestaetigt(f.massstab(), f.bytes()));
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

    /** Der Ordner eines Baums auf diesem Server, oder null im Einzelspieler. */
    private static Path ordner(String baum) {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        if (server == null || !Freigabe.baum(baum)) {
            return null;
        }
        return wurzel().resolve(name(server.ip)).resolve(baum);
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
