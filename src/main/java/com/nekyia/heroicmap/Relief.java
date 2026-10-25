package com.nekyia.heroicmap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Das Relief der Kacheln vom Server: aus Kachel, Schicht und Rest jede der drei Arten, genau nach der Regel des Renderers in
 * f32. Ohne Minecraft. Siehe docs/vollbildkarte.md, „Relief“.
 */
final class Relief {

    /** Die drei Arten, mit dem Namen wie in {@code map.json} und in den Einstellungen. */
    enum Art {
        OHNE("ohne"), SLOPE("slope"), SHADOWS("shadows");

        final String name;

        Art(String name) {
            this.name = name;
        }

        /** Die Art zu einem Namen, sonst {@code sonst}. */
        static Art von(String name, Art sonst) {
            for (Art a : values()) {
                if (a.name.equals(name)) {
                    return a;
                }
            }
            return sonst;
        }
    }

    /** Das einzige Muster der Schicht, das der Mod kennt; ein neues Format bekäme ein neues Feld in {@code map.json}. */
    static final String SCHICHT = "relief/{z}/{x}/{y}.webp";

    /** Was ein Satz mitbringt: die Art in seinen Kacheln und ob seine Schicht im bekannten Format daliegt. */
    record Satzrelief(Art gebacken, boolean schicht) {

        /** Muss der Mod rechnen, um {@code gewaehlt} zu zeigen? Nur mit der Schicht und einer anderen Art als gebacken. */
        boolean noetig(Art gewaehlt) {
            return schicht && gewaehlt != gebacken;
        }
    }

    private Relief() {
    }

    /**
     * Liest {@code relief} und {@code reliefTiles} aus {@code map.json} im Ordner des Massstabs; ohne lesbare Datei kein
     * Relief. Siehe docs/vollbildkarte.md, „Relief“.
     */
    static Satzrelief von(Path ordner) {
        try {
            JsonObject karte = JsonParser.parseString(Files.readString(ordner.resolve("map.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            Art gebacken = karte.has("relief") ? Art.von(karte.get("relief").getAsString(), null) : Art.OHNE;
            if (gebacken == null || gebacken == Art.OHNE) {
                return new Satzrelief(Art.OHNE, false);
            }
            return new Satzrelief(gebacken, karte.has("reliefTiles") && SCHICHT.equals(karte.get("reliefTiles").getAsString()));
        } catch (IOException | RuntimeException e) {
            return new Satzrelief(Art.OHNE, false);
        }
    }

    /**
     * Der Faktor einer Art bei einem Code der Schicht: 1 + 0,12 · (n / 2) mit n = (code mod 9) − 4, begrenzt auf 0,76 bis
     * 1,24, mit {@code SHADOWS} ab Code 9 mal 0,75; ohne Relief 1. Ein Code über 17 gilt wie 4. In derselben Folge wie der
     * Renderer, so trifft das letzte Bit.
     */
    static float faktor(int code, Art art) {
        if (art == Art.OHNE) {
            return 1;
        }
        int c = code > 17 ? 4 : code;
        float f = Math.clamp(1f + 0.12f * ((c % 9 - 4) / 2f), 1f - 0.24f, 1f + 0.24f);
        return art == Art.SHADOWS && c >= 9 ? f * 0.75f : f;
    }

    /**
     * Rechnet die Kachel {@code kachel} (ARGB, Art {@code gebacken}) in die Art {@code gewaehlt} um, an Ort und Stelle: je
     * Pixel mit Alpha über 0 die Farbe ohne Relief aus dem Rest, wo er Alpha 255 hat, sonst je Kanal round(k / f) mit dem
     * Faktor der Kachel; dann mal den Faktor der gewählten Art, gerundet und bei 255 gekappt. Alpha bleibt. Der Code steht
     * in Rot der Schicht; ohne Schicht gilt 4, ohne Rest keiner.
     */
    static void rechne(int[] kachel, int[] schicht, int[] rest, Art gebacken, Art gewaehlt) {
        for (int p = 0; p < kachel.length; p++) {
            int argb = kachel[p];
            if (argb >>> 24 == 0) {
                continue;
            }
            int code = schicht == null ? 4 : schicht[p] >> 16 & 0xFF;
            boolean ausRest = rest != null && rest[p] >>> 24 == 255;
            float fk = faktor(code, gebacken), fg = faktor(code, gewaehlt);
            int neu = argb & 0xFF000000;
            for (int s = 16; s >= 0; s -= 8) {
                int ohne = ausRest ? rest[p] >> s & 0xFF : Math.round((argb >> s & 0xFF) / fk);
                neu |= Math.min(255, Math.round(ohne * fg)) << s;
            }
            kachel[p] = neu;
        }
    }
}
