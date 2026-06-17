package com.susinstantswap.client;

import com.susinstantswap.SwapLog;
import com.susinstantswap.config.SwapConfigAdapter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Platform-independent core swap logic.
 * <p>
 * All methods use only Minecraft API ({@code net.minecraft.*}) plus
 * {@link SwapConfigAdapter} for configuration.  No NeoForge/Forge/Fabric
 * imports — this class can be synced verbatim across all platforms.
 */
public final class SwapEngine {

    // ── SWAP verification state ──
    private static final int MAX_VERIFY = 9;
    static int swapVerifyTicks = 0;
    static int verifyCount = 0;
    static int verifyContainerId = -1;
    static final int[] verifySlotIdx = new int[MAX_VERIFY];
    static final int[] verifyHotbarIdx = new int[MAX_VERIFY];
    static final ItemStack[] verifyPreSlot = new ItemStack[MAX_VERIFY];
    static final ItemStack[] verifyPreHotbar = new ItemStack[MAX_VERIFY];
    static final boolean[] verifyActive = new boolean[MAX_VERIFY];

    private SwapEngine() {}

    // ── Public API ──

    /** Call every client tick to progress swap verification. */
    public static void tickVerification(Minecraft mc) {
        if (swapVerifyTicks > 0) {
            swapVerifyTicks--;
            if (swapVerifyTicks == 0 && mc.screen instanceof AbstractContainerScreen<?> vScreen) {
                verifySwapResult(mc, vScreen);
            }
        }
    }

    /** Main swap entry point. Returns true if a swap was performed (screen closes via closePendingTicks). */
    public static boolean performSwap(Minecraft mc, SwapConfigAdapter config) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return false;

        // Row swap
        if (RowArrowWidget.hoveredRow >= 0 && config.rowSwapEnabled()) {
            if (performRowSwap(mc, screen, config)) return true;
            return false;
        }

        Slot hs = screen.getSlotUnderMouse();
        if (hs == null || (!hs.hasItem() && !config.emptySlotSwapEnabled())) {
            if (hs != null && !hs.hasItem()) SwapToast.warn("toast.susinstantswap.empty_slot_swap_disabled");
            return false;
        }

        int sel = mc.player.getInventory().selected;

        if (isPlayerInventorySlot(hs) && hs.getContainerSlot() == sel) return false;
        if (!hs.hasItem() && mc.player.getInventory().getItem(sel).isEmpty()) return false;

        if (screen instanceof CreativeModeInventoryScreen cs) {
            if (creativeSwap(mc, cs, sel, config)) { playSwapSound(mc, config); return true; }
            return false;
        }

        // Capture pre-swap state
        ItemStack preSlot = hs.getItem().copy();
        ItemStack preHotbar = mc.player.getInventory().getItem(sel).copy();

        int closeDelay = isVanillaInventory(screen) ? 1 : 2;

        // Backpack mod: PICKUP direct for held hotbar slot
        if (!isVanillaInventory(screen) && isBackpackScreen(screen)) {
            Slot hotbarSlot = findHotbarMenuSlot(screen, sel);
            if (hotbarSlot != null && (hs.hasItem() || hotbarSlot.hasItem())) {
                boolean canPickup = !hs.hasItem() || hs.mayPickup(mc.player);
                boolean canPickupHotbar = !hotbarSlot.hasItem() || hotbarSlot.mayPickup(mc.player);
                ItemStack hand = mc.player.getInventory().getItem(sel);
                boolean canPlace = hand.isEmpty() || hs.mayPlace(hand);
                if (canPickup && canPickupHotbar && canPlace) {
                    performPickupExchange(mc, screen, hs, sel);
                    playSwapSound(mc, config);
                    SwapKeyState.closePendingTicks = closeDelay;
                    SwapLog.debug("performSwap: PICKUP direct for held slot in backpack screen, hoverIdx={} sel={}",
                            hs.index, sel);
                    return true;
                }
            }
        }

        if (swapSingleSlot(mc, screen, hs, sel, false, config)) {
            verifySlotIdx[0] = hs.index;
            verifyHotbarIdx[0] = sel;
            verifyPreSlot[0] = preSlot;
            verifyPreHotbar[0] = preHotbar;
            verifyActive[0] = true;
            verifyCount = 1;
            verifyContainerId = screen.getMenu().containerId;
            swapVerifyTicks = 2;
            playSwapSound(mc, config);
            SwapKeyState.closePendingTicks = closeDelay;
            return true;
        }
        return false;
    }

    // ── Single-slot swap ──

    static boolean swapSingleSlot(Minecraft mc, AbstractContainerScreen<?> screen,
                                  Slot hs, int hotbarIdx, boolean suppressToast,
                                  SwapConfigAdapter config) {
        ItemStack hotbarStack = mc.player.getInventory().getItem(hotbarIdx);
        if (isPlayerInventorySlot(hs) && hs.getContainerSlot() == hotbarIdx) return false;
        if (!hs.hasItem() && hotbarStack.isEmpty()) return false;

        Slot hotbarMenuSlot = findHotbarMenuSlot(screen, hotbarIdx);
        if (hotbarMenuSlot != null && hotbarMenuSlot.hasItem()
                && !hotbarMenuSlot.mayPickup(mc.player)) {
            if (!suppressToast) SwapToast.warn("toast.susinstantswap.item_in_use");
            return false;
        }

        ItemStack hand = mc.player.getInventory().getItem(hotbarIdx);
        if (!hand.isEmpty() && !hs.mayPlace(hand) && isPlayerInventorySlot(hs)) {
            if (!suppressToast) SwapToast.warn("toast.susinstantswap.item_not_placeable");
            return false;
        }

        if (hs.hasItem() && !hs.mayPickup(mc.player) && isPlayerInventorySlot(hs)) {
            if (!suppressToast) SwapToast.warn("toast.susinstantswap.slot_locked");
            return false;
        }

        if (!isPlayerInventorySlot(hs) && !isBackpackScreen(screen)) {
            if (!hand.isEmpty() && !hs.mayPlace(hand)) {
                if (!suppressToast) SwapToast.warn("toast.susinstantswap.item_not_placeable");
                return false;
            }
            if (hs.hasItem() && !hs.mayPickup(mc.player)) {
                if (!suppressToast) SwapToast.warn("toast.susinstantswap.item_not_placeable");
                return false;
            }
        }

        // Hotbar priority: all validation passed — if enabled and hotbar has an
        // empty slot, stash the held item there first, then pick the target.
        // Skipped during row swap (suppressToast=true) to avoid per-column
        // interference.
        if (!suppressToast && config.hotbarPriorityEnabled()
                && hs.hasItem() && !hotbarStack.isEmpty()) {
            int emptyIdx = findEmptyHotbarSlot(mc, hotbarIdx);
            if (emptyIdx >= 0) {
                Slot emptyMenuSlot = findHotbarMenuSlot(screen, emptyIdx);
                if (emptyMenuSlot != null) {
                    performHotbarStashThenPickup(mc, screen, hs, hotbarIdx, emptyMenuSlot.index);
                    SwapLog.debug("swapSingleSlot: hotbar priority — stashed held to slot {} → pick target idx={}",
                            emptyIdx, hs.index);
                    return true;
                }
            }
        }

        return containerSwap(screen, hs.index, hotbarIdx);
    }

    // ── PICKUP exchange ──

    static void performPickupExchange(Minecraft mc, AbstractContainerScreen<?> screen,
                                       Slot containerSlot, int hotbarIdx) {
        int cid = screen.getMenu().containerId;
        Slot hotbarMenuSlot = findHotbarMenuSlot(screen, hotbarIdx);
        if (hotbarMenuSlot == null) return;

        boolean containerHasItem = containerSlot.hasItem();
        boolean hotbarHasItem = hotbarMenuSlot.hasItem();

        if (containerHasItem && !hotbarHasItem) {
            mc.gameMode.handleInventoryMouseClick(cid, containerSlot.index, 0, ClickType.PICKUP, mc.player);
            mc.gameMode.handleInventoryMouseClick(cid, hotbarMenuSlot.index, 0, ClickType.PICKUP, mc.player);
        } else if (!containerHasItem && hotbarHasItem) {
            mc.gameMode.handleInventoryMouseClick(cid, hotbarMenuSlot.index, 0, ClickType.PICKUP, mc.player);
            mc.gameMode.handleInventoryMouseClick(cid, containerSlot.index, 0, ClickType.PICKUP, mc.player);
        } else if (containerHasItem && hotbarHasItem) {
            mc.gameMode.handleInventoryMouseClick(cid, containerSlot.index, 0, ClickType.PICKUP, mc.player);
            mc.gameMode.handleInventoryMouseClick(cid, hotbarMenuSlot.index, 0, ClickType.PICKUP, mc.player);
            mc.gameMode.handleInventoryMouseClick(cid, containerSlot.index, 0, ClickType.PICKUP, mc.player);
        }
    }

    // ── SWAP verification ──

    private static void verifySwapResult(Minecraft mc, AbstractContainerScreen<?> screen) {
        if (screen.getMenu().containerId != verifyContainerId) return;

        for (int i = 0; i < verifyCount; i++) {
            if (!verifyActive[i]) continue;
            if (verifySlotIdx[i] < 0 || verifySlotIdx[i] >= screen.getMenu().slots.size()) continue;

            Slot hs = screen.getMenu().getSlot(verifySlotIdx[i]);
            ItemStack postSlot = hs.getItem();
            ItemStack postHotbar = mc.player.getInventory().getItem(verifyHotbarIdx[i]);

            boolean slotChanged = !ItemStack.matches(postSlot, verifyPreSlot[i]);
            boolean hotbarChanged = !ItemStack.matches(postHotbar, verifyPreHotbar[i]);
            boolean swapSucceeded = slotChanged || hotbarChanged;

            if (swapSucceeded) {
                SwapLog.debug("SWAP verify: OK col={} slotChanged={} hotbarChanged={}",
                        i, slotChanged, hotbarChanged);
                continue;
            }

            SwapLog.debug("SWAP verify: FAILED col={} hoverIdx={} sel={}", i, verifySlotIdx[i], verifyHotbarIdx[i]);

            if (mc.gameMode == null) return;

            Slot hotbarSlot = findHotbarMenuSlot(screen, verifyHotbarIdx[i]);
            if (hotbarSlot != null) {
                performPickupExchange(mc, screen, hs, verifyHotbarIdx[i]);
                SwapLog.debug("  PICKUP fallback for col={}", i);
            }

            SwapKeyState.closePendingTicks = Math.max(SwapKeyState.closePendingTicks, 2);
        }
    }

    // ── Container swap ──

    private static boolean containerSwap(AbstractContainerScreen<?> s, int slotIdx, int hotbar) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null) return false;

        Slot slot = s.getMenu().getSlot(slotIdx);
        if (slot != null && slot.container == mc.player.getInventory()
                && slot.getContainerSlot() == hotbar) return false;

        if (slot != null && slot.hasItem() && !slot.mayPickup(mc.player)
                && slot.container == mc.player.getInventory()) return false;

        Slot hSlot = findHotbarMenuSlot(s, hotbar);
        if (hSlot != null && hSlot.hasItem() && !hSlot.mayPickup(mc.player)) return false;

        mc.gameMode.handleInventoryMouseClick(
                s.getMenu().containerId, slotIdx, hotbar, ClickType.SWAP, mc.player);
        return true;
    }

    // ── Row swap ──

    static boolean performRowSwap(Minecraft mc, AbstractContainerScreen<?> screen,
                                  SwapConfigAdapter config) {
        int row = RowArrowWidget.hoveredRow;
        int sel = mc.player.getInventory().selected;

        SwapLog.debug("performRowSwap ENTER: row={} creative={} sel={}", row, mc.player.isCreative(), sel);

        if (screen instanceof CreativeModeInventoryScreen cs) {
            return creativeRowSwap(mc, cs, row, sel, config);
        }

        boolean anySwap = false;
        verifyCount = 0;
        verifyContainerId = screen.getMenu().containerId;

        for (int col = 0; col < 9; col++) {
            int slotIdx = RowArrowWidget.rowSlotIndex(row, col);
            Slot s = screen.getMenu().getSlot(slotIdx);
            if (s == null) continue;

            if (s.container != mc.player.getInventory() && !isBackpackScreen(screen)) continue;

            ItemStack preSlot = s.getItem().copy();
            ItemStack preHotbar = mc.player.getInventory().getItem(col).copy();

            if (col == sel) {
                Slot hotbarSlot = findHotbarMenuSlot(screen, col);
                if (hotbarSlot != null && (s.hasItem() || hotbarSlot.hasItem())
                        && s.mayPickup(mc.player) && (!hotbarSlot.hasItem() || hotbarSlot.mayPickup(mc.player))) {
                    ItemStack hand = mc.player.getInventory().getItem(col);
                    if (hand.isEmpty() || s.mayPlace(hand)) {
                        performPickupExchange(mc, screen, s, col);
                        anySwap = true;
                    }
                }
                continue;
            }

            if (swapSingleSlot(mc, screen, s, col, true, config)) {
                anySwap = true;
                if (verifyCount < MAX_VERIFY) {
                    int vi = verifyCount;
                    verifySlotIdx[vi] = s.index;
                    verifyHotbarIdx[vi] = col;
                    verifyPreSlot[vi] = preSlot;
                    verifyPreHotbar[vi] = preHotbar;
                    verifyActive[vi] = true;
                    verifyCount++;
                }
            }
        }

        SwapLog.debug("performRowSwap EXIT: anySwap={} verifyCount={}", anySwap, verifyCount);

        if (anySwap) {
            if (verifyCount > 0) swapVerifyTicks = 2;
            playSwapSound(mc, config);
            SwapKeyState.closePendingTicks = isVanillaInventory(screen) ? 1 : 2;
        }
        return anySwap;
    }

    // ── Creative-mode swap ──

    private static boolean creativeSwap(Minecraft mc, CreativeModeInventoryScreen cs,
                                        int sel, SwapConfigAdapter config) {
        if (mc.gameMode == null) return false;
        Slot hs = cs.getSlotUnderMouse();
        if (hs == null || (!hs.hasItem() && !config.emptySlotSwapEnabled())) return false;

        int hotbarSize = hotbarSize(mc);
        int menuHotbarStart = 36;
        int heldIdx = menuHotbarStart + sel;
        ItemStack handStack = mc.player.getInventory().getItem(sel);

        if (hs.container == CreativeModeInventoryScreen.CONTAINER) {
            ItemStack held = handStack.copy();
            ItemStack item = hs.getItem().copyWithCount(1);
            if (!held.isEmpty()) {
                int f = freeSlot(mc);
                if (f >= 0 && f < mc.player.getInventory().items.size()) {
                    mc.player.getInventory().items.set(f, held.copy());
                    mc.gameMode.handleCreativeModeItemAdd(held.copy(), menuHotbarStart + f);
                }
            }
            if (sel < mc.player.getInventory().items.size()) {
                mc.player.getInventory().items.set(sel, item);
                mc.gameMode.handleCreativeModeItemAdd(item, heldIdx);
            }
            SwapKeyState.closePendingTicks = 1;
            return true;
        }

        int csi = hs.getContainerSlot();
        if (cs.isInventoryOpen() && (csi == 45 || (csi >= 5 && csi <= 8))) {
            if (!handStack.isEmpty() && !hs.mayPlace(handStack)) {
                SwapToast.warn("toast.susinstantswap.item_not_placeable");
                return false;
            }
            if (csi <= 8 && !handStack.isEmpty()) {
                EquipmentSlot expected = csi == 5 ? EquipmentSlot.HEAD :
                                        csi == 6 ? EquipmentSlot.CHEST :
                                        csi == 7 ? EquipmentSlot.LEGS : EquipmentSlot.FEET;
                EquipmentSlot actual = mc.player.getEquipmentSlotForItem(handStack);
                if (!actual.isArmor() || actual != expected) {
                    SwapToast.warn("toast.susinstantswap.item_not_placeable");
                    return false;
                }
            }
            mc.gameMode.handleInventoryMouseClick(
                cs.getMenu().containerId, csi, sel, ClickType.SWAP, mc.player);
            SwapKeyState.closePendingTicks = 1;
            return true;
        }

        if (hs instanceof CreativeModeInventoryScreen.SlotWrapper w) {
            int t = w.target.index;
            if (isPlayerInventorySlot(w) && t != heldIdx) {
                ItemStack ti = cs.getMenu().getSlot(t).getItem().copy();
                ItemStack hi = cs.getMenu().getSlot(heldIdx).getItem().copy();
                int invIdx = t >= menuHotbarStart ? t - menuHotbarStart : t;
                safeSet(mc, sel, ti);
                mc.gameMode.handleCreativeModeItemAdd(ti, heldIdx);
                safeSet(mc, invIdx, hi);
                mc.gameMode.handleCreativeModeItemAdd(hi, t);
                SwapKeyState.closePendingTicks = 1;
                return true;
            }
            return false;
        }

        int c2 = hs.getContainerSlot();
        if (c2 >= 0 && c2 < hotbarSize && c2 != sel) {
            ItemStack hi = handStack.copy();
            ItemStack oi = mc.player.getInventory().getItem(c2).copy();
            safeSet(mc, sel, oi);
            mc.gameMode.handleCreativeModeItemAdd(oi, heldIdx);
            safeSet(mc, c2, hi);
            mc.gameMode.handleCreativeModeItemAdd(hi, menuHotbarStart + c2);
            SwapKeyState.closePendingTicks = 1;
            return true;
        }
        return false;
    }

    private static boolean creativeRowSwap(Minecraft mc, CreativeModeInventoryScreen cs,
                                           int row, int sel, SwapConfigAdapter config) {
        int menuHotbarStart = 36;
        boolean anySwap = false;

        for (int col = 0; col < 9; col++) {
            int slotIdx = RowArrowWidget.rowSlotIndex(row, col);
            if (slotIdx < 0 || slotIdx >= cs.getMenu().slots.size()) continue;
            Slot s = cs.getMenu().getSlot(slotIdx);
            if (s == null) continue;
            if (s.container != mc.player.getInventory()) continue;

            int csi = s.getContainerSlot();
            if (csi == col) continue;

            ItemStack invItem = s.getItem().copy();
            ItemStack hotbarItem = mc.player.getInventory().getItem(col).copy();

            if (invItem.isEmpty() && hotbarItem.isEmpty()) continue;

            safeSet(mc, col, invItem);
            mc.gameMode.handleCreativeModeItemAdd(invItem, menuHotbarStart + col);

            int invIdx = csi >= menuHotbarStart ? csi - menuHotbarStart : csi;
            safeSet(mc, invIdx, hotbarItem);
            mc.gameMode.handleCreativeModeItemAdd(hotbarItem, csi);

            anySwap = true;
        }

        if (anySwap) {
            playSwapSound(mc, config);
            SwapKeyState.closePendingTicks = 1;
        }
        return anySwap;
    }

    // ── Helpers ──

    static boolean isPlayerInventorySlot(Slot slot) {
        return slot.container == Minecraft.getInstance().player.getInventory();
    }

    static Slot findMenuSlot(AbstractContainerScreen<?> screen, Inventory inv, int containerSlot) {
        for (Slot slot : screen.getMenu().slots)
            if (slot.container == inv && slot.getContainerSlot() == containerSlot) return slot;
        return null;
    }

    static Slot findHotbarMenuSlot(AbstractContainerScreen<?> screen, int hotbarIdx) {
        Inventory inv = Minecraft.getInstance().player.getInventory();
        Slot s = findMenuSlot(screen, inv, hotbarIdx);
        if (s != null) return s;
        for (Slot slot : screen.getMenu().slots)
            if (slot.getContainerSlot() == hotbarIdx) return slot;
        return null;
    }

    static boolean isVanillaInventory(AbstractContainerScreen<?> screen) {
        return screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen;
    }

    static boolean isBackpackScreen(AbstractContainerScreen<?> screen) {
        return BackpackScreenMatcher.isBackpackScreen(screen);
    }

    static int hotbarSize(Minecraft mc) {
        return mc.player.getInventory().items.size() - 27;
    }

    static int freeSlot(Minecraft mc) {
        int size = mc.player.getInventory().items.size();
        int hbSize = hotbarSize(mc);
        int sel = mc.player.getInventory().selected;
        for (int i = 0; i < hbSize; i++)
            if (i != sel && mc.player.getInventory().items.get(i).isEmpty()) return i;
        for (int i = hbSize; i < size; i++)
            if (mc.player.getInventory().items.get(i).isEmpty()) return i;
        return -1;
    }

    private static void safeSet(Minecraft mc, int idx, ItemStack stack) {
        if (idx >= 0 && idx < mc.player.getInventory().items.size())
            mc.player.getInventory().items.set(idx, stack);
    }

    static void playSwapSound(Minecraft mc, SwapConfigAdapter config) {
        if (!config.soundEnabled() || mc.player == null) return;
        mc.player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f);
    }

    // ── Hotbar priority helpers ──

    /** Find the first empty hotbar slot (0-8) excluding {@code exclude}. Returns -1 if none. */
    private static int findEmptyHotbarSlot(Minecraft mc, int exclude) {
        for (int i = 0; i < 9; i++) {
            if (i != exclude && mc.player.getInventory().getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    /**
     * 4-step PICKUP: stash held item from {@code sel} → {@code emptyMenuIdx},
     * then pick target item → {@code sel}.  All steps in the same tick,
     * synchronous via {@code handleInventoryMouseClick}.
     */
    private static void performHotbarStashThenPickup(Minecraft mc, AbstractContainerScreen<?> screen,
                                                      Slot targetSlot, int sel, int emptyMenuIdx) {
        int cid = screen.getMenu().containerId;
        Slot hotbarMenuSlot = findHotbarMenuSlot(screen, sel);
        if (hotbarMenuSlot == null) return;

        // Step 1-2: Stash held item from sel → empty hotbar slot
        mc.gameMode.handleInventoryMouseClick(cid, hotbarMenuSlot.index, 0, ClickType.PICKUP, mc.player);
        mc.gameMode.handleInventoryMouseClick(cid, emptyMenuIdx, 0, ClickType.PICKUP, mc.player);

        // Step 3-4: Pick target item → sel
        mc.gameMode.handleInventoryMouseClick(cid, targetSlot.index, 0, ClickType.PICKUP, mc.player);
        mc.gameMode.handleInventoryMouseClick(cid, hotbarMenuSlot.index, 0, ClickType.PICKUP, mc.player);
    }
}
