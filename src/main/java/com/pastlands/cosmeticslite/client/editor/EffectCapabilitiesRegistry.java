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
        boolean supportsTint, // Whether the particle can be tinted/colored
        String notes // Optional explanatory text
    ) {}
    
    private static final EffectCapabilities DEFAULT_CAPABILITIES = new EffectCapabilities(
        true,  // supportsColorOverride
        true,  // supportsEditorMovement
        true,  // supportsScaleOverride
        true,  // supportsTint
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
            false, false, false, false,
            "Minecraft flame behavior; color and motion control are limited.");
        register("cosmeticslite:particle/happy_villager",
            false, false, false, false,
            "Minecraft villager behavior; color and motion control are limited.");
        register("cosmeticslite:particle/heart",
            false, false, false, false,
            "Minecraft heart behavior; color and motion control are limited.");
        
        // Register vanilla particle effects that don't support tinting
        // These are the actual minecraft: particle IDs used in world layers
        register("minecraft:heart",
            true, true, true, false,
            "Vanilla heart particle does not support color tinting.");
        register("minecraft:angry_villager",
            true, true, true, false,
            "Vanilla angry_villager particle does not support color tinting.");
        register("minecraft:happy_villager",
            true, true, true, false,
            "Vanilla happy_villager particle does not support color tinting.");
        register("minecraft:flame",
            true, true, true, false,
            "Vanilla flame particle does not support color tinting.");
        register("minecraft:note",
            true, true, true, false,
            "Vanilla note particle does not support color tinting.");
        register("minecraft:portal",
            true, true, true, false,
            "Vanilla portal particle does not support color tinting.");
        register("minecraft:enchant",
            true, true, true, false,
            "Vanilla enchant particle does not support color tinting.");
        register("minecraft:crit",
            true, true, true, false,
            "Vanilla crit particle does not support color tinting.");
        register("minecraft:smoke",
            true, true, true, false,
            "Vanilla smoke particle does not support color tinting.");
        register("minecraft:explosion",
            true, true, true, false,
            "Vanilla explosion particle does not support color tinting.");
        register("minecraft:large_smoke",
            true, true, true, false,
            "Vanilla large_smoke particle does not support color tinting.");
        register("minecraft:cloud",
            true, true, true, false,
            "Vanilla cloud particle does not support color tinting.");
        register("minecraft:totem",
            true, true, true, false,
            "Vanilla totem particle does not support color tinting.");
        register("minecraft:spit",
            true, true, true, false,
            "Vanilla spit particle does not support color tinting.");
        register("minecraft:item",
            true, true, true, false,
            "Vanilla item particle does not support color tinting.");
        register("minecraft:block",
            true, true, true, false,
            "Vanilla block particle does not support color tinting.");
        register("minecraft:falling_dust",
            true, true, true, false,
            "Vanilla falling_dust particle does not support color tinting.");
        register("minecraft:effect",
            true, true, true, false,
            "Vanilla effect particle does not support color tinting.");
        register("minecraft:elder_guardian",
            true, true, true, false,
            "Vanilla elder_guardian particle does not support color tinting.");
        register("minecraft:dragon_breath",
            true, true, true, false,
            "Vanilla dragon_breath particle does not support color tinting.");
        register("minecraft:end_rod",
            true, true, true, false,
            "Vanilla end_rod particle does not support color tinting.");
        register("minecraft:damage_indicator",
            true, true, true, false,
            "Vanilla damage_indicator particle does not support color tinting.");
        register("minecraft:sweep_attack",
            true, true, true, false,
            "Vanilla sweep_attack particle does not support color tinting.");
        register("minecraft:barrier",
            true, true, true, false,
            "Vanilla barrier particle does not support color tinting.");
        register("minecraft:light",
            true, true, true, false,
            "Vanilla light particle does not support color tinting.");
        register("minecraft:sculk_charge",
            true, true, true, false,
            "Vanilla sculk_charge particle does not support color tinting.");
        register("minecraft:sculk_soul",
            true, true, true, false,
            "Vanilla sculk_soul particle does not support color tinting.");
        register("minecraft:shriek",
            true, true, true, false,
            "Vanilla shriek particle does not support color tinting.");
        register("minecraft:sonic_boom",
            true, true, true, false,
            "Vanilla sonic_boom particle does not support color tinting.");
        register("minecraft:splash",
            true, true, true, false,
            "Vanilla splash particle does not support color tinting.");
        register("minecraft:bubble",
            true, true, true, false,
            "Vanilla bubble particle does not support color tinting.");
        register("minecraft:bubble_column_up",
            true, true, true, false,
            "Vanilla bubble_column_up particle does not support color tinting.");
        register("minecraft:bubble_column_down",
            true, true, true, false,
            "Vanilla bubble_column_down particle does not support color tinting.");
        register("minecraft:bubble_pop",
            true, true, true, false,
            "Vanilla bubble_pop particle does not support color tinting.");
        
        // Note: dust, dust_color_transition, entity_effect, ambient_entity_effect are tintable
        // They will use DEFAULT_CAPABILITIES (supportsTint = true)
    }
    
    /**
     * Register capabilities for an effect ID.
     */
    public static void register(String id, boolean color, boolean movement, boolean scale, boolean tint, String notes) {
        ResourceLocation loc = ResourceLocation.tryParse(id);
        if (loc != null) {
            CAPABILITIES.put(loc, new EffectCapabilities(color, movement, scale, tint, notes != null ? notes : ""));
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
     * Check if an effect supports color tinting.
     */
    public static boolean supportsTint(ResourceLocation id) {
        return get(id).supportsTint();
    }
    
    /**
     * Check if a profile ID is one of the vanilla wrapper profiles that should have locked Layers tab.
     */
    public static boolean isProfileVanillaWrapper(ResourceLocation id) {
        if (id == null) return false;
        return id.equals(FLAME_ID) || id.equals(HAPPY_VILLAGER_ID) || id.equals(HEART_ID);
    }
}

