package com.susinstantswap;

import com.susinstantswap.client.InstantSwapClient;
import com.susinstantswap.config.ForgeConfigScreen;
import com.susinstantswap.config.SwapConfig;
import net.minecraftforge.common.ForgeConfigSpec;
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

        // Config screen via ModMenu / mods list
        ModLoadingContext.get().registerExtensionPoint(
                net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory(
                        (mc, screen) -> new ForgeConfigScreen(screen)
                )
        );

        // Key mappings are registered inside InstantSwapClient.init() via
        // RegisterKeyMappingsEvent.BUS.addListener (mod event bus). The old
        // @Mod-class @SubscribeEvent handler did NOT receive the mod-bus event
        // under FML 8 auto-scan, so it has been removed.
        InstantSwapClient.init(CONFIG);
        SwapLog.info("v3.0.0 initialized (Forge 26.1)");
    }
}
