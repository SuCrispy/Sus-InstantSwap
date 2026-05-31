package com.susinstantswap.client;

/**
 * Shared state between mixins and InstantSwapClient.
 */
public final class SwapKeyState {
    public static volatile boolean inventoryKeyHeld = false;
    public static volatile long pressStartNanos = 0;
    public static volatile boolean longPressConfirmed = false;
    /** GUI swap completed — close on next tick so server can update visuals first. */
    public static volatile boolean closeRequested = false;
    /** Master switch — when false, the entire mod is disabled. Synced from config. */
    public static volatile boolean modEnabled = true;
    private SwapKeyState() {}
}
