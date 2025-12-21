package com.pastlands.cosmeticslite.network;

import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.permission.CosmeticsPermissions;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * Client -> Server: Request to open Particle Lab.
 * Server validates permission and responds with OpenParticleLabResponsePacket.
 */
public final class OpenParticleLabPacket {

    public OpenParticleLabPacket() {}

    public static void encode(OpenParticleLabPacket msg, FriendlyByteBuf buf) {
        // no payload
    }

    public static OpenParticleLabPacket decode(FriendlyByteBuf buf) {
        return new OpenParticleLabPacket();
    }

    public static void handle(OpenParticleLabPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            if (!CosmeticsPermissions.canUseParticleLab(player)) {
                player.sendSystemMessage(Component.literal("§c[CosmeticsLite] Particle Lab is staff-only."));
                return;
            }

            CosmeticsLite.NETWORK.send(
                PacketDistributor.PLAYER.with(() -> player),
                new OpenParticleLabResponsePacket()
            );
        });
        ctx.get().setPacketHandled(true);
    }
}
