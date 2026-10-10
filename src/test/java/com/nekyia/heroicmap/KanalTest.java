package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

/** Die anfrage und show an das Plugin. Siehe docs/download.md, „Kanal“. */
class KanalTest {

    @Test
    void anfrageOhneNeu() {
        JsonObject json = JsonParser.parseString(Kanal.anfrage("welt", 4, "voll", false)).getAsJsonObject();
        assertEquals(1, json.get("v").getAsInt());
        assertEquals("anfrage", json.get("typ").getAsString());
        assertEquals("welt", json.get("baum").getAsString());
        assertEquals(4, json.get("massstab").getAsInt());
        assertEquals("voll", json.get("art").getAsString());
        // Ohne Feld gilt das Fortsetzen; ein neu=false schickt der Mod nie.
        assertFalse(json.has("neu"));
    }

    @Test
    void show() {
        JsonObject an = JsonParser.parseString(Kanal.show(true)).getAsJsonObject();
        assertEquals(1, an.get("v").getAsInt());
        assertEquals("show", an.get("typ").getAsString());
        assertEquals("simplevoicechat", an.get("show").getAsString());
        assertEquals("hidden", JsonParser.parseString(Kanal.show(false)).getAsJsonObject().get("show").getAsString());
    }

    @Test
    void tafelDurchDenCodec() {
        // Die Antwort liest schon der Thread des Netzes; der Render-Thread bekommt kein JSON.
        String text = "{\"v\":1,\"typ\":\"tafel\",\"ebene\":\"b:e\",\"version\":\"v\",\"id\":\"a\","
                + "\"panel\":{\"blocks\":[{\"type\":\"title\",\"text\":\"Hafen\"}]}}";
        FriendlyByteBuf rein = new FriendlyByteBuf(Unpooled.wrappedBuffer(text.getBytes(StandardCharsets.UTF_8)));
        Kanal k = Kanal.CODEC.decode(rein);
        assertEquals("", k.json());
        assertNull(k.teil());
        assertEquals(new Tafeln.Ziel("b:e", "v", "a"), k.tafel().ziel());
        assertEquals(new Tafel(java.util.List.of(new Tafel.Titel("Hafen", Tafel.SCHRIFT))), k.tafel().tafel());
        // Die Frage geht als UTF-8-JSON ohne Längenpräfix hinaus.
        FriendlyByteBuf raus = new FriendlyByteBuf(Unpooled.buffer());
        String frage = Kanal.tafel(new Tafeln.Ziel("b:e", "v", "ä"));
        Kanal.CODEC.encode(raus, new Kanal(frage));
        byte[] bytes = new byte[raus.readableBytes()];
        raus.readBytes(bytes);
        assertEquals(frage, new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void anfrageMitNeu() {
        JsonObject json = JsonParser.parseString(Kanal.anfrage("welt", 2, "voll", true)).getAsJsonObject();
        assertTrue(json.get("neu").getAsBoolean());
    }
}
