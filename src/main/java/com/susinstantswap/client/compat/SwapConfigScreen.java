package com.susinstantswap.client.compat;

import com.susinstantswap.SusInstantSwapMod;
import com.susinstantswap.config.SwapConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class SwapConfigScreen extends Screen {
    private final Screen parent;
    private final SwapConfig config;
    private net.minecraft.client.gui.components.AbstractSliderButton thresholdSlider;

    public SwapConfigScreen(Screen parent) {
        super(Component.translatable("config.susinstantswap.title"));
        this.parent = parent;
        this.config = SusInstantSwapMod.CONFIG;
    }

    @Override
    protected void init() {
        int y = 40;
        int leftCol = this.width / 2 - 155;
        int rightCol = this.width / 2 + 5;

        // Mod Enabled
        this.addRenderableWidget(CycleButton.onOffBuilder(config.modEnabled)
                .create(leftCol, y, 150, 20, Component.translatable("config.susinstantswap.modEnabled"),
                        (b, v) -> config.modEnabled = v));
        // Sound
        this.addRenderableWidget(CycleButton.onOffBuilder(config.soundEnabled)
                .create(rightCol, y, 150, 20, Component.translatable("config.susinstantswap.soundEnabled"),
                        (b, v) -> config.soundEnabled = v));
        y += 24;

        // Mouse Reposition
        this.addRenderableWidget(CycleButton.onOffBuilder(config.mouseReposition)
                .create(leftCol, y, 150, 20, Component.translatable("config.susinstantswap.mouseReposition"),
                        (b, v) -> config.mouseReposition = v));
        // Row Swap
        this.addRenderableWidget(CycleButton.onOffBuilder(config.rowSwapEnabled)
                .create(rightCol, y, 150, 20, Component.translatable("config.susinstantswap.rowSwapEnabled"),
                        (b, v) -> config.rowSwapEnabled = v));
        y += 24;

        // Hold Threshold (slider, 50-1000ms)
        this.thresholdSlider = new net.minecraft.client.gui.components.AbstractSliderButton(
                leftCol, y, 150, 20,
                sliderLabel(config.holdThresholdMs),
                (double)(config.holdThresholdMs - 50) / 950.0) {
            @Override
            protected void updateMessage() {
                setMessage(sliderLabel((int)(this.value * 950 + 50)));
            }
            @Override
            protected void applyValue() {
                config.holdThresholdMs = (int)(this.value * 950 + 50);
            }
        };
        this.addRenderableWidget(thresholdSlider);
        // GUI Swap
        this.addRenderableWidget(CycleButton.onOffBuilder(config.guiSwapEnabled)
                .create(rightCol, y, 150, 20, Component.translatable("config.susinstantswap.guiSwapEnabled"),
                        (b, v) -> config.guiSwapEnabled = v));
        y += 24;

        // Empty Slot Swap
        this.addRenderableWidget(CycleButton.onOffBuilder(config.emptySlotSwapEnabled)
                .create(leftCol, y, 150, 20, Component.translatable("config.susinstantswap.emptySlotSwapEnabled"),
                        (b, v) -> config.emptySlotSwapEnabled = v));
        // Hotbar Priority
        this.addRenderableWidget(CycleButton.onOffBuilder(config.hotbarPriorityEnabled)
                .create(rightCol, y, 150, 20, Component.translatable("config.susinstantswap.hotbarPriorityEnabled"),
                        (b, v) -> config.hotbarPriorityEnabled = v));
        y += 24;

        // Toast
        this.addRenderableWidget(CycleButton.onOffBuilder(config.toastEnabled)
                .create(leftCol, y, 150, 20, Component.translatable("config.susinstantswap.toastEnabled"),
                        (b, v) -> config.toastEnabled = v));
        // Debug
        this.addRenderableWidget(CycleButton.onOffBuilder(config.debug)
                .create(rightCol, y, 150, 20, Component.translatable("config.susinstantswap.debug"),
                        (b, v) -> config.debug = v));
        y += 30;

        // Done
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
                .bounds(this.width / 2 - 100, y, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    /**
     * Build the slider label as "<localized title>: <value> ms".
     * The lang strings for holdThresholdMs carry no %s placeholder, so passing
     * the value as a translation arg silently drops it. We append the number
     * explicitly so the slider always shows the current value.
     */
    private static Component sliderLabel(int ms) {
        return Component.translatable("config.susinstantswap.holdThresholdMs")
                .copy().append(Component.literal(": " + ms + " ms"));
    }

    @Override
    public void onClose() {
        config.save();
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
