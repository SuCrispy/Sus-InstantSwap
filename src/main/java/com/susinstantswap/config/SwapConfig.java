package com.susinstantswap.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class SwapConfig {
    public final ModConfigSpec.BooleanValue modEnabled;
    public final ModConfigSpec.IntValue holdThresholdMs;
    public final ModConfigSpec.BooleanValue soundEnabled;
    public final ModConfigSpec.BooleanValue mouseReposition;
    public final ModConfigSpec.BooleanValue guiSwapEnabled;
    public final ModConfigSpec.BooleanValue emptySlotSwapEnabled;
    public final ModConfigSpec.BooleanValue debug;

    public SwapConfig(ModConfigSpec.Builder builder) {
        builder.comment("Su's Instant Swap Configuration", "", "Changes take effect immediately.");
        modEnabled = builder.translation("config.susinstantswap.modEnabled")
                .comment("Master switch — disable to turn off all mod functionality").define("modEnabled", true);
        holdThresholdMs = builder.translation("config.susinstantswap.holdThresholdMs")
                .comment("Long Press Threshold (ms). Range: 50~1000").defineInRange("holdThresholdMs", 200, 50, 1000);
        soundEnabled = builder.translation("config.susinstantswap.soundEnabled")
                .comment("Swap Sound").define("soundEnabled", true);
        mouseReposition = builder.translation("config.susinstantswap.mouseReposition")
                .comment("Auto-move cursor to bottom-right when container opens").define("mouseReposition", true);
        guiSwapEnabled = builder.translation("config.susinstantswap.guiSwapEnabled")
                .comment("Press inventory key on a slot to swap and close").define("guiSwapEnabled", false);
        emptySlotSwapEnabled = builder.translation("config.susinstantswap.emptySlotSwapEnabled")
                .comment("Also swap empty slots").define("emptySlotSwapEnabled", false);
        debug = builder.translation("config.susinstantswap.debug")
                .comment("Debug Logging").define("debug", false);
    }
}
