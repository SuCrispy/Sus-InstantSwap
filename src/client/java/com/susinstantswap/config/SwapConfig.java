package com.susinstantswap.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;

/**
 * Sus-InstantSwap configuration for Fabric — Gson JSON based.
 * Config file: config/susinstantswap.json
 * Changes take effect immediately — no restart needed.
 */
public class SwapConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(SwapConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().excludeFieldsWithoutExposeAnnotation().create();

    @Expose public boolean modEnabled = true;
    @Expose public int holdThresholdMs = 200;
    @Expose public boolean soundEnabled = true;
    @Expose public boolean mouseReposition = true;
    @Expose public boolean guiSwapEnabled = false;
    @Expose public boolean emptySlotSwapEnabled = false;
    @Expose public boolean debug = false;

    private static SwapConfig INSTANCE;
    private static Path configPath;

    public static SwapConfig get() {
        if (INSTANCE == null) {
            INSTANCE = new SwapConfig();
            configPath = FabricLoader.getInstance().getConfigDir().resolve("susinstantswap.json");
            load();
        }
        return INSTANCE;
    }

    public static void load() {
        File file = configPath.toFile();
        if (file.exists()) {
            try (Reader reader = new FileReader(file)) {
                INSTANCE = GSON.fromJson(reader, SwapConfig.class);
                LOGGER.info("[SusInstantSwap] Config loaded: modEnabled={}, holdThresholdMs={}, sound={}, mouse={}, guiSwap={}, emptySwap={}, debug={}",
                        INSTANCE.modEnabled, INSTANCE.holdThresholdMs, INSTANCE.soundEnabled,
                        INSTANCE.mouseReposition, INSTANCE.guiSwapEnabled, INSTANCE.emptySlotSwapEnabled, INSTANCE.debug);
            } catch (Exception e) {
                LOGGER.warn("[SusInstantSwap] Config load failed, using defaults: {}", e.getMessage());
            }
        } else {
            save();
            LOGGER.info("[SusInstantSwap] Created default config file");
        }
    }

    public static void save() {
        if (configPath == null || INSTANCE == null) return;
        try {
            configPath.getParent().toFile().mkdirs();
            try (Writer writer = new FileWriter(configPath.toFile())) {
                GSON.toJson(INSTANCE, writer);
            }
        } catch (Exception e) {
            LOGGER.warn("[SusInstantSwap] Config save failed: {}", e.getMessage());
        }
    }
}
