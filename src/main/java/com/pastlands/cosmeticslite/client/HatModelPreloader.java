// src/main/java/com/pastlands/cosmeticslite/client/HatModelPreloader.java
package com.pastlands.cosmeticslite.client;

import com.mojang.logging.LogUtils;
import com.pastlands.cosmeticslite.CosmeticsLite;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Map;

@Mod.EventBusSubscriber(modid = CosmeticsLite.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class HatModelPreloader {
    private static final Logger LOGGER = LogUtils.getLogger();
    private HatModelPreloader() {}

    /** Bake every JSON under assets/<modid>/models/hats/*.json as an "inventory" model. */
    @SubscribeEvent
    public static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
        try {
            var rm = Minecraft.getInstance().getResourceManager();

            Map<ResourceLocation, Resource> found = rm.listResources(
                "models/hats",
                rl -> rl.getPath().toLowerCase(Locale.ROOT).endsWith(".json")
            );

            int count = 0;
            for (ResourceLocation file : found.keySet()) {
                if (!CosmeticsLite.MODID.equals(file.getNamespace())) continue;

                // file: cosmeticslite:models/hats/burger_hat.json
                String path = file.getPath();
                if (!path.startsWith("models/") || !path.endsWith(".json")) continue;

                // -> cosmeticslite:hats/burger_hat
                String base = path.substring("models/".length(), path.length() - ".json".length());
                ResourceLocation modelId = ResourceLocation.fromNamespaceAndPath(file.getNamespace(), base);

                // Register cosmeticslite:hats/<name> (no #inventory)
event.register(modelId);
LOGGER.info("[{}] Registering hat model: {}", CosmeticsLite.MODID, modelId);
			}

            LOGGER.info("[{}] HatModelPreloader: {} model(s) registered", CosmeticsLite.MODID, count);
        } catch (Throwable t) {
            LOGGER.error("[{}] HatModelPreloader failed while discovering models", CosmeticsLite.MODID, t);
        }
    }
}
