package com.pastlands.cosmeticslite.particle;

import com.pastlands.cosmeticslite.CosmeticDef;
import com.pastlands.cosmeticslite.CosmeticsRegistry;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Shared particle spawner for consistent particle rendering across both:
 * - Local player renderer (self)
 * - Packet-based renderer (others)
 * 
 * <p>This ensures that what the emitter sees matches what others see,
 * using the same anchor point and offset calculations.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class SharedParticleSpawner {

    private SharedParticleSpawner() {}

    /**
     * Standard body-center anchor height factor.
     * This is used consistently for all particle spawning to ensure
     * visual consistency between self-view and observer-view.
     */
    public static final double BODY_CENTER_HEIGHT_FACTOR = 0.6;

    /**
     * Spawns particles for a given entity using a resolved particle definition/profile.
     * 
     * @param level The client level
     * @param entity The entity to spawn particles around (can be any entity, not just player)
     * @param resolution The resolved particle definition from ParticleProfileResolver
     * @param random Random source (should be seeded consistently for the same entity-viewer pair)
     * @param strength Strength multiplier (1.0 = normal, >1.0 = more intense)
     * @param isLocalViewer If true, this is the local player viewing themselves (for any special handling)
     */
    public static void spawnForEntity(
            ClientLevel level,
            Entity entity,
            ParticleProfileResolver.ResolutionResult resolution,
            RandomSource random,
            float strength,
            boolean isLocalViewer) {
        
        if (level == null || entity == null || resolution == null) return;

        String mode = resolution.source();
        
        // Branch 1: profile-json mode - use registry definition with world layers
        if ("profile-json".equals(mode) && resolution.profile() != null) {
            spawnProfileParticles(level, entity, resolution.profile(), random, strength);
            return;
        }
        
        // Branch 2: simple-catalog mode - use catalog entry for vanilla particles
        if ("simple-catalog".equals(mode) && resolution.catalogEntry() != null) {
            spawnSimplePattern(level, entity, resolution.catalogEntry(), random);
            return;
        }
        
        // Branch 3: fallback-default mode
        ResourceLocation cosmeticId = resolution.profileId();
        if (cosmeticId == null) {
            // Try to get from resolution if available
            cosmeticId = resolution.catalogEntry() != null ? resolution.catalogEntry().id() : null;
        }
        spawnFallbackPattern(level, entity, cosmeticId, random);
    }

    /**
     * Spawns particles from a particle profile (world layers).
     */
    private static void spawnProfileParticles(
            ClientLevel level,
            Entity entity,
            ParticleProfiles.ParticleProfile profile,
            RandomSource random,
            float strength) {
        
        if (profile.worldLayers() == null || profile.worldLayers().isEmpty()) {
            // No world layers, fallback to simple pattern
            spawnFallbackPattern(level, entity, profile.cosmeticId(), random);
            return;
        }

        // Use the first world layer for simplicity (can be enhanced to support multiple layers)
        var layer = profile.worldLayers().get(0);
        ResourceLocation effectId = layer.effect();
        ParticleOptions particle = resolveParticle(effectId);
        if (particle == null) return;

        // Get base position using consistent anchor
        double baseX = entity.getX();
        double baseY = entity.getY() + entity.getBbHeight() * BODY_CENTER_HEIGHT_FACTOR;
        double baseZ = entity.getZ();

        // Apply layer configuration
        float radius = layer.radius();
        float heightFactor = layer.heightFactor();
        int count = Math.max(1, Math.round(layer.count() * strength));
        float speedY = layer.speedY();

        // Spawn particles in a simple pattern around the entity
        for (int i = 0; i < count; i++) {
            // Random angle for horizontal distribution
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = random.nextDouble() * radius;
            
            double offsetX = Math.cos(angle) * distance;
            double offsetY = (random.nextDouble() - 0.5) * heightFactor * entity.getBbHeight();
            double offsetZ = Math.sin(angle) * distance;
            
            // If entity has yaw (like players), optionally rotate offsets to "wrap" around them
            // This makes orbiting effects consistent for observers
            if (entity.getYRot() != 0.0F) {
                float yawRad = (float) Math.toRadians(-entity.getYRot());
                double rotatedX = offsetX * Math.cos(yawRad) - offsetZ * Math.sin(yawRad);
                double rotatedZ = offsetX * Math.sin(yawRad) + offsetZ * Math.cos(yawRad);
                offsetX = rotatedX;
                offsetZ = rotatedZ;
            }
            
            double vx = (random.nextDouble() - 0.5) * 0.02;
            double vy = speedY + (random.nextDouble() - 0.5) * 0.01;
            double vz = (random.nextDouble() - 0.5) * 0.02;
            
            level.addParticle(particle, baseX + offsetX, baseY + offsetY, baseZ + offsetZ, vx, vy, vz);
        }
    }

    /**
     * Spawns a simple pattern for catalog entries (vanilla particles).
     */
    private static void spawnSimplePattern(
            ClientLevel level,
            Entity entity,
            CosmeticParticleEntry entry,
            RandomSource random) {
        
        ResourceLocation particleId = entry.particleId();
        String effectId;
        
        if ("minecraft".equals(particleId.getNamespace())) {
            effectId = particleId.toString();
        } else {
            CosmeticDef def = CosmeticsRegistry.get(entry.id());
            effectId = (def != null)
                    ? def.properties().getOrDefault("effect", "minecraft:happy_villager")
                    : "minecraft:happy_villager";
        }
        
        ParticleOptions particle = resolveParticle(effectId);
        if (particle == null) return;

        // Use consistent body-center anchor
        double baseX = entity.getX();
        double baseY = entity.getY() + entity.getBbHeight() * BODY_CENTER_HEIGHT_FACTOR;
        double baseZ = entity.getZ();
        int count = 2;
        
        for (int i = 0; i < count; i++) {
            double ox = (random.nextDouble() - 0.5) * 0.6;
            double oy = (random.nextDouble() - 0.5) * 0.3;
            double oz = (random.nextDouble() - 0.5) * 0.6;
            double vx = 0.0;
            double vy = 0.01 + random.nextDouble() * 0.01;
            double vz = 0.0;
            level.addParticle(particle, baseX + ox, baseY + oy, baseZ + oz, vx, vy, vz);
        }
    }

    /**
     * Spawns fallback pattern when no specific definition is found.
     */
    private static void spawnFallbackPattern(
            ClientLevel level,
            Entity entity,
            ResourceLocation cosmeticId,
            RandomSource random) {
        
        CosmeticDef def = cosmeticId != null ? CosmeticsRegistry.get(cosmeticId) : null;
        String effectId = (def != null)
                ? def.properties().getOrDefault("effect", "minecraft:happy_villager")
                : "minecraft:happy_villager";

        ParticleOptions particle = resolveParticle(effectId);
        if (particle == null) return;

        // Use consistent body-center anchor
        double baseX = entity.getX();
        double baseY = entity.getY() + entity.getBbHeight() * BODY_CENTER_HEIGHT_FACTOR;
        double baseZ = entity.getZ();
        int count = 2;
        
        for (int i = 0; i < count; i++) {
            double ox = (random.nextDouble() - 0.5) * 0.6;
            double oy = (random.nextDouble() - 0.5) * 0.3;
            double oz = (random.nextDouble() - 0.5) * 0.6;
            double vx = 0.0;
            double vy = 0.01 + random.nextDouble() * 0.01;
            double vz = 0.0;
            level.addParticle(particle, baseX + ox, baseY + oy, baseZ + oz, vx, vy, vz);
        }
    }

    /**
     * Resolves a particle effect ID string to a ParticleOptions instance.
     */
    private static ParticleOptions resolveParticle(String idOrNull) {
        if (idOrNull == null || idOrNull.isEmpty()) return ParticleTypes.HAPPY_VILLAGER;

        ResourceLocation rl = ResourceLocation.tryParse(idOrNull);
        if (rl == null) return ParticleTypes.HAPPY_VILLAGER;

        return resolveParticle(rl);
    }

    /**
     * Resolves a ResourceLocation to a ParticleOptions instance.
     */
    private static ParticleOptions resolveParticle(ResourceLocation rl) {
        var particleType = BuiltInRegistries.PARTICLE_TYPE.get(rl);
        if (particleType instanceof net.minecraft.core.particles.SimpleParticleType simple) {
            return simple;
        }
        // Unsupported parameterized particle type -> visible fallback
        return ParticleTypes.HAPPY_VILLAGER;
    }
}
