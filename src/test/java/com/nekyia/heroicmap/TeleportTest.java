package com.nekyia.heroicmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import org.junit.jupiter.api.Test;

/** Der Befehl zum Teleportieren und wann es ihn gibt. Siehe docs/vollbildkarte.md, „Bedienung“. */
class TeleportTest {

    @Test
    void befehlAufDieMitteDesBlocks() {
        assertEquals("execute in minecraft:overworld positioned 12.5 0 -40.5 positioned over motion_blocking_no_leaves run tp @s ~ ~ ~",
                Teleport.befehl("minecraft:overworld", 12, -41));
    }

    @Test
    void nurMitExecuteUndTp() {
        CommandDispatcher<Object> befehle = new CommandDispatcher<>();
        assertFalse(Teleport.erlaubt(befehle));
        befehle.register(LiteralArgumentBuilder.literal("tp"));
        assertFalse(Teleport.erlaubt(befehle));
        befehle.register(LiteralArgumentBuilder.literal("execute"));
        assertTrue(Teleport.erlaubt(befehle));
    }
}
