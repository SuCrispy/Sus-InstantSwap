package com.susinstantswap.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Sus-InstantSwap v2.0 — Forge config (Spec + Runtime dual-layer).
 */
public class SwapConfig {

    public final ForgeConfigSpec.BooleanValue modEnabled;
    public final ForgeConfigSpec.IntValue holdThresholdMs;
    public final ForgeConfigSpec.BooleanValue soundEnabled;
    public final ForgeConfigSpec.BooleanValue mouseReposition;
    public final ForgeConfigSpec.BooleanValue guiSwapEnabled;
    public final ForgeConfigSpec.BooleanValue emptySlotSwapEnabled;
    public final ForgeConfigSpec.BooleanValue debug;

    public static boolean modEnabledRuntime = true;
    public static int holdThresholdMsRuntime = 200;
    public static boolean soundEnabledRuntime = true;
    public static boolean mouseRepositionRuntime = true;
    public static boolean guiSwapEnabledRuntime = false;
    public static boolean emptySlotSwapEnabledRuntime = false;
    public static boolean debugRuntime = false;

    public SwapConfig(ForgeConfigSpec.Builder builder) {
        builder.comment("Su's Instant Swap v2.0 Configuration");

        modEnabled = builder
                .translation("config.susinstantswap.modEnabled")
                .comment("Master switch - off disables the mod completely")
                .define("modEnabled", true);

        holdThresholdMs = builder
                .translation("config.susinstantswap.holdThresholdMs")
                .comment("Duration (ms) above which a press is treated as long press. Range: 50-1000")
                .defineInRange("holdThresholdMs", 200, 50, 1000);

        soundEnabled = builder
                .translation("config.susinstantswap.soundEnabled")
                .comment("Play item pickup sound on swap")
                .define("soundEnabled", true);

        mouseReposition = builder
                .translation("config.susinstantswap.mouseReposition")
                .comment("Auto-move cursor to UI corner when container opens")
                .define("mouseReposition", true);

        guiSwapEnabled = builder
                .translation("config.susinstantswap.guiSwapEnabled")
                .comment("Enable in-GUI swap key (press swap key in container to swap + close)")
                .define("guiSwapEnabled", false);

        emptySlotSwapEnabled = builder
                .translation("config.susinstantswap.emptySlotSwapEnabled")
                .comment("Also perform swap when hovering over an empty slot")
                .define("emptySlotSwapEnabled", false);

        debug = builder
                .translation("config.susinstantswap.debug")
                .comment("Print debug info to game log")
                .define("debug", false);
    }

    public void syncToRuntime() {
        modEnabledRuntime = modEnabled.get();
        holdThresholdMsRuntime = holdThresholdMs.get();
        soundEnabledRuntime = soundEnabled.get();
        mouseRepositionRuntime = mouseReposition.get();
        guiSwapEnabledRuntime = guiSwapEnabled.get();
        emptySlotSwapEnabledRuntime = emptySlotSwapEnabled.get();
        debugRuntime = debug.get();
    }

    public void syncToSpec() {
        modEnabled.set(modEnabledRuntime);
        holdThresholdMs.set(holdThresholdMsRuntime);
        soundEnabled.set(soundEnabledRuntime);
        mouseReposition.set(mouseRepositionRuntime);
        guiSwapEnabled.set(guiSwapEnabledRuntime);
        emptySlotSwapEnabled.set(emptySlotSwapEnabledRuntime);
        debug.set(debugRuntime);
    }
}
