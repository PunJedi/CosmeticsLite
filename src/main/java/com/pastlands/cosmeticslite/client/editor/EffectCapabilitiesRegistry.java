package com.pastlands.cosmeticslite.client.editor;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry for particle effect capabilities.
 * Tracks which effects support color overrides, editor movement, and scale overrides.
 */
public final class EffectCapabilitiesRegistry {
    
    public record EffectCapabilities(
        boolean supportsColorOverride,
        boolean supportsEditorMovement,
        boolean supportsScaleOverride,
        String notes // Optional explanatory text
    ) {}
    
    private static final EffectCapabilities DEFAULT_CAPABILITIES = new EffectCapabilities(
        true,  // supportsColorOverride
        true,  // supportsEditorMovement
        true,  // supportsScaleOverride
        ""     // notes
    );
    
    private static final Map<ResourceLocation, EffectCapabilities> CAPABILITIES = new HashMap<>();
    
    // IDs of vanilla wrapper profiles that should have locked Layers tab
    private static final ResourceLocation FLAME_ID = ResourceLocation.fromNamespaceAndPath("cosmeticslite", "particle/flame");
    private static final ResourceLocation HAPPY_VILLAGER_ID = ResourceLocation.fromNamespaceAndPath("cosmeticslite", "particle/happy_villager");
    private static final ResourceLocation HEART_ID = ResourceLocation.fromNamespaceAndPath("cosmeticslite", "particle/heart");
    
    private EffectCapabilitiesRegistry() {}
    
    /**
     * Initialize default capability registrations.
     * Call this from client setup.
     */
    public static void initDefaults() {
        // Only register the three raw vanilla wrappers as limited
        // All other effects (including *_blended profiles) fall back to DEFAULT_CAPABILITIES (all true)
        register("cosmeticslite:particle/flame",
            false, false, false,
            "Minecraft flame behavior; color and motion control are limited.");
        register("cosmeticslite:particle/happy_villager",
            false, false, false,
            "Minecraft villager behavior; color and motion control are limited.");
        register("cosmeticslite:particle/heart",
            false, false, false,
            "Minecraft heart behavior; color and motion control are limited.");
    }
    
    /**
     * Register capabilities for an effect ID.
     */
    public static void register(String id, boolean color, boolean movement, boolean scale, String notes) {
        ResourceLocation loc = ResourceLocation.tryParse(id);
        if (loc != null) {
            CAPABILITIES.put(loc, new EffectCapabilities(color, movement, scale, notes != null ? notes : ""));
        }
    }
    
    /**
     * Get capabilities for an effect ID.
     * Returns DEFAULT_CAPABILITIES (all true) if not registered.
     */
    public static EffectCapabilities get(ResourceLocation id) {
        if (id == null) return DEFAULT_CAPABILITIES;
        return CAPABILITIES.getOrDefault(id, DEFAULT_CAPABILITIES);
    }
    
    /**
     * Check if an effect supports color overrides.
     */
    public static boolean supportsColorOverride(ResourceLocation id) {
        return get(id).supportsColorOverride();
    }
    
    /**
     * Check if an effect supports editor movement.
     */
    public static boolean supportsEditorMovement(ResourceLocation id) {
        return get(id).supportsEditorMovement();
    }
    
    /**
     * Check if an effect supports scale overrides.
     */
    public static boolean supportsScaleOverride(ResourceLocation id) {
        return get(id).supportsScaleOverride();
    }
    
    /**
     * Check if a profile ID is one of the vanilla wrapper profiles that should have locked Layers tab.
     */
    public static boolean isProfileVanillaWrapper(ResourceLocation id) {
        if (id == null) return false;
        return id.equals(FLAME_ID) || id.equals(HAPPY_VILLAGER_ID) || id.equals(HEART_ID);
    }
}

