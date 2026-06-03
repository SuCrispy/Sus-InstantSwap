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
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

/**
 * Sus-InstantSwap v2.0 — coexists with the vanilla inventory key.
 */
public class InstantSwapClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("susinstantswap", "main"));
    private static KeyMapping SWAP_IN_GUI_KEY;
    private static SwapConfig config;

    enum SwapState { IDLE, WATCHING, LONG_PRESS }
    private static SwapState state = SwapState.IDLE;

    private static boolean configLogged = false;
    private static int suppressTooltipTicks;

    public static boolean isTooltipSuppressed() { return suppressTooltipTicks > 0; }

    public static void init(SwapConfig cfg) {
        LOGGER.info("[SusInstantSwap] v2.0");
        config = cfg;
        SWAP_IN_GUI_KEY = new KeyMapping("key.susinstantswap.swap_in_gui",
                InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);
        NeoForge.EVENT_BUS.register(InstantSwapClient.class);
    }

    public static void registerKey(RegisterKeyMappingsEvent event) {
        event.register(SWAP_IN_GUI_KEY);
    }

    // ── Container opened via right-click → reposition cursor ──
    // Tracked by PlayerInteractEvent to avoid repositioning for keybind-opened
    // screens.

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
        if (!config.mouseReposition.get()) return;
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> s)) return;
        if (s instanceof InventoryScreen || s instanceof CreativeModeInventoryScreen) return;
        if (!screenOpenedByInteract) return;
        screenOpenedByInteract = false;
        positionCursorToUIBottomRight(s);
    }

    // ── NF 26.1: ScreenEvent.KeyPressed.Pre replaces ScreenKeyMixin ──

    @SubscribeEvent
    public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!SwapKeyState.modEnabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return;
        if (event.getKeyCode() != mc.options.keyInventory.getKey().getValue()) return;

        if (SwapKeyState.inventoryKeyHeld) {
            event.setCanceled(true); return;
        }

        if (config.guiSwapEnabled.get() && isGuiSwapUnbound()
                && mc.screen instanceof AbstractContainerScreen) {
            if (performSwap(mc)) {
                SwapKeyState.closePendingTicks = isInventoryScreen(mc.screen) ? 1 : 2;
                event.setCanceled(true);
            }
        }
    }

    // ── Per-tick ──

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        if (suppressTooltipTicks > 0) suppressTooltipTicks--;

        if (!configLogged) {
            configLogged = true;
            LOGGER.info("[SusInstantSwap] Config: mod={} threshold={}ms sound={} guiSwap={} emptySwap={} debug={} mouse={}",
                    config.modEnabled.get(), config.holdThresholdMs.get(), config.soundEnabled.get(),
                    config.guiSwapEnabled.get(), config.emptySlotSwapEnabled.get(),
                    config.debug.get(), config.mouseReposition.get());
        }

        // Sync master switch to shared state (read by mixins)
        SwapKeyState.modEnabled = config.modEnabled.get();
        if (!SwapKeyState.modEnabled) return;

        if (mc.player == null || mc.gameMode == null) {
            state = SwapState.IDLE;
            SwapKeyState.closePendingTicks = 0;
            return;
        }

        // Deferred close — countdown gives server 1 tick to process SWAP before closing
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
                    >= config.holdThresholdMs.get() * 1_000_000L) {
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
                SwapKeyState.closePendingTicks = isInventoryScreen(mc.screen) ? 1 : 2;
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
        boolean isInventoryKey = isInventoryKeyEvent(event);
        boolean isGuiSwapKey = isGuiSwapUnbound() ? false : isGuiSwapKeyEvent(event);

        // EditBox protection: consume vanilla click so E doesn't close screen
        if (keyDown && isInventoryKey && mc.screen != null && hasEditBoxFocus(mc.screen)) {
            while (mc.options.keyInventory.consumeClick()) {}
            if (mc.screen instanceof AbstractContainerScreen) {
                if (mc.player.containerMenu.getSlot(0).hasItem()) return;
            } else return;
        }

        // GUI swap
        if (keyDown && config.guiSwapEnabled.get()) {
            // Dedicated GUI swap key only — inventory key handled by onScreenKeyPressed
            if (isGuiSwapKey && mc.screen instanceof AbstractContainerScreen) {
                if (performSwap(mc)) {
                    SwapKeyState.closePendingTicks = isInventoryScreen(mc.screen) ? 1 : 2;
                }
            }
        }
    }

    // ── GUI swap entry (from ScreenKeyMixin) ──

    public static boolean tryPerformGuiSwap(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return false;
        if (!config.guiSwapEnabled.get() || !isGuiSwapUnbound()) return false;
        if (performSwap(mc)) {
            SwapKeyState.closePendingTicks = isInventoryScreen(mc.screen) ? 1 : 2;
            return true;
        }
        return false;
    }

    @SubscribeEvent
    public static void onRenderTooltip(RenderTooltipEvent.Pre event) {
        if (isTooltipSuppressed()) {
            event.setCanceled(true);
        }
    }

    // ── Unified swap (GUI + long press) ──
    //
    // Gatekeeper: categorises screens and decides which slots are swappable.
    //
    // All slots are allowed provided the items are mutually placeable
    // (itemsCompatible) and the slot permits pickup (mayPickup in containerSwap).
    // Auto-close only for the player's own inventory screens.

    private static boolean performSwap(Minecraft mc) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return false;
        Slot hs = screen.getSlotUnderMouse();
        if (hs == null || (!hs.hasItem() && !config.emptySlotSwapEnabled.get())) return false;

        int sel = mc.player.getInventory().getSelectedSlot();
        // Both slots empty → nothing to swap, no sound
        if (!hs.hasItem() && mc.player.getInventory().getItem(sel).isEmpty()) return false;
        boolean creative = mc.player.hasInfiniteMaterials();

        // Equipment slots (armor, offhand) — never swappable
        if (isEquipSlot(hs)) return false;

        // ── Creative inventory ──
        if (screen instanceof CreativeModeInventoryScreen cs) {
            if (!creative) return false;
            debugLog("performSwap creative sel=" + sel + " hasItem=" + hs.hasItem());
            if (creativeSwap(mc, cs, sel)) { playSwapSound(mc); return true; }
            return false;
        }

        // ── Survival inventory ──
        if (screen instanceof InventoryScreen) {
            if (hs.getContainerSlot() == sel) return false;
            if (!itemsCompatible(hs, screen, mc, sel)) return false;
            if (containerSwap(screen, hs.index, sel)) {
                playSwapSound(mc);
                return true;
            }
            return false;
        }

        // ── Other containers (chest, furnace, hopper, vehicle, etc.) ──
        if (!itemsCompatible(hs, screen, mc, sel)) return false;

        if (containerSwap(screen, hs.index, sel)) {
            playSwapSound(mc);
            return true;
        }
        return false;
    }

    /**
     * Bidirectional mayPlace check — avoids starting a swap that the server will reject.
     * Not needed for creative mode (handleCreativeModeItemAdd bypasses mayPlace).
     */
    private static boolean itemsCompatible(Slot hs, AbstractContainerScreen<?> screen,
                                            Minecraft mc, int sel) {
        ItemStack hotbarStack = mc.player.getInventory().getItem(sel);
        if (!hotbarStack.isEmpty() && !hs.mayPlace(hotbarStack)) return false;
        if (hs.hasItem()) {
            int hotbarMenuIdx = screen.getMenu().slots.size() - 9 + sel;
            Slot hotbarSlot = screen.getMenu().getSlot(hotbarMenuIdx);
            if (!hotbarSlot.mayPlace(hs.getItem())) return false;
        }
        return true;
    }

    private static boolean containerSwap(AbstractContainerScreen<?> s, int slotIdx, int hotbar) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return false;

        // Pre-check: skip if slot doesn't allow pickup
        Slot slot = s.getMenu().getSlot(slotIdx);
        if (slot != null && !slot.mayPickup(mc.player)) return false;

        int containerId = s.getMenu().containerId;
        int stateId = s.getMenu().getStateId();

        Int2ObjectOpenHashMap<HashedStack> cs = new Int2ObjectOpenHashMap<>();
        mc.getConnection().send(new ServerboundContainerClickPacket(
                containerId, stateId, (byte) slotIdx, (byte) hotbar,
                ContainerInput.SWAP, cs, HashedStack.EMPTY));
        return true;
    }

    // ── Creative mode swap (ported from NF 1.21.1 v2.0) ──
    // Branch order:
    //   1. CONTAINER (creative tab item grid)
    //   2. CREATIVE_EQUIP (armor/offhand, isInventoryOpen=true)
    //   3. SlotWrapper (non-equipment player inventory: hotbar 36-44, backpack 9-35)
    //   4. REGULAR (fallback hotbar slots)

    private static boolean creativeSwap(Minecraft mc, CreativeModeInventoryScreen cs, int sel) {
        if (mc.gameMode == null) return false;
        Slot hs = cs.getSlotUnderMouse();
        if (hs == null || (!hs.hasItem() && !config.emptySlotSwapEnabled.get())) return false;

        int hotbarSize = hotbarSize(mc);
        int menuHotbarStart = 36;
        int heldIdx = menuHotbarStart + sel;
        ItemStack handStack = mc.player.getInventory().getItem(sel);
        debugLog("creativeSwap ENTER: sel=" + sel + " hand=" + (handStack.isEmpty()?"EMPTY":handStack.getDisplayName().getString())
                + " hs.container=" + (hs.container==CreativeModeInventoryScreen.CONTAINER?"CONTAINER":hs.container==mc.player.getInventory()?"PLAYER_INV":
                  hs instanceof CreativeModeInventoryScreen.SlotWrapper?"SlotWrapper("+((CreativeModeInventoryScreen.SlotWrapper)hs).target.index+")":"OTHER")
                + " hs.index=" + hs.index + " csi=" + hs.getContainerSlot());

        // ── CONTAINER (creative tab item grid) ──
        if (hs.container == CreativeModeInventoryScreen.CONTAINER) {
            debugLog("  branch=CONTAINER");
            ItemStack held = handStack.copy();
            ItemStack item = hs.getItem().copyWithCount(1);
            debugLog("  held=" + (held.isEmpty()?"EMPTY":held.getDisplayName().getString()) + " item=" + item.getDisplayName().getString());
            if (!held.isEmpty()) {
                int f = freeSlot(mc);
                debugLog("  freeSlot=" + f);
                if (f >= 0 && f < mc.player.getInventory().getContainerSize()) {
                    mc.player.getInventory().setItem(f, held.copy());
                    mc.gameMode.handleCreativeModeItemAdd(held.copy(), menuHotbarStart + f);
                    debugLog("  setItem(" + f + ",held) + addItem(" + (menuHotbarStart+f) + ")");
                }
            }
            if (sel < mc.player.getInventory().getContainerSize()) {
                mc.player.getInventory().setItem(sel, item);
                mc.gameMode.handleCreativeModeItemAdd(item, heldIdx);
                debugLog("  setItem(" + sel + ",item) + addItem(" + heldIdx + ")");
            }
            SwapKeyState.closePendingTicks = 1;
            return true;
        }

        // ── CREATIVE_EQUIP: csi=5-8 (armor) or 45 (offhand) ──
        // Only applies on the inventory/survival tab (not creative item tabs)
        int csi = hs.getContainerSlot();
        if (cs.isInventoryOpen() && (csi == 45 || (csi >= 5 && csi <= 8))) {
            debugLog("  branch=CREATIVE_EQUIP csi=" + csi + " sel=" + sel);
            // Slot type validation — reject items that don't fit the equipment slot
            if (!handStack.isEmpty() && !hs.mayPlace(handStack)) {
                debugLog("  mayPlace rejected -> false");
                return false;
            }
            // Armor type validation (only for armor slots, not offhand)
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
            // Use ServerboundContainerClickPacket for MC 26.1 (handleInventoryMouseClick deprecated)
            mc.getConnection().send(new ServerboundContainerClickPacket(
                cs.getMenu().containerId, cs.getMenu().getStateId(),
                (byte) csi, (byte) sel, ContainerInput.SWAP,
                new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY));
            debugLog("  ContainerClickPacket(slot=" + csi + " hotbar=" + sel + " SWAP)");
            SwapKeyState.closePendingTicks = 1;
            return true;
        }

        // ── SlotWrapper (non-equipment player inventory: hotbar 36-44, backpack 9-35) ──
        // Equipment SlotWrappers (5-8/45) are handled by CREATIVE_EQUIP above.
        if (hs instanceof CreativeModeInventoryScreen.SlotWrapper w) {
            int t = w.target.index;
            debugLog("  branch=SlotWrapper t=" + t + " heldMenuIdx=" + heldIdx);
            if (isPlayerInventorySlot(w) && t != heldIdx) {
                ItemStack ti = cs.getMenu().getSlot(t).getItem().copy();
                ItemStack hi = cs.getMenu().getSlot(heldIdx).getItem().copy();
                int invIdx = t >= menuHotbarStart ? t - menuHotbarStart : t;
                debugLog("  ti=" + ti.getDisplayName().getString() + " hi=" + hi.getDisplayName().getString() + " invIdx=" + invIdx);
                mc.player.getInventory().setItem(sel, ti);
                mc.gameMode.handleCreativeModeItemAdd(ti, heldIdx);
                debugLog("  setItem(" + sel + ",ti) + addItem(" + heldIdx + ")");
                mc.player.getInventory().setItem(invIdx, hi);
                mc.gameMode.handleCreativeModeItemAdd(hi, t);
                debugLog("  setItem(" + invIdx + ",hi) + addItem(" + t + ")");
                SwapKeyState.closePendingTicks = 1;
                return true;
            }
            debugLog("  SKIP: sameSlot=" + (t==heldIdx) + " isPlayerInv=" + isPlayerInventorySlot(w));
            return false;
        }

        // ── REGULAR (fallback hotbar slots, non-SlotWrapper) ──
        int c2 = hs.getContainerSlot();
        debugLog("  branch=REGULAR c2=" + c2);
        if (c2 >= 0 && c2 < hotbarSize && c2 != sel) {
            ItemStack hi = handStack.copy();
            ItemStack oi = mc.player.getInventory().getItem(c2).copy();
            debugLog("  hi(hand->target)=" + (hi.isEmpty()?"EMPTY":hi.getDisplayName().getString()) + " oi(target->hotbar)=" + oi.getDisplayName().getString());
            mc.player.getInventory().setItem(sel, oi);
            mc.gameMode.handleCreativeModeItemAdd(oi, heldIdx);
            debugLog("  setItem(" + sel + ",oi) + addItem(" + heldIdx + ")");
            mc.player.getInventory().setItem(c2, hi);
            mc.gameMode.handleCreativeModeItemAdd(hi, menuHotbarStart + c2);
            debugLog("  setItem(" + c2 + ",hi) + addItem(" + (menuHotbarStart+c2) + ")");
            SwapKeyState.closePendingTicks = 1;
            return true;
        }
        debugLog("  NO MATCH -> false");
        return false;
    }

    /** Equipment = armor + offhand.  SlotWrapper (creative screen) is never equipment. */
    private static boolean isEquipSlot(Slot hs) {
        if (hs instanceof CreativeModeInventoryScreen.SlotWrapper) return false;
        return isPlayerInventorySlot(hs) && hs.getContainerSlot() >= 36;
    }

    private static boolean isPlayerInventorySlot(Slot slot) {
        return slot.container == Minecraft.getInstance().player.getInventory();
    }

    // ── Close policy ──

    private static boolean isInventoryScreen(Screen screen) {
        return screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen;
    }

    private static int hotbarSize(Minecraft mc) {
        return mc.player.getInventory().getContainerSize() - 27; // 9 in vanilla
    }

    private static int freeSlot(Minecraft mc) {
        var inv = mc.player.getInventory();
        int size = 36; // hotbar(9) + main(27), excludes armor/offhand
        int hbSize = hotbarSize(mc);
        int sel = inv.getSelectedSlot();
        // Hotbar first (creative — closest to cursor), then backpack
        for (int i = 0; i < hbSize; i++)
            if (i != sel && inv.getItem(i).isEmpty()) return i;
        for (int i = hbSize; i < size; i++)
            if (inv.getItem(i).isEmpty()) return i;
        return -1;
    }

    // ── Key detection ──

    private static boolean isInventoryKeyPhysicallyDown(Minecraft mc) {
        InputConstants.Key key = mc.options.keyInventory.getKey();
        if (key.getType() != InputConstants.Type.KEYSYM) return false;
        return GLFW.glfwGetKey(mc.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
    }

    private static boolean isInventoryKeyEvent(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        InputConstants.Key ik = mc.options.keyInventory.getKey();
        return ik.getType() == InputConstants.Type.KEYSYM && event.getKey() == ik.getValue();
    }

    private static boolean isGuiSwapKeyEvent(InputEvent.Key event) {
        if (isGuiSwapUnbound()) return false;
        InputConstants.Key bk = SWAP_IN_GUI_KEY.getKey();
        return bk.getType() == InputConstants.Type.KEYSYM && event.getKey() == bk.getValue();
    }

    private static boolean isGuiSwapUnbound() {
        return SWAP_IN_GUI_KEY.getKey().getValue() == InputConstants.UNKNOWN.getValue();
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
        long h = mc.getWindow().handle();
        double gs = mc.getWindow().getGuiScale();
        GLFW.glfwSetCursorPos(h,
                (int) ((s.getGuiLeft() + s.getXSize()) * gs) - 5,
                (int) ((s.getGuiTop() + s.getYSize()) * gs) - 5);
        suppressTooltipTicks = 3;
    }

    private static void playSwapSound(Minecraft mc) {
        if (!config.soundEnabled.get() || mc.player == null) return;
        mc.player.playSound(SoundEvents.ITEM_PICKUP, 0.8f, 1.0f);
    }

    private static void debugLog(String msg) {
        if (config.debug.get()) LOGGER.info("[SusInstantSwap] {}", msg);
    }
}
