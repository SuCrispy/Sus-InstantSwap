package com.susinstantswap.client.compat;

import com.susinstantswap.SusInstantSwapMod;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Fabric 26.1 config screen, opened from ModMenu.
 * MC 26.1 GUI API: render override is extractRenderState(GuiGraphicsExtractor,...);
 * background auto-rendered; text via centeredText (replaces drawCenteredString).
 * Config is the Gson-backed {@link com.susinstantswap.config.SwapConfig} with public fields.
 */
public class SwapConfigScreen extends Screen {
    private final Screen parent;

    public SwapConfigScreen(Screen parent) {
        super(Component.translatable("config.susinstantswap.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        var cfg = SusInstantSwapMod.CONFIG;
        int y = 40;
        int leftCol = this.width / 2 - 155;
        int rightCol = this.width / 2 + 5;

        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.modEnabled)
                .create(leftCol, y, 150, 20, Component.translatable("config.susinstantswap.modEnabled"),
                        (b, v) -> cfg.modEnabled = v));
        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.soundEnabled)
                .create(rightCol, y, 150, 20, Component.translatable("config.susinstantswap.soundEnabled"),
                        (b, v) -> cfg.soundEnabled = v));
        y += 24;

        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.mouseReposition)
                .create(leftCol, y, 150, 20, Component.translatable("config.susinstantswap.mouseReposition"),
                        (b, v) -> cfg.mouseReposition = v));
        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.rowSwapEnabled)
                .create(rightCol, y, 150, 20, Component.translatable("config.susinstantswap.rowSwapEnabled"),
                        (b, v) -> cfg.rowSwapEnabled = v));
        y += 24;

        int currentMs = cfg.holdThresholdMs;
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
                cfg.holdThresholdMs = (int) (this.value * 950 + 50);
            }
        });
        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.guiSwapEnabled)
                .create(rightCol, y, 150, 20, Component.translatable("config.susinstantswap.guiSwapEnabled"),
                        (b, v) -> cfg.guiSwapEnabled = v));
        y += 24;

        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.emptySlotSwapEnabled)
                .create(leftCol, y, 150, 20, Component.translatable("config.susinstantswap.emptySlotSwapEnabled"),
                        (b, v) -> cfg.emptySlotSwapEnabled = v));
        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.hotbarPriorityEnabled)
                .create(rightCol, y, 150, 20, Component.translatable("config.susinstantswap.hotbarPriorityEnabled"),
                        (b, v) -> cfg.hotbarPriorityEnabled = v));
        y += 24;

        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.toastEnabled)
                .create(leftCol, y, 150, 20, Component.translatable("config.susinstantswap.toastEnabled"),
                        (b, v) -> cfg.toastEnabled = v));
        this.addRenderableWidget(CycleButton.onOffBuilder(cfg.debug)
                .create(rightCol, y, 150, 20, Component.translatable("config.susinstantswap.debug"),
                        (b, v) -> cfg.debug = v));
        y += 30;

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
                .bounds(this.width / 2 - 100, y, 200, 20).build());
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
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 15, -1);
    }

    @Override
    public void onClose() {
        SusInstantSwapMod.CONFIG.save();
        if (this.minecraft != null) this.minecraft.gui.setScreen(parent);
    }
}
