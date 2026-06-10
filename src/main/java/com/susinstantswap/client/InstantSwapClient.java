package com.susinstantswap.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.susinstantswap.SwapLog;
import com.susinstantswap.config.SwapConfig;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import java.lang.reflect.Field;

import org.lwjgl.glfw.GLFW;

/**
 * Sus-InstantSwap v2.1.0 — row-swap grooves on any container with player inventory.
 */
public class InstantSwapClient {

    private static KeyMapping SWAP_IN_GUI_KEY;
    private static SwapConfig config;

    enum SwapState { IDLE, WATCHING, LONG_PRESS }
    private static SwapState state = SwapState.IDLE;

    private static boolean configLogged = false;

    public static void init(SwapConfig cfg) {
        config = cfg;
        SwapLog.info("InstantSwapClient initializing...");
        SWAP_IN_GUI_KEY = new KeyMapping("key.susinstantswap.swap_in_gui",
                InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(),
                "key.categories.susinstantswap");
        NeoForge.EVENT_BUS.register(InstantSwapClient.class);
        SwapLog.info("InstantSwapClient initialized, GUI swap key: {}", SWAP_IN_GUI_KEY.getKey().getName());
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
        SwapLog.debug("RightClickBlock detected, screenOpenedByInteract=true");
    }

    @SubscribeEvent
    public static void onRightClickEntity(PlayerInteractEvent.EntityInteract event) {
        screenOpenedByInteract = true;
        SwapLog.debug("RightClickEntity detected, screenOpenedByInteract=true");
    }

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!config.mouseReposition.get()) return;
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> s)) return;
        if (s instanceof InventoryScreen || s instanceof CreativeModeInventoryScreen) return;
        if (!screenOpenedByInteract) return;
        screenOpenedByInteract = false;
        SwapLog.debug("Screen init (interact): repositioning cursor for {}", s.getClass().getSimpleName());
        positionCursorToUIBottomRight(s);
    }

    // ── Per-tick ──

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        if (!configLogged) {
            configLogged = true;
            // Scan registered key mappings for target keys (inventory + backpack mods)
            SwapKeyState.refreshTargetKeys(mc.options.keyInventory.getKey());
            SwapLog.info("Config loaded: modEnabled={}, holdThreshold={}ms, soundEnabled={}, guiSwapEnabled={}, emptySlotSwapEnabled={}, rowSwapEnabled={}, debug={}, mouseReposition={}",
                    config.modEnabled.get(), config.holdThresholdMs.get(), config.soundEnabled.get(),
                    config.guiSwapEnabled.get(), config.emptySlotSwapEnabled.get(),
                    config.rowSwapEnabled.get(),
                    config.debug.get(), config.mouseReposition.get());
            SwapLog.debug("Target keys count: {}", SwapKeyState.getTargetKeys().size());
        }

        // Detect runtime key rebinds and refresh target keys automatically
        SwapKeyState.checkForKeyRebind(mc.options.keyInventory.getKey());

        // Sync master switch to shared state (read by mixins)
        boolean newModEnabled = config.modEnabled.get();
        if (SwapKeyState.modEnabled != newModEnabled) {
            SwapLog.debug("modEnabled changed: {} -> {}", SwapKeyState.modEnabled, newModEnabled);
        }
        SwapKeyState.modEnabled = newModEnabled;
        if (!SwapKeyState.modEnabled) return;

        if (mc.player == null || mc.gameMode == null) {
            if (state != SwapState.IDLE) {
                SwapLog.debug("State reset: player={} gameMode={}", mc.player, mc.gameMode);
                state = SwapState.IDLE;
            }
            SwapKeyState.closePendingTicks = 0;
            return;
        }

        // Deferred close — gives server a tick to sync after swap
        if (SwapKeyState.closePendingTicks > 0) {
            SwapKeyState.closePendingTicks--;
            if (SwapKeyState.closePendingTicks == 0) {
                if (mc.screen instanceof AbstractContainerScreen) {
                    SwapLog.debug("Deferred close triggered for {}", mc.screen.getClass().getSimpleName());
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
                    SwapLog.debug("State: IDLE -> WATCHING (screen={})", mc.screen.getClass().getSimpleName());
                }
            }
            return;
        }

        // ── WATCHING: check threshold ──
        if (state == SwapState.WATCHING) {
            if (mc.screen == null) {
                SwapLog.debug("State: WATCHING -> IDLE (screen closed)");
                state = SwapState.IDLE;
                return;
            }
            if (!isInventoryKeyPhysicallyDown(mc)) {
                SwapLog.debug("State: WATCHING -> IDLE (key released before threshold)");
                state = SwapState.IDLE;
                return;
            }
            if ((System.nanoTime() - SwapKeyState.pressStartNanos)
                    >= config.holdThresholdMs.get() * 1_000_000L) {
                SwapKeyState.longPressConfirmed = true;
                state = SwapState.LONG_PRESS;
                SwapLog.debug("State: WATCHING -> LONG_PRESS (threshold {}ms reached)", config.holdThresholdMs.get());
            }
            return;
        }

        // ── LONG_PRESS → release triggers swap ──
        if (state == SwapState.LONG_PRESS) {
            if (mc.screen == null) {
                SwapLog.debug("State: LONG_PRESS -> IDLE (screen closed)");
                state = SwapState.IDLE;
                return;
            }
            if (!isInventoryKeyPhysicallyDown(mc) || !SwapKeyState.inventoryKeyHeld) {
                SwapLog.debug("Long press released, executing swap...");
                boolean swapped = performSwap(mc);
                SwapLog.debug("Swap result: swapped={}", swapped);
                if (!swapped) {
                    int closeDelay = (mc.screen instanceof AbstractContainerScreen<?> s && isVanillaInventory(s)) ? 1 : 2;
                    SwapKeyState.closePendingTicks = closeDelay; // auto-close
                    SwapLog.debug("Swap not performed, scheduling close delay={}", closeDelay);
                }
                state = SwapState.IDLE;
            }
        }
    }

    // ── Block backpack key repeats at Screen level ──
    // ScreenEvent.KeyPressed.Pre fires before any Screen.keyPressed(), including
    // backpack mod screens that override keyPressed() (e.g. SophisticatedBackpacks).
    // Cancelling this event prevents the backpack from toggling closed when the
    // user is holding the inventory key for long-press swap.

    @SubscribeEvent
    public static void onScreenKeyPressedPre(ScreenEvent.KeyPressed.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return;
        if (!SwapKeyState.inventoryKeyHeld) return;
        if (!(event.getScreen() instanceof AbstractContainerScreen)) return;

        for (InputConstants.Key target : SwapKeyState.getTargetKeys()) {
            if (target.getType() == InputConstants.Type.KEYSYM && event.getKeyCode() == target.getValue()) {
                SwapLog.debug("onScreenKeyPressedPre: cancelling repeat key for held inventory key (screen={}, key={})",
                        event.getScreen().getClass().getSimpleName(), event.getKeyCode());
                event.setCanceled(true);
                return;
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

        // EditBox protection: consume vanilla click so E doesn't close screen
        if (keyDown && isInventoryKey && mc.screen != null && hasEditBoxFocus(mc.screen)) {
            SwapLog.debug("EditBox focused, consuming inventory key to prevent screen close");
            while (mc.options.keyInventory.consumeClick()) {}
            if (mc.screen instanceof AbstractContainerScreen) {
                if (mc.player.containerMenu.getSlot(0).hasItem()) return;
            } else return;
        }

        // GUI swap
        if (keyDown && config.guiSwapEnabled.get()) {
            if ((isGuiSwapKey || (isInventoryKey && SWAP_IN_GUI_KEY.isUnbound()))
                    && mc.screen instanceof AbstractContainerScreen) {
                SwapLog.debug("GUI swap triggered by key");
                boolean result = performSwap(mc);
                SwapLog.debug("GUI swap result: {}", result);
            }
        }
    }

    // ── GUI swap entry (from ScreenKeyMixin) ──

    public static boolean tryPerformGuiSwap(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return false;
        if (!config.guiSwapEnabled.get() || !SWAP_IN_GUI_KEY.isUnbound()) return false;
        SwapLog.debug("tryPerformGuiSwap from ScreenKeyMixin");
        return performSwap(mc);
    }

    // ── Unified swap (GUI + long press) ──

    private static boolean performSwap(Minecraft mc) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            SwapLog.debug("performSwap: not an AbstractContainerScreen, abort");
            return false;
        }

        // ── Guard: block all swaps when the selected hotbar item is the
        //     container opener (open backpack, bundle, etc.).  Detected by
        //     finding a locked non-player-inventory slot with the same item.
        if (isContainerOpener(mc.player.getInventory()
                .getItem(mc.player.getInventory().selected),
                mc.player.containerMenu)) {
            SwapLog.debug("performSwap BLOCKED by container opener guard");
            return false;
        }

        // ── Row swap: hovering over a groove on any container with player inventory ──
        if (RowArrowWidget.hoveredRow >= 0 && config.rowSwapEnabled.get()) {
            SwapLog.debug("performSwap TRIGGERED: screen=" + screen.getClass().getSimpleName()
                    + " hoveredRow=" + RowArrowWidget.hoveredRow
                    + " creative=" + mc.player.isCreative());
            if (performRowSwap(mc, screen)) return true;
            return false;
        }

        Slot hs = screen.getSlotUnderMouse();
        if (hs == null || (!hs.hasItem() && !config.emptySlotSwapEnabled.get())) {
            SwapLog.debug("performSwap: no valid slot under mouse (hs={}, hasItem={})",
                    hs, hs != null && hs.hasItem());
            if (hs != null && !hs.hasItem()) {
                SwapToast.warn("toast.susinstantswap.empty_slot_swap_disabled");
            }
            return false;
        }

        int sel = mc.player.getInventory().selected;

        // Never swap a hotbar slot with itself (any container type)
        if (isPlayerInventorySlot(hs) && hs.getContainerSlot() == sel) return false;

        // Both slots empty → nothing to swap (unified before creative/survival split)
        if (!hs.hasItem() && mc.player.getInventory().getItem(sel).isEmpty()) {
            SwapLog.debug("performSwap: both slots empty, nothing to swap");
            SwapToast.warn("toast.susinstantswap.both_slots_empty");
            return false;
        }

        boolean creative = mc.gameMode.hasInfiniteItems();
        SwapLog.debug("performSwap: slot={}, hotbarSel={}, creative={}, screen={}",
                hs.index, sel, creative, screen.getClass().getSimpleName());

        // ── Creative inventory → special handling (must be BEFORE csi filter) ──
        if (screen instanceof CreativeModeInventoryScreen cs) {
            if (creativeSwap(mc, cs, sel)) { playSwapSound(mc); return true; }
            return false;
        }

        // Player inventory → restrict to backpack + hotbar
        if (screen instanceof InventoryScreen && !isPlayerInventorySlot(hs)) {
            SwapLog.debug("performSwap: inventory screen restriction");
            SwapToast.error("toast.susinstantswap.not_player_inventory");
            return false;
        }

        // Slot validation: hand item must fit the target slot (e.g., Curios ring slot rejects non-ring items)
        ItemStack hand = mc.player.getInventory().getItem(sel);
        if (!hand.isEmpty() && !hs.mayPlace(hand)) {
            SwapLog.debug("performSwap: hand item {} may not be placed in target slot", hand.getDisplayName().getString());
            SwapToast.error("toast.susinstantswap.item_not_placeable");
            return false;
        }

        // Guard: can we pick up the item from the hovered slot? (vanilla checks this for SWAP)
        if (hs.hasItem() && !hs.mayPickup(mc.player)) {
            SwapLog.debug("performSwap BLOCKED: hovered slot mayPickup=false");
            return false;
        }

        // Guard: can we pick up the item from the selected hotbar slot?
        // Prevents swapping away items that are "in use" (e.g. an open backpack in mainhand)
        Slot hotbarMenuSlot = findMenuSlot(screen, mc.player.getInventory(), sel);
        if (hotbarMenuSlot != null && hotbarMenuSlot.hasItem() && !hotbarMenuSlot.mayPickup(mc.player)) {
            SwapLog.debug("performSwap BLOCKED: selected hotbar slot mayPickup=false (item in use)");
            return false;
        }

        int closeDelay = isVanillaInventory(screen) ? 1 : 2;

        // All containers → ClickType.SWAP
        if (containerSwap(screen, hs.index, sel)) {
            playSwapSound(mc);
            SwapKeyState.closePendingTicks = closeDelay;
            SwapLog.debug("performSwap: SWAP packet sent, closeDelay={}", closeDelay);
            return true;
        }
        SwapLog.warn("performSwap: containerSwap returned false unexpectedly");
        SwapToast.error("toast.susinstantswap.unknown_error");
        return false;
    }

    private static boolean containerSwap(AbstractContainerScreen<?> s, int slotIdx, int hotbar) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            SwapLog.warn("containerSwap: connection is null, cannot send packet");
            return false;
        }
        SwapLog.debug("containerSwap: containerId={}, stateId={}, slotIdx={}, hotbar={}",
                s.getMenu().containerId, s.getMenu().getStateId(), slotIdx, hotbar);
        Int2ObjectOpenHashMap<ItemStack> cs = new Int2ObjectOpenHashMap<>();
        // Guard: never swap a hotbar slot with itself
        Slot slot = s.getMenu().getSlot(slotIdx);
        if (slot != null && slot.container == mc.player.getInventory()
                && slot.getContainerSlot() == hotbar) {
            SwapLog.debug("containerSwap: self-swap guard → false");
            return false;
        }
        // Guard: target slot must allow pickup (vanilla checks this for SWAP)
        if (slot != null && slot.hasItem() && !slot.mayPickup(mc.player)) {
            SwapLog.debug("containerSwap: target slot mayPickup=false → false");
            return false;
        }
        // Guard: hotbar slot must allow pickup (prevents swapping "in use" items like open backpacks)
        Slot hSlot = findMenuSlot(s, mc.player.getInventory(), hotbar);
        if (hSlot != null && hSlot.hasItem() && !hSlot.mayPickup(mc.player)) {
            SwapLog.debug("containerSwap: hotbar slot mayPickup=false → false");
            return false;
        }
        SwapLog.debug("containerSwap SEND: containerId=" + s.getMenu().containerId
                + " stateId=" + s.getMenu().getStateId()
                + " slotIdx=" + slotIdx + " hotbar=" + hotbar);
        mc.getConnection().send(new ServerboundContainerClickPacket(
                s.getMenu().containerId, s.getMenu().getStateId(), slotIdx, hotbar,
                ClickType.SWAP, ItemStack.EMPTY, cs));
        return true;
    }

    /** Swap an entire inventory row (9 slots) with the hotbar. */
    private static boolean performRowSwap(Minecraft mc, AbstractContainerScreen<?> screen) {
        int row = RowArrowWidget.hoveredRow;

        SwapLog.debug("performRowSwap ENTER: row={} creative={}", row, mc.player.isCreative());

        boolean anySwap = false;
        for (int col = 0; col < 9; col++) {
            int slotIdx = RowArrowWidget.rowSlotIndex(row, col);
            Slot s = screen.getMenu().getSlot(slotIdx);
            if (s == null) {
                SwapLog.debug("  col={} slotIdx={} → NULL, skip", col, slotIdx);
                continue;
            }

            // Safety: must be a player-inventory slot
            if (s.container != mc.player.getInventory()) {
                SwapLog.debug("  col={} slotIdx={} → container={} not playerInv, skip",
                        col, slotIdx, s.container.getClass().getSimpleName());
                continue;
            }
            if (s.getContainerSlot() == col) {
                SwapLog.debug("  col={} slotIdx={} → self-swap, skip", col, slotIdx);
                continue;
            }

            if (!s.hasItem() && mc.player.getInventory().getItem(col).isEmpty()
                    && !config.emptySlotSwapEnabled.get()) {
                SwapLog.debug("  col={} slotIdx={} → both empty + emptySwap=off, skip", col, slotIdx);
                continue;
            }

            // Guard: row slot must allow pickup (locked output slots, etc.)
            if (s.hasItem() && !s.mayPickup(mc.player)) {
                SwapLog.debug("  col={} → row slot mayPickup=false, skip", col);
                continue;
            }

            // Guard: hotbar item must fit in the row slot
            ItemStack hotbarItem = mc.player.getInventory().getItem(col);
            if (!hotbarItem.isEmpty() && !s.mayPlace(hotbarItem)) {
                SwapLog.debug("  col={} → mayPlace rejected hotbar item, skip", col);
                continue;
            }

            // Guard: hotbar slot must allow pickup (prevents swapping "in use" items)
            Slot hSlot = findMenuSlot(screen, mc.player.getInventory(), col);
            if (hSlot != null && hSlot.hasItem() && !hSlot.mayPickup(mc.player)) {
                SwapLog.debug("  col={} → hotbar slot mayPickup=false, skip", col);
                continue;
            }

            SwapLog.debug("  col={} slotIdx={} cs={} hasItem={} hotbarHasItem={} → containerSwap",
                    col, slotIdx, s.getContainerSlot(), s.hasItem(),
                    !mc.player.getInventory().getItem(col).isEmpty());

            if (containerSwap(screen, slotIdx, col)) {
                anySwap = true;
                SwapLog.debug("  col={} → OK", col);
            } else {
                SwapLog.debug("  col={} → FAILED", col);
            }
        }

        SwapLog.debug("performRowSwap EXIT: anySwap={}", anySwap);

        if (anySwap) {
            playSwapSound(mc);
            SwapKeyState.closePendingTicks = isVanillaInventory(screen) ? 1 : 2;
        }
        return anySwap;
    }

    private static boolean creativeSwap(Minecraft mc, CreativeModeInventoryScreen cs, int sel) {
        if (mc.gameMode == null) return false;
        Slot hs = cs.getSlotUnderMouse();
        if (hs == null || (!hs.hasItem() && !config.emptySlotSwapEnabled.get())) return false;

        int hotbarSize = hotbarSize(mc);
        int menuHotbarStart = 36;
        int heldIdx = menuHotbarStart + sel;
        ItemStack handStack = mc.player.getInventory().getItem(sel);
        SwapLog.debug("creativeSwap ENTER: sel={} hand={} hs.container={} hs.index={} csi={}",
                sel, handStack.isEmpty() ? "EMPTY" : handStack.getDisplayName().getString(),
                hs.container == CreativeModeInventoryScreen.CONTAINER ? "CONTAINER" :
                hs.container == mc.player.getInventory() ? "PLAYER_INV" :
                hs instanceof CreativeModeInventoryScreen.SlotWrapper ?
                        "SlotWrapper(" + ((CreativeModeInventoryScreen.SlotWrapper) hs).target.index + ")" : "OTHER",
                hs.index, hs.getContainerSlot());

        if (hs.container == CreativeModeInventoryScreen.CONTAINER) {
            SwapLog.debug("  branch=CONTAINER");
            ItemStack held = handStack.copy();
            ItemStack item = hs.getItem().copyWithCount(1);
            SwapLog.debug("  held={} item={}",
                    held.isEmpty() ? "EMPTY" : held.getDisplayName().getString(),
                    item.getDisplayName().getString());
            if (!held.isEmpty()) {
                int f = freeSlot(mc);
                SwapLog.debug("  freeSlot={}", f);
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

        // Creative equipment: csi=5-8 (armor) or 45 (offhand) — use native SWAP
        // Only applies on the inventory/survival tab (not creative item tabs)
        int csi = hs.getContainerSlot();
        if (cs.isInventoryOpen() && (csi == 45 || (csi >= 5 && csi <= 8))) {
            SwapLog.debug("  branch=CREATIVE_EQUIP csi={} sel={}", csi, sel);
            // Slot type validation — reject items that don't fit the equipment slot
            if (!handStack.isEmpty() && !hs.mayPlace(handStack)) {
                SwapLog.debug("  mayPlace rejected -> false");
                return false;
            }
            // Armor type validation
            if (csi <= 8 && !handStack.isEmpty()) {
                EquipmentSlot expected = csi == 5 ? EquipmentSlot.HEAD :
                                        csi == 6 ? EquipmentSlot.CHEST :
                                        csi == 7 ? EquipmentSlot.LEGS : EquipmentSlot.FEET;
                EquipmentSlot actual = mc.player.getEquipmentSlotForItem(handStack);
                if (!actual.isArmor() || actual != expected) {
                    SwapLog.debug("  armor mismatch: expected={} actual={} -> false", expected, actual);
                    SwapToast.error("toast.susinstantswap.armor_slot_mismatch");
                    return false;
                }
            }
            mc.gameMode.handleInventoryMouseClick(
                cs.getMenu().containerId, csi, sel, ClickType.SWAP, mc.player);
            SwapLog.debug("  handleInventoryMouseClick(slot={} hotbar={} SWAP)", csi, sel);
            SwapKeyState.closePendingTicks = 1;
            return true;
        }

        // SlotWrapper — hotbar slots on creative item tabs (not inventory tab)
        if (hs instanceof CreativeModeInventoryScreen.SlotWrapper w) {
            int t = w.target.index;
            SwapLog.debug("  branch=SlotWrapper t={} heldMenuIdx={}", t, heldIdx);
            if (isPlayerInventorySlot(w) && t != heldIdx) {
                ItemStack ti = cs.getMenu().getSlot(t).getItem().copy();
                ItemStack hi = cs.getMenu().getSlot(heldIdx).getItem().copy();
                int invIdx = t >= menuHotbarStart ? t - menuHotbarStart : t;
                SwapLog.debug("  ti={} hi={} invIdx={}",
                        ti.getDisplayName().getString(), hi.getDisplayName().getString(), invIdx);
                safeSet(mc, sel, ti);
                mc.gameMode.handleCreativeModeItemAdd(ti, heldIdx);
                safeSet(mc, invIdx, hi);
                mc.gameMode.handleCreativeModeItemAdd(hi, t);
                SwapKeyState.closePendingTicks = 1;
                return true;
            }
            SwapLog.debug("  SKIP: sameSlot={} isPlayerInv={}", t == heldIdx, isPlayerInventorySlot(w));
            return false;
        }

        int c2 = hs.getContainerSlot();
        SwapLog.debug("  branch=REGULAR c2={}", c2);
        if (c2 >= 0 && c2 < hotbarSize && c2 != sel) {
            ItemStack hi = handStack.copy();
            ItemStack oi = mc.player.getInventory().getItem(c2).copy();
            SwapLog.debug("  hi(hand->target)={} oi(target->hotbar)={}",
                    hi.isEmpty() ? "EMPTY" : hi.getDisplayName().getString(),
                    oi.getDisplayName().getString());
            safeSet(mc, sel, oi);
            mc.gameMode.handleCreativeModeItemAdd(oi, heldIdx);
            safeSet(mc, c2, hi);
            mc.gameMode.handleCreativeModeItemAdd(hi, menuHotbarStart + c2);
            SwapKeyState.closePendingTicks = 1;
            return true;
        }
        SwapLog.debug("  NO MATCH -> false");
        return false;
    }

    private static void safeSet(Minecraft mc, int idx, ItemStack stack) {
        if (idx >= 0 && idx < mc.player.getInventory().items.size())
            mc.player.getInventory().items.set(idx, stack);
    }

    private static boolean isPlayerInventorySlot(Slot slot) {
        return slot.container == Minecraft.getInstance().player.getInventory();
    }

    /**
     * Finds the menu slot for a specific inventory container slot.
     * Returns null if the slot is not present in this menu.
     */
    private static Slot findMenuSlot(AbstractContainerScreen<?> screen, Inventory inv, int containerSlot) {
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container == inv && slot.getContainerSlot() == containerSlot) {
                return slot;
            }
        }
        return null;
    }

    private static boolean isVanillaInventory(AbstractContainerScreen<?> screen) {
        return screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen;
    }

    private static int hotbarSize(Minecraft mc) {
        return mc.player.getInventory().items.size() - 27; // 9 in vanilla
    }

    /**
     * Returns true if the given stack is the item that opened the current
     * container (e.g. an open backpack).  Detection: any non-player-inventory
     * slot with the same Item AND {@code mayPickup()==false} (locked).
     */
    private static boolean isContainerOpener(ItemStack hotbarStack, AbstractContainerMenu menu) {
        if (hotbarStack.isEmpty()) return false;
        Minecraft mc = Minecraft.getInstance();
        var playerInv = mc.player.getInventory();
        SwapLog.debug("isContainerOpener: hotbarItem={}", hotbarStack.getItem());
        int idx = 0;
        for (Slot slot : menu.slots) {
            if (slot.container == playerInv) { idx++; continue; }
            if (!slot.hasItem()) { idx++; continue; }
            boolean locked = !slot.mayPickup(mc.player);
            boolean sameItem = slot.getItem().getItem() == hotbarStack.getItem();
            SwapLog.debug("  slot[{}] container={} item={} locked={} sameItem={}",
                    idx, slot.container.getClass().getSimpleName(), slot.getItem().getItem(), locked, sameItem);
            if (locked && sameItem) return true;
            idx++;
        }
        return false;
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
        for (InputConstants.Key key : SwapKeyState.getTargetKeys()) {
            if (key.getType() == InputConstants.Type.KEYSYM
                    && GLFW.glfwGetKey(mc.getWindow().getWindow(), key.getValue()) == GLFW.GLFW_PRESS) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInventoryKeyEvent(Minecraft mc, InputEvent.Key event) {
        for (InputConstants.Key target : SwapKeyState.getTargetKeys()) {
            if (target.getType() == InputConstants.Type.KEYSYM && event.getKey() == target.getValue()) {
                return true;
            }
        }
        return false;
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
        if (!config.mouseReposition.get() || !(screen instanceof AbstractContainerScreen<?> s)) return;
        positionCursorToUIBottomRight(s);
    }

    private static void positionCursorToUIBottomRight(AbstractContainerScreen<?> s) {
        Minecraft mc = Minecraft.getInstance();
        long h = mc.getWindow().getWindow();
        double gs = mc.getWindow().getGuiScale();
        int targetX = (int) ((s.getGuiLeft() + s.getXSize()) * gs) - 5;
        int targetY = (int) ((s.getGuiTop() + s.getYSize()) * gs) - 5;

        // Root fix: set MouseHandler's internal xpos/ypos directly so the first
        // render frame already reads the correct cursor position.
        // NeoForge uses official mappings, so "xpos"/"ypos" work as-is.
        MouseHandler mh = mc.mouseHandler;
        try {
            Field f = mh.getClass().getDeclaredField("xpos");
            f.setAccessible(true);
            f.setDouble(mh, targetX);
            f = mh.getClass().getDeclaredField("ypos");
            f.setAccessible(true);
            f.setDouble(mh, targetY);
        } catch (Exception ignored) {}

        // Also move the real OS cursor asynchronously.
        GLFW.glfwSetCursorPos(h, targetX, targetY);
    }

    private static void playSwapSound(Minecraft mc) {
        if (!config.soundEnabled.get() || mc.player == null) return;
        SwapLog.debug("Playing swap sound");
        mc.player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f);
    }
}