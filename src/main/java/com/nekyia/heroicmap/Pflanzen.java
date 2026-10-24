package com.nekyia.heroicmap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.Reader;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Welche Zustände Bodenpflanzen sind: Jedes Modell des Zustands erbt über {@code parent} von einer Vorlage des Kreuzes,
 * der Feldfrucht, des Seegrases, der Blütenteppiche oder des Laubs am Boden; gelesen aus den Packs des Spielers.
 * Siehe docs/minimap.md, „Pflanzen von oben“.
 */
final class Pflanzen {

    /** Tiefer geht keine {@code parent}-Kette; eine längere gilt als Schleife. */
    private static final int TIEFE = 32;

    /** Die Ergebnisse eines Stands der Packs; ein Neuladen nimmt einen neuen, was danach in den alten fällt, zählt nicht. */
    private record Merker(Map<BlockState, Boolean> zustaende, Map<Identifier, Boolean> modelle) {
        Merker() {
            this(new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        }
    }

    private static volatile Merker merker = new Merker();

    private Pflanzen() {
    }

    /** Nach dem Neuladen der Packs; der Worker liest danach neu. */
    static void neuGeladen() {
        merker = new Merker();
    }

    /** Ist der Zustand eine Bodenpflanze? Je Block liest der Mod die Blockstate-Datei einmal, je Modell die Kette einmal. */
    static boolean bodenpflanze(BlockState state) {
        Merker m = merker;
        Boolean ja = m.zustaende().get(state);
        if (ja == null) {
            ResourceManager packs = Minecraft.getInstance().getResourceManager();
            Function<Identifier, Identifier> eltern = id -> eltern(packs, id);
            Block block = state.getBlock();
            eintragen(block.getStateDefinition().getPossibleStates(), lies(packs, block), id -> erbt(id, eltern, m.modelle()),
                    m.zustaende());
            ja = m.zustaende().get(state);
        }
        return ja;
    }

    /**
     * Trägt jeden Zustand des Blocks ein: eine Bodenpflanze, wenn er Modelle hat und jedes erbt. Ohne Datei oder ohne
     * Eintrag für den Zustand keine; so liest kein weiterer Zustand desselben Blocks die Dateien noch einmal.
     */
    static <S> void eintragen(Collection<S> alle, Map<S, Set<Identifier>> modelle, Predicate<Identifier> erbt, Map<S, Boolean> ziel) {
        for (S s : alle) {
            Set<Identifier> m = modelle.getOrDefault(s, Set.of());
            ziel.put(s, !m.isEmpty() && m.stream().allMatch(erbt));
        }
    }

    /**
     * Je Zustand des Blocks die Modelle aus der untersten Quelle, dem Spiel oder dem Mod, der den Block bringt; ein
     * Ressourcenpaket darüber zählt nicht. Leer bei jedem Fehler. Siehe docs/minimap.md, „Pflanzen aus Paketen“.
     */
    private static Map<BlockState, Set<Identifier>> lies(ResourceManager packs, Block block) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        Map<BlockState, Set<Identifier>> aus = new HashMap<>();
        try {
            for (Resource r : packs.getResourceStack(id.withPath(p -> "blockstates/" + p + ".json")).stream().limit(1).toList()) {
                JsonElement json;
                try (Reader rein = r.openAsReader()) {
                    json = JsonParser.parseReader(rein);
                }
                Optional<BlockStateModelDispatcher> d = BlockStateModelDispatcher.CODEC.parse(JsonOps.INSTANCE, json).result();
                if (d.isEmpty()) {
                    continue;
                }
                for (Map.Entry<BlockState, BlockStateModel.UnbakedRoot> e : d.get().instantiate(block.getStateDefinition(), id::toString).entrySet()) {
                    Set<Identifier> modelle = new HashSet<>();
                    e.getValue().resolveDependencies(modelle::add);
                    aus.put(e.getKey(), modelle);
                }
            }
        } catch (Exception e) {
            return Map.of();
        }
        return aus;
    }

    /** Der {@code parent} der Modelldatei aus der untersten Quelle, wie bei {@link #lies}, oder null. */
    private static Identifier eltern(ResourceManager packs, Identifier modell) {
        Optional<Resource> r = packs.getResourceStack(modell.withPath(p -> "models/" + p + ".json")).stream().findFirst();
        if (r.isEmpty()) {
            return null;
        }
        try (Reader rein = r.get().openAsReader()) {
            JsonObject json = JsonParser.parseReader(rein).getAsJsonObject();
            return json.has("parent") ? Identifier.parse(json.get("parent").getAsString()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Erbt das Modell, es selbst eingeschlossen, von einer {@link #vorlage}? Je Modell einmal, im {@code merker}. */
    static boolean erbt(Identifier modell, Function<Identifier, Identifier> eltern, Map<Identifier, Boolean> merker) {
        return erbt(modell, eltern, merker, 0);
    }

    private static boolean erbt(Identifier modell, Function<Identifier, Identifier> eltern, Map<Identifier, Boolean> merker, int tiefe) {
        Boolean ja = merker.get(modell);
        if (ja != null) {
            return ja;
        }
        boolean erbt = vorlage(modell);
        if (!erbt && tiefe < TIEFE) {
            Identifier e = eltern.apply(modell);
            erbt = e != null && erbt(e, eltern, merker, tiefe + 1);
        }
        merker.put(modell, erbt);
        return erbt;
    }

    /** Die Vorlagen der Bodenpflanzen im Spiel, dieselben wie im Renderer. */
    static boolean vorlage(Identifier modell) {
        String p = modell.getPath();
        return modell.getNamespace().equals("minecraft")
                && (p.equals("block/cross") || p.equals("block/tinted_cross") || p.equals("block/cross_emissive")
                        || p.equals("block/crop") || p.equals("block/template_seagrass")
                        || p.startsWith("block/flowerbed_") || p.startsWith("block/template_leaf_litter_"));
    }
}
