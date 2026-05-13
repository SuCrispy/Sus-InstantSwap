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

public class InstantSwapClient {

    private static KeyMapping SWAP_KEY;
    private static boolean openedByUs;

    public static void init() {
        SWAP_KEY = new KeyMapping("key.susinstantswap.swap",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT,
                "key.categories.susinstantswap");
        NeoForge.EVENT_BUS.register(InstantSwapClient.class);
    }

    public static void registerKey(RegisterKeyMappingsEvent event) {
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
            mc.setScreen(new InventoryScreen(mc.player));
            openedByUs = true;
        }

        if (!down && openedByUs) {
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
            return;
        }

        Slot hoveredSlot = inventoryScreen.getSlotUnderMouse();
        if (hoveredSlot == null) {
            return;
        }

        // 悬停槽位必须有物品才允许交换（不与空格交换，主手可以为空）
        if (!hoveredSlot.hasItem()) {
            return;
        }

        int hoveredIndex = hoveredSlot.index;
        int selectedHotbar = mc.player.getInventory().selected;
        int hotbarMenuSlot = selectedHotbar + 36;

        if (!isAllowedMenuSlot(hoveredIndex) || hoveredIndex == hotbarMenuSlot) {
            return;
        }

        int containerId = mc.player.inventoryMenu.containerId;
        int stateId = mc.player.inventoryMenu.getStateId();
        Int2ObjectOpenHashMap<ItemStack> changedSlots = new Int2ObjectOpenHashMap<>();
        ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                containerId, stateId, hoveredIndex, selectedHotbar,
                ClickType.SWAP, ItemStack.EMPTY, changedSlots);
        mc.getConnection().send(packet);
        mc.player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f);
    }

    private static void performCreativeSwap(Minecraft mc) {
        if (!(mc.screen instanceof CreativeModeInventoryScreen creativeScreen)) {
            return;
        }

        Slot hoveredSlot = creativeScreen.getSlotUnderMouse();
        if (hoveredSlot == null || !hoveredSlot.hasItem()) {
            return;
        }

        int selected = mc.player.getInventory().selected;
        int heldSlotIndex = selected + 36;
        boolean didSwap = false;

        if (hoveredSlot.container == CreativeModeInventoryScreen.CONTAINER) {
            // 创造模式物品栏：拿一个到手上
            ItemStack item = hoveredSlot.getItem().copyWithCount(1);
            mc.gameMode.handleCreativeModeItemAdd(item, heldSlotIndex);
            didSwap = true;

        } else if (hoveredSlot instanceof CreativeModeInventoryScreen.SlotWrapper wrapper) {
            // 背包/装备区（SlotWrapper 包装）
            int targetMenuSlot = wrapper.target.index;
            if (isAllowedMenuSlot(targetMenuSlot) && targetMenuSlot != heldSlotIndex) {
                ItemStack targetItem = mc.player.inventoryMenu.getSlot(targetMenuSlot).getItem().copy();
                ItemStack heldItem = mc.player.inventoryMenu.getSlot(heldSlotIndex).getItem().copy();
                mc.gameMode.handleCreativeModeItemAdd(targetItem, heldSlotIndex);
                mc.gameMode.handleCreativeModeItemAdd(heldItem, targetMenuSlot);
                didSwap = true;
            }

        } else {
            // 快捷栏区域
            int containerSlot = hoveredSlot.getContainerSlot();
            if (containerSlot >= 0 && containerSlot <= 8) {
                int hotbarMenuSlot = containerSlot + 36;
                if (hotbarMenuSlot != heldSlotIndex) {
                    ItemStack hotbarItem = mc.player.getInventory().getItem(containerSlot).copy();
                    ItemStack heldItem = mc.player.getInventory().getItem(selected).copy();
                    mc.gameMode.handleCreativeModeItemAdd(hotbarItem, heldSlotIndex);
                    mc.gameMode.handleCreativeModeItemAdd(heldItem, hotbarMenuSlot);
                    didSwap = true;
                }
            }
        }

        if (didSwap) {
            mc.player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.8f, 1.0f);
        }
    }
}
