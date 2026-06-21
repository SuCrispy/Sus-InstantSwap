package com.susinstantswap.client;

import com.susinstantswap.mixin.AbstractContainerScreenAccessor;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

/**
 * Fabric helper that exposes {@link AbstractContainerScreen} internals which are
 * not publicly accessible on the Fabric (intermediary) runtime.
 * <p>
 * On NeoForge these are plain public methods ({@code getLeftPos()},
 * {@code getTopPos()}, {@code getSlotUnderMouse()}, ...).  On Fabric we route
 * through {@link AbstractContainerScreenAccessor} mixin accessors instead.
 */
public final class ScreenAccess {

    private ScreenAccess() {}

    public static int getLeftPos(AbstractContainerScreen<?> screen) {
        return ((AbstractContainerScreenAccessor) screen).getLeftPos();
    }

    public static int getTopPos(AbstractContainerScreen<?> screen) {
        return ((AbstractContainerScreenAccessor) screen).getTopPos();
    }

    public static int getImageWidth(AbstractContainerScreen<?> screen) {
        return ((AbstractContainerScreenAccessor) screen).getImageWidth();
    }

    public static int getImageHeight(AbstractContainerScreen<?> screen) {
        return ((AbstractContainerScreenAccessor) screen).getImageHeight();
    }

    public static Slot getSlotUnderMouse(AbstractContainerScreen<?> screen) {
        return ((AbstractContainerScreenAccessor) screen).getHoveredSlot();
    }
}
