package com.susinstantswap;

import com.susinstantswap.client.InstantSwapClient;
import com.susinstantswap.config.SwapConfig;
import net.fabricmc.api.ClientModInitializer;

public class SusInstantSwapMod implements ClientModInitializer {
    public static final String MOD_ID = "susinstantswap";
    public static SwapConfig CONFIG;

    @Override
    public void onInitializeClient() {
        CONFIG = SwapConfig.load();
        InstantSwapClient.init(CONFIG);
    }
}
