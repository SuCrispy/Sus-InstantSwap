package com.susinstantswap.config;

import com.susinstantswap.SusInstantSwapMod;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Forge native config screen using vanilla widgets only.
 */
public class ForgeConfigScreen extends Screen {

    private final Screen parent;
    private static final int BUTTON_WIDTH = 200;
    private static final int SLIDER_WIDTH = 200;
    private static final int WIDGET_HEIGHT = 20;
    private static final int SPACING = 24;

    public ForgeConfigScreen(Screen parent) {
        super(Component.translatable("config.susinstantswap.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = 40;

        addRenderableWidget(createToggle(centerX, y,
                "config.susinstantswap.modEnabled", () -> SwapConfig.modEnabledRuntime,
                v -> SwapConfig.modEnabledRuntime = v));
        y += SPACING;

        addRenderableWidget(new HoldThresholdSlider(centerX - SLIDER_WIDTH / 2, y, SLIDER_WIDTH, WIDGET_HEIGHT));
        y += SPACING;

        addRenderableWidget(createToggle(centerX, y,
                "config.susinstantswap.soundEnabled", () -> SwapConfig.soundEnabledRuntime,
                v -> SwapConfig.soundEnabledRuntime = v));
        y += SPACING;

        addRenderableWidget(createToggle(centerX, y,
                "config.susinstantswap.mouseReposition", () -> SwapConfig.mouseRepositionRuntime,
                v -> SwapConfig.mouseRepositionRuntime = v));
        y += SPACING;

        addRenderableWidget(createToggle(centerX, y,
                "config.susinstantswap.guiSwapEnabled", () -> SwapConfig.guiSwapEnabledRuntime,
                v -> SwapConfig.guiSwapEnabledRuntime = v));
        y += SPACING;

        addRenderableWidget(createToggle(centerX, y,
                "config.susinstantswap.emptySlotSwapEnabled", () -> SwapConfig.emptySlotSwapEnabledRuntime,
                v -> SwapConfig.emptySlotSwapEnabledRuntime = v));
        y += SPACING;

        addRenderableWidget(createToggle(centerX, y,
                "config.susinstantswap.debug", () -> SwapConfig.debugRuntime,
                v -> SwapConfig.debugRuntime = v));
        y += SPACING;

        y += 12;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, btn -> saveAndClose())
                .pos(centerX - 50, y).size(100, WIDGET_HEIGHT).build());
    }

    private Button createToggle(int centerX, int y, String key, java.util.function.BooleanSupplier getter, java.util.function.Consumer<Boolean> setter) {
        Component label = Component.translatable(key);
        boolean init = getter.getAsBoolean();
        return Button.builder(makeToggleLabel(label, init), btn -> {
                    boolean v = !getter.getAsBoolean();
                    setter.accept(v);
                    btn.setMessage(makeToggleLabel(label, v));
                })
                .pos(centerX - BUTTON_WIDTH / 2, y).size(BUTTON_WIDTH, WIDGET_HEIGHT).build();
    }

    private Component makeToggleLabel(Component label, boolean on) {
        return Component.literal(on ? "\u00a7a\u2714 " : "\u00a7c\u2718 ").append(label);
    }

    private void saveAndClose() {
        SusInstantSwapMod.CONFIG.syncToSpec();
        SusInstantSwapMod.CONFIG_SPEC.save();
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(gui);
        gui.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);
        super.render(gui, mouseX, mouseY, partialTicks);
    }

    @Override
    public void onClose() {
        saveAndClose();
    }

    private static class HoldThresholdSlider extends AbstractSliderButton {
        private static final int MIN = 50, MAX = 1000;

        HoldThresholdSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(),
                    (SwapConfig.holdThresholdMsRuntime - MIN) / (double)(MAX - MIN));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int val = MIN + (int)Math.round(value * (MAX - MIN));
            SwapConfig.holdThresholdMsRuntime = val;
            setMessage(Component.translatable("config.susinstantswap.holdThresholdMs.value", val));
        }

        @Override
        protected void applyValue() {}
    }
}
