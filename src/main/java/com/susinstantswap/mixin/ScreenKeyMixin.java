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
 * NOTE: Uses SRG method name m_7933_ instead of Mojang keyPressed because
 * Forge 1.20.1 reobfuscates all MC methods to SRG at runtime.
 */
@Mixin(value = AbstractContainerScreen.class, remap = false)
public class ScreenKeyMixin {

    @Inject(method = "m_7933_(III)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void onKeyPressed(int keyCode, int scanCode, int modifiers,
                              CallbackInfoReturnable<Boolean> cir) {
        if (!SwapKeyState.modEnabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;

        InputConstants.Key pressed = InputConstants.getKey(keyCode, scanCode);

        // ── GUI swap key (bound) → perform swap directly ──
        if (InstantSwapClient.tryPerformGuiSwap((AbstractContainerScreen<?>) (Object) this, pressed)) {
            cir.setReturnValue(true);
            cir.cancel();
            return;
        }

        // ── Inventory key (E) handling ──
        if (!pressed.equals(mc.options.keyInventory.getKey())) return;

        if (SwapKeyState.inventoryKeyHeld) {
            // REPEAT — cancel keyPressed to prevent inventory close during long press
            cir.setReturnValue(false);
            cir.cancel();
            return;
        }
        // Fresh press — let vanilla handle (E opens/closes inventory)
    }
}
