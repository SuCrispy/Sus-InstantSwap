package com.susinstantswap.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.susinstantswap.SwapLog;
import com.susinstantswap.config.SwapConfigAdapter;
import net.minecraft.client.KeyMapping;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Shared state between mixins and InstantSwapClient.
 */
public final class SwapKeyState {
    public static volatile boolean inventoryKeyHeld = false;
    public static volatile long pressStartNanos = 0;
    /** Swap completed — countdown to close (0=idle, 1=vanilla/creative, 2=mod containers to prevent fake swap). */
    public static volatile int closePendingTicks = 0;
    /** Master switch — when false, the entire mod is disabled. Synced from config. */
    public static volatile boolean modEnabled = true;
    /**
     * Whether the last key that triggered a screen open was a vanilla
     * key (E / inventory key).  When false, row-swap grooves and
     * screen-level swap interception are disabled.
     */
    public static volatile boolean lastTriggerKeyIsVanilla = true;

    /**
     * Whether a container screen was already open when the target key
     * was first pressed.  When false, the key press itself opened the
     * screen, so the swap state machine should NOT engage (short press
     * should just open the screen and leave it open).
     */
    public static volatile boolean screenWasOpenAtPressStart = false;

    /** Set of {@link InputConstants.Key} that should be intercepted (inventory + backpack mod keys). */
    private static volatile Set<InputConstants.Key> targetKeys = Collections.emptySet();

    /**
     * Known translation-key patterns for backpack-mod open-backpack bindings.
     * Add new entries here when supporting additional mods.
     */
    private static final String[] BACKPACK_KEY_PATTERNS = {
            // Sophisticated Backpacks — uses "keybind." prefix via TranslationHelper
            "keybind.sophisticatedbackpacks.open_backpack",
            "key.sophisticatedbackpacks.open_backpack",

            // Simply Backpacks
            "key.simplybackpacks.backpackopen",
            "key.simplybackpacks.open_backpack",

            // Traveller's Backpack
            "key.travelersbackpack.inventory",
            "key.travelersbackpack.open_backpack",

            // Backpacked (MrCrayfish)
            "key.backpacked.open_backpack",
            "key.backpacked.backpack",

            // Inmis
            "key.inmis.open_backpack",
            "key.inmis.backpack",

            // Resource Backpacks — modid "resource_backpacks" (underscore)
            "key.resource_backpacks.open_backpack",
            "key.resourcebackpacks.open_backpack",

            // Packed Up — uses non-standard "packedup.keys." prefix
            "packedup.keys.openbag",

            // L2 Backpack
            "key.l2backpack.open",

            // Beans Backpacks (v1/v2/v3)
            "key.beansbackpacks.action",
            "key.beansbackpacks.inventory",
            "key.beansbackpacks.instant",
            "key.beansbackpacks.shorthand",
    };

    /**
     * Snapshot of tracked KeyMapping names → their current InputConstants.Key.
     * Used to detect runtime key rebinds without restart.
     */
    private static final Map<String, InputConstants.Key> trackedMappingSnapshot = new HashMap<>();
    private static volatile InputConstants.Key trackedInventoryKey;

    /**
     * Call from KeyClickMixin when a target key is pressed or released.
     * Records whether the triggering key is one of our tracked target keys
     * (vanilla inventory key OR backpack-mod key).
     */
    public static void updateLastTriggerKeyIsVanilla(InputConstants.Key key) {
        if (key == null) {
            lastTriggerKeyIsVanilla = false;
            return;
        }
        // Only the actual inventory key triggers swap behavior — backpack keys do not
        lastTriggerKeyIsVanilla = trackedInventoryKey != null
                && trackedInventoryKey.getType() == key.getType()
                && trackedInventoryKey.getValue() == key.getValue();
    }

    private SwapKeyState() {}

    /** Config reference for use by Mixins and other classes that can't receive config via init(). */
    static SwapConfigAdapter config;

    /** Called by InstantSwapClient.init() to make config available to platform-independent code. */
    public static void setConfig(SwapConfigAdapter cfg) { config = cfg; }
    public static SwapConfigAdapter getConfig() { return config; }

    /** Call once during client init to scan all registered KeyMappings and populate targetKeys. */
    public static void refreshTargetKeys(InputConstants.Key inventoryKey) {
        Set<InputConstants.Key> keys = new HashSet<>();
        // Always include the vanilla inventory key
        if (inventoryKey != null) {
            keys.add(inventoryKey);
            SwapLog.debug("SwapKeyState.refreshTargetKeys: added inventory key={}", inventoryKey.getName());
        } else {
            SwapLog.warn("SwapKeyState.refreshTargetKeys: inventory key is null!");
        }

        // Scan all registered key mappings for backpack-mod keys
        int backpackKeysFound = 0;
        try {
            for (KeyMapping km : getAllKeyMappings()) {
                String name = km.getName();
                for (String pattern : BACKPACK_KEY_PATTERNS) {
                    if (name.equals(pattern)) {
                        keys.add(InputConstants.getKey(km.saveString()));
                        SwapLog.debug("SwapKeyState.refreshTargetKeys: found backpack key: {} -> {}",
                                pattern, InputConstants.getKey(km.saveString()).getName());
                        backpackKeysFound++;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            SwapLog.warn("SwapKeyState.refreshTargetKeys: failed to scan key mappings: {}", e.toString());
        }

        targetKeys = Collections.unmodifiableSet(keys);
        trackedInventoryKey = inventoryKey;
        saveSnapshot(/* inventoryKey= */ inventoryKey);
        SwapLog.info("SwapKeyState.refreshTargetKeys: total target keys = {} (backpack mod keys found: {})",
                keys.size(), backpackKeysFound);
    }

    /**
     * Call every client tick. Detects if any tracked key mapping has been rebound
     * and refreshes target keys automatically.
     */
    public static void checkForKeyRebind(InputConstants.Key currentInventoryKey) {
        if (trackedInventoryKey != null && !trackedInventoryKey.equals(currentInventoryKey)) {
            SwapLog.info("checkForKeyRebind: inventory key changed from {} to {}, refreshing",
                    trackedInventoryKey.getName(), currentInventoryKey.getName());
            refreshTargetKeys(currentInventoryKey);
            return;
        }
        for (KeyMapping km : getAllKeyMappings()) {
            String name = km.getName();
            InputConstants.Key snapshot = trackedMappingSnapshot.get(name);
            if (snapshot != null && !snapshot.equals(InputConstants.getKey(km.saveString()))) {
                SwapLog.info("checkForKeyRebind: key mapping '{}' changed from {} to {}, refreshing",
                        name, snapshot.getName(), InputConstants.getKey(km.saveString()).getName());
                refreshTargetKeys(currentInventoryKey);
                return;
            }
        }
    }

    private static void saveSnapshot(InputConstants.Key inventoryKey) {
        trackedMappingSnapshot.clear();
        for (KeyMapping km : getAllKeyMappings()) {
            for (String pattern : BACKPACK_KEY_PATTERNS) {
                if (km.getName().equals(pattern)) {
                    trackedMappingSnapshot.put(km.getName(), InputConstants.getKey(km.saveString()));
                    break;
                }
            }
        }
    }

    /** Returns true if the given key is one of the intercepted target keys. */
    public static boolean isTargetKey(InputConstants.Key key) {
        return targetKeys.contains(key);
    }

    /** Returns the current set of target keys (for physical-key polling). */
    public static Set<InputConstants.Key> getTargetKeys() {
        return targetKeys;
    }

    /** Reflection-based access to KeyMapping.ALL (private field). Fallback only — the primary path uses the injected keyMappingsSupplier. */
    @SuppressWarnings("unchecked")
    private static Collection<KeyMapping> getAllKeyMappings() {
        try {
            Field f = KeyMapping.class.getDeclaredField("ALL");
            f.setAccessible(true);
            return ((Map<String, KeyMapping>) f.get(null)).values();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
