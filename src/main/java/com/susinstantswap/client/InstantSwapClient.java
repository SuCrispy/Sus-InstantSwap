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
import net.minecraft.world.inventory.AbstractContainerMenu;
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

    public static void init(SwapConfig cfg) {
        config = cfg;
        SwapLog.init(config);
        SwapToast.init(config);
        SWAP_IN_GUI_KEY = new KeyMapping("key.susinstantswap.swap_in_gui",
                InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(),
                "key.categories.susinstantswap");
        NeoForge.EVENT_BUS.register(InstantSwapClient.class);
    }

    public static void registerKey(RegisterKeyMappingsEvent event) {
        event.register(SWAP_IN_GUI_KEY);
    }

    private static boolean screenOpenedByInteract = false;
    private static Screen previousScreen = null;

    // SWAP verification
    private static int swapVerifyTicks = 0;
    private static int verifyHoverIdx = -1;
    private static int verifySelIdx = -1;
    private static ItemStack verifyPreHovered = ItemStack.EMPTY;
    private static ItemStack verifyPreHotbar = ItemStack.EMPTY;

    // Multi-step PICKUP retry: stage 3→2→1→0
    private static int pickupRetryStage = 0;
    private static int pickupRetryTargetIdx = -1;
    private static int pickupRetryScreenCid = -1;

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

        String name = screen.getClass().getName();
        if (!name.contains("sophisticated") && !name.contains("flanks255")
                && !name.contains("BackpackScreen") && !name.contains("omnis")
                && !name.contains("backpacked") && !name.contains("inmis")
                && !name.contains("goodbackpacks") && !name.contains("resource_backpacks")
                && !name.contains("ironbackpacks")) return;

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

        // Multi-step PICKUP retry: stage 3 → PICKUP from hotbar
        if (pickupRetryStage == 3 && mc.screen instanceof AbstractContainerScreen<?> rScreen
                && mc.getConnection() != null
                && rScreen.getMenu().containerId == pickupRetryScreenCid) {
            Slot hotbarSlot = findMenuSlot(rScreen, mc.player.getInventory(), verifySelIdx);
            if (hotbarSlot != null) {
                int stateId = rScreen.getMenu().getStateId();
                var cs = new Int2ObjectOpenHashMap<ItemStack>();
                mc.getConnection().send(new ServerboundContainerClickPacket(
                        pickupRetryScreenCid, stateId,
                        hotbarSlot.index, 0, ClickType.PICKUP,
                        ItemStack.EMPTY, cs));
                pickupRetryStage = 1;
                SwapKeyState.closePendingTicks = Math.max(SwapKeyState.closePendingTicks, 2);
            } else {
                mc.getConnection().send(new ServerboundContainerClickPacket(
                        pickupRetryScreenCid, rScreen.getMenu().getStateId(),
                        pickupRetryTargetIdx, 0, ClickType.PICKUP,
                        ItemStack.EMPTY, new Int2ObjectOpenHashMap<>()));
                pickupRetryStage = 0;
            }
        }
        // Multi-step PICKUP retry: stage 2 (PUT) → PICKUP from hotbar
        if (pickupRetryStage == 2 && mc.screen instanceof AbstractContainerScreen<?> rScreen
                && mc.getConnection() != null
                && rScreen.getMenu().containerId == pickupRetryScreenCid) {
            Slot hotbarSlot = findMenuSlot(rScreen, mc.player.getInventory(), verifySelIdx);
            if (hotbarSlot != null && hotbarSlot.hasItem()) {
                int stateId = rScreen.getMenu().getStateId();
                var cs = new Int2ObjectOpenHashMap<ItemStack>();
                mc.getConnection().send(new ServerboundContainerClickPacket(
                        pickupRetryScreenCid, stateId,
                        hotbarSlot.index, 0, ClickType.PICKUP,
                        ItemStack.EMPTY, cs));
                pickupRetryStage = 1;
                SwapKeyState.closePendingTicks = Math.max(SwapKeyState.closePendingTicks, 2);
            } else {
                pickupRetryStage = 0;
            }
        }
        // Multi-step PICKUP retry: stage 1 → PICKUP on target (final)
        if (pickupRetryStage == 1 && mc.screen instanceof AbstractContainerScreen<?> rScreen
                && mc.getConnection() != null
                && rScreen.getMenu().containerId == pickupRetryScreenCid) {
            int stateId = rScreen.getMenu().getStateId();
            var cs = new Int2ObjectOpenHashMap<ItemStack>();
            mc.getConnection().send(new ServerboundContainerClickPacket(
                    pickupRetryScreenCid, stateId,
                    pickupRetryTargetIdx, 0, ClickType.PICKUP,
                    ItemStack.EMPTY, cs));
            pickupRetryStage = 0;
            SwapKeyState.closePendingTicks = Math.max(SwapKeyState.closePendingTicks, 2);
        } else if (pickupRetryStage == 1) {
            pickupRetryStage = 0;
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

        // IDLE: wait for inventory key press
        if (state == SwapState.IDLE) {
            if (SwapKeyState.inventoryKeyHeld && mc.screen instanceof AbstractContainerScreen) {
                SwapKeyState.pressStartNanos = System.nanoTime();
                positionCursorIfEnabled(mc, mc.screen);
                state = SwapState.WATCHING;
            }
            return;
        }

        // WATCHING: check threshold
        if (state == SwapState.WATCHING) {
            if (mc.screen == null) { state = SwapState.IDLE; return; }
            if (!isInventoryKeyPhysicallyDown(mc)) { state = SwapState.IDLE; return; }
            if ((System.nanoTime() - SwapKeyState.pressStartNanos)
                    >= config.holdThresholdMs.get() * 1_000_000L) {
                SwapKeyState.longPressConfirmed = true;
                state = SwapState.LONG_PRESS;
            }
            return;
        }

        // LONG_PRESS → release triggers swap
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

    // InputEvent: GUI swap + text protection
    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return;

        int action = event.getAction();
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_RELEASE) return;

        boolean keyDown = (action == GLFW.GLFW_PRESS);
        boolean isInventoryKey = isInventoryKeyEvent(mc, event);
        boolean isGuiSwapKey = SWAP_IN_GUI_KEY.isUnbound() ? false : isGuiSwapKeyEvent(event);

        if (keyDown && isInventoryKey && mc.screen != null && hasEditBoxFocus(mc.screen)) {
            while (mc.options.keyInventory.consumeClick()) {}
            if (mc.screen instanceof AbstractContainerScreen) {
                if (mc.player.containerMenu.getSlot(0).hasItem()) return;
            } else return;
        }

        if (keyDown && config.guiSwapEnabled.get()) {
            if ((isGuiSwapKey || (isInventoryKey && SWAP_IN_GUI_KEY.isUnbound()))
                    && mc.screen instanceof AbstractContainerScreen) {
                performSwap(mc);
            }
        }
    }

    public static boolean tryPerformGuiSwap(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return false;
        if (!config.guiSwapEnabled.get() || !SWAP_IN_GUI_KEY.isUnbound()) return false;
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

        if (screen instanceof InventoryScreen && !isPlayerInventorySlot(hs)) return false;

        int closeDelay = isVanillaInventory(screen) ? 1 : 2;
        if (swapSingleSlot(mc, screen, hs, sel, false)) {
            verifyHoverIdx = hs.index;
            verifySelIdx = sel;
            verifyPreHovered = hs.getItem().copy();
            verifyPreHotbar = mc.player.getInventory().getItem(sel).copy();
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

        Slot hotbarMenuSlot = findMenuSlot(screen, mc.player.getInventory(), hotbarIdx);
        if (hotbarMenuSlot != null && hotbarMenuSlot.hasItem()
                && !hotbarMenuSlot.mayPickup(mc.player)) {
            if (!suppressToast) SwapToast.warn("toast.susinstantswap.item_in_use");
            return false;
        }

        ItemStack hand = mc.player.getInventory().getItem(hotbarIdx);
        if (!hand.isEmpty() && !hs.mayPlace(hand)) {
            if (!suppressToast) SwapToast.warn("toast.susinstantswap.item_not_placeable");
            return false;
        }

        if (hs.hasItem() && !hs.mayPickup(mc.player) && isPlayerInventorySlot(hs)) {
            if (!suppressToast) SwapToast.warn("toast.susinstantswap.slot_locked");
            return false;
        }

        return containerSwap(screen, hs.index, hotbarIdx);
    }

    private static void verifySwapResult(Minecraft mc, AbstractContainerScreen<?> screen) {
        if (verifyHoverIdx < 0 || verifyHoverIdx >= screen.getMenu().slots.size()) return;

        Slot hs = screen.getMenu().getSlot(verifyHoverIdx);
        ItemStack postHovered = hs.getItem();
        ItemStack postHotbar = mc.player.getInventory().getItem(verifySelIdx);
        ItemStack hotbarItem = postHotbar;

        boolean hoveredChanged = !ItemStack.matches(postHovered, verifyPreHovered);
        boolean hotbarChanged = !ItemStack.matches(postHotbar, verifyPreHotbar);
        boolean swapSucceeded = hoveredChanged || hotbarChanged;

        if (swapSucceeded) return;

        SwapLog.debug("SWAP verify: FAILED screen={} hoverIdx={} sel={} hoverHasItem={} hotbarHasItem={} hoverMayPickup={} isPlayerInv={}",
                screen.getClass().getSimpleName(), verifyHoverIdx, verifySelIdx,
                hs.hasItem(), !hotbarItem.isEmpty(),
                hs.hasItem() ? hs.mayPickup(mc.player) : "n/a", isPlayerInventorySlot(hs));

        if (mc.getConnection() == null) return;

        int cid = screen.getMenu().containerId;
        int stateId = screen.getMenu().getStateId();
        var cs = new Int2ObjectOpenHashMap<ItemStack>();

        // Case 1: TAKE — hovered has item, hotbar empty
        if (hs.hasItem() && hotbarItem.isEmpty()) {
            if (isPlayerInventorySlot(hs)) {
                // Player-inv slot → PICKUP to specific hotbar
                mc.getConnection().send(new ServerboundContainerClickPacket(
                        cid, stateId, hs.index, 0, ClickType.PICKUP, ItemStack.EMPTY, cs));
                pickupRetryStage = 3;
                pickupRetryTargetIdx = hs.index;
                pickupRetryScreenCid = cid;
                SwapKeyState.closePendingTicks = Math.max(SwapKeyState.closePendingTicks, 3);
            } else {
                // Container slot → QUICK_MOVE
                mc.getConnection().send(new ServerboundContainerClickPacket(
                        cid, stateId, hs.index, 0, ClickType.QUICK_MOVE, ItemStack.EMPTY, cs));
                SwapKeyState.closePendingTicks = Math.max(SwapKeyState.closePendingTicks, 2);
            }
            return;
        }

        // Case 2: EXCHANGE — both have items → three-step PICKUP
        if (hs.hasItem() && !hotbarItem.isEmpty()) {
            mc.getConnection().send(new ServerboundContainerClickPacket(
                    cid, stateId, hs.index, 0, ClickType.PICKUP, ItemStack.EMPTY, cs));
            pickupRetryStage = 3;
            pickupRetryTargetIdx = hs.index;
            pickupRetryScreenCid = cid;
            SwapKeyState.closePendingTicks = Math.max(SwapKeyState.closePendingTicks, 3);
            return;
        }

        // Case 3: PUT — hovered empty, hotbar has item → two-step PICKUP
        if (!hs.hasItem() && !hotbarItem.isEmpty()
                && (hs.mayPlace(hotbarItem) || !isPlayerInventorySlot(hs))) {
            SwapLog.debug("  retry PUT: emptyTarget={} mayPlace={} isPlayerInv={} hotbar={}",
                    hs.index, hs.mayPlace(hotbarItem), isPlayerInventorySlot(hs), verifySelIdx);
            Slot hotbarSlot = findMenuSlot(screen, mc.player.getInventory(), verifySelIdx);
            if (hotbarSlot != null && hotbarSlot.hasItem()) {
                mc.getConnection().send(new ServerboundContainerClickPacket(
                        cid, stateId, hotbarSlot.index, 0, ClickType.PICKUP, ItemStack.EMPTY, cs));
                pickupRetryStage = 2;
                pickupRetryTargetIdx = hs.index;
                pickupRetryScreenCid = cid;
                SwapKeyState.closePendingTicks = Math.max(SwapKeyState.closePendingTicks, 2);
            } else {
                SwapLog.debug("  retry PUT SKIP: hotbar slot {} found={} hasItem={}",
                        verifySelIdx, hotbarSlot != null, hotbarSlot != null && hotbarSlot.hasItem());
            }
        }
    }

    private static boolean containerSwap(AbstractContainerScreen<?> s, int slotIdx, int hotbar) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return false;

        Int2ObjectOpenHashMap<ItemStack> cs = new Int2ObjectOpenHashMap<>();
        Slot slot = s.getMenu().getSlot(slotIdx);
        if (slot != null && slot.container == mc.player.getInventory()
                && slot.getContainerSlot() == hotbar) return false;

        // Skip mayPickup for container slots (server-side may differ)
        if (slot != null && slot.hasItem() && !slot.mayPickup(mc.player)
                && slot.container == mc.player.getInventory()) return false;

        Slot hSlot = findMenuSlot(s, mc.player.getInventory(), hotbar);
        if (hSlot != null && hSlot.hasItem() && !hSlot.mayPickup(mc.player)) return false;

        mc.getConnection().send(new ServerboundContainerClickPacket(
                s.getMenu().containerId, s.getMenu().getStateId(), slotIdx, hotbar,
                ClickType.SWAP, ItemStack.EMPTY, cs));
        return true;
    }

    private static boolean performRowSwap(Minecraft mc, AbstractContainerScreen<?> screen) {
        int row = RowArrowWidget.hoveredRow;
        boolean anySwap = false;
        Slot selSlot = null;
        int selHotbar = mc.player.getInventory().selected;

        for (int col = 0; col < 9; col++) {
            int slotIdx = RowArrowWidget.rowSlotIndex(row, col);
            Slot s = screen.getMenu().getSlot(slotIdx);
            if (s == null) continue;

            if (s.container != mc.player.getInventory() && !isBackpackScreen(screen)) continue;

            if (swapSingleSlot(mc, screen, s, col, true)) {
                anySwap = true;
                if (col == selHotbar) selSlot = s;
            }
        }

        if (selSlot != null) {
            verifyHoverIdx = selSlot.index;
            verifySelIdx = selHotbar;
            verifyPreHovered = selSlot.getItem().copy();
            verifyPreHotbar = mc.player.getInventory().getItem(selHotbar).copy();
            swapVerifyTicks = 2;
        }

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

    private static Slot findMenuSlot(AbstractContainerScreen<?> screen, Inventory inv, int containerSlot) {
        for (Slot slot : screen.getMenu().slots)
            if (slot.container == inv && slot.getContainerSlot() == containerSlot) return slot;
        return null;
    }

    private static boolean isVanillaInventory(AbstractContainerScreen<?> screen) {
        return screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen;
    }

    private static boolean isBackpackScreen(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> s)) return false;
        String name = s.getClass().getName();
        return name.contains("sophisticated") || name.contains("flanks255")
            || name.contains("BackpackScreen") || name.contains("omnis")
            || name.contains("backpacked") || name.contains("inmis")
            || name.contains("goodbackpacks") || name.contains("resource_backpacks")
            || name.contains("ironbackpacks");
    }

    private static int hotbarSize(Minecraft mc) {
        return mc.player.getInventory().items.size() - 27;
    }

    private static boolean isContainerOpener(ItemStack hotbarStack, AbstractContainerMenu menu) {
        if (hotbarStack.isEmpty()) return false;
        Minecraft mc = Minecraft.getInstance();
        var playerInv = mc.player.getInventory();
        int idx = 0;
        for (Slot slot : menu.slots) {
            if (slot.container == playerInv) { idx++; continue; }
            if (!slot.hasItem()) { idx++; continue; }
            boolean locked = !slot.mayPickup(mc.player);
            boolean sameItem = slot.getItem().getItem() == hotbarStack.getItem();
            if (locked && sameItem) return true;
            idx++;
        }
        return false;
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

    public static boolean isSwapKey(InputConstants.Key key) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return false;
        InputConstants.Key invKey = mc.options.keyInventory.getKey();
        return invKey.getType() == key.getType() && invKey.getValue() == key.getValue();
    }

    private static boolean isInventoryKeyPhysicallyDown(Minecraft mc) {
        for (InputConstants.Key key : SwapKeyState.getTargetKeys()) {
            if (key.getType() == InputConstants.Type.KEYSYM
                    && GLFW.glfwGetKey(mc.getWindow().getWindow(), key.getValue()) == GLFW.GLFW_PRESS)
                return true;
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
