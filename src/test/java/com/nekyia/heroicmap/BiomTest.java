package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class BiomTest {

    /** In einer Höhle bleibt die letzte Kategorie; {@link Biom#kategorie} gibt dann null. */
    private static final String BLEIBT = "bleibt";
    /** Jedes Biom der Oberwelt in 26.3 (Tag minecraft:is_overworld) und seine Kategorie. */
    private static final Map<String, String> OBERWELT = Map.ofEntries(
            Map.entry("mushroom_fields", "gewaesser"), Map.entry("deep_frozen_ocean", "schnee"), Map.entry("frozen_ocean", "schnee"),
            Map.entry("deep_cold_ocean", "gewaesser"), Map.entry("cold_ocean", "gewaesser"), Map.entry("deep_ocean", "gewaesser"),
            Map.entry("ocean", "gewaesser"), Map.entry("deep_lukewarm_ocean", "gewaesser"), Map.entry("lukewarm_ocean", "gewaesser"),
            Map.entry("warm_ocean", "gewaesser"), Map.entry("stony_shore", "gewaesser"), Map.entry("swamp", "feuchtgebiete"),
            Map.entry("mangrove_swamp", "feuchtgebiete"), Map.entry("snowy_slopes", "schnee"), Map.entry("snowy_plains", "schnee"),
            Map.entry("snowy_beach", "schnee"), Map.entry("windswept_gravelly_hills", "gebirge"), Map.entry("grove", "schnee"),
            Map.entry("windswept_hills", "gebirge"), Map.entry("snowy_taiga", "schnee"), Map.entry("windswept_forest", "gebirge"),
            Map.entry("taiga", "waelder"), Map.entry("plains", "grasland"), Map.entry("meadow", "grasland"),
            Map.entry("beach", "gewaesser"), Map.entry("forest", "waelder"), Map.entry("old_growth_spruce_taiga", "waelder"),
            Map.entry("flower_forest", "waelder"), Map.entry("birch_forest", "waelder"), Map.entry("dark_forest", "waelder"),
            Map.entry("pale_garden", "waelder"), Map.entry("savanna_plateau", "grasland"), Map.entry("savanna", "grasland"),
            Map.entry("jungle", "tropen"), Map.entry("badlands", "wueste"), Map.entry("desert", "wueste"),
            Map.entry("wooded_badlands", "wueste"), Map.entry("jagged_peaks", "schnee"), Map.entry("stony_peaks", "gebirge"),
            Map.entry("frozen_river", "schnee"), Map.entry("river", "gewaesser"), Map.entry("ice_spikes", "schnee"),
            Map.entry("dappled_forest", "waelder"), Map.entry("old_growth_pine_taiga", "waelder"), Map.entry("sunflower_plains", "grasland"),
            Map.entry("old_growth_birch_forest", "waelder"), Map.entry("sparse_jungle", "tropen"), Map.entry("bamboo_jungle", "tropen"),
            Map.entry("eroded_badlands", "wueste"), Map.entry("windswept_savanna", "grasland"), Map.entry("cherry_grove", "waelder"),
            Map.entry("frozen_peaks", "schnee"), Map.entry("dripstone_caves", BLEIBT), Map.entry("lush_caves", BLEIBT),
            Map.entry("sulfur_caves", BLEIBT), Map.entry("deep_dark", BLEIBT));

    @Test
    void jedesBiomDesSpiels() {
        // Die Tags aus den Daten des Spiels und von Fabric API auf dem Klassenpfad, nicht abgeschrieben.
        Set<String> oberwelt = Tags.inhalt("minecraft:is_overworld");
        assertEquals(OBERWELT.keySet(), strip(oberwelt), "ein neues oder fehlendes Biom der Oberwelt");
        assertFalse(Tags.inhalt("c:is_snowy").isEmpty(), "die Tags von Fabric API fehlen auf dem Klassenpfad");
        Tags paper = new Tags(false), fabric = new Tags(true);
        for (String id : oberwelt) {
            // Auf einem Paper-Server kommen nur die Tags des Spiels an, mit Fabric API auf dem Server auch die c:.
            assertEquals(OBERWELT.get(id.substring(10)), kategorie(id, paper.von(id)), id + " ohne c:");
            assertEquals(OBERWELT.get(id.substring(10)), kategorie(id, fabric.von(id)), id + " mit c:");
        }
        // Nether und End nehmen den Rückfall, auch der Wald im Nether.
        Set<String> andere = new HashSet<>(Tags.inhalt("minecraft:is_nether"));
        andere.addAll(Tags.inhalt("minecraft:is_end"));
        assertEquals(10, andere.size());
        for (String id : andere) {
            assertEquals(Biom.GRASLAND, Biom.kategorie(id, fabric.von(id)), id);
        }
    }

    @Test
    void biomeEinesDatapacks() {
        // Echte Namen eines Datapacks mit eigenen Biomen, im Namensraum „beispiel“: einmal nur über den Namen,
        // einmal mit den Tags, die der Datapack ihnen gibt.
        Map<String, Set<String>> tags = Map.of(
                "fungal_caves", Set.of("c:is_mushroom", "c:is_cave"),
                "gravel_desert", Set.of("c:is_snowy", "c:is_desert"),
                "alpha_islands_winter", Set.of(),
                "skylands_winter", Set.of(),
                "hot_shrubland", Set.of());
        Map<String, String> nurName = Map.of("fungal_caves", Biom.GRASLAND, "gravel_desert", "wueste", "alpha_islands_winter", "schnee",
                "skylands_winter", "schnee", "hot_shrubland", "wueste");
        Map<String, String> mitTags = Map.of("fungal_caves", BLEIBT, "gravel_desert", "schnee", "alpha_islands_winter", "schnee",
                "skylands_winter", "schnee", "hot_shrubland", "wueste");
        for (String name : tags.keySet()) {
            assertEquals(nurName.get(name), kategorie("beispiel:" + name, t -> false), name + " nur über den Namen");
            assertEquals(mitTags.get(name), kategorie("beispiel:" + name, tags.get(name)::contains), name + " mit Tags");
        }
    }

    @Test
    void jedeKategorieHatEinenOrdner() {
        for (String[] zeile : Biom.REGELN) {
            assertTrue(Biom.KATEGORIEN.contains(zeile[0]), zeile[0]);
        }
        for (String[] zeile : Biom.WOERTER) {
            assertTrue(Biom.KATEGORIEN.contains(zeile[0]), zeile[0]);
        }
        assertEquals(Set.copyOf(Biom.KATEGORIEN), Biom.ORDNER.keySet());
    }

    @Test
    void rueckfallUeberDenNamen() {
        Predicate<String> ohne = t -> false;
        assertEquals("schnee", Biom.kategorie("beispiel:snowy_cliffs", ohne));
        assertEquals("tropen", Biom.kategorie("beispiel:tropical_beach", ohne));
        assertEquals("gebirge", Biom.kategorie("beispiel:volcanic_peaks", ohne));
        assertEquals(Biom.GRASLAND, Biom.kategorie("beispiel:vulkan", ohne));
        // Gegenprobe: Die Namen der Regeln gelten nur für die Biome des Spiels; ein fremder grove ist Wald.
        assertEquals("waelder", Biom.kategorie("beispiel:grove", ohne));
        assertEquals("schnee", Biom.kategorie("minecraft:grove", ohne));
        // Ein Wort ist ein ganzes Stück des Namens: „icy“ steckt in „spicy“, zählt dort aber nicht.
        assertEquals(Biom.GRASLAND, Biom.kategorie("beispiel:spicy_fields", ohne));
        // Ein Tag geht dem Namen vor.
        assertEquals("tropen", Biom.kategorie("beispiel:snowy_jungle", "minecraft:is_jungle"::equals));
    }

    private static String kategorie(String id, Predicate<String> tag) {
        String k = Biom.kategorie(id, tag);
        return k == null ? BLEIBT : k;
    }

    @Test
    void wechselErstNachZweiSekunden() {
        Biom b = new Biom();
        assertEquals(Biom.GRASLAND, b.gezeigt());
        // Die erste Kategorie gilt sofort, ohne Überblendung.
        b.sieh("waelder", 0);
        assertEquals("waelder", b.gezeigt());
        assertEquals(1, b.anteil(0));
        // Hin und her an einer Grenze: Jeder Rückweg setzt die Wartezeit neu.
        b.sieh("schnee", 100);
        b.sieh("waelder", 1000);
        b.sieh("schnee", 1500);
        b.sieh("schnee", 1500 + Biom.WARTEN_MS - 1);
        assertEquals("waelder", b.gezeigt());
        b.sieh("schnee", 1500 + Biom.WARTEN_MS);
        assertEquals("schnee", b.gezeigt());
        // Danach blendet der alte Rahmen über BLENDE_MS aus.
        long t = 1500 + Biom.WARTEN_MS;
        assertEquals("waelder", b.vorher(t));
        assertEquals(0, b.anteil(t));
        assertEquals(0.5f, b.anteil(t + Biom.BLENDE_MS / 2));
        assertNull(b.vorher(t + Biom.BLENDE_MS));
        assertEquals(1, b.anteil(t + Biom.BLENDE_MS));
    }

    private static Set<String> strip(Set<String> ids) {
        Set<String> aus = new HashSet<>();
        ids.forEach(id -> aus.add(id.substring(10)));
        return aus;
    }

    /** Die Tags der Biome, aufgelöst samt verschachtelter; mit c: aus Fabric API oder nur die des Spiels. */
    private record Tags(boolean mitC) {

        private static final Map<String, Set<String>> GELESEN = new HashMap<>();

        Predicate<String> von(String id) {
            return tag -> (mitC || tag.startsWith("minecraft:")) && inhalt(tag).contains(id);
        }

        static Set<String> inhalt(String t) {
            // Nicht computeIfAbsent: Verschachtelte Tags lesen rekursiv und änderten die Map mitten im Rechnen.
            Set<String> aus = GELESEN.get(t);
            if (aus == null) {
                aus = new HashSet<>();
                String ns = t.substring(0, t.indexOf(':')), pfad = t.substring(t.indexOf(':') + 1);
                try (InputStream rein = BiomTest.class.getResourceAsStream("/data/" + ns + "/tags/worldgen/biome/" + pfad + ".json")) {
                    // Ein c:-Tag, den es nicht gibt, ist leer; einer des Spiels muss da sein.
                    assertTrue(rein != null || "c".equals(ns), t);
                    for (JsonElement e : rein == null ? new JsonArray()
                            : JsonParser.parseString(new String(rein.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("values")) {
                        String wert = e.isJsonObject() ? e.getAsJsonObject().get("id").getAsString() : e.getAsString();
                        if (wert.startsWith("#")) {
                            aus.addAll(inhalt(wert.substring(1)));
                        } else {
                            aus.add(wert);
                        }
                    }
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
                GELESEN.put(t, aus);
            }
            return aus;
        }
    }
}
