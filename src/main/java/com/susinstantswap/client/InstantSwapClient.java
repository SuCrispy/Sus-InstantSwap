package com.susinstantswap.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.susinstantswap.config.SwapConfig;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
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
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import java.lang.reflect.Field;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

/**
 * Sus-InstantSwap v2.0 — coexists with the vanilla inventory key.
 * Forge 1.21.1 — uses AT for CreativeModeInventoryScreen internals.
 */
public class InstantSwapClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static KeyMapping SWAP_IN_GUI_KEY;

    enum SwapState { IDLE, WATCHING, LONG_PRESS }
    private static SwapState state = SwapState.IDLE;

    private static boolean configLogged = false;
    private static boolean suppressNextTooltip;
    private static int suppressTooltipFrames;

    // Belt-and-suspenders: sync config once on first tick
    private static boolean firstTickSyncDone = false;

    public static void init() {
        SWAP_IN_GUI_KEY = new KeyMapping("key.susinstantswap.swap_in_gui",
                InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(),
                "key.categories.susinstantswap");
        MinecraftForge.EVENT_BUS.register(InstantSwapClient.class);
    }

    public static void registerKey(RegisterKeyMappingsEvent event) {
        event.register(SWAP_IN_GUI_KEY);
    }

    // ── Container opened via right-click → reposition cursor ──
    // Tracked by PlayerInteractEvent to avoid repositioning for keybind-opened
    // screens (Curios, cosmetic armor, etc.)

    private static boolean screenOpenedByInteract = false;

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
        if (!SwapConfig.mouseRepositionRuntime) return;
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> s)) return;
        if (s instanceof InventoryScreen || s instanceof CreativeModeInventoryScreen) return;
        if (!screenOpenedByInteract) return;
        screenOpenedByInteract = false;
        positionCursorToUIBottomRight(s);
    }

    // ── Per-tick ──

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();

        if (suppressTooltipFrames > 0 && --suppressTooltipFrames == 0)
            suppressNextTooltip = false;

        // Belt-and-suspenders: sync config on first tick
        if (!firstTickSyncDone) {
            firstTickSyncDone = true;
            com.susinstantswap.SusInstantSwapMod.CONFIG.syncToRuntime();
        }

        if (!configLogged) {
            configLogged = true;
            LOGGER.info("[SusInstantSwap] Config: mod={} threshold={}ms sound={} guiSwap={} emptySwap={} debug={} mouse={}",
                    SwapConfig.modEnabledRuntime, SwapConfig.holdThresholdMsRuntime, SwapConfig.soundEnabledRuntime,
                    SwapConfig.guiSwapEnabledRuntime, SwapConfig.emptySlotSwapEnabledRuntime,
                    SwapConfig.debugRuntime, SwapConfig.mouseRepositionRuntime);
        }

        // Sync master switch to shared state (read by mixins)
        SwapKeyState.modEnabled = SwapConfig.modEnabledRuntime;
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
                    >= SwapConfig.holdThresholdMsRuntime * 1_000_000L) {
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
                    SwapKeyState.closePendingTicks = closeDelay; // auto-close
                }
                state = SwapState.IDLE;
            }
        }
    }

    // ── InputEvent: GUI swap + text protection ──

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return;

        int action = event.getAction();
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_RELEASE) return;

        boolean keyDown = (action == GLFW.GLFW_PRESS);
        boolean isInventoryKey = isInventoryKeyEvent(mc, event);
        boolean isGuiSwapKey = SWAP_IN_GUI_KEY.isUnbound() ? false : isGuiSwapKeyEvent(event);

        // EditBox protection: consume click so swap keys don't close screen
        if (keyDown && isInventoryKey && mc.screen != null && hasEditBoxFocus(mc.screen)) {
            while (mc.options.keyInventory.consumeClick()) {}
            if (mc.screen instanceof AbstractContainerScreen) {
                if (mc.player.containerMenu.getSlot(0).hasItem()) return;
            } else return;
        }

        // GUI swap
        if (keyDown && SwapConfig.guiSwapEnabledRuntime) {
            if ((isGuiSwapKey || (isInventoryKey && SWAP_IN_GUI_KEY.isUnbound()))
                    && mc.screen instanceof AbstractContainerScreen) {
                performSwap(mc);
            }
        }
    }

    // ── GUI swap entry (from ScreenKeyMixin) ──

    public static boolean tryPerformGuiSwap(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return false;
        if (!SwapConfig.guiSwapEnabledRuntime || !SWAP_IN_GUI_KEY.isUnbound()) return false;
        return performSwap(mc);
    }

    @SubscribeEvent
    public static void onRenderTooltip(RenderTooltipEvent.Pre event) {
        if (suppressNextTooltip) {
            event.setCanceled(true);
            suppressNextTooltip = false;
            suppressTooltipFrames = 0;
        }
    }

    // ── Unified swap (GUI + long press) ──

    private static boolean performSwap(Minecraft mc) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return false;
        Slot hs = screen.getSlotUnderMouse();
        if (hs == null || (!hs.hasItem() && !SwapConfig.emptySlotSwapEnabledRuntime)) return false;

        int sel = mc.player.getInventory().selected;

        // Both slots empty → nothing to swap (unified before creative/survival split)
        if (!hs.hasItem() && mc.player.getInventory().getItem(sel).isEmpty()) return false;

        // ── Creative inventory → special handling ──
        if (screen instanceof CreativeModeInventoryScreen cs) {
            if (creativeSwap(mc, cs, sel)) { playSwapSound(mc); return true; }
            return false;
        }

        // Player inventory → restrict to backpack + hotbar
        if (screen instanceof InventoryScreen && (!isPlayerInventorySlot(hs) || hs.index == hotbarMenuSlot(sel)))
            return false;

        // Slot validation: hand item must fit the target slot (e.g., Curios ring slot rejects non-ring items)
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

    // ── Creative swap (FG6 AT unreliable, use reflection for SlotWrapper/CONTAINER) ──
    // ⚠️ BRANCH ORDER MATTERS: On Forge, SlotWrapper.getContainerSlot() is NOT overridden
    // and returns screen position (0-8) which overlaps with CREATIVE_EQUIP csi 5-8.
    // Therefore SlotWrapper MUST come BEFORE CREATIVE_EQUIP in the branch order.

    private static boolean creativeSwap(Minecraft mc, CreativeModeInventoryScreen cs, int sel) {
        if (mc.gameMode == null) return false;
        Slot hs = cs.getSlotUnderMouse();
        if (hs == null || (!hs.hasItem() && !SwapConfig.emptySlotSwapEnabledRuntime)) return false;

        int hotbarSize = hotbarSize(mc);
        int menuHotbarStart = 36;
        int heldIdx = menuHotbarStart + sel;
        ItemStack handStack = mc.player.getInventory().getItem(sel);

        Slot swTarget = getSlotWrapperTarget(hs);
        debugLog("creativeSwap ENTER: sel=" + sel + " hand=" + (handStack.isEmpty()?"EMPTY":handStack.getDisplayName().getString())
                + " hs.container=" + (hs.container==getCreativeContainer()?"CONTAINER":hs.container==mc.player.getInventory()?"PLAYER_INV":
                  swTarget!=null?"SlotWrapper("+swTarget.index+")":"OTHER")
                + " hs.index=" + hs.index + " csi=" + hs.getContainerSlot());

        // ── CONTAINER (creative tab item grid) ──
        if (hs.container == getCreativeContainer()) {
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

        // ── SlotWrapper (hotbar slots on creative item tabs) ──
        // ⚠️ MUST come before CREATIVE_EQUIP because Forge's SlotWrapper.getContainerSlot()
        // is NOT overridden and returns screen position (0-8), which overlaps with csi 5-8.
        if (swTarget != null) {
            int t = swTarget.index;
            debugLog("  branch=SlotWrapper t=" + t + " heldMenuIdx=" + heldIdx);
            if (isPlayerInventorySlot(hs) && t != heldIdx) {
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
            debugLog("  SKIP: sameSlot=" + (t==heldIdx) + " isPlayerInv=" + isPlayerInventorySlot(hs));
            return false;
        }

        // ── CREATIVE_EQUIP: csi=5-8 (armor) or 45 (offhand) ──
        // ⚠️ Only applies on the inventory/survival tab (not creative item tabs)
        int csi = hs.getContainerSlot();
        if (cs.isInventoryOpen() && (csi == 45 || (csi >= 5 && csi <= 8))) {
            debugLog("  branch=CREATIVE_EQUIP csi=" + csi + " sel=" + sel);
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

        // ── REGULAR (fallback hotbar slots) ──
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
        return 36 + sel; // hotbar at menu slots 36-44 in player inventory screen
    }

    private static int hotbarSize(Minecraft mc) {
        return mc.player.getInventory().items.size() - 27; // 9 in vanilla
    }

    private static int freeSlot(Minecraft mc) {
        int size = mc.player.getInventory().items.size();
        int hbSize = hotbarSize(mc);
        int sel = mc.player.getInventory().selected;
        // Hotbar first (creative — closest to cursor), then backpack
        for (int i = 0; i < hbSize; i++)
            if (i != sel && mc.player.getInventory().items.get(i).isEmpty()) return i;
        for (int i = hbSize; i < size; i++)
            if (mc.player.getInventory().items.get(i).isEmpty()) return i;
        return -1;
    }

    // ── Key detection ──

    /** Public entry for mixins: checks whether an InputConstants.Key matches the vanilla inventory key. */
    public static boolean isSwapKey(InputConstants.Key key) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return false;
        InputConstants.Key invKey = mc.options.keyInventory.getKey();
        return invKey.getType() == key.getType() && invKey.getValue() == key.getValue();
    }

    private static boolean isInventoryKeyPhysicallyDown(Minecraft mc) {
        InputConstants.Key key = mc.options.keyInventory.getKey();
        if (key.getType() != InputConstants.Type.KEYSYM) return false;
        return GLFW.glfwGetKey(mc.getWindow().getWindow(), key.getValue()) == GLFW.GLFW_PRESS;
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

    // ── Mouse reposition ──

    private static void positionCursorIfEnabled(Minecraft mc, Screen screen) {
        if (!SwapConfig.mouseRepositionRuntime || !(screen instanceof AbstractContainerScreen<?> s)) return;
        positionCursorToUIBottomRight(s);
    }

    private static void positionCursorToUIBottomRight(AbstractContainerScreen<?> s) {
        Minecraft mc = Minecraft.getInstance();
        long h = mc.getWindow().getWindow();
        double gs = mc.getWindow().getGuiScale();
        GLFW.glfwSetCursorPos(h,
                (int) ((s.getGuiLeft() + s.getXSize()) * gs) - 5,
                (int) ((s.getGuiTop() + s.getYSize()) * gs) - 5);
        suppressNextTooltip = true;
        suppressTooltipFrames = 2;
    }

    private static void playSwapSound(Minecraft mc) {
        if (!SwapConfig.soundEnabledRuntime || mc.player == null) return;
        mc.player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f);
    }

    private static void debugLog(String msg) {
        if (SwapConfig.debugRuntime) LOGGER.info("[SusInstantSwap] {}", msg);
    }

    // ── Reflection cache (FG6 AT unreliable, fall back to reflection) ──

    private static Object cachedContainer;
    private static boolean containerCached;

    private static Object getCreativeContainer() {
        if (!containerCached) {
            containerCached = true;
            try {
                Field f = CreativeModeInventoryScreen.class.getDeclaredField("CONTAINER");
                f.setAccessible(true);
                cachedContainer = f.get(null);
            } catch (Exception e) {
                LOGGER.warn("[SusInstantSwap] CONTAINER field access failed: {}", e.toString());
            }
        }
        return cachedContainer;
    }

    private static Slot getSlotWrapperTarget(Slot slot) {
        try {
            Field f = slot.getClass().getDeclaredField("target");
            f.setAccessible(true);
            return (Slot) f.get(slot);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isSlotWrapper(Slot slot) {
        return slot.getClass().getSimpleName().equals("SlotWrapper");
    }
}
