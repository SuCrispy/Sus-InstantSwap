package com.susinstantswap.mixin;

import com.susinstantswap.client.BackpackScreenMatcher;
import com.susinstantswap.client.RowArrowWidget;
import com.susinstantswap.client.SwapKeyState;
import com.susinstantswap.config.SwapConfigAdapter;

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

        // Skip for backpack screens — handled by onScreenRenderPost fallback
        // (some backpack screens don't call super.render(), so TAIL won't fire)
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (BackpackScreenMatcher.isBackpackScreen(self)) return;

        SwapConfigAdapter cfg = SwapKeyState.getConfig();
        if (cfg == null || !cfg.rowSwapEnabled()) {
            RowArrowWidget.visible = false;
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        RowArrowWidget.detectRows(self, mc.player);
        RowArrowWidget.visible = true;
        RowArrowWidget.checkHover(mouseX, mouseY);

        guiGraphics.pose().pushMatrix();
        RowArrowWidget.render(mc, guiGraphics);
        guiGraphics.pose().popMatrix();
    }
}
