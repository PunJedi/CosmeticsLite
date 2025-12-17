package com.pastlands.cosmeticslite.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → Client: Permission granted, open mini-game hub.
 */
public final class OpenMiniGameHubResponsePacket {

    public OpenMiniGameHubResponsePacket() {}

    // --------------------------------------------------------------------------------------------
    // Codec
    // --------------------------------------------------------------------------------------------

    public static void encode(OpenMiniGameHubResponsePacket msg, FriendlyByteBuf buf) {
        // No payload
    }

    public static OpenMiniGameHubResponsePacket decode(FriendlyByteBuf buf) {
        return new OpenMiniGameHubResponsePacket();
    }

    // --------------------------------------------------------------------------------------------
    // Handler (Client-side)
    // --------------------------------------------------------------------------------------------

    public static void handle(OpenMiniGameHubResponsePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClient(msg))
        );
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(OpenMiniGameHubResponsePacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.screen != null) {
            // Open mini-game hub with current screen as parent
            mc.setScreen(new com.pastlands.cosmeticslite.minigame.client.MiniGameHubScreen(mc.screen));
        } else if (mc.player != null) {
            // Fallback: open with no parent
            mc.setScreen(new com.pastlands.cosmeticslite.minigame.client.MiniGameHubScreen(null));
        }
    }
}

