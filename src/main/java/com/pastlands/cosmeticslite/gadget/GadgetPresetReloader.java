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
 * Client resource reloader that merges gadget preset properties into CosmeticDef.properties().
 *
 * Looks for: assets/cosmeticslite/gadgets_presets.json  (in this mod or any active resource pack)
 *
 * Format:
 * {
 *   "cosmeticslite:confetti_popper": { "cooldown": "1200" },
 *   "cosmeticslite:bubble_blower":   { "cooldown": "600"  },
 *   "cosmeticslite:gear_spark_emitter": { "cooldown": "2000" }
 * }
 *
 * Notes:
 * - Values are coerced to strings (CosmeticDef.properties() is String->String).
 * - Applies on client resource reload; UI then reads via GadgetClientCommands.cooldownMillisFor(...).
 */
@Mod.EventBusSubscriber(modid = CosmeticsLite.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GadgetPresetReloader extends SimplePreparableReloadListener<Map<ResourceLocation, Map<String, String>>> {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation PRESETS_LOC =
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "gadgets_presets.json");

    private GadgetPresetReloader() {}

    /** Register this as a CLIENT resource reload listener. */
    @SubscribeEvent
    public static void onRegisterClientReloaders(RegisterClientReloadListenersEvent evt) {
        LOGGER.info("[CosmeticsLite] Registering gadget preset client reload listener");
        evt.registerReloadListener(new GadgetPresetReloader());
    }

    @Override
    protected Map<ResourceLocation, Map<String, String>> prepare(ResourceManager rm, ProfilerFiller profiler) {
        try {
            Resource res = rm.getResource(PRESETS_LOC).orElse(null);
            if (res == null) {
                LOGGER.info("[CosmeticsLite] No gadget presets found at {}", PRESETS_LOC);
                return Collections.emptyMap();
            }

            try (InputStreamReader reader = new InputStreamReader(res.open(), StandardCharsets.UTF_8)) {
                JsonElement rootEl = JsonParser.parseReader(reader);
                if (rootEl == null || !rootEl.isJsonObject()) {
                    LOGGER.warn("[CosmeticsLite] Gadget presets file exists but is not a JSON object: {}", PRESETS_LOC);
                    return Collections.emptyMap();
                }

                JsonObject root = rootEl.getAsJsonObject();
                Map<ResourceLocation, Map<String, String>> out = new LinkedHashMap<>();

                for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                    ResourceLocation id = ResourceLocation.tryParse(e.getKey());
                    if (id == null) {
                        LOGGER.warn("[CosmeticsLite] Skipping gadget preset with invalid id key: {}", e.getKey());
                        continue;
                    }

                    JsonElement val = e.getValue();
                    if (val == null || !val.isJsonObject()) {
                        LOGGER.warn("[CosmeticsLite] Preset for {} is not an object; skipping.", id);
                        continue;
                    }

                    JsonObject propsObj = val.getAsJsonObject();
                    Map<String, String> props = new HashMap<>();
                    for (Map.Entry<String, JsonElement> pe : propsObj.entrySet()) {
                        String k = pe.getKey();
                        JsonElement v = pe.getValue();
                        String asString = (v == null || v.isJsonNull())
                                ? ""
                                : (v.isJsonPrimitive() ? v.getAsJsonPrimitive().getAsString() : v.toString());
                        props.put(k, asString);
                    }

                    out.put(id, props);
                }

                LOGGER.info("[CosmeticsLite] Loaded {} gadget preset(s) from {}", out.size(), PRESETS_LOC);
                return out;
            }
        } catch (Exception ex) {
            LOGGER.error("[CosmeticsLite] Failed loading gadget presets from {}", PRESETS_LOC, ex);
            return Collections.emptyMap();
        }
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
            if (def == null) continue; // only merge onto known cosmetics
            CosmeticsRegistry.mergeProps(id, e.getValue());
            applied++;
        }
        LOGGER.info("[CosmeticsLite] Applied {} gadget preset(s) from {}", applied, PRESETS_LOC);
    }
}
