package com.pastlands.cosmeticslite.permission;

import com.pastlands.cosmeticslite.CosmeticsLite;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;

/**
 * Single source of truth for all CosmeticsLite PermissionNode instances.
 * Registers all permission nodes via Forge's PermissionGatherEvent.
 */
@Mod.EventBusSubscriber(modid = CosmeticsLite.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CosmeticsPermissionNodes {

    private CosmeticsPermissionNodes() {}

    // ============================================================================================
    // Menu/Admin nodes
    // ============================================================================================

    /** Menu access permission. Default: false (requires any cosmetics permission). */
    public static final PermissionNode<Boolean> MENU = new PermissionNode<>(
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "menu"),
            PermissionTypes.BOOLEAN,
            (player, uuid, ctx) -> false // default: no permission
    );

    /** Admin commands permission. Default: OPs only. */
    public static final PermissionNode<Boolean> ADMIN = new PermissionNode<>(
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "admin"),
            PermissionTypes.BOOLEAN,
            (player, uuid, ctx) -> player.hasPermissions(2) // default: OPs only
    );

    // ============================================================================================
    // Rank nodes
    // ============================================================================================

    public static final PermissionNode<Boolean> RANK_VIP = new PermissionNode<>(
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "rank.vip"),
            PermissionTypes.BOOLEAN,
            (player, uuid, ctx) -> false
    );

    public static final PermissionNode<Boolean> RANK_VIP_PLUS = new PermissionNode<>(
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "rank.vip_plus"),
            PermissionTypes.BOOLEAN,
            (player, uuid, ctx) -> false
    );

    public static final PermissionNode<Boolean> RANK_MVP = new PermissionNode<>(
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "rank.mvp"),
            PermissionTypes.BOOLEAN,
            (player, uuid, ctx) -> false
    );

    public static final PermissionNode<Boolean> RANK_MVP_PLUS = new PermissionNode<>(
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "rank.mvp_plus"),
            PermissionTypes.BOOLEAN,
            (player, uuid, ctx) -> false
    );

    // ============================================================================================
    // Feature nodes
    // ============================================================================================

    public static final PermissionNode<Boolean> FEATURE_BASE_PARTICLES = new PermissionNode<>(
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "feature.base_particles"),
            PermissionTypes.BOOLEAN,
            (player, uuid, ctx) -> false
    );

    public static final PermissionNode<Boolean> FEATURE_BASE_HATS = new PermissionNode<>(
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "feature.base_hats"),
            PermissionTypes.BOOLEAN,
            (player, uuid, ctx) -> false
    );

    public static final PermissionNode<Boolean> FEATURE_CAPES = new PermissionNode<>(
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "feature.capes"),
            PermissionTypes.BOOLEAN,
            (player, uuid, ctx) -> false
    );

    public static final PermissionNode<Boolean> FEATURE_BLENDED_PARTICLES = new PermissionNode<>(
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "feature.blended_particles"),
            PermissionTypes.BOOLEAN,
            (player, uuid, ctx) -> false
    );

    public static final PermissionNode<Boolean> FEATURE_CUSTOM_HATS = new PermissionNode<>(
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "feature.custom_hats"),
            PermissionTypes.BOOLEAN,
            (player, uuid, ctx) -> false
    );

    public static final PermissionNode<Boolean> FEATURE_PETS = new PermissionNode<>(
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "feature.pets"),
            PermissionTypes.BOOLEAN,
            (player, uuid, ctx) -> false
    );

    public static final PermissionNode<Boolean> FEATURE_MINI_GAMES = new PermissionNode<>(
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "feature.minigames"),
            PermissionTypes.BOOLEAN,
            (player, uuid, ctx) -> false
    );

    public static final PermissionNode<Boolean> FEATURE_PARTICLE_LAB = new PermissionNode<>(
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "feature.particle_lab"),
            PermissionTypes.BOOLEAN,
            (player, uuid, ctx) -> false
    );

    // ============================================================================================
    // Helper/Bypass nodes
    // ============================================================================================

    public static final PermissionNode<Boolean> BYPASS = new PermissionNode<>(
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "bypass"),
            PermissionTypes.BOOLEAN,
            (player, uuid, ctx) -> false
    );

    public static final PermissionNode<Boolean> STAFF = new PermissionNode<>(
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "staff"),
            PermissionTypes.BOOLEAN,
            (player, uuid, ctx) -> false
    );

    public static final PermissionNode<Boolean> PARTICLELAB_OVERRIDE = new PermissionNode<>(
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "particlelab"),
            PermissionTypes.BOOLEAN,
            (player, uuid, ctx) -> false
    );

    // ============================================================================================
    // Registration
    // ============================================================================================

    @SubscribeEvent
    public static void onPermissionGather(PermissionGatherEvent.Nodes event) {
        // Register all permission nodes
        event.addNodes(
                MENU,
                ADMIN,
                RANK_VIP,
                RANK_VIP_PLUS,
                RANK_MVP,
                RANK_MVP_PLUS,
                FEATURE_BASE_PARTICLES,
                FEATURE_BASE_HATS,
                FEATURE_CAPES,
                FEATURE_BLENDED_PARTICLES,
                FEATURE_CUSTOM_HATS,
                FEATURE_PETS,
                FEATURE_MINI_GAMES,
                FEATURE_PARTICLE_LAB,
                BYPASS,
                STAFF,
                PARTICLELAB_OVERRIDE
        );

        CosmeticsLite.LOGGER.info("[{}] Permission nodes registered via PermissionGatherEvent", CosmeticsLite.MODID);
    }
}
