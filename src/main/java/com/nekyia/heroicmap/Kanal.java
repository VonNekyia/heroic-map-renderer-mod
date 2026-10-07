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
 * Richtungen. Siehe docs/download.md, „Kanal“.
 */
record Kanal(String json) implements CustomPacketPayload {

    static final Type<Kanal> TYPE = new Type<>(Identifier.fromNamespaceAndPath(HeroicMap.ID, "karte"));
    /** Das Plugin schickt weniger als 1 KiB; mehr liest der Mod nicht. */
    static final int MAX = 64 << 10;
    static final StreamCodec<FriendlyByteBuf, Kanal> CODEC = CustomPacketPayload.codec(Kanal::schreibe, Kanal::lies);

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
        return new Kanal(new String(daten, StandardCharsets.UTF_8));
    }

    @Override
    public Type<Kanal> type() {
        return TYPE;
    }

    /** Meldet den Kanal in beide Richtungen an; Fabric sagt es dem Server mit minecraft:register. */
    static void anmelden() {
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
        ClientPlayNetworking.registerGlobalReceiver(TYPE, (nachricht, kontext) -> Downloads.INSTANZ.empfange(nachricht.json()));
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
