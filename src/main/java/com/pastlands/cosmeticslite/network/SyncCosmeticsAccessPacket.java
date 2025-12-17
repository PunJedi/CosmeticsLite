// src/main/java/com/pastlands/cosmeticslite/network/SyncCosmeticsAccessPacket.java
package com.pastlands.cosmeticslite.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class SyncCosmeticsAccessPacket {
    private final boolean unlocked;

    public SyncCosmeticsAccessPacket(boolean unlocked) {
        this.unlocked = unlocked;
    }

    // Encode to buffer
    public static void encode(SyncCosmeticsAccessPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.unlocked);
    }

    // Decode from buffer
    public static SyncCosmeticsAccessPacket decode(FriendlyByteBuf buf) {
        return new SyncCosmeticsAccessPacket(buf.readBoolean());
    }

    // Handle packet on client
    public static void handle(SyncCosmeticsAccessPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // Update client-side cosmetics state
            com.pastlands.cosmeticslite.client.CosmeticsClientState.setUnlocked(msg.unlocked);
        });
        ctx.get().setPacketHandled(true);
    }
}
