package com.susinstantswap.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class SwapConfig {
    public final ModConfigSpec.BooleanValue modEnabled;
    public final ModConfigSpec.IntValue holdThresholdMs;
    public final ModConfigSpec.BooleanValue soundEnabled;
    public final ModConfigSpec.BooleanValue mouseReposition;
    public final ModConfigSpec.BooleanValue guiSwapEnabled;
    public final ModConfigSpec.BooleanValue emptySlotSwapEnabled;
    public final ModConfigSpec.BooleanValue rowSwapEnabled;
    public final ModConfigSpec.BooleanValue toastEnabled;
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
                .comment("Open container or inventory, the mouse will automatically move to the bottom-right corner").define("mouseReposition", true);
        guiSwapEnabled = builder.translation("config.susinstantswap.guiSwapEnabled")
                .comment("In container screens, press the GUI swap key to directly swap items and close the screen").define("guiSwapEnabled", false);
        emptySlotSwapEnabled = builder.translation("config.susinstantswap.emptySlotSwapEnabled")
                .comment("Also swap empty slots").define("emptySlotSwapEnabled", false);
        rowSwapEnabled = builder.translation("config.susinstantswap.rowSwapEnabled")
                .comment("In survival inventory, show arrow icons on the left to swap entire rows with the hotbar").define("rowSwapEnabled", true);
        toastEnabled = builder.translation("config.susinstantswap.toastEnabled")
                .comment("Show action bar toast messages (warnings, errors, info)").define("toastEnabled", true);
        debug = builder.translation("config.susinstantswap.debug")
                .comment("Debug Logging").define("debug", false);
    }
}
