package com.pastlands.cosmeticslite.permission;

import com.pastlands.cosmeticslite.CosmeticDef;
import com.pastlands.cosmeticslite.CosmeticsRegistry;
import com.pastlands.cosmeticslite.particle.config.CosmeticParticleRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.nodes.PermissionNode;

import java.util.EnumSet;
import java.util.Set;

public final class CosmeticsPermissions {

    // -----------------------------
    //  Permission node constants
    // -----------------------------

    public static final String NODE_RANK_VIP       = "cosmeticslite.rank.vip";
    public static final String NODE_RANK_VIP_PLUS  = "cosmeticslite.rank.vip_plus";
    public static final String NODE_RANK_MVP       = "cosmeticslite.rank.mvp";
    public static final String NODE_RANK_MVP_PLUS  = "cosmeticslite.rank.mvp_plus";

    public static final String NODE_FEATURE_BASE_PARTICLES   = "cosmeticslite.feature.base_particles";
    public static final String NODE_FEATURE_BASE_HATS        = "cosmeticslite.feature.base_hats";
    public static final String NODE_FEATURE_CAPES            = "cosmeticslite.feature.capes";
    public static final String NODE_FEATURE_BLENDED_PARTS    = "cosmeticslite.feature.blended_particles";
    public static final String NODE_FEATURE_CUSTOM_HATS      = "cosmeticslite.feature.custom_hats";
    public static final String NODE_FEATURE_PETS             = "cosmeticslite.feature.pets";
    public static final String NODE_FEATURE_MINI_GAMES       = "cosmeticslite.feature.minigames";
    public static final String NODE_FEATURE_PARTICLE_LAB     = "cosmeticslite.feature.particle_lab";

    // Bypass / staff helpers
    public static final String NODE_BYPASS          = "cosmeticslite.bypass";
    public static final String NODE_STAFF           = "cosmeticslite.staff";
    public static final String NODE_PARTICLE_LAB    = "cosmeticslite.particlelab"; // explicit override, if desired

    private CosmeticsPermissions() {
    }
    
    /**
     * Result of a permission check, including whether it's allowed and an optional reason code.
     */
    public record PermissionResult(boolean allowed, String reasonCode) {
        public static PermissionResult allow() {
            return new PermissionResult(true, null);
        }
        
        public static PermissionResult deny(String reasonCode) {
            return new PermissionResult(false, reasonCode);
        }
    }

    // ---------------------------------------------------
    //  Public API used throughout CosmeticsLite
    // ---------------------------------------------------

    /**
     * Main entry point: checks whether this player may use a given cosmetics feature.
     * This should be called on the SERVER side only.
     */
    public static boolean canUseFeature(ServerPlayer player, CosmeticsFeature feature) {
        if (player == null) {
            return false;
        }

        // 1. Special rules for Particle Lab (staff / op / override ONLY - checked BEFORE everything else)
        // This ensures Particle Lab is NEVER granted by ranks or feature nodes
        if (feature == CosmeticsFeature.PARTICLE_LAB) {
            return canUseParticleLabInternal(player);
        }

        // 2. Bypass / staff / op: full access (for all other features)
        if (has(player, CosmeticsPermissionNodes.BYPASS) || 
            has(player, CosmeticsPermissionNodes.STAFF) || 
            isOp(player)) {
            return true;
        }

        // 3. Direct feature node (server owners can override matrix via perms)
        // NOTE: FEATURE_PARTICLE_LAB node is intentionally ignored - Particle Lab requires staff/override/OP
        PermissionNode<Boolean> featureNode = featureToNode(feature);
        if (featureNode != null && has(player, featureNode)) {
            return true;
        }

        // 4. Rank-based mapping (VIP, VIP+, MVP, MVP+)
        // NOTE: Rank matrix does NOT include PARTICLE_LAB - it's staff-only
        Set<CosmeticsFeature> rankFeatures = getFeaturesGrantedByHighestRank(player);
        if (rankFeatures.contains(feature)) {
            return true;
        }

        // 5. Default: no
        return false;
    }

    /**
     * Convenience wrapper just for opening the Particle Lab.
     */
    public static boolean canOpenParticleLab(ServerPlayer player) {
        return canUseFeature(player, CosmeticsFeature.PARTICLE_LAB);
    }

    /**
     * Alias for canOpenParticleLab for backward compatibility.
     */
    public static boolean canUseParticleLab(ServerPlayer player) {
        return canOpenParticleLab(player);
    }

    /**
     * Check if a player can open the cosmetics menu.
     * Allows access if:
     * - Player is OP, has bypass, or has staff permission
     * - Player has explicit menu permission
     * - Player has any rank node (VIP, VIP+, MVP, MVP+)
     * - Player has any feature node
     * - Player has legacy unlock tag (backward compatibility)
     */
    public static boolean canOpenMenu(ServerPlayer player) {
        if (player == null) {
            return false;
        }

        // 1. Bypass / staff / op: full access
        if (has(player, CosmeticsPermissionNodes.BYPASS) || 
            has(player, CosmeticsPermissionNodes.STAFF) || 
            isOp(player)) {
            return true;
        }

        // 2. Explicit menu permission
        if (has(player, CosmeticsPermissionNodes.MENU)) {
            return true;
        }

        // 3. Any rank node
        if (has(player, CosmeticsPermissionNodes.RANK_VIP) ||
            has(player, CosmeticsPermissionNodes.RANK_VIP_PLUS) ||
            has(player, CosmeticsPermissionNodes.RANK_MVP) ||
            has(player, CosmeticsPermissionNodes.RANK_MVP_PLUS)) {
            return true;
        }

        // 4. Any feature node (excluding PARTICLE_LAB - that requires staff/override/OP)
        if (has(player, CosmeticsPermissionNodes.FEATURE_BASE_PARTICLES) ||
            has(player, CosmeticsPermissionNodes.FEATURE_BASE_HATS) ||
            has(player, CosmeticsPermissionNodes.FEATURE_CAPES) ||
            has(player, CosmeticsPermissionNodes.FEATURE_BLENDED_PARTICLES) ||
            has(player, CosmeticsPermissionNodes.FEATURE_CUSTOM_HATS) ||
            has(player, CosmeticsPermissionNodes.FEATURE_PETS) ||
            has(player, CosmeticsPermissionNodes.FEATURE_MINI_GAMES)) {
            return true;
        }

        // 5. Legacy unlock tag (backward compatibility)
        // Check if player has the legacy unlock tag
        if (hasLegacyUnlock(player)) {
            return true;
        }

        // 6. Default: no access
        return false;
    }

    /**
     * Check if player has legacy unlock tag (backward compatibility).
     */
    private static boolean hasLegacyUnlock(ServerPlayer player) {
        if (player == null) return false;
        return player.getPersistentData().getBoolean("cosmeticslite_unlocked");
    }

    /**
     * Check if a player can use a specific hat cosmetic.
     * Determines base vs custom hat based on the pack field.
     */
    public static boolean canUseHat(ServerPlayer player, CosmeticDef def) {
        if (player == null || def == null) {
            return false;
        }

        // Ensure it's actually a hat
        if (!CosmeticsRegistry.TYPE_HATS.equals(def.type())) {
            return false;
        }

        // Determine if it's a base hat or custom hat
        String pack = def.pack();
        boolean isBaseHat = pack != null && pack.equalsIgnoreCase("base");

        if (isBaseHat) {
            return canUseFeature(player, CosmeticsFeature.BASE_HATS);
        } else {
            // Custom hat (animal, fantasy, food, etc.)
            return canUseFeature(player, CosmeticsFeature.CUSTOM_HATS);
        }
    }

    /**
     * Check if a player can use a specific particle cosmetic.
     * Determines base vs blended/lab particle based on naming and registry.
     */
    public static boolean canUseParticle(ServerPlayer player, CosmeticDef def) {
        return checkParticlePermission(player, def).allowed();
    }
    
    /**
     * Centralized permission check for particles that returns a result with reason code.
     * Use this for user-initiated actions where you need to show messages.
     * For background checks (broadcasting, rendering), use canUseParticle() which doesn't message.
     */
    public static PermissionResult checkParticlePermission(ServerPlayer player, CosmeticDef def) {
        if (player == null || def == null) {
            return PermissionResult.deny("null_params");
        }

        // Ensure it's actually a particle
        if (!CosmeticsRegistry.TYPE_PARTICLES.equals(def.type())) {
            return PermissionResult.deny("not_particle");
        }

        ResourceLocation id = def.id();

        // Check if it's a blended particle
        boolean isBlended = CosmeticsRegistry.isBlendedParticle(def);

        // Check if it's from the Particle Lab
        boolean isFromLab = com.pastlands.cosmeticslite.particle.config.CosmeticParticleRegistry.isLabParticle(id);

        CosmeticsFeature requiredFeature;
        if (isBlended || isFromLab) {
            requiredFeature = CosmeticsFeature.BLENDED_PARTICLES;
        } else {
            requiredFeature = CosmeticsFeature.BASE_PARTICLES;
        }
        
        boolean allowed = canUseFeature(player, requiredFeature);
        return allowed ? PermissionResult.allow() : PermissionResult.deny("feature_" + requiredFeature.name());
    }

    // ---------------------------------------------------
    //  Internal helpers
    // ---------------------------------------------------

    /**
     * Internal check for Particle Lab access.
     * Particle Lab is staff-only and cannot be granted by:
     * - Ranks (VIP, MVP, etc.)
     * - Feature nodes (cosmeticslite.feature.particle_lab)
     * - Generic menu access
     * 
     * Only allows:
     * - cosmeticslite.bypass (full access)
     * - cosmeticslite.particlelab (explicit override)
     * - cosmeticslite.staff
     * - OP (permission level 2+)
     */
    private static boolean canUseParticleLabInternal(ServerPlayer player) {
        if (player == null) {
            return false;
        }

        // Check in order: bypass, explicit override, staff, OP
        if (has(player, CosmeticsPermissionNodes.BYPASS)) {
            return true;
        }

        if (has(player, CosmeticsPermissionNodes.PARTICLELAB_OVERRIDE)) {
            return true;
        }

        if (has(player, CosmeticsPermissionNodes.STAFF)) {
            return true;
        }

        if (isOp(player)) {
            return true;
        }

        // No other paths - ranks and feature nodes are intentionally excluded
        return false;
    }

    private static PermissionNode<Boolean> featureToNode(CosmeticsFeature feature) {
        return switch (feature) {
            case BASE_PARTICLES -> CosmeticsPermissionNodes.FEATURE_BASE_PARTICLES;
            case BASE_HATS -> CosmeticsPermissionNodes.FEATURE_BASE_HATS;
            case CAPES -> CosmeticsPermissionNodes.FEATURE_CAPES;
            case BLENDED_PARTICLES -> CosmeticsPermissionNodes.FEATURE_BLENDED_PARTICLES;
            case CUSTOM_HATS -> CosmeticsPermissionNodes.FEATURE_CUSTOM_HATS;
            case PETS -> CosmeticsPermissionNodes.FEATURE_PETS;
            case MINI_GAMES -> CosmeticsPermissionNodes.FEATURE_MINI_GAMES;
            case PARTICLE_LAB -> CosmeticsPermissionNodes.FEATURE_PARTICLE_LAB;
        };
    }

    private static Set<CosmeticsFeature> getFeaturesGrantedByHighestRank(ServerPlayer player) {
        CosmeticsRank highest = getHighestRank(player);

        // Matrix from our design:
        EnumSet<CosmeticsFeature> features = EnumSet.noneOf(CosmeticsFeature.class);

        switch (highest) {
            case VIP -> {
                features.add(CosmeticsFeature.BASE_PARTICLES);
                features.add(CosmeticsFeature.BASE_HATS);
            }

            case VIP_PLUS -> {
                features.add(CosmeticsFeature.BASE_PARTICLES);
                features.add(CosmeticsFeature.BASE_HATS);
                features.add(CosmeticsFeature.CAPES);
                features.add(CosmeticsFeature.BLENDED_PARTICLES);
            }

            case MVP -> {
                features.add(CosmeticsFeature.BASE_PARTICLES);
                features.add(CosmeticsFeature.BASE_HATS);
                features.add(CosmeticsFeature.CAPES);
                features.add(CosmeticsFeature.BLENDED_PARTICLES);
                features.add(CosmeticsFeature.CUSTOM_HATS);
                features.add(CosmeticsFeature.PETS);
            }

            case MVP_PLUS -> {
                features.add(CosmeticsFeature.BASE_PARTICLES);
                features.add(CosmeticsFeature.BASE_HATS);
                features.add(CosmeticsFeature.CAPES);
                features.add(CosmeticsFeature.BLENDED_PARTICLES);
                features.add(CosmeticsFeature.CUSTOM_HATS);
                features.add(CosmeticsFeature.PETS);
                features.add(CosmeticsFeature.MINI_GAMES);
            }

            case NONE -> {
                // nothing
            }
        }

        return features;
    }

    private static CosmeticsRank getHighestRank(ServerPlayer player) {
        // We check in descending order so the "highest" wins.
        if (has(player, CosmeticsPermissionNodes.RANK_MVP_PLUS)) {
            return CosmeticsRank.MVP_PLUS;
        }
        if (has(player, CosmeticsPermissionNodes.RANK_MVP)) {
            return CosmeticsRank.MVP;
        }
        if (has(player, CosmeticsPermissionNodes.RANK_VIP_PLUS)) {
            return CosmeticsRank.VIP_PLUS;
        }
        if (has(player, CosmeticsPermissionNodes.RANK_VIP)) {
            return CosmeticsRank.VIP;
        }
        return CosmeticsRank.NONE;
    }

    // ---------------------------------------------------
    //  Permission / op bridge
    // ---------------------------------------------------

    /**
     * Check if a player has a specific permission node.
     * Uses registered PermissionNode instances instead of dynamic creation.
     */
    private static boolean has(ServerPlayer player, PermissionNode<Boolean> node) {
        if (player == null || node == null) {
            return false;
        }

        try {
            return PermissionAPI.getPermission(player, node);
        } catch (Throwable t) {
            // If PermissionAPI fails, fall through to OP check
        }

        // Fallback: check if player is OP (permission level 2+)
        return player.hasPermissions(2);
    }

    private static boolean isOp(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        // Vanilla op-level check
        return player.getServer() != null && player.getServer().getProfilePermissions(player.getGameProfile()) > 0;
    }
}

