package com.nekyia.heroicmap;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;

/**
 * Der Übergang vom Ordner, den ein Server mit {@code karte.von} teilte, zum Zeiger: {@code heroicmap/server.txt} merkt je
 * Adresse, Hash und Server die Karte eines anderen, die er zeigte. Meldet er später seine eigene, setzt der Mod genau
 * einmal einen Zeiger auf die andere; danach ist die Zeile weg. Siehe docs/download.md, „Ablage“.
 */
final class Uebergang {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String DATEI = "server.txt";

    private Uebergang() {
    }

    /** {@code server} zeigt unter {@code adresse} und {@code hash} die Karte {@code karte}; seine eigene merkt es nicht. */
    static void merke(Path wurzel, String adresse, long hash, String server, String karte) {
        if (karte.equals(server)) {
            return;
        }
        String vorn = schluessel(adresse, hash, server);
        List<String> neu = new ArrayList<>();
        for (String zeile : zeilen(wurzel)) {
            if (!zeile.startsWith(vorn)) {
                neu.add(zeile);
            }
        }
        neu.add(vorn + karte);
        schreibe(wurzel, neu);
    }

    /** Zeigte {@code server} dort eine andere Karte, ihr Name, und die Zeile ist danach weg; sonst null. */
    static String einmal(Path wurzel, String adresse, long hash, String server) {
        String vorn = schluessel(adresse, hash, server), karte = null;
        List<String> neu = new ArrayList<>();
        for (String zeile : zeilen(wurzel)) {
            if (zeile.startsWith(vorn)) {
                karte = Kartenname.gueltig(zeile.substring(vorn.length()));
            } else {
                neu.add(zeile);
            }
        }
        if (karte != null) {
            schreibe(wurzel, neu);
        }
        return karte;
    }

    /** Adresse wie bei {@link Servername}, Hash in 16 Stellen hex und Server, mit Leerzeichen dahinter. */
    private static String schluessel(String adresse, long hash, String server) {
        return Servername.schluessel(adresse) + " " + HexFormat.of().toHexDigits(hash) + " " + server + " ";
    }

    private static List<String> zeilen(Path wurzel) {
        Path datei = wurzel.resolve(DATEI);
        try {
            return Files.exists(datei) ? Files.readAllLines(datei, StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static void schreibe(Path wurzel, List<String> zeilen) {
        try {
            Files.createDirectories(wurzel);
            Files.write(wurzel.resolve(DATEI), zeilen, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Heroic Map: {} nicht geschrieben", wurzel.resolve(DATEI), e);
        }
    }
}
