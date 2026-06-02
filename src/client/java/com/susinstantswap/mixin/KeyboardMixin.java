package com.susinstantswap.mixin;

import com.susinstantswap.client.InstantSwapClient;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MC 26.1: keyPress(JIIII)V → keyPress(JILKeyEvent;)V.
 * KeyEvent record encapsulates key, scancode, modifiers.
 */
@Mixin(KeyboardHandler.class)
public class KeyboardMixin {

    @Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V",
            at = @At("HEAD"), cancellable = true)
    private void onKeyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (InstantSwapClient.handleKeyInput(window, action, event)) {
            ci.cancel();
        }
    }
}
