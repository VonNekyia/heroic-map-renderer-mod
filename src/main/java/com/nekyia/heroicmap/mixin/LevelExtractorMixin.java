package com.nekyia.heroicmap.mixin;

import com.nekyia.heroicmap.Live;
import com.nekyia.heroicmap.Minimap;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Die Wege, auf denen der Client einen Abschnitt neu zeichnen lässt, enden in
 * {@code setSectionDirty}: Block, Bereich, Licht, Chunk; {@code allChanged} nicht. Die Minimap
 * zeichnet die Spalte dann auch neu. Siehe docs/minimap.md, „Neu zeichnen“. Nur geänderte
 * Blöcke gehen über {@code setBlockDirty}, dort hört die Live-Ebene zu. Siehe docs/live.md,
 * „Wann gezeichnet wird“.
 */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorMixin {

    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void heroicmap$markiere(int x, int y, int z, boolean sofort, CallbackInfo info) {
        Minimap.INSTANZ.markiere(x, z);
    }

    @Inject(method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/block/state/BlockState;)V", at = @At("HEAD"))
    private void heroicmap$live(BlockPos pos, BlockState alt, BlockState neu, CallbackInfo info) {
        Live.INSTANZ.markiere(pos.getX(), pos.getZ());
    }
}
