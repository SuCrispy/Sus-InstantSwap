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
    private static boolean suppressNextTooltip;
    private static int suppressTooltipFrames;

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

        if (suppressTooltipFrames > 0 && --suppressTooltipFrames == 0)
            suppressNextTooltip = false;

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
        if (suppressNextTooltip) {
            event.setCanceled(true);
            suppressNextTooltip = false;
            suppressTooltipFrames = 0;
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

    // ── Creative mode swap (called only for CONTAINER tabs + player storage) ──

    private static boolean creativeSwap(Minecraft mc, CreativeModeInventoryScreen cs, int sel) {
        if (mc.gameMode == null) return false;
        Slot hs = cs.getSlotUnderMouse();
        if (hs == null || (!hs.hasItem() && !config.emptySlotSwapEnabled.get())) return false;

        int menuHotbarStart = 36;
        int heldMenuSlot = menuHotbarStart + sel;

        // ── Player inventory slots ──
        if (hs.container == mc.player.getInventory()) {
            int csi = hs.getContainerSlot();
            // Armor/offhand disabled in creative
            if (csi >= 36) { debugLog("creativeSwap blocked equip csi=" + csi); return false; }
            // Swap with another storage slot
            if (csi != sel) {
                ItemStack ti = hs.getItem().copy();
                ItemStack hi = mc.player.getInventory().getItem(sel).copy();
                mc.player.getInventory().setItem(sel, ti);
                mc.gameMode.handleCreativeModeItemAdd(ti, heldMenuSlot);
                mc.player.getInventory().setItem(csi, hi);
                mc.gameMode.handleCreativeModeItemAdd(hi, menuHotbarStart + csi);
                return true;
            }
            debugLog("creativeSwap same slot csi=" + csi + " sel=" + sel);
            return false;
        }

        // ── Everything else (CONTAINER tabs, unknown) → creative placement ──
        ItemStack held = mc.player.getInventory().getItem(sel).copy();
        ItemStack item = hs.getItem().copyWithCount(1);
        if (!held.isEmpty()) {
            int f = freeSlot(mc);
            if (f >= 0 && f < mc.player.getInventory().getContainerSize()) {
                mc.player.getInventory().setItem(f, held.copy());
                mc.gameMode.handleCreativeModeItemAdd(held.copy(), menuHotbarStart + f);
            }
        }
        mc.player.getInventory().setItem(sel, item);
        mc.gameMode.handleCreativeModeItemAdd(item, heldMenuSlot);
        return true;
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
        int size = mc.player.getInventory().getContainerSize();
        int hbSize = hotbarSize(mc);
        int sel = mc.player.getInventory().getSelectedSlot();
        // Hotbar first (creative — closest to cursor), then backpack
        for (int i = 0; i < hbSize; i++)
            if (i != sel && mc.player.getInventory().getItem(i).isEmpty()) return i;
        for (int i = hbSize; i < size; i++)
            if (mc.player.getInventory().getItem(i).isEmpty()) return i;
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
        suppressNextTooltip = true;
        suppressTooltipFrames = 2;
    }

    private static void playSwapSound(Minecraft mc) {
        if (!config.soundEnabled.get() || mc.player == null) return;
        mc.player.playSound(SoundEvents.ITEM_PICKUP, 0.8f, 1.0f);
    }

    private static void debugLog(String msg) {
        if (config.debug.get()) LOGGER.info("[SusInstantSwap] {}", msg);
    }
}
