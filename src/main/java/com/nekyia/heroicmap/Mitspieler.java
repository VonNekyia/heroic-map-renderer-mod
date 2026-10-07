package com.nekyia.heroicmap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.Player;

/**
 * Die anderen Spieler, die der Server zeigen lässt: wer den Spieler in Simple Voice Chat hört.
 * Der Server entscheidet; der Mod zeigt nur, was in der letzten Nachricht {@code spieler} stand.
 * Siehe docs/minimap.md, „Mitspieler“.
 */
final class Mitspieler {

    static final Mitspieler INSTANZ = new Mitspieler();
    /** So lange gilt eine Liste ohne neue Nachricht, in ms. */
    static final long FRIST = 5000;
    /** Mehr Einträge liest der Mod nicht. */
    static final int HOECHSTENS = 256;
    /** Wie das Spiel Namen von Spielern zulässt: druckbares ASCII ohne Leerzeichen, bis 16 Zeichen. */
    private static final Pattern NAME = Pattern.compile("[!-~]{1,16}");

    /** Ein Spieler und wo er steht. */
    record Eintrag(UUID uuid, String name, String dimension, double x, double z) {
    }

    private volatile List<Eintrag> liste = List.of();
    private volatile long empfangen;
    /** Warum der Server die Wahl show ablehnt, oder null; das Menü zeigt es. */
    private volatile String verweigert;

    void empfange(JsonObject json, long jetztMs) {
        liste = lies(json);
        empfangen = jetztMs;
    }

    /** Liest die Liste; ein unlesbarer Eintrag fällt weg, nicht die ganze Nachricht. */
    static List<Eintrag> lies(JsonObject json) {
        List<Eintrag> neu = new ArrayList<>();
        JsonArray spieler = json.has("spieler") && json.get("spieler").isJsonArray()
                ? json.getAsJsonArray("spieler") : new JsonArray();
        for (JsonElement element : spieler) {
            if (neu.size() >= HOECHSTENS) {
                break;
            }
            try {
                JsonObject o = element.getAsJsonObject();
                String name = o.get("name").getAsString();
                double x = o.get("x").getAsDouble(), z = o.get("z").getAsDouble();
                if (!NAME.matcher(name).matches() || !Double.isFinite(x) || !Double.isFinite(z)) {
                    continue;
                }
                neu.add(new Eintrag(UUID.fromString(o.get("uuid").getAsString()), name,
                        o.get("dimension").getAsString(), x, z));
            } catch (RuntimeException kaputt) {
                // Nur dieser Eintrag fällt weg.
            }
        }
        return List.copyOf(neu);
    }

    /** Die Liste, solange sie gilt; nach {@link #FRIST} ohne Nachricht leer. */
    List<Eintrag> aktuell(long jetztMs) {
        return jetztMs - empfangen > FRIST ? List.of() : liste;
    }

    /** Was Minimap und Vollbildkarte zeichnen: die gültige Liste, bei show hidden nichts. */
    List<Eintrag> sichtbar(long jetztMs) {
        return Minimap.INSTANZ.show() ? aktuell(jetztMs) : List.of();
    }

    void leeren() {
        liste = List.of();
        empfangen = 0;
        verweigert = null;
    }

    /** Die Antwort auf show: erlaubt, oder ein Grund wie permission oder simplevoicechat. */
    void antwort(JsonObject json) {
        boolean erlaubt = json.has("erlaubt") && json.get("erlaubt").isJsonPrimitive() && json.get("erlaubt").getAsBoolean();
        if (erlaubt) {
            verweigert = null;
        } else {
            String grund = json.has("grund") && json.get("grund").isJsonPrimitive() ? json.get("grund").getAsString() : "";
            verweigert = grund.equals("permission") || grund.equals("simplevoicechat") ? grund : "sonst";
        }
    }

    String verweigert() {
        return verweigert;
    }

    /** x und z: hat der Client den Spieler als Entity mit dieser UUID, dessen Lage, die ist flüssiger; sonst die des Servers. */
    static double[] lage(Minecraft mc, Eintrag e) {
        Player p = mc.level == null ? null : mc.level.getPlayerByUUID(e.uuid());
        return p != null ? new double[] {p.getX(), p.getZ()} : new double[] {e.x(), e.z()};
    }

    /** Der Kopf aus dem Skin, {@code groesse} Einheiten gross, die Mitte bei (x, y); ohne Skin ein weisses Quadrat. */
    static void kopf(GuiGraphicsExtractor g, Minecraft mc, Eintrag e, int x, int y, int groesse) {
        ClientPacketListener verbindung = mc.getConnection();
        PlayerInfo info = verbindung == null ? null : verbindung.getPlayerInfo(e.uuid());
        g.fill(x - groesse / 2 - 1, y - groesse / 2 - 1, x + groesse / 2 + 1, y + groesse / 2 + 1, 0xFF000000);
        if (info != null) {
            PlayerFaceExtractor.extractRenderState(g, info.getSkin(), x - groesse / 2, y - groesse / 2, groesse);
        } else {
            g.fill(x - groesse / 2, y - groesse / 2, x + groesse / 2, y + groesse / 2, 0xFFFFFFFF);
        }
    }
}
