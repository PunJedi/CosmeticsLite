package com.pastlands.cosmeticslite.particle;

import com.pastlands.cosmeticslite.client.CosmeticParticleClientCatalog;
import com.pastlands.cosmeticslite.particle.config.CosmeticParticleRegistry;
import com.pastlands.cosmeticslite.particle.config.ParticleDefinition;
import com.pastlands.cosmeticslite.particle.config.ParticlePreviewState;
import net.minecraft.resources.ResourceLocation;

/**
 * Centralized resolver for particle profiles with 3-step fallback:
 * 1. Registry (Particle Lab / JSON) - if has world layers, use it
 * 2. Legacy built-ins (ParticleProfiles) - if registry missing or has 0 world layers
 * 3. Default (gear_spark) - if nothing found
 * 
 * Handles the case where registry has 0 world layers by merging registry layers
 * with legacy world layers.
 * 
 * Uses CosmeticParticleCatalog to determine the profile ID from the cosmetic entry.
 */
public final class ParticleProfileResolver {
    
    /**
     * Result of profile resolution, including the source and profile ID used.
     */
    public record ResolutionResult(
        ParticleProfiles.ParticleProfile profile,
        String source,  // "profile-json", "simple-catalog", "fallback-default", "legacy", "merge-legacy", or "none"
        ResourceLocation profileId,  // The profile ID that was resolved
        com.pastlands.cosmeticslite.particle.CosmeticParticleEntry catalogEntry  // Catalog entry if available
    ) {
        // Backward compatibility constructor
        public ResolutionResult(ParticleProfiles.ParticleProfile profile, String source, ResourceLocation profileId) {
            this(profile, source, profileId, null);
        }
    }
    
    private ParticleProfileResolver() {}
    
    /**
     * Derive profile ID from a cosmetic entry.
     * For cosmeticslite namespace particle IDs, use as-is.
     * For minecraft namespace particle IDs, convert to cosmeticslite:particle/<name> format.
     */
    private static ResourceLocation deriveProfileId(CosmeticParticleEntry entry, ResourceLocation cosmeticId) {
        ResourceLocation particleId = entry.particleId();
        
        // If particle ID is in cosmeticslite namespace, use it directly as profile ID
        if ("cosmeticslite".equals(particleId.getNamespace())) {
            return particleId;
        }
        
        // For minecraft namespace (base particles), convert to cosmeticslite:particle/<name>
        if ("minecraft".equals(particleId.getNamespace())) {
            String path = "particle/" + particleId.getPath();
            return ResourceLocation.fromNamespaceAndPath("cosmeticslite", path);
        }
        
        // Fallback: try to derive from cosmetic ID
        String path = cosmeticId.getPath();
        if (path.startsWith("cosmetic/")) {
            path = "particle/" + path.substring("cosmetic/".length());
        } else if (!path.startsWith("particle/")) {
            path = "particle/" + path;
        }
        return ResourceLocation.fromNamespaceAndPath("cosmeticslite", path);
    }
    
    /**
     * Resolve particle profile for a cosmetic ID with preview state/override checks.
     * This is the full resolver that handles preview overrides and unsaved editor state.
     * 
     * @param cosmeticId The cosmetic ID to resolve
     * @param checkPreviewState If true, check ParticlePreviewState for unsaved editor values
     * @param checkPreviewOverride If true, check preview override map
     * @return Resolution result with profile, source, and profile ID used
     */
    public static ResolutionResult resolveWithPreview(ResourceLocation cosmeticId, 
                                          boolean checkPreviewState,
                                          boolean checkPreviewOverride) {
        if (cosmeticId == null || isAir(cosmeticId)) {
            return new ResolutionResult(null, "none", null, null);
        }
        
        // Step 0: Get catalog entry to determine profile ID
        CosmeticParticleEntry entry = CosmeticParticleClientCatalog.get(cosmeticId);
        ResourceLocation entryProfileId = null;
        
        if (entry == null) {
            // Not a known particle cosmetic - try to resolve directly using cosmeticId
            // This handles cases where cosmeticId is already a particle ID
            entryProfileId = deriveProfileIdFromCosmeticId(cosmeticId);
        } else {
            // Derive profile ID from entry
            entryProfileId = deriveProfileId(entry, cosmeticId);
        }
        
        ResourceLocation resolvedProfileId = entryProfileId;
        
        // Step 1: Check preview override (highest priority)
        if (checkPreviewOverride) {
            var overrideDef = ParticlePreviewState.getPreviewOverride(cosmeticId);
            if (overrideDef != null) {
                var registry = CosmeticParticleRegistry.toParticleProfile(overrideDef);
                if (registry != null) {
                    int registryWorldLayers = (registry.worldLayers() != null) ? registry.worldLayers().size() : 0;
                    if (registryWorldLayers > 0) {
                        return new ResolutionResult(registry, "preview_override", resolvedProfileId, null);
                    }
                    // Registry has 0 world layers - try to merge with legacy
                    var legacy = ParticleProfiles.get(resolvedProfileId);
                    int legacyWorldLayers = (legacy != null && legacy.worldLayers() != null) ? legacy.worldLayers().size() : 0;
                    if (legacy != null && legacyWorldLayers > 0) {
                        var merged = mergeRegistryAndLegacy(registry, legacy);
                        return new ResolutionResult(merged, "preview_override-merge-legacy", resolvedProfileId, null);
                    }
                    // No legacy - return null (will fall back to default in renderer)
                    return new ResolutionResult(null, "preview_override-none", resolvedProfileId, null);
                }
            }
        }
        
        // Step 2: Check preview state (unsaved editor values)
        if (checkPreviewState && ParticlePreviewState.isActive() 
            && ParticlePreviewState.getCurrentPreviewId() != null 
            && ParticlePreviewState.getCurrentPreviewId().equals(cosmeticId)) {
            var previewDef = ParticlePreviewState.getCurrentPreviewDefinition();
            if (previewDef != null) {
                var registry = CosmeticParticleRegistry.toParticleProfile(previewDef);
                if (registry != null) {
                    int registryWorldLayers = (registry.worldLayers() != null) ? registry.worldLayers().size() : 0;
                    if (registryWorldLayers > 0) {
                        return new ResolutionResult(registry, "preview_state", resolvedProfileId, null);
                    }
                    // Registry has 0 world layers - try to merge with legacy
                    var legacy = ParticleProfiles.get(resolvedProfileId);
                    int legacyWorldLayers = (legacy != null && legacy.worldLayers() != null) ? legacy.worldLayers().size() : 0;
                    if (legacy != null && legacyWorldLayers > 0) {
                        var merged = mergeRegistryAndLegacy(registry, legacy);
                        return new ResolutionResult(merged, "preview_state-merge-legacy", resolvedProfileId, null);
                    }
                    // No legacy - return null (will fall back to default in renderer)
                    return new ResolutionResult(null, "preview_state-none", resolvedProfileId, null);
                }
            }
        }
        
        // Step 3: Resolve profile using clean flow (registry → catalog → fallback)
        return resolve(cosmeticId);
    }
    
    /**
     * Resolve profile ID from either a cosmetic ID or particle ID.
     * 
     * @param id The ID to resolve (can be cosmetic or particle ID)
     * @return The resolved profile ID (particle ID), or null if cannot be resolved
     */
    public static ResourceLocation resolveProfileIdFromAnyId(ResourceLocation id) {
        if (id == null) return null;
        
        // If it's already a particle ID, use it directly
        if ("cosmeticslite".equals(id.getNamespace()) && id.getPath().startsWith("particle/")) {
            return id;
        }
        
        // If it's a cosmetic ID, look up the catalog entry
        if ("cosmeticslite".equals(id.getNamespace()) && id.getPath().startsWith("cosmetic/")) {
            CosmeticParticleEntry entry = CosmeticParticleClientCatalog.get(id);
            if (entry != null) {
                // If entry.particleId is cosmeticslite:particle/..., use that
                ResourceLocation particleId = entry.particleId();
                if ("cosmeticslite".equals(particleId.getNamespace())
                    && particleId.getPath().startsWith("particle/")) {
                    return particleId;
                }
            }
        }
        
        // No profile id → return null and let caller choose simple/fallback paths
        return null;
    }
    
    /**
     * Clean resolution flow following the spec:
     * 0. Preview override first (preview-live mode) - highest priority
     * 1. Try lab / JSON profile from registry (profile-json mode)
     * 2. Try catalog + simple pattern (simple-catalog mode)
     * 3. True fallback → gear spark (fallback-default mode)
     * 
     * @param id An ID which MAY be a cosmetic ID OR a particle ID
     * @return Resolution result with profile, mode, and catalog entry
     */
    public static ResolutionResult resolve(ResourceLocation id) {
        if (id == null || isAir(id)) {
            return new ResolutionResult(null, "fallback-default", null, null);
        }
        
        // Step 0: Resolve profile ID from cosmetic or particle ID
        ResourceLocation profileId = resolveProfileIdFromAnyId(id);
        
        // Step 0.5: Preview override first (highest priority) - live working copy from Particle Lab
        if (profileId != null) {
            ParticleDefinition previewOverride = ParticlePreviewState.getPreviewOverride(profileId);
            if (previewOverride != null && previewOverride.worldLayers() != null && !previewOverride.worldLayers().isEmpty()) {
                // Convert to ParticleProfile for rendering
                ParticleProfiles.ParticleProfile profile = CosmeticParticleRegistry.toParticleProfile(previewOverride);
                if (profile != null) {
                    return new ResolutionResult(profile, "preview-live", profileId, null);
                }
            }
        }
        
        // Step 1: Try lab / JSON profile from registry (PRIMARY SOURCE OF TRUTH)
        if (profileId != null) {
            ParticleDefinition def = CosmeticParticleRegistry.get(profileId);
            if (def != null && def.worldLayers() != null && !def.worldLayers().isEmpty()) {
                // Convert to ParticleProfile for rendering
                ParticleProfiles.ParticleProfile profile = CosmeticParticleRegistry.toParticleProfile(def);
                if (profile != null) {
                    return new ResolutionResult(profile, "profile-json", profileId, null);
                }
            }
        }
        
        // Step 3: Try catalog + simple pattern (for vanilla minecraft:* particles)
        // Map cosmetic ID: if id is already cosmetic ID, use it; if particle ID, convert
        ResourceLocation cosmeticId = id;
        if (profileId != null && profileId.getPath().startsWith("particle/")) {
            String path = profileId.getPath().substring("particle/".length());
            cosmeticId = ResourceLocation.fromNamespaceAndPath(profileId.getNamespace(), "cosmetic/" + path);
        }
        
        CosmeticParticleEntry entry = CosmeticParticleClientCatalog.get(cosmeticId);
        if (entry != null) {
            return new ResolutionResult(null, "simple-catalog", profileId, entry);
        }
        
        // Step 4: True fallback → gear spark
        return new ResolutionResult(null, "fallback-default", profileId, null);
    }
    
    /**
     * Resolve a profile by profile ID using the exact control flow:
     * 1. If registry exists AND has world layers → use it as-is (profile-json mode)
     * 2. If legacy exists AND has world layers → merge (if registry exists) or return legacy
     * 3. No registry, no legacy → return null (renderer handles default)
     * 
     * Never returns a profile with 0 world layers.
     * 
     * @deprecated Use resolve(ResourceLocation) instead for cleaner flow
     */
    @Deprecated
    public static ResolutionResult resolveProfile(ResourceLocation profileId) {
        if (profileId == null) {
            return new ResolutionResult(null, "none", null, null);
        }
        
        // Get registry profile (Particle Lab / JSON) - PRIMARY SOURCE OF TRUTH
        ParticleProfiles.ParticleProfile registry = null;
        ParticleDefinition registryDef = CosmeticParticleRegistry.get(profileId);
        if (registryDef != null) {
            registry = CosmeticParticleRegistry.toParticleProfile(registryDef);
        }
        
        // Get legacy profile (old hard-wired map from ParticleProfiles)
        ParticleProfiles.ParticleProfile legacy = ParticleProfiles.get(profileId);
        
        int registryWorldLayers = (registry != null && registry.worldLayers() != null) ? registry.worldLayers().size() : 0;
        int legacyWorldLayers = (legacy != null && legacy.worldLayers() != null) ? legacy.worldLayers().size() : 0;
        
        // 1) If registry exists AND has world layers → use it as-is (profile-json mode)
        if (registry != null && registryWorldLayers > 0) {
            return new ResolutionResult(registry, "profile-json", profileId, null);
        }
        
        // 2) If legacy exists AND has world layers → either merge or return it
        if (legacy != null && legacyWorldLayers > 0) {
            if (registry != null) {
                // JSON overrides behavior/colors, legacy supplies world pattern
                ParticleProfiles.ParticleProfile merged = mergeRegistryAndLegacy(registry, legacy);
                return new ResolutionResult(merged, "merge-legacy", profileId, null);
            } else {
                return new ResolutionResult(legacy, "legacy", profileId, null);
            }
        }
        
        // 3) No registry, no legacy, or both have 0 world layers → truly missing
        return new ResolutionResult(null, "none", profileId, null);
    }
    
    /**
     * Merge registry profile (with layers but 0 world layers) with legacy world layers.
     * Keeps registry's Layers tab behavior (movement, colors, etc.) and uses legacy's world layer list.
     */
    private static ParticleProfiles.ParticleProfile mergeRegistryAndLegacy(
            ParticleProfiles.ParticleProfile registry,
            ParticleProfiles.ParticleProfile legacy) {
        return new ParticleProfiles.ParticleProfile(
            registry.id(),
            registry.cosmeticId(),
            registry.layers(),      // From registry (Layers tab behavior)
            legacy.worldLayers()    // From legacy (world pattern)
        );
    }
    
    /**
     * Derive profile ID from cosmetic ID when no catalog entry exists.
     */
    private static ResourceLocation deriveProfileIdFromCosmeticId(ResourceLocation cosmeticId) {
        String path = cosmeticId.getPath();
        if (path.startsWith("cosmetic/")) {
            path = "particle/" + path.substring("cosmetic/".length());
        } else if (!path.startsWith("particle/")) {
            path = "particle/" + path;
        }
        return ResourceLocation.fromNamespaceAndPath("cosmeticslite", path);
    }
    
    private static boolean isAir(ResourceLocation id) {
        return id == null || ("minecraft".equals(id.getNamespace()) && "air".equals(id.getPath()));
    }
}



