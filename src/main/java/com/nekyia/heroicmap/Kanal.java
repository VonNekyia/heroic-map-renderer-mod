package com.nekyia.heroicmap;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ServerboundPlayChannelEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Der Kanal {@code heroicmap:karte} zum Plugin: UTF-8-JSON ohne Längenpräfix, in beide
 * Richtungen. Einen Teil {@code ebene} und eine Antwort {@code tafel} liest schon der Thread des
 * Netzes ({@code teil}, {@code tafel}), alles andere geht als Text weiter. Siehe docs/download.md, „Kanal“.
 */
record Kanal(String json, Ebenen.Teil teil, Tafeln.Antwort tafel) implements CustomPacketPayload {

    static final Type<Kanal> TYPE = new Type<>(Identifier.fromNamespaceAndPath(HeroicMap.ID, "karte"));
    /** Ein Teil einer Ebene hat bis 1 MiB, wenn ein Objekt allein so gross ist; mehr liest der Mod nicht. */
    static final int MAX = 1 << 20;
    static final StreamCodec<FriendlyByteBuf, Kanal> CODEC = CustomPacketPayload.codec(Kanal::schreibe, Kanal::lies);

    Kanal(String json) {
        this(json, null, null);
    }

    private void schreibe(FriendlyByteBuf puffer) {
        puffer.writeBytes(json.getBytes(StandardCharsets.UTF_8));
    }

    private static Kanal lies(FriendlyByteBuf puffer) {
        int n = puffer.readableBytes();
        if (n > MAX) {
            puffer.skipBytes(n);
            return new Kanal("");
        }
        byte[] daten = new byte[n];
        puffer.readBytes(daten);
        String text = new String(daten, StandardCharsets.UTF_8);
        // Bis 1 MiB JSON nicht auf dem Render-Thread; dorthin gehen nur Nadeln, Formen und Tafeln.
        Ebenen.Teil teil = Ebenen.Teil.lies(text);
        if (teil != null) {
            return new Kanal("", teil, null);
        }
        Tafeln.Antwort tafel = Tafeln.Antwort.lies(text);
        return tafel != null ? new Kanal("", null, tafel) : new Kanal(text);
    }

    @Override
    public Type<Kanal> type() {
        return TYPE;
    }

    /** Meldet den Kanal in beide Richtungen an; Fabric sagt es dem Server mit minecraft:register. */
    static void anmelden() {
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
        ClientPlayNetworking.registerGlobalReceiver(TYPE, (nachricht, kontext) -> {
            if (nachricht.teil() != null) {
                if (Ebenen.INSTANZ.teil(nachricht.teil())) {
                    Wegpunkte.INSTANZ.pruefe(Ebenen.INSTANZ, nachricht.teil().id());
                }
            } else if (nachricht.tafel() != null) {
                Tafeln.INSTANZ.antwort(nachricht.tafel());
            } else {
                Downloads.INSTANZ.empfange(nachricht.json());
            }
        });
        // Sobald der Server den Kanal anmeldet, erfährt er die Wahl show.
        ServerboundPlayChannelEvents.REGISTER.register((verbindung, sender, mc, kanaele) -> {
            if (kanaele.contains(TYPE.id())) {
                mc.execute(Kanal::sendeShow);
            }
        });
    }

    /** Schickt die Wahl {@code show}, wenn der Server den Kanal hört; beim Start und nach jeder Änderung. */
    static void sendeShow() {
        if (offen()) {
            ClientPlayNetworking.send(new Kanal(show(Minimap.INSTANZ.show())));
        }
    }

    /** Die Nachricht {@code show}: simplevoicechat oder hidden. Siehe docs/minimap.md, „Mitspieler“. */
    static String show(boolean an) {
        JsonObject json = new JsonObject();
        json.addProperty("v", 1);
        json.addProperty("typ", "show");
        json.addProperty("show", an ? "simplevoicechat" : "hidden");
        return json.toString();
    }

    /** Fragt die Tafel eines Objekts, wenn der Server den Kanal hört; true, wenn die Frage hinausging. Siehe docs/ebenen.md, „Infotafel“. */
    static boolean frageTafel(Tafeln.Ziel z) {
        if (!offen()) {
            return false;
        }
        ClientPlayNetworking.send(new Kanal(tafel(z)));
        return true;
    }

    /** Die Frage {@code tafel}: Ebene, version und Kennung des Objekts. */
    static String tafel(Tafeln.Ziel z) {
        JsonObject json = new JsonObject();
        json.addProperty("v", 1);
        json.addProperty("typ", "tafel");
        json.addProperty("ebene", z.ebene());
        json.addProperty("version", z.version());
        json.addProperty("id", z.id());
        return json.toString();
    }

    /** Hört der Server auf dem Kanal? Das meldet Paper, wenn das Plugin ihn angemeldet hat. */
    static boolean offen() {
        return ClientPlayNetworking.canSend(TYPE);
    }

    /**
     * Fragt einen Satz an: {@code art} ist voll oder abgleich. {@code neu} heisst ein voller
     * Download ohne Stand zum Fortsetzen; dann gibt das Plugin ein neues Token aus.
     */
    static void frage(String baum, int massstab, String art, boolean neu) {
        ClientPlayNetworking.send(new Kanal(anfrage(baum, massstab, art, neu)));
    }

    /** Die {@code anfrage}; {@code neu} steht nur darin, wenn es wahr ist. */
    static String anfrage(String baum, int massstab, String art, boolean neu) {
        JsonObject json = new JsonObject();
        json.addProperty("v", 1);
        json.addProperty("typ", "anfrage");
        json.addProperty("baum", baum);
        json.addProperty("massstab", massstab);
        json.addProperty("art", art);
        if (neu) {
            json.addProperty("neu", true);
        }
        return json.toString();
    }
}
