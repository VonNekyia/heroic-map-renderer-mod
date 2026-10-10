package com.nekyia.heroicmap.mixin;

import com.nekyia.heroicmap.Formen;
import net.minecraft.client.Options;
import net.minecraft.client.gui.font.FontManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * „Unicode-Schrift erzwingen“ und die japanischen Glyphen tauschen die Schriften in
 * {@code updateOptions}, ohne die Ressourcen neu zu laden; die gespeicherte Kartenschrift baut dann
 * neu. Siehe docs/ebenen.md, „Kartenschrift“.
 */
@Mixin(FontManager.class)
abstract class FontManagerMixin {

    @Inject(method = "updateOptions", at = @At("TAIL"))
    private void heroicmap$neuGeladen(Options options, CallbackInfo info) {
        Formen.neuGeladen();
    }
}
