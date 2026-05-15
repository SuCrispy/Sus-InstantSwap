package com.susinstantswap.client;

import com.mojang.blaze3d.platform.InputConstants;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
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
import net.minecraft.world.level.GameType;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

public class InstantSwapClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(InstantSwapClient.class);
    private static KeyMapping SWAP_KEY;
    private static boolean openedByUs;

    // 反射缓存：AbstractContainerScreen 中悬停槽位的 Field
    private static Field hoveredSlotField = null;
    private static boolean hoveredSlotFieldResolved = false;

    public static void init() {
        LOGGER.info("[SusInstantSwap] 初始化客户端交换逻辑 (Fabric)...");

        SWAP_KEY = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.susinstantswap.swap",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_ALT,
                "key.categories.susinstantswap"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(InstantSwapClient::onClientTick);

        LOGGER.info("[SusInstantSwap] 按键绑定和 Tick 事件已注册 (Fabric)");
    }

    // ── 按键检测 ──

    private static boolean isKeyPhysicallyDown(Minecraft mc) {
        long handle = mc.getWindow().getWindow();
        InputConstants.Key key = SWAP_KEY.getDefaultKey();
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(handle, key.getValue()) == GLFW.GLFW_PRESS;
        }
        return InputConstants.isKeyDown(handle, key.getValue());
    }

    // ── 悬停槽位：反射查找，不依赖单一映射名称 ──

    private static Slot getHoveredSlot(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) return null;
        return getHoveredSlotReflective(containerScreen);
    }

    private static Slot getHoveredSlotReflective(AbstractContainerScreen<?> screen) {
        if (!hoveredSlotFieldResolved) {
            hoveredSlotFieldResolved = true;
            // 策略1：按已知映射名称尝试
            for (String name : new String[]{"hoveredSlot", "focusedSlot"}) {
                try {
                    Field f = AbstractContainerScreen.class.getDeclaredField(name);
                    f.setAccessible(true);
                    hoveredSlotField = f;
                    LOGGER.info("[SusInstantSwap] 悬停槽位: {} (精确匹配)", name);
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
            // 策略2：遍历所有 Slot 类型字段，优先非 private（AbstractContainerScreen 中仅 hoveredSlot 是 protected）
            if (hoveredSlotField == null) {
                for (Field field : AbstractContainerScreen.class.getDeclaredFields()) {
                    if (!Slot.class.isAssignableFrom(field.getType())) continue;
                    int mod = field.getModifiers();
                    if (java.lang.reflect.Modifier.isStatic(mod)) continue; // 跳过静态
                    if (java.lang.reflect.Modifier.isPrivate(mod)) continue; // 跳过 private（clickedSlot 等）
                    field.setAccessible(true);
                    hoveredSlotField = field;
                    LOGGER.info("[SusInstantSwap] 悬停槽位: {} (类型匹配, modifiers=0x{})",
                            field.getName(), Integer.toHexString(mod));
                    break;
                }
            }
            if (hoveredSlotField == null) {
                LOGGER.error("[SusInstantSwap] 未找到悬停槽位字段！AbstractContainerScreen 的所有 Slot 字段:");
                for (Field f : AbstractContainerScreen.class.getDeclaredFields()) {
                    if (Slot.class.isAssignableFrom(f.getType())) {
                        LOGGER.error("  {} (modifiers=0x{})", f.getName(), Integer.toHexString(f.getModifiers()));
                    }
                }
            }
        }
        if (hoveredSlotField == null) return null;
        try {
            return (Slot) hoveredSlotField.get(screen);
        } catch (Exception e) {
            return null;
        }
    }

    // ── 每帧状态机 ──

    private static void onClientTick(Minecraft mc) {
        if (mc.player == null || mc.gameMode == null) return;

        GameType mode = mc.gameMode.getPlayerMode();
        if (mode != GameType.SURVIVAL && mode != GameType.CREATIVE && mode != GameType.ADVENTURE) {
            return;
        }

        boolean down = isKeyPhysicallyDown(mc);
        boolean creative = mc.gameMode.hasInfiniteItems();

        if (down && !openedByUs && mc.screen == null) {
            LOGGER.info("[SusInstantSwap] 按键按下，打开{}物品栏", creative ? "创造模式" : "生存模式");
            mc.setScreen(createScreen(mc, creative));
            openedByUs = true;
        }

        if (!down && openedByUs) {
            LOGGER.info("[SusInstantSwap] 按键释放，执行交换");
            if (creative) {
                performCreativeSwap(mc);
            } else {
                performSurvivalSwap(mc);
            }
            mc.player.closeContainer();
            openedByUs = false;
        }
    }

    private static Screen createScreen(Minecraft mc, boolean creative) {
        if (creative) {
            // CreativeModeInventoryScreen 1.21.1 构造: (Player, FeatureFlagSet, boolean)
            return new CreativeModeInventoryScreen(
                    mc.player,
                    mc.player.connection.enabledFeatures(),
                    false);
        }
        return new InventoryScreen(mc.player);
    }

    // ── 生存模式交换 ──

    private static void performSurvivalSwap(Minecraft mc) {
        Screen screen = mc.screen;
        if (!(screen instanceof InventoryScreen)) {
            LOGGER.warn("[SusInstantSwap] 生存交换失败: screen={}", screen);
            return;
        }

        Slot hovered = getHoveredSlot(screen);
        if (hovered == null || !hovered.hasItem()) {
            LOGGER.warn("[SusInstantSwap] 生存交换失败: 无悬停物品");
            return;
        }

        int slotIndex = hovered.index;
        if (slotIndex < 9 || slotIndex > 44) {
            LOGGER.warn("[SusInstantSwap] 生存交换失败: slotIndex={}", slotIndex);
            return;
        }

        int hotbar = mc.player.getInventory().selected;
        if (slotIndex == hotbar + 36) {
            LOGGER.warn("[SusInstantSwap] 生存交换失败: 目标=当前手持");
            return;
        }

        int containerId = mc.player.inventoryMenu.containerId;
        int stateId = mc.player.inventoryMenu.getStateId();
        Int2ObjectOpenHashMap<ItemStack> changedSlots = new Int2ObjectOpenHashMap<>();
        ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                containerId, stateId, slotIndex, hotbar,
                ClickType.SWAP, ItemStack.EMPTY, changedSlots);

        if (mc.getConnection() != null) {
            mc.getConnection().send(packet);
            mc.player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f);
            LOGGER.info("[SusInstantSwap] 生存交换: {}({}) <-> 手持快捷栏{}", slotIndex, hovered.getItem().getDisplayName().getString(), hotbar);
        }
    }

    // ── 创造模式交换 ──

    /**
     * 创造模式交换逻辑：
     *   标签页物品 (container != player inventory) → handleCreativeModeItemAdd 拿一个到快捷栏
     *   玩家背包槽位 (container == player inventory, index 9-44) → 双向交换
     *   装备/副手 (index 5-8, 45) → 不交换
     *
     * 创造物品栏中的玩家背包槽位被 SlotWrapper 包装，
     * 需要反射取出 wrapper 内部的真实 Slot 以获取正确的 index。
     */
    private static void performCreativeSwap(Minecraft mc) {
        Screen screen = mc.screen;
        if (!(screen instanceof CreativeModeInventoryScreen)) {
            LOGGER.warn("[SusInstantSwap] 创造交换失败: screen={}", screen);
            return;
        }

        Slot hovered = getHoveredSlot(screen);
        if (hovered == null) {
            LOGGER.warn("[SusInstantSwap] 创造交换失败: hovered=null");
            return;
        }
        if (!hovered.hasItem()) {
            LOGGER.warn("[SusInstantSwap] 创造交换失败: 悬停槽位无物品 index={}", hovered.index);
            return;
        }

        int selected = mc.player.getInventory().selected;
        int heldSlotIndex = selected + 36;

        LOGGER.info("[SusInstantSwap] 创造交换: slotClass={}, hoveredIndex={}, container={}, item={}",
                hovered.getClass().getSimpleName(), hovered.index,
                hovered.container.getClass().getSimpleName(),
                hovered.getItem().getDisplayName().getString());

        // 尝试解包 SlotWrapper → 取真实 Slot
        // (非 SlotWrapper 会返回 null，后续用 hovered 本身的 index)
        Slot realSlot = unwrapSlot(hovered);
        int realIndex = (realSlot != null) ? realSlot.index : hovered.index;

        LOGGER.info("[SusInstantSwap] 创造交换: realIndex={}, isWrapper={}", realIndex, realSlot != null);

        boolean didSwap = false;

        if (!isPlayerInventorySlot(hovered, mc)) {
            // ── 创造标签页物品：container != 玩家背包 ──
            ItemStack item = hovered.getItem().copyWithCount(1);
            mc.gameMode.handleCreativeModeItemAdd(item, heldSlotIndex);
            didSwap = true;
            LOGGER.info("[SusInstantSwap] 创造交换(标签页) -> 快捷栏{}", selected);

        } else if (realIndex >= 9 && realIndex <= 44 && realIndex != heldSlotIndex) {
            // ── 玩家背包/快捷栏：双向交换 (excludes armor 5-8, offhand 45) ──
            ItemStack targetItem = mc.player.inventoryMenu.getSlot(realIndex).getItem().copy();
            ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
            mc.gameMode.handleCreativeModeItemAdd(targetItem, heldSlotIndex);
            if (!heldItem.isEmpty()) {
                mc.gameMode.handleCreativeModeItemAdd(heldItem, realIndex);
            }
            didSwap = true;
            LOGGER.info("[SusInstantSwap] 创造交换(背包): {} <-> 快捷栏{}", realIndex, selected);

        } else {
            LOGGER.warn("[SusInstantSwap] 创造交换跳过: realIndex={} 不在可交换范围(9-44)", realIndex);
        }

        if (didSwap) {
            mc.player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f);
        }
    }

    /** 判断槽位是否属于玩家背包（container 引用 == player.inventory） */
    private static boolean isPlayerInventorySlot(Slot slot, Minecraft mc) {
        return slot.container == mc.player.getInventory();
    }

    /**
     * 解包 SlotWrapper：如果 slot 内部包含另一个 Slot 字段，取出真实 Slot。
     * 非 SlotWrapper（即普通 Slot 实例）返回 null。
     *
     * 不缓存 Field：不同 slot 实例可能属于不同内部类。
     */
    private static Slot unwrapSlot(Slot slot) {
        // 基础 Slot 类没有包装字段
        if (slot.getClass() == Slot.class) return null;

        try {
            for (Field field : slot.getClass().getDeclaredFields()) {
                if (Slot.class.isAssignableFrom(field.getType())
                        && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    return (Slot) field.get(slot);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[SusInstantSwap] 解包 Slot 失败: {}", e.getMessage());
        }
        return null;
    }
}
