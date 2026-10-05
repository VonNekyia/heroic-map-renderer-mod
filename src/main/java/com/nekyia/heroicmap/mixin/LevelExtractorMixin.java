package com.nekyia.heroicmap.mixin;

import com.nekyia.heroicmap.Minimap;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Die Wege, auf denen der Client einen Abschnitt neu zeichnen lässt, enden hier: Block,
 * Bereich, Licht, Chunk; {@code allChanged} nicht. Die Minimap zeichnet die Spalte dann auch
 * neu. Siehe docs/minimap.md, „Neu zeichnen“.
 */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorMixin {

    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void heroicmap$markiere(int x, int y, int z, boolean sofort, CallbackInfo info) {
        Minimap.INSTANZ.markiere(x, z);
    }
}
