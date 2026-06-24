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
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import java.lang.reflect.Field;

import org.lwjgl.glfw.GLFW;

/**
 * Forge platform adapter — event wiring layer.
 * <p>
 * All core swap logic lives in {@link SwapEngine}.  This class handles
 * only platform-specific event registration and delegates work to SwapEngine
 * or other platform-independent helpers.
 */
public class InstantSwapClient {

    private static KeyMapping SWAP_IN_GUI_KEY;
    private static SwapConfigAdapter config;

    /**
     * Dedicated key category so the binding shows under its own group in the
     * Controls screen. On 26.1 the KeyBindsList groups bindings by a
     * registered {@link KeyMapping.Category}; the built-in MISC constant does
     * NOT surface modded bindings there, so we register our own category
     * (matches the verified v2.0.0 behaviour).
     */
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("susinstantswap", "main"));

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
                CATEGORY);
        MinecraftForge.EVENT_BUS.register(InstantSwapClient.class);
        TickEvent.ClientTickEvent.Post.BUS.addListener(InstantSwapClient::onClientTick);
        // Key bindings must be registered on the mod event bus via the event's
        // own BUS (FML 8 / eventbus 7.0.1). The @Mod-class @SubscribeEvent
        // auto-scan does NOT deliver the mod-bus RegisterKeyMappingsEvent, so
        // the binding never reached the Controls screen before — this restores
        // the verified v2.0.0 registration path.
        RegisterKeyMappingsEvent.BUS.addListener(InstantSwapClient::registerKey);
        SwapLog.info("v3.0.0 initialized (Forge 26.1)");
    }
    public static void registerKey(RegisterKeyMappingsEvent event) {
        event.register(SWAP_IN_GUI_KEY);
        SwapLog.info("swap_in_gui key registered via RegisterKeyMappingsEvent");
    }

    private static boolean screenOpenedByInteract = false;
    private static Screen previousScreen = null;

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        screenOpenedByInteract = true;
    }

    @SubscribeEvent
    public static void onRightClickEntity(PlayerInteractEvent.EntityInteractSpecific event) {
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
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) return;
        SwapConfigAdapter cfg = config;
        if (cfg == null || !cfg.rowSwapEnabled()) {
            RowArrowWidget.visible = false;
            return;
        }

        // Single source of truth for the row-swap UI: handles ALL container
        // screens (survival inventory, chests, backpack mods). On 26.1 the
        // render-state architecture makes drawing during the Mixin's
        // extractRenderState TAIL ineffective for vanilla containers, so the
        // event path (Screen render Post) draws everything now.
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        RowArrowWidget.detectRows(screen, mc.player);
        RowArrowWidget.visible = true;
        RowArrowWidget.checkHover(event.getMouseX(), event.getMouseY());
        event.getGuiGraphics().pose().pushMatrix();
        RowArrowWidget.render(mc, event.getGuiGraphics());
        event.getGuiGraphics().pose().popMatrix();
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
                // event.setCanceled(true); // Forge 26.1 Pre event API changed
                return;
            }
        }
    }

    public static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        previousScreen = mc.screen;

        // Delegate swap verification to SwapEngine
        SwapEngine.tickVerification(mc);

        if (!configLogged) {
            configLogged = true;
            SwapKeyState.refreshTargetKeys(mc.options.keyInventory.getKey());
            SwapLog.info("v3.0.0 (Forge 26.1)");
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
        long window = mc.getWindow().handle();
        for (InputConstants.Key key : SwapKeyState.getTargetKeys()) {
            if (key.getType() == InputConstants.Type.KEYSYM
                    && GLFW.glfwGetKey(window, key.getValue()) == GLFW.GLFW_PRESS) {
                return true;
            }
        }
        return false;
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
        long h = mc.getWindow().handle();
        double gs = mc.getWindow().getGuiScale();
        int targetX = (int) ((s.getGuiLeft() + s.getXSize()) * gs) - 5;
        int targetY = (int) ((s.getGuiTop() + s.getYSize()) * gs) - 5;

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
