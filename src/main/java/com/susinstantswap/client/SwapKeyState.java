package com.susinstantswap.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.susinstantswap.SwapLog;
import net.minecraft.client.KeyMapping;

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
    public static volatile boolean longPressConfirmed = false;
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

    /** Set of {@link InputConstants.Key} that should be intercepted (inventory + backpack mod keys). */
    private static volatile Set<InputConstants.Key> targetKeys = Collections.emptySet();

    /**
     * Known translation-key patterns for backpack-mod open-backpack bindings.
     * Add new entries here when supporting additional mods.
     */
    private static final String[] BACKPACK_KEY_PATTERNS = {
            "key.sophisticatedbackpacks.open_backpack",
            "key.travelersbackpack.open_backpack",
            "key:omnis_backpack",
            "key.backpacked.open_backpack",
            "key.inmis.open_backpack",
            "key.goodbackpacks.open_backpack",
            "key.resource_backpacks.open_backpack",
            "key.ironbackpacks.open_backpack",
            "key.simplybackpacks.open_backpack",
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
        // A key is "vanilla for our purposes" if it matches ANY target key
        // (inventory key + all tracked backpack-mod keys).
        lastTriggerKeyIsVanilla = matchesAnyTargetKey(key);
    }

    /** Compares type and value against all target keys (inventory + backpack mods). */
    private static boolean matchesAnyTargetKey(InputConstants.Key key) {
        for (InputConstants.Key target : targetKeys) {
            if (target.getType() == key.getType() && target.getValue() == key.getValue()) {
                return true;
            }
        }
        return false;
    }

    private SwapKeyState() {}

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
            for (KeyMapping km : KeyMapping.ALL.values()) {
                String name = km.getName();
                for (String pattern : BACKPACK_KEY_PATTERNS) {
                    if (name.equals(pattern)) {
                        keys.add(km.getKey());
                        SwapLog.debug("SwapKeyState.refreshTargetKeys: found backpack key: {} -> {}",
                                pattern, km.getKey().getName());
                        backpackKeysFound++;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            SwapLog.warn("SwapKeyState.refreshTargetKeys: failed to scan KeyMapping.ALL: {}", e.toString());
            // KeyMapping.ALL may not be accessible in some environments; fall back to inventory-only
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
        for (KeyMapping km : KeyMapping.ALL.values()) {
            String name = km.getName();
            InputConstants.Key snapshot = trackedMappingSnapshot.get(name);
            if (snapshot != null && !snapshot.equals(km.getKey())) {
                SwapLog.info("checkForKeyRebind: key mapping '{}' changed from {} to {}, refreshing",
                        name, snapshot.getName(), km.getKey().getName());
                refreshTargetKeys(currentInventoryKey);
                return;
            }
        }
    }

    private static void saveSnapshot(InputConstants.Key inventoryKey) {
        trackedInventoryKey = inventoryKey;
        trackedMappingSnapshot.clear();
        for (KeyMapping km : KeyMapping.ALL.values()) {
            for (String pattern : BACKPACK_KEY_PATTERNS) {
                if (km.getName().equals(pattern)) {
                    trackedMappingSnapshot.put(km.getName(), km.getKey());
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
}
