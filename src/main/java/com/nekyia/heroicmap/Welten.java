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
 * Welcher Hash des Seeds zu welcher Dimension eines Servers gehört, wie der Client ihn beim
 * Betreten gesehen hat; gespeichert in {@code heroicmap/welten.properties}. Paper hat einen Seed
 * je Welt: Ein Baum liegt deshalb unter dem Hash seiner Dimension, nicht unter dem der Welt, in der
 * der Spieler gerade steht. Nur der Render-Thread nutzt das. Siehe docs/download.md, „Ablage“.
 */
final class Welten {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Path datei;
    private final Properties hashes = new Properties();
    private boolean gelesen;

    Welten(Path datei) {
        this.datei = datei;
    }

    /** Der Schlüssel eines Servers: Host und Port, gleich, was die Wahl Ablage nennt. */
    static String server(String adresse) {
        ServerAddress a = ServerAddress.parseString(adresse);
        return Downloads.name(a.getHost() + "_" + a.getPort());
    }

    /** Der Hash der Dimension auf dem Server, oder null, solange der Spieler sie nicht betreten hat. */
    Long hash(String server, String dimension) {
        lies();
        String hex = hashes.getProperty(server + "|" + dimension);
        try {
            return hex == null ? null : HexFormat.fromHexDigitsToLong(hex);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Merkt den Hash der Dimension; geschrieben wird nur, wenn er neu ist. */
    void merke(String server, String dimension, long hash) {
        lies();
        String hex = HexFormat.of().toHexDigits(hash);
        if (hex.equals(hashes.setProperty(server + "|" + dimension, hex))) {
            return;
        }
        try {
            Files.createDirectories(datei.getParent());
            Path tmp = datei.resolveSibling(datei.getFileName() + ".tmp");
            try (Writer raus = Files.newBufferedWriter(tmp)) {
                hashes.store(raus, "Heroic Map: Hash des Seeds je Server und Dimension");
            }
            Files.move(tmp, datei, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn("Heroic Map: {} nicht geschrieben", datei, e);
        }
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
