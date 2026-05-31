package com.susinstantswap.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.susinstantswap.config.SwapConfig;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Sus-InstantSwap v2.0 for Fabric 1.21.1
 *
 * Ported from NeoForge 1.21.1 v2.0.0.
 * Uses vanilla inventory key (E) — coexists with vanilla behavior.
 *
 * Key differences from NF:
 *   - No NeoForge event bus → Fabric API callbacks
 *   - No InputEvent.Key → edge detection via KeyClickMixin state
 *   - No RenderTooltipEvent.Pre → AbstractContainerScreenMixin
 *   - No getSlotUnderMouse() → reflection (Loom remap safe)
 *   - No SlotWrapper instanceof → unwrapSlot() reflection
 *   - No ModConfigSpec → Gson JSON config
 */
public class InstantSwapClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static KeyMapping SWAP_IN_GUI_KEY;
    private static SwapConfig config;

    enum SwapState { IDLE, WATCHING, LONG_PRESS }
    private static SwapState state = SwapState.IDLE;

    private static boolean configLogged;
    private static boolean suppressNextTooltip;
    private static int suppressTooltipFrames;
    private static boolean prevInventoryKeyHeld;
    private static boolean prevGuiSwapDown;

    /** Track right-click interactions for container repositioning. */
    private static boolean screenOpenedByInteract;

    // ── Reflection caches ──
    private static Field hoveredSlotField;
    private static boolean hoveredSlotResolved;

    // ═══════════════════════════════════════════════════════════
    // Init
    // ═══════════════════════════════════════════════════════════

    public static void init() {
        LOGGER.info("[SusInstantSwap] v2.0.0");
        config = SwapConfig.get();

        SWAP_IN_GUI_KEY = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.susinstantswap.swap_in_gui",
                InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(),
                "key.categories.susinstantswap"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(InstantSwapClient::onClientTick);

        // Screen open → auto-reposition cursor for right-click containers
        ScreenEvents.AFTER_INIT.register((client, screen, sw, sh) -> onScreenInitPost(screen));

        // Track right-click interactions
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            screenOpenedByInteract = true;
            return InteractionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            screenOpenedByInteract = true;
            return InteractionResult.PASS;
        });

        LOGGER.info("[SusInstantSwap] Key bindings and events registered");
    }

    // ═══════════════════════════════════════════════════════════
    // GUI swap entry (from ScreenKeyMixin)
    // ═══════════════════════════════════════════════════════════

    public static boolean tryPerformGuiSwap(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return false;
        if (!config.guiSwapEnabled || !SWAP_IN_GUI_KEY.isUnbound()) return false;
        if (performSwap(mc)) {
            SwapKeyState.closeRequested = true;
            return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════
    // Screen init (right-click container reposition)
    // ═══════════════════════════════════════════════════════════

    private static void onScreenInitPost(Screen screen) {
        if (!config.mouseReposition) return;
        if (!(screen instanceof AbstractContainerScreen<?> s)) return;
        if (screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen) return;
        if (!screenOpenedByInteract) return;
        screenOpenedByInteract = false;
        positionCursorToUIBottomRight(s);
    }

    // ═══════════════════════════════════════════════════════════
    // Per-tick (ClientTickEvents.END_CLIENT_TICK)
    // ═══════════════════════════════════════════════════════════

    private static void onClientTick(Minecraft mc) {

        // ── Tooltip suppression frame counter ──
        if (suppressTooltipFrames > 0 && --suppressTooltipFrames == 0)
            suppressNextTooltip = false;

        // ── First-run config log ──
        if (!configLogged) {
            configLogged = true;
            LOGGER.info("[SusInstantSwap] Config: mod={} threshold={}ms sound={} guiSwap={} emptySwap={} debug={} mouse={}",
                    config.modEnabled, config.holdThresholdMs, config.soundEnabled,
                    config.guiSwapEnabled, config.emptySlotSwapEnabled,
                    config.debug, config.mouseReposition);
        }

        // ── Sync master switch ──
        SwapKeyState.modEnabled = config.modEnabled;
        if (!SwapKeyState.modEnabled) return;

        if (mc.player == null || mc.gameMode == null) {
            state = SwapState.IDLE;
            SwapKeyState.closeRequested = false;
            return;
        }

        // ── Edge detection for inventory key (replaces NF InputEvent.Key) ──
        boolean invKeyHeld = SwapKeyState.inventoryKeyHeld;
        boolean invKeyPressed = invKeyHeld && !prevInventoryKeyHeld;
        prevInventoryKeyHeld = invKeyHeld;

        // ── Edge detection for GUI swap key ──
        boolean guiSwapDown = !SWAP_IN_GUI_KEY.isUnbound() && SWAP_IN_GUI_KEY.isDown();
        boolean guiSwapPressed = guiSwapDown && !prevGuiSwapDown;
        prevGuiSwapDown = guiSwapDown;

        // ── Deferred close ──
        if (SwapKeyState.closeRequested) {
            if (mc.screen instanceof AbstractContainerScreen) {
                debugLog("deferred close");
                mc.player.closeContainer();
            }
            SwapKeyState.closeRequested = false;
            state = SwapState.IDLE;
        }

        // ── EditBox protection (replaces NF InputEvent.Key editbox logic) ──
        if (invKeyPressed && mc.screen != null && hasEditBoxFocus(mc.screen)) {
            while (mc.options.keyInventory.consumeClick()) {}
            if (mc.screen instanceof AbstractContainerScreen) {
                if (mc.player.containerMenu.getSlot(0).hasItem()) return;
            } else return;
        }

        // ── GUI swap on key press (replaces NF InputEvent.Key guiSwap logic) ──
        if (invKeyPressed && config.guiSwapEnabled) {
            // When SWAP_IN_GUI_KEY is unbound, inventory key doubles as GUI swap key
            boolean trigger = SWAP_IN_GUI_KEY.isUnbound();
            // When SWAP_IN_GUI_KEY is bound, use it instead
            if (!SWAP_IN_GUI_KEY.isUnbound() && guiSwapPressed) trigger = true;
            if (trigger && mc.screen instanceof AbstractContainerScreen) {
                if (performSwap(mc)) {
                    SwapKeyState.closeRequested = true;
                }
            }
            if (trigger) {
                return; // Don't process as normal long-press
            }
        }
        // Also handle guiSwapPressed separately (when only SWAP_IN_GUI_KEY bound)
        if (guiSwapPressed && config.guiSwapEnabled && !SWAP_IN_GUI_KEY.isUnbound()
                && mc.screen instanceof AbstractContainerScreen) {
            if (performSwap(mc)) {
                SwapKeyState.closeRequested = true;
            }
            return;
        }

        // ═══════════════════════════════════════════════════════
        // State Machine (ported from NF v2.0)
        // ═══════════════════════════════════════════════════════

        // ── IDLE: wait for screen to open after E press ──
        if (state == SwapState.IDLE) {
            if (SwapKeyState.inventoryKeyHeld && !SwapKeyState.longPressConfirmed) {
                if (mc.screen instanceof AbstractContainerScreen) {
                    SwapKeyState.pressStartNanos = System.nanoTime();
                    positionCursorIfEnabled(mc, mc.screen);
                    state = SwapState.WATCHING;
                    debugLog("WATCHING");
                }
            }
            return;
        }

        // ── WATCHING: check threshold ──
        if (state == SwapState.WATCHING) {
            if (mc.screen == null) { state = SwapState.IDLE; return; }
            if (!isInventoryKeyPhysicallyDown(mc)) { state = SwapState.IDLE; return; }
            if ((System.nanoTime() - SwapKeyState.pressStartNanos)
                    >= config.holdThresholdMs * 1_000_000L) {
                SwapKeyState.longPressConfirmed = true;
                state = SwapState.LONG_PRESS;
                debugLog("LONG_PRESS");
            }
            return;
        }

        // ── LONG_PRESS → release triggers swap ──
        if (state == SwapState.LONG_PRESS) {
            if (mc.screen == null) { state = SwapState.IDLE; return; }
            if (!isInventoryKeyPhysicallyDown(mc) || !SwapKeyState.inventoryKeyHeld) {
                performSwap(mc);
                SwapKeyState.closeRequested = true;
                state = SwapState.IDLE;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Unified swap
    // ═══════════════════════════════════════════════════════════

    private static boolean performSwap(Minecraft mc) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return false;
        Slot hs = getHoveredSlot(screen);
        if (hs == null || (!hs.hasItem() && !config.emptySlotSwapEnabled)) return false;

        int sel = mc.player.getInventory().selected;
        boolean creative = mc.gameMode.hasInfiniteItems();

        // Creative inventory → special handling
        if (screen instanceof CreativeModeInventoryScreen cs) {
            if (!creative) return false;
            if (creativeSwap(mc, cs, sel)) { playSwapSound(mc); return true; }
            return false;
        }

        // Player inventory → restrict to backpack + hotbar
        if (screen instanceof InventoryScreen && (!isPlayerInventorySlot(hs) || hs.index == hotbarMenuSlot(sel)))
            return false;

        // All containers → ClickType.SWAP
        if (containerSwap(screen, hs.index, sel)) {
            playSwapSound(mc);
            return true;
        }
        return false;
    }

    private static boolean containerSwap(AbstractContainerScreen<?> s, int slotIdx, int hotbar) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return false;
        Int2ObjectOpenHashMap<ItemStack> cs = new Int2ObjectOpenHashMap<>();
        mc.getConnection().send(new ServerboundContainerClickPacket(
                s.getMenu().containerId, s.getMenu().getStateId(), slotIdx, hotbar,
                ClickType.SWAP, ItemStack.EMPTY, cs));
        return true;
    }

    // ── Creative swap (local inventory manipulation, no server packet) ──
    private static boolean creativeSwap(Minecraft mc, CreativeModeInventoryScreen cs, int sel) {
        if (mc.gameMode == null) return false;
        Slot hs = getHoveredSlot(cs);
        if (hs == null || (!hs.hasItem() && !config.emptySlotSwapEnabled)) return false;

        int hotbarSize = hotbarSize(mc);
        int menuHotbarStart = 36;
        int heldIdx = menuHotbarStart + sel;

        // Path A: Creative tab item → pick to hotbar
        if (!isPlayerInventorySlot(hs)) {
            ItemStack held = mc.player.getInventory().getItem(sel).copy();
            ItemStack item = hs.getItem().copyWithCount(1);
            if (!held.isEmpty()) {
                int f = freeSlot(mc);
                if (f >= 0 && f < mc.player.getInventory().items.size()) {
                    mc.player.getInventory().items.set(f, held.copy());
                }
            }
            if (sel < mc.player.getInventory().items.size()) {
                mc.player.getInventory().items.set(sel, item);
            }
            return true;
        }

        // Path B: SlotWrapper → player inventory slot
        if (!isPlainSlot(hs)) {
            Slot unwrapped = unwrapSlot(hs);
            if (unwrapped != null && isPlayerInventorySlot(unwrapped)) {
                int t = unwrapped.index;
                if (t != heldIdx) {
                    ItemStack ti = cs.getMenu().getSlot(t).getItem().copy();
                    ItemStack hi = cs.getMenu().getSlot(heldIdx).getItem().copy();
                    safeSet(mc, sel, ti);
                    int invIdx = t >= menuHotbarStart ? t - menuHotbarStart : t;
                    safeSet(mc, invIdx, hi);
                    return true;
                }
            }
            return false;
        }

        // Path C: Hotbar internal swap
        int c2 = hs.getContainerSlot();
        if (c2 >= 0 && c2 < hotbarSize && c2 != sel) {
            ItemStack hi = mc.player.getInventory().getItem(sel).copy();
            ItemStack oi = mc.player.getInventory().getItem(c2).copy();
            safeSet(mc, sel, oi);
            safeSet(mc, c2, hi);
            return true;
        }
        return false;
    }

    private static void safeSet(Minecraft mc, int idx, ItemStack stack) {
        if (idx >= 0 && idx < mc.player.getInventory().items.size())
            mc.player.getInventory().items.set(idx, stack);
    }

    private static boolean isPlayerInventorySlot(Slot slot) {
        return slot.container == Minecraft.getInstance().player.getInventory();
    }

    private static int hotbarMenuSlot(int sel) {
        return 36 + sel;
    }

    private static int hotbarSize(Minecraft mc) {
        return mc.player.getInventory().items.size() - 27;
    }

    private static int freeSlot(Minecraft mc) {
        int size = mc.player.getInventory().items.size();
        int hbSize = hotbarSize(mc);
        int sel = mc.player.getInventory().selected;
        for (int i = 0; i < hbSize; i++)
            if (i != sel && mc.player.getInventory().items.get(i).isEmpty()) return i;
        for (int i = hbSize; i < size; i++)
            if (mc.player.getInventory().items.get(i).isEmpty()) return i;
        return -1;
    }

    // ═══════════════════════════════════════════════════════════
    // Key detection
    // ═══════════════════════════════════════════════════════════

    private static boolean isInventoryKeyPhysicallyDown(Minecraft mc) {
        InputConstants.Key key = InputConstants.getKey(mc.options.keyInventory.saveString());
        if (key.getType() != InputConstants.Type.KEYSYM) return false;
        return GLFW.glfwGetKey(mc.getWindow().getWindow(), key.getValue()) == GLFW.GLFW_PRESS;
    }

    private static boolean hasEditBoxFocus(Screen s) {
        if (s == null) return false;
        if (s.getFocused() instanceof EditBox) return true;
        for (var c : s.children()) if (c instanceof EditBox) return true;
        String n = s.getClass().getName();
        return n.contains("BookEdit") || n.contains("SignEdit");
    }

    // ═══════════════════════════════════════════════════════════
    // Mouse reposition
    // ═══════════════════════════════════════════════════════════

    private static void positionCursorIfEnabled(Minecraft mc, Screen screen) {
        if (!config.mouseReposition || !(screen instanceof AbstractContainerScreen<?> s)) return;
        positionCursorToUIBottomRight(s);
    }

    private static void positionCursorToUIBottomRight(AbstractContainerScreen<?> s) {
        Minecraft mc = Minecraft.getInstance();
        long h = mc.getWindow().getWindow();
        int imageW = (s instanceof CreativeModeInventoryScreen) ? 195 : 176;
        int imageH = (s instanceof CreativeModeInventoryScreen) ? 136 : 166;
        int guiRight = (s.width + imageW) / 2;
        int guiBottom = (s.height + imageH) / 2;
        double gs = mc.getWindow().getGuiScale();
        GLFW.glfwSetCursorPos(h,
                (int) (guiRight * gs) - 5,
                (int) (guiBottom * gs) - 5);
        suppressNextTooltip = true;
        suppressTooltipFrames = 2;
    }

    // ═══════════════════════════════════════════════════════════
    // Hovered slot via reflection (Loom remap safe)
    // ═══════════════════════════════════════════════════════════

    private static Slot getHoveredSlot(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> cs)) return null;

        if (!hoveredSlotResolved) {
            hoveredSlotResolved = true;
            resolveHoveredSlotField();
        }
        if (hoveredSlotField == null) return null;
        try {
            return (Slot) hoveredSlotField.get(cs);
        } catch (Exception e) {
            return null;
        }
    }

    private static void resolveHoveredSlotField() {
        // Try by known names first
        for (String name : new String[]{"hoveredSlot", "focusedSlot"}) {
            try {
                hoveredSlotField = AbstractContainerScreen.class.getDeclaredField(name);
                hoveredSlotField.setAccessible(true);
                LOGGER.info("[SusInstantSwap] hoveredSlot field: {}", name);
                return;
            } catch (NoSuchFieldException ignored) {}
        }
        // Fallback: find by type
        for (Field f : AbstractContainerScreen.class.getDeclaredFields()) {
            if (!Slot.class.isAssignableFrom(f.getType())) continue;
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            hoveredSlotField = f;
            LOGGER.info("[SusInstantSwap] hoveredSlot field (type match): {}", f.getName());
            return;
        }
        LOGGER.warn("[SusInstantSwap] Could not resolve hoveredSlot field");
    }

    // ═══════════════════════════════════════════════════════════
    // SlotWrapper unwrapping (remap safe)
    // ═══════════════════════════════════════════════════════════

    private static boolean isPlainSlot(Slot slot) {
        return slot.getClass() == Slot.class;
    }

    private static Slot unwrapSlot(Slot slot) {
        if (slot.getClass() == Slot.class) return null;
        try {
            for (Field field : slot.getClass().getDeclaredFields()) {
                if (Slot.class.isAssignableFrom(field.getType())
                        && !Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    Slot inner = (Slot) field.get(slot);
                    if (inner != null && inner != slot) return inner;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ═══════════════════════════════════════════════════════════
    // Sound & debug
    // ═══════════════════════════════════════════════════════════

    private static void playSwapSound(Minecraft mc) {
        if (!config.soundEnabled || mc.player == null) return;
        mc.player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f);
    }

    private static void debugLog(String msg) {
        if (config.debug) LOGGER.info("[SusInstantSwap] {}", msg);
    }

    // ═══════════════════════════════════════════════════════════
    // Tooltip suppression (for Mixin)
    // ═══════════════════════════════════════════════════════════

    public static boolean isSuppressNextTooltip() {
        return suppressNextTooltip;
    }

    public static void consumeTooltipSuppress() {
        suppressNextTooltip = false;
        suppressTooltipFrames = 0;
    }
}
