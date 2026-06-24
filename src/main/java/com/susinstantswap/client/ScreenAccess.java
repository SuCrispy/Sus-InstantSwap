package com.susinstantswap.client;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

import java.lang.reflect.Field;

/**
 * Reflection-based access to {@link AbstractContainerScreen} layout fields.
 * <p>
 * The public getters ({@code getLeftPos/getTopPos/getImageWidth/getImageHeight/getHoveredSlot})
 * are <b>absent on some NeoForge 26.1 beta runtimes</b> (e.g. {@code 26.1.0.19-beta}).
 * Compiling against a newer MDK that exposes them succeeds, but invoking them at
 * runtime throws {@link NoSuchMethodError} (observed when opening the inventory with E).
 * <p>
 * NeoForge uses official Mojang mappings at runtime, so the underlying field names
 * ({@code leftPos / topPos / imageWidth / imageHeight / hoveredSlot}) are resolved
 * directly by name and are stable across 1.17+.
 */
public final class ScreenAccess {

    private ScreenAccess() {}

    private static Field fLeft;
    private static Field fTop;
    private static Field fWidth;
    private static Field fHeight;
    private static Field fHovered;

    private static Field field(String name) throws NoSuchFieldException {
        Field f = AbstractContainerScreen.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    public static int leftPos(AbstractContainerScreen<?> s) {
        try {
            if (fLeft == null) fLeft = field("leftPos");
            return fLeft.getInt(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int topPos(AbstractContainerScreen<?> s) {
        try {
            if (fTop == null) fTop = field("topPos");
            return fTop.getInt(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int imageWidth(AbstractContainerScreen<?> s) {
        try {
            if (fWidth == null) fWidth = field("imageWidth");
            return fWidth.getInt(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int imageHeight(AbstractContainerScreen<?> s) {
        try {
            if (fHeight == null) fHeight = field("imageHeight");
            return fHeight.getInt(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static Slot hoveredSlot(AbstractContainerScreen<?> s) {
        try {
            if (fHovered == null) fHovered = field("hoveredSlot");
            return (Slot) fHovered.get(s);
        } catch (Throwable t) {
            return null;
        }
    }
}
