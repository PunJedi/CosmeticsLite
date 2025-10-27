// src/main/java/com/pastlands/cosmeticslite/PlayerData.java
package com.pastlands.cosmeticslite;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.common.capabilities.CapabilityToken;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Server-authoritative per-player cosmetic state.
 *
 * Tracks exactly one equipped cosmetic per category (particles / hats / capes / pets / gadgets).
 * Serialized to NBT as:
 *
 * {
 *   equipped: {
 *     particles: "namespace:path",
 *     hats:      "namespace:path",
 *     capes:     "namespace:path",
 *     pets:      "namespace:path",
 *     gadgets:   "namespace:path"
 *   }
 * }
 *
 * Notes:
 * - Unknown or invalid RLs are ignored on load.
 * - Missing keys are treated as "air" (unset).
 * - Backing store is a String map (RL string) for forward-compat.
 */
public class PlayerData implements INBTSerializable<CompoundTag> {

    // ------------------------------------------------------------------------------------------------
    // Canonical type keys (keep in sync with CosmeticsRegistry)
    // ------------------------------------------------------------------------------------------------
    public static final String TYPE_PARTICLES = "particles";
    public static final String TYPE_HATS      = "hats";
    public static final String TYPE_CAPES     = "capes";
    public static final String TYPE_PETS      = "pets";
    public static final String TYPE_GADGETS   = "gadgets"; // NEW: active-use visual toys

    // Capability id & handle
    public static final ResourceLocation CAP_ID =
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "player_data");

    public static final Capability<PlayerData> CAPABILITY =
            CapabilityManager.get(new CapabilityToken<>(){});

    // Constant AIR id
    private static final ResourceLocation AIR =
            ResourceLocation.fromNamespaceAndPath("minecraft", "air");

    // --- Backing store: per-type equipped id as string (RL string), server-authoritative
    private final Map<String, String> equipped = new HashMap<>();

    // ------------------------------------------------------------------------------------------------
    // Accessors (generic)
    // ------------------------------------------------------------------------------------------------

    /** Returns the equipped id for a type, or AIR if empty/invalid. */
    public ResourceLocation getEquippedId(String type) {
        if (type == null) return AIR;
        String s = equipped.get(type);
        ResourceLocation rl = tryParseRL(s);
        return rl != null ? rl : AIR;
    }

    /** Sets the equipped id for a type (null or AIR clears). */
    public void setEquippedId(String type, @Nullable ResourceLocation id) {
        if (type == null) return;
        if (id == null || isAir(id)) {
            equipped.remove(type);
        } else {
            equipped.put(type, id.toString());
        }
    }

    /** Clears just this type. */
    public void clearEquipped(String type) {
        if (type != null) equipped.remove(type);
    }

    /** Clears ALL equipped cosmetics. */
    public void clearAll() {
        equipped.clear();
    }

    /** Read-only copy of all equipped entries (type -> id string). */
    public Map<String, String> getAllEquipped() {
        return Collections.unmodifiableMap(new HashMap<>(equipped));
    }

    // ------------------------------------------------------------------------------------------------
    // Convenience helpers for common categories (keeps callsites tidy)
    // ------------------------------------------------------------------------------------------------

    // Particles
    public ResourceLocation getEquippedParticlesId() { return getEquippedId(TYPE_PARTICLES); }
    public void setEquippedParticlesId(@Nullable ResourceLocation id) { setEquippedId(TYPE_PARTICLES, id); }
    public void clearParticles() { clearEquipped(TYPE_PARTICLES); }

    // Hats
    public ResourceLocation getEquippedHatId() { return getEquippedId(TYPE_HATS); }
    public void setEquippedHatId(@Nullable ResourceLocation id) { setEquippedId(TYPE_HATS, id); }
    public void clearHat() { clearEquipped(TYPE_HATS); }

    // Capes
    public ResourceLocation getEquippedCapeId() { return getEquippedId(TYPE_CAPES); }
    public void setEquippedCapeId(@Nullable ResourceLocation id) { setEquippedId(TYPE_CAPES, id); }
    public void clearCape() { clearEquipped(TYPE_CAPES); }

    // Pets
    public ResourceLocation getEquippedPetId() { return getEquippedId(TYPE_PETS); }
    public void setEquippedPetId(@Nullable ResourceLocation id) { setEquippedId(TYPE_PETS, id); }
    public void clearPet() { clearEquipped(TYPE_PETS); }

    // Gadgets (NEW)
    public ResourceLocation getEquippedGadgetId() { return getEquippedId(TYPE_GADGETS); }
    public void setEquippedGadgetId(@Nullable ResourceLocation id) { setEquippedId(TYPE_GADGETS, id); }
    public void clearGadget() { clearEquipped(TYPE_GADGETS); }

    // ------------------------------------------------------------------------------------------------
    // INBTSerializable
    // ------------------------------------------------------------------------------------------------

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag root = new CompoundTag();
        CompoundTag eq = new CompoundTag();
        for (Map.Entry<String, String> e : equipped.entrySet()) {
            if (e.getKey() == null || e.getKey().isEmpty()) continue;
            String id = e.getValue();
            if (id != null && !id.isEmpty()) {
                eq.putString(e.getKey(), id);
            }
        }
        root.put("equipped", eq);
        return root;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        equipped.clear();
        if (nbt == null) return;
        if (!nbt.contains("equipped")) return;

        CompoundTag eq = nbt.getCompound("equipped");
        for (String key : eq.getAllKeys()) {
            String s = safeString(eq.getString(key));
            // Validate RL syntax; if bad, skip it
            ResourceLocation parsed = tryParseRL(s);
            if (parsed != null && !isAir(Objects.requireNonNull(parsed))) {
                equipped.put(key, s);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Capability plumbing
    // ------------------------------------------------------------------------------------------------

    public static Optional<PlayerData> get(@Nullable ServerPlayer sp) {
        if (sp == null) return Optional.empty();
        return sp.getCapability(CAPABILITY).resolve();
    }

    public static final class Provider implements ICapabilityProvider, INBTSerializable<CompoundTag> {
        private final PlayerData instance = new PlayerData();
        private final LazyOptional<PlayerData> lazy = LazyOptional.of(() -> instance);

        @Override
        public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable net.minecraft.core.Direction side) {
            return cap == CAPABILITY ? lazy.cast() : LazyOptional.empty();
        }

        @Override
        public CompoundTag serializeNBT() {
            return instance.serializeNBT();
        }

        @Override
        public void deserializeNBT(CompoundTag nbt) {
            instance.deserializeNBT(nbt);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Utilities
    // ------------------------------------------------------------------------------------------------

    private static boolean isAir(@Nullable ResourceLocation id) {
        return id == null || ("minecraft".equals(id.getNamespace()) && "air".equals(id.getPath()));
    }

    @Nullable
    private static ResourceLocation tryParseRL(@Nullable String s) {
        if (s == null || s.isEmpty()) return null;
        return ResourceLocation.tryParse(s);
    }

    private static String safeString(@Nullable String s) {
        return s == null ? "" : s;
    }
}
