package com.susinstantswap.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.susinstantswap.client.InstantSwapClient;
import com.susinstantswap.client.SwapKeyState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts Screen.keyPressed() for the inventory key on any AbstractContainerScreen.
 *
 * Two scenarios:
 *   REPEAT (inventoryKeyHeld already true): Block close to prevent flicker during long press.
 *   FRESH press (inventoryKeyHeld false): Try GUI swap first. If not, let vanilla handle normally.
 */
@Mixin(value = AbstractContainerScreen.class, remap = false)
public class ScreenKeyMixin {

    @Inject(method = "keyPressed(III)Z", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(int keyCode, int scanCode, int modifiers,
                              CallbackInfoReturnable<Boolean> cir) {
        if (!SwapKeyState.modEnabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;

        InputConstants.Key pressed = InputConstants.getKey(keyCode, scanCode);
        if (!InstantSwapClient.isSwapKey(pressed)) return;

        if (SwapKeyState.inventoryKeyHeld) {
            cir.setReturnValue(false);
            return;
        }

        if (InstantSwapClient.tryPerformGuiSwap((AbstractContainerScreen<?>) (Object) this)) {
            cir.setReturnValue(true);
        }
    }
}
