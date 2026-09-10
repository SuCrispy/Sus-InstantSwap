package com.susinstantswap;

import com.susinstantswap.client.InstantSwapClient;
import com.susinstantswap.config.ForgeConfigScreen;
import com.susinstantswap.config.SwapConfig;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod("susinstantswap")
public class SusInstantSwapMod {

    public static final String MOD_ID = "susinstantswap";
    public static SwapConfig CONFIG;
    public static ForgeConfigSpec CONFIG_SPEC;

    public SusInstantSwapMod(FMLJavaModLoadingContext context) {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        CONFIG = new SwapConfig(builder);
        CONFIG_SPEC = builder.build();

        context.registerConfig(ModConfig.Type.CLIENT, CONFIG_SPEC);

        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(ForgeConfigScreen::new)
        );

        // In Forge 61.x (FG 7), getModEventBus() is removed.
        // RegisterKeyMappingsEvent uses the BUS pattern instead.
        net.minecraftforge.client.event.RegisterKeyMappingsEvent.BUS.addListener(InstantSwapClient::registerKey);
        InstantSwapClient.init(CONFIG);
        SwapLog.info("v3.0.0 initialized (Forge 1.21.11)");
    }

}
