package com.nekyia.heroicmap;

import com.google.gson.JsonElement;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Meldet Minimap, Download, Tasten, Befehle und Ereignisse an. Siehe docs/minimap.md, docs/download.md. */
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
        // Fabric meldet den Wechsel der Welt nur zu einer neuen; das Trennen eigens, und das auf
        // einem Thread von Netty, deshalb auf den Render-Thread.
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> Minimap.INSTANZ.leeren());
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> client.execute(() -> {
            Minimap.INSTANZ.leeren();
            Downloads.INSTANZ.leeren();
        }));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(ID, "minimap"), Minimap.INSTANZ::zeichne);
        Kanal.anmelden();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, kontext) -> dispatcher.register(
                ClientCommands.literal(ID)
                        .then(ClientCommands.literal("angebot").executes(HeroicMap::angebot))
                        .then(ClientCommands.literal("laden")
                                .then(ClientCommands.argument("baum", StringArgumentType.word())
                                        .then(ClientCommands.argument("massstab", IntegerArgumentType.integer(1, 4))
                                                .executes(HeroicMap::laden))))
                        .then(ClientCommands.literal("abgleich")
                                .then(ClientCommands.argument("baum", StringArgumentType.word())
                                        .executes(HeroicMap::abgleich)))));
    }

    private static int angebot(CommandContext<FabricClientCommandSource> c) {
        Downloads.INSTANZ.zeilen().forEach(c.getSource()::sendFeedback);
        return 1;
    }

    /** Fragt einen vollen Download an; vorher sagt der Mod, dass er zählt. */
    private static int laden(CommandContext<FabricClientCommandSource> c) {
        String baum = StringArgumentType.getString(c, "baum");
        int massstab = IntegerArgumentType.getInteger(c, "massstab");
        if (massstab == 3) {
            c.getSource().sendError(Component.translatable("heroicmap.befehl.massstab"));
            return 0;
        }
        if (!Kanal.offen() || !angeboten(baum)) {
            c.getSource().sendError(Component.translatable("heroicmap.befehl.unbekannt", baum));
            return 0;
        }
        c.getSource().sendFeedback(Component.translatable("heroicmap.befehl.voll"));
        int alt = Downloads.INSTANZ.massstab(baum);
        if (alt != 0 && alt != massstab) {
            c.getSource().sendFeedback(Component.translatable("heroicmap.befehl.wechsel", alt, massstab));
        }
        Kanal.frage(baum, massstab, "voll");
        return 1;
    }

    /** Fragt einen Abgleich von Hand an, im gespeicherten Massstab. */
    private static int abgleich(CommandContext<FabricClientCommandSource> c) {
        String baum = StringArgumentType.getString(c, "baum");
        int massstab = Downloads.INSTANZ.massstab(baum);
        if (massstab == 0) {
            c.getSource().sendError(Component.translatable("heroicmap.befehl.kein_satz", baum));
            return 0;
        }
        if (!Kanal.offen() || !angeboten(baum)) {
            c.getSource().sendError(Component.translatable("heroicmap.befehl.unbekannt", baum));
            return 0;
        }
        Kanal.frage(baum, massstab, "abgleich");
        return 1;
    }

    private static boolean angeboten(String baum) {
        var angebot = Downloads.INSTANZ.angebot();
        if (angebot == null || !angebot.has("baeume")) {
            return false;
        }
        for (JsonElement e : angebot.getAsJsonArray("baeume")) {
            if (baum.equals(e.getAsJsonObject().get("id").getAsString())) {
                return true;
            }
        }
        return false;
    }
}
