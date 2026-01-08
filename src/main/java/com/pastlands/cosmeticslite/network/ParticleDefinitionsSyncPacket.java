package com.pastlands.cosmeticslite.network;

import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.particle.config.CosmeticParticleRegistry;
import com.pastlands.cosmeticslite.particle.config.MotionCurve;
import com.pastlands.cosmeticslite.particle.config.ParticleDefinition;
import com.pastlands.cosmeticslite.particle.config.ParticleLayerDefinition;
import com.pastlands.cosmeticslite.particle.config.RotationMode;
import com.pastlands.cosmeticslite.particle.config.WorldLayerDefinition;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Server → Client: Synchronize all particle definitions from server to client.
 * Sent after /cosmetics particles reload and on player join.
 */
public final class ParticleDefinitionsSyncPacket {
    private final Map<ResourceLocation, ParticleDefinition> definitions;

    public ParticleDefinitionsSyncPacket(Map<ResourceLocation, ParticleDefinition> definitions) {
        this.definitions = definitions != null ? new HashMap<>(definitions) : new HashMap<>();
    }

    public Map<ResourceLocation, ParticleDefinition> definitions() {
        return definitions;
    }

    // --------------------------------------------------------------------------------------------
    // Codec
    // --------------------------------------------------------------------------------------------

    public static void encode(ParticleDefinitionsSyncPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.definitions.size());
        for (Map.Entry<ResourceLocation, ParticleDefinition> entry : msg.definitions.entrySet()) {
            writeResourceLocation(buf, entry.getKey());
            writeParticleDefinition(buf, entry.getValue());
        }
    }

    public static ParticleDefinitionsSyncPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        Map<ResourceLocation, ParticleDefinition> definitions = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            ResourceLocation id = readResourceLocation(buf);
            ParticleDefinition def = readParticleDefinition(buf, id);
            if (def != null) {
                definitions.put(id, def);
            }
        }
        return new ParticleDefinitionsSyncPacket(definitions);
    }

    // --------------------------------------------------------------------------------------------
    // Handler
    // --------------------------------------------------------------------------------------------

    public static void handle(ParticleDefinitionsSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // Apply lab snapshot from server (replaces only lab entries, leaves built-ins untouched)
            CosmeticParticleRegistry.applyLabSnapshotFromServer(msg.definitions);
            CosmeticsLite.LOGGER.info("[ParticleLab] Applied lab snapshot from server: {} definition(s)",
                msg.definitions.size());
            
            // Update ParticleLabScreen's editor collection if it's open
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen instanceof com.pastlands.cosmeticslite.client.screen.ParticleLabScreen) {
                ((com.pastlands.cosmeticslite.client.screen.ParticleLabScreen) mc.screen).onDefinitionsSynced(msg.definitions);
            } else {
                // Screen is not open, but we should still log what was synced for debugging
                if (!msg.definitions.isEmpty()) {
                    CosmeticsLite.LOGGER.debug("[cosmeticslite] Synced definitions (screen closed): {}", 
                        msg.definitions.keySet().stream().map(ResourceLocation::toString).collect(java.util.stream.Collectors.joining(", ")));
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }

    // --------------------------------------------------------------------------------------------
    // Serialization Helpers (public for use by other packets)
    // --------------------------------------------------------------------------------------------

    public static void writeResourceLocation(FriendlyByteBuf buf, ResourceLocation rl) {
        buf.writeUtf(rl.toString(), 256);
    }

    public static ResourceLocation readResourceLocation(FriendlyByteBuf buf) {
        return ResourceLocation.parse(buf.readUtf(256));
    }

    public static void writeParticleDefinition(FriendlyByteBuf buf, ParticleDefinition def) {
        // Note: ID is NOT written here - it's written by the caller (ParticleDefinitionChangePacket or sync packet)
        // This method only writes the definition content
        
        // Write layers
        buf.writeVarInt(def.layers().size());
        for (ParticleLayerDefinition layer : def.layers()) {
            writeParticleLayer(buf, layer);
        }
        
        // Write world layers
        buf.writeVarInt(def.worldLayers().size());
        for (WorldLayerDefinition worldLayer : def.worldLayers()) {
            writeWorldLayer(buf, worldLayer);
        }
        
        // Optional fields
        buf.writeBoolean(def.description() != null);
        if (def.description() != null) {
            buf.writeUtf(def.description(), 1024);
        }
        buf.writeBoolean(def.styleHint() != null);
        if (def.styleHint() != null) {
            buf.writeUtf(def.styleHint(), 256);
        }
        buf.writeUtf(def.displayName() != null ? def.displayName() : "", 256);
        buf.writeUtf(def.notes() != null ? def.notes() : "", 2048);
    }

    public static ParticleDefinition readParticleDefinition(FriendlyByteBuf buf, ResourceLocation id) {
        try {
            // Read layers
            int layerCount = buf.readVarInt();
            List<ParticleLayerDefinition> layers = new ArrayList<>(layerCount);
            for (int i = 0; i < layerCount; i++) {
                try {
                    ParticleLayerDefinition layer = readParticleLayer(buf);
                    if (layer != null) {
                        layers.add(layer);
                    }
                } catch (IndexOutOfBoundsException ex) {
                    CosmeticsLite.LOGGER.warn("[cosmeticslite] Failed to deserialize particle layer {} for {}: {}", i, id, ex.toString());
                    return null; // Abort this definition cleanly
                }
            }
            
            // Read world layers
            int worldLayerCount = buf.readVarInt();
            List<WorldLayerDefinition> worldLayers = new ArrayList<>(worldLayerCount);
            for (int i = 0; i < worldLayerCount; i++) {
                try {
                    WorldLayerDefinition worldLayer = readWorldLayer(buf);
                    if (worldLayer != null) {
                        worldLayers.add(worldLayer);
                    }
                } catch (IndexOutOfBoundsException ex) {
                    CosmeticsLite.LOGGER.warn("[cosmeticslite] Failed to deserialize world layer {} for {}: {}", i, id, ex.toString());
                    return null; // Abort this definition cleanly
                }
            }
            
            // Optional fields
            String description = buf.readBoolean() ? buf.readUtf(1024) : null; // Increased to 1024 to match write
            String styleHint = buf.readBoolean() ? buf.readUtf(256) : null; // Increased to 256 to match write
            String displayName = buf.readUtf(256); // Increased to 256 to match write
            String notes = buf.readUtf(2048); // Increased to 2048 to match write
            
            return new ParticleDefinition(id, layers, worldLayers, description, styleHint, displayName, notes);
        } catch (Exception e) {
            CosmeticsLite.LOGGER.warn("[cosmeticslite] Failed to deserialize particle definition {}: {}", id, e.getMessage());
            return null;
        }
    }

    public static void writeParticleLayer(FriendlyByteBuf buf, ParticleLayerDefinition layer) {
        // Write in explicit, fixed order
        buf.writeUtf(layer.movement(), 256);
        buf.writeVarInt(layer.colors().size());
        for (Integer color : layer.colors()) {
            buf.writeInt(color);
        }
        buf.writeFloat(layer.lifespan());
        buf.writeFloat(layer.spawnInterval());
        buf.writeFloat(layer.size());
        buf.writeFloat(layer.speed());
        buf.writeFloat(layer.weight());
        buf.writeFloat(layer.previewScale());
    }

    public static ParticleLayerDefinition readParticleLayer(FriendlyByteBuf buf) {
        try {
            // Read in exact same order as write
            String movement = buf.readUtf(256);
            int colorCount = buf.readVarInt();
            List<Integer> colors = new ArrayList<>(colorCount);
            for (int i = 0; i < colorCount; i++) {
                colors.add(buf.readInt());
            }
            float lifespan = buf.readFloat();
            float spawnInterval = buf.readFloat();
            float size = buf.readFloat();
            float speed = buf.readFloat();
            float weight = buf.readFloat();
            float previewScale = buf.readFloat();
            return new ParticleLayerDefinition(movement, colors, lifespan, spawnInterval, size, speed, weight, previewScale);
        } catch (Exception e) {
            CosmeticsLite.LOGGER.warn("[cosmeticslite] Failed to deserialize particle layer: {}", e.getMessage());
            return null;
        }
    }

    public static void writeWorldLayer(FriendlyByteBuf buf, WorldLayerDefinition worldLayer) {
        // Write in explicit, fixed order
        writeResourceLocation(buf, worldLayer.effect());
        buf.writeUtf(worldLayer.style(), 256);
        buf.writeFloat(worldLayer.radius());
        buf.writeFloat(worldLayer.heightFactor());
        buf.writeVarInt(worldLayer.count());
        buf.writeFloat(worldLayer.speedY());
        
        // Optional fields - write boolean flags first, then values
        buf.writeBoolean(worldLayer.yOffset() != null);
        if (worldLayer.yOffset() != null) {
            buf.writeFloat(worldLayer.yOffset());
        }
        buf.writeBoolean(worldLayer.xScale() != null);
        if (worldLayer.xScale() != null) {
            buf.writeFloat(worldLayer.xScale());
        }
        buf.writeBoolean(worldLayer.direction() != null);
        if (worldLayer.direction() != null) {
            buf.writeUtf(worldLayer.direction(), 256);
        }
        
        // Write new Tier 1 & 2 fields
        buf.writeFloat(worldLayer.baseHeight());
        buf.writeFloat(worldLayer.heightStretch());
        buf.writeUtf(worldLayer.rotationMode().name(), 32);
        buf.writeVarInt(worldLayer.rotationDirection()); // +1 or -1
        buf.writeFloat(worldLayer.tiltDegrees());
        buf.writeFloat(worldLayer.offsetX());
        buf.writeFloat(worldLayer.offsetY());
        buf.writeFloat(worldLayer.offsetZ());
        buf.writeFloat(worldLayer.spreadStart());
        buf.writeFloat(worldLayer.spreadEnd());
        buf.writeFloat(worldLayer.jitterDegrees());
        buf.writeFloat(worldLayer.jitterSpeed());
        buf.writeFloat(worldLayer.driftX());
        buf.writeFloat(worldLayer.driftY());
        buf.writeFloat(worldLayer.driftZ());
        buf.writeFloat(worldLayer.driftVariance());
        buf.writeFloat(worldLayer.torqueSpeed());
        buf.writeFloat(worldLayer.torqueAmount());
        buf.writeUtf(worldLayer.motionCurve().name(), 32);
        buf.writeVarInt(worldLayer.spawnDelayVarianceMs());
    }

    public static WorldLayerDefinition readWorldLayer(FriendlyByteBuf buf) {
        try {
            // Read in exact same order as write
            ResourceLocation effect = readResourceLocation(buf);
            String style = buf.readUtf(256);
            float radius = buf.readFloat();
            float heightFactor = buf.readFloat();
            int count = buf.readVarInt();
            float speedY = buf.readFloat();
            
            // Optional fields - read boolean flags first, then values
            Float yOffset = buf.readBoolean() ? buf.readFloat() : null;
            Float xScale = buf.readBoolean() ? buf.readFloat() : null;
            String direction = buf.readBoolean() ? buf.readUtf(256) : null;
            
            // Read new Tier 1 & 2 fields (with backward compatibility - use defaults if buffer is exhausted)
            float baseHeight = heightFactor; // Default to legacy heightFactor
            float heightStretch = 0.0f;
            RotationMode rotationMode = RotationMode.HORIZONTAL;
            int rotationDirection = 1; // Default to clockwise
            float tiltDegrees = 0.0f;
            float offsetX = 0.0f, offsetY = 0.0f, offsetZ = 0.0f;
            float spreadStart = 1.0f, spreadEnd = 1.0f;
            float jitterDegrees = 0.0f, jitterSpeed = 0.0f;
            float driftX = 0.0f, driftY = 0.0f, driftZ = 0.0f, driftVariance = 0.0f;
            float torqueSpeed = 0.0f, torqueAmount = 0.0f;
            MotionCurve motionCurve = MotionCurve.LINEAR;
            int spawnDelayVarianceMs = 0;
            
            // Try to read new fields if available (for backward compatibility with old packets)
            if (buf.readableBytes() > 0) {
                try {
                    baseHeight = buf.readFloat();
                    heightStretch = buf.readFloat();
                    String rotationModeStr = buf.readUtf(32);
                    try {
                        rotationMode = RotationMode.valueOf(rotationModeStr);
                    } catch (IllegalArgumentException e) {
                        rotationMode = RotationMode.HORIZONTAL;
                    }
                    // Read rotation direction (+1 or -1), default to 1 if missing (backward compatibility)
                    if (buf.readableBytes() > 0) {
                        try {
                            rotationDirection = buf.readVarInt();
                            // Clamp to +1 or -1
                            if (rotationDirection != 1 && rotationDirection != -1) {
                                rotationDirection = 1;
                            }
                        } catch (Exception e) {
                            rotationDirection = 1; // Default to clockwise
                        }
                    }
                    tiltDegrees = buf.readFloat();
                    offsetX = buf.readFloat();
                    offsetY = buf.readFloat();
                    offsetZ = buf.readFloat();
                    spreadStart = buf.readFloat();
                    spreadEnd = buf.readFloat();
                    jitterDegrees = buf.readFloat();
                    jitterSpeed = buf.readFloat();
                    driftX = buf.readFloat();
                    driftY = buf.readFloat();
                    driftZ = buf.readFloat();
                    driftVariance = buf.readFloat();
                    torqueSpeed = buf.readFloat();
                    torqueAmount = buf.readFloat();
                    String motionCurveStr = buf.readUtf(32);
                    try {
                        motionCurve = MotionCurve.valueOf(motionCurveStr);
                    } catch (IllegalArgumentException e) {
                        motionCurve = MotionCurve.LINEAR;
                    }
                    spawnDelayVarianceMs = buf.readVarInt();
                } catch (Exception e) {
                    // Old packet format - use defaults
                    CosmeticsLite.LOGGER.debug("[cosmeticslite] Old packet format detected, using defaults for new fields");
                }
            }
            
            return new WorldLayerDefinition(effect, style, radius, heightFactor, count, speedY, yOffset, xScale, direction,
                baseHeight, heightStretch, rotationMode, rotationDirection, tiltDegrees, offsetX, offsetY, offsetZ,
                spreadStart, spreadEnd, jitterDegrees, jitterSpeed, driftX, driftY, driftZ, driftVariance,
                torqueSpeed, torqueAmount, motionCurve, spawnDelayVarianceMs);
        } catch (Exception e) {
            CosmeticsLite.LOGGER.warn("[cosmeticslite] Failed to deserialize world layer: {}", e.getMessage());
            return null;
        }
    }
}

