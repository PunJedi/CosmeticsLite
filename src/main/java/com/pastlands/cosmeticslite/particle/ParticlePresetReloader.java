package com.pastlands.cosmeticslite.particle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.particle.config.CosmeticParticleRegistry;
import com.pastlands.cosmeticslite.particle.config.ParticleDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client resource reloader for particle profiles from JSON.
 * Mirrors GadgetPresetReloader structure.
 *
 * Scans: assets/cosmeticslite/particles/particle/*.json
 */
@Mod.EventBusSubscriber(
    modid = CosmeticsLite.MODID,
    value = Dist.CLIENT,
    bus = Mod.EventBusSubscriber.Bus.MOD
)
public final class ParticlePresetReloader extends SimplePreparableReloadListener<Map<ResourceLocation, ParticleProfiles.ParticleProfile>> {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PARTICLE_DIR = "particles/particle";

    static {
        // Ensure class is loaded - this should fire when the class is first referenced
        LOGGER.info("[cosmeticslite] ParticlePresetReloader class loaded (static initializer)");
    }
    
    // Force class loading by referencing it
    public static void ensureLoaded() {
        LOGGER.info("[cosmeticslite] ParticlePresetReloader.ensureLoaded() called");
    }

    private ParticlePresetReloader() {}

    @SubscribeEvent
    public static void onRegisterClientReloaders(RegisterClientReloadListenersEvent evt) {
        LOGGER.info("[cosmeticslite] Registering particle profile client reload listener");
        evt.registerReloadListener(new ParticlePresetReloader());
        LOGGER.info("[cosmeticslite] ParticlePresetReloader registered successfully");
    }

    @Override
    protected Map<ResourceLocation, ParticleProfiles.ParticleProfile> prepare(ResourceManager rm, ProfilerFiller profiler) {
        Map<ResourceLocation, ParticleProfiles.ParticleProfile> result = new LinkedHashMap<>();

        LOGGER.info("[cosmeticslite] ParticlePresetReloader.prepare() called");
        Map<ResourceLocation, Resource> resources;
        try {
            LOGGER.info("[cosmeticslite] Scanning for resources in '{}' with namespace '{}'", PARTICLE_DIR, CosmeticsLite.MODID);
            resources = rm.listResources(
                PARTICLE_DIR,
                loc -> {
                    boolean matches = loc.getPath().endsWith(".json") && loc.getNamespace().equals(CosmeticsLite.MODID);
                    if (matches) {
                        LOGGER.debug("[cosmeticslite] Resource matches: {}", loc);
                    }
                    return matches;
                }
            );
        } catch (Throwable t) {
            LOGGER.error("[cosmeticslite] Failed to list particle JSONs in '{}'.", PARTICLE_DIR, t);
            return result;
        }

        if (resources == null || resources.isEmpty()) {
            LOGGER.warn("[cosmeticslite] No particle JSON profiles found in '{}'. Searched namespace: {}", PARTICLE_DIR, CosmeticsLite.MODID);
            return result;
        }

        LOGGER.info("[cosmeticslite] Found {} resource(s) in '{}'", resources.size(), PARTICLE_DIR);

        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation fileLoc = entry.getKey();
            // Path example: cosmeticslite:particles/particle/heart_blended.json

            // Derive the cosmetic id from the filename
            String path = fileLoc.getPath(); // "particles/particle/heart_blended.json"
            String fileName = path.substring(path.lastIndexOf('/') + 1, path.length() - ".json".length());
            ResourceLocation cosmeticId = ResourceLocation.fromNamespaceAndPath(
                fileLoc.getNamespace(),
                "particle/" + fileName
            ); // cosmeticslite:particle/heart_blended

            LOGGER.info("[cosmeticslite] Loading particle profile from '{}' -> cosmeticId='{}'", fileLoc, cosmeticId);

            try (InputStreamReader reader = new InputStreamReader(entry.getValue().open(), StandardCharsets.UTF_8)) {
                ParticleProfiles.ParticleProfile profile = parseProfile(fileLoc, cosmeticId, reader);
                if (profile != null) {
                    result.put(cosmeticId, profile);
                    LOGGER.info("[cosmeticslite] Successfully parsed profile for '{}' with {} world layer(s)",
                        cosmeticId, profile.worldLayers().size());
                } else {
                    LOGGER.warn("[cosmeticslite] parseProfile returned null for '{}'", fileLoc);
                }
            } catch (Exception ex) {
                LOGGER.warn("[cosmeticslite] Failed reading particle JSON '{}'; skipping.", fileLoc, ex);
            }
        }

        LOGGER.info("[cosmeticslite] ParticlePresetReloader.prepare found {} JSON(s): {}",
            result.size(), result.keySet());

        return result;
    }

    @Override
    protected void apply(Map<ResourceLocation, ParticleProfiles.ParticleProfile> prepared,
                         ResourceManager rm, ProfilerFiller profiler) {
        // Update ParticleProfiles (used by renderer)
        ParticleProfiles.replaceAll(prepared);
        LOGGER.info("[cosmeticslite] ParticlePresetReloader.apply loaded {} particle profile(s): {}",
            prepared.size(), ParticleProfiles.debugKeys());
        
        // Convert to ParticleDefinition and update CosmeticParticleRegistry
        Map<ResourceLocation, ParticleDefinition> builtinDefinitions = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, ParticleProfiles.ParticleProfile> entry : prepared.entrySet()) {
            ParticleDefinition def = CosmeticParticleRegistry.fromParticleProfile(entry.getValue());
            if (def != null) {
                builtinDefinitions.put(def.id(), def);
            }
        }
        
        // Apply built-in profiles to registry (leaves lab entries untouched)
        CosmeticParticleRegistry.applyBuiltinProfiles(builtinDefinitions);
        LOGGER.info("[cosmeticslite] Applied {} built-in particle definition(s) to registry (lab entries preserved)",
            builtinDefinitions.size());
    }

    private ParticleProfiles.ParticleProfile parseProfile(ResourceLocation fileLoc,
                                                         ResourceLocation cosmeticId,
                                                         Reader reader) {
        try {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

            // id field is optional; if present, log mismatch but ignore for keying
            ResourceLocation profileId = fileLoc;
            if (root.has("id")) {
                JsonElement idEl = root.get("id");
                if (idEl != null && idEl.isJsonPrimitive()) {
                    ResourceLocation explicitId = ResourceLocation.tryParse(idEl.getAsString());
                    if (explicitId != null) {
                        profileId = explicitId;
                        if (!explicitId.equals(cosmeticId)) {
                            LOGGER.debug("[cosmeticslite] Profile '{}' has explicit id '{}' but cosmeticId is '{}'",
                                fileLoc, explicitId, cosmeticId);
                        }
                    }
                }
            }

            // Parse GUI layers
            List<ParticleProfiles.GuiLayerConfig> guiLayers = parseGuiLayers(root, fileLoc);

            // Parse world layers
            List<ParticleProfiles.WorldLayerConfig> worldLayers = parseWorldLayers(root, fileLoc);

            return new ParticleProfiles.ParticleProfile(profileId, cosmeticId, guiLayers, worldLayers);
        } catch (Exception ex) {
            LOGGER.warn("[cosmeticslite] Failed parsing particle profile '{}'; skipping.", fileLoc, ex);
            return null;
        }
    }

    private List<ParticleProfiles.GuiLayerConfig> parseGuiLayers(JsonObject root, ResourceLocation fileLoc) {
        List<ParticleProfiles.GuiLayerConfig> layers = new ArrayList<>();

        if (root.has("layers") && root.get("layers").isJsonArray()) {
            JsonArray layersArr = root.getAsJsonArray("layers");
            for (JsonElement layerEl : layersArr) {
                if (layerEl != null && layerEl.isJsonObject()) {
                    ParticleProfiles.GuiLayerConfig layer = parseGuiLayer(layerEl.getAsJsonObject(), fileLoc);
                    if (layer != null) {
                        layers.add(layer);
                    }
                }
            }
        }

        return layers;
    }

    private ParticleProfiles.GuiLayerConfig parseGuiLayer(JsonObject layerObj, ResourceLocation fileLoc) {
        try {
            // Movement (handle aliases: ORBIT -> SWIRL, PULSE -> BURST)
            ParticleProfiles.Movement movement = ParticleProfiles.Movement.DEFAULT;
            if (layerObj.has("movement")) {
                String movementStr = layerObj.get("movement").getAsString();
                String upper = movementStr.toUpperCase();
                if ("ORBIT".equals(upper)) {
                    movement = ParticleProfiles.Movement.SWIRL;
                } else if ("PULSE".equals(upper)) {
                    movement = ParticleProfiles.Movement.BURST;
                } else {
                    try {
                        movement = ParticleProfiles.Movement.valueOf(upper);
                    } catch (IllegalArgumentException e) {
                        LOGGER.warn("[cosmeticslite] Unknown movement '{}' in '{}'; using DEFAULT.", movementStr, fileLoc);
                    }
                }
            }

            // Colors
            List<Integer> colors = new ArrayList<>();
            if (layerObj.has("colors") && layerObj.get("colors").isJsonArray()) {
                JsonArray colorsArr = layerObj.getAsJsonArray("colors");
                for (JsonElement colorEl : colorsArr) {
                    if (colorEl != null && colorEl.isJsonPrimitive()) {
                        String colorStr = colorEl.getAsString();
                        Integer color = parseColor(colorStr);
                        if (color != null) {
                            colors.add(color);
                        }
                    }
                }
            }

            // Numeric fields with clamping
            int lifespan = 30;
            if (layerObj.has("lifespan")) {
                lifespan = Math.max(10, Math.min(200, layerObj.get("lifespan").getAsInt()));
            }

            int spawnInterval = 2;
            if (layerObj.has("spawn_interval")) {
                spawnInterval = Math.max(1, Math.min(20, layerObj.get("spawn_interval").getAsInt()));
            }

            int size = 2;
            if (layerObj.has("size")) {
                size = Math.max(1, Math.min(5, layerObj.get("size").getAsInt()));
            }

            float speed = 1.0f;
            if (layerObj.has("speed")) {
                speed = Math.max(0.1f, Math.min(5.0f, layerObj.get("speed").getAsFloat()));
            }

            float weight = 1.0f;
            if (layerObj.has("weight")) {
                weight = Math.max(0.1f, Math.min(5.0f, layerObj.get("weight").getAsFloat()));
            }

            float previewScale = 1.0f;
            if (layerObj.has("preview_scale")) {
                previewScale = Math.max(0.5f, Math.min(3.0f, layerObj.get("preview_scale").getAsFloat()));
            }

            return new ParticleProfiles.GuiLayerConfig(movement, colors, lifespan, spawnInterval, size, speed, weight, previewScale);
        } catch (Exception ex) {
            LOGGER.warn("[cosmeticslite] Failed parsing GUI layer in '{}'; skipping layer.", fileLoc, ex);
            return null;
        }
    }

    private List<ParticleProfiles.WorldLayerConfig> parseWorldLayers(JsonObject root, ResourceLocation fileLoc) {
        List<ParticleProfiles.WorldLayerConfig> worldLayers = new ArrayList<>();

        if (root.has("world_layers") && root.get("world_layers").isJsonArray()) {
            JsonArray worldLayersArr = root.getAsJsonArray("world_layers");
            for (JsonElement worldLayerEl : worldLayersArr) {
                if (worldLayerEl != null && worldLayerEl.isJsonObject()) {
                    ParticleProfiles.WorldLayerConfig worldLayer = parseWorldLayer(worldLayerEl.getAsJsonObject(), fileLoc);
                    if (worldLayer != null) {
                        worldLayers.add(worldLayer);
                    }
                }
            }
        }

        return worldLayers;
    }

    private ParticleProfiles.WorldLayerConfig parseWorldLayer(JsonObject worldLayerObj, ResourceLocation fileLoc) {
        try {
            // Effect is mandatory
            ResourceLocation effect = null;
            if (worldLayerObj.has("effect")) {
                String effectStr = worldLayerObj.get("effect").getAsString();
                effect = ResourceLocation.tryParse(effectStr);
                if (effect == null) {
                    LOGGER.warn("[cosmeticslite] Invalid effect '{}' in world layer of '{}'; skipping layer.", effectStr, fileLoc);
                    return null;
                }
            } else {
                LOGGER.warn("[cosmeticslite] World layer in '{}' missing required 'effect' field; skipping layer.", fileLoc);
                return null;
            }

            // Style is required
            String style = "halo";
            if (worldLayerObj.has("style")) {
                style = worldLayerObj.get("style").getAsString();
            }

            // Numeric fields with clamping
            float radius = 0.5f;
            if (worldLayerObj.has("radius")) {
                float raw = worldLayerObj.get("radius").getAsFloat();
                radius = Math.max(0.1f, Math.min(2.0f, raw));
                if (Float.compare(raw, radius) != 0) {
                    LOGGER.warn("[cosmeticslite] {}: 'radius' clamped from {} to {}", fileLoc, raw, radius);
                }
            }

            float heightFactor = 0.85f;
            if (worldLayerObj.has("height_factor")) {
                float raw = worldLayerObj.get("height_factor").getAsFloat();
                heightFactor = Math.max(0.0f, Math.min(2.0f, raw));
                if (Float.compare(raw, heightFactor) != 0) {
                    LOGGER.warn("[cosmeticslite] {}: 'height_factor' clamped from {} to {}", fileLoc, raw, heightFactor);
                }
            }

            int count = 2;
            if (worldLayerObj.has("count")) {
                int raw = worldLayerObj.get("count").getAsInt();
                count = Math.max(1, Math.min(20, raw));
                if (raw != count) {
                    LOGGER.warn("[cosmeticslite] {}: 'count' clamped from {} to {}", fileLoc, raw, count);
                }
            }

            float speedY = 0.01f;
            if (worldLayerObj.has("speed_y")) {
                float raw = worldLayerObj.get("speed_y").getAsFloat();
                speedY = Math.max(0.0f, Math.min(0.1f, raw));
                if (Float.compare(raw, speedY) != 0) {
                    LOGGER.warn("[cosmeticslite] {}: 'speed_y' clamped from {} to {}", fileLoc, raw, speedY);
                }
            }

            return new ParticleProfiles.WorldLayerConfig(effect, style, radius, heightFactor, count, speedY);
        } catch (Exception ex) {
            LOGGER.warn("[cosmeticslite] Failed parsing world layer in '{}'; skipping layer.", fileLoc, ex);
            return null;
        }
    }

    private Integer parseColor(String colorStr) {
        if (colorStr == null || colorStr.isEmpty()) return null;

        // Handle #RRGGBB or #AARRGGBB format
        if (colorStr.startsWith("#")) {
            try {
                String hex = colorStr.substring(1);
                if (hex.length() == 6) {
                    // Add alpha
                    return 0xFF000000 | Integer.parseInt(hex, 16);
                } else if (hex.length() == 8) {
                    return (int) Long.parseLong(hex, 16);
                }
            } catch (NumberFormatException e) {
                // Invalid hex
            }
        }

        // Try parsing as integer directly
        try {
            return Integer.parseInt(colorStr);
        } catch (NumberFormatException e) {
            // Invalid
        }

        return null;
    }
}
