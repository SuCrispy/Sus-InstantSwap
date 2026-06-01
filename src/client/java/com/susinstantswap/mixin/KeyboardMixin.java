package com.susinstantswap.mixin;

import com.susinstantswap.client.InstantSwapClient;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Primary keyboard hook — intercepts GLFW key events directly.
 *
 * <p>Handles:</p>
 * <ul>
 *   <li>E key press/release tracking for long-press state machine</li>
 *   <li>EditBox protection (consume E clicks when text field focused)</li>
 *   <li>GUI swap key detection</li>
 * </ul>
 *
 * <p>Replaces KeyClickMixin for E key tracking because
 * {@code KeyboardHandler.keyPress()} is guaranteed to fire for every
 * key event, while {@code KeyMapping.click()} may have inconsistent
 * Intermediary naming across MC versions.</p>
 */
@Mixin(KeyboardHandler.class)
public class KeyboardMixin {

    @Inject(method = "keyPress(JIIII)V", at = @At("HEAD"), cancellable = true)
    private void onKeyPress(long window, int key, int scancode, int action, int modifiers, CallbackInfo ci) {
        if (InstantSwapClient.handleKeyInput(window, key, scancode, action, modifiers)) {
            ci.cancel();
        }
    }
}
