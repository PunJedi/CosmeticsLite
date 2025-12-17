package com.pastlands.cosmeticslite.client;

import com.mojang.logging.LogUtils;
import com.pastlands.cosmeticslite.particle.CosmeticParticleEntry;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.*;

/**
 * Client-side catalog for cosmetic particle entries (synced from server).
 * Used by the Cosmetics Particle tab to display published cosmetics.
 * Includes both built-in and config entries (mirror from server).
 */
public final class CosmeticParticleClientCatalog {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceLocation, CosmeticParticleEntry> ENTRIES = new LinkedHashMap<>();

    private CosmeticParticleClientCatalog() {}

    /**
     * Replace all entries with the synced list from server.
     * Includes both built-in and config entries.
     */
    public static synchronized void replaceAll(Collection<CosmeticParticleEntry> entries) {
        ENTRIES.clear();
        for (CosmeticParticleEntry entry : entries) {
            ENTRIES.put(entry.id(), entry);
        }
        LOGGER.info("[cosmeticslite] Synced {} cosmetic particle entry(ies) from server", entries.size());
    }

    /**
     * Get all entries.
     */
    public static synchronized Collection<CosmeticParticleEntry> all() {
        return Collections.unmodifiableCollection(ENTRIES.values());
    }

    /**
     * Get entry by ID.
     */
    public static synchronized CosmeticParticleEntry get(ResourceLocation id) {
        return ENTRIES.get(id);
    }

    /**
     * Get entries by slot.
     */
    public static synchronized List<CosmeticParticleEntry> getBySlot(CosmeticParticleEntry.Slot slot) {
        List<CosmeticParticleEntry> result = new ArrayList<>();
        for (CosmeticParticleEntry entry : ENTRIES.values()) {
            if (entry.slot() == slot) {
                result.add(entry);
            }
        }
        return Collections.unmodifiableList(result);
    }
}

