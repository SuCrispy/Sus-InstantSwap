package com.susinstantswap.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.susinstantswap.client.InstantSwapClient;
import com.susinstantswap.client.SwapKeyState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts AbstractContainerScreen.keyPressed() for the inventory key.
 *
 * <p>Three cases:</p>
 * <ol>
 *   <li><b>REPEAT</b> (inventoryKeyHeld=true): block to prevent flicker
 *       during long-press detection.</li>
 *   <li><b>FRESH press, GUI swap key == inventory key</b>: try swap first,
 *       then close the screen via {@code onClose()}. Mark key as handled
 *       to prevent reopening.</li>
 *   <li><b>FRESH press, GUI swap key ≠ inventory key</b>: don't intercept
 *       — vanilla closes normally.</li>
 * </ol>
 */
@Mixin(value = AbstractContainerScreen.class, remap = false)
public class ScreenKeyMixin {

    @Inject(method = "keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(KeyEvent event,
                              CallbackInfoReturnable<Boolean> cir) {
        if (!SwapKeyState.modEnabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;

        InputConstants.Key invKey = mc.options.keyInventory.getKey();
        InputConstants.Key pressed = InputConstants.getKey(event);
        if (pressed.getType() != invKey.getType() || pressed.getValue() != invKey.getValue()) return;

        if (SwapKeyState.inventoryKeyHeld) {
            // REPEAT — block to prevent flicker during long-press detection
            cir.setReturnValue(false);
            return;
        }

        // FRESH press — check if GUI swap key matches inventory key
        InputConstants.Key guiSwapKey = InstantSwapClient.getGuiSwapKey();
        if (guiSwapKey != null && !InstantSwapClient.isGuiSwapKeyUnbound()
                && guiSwapKey.getType() == invKey.getType()
                && guiSwapKey.getValue() == invKey.getValue()) {
            // GUI swap key == inventory key → try swap then close
            InstantSwapClient.tryPerformGuiSwap();
            ((AbstractContainerScreen<?>) (Object) this).onClose();
            // Consume pending clicks so tick handler doesn't reopen
            while (mc.options.keyInventory.consumeClick()) {}
            cir.setReturnValue(true);
            return;
        }
        // else: GUI swap key is different or unbound → don't intercept,
        // let vanilla keyPressed() handle close normally.
    }
}
