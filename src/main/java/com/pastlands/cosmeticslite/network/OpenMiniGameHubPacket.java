package com.pastlands.cosmeticslite.network;

import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.permission.CosmeticsFeature;
import com.pastlands.cosmeticslite.permission.CosmeticsPermissions;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * Client → Server: Request to open mini-game hub.
 * Server validates permission and responds with OpenMiniGameHubResponsePacket.
 */
public final class OpenMiniGameHubPacket {

    public OpenMiniGameHubPacket() {}

    // --------------------------------------------------------------------------------------------
    // Codec
    // --------------------------------------------------------------------------------------------

    public static void encode(OpenMiniGameHubPacket msg, FriendlyByteBuf buf) {
        // No payload
    }

    public static OpenMiniGameHubPacket decode(FriendlyByteBuf buf) {
        return new OpenMiniGameHubPacket();
    }

    // --------------------------------------------------------------------------------------------
    // Handler (Server-side)
    // --------------------------------------------------------------------------------------------

    public static void handle(OpenMiniGameHubPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // Check permission
            if (!CosmeticsPermissions.canUseFeature(player, CosmeticsFeature.MINI_GAMES)) {
                player.sendSystemMessage(Component.literal("§c[CosmeticsLite] You don't have permission to use mini-games."));
                return;
            }

            // Send response packet to open the hub
            CosmeticsLite.NETWORK.send(
                PacketDistributor.PLAYER.with(() -> player),
                new OpenMiniGameHubResponsePacket()
            );
        });
        ctx.get().setPacketHandled(true);
    }
}

