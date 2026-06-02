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
 * MC 26.1: Handles E key on open container screens.
 * Repeat prevention is handled by handleKeyInput at the KeyboardHandler level,
 * so we only deal with fresh E presses here.
 */
@Mixin(AbstractContainerScreen.class)
public class ScreenKeyMixin {

    @Inject(method = "keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z",
            at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(KeyEvent keyEvent, CallbackInfoReturnable<Boolean> cir) {
        if (!SwapKeyState.modEnabled) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null || mc.player == null) return;

        InputConstants.Key pressed = InputConstants.Type.KEYSYM.getOrCreate(keyEvent.key());
        InputConstants.Key invKey = ((KeyMappingAccessor) (Object) mc.options.keyInventory).getKey();
        if (!pressed.equals(invKey)) return;

        // 1. GUI swap via unbound E key
        if (InstantSwapClient.tryPerformGuiSwap((AbstractContainerScreen<?>) (Object) this)) {
            cir.setReturnValue(true);
            return;
        }

        // 2. No valid swap target → close immediately
        mc.player.closeContainer();
        cir.setReturnValue(true);
    }
}
