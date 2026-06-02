package com.susinstantswap;

import com.mojang.logging.LogUtils;
import com.susinstantswap.client.InstantSwapClient;
import com.susinstantswap.config.SwapConfig;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;

public class SusInstantSwapMod implements ClientModInitializer {
    public static final String MOD_ID = "susinstantswap";
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitializeClient() {
        LOGGER.info("[SusInstantSwap] v2.0 (Fabric 26.1) initializing...");
        SwapConfig config = SwapConfig.load();
        LOGGER.info("[SusInstantSwap] Config loaded — modEnabled={} threshold={}ms guiSwap={}",
                config.modEnabled, config.holdThresholdMs, config.guiSwapEnabled);
        InstantSwapClient.init(config);
    }
}
