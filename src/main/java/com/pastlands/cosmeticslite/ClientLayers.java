package com.pastlands.cosmeticslite;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.EntityRenderersEvent;

import net.minecraft.client.renderer.entity.player.PlayerRenderer;

/** Registers client-only render layers for player models. */
@Mod.EventBusSubscriber(modid = CosmeticsLite.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientLayers {

    private ClientLayers() {}

    @SubscribeEvent
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        // Vanilla has two player skins: "default" (Steve) and "slim" (Alex).
        for (String skin : event.getSkins()) {
            PlayerRenderer renderer = event.getSkin(skin);
            if (renderer == null) continue;

            // Order: cape first (behind), hat after (on the head), pets last (near player)
            renderer.addLayer(new CosmeticCapeLayer(renderer));
            renderer.addLayer(new CosmeticHatLayer(renderer));
            renderer.addLayer(new PetRenderLayer(renderer));    // NEW: pet render layer
        }
    }
}