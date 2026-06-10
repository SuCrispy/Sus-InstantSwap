package com.susinstantswap.mixin;

import com.susinstantswap.SusInstantSwapMod;
import com.susinstantswap.client.RowArrowWidget;
import com.susinstantswap.client.SwapKeyState;
import com.susinstantswap.config.SwapConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AbstractContainerScreen.class, remap = false)
public class ContainerScreenMixin {

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At("TAIL"))
    private void onRender(GuiGraphics guiGraphics, int mouseX, int mouseY,
                          float partialTick, CallbackInfo ci) {
        if (!SwapKeyState.modEnabled) return;

        // Do not draw row-swap grooves when the screen was opened
        // by a non-vanilla key (e.g. backpack mod).
        if (!SwapKeyState.lastTriggerKeyIsVanilla) return;

        SwapConfig cfg = SusInstantSwapMod.CONFIG;
        if (cfg == null || !cfg.rowSwapEnabled.get()) {
            RowArrowWidget.visible = false;
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;

        RowArrowWidget.detectRows(self, mc.player);
        RowArrowWidget.visible = true;
        RowArrowWidget.checkHover(mouseX, mouseY);

        guiGraphics.pose().pushPose();
        RowArrowWidget.render(mc, guiGraphics);
        guiGraphics.pose().popPose();
    }
}