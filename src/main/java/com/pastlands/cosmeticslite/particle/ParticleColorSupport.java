package com.pastlands.cosmeticslite.particle;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/**
 * Utility class to determine which particle effects support custom colorization.
 * Only 4 particle types in Minecraft support color tinting.
 */
public final class ParticleColorSupport {
    private static final Set<ResourceLocation> COLORIZABLE = Set.of(
        ResourceLocation.fromNamespaceAndPath("minecraft", "dust"),
        ResourceLocation.fromNamespaceAndPath("minecraft", "dust_color_transition"),
        ResourceLocation.fromNamespaceAndPath("minecraft", "entity_effect"),
        ResourceLocation.fromNamespaceAndPath("minecraft", "ambient_entity_effect")
    );

    private ParticleColorSupport() {}

    /**
     * Check if a particle effect supports custom colorization.
     * 
     * @param effectId The particle effect ResourceLocation
     * @return true if the particle type supports color tinting
     */
    public static boolean isColorizable(ResourceLocation effectId) {
        if (effectId == null) {
            return false;
        }
        return COLORIZABLE.contains(effectId);
    }
}
