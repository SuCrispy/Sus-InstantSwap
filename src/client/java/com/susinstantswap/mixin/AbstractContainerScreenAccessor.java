package com.susinstantswap.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
    @Accessor("hoveredSlot")
    Slot getHoveredSlot();

    @Accessor("leftPos")
    int getLeftPos();

    @Accessor("imageWidth")
    int getImageWidth();

    @Accessor("topPos")
    int getTopPos();

    @Accessor("imageHeight")
    int getImageHeight();

    @Invoker("isHovering")
    boolean invokeIsHovering(Slot slot, double mouseX, double mouseY);
}
