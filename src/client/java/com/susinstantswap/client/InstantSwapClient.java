package com.susinstantswap.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.susinstantswap.config.SwapConfig;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
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
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.EquipmentSlot;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.lang.reflect.Field;

import com.susinstantswap.mixin.AbstractContainerScreenAccessor;
import com.susinstantswap.mixin.KeyMappingAccessor;

/**
 * Sus-InstantSwap v2.0 — Fabric edition, ported to MC 26.1.
 * Uses Fabric API callbacks + Accessor mixins instead of NeoForge event bus.
 *
 * <p>MC 26.1 API changes from 1.21.1:</p>
 * <ul>
 *   <li>KeyBindingHelper → KeyMappingHelper</li>
 *   <li>ClickType.SWAP → ContainerInput.SWAP</li>
 *   <li>ItemStack.EMPTY → HashedStack.EMPTY</li>
 *   <li>mc.getWindow().getWindow() → mc.getWindow().handle()</li>
 *   <li>mc.player.playNotifySound → mc.player.playSound</li>
 *   <li>Inventory.items.set/get/size → setItem/getItem/getContainerSize</li>
 *   <li>Inventory.selected → getSelectedSlot()</li>
 * </ul>
 */
public class InstantSwapClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static KeyMapping SWAP_IN_GUI_KEY;
    private static SwapConfig config;

    enum SwapState { IDLE, WATCHING, LONG_PRESS }
    private static SwapState state = SwapState.IDLE;

    private static boolean configLogged = false;

    // ── Tooltip suppression after cursor reposition ──
    private static boolean suppressNextTooltip;
    private static int suppressTooltipFrames;
    private static Screen lastTooltipSuppressScreen;

    // ── Right-click container tracking ──
    private static boolean screenOpenedByInteract;
    // Track E-key opens (vs tab switches in creative) for AFTER_INIT reposition
    private static boolean screenOpenedByKey;

    public static boolean shouldSuppressTooltip() {
        if (!suppressNextTooltip) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != lastTooltipSuppressScreen) {
            suppressNextTooltip = false;
            suppressTooltipFrames = 0;
            return false;
        }
        suppressNextTooltip = false;
        return true;
    }

    // ── Reflection cache for CreativeModeInventoryScreen internals ──
    private static Object CREATIVE_CONTAINER;
    private static boolean creativeContainerInit;

    // ── Init ──

    public static SwapConfig getConfig() { return config; }

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(net.minecraft.resources.Identifier.fromNamespaceAndPath("susinstantswap", "main"));

    public static void init(SwapConfig cfg) {
        config = cfg;
        SWAP_IN_GUI_KEY = new KeyMapping("key.susinstantswap.swap_in_gui",
                InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(),
                CATEGORY);
        KeyMappingHelper.registerKeyMapping(SWAP_IN_GUI_KEY);

        ClientTickEvents.END_CLIENT_TICK.register(InstantSwapClient::onClientTick);
        ScreenEvents.AFTER_INIT.register(InstantSwapClient::onScreenInitPost);

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
        if (suppressTooltipFrames > 0 && --suppressTooltipFrames == 0)
            suppressNextTooltip = false;

        if (!configLogged) {
            configLogged = true;
            LOGGER.info("[SusInstantSwap] Config: mod={} threshold={}ms sound={} guiSwap={} emptySwap={} debug={} mouse={}",
                    config.modEnabled, config.holdThresholdMs, config.soundEnabled,
                    config.guiSwapEnabled, config.emptySlotSwapEnabled,
                    config.debug, config.mouseReposition);
        }

        // Sync master switch to shared state
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

        // ── IDLE: wait for screen after E press ──
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

    public static boolean handleKeyInput(long window, int action, net.minecraft.client.input.KeyEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return false;
        if (!SwapKeyState.modEnabled) return false;

        int key = event.key();
        boolean keyDown = (action == GLFW.GLFW_PRESS || action == GLFW.GLFW_REPEAT);
        boolean isRelease = (action == GLFW.GLFW_RELEASE);
        boolean isInventoryKey = isInventoryKeyByCode(mc, key);
        boolean isGuiSwapKey = SWAP_IN_GUI_KEY.isUnbound() ? false : isGuiSwapKeyByCode(key);

        // ── E key press/release tracking for long-press state machine ──
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
                return true;
            } else if (isRelease) {
                SwapKeyState.inventoryKeyHeld = false;
                SwapKeyState.longPressConfirmed = false;
            }
        }

        // ── EditBox protection ──
        if (keyDown && isInventoryKey && mc.screen != null && hasEditBoxFocus(mc.screen)) {
            while (mc.options.keyInventory.consumeClick()) {}
            if (mc.screen instanceof AbstractContainerScreen) {
                var menu = mc.player.containerMenu;
                if (menu != null && menu.getSlot(0).hasItem()) return false;
            } else return false;
        }

        // ── GUI swap with bound custom key ──
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

    private static Slot getHoveredSlotSafe(AbstractContainerScreen<?> screen) {
        // Field reflection — field names survive Fabric Loom remapping
        try {
            java.lang.reflect.Field f = AbstractContainerScreen.class.getDeclaredField("hoveredSlot");
            f.setAccessible(true);
            return (Slot) f.get(screen);
        } catch (Exception ignored) {}
        // Fallback: Mixin accessor
        return ((AbstractContainerScreenAccessor) screen).getHoveredSlot();
    }

    private static boolean performSwap(Minecraft mc) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) return false;
        Slot hs = getHoveredSlotSafe(screen);

        // Fallback: find slot manually for creative screen hover
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

        int sel = mc.player.getInventory().getSelectedSlot();

        if (!hs.hasItem() && mc.player.getInventory().getItem(sel).isEmpty()) return false;

        // ── Creative inventory → special handling ──
        if (screen instanceof CreativeModeInventoryScreen cs) {
            if (creativeSwap(mc, cs, sel, hs)) { playSwapSound(mc); return true; }
            return false;
        }

        // Player inventory → restrict to backpack + hotbar
        if (screen instanceof InventoryScreen && (!isPlayerInventorySlot(hs) || hs.index == hotbarMenuSlot(sel)))
            return false;

        // Slot validation
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
        Int2ObjectOpenHashMap<HashedStack> cs = new Int2ObjectOpenHashMap<>();
        mc.getConnection().send(new ServerboundContainerClickPacket(
                s.getMenu().containerId, s.getMenu().getStateId(),
                (byte) slotIdx, (byte) hotbar, ContainerInput.SWAP, cs, HashedStack.EMPTY));
        return true;
    }

    // ── Creative mode swap (ported from NF v2.0.0) ──
    // Branch order:
    //   1. CONTAINER (creative tab item grid)
    //   2. CREATIVE_EQUIP (armor/offhand, isInventoryOpen=true)
    //   3. SlotWrapper (non-equipment player inventory)
    //   4. REGULAR (fallback hotbar slots)

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

        // Creative equipment: csi=5-8 (armor) or 45 (offhand)
        int csi = hs.getContainerSlot();
        if (cs.isInventoryOpen() && (csi == 45 || (csi >= 5 && csi <= 8))) {
            debugLog("  branch=CREATIVE_EQUIP csi=" + csi + " sel=" + sel);
            if (!handStack.isEmpty() && !hs.mayPlace(handStack)) {
                debugLog("  mayPlace rejected -> false");
                return false;
            }
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
            mc.getConnection().send(new ServerboundContainerClickPacket(
                cs.getMenu().containerId, cs.getMenu().getStateId(),
                (byte) csi, (byte) sel, ContainerInput.SWAP,
                new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY));
            debugLog("  ContainerClickPacket(slot=" + csi + " hotbar=" + sel + " SWAP)");
            SwapKeyState.closePendingTicks = 1;
            return true;
        }

        // Player inventory slots on inventory page
        if (cs.isInventoryOpen() && isPlayerInventorySlot(hs) && csi != heldIdx) {
            debugLog("  branch=PLAYER_INV csi=" + csi + " sel=" + sel);
            if (!handStack.isEmpty() && !hs.mayPlace(handStack)) {
                debugLog("  mayPlace rejected -> false");
                return false;
            }
            mc.getConnection().send(new ServerboundContainerClickPacket(
                cs.getMenu().containerId, cs.getMenu().getStateId(),
                (byte) csi, (byte) sel, ContainerInput.SWAP,
                new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY));
            debugLog("  ContainerClickPacket(slot=" + csi + " hotbar=" + sel + " SWAP)");
            SwapKeyState.closePendingTicks = 1;
            return true;
        }

        // SlotWrapper — hotbar slots on creative item tabs
        if (isSlotWrapper(hs)) {
            int t = getSlotWrapperTargetIndex(hs);
            debugLog("  branch=SlotWrapper t=" + t + " heldMenuIdx=" + heldIdx);
            if (t >= 0 && isPlayerInventorySlot(hs) && t != heldIdx) {
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
            debugLog("  SKIP: sameSlot=" + (t==heldIdx) + " isPlayerInv=" + isPlayerInventorySlot(hs));
            return false;
        }

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

    // ── Reflection helpers for CreativeModeInventoryScreen internals ──

    private static final java.util.Map<Class<?>, Field> targetFieldCache = new java.util.HashMap<>();

    private static Object getCreativeContainer() {
        if (!creativeContainerInit) {
            creativeContainerInit = true;
            try {
                Field f = CreativeModeInventoryScreen.class.getDeclaredField("CONTAINER");
                f.setAccessible(true);
                CREATIVE_CONTAINER = f.get(null);
            } catch (NoSuchFieldException e1) {
                try {
                    for (Field f : CreativeModeInventoryScreen.class.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                                && net.minecraft.world.Container.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            CREATIVE_CONTAINER = f.get(null);
                            break;
                        }
                    }
                } catch (Exception ignored) {}
            } catch (Exception ignored) {}
        }
        return CREATIVE_CONTAINER;
    }

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
        return slot.getClass() != Slot.class && findTargetField(slot.getClass()) != null;
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
        return mc.player.getInventory().getContainerSize() - 27;
    }

    private static int freeSlot(Minecraft mc) {
        var inv = mc.player.getInventory();
        int size = 36; // hotbar(9) + main(27), excludes armor/offhand
        int hbSize = hotbarSize(mc);
        int sel = inv.getSelectedSlot();
        for (int i = 0; i < hbSize; i++)
            if (i != sel && inv.getItem(i).isEmpty()) return i;
        for (int i = hbSize; i < size; i++)
            if (inv.getItem(i).isEmpty()) return i;
        return -1;
    }

    // ── Key detection ──

    private static boolean isInventoryKeyPhysicallyDown(Minecraft mc) {
        InputConstants.Key key = ((KeyMappingAccessor) (Object) mc.options.keyInventory).getKey();
        if (key.getType() != InputConstants.Type.KEYSYM) return false;
        return GLFW.glfwGetKey(mc.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
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
        long h = mc.getWindow().handle();
        double gs = mc.getWindow().getGuiScale();
        // Field reflection — field names survive Fabric Loom remapping
        try {
            java.lang.reflect.Field lf = AbstractContainerScreen.class.getDeclaredField("leftPos");
            java.lang.reflect.Field tf = AbstractContainerScreen.class.getDeclaredField("topPos");
            java.lang.reflect.Field wf = AbstractContainerScreen.class.getDeclaredField("imageWidth");
            java.lang.reflect.Field hf = AbstractContainerScreen.class.getDeclaredField("imageHeight");
            lf.setAccessible(true); tf.setAccessible(true);
            wf.setAccessible(true); hf.setAccessible(true);
            int left = (int) lf.get(s), top = (int) tf.get(s);
            int w = (int) wf.get(s), ht = (int) hf.get(s);
            GLFW.glfwSetCursorPos(h,
                    (int) ((left + w) * gs) - 5,
                    (int) ((top + ht) * gs) - 5);
        } catch (Exception ignored) {
            // Fallback: Mixin accessor
            AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) s;
            GLFW.glfwSetCursorPos(h,
                    (int) ((acc.getLeftPos() + acc.getImageWidth()) * gs) - 5,
                    (int) ((acc.getTopPos() + acc.getImageHeight()) * gs) - 5);
        }
        suppressNextTooltip = true;
        suppressTooltipFrames = 2;
        lastTooltipSuppressScreen = s;
    }

    private static void playSwapSound(Minecraft mc) {
        if (!config.soundEnabled || mc.player == null) return;
        mc.player.playSound(SoundEvents.ITEM_PICKUP, 0.8f, 1.0f);
    }

    private static void debugLog(String msg) {
        if (config.debug) LOGGER.info("[SusInstantSwap] {}", msg);
    }
}
