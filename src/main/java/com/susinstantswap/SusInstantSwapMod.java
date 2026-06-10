package com.susinstantswap;

import com.susinstantswap.client.InstantSwapClient;
import com.susinstantswap.client.SwapToast;
import com.susinstantswap.config.SwapConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = "susinstantswap", dist = Dist.CLIENT)
public class SusInstantSwapMod {
    public static final String MOD_ID = "susinstantswap";
    public static SwapConfig CONFIG;
    public static ModConfigSpec CONFIG_SPEC;

    public SusInstantSwapMod(IEventBus modEventBus, ModContainer modContainer) {
        SwapLog.info("[SusInstantSwap] v2.2.0");
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        CONFIG = new SwapConfig(b);
        CONFIG_SPEC = b.build();
        SwapLog.init(CONFIG);
        SwapToast.init(CONFIG);
        SwapLog.info("Sus-InstantSwap v2.2.0 initializing...");
        modContainer.registerConfig(ModConfig.Type.CLIENT, CONFIG_SPEC);
        if (FMLEnvironment.dist == Dist.CLIENT)
            modContainer.registerExtensionPoint(IConfigScreenFactory.class, (c, s) -> new ConfigurationScreen(c, s));
        modEventBus.register(this);
        InstantSwapClient.init(CONFIG);
        SwapLog.info("Sus-InstantSwap v2.2.0 initialized successfully");
    }

    @SubscribeEvent
    public void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        InstantSwapClient.registerKey(event);
    }
}