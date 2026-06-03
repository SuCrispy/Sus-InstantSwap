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
 * <p>When a container screen is already open and the user presses E,
 * {@code handleKeyInput} does NOT set {@code inventoryKeyHeld} (since it
 * detects the open screen and delegates to this Mixin). This Mixin then:</p>
 * <ol>
 *   <li>Tries GUI swap (unbound E key fallback) — if a valid target exists,
 *       performs the swap and consumes the event.</li>
 *   <li>If no valid swap target, closes the screen via
 *       {@code mc.player.closeContainer()} which sends the proper
 *       {@code ServerboundContainerClosePacket} to the server.</li>
 * </ol>
 *
 * <p>REPEAT events are blocked upstream by {@code handleKeyInput} and
 * never reach this Mixin.</p>
 */
@Mixin(AbstractContainerScreen.class)
public class ScreenKeyMixin {

    @Inject(method = "keyPressed(III)Z", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(int keyCode, int scanCode, int modifiers,
                              CallbackInfoReturnable<Boolean> cir) {
        if (!SwapKeyState.modEnabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null || mc.player == null) return;

        InputConstants.Key pressed = InputConstants.getKey(keyCode, scanCode);
        if (!pressed.equals(((KeyMappingAccessor) (Object) mc.options.keyInventory).getKey())) return;

        // 1. Try GUI swap first (unbound E key fallback)
        if (InstantSwapClient.tryPerformGuiSwap((AbstractContainerScreen<?>) (Object) this)) {
            cir.setReturnValue(true);
            return;
        }

        // 2. No valid swap target — close with proper container close packet
        mc.player.closeContainer();
        cir.setReturnValue(true);
    }
}
