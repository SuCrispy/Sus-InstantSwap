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
 * Intercepts Screen.keyPressed() for the inventory key AND the GUI swap key
 * on any AbstractContainerScreen.
 *
 * Three scenarios:
 *   - GUI SWAP KEY (bound + pressed): Perform swap directly, block vanilla.
 *   - REPEAT E (inventoryKeyHeld already true): Block close to prevent
 *       flicker during long press.
 *   - FRESH E (inventoryKeyHeld false): Try GUI swap first (unbound key fallback).
 *       If not, let vanilla handle normally.
 */
@Mixin(value = AbstractContainerScreen.class)
public class ScreenKeyMixin {

    @Inject(method = "keyPressed(III)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void onKeyPressed(int keyCode, int scanCode, int modifiers,
                              CallbackInfoReturnable<Boolean> cir) {
        if (!SwapKeyState.modEnabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;

        InputConstants.Key pressed = InputConstants.getKey(keyCode, scanCode);

        // ── GUI swap key (bound) → perform swap directly ──
        if (InstantSwapClient.tryPerformGuiSwap((AbstractContainerScreen<?>) (Object) this, pressed)) {
            cir.setReturnValue(true);
            return;
        }

        // ── Inventory key (E) handling ──
        if (!pressed.equals(mc.options.keyInventory.getKey())) return;

        if (SwapKeyState.inventoryKeyHeld) {
            // REPEAT — block close to prevent flicker during long press
            cir.setReturnValue(false);
            return;
        }
        // Fresh press — let vanilla handle (E opens/closes inventory)
    }
}
