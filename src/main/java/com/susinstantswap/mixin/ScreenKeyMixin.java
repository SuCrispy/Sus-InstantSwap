package com.susinstantswap.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.susinstantswap.client.SwapKeyState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts Screen.keyPressed() for the inventory key REPEAT events
 * on any AbstractContainerScreen.
 *
 * <p>When the inventory key was held BEFORE the screen opened (i.e., the
 * key press opened the screen and the user is still holding it), we block
 * the vanilla close-on-E to prevent flicker during long-press detection.</p>
 *
 * <p>When the screen was already open and the user presses E fresh,
 * we do NOT intercept — vanilla closes the screen normally.</p>
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
            // REPEAT — key was held since before the screen opened.
            // Block close to prevent flicker during long-press detection.
            cir.setReturnValue(false);
        }
        // FRESH press while screen is already open → don't intercept,
        // let vanilla close the screen (normal E-to-close behavior).
    }
}
