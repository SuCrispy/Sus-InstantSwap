package com.susinstantswap.client.compat;

import com.susinstantswap.client.InstantSwapClient;
import com.susinstantswap.config.SwapConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/**
 * All-button config screen — each option is a full-width button or slider.
 * MC 26.1: render → extractRenderState, renderBackground → extractBackground,
 * drawCenteredString → centeredText.
 */
public class SwapConfigScreen extends Screen {
    private static final int ROW_W = 310;
    private static final int ROW_H = 20;
    private static final int GAP = 2;
    private static final int TITLE_Y = 16;
    private static final int TOP = 34;
    private static final int DONE_W = 200;
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

        addBoolean(cx, y, "config.susinstantswap.modEnabled",
                cfg.modEnabled, v -> { cfg.modEnabled = v; save(); });
        y += ROW_H + GAP;
        addSlider(cx, y);
        y += ROW_H + GAP;
        addBoolean(cx, y, "config.susinstantswap.soundEnabled",
                cfg.soundEnabled, v -> { cfg.soundEnabled = v; save(); });
        y += ROW_H + GAP;
        addBoolean(cx, y, "config.susinstantswap.mouseReposition",
                cfg.mouseReposition, v -> { cfg.mouseReposition = v; save(); });
        y += ROW_H + GAP;
        addBoolean(cx, y, "config.susinstantswap.guiSwapEnabled",
                cfg.guiSwapEnabled, v -> { cfg.guiSwapEnabled = v; save(); });
        y += ROW_H + GAP;
        addBoolean(cx, y, "config.susinstantswap.emptySlotSwapEnabled",
                cfg.emptySlotSwapEnabled, v -> { cfg.emptySlotSwapEnabled = v; save(); });
        y += ROW_H + GAP;
        addBoolean(cx, y, "config.susinstantswap.debug",
                cfg.debug, v -> { cfg.debug = v; save(); });

        int doneY = this.height - DONE_H - 8;
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.done"),
                btn -> Minecraft.getInstance().setScreen(parent))
                .pos(cx + (ROW_W - DONE_W) / 2, doneY).width(DONE_W).build());
    }

    private void addBoolean(int cx, int y, String key, boolean initial,
                            java.util.function.Consumer<Boolean> setter) {
        final boolean[] st = { initial };
        Button btn = Button.builder(
                buttonText(key, st[0]),
                b -> {
                    st[0] = !st[0];
                    setter.accept(st[0]);
                    b.setMessage(buttonText(key, st[0]));
                })
                .pos(cx, y).width(ROW_W).build();
        this.addRenderableWidget(btn);
    }

    private void addSlider(int cx, int y) {
        this.addRenderableWidget(new HoldThresholdSlider(cx, y, ROW_W, ROW_H, cfg));
    }

    private static Component buttonText(String key, boolean on) {
        return Component.translatable(key)
                .append(Component.literal(": "))
                .append(on ? ON : OFF);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        // extractBackground is already called by extractRenderStateWithTooltipAndSubtitles in MC 26.1
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.centeredText(this.font, this.title, this.width / 2, TITLE_Y, 0xFFFFFF);
    }

    @Override
    public void onClose() { Minecraft.getInstance().setScreen(parent); }

    private void save() { cfg.save(); }

    private static final Component ON  = Component.translatable("options.on")
            .withStyle(ChatFormatting.GREEN);
    private static final Component OFF = Component.translatable("options.off")
            .withStyle(ChatFormatting.RED);

    /** Slider with label + value text rendered inside. */
    private static class HoldThresholdSlider extends AbstractSliderButton {
        private static final int MIN = 50, MAX = 1000;
        private final SwapConfig cfg;

        HoldThresholdSlider(int x, int y, int w, int h, SwapConfig cfg) {
            super(x, y, w, h, Component.empty(), 0.0);
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
