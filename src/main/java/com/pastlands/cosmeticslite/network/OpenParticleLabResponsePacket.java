package com.pastlands.cosmeticslite.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> Client: Permission granted, open Particle Lab.
 */
public final class OpenParticleLabResponsePacket {

    public OpenParticleLabResponsePacket() {}

    public static void encode(OpenParticleLabResponsePacket msg, FriendlyByteBuf buf) {
        // no payload
    }

    public static OpenParticleLabResponsePacket decode(FriendlyByteBuf buf) {
        return new OpenParticleLabResponsePacket();
    }

    public static void handle(OpenParticleLabResponsePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClient())
        );
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient() {
        Minecraft mc = Minecraft.getInstance();
        // parent the lab to the current screen (cosmetics menu) if present
        mc.setScreen(new com.pastlands.cosmeticslite.client.screen.ParticleLabScreen());
    }
}
