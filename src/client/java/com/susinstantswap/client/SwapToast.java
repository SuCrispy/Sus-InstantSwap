package com.susinstantswap.client;

import com.susinstantswap.config.SwapConfigAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * 游戏内浮窗提示工具。
 * <p>
 * 使用 action bar 显示提示信息，并内置冷却机制避免频繁刷屏。
 * <ul>
 *   <li>警告提示：黄色</li>
 * </ul>
 * <p>当前仅实现 {@link #warn}（黄色警告提示）。
 */
public final class SwapToast {

    /** 两次提示之间的最小间隔（毫秒） */
    private static final long COOLDOWN_MS = 1500;

    /** 上次显示提示的时间戳（System.nanoTime 毫秒等效） */
    private static long lastToastTime;

    private static SwapConfigAdapter config;

    /** 黄色文本颜色（0xFFFF00），替代 26.2 中精简的 ChatFormatting */
    private static final TextColor WARN_COLOR = TextColor.fromRgb(0xFFFF00);

    public static void init(SwapConfigAdapter cfg) {
        config = cfg;
    }

    private SwapToast() {}

    /**
     * 显示黄色警告提示。
     * @param key 翻译键
     * @param args 格式化参数
     */
    public static void warn(String key, Object... args) {
        show(WARN_COLOR, key, args);
    }

    private static void show(TextColor color, String key, Object... args) {
        if (config == null || !config.toastEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        long now = System.currentTimeMillis();
        if (now - lastToastTime < COOLDOWN_MS) return;
        lastToastTime = now;

        MutableComponent msg = Component.translatable(key, args);
        // 将整个消息着色
        msg.setStyle(Style.EMPTY.withColor(color));

        // 使用 action bar 显示（第二个参数 true = overlay）
        mc.player.sendOverlayMessage(msg);
    }
}
