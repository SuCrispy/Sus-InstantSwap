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
 * Intercepts Screen.keyPressed() for the inventory key on
 * AbstractContainerScreen.
 *
 * <p>Three cases:</p>
 * <ol>
 *   <li><b>REPEAT</b> (inventoryKeyHeld=true): block close to prevent
 *       flicker during long-press detection.</li>
 *   <li><b>FRESH press, GUI swap key == inventory key (E)</b>: block
 *       close and try GUI swap. If swap succeeds, screen closes after
 *       swap. If swap fails, let vanilla close the screen.</li>
 *   <li><b>FRESH press, GUI swap key ≠ inventory key</b>: don't
 *       intercept — vanilla closes the screen normally.</li>
 * </ol>
 */
@Mixin(value = AbstractContainerScreen.class, remap = false)
public class ScreenKeyMixin {

    @Inject(method = "keyPressed(III)Z", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(int keyCode, int scanCode, int modifiers,
                              CallbackInfoReturnable<Boolean> cir) {
        if (!SwapKeyState.modEnabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;

        InputConstants.Key invKey = mc.options.keyInventory.getKey();
        InputConstants.Key pressed = InputConstants.getKey(keyCode, scanCode);
        if (pressed.getType() != invKey.getType() || pressed.getValue() != invKey.getValue()) return;

        if (SwapKeyState.inventoryKeyHeld) {
            // REPEAT — block close to prevent flicker during long-press detection
            cir.setReturnValue(false);
            return;
        }

        // FRESH press — check if GUI swap key is bound to the same key as inventory
        InputConstants.Key guiSwapKey = InstantSwapClient.getGuiSwapKey();
        if (guiSwapKey != null && !InstantSwapClient.isGuiSwapKeyUnbound()
                && guiSwapKey.getType() == invKey.getType()
                && guiSwapKey.getValue() == invKey.getValue()) {
            // If the last swap attempt failed, let vanilla close the screen
            // (prevents user being stuck: swap fails → press E again → close)
            if (InstantSwapClient.wasLastGuiSwapFailed()) {
                InstantSwapClient.resetGuiSwapFailed();
                // Don't intercept → vanilla closes the screen
                return;
            }
            // GUI swap key == inventory key → block close and try swap first
            cir.setReturnValue(false);
            InstantSwapClient.tryPerformGuiSwap((AbstractContainerScreen<?>) (Object) this);
        }
        // else: GUI swap key is different or unbound → don't intercept,
        // let vanilla close the screen (normal E-to-close behavior).
    }
}
