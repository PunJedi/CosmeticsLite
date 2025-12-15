package com.pastlands.cosmeticslite.permission;

import com.pastlands.cosmeticslite.CosmeticDef;
import com.pastlands.cosmeticslite.CosmeticsRegistry;
import com.pastlands.cosmeticslite.particle.config.CosmeticParticleRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    // Cache for PermissionNode instances to avoid recreating them
    private static final ConcurrentHashMap<String, PermissionNode<Boolean>> NODE_CACHE = new ConcurrentHashMap<>();

    private CosmeticsPermissions() {
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

        // 1. Bypass / staff / op: full access
        if (hasNode(player, NODE_BYPASS) || hasNode(player, NODE_STAFF) || isOp(player)) {
            return true;
        }

        // 2. Special rules for Particle Lab (staff / op only)
        if (feature == CosmeticsFeature.PARTICLE_LAB) {
            return canUseParticleLabInternal(player);
        }

        // 3. Direct feature node (server owners can override matrix via perms)
        if (hasNode(player, featureToNode(feature))) {
            return true;
        }

        // 4. Rank-based mapping (VIP, VIP+, MVP, MVP+)
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
        if (player == null || def == null) {
            return false;
        }

        // Ensure it's actually a particle
        if (!CosmeticsRegistry.TYPE_PARTICLES.equals(def.type())) {
            return false;
        }

        ResourceLocation id = def.id();

        // Check if it's a blended particle
        boolean isBlended = CosmeticsRegistry.isBlendedParticle(def);

        // Check if it's from the Particle Lab
        boolean isFromLab = CosmeticParticleRegistry.isLabParticle(id);

        if (isBlended || isFromLab) {
            return canUseFeature(player, CosmeticsFeature.BLENDED_PARTICLES);
        } else {
            return canUseFeature(player, CosmeticsFeature.BASE_PARTICLES);
        }
    }

    // ---------------------------------------------------
    //  Internal helpers
    // ---------------------------------------------------

    private static boolean canUseParticleLabInternal(ServerPlayer player) {
        // Staff / op / explicit node
        if (hasNode(player, NODE_PARTICLE_LAB)) {
            return true;
        }

        if (hasNode(player, NODE_STAFF)) {
            return true;
        }

        if (isOp(player)) {
            return true;
        }

        return false;
    }

    private static String featureToNode(CosmeticsFeature feature) {
        return switch (feature) {
            case BASE_PARTICLES -> NODE_FEATURE_BASE_PARTICLES;
            case BASE_HATS -> NODE_FEATURE_BASE_HATS;
            case CAPES -> NODE_FEATURE_CAPES;
            case BLENDED_PARTICLES -> NODE_FEATURE_BLENDED_PARTS;
            case CUSTOM_HATS -> NODE_FEATURE_CUSTOM_HATS;
            case PETS -> NODE_FEATURE_PETS;
            case MINI_GAMES -> NODE_FEATURE_MINI_GAMES;
            case PARTICLE_LAB -> NODE_FEATURE_PARTICLE_LAB;
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
        if (hasNode(player, NODE_RANK_MVP_PLUS)) {
            return CosmeticsRank.MVP_PLUS;
        }
        if (hasNode(player, NODE_RANK_MVP)) {
            return CosmeticsRank.MVP;
        }
        if (hasNode(player, NODE_RANK_VIP_PLUS)) {
            return CosmeticsRank.VIP_PLUS;
        }
        if (hasNode(player, NODE_RANK_VIP)) {
            return CosmeticsRank.VIP;
        }
        return CosmeticsRank.NONE;
    }

    // ---------------------------------------------------
    //  Permission / op bridge
    // ---------------------------------------------------

    private static boolean hasNode(ServerPlayer player, String node) {
        if (player == null || node == null || node.isEmpty()) {
            return false;
        }

        try {
            // Get or create a PermissionNode for this string
            PermissionNode<Boolean> permNode = NODE_CACHE.computeIfAbsent(node, n -> {
                // Parse the node string (e.g., "cosmeticslite.rank.vip")
                String[] parts = n.split("\\.");
                if (parts.length < 2) {
                    return null;
                }
                String namespace = parts[0];
                String path = n.substring(namespace.length() + 1); // Everything after the first dot
                
                // Create a PermissionNode with default false (no permission by default)
                return new PermissionNode<>(namespace, path, PermissionTypes.BOOLEAN,
                        (p, uuid, ctx) -> false);
            });

            if (permNode != null) {
                return PermissionAPI.getPermission(player, permNode);
            }
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

