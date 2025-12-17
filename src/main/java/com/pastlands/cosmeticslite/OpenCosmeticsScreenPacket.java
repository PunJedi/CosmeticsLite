package com.pastlands.cosmeticslite;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server → client: open the cosmetics GUI. No payload. */
public class OpenCosmeticsScreenPacket {

    public OpenCosmeticsScreenPacket() {}

    // --- Codec (no fields) ---
    public static void encode(OpenCosmeticsScreenPacket msg, FriendlyByteBuf buf) {
        // no payload
    }

    public static OpenCosmeticsScreenPacket decode(FriendlyByteBuf buf) {
        return new OpenCosmeticsScreenPacket();
    }

    // --- Handler ---
    public static void handle(OpenCosmeticsScreenPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClient(msg))
        );
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(OpenCosmeticsScreenPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.setScreen(new CosmeticsChestScreen());
        }
    }
}
