package com.pastlands.cosmeticslite.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> Client: notifies the player that an equip attempt was denied.
 *
 * Triggered when a cosmetic equip request fails entitlement checks.
 * Displays a short red chat message on the client.
 */
public class S2CEquipDenied {

    private final String type;
    private final ResourceLocation id;

    // --------------------------------------------------------------------------------------------
    // Construction
    // --------------------------------------------------------------------------------------------
    public S2CEquipDenied(String type, ResourceLocation id) {
        this.type = type;
        this.id = id;
    }

    // --------------------------------------------------------------------------------------------
    // Encode / Decode
    // --------------------------------------------------------------------------------------------
    public static void encode(S2CEquipDenied msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.type);
        buf.writeResourceLocation(msg.id);
    }

    public static S2CEquipDenied decode(FriendlyByteBuf buf) {
        String type = buf.readUtf();
        ResourceLocation id = buf.readResourceLocation();
        return new S2CEquipDenied(type, id);
    }

    // --------------------------------------------------------------------------------------------
    // Handle (client)
    // --------------------------------------------------------------------------------------------
    public static void handle(S2CEquipDenied msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> handleClient(msg));
        ctx.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(S2CEquipDenied msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("§cYou don't own this cosmetic: §f" + msg.type + " / " + msg.id.getPath()),
                    false
            );
        }
    }
}
