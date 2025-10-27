package com.pastlands.cosmeticslite;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Server -> Client: synchronize a player's equipped cosmetics per category.
 * Payload:
 *   - int entityId
 *   - Map<String type, String id>
 */
public final class PacketSyncCosmetics {

    private final int entityId;
    final Map<String, String> equippedByType;

    public PacketSyncCosmetics(int entityId, Map<String, String> equippedByType) {
        this.entityId = entityId;
        this.equippedByType = new HashMap<>(equippedByType);
    }

    public static void encode(PacketSyncCosmetics msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entityId);
        buf.writeVarInt(msg.equippedByType.size());
        msg.equippedByType.forEach((t, id) -> { buf.writeUtf(t); buf.writeUtf(id); });
    }

    public static PacketSyncCosmetics decode(FriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        int n = buf.readVarInt();
        Map<String, String> map = new HashMap<>(Math.max(1, n));
        for (int i = 0; i < n; i++) map.put(buf.readUtf(), buf.readUtf());
        return new PacketSyncCosmetics(entityId, map);
    }

    public static void handle(PacketSyncCosmetics msg, Supplier<NetworkEvent.Context> ctxSup) {
        NetworkEvent.Context ctx = ctxSup.get();
        ctx.enqueueWork(() -> {
            // Always update the per-entity cache
            ClientState.applySync(msg.entityId, msg.equippedByType);

            // If this snapshot is for the local player, mirror into the legacy LOCAL cache
            var mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.getId() == msg.entityId) {
                ClientState.applySync(msg.equippedByType);
            }
        });
        ctx.setPacketHandled(true);
    }
}
