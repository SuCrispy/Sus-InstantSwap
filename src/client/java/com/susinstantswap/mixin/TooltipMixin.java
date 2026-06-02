package com.susinstantswap.mixin;

import com.susinstantswap.client.InstantSwapClient;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MC 26.1: suppress tooltip after cursor reposition.
 * Hooks both text and item tooltip entry points.
 */
@Mixin(GuiGraphicsExtractor.class)
public class TooltipMixin {

    /** Text tooltips (e.g. button descriptions, name tags) */
    @Inject(method = "setTooltipForNextFrame(Lnet/minecraft/network/chat/Component;II)V",
            at = @At("HEAD"), cancellable = true)
    private void onTextTooltip(Component component, int x, int y, CallbackInfo ci) {
        if (InstantSwapClient.shouldSuppressTooltip()) {
            ci.cancel();
        }
    }

    /** Item tooltips (most common: hovering over inventory slots) */
    @Inject(method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;II)V",
            at = @At("HEAD"), cancellable = true)
    private void onItemTooltip(Font font, ItemStack stack, int x, int y, CallbackInfo ci) {
        if (InstantSwapClient.shouldSuppressTooltip()) {
            ci.cancel();
        }
    }
}
