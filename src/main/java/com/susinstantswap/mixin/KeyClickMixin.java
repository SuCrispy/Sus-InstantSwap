package com.susinstantswap.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.susinstantswap.client.InstantSwapClient;
import com.susinstantswap.client.SwapKeyState;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts KeyMapping.click() and KeyMapping.set() for the vanilla inventory key.
 * Tracks held state and blocks repeat clicks during long press.
 */
@Mixin(value = KeyMapping.class, remap = false)
public class KeyClickMixin {

    @Inject(method = "click", at = @At("HEAD"), cancellable = true)
    private static void onClick(InputConstants.Key key, CallbackInfo ci) {
        if (!SwapKeyState.modEnabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;
        if (!InstantSwapClient.isSwapKey(key)) return;

        if (SwapKeyState.inventoryKeyHeld) {
            ci.cancel();
            return;
        }
        SwapKeyState.inventoryKeyHeld = true;
        SwapKeyState.pressStartNanos = System.nanoTime();
        SwapKeyState.longPressConfirmed = false;
    }

    @Inject(method = "set", at = @At("HEAD"))
    private static void onSet(InputConstants.Key key, boolean pressed, CallbackInfo ci) {
        if (!SwapKeyState.modEnabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;
        if (pressed || !InstantSwapClient.isSwapKey(key)) return;
        SwapKeyState.inventoryKeyHeld = false;
    }
}
