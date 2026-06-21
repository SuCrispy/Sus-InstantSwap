package com.susinstantswap.client;

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
            "backpacked",             // Backpacked (MrCrayfish)
            "inmis",                  // Inmis Backpack
            "resource_backpacks",     // Resource Backpacks
            "packedup",               // Packed Up (SuperMartijn642)
            "l2backpack",             // L2 Backpack (LightLand)
            "beansgalaxy",            // Beans Backpacks (v1/v2/v3)
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
