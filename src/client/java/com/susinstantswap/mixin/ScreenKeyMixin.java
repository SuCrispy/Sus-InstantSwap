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
 * Intercepts AbstractContainerScreen.keyPressed() for the inventory key.
 *
 * <p>In NF, {@code screen.keyPressed()} fires BEFORE {@code KeyMapping.click()},
 * so {@code inventoryKeyHeld} is still false at Mixin time — fresh presses
 * fall through to vanilla close.</p>
 *
 * <p>In Fabric, {@code KeyboardMixin.handleKeyInput} fires at HEAD before
 * {@code screen.keyPressed()}, so {@code inventoryKeyHeld} is already true.
 * However, REPEAT events are blocked by {@code handleKeyInput} (returns true,
 * cancels the whole {@code KeyboardHandler.keyPress()}), so this Mixin only
 * sees FRESH presses. That means we can close immediately when no swap
 * target exists — no deferred-close mechanism needed.</p>
 */
@Mixin(AbstractContainerScreen.class)
public class ScreenKeyMixin {

    @Inject(method = "keyPressed(III)Z", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(int keyCode, int scanCode, int modifiers,
                              CallbackInfoReturnable<Boolean> cir) {
        if (!SwapKeyState.modEnabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;

        InputConstants.Key pressed = InputConstants.getKey(keyCode, scanCode);
        if (!pressed.equals(((KeyMappingAccessor) (Object) mc.options.keyInventory).getKey())) return;

        // Try GUI swap first (NF's InputEvent.Key fires before Screen.keyPressed)
        if (InstantSwapClient.tryPerformGuiSwap((AbstractContainerScreen<?>) (Object) this)) {
            cir.setReturnValue(true);
            return;
        }

        // This is a fresh E press (repeats are blocked by KeyboardMixin).
        // No valid swap target — close with packet so the server knows.
        if (mc.player != null) {
            mc.player.closeContainer();
        } else {
            mc.setScreen(null);
        }
        cir.setReturnValue(true);
    }
}
