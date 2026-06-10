package com.susinstantswap.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.susinstantswap.client.SwapKeyState;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = KeyMapping.class, remap = false)
public abstract class KeyClickMixin {

    @Inject(method = "click(Lcom/mojang/blaze3d/platform/InputConstants$Key;)V",
            at = @At("HEAD"), cancellable = true)
    private static void onClick(InputConstants.Key key, CallbackInfo ci) {
        if (!SwapKeyState.modEnabled) return;
        if (!SwapKeyState.isTargetKey(key)) return;
        if (SwapKeyState.inventoryKeyHeld) {
            ci.cancel();
            return;
        }
        SwapKeyState.updateLastTriggerKeyIsVanilla(key);
        SwapKeyState.inventoryKeyHeld = true;
        SwapKeyState.pressStartNanos = System.nanoTime();
        SwapKeyState.longPressConfirmed = false;
    }

    @Inject(method = "set(Lcom/mojang/blaze3d/platform/InputConstants$Key;Z)V", at = @At("HEAD"))
    private static void onSet(InputConstants.Key key, boolean pressed, CallbackInfo ci) {
        if (!SwapKeyState.modEnabled) return;
        if (pressed || !SwapKeyState.isTargetKey(key)) return;
        SwapKeyState.updateLastTriggerKeyIsVanilla(key);
        SwapKeyState.inventoryKeyHeld = false;
        SwapKeyState.longPressConfirmed = false;
    }
}