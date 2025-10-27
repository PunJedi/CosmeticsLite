// src/main/java/com/pastlands/cosmeticslite/UnlockManager.java
package com.pastlands.cosmeticslite;

import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * UnlockManager
 *  - Tracks which cosmetic packs each player has access to.
 *  - Default: ops/admins have everything, regular players only get "base".
 *  - Future: integrate with Tebex or config files to persist unlocks.
 *
 * Forge 47.4.0 (MC 1.20.1)
 */
public final class UnlockManager {

    // Map: player UUID -> set of unlocked pack ids
    private static final Map<UUID, Set<String>> UNLOCKED = new HashMap<>();

    private UnlockManager() {}

    // ------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------

    /** Check if a player has the given pack unlocked. */
    public static boolean isUnlocked(ServerPlayer player, String packId) {
        if (player == null || packId == null || packId.isBlank()) return false;

        // Admins / ops always unlocked
        if (player.hasPermissions(2)) return true;

        // Default unlocked pack
        if ("base".equalsIgnoreCase(packId)) return true;

        // Look up player-specific unlocks
        Set<String> packs = UNLOCKED.get(player.getUUID());
        return packs != null && packs.contains(packId.toLowerCase(Locale.ROOT));
    }

    /** Grant a pack to a player (temporary, memory only). */
    public static void grant(ServerPlayer player, String packId) {
        if (player == null || packId == null || packId.isBlank()) return;
        UNLOCKED.computeIfAbsent(player.getUUID(), k -> new HashSet<>())
                .add(packId.toLowerCase(Locale.ROOT));
    }

    /** Revoke a pack from a player. */
    public static void revoke(ServerPlayer player, String packId) {
        if (player == null || packId == null || packId.isBlank()) return;
        Set<String> packs = UNLOCKED.get(player.getUUID());
        if (packs != null) {
            packs.remove(packId.toLowerCase(Locale.ROOT));
            if (packs.isEmpty()) UNLOCKED.remove(player.getUUID());
        }
    }

    /** Get all unlocked packs for a player (never null). */
    public static Set<String> getUnlocked(ServerPlayer player) {
        if (player == null) return Collections.emptySet();
        if (player.hasPermissions(2)) return CosmeticsRegistry.getKnownPacks();
        Set<String> packs = UNLOCKED.get(player.getUUID());
        if (packs == null) return Set.of("base");
        Set<String> result = new HashSet<>(packs);
        result.add("base");
        return Collections.unmodifiableSet(result);
    }

    /** Reset all unlocks (clears memory). */
    public static void clear() {
        UNLOCKED.clear();
    }
}
