package com.susinstantswap.client;

/**
 * Shared volatile state between Mixins and InstantSwapClient.
 */
public final class SwapKeyState {
    public static volatile boolean inventoryKeyHeld = false;
    public static volatile long pressStartNanos = 0;
    public static volatile boolean longPressConfirmed = false;
    /** Swap completed — countdown to close (0=idle, 1=vanilla/creative, 2=mod containers). */
    public static volatile int closePendingTicks = 0;
    /** Master switch — when false, the mod is completely disabled. Synced from config. */
    public static volatile boolean modEnabled = true;
    private SwapKeyState() {}
}
