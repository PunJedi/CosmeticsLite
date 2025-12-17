package com.pastlands.cosmeticslite.particle;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Registry of available cosmetic icons for particle cosmetics.
 * Provides a list of common Minecraft items that can be used as icons.
 */
public final class CosmeticIconRegistry {
    
    /**
     * Default icon for particle cosmetics (AURA slot).
     */
    public static final ResourceLocation DEFAULT_PARTICLE_ICON = 
        ResourceLocation.fromNamespaceAndPath("minecraft", "amethyst_shard");
    
    /**
     * Get all available icon items for selection.
     * Returns a list of common Minecraft items suitable for particle cosmetic icons.
     */
    public static List<IconOption> getAvailableIcons() {
        List<IconOption> icons = new ArrayList<>();
        
        // Particle/Magic themed items
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "amethyst_shard"),
            "Amethyst Shard"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "glowstone_dust"),
            "Glowstone Dust"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "blaze_powder"),
            "Blaze Powder"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "ghast_tear"),
            "Ghast Tear"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "ender_pearl"),
            "Ender Pearl"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "experience_bottle"),
            "Experience Bottle"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "firework_rocket"),
            "Firework Rocket"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "fire_charge"),
            "Fire Charge"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "magma_cream"),
            "Magma Cream"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "nether_star"),
            "Nether Star"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "soul_torch"),
            "Soul Torch"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "torch"),
            "Torch"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "lantern"),
            "Lantern"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "soul_lantern"),
            "Soul Lantern"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "blue_dye"),
            "Blue Dye"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "purple_dye"),
            "Purple Dye"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "pink_dye"),
            "Pink Dye"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "cyan_dye"),
            "Cyan Dye"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "lime_dye"),
            "Lime Dye"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "yellow_dye"),
            "Yellow Dye"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "orange_dye"),
            "Orange Dye"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "red_dye"),
            "Red Dye"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "green_dye"),
            "Green Dye"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "white_dye"),
            "White Dye"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "light_blue_dye"),
            "Light Blue Dye"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "magenta_dye"),
            "Magenta Dye"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "light_gray_dye"),
            "Light Gray Dye"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "gray_dye"),
            "Gray Dye"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "black_dye"),
            "Black Dye"
        ));
        icons.add(new IconOption(
            ResourceLocation.fromNamespaceAndPath("minecraft", "brown_dye"),
            "Brown Dye"
        ));
        
        return icons;
    }
    
    /**
     * Find icon option by item ID.
     */
    public static IconOption findIcon(ResourceLocation itemId) {
        if (itemId == null) return null;
        for (IconOption icon : getAvailableIcons()) {
            if (icon.itemId().equals(itemId)) {
                return icon;
            }
        }
        return null;
    }
    
    /**
     * Get display name for an icon item ID.
     */
    public static String getDisplayName(ResourceLocation itemId) {
        IconOption icon = findIcon(itemId);
        if (icon != null) {
            return icon.displayName();
        }
        // Fallback: format the item ID nicely
        String path = itemId.getPath();
        return path.replace("_", " ").substring(0, 1).toUpperCase() + path.substring(1);
    }
    
    /**
     * Represents an available icon option.
     */
    public record IconOption(ResourceLocation itemId, String displayName) {
        public IconOption {
            if (itemId == null) throw new IllegalArgumentException("itemId cannot be null");
            if (displayName == null || displayName.isBlank()) {
                throw new IllegalArgumentException("displayName cannot be null or blank");
            }
        }
    }
}

