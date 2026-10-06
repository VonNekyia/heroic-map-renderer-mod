package com.nekyia.heroicmap;

import com.google.gson.JsonObject;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Eine {@code freigabe} des Plugins, geprüft, und ob der Mod vor dem Laden fragt. Ohne
 * Minecraft, damit es sich testen lässt. Siehe docs/download.md, „Zustimmung und Grösse“.
 */
record Freigabe(String baum, String art, int massstab, long bytes, URI url, String token, String manifestSha256) {

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
                json.get("token").getAsString(), json.get("manifest_sha256").getAsString());
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
     * Still lädt nur ein Abgleich im gespeicherten Massstab mit höchstens 11 % des Satzes, oder ein
     * voller Download, dessen Grösse der Spieler vor der {@code anfrage} bestätigt hat, plus 10 %;
     * beides nur mit Zustimmung zum Host. {@code aktiv} ist der gespeicherte Massstab oder 0,
     * {@code satzBytes} die Grösse des Satzes aus dem {@code angebot}, {@code bestaetigt} die vom
     * Spieler bestätigten Bytes für diesen Baum und Massstab oder 0.
     */
    Weg weg(int aktiv, long satzBytes, long bestaetigt, boolean zugestimmt) {
        boolean erwartet = abgleich()
                ? aktiv == massstab && bytes <= satzBytes / 100 * 11
                : bestaetigt > 0 && bytes <= bestaetigt + bestaetigt / 10;
        if (!erwartet) {
            return Weg.FRAGEN;
        }
        return zugestimmt ? Weg.STILL : Weg.HOST;
    }
}
