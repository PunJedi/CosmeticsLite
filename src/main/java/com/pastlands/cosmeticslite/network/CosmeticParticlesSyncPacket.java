package com.pastlands.cosmeticslite.network;

import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.particle.CosmeticParticleEntry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server → Client: Sync cosmetic particle catalog entries.
 * Sent when catalog changes or on player join.
 */
public final class CosmeticParticlesSyncPacket {
    private final List<CosmeticParticleEntry> entries;

    public CosmeticParticlesSyncPacket(Collection<CosmeticParticleEntry> entries) {
        this.entries = new ArrayList<>(entries);
    }

    public List<CosmeticParticleEntry> entries() {
        return entries;
    }

    // --------------------------------------------------------------------------------------------
    // Codec
    // --------------------------------------------------------------------------------------------

    public static void encode(CosmeticParticlesSyncPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entries.size());
        for (CosmeticParticleEntry entry : msg.entries) {
            encodeEntry(entry, buf);
        }
    }

    public static CosmeticParticlesSyncPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<CosmeticParticleEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CosmeticParticleEntry entry = decodeEntry(buf);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return new CosmeticParticlesSyncPacket(entries);
    }

    private static void encodeEntry(CosmeticParticleEntry entry, FriendlyByteBuf buf) {
        writeResourceLocation(buf, entry.id());
        writeResourceLocation(buf, entry.particleId());
        buf.writeUtf(entry.displayName().getString(), 256);
        buf.writeUtf(entry.slot().name(), 16);
        buf.writeBoolean(entry.rarity() != null);
        if (entry.rarity() != null) {
            buf.writeUtf(entry.rarity(), 64);
        }
        buf.writeBoolean(entry.price() != null);
        if (entry.price() != null) {
            buf.writeVarInt(entry.price());
        }
            buf.writeBoolean(entry.icon() != null);
            if (entry.icon() != null) {
                writeResourceLocation(buf, entry.icon().itemId());
                buf.writeBoolean(entry.icon().argbTint() != null);
                if (entry.icon().argbTint() != null) {
                    buf.writeInt(entry.icon().argbTint());
                }
            }
            // Write source (default to CONFIG if missing for backward compatibility)
            buf.writeUtf(entry.source().name(), 16);
        }

    private static CosmeticParticleEntry decodeEntry(FriendlyByteBuf buf) {
        try {
            ResourceLocation id = readResourceLocation(buf);
            ResourceLocation particleId = readResourceLocation(buf);
            String displayNameStr = buf.readUtf(256);
            net.minecraft.network.chat.Component displayName = net.minecraft.network.chat.Component.literal(displayNameStr);
            CosmeticParticleEntry.Slot slot = CosmeticParticleEntry.Slot.fromString(buf.readUtf(16));
            
            String rarity = buf.readBoolean() ? buf.readUtf(64) : null;
            Integer price = buf.readBoolean() ? buf.readVarInt() : null;
            
            CosmeticParticleEntry.Icon icon = null;
            if (buf.readBoolean()) {
                ResourceLocation itemId = readResourceLocation(buf);
                Integer tint = buf.readBoolean() ? buf.readInt() : null;
                icon = new CosmeticParticleEntry.Icon(itemId, tint);
            }
            
            // Read source (default to CONFIG if missing for backward compatibility)
            CosmeticParticleEntry.Source source = CosmeticParticleEntry.Source.CONFIG;
            try {
                String sourceStr = buf.readUtf(16);
                source = CosmeticParticleEntry.Source.fromString(sourceStr);
            } catch (Exception e) {
                // Backward compatibility: if source is missing or parsing fails, default to CONFIG
                source = CosmeticParticleEntry.Source.CONFIG;
            }
            
            return new CosmeticParticleEntry(id, particleId, displayName, slot, rarity, price, icon, source);
        } catch (Exception e) {
            CosmeticsLite.LOGGER.warn("[cosmeticslite] Failed to decode CosmeticParticleEntry: {}", e.getMessage());
            return null;
        }
    }

    private static void writeResourceLocation(FriendlyByteBuf buf, ResourceLocation rl) {
        buf.writeUtf(rl.toString(), 256);
    }

    private static ResourceLocation readResourceLocation(FriendlyByteBuf buf) {
        return ResourceLocation.parse(buf.readUtf(256));
    }

    // --------------------------------------------------------------------------------------------
    // Handler (Client-side)
    // --------------------------------------------------------------------------------------------

    public static void handle(CosmeticParticlesSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // Store entries on client-side for Cosmetics Particle tab
            com.pastlands.cosmeticslite.client.CosmeticParticleClientCatalog.replaceAll(msg.entries);
            CosmeticsLite.LOGGER.info("[cosmeticslite] Synced {} cosmetic particle entry(ies) from server", msg.entries.size());
            
            // Notify Particle Lab screen if it's open
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.screen instanceof com.pastlands.cosmeticslite.client.screen.ParticleLabScreen screen) {
                    screen.onCatalogSynced();
                }
            });
            
            // Step 3: Sanity check - verify all built-in cosmetics have legacy profiles
            // Run this after catalog is synced (ParticleProfiles should be loaded by now)
            checkBuiltinCosmeticProfiles();
        });
        ctx.get().setPacketHandled(true);
    }
    
    /**
     * Sanity check: verify all built-in cosmetic particle entries have legacy profiles.
     * Logs warnings for any built-ins that don't have a corresponding ParticleProfile.
     */
    private static void checkBuiltinCosmeticProfiles() {
        // Get all built-in entries from LegacyCosmeticParticles
        var builtins = com.pastlands.cosmeticslite.particle.LegacyCosmeticParticles.builtins();
        
        int missingCount = 0;
        for (var entry : builtins) {
            // Derive profile ID from entry (same logic as ParticleProfileResolver)
            net.minecraft.resources.ResourceLocation profileId;
            net.minecraft.resources.ResourceLocation particleId = entry.particleId();
            
            if ("cosmeticslite".equals(particleId.getNamespace())) {
                profileId = particleId;
            } else if ("minecraft".equals(particleId.getNamespace())) {
                String path = "particle/" + particleId.getPath();
                profileId = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("cosmeticslite", path);
            } else {
                // Fallback: derive from cosmetic ID
                String path = entry.id().getPath();
                if (path.startsWith("cosmetic/")) {
                    path = "particle/" + path.substring("cosmetic/".length());
                } else if (!path.startsWith("particle/")) {
                    path = "particle/" + path;
                }
                profileId = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("cosmeticslite", path);
            }
            
            // Check if legacy profile exists
            var legacyProfile = com.pastlands.cosmeticslite.particle.ParticleProfiles.get(profileId);
            if (legacyProfile == null) {
                CosmeticsLite.LOGGER.warn("[cosmeticslite] WARN: No legacy ParticleProfile for {} (cosmeticId={}, particleId={})",
                    profileId, entry.id(), particleId);
                missingCount++;
            }
        }
        
        if (missingCount > 0) {
            CosmeticsLite.LOGGER.warn("[cosmeticslite] Found {} built-in cosmetic(s) without legacy ParticleProfile entries", missingCount);
        } else {
            CosmeticsLite.LOGGER.info("[cosmeticslite] All {} built-in cosmetic(s) have legacy ParticleProfile entries", builtins.size());
        }
    }
}

