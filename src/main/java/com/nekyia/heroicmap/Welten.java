package com.nekyia.heroicmap;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.slf4j.Logger;

/**
 * Welcher Hash des Seeds zu welcher Dimension gehört, wie der Client ihn beim Betreten gesehen
 * hat; gespeichert in {@code heroicmap/welten.properties}. Paper hat einen Seed je Welt: Ein Baum
 * liegt deshalb unter dem Hash seiner Dimension, nicht unter dem der Welt, in der der Spieler steht.
 * Hinter einem Proxy hat jedes Backend dieselbe Adresse; die Paare einer Sitzung, von einem Login
 * zum nächsten, bilden deshalb eine Gruppe. Nur der Render-Thread nutzt das.
 * Siehe docs/download.md, „Ablage“.
 */
final class Welten {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** So viele Gruppen behält der Mod je Server, die zuletzt benutzten; Weltresets häufen sonst immer mehr an. */
    static final int GRUPPEN = 16;

    private final Path datei;
    /**
     * Schlüssel {@code <server>|<gruppe>|<dimension>}, Wert der Hash, 16 Stellen hex; dazu
     * {@code <server>|<gruppe>|#}, wann die Gruppe zuletzt eine Sitzung bekam, als laufende Zahl.
     */
    private final Properties hashes = new Properties();
    private boolean gelesen;
    /** Die Gruppe dieser Sitzung und ihr Server; -1, bis der Spieler nach dem Login ein Level betritt. */
    private int gruppe = -1;
    private String sitzung;

    Welten(Path datei) {
        this.datei = datei;
    }

    /** Der Schlüssel eines Servers: Host und Port, gleich, was die Wahl Ablage nennt. */
    static String server(String adresse) {
        ServerAddress a = ServerAddress.parseString(adresse);
        return Downloads.name(a.getHost() + "_" + a.getPort());
    }

    /** Ein neuer Login, auch der Wechsel des Backends hinter einem Proxy: Welche Gruppe gilt, sagt erst das nächste Level. */
    void neueSitzung() {
        gruppe = -1;
        sitzung = null;
    }

    /**
     * Der Spieler betritt eine Dimension. Das erste Paar aus Dimension und Hash nach einem Login
     * wählt die gespeicherte Gruppe, die genau dieses Paar kennt, sonst eine neue; jedes weitere
     * Paar der Sitzung kommt in sie. Geschrieben wird nur, was neu ist.
     */
    void merke(String server, String dimension, long hash) {
        lies();
        String hex = HexFormat.of().toHexDigits(hash);
        boolean neu = false;
        if (gruppe < 0 || !server.equals(sitzung)) {
            sitzung = server;
            gruppe = gruppe(server, dimension, hex);
            hashes.setProperty(server + "|" + gruppe + "|#", Long.toString(zuletzt(server) + 1));
            raeume(server);
            neu = true;
        }
        if (hex.equals(hashes.setProperty(server + "|" + gruppe + "|" + dimension, hex)) && !neu) {
            return;
        }
        try {
            Files.createDirectories(datei.getParent());
            Path tmp = datei.resolveSibling(datei.getFileName() + ".tmp");
            try (Writer raus = Files.newBufferedWriter(tmp)) {
                hashes.store(raus, "Heroic Map: Hash des Seeds je Server, Sitzungsgruppe und Dimension");
            }
            Files.move(tmp, datei, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn("Heroic Map: {} nicht geschrieben", datei, e);
        }
    }

    /**
     * Der Hash der Dimension in der Gruppe dieser Sitzung, oder null: vor dem ersten Level, auf
     * einem anderen Server oder für eine Dimension, die der Spieler auf diesem Backend nie betrat.
     */
    Long hash(String server, String dimension) {
        lies();
        if (gruppe < 0 || !server.equals(sitzung)) {
            return null;
        }
        String hex = hashes.getProperty(server + "|" + gruppe + "|" + dimension);
        try {
            return hex == null ? null : HexFormat.fromHexDigitsToLong(hex);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Die höchste laufende Zahl einer Gruppe des Servers, 0 ohne. */
    private long zuletzt(String server) {
        long hoechste = 0;
        for (String schluessel : hashes.stringPropertyNames()) {
            if (schluessel.startsWith(server + "|") && schluessel.endsWith("|#")) {
                try {
                    hoechste = Math.max(hoechste, Long.parseLong(hashes.getProperty(schluessel)));
                } catch (NumberFormatException e) {
                    // Zählt nicht.
                }
            }
        }
        return hoechste;
    }

    /** Behält je Server die {@link #GRUPPEN} zuletzt benutzten Gruppen; die anderen gehen ganz. */
    private void raeume(String server) {
        Map<String, Long> wann = new HashMap<>();
        for (String schluessel : hashes.stringPropertyNames()) {
            String[] teile = schluessel.split("\\|", 3);
            if (teile.length == 3 && teile[0].equals(server)) {
                long z = 0;
                try {
                    z = teile[2].equals("#") ? Long.parseLong(hashes.getProperty(schluessel)) : 0;
                } catch (NumberFormatException e) {
                    // Eine Gruppe ohne lesbare Zahl gilt als die älteste.
                }
                wann.merge(teile[1], z, Math::max);
            }
        }
        if (wann.size() <= GRUPPEN) {
            return;
        }
        Set<String> weg = new HashSet<>(wann.keySet());
        wann.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(GRUPPEN).forEach(e -> weg.remove(e.getKey()));
        for (String schluessel : hashes.stringPropertyNames()) {
            String[] teile = schluessel.split("\\|", 3);
            if (teile.length == 3 && teile[0].equals(server) && weg.contains(teile[1])) {
                hashes.remove(schluessel);
            }
        }
    }

    /** Für den Test: wie viele Gruppen der Server hat. */
    int gruppen(String server) {
        lies();
        Set<String> gruppen = new HashSet<>();
        for (String schluessel : hashes.stringPropertyNames()) {
            String[] teile = schluessel.split("\\|", 3);
            if (teile.length == 3 && teile[0].equals(server)) {
                gruppen.add(teile[1]);
            }
        }
        return gruppen.size();
    }

    /** Die kleinste Gruppe des Servers, die das Paar kennt, sonst die nächste freie Nummer. */
    private int gruppe(String server, String dimension, String hex) {
        int beste = Integer.MAX_VALUE, frei = 0;
        for (String schluessel : hashes.stringPropertyNames()) {
            String[] teile = schluessel.split("\\|", 3);
            if (teile.length != 3 || !teile[0].equals(server)) {
                continue;
            }
            int g;
            try {
                g = Integer.parseInt(teile[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            frei = Math.max(frei, g + 1);
            if (teile[2].equals(dimension) && hex.equals(hashes.getProperty(schluessel))) {
                beste = Math.min(beste, g);
            }
        }
        return beste != Integer.MAX_VALUE ? beste : frei;
    }

    private void lies() {
        if (gelesen) {
            return;
        }
        gelesen = true;
        if (Files.exists(datei)) {
            try (Reader rein = Files.newBufferedReader(datei)) {
                hashes.load(rein);
            } catch (IOException | IllegalArgumentException e) {
                LOGGER.warn("Heroic Map: {} nicht lesbar", datei, e);
            }
        }
    }
}
