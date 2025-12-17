// src/main/java/com/pastlands/cosmeticslite/ModLifecycle.java
package com.pastlands.cosmeticslite;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod.EventBusSubscriber(modid = CosmeticsLite.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModLifecycle {
    private ModLifecycle() {}

    @SubscribeEvent
    public static void onCommonSetup(final FMLCommonSetupEvent event) {
        // Common setup tasks (if any)
    }
}
