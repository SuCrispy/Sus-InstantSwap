package com.susinstantswap.config;

/**
 * Platform-independent config facade for SusInstantSwap.
 * <p>
 * All core logic (SwapEngine, SwapKeyState, etc.) reads config values
 * through this interface — never through platform-specific types like
 * {@code ModConfigSpec} or {@code SimpleConfig}.
 * <p>
 * Each platform provides its own implementation:
 * <ul>
 *   <li>NeoForge/Forge: {@code SwapConfig} wraps {@code ModConfigSpec} values</li>
 *   <li>Fabric: {@code SwapConfig} wraps plain fields backed by cloth-config or similar</li>
 * </ul>
 */
public interface SwapConfigAdapter {

    /** Master switch — disable to turn off all mod functionality. */
    boolean modEnabled();

    /** Long press threshold in milliseconds. Range: 50~1000. */
    int holdThresholdMs();

    /** Whether swap sound effects are enabled. */
    boolean soundEnabled();

    /** Whether auto mouse reposition on container open is enabled. */
    boolean mouseReposition();

    /** Whether the GUI swap key binding is enabled. */
    boolean guiSwapEnabled();

    /** Whether swapping empty slots to hotbar is allowed. */
    boolean emptySlotSwapEnabled();

    /** Whether row-swap arrow indicators and functionality are enabled. */
    boolean rowSwapEnabled();

    /** Whether toast (action-bar) messages are enabled. */
    boolean toastEnabled();

    /** Whether debug logging is enabled. */
    boolean debug();

    /**
     * Hotbar priority mode (survival only).
     * When swapping an item with a full hand, if any hotbar slot (other than
     * the currently selected one) is empty, the held item is stashed into that
     * empty slot first, then the target item is picked into the hand.
     * Falls back to normal swap if the hotbar is completely full.
     */
    boolean hotbarPriorityEnabled();
}
