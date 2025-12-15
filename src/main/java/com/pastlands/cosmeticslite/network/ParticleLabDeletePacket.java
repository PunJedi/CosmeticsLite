package com.pastlands.cosmeticslite.network;

import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.permission.CosmeticsPermissions;
import com.pastlands.cosmeticslite.particle.CosmeticParticleCatalog;
import com.pastlands.cosmeticslite.particle.config.CosmeticParticleRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Client → Server: Delete a lab-created particle definition.
 * Server validates permission, deletes file, removes from registry/catalog, and syncs to all clients.
 */
public final class ParticleLabDeletePacket {
    private final ResourceLocation particleId;

    public ParticleLabDeletePacket(ResourceLocation particleId) {
        this.particleId = particleId;
    }

    public ResourceLocation particleId() {
        return particleId;
    }

    // --------------------------------------------------------------------------------------------
    // Codec
    // --------------------------------------------------------------------------------------------

    public static void encode(ParticleLabDeletePacket msg, FriendlyByteBuf buf) {
        writeResourceLocation(buf, msg.particleId);
    }

    public static ParticleLabDeletePacket decode(FriendlyByteBuf buf) {
        ResourceLocation particleId = readResourceLocation(buf);
        return new ParticleLabDeletePacket(particleId);
    }

    private static void writeResourceLocation(FriendlyByteBuf buf, ResourceLocation rl) {
        buf.writeUtf(rl.toString(), 256);
    }

    private static ResourceLocation readResourceLocation(FriendlyByteBuf buf) {
        return ResourceLocation.parse(buf.readUtf(256));
    }

    // --------------------------------------------------------------------------------------------
    // Handler (Server-side)
    // --------------------------------------------------------------------------------------------

    public static void handle(ParticleLabDeletePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // Permission check
            if (!CosmeticsPermissions.canUseFeature(player, com.pastlands.cosmeticslite.permission.CosmeticsFeature.PARTICLE_LAB)) {
                player.sendSystemMessage(Component.literal("§cYou don't have permission to delete particles."));
                return;
            }

            // Prevent deleting built-ins
            if (!CosmeticParticleRegistry.isLabDefinition(msg.particleId)) {
                CosmeticsLite.LOGGER.warn("[cosmeticslite] {} attempted to delete non-lab definition: {}", 
                    player.getName().getString(), msg.particleId);
                player.sendSystemMessage(Component.literal("§cCannot delete built-in particles."));
                return;
            }

            // Delete from registry
            boolean deleted = CosmeticParticleRegistry.delete(msg.particleId);
            if (!deleted) {
                CosmeticsLite.LOGGER.warn("[cosmeticslite] Failed to delete particle definition: {}", msg.particleId);
                player.sendSystemMessage(Component.literal("§cFailed to delete particle definition."));
                return;
            }

            // Remove from catalog (map particle ID to cosmetic ID)
            String path = msg.particleId.getPath();
            if (path.startsWith("particle/")) {
                path = path.substring("particle/".length());
            }
            ResourceLocation cosmeticId = ResourceLocation.fromNamespaceAndPath(
                msg.particleId.getNamespace(),
                "cosmetic/" + path
            );
            CosmeticParticleCatalog catalog = com.pastlands.cosmeticslite.network.PublishCosmeticPacket.getCatalog();
            catalog.remove(cosmeticId);
            catalog.saveToFile();

            CosmeticsLite.LOGGER.info("[cosmeticslite] {} deleted particle definition: {} (cosmetic: {})", 
                player.getName().getString(), msg.particleId, cosmeticId);

            // Sync updated definitions to all clients
            Map<ResourceLocation, com.pastlands.cosmeticslite.particle.config.ParticleDefinition> allDefs = new HashMap<>();
            for (var def : CosmeticParticleRegistry.all()) {
                allDefs.put(def.id(), def);
            }
            ParticleDefinitionsSyncPacket defSyncPacket = new ParticleDefinitionsSyncPacket(allDefs);
            CosmeticsLite.NETWORK.send(PacketDistributor.ALL.noArg(), defSyncPacket);

            // Sync updated catalog to all clients
            com.pastlands.cosmeticslite.network.CosmeticParticlesSyncPacket catalogSync = 
                new com.pastlands.cosmeticslite.network.CosmeticParticlesSyncPacket(catalog.all());
            CosmeticsLite.NETWORK.send(PacketDistributor.ALL.noArg(), catalogSync);

            player.sendSystemMessage(Component.literal("§aDeleted particle: " + msg.particleId));
        });
        ctx.get().setPacketHandled(true);
    }
}

