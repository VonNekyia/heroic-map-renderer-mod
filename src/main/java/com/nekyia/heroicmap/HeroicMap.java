package com.nekyia.heroicmap;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Meldet Minimap, Download, Tasten, Befehle, Menü und Ereignisse an. Siehe docs/minimap.md, docs/download.md. */
public final class HeroicMap implements ClientModInitializer {

    public static final String ID = "heroicmap";
    /** Die Taste der Vollbildkarte; die Karte schliesst sich mit ihr. */
    static KeyMapping karte;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category kategorie = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "karte"));
        // Vorbelegt ist nur die Vollbildkarte; die Minimap stellt das Menü hinter /hmap ein.
        KeyMapping zeigen = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.heroicmap.zeigen", InputConstants.UNKNOWN.getValue(), kategorie));
        KeyMapping zoom = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.heroicmap.zoom", InputConstants.UNKNOWN.getValue(), kategorie));
        karte = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.heroicmap.karte", InputConstants.KEY_PERIOD, kategorie));
        Minimap.INSTANZ.lies(einstellungen());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean geaendert = false;
            while (zeigen.consumeClick()) {
                Minimap.INSTANZ.umschalten();
                geaendert = true;
            }
            while (zoom.consumeClick()) {
                Minimap.INSTANZ.naechsterZoom();
                geaendert = true;
            }
            if (geaendert) {
                Minimap.INSTANZ.schreibe(einstellungen());
            }
            Live.INSTANZ.arbeite(client);
            while (karte.consumeClick()) {
                if (client.gui.screen() == null && client.level != null) {
                    String dimension = client.level.dimension().identifier().toString();
                    client.gui.setScreen(new Karte(Satz.fuer(Downloads.serverOrdner(), dimension)));
                }
            }
        });
        // Fabric meldet den Wechsel der Welt nur zu einer neuen; das Trennen eigens, und das auf
        // einem Thread von Netty, deshalb auf den Render-Thread.
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> {
            Minimap.INSTANZ.leeren();
            Live.INSTANZ.leeren();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> client.execute(() -> {
            Minimap.INSTANZ.leeren();
            Live.INSTANZ.leeren();
            Downloads.INSTANZ.leeren();
        }));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(ID, "minimap"), Minimap.INSTANZ::zeichne);
        Kanal.anmelden();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, kontext) -> dispatcher.register(
                ClientCommands.literal("hmap")
                        .executes(HeroicMap::menue)
                        .then(ClientCommands.literal("angebot").executes(HeroicMap::angebot))
                        .then(ClientCommands.literal("laden")
                                .then(ClientCommands.argument("baum", StringArgumentType.word())
                                        .then(ClientCommands.argument("massstab", IntegerArgumentType.integer(1, 4))
                                                .executes(HeroicMap::laden))))
                        .then(ClientCommands.literal("abgleich")
                                .then(ClientCommands.argument("baum", StringArgumentType.word())
                                        .executes(HeroicMap::abgleich)))));
    }

    /** Öffnet das Menü; nach einem Befehl schliesst der Chat noch, das Menü kommt danach. */
    private static int menue(CommandContext<FabricClientCommandSource> c) {
        Minecraft mc = c.getSource().getClient();
        mc.schedule(() -> mc.gui.setScreen(new Einstellungen()));
        return 1;
    }

    /** Die Datei der Einstellungen im Ordner config. */
    static Path einstellungen() {
        return FabricLoader.getInstance().getConfigDir().resolve(ID + ".properties");
    }

    private static int angebot(CommandContext<FabricClientCommandSource> c) {
        Downloads.INSTANZ.zeilen().forEach(c.getSource()::sendFeedback);
        return 1;
    }

    /** Fragt einen vollen Download an, nach einem Dialog mit Grösse und Massstab. */
    private static int laden(CommandContext<FabricClientCommandSource> c) {
        String baum = StringArgumentType.getString(c, "baum");
        int massstab = IntegerArgumentType.getInteger(c, "massstab");
        if (massstab == 3) {
            c.getSource().sendError(Component.translatable("heroicmap.befehl.massstab"));
            return 0;
        }
        Component fehler = Downloads.INSTANZ.frageVoll(baum, massstab);
        if (fehler != null) {
            c.getSource().sendError(fehler);
            return 0;
        }
        return 1;
    }

    /** Fragt einen Abgleich von Hand an, im gespeicherten Massstab. */
    private static int abgleich(CommandContext<FabricClientCommandSource> c) {
        Component fehler = Downloads.INSTANZ.frageAbgleich(StringArgumentType.getString(c, "baum"));
        if (fehler != null) {
            c.getSource().sendError(fehler);
            return 0;
        }
        return 1;
    }
}
