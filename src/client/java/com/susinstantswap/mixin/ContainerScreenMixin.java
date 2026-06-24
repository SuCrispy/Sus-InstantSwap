package com.susinstantswap.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AbstractContainerScreen.class)
public class ContainerScreenMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("TAIL"))
    private void onRender(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
                          float partialTick, CallbackInfo ci) {
        // Disabled on 26.1: drawing the row-swap UI here does NOT render for
        // vanilla containers. AbstractContainerScreen.extractRenderState is a
        // sub-step invoked by Screen.extractWithTooltip; anything filled at its
        // TAIL is overwritten by the outer extract pass that continues
        // afterwards (slot highlights, carried item, etc.).
        //
        // All row-swap UI is now drawn from
        // InstantSwapClient.onContainerExtractPost, registered via
        // ScreenEvents.afterExtract — which fires at the very end of the
        // outermost Screen.extractWithTooltip, the Fabric equivalent of
        // NeoForge/Forge ScreenEvent.Render.Post, and works for every
        // AbstractContainerScreen (vanilla inventory, chests, backpack mods).
        //
        // The @Inject is kept (body empty) so mixins.json needs no change.
    }
}
