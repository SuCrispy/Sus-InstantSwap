package com.susinstantswap.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.susinstantswap.SwapLog;
import com.susinstantswap.config.SwapConfigAdapter;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import java.lang.reflect.Field;

import org.lwjgl.glfw.GLFW;

/**
 * Forge 1.20.1 platform adapter — event wiring layer.
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
    private static boolean guiSwapKeyWasDown = false;

    public static void init(SwapConfigAdapter cfg) {
        config = cfg;
        SwapLog.init(cfg);
        SwapToast.init(cfg);
        RowArrowWidget.init(cfg);
        SwapKeyState.setConfig(cfg);
        SWAP_IN_GUI_KEY = new KeyMapping("key.susinstantswap.swap_in_gui",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT,
                "key.categories.susinstantswap");
        MinecraftForge.EVENT_BUS.register(InstantSwapClient.class);
        SwapLog.info("v3.0.0 (Forge 1.20.1)");
    }

    public static void registerKey(RegisterKeyMappingsEvent event) {
        event.register(SWAP_IN_GUI_KEY);
    }

    private static boolean screenOpenedByInteract = false;
    private static Screen previousScreen = null;

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        screenOpenedByInteract = true;
    }

    @SubscribeEvent
    public static void onRightClickEntity(PlayerInteractEvent.EntityInteract event) {
        screenOpenedByInteract = true;
    }

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!config.mouseReposition()) return;
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> s)) return;
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

    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!SwapKeyState.modEnabled) return;
        SwapConfigAdapter cfg = config;
        if (cfg == null || !cfg.rowSwapEnabled()) return;
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) return;

        if (!BackpackScreenMatcher.isBackpackScreen(screen)) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        RowArrowWidget.detectRows(screen, mc.player);
        RowArrowWidget.visible = true;
        RowArrowWidget.checkHover(event.getMouseX(), event.getMouseY());
        event.getGuiGraphics().pose().pushPose();
        RowArrowWidget.render(mc, event.getGuiGraphics());
        event.getGuiGraphics().pose().popPose();
    }

    @SubscribeEvent
    public static void onScreenKeyPressedPre(ScreenEvent.KeyPressed.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return;
        if (!SwapKeyState.inventoryKeyHeld) return;
        if (!(event.getScreen() instanceof AbstractContainerScreen)) return;

        for (InputConstants.Key target : SwapKeyState.getTargetKeys()) {
            if (target.getType() == InputConstants.Type.KEYSYM
                    && event.getKeyCode() == target.getValue()) {
                event.setCanceled(true);
                return;
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();

        previousScreen = mc.screen;

        // Delegate swap verification to SwapEngine
        SwapEngine.tickVerification(mc);

        if (!configLogged) {
            configLogged = true;
            SwapKeyState.refreshTargetKeys(mc.options.keyInventory.getKey());
            SwapLog.info("Config: mod={} threshold={}ms sound={} guiSwap={} emptySwap={} rowSwap={} hotbarPri={} debug={} mouse={} toast={}",
                    config.modEnabled(), config.holdThresholdMs(), config.soundEnabled(),
                    config.guiSwapEnabled(), config.emptySlotSwapEnabled(),
                    config.rowSwapEnabled(), config.hotbarPriorityEnabled(),
                    config.debug(), config.mouseReposition(), config.toastEnabled());
        }

        SwapKeyState.checkForKeyRebind(mc.options.keyInventory.getKey());
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

        // GUI swap key (polled) — runs before state machine; skipped when GUI
        // key matches a target key (ScreenKeyMixin handles that case).
        if (config.guiSwapEnabled() && !SWAP_IN_GUI_KEY.isUnbound()
                && mc.screen instanceof AbstractContainerScreen) {
            InputConstants.Key guiKey = SWAP_IN_GUI_KEY.getKey();
            if (!SwapKeyState.isTargetKey(guiKey)) {
                boolean down = isGuiSwapKeyPhysicallyDown(mc);
                if (down && !guiSwapKeyWasDown) {
                    SwapEngine.performSwap(mc, config);
                }
                guiSwapKeyWasDown = down;
            }
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
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return;

        int action = event.getAction();
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_RELEASE) return;

        boolean keyDown = (action == GLFW.GLFW_PRESS);

        // ── MC 1.20.1 fix: KeyMapping.set() doesn't fire when a container
        //    screen is open (passEvents=false), so inventoryKeyHeld is never
        //    reset on key release.  InputEvent.Key fires regardless of screen
        //    state, so we use it to keep the flag in sync.
        if (!keyDown) {
            InputConstants.Key eventKey = InputConstants.getKey(event.getKey(), event.getScanCode());
            if (SwapKeyState.isTargetKey(eventKey)) {
                SwapKeyState.inventoryKeyHeld = false;
            }
        }

        boolean isInventoryKey = isInventoryKeyEvent(mc, event);
        boolean isGuiSwapKey = !SWAP_IN_GUI_KEY.isUnbound() && isGuiSwapKeyEvent(event);

        if (keyDown && isInventoryKey && mc.screen != null && hasEditBoxFocus(mc.screen)) {
            while (mc.options.keyInventory.consumeClick()) {}
            if (mc.screen instanceof AbstractContainerScreen) {
                if (mc.player.containerMenu.getSlot(0).hasItem()) return;
            } else return;
        }

        if (keyDown && config.guiSwapEnabled()) {
            if (isGuiSwapKey && mc.screen instanceof AbstractContainerScreen) {
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
        return SWAP_IN_GUI_KEY != null ? SWAP_IN_GUI_KEY.getKey() : null;
    }

    public static boolean isGuiSwapKeyUnbound() {
        return SWAP_IN_GUI_KEY == null || SWAP_IN_GUI_KEY.isUnbound();
    }

    // ── Private helpers (Forge-specific) ──

    private static boolean isAnyTargetKeyPhysicallyDown(Minecraft mc) {
        long window = mc.getWindow().getWindow();
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
        InputConstants.Key bk = SWAP_IN_GUI_KEY.getKey();
        long window = mc.getWindow().getWindow();
        return bk.getType() == InputConstants.Type.KEYSYM
                && GLFW.glfwGetKey(window, bk.getValue()) == GLFW.GLFW_PRESS;
    }

    private static boolean isInventoryKeyEvent(Minecraft mc, InputEvent.Key event) {
        InputConstants.Key ik = mc.options.keyInventory.getKey();
        return ik.getType() == InputConstants.Type.KEYSYM && event.getKey() == ik.getValue();
    }

    private static boolean isGuiSwapKeyEvent(InputEvent.Key event) {
        if (SWAP_IN_GUI_KEY.isUnbound()) return false;
        InputConstants.Key bk = SWAP_IN_GUI_KEY.getKey();
        return bk.getType() == InputConstants.Type.KEYSYM && event.getKey() == bk.getValue();
    }

    private static boolean hasEditBoxFocus(Screen s) {
        if (s == null) return false;
        if (s.getFocused() instanceof EditBox) return true;
        for (var c : s.children()) if (c instanceof EditBox) return true;
        String n = s.getClass().getName();
        return n.contains("BookEdit") || n.contains("SignEdit");
    }

    private static void positionCursorIfEnabled(Minecraft mc, Screen screen) {
        if (!config.mouseReposition() || !(screen instanceof AbstractContainerScreen<?> s)) return;
        positionCursorToUIBottomRight(s);
    }

    private static void positionCursorToUIBottomRight(AbstractContainerScreen<?> s) {
        Minecraft mc = Minecraft.getInstance();
        long h = mc.getWindow().getWindow();
        double gs = mc.getWindow().getGuiScale();
        int targetX = (int) ((s.getGuiLeft() + s.getXSize()) * gs) - 5;
        int targetY = (int) ((s.getGuiTop() + s.getYSize()) * gs) - 5;

        MouseHandler mh = mc.mouseHandler;
        try {
            // Forge 1.20.1: dev uses Mojang names, runtime uses SRG names.
            // Try Mojang first, fall back to SRG (f_91507_ = xpos, f_91508_ = ypos).
            Field fx = getDeclaredFieldWithFallback(mh.getClass(), "xpos", "f_91507_");
            fx.setAccessible(true);
            fx.setDouble(mh, targetX);
            Field fy = getDeclaredFieldWithFallback(mh.getClass(), "ypos", "f_91508_");
            fy.setAccessible(true);
            fy.setDouble(mh, targetY);
        } catch (Exception e) {
            SwapLog.debug("mouseReposition: reflection failed — {}", e.toString());
        }
        GLFW.glfwSetCursorPos(h, targetX, targetY);
    }

    /**
     * Try Mojang field name first (works in dev); fall back to SRG name
     * (works at runtime in Forge 1.20.1 production).
     */
    private static Field getDeclaredFieldWithFallback(Class<?> clz, String mojang, String srg)
            throws NoSuchFieldException {
        try {
            return clz.getDeclaredField(mojang);
        } catch (NoSuchFieldException e) {
            return clz.getDeclaredField(srg);
        }
    }
}
