package com.susinstantswap.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class SwapConfig implements SwapConfigAdapter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().excludeFieldsWithoutExposeAnnotation().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("susinstantswap.json");

    @Expose public boolean modEnabled = true;
    @Expose public int holdThresholdMs = 200;
    @Expose public boolean soundEnabled = true;
    @Expose public boolean mouseReposition = true;
    @Expose public boolean hotbarPriorityEnabled = false;
    @Expose public boolean guiSwapEnabled = false;
    @Expose public boolean emptySlotSwapEnabled = false;
    @Expose public boolean rowSwapEnabled = true;
    @Expose public boolean toastEnabled = true;
    @Expose public boolean debug = false;

    public static SwapConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader r = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
                SwapConfig cfg = GSON.fromJson(r, SwapConfig.class);
                if (cfg != null) { cfg.clamp(); return cfg; }
            } catch (Exception ignored) {}
        }
        SwapConfig def = new SwapConfig();
        def.save();
        return def;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer w = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(this, w);
            }
        } catch (Exception ignored) {}
    }

    private void clamp() {
        if (holdThresholdMs < 50) holdThresholdMs = 50;
        if (holdThresholdMs > 1000) holdThresholdMs = 1000;
    }

    @Override public boolean modEnabled()            { return modEnabled; }
    @Override public int     holdThresholdMs()        { return holdThresholdMs; }
    @Override public boolean soundEnabled()           { return soundEnabled; }
    @Override public boolean mouseReposition()        { return mouseReposition; }
    @Override public boolean hotbarPriorityEnabled()  { return hotbarPriorityEnabled; }
    @Override public boolean guiSwapEnabled()         { return guiSwapEnabled; }
    @Override public boolean emptySlotSwapEnabled()   { return emptySlotSwapEnabled; }
    @Override public boolean rowSwapEnabled()         { return rowSwapEnabled; }
    @Override public boolean toastEnabled()           { return toastEnabled; }
    @Override public boolean debug()                  { return debug; }
}
