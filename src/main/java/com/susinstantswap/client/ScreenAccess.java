package com.susinstantswap.client;

import com.susinstantswap.mixin.AbstractContainerScreenAccessor;
import com.susinstantswap.mixin.MouseHandlerAccessor;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

/**
 * Platform-safe access to screen internals via Accessor mixins.
 * Fabric Loom remaps Accessor targets to correct runtime field names.
 */
public final class ScreenAccess {
    private ScreenAccess() {}

    public static int getLeftPos(AbstractContainerScreen<?> s) {
        return ((AbstractContainerScreenAccessor) s).getLeftPos();
    }

    public static int getTopPos(AbstractContainerScreen<?> s) {
        return ((AbstractContainerScreenAccessor) s).getTopPos();
    }

    public static int getImageWidth(AbstractContainerScreen<?> s) {
        return ((AbstractContainerScreenAccessor) s).getImageWidth();
    }

    public static int getImageHeight(AbstractContainerScreen<?> s) {
        return ((AbstractContainerScreenAccessor) s).getImageHeight();
    }

    public static Slot getSlotUnderMouse(AbstractContainerScreen<?> s) {
        return ((AbstractContainerScreenAccessor) s).getHoveredSlot();
    }

    public static double getMouseX(MouseHandler mh) {
        return ((MouseHandlerAccessor) mh).getXpos();
    }

    public static void setMouseX(MouseHandler mh, double v) {
        ((MouseHandlerAccessor) mh).setXpos(v);
    }

    public static double getMouseY(MouseHandler mh) {
        return ((MouseHandlerAccessor) mh).getYpos();
    }

    public static void setMouseY(MouseHandler mh, double v) {
        ((MouseHandlerAccessor) mh).setYpos(v);
    }
}
