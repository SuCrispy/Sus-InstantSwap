package com.susinstantswap;

import com.susinstantswap.client.InstantSwapClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

@Mod(value = "susinstantswap")
public class SusInstantSwapMod {

    public static final String MOD_ID = "susinstantswap";

    public SusInstantSwapMod(IEventBus modEventBus) {
        modEventBus.register(this);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            InstantSwapClient.init();
        }
    }

    @SubscribeEvent
    public void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        InstantSwapClient.registerKey(event);
    }
}
