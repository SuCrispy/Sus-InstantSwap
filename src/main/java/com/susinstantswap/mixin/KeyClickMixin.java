package com.susinstantswap.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.susinstantswap.client.InstantSwapClient;
import com.susinstantswap.client.SwapKeyState;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts KeyMapping.click() to prevent vanilla from toggling inventory
 * on OS key-repeat events during long press.
 *
 * Release detection is handled by GLFW polling in InstantSwapClient.onClientTick
 * (avoids SRG/refMap issues with KeyMapping.set).
 */
@Mixin(value = KeyMapping.class, remap = false)
public abstract class KeyClickMixin {

    @Inject(method = "m_90835_(Lcom/mojang/blaze3d/platform/InputConstants$Key;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void onClick(InputConstants.Key key, CallbackInfo ci) {
        if (!SwapKeyState.modEnabled) return;
        if (!InstantSwapClient.isSwapKey(key)) return;
        if (SwapKeyState.inventoryKeyHeld) {
            // REPEAT — cancel click to prevent vanilla inventory toggle
            ci.cancel();
            return;
        }
        // First press — mark hold start
        SwapKeyState.inventoryKeyHeld = true;
        SwapKeyState.pressStartNanos = System.nanoTime();
        SwapKeyState.longPressConfirmed = false;
    }
}
