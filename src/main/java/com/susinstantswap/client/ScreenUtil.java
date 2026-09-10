package com.susinstantswap.client;

import com.susinstantswap.mixin.GuiAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 26.2 Gui reorganization helper: the {@code screen} field moved from
 * {@code Minecraft} (public) to {@code Gui} (private). ForgeGradle does not
 * auto-load Access Transformers, so we use a Mixin {@link GuiAccessor} instead.
 */
public final class ScreenUtil {

    private ScreenUtil() {}

    public static Screen get(Minecraft mc) {
        return ((GuiAccessor) mc.gui).susinstantswap$getScreen();
    }
}
