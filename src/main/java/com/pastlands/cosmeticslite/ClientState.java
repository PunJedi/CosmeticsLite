package com.pastlands.cosmeticslite;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Client-side cache of server-authoritative cosmetic state.
 *
 * Supports both:
 *  1) Legacy local-only cache (type -> id) used by existing UI/renderers.
 *  2) Per-entity cache (entityId -> (type -> id)) so other players' cosmetics render.
 *
 * IDs are stored as ResourceLocations. "minecraft:air" (or null/missing) is treated as "unequipped".
 *
 * Also keeps lightweight client-only UI preferences for pets:
 *  - Last chosen variant per pet species (e.g., "parrot", "cat", "fox").
 *  - Last chosen color/random toggle per pet species (for color-wheel pets).
 * These prefs are NOT persisted to disk and are not server-authoritative.
 */
public final class ClientState {

    private ClientState() {}

    // --------------------------------------------------------------------------------------------
    // Local (legacy) cache — kept for compatibility with existing calls.
    // --------------------------------------------------------------------------------------------

    /** Local player's equipped map: type -> id (null/air = unequipped). */
    private static final Map<String, ResourceLocation> LOCAL = new HashMap<>();

    // --------------------------------------------------------------------------------------------
    // Per-entity cache
    // --------------------------------------------------------------------------------------------

    /** entityId -> (type -> id). */
    private static final Map<Integer, Map<String, ResourceLocation>> BY_ENTITY = new HashMap<>();

    // --------------------------------------------------------------------------------------------
    // Client-only UI prefs for pets (not synced/persisted)
    // --------------------------------------------------------------------------------------------

    /** species -> last chosen variant key (e.g., "red", "siamese", "snow"). */
    private static final Map<String, String> PET_VARIANT_PREF = new HashMap<>();

    /** species -> last chosen color/random selection from the color wheel. */
    private static final Map<String, PetColorPref> PET_COLOR_PREF = new HashMap<>();

    /** Simple value holder for color-wheel choice. */
    public record PetColorPref(int rgb, boolean random) {}

    /** Save the last chosen variant for this pet species (e.g., "parrot", "cat"). */
    public static void setPetVariantPref(@Nullable String species, @Nullable String variantKey) {
        if (species == null || species.isEmpty() || variantKey == null || variantKey.isEmpty()) return;
        PET_VARIANT_PREF.put(species, variantKey);
    }

    /** Read the last chosen variant for this pet species, or null if none. */
    @Nullable
    public static String getPetVariantPref(@Nullable String species) {
        if (species == null) return null;
        return PET_VARIANT_PREF.get(species);
    }

    /** Save the last chosen color/random for this pet species. */
    public static void setPetColorPref(@Nullable String species, int rgb, boolean random) {
        if (species == null || species.isEmpty()) return;
        PET_COLOR_PREF.put(species, new PetColorPref(rgb, random));
    }

    /** Read the last chosen color/random for this pet species, or null if none. */
    @Nullable
    public static PetColorPref getPetColorPref(@Nullable String species) {
        if (species == null) return null;
        return PET_COLOR_PREF.get(species);
    }

    // --------------------------------------------------------------------------------------------
    // Apply sync payloads
    // --------------------------------------------------------------------------------------------

    /** Legacy path: apply a snapshot for the local player only. */
    public static void applySync(@Nullable Map<String, String> equippedByType) {
        LOCAL.clear();
        if (equippedByType == null) return;
        for (Map.Entry<String, String> e : equippedByType.entrySet()) {
            ResourceLocation id = tryParseRL(e.getValue());
            if (isAir(id)) continue; // treat as unequipped
            LOCAL.put(e.getKey(), id);
        }
    }

    /** New path: apply a snapshot for a specific entity id. */
    public static void applySync(int entityId, @Nullable Map<String, String> equippedByType) {
        Map<String, ResourceLocation> map = new HashMap<>();
        if (equippedByType != null) {
            for (Map.Entry<String, String> e : equippedByType.entrySet()) {
                ResourceLocation id = tryParseRL(e.getValue());
                if (isAir(id)) continue;
                map.put(e.getKey(), id);
            }
        }
        BY_ENTITY.put(entityId, map);
    }

    // --------------------------------------------------------------------------------------------
    // Queries
    // --------------------------------------------------------------------------------------------

    /** Legacy helper: get local player's equipped id for a type, or null. */
    @Nullable
    public static ResourceLocation getEquippedId(String type) {
        return LOCAL.get(type);
    }

    /** Entity-aware helper: get equipped id for a specific player entity, or null. */
    @Nullable
    public static ResourceLocation getEquippedId(AbstractClientPlayer player, String type) {
        Map<String, ResourceLocation> map = BY_ENTITY.get(player.getId());
        ResourceLocation rl = (map != null) ? map.get(type) : null;

        // Only fall back to LOCAL if the queried entity *is the local player*.
        if (rl == null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.getId() == player.getId()) {
                rl = LOCAL.get(type);
            }
        }
        return rl;
    }

    /** Entity-aware helper returning a resolved CosmeticDef, or null if unknown. */
    @Nullable
    public static CosmeticDef getDef(AbstractClientPlayer player, String type) {
        ResourceLocation id = getEquippedId(player, type);
        return id != null ? CosmeticsRegistry.get(id) : null;
    }

    // --------------------------------------------------------------------------------------------
    // Local (legacy) mutation helpers
    // --------------------------------------------------------------------------------------------

    /** Compatibility setter used by UI. */
    public static void setEquippedId(String type, @Nullable ResourceLocation id) {
        set(type, id);
    }

    /** Set local player's equipped id (null/air -> remove). */
    public static void set(String type, @Nullable ResourceLocation id) {
        if (isAir(id)) {
            LOCAL.remove(type);
        } else {
            LOCAL.put(type, id);
        }
    }

    public static void clear(String type) {
        LOCAL.remove(type);
    }

    public static void clearAll() {
        LOCAL.clear();
    }

    /** Immutable snapshot of the local cache as type -> "namespace:path" string. */
    public static Map<String, String> snapshot() {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, ResourceLocation> e : LOCAL.entrySet()) {
            if (!isAir(e.getValue())) {
                out.put(e.getKey(), e.getValue().toString());
            }
        }
        return Collections.unmodifiableMap(out);
    }

    // --------------------------------------------------------------------------------------------
    // Entity cache maintenance
    // --------------------------------------------------------------------------------------------

    public static void clearEntity(int entityId) {
        BY_ENTITY.remove(entityId);
    }

    public static void clearAllEntities() {
        BY_ENTITY.clear();
    }

    // --------------------------------------------------------------------------------------------
    // Utilities
    // --------------------------------------------------------------------------------------------

    private static boolean isAir(@Nullable ResourceLocation id) {
        return id == null || ("minecraft".equals(id.getNamespace()) && "air".equals(id.getPath()));
    }

    @Nullable
    private static ResourceLocation tryParseRL(@Nullable String s) {
        if (s == null || s.isEmpty()) return null;
        return ResourceLocation.tryParse(s);
    }
}
