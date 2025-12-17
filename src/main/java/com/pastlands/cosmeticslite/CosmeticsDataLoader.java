// src/main/java/com/pastlands/cosmeticslite/CosmeticsDataLoader.java
package com.pastlands.cosmeticslite;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = CosmeticsLite.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CosmeticsDataLoader {
    private static final Logger LOG = LogUtils.getLogger();

    private CosmeticsDataLoader() {}

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        LOG.info("[{}] Registering server reload listener", CosmeticsLite.MODID);
        event.addListener(new DirectFileLoader());
    }

    @Mod.EventBusSubscriber(modid = CosmeticsLite.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ClientSide {
        @SubscribeEvent
        public static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
            LOG.info("[{}] Registering client reload listener", CosmeticsLite.MODID);
            event.registerReloadListener(new DirectFileLoader());
        }
    }

    // Loader based on SimpleJsonResourceReloadListener
static final class DirectFileLoader extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    DirectFileLoader() {
        super(GSON, "cosmetics"); // watches data/<namespace>/cosmetics/*.json
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> objects,
                         ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        LOG.info("[{}] Cosmetics loader running, found {} JSONs",
                CosmeticsLite.MODID, objects.size());

        // NEW DEBUG: dump raw keys that Forge passed in
        LOG.info("[{}] DEBUG: Raw keys passed into loader: {}",
                CosmeticsLite.MODID, objects.keySet());

        List<CosmeticDef> loaded = new ArrayList<>();

        for (Map.Entry<ResourceLocation, JsonElement> entry : objects.entrySet()) {
            ResourceLocation rl = entry.getKey();
            JsonElement element = entry.getValue();
            try {
                if (element.isJsonObject()) {
                    CosmeticDef def = parseDef(rl, element.getAsJsonObject());
                    if (def != null) {
                        loaded.add(def);
                        LOG.info("[{}] Loaded cosmetic: {} -> {}", CosmeticsLite.MODID, rl, def.id());
                    }
                }
            } catch (Exception ex) {
                LOG.error("[{}] Failed to parse cosmetic JSON {}: {}", CosmeticsLite.MODID, rl, ex.getMessage());
            }
        }

        LOG.info("[{}] Applying {} cosmetics to registry", CosmeticsLite.MODID, loaded.size());
        CosmeticsRegistry.replaceAll(loaded, false);
    
}


        // ------------------------
        // Helpers
        // ------------------------

        private static CosmeticDef parseDef(ResourceLocation fileId, JsonObject root) {
            try {
                ResourceLocation id = tryParseRL(optString(root, "id"));
                if (id == null) id = fileId;

                String name = optString(root, "name");
                String desc = optString(root, "description");
                String type = optString(root, "type");

                if (isBlank(type)) {
                    type = CosmeticsRegistry.TYPE_PARTICLES;
                }
                if (isBlank(name)) name = id.getPath();

                ResourceLocation icon = tryParseRL(optString(root, "icon"));
                if (icon == null) icon = tryParseRL(optString(root, "icon_item"));
                if (icon == null) {
                    icon = switch (type) {
                        case "hats"      -> rl("minecraft", "diamond");
                        case "capes"     -> rl("minecraft", "gold_ingot");
                        case "particles" -> rl("minecraft", "blue_dye");
                        case "pets"      -> rl("minecraft", "bone");
                        default          -> rl("minecraft", "paper");
                    };
                }

                Map<String, String> props = new HashMap<>();
                if (root.has("properties") && root.get("properties").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> pe : root.getAsJsonObject("properties").entrySet()) {
                        props.put(pe.getKey(), pe.getValue().getAsString());
                    }
                }

                return new CosmeticDef(id, name, desc, type, icon, props);
            } catch (Exception ex) {
                LOG.error("[{}] Exception parsing CosmeticDef from {}: {}", CosmeticsLite.MODID, fileId, ex.toString());
                return null;
            }
        }

        private static boolean isBlank(String s) { return s == null || s.isEmpty(); }

        private static String optString(JsonObject o, String key) {
            return (o.has(key) && o.get(key).isJsonPrimitive()) ? o.get(key).getAsString() : null;
        }

        private static ResourceLocation tryParseRL(String s) {
            if (isBlank(s)) return null;
            try {
                return ResourceLocation.tryParse(s);
            } catch (Exception ex) {
                return null;
            }
        }

        private static ResourceLocation rl(String ns, String path) {
            return ResourceLocation.fromNamespaceAndPath(ns, path);
        }
    }
}
