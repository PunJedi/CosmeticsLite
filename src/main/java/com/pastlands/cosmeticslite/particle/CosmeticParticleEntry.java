package com.pastlands.cosmeticslite.particle;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Represents a published cosmetic particle entry that appears in the Cosmetics Particle tab.
 * Links a particle definition to display metadata (name, slot, icon).
 */
public class CosmeticParticleEntry {
    private final ResourceLocation id;           // cosmeticslite:cosmetic/angel_wisps_v2
    private final ResourceLocation particleId;   // cosmeticslite:particle/angel_wisps_blended
    private final Component displayName;         // localized or plain text
    private final Slot slot;                    // enum CAPE, HEAD, AURA, TRAIL, MISC
    @Nullable private final String rarity;      // optional
    @Nullable private final Integer price;      // optional
    @Nullable private final Icon icon;          // item + optional tint
    private final Source source;                // BUILTIN or CONFIG

    public CosmeticParticleEntry(ResourceLocation id,
                                 ResourceLocation particleId,
                                 Component displayName,
                                 Slot slot,
                                 @Nullable String rarity,
                                 @Nullable Integer price,
                                 @Nullable Icon icon) {
        this(id, particleId, displayName, slot, rarity, price, icon, Source.CONFIG);
    }

    public CosmeticParticleEntry(ResourceLocation id,
                                 ResourceLocation particleId,
                                 Component displayName,
                                 Slot slot,
                                 @Nullable String rarity,
                                 @Nullable Integer price,
                                 @Nullable Icon icon,
                                 Source source) {
        this.id = Objects.requireNonNull(id, "id");
        this.particleId = Objects.requireNonNull(particleId, "particleId");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.slot = Objects.requireNonNull(slot, "slot");
        this.rarity = rarity;
        this.price = price;
        this.icon = icon;
        this.source = source != null ? source : Source.CONFIG;
    }

    // Getters
    public ResourceLocation id() { return id; }
    public ResourceLocation particleId() { return particleId; }
    public Component displayName() { return displayName; }
    public Slot slot() { return slot; }
    @Nullable public String rarity() { return rarity; }
    @Nullable public Integer price() { return price; }
    @Nullable public Icon icon() { return icon; }
    public Source source() { return source; }

    /**
     * Create a copy with a different source.
     */
    public CosmeticParticleEntry withSource(Source newSource) {
        return new CosmeticParticleEntry(id, particleId, displayName, slot, rarity, price, icon, newSource);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CosmeticParticleEntry that = (CosmeticParticleEntry) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id.toString() + " -> " + particleId.toString() + " [" + slot + "]";
    }

    /**
     * Get the icon ItemStack for this entry.
     * Returns the actual selected icon item, or a fallback default if icon is missing.
     */
    public net.minecraft.world.item.ItemStack iconStack() {
        if (icon != null && icon.itemId() != null) {
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(icon.itemId());
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                return new net.minecraft.world.item.ItemStack(item);
            }
        }
        // Fallback: use default particle icon
        net.minecraft.resources.ResourceLocation defaultItemId = 
            com.pastlands.cosmeticslite.particle.CosmeticIconRegistry.DEFAULT_PARTICLE_ICON;
        net.minecraft.world.item.Item defaultItem = 
            net.minecraft.core.registries.BuiltInRegistries.ITEM.get(defaultItemId);
        if (defaultItem != null) {
            return new net.minecraft.world.item.ItemStack(defaultItem);
        }
        return net.minecraft.world.item.ItemStack.EMPTY;
    }

    /**
     * Icon definition with item and optional tint.
     */
    public static class Icon {
        private final ResourceLocation itemId;   // e.g. minecraft:elytra
        @Nullable private final Integer argbTint; // e.g. 0xFFFF80FF (ARGB)

        public Icon(ResourceLocation itemId, @Nullable Integer argbTint) {
            this.itemId = Objects.requireNonNull(itemId, "itemId");
            this.argbTint = argbTint;
        }

        public ResourceLocation itemId() { return itemId; }
        @Nullable public Integer argbTint() { return argbTint; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Icon icon = (Icon) o;
            return itemId.equals(icon.itemId) && Objects.equals(argbTint, icon.argbTint);
        }

        @Override
        public int hashCode() {
            return Objects.hash(itemId, argbTint);
        }
    }

    /**
     * Slot enum for cosmetic placement.
     */
    public enum Slot {
        CAPE, HEAD, AURA, TRAIL, MISC;

        public static Slot fromString(String s) {
            if (s == null) return CAPE;
            try {
                return valueOf(s.toUpperCase());
            } catch (IllegalArgumentException e) {
                return CAPE;
            }
        }
    }

    /**
     * Source enum for distinguishing built-in vs config entries.
     */
    public enum Source {
        BUILTIN,
        CONFIG;

        public static Source fromString(String s) {
            if (s == null) return CONFIG;
            try {
                return valueOf(s.toUpperCase());
            } catch (IllegalArgumentException e) {
                return CONFIG;
            }
        }
    }

    /**
     * Factory method for creating built-in entries.
     */
    public static CosmeticParticleEntry builtin(
            ResourceLocation id,
            ResourceLocation particleId,
            String displayName,
            Slot slot,
            Icon icon) {
        return new CosmeticParticleEntry(
            id,
            particleId,
            Component.literal(displayName),
            slot,
            null, // rarity
            null, // price
            icon,
            Source.BUILTIN
        );
    }

    /**
     * Factory method for creating config entries.
     */
    public static CosmeticParticleEntry config(
            ResourceLocation id,
            ResourceLocation particleId,
            Component displayName,
            Slot slot,
            @Nullable String rarity,
            @Nullable Integer price,
            @Nullable Icon icon) {
        return new CosmeticParticleEntry(id, particleId, displayName, slot, rarity, price, icon, Source.CONFIG);
    }
}

