package com.pastlands.cosmeticslite.network;

import com.pastlands.cosmeticslite.CosmeticsLite;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → Server: Request to enable or disable particle preview mode.
 * Sent from Particle Lab GUI or /cosmetics particles preview command.
 * Server processes the request and may send confirmation back.
 */
public final class ParticlePreviewPacket {
    private final boolean enable;
    @org.jetbrains.annotations.Nullable
    private final ResourceLocation particleId;

    public ParticlePreviewPacket(boolean enable, @org.jetbrains.annotations.Nullable ResourceLocation particleId) {
        this.enable = enable;
        this.particleId = particleId;
    }

    public boolean enable() { return enable; }
    @org.jetbrains.annotations.Nullable
    public ResourceLocation particleId() { return particleId; }

    // --------------------------------------------------------------------------------------------
    // Codec
    // --------------------------------------------------------------------------------------------

    public static void encode(ParticlePreviewPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.enable);
        buf.writeBoolean(msg.particleId != null);
        if (msg.particleId != null) {
            buf.writeUtf(msg.particleId.toString(), 256);
        }
    }

    public static ParticlePreviewPacket decode(FriendlyByteBuf buf) {
        boolean enable = buf.readBoolean();
        ResourceLocation particleId = null;
        if (buf.readBoolean()) {
            particleId = ResourceLocation.parse(buf.readUtf(256));
        }
        return new ParticlePreviewPacket(enable, particleId);
    }

    // --------------------------------------------------------------------------------------------
    // Handler (Server-side)
    // --------------------------------------------------------------------------------------------

    public static void handle(ParticlePreviewPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var sender = ctx.get().getSender();
            if (sender == null) {
                CosmeticsLite.LOGGER.warn("[cosmeticslite] ParticlePreviewPacket received from null sender");
                return;
            }
            
            CosmeticsLite.LOGGER.info("[cosmeticslite] ParticlePreviewPacket received from {}: enable={}, particleId={}", 
                sender.getName().getString(), msg.enable, msg.particleId);
            
            // Server validates and processes the preview request
            // For now, we'll just log it - the actual preview state is managed client-side
            // In the future, server could validate permissions, sync to other players, etc.
            
            // Server validates and processes the preview request
            // Note: Client-side preview can use unsaved working copies or asset-based definitions,
            // so a missing registry entry is normal and not an error
            if (msg.enable && msg.particleId != null) {
                // Check if the particle definition exists in registry (optional validation)
                var def = com.pastlands.cosmeticslite.particle.config.CosmeticParticleRegistry.get(msg.particleId);
                if (def != null) {
                    CosmeticsLite.LOGGER.debug("[cosmeticslite] Preview validated for: {}", msg.particleId);
                } else {
                    // Not in registry - could be asset-based or unsaved working copy, which is fine
                    CosmeticsLite.LOGGER.debug("[cosmeticslite] Preview requested for particle not in registry (may be asset-based or unsaved): {}", msg.particleId);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}

