package com.susinstantswap.config;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import com.susinstantswap.SusInstantSwapMod;

/**
 * Forge 26.1 config screen. MC 26.1 GUI API:
 * - render override is extractRenderState(GuiGraphicsExtractor, mx, my, partialTick)
 * - background auto-rendered by the screen pipeline (extractBackground)
 * - text drawing: drawCenteredString -> centeredText
 */
public class ForgeConfigScreen extends Screen {
    private final Screen parent;

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

        int currentMs = SusInstantSwapMod.CONFIG.holdThresholdMs.get();
        this.addRenderableWidget(new AbstractSliderButton(
                leftCol, y, 150, 20,
                sliderLabel(currentMs),
                (double) (currentMs - 50) / 950.0) {
            @Override
            protected void updateMessage() {
                setMessage(sliderLabel((int) (this.value * 950 + 50)));
            }
            @Override
            protected void applyValue() {
                SusInstantSwapMod.CONFIG.holdThresholdMs.set((int) (this.value * 950 + 50));
            }
        });
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

    /**
     * Build the slider label as "&lt;localized title&gt;: &lt;value&gt; ms".
     * The lang strings for holdThresholdMs carry no %s placeholder, so passing
     * the value as a translation arg silently drops it. We append the number
     * explicitly so the slider always shows the current value.
     */
    private static Component sliderLabel(int ms) {
        return Component.translatable("config.susinstantswap.holdThresholdMs")
                .copy().append(Component.literal(": " + ms + " ms"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 15, -1);
    }

    @Override
    public void onClose() {
        SusInstantSwapMod.CONFIG_SPEC.save();
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
