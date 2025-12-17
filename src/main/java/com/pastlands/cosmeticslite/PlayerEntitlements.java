package com.pastlands.cosmeticslite;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.common.util.LazyOptional;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Server-authoritative per-player entitlements (what a player is allowed to use).
 *
 * <p>Two buckets are tracked:</p>
 * <ul>
 *   <li><b>Packs</b> — coarse-grained grants (e.g., "cosmeticslite:fantasy").</li>
 *   <li><b>Cosmetics</b> — fine-grained grants by cosmetic id (e.g., "cosmeticslite:hats/jester_hat").</li>
 * </ul>
 *
 * <p>Serialized to NBT under a single root compound:</p>
 * <pre>
 * {
 *   packs:     [ "namespace:path", ... ],
 *   cosmetics: [ "namespace:path", ... ]
 * }
 * </pre>
 *
 * <p>Networking is intentionally <b>not</b> included here. Sync will be added by a dedicated
 * S2C packet in a later step. This class only defines the storage, API and capability plumbing.</p>
 */
public class PlayerEntitlements implements INBTSerializable<CompoundTag> {

    // --------------------------------------------------------------------------------------------
    // Capability id & handle
    // --------------------------------------------------------------------------------------------

    /** Capability id used when attaching to players. */
    public static final ResourceLocation CAP_ID =
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "player_entitlements");

    /** Capability handle. */
    public static final Capability<PlayerEntitlements> CAPABILITY =
            CapabilityManager.get(new CapabilityToken<>() {});

    // --------------------------------------------------------------------------------------------
    // Backing store
    // --------------------------------------------------------------------------------------------

    private final Set<String> packs = new HashSet<>();       // RL string form
    private final Set<String> cosmetics = new HashSet<>();   // RL string form

    // --------------------------------------------------------------------------------------------
    // Public API (server-authoritative)
    // --------------------------------------------------------------------------------------------

    // ---- Packs ----

    /**
     * Grants a pack id (null/invalid is ignored).
     */
    public void grantPack(@Nullable ResourceLocation id) {
        if (id == null) return;
        packs.add(id.toString());
    }

    /**
     * Revokes a pack id (no-op if not present).
     */
    public void revokePack(@Nullable ResourceLocation id) {
        if (id == null) return;
        packs.remove(id.toString());
    }

    /**
     * Returns true if the player owns the given pack id.
     */
    public boolean hasPack(@Nullable ResourceLocation id) {
        if (id == null) return false;
        return packs.contains(id.toString());
    }

    /** Immutable snapshot of all granted packs (as RL strings). */
    public Set<String> allPacks() {
        return Collections.unmodifiableSet(packs);
    }

    // ---- Cosmetics ----

    /** Grants a specific cosmetic id. */
    public void grantCosmetic(@Nullable ResourceLocation id) {
        if (id == null) return;
        cosmetics.add(id.toString());
    }

    /** Revokes a specific cosmetic id. */
    public void revokeCosmetic(@Nullable ResourceLocation id) {
        if (id == null) return;
        cosmetics.remove(id.toString());
    }

    /** Returns true if the player owns the given cosmetic id. */
    public boolean hasCosmetic(@Nullable ResourceLocation id) {
        if (id == null) return false;
        return cosmetics.contains(id.toString());
    }

    /** Immutable snapshot of all granted cosmetics (as RL strings). */
    public Set<String> allCosmetics() {
        return Collections.unmodifiableSet(cosmetics);
    }

    /** Removes all grants (packs and cosmetics). */
    public void clearAll() {
        packs.clear();
        cosmetics.clear();
    }

    // --------------------------------------------------------------------------------------------
    // INBTSerializable
    // --------------------------------------------------------------------------------------------

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag root = new CompoundTag();

        ListTag packList = new ListTag();
        for (String s : packs) {
            if (isValidRL(s)) packList.add(StringTag.valueOf(s));
        }
        root.put("packs", packList);

        ListTag cosList = new ListTag();
        for (String s : cosmetics) {
            if (isValidRL(s)) cosList.add(StringTag.valueOf(s));
        }
        root.put("cosmetics", cosList);

        return root;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        packs.clear();
        cosmetics.clear();
        if (nbt == null) return;

        if (nbt.contains("packs", ListTag.TAG_LIST)) {
            ListTag list = nbt.getList("packs", ListTag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                String s = list.getString(i);
                if (isValidRL(s)) packs.add(s);
            }
        }
        if (nbt.contains("cosmetics", ListTag.TAG_LIST)) {
            ListTag list = nbt.getList("cosmetics", ListTag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                String s = list.getString(i);
                if (isValidRL(s)) cosmetics.add(s);
            }
        }
    }

    // --------------------------------------------------------------------------------------------
    // Capability plumbing helpers
    // --------------------------------------------------------------------------------------------

    /** Resolves this capability for the given server player. */
    public static Optional<PlayerEntitlements> get(@Nullable ServerPlayer sp) {
        if (sp == null) return Optional.empty();
        return sp.getCapability(CAPABILITY).resolve();
    }

    /** Provider used when attaching to Player entities. */
    public static final class Provider implements ICapabilityProvider, INBTSerializable<CompoundTag> {
        private final PlayerEntitlements instance = new PlayerEntitlements();
        private final LazyOptional<PlayerEntitlements> lazy = LazyOptional.of(() -> instance);

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

    // --------------------------------------------------------------------------------------------
    // Utils
    // --------------------------------------------------------------------------------------------

    private static boolean isValidRL(@Nullable String s) {
        if (s == null || s.isEmpty()) return false;
        ResourceLocation rl = ResourceLocation.tryParse(s);
        return rl != null && Objects.equals(rl.toString(), s);
    }
}
