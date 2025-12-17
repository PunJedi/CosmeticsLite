package com.pastlands.cosmeticslite;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Entitlements {

    private static final Map<UUID, Set<String>> ENTITLED = new HashMap<>();

    public static void grant(UUID playerId, String cosmeticId) {
        ENTITLED.computeIfAbsent(playerId, id -> new HashSet<>()).add(cosmeticId);
    }

    public static void revoke(UUID playerId, String cosmeticId) {
        Set<String> set = ENTITLED.get(playerId);
        if (set != null) {
            set.remove(cosmeticId);
            if (set.isEmpty()) {
                ENTITLED.remove(playerId);
            }
        }
    }

    public static boolean has(UUID playerId, String cosmeticId) {
        return ENTITLED.getOrDefault(playerId, Collections.emptySet()).contains(cosmeticId);
    }

    public static Set<String> all(UUID playerId) {
        return ENTITLED.getOrDefault(playerId, Collections.emptySet());
    }

    public static void clear(UUID playerId) {
        ENTITLED.remove(playerId);
    }

    public static void clearAll() {
        ENTITLED.clear();
    }
}
