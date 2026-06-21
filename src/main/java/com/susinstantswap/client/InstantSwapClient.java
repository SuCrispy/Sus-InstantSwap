package com.susinstantswap.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.susinstantswap.SwapLog;
import com.susinstantswap.config.SwapConfigAdapter;
import com.susinstantswap.mixin.KeyMappingAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.InteractionResult;
import org.lwjgl.glfw.GLFW;

/**
 * Fabric platform adapter — event wiring layer.
 * All core swap logic lives in {@link SwapEngine}.
 */
public class InstantSwapClient {

    private static KeyMapping SWAP_IN_GUI_KEY;
    private static SwapConfigAdapter config;

    enum SwapState { IDLE, WATCHING, LONG_PRESS }
    private static SwapState state = SwapState.IDLE;

    private static boolean configLogged = false;
    private static boolean cursorRepositionedThisPress = false;
    private static boolean guiSwapKeyWasDown = false;
    private static boolean wasAnyTargetKeyDown = false;

    public static void init(SwapConfigAdapter cfg) {
        config = cfg;
        SwapLog.init(cfg);
        SwapToast.init(cfg);
        RowArrowWidget.init(cfg);
        SwapKeyState.setConfig(cfg);
        // Bypass Loom intermediary remap breaking reflection on KeyMapping.ALL
        SwapKeyState.setKeyMappingsSupplier(() -> {
            Minecraft mc = Minecraft.getInstance();
            return (mc != null && mc.options != null && mc.options.keyMappings != null)
                    ? java.util.Arrays.asList(mc.options.keyMappings)
                    : java.util.Collections.emptyList();
        });
        SWAP_IN_GUI_KEY = new KeyMapping("key.susinstantswap.swap_in_gui",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT,
                "key.categories.susinstantswap");
        KeyBindingHelper.registerKeyBinding(SWAP_IN_GUI_KEY);

        ClientTickEvents.END_CLIENT_TICK.register(InstantSwapClient::onClientTick);
        ScreenEvents.AFTER_INIT.register(InstantSwapClient::onScreenInitPost);

        // Per-screen event registration for container screens:
        //  1) allowKeyPress — block target key REPEAT to prevent flicker/close
        //     (equivalent to NF's onScreenKeyPressedPre; needed because some mods
        //      override keyPressed without calling super)
        //  2) afterRender  — draw row-swap arrows for backpack screens
        //     (equivalent to NF's onScreenRenderPost; ContainerScreenMixin skips
        //      backpack screens that may not call super.render)
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> acs)) return;

            ScreenKeyboardEvents.allowKeyPress(screen).register((s, key, scancode, mods) -> {
                if (!SwapKeyState.modEnabled || !SwapKeyState.inventoryKeyHeld) return true;
                return !SwapKeyState.isTargetKey(InputConstants.getKey(key, scancode));
            });

            if (BackpackScreenMatcher.isBackpackScreen(acs)) {
                ScreenEvents.afterRender(screen).register((s, gfx, mx, my, dt) -> {
                    SwapConfigAdapter c = config;
                    if (c == null || !c.rowSwapEnabled()) return;
                    Minecraft mc2 = Minecraft.getInstance();
                    if (mc2.player == null) return;
                    RowArrowWidget.detectRows(acs, mc2.player);
                    RowArrowWidget.visible = true;
                    RowArrowWidget.checkHover(mx, my);
                    gfx.pose().pushPose();
                    RowArrowWidget.render(mc2, gfx);
                    gfx.pose().popPose();
                });
            }
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            screenOpenedByInteract = true;
            return InteractionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            screenOpenedByInteract = true;
            return InteractionResult.PASS;
        });

        SwapLog.info("v3.0.0 client initialized (Fabric 1.20.1)");
    }

    public static void registerKey(Object event) {}

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

    // ── Tick handler ──

    private static void onClientTick(Minecraft mc) {
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
            wasAnyTargetKeyDown = false;
            return;
        }

        // ── GLFW-polling key state (overrides Mixin-based tracking) ──
        boolean keyDown = isAnyTargetKeyPhysicallyDown(mc);
        boolean keyJustPressed = keyDown && !wasAnyTargetKeyDown;

        SwapKeyState.inventoryKeyHeld = keyDown;

        if (keyJustPressed) {
            SwapLog.debug("KEY: pressed (screen={} prevScreen={})", mc.screen, previousScreen);
            SwapKeyState.pressStartNanos = System.nanoTime();
            // screenWasOpenAtPressStart is set by KeyClickMixin.onClick
            // (real-time mc.screen, not stale previousScreen).
            SwapKeyState.closePendingTicks = 0;
        }

        wasAnyTargetKeyDown = keyDown;
        previousScreen = mc.screen;

        // ── Close countdown ──
        if (SwapKeyState.closePendingTicks > 0) {
            SwapKeyState.closePendingTicks--;
            if (SwapKeyState.closePendingTicks == 0 && mc.screen instanceof AbstractContainerScreen) {
                mc.player.closeContainer();
                SwapKeyState.screenWasOpenAtPressStart = false;
                previousScreen = null;
            }
        }

        // ── GUI swap key (polled, only when different from target keys) ──
        if (config.guiSwapEnabled() && !SWAP_IN_GUI_KEY.isUnbound()
                && mc.screen instanceof AbstractContainerScreen) {
            InputConstants.Key guiKey = ((KeyMappingAccessor) SWAP_IN_GUI_KEY).getKey();
            if (!SwapKeyState.isTargetKey(guiKey)) {
                boolean down = isGuiSwapKeyPhysicallyDown(mc);
                if (down && !guiSwapKeyWasDown) {
                    SwapEngine.performSwap(mc, config);
                }
                guiSwapKeyWasDown = down;
            }
        }

        // ── State machine ──
        if (state == SwapState.IDLE) {
            if (SwapKeyState.inventoryKeyHeld && !SwapKeyState.screenWasOpenAtPressStart
                    && mc.screen instanceof AbstractContainerScreen) {
                SwapKeyState.pressStartNanos = System.nanoTime();
                SwapLog.debug("STATE: IDLE -> WATCHING");
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
            if (!SwapKeyState.inventoryKeyHeld) { state = SwapState.IDLE; cursorRepositionedThisPress = false; return; }
            long elapsed = System.nanoTime() - SwapKeyState.pressStartNanos;
            if (elapsed >= config.holdThresholdMs() * 1_000_000L) {
                SwapLog.debug("STATE: WATCHING -> LONG_PRESS ({}ms)", elapsed / 1_000_000);
                state = SwapState.LONG_PRESS;
            }
            return;
        }

        if (state == SwapState.LONG_PRESS) {
            if (mc.screen == null) { state = SwapState.IDLE; cursorRepositionedThisPress = false; return; }
            if (!SwapKeyState.inventoryKeyHeld) {
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

    // ── Public API ──

    public static boolean tryPerformGuiSwap() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return false;
        if (!config.guiSwapEnabled() || SWAP_IN_GUI_KEY.isUnbound()) return false;
        return SwapEngine.performSwap(mc, config);
    }

    public static InputConstants.Key getGuiSwapKey() {
        return SWAP_IN_GUI_KEY != null ? ((KeyMappingAccessor) SWAP_IN_GUI_KEY).getKey() : null;
    }

    public static boolean isGuiSwapKeyUnbound() {
        return SWAP_IN_GUI_KEY == null || SWAP_IN_GUI_KEY.isUnbound();
    }

    // ── Private helpers ──

    private static boolean isAnyTargetKeyPhysicallyDown(Minecraft mc) {
        long window = mc.getWindow().getWindow();
        for (InputConstants.Key k : SwapKeyState.getTargetKeys()) {
            if (k.getType() == InputConstants.Type.KEYSYM
                    && GLFW.glfwGetKey(window, k.getValue()) == GLFW.GLFW_PRESS) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGuiSwapKeyPhysicallyDown(Minecraft mc) {
        if (SWAP_IN_GUI_KEY.isUnbound()) return false;
        InputConstants.Key bk = ((KeyMappingAccessor) SWAP_IN_GUI_KEY).getKey();
        long window = mc.getWindow().getWindow();
        return bk.getType() == InputConstants.Type.KEYSYM
                && GLFW.glfwGetKey(window, bk.getValue()) == GLFW.GLFW_PRESS;
    }

    private static void positionCursorIfEnabled(Minecraft mc, Screen screen) {
        if (!config.mouseReposition() || !(screen instanceof AbstractContainerScreen<?> s)) return;
        positionCursorToUIBottomRight(s);
    }

    private static void positionCursorToUIBottomRight(AbstractContainerScreen<?> s) {
        Minecraft mc = Minecraft.getInstance();
        long h = mc.getWindow().getWindow();
        double gs = mc.getWindow().getGuiScale();
        int targetX = (int) ((ScreenAccess.getLeftPos(s) + ScreenAccess.getImageWidth(s)) * gs) - 5;
        int targetY = (int) ((ScreenAccess.getTopPos(s) + ScreenAccess.getImageHeight(s)) * gs) - 5;
        ScreenAccess.setMouseX(mc.mouseHandler, targetX);
        ScreenAccess.setMouseY(mc.mouseHandler, targetY);
        GLFW.glfwSetCursorPos(h, targetX, targetY);
    }
}
