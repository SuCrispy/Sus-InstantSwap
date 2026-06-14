package com.susinstantswap.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.susinstantswap.SwapLog;
import com.susinstantswap.config.SwapConfig;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import java.lang.reflect.Field;

import org.lwjgl.glfw.GLFW;

public class InstantSwapClient {

    private static KeyMapping SWAP_IN_GUI_KEY;
    private static SwapConfig config;

    enum SwapState { IDLE, WATCHING, LONG_PRESS }
    private static SwapState state = SwapState.IDLE;

    private static boolean configLogged = false;
    /** Guard: only reposition cursor once per IDLE→WATCHING transition. */
    private static boolean cursorRepositionedThisPress = false;

    public static void init(SwapConfig cfg) {
        config = cfg;
        SwapLog.init(config);
        SwapToast.init(config);
        SWAP_IN_GUI_KEY = new KeyMapping("key.susinstantswap.swap_in_gui",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT,
                "key.categories.susinstantswap");
        NeoForge.EVENT_BUS.register(InstantSwapClient.class);
    }

    public static void registerKey(RegisterKeyMappingsEvent event) {
        event.register(SWAP_IN_GUI_KEY);
    }

    private static boolean screenOpenedByInteract = false;
    private static Screen previousScreen = null;

    // SWAP verification — array-based for 9-column row swap
    private static final int MAX_VERIFY = 9;
    private static int swapVerifyTicks = 0;
    private static int verifyCount = 0;
    private static int verifyContainerId = -1;
    private static final int[] verifySlotIdx = new int[MAX_VERIFY];
    private static final int[] verifyHotbarIdx = new int[MAX_VERIFY];
    private static final ItemStack[] verifyPreSlot = new ItemStack[MAX_VERIFY];
    private static final ItemStack[] verifyPreHotbar = new ItemStack[MAX_VERIFY];
    private static final boolean[] verifyActive = new boolean[MAX_VERIFY];

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
        if (!config.mouseReposition.get()) return;
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> s)) return;
        if (s instanceof InventoryScreen || s instanceof CreativeModeInventoryScreen) return;

        boolean byInteraction = screenOpenedByInteract;
        screenOpenedByInteract = false;

        boolean isTopLevel = !(previousScreen instanceof AbstractContainerScreen);

        boolean openedDuringLongPress = SwapKeyState.inventoryKeyHeld
                && SwapKeyState.lastTriggerKeyIsVanilla
                && isBackpackScreen(s);

        if (byInteraction || (isTopLevel && !openedDuringLongPress)) {
            positionCursorToUIBottomRight(s);
        }
    }

    // Row swap rendering for backpack mods (ScreenEvent fallback)
    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!SwapKeyState.modEnabled) return;
        SwapConfig cfg = config;
        if (cfg == null || !cfg.rowSwapEnabled.get()) return;
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

    // Block backpack key repeats at Screen level
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

    // Per-tick
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        previousScreen = mc.screen;

        // SWAP verification
        if (swapVerifyTicks > 0) {
            swapVerifyTicks--;
            if (swapVerifyTicks == 0 && mc.screen instanceof AbstractContainerScreen<?> vScreen) {
                verifySwapResult(mc, vScreen);
            }
        }

        if (!configLogged) {
            configLogged = true;
            SwapKeyState.refreshTargetKeys(mc.options.keyInventory.getKey());
            SwapLog.info("Config: mod={} threshold={}ms sound={} guiSwap={} emptySwap={} rowSwap={} debug={} mouse={} toast={}",
                    config.modEnabled.get(), config.holdThresholdMs.get(), config.soundEnabled.get(),
                    config.guiSwapEnabled.get(), config.emptySlotSwapEnabled.get(),
                    config.rowSwapEnabled.get(),
                    config.debug.get(), config.mouseReposition.get(), config.toastEnabled.get());
        }

        SwapKeyState.checkForKeyRebind(mc.options.keyInventory.getKey());
        SwapKeyState.modEnabled = config.modEnabled.get();
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

        // IDLE: wait for target key press with a container screen open.
        // Only engage when the key press OPENED the screen
        // (screenWasOpenAtPressStart=false). When screen was already open,
        // vanilla handles E-to-close without state machine intervention.
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

        // WATCHING: check threshold
        if (state == SwapState.WATCHING) {
            if (mc.screen == null) { state = SwapState.IDLE; cursorRepositionedThisPress = false; return; }
            if (!isAnyTargetKeyPhysicallyDown(mc)) {
                // Short press released before threshold — keep screen open
                // (the key press opened this screen, short press = just open UI)
                state = SwapState.IDLE;
                cursorRepositionedThisPress = false;
                return;
            }
            if ((System.nanoTime() - SwapKeyState.pressStartNanos)
                    >= config.holdThresholdMs.get() * 1_000_000L) {
                state = SwapState.LONG_PRESS;
            }
            return;
        }

        // LONG_PRESS → release triggers swap
        if (state == SwapState.LONG_PRESS) {
            if (mc.screen == null) { state = SwapState.IDLE; cursorRepositionedThisPress = false; return; }
            if (!isAnyTargetKeyPhysicallyDown(mc) || !SwapKeyState.inventoryKeyHeld) {
                boolean swapped = performSwap(mc);
                if (!swapped) {
                    int closeDelay = (mc.screen instanceof AbstractContainerScreen<?> s && isVanillaInventory(s)) ? 1 : 2;
                    SwapKeyState.closePendingTicks = closeDelay;
                }
                state = SwapState.IDLE;
                cursorRepositionedThisPress = false;
            }
        }
    }

    // InputEvent: GUI swap + text protection
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

        if (keyDown && config.guiSwapEnabled.get()) {
            if (isGuiSwapKey && mc.screen instanceof AbstractContainerScreen) {
                performSwap(mc);
            }
        }
    }

    /** Try GUI swap when the GUI swap key is pressed (called from ScreenKeyMixin). */
    public static boolean tryPerformGuiSwap() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return false;
        if (!config.guiSwapEnabled.get() || SWAP_IN_GUI_KEY.isUnbound()) return false;
        return performSwap(mc);
    }

    // ── Unified swap ──

    private static boolean performSwap(Minecraft mc) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return false;

        // Row swap
        if (RowArrowWidget.hoveredRow >= 0 && config.rowSwapEnabled.get()) {
            if (performRowSwap(mc, screen)) return true;
            return false;
        }

        Slot hs = screen.getSlotUnderMouse();
        if (hs == null || (!hs.hasItem() && !config.emptySlotSwapEnabled.get())) {
            if (hs != null && !hs.hasItem()) SwapToast.warn("toast.susinstantswap.empty_slot_swap_disabled");
            return false;
        }

        int sel = mc.player.getInventory().selected;

        if (isPlayerInventorySlot(hs) && hs.getContainerSlot() == sel) return false;
        if (!hs.hasItem() && mc.player.getInventory().getItem(sel).isEmpty()) return false;

        if (screen instanceof CreativeModeInventoryScreen cs) {
            if (creativeSwap(mc, cs, sel)) { playSwapSound(mc); return true; }
            return false;
        }

        // Capture pre-swap state BEFORE swapSingleSlot (which calls
        // handleInventoryMouseClick → menu.clicked() updates local state)
        ItemStack preSlot = hs.getItem().copy();
        ItemStack preHotbar = mc.player.getInventory().getItem(sel).copy();

        int closeDelay = isVanillaInventory(screen) ? 1 : 2;

        // ── For the selected/held hotbar slot in backpack mod screens,
        //    use PICKUP directly ──
        //    Reason: many backpack mods (Traveller's Backpack, etc.) block
        //    SWAP when the target hotbar slot == inventory.selected.
        //    Using PICKUP avoids the 2-tick verify delay.
        if (!isVanillaInventory(screen) && isBackpackScreen(screen)) {
            Slot hotbarSlot = findHotbarMenuSlot(screen, sel);
            if (hotbarSlot != null && (hs.hasItem() || hotbarSlot.hasItem())) {
                boolean canPickup = !hs.hasItem() || hs.mayPickup(mc.player);
                boolean canPickupHotbar = !hotbarSlot.hasItem() || hotbarSlot.mayPickup(mc.player);
                ItemStack hand = mc.player.getInventory().getItem(sel);
                boolean canPlace = hand.isEmpty() || hs.mayPlace(hand);
                if (canPickup && canPickupHotbar && canPlace) {
                    performPickupExchange(mc, screen, hs, sel);
                    playSwapSound(mc);
                    SwapKeyState.closePendingTicks = closeDelay;
                    SwapLog.debug("performSwap: PICKUP direct for held slot in backpack screen, hoverIdx={} sel={}",
                            hs.index, sel);
                    return true;
                }
            }
            // Fallback to SWAP if PICKUP guards fail
        }

        if (swapSingleSlot(mc, screen, hs, sel, false)) {
            // Record for verification
            verifySlotIdx[0] = hs.index;
            verifyHotbarIdx[0] = sel;
            verifyPreSlot[0] = preSlot;
            verifyPreHotbar[0] = preHotbar;
            verifyActive[0] = true;
            verifyCount = 1;
            verifyContainerId = screen.getMenu().containerId;
            swapVerifyTicks = 2;
            playSwapSound(mc);
            SwapKeyState.closePendingTicks = closeDelay;
            return true;
        }
        return false;
    }

    /** Core swap logic shared by single-slot and row swap. */
    private static boolean swapSingleSlot(Minecraft mc, AbstractContainerScreen<?> screen,
                                          Slot hs, int hotbarIdx, boolean suppressToast) {
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

        // For non-player-inventory slots outside backpack screens (e.g., Curios accessories),
        // validate mayPlace/mayPickup to reject incompatible slots early
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

        return containerSwap(screen, hs.index, hotbarIdx);
    }

    /**
     * Synchronous PICKUP exchange — 2 or 3 steps all in the same tick.
     * - TAKE (target has item, hotbar empty):   PICKUP target → PICKUP hotbar (2 steps)
     * - PUT  (target empty, hotbar has item):    PICKUP hotbar → PICKUP target (2 steps)
     * - EXCHANGE (both have items):              PICKUP target → PICKUP hotbar → PICKUP target (3 steps)
     */
    private static void performPickupExchange(Minecraft mc, AbstractContainerScreen<?> screen,
                                               Slot containerSlot, int hotbarIdx) {
        int cid = screen.getMenu().containerId;
        Slot hotbarMenuSlot = findHotbarMenuSlot(screen, hotbarIdx);
        if (hotbarMenuSlot == null) return;

        boolean containerHasItem = containerSlot.hasItem();
        boolean hotbarHasItem = hotbarMenuSlot.hasItem();

        if (containerHasItem && !hotbarHasItem) {
            // TAKE: 2 steps
            mc.gameMode.handleInventoryMouseClick(cid, containerSlot.index, 0, ClickType.PICKUP, mc.player);
            mc.gameMode.handleInventoryMouseClick(cid, hotbarMenuSlot.index, 0, ClickType.PICKUP, mc.player);
        } else if (!containerHasItem && hotbarHasItem) {
            // PUT: 2 steps
            mc.gameMode.handleInventoryMouseClick(cid, hotbarMenuSlot.index, 0, ClickType.PICKUP, mc.player);
            mc.gameMode.handleInventoryMouseClick(cid, containerSlot.index, 0, ClickType.PICKUP, mc.player);
        } else if (containerHasItem && hotbarHasItem) {
            // EXCHANGE: 3 steps
            mc.gameMode.handleInventoryMouseClick(cid, containerSlot.index, 0, ClickType.PICKUP, mc.player);
            mc.gameMode.handleInventoryMouseClick(cid, hotbarMenuSlot.index, 0, ClickType.PICKUP, mc.player);
            mc.gameMode.handleInventoryMouseClick(cid, containerSlot.index, 0, ClickType.PICKUP, mc.player);
        }
    }

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

            // SWAP failed — use PICKUP fallback
            SwapLog.debug("SWAP verify: FAILED col={} hoverIdx={} sel={}", i, verifySlotIdx[i], verifyHotbarIdx[i]);

            if (mc.gameMode == null) return;

            // Perform PICKUP exchange for this column
            Slot hotbarSlot = findHotbarMenuSlot(screen, verifyHotbarIdx[i]);

            if (hotbarSlot != null) {
                performPickupExchange(mc, screen, hs, verifyHotbarIdx[i]);
                SwapLog.debug("  PICKUP fallback for col={}", i);
            }

            SwapKeyState.closePendingTicks = Math.max(SwapKeyState.closePendingTicks, 2);
        }
    }

    private static boolean containerSwap(AbstractContainerScreen<?> s, int slotIdx, int hotbar) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null) return false;

        Slot slot = s.getMenu().getSlot(slotIdx);
        if (slot != null && slot.container == mc.player.getInventory()
                && slot.getContainerSlot() == hotbar) return false;

        // Skip mayPickup for container slots (server-side may differ)
        if (slot != null && slot.hasItem() && !slot.mayPickup(mc.player)
                && slot.container == mc.player.getInventory()) return false;

        Slot hSlot = findHotbarMenuSlot(s, hotbar);
        if (hSlot != null && hSlot.hasItem() && !hSlot.mayPickup(mc.player)) return false;

        // Use handleInventoryMouseClick instead of manual packet construction
        // → automatically calls menu.clicked() + sends correct changedSlots/carriedItem/stateId
        mc.gameMode.handleInventoryMouseClick(
                s.getMenu().containerId, slotIdx, hotbar, ClickType.SWAP, mc.player);
        return true;
    }

    /** Swap an entire inventory row (9 slots) with the hotbar. */
    private static boolean performRowSwap(Minecraft mc, AbstractContainerScreen<?> screen) {
        int row = RowArrowWidget.hoveredRow;
        int sel = mc.player.getInventory().selected;

        SwapLog.debug("performRowSwap ENTER: row={} creative={} sel={}", row, mc.player.isCreative(), sel);

        // ── Creative mode: use handleCreativeModeItemAdd (SWAP packets don't
        //    work because CreativeModeInventoryMenu is client-side only) ──
        if (screen instanceof CreativeModeInventoryScreen cs) {
            return creativeRowSwap(mc, cs, row, sel);
        }

        boolean anySwap = false;
        verifyCount = 0;
        verifyContainerId = screen.getMenu().containerId;

        for (int col = 0; col < 9; col++) {
            int slotIdx = RowArrowWidget.rowSlotIndex(row, col);
            Slot s = screen.getMenu().getSlot(slotIdx);
            if (s == null) continue;

            if (s.container != mc.player.getInventory() && !isBackpackScreen(screen)) continue;

            // Capture pre-swap state BEFORE swapSingleSlot (which calls
            // handleInventoryMouseClick → menu.clicked() updates local state)
            ItemStack preSlot = s.getItem().copy();
            ItemStack preHotbar = mc.player.getInventory().getItem(col).copy();

            // ── For the selected/held hotbar slot, use PICKUP directly ──
            //    Reason: many backpack mods (Traveller's Backpack, etc.) block
            //    SWAP when the target hotbar slot == inventory.selected.
            //    Using PICKUP avoids the 2-tick verify delay for that column.
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

            if (swapSingleSlot(mc, screen, s, col, true)) {
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
            playSwapSound(mc);
            SwapKeyState.closePendingTicks = isVanillaInventory(screen) ? 1 : 2;
        }
        return anySwap;
    }

    /**
     * Creative-mode row swap using handleCreativeModeItemAdd.
     * CreativeModeInventoryMenu is client-side only; SWAP packets sent via
     * handleInventoryMouseClick are processed against the server's
     * InventoryMenu (different slot layout), so they silently fail.
     * Instead, we directly swap inventory.Items and sync each change
     * via handleCreativeModeItemAdd — the same pattern used by
     * creativeSwap() for single-slot swaps.
     */
    private static boolean creativeRowSwap(Minecraft mc, CreativeModeInventoryScreen cs,
                                           int row, int sel) {
        int menuHotbarStart = 36;
        boolean anySwap = false;

        for (int col = 0; col < 9; col++) {
            int slotIdx = RowArrowWidget.rowSlotIndex(row, col);
            if (slotIdx < 0 || slotIdx >= cs.getMenu().slots.size()) continue;
            Slot s = cs.getMenu().getSlot(slotIdx);
            if (s == null) continue;
            if (s.container != mc.player.getInventory()) continue;

            int csi = s.getContainerSlot();
            if (csi == col) continue; // self-swap guard

            ItemStack invItem = s.getItem().copy();
            ItemStack hotbarItem = mc.player.getInventory().getItem(col).copy();

            if (invItem.isEmpty() && hotbarItem.isEmpty()) continue;

            // Swap: hotbar slot ← inventory item
            safeSet(mc, col, invItem);
            mc.gameMode.handleCreativeModeItemAdd(invItem, menuHotbarStart + col);

            // Swap: inventory slot ← hotbar item
            int invIdx = csi >= menuHotbarStart ? csi - menuHotbarStart : csi;
            safeSet(mc, invIdx, hotbarItem);
            mc.gameMode.handleCreativeModeItemAdd(hotbarItem, csi);

            anySwap = true;
        }

        if (anySwap) {
            playSwapSound(mc);
            SwapKeyState.closePendingTicks = 1;
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

    private static void safeSet(Minecraft mc, int idx, ItemStack stack) {
        if (idx >= 0 && idx < mc.player.getInventory().items.size())
            mc.player.getInventory().items.set(idx, stack);
    }

    private static boolean isPlayerInventorySlot(Slot slot) {
        return slot.container == Minecraft.getInstance().player.getInventory();
    }

    /** Standard findMenuSlot — matches by container + containerSlot. */
    private static Slot findMenuSlot(AbstractContainerScreen<?> screen, Inventory inv, int containerSlot) {
        for (Slot slot : screen.getMenu().slots)
            if (slot.container == inv && slot.getContainerSlot() == containerSlot) return slot;
        return null;
    }

    /**
     * Find hotbar menu slot with fallback for backpack mods.
     * Standard: container == playerInv && containerSlot == hotbarIdx.
     * Fallback (for backpack mods that wrap slots): getContainerSlot() == hotbarIdx.
     */
    private static Slot findHotbarMenuSlot(AbstractContainerScreen<?> screen, int hotbarIdx) {
        // Standard lookup first
        Inventory inv = Minecraft.getInstance().player.getInventory();
        Slot s = findMenuSlot(screen, inv, hotbarIdx);
        if (s != null) return s;
        // Fallback: match by getContainerSlot() only (for wrapped slots)
        for (Slot slot : screen.getMenu().slots)
            if (slot.getContainerSlot() == hotbarIdx) return slot;
        return null;
    }

    private static boolean isVanillaInventory(AbstractContainerScreen<?> screen) {
        return screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen;
    }

    private static boolean isBackpackScreen(Screen screen) {
        return BackpackScreenMatcher.isBackpackScreen(screen);
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

    /** Returns the current GUI swap key binding, or null if not initialized. */
    public static InputConstants.Key getGuiSwapKey() {
        return SWAP_IN_GUI_KEY != null ? SWAP_IN_GUI_KEY.getKey() : null;
    }

    /** Returns true if the GUI swap key is unbound (no key assigned). */
    public static boolean isGuiSwapKeyUnbound() {
        return SWAP_IN_GUI_KEY == null || SWAP_IN_GUI_KEY.isUnbound();
    }

    /** Check if ANY tracked target key (inventory key or backpack key) is physically held. */
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
        if (!config.mouseReposition.get() || !(screen instanceof AbstractContainerScreen<?> s)) return;
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

    private static void playSwapSound(Minecraft mc) {
        if (!config.soundEnabled.get() || mc.player == null) return;
        mc.player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f);
    }
}
