package com.pastlands.cosmeticslite.network;

import com.pastlands.cosmeticslite.particle.ParticleProfileResolver;
import com.pastlands.cosmeticslite.particle.SharedParticleSpawner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server → Client: instructs a viewer client to spawn particles for a cosmetic effect
 * attached to a specific emitter entity.
 * 
 * <p>This packet tells the client: "spawn particles for effect X on entity Y".
 * The client is responsible for resolving the particle definition and rendering it.</p>
 * 
 * <p>Fields (minimum viable):</p>
 * <ul>
 *   <li>emitterEntityId - The entity ID of the player wearing the cosmetic</li>
 *   <li>effectId - The cosmetic ID or particle effect ID to spawn</li>
 *   <li>flags - Optional byte flags (inWater, sprinting, etc.)</li>
 *   <li>seed - Optional seed for consistent patterns per viewer</li>
 *   <li>strength - Optional strength/intensity multiplier</li>
 * </ul>
 */
public final class S2CCosmeticParticleEmit {
    private final int emitterEntityId;
    private final ResourceLocation effectId;
    private final byte flags;
    private final int seed;
    private final float strength;

    public S2CCosmeticParticleEmit(int emitterEntityId, ResourceLocation effectId) {
        this(emitterEntityId, effectId, (byte) 0, 0, 1.0f);
    }

    public S2CCosmeticParticleEmit(int emitterEntityId, ResourceLocation effectId, byte flags, int seed, float strength) {
        this.emitterEntityId = emitterEntityId;
        this.effectId = effectId;
        this.flags = flags;
        this.seed = seed;
        this.strength = strength;
    }

    public int emitterEntityId() { return emitterEntityId; }
    public ResourceLocation effectId() { return effectId; }
    public byte flags() { return flags; }
    public int seed() { return seed; }
    public float strength() { return strength; }

    // --------------------------------------------------------------------------------------------
    // Codec
    // --------------------------------------------------------------------------------------------

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(emitterEntityId);
        buf.writeResourceLocation(effectId);
        buf.writeByte(flags);
        buf.writeVarInt(seed);
        buf.writeFloat(strength);
    }

    public static S2CCosmeticParticleEmit decode(FriendlyByteBuf buf) {
        int emitterEntityId = buf.readVarInt();
        ResourceLocation effectId = buf.readResourceLocation();
        byte flags = buf.readByte();
        int seed = buf.readVarInt();
        float strength = buf.readFloat();
        return new S2CCosmeticParticleEmit(emitterEntityId, effectId, flags, seed, strength);
    }

    // --------------------------------------------------------------------------------------------
    // Handler
    // --------------------------------------------------------------------------------------------

    public static void handle(S2CCosmeticParticleEmit msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // Client-side only
            if (ctx.get().getDirection().getReceptionSide().isClient()) {
                handleClient(msg);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(S2CCosmeticParticleEmit msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // Safety check: ignore packets for self to prevent double-spawning
        // (Server should not send to emitter, but this provides defense in depth)
        if (msg.emitterEntityId == mc.player.getId()) {
            return;
        }

        ClientLevel level = (ClientLevel) mc.level;
        Entity emitter = level.getEntity(msg.emitterEntityId);
        if (emitter == null) {
            // Entity not loaded yet - this is fine, just skip
            return;
        }

        // Resolve particle definition using the same flow as local renderer
        ResourceLocation cosmeticId = msg.effectId;
        var resolution = ParticleProfileResolver.resolve(cosmeticId);
        
        // Use seed from packet for consistent patterns per viewer
        RandomSource random = RandomSource.create(msg.seed);
        
        // Use shared spawner for consistent rendering
        SharedParticleSpawner.spawnForEntity(
                level,
                emitter,
                resolution,
                random,
                msg.strength,
                false // isLocalViewer = false (this is for other players)
        );
    }
}
