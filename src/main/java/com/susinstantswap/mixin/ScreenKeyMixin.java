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
 * Intercepts Screen.keyPressed() for the inventory key on any
 * AbstractContainerScreen.
 *
 * Two scenarios:
 *   - REPEAT (inventoryKeyHeld already true): Block close to prevent
 *       flicker during long press. The screen stays open while the key is held.
 *   - FRESH press (inventoryKeyHeld false): Try GUI swap first.
 *       If not, let vanilla handle normally (E closes the screen).
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
        if (!pressed.equals(mc.options.keyInventory.getKey())) return;

        if (SwapKeyState.inventoryKeyHeld) {
            // REPEAT — block close to prevent flicker during long press
            cir.setReturnValue(false);
            return;
        }

        // Fresh press — try GUI swap, otherwise let vanilla handle
        if (InstantSwapClient.tryPerformGuiSwap((AbstractContainerScreen<?>) (Object) this)) {
            cir.setReturnValue(true);
        }
        // If GUI swap didn't fire: don't intercept — let vanilla close the screen
    }
}
