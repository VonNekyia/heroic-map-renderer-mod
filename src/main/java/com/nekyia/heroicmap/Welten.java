package com.nekyia.heroicmap;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HexFormat;
import java.util.Properties;
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

    private final Path datei;
    /** Schlüssel {@code <server>|<gruppe>|<dimension>}, Wert der Hash, 16 Stellen hex. */
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
        if (gruppe < 0 || !server.equals(sitzung)) {
            sitzung = server;
            gruppe = gruppe(server, dimension, hex);
        }
        if (hex.equals(hashes.setProperty(server + "|" + gruppe + "|" + dimension, hex))) {
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
