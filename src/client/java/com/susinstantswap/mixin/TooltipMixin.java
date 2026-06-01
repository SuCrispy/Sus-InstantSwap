package com.susinstantswap.mixin;

import com.susinstantswap.client.InstantSwapClient;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Suppress tooltips for 2 frames after cursor reposition.
 *
 * Targets GuiGraphics.renderTooltip(Font, List, int, int) — the most common
 * deferred tooltip rendering path called by Screen and containers.
 */
@Mixin(GuiGraphics.class)
public class TooltipMixin {

    @Inject(method = "renderTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;II)V",
            at = @At("HEAD"), cancellable = true)
    private void onRenderTooltip(Font font, List<?> tooltip, int x, int y, CallbackInfo ci) {
        if (InstantSwapClient.shouldSuppressTooltip()) {
            ci.cancel();
        }
    }
}
