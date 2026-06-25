package com.susinstantswap;

import com.mojang.logging.LogUtils;
import com.susinstantswap.config.SwapConfigAdapter;
import org.slf4j.Logger;

/**
 * Unified logging facade for Sus-InstantSwap.
 * <p>
 * Log level is controlled by the {@code debug} config option:
 * <ul>
 *   <li>{@code debug=true}  → {@link #debug(String, Object...)} outputs at INFO level</li>
 *   <li>{@code debug=false} → {@link #debug(String, Object...)} is suppressed</li>
 * </ul>
 * {@link #info} and {@link #warn} always output regardless of debug mode.
 * <p>
 * Call {@link #init(SwapConfigAdapter)} once during mod construction before any logging occurs.
 */
public final class SwapLog {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static SwapConfigAdapter config;

    private SwapLog() {}

    /**
     * Bind the mod config so that {@link #debug} can read the live {@code debug} flag.
     * Must be called once before any log statements.
     */
    public static void init(SwapConfigAdapter cfg) {
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
        if (config != null && config.debug()) {
            LOGGER.info("[SusInstantSwap] " + msg, args);
        }
    }

    /** Warning log — always visible. */
    public static void warn(String msg, Object... args) {
        LOGGER.warn("[SusInstantSwap] " + msg, args);
    }
}