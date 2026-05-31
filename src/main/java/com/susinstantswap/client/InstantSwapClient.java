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
 * Uses Forge AT for CreativeModeInventoryScreen internals (CONTAINER, SlotWrapper).
 */
public class InstantSwapClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static KeyMapping SWAP_IN_GUI_KEY;

    enum SwapState { IDLE, WATCHING, LONG_PRESS }
    private static SwapState state = SwapState.IDLE;

    private static boolean configLogged = false;
    private static boolean suppressNextTooltip;
    private static int suppressTooltipFrames;

    // ── Edge detection for GUI swap (v1.3.0 pattern) ──
    private static boolean prevEKeyDown;

    // ── Initialisation ──

    public static void init() {
        LOGGER.info("[SusInstantSwap] v2.0 Forge 1.21.1 — client init");
        SWAP_IN_GUI_KEY = new KeyMapping("key.susinstantswap.swap_in_gui",
                InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(),
                "key.categories.susinstantswap");
        MinecraftForge.EVENT_BUS.register(InstantSwapClient.class);
    }

    public static void registerKey(RegisterKeyMappingsEvent event) {
        event.register(SWAP_IN_GUI_KEY);
    }

    // ── Container opened via right-click → reposition cursor ──

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

        if (!configLogged) {
            configLogged = true;
            LOGGER.info("[SusInstantSwap] Config: mod={} threshold={}ms sound={} guiSwap={} emptySwap={} debug={} mouse={}",
                    SwapConfig.modEnabledRuntime, SwapConfig.holdThresholdMsRuntime, SwapConfig.soundEnabledRuntime,
                    SwapConfig.guiSwapEnabledRuntime, SwapConfig.emptySlotSwapEnabledRuntime,
                    SwapConfig.debugRuntime, SwapConfig.mouseRepositionRuntime);
        }

        SwapKeyState.modEnabled = SwapConfig.modEnabledRuntime;
        if (!SwapKeyState.modEnabled) return;

        if (mc.player == null || mc.gameMode == null) {
            state = SwapState.IDLE;
            prevEKeyDown = false;
            SwapKeyState.closePendingTicks = 0;
            return;
        }

        // ── Edge detection for E key (v1.3.0 pattern) ──
        boolean eDown = isInventoryKeyPhysicallyDown(mc);
        boolean ePressed = eDown && !prevEKeyDown;
        prevEKeyDown = eDown;

        // GUI swap on fresh E press in container (before state machine)
        if (ePressed && SwapConfig.guiSwapEnabledRuntime && SWAP_IN_GUI_KEY.isUnbound()
                && mc.screen instanceof AbstractContainerScreen) {
            debugLog("GUI swap press detected");
            if (performSwap(mc)) {
                debugLog("GUI swap OK → close");
                state = SwapState.IDLE;
                mc.player.closeContainer();
                return;
            }
        }

        // Deferred close
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
                    SwapKeyState.closePendingTicks = closeDelay;
                }
                state = SwapState.IDLE;
            }
        }
    }

    // ── InputEvent: text protection ──

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return;

        int action = event.getAction();
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_RELEASE) return;

        boolean keyDown = (action == GLFW.GLFW_PRESS);
        boolean isInventoryKey = isInventoryKeyEvent(mc, event);
        boolean isGuiSwapKey = SWAP_IN_GUI_KEY.isUnbound() ? false : isGuiSwapKeyEvent(event);
        boolean isAnySwapKey = isInventoryKey || isGuiSwapKey;

        // EditBox protection
        if (keyDown && isAnySwapKey && mc.screen != null && hasEditBoxFocus(mc.screen)) {
            if (isInventoryKey) while (mc.options.keyInventory.consumeClick()) {}
            if (isGuiSwapKey) while (SWAP_IN_GUI_KEY.consumeClick()) {}
            if (mc.screen instanceof AbstractContainerScreen) {
                if (mc.player.containerMenu.getSlot(0).hasItem()) return;
            } else return;
        }

        // GUI swap via dedicated key (when SWAP_IN_GUI_KEY is bound)
        if (keyDown && SwapConfig.guiSwapEnabledRuntime && isGuiSwapKey
                && mc.screen instanceof AbstractContainerScreen) {
            performSwap(mc);
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

    // ── Unified swap ──

    private static boolean performSwap(Minecraft mc) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return false;
        Slot hs = screen.getSlotUnderMouse();
        if (hs == null || (!hs.hasItem() && !SwapConfig.emptySlotSwapEnabledRuntime)) return false;

        int sel = mc.player.getInventory().selected;
        if (!hs.hasItem() && mc.player.getInventory().getItem(sel).isEmpty()) return false;

        boolean creative = mc.gameMode.hasInfiniteItems();

        if (screen instanceof CreativeModeInventoryScreen cs) {
            if (!creative) return false;
            if (creativeSwap(mc, cs, sel)) { playSwapSound(mc); return true; }
            return false;
        }

        if (screen instanceof InventoryScreen && (!isPlayerInventorySlot(hs) || hs.index == hotbarMenuSlot(sel)))
            return false;

        ItemStack hand = mc.player.getInventory().getItem(sel);
        if (!hand.isEmpty() && !hs.mayPlace(hand)) return false;

        int closeDelay = isVanillaInventory(screen) ? 1 : 2;

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

    // ── Creative swap (reflection-based, AT makes runtime access work) ──

    private static boolean creativeSwap(Minecraft mc, CreativeModeInventoryScreen cs, int sel) {
        if (mc.gameMode == null) return false;
        Slot hs = cs.getSlotUnderMouse();
        if (hs == null || (!hs.hasItem() && !SwapConfig.emptySlotSwapEnabledRuntime)) return false;

        int hotbarSize = hotbarSize(mc);
        int menuHotbarStart = 36;
        int heldIdx = menuHotbarStart + sel;
        ItemStack handStack = mc.player.getInventory().getItem(sel);
        debugLog("creativeSwap ENTER: sel=" + sel
                + " hand=" + (handStack.isEmpty()?"EMPTY":handStack.getDisplayName().getString())
                + " hs.index=" + hs.index + " csi=" + hs.getContainerSlot());

        // CONTAINER (creative tab items) — AT makes runtime access work
        if (hs.container == getCreativeContainer()) {
            debugLog("  branch=CONTAINER");
            ItemStack item = hs.getItem().copyWithCount(1);
            if (!handStack.isEmpty()) {
                int f = freeSlot(mc);
                if (f >= 0 && f < mc.player.getInventory().items.size()) {
                    mc.player.getInventory().items.set(f, handStack.copy());
                    mc.gameMode.handleCreativeModeItemAdd(handStack.copy(), menuHotbarStart + f);
                }
            }
            if (sel < mc.player.getInventory().items.size()) {
                mc.player.getInventory().items.set(sel, item);
                mc.gameMode.handleCreativeModeItemAdd(item, heldIdx);
            }
            SwapKeyState.closePendingTicks = 1;
            return true;
        }

        // CREATIVE_EQUIP: equipment slots (must be BEFORE SlotWrapper)
        int csi = hs.getContainerSlot();
        if (csi == 45 || (csi >= 5 && csi <= 8)) {
            debugLog("  branch=CREATIVE_EQUIP csi=" + csi + " sel=" + sel);
            if (csi <= 8 && !handStack.isEmpty()) {
                EquipmentSlot expected = csi == 5 ? EquipmentSlot.HEAD :
                        csi == 6 ? EquipmentSlot.CHEST :
                                csi == 7 ? EquipmentSlot.LEGS : EquipmentSlot.FEET;
                EquipmentSlot actual = mc.player.getEquipmentSlotForItem(handStack);
                if (!actual.isArmor() || actual != expected) {
                    debugLog("  armor mismatch -> false");
                    return false;
                }
            }
            mc.gameMode.handleInventoryMouseClick(cs.getMenu().containerId, csi, sel, ClickType.SWAP, mc.player);
            SwapKeyState.closePendingTicks = 1;
            return true;
        }

        // SlotWrapper (hotbar wrappers) — AT makes runtime reflection work
        Slot target = getSlotWrapperTarget(hs);
        if (target != null) {
            int t = target.index;
            debugLog("  branch=SlotWrapper t=" + t + " heldMenuIdx=" + heldIdx);
            if (t != heldIdx) {
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
            debugLog("  SKIP: sameSlot");
            return false;
        }

        // REGULAR fallback
        int c2 = hs.getContainerSlot();
        debugLog("  branch=REGULAR c2=" + c2);
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

    public static boolean isSwapKey(InputConstants.Key key) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return false;
        return key.equals(mc.options.keyInventory.getKey());
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

    // ── Reflection (AT makes these accessible at runtime) ──

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
}
