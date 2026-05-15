package com.susinstantswap;

import com.mojang.logging.LogUtils;
import com.susinstantswap.client.InstantSwapClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.slf4j.Logger;

@Mod(value = "susinstantswap", dist = Dist.CLIENT)
public class SusInstantSwapMod {

    public static final String MOD_ID = "susinstantswap";
    private static final Logger LOGGER = LogUtils.getLogger();

    public SusInstantSwapMod(IEventBus modEventBus) {
        LOGGER.info("[SusInstantSwap] 模组构造器被调用 (开始加载)");
        modEventBus.register(this);
        LOGGER.info("[SusInstantSwap] 已注册模组主类到事件总线");

        if (FMLEnvironment.dist == Dist.CLIENT) {
            LOGGER.info("[SusInstantSwap] 当前环境为客户端，开始初始化客户端逻辑");
            InstantSwapClient.init();
        } else {
            LOGGER.warn("[SusInstantSwap] 非客户端环境，跳过客户端初始化");
        }
        LOGGER.info("[SusInstantSwap] 模组加载完成");
    }

    @SubscribeEvent
    public void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        LOGGER.info("[SusInstantSwap] 触发按键映射注册事件");
        InstantSwapClient.registerKey(event);
    }
}