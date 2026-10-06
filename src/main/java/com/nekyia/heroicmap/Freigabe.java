package com.nekyia.heroicmap;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Eine {@code freigabe} des Plugins, geprüft, und ob der Mod vor dem Laden fragt. Ohne
 * Minecraft, damit es sich testen lässt. Siehe docs/download.md, „Zustimmung und Grösse“.
 */
record Freigabe(String baum, String art, int massstab, long bytes, URI url, String token, String manifestSha256,
        long abdecktBis) {

    /** Ein Baum wird ein Ordnername; nur so viel ist erlaubt. */
    private static final Pattern BAUM = Pattern.compile("[a-z0-9_-]{1,64}");
    /** Namen, die Windows für Geräte hält, auch mit Endung. */
    private static final Pattern RESERVIERT = Pattern.compile("(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?");

    /** Was vor dem Laden geschieht. */
    enum Weg {
        /** Laden ohne Rückfrage. */
        STILL,
        /** Nur nach der Zustimmung zum Host fragen. */
        HOST,
        /** Nach Host, Grösse und Massstab fragen. */
        FRAGEN
    }

    /** Liest eine {@code freigabe}; wirft, wenn ein Feld fehlt oder nicht passt. */
    static Freigabe lies(JsonObject json) {
        Freigabe f = new Freigabe(json.get("baum").getAsString(), json.get("art").getAsString(),
                json.get("massstab").getAsInt(), json.get("bytes").getAsLong(), URI.create(json.get("url").getAsString()),
                json.get("token").getAsString(), json.get("manifest_sha256").getAsString(),
                json.has("abdeckt_bis") ? json.get("abdeckt_bis").getAsLong() : 0);
        if (!baum(f.baum) || !(f.art.equals("voll") || f.art.equals("abgleich"))
                || (f.massstab != 1 && f.massstab != 2 && f.massstab != 4) || f.bytes < 0 || !Adresse.form(f.url)) {
            throw new IllegalArgumentException("freigabe");
        }
        return f;
    }

    /** Taugt {@code name} als Ordner eines Baums? */
    static boolean baum(String name) {
        return BAUM.matcher(name).matches() && !reserviert(name);
    }

    /** Hält Windows {@code name} für ein Gerät? */
    static boolean reserviert(String name) {
        return RESERVIERT.matcher(name.toLowerCase(Locale.ROOT)).matches();
    }

    boolean abgleich() {
        return art.equals("abgleich");
    }

    /**
     * Ein Satz, wie der Spieler ihn bestätigt hat: Massstab, Grösse und Kacheln. Beim Start eines
     * vollen Downloads liegt er in {@code <baum>/massstab.txt}; ein Abgleich misst daran, nie an
     * Zahlen aus dem letzten {@code angebot}.
     */
    record Stand(int massstab, long bytes, long kacheln) {

        /** Liest den Stand, oder null, wenn keiner lesbar daliegt. */
        static Stand lies(Path datei) {
            try {
                String[] teile = Files.readString(datei, StandardCharsets.UTF_8).trim().split(" ");
                Stand stand = new Stand(Integer.parseInt(teile[0]), Long.parseLong(teile[1]), Long.parseLong(teile[2]));
                return teile.length == 3 && (stand.massstab == 1 || stand.massstab == 2 || stand.massstab == 4)
                        && stand.bytes >= 0 && stand.kacheln >= 0 ? stand : null;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        void schreibe(Path datei) throws IOException {
            Files.createDirectories(datei.getParent());
            Files.writeString(datei, massstab + " " + bytes + " " + kacheln, StandardCharsets.UTF_8);
        }
    }

    /**
     * Woran die {@code freigabe} gemessen wird: ein Abgleich am gespeicherten Stand, ein voller
     * Download an dem, was der Spieler vor der {@code anfrage} bestätigt hat. Null, wenn keiner
     * passt.
     */
    private Stand passend(Stand gespeichert, Stand bestaetigt) {
        Stand stand = abgleich() ? gespeichert : bestaetigt;
        if (stand == null || stand.massstab != massstab) {
            return null;
        }
        long grenze = abgleich() ? stand.bytes / 100 * 11 : stand.bytes + stand.bytes / 10;
        return bytes <= grenze ? stand : null;
    }

    /**
     * Still lädt nur ein Abgleich im gespeicherten Massstab mit höchstens 11 % des Satzes, oder ein
     * voller Download mit höchstens 10 % mehr, als der Spieler bestätigt hat; beides nur mit
     * Zustimmung zum Host. Sonst fragt ein Dialog nach Host, Grösse und Massstab.
     */
    Weg weg(Stand gespeichert, Stand bestaetigt, boolean zugestimmt) {
        if (passend(gespeichert, bestaetigt) == null) {
            return Weg.FRAGEN;
        }
        return zugestimmt ? Weg.STILL : Weg.HOST;
    }

    /**
     * Der Stand, an dem der Download Zeilen und Grösse misst. Passt keiner, hat der Spieler im
     * Dialog {@code bytes} gesehen: Ein voller Download misst daran, ein Abgleich am Zehnfachen,
     * denn sein Deckel ist 10 % des Satzes. Die Kacheln aus dem {@code angebot} gelten nur
     * zusammen mit dieser Grösse, siehe {@link Laden.Auftrag#zeilen()}.
     */
    Stand mass(Stand gespeichert, Stand bestaetigt, long angebotKacheln) {
        Stand stand = passend(gespeichert, bestaetigt);
        if (stand != null) {
            return stand;
        }
        if (abgleich() && gespeichert != null && gespeichert.massstab == massstab) {
            return gespeichert;
        }
        return new Stand(massstab, abgleich() ? Math.min(bytes, Long.MAX_VALUE / 10) * 10 : bytes, angebotKacheln);
    }
}
