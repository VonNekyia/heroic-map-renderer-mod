package com.nekyia.heroicmap.mixin;

import com.nekyia.heroicmap.Minimap;
import com.nekyia.heroicmap.Selbst;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Die Wege, auf denen der Client Blöcke, Bereiche und Licht neu zeichnen lässt, an der Welt statt am
 * Renderer: Mods wie Sodium ersetzen die Methoden von {@code LevelExtractor}, die von
 * {@code ClientLevel} nicht. Doppelt mit {@link LevelExtractorMixin} schadet nicht, ein Chunk ist nur
 * einmal offen. Siehe docs/minimap.md, „Neu zeichnen“.
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {

    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void heroicmap$block(BlockPos pos, BlockState alt, BlockState neu, int flags, CallbackInfo info) {
        // Wie LevelExtractor.blockChanged: der Block und seine Nachbarn.
        heroicmap$markiere(pos.getX() - 1, pos.getZ() - 1, pos.getX() + 1, pos.getZ() + 1);
    }

    @Inject(method = "setBlocksDirty", at = @At("HEAD"))
    private void heroicmap$modell(BlockPos pos, BlockState alt, BlockState neu, CallbackInfo info) {
        heroicmap$markiere(pos.getX(), pos.getZ(), pos.getX(), pos.getZ());
    }

    @Inject(method = "setSectionDirtyWithNeighbors", at = @At("HEAD"))
    private void heroicmap$abschnitt(int x, int y, int z, CallbackInfo info) {
        heroicmap$abschnitte(x - 1, z - 1, x + 1, z + 1);
    }

    @Inject(method = "setSectionRangeDirty", at = @At("HEAD"))
    private void heroicmap$bereich(int x0, int y0, int z0, int x1, int y1, int z1, CallbackInfo info) {
        heroicmap$abschnitte(x0, z0, x1, z1);
    }

    /** Die Spalten der Blöcke von (x0, z0) bis (x1, z1). */
    private static void heroicmap$markiere(int x0, int z0, int x1, int z1) {
        heroicmap$abschnitte(SectionPos.blockToSectionCoord(x0), SectionPos.blockToSectionCoord(z0),
                SectionPos.blockToSectionCoord(x1), SectionPos.blockToSectionCoord(z1));
    }

    private static void heroicmap$abschnitte(int x0, int z0, int x1, int z1) {
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                Minimap.INSTANZ.markiere(x, z);
                Selbst.INSTANZ.markiere(x, z);
            }
        }
    }
}
