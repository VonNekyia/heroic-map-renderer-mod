package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Die Projektion gegen projektion.json des Renderers. Siehe docs/projektion.md. */
class ProjektionTest {

    private static final URI FIXTURE = URI.create(
            "https://raw.githubusercontent.com/VonNekyia/heroic-map-renderer/master/renderer/tests/fixtures/projektion.json");

    @Test
    void wieTopNorthDesRenderers() throws Exception {
        HttpResponse<String> antwort = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(FIXTURE).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, antwort.statusCode(), "projektion.json nicht geladen: " + FIXTURE);

        int geprueft = 0;
        for (JsonElement element : JsonParser.parseString(antwort.body()).getAsJsonArray()) {
            JsonObject eintrag = element.getAsJsonObject();
            if (!eintrag.get("camera").getAsString().equals("top-north")
                    || !eintrag.get("direction").getAsString().equals("s")) {
                continue;
            }
            int scale = eintrag.get("scale").getAsInt();
            JsonArray block = eintrag.getAsJsonArray("block");
            JsonArray pixel = eintrag.getAsJsonArray("pixel");
            assertEquals(pixel.get(0).getAsDouble(), Projektion.zuPixel(block.get(0).getAsDouble(), scale), eintrag::toString);
            assertEquals(pixel.get(1).getAsDouble(), Projektion.zuPixel(block.get(2).getAsDouble(), scale), eintrag::toString);
            geprueft++;
        }
        assertTrue(geprueft > 0, "keine Einträge für top-north aus s");
    }
}
