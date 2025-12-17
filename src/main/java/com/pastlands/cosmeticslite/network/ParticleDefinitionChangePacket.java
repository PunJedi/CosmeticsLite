package com.pastlands.cosmeticslite.network;

import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.permission.CosmeticsPermissions;
import com.pastlands.cosmeticslite.particle.config.CosmeticParticleRegistry;
import com.pastlands.cosmeticslite.particle.config.ParticleDefinition;
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
 * Client → Server: Save a particle definition change (CREATE/UPDATE/DELETE).
 * Server validates permission, saves to config, and broadcasts update to all clients.
 */
public final class ParticleDefinitionChangePacket {
    public enum ChangeType {
        CREATE,
        UPDATE,
        DELETE
    }

    private final ChangeType changeType;
    private final ResourceLocation id;
    private final ParticleDefinition definition; // null for DELETE

    public ParticleDefinitionChangePacket(ChangeType changeType, ResourceLocation id, ParticleDefinition definition) {
        this.changeType = changeType;
        this.id = id;
        this.definition = definition;
    }

    public ChangeType changeType() { return changeType; }
    public ResourceLocation id() { return id; }
    public ParticleDefinition definition() { return definition; }

    // --------------------------------------------------------------------------------------------
    // Codec
    // --------------------------------------------------------------------------------------------

    public static void encode(ParticleDefinitionChangePacket msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.changeType);
        writeResourceLocation(buf, msg.id);
        if (msg.definition != null && msg.changeType != ChangeType.DELETE) {
            buf.writeBoolean(true);
            ParticleDefinitionsSyncPacket.writeParticleDefinition(buf, msg.definition);
        } else {
            buf.writeBoolean(false);
        }
    }

    public static ParticleDefinitionChangePacket decode(FriendlyByteBuf buf) {
        ChangeType changeType = buf.readEnum(ChangeType.class);
        ResourceLocation id = readResourceLocation(buf);
        
        // Log after reading ID and changeType
        int readableBytes = buf.readableBytes();
        CosmeticsLite.LOGGER.info("[ParticleLab] Receiving SAVE for {} (raw buffer readableBytes = {})",
            id, readableBytes);
        
        ParticleDefinition definition = null;
        if (buf.readBoolean()) {
            definition = ParticleDefinitionsSyncPacket.readParticleDefinition(buf, id);
        }
        return new ParticleDefinitionChangePacket(changeType, id, definition);
    }

    // --------------------------------------------------------------------------------------------
    // Handler (Server-side)
    // --------------------------------------------------------------------------------------------

    public static void handle(ParticleDefinitionChangePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // Check permission
            if (!CosmeticsPermissions.canUseParticleLab(player)) {
                player.sendSystemMessage(Component.literal("§cYou do not have permission to modify particle definitions."));
                return;
            }

            // Apply change
            boolean success = false;
            switch (msg.changeType) {
                case CREATE:
                case UPDATE:
                    if (msg.definition != null) {
                        // Log after successful decode
                        CosmeticsLite.LOGGER.info("[ParticleLab] Saving definition {}: {} layer(s), {} world layer(s)",
                            msg.id, msg.definition.layers().size(), msg.definition.worldLayers().size());
                        
                        success = CosmeticParticleRegistry.saveDefinition(msg.definition);
                        if (success) {
                            // Update registry directly (more efficient than full reload)
                            // The save already wrote to file, just update in-memory registry
                            Map<ResourceLocation, ParticleDefinition> current = new HashMap<>();
                            for (var def : CosmeticParticleRegistry.all()) {
                                current.put(def.id(), def);
                            }
                            current.put(msg.definition.id(), msg.definition);
                            CosmeticParticleRegistry.replaceAll(current);
                            
                            player.sendSystemMessage(Component.literal("§aSaved particle definition: " + msg.id));
                        } else {
                            player.sendSystemMessage(Component.literal("§cFailed to save particle definition: " + msg.id));
                        }
                    }
                    break;
                case DELETE:
                    // TODO: Implement delete (remove file and update registry)
                    player.sendSystemMessage(Component.literal("§cDelete not yet implemented"));
                    success = false;
                    break;
            }

            if (success && msg.changeType != ChangeType.DELETE) {
                // Broadcast updated definitions to all clients
                Map<ResourceLocation, ParticleDefinition> allDefs = new HashMap<>();
                for (var def : CosmeticParticleRegistry.all()) {
                    allDefs.put(def.id(), def);
                }
                ParticleDefinitionsSyncPacket syncPacket = new ParticleDefinitionsSyncPacket(allDefs);
                CosmeticsLite.NETWORK.send(PacketDistributor.ALL.noArg(), syncPacket);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    // --------------------------------------------------------------------------------------------
    // Serialization Helpers
    // --------------------------------------------------------------------------------------------

    private static void writeResourceLocation(FriendlyByteBuf buf, ResourceLocation rl) {
        buf.writeUtf(rl.toString(), 256);
    }

    private static ResourceLocation readResourceLocation(FriendlyByteBuf buf) {
        return ResourceLocation.parse(buf.readUtf(256));
    }
}

