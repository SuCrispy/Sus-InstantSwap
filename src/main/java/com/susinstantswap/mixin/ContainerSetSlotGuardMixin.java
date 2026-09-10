package com.susinstantswap.mixin;

import com.susinstantswap.SwapLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Defends against an out-of-range {@code ClientboundContainerSetSlot} packet that
 * crashes the client with "Index N out of bounds".
 */
@Mixin(value = ClientPacketListener.class, remap = false)
public class ContainerSetSlotGuardMixin {

    @Inject(method = "handleContainerSetSlot", at = @At("HEAD"), cancellable = true)
    private void susinstantswap$guardSetSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        int cid = packet.getContainerId();
        int slot = packet.getSlot();
        AbstractContainerMenu live = mc.player.containerMenu;
        if (live != null && cid == live.containerId
                && (slot < 0 || slot >= live.slots.size())) {
            SwapLog.warn("Dropped out-of-range ContainerSetSlot: cid={} slot={} openMenuSize={} "
                            + "(extended inventory-menu / creative-menu containerId clash)",
                    cid, slot, live.slots.size());
            ci.cancel();
        }
    }
}
