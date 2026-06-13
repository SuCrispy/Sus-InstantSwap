package com.susinstantswap.client;

import com.susinstantswap.SusInstantSwapMod;
import com.susinstantswap.config.SwapConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Unified backpack-screen detection — single source of truth.
 * Eliminates duplicated class-name matching lists across the codebase.
 */
public final class BackpackScreenMatcher {

    private BackpackScreenMatcher() {}

    /** Class-name substrings that identify backpack mod screens. */
    private static final String[] PATTERNS = {
            "sophisticated",          // Sophisticated Backpacks / Core
            "flanks255",              // Simply Backpacks (SBGui)
            "BackpackScreen",         // Traveller's Backpack
            "omnis",                  // Omnis Backpack
            "backpacked",             // Backpacked
            "inmis",                  // Inmis Backpack
            "goodbackpacks",          // Good Backpacks
            "resource_backpacks",     // Resource Backpacks
            "ironbackpacks",          // Iron Backpacks
    };

    /** Returns true if the screen belongs to a known backpack mod. */
    public static boolean isBackpackScreen(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?>)) return false;
        String name = screen.getClass().getName();
        for (String p : PATTERNS) {
            if (name.contains(p)) return true;
        }
        return false;
    }

    /**
     * Backpack mods that use wrapper containers, hiding
     * player.getInventory() from the standard containerSlot lookup.
     * These need position-based row detection.
     */
    public static boolean needsPositionBasedRows(AbstractContainerScreen<?> screen) {
        // Currently all backpack screens need position-based detection
        return isBackpackScreen(screen);
    }
}
