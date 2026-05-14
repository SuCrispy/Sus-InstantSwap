package com.susinstantswap.client;

import com.mojang.blaze3d.platform.InputConstants;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

public class InstantSwapClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(InstantSwapClient.class);
    private static KeyMapping SWAP_KEY;
    private static boolean openedByUs;

    public static void init() {
        LOGGER.info("[SusInstantSwap] 初始化客户端交换逻辑...");
        SWAP_KEY = new KeyMapping("key.susinstantswap.swap",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT,
                "key.categories.susinstantswap");
        NeoForge.EVENT_BUS.register(InstantSwapClient.class);
        LOGGER.info("[SusInstantSwap] 已注册客户端事件到 NeoForge 总线");
    }

    public static void registerKey(RegisterKeyMappingsEvent event) {
        LOGGER.info("[SusInstantSwap] 注册交换按键到按键映射表");
        event.register(SWAP_KEY);
    }

    private static boolean isKeyPhysicallyDown(Minecraft mc) {
        long handle = mc.getWindow().getWindow();
        InputConstants.Key key = SWAP_KEY.getKey();
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(handle, key.getValue()) == 1;
        }
        return InputConstants.isKeyDown(handle, key.getValue());
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) {
            return;
        }

        boolean creative = mc.gameMode.hasInfiniteItems();
        boolean survival = mc.gameMode.getPlayerMode() == GameType.SURVIVAL
                || mc.gameMode.getPlayerMode() == GameType.ADVENTURE;

        if (!creative && !survival) {
            return;
        }

        boolean down = isKeyPhysicallyDown(mc);

        if (down && !openedByUs && mc.screen == null) {
            LOGGER.info("[SusInstantSwap] 交换按键按下，打开物品栏");
            mc.setScreen(new InventoryScreen(mc.player));
            openedByUs = true;
        }

        if (!down && openedByUs) {
            LOGGER.info("[SusInstantSwap] 交换按键释放，执行{}交换",
                    creative ? "创造模式" : "生存模式");
            if (creative) {
                performCreativeSwap(mc);
            } else {
                performSurvivalSwap(mc);
            }
            mc.player.closeContainer();
            openedByUs = false;
        }
    }

    private static boolean isAllowedMenuSlot(int menuSlot) {
        return menuSlot >= 9 && menuSlot <= 44;
    }

    private static void performSurvivalSwap(Minecraft mc) {
        if (!(mc.screen instanceof InventoryScreen inventoryScreen)) {
            LOGGER.warn("[SusInstantSwap] 生存模式交换失败: 当前屏幕不是物品栏");
            return;
        }

        Slot hoveredSlot = inventoryScreen.getSlotUnderMouse();
        if (hoveredSlot == null) {
            LOGGER.warn("[SusInstantSwap] 生存模式交换失败: 未悬停在任何槽位");
            return;
        }

        if (!hoveredSlot.hasItem()) {
            LOGGER.warn("[SusInstantSwap] 生存模式交换失败: 悬停槽位无物品");
            return;
        }

        int hoveredIndex = hoveredSlot.index;
        int selectedHotbar = 0;
        if (mc.player != null) {
            selectedHotbar = mc.player.getInventory().selected;
        }
        int hotbarMenuSlot = selectedHotbar + 36;

        if (!isAllowedMenuSlot(hoveredIndex) || hoveredIndex == hotbarMenuSlot) {
            LOGGER.warn("[SusInstantSwap] 生存模式交换失败: 不允许的槽位 (悬停={}, 快捷栏={})",
                    hoveredIndex, hotbarMenuSlot);
            return;
        }

        int containerId = 0;
        if (mc.player != null) {
            containerId = mc.player.inventoryMenu.containerId;
        }
        int stateId = 0;
        if (mc.player != null) {
            stateId = mc.player.inventoryMenu.getStateId();
        }
        Int2ObjectOpenHashMap<ItemStack> changedSlots = new Int2ObjectOpenHashMap<>();
        ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                containerId, stateId, hoveredIndex, selectedHotbar,
                ClickType.SWAP, ItemStack.EMPTY, changedSlots);
        Objects.requireNonNull(mc.getConnection()).send(packet);
        mc.player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f);
        LOGGER.info("[SusInstantSwap] 生存模式交换完成: 槽位{} <-> 快捷栏{}",
                hoveredIndex, selectedHotbar);
    }

    private static void performCreativeSwap(Minecraft mc) {
        if (!(mc.screen instanceof CreativeModeInventoryScreen creativeScreen)) {
            LOGGER.warn("[SusInstantSwap] 创造模式交换失败: 当前屏幕不是创造模式物品栏");
            return;
        }

        Slot hoveredSlot = creativeScreen.getSlotUnderMouse();
        if (hoveredSlot == null || !hoveredSlot.hasItem()) {
            LOGGER.warn("[SusInstantSwap] 创造模式交换失败: 悬停槽位无物品");
            return;
        }

        int selected = 0;
        if (mc.player != null) {
            selected = mc.player.getInventory().selected;
        }
        int heldSlotIndex = selected + 36;
        boolean didSwap = false;

        if (hoveredSlot.container == CreativeModeInventoryScreen.CONTAINER) {
            ItemStack item = hoveredSlot.getItem().copyWithCount(1);
            if (mc.gameMode != null) {
                mc.gameMode.handleCreativeModeItemAdd(item, heldSlotIndex);
            }
            didSwap = true;
            LOGGER.info("[SusInstantSwap] 创造模式交换: 从创造物品栏拿取物品到快捷栏{}", selected);

        } else if (hoveredSlot instanceof CreativeModeInventoryScreen.SlotWrapper wrapper) {
            int targetMenuSlot = wrapper.target.index;
            if (isAllowedMenuSlot(targetMenuSlot) && targetMenuSlot != heldSlotIndex) {
                ItemStack targetItem = null;
                if (mc.player != null) {
                    targetItem = mc.player.inventoryMenu.getSlot(targetMenuSlot).getItem().copy();
                }
                ItemStack heldItem = null;
                if (mc.player != null) {
                    heldItem = mc.player.inventoryMenu.getSlot(heldSlotIndex).getItem().copy();
                }
                if (mc.gameMode != null) {
                    mc.gameMode.handleCreativeModeItemAdd(targetItem, heldSlotIndex);
                }
                if (mc.gameMode != null) {
                    mc.gameMode.handleCreativeModeItemAdd(heldItem, targetMenuSlot);
                }
                didSwap = true;
                LOGGER.info("[SusInstantSwap] 创造模式交换: 背包槽位{} <-> 快捷栏{}",
                        targetMenuSlot, selected);
            }
        } else {
            int containerSlot = hoveredSlot.getContainerSlot();
            if (containerSlot >= 0 && containerSlot <= 8) {
                int hotbarMenuSlot = containerSlot + 36;
                if (hotbarMenuSlot != heldSlotIndex) {
                    ItemStack hotbarItem = mc.player.getInventory().getItem(containerSlot).copy();
                    ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
                    mc.gameMode.handleCreativeModeItemAdd(hotbarItem, heldSlotIndex);
                    mc.gameMode.handleCreativeModeItemAdd(heldItem, hotbarMenuSlot);
                    didSwap = true;
                    LOGGER.info("[SusInstantSwap] 创造模式交换: 快捷栏槽位{} <-> 快捷栏{}",
                            containerSlot, selected);
                }
            }
        }

        if (didSwap) {
            if (mc.player != null) {
                mc.player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f);
            }
        }
    }
}