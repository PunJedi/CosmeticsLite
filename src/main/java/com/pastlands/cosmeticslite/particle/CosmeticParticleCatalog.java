package com.pastlands.cosmeticslite.particle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.pastlands.cosmeticslite.CosmeticsLite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Server-side catalog for published cosmetic particle entries.
 * Manages loading/saving from JSON file and provides lookup.
 */
public final class CosmeticParticleCatalog {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Map<ResourceLocation, CosmeticParticleEntry> entries = new LinkedHashMap<>();
    private Path catalogFile;

    public CosmeticParticleCatalog() {
        // Default catalog file location: world/config/cosmeticslite/cosmetics_particles.json
        // or config/cosmeticslite/cosmetics_particles.json for global
        this.catalogFile = Path.of("config", CosmeticsLite.MODID, "cosmetics_particles.json");
    }

    /**
     * Set the catalog file path (for per-world or global config).
     */
    public void setCatalogFile(Path path) {
        this.catalogFile = path;
    }

    /**
     * Get the catalog file path.
     */
    public Path getCatalogFile() {
        return catalogFile;
    }

    /**
     * Add built-in entries from legacy definitions.
     * Config entries can override built-ins (by using putIfAbsent, config entries loaded later will win).
     */
    private void addBuiltinEntries(Map<ResourceLocation, CosmeticParticleEntry> map) {
        // 1. Start from legacy entries
        for (CosmeticParticleEntry entry : LegacyCosmeticParticles.builtins()) {
            // Only insert if not already defined by config (config wins)
            map.putIfAbsent(entry.id(), entry);
        }
        
        // 2. Optionally: add any extra entries discovered from registry/config
        // This allows any additional particles in the registry to also get catalog entries
        // (though they won't have the legacy display names/icons)
        for (com.pastlands.cosmeticslite.particle.config.ParticleDefinition def : 
                com.pastlands.cosmeticslite.particle.config.CosmeticParticleRegistry.all()) {
            ResourceLocation particleId = def.id();
            
            // Only process cosmeticslite namespace particles
            if (!"cosmeticslite".equals(particleId.getNamespace())) continue;
            if (!particleId.getPath().startsWith("particle/")) continue;
            
            // Map particle ID to cosmetic entry ID
            String path = particleId.getPath().substring("particle/".length());
            ResourceLocation entryId = ResourceLocation.fromNamespaceAndPath("cosmeticslite", "cosmetic/" + path);
            
            // Skip if already exists (either from legacy list or from config entries loaded earlier)
            if (map.containsKey(entryId)) continue;
            
            // Skip if it's a legacy built-in (already added above)
            if (LegacyCosmeticParticles.isBuiltin(entryId)) continue;
            
            // Generate display name from path
            String displayName = niceNameFromPath(path);
            
            // Default to AURA slot for particles
            CosmeticParticleEntry.Slot slot = CosmeticParticleEntry.Slot.AURA;
            
            // Create default icon
            ResourceLocation iconItemId = defaultIconItemForSlot(slot);
            CosmeticParticleEntry.Icon icon = new CosmeticParticleEntry.Icon(iconItemId, null);
            
            // Create built-in entry
            CosmeticParticleEntry entry = CosmeticParticleEntry.builtin(
                entryId, particleId, displayName, slot, icon
            );
            
            map.put(entryId, entry);
        }
    }
    
    /**
     * Convert a path like "angel_wings_blended" to "Angel Wings (Blended)".
     */
    private static String niceNameFromPath(String path) {
        // Split by underscores and capitalize each word
        String[] parts = path.split("_");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                // Check if this is a modifier in parentheses
                if (parts[i].equals("blended") || parts[i].equals("enhanced") || 
                    parts[i].equals("premium") || parts[i].equals("deluxe")) {
                    result.append(" (").append(capitalize(parts[i])).append(")");
                    break;
                } else {
                    result.append(" ");
                }
            }
            result.append(capitalize(parts[i]));
        }
        return result.toString();
    }
    
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    /**
     * Load entries from JSON file.
     * This method is kept for backward compatibility but reloadFromConfig should be used instead.
     * @deprecated Use reloadFromConfig() instead, which includes built-ins.
     */
    @Deprecated
    public void loadFromFile(Path path) {
        // For backward compatibility, just load config entries without built-ins
        entries.clear();
        if (!Files.exists(path)) {
            LOGGER.info("[cosmeticslite] Cosmetic particle catalog file does not exist: {}", path);
            return;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!root.has("entries")) {
                LOGGER.warn("[cosmeticslite] Catalog file missing 'entries' array: {}", path);
                return;
            }

            JsonArray entriesArray = root.getAsJsonArray("entries");
            for (JsonElement element : entriesArray) {
                try {
                    CosmeticParticleEntry entry = fromJson(element.getAsJsonObject());
                    if (entry != null) {
                        entries.put(entry.id(), entry);
                    }
                } catch (Exception e) {
                    LOGGER.warn("[cosmeticslite] Failed to parse catalog entry: {}", e.getMessage());
                }
            }

            LOGGER.info("[cosmeticslite] Loaded {} cosmetic particle entry(ies) from {}", entries.size(), path);
        } catch (IOException e) {
            LOGGER.error("[cosmeticslite] Failed to load cosmetic particle catalog from {}: {}", path, e.getMessage(), e);
        }
    }

    /**
     * Save entries to JSON file.
     * Only saves CONFIG entries (built-ins are not persisted).
     */
    public void saveToFile(Path path) {
        try {
            Files.createDirectories(path.getParent());
            
            JsonObject root = new JsonObject();
            JsonArray entriesArray = new JsonArray();
            
            // Only save CONFIG entries (built-ins are not persisted)
            int savedCount = 0;
            for (CosmeticParticleEntry entry : entries.values()) {
                // Filter out built-ins - only save published/config entries
                if (!LegacyCosmeticParticles.isBuiltin(entry.id()) && 
                    entry.source() == CosmeticParticleEntry.Source.CONFIG) {
                    entriesArray.add(toJson(entry));
                    savedCount++;
                }
            }
            
            root.add("entries", entriesArray);
            
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
                gson.toJson(root, writer);
            }
            
            LOGGER.info("[cosmeticslite] Saved {} cosmetic particle entry(ies) to {} (built-ins excluded)", savedCount, path);
        } catch (IOException e) {
            LOGGER.error("[cosmeticslite] Failed to save cosmetic particle catalog to {}: {}", path, e.getMessage(), e);
        }
    }

    /**
     * Load from the configured catalog file.
     */
    public void loadFromFile() {
        loadFromFile(catalogFile);
    }

    /**
     * Reload catalog: seed built-ins, then overlay config entries.
     */
    public void reloadFromConfig(Path configDir) {
        Map<ResourceLocation, CosmeticParticleEntry> map = new LinkedHashMap<>();
        
        // 1) Seed built-ins first
        addBuiltinEntries(map);
        
        // 2) Read config file and overlay
        Path configFile = configDir.resolve("cosmetics_particles.json");
        List<CosmeticParticleEntry> configEntries = new ArrayList<>();
        
        if (Files.exists(configFile)) {
            try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                if (root.has("entries")) {
                    JsonArray entriesArray = root.getAsJsonArray("entries");
                    for (JsonElement element : entriesArray) {
                        try {
                            CosmeticParticleEntry entry = fromJson(element.getAsJsonObject());
                            if (entry != null) {
                                // Ensure config entries are marked as CONFIG
                                entry = entry.withSource(CosmeticParticleEntry.Source.CONFIG);
                                // Config entries override built-ins (if same ID)
                                map.put(entry.id(), entry);
                                configEntries.add(entry);
                            }
                        } catch (Exception e) {
                            LOGGER.warn("[cosmeticslite] Failed to parse catalog entry: {}", e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                LOGGER.error("[cosmeticslite] Failed to load cosmetic particle catalog from {}: {}", configFile, e.getMessage(), e);
            }
        }
        
        // Replace internal map
        entries.clear();
        entries.putAll(map);
        
        // Count actual built-ins (those that weren't overridden)
        long actualBuiltinCount = map.values().stream()
            .filter(e -> e.source() == CosmeticParticleEntry.Source.BUILTIN)
            .count();
        
        int configCount = configEntries.size();
        
        LOGGER.info("[cosmeticslite] Loaded {} cosmetic particle entry(ies) from config and {} built-ins (total: {})",
            configCount, actualBuiltinCount, entries.size());
    }

    /**
     * Reload from default config directory.
     */
    public void reloadFromConfig() {
        Path configDir = catalogFile.getParent();
        if (configDir == null) {
            configDir = Path.of("config", CosmeticsLite.MODID);
        }
        reloadFromConfig(configDir);
    }

    /**
     * Save to the configured catalog file.
     */
    public void saveToFile() {
        saveToFile(catalogFile);
    }

    /**
     * Get all entries.
     */
    public Collection<CosmeticParticleEntry> all() {
        return Collections.unmodifiableCollection(entries.values());
    }

    /**
     * Get entry by ID.
     */
    @Nullable
    public CosmeticParticleEntry get(ResourceLocation id) {
        return entries.get(id);
    }

    /**
     * Add or update an entry.
     */
    public void addOrUpdate(CosmeticParticleEntry entry) {
        entries.put(entry.id(), entry);
    }

    /**
     * Remove an entry.
     */
    public void remove(ResourceLocation id) {
        entries.remove(id);
    }

    /**
     * Deserialize CosmeticParticleEntry from JSON.
     */
    @Nullable
    public static CosmeticParticleEntry fromJson(JsonObject json) {
        try {
            ResourceLocation id = ResourceLocation.parse(json.get("id").getAsString());
            ResourceLocation particleId = ResourceLocation.parse(json.get("particle_id").getAsString());
            String displayNameStr = json.get("display_name").getAsString();
            Component displayName = Component.literal(displayNameStr);
            CosmeticParticleEntry.Slot slot = CosmeticParticleEntry.Slot.fromString(
                json.has("slot") ? json.get("slot").getAsString() : "cape"
            );
            
            String rarity = json.has("rarity") && !json.get("rarity").isJsonNull() 
                ? json.get("rarity").getAsString() : null;
            Integer price = json.has("price") && !json.get("price").isJsonNull()
                ? json.get("price").getAsInt() : null;
            
            CosmeticParticleEntry.Icon icon = null;
            if (json.has("icon") && !json.get("icon").isJsonNull()) {
                JsonObject iconObj = json.getAsJsonObject("icon");
                ResourceLocation itemId = ResourceLocation.parse(iconObj.get("item").getAsString());
                Integer tint = null;
                if (iconObj.has("tint") && !iconObj.get("tint").isJsonNull()) {
                    String tintStr = iconObj.get("tint").getAsString();
                    tint = parseHexColorToArgb(tintStr);
                }
                icon = new CosmeticParticleEntry.Icon(itemId, tint);
            }
            
            // Default to CONFIG if source is missing (backward compatibility)
            CosmeticParticleEntry.Source source = CosmeticParticleEntry.Source.CONFIG;
            if (json.has("source") && !json.get("source").isJsonNull()) {
                source = CosmeticParticleEntry.Source.fromString(json.get("source").getAsString());
            }
            
            return new CosmeticParticleEntry(id, particleId, displayName, slot, rarity, price, icon, source);
        } catch (Exception e) {
            LOGGER.warn("[cosmeticslite] Failed to parse CosmeticParticleEntry from JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Serialize CosmeticParticleEntry to JSON.
     */
    public static JsonObject toJson(CosmeticParticleEntry entry) {
        JsonObject json = new JsonObject();
        json.addProperty("id", entry.id().toString());
        json.addProperty("particle_id", entry.particleId().toString());
        json.addProperty("display_name", entry.displayName().getString());
        json.addProperty("slot", entry.slot().name().toLowerCase());
        
        if (entry.rarity() != null) {
            json.addProperty("rarity", entry.rarity());
        }
        if (entry.price() != null) {
            json.addProperty("price", entry.price());
        }
        
        if (entry.icon() != null) {
            JsonObject iconObj = new JsonObject();
            iconObj.addProperty("item", entry.icon().itemId().toString());
            if (entry.icon().argbTint() != null) {
                iconObj.addProperty("tint", formatArgbToHex(entry.icon().argbTint()));
            }
            json.add("icon", iconObj);
        }
        
        // Only write source for CONFIG entries (built-ins are not saved)
        if (entry.source() == CosmeticParticleEntry.Source.CONFIG) {
            json.addProperty("source", entry.source().name().toLowerCase());
        }
        
        return json;
    }

    /**
     * Parse hex color string (#RRGGBB or #AARRGGBB) to ARGB integer.
     */
    @Nullable
    public static Integer parseHexColorToArgb(String hex) {
        if (hex == null || hex.isBlank()) return null;
        String s = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            if (s.length() == 6) {
                int rgb = (int) Long.parseLong(s, 16);
                return 0xFF000000 | rgb;
            } else if (s.length() == 8) {
                return (int) Long.parseLong(s, 16);
            }
        } catch (NumberFormatException e) {
            LOGGER.warn("[cosmeticslite] Invalid hex color format: {}", hex);
        }
        return null;
    }

    /**
     * Format ARGB integer to hex string (#RRGGBB or #AARRGGBB).
     */
    public static String formatArgbToHex(int argb) {
        return String.format("#%08X", argb);
    }

    /**
     * Get default icon item for a slot.
     */
    public static ResourceLocation defaultIconItemForSlot(CosmeticParticleEntry.Slot slot) {
        return switch (slot) {
            case CAPE  -> ResourceLocation.fromNamespaceAndPath("minecraft", "elytra");
            case HEAD  -> ResourceLocation.fromNamespaceAndPath("minecraft", "player_head");
            case AURA  -> ResourceLocation.fromNamespaceAndPath("minecraft", "amethyst_shard");
            case TRAIL -> ResourceLocation.fromNamespaceAndPath("minecraft", "firework_rocket");
            case MISC  -> ResourceLocation.fromNamespaceAndPath("minecraft", "glowstone_dust");
        };
    }
}

