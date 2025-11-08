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
 * Tracks exactly one equipped cosmetic per category (particles / hats / capes / pets / gadgets)
 * plus a STYLE payload for each category:
 *   - variant (int, -1 if unused)
 *   - colorARGB (int, -1 if unused)
 *   - extra (CompoundTag, optional structured details e.g., horse color/markings, villager profession)
 *
 * NBT format (backward-compatible with prior releases):
 *
 * {
 *   equipped: {
 *     particles: "namespace:path",
 *     hats:      "namespace:path",
 *     capes:     "namespace:path",
 *     pets:      "namespace:path",
 *     gadgets:   "namespace:path"
 *   },
 *   styles: {
 *     particles: { variant: 3, color: 0xFFAABBCC, extra: { ... } },
 *     hats:      { variant: -1, color: -1 },
 *     capes:     { ... },
 *     pets:      { variant: 2, color: 0xFF112233, extra: { color:1, markings:3 } },
 *     gadgets:   { ... }
 *   }
 * }
 *
 * Notes:
 *  - Unknown or invalid RLs are ignored on load.
 *  - Missing keys default to AIR (unset).
 *  - Style defaults: variant = -1, colorARGB = -1, extra = {}.
 *  - Style is stored per-type, but you can ignore it for categories that don't use it.
 */
public class PlayerData implements INBTSerializable<CompoundTag> {

    // ------------------------------------------------------------------------------------------------
    // Canonical type keys (keep in sync with the rest of the mod)
    // ------------------------------------------------------------------------------------------------
    public static final String TYPE_PARTICLES = "particles";
    public static final String TYPE_HATS      = "hats";
    public static final String TYPE_CAPES     = "capes";
    public static final String TYPE_PETS      = "pets";
    public static final String TYPE_GADGETS   = "gadgets";

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

    /**
     * Per-type style payload. Stored as a small struct to keep things tidy/forward-compatible.
     */
    private static final class Style {
        int variant = -1;           // -1 means "unused"
        int colorARGB = -1;         // -1 means "unused"
        CompoundTag extra = new CompoundTag(); // arbitrary keys, e.g. horse/villager specifics

        CompoundTag toNbt() {
            CompoundTag t = new CompoundTag();
            t.putInt("variant", variant);
            t.putInt("color", colorARGB);
            if (!extra.isEmpty()) t.put("extra", extra.copy());
            return t;
        }

        static Style fromNbt(@Nullable CompoundTag t) {
            Style s = new Style();
            if (t == null || t.isEmpty()) return s;
            s.variant = t.contains("variant") ? t.getInt("variant") : -1;
            s.colorARGB = t.contains("color") ? t.getInt("color") : -1;
            if (t.contains("extra")) {
                CompoundTag ex = t.getCompound("extra");
                s.extra = ex == null ? new CompoundTag() : ex.copy();
            }
            return s;
        }

        Style copy() {
            Style c = new Style();
            c.variant = this.variant;
            c.colorARGB = this.colorARGB;
            c.extra = this.extra.copy();
            return c;
        }
    }

    // Per-type style map
    private final Map<String, Style> styles = new HashMap<>();

    // ------------------------------------------------------------------------------------------------
    // Accessors (generic) - EQUIPPED
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
        if (type != null) {
            equipped.remove(type);
            styles.remove(type); // also clear style when type is cleared
        }
    }

    /** Clears ALL equipped cosmetics (and styles). */
    public void clearAll() {
        equipped.clear();
        styles.clear();
    }

    /** Read-only copy of all equipped entries (type -> id string). */
    public Map<String, String> getAllEquipped() {
        return Collections.unmodifiableMap(new HashMap<>(equipped));
    }

    // ------------------------------------------------------------------------------------------------
    // Accessors (generic) - STYLE
    // ------------------------------------------------------------------------------------------------

    /** Returns the style-variant for a type, or -1 if unset/unused. */
    public int getEquippedVariant(String type) {
        Style s = styles.get(type);
        return (s == null) ? -1 : s.variant;
    }

    /** Sets the style-variant for a type; pass -1 to unset. */
    public void setEquippedVariant(String type, int variant) {
        if (type == null) return;
        if (variant < 0) {
            Style s = styles.get(type);
            if (s != null) { s.variant = -1; pruneIfEmpty(type, s); }
            return;
        }
        Style s = styles.computeIfAbsent(type, k -> new Style());
        s.variant = variant;
    }

    /** Returns the ARGB color for a type, or -1 if unset/unused. */
    public int getEquippedColor(String type) {
        Style s = styles.get(type);
        return (s == null) ? -1 : s.colorARGB;
    }

    /** Sets the ARGB color for a type; pass -1 to unset. */
    public void setEquippedColor(String type, int argb) {
        if (type == null) return;
        if (argb < 0) {
            Style s = styles.get(type);
            if (s != null) { s.colorARGB = -1; pruneIfEmpty(type, s); }
            return;
        }
        Style s = styles.computeIfAbsent(type, k -> new Style());
        s.colorARGB = argb;
    }

    /** Returns a COPY of the extra tag for a type (never null). */
    public CompoundTag getEquippedStyleTag(String type) {
        Style s = styles.get(type);
        return (s == null || s.extra == null) ? new CompoundTag() : s.extra.copy();
    }

    /** Replaces the extra tag for a type; empty tag removes it. */
    public void setEquippedStyleTag(String type, @Nullable CompoundTag extra) {
        if (type == null) return;
        if (extra == null || extra.isEmpty()) {
            Style s = styles.get(type);
            if (s != null) { s.extra = new CompoundTag(); pruneIfEmpty(type, s); }
            return;
        }
        Style s = styles.computeIfAbsent(type, k -> new Style());
        s.extra = extra.copy();
    }

    private void pruneIfEmpty(String type, Style s) {
        if (s.variant < 0 && s.colorARGB < 0 && (s.extra == null || s.extra.isEmpty())) {
            styles.remove(type);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Convenience helpers for common categories (equipped ID only, unchanged API)
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

    // Gadgets
    public ResourceLocation getEquippedGadgetId() { return getEquippedId(TYPE_GADGETS); }
    public void setEquippedGadgetId(@Nullable ResourceLocation id) { setEquippedId(TYPE_GADGETS, id); }
    public void clearGadget() { clearEquipped(TYPE_GADGETS); }

    // ------------------------------------------------------------------------------------------------
    // INBTSerializable
    // ------------------------------------------------------------------------------------------------

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag root = new CompoundTag();

        // equipped
        CompoundTag eq = new CompoundTag();
        for (Map.Entry<String, String> e : equipped.entrySet()) {
            if (e.getKey() == null || e.getKey().isEmpty()) continue;
            String id = e.getValue();
            if (id != null && !id.isEmpty()) {
                eq.putString(e.getKey(), id);
            }
        }
        root.put("equipped", eq);

        // styles
        if (!styles.isEmpty()) {
            CompoundTag stylesTag = new CompoundTag();
            for (Map.Entry<String, Style> e : styles.entrySet()) {
                String type = e.getKey();
                if (type == null || type.isEmpty()) continue;
                Style s = e.getValue();
                if (s == null) continue;
                CompoundTag st = s.toNbt();
                if (!st.isEmpty()) stylesTag.put(type, st);
            }
            if (!stylesTag.isEmpty()) root.put("styles", stylesTag);
        }

        return root;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        equipped.clear();
        styles.clear();

        if (nbt == null) return;

        // equipped (legacy/primary)
        if (nbt.contains("equipped")) {
            CompoundTag eq = nbt.getCompound("equipped");
            for (String key : eq.getAllKeys()) {
                String s = safeString(eq.getString(key));
                ResourceLocation parsed = tryParseRL(s);
                if (parsed != null && !isAir(Objects.requireNonNull(parsed))) {
                    equipped.put(key, s);
                }
            }
        }

        // styles (new; optional)
        if (nbt.contains("styles")) {
            CompoundTag stylesTag = nbt.getCompound("styles");
            for (String key : stylesTag.getAllKeys()) {
                CompoundTag st = stylesTag.getCompound(key);
                Style s = Style.fromNbt(st);
                // Only keep non-empty styles
                if (s.variant >= 0 || s.colorARGB >= 0 || (s.extra != null && !s.extra.isEmpty())) {
                    styles.put(key, s);
                }
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
