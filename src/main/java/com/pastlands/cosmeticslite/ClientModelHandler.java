// src/main/java/com/pastlands/cosmeticslite/ClientModelHandler.java
package com.pastlands.cosmeticslite;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CosmeticsLite.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModelHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void registerAdditionalModels(ModelEvent.RegisterAdditional event) {
        System.out.println("[CosmeticsLite] Registering additional models...");
        
        // NOTE: Tophat uses hardcoded EntityModel approach, no JSON model registration needed
        
        System.out.println("[CosmeticsLite] Model registration complete");
    }
}