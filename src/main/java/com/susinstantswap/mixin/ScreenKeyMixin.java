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
 * <p>Two scenarios:</p>
 * <ul>
 *   <li><b>REPEAT (inventoryKeyHeld already true):</b> Block close to
 *       prevent flicker during long press. The screen stays open
 *       while the key is held.</li>
 *   <li><b>FRESH press (inventoryKeyHeld false):</b> Try GUI swap
 *       first. If not, let vanilla handle normally (E closes the
 *       screen — standard vanilla behavior).</li>
 * </ul>
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
        if (!SwapKeyState.isTargetKey(pressed)) return;

        if (SwapKeyState.inventoryKeyHeld) {
            // REPEAT — block close to prevent flicker during long press
            cir.setReturnValue(false);
            return;
        }

        // If screen was opened by a non-vanilla key (e.g. backpack mod),
        // let vanilla handle normally — no swap, no interception.
        if (!SwapKeyState.lastTriggerKeyIsVanilla) {
            return;
        }

        // Fresh press — try GUI swap, otherwise let vanilla handle
        if (InstantSwapClient.tryPerformGuiSwap((AbstractContainerScreen<?>) (Object) this)) {
            cir.setReturnValue(true);
        }
        // If GUI swap didn't fire: don't intercept — let vanilla close the screen
    }
}