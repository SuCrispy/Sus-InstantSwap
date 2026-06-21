package com.susinstantswap.config;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import com.susinstantswap.SusInstantSwapMod;

public class ForgeConfigScreen extends Screen {
    private final Screen parent;
    private net.minecraft.client.gui.components.AbstractSliderButton thresholdSlider;

    public ForgeConfigScreen(Screen parent) {
        super(Component.translatable("config.susinstantswap.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int y = 40;
        int leftCol = this.width / 2 - 155;
        int rightCol = this.width / 2 + 5;

        this.addRenderableWidget(CycleButton.onOffBuilder(SusInstantSwapMod.CONFIG.modEnabled.get())
                .create(leftCol, y, 150, 20, Component.translatable("config.susinstantswap.modEnabled"),
                        (b, v) -> SusInstantSwapMod.CONFIG.modEnabled.set(v)));
        this.addRenderableWidget(CycleButton.onOffBuilder(SusInstantSwapMod.CONFIG.soundEnabled.get())
                .create(rightCol, y, 150, 20, Component.translatable("config.susinstantswap.soundEnabled"),
                        (b, v) -> SusInstantSwapMod.CONFIG.soundEnabled.set(v)));
        y += 24;

        this.addRenderableWidget(CycleButton.onOffBuilder(SusInstantSwapMod.CONFIG.mouseReposition.get())
                .create(leftCol, y, 150, 20, Component.translatable("config.susinstantswap.mouseReposition"),
                        (b, v) -> SusInstantSwapMod.CONFIG.mouseReposition.set(v)));
        this.addRenderableWidget(CycleButton.onOffBuilder(SusInstantSwapMod.CONFIG.rowSwapEnabled.get())
                .create(rightCol, y, 150, 20, Component.translatable("config.susinstantswap.rowSwapEnabled"),
                        (b, v) -> SusInstantSwapMod.CONFIG.rowSwapEnabled.set(v)));
        y += 24;

        // Threshold slider (50-1000ms)
        int currentMs = SusInstantSwapMod.CONFIG.holdThresholdMs.get();
        this.thresholdSlider = new net.minecraft.client.gui.components.AbstractSliderButton(
                leftCol, y, 150, 20,
                Component.translatable("config.susinstantswap.holdThresholdMs", currentMs),
                (double)(currentMs - 50) / 950.0) {
            @Override
            protected void updateMessage() {
                setMessage(Component.translatable("config.susinstantswap.holdThresholdMs",
                        (int)(this.value * 950 + 50)));
            }
            @Override
            protected void applyValue() {
                SusInstantSwapMod.CONFIG.holdThresholdMs.set((int)(this.value * 950 + 50));
            }
        };
        this.addRenderableWidget(thresholdSlider);
        this.addRenderableWidget(CycleButton.onOffBuilder(SusInstantSwapMod.CONFIG.guiSwapEnabled.get())
                .create(rightCol, y, 150, 20, Component.translatable("config.susinstantswap.guiSwapEnabled"),
                        (b, v) -> SusInstantSwapMod.CONFIG.guiSwapEnabled.set(v)));
        y += 24;

        this.addRenderableWidget(CycleButton.onOffBuilder(SusInstantSwapMod.CONFIG.emptySlotSwapEnabled.get())
                .create(leftCol, y, 150, 20, Component.translatable("config.susinstantswap.emptySlotSwapEnabled"),
                        (b, v) -> SusInstantSwapMod.CONFIG.emptySlotSwapEnabled.set(v)));
        this.addRenderableWidget(CycleButton.onOffBuilder(SusInstantSwapMod.CONFIG.hotbarPriorityEnabled.get())
                .create(rightCol, y, 150, 20, Component.translatable("config.susinstantswap.hotbarPriorityEnabled"),
                        (b, v) -> SusInstantSwapMod.CONFIG.hotbarPriorityEnabled.set(v)));
        y += 24;

        this.addRenderableWidget(CycleButton.onOffBuilder(SusInstantSwapMod.CONFIG.toastEnabled.get())
                .create(leftCol, y, 150, 20, Component.translatable("config.susinstantswap.toastEnabled"),
                        (b, v) -> SusInstantSwapMod.CONFIG.toastEnabled.set(v)));
        this.addRenderableWidget(CycleButton.onOffBuilder(SusInstantSwapMod.CONFIG.debug.get())
                .create(rightCol, y, 150, 20, Component.translatable("config.susinstantswap.debug"),
                        (b, v) -> SusInstantSwapMod.CONFIG.debug.set(v)));
        y += 30;

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
                .bounds(this.width / 2 - 100, y, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
