package com.susinstantswap.client.compat;

import com.susinstantswap.client.InstantSwapClient;
import com.susinstantswap.config.SwapConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;

/**
 * Vanilla-style config screen. Boolean rows have label left + toggle right.
 * Slider row has label left + short slider right (matching video settings).
 */
public class SwapConfigScreen extends Screen {
    private static final int ROW_H = 24;
    private static final int ROW_W = 310;
    private static final int TOGGLE_W = 56;
    private static final int SLIDER_W = 150;
    private static final int TITLE_Y = 17;
    private static final int TOP = 38;
    private static final int GAP = 1;
    private static final int DONE_H = 20;

    private final Screen parent;
    private final SwapConfig cfg;

    public SwapConfigScreen(Screen parent) {
        super(Component.translatable("config.susinstantswap.title"));
        this.parent = parent;
        this.cfg = InstantSwapClient.getConfig();
    }

    @Override
    protected void init() {
        int cx = (this.width - ROW_W) / 2;
        int y = TOP;

        addToggle(cx, y, cfg.modEnabled, v -> { cfg.modEnabled = v; save(); });
        y += ROW_H + GAP;
        addSlider(cx, y);
        y += ROW_H + GAP;
        addToggle(cx, y, cfg.soundEnabled, v -> { cfg.soundEnabled = v; save(); });
        y += ROW_H + GAP;
        addToggle(cx, y, cfg.mouseReposition, v -> { cfg.mouseReposition = v; save(); });
        y += ROW_H + GAP;
        addToggle(cx, y, cfg.guiSwapEnabled, v -> { cfg.guiSwapEnabled = v; save(); });
        y += ROW_H + GAP;
        addToggle(cx, y, cfg.emptySlotSwapEnabled, v -> { cfg.emptySlotSwapEnabled = v; save(); });
        y += ROW_H + GAP;
        addToggle(cx, y, cfg.debug, v -> { cfg.debug = v; save(); });

        // Done button at bottom, separated from options
        int doneY = this.height - DONE_H - 8;
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.done"),
                btn -> Minecraft.getInstance().setScreen(parent))
                .pos(cx + (ROW_W - 200) / 2, doneY).width(200).build());
    }

    private void addToggle(int cx, int y, boolean initial, java.util.function.Consumer<Boolean> setter) {
        boolean[] state = { initial };
        Button btn = Button.builder(
                state[0] ? ON : OFF,
                b -> {
                    state[0] = !state[0];
                    setter.accept(state[0]);
                    b.setMessage(state[0] ? ON : OFF);
                })
                .pos(cx + ROW_W - TOGGLE_W, y).width(TOGGLE_W).build();
        this.addRenderableWidget(btn);
    }

    private void addSlider(int cx, int y) {
        this.addRenderableWidget(new HoldThresholdSlider(cx + ROW_W - SLIDER_W, y, SLIDER_W, ROW_H, cfg));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        this.renderBackground(g, mouseX, mouseY, delta);
        super.render(g, mouseX, mouseY, delta);

        // Title
        g.drawCenteredString(this.font, this.title, this.width / 2, TITLE_Y, 0xFFFFFF);

        // Row labels
        int cx = (this.width - ROW_W) / 2;
        int y = TOP;
        drawLabel(g, cx, y, "config.susinstantswap.modEnabled");
        y += ROW_H + GAP;
        // Slider row — label drawn in render for slider
        g.drawString(this.font, Component.translatable("config.susinstantswap.holdThresholdMs"), cx, y + 6, 0xCCCCCC);
        y += ROW_H + GAP;
        drawLabel(g, cx, y, "config.susinstantswap.soundEnabled");
        y += ROW_H + GAP;
        drawLabel(g, cx, y, "config.susinstantswap.mouseReposition");
        y += ROW_H + GAP;
        drawLabel(g, cx, y, "config.susinstantswap.guiSwapEnabled");
        y += ROW_H + GAP;
        drawLabel(g, cx, y, "config.susinstantswap.emptySlotSwapEnabled");
        y += ROW_H + GAP;
        drawLabel(g, cx, y, "config.susinstantswap.debug");
    }

    private void drawLabel(GuiGraphics g, int cx, int y, String key) {
        g.drawString(this.font, Component.translatable(key), cx, y + 6, 0xCCCCCC);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private void save() { cfg.save(); }

    private static final Component ON  = Component.translatable("options.on").withStyle(ChatFormatting.GREEN);
    private static final Component OFF = Component.translatable("options.off").withStyle(ChatFormatting.RED);

    /** Compact slider for hold threshold (50–1000 ms). */
    private static class HoldThresholdSlider extends AbstractSliderButton {
        private static final int MIN = 50, MAX = 1000;
        private final SwapConfig cfg;

        HoldThresholdSlider(int x, int y, int width, int height, SwapConfig cfg) {
            super(x, y, width, height, Component.empty(), 0.0);
            this.cfg = cfg;
            this.value = clamp((double) (cfg.holdThresholdMs - MIN) / (MAX - MIN));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            int ms = (int) (MIN + value * (MAX - MIN));
            setMessage(Component.translatable("config.susinstantswap.holdThresholdMs")
                    .append(Component.literal(": " + ms + "ms")));
        }

        @Override
        protected void applyValue() {
            cfg.holdThresholdMs = (int) (MIN + value * (MAX - MIN));
            cfg.save();
        }

        private static double clamp(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
    }
}
