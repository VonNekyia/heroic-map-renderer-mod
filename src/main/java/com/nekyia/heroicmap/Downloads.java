package com.nekyia.heroicmap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/**
 * Der Download der Karte auf der Seite des Spiels: merkt sich das Angebot, lässt den Spieler
 * Host und Grösse bestätigen, lädt im Hintergrund und meldet das Ergebnis.
 * Siehe docs/download.md.
 */
final class Downloads {

    static final Downloads INSTANZ = new Downloads();
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Ein Baum wird ein Ordnername; nur so viel ist erlaubt. */
    private static final Pattern BAUM = Pattern.compile("[a-z0-9_-]{1,64}");

    /** Thread und HttpClient entstehen erst beim ersten Download; beim Trennen wäre es zu früh. */
    private ExecutorService hintergrund;
    private Laden laden;
    /** Das letzte Angebot des Servers, oder null. */
    private JsonObject angebot;
    private boolean laeuft;
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
            case "abgelehnt" -> melde(Component.translatable("heroicmap.download.abgelehnt",
                    json.has("grund") ? json.get("grund").getAsString() : "?"));
            default -> {
            }
        }
    }

    /** Vergisst das Angebot, etwa beim Trennen. */
    void leeren() {
        angebot = null;
        neuGefragt = false;
    }

    JsonObject angebot() {
        return angebot;
    }

    /** Der gespeicherte Massstab eines Baums auf diesem Server, oder 0. */
    int massstab(String baum) {
        Path ordner = ordner(baum);
        if (ordner == null) {
            return 0;
        }
        for (int m : new int[] {4, 2, 1}) {
            if (Files.exists(ordner.resolve(String.valueOf(m)).resolve("etags.txt"))) {
                return m;
            }
        }
        return 0;
    }

    private void freigabe(JsonObject json) {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        if (server == null || laeuft) {
            return;
        }
        String baum, art;
        int massstab;
        long bytes;
        URI url;
        Laden.Auftrag auftrag;
        try {
            baum = json.get("baum").getAsString();
            art = json.get("art").getAsString();
            massstab = json.get("massstab").getAsInt();
            bytes = json.get("bytes").getAsLong();
            url = URI.create(json.get("url").getAsString());
            auftrag = new Laden.Auftrag(url, json.get("token").getAsString(), json.get("manifest_sha256").getAsString(),
                    bytes, massstab);
        } catch (RuntimeException e) {
            melde(Component.translatable("heroicmap.download.unlesbar"));
            return;
        }
        if (!BAUM.matcher(baum).matches() || (massstab != 1 && massstab != 2 && massstab != 4)) {
            melde(Component.translatable("heroicmap.download.unlesbar"));
            return;
        }
        String spielserver = ServerAddress.parseString(server.ip).getHost();
        switch (Adresse.pruefe(url, spielserver)) {
            case SCHEMA -> {
                melde(Component.translatable("heroicmap.download.schema"));
                return;
            }
            case HEIMNETZ -> {
                melde(Component.translatable("heroicmap.download.heimnetz", url.getHost()));
                return;
            }
            case UNBEKANNT -> {
                melde(Component.translatable("heroicmap.download.unbekannt", url.getHost()));
                return;
            }
            case GUT -> {
            }
        }
        boolean zugestimmt = zugestimmt(spielserver, url.getHost());
        if (art.equals("abgleich") && zugestimmt) {
            // Der tägliche Abgleich: Die Zustimmung liegt vor, der Mod lädt im Hintergrund.
            starte(baum, art, auftrag);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Component frage = Component.translatable("heroicmap.download.frage", spielserver, url.getHost(), groesse(bytes),
                massstab);
        mc.gui.setScreen(new ConfirmScreen(ja -> {
            mc.gui.setScreen(null);
            if (ja) {
                stimmeZu(spielserver, url.getHost());
                starte(baum, art, auftrag);
            }
        }, Component.translatable("heroicmap.download.titel"), frage));
    }

    private void starte(String baum, String art, Laden.Auftrag auftrag) {
        Path ordner = ordner(baum);
        if (ordner == null) {
            return;
        }
        laeuft = true;
        if (hintergrund == null) {
            hintergrund = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Heroic Map Laden");
                t.setDaemon(true);
                return t;
            });
            laden = new Laden();
        }
        melde(Component.translatable("heroicmap.download.beginnt", groesse(auftrag.bytes())));
        Path ziel = ordner.resolve(String.valueOf(auftrag.massstab()));
        hintergrund.execute(() -> {
            Laden.Ergebnis ergebnis = null;
            Laden.Fehler fehler = null;
            Exception anderer = null;
            try {
                ergebnis = laden.lade(auftrag, ziel);
                if (!ergebnis.gekappt()) {
                    behalteNur(ordner, auftrag.massstab());
                }
            } catch (Laden.Fehler f) {
                fehler = f;
            } catch (Exception e) {
                anderer = e;
            }
            Laden.Ergebnis fertig = ergebnis;
            Laden.Fehler f = fehler;
            Exception e = anderer;
            Minecraft.getInstance().execute(() -> beende(baum, art, auftrag, fertig, f, e));
        });
    }

    private void beende(String baum, String art, Laden.Auftrag auftrag, Laden.Ergebnis ergebnis, Laden.Fehler fehler,
            Exception anderer) {
        laeuft = false;
        if (ergebnis != null) {
            neuGefragt = false;
            melde(Component.translatable(ergebnis.gekappt() ? "heroicmap.download.gekappt" : "heroicmap.download.fertig",
                    ergebnis.geladen(), ergebnis.gleich(), ergebnis.geloescht(), groesse(ergebnis.bytesGeladen())));
            return;
        }
        if (fehler != null && fehler.grund == Laden.Grund.PRUEFSUMME && !neuGefragt && Kanal.offen()) {
            // Zwischen freigabe und Abruf endete ein Lauf; dasselbe Token kommt mit dem neuen Manifest.
            neuGefragt = true;
            Kanal.frage(baum, auftrag.massstab(), art);
            return;
        }
        LOGGER.warn("Heroic Map: Download abgebrochen", fehler != null ? fehler : anderer);
        melde(Component.translatable("heroicmap.download.fehler",
                fehler != null ? fehler.grund.name() : String.valueOf(anderer)));
    }

    /** Ein Spieler hat je Baum nur einen Massstab; die anderen Sätze fallen weg. */
    private static void behalteNur(Path ordner, int massstab) throws IOException {
        for (int m : new int[] {1, 2, 4}) {
            if (m != massstab) {
                loesche(ordner.resolve(String.valueOf(m)));
            }
        }
    }

    private static void loesche(Path ordner) throws IOException {
        if (!Files.exists(ordner)) {
            return;
        }
        try (Stream<Path> alle = Files.walk(ordner)) {
            for (Path p : alle.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /** Der Ordner eines Baums auf diesem Server, oder null im Einzelspieler. */
    private static Path ordner(String baum) {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        if (server == null || !BAUM.matcher(baum).matches()) {
            return null;
        }
        return wurzel().resolve(name(server.ip)).resolve(baum);
    }

    private static Path wurzel() {
        return FabricLoader.getInstance().getGameDir().resolve(HeroicMap.ID);
    }

    /** Ein Ordnername aus der Adresse des Servers. */
    static String name(String adresse) {
        String name = adresse.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.-]", "_");
        return name.isEmpty() || name.startsWith(".") ? "_" + name : name;
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
    }
}
