package com.pastlands.cosmeticslite.particle;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Legacy built-in particle cosmetics.
 * Contains the original hard-wired particle cosmetic definitions that existed before the catalog system.
 * These are seeded into the catalog on server startup and synced to clients.
 */
public final class LegacyCosmeticParticles {
    private static final Set<ResourceLocation> BUILTIN_IDS = new HashSet<>();

    private LegacyCosmeticParticles() {}

    /**
     * Base particle effect names from seedDevParticlesUnlocked().
     * These correspond to vanilla Minecraft particle effects.
     */
    private static final String[] BASE_PARTICLE_NAMES = {
        "ambient_entity_effect", "angry_villager", "ash", "bubble", "bubble_column_up", "bubble_pop",
        "campfire_cosy_smoke", "campfire_signal_smoke", "cherry_leaves", "cloud", "composter", "crit",
        "current_down", "damage_indicator", "dolphin", "dragon_breath", "dripping_dripstone_lava",
        "dripping_dripstone_water", "dripping_honey", "dripping_lava", "dripping_obsidian_tear",
        "dripping_water", "elder_guardian", "electric_spark", "enchant", "enchanted_hit", "end_rod",
        "entity_effect", "explosion", "explosion_emitter", "falling_dripstone_lava", "falling_dripstone_water",
        "falling_honey", "flame", "flash", "glow", "happy_villager", "heart", "large_smoke", "lava", "mycelium",
        "nautilus", "note", "poof", "portal", "rain", "sculk_charge", "sculk_charge_pop", "sculk_soul", "scrape",
        "shriek", "smoke", "sneeze", "soul", "soul_fire_flame", "spit", "spore_blossom_air", "spore_blossom_ambient",
        "squid_ink", "sweep_attack", "splash", "totem_of_undying", "underwater", "white_ash", "witch"
    };

    /**
     * Get all legacy built-in particle entries.
     * These match the original hard-wired definitions from CosmeticsRegistry.
     */
    public static List<CosmeticParticleEntry> builtins() {
        List<CosmeticParticleEntry> list = new ArrayList<>();
        BUILTIN_IDS.clear();

        // 1) Base particles (from seedDevParticlesUnlocked)
        for (String name : BASE_PARTICLE_NAMES) {
            list.add(baseParticle(name));
        }

        // 2) Blended particles (from seedDevBlendedParticlesUnlocked)
        list.add(blendedParticle("heart_blended", "Blended Heart Halo", Items.PINK_DYE, "heart"));
        list.add(blendedParticle("happy_villager_blended", "Blended Emerald Aura", Items.EMERALD, "happy_villager"));
        list.add(blendedParticle("flame_blended", "Blended Flame Swirl", Items.BLAZE_POWDER, "flame"));
        list.add(blendedParticle("frost_aura_blended", "Blended Frost Aura", Items.DIAMOND, "cloud"));
        list.add(blendedParticle("shadow_wisps_blended", "Blended Shadow Wisps", Items.ENDER_PEARL, "smoke"));
        list.add(blendedParticle("starfall_crown_blended", "Blended Starfall Crown", Items.NETHER_STAR, "end_rod"));
        list.add(blendedParticle("storm_aura_blended", "Blended Storm Aura", Items.LIGHTNING_ROD, "electric_spark"));
        list.add(blendedParticle("flame_cape_blended", "Blended Flame Cape", Items.BLAZE_ROD, "flame"));
        list.add(blendedParticle("bubbling_ground_blended", "Blended Bubbling Ground", Items.SLIME_BALL, "bubble"));
        list.add(blendedParticle("angel_wings_blended", "Blended Angel Wings", Items.FEATHER, "cloud"));
        list.add(blendedParticle("sword_belt_blended", "Blended Sword Belt", Items.IRON_SWORD, "sweep_attack"));
        list.add(blendedParticle("arcane_spiral_blended", "Blended Arcane Spiral", Items.END_CRYSTAL, "enchant"));

        // Sort: non-blended first, then blended (by display name)
        list.sort(Comparator
            .comparingInt((CosmeticParticleEntry e) -> isBlendedId(e.id()) ? 1 : 0)
            .thenComparing(e -> e.displayName().getString().toLowerCase(Locale.ROOT))
        );

        return List.copyOf(list);
    }

    /**
     * Create a base particle entry (maps to vanilla Minecraft particle).
     * Matches the old makeParticleDef() logic.
     */
    private static CosmeticParticleEntry baseParticle(String effectName) {
        // ID: cosmeticslite:cosmetic/<effectName> (matches catalog convention: particle/X -> cosmetic/X)
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("cosmeticslite", "cosmetic/" + effectName);
        
        // Particle ID: For base particles, use minecraft:effectName (matches old system's properties["effect"])
        // The old system used properties["effect"] = "minecraft:<effectName>" for base particles
        ResourceLocation particleId = ResourceLocation.fromNamespaceAndPath("minecraft", effectName);
        
        // Display name: converted from effect name (e.g. "happy_villager" -> "Happy Villager")
        String displayName = toTitle(effectName);
        
        // Icon: chosen based on effect name
        ResourceLocation iconItemId = chooseIconForEffect(effectName);
        
        BUILTIN_IDS.add(id);
        
        return CosmeticParticleEntry.builtin(
            id,
            particleId,
            displayName,
            CosmeticParticleEntry.Slot.AURA,
            new CosmeticParticleEntry.Icon(iconItemId, null)
        );
    }

    /**
     * Create a blended particle entry.
     * Matches the old makeBlendedParticleDef() logic.
     */
    private static CosmeticParticleEntry blendedParticle(
            String idPath,
            String title,
            Item iconItem,
            String baseEffectName) {
        // ID: cosmeticslite:cosmetic/<idPath> (matches catalog convention: particle/X -> cosmetic/X)
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("cosmeticslite", "cosmetic/" + idPath);
        
        // Particle ID: cosmeticslite:particle/<idPath> (blended particles have cosmeticslite definitions)
        ResourceLocation particleId = ResourceLocation.fromNamespaceAndPath("cosmeticslite", "particle/" + idPath);
        
        // Icon: from the provided Item
        ResourceLocation iconItemId = BuiltInRegistries.ITEM.getKey(iconItem);
        
        BUILTIN_IDS.add(id);
        
        return CosmeticParticleEntry.builtin(
            id,
            particleId,
            title,
            CosmeticParticleEntry.Slot.AURA,
            new CosmeticParticleEntry.Icon(iconItemId, null)
        );
    }

    /**
     * Choose icon item for a particle effect name.
     * Copied from CosmeticsRegistry.chooseIconForEffect().
     */
    private static ResourceLocation chooseIconForEffect(String n) {
        n = n.toLowerCase(Locale.ROOT);
        if (n.equals("heart")) return BuiltInRegistries.ITEM.getKey(Items.PINK_DYE);
        if (n.equals("happy_villager")) return BuiltInRegistries.ITEM.getKey(Items.EMERALD);
        if (n.equals("angry_villager")) return BuiltInRegistries.ITEM.getKey(Items.RED_DYE);
        if (n.equals("flame")) return BuiltInRegistries.ITEM.getKey(Items.BLAZE_POWDER);
        if (n.equals("soul_fire_flame") || n.contains("soul")) return BuiltInRegistries.ITEM.getKey(Items.SOUL_TORCH);
        if (n.equals("enchant")) return BuiltInRegistries.ITEM.getKey(Items.ENCHANTED_BOOK);
        if (n.equals("portal")) return BuiltInRegistries.ITEM.getKey(Items.ENDER_PEARL);
        if (n.equals("end_rod")) return BuiltInRegistries.ITEM.getKey(Items.END_ROD);
        if (n.equals("note")) return BuiltInRegistries.ITEM.getKey(Items.NOTE_BLOCK);
        if (n.equals("crit") || n.equals("enchanted_hit")) return BuiltInRegistries.ITEM.getKey(Items.DIAMOND_SWORD);
        if (n.equals("sweep_attack")) return BuiltInRegistries.ITEM.getKey(Items.IRON_SWORD);
        if (n.equals("explosion") || n.equals("explosion_emitter")) return BuiltInRegistries.ITEM.getKey(Items.TNT);
        if (n.equals("totem_of_undying")) return BuiltInRegistries.ITEM.getKey(Items.TOTEM_OF_UNDYING);
        if (n.equals("dragon_breath")) return BuiltInRegistries.ITEM.getKey(Items.DRAGON_BREATH);
        if (n.equals("elder_guardian")) return BuiltInRegistries.ITEM.getKey(Items.PRISMARINE_SHARD);
        if (n.equals("witch")) return BuiltInRegistries.ITEM.getKey(Items.SPIDER_EYE);
        if (n.equals("damage_indicator")) return BuiltInRegistries.ITEM.getKey(Items.BONE);
        if (n.equals("electric_spark")) return BuiltInRegistries.ITEM.getKey(Items.LIGHTNING_ROD);
        if (n.equals("glow")) return BuiltInRegistries.ITEM.getKey(Items.GLOW_INK_SAC);
        if (n.equals("sneeze")) return BuiltInRegistries.ITEM.getKey(Items.SLIME_BALL);
        if (n.equals("spit")) return BuiltInRegistries.ITEM.getKey(Items.SNOWBALL);
        if (n.equals("squid_ink")) return BuiltInRegistries.ITEM.getKey(Items.INK_SAC);
        if (n.equals("composter")) return BuiltInRegistries.ITEM.getKey(Items.COMPOSTER);
        if (n.equals("mycelium")) return BuiltInRegistries.ITEM.getKey(Items.MYCELIUM);
        if (n.equals("nautilus")) return BuiltInRegistries.ITEM.getKey(Items.NAUTILUS_SHELL);
        if (n.equals("cherry_leaves")) return BuiltInRegistries.ITEM.getKey(Items.CHERRY_LEAVES);
        if (n.contains("spore_blossom")) return BuiltInRegistries.ITEM.getKey(Items.SPORE_BLOSSOM);
        if (n.equals("scrape")) return BuiltInRegistries.ITEM.getKey(Items.IRON_INGOT);
        if (n.equals("poof")) return BuiltInRegistries.ITEM.getKey(Items.WHITE_WOOL);
        if (n.equals("flash")) return BuiltInRegistries.ITEM.getKey(Items.GLOWSTONE_DUST);
        if (n.equals("lava")) return BuiltInRegistries.ITEM.getKey(Items.LAVA_BUCKET);
        if (n.contains("dripping_lava") || (n.contains("falling") && n.contains("lava"))) return BuiltInRegistries.ITEM.getKey(Items.MAGMA_BLOCK);
        if (n.equals("bubble")) return BuiltInRegistries.ITEM.getKey(Items.BUBBLE_CORAL);
        if (n.equals("bubble_pop")) return BuiltInRegistries.ITEM.getKey(Items.TUBE_CORAL);
        if (n.equals("bubble_column_up") || n.equals("current_down")) return BuiltInRegistries.ITEM.getKey(Items.MAGMA_BLOCK);
        if (n.equals("underwater")) return BuiltInRegistries.ITEM.getKey(Items.KELP);
        if (n.equals("dolphin")) return BuiltInRegistries.ITEM.getKey(Items.COD);
        if (n.contains("dripping_water") || (n.contains("falling") && n.contains("water"))) return BuiltInRegistries.ITEM.getKey(Items.WATER_BUCKET);
        if (n.equals("rain")) return BuiltInRegistries.ITEM.getKey(Items.BLUE_ICE);
        if (n.equals("splash")) return BuiltInRegistries.ITEM.getKey(Items.PRISMARINE_CRYSTALS);
        if (n.contains("honey")) return BuiltInRegistries.ITEM.getKey(Items.HONEY_BOTTLE);
        if (n.contains("obsidian")) return BuiltInRegistries.ITEM.getKey(Items.CRYING_OBSIDIAN);
        if (n.contains("sculk_charge")) return BuiltInRegistries.ITEM.getKey(Items.SCULK_SENSOR);
        if (n.contains("sculk_soul")) return BuiltInRegistries.ITEM.getKey(Items.SCULK_SHRIEKER);
        if (n.contains("shriek")) return BuiltInRegistries.ITEM.getKey(Items.ECHO_SHARD);
        if (n.equals("smoke")) return BuiltInRegistries.ITEM.getKey(Items.CHARCOAL);
        if (n.equals("large_smoke")) return BuiltInRegistries.ITEM.getKey(Items.COAL_BLOCK);
        if (n.contains("campfire")) return BuiltInRegistries.ITEM.getKey(Items.CAMPFIRE);
        if (n.equals("ash") || n.equals("white_ash")) return BuiltInRegistries.ITEM.getKey(Items.BONE_MEAL);
        if (n.equals("cloud")) return BuiltInRegistries.ITEM.getKey(Items.WHITE_CONCRETE_POWDER);
        if (n.equals("ambient_entity_effect") || n.equals("entity_effect")) return BuiltInRegistries.ITEM.getKey(Items.POTION);
        if (n.contains("dripstone")) return BuiltInRegistries.ITEM.getKey(Items.POINTED_DRIPSTONE);

        // Fallback: hash-based selection
        if (n.hashCode() % 3 == 0) return BuiltInRegistries.ITEM.getKey(Items.AMETHYST_SHARD);
        if (n.hashCode() % 3 == 1) return BuiltInRegistries.ITEM.getKey(Items.PRISMARINE_CRYSTALS);
        return BuiltInRegistries.ITEM.getKey(Items.GLOWSTONE_DUST);
    }

    /**
     * Convert a string like "happy_villager" to "Happy Villager".
     * Copied from CosmeticsRegistry.toTitle().
     */
    private static String toTitle(String base) {
        String[] parts = base.replace('-', ' ').replace('_', ' ').trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1));
            if (i < parts.length - 1) sb.append(' ');
        }
        return sb.length() == 0 ? base : sb.toString();
    }

    /**
     * Check if an entry ID is a blended particle (contains "blended" in path).
     */
    private static boolean isBlendedId(ResourceLocation id) {
        return id.getPath().contains("blended");
    }

    /**
     * Check if an entry ID is a legacy built-in.
     */
    public static boolean isBuiltin(ResourceLocation entryId) {
        // Initialize the set if needed
        if (BUILTIN_IDS.isEmpty()) {
            builtins(); // This populates BUILTIN_IDS
        }
        return BUILTIN_IDS.contains(entryId);
    }
}

