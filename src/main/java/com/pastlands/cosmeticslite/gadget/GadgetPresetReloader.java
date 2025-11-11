package com.pastlands.cosmeticslite.gadget;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.CosmeticsRegistry;
import com.pastlands.cosmeticslite.CosmeticDef;
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
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client resource reloader for Gadget presets / JSON definitions.
 *
 * New path (preferred):
 *   assets/cosmeticslite/gadgets/*.json
 *   e.g. assets/cosmeticslite/gadgets/confetti_popper.json
 *
 * Back-compat path (optional):
 *   assets/cosmeticslite/gadgets_presets.json
 *   {
 *     "cosmeticslite:confetti_popper": { "cooldown_ms": 1200 },
 *     "cosmeticslite:bubble_blower":   { "cooldown_ms": 600 }
 *   }
 *
 * Behavior:
 * - Collects each JSON object into String->String properties (CosmeticDef.properties()).
 * - Validates & clamps known numeric fields (duration_ms, cooldown_ms, count, cone/arc degrees, etc.).
 * - Applies ONLY to cosmetics that exist in the registry.
 * - Silent fallback: invalid JSON entries are skipped with a single warning; no spam during gameplay.
 */
@Mod.EventBusSubscriber(modid = CosmeticsLite.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GadgetPresetReloader extends SimplePreparableReloadListener<Map<ResourceLocation, Map<String, String>>> {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Back-compat single-file presets location
    private static final ResourceLocation LEGACY_PRESETS =
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "gadgets_presets.json");

    // Directory for the new per-gadget JSON files
    private static final String GADGETS_DIR = "gadgets";

    private GadgetPresetReloader() {}

    /** Register this as a CLIENT resource reload listener. */
    @SubscribeEvent
    public static void onRegisterClientReloaders(RegisterClientReloadListenersEvent evt) {
        LOGGER.info("[CosmeticsLite] Registering gadget preset client reload listener");
        evt.registerReloadListener(new GadgetPresetReloader());
    }

    @Override
    protected Map<ResourceLocation, Map<String, String>> prepare(ResourceManager rm, ProfilerFiller profiler) {
        Map<ResourceLocation, Map<String, String>> merged = new LinkedHashMap<>();

        // 1) Preferred path: assets/cosmeticslite/gadgets/*.json
        loadDirectoryPresets(rm, merged);

        // 2) Back-compat: assets/cosmeticslite/gadgets_presets.json
        loadLegacyPresets(rm, merged);

        if (merged.isEmpty()) {
            LOGGER.info("[CosmeticsLite] No gadget JSON presets found (dir '{}' and legacy file '{}').",
                    GADGETS_DIR, LEGACY_PRESETS);
        } else {
            LOGGER.info("[CosmeticsLite] Prepared {} gadget preset(s) from resources.", merged.size());
        }
        return merged;
    }

    @Override
    protected void apply(Map<ResourceLocation, Map<String, String>> data, ResourceManager rm, ProfilerFiller profiler) {
        if (data == null || data.isEmpty()) {
            LOGGER.info("[CosmeticsLite] No gadget presets applied (none loaded).");
            return;
        }

        int applied = 0;
        for (Map.Entry<ResourceLocation, Map<String, String>> e : data.entrySet()) {
            ResourceLocation id = e.getKey();
            CosmeticDef def = CosmeticsRegistry.get(id);
            if (def == null) {
                // Only merge onto known cosmetics; keep logs clean.
                continue;
            }
            CosmeticsRegistry.mergeProps(id, e.getValue());
            applied++;
        }
        LOGGER.info("[CosmeticsLite] Applied {} gadget preset(s).", applied);
    }

    // ------------------------------------------------------------
    // Loading helpers
    // ------------------------------------------------------------

    private void loadDirectoryPresets(ResourceManager rm, Map<ResourceLocation, Map<String, String>> out) {
        // We scan the "gadgets" directory under this mod's namespace.
        // listResources takes a path prefix and a predicate. We only want .json files.
        Map<ResourceLocation, Resource> found;
        try {
            found = rm.listResources(GADGETS_DIR, rl -> rl.getPath().endsWith(".json"));
        } catch (Throwable t) {
            LOGGER.error("[CosmeticsLite] Failed to list gadget JSONs in '{}'.", GADGETS_DIR, t);
            return;
        }

        if (found == null || found.isEmpty()) {
            return;
        }

        int loaded = 0;
        for (Map.Entry<ResourceLocation, Resource> entry : found.entrySet()) {
            ResourceLocation fileLoc = entry.getKey();
            // Require the cosmeticslite namespace for now (avoid picking up others accidentally).
            if (!CosmeticsLite.MODID.equals(fileLoc.getNamespace())) continue;

            // Derive an id from filename: gadgets/<id>.json => cosmeticslite:<id>
            String path = fileLoc.getPath(); // e.g. "gadgets/confetti_popper.json"
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (!name.endsWith(".json")) continue;
            String base = name.substring(0, name.length() - ".json".length());
            if (base.isEmpty()) continue;

            ResourceLocation gadgetId = ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, base);

            try (InputStreamReader reader = new InputStreamReader(entry.getValue().open(), StandardCharsets.UTF_8)) {
                JsonElement rootEl = JsonParser.parseReader(reader);
                if (rootEl == null || !rootEl.isJsonObject()) {
                    LOGGER.warn("[CosmeticsLite] Gadget JSON '{}' is not a JSON object; skipping.", fileLoc);
                    continue;
                }

                JsonObject obj = rootEl.getAsJsonObject();
                Map<String, String> props = coerceAndValidate(obj, /*label*/fileLoc.toString());
                if (!props.isEmpty()) {
                    // Later files override earlier ones (resource pack priority).
                    out.put(gadgetId, props);
                    loaded++;
                }
            } catch (Exception ex) {
                LOGGER.warn("[CosmeticsLite] Failed reading gadget JSON '{}'; skipping.", fileLoc, ex);
            }
        }

        if (loaded > 0) {
            LOGGER.info("[CosmeticsLite] Loaded {} gadget JSON file(s) from '{}'.", loaded, GADGETS_DIR);
        }
    }

    private void loadLegacyPresets(ResourceManager rm, Map<ResourceLocation, Map<String, String>> out) {
        try {
            Resource res = rm.getResource(LEGACY_PRESETS).orElse(null);
            if (res == null) return;

            try (InputStreamReader reader = new InputStreamReader(res.open(), StandardCharsets.UTF_8)) {
                JsonElement rootEl = JsonParser.parseReader(reader);
                if (rootEl == null || !rootEl.isJsonObject()) {
                    LOGGER.warn("[CosmeticsLite] Legacy presets exist but are not a JSON object: {}", LEGACY_PRESETS);
                    return;
                }

                JsonObject root = rootEl.getAsJsonObject();
                int merged = 0;

                for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                    ResourceLocation id = ResourceLocation.tryParse(e.getKey());
                    if (id == null) {
                        LOGGER.warn("[CosmeticsLite] Skipping legacy preset with invalid id: {}", e.getKey());
                        continue;
                    }

                    JsonElement val = e.getValue();
                    if (val == null || !val.isJsonObject()) {
                        LOGGER.warn("[CosmeticsLite] Legacy preset for {} is not an object; skipping.", id);
                        continue;
                    }

                    Map<String, String> props = coerceAndValidate(val.getAsJsonObject(),
                            LEGACY_PRESETS.toString() + "#" + id);
                    if (!props.isEmpty()) {
                        // Directory JSONs (if any) already put entries into 'out'. Legacy entries merge afterwards,
                        // preserving resource pack priority order while still allowing fallback.
                        Map<String, String> existing = out.get(id);
                        if (existing == null) {
                            out.put(id, props);
                        } else {
                            existing.putAll(props);
                        }
                        merged++;
                    }
                }

                LOGGER.info("[CosmeticsLite] Loaded {} legacy gadget preset(s) from {}", merged, LEGACY_PRESETS);
            }
        } catch (Exception ex) {
            LOGGER.error("[CosmeticsLite] Failed loading legacy gadget presets from {}", LEGACY_PRESETS, ex);
        }
    }

    // ------------------------------------------------------------
    // JSON coercion & validation
    // ------------------------------------------------------------

    /**
     * Coerce a JSON object to String->String properties, applying light validation/clamping
     * for known numeric fields. Unknown keys are passed through verbatim as strings.
     */
    private Map<String, String> coerceAndValidate(JsonObject obj, String label) {
        if (obj == null) return Collections.emptyMap();

        Map<String, String> props = new HashMap<>();

        // Known string keys
        copyString(obj, props, "pattern");
        copyString(obj, props, "sound");

        // Known numeric keys with clamping
        clampAndPutInt(obj, props, "duration_ms", 100, 2500, 900, label);
        clampAndPutInt(obj, props, "cooldown_ms", 200, 6000, 1800, label);
        clampAndPutInt(obj, props, "count", 1, 180, 96, label);

        clampAndPutFloat(obj, props, "cone_deg", 0f, 180f, 60f, label);
        clampAndPutFloat(obj, props, "arc_deg", 0f, 360f, 0f, label); // 0 means "unused" for most bursts
        clampAndPutFloat(obj, props, "radius_max", 0.1f, 12f, 3.0f, label);
        clampAndPutFloat(obj, props, "length", 0.1f, 16f, 1.0f, label);
        clampAndPutInt(obj, props, "coils", 1, 8, 1, label);

        clampAndPutFloat(obj, props, "volume", 0.0f, 2.0f, 1.0f, label);
        clampAndPutFloat(obj, props, "pitch", 0.25f, 4.0f, 1.0f, label);

        // Reserved for future “Rocket Pop” style; stored if present, but clamped
        clampAndPutFloat(obj, props, "impulse_v", 0.0f, 1.0f, 0.0f, label);
        clampAndPutInt(obj, props, "slowfall_ms", 0, 2000, 0, label);

        // Pass-through: any other primitive keys become strings verbatim
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            String k = e.getKey();
            if (props.containsKey(k)) continue; // already handled above
            JsonElement v = e.getValue();
            if (v == null || v.isJsonNull()) {
                props.put(k, "");
            } else if (v.isJsonPrimitive()) {
                props.put(k, v.getAsJsonPrimitive().getAsString());
            } else {
                // Keep complex objects/arrays as minified JSON to preserve info without schema explosion
                props.put(k, v.toString());
            }
        }

        return props;
    }

    private static void copyString(JsonObject obj, Map<String, String> props, String key) {
        if (!obj.has(key)) return;
        JsonElement el = obj.get(key);
        if (el != null && el.isJsonPrimitive()) {
            props.put(key, el.getAsJsonPrimitive().getAsString());
        }
    }

    private static void clampAndPutInt(JsonObject obj, Map<String, String> props,
                                       String key, int min, int max, int def, String label) {
        if (!obj.has(key)) return;
        try {
            int raw = obj.get(key).getAsInt();
            int clamped = Math.max(min, Math.min(max, raw));
            if (raw != clamped) {
                LOGGER.warn("[CosmeticsLite] {}: '{}' clamped from {} to {}", label, key, raw, clamped);
            }
            props.put(key, Integer.toString(clamped));
        } catch (Exception ex) {
            LOGGER.warn("[CosmeticsLite] {}: '{}' invalid (expected int); using default {}", label, key, def);
            props.put(key, Integer.toString(def));
        }
    }

    private static void clampAndPutFloat(JsonObject obj, Map<String, String> props,
                                         String key, float min, float max, float def, String label) {
        if (!obj.has(key)) return;
        try {
            float raw = obj.get(key).getAsFloat();
            float clamped = Math.max(min, Math.min(max, raw));
            if (Float.compare(raw, clamped) != 0) {
                LOGGER.warn("[CosmeticsLite] {}: '{}' clamped from {} to {}", label, key, raw, clamped);
            }
            props.put(key, Float.toString(clamped));
        } catch (Exception ex) {
            LOGGER.warn("[CosmeticsLite] {}: '{}' invalid (expected float); using default {}", label, key, def);
            props.put(key, Float.toString(def));
        }
    }
}
