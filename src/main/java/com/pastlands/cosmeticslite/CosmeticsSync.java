package com.pastlands.cosmeticslite;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;

/**
 * Server -> Client sync helper for cosmetic state.
 *
 * Sends a compact payload: Map<String, String> (type -> "namespace:path"),
 * tagged with the source player's entityId. Clients store this in a per-entity cache.
 */
public final class CosmeticsSync {

    private CosmeticsSync() {}

    /**
     * Sync the given player's server-authoritative cosmetic state to
     * ALL tracking clients (and to the player themselves).
     */
    public static void sync(ServerPlayer sp) {
        if (sp == null) return;

        PlayerData.get(sp).ifPresent(data -> {
            Map<String, String> equipped = new HashMap<>(data.getAllEquipped());
            CosmeticsLite.NETWORK.send(
                    PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> sp),
                    new PacketSyncCosmetics(sp.getId(), equipped)
            );
        });
    }

    /**
     * Send the subject's cosmetic snapshot to a single viewer (used on StartTracking).
     */
    public static void syncTo(ServerPlayer viewer, ServerPlayer subject) {
        if (viewer == null || subject == null) return;

        PlayerData.get(subject).ifPresent(data -> {
            Map<String, String> equipped = new HashMap<>(data.getAllEquipped());
            CosmeticsLite.NETWORK.send(
                    PacketDistributor.PLAYER.with(() -> viewer),
                    new PacketSyncCosmetics(subject.getId(), equipped)
            );
        });
    }

    /** Alias for readability; currently delegates to {@link #sync(ServerPlayer)}. */
    public static void syncAround(ServerPlayer sp) {
        sync(sp);
    }
}
