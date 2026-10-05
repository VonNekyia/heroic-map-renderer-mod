package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** Meldet Minimap, Tasten und Ereignisse an. Siehe docs/minimap.md. */
public final class HeroicMap implements ClientModInitializer {

    public static final String ID = "heroicmap";

    @Override
    public void onInitializeClient() {
        KeyMapping.Category kategorie = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "karte"));
        KeyMapping zeigen = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.heroicmap.zeigen", InputConstants.KEY_M, kategorie));
        KeyMapping massstab = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.heroicmap.massstab", InputConstants.KEY_N, kategorie));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (zeigen.consumeClick()) {
                Minimap.INSTANZ.umschalten();
            }
            while (massstab.consumeClick()) {
                Minimap.INSTANZ.naechsterMassstab();
            }
        });
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) ->
                Minimap.INSTANZ.markiere(chunk.getPos().x(), chunk.getPos().z()));
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> Minimap.INSTANZ.leeren());
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(ID, "minimap"), Minimap.INSTANZ::zeichne);
    }
}
