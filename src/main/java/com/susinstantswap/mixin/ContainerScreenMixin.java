package com.susinstantswap.mixin;

import com.susinstantswap.client.BackpackScreenMatcher;
import com.susinstantswap.client.RowArrowWidget;
import com.susinstantswap.client.SwapKeyState;
import com.susinstantswap.config.SwapConfigAdapter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AbstractContainerScreen.class, remap = false)
public class ContainerScreenMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("TAIL"))
    private void onRender(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
                          float partialTick, CallbackInfo ci) {
        // Disabled on 26.1: drawing the row-swap UI during the extractRenderState
        // TAIL does NOT render for vanilla containers under the new render-state
        // architecture. All row-swap UI is now drawn from
        // InstantSwapClient.onScreenRenderPost (ScreenEvent.Render.Post), which
        // works for every AbstractContainerScreen (vanilla inventory, chests,
        // and backpack mods alike).
    }
}
