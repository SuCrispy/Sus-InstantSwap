package com.susinstantswap.client.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu integration — provides the config screen accessible from the ModMenu
 * mod list. ModMenu 18.0.0-beta.1 (MC 26.1) API:
 * {@code ConfigScreenFactory<S extends Screen>} with {@code S create(Screen parent)}.
 */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SwapConfigScreen::new;
    }
}
