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
import net.minecraft.world.InteractionResult;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.EquipmentSlot;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.lang.reflect.Field;

import com.susinstantswap.mixin.AbstractContainerScreenAccessor;
import com.susinstantswap.mixin.KeyMappingAccessor;

/**
 * Sus-InstantSwap v2.0 — Fabric edition.
 * Core swap logic ported from NeoForge 1.21.1 baseline.
 *
 * <p>Uses Fabric API callbacks instead of NeoForge event bus,
 * Accessor mixins instead of Access Transformers, and reflection
 * for private CreativeModeInventoryScreen internals.</p>
 */
public class InstantSwapClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static KeyMapping SWAP_IN_GUI_KEY;
    private static SwapConfig config;

    enum SwapState { IDLE, WATCHING, LONG_PRESS }
    private static SwapState state = SwapState.IDLE;

    private static boolean configLogged = false;

    // ── Tooltip suppression: tick-count window. 3 ticks covers the reposition + 2 renders. ──
    private static int suppressTooltipTicks;

    /** Called from TooltipSuppressMixin. Returns true during suppression window. */
    public static boolean isTooltipSuppressed() {
        return suppressTooltipTicks > 0;
    }

    // ── Right-click container tracking ──
    // Only reposition cursor when container was opened via right-click
    // (not via keybind — Curios, cosmetic armor, etc.).
    private static boolean screenOpenedByInteract;
    // Track E-key opens (vs tab switches in creative) for AFTER_INIT reposition
    private static boolean screenOpenedByKey;

    // ── Reflection cache for CreativeModeInventoryScreen internals ──
    private static Object CREATIVE_CONTAINER;
    private static boolean creativeContainerInit;

    // ── Init ──

    /** Returns the shared config instance, for use by config screens. */
    public static SwapConfig getConfig() { return config; }

    public static void init(SwapConfig cfg) {
        config = cfg;
        SWAP_IN_GUI_KEY = new KeyMapping("key.susinstantswap.swap_in_gui",
                InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(),
                "key.categories.susinstantswap");
        KeyBindingHelper.registerKeyBinding(SWAP_IN_GUI_KEY);

        ClientTickEvents.END_CLIENT_TICK.register(InstantSwapClient::onClientTick);
        ScreenEvents.AFTER_INIT.register(InstantSwapClient::onScreenInitPost);

        // Track right-click interactions to distinguish keybind-opened
        // containers (Curios, cosmetic armor, etc.) from player-opened ones.
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            screenOpenedByInteract = true;
            return InteractionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            screenOpenedByInteract = true;
            return InteractionResult.PASS;
        });
    }

    // ── Screen init: reposition cursor ──

    private static void onScreenInitPost(Minecraft mc, Screen screen, int scaledWidth, int scaledHeight) {
        if (!config.mouseReposition) return;
        if (!(screen instanceof AbstractContainerScreen<?> s)) return;

        // Creative inventory: reposition here (AFTER_INIT, layout guaranteed ready)
        if (s instanceof CreativeModeInventoryScreen) {
            if (screenOpenedByKey) {
                screenOpenedByKey = false;
                positionCursorToUIBottomRight(s);
            }
            return;
        }

        // Other containers: only reposition if opened by right-click
        if (s instanceof InventoryScreen) return;
        if (!screenOpenedByInteract) return;
        screenOpenedByInteract = false;
        positionCursorToUIBottomRight(s);
    }

    // ── Per-tick ──

    private static void onClientTick(Minecraft mc) {
        if (suppressTooltipTicks > 0) suppressTooltipTicks--;

        if (!configLogged) {
            configLogged = true;
            LOGGER.info("[SusInstantSwap] Config: mod={} threshold={}ms sound={} guiSwap={} emptySwap={} debug={} mouse={}",
                    config.modEnabled, config.holdThresholdMs, config.soundEnabled,
                    config.guiSwapEnabled, config.emptySlotSwapEnabled,
                    config.debug, config.mouseReposition);
        }

        // Sync master switch to shared state (read by mixins)
        SwapKeyState.modEnabled = config.modEnabled;
        if (!SwapKeyState.modEnabled) return;

        if (mc.player == null || mc.gameMode == null) {
            state = SwapState.IDLE;
            SwapKeyState.closePendingTicks = 0;
            return;
        }

        // Deferred close — gives server a tick to sync after swap
        if (SwapKeyState.closePendingTicks > 0) {
            SwapKeyState.closePendingTicks--;
            if (SwapKeyState.closePendingTicks == 0) {
                if (mc.screen instanceof AbstractContainerScreen) {
                    debugLog("deferred close");
                    mc.player.closeContainer();
                }
            }
            state = SwapState.IDLE;
        }

        // ── IDLE: wait for screen to open after E press ──
        if (state == SwapState.IDLE) {
            if (SwapKeyState.inventoryKeyHeld && !SwapKeyState.longPressConfirmed) {
                if (mc.screen instanceof AbstractContainerScreen) {
                    SwapKeyState.pressStartNanos = System.nanoTime();
                    state = SwapState.WATCHING;
                    debugLog("WATCHING");
                    // Creative: repositioned in onScreenInitPost (AFTER_INIT, same tick)
                    // Survival: reposition here (END_CLIENT_TICK, same tick)
                    if (!(mc.screen instanceof CreativeModeInventoryScreen)) {
                        positionCursorIfEnabled(mc, mc.screen);
                    }
                }
            }
            return;
        }

        // ── WATCHING: check threshold ──
        if (state == SwapState.WATCHING) {
            if (mc.screen == null) { state = SwapState.IDLE; return; }
            if (!isInventoryKeyPhysicallyDown(mc)) {
                // Key released before threshold — abort.
                state = SwapState.IDLE;
                return;
            }
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
                boolean swapped = performSwap(mc);
                if (!swapped) {
                    int closeDelay = (mc.screen instanceof AbstractContainerScreen<?> s && isVanillaInventory(s)) ? 1 : 2;
                    SwapKeyState.closePendingTicks = closeDelay;
                }
                state = SwapState.IDLE;
            }
        }
    }

    // ── Keyboard input (called from KeyboardMixin) ──
    // Returns true if the event should be cancelled (blocked from reaching KeyMapping.click).

    public static boolean handleKeyInput(long window, int key, int scancode, int action, int modifiers) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return false;
        if (!SwapKeyState.modEnabled) return false;

        boolean keyDown = (action == GLFW.GLFW_PRESS || action == GLFW.GLFW_REPEAT);
        boolean isRelease = (action == GLFW.GLFW_RELEASE);
        boolean isInventoryKey = isInventoryKeyByCode(mc, key);
        boolean isGuiSwapKey = SWAP_IN_GUI_KEY.isUnbound() ? false : isGuiSwapKeyByCode(key);

        // ── E key press/release tracking for long-press state machine ──
        // Must track ALL E presses (even when no screen is open), matching
        // NF's KeyClickMixin behavior. The E press that OPENS the inventory
        // fires before mc.screen is set, so we can't gate on screen state.
        if (isInventoryKey) {
            if (keyDown && !SwapKeyState.inventoryKeyHeld) {
                // If a container screen is already open, this is a GUI swap attempt
                // (not a long-press start). Don't set inventoryKeyHeld — let
                // ScreenKeyMixin handle it via tryPerformGuiSwap without interference.
                if (mc.screen instanceof AbstractContainerScreen) {
                    return false;
                }
                SwapKeyState.inventoryKeyHeld = true;
                SwapKeyState.pressStartNanos = System.nanoTime();
                SwapKeyState.longPressConfirmed = false;
                screenOpenedByKey = true; // track for AFTER_INIT reposition
            } else if (keyDown && SwapKeyState.inventoryKeyHeld) {
                // Repeat while held: block it
                return true;
            } else if (isRelease) {
                SwapKeyState.inventoryKeyHeld = false;
                SwapKeyState.longPressConfirmed = false;
            }
        }

        // ── EditBox protection: consume click so swap keys don't close screen ──
        if (keyDown && isInventoryKey && mc.screen != null && hasEditBoxFocus(mc.screen)) {
            while (mc.options.keyInventory.consumeClick()) {}
            if (mc.screen instanceof AbstractContainerScreen) {
                if (mc.player.containerMenu.getSlot(0).hasItem()) return false;
            } else return false;
        }

        // ── GUI swap ──
        // GUI swap with bound custom key fires immediately.
        // E-key fallback (unbound custom key) is handled in ScreenKeyMixin
        // via tryPerformGuiSwap, which fires after the screen is open.
        if (keyDown && config.guiSwapEnabled && isGuiSwapKey
                && mc.screen instanceof AbstractContainerScreen) {
            performSwap(mc);
        }

        return false;
    }

    // ── GUI swap entry (from ScreenKeyMixin) ──

    public static boolean tryPerformGuiSwap(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return false;
        if (!config.guiSwapEnabled || !SWAP_IN_GUI_KEY.isUnbound()) return false;
        return performSwap(mc);
    }

    // ── Unified swap (GUI + long press) ──

    private static boolean performSwap(Minecraft mc) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return false;
        Slot hs = ((AbstractContainerScreenAccessor) screen).getHoveredSlot();

        // Fallback: CreativeModeInventoryScreen may not track hoveredSlot reliably
        // for the item grid on the tab page. Manually find the slot under the mouse.
        if (hs == null && screen instanceof CreativeModeInventoryScreen) {
            AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
            double mx = mc.mouseHandler.xpos();
            double my = mc.mouseHandler.ypos();
            for (Slot s : screen.getMenu().slots) {
                if (s.isActive() && acc.invokeIsHovering(s, mx, my)) {
                    hs = s;
                    break;
                }
            }
        }

        if (hs == null || (!hs.hasItem() && !config.emptySlotSwapEnabled)) return false;

        int sel = mc.player.getInventory().selected;

        // Both slots empty → nothing to swap
        if (!hs.hasItem() && mc.player.getInventory().getItem(sel).isEmpty()) return false;

        // ── Creative inventory → special handling ──
        if (screen instanceof CreativeModeInventoryScreen cs) {
            if (creativeSwap(mc, cs, sel, hs)) { playSwapSound(mc); return true; }
            return false;
        }

        // Player inventory → restrict to backpack + hotbar
        if (screen instanceof InventoryScreen && (!isPlayerInventorySlot(hs) || hs.index == hotbarMenuSlot(sel)))
            return false;

        // Slot validation: hand item must fit the target slot
        ItemStack hand = mc.player.getInventory().getItem(sel);
        if (!hand.isEmpty() && !hs.mayPlace(hand)) return false;

        int closeDelay = isVanillaInventory(screen) ? 1 : 2;

        // All containers → ClickType.SWAP
        if (containerSwap(screen, hs.index, sel)) {
            playSwapSound(mc);
            SwapKeyState.closePendingTicks = closeDelay;
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

    // ── Creative mode swap (ported from NF v2.0.0) ──

    private static boolean creativeSwap(Minecraft mc, CreativeModeInventoryScreen cs, int sel, Slot hs) {
        if (mc.gameMode == null) return false;

        int hotbarSize = hotbarSize(mc);
        int menuHotbarStart = 36;
        int heldIdx = menuHotbarStart + sel;
        ItemStack handStack = mc.player.getInventory().getItem(sel);
        debugLog("creativeSwap ENTER: sel=" + sel + " hand=" + (handStack.isEmpty()?"EMPTY":handStack.getDisplayName().getString())
                + " hs.container=" + (hs.container==getCreativeContainer()?"CONTAINER":hs.container==mc.player.getInventory()?"PLAYER_INV":
                  isSlotWrapper(hs)?"SlotWrapper("+getSlotWrapperTargetIndex(hs)+")":"OTHER")
                + " hs.index=" + hs.index + " csi=" + hs.getContainerSlot());

        Object creativeContainer = getCreativeContainer();
        // CONTAINER branch: creative item grid slots.
        // Primary check: compare with the static CONTAINER field (via reflection).
        // Fallback: on the tab page, any non-player-inventory, non-SlotWrapper slot
        // is a creative grid slot (no reflection needed).
        boolean isContainerSlot = (creativeContainer != null && hs.container == creativeContainer)
                || (!cs.isInventoryOpen() && !isPlayerInventorySlot(hs) && !isSlotWrapper(hs));
        if (isContainerSlot) {
            debugLog("  branch=CONTAINER");
            ItemStack held = handStack.copy();
            ItemStack item = hs.getItem().copyWithCount(1);
            debugLog("  held=" + (held.isEmpty()?"EMPTY":held.getDisplayName().getString()) + " item=" + item.getDisplayName().getString());
            if (!held.isEmpty()) {
                int f = freeSlot(mc);
                debugLog("  freeSlot=" + f);
                if (f >= 0 && f < mc.player.getInventory().items.size()) {
                    mc.player.getInventory().items.set(f, held.copy());
                    mc.gameMode.handleCreativeModeItemAdd(held.copy(), menuHotbarStart + f);
                    debugLog("  items.set(" + f + ",held) + addItem(" + (menuHotbarStart+f) + ")");
                }
            }
            if (sel < mc.player.getInventory().items.size()) {
                mc.player.getInventory().items.set(sel, item);
                mc.gameMode.handleCreativeModeItemAdd(item, heldIdx);
                debugLog("  items.set(" + sel + ",item) + addItem(" + heldIdx + ")");
            }
            SwapKeyState.closePendingTicks = 1;
            return true;
        }

        // Creative equipment: csi=5-8 (armor) or 45 (offhand) — use native SWAP
        // Only applies on the inventory/survival tab (not creative item tabs)
        int csi = hs.getContainerSlot();
        if (cs.isInventoryOpen() && (csi == 45 || (csi >= 5 && csi <= 8))) {
            debugLog("  branch=CREATIVE_EQUIP csi=" + csi + " sel=" + sel);
            // Slot type validation — reject items that don't fit the equipment slot
            if (!handStack.isEmpty() && !hs.mayPlace(handStack)) {
                debugLog("  mayPlace rejected -> false");
                return false;
            }
            // Armor type validation
            if (csi <= 8 && !handStack.isEmpty()) {
                EquipmentSlot expected = csi == 5 ? EquipmentSlot.HEAD :
                                        csi == 6 ? EquipmentSlot.CHEST :
                                        csi == 7 ? EquipmentSlot.LEGS : EquipmentSlot.FEET;
                EquipmentSlot actual = mc.player.getEquipmentSlotForItem(handStack);
                if (!actual.isArmor() || actual != expected) {
                    debugLog("  armor mismatch: expected=" + expected + " actual=" + actual + " -> false");
                    return false;
                }
            }
            mc.gameMode.handleInventoryMouseClick(
                cs.getMenu().containerId, csi, sel, ClickType.SWAP, mc.player);
            debugLog("  handleInventoryMouseClick(slot=" + csi + " hotbar=" + sel + " SWAP)");
            SwapKeyState.closePendingTicks = 1;
            return true;
        }

        // Player inventory slots on inventory page: backpack (9-35) + hotbar (36-44)
        // Uses the same SWAP mechanism as equipment slots — native inventory click.
        if (cs.isInventoryOpen() && isPlayerInventorySlot(hs) && csi != heldIdx) {
            debugLog("  branch=PLAYER_INV csi=" + csi + " sel=" + sel);
            if (!handStack.isEmpty() && !hs.mayPlace(handStack)) {
                debugLog("  mayPlace rejected -> false");
                return false;
            }
            mc.gameMode.handleInventoryMouseClick(
                cs.getMenu().containerId, csi, sel, ClickType.SWAP, mc.player);
            debugLog("  handleInventoryMouseClick(slot=" + csi + " hotbar=" + sel + " SWAP)");
            SwapKeyState.closePendingTicks = 1;
            return true;
        }

        // SlotWrapper — hotbar slots on creative item tabs (not inventory tab)
        if (isSlotWrapper(hs)) {
            int t = getSlotWrapperTargetIndex(hs);
            debugLog("  branch=SlotWrapper t=" + t + " heldMenuIdx=" + heldIdx);
            if (t >= 0 && isPlayerInventorySlot(hs) && t != heldIdx) {
                ItemStack ti = cs.getMenu().getSlot(t).getItem().copy();
                ItemStack hi = cs.getMenu().getSlot(heldIdx).getItem().copy();
                int invIdx = t >= menuHotbarStart ? t - menuHotbarStart : t;
                debugLog("  ti=" + ti.getDisplayName().getString() + " hi=" + hi.getDisplayName().getString() + " invIdx=" + invIdx);
                safeSet(mc, sel, ti);
                mc.gameMode.handleCreativeModeItemAdd(ti, heldIdx);
                debugLog("  safeSet(" + sel + ",ti) + addItem(" + heldIdx + ")");
                safeSet(mc, invIdx, hi);
                mc.gameMode.handleCreativeModeItemAdd(hi, t);
                debugLog("  safeSet(" + invIdx + ",hi) + addItem(" + t + ")");
                SwapKeyState.closePendingTicks = 1;
                return true;
            }
            debugLog("  SKIP: t<0=" + (t<0) + " isPlayerInv=" + isPlayerInventorySlot(hs) + " sameSlot=" + (t==heldIdx));
            return false;
        }

        int c2 = hs.getContainerSlot();
        debugLog("  branch=REGULAR c2=" + c2);
        if (c2 >= 0 && c2 < hotbarSize && c2 != sel) {
            ItemStack hi = handStack.copy();
            ItemStack oi = mc.player.getInventory().getItem(c2).copy();
            debugLog("  hi(hand->target)=" + (hi.isEmpty()?"EMPTY":hi.getDisplayName().getString()) + " oi(target->hotbar)=" + oi.getDisplayName().getString());
            safeSet(mc, sel, oi);
            mc.gameMode.handleCreativeModeItemAdd(oi, heldIdx);
            debugLog("  safeSet(" + sel + ",oi) + addItem(" + heldIdx + ")");
            safeSet(mc, c2, hi);
            mc.gameMode.handleCreativeModeItemAdd(hi, menuHotbarStart + c2);
            debugLog("  safeSet(" + c2 + ",hi) + addItem(" + (menuHotbarStart+c2) + ")");
            SwapKeyState.closePendingTicks = 1;
            return true;
        }
        debugLog("  NO MATCH -> false");
        return false;
    }

    // ── Reflection helpers for CreativeModeInventoryScreen internals ──
    // SlotWrapper detection — uses type-based field scanning instead of
    // getDeclaredField("target") because Loom may not remap the string.
    private static final java.util.Map<Class<?>, Field> targetFieldCache = new java.util.HashMap<>();

    private static Object getCreativeContainer() {
        if (!creativeContainerInit) {
            creativeContainerInit = true;
            try {
                Field f = CreativeModeInventoryScreen.class.getDeclaredField("CONTAINER");
                f.setAccessible(true);
                CREATIVE_CONTAINER = f.get(null);
            } catch (NoSuchFieldException e1) {
                LOGGER.warn("[SusInstantSwap] CONTAINER not found by name, scanning by type...");
                try {
                    for (Field f : CreativeModeInventoryScreen.class.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                                && net.minecraft.world.Container.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            CREATIVE_CONTAINER = f.get(null);
                            LOGGER.info("[SusInstantSwap] Found CONTAINER via type: {}", f.getName());
                            break;
                        }
                    }
                } catch (Exception e2) {
                    LOGGER.error("[SusInstantSwap] CONTAINER type scan failed", e2);
                }
            } catch (Exception e) {
                LOGGER.error("[SusInstantSwap] CONTAINER access failed", e);
            }
        }
        return CREATIVE_CONTAINER;
    }

    /** Find the Slot-typed 'target' field on a SlotWrapper by type (not name). */
    private static Field findTargetField(Class<?> clz) {
        return targetFieldCache.computeIfAbsent(clz, c -> {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == Slot.class) {
                    f.setAccessible(true);
                    return f;
                }
            }
            return null;
        });
    }

    private static boolean isSlotWrapper(Slot slot) {
        return findTargetField(slot.getClass()) != null;
    }

    private static int getSlotWrapperTargetIndex(Slot slot) {
        try {
            Field f = findTargetField(slot.getClass());
            if (f == null) return -1;
            Slot target = (Slot) f.get(slot);
            return target != null ? target.index : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    // ── Utility ──

    private static void safeSet(Minecraft mc, int idx, ItemStack stack) {
        if (idx >= 0 && idx < mc.player.getInventory().items.size())
            mc.player.getInventory().items.set(idx, stack);
    }

    private static boolean isPlayerInventorySlot(Slot slot) {
        return slot.container == Minecraft.getInstance().player.getInventory();
    }

    private static boolean isVanillaInventory(AbstractContainerScreen<?> screen) {
        return screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen;
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

    // ── Key detection ──

    private static boolean isInventoryKeyPhysicallyDown(Minecraft mc) {
        InputConstants.Key key = ((KeyMappingAccessor) (Object) mc.options.keyInventory).getKey();
        if (key.getType() != InputConstants.Type.KEYSYM) return false;
        return GLFW.glfwGetKey(mc.getWindow().getWindow(), key.getValue()) == GLFW.GLFW_PRESS;
    }

    private static boolean isInventoryKeyByCode(Minecraft mc, int keyCode) {
        InputConstants.Key ik = ((KeyMappingAccessor) (Object) mc.options.keyInventory).getKey();
        return ik.getType() == InputConstants.Type.KEYSYM && ik.getValue() == keyCode;
    }

    private static boolean isGuiSwapKeyByCode(int keyCode) {
        if (SWAP_IN_GUI_KEY.isUnbound()) return false;
        InputConstants.Key bk = ((KeyMappingAccessor) (Object) SWAP_IN_GUI_KEY).getKey();
        return bk.getType() == InputConstants.Type.KEYSYM && bk.getValue() == keyCode;
    }

    private static boolean hasEditBoxFocus(Screen s) {
        if (s == null) return false;
        if (s.getFocused() instanceof EditBox) return true;
        for (var c : s.children()) if (c instanceof EditBox) return true;
        String n = s.getClass().getName();
        return n.contains("BookEdit") || n.contains("SignEdit");
    }

    // ── Mouse reposition ──

    private static void positionCursorIfEnabled(Minecraft mc, Screen screen) {
        if (!config.mouseReposition || !(screen instanceof AbstractContainerScreen<?> s)) return;
        if (hasEditBoxFocus(screen)) return;
        positionCursorToUIBottomRight(s);
    }

    private static void positionCursorToUIBottomRight(AbstractContainerScreen<?> s) {
        Minecraft mc = Minecraft.getInstance();
        long h = mc.getWindow().getWindow();
        double gs = mc.getWindow().getGuiScale();
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) s;
        GLFW.glfwSetCursorPos(h,
                (int) ((acc.getLeftPos() + acc.getImageWidth()) * gs) - 5,
                (int) ((acc.getTopPos() + acc.getImageHeight()) * gs) - 5);
        suppressTooltipTicks = 3;
    }

    private static void playSwapSound(Minecraft mc) {
        if (!config.soundEnabled || mc.player == null) return;
        mc.player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f);
    }

    private static void debugLog(String msg) {
        if (config.debug) LOGGER.info("[SusInstantSwap] {}", msg);
    }
}
