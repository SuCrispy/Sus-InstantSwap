package com.susinstantswap.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.susinstantswap.SwapLog;
import com.susinstantswap.config.SwapConfigAdapter;
import com.susinstantswap.mixin.KeyMappingAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
// TODO[Fabric 26.1]: verify KeyBindingHelper package. The v1 package
// (net.fabricmc.fabric.api.client.keybinding.v1) may have moved in Fabric API
// 0.145 for MC 26.1; using the non-versioned package as the candidate path.
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.InteractionResult;
import java.lang.reflect.Field;

import org.lwjgl.glfw.GLFW;

/**
 * Fabric platform adapter — event wiring layer.
 * <p>
 * All core swap logic lives in {@link SwapEngine}.  This class handles
 * only platform-specific event registration and delegates work to SwapEngine
 * or other platform-independent helpers.
 */
public class InstantSwapClient {

    private static KeyMapping SWAP_IN_GUI_KEY;
    private static SwapConfigAdapter config;

    enum SwapState { IDLE, WATCHING, LONG_PRESS }
    private static SwapState state = SwapState.IDLE;

    private static boolean configLogged = false;
    private static boolean cursorRepositionedThisPress = false;

    public static void init(SwapConfigAdapter cfg) {
        config = cfg;
        SwapLog.init(cfg);
        SwapToast.init(cfg);
        RowArrowWidget.init(cfg);
        SwapKeyState.setConfig(cfg);
        SWAP_IN_GUI_KEY = new KeyMapping("key.susinstantswap.swap_in_gui",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT,
                KeyMapping.Category.MISC);
        // MC 26.1: KeyMapping constructor auto-registers via ALL.put(); no KeyBindingHelper needed

        ClientTickEvents.END_CLIENT_TICK.register(InstantSwapClient::onClientTick);
        ScreenEvents.AFTER_INIT.register(InstantSwapClient::onScreenInitPost);

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            screenOpenedByInteract = true;
            return InteractionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            screenOpenedByInteract = true;
            return InteractionResult.PASS;
        });

        SwapLog.info("v3.0.0 client initialized (Fabric 26.1)");
    }

    public static void registerKey(Object event) {
        // Key already registered via KeyBindingHelper in init()
    }

    private static boolean screenOpenedByInteract = false;
    private static Screen previousScreen = null;

    private static void onScreenInitPost(Minecraft mc, Screen screen, int scaledWidth, int scaledHeight) {
        if (!config.mouseReposition()) return;
        if (!(screen instanceof AbstractContainerScreen<?> s)) return;
        if (s instanceof InventoryScreen || s instanceof CreativeModeInventoryScreen) return;

        boolean byInteraction = screenOpenedByInteract;
        screenOpenedByInteract = false;

        boolean isTopLevel = !(previousScreen instanceof AbstractContainerScreen);

        boolean openedDuringLongPress = SwapKeyState.inventoryKeyHeld
                && SwapKeyState.lastTriggerKeyIsVanilla
                && BackpackScreenMatcher.isBackpackScreen(s);

        if (byInteraction || (isTopLevel && !openedDuringLongPress)) {
            positionCursorToUIBottomRight(s);
        }
    }

    private static void onClientTick(Minecraft mc) {
        previousScreen = mc.screen;

        SwapEngine.tickVerification(mc);

        if (!configLogged) {
            configLogged = true;
            SwapKeyState.refreshTargetKeys(((KeyMappingAccessor) mc.options.keyInventory).getKey());
            SwapLog.info("Config: mod={} threshold={}ms sound={} guiSwap={} emptySwap={} rowSwap={} hotbarPri={} debug={} mouse={} toast={}",
                    config.modEnabled(), config.holdThresholdMs(), config.soundEnabled(),
                    config.guiSwapEnabled(), config.emptySlotSwapEnabled(),
                    config.rowSwapEnabled(), config.hotbarPriorityEnabled(),
                    config.debug(), config.mouseReposition(), config.toastEnabled());
        }

        SwapKeyState.checkForKeyRebind(((KeyMappingAccessor) mc.options.keyInventory).getKey());
        SwapKeyState.modEnabled = config.modEnabled();
        if (!SwapKeyState.modEnabled) return;

        if (mc.player == null || mc.gameMode == null) {
            state = SwapState.IDLE;
            SwapKeyState.closePendingTicks = 0;
            return;
        }

        if (SwapKeyState.closePendingTicks > 0) {
            SwapKeyState.closePendingTicks--;
            if (SwapKeyState.closePendingTicks == 0 && mc.screen instanceof AbstractContainerScreen)
                mc.player.closeContainer();
        }

        if (state == SwapState.IDLE) {
            if (SwapKeyState.inventoryKeyHeld && !SwapKeyState.screenWasOpenAtPressStart
                    && mc.screen instanceof AbstractContainerScreen) {
                if (!cursorRepositionedThisPress) {
                    positionCursorIfEnabled(mc, mc.screen);
                    cursorRepositionedThisPress = true;
                }
                state = SwapState.WATCHING;
            }
            return;
        }

        if (state == SwapState.WATCHING) {
            if (mc.screen == null) { state = SwapState.IDLE; cursorRepositionedThisPress = false; return; }
            if (!isAnyTargetKeyPhysicallyDown(mc)) {
                state = SwapState.IDLE;
                cursorRepositionedThisPress = false;
                return;
            }
            if ((System.nanoTime() - SwapKeyState.pressStartNanos)
                    >= config.holdThresholdMs() * 1_000_000L) {
                state = SwapState.LONG_PRESS;
            }
            return;
        }

        if (state == SwapState.LONG_PRESS) {
            if (mc.screen == null) { state = SwapState.IDLE; cursorRepositionedThisPress = false; return; }
            if (!isAnyTargetKeyPhysicallyDown(mc) || !SwapKeyState.inventoryKeyHeld) {
                boolean swapped = SwapEngine.performSwap(mc, config);
                if (!swapped) {
                    int closeDelay = (mc.screen instanceof AbstractContainerScreen<?> s
                            && SwapEngine.isVanillaInventory(s)) ? 1 : 2;
                    SwapKeyState.closePendingTicks = closeDelay;
                }
                state = SwapState.IDLE;
                cursorRepositionedThisPress = false;
            }
        }

        // GUI swap key check (polled in tick for Fabric)
        if (config.guiSwapEnabled() && !SWAP_IN_GUI_KEY.isUnbound()
                && mc.screen instanceof AbstractContainerScreen) {
            if (isGuiSwapKeyPhysicallyDown(mc)) {
                SwapEngine.performSwap(mc, config);
            }
        }
    }

    public static boolean tryPerformGuiSwap() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return false;
        if (!config.guiSwapEnabled() || SWAP_IN_GUI_KEY.isUnbound()) return false;
        return SwapEngine.performSwap(mc, config);
    }

    // ── Key binding accessors ──

    public static InputConstants.Key getGuiSwapKey() {
        return SWAP_IN_GUI_KEY != null ? ((KeyMappingAccessor) SWAP_IN_GUI_KEY).getKey() : null;
    }

    public static boolean isGuiSwapKeyUnbound() {
        return SWAP_IN_GUI_KEY == null || SWAP_IN_GUI_KEY.isUnbound();
    }

    // ── Private helpers ──

    private static boolean isAnyTargetKeyPhysicallyDown(Minecraft mc) {
        long window = mc.getWindow().handle();
        for (InputConstants.Key key : SwapKeyState.getTargetKeys()) {
            if (key.getType() == InputConstants.Type.KEYSYM
                    && GLFW.glfwGetKey(window, key.getValue()) == GLFW.GLFW_PRESS) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGuiSwapKeyPhysicallyDown(Minecraft mc) {
        if (SWAP_IN_GUI_KEY.isUnbound()) return false;
        InputConstants.Key bk = ((KeyMappingAccessor) SWAP_IN_GUI_KEY).getKey();
        long window = mc.getWindow().handle();
        return bk.getType() == InputConstants.Type.KEYSYM
                && GLFW.glfwGetKey(window, bk.getValue()) == GLFW.GLFW_PRESS;
    }

    private static void positionCursorIfEnabled(Minecraft mc, Screen screen) {
        if (!config.mouseReposition() || !(screen instanceof AbstractContainerScreen<?> s)) return;
        positionCursorToUIBottomRight(s);
    }

    private static void positionCursorToUIBottomRight(AbstractContainerScreen<?> s) {
        Minecraft mc = Minecraft.getInstance();
        long h = mc.getWindow().handle();
        double gs = mc.getWindow().getGuiScale();
        int targetX = (int) ((ScreenAccess.getLeftPos(s) + ScreenAccess.getImageWidth(s)) * gs) - 5;
        int targetY = (int) ((ScreenAccess.getTopPos(s) + ScreenAccess.getImageHeight(s)) * gs) - 5;

        MouseHandler mh = mc.mouseHandler;
        try {
            Field f = mh.getClass().getDeclaredField("xpos");
            f.setAccessible(true);
            f.setDouble(mh, targetX);
            f = mh.getClass().getDeclaredField("ypos");
            f.setAccessible(true);
            f.setDouble(mh, targetY);
        } catch (Exception e) {
            SwapLog.debug("mouseReposition: reflection failed — {}", e.toString());
        }
        GLFW.glfwSetCursorPos(h, targetX, targetY);
    }
}
