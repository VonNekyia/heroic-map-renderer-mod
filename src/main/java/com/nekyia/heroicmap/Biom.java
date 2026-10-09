package com.nekyia.heroicmap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

/**
 * Der Rahmen „biom“: welche Kategorie zum Biom unter dem Spieler gehört, und wann der Rahmen wechselt.
 * Siehe docs/rahmen.md, „Biom“.
 */
final class Biom {

    static final String GRASLAND = "grasland";
    static final List<String> KATEGORIEN = List.of("waelder", GRASLAND, "gebirge", "schnee", "wueste", "tropen", "feuchtgebiete", "gewaesser");
    /** So lange steht der Spieler in einer neuen Kategorie, bevor der Rahmen wechselt; so flackert er an Grenzen nicht. */
    static final long WARTEN_MS = 2000;
    static final long BLENDE_MS = 300;

    /**
     * Je Kategorie in fester Rangfolge Tags ({@code #}) und Biome des Spiels; die erste passende Zeile
     * gewinnt. Schnee steht vor Wald und Grasland vor Gebirge, so ist die verschneite Taiga Schnee und
     * die Wiese Grasland. Die Tags {@code c:} gibt es nur mit Fabric API auf dem Server, darum stehen
     * die Biome ohne Tag des Spiels mit Namen da.
     */
    private static final String[][] REGELN = {
        {"schnee", "#c:is_snowy", "#c:is_icy", "#c:is_aquatic_icy", "snowy_plains", "ice_spikes", "snowy_taiga", "snowy_beach",
            "snowy_slopes", "grove", "frozen_peaks", "jagged_peaks", "frozen_river", "frozen_ocean", "deep_frozen_ocean"},
        {"feuchtgebiete", "#c:is_swamp", "swamp", "mangrove_swamp"},
        {"tropen", "#minecraft:is_jungle"},
        {"wueste", "#c:is_desert", "#minecraft:is_badlands", "desert"},
        {"gewaesser", "#minecraft:is_ocean", "#minecraft:is_river", "#minecraft:is_beach", "#c:is_mushroom", "mushroom_fields"},
        {"waelder", "#minecraft:is_forest", "#minecraft:is_taiga", "cherry_grove"},
        {GRASLAND, "#c:is_plains", "#minecraft:is_savanna", "plains", "sunflower_plains", "meadow"},
        {"gebirge", "#minecraft:is_mountain", "#minecraft:is_hill", "stony_shore"},
    };
    /** Der Rückfall für Biome ohne passenden Tag, etwa aus Datapacks: ein Wort im Namen, in derselben Rangfolge. */
    private static final String[][] WOERTER = {
        {"schnee", "snow", "snowy", "frozen", "ice", "icy", "glacier"},
        {"feuchtgebiete", "swamp", "marsh", "bog", "mangrove"},
        {"tropen", "jungle", "tropical", "rainforest"},
        {"wueste", "desert", "badlands", "mesa", "dunes"},
        {"gewaesser", "ocean", "river", "beach", "lake", "sea"},
        {"waelder", "forest", "taiga", "woods", "woodland", "grove"},
        {GRASLAND, "plains", "meadow", "savanna", "steppe", "prairie"},
        {"gebirge", "mountain", "mountains", "peaks", "hills", "cliffs", "alps"},
    };
    private static final Map<String, TagKey<Biome>> TAGS = new HashMap<>();

    private String gezeigt, kandidat, vorher;
    private long seit, wechsel;
    private Holder<Biome> zuletzt;
    private String kategorieZuletzt;

    /**
     * Die Kategorie des Bioms {@code id}; {@code tag} sagt, ob es im Tag liegt. Nether und End nehmen
     * den Rückfall Grasland, auch wenn ein Wort passte.
     */
    static String kategorie(String id, Predicate<String> tag) {
        if (tag.test("minecraft:is_nether") || tag.test("minecraft:is_end")) {
            return GRASLAND;
        }
        for (String[] regel : REGELN) {
            for (int i = 1; i < regel.length; i++) {
                String e = regel[i];
                if (e.startsWith("#") ? tag.test(e.substring(1)) : id.equals("minecraft:" + e)) {
                    return regel[0];
                }
            }
        }
        List<String> worte = List.of(id.substring(id.indexOf(':') + 1).split("[_/]"));
        for (String[] w : WOERTER) {
            for (int i = 1; i < w.length; i++) {
                if (worte.contains(w[i])) {
                    return w[0];
                }
            }
        }
        return GRASLAND;
    }

    /** Die Kategorie des Bioms unter dem Spieler, neu gerechnet nur, wenn sich das Biom ändert. */
    String kategorie(Holder<Biome> biom) {
        if (biom != zuletzt) {
            String id = biom.unwrapKey().map(k -> k.identifier().toString()).orElse("");
            kategorieZuletzt = kategorie(id, t -> biom.is(TAGS.computeIfAbsent(t, s -> TagKey.create(Registries.BIOME, Identifier.parse(s)))));
            zuletzt = biom;
        }
        return kategorieZuletzt;
    }

    /** Meldet die Kategorie unter dem Spieler zur Zeit {@code ms}; die erste gilt sofort, jede weitere nach {@link #WARTEN_MS}. */
    void sieh(String kategorie, long ms) {
        if (gezeigt == null) {
            gezeigt = kategorie;
        } else if (kategorie.equals(gezeigt)) {
            kandidat = null;
        } else if (!kategorie.equals(kandidat)) {
            kandidat = kategorie;
            seit = ms;
        } else if (ms - seit >= WARTEN_MS) {
            vorher = gezeigt;
            gezeigt = kategorie;
            wechsel = ms;
            kandidat = null;
        }
    }

    String gezeigt() {
        return gezeigt == null ? GRASLAND : gezeigt;
    }

    /** Die Kategorie, aus der gerade übergeblendet wird, oder null. */
    String vorher(long ms) {
        return anteil(ms) < 1 ? vorher : null;
    }

    /** Wie weit die Überblendung zu {@link #gezeigt} ist, 0 bis 1. */
    float anteil(long ms) {
        return vorher == null ? 1 : Math.min(1, (ms - wechsel) / (float) BLENDE_MS);
    }
}
