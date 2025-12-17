package com.pastlands.cosmeticslite.particle;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-only runtime map for particle profiles loaded from JSON.
 * Profiles define multi-layer behaviors and world rendering styles.
 */
@OnlyIn(Dist.CLIENT)
public final class ParticleProfiles {
    private static final Map<ResourceLocation, ParticleProfile> BY_ID = new ConcurrentHashMap<>();

    private ParticleProfiles() {}

    @org.jetbrains.annotations.Nullable
    public static ParticleProfile get(ResourceLocation cosmeticId) {
        return (cosmeticId != null) ? BY_ID.get(cosmeticId) : null;
    }

    public static void replaceAll(Map<ResourceLocation, ParticleProfile> newProfiles) {
        BY_ID.clear();
        if (newProfiles != null) {
            BY_ID.putAll(newProfiles);
        }
        // Temporary debug: log a handful of keys to understand key format
        if (!BY_ID.isEmpty()) {
            com.pastlands.cosmeticslite.CosmeticsLite.LOGGER.info("[cosmeticslite] ParticleProfiles keys example: {}",
                BY_ID.keySet().stream().limit(10).toList());
        }
    }

    public static Set<ResourceLocation> debugKeys() {
        return Collections.unmodifiableSet(BY_ID.keySet());
    }

    /**
     * Complete particle profile for a cosmetic.
     */
    public record ParticleProfile(
        ResourceLocation id,               // file/profile id
        ResourceLocation cosmeticId,       // the cosmetic this applies to
        List<GuiLayerConfig> layers,
        List<WorldLayerConfig> worldLayers
    ) {
        public ParticleProfile {
            layers = (layers != null) ? List.copyOf(layers) : List.of();
            worldLayers = (worldLayers != null) ? List.copyOf(worldLayers) : List.of();
        }
    }

    /**
     * Configuration for a single GUI layer in the preview pane.
     */
    public record GuiLayerConfig(
        Movement movement,
        List<Integer> colors,
        int lifespan,
        int spawnInterval,
        int size,
        float speed,
        float weight,
        float previewScale
    ) {
        public GuiLayerConfig {
            colors = (colors != null) ? List.copyOf(colors) : List.of();
            lifespan = Math.max(10, Math.min(200, lifespan));
            spawnInterval = Math.max(1, Math.min(20, spawnInterval));
            size = Math.max(1, Math.min(5, size));
            speed = Math.max(0.1f, Math.min(5.0f, speed));
            weight = Math.max(0.1f, Math.min(5.0f, weight));
            previewScale = Math.max(0.5f, Math.min(3.0f, previewScale));
        }
    }

    /**
     * World rendering configuration for a single particle layer in-game.
     */
    public record WorldLayerConfig(
        ResourceLocation effect,
        String style,
        float radius,
        float heightFactor,
        int count,
        float speedY
    ) {
        public WorldLayerConfig {
            if (effect == null) {
                throw new IllegalArgumentException("WorldLayerConfig.effect cannot be null");
            }
            style = (style != null) ? style : "default";
            radius = Math.max(0.1f, Math.min(2.0f, radius));
            heightFactor = Math.max(0.0f, Math.min(2.0f, heightFactor));
            count = Math.max(1, Math.min(20, count));
            speedY = Math.max(0.0f, Math.min(0.1f, speedY));
        }
    }

    /**
     * Movement types matching ParticlePreviewPane.EffectBehavior.Movement enum.
     */
    public enum Movement {
        FLOAT_UP,
        BOUNCE_UP,
        DRIFT_UP,
        FLICKER_UP,
        BURST,
        SWIRL,
        FALL_DOWN,
        BUBBLE_POP,
        MUSICAL_FLOAT,
        DEFAULT
    }
}

