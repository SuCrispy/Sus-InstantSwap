package com.susinstantswap.client.compat;

import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu integration — registers the mod in the ModMenu list.
 * <p>
 * No config screen is provided. The previous {@code ConfigScreenFactory} based
 * override was removed because that API is not available in the MC 26.1 ModMenu
 * build; the default {@link ModMenuApi#getModConfigScreenFactory()} (a no-op
 * factory) is used instead.
 * <p>
 * TODO[Fabric 26.1]: verify the ModMenu 26.1 config-screen API and, if desired,
 * re-add a config screen using whatever replaces ConfigScreenFactory.
 */
public class ModMenuIntegration implements ModMenuApi {
    // Intentionally empty — relies on ModMenuApi default methods.
}
