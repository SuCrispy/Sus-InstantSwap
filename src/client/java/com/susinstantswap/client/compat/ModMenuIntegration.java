package com.susinstantswap.client.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu integration for SusInstantSwap Fabric 26.1.
 * ModMenu 18.0.0-beta.1 supports MC 26.1.
 */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SwapConfigScreen::new;
    }
}
