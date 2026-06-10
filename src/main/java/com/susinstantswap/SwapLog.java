package com.susinstantswap;

import com.mojang.logging.LogUtils;
import com.susinstantswap.config.SwapConfig;
import org.slf4j.Logger;

/**
 * Unified logging facade for Sus-InstantSwap.
 * <p>
 * Log level is controlled by the {@code debug} config option:
 * <ul>
 *   <li>{@code debug=true}  → {@link #debug(String, Object...)} outputs at INFO level</li>
 *   <li>{@code debug=false} → {@link #debug(String, Object...)} is suppressed</li>
 * </ul>
 * {@link #info}, {@link #warn}, {@link #error} always output regardless of debug mode.
 * <p>
 * Call {@link #init(SwapConfig)} once during mod construction before any logging occurs.
 */
public final class SwapLog {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static SwapConfig config;

    private SwapLog() {}

    /**
     * Bind the mod config so that {@link #debug} can read the live {@code debug} flag.
     * Must be called once before any log statements.
     */
    public static void init(SwapConfig cfg) {
        config = cfg;
    }

    /** Always-visible informational log. */
    public static void info(String msg, Object... args) {
        LOGGER.info("[SusInstantSwap] " + msg, args);
    }

    /**
     * Debug-level log.  Only emitted when the {@code debug} config option is {@code true}.
     * If config has not been initialised yet this method is a no-op.
     */
    public static void debug(String msg, Object... args) {
        if (config != null && config.debug.get()) {
            LOGGER.info("[SusInstantSwap] " + msg, args);
        }
    }

    /** Warning log — always visible. */
    public static void warn(String msg, Object... args) {
        LOGGER.warn("[SusInstantSwap] " + msg, args);
    }

    /** Error log — always visible. */
    public static void error(String msg, Object... args) {
        LOGGER.error("[SusInstantSwap] " + msg, args);
    }

    /** Returns true when debug logging is active (config loaded and debug=true). */
    public static boolean shouldDebug() {
        return config != null && config.debug.get();
    }
}