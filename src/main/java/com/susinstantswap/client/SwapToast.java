package com.susinstantswap.client;

import com.susinstantswap.config.SwapConfig;
import net.minecraft.ChatFormatting;
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
 *   <li>信息提示：白色/绿色</li>
 *   <li>警告提示：黄色</li>
 *   <li>错误提示：红色</li>
 * </ul>
 */
public final class SwapToast {

    /** 两次提示之间的最小间隔（毫秒） */
    private static final long COOLDOWN_MS = 1500;

    /** 上次显示提示的时间戳（System.nanoTime 毫秒等效） */
    private static long lastToastTime;

    private static SwapConfig config;

    public static void init(SwapConfig cfg) {
        config = cfg;
    }

    private SwapToast() {}

    /**
     * 显示绿色/白色信息提示。
     * @param key 翻译键
     * @param args 格式化参数
     */
    public static void info(String key, Object... args) {
        show(ChatFormatting.WHITE, key, args);
    }

    /**
     * 显示黄色警告提示。
     * @param key 翻译键
     * @param args 格式化参数
     */
    public static void warn(String key, Object... args) {
        show(ChatFormatting.YELLOW, key, args);
    }

    /**
     * 显示红色错误提示。
     * @param key 翻译键
     * @param args 格式化参数
     */
    public static void error(String key, Object... args) {
        show(ChatFormatting.RED, key, args);
    }

    private static void show(ChatFormatting color, String key, Object... args) {
        if (config == null || !config.toastEnabled.get()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        long now = System.currentTimeMillis();
        if (now - lastToastTime < COOLDOWN_MS) return;
        lastToastTime = now;

        MutableComponent msg = Component.translatable(key, args);
        // 将整个消息着色
        msg.setStyle(Style.EMPTY.withColor(TextColor.fromLegacyFormat(color)));

        // 使用 action bar 显示（第二个参数 true = overlay）
        mc.player.displayClientMessage(msg, true);
    }
}