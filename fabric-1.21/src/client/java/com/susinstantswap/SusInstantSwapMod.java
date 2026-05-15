package com.susinstantswap;

import com.susinstantswap.client.InstantSwapClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Environment(EnvType.CLIENT)
public class SusInstantSwapMod implements ClientModInitializer {

    public static final String MOD_ID = "susinstantswap";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("[SusInstantSwap] 模组客户端初始化开始");
        InstantSwapClient.init();
        LOGGER.info("[SusInstantSwap] 模组客户端初始化完成");
    }
}
