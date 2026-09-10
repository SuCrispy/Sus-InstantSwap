package com.susinstantswap.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for the private {@code screen} field on {@link Gui} (26.2 Gui reorganization).
 * Replaces the Access Transformer approach that ForgeGradle does not auto-load.
 */
@Mixin(value = Gui.class, remap = false)
public interface GuiAccessor {

    @Accessor("screen")
    Screen susinstantswap$getScreen();
}
