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
 * <p>Always blocks the vanilla close-on-E behavior so the swap state
 * machine can decide whether to swap (long press) or close (short press).</p>
 *
 * <p>Key timing: this mixin runs BEFORE KeyClickMixin (which updates
 * lastTriggerKeyIsVanilla), so we check the pressed key directly
 * against the inventory key binding instead of relying on the stale flag.</p>
 */
@Mixin(value = AbstractContainerScreen.class, remap = false)
public class ScreenKeyMixin {

    @Inject(method = "keyPressed(III)Z", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(int keyCode, int scanCode, int modifiers,
                              CallbackInfoReturnable<Boolean> cir) {
        if (!SwapKeyState.modEnabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;

        // Only intercept the vanilla inventory key — not backpack mod keys
        InputConstants.Key invKey = mc.options.keyInventory.getKey();
        InputConstants.Key pressed = InputConstants.getKey(keyCode, scanCode);
        if (pressed.getType() != invKey.getType() || pressed.getValue() != invKey.getValue()) return;

        if (SwapKeyState.inventoryKeyHeld) {
            // REPEAT — block close to prevent flicker during long press
            cir.setReturnValue(false);
            return;
        }

        // FRESH press of inventory key — always block close so the state
        // machine can handle short-press-close vs long-press-swap
        cir.setReturnValue(false);

        // Try instant GUI swap only when the screen was opened by vanilla key.
        // (lastTriggerKeyIsVanilla is still valid here because it was set
        // when the screen was first opened, not during this key event)
        if (SwapKeyState.lastTriggerKeyIsVanilla) {
            InstantSwapClient.tryPerformGuiSwap((AbstractContainerScreen<?>) (Object) this);
        }
    }
}
