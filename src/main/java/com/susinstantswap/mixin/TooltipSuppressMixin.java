package com.susinstantswap.mixin;

import com.susinstantswap.client.InstantSwapClient;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public class TooltipSuppressMixin {

    @Inject(method = "isHovering(Lnet/minecraft/world/inventory/Slot;DD)Z",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void onIsHovering(Slot slot, double mouseX, double mouseY,
                               CallbackInfoReturnable<Boolean> cir) {
        if (InstantSwapClient.isTooltipSuppressed()) {
            cir.setReturnValue(false);
        }
    }
}
