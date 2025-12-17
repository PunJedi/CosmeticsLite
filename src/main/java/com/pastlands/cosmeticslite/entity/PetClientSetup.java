// src/main/java/com/pastlands/cosmeticslite/entity/PetClientSetup.java
package com.pastlands.cosmeticslite.entity;

import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.entity.render.CosmeticDonkeyRenderer;
import com.pastlands.cosmeticslite.entity.render.CosmeticMuleRenderer;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Client-side setup for pet entity renderers (Forge 47.4.0 / MC 1.20.1). */
@Mod.EventBusSubscriber(modid = CosmeticsLite.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class PetClientSetup {
    private PetClientSetup() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // === Original pets ===
            EntityRenderers.register(PetEntities.COSMETIC_PET_WOLF.get(),    WolfRenderer::new);
            EntityRenderers.register(PetEntities.COSMETIC_PET_CAT.get(),     CatRenderer::new);
            EntityRenderers.register(PetEntities.COSMETIC_PET_CHICKEN.get(), ChickenRenderer::new);

            EntityRenderers.register(PetEntities.COSMETIC_PET_FOX.get(),     FoxRenderer::new);
            EntityRenderers.register(PetEntities.COSMETIC_PET_AXOLOTL.get(), AxolotlRenderer::new);
            EntityRenderers.register(PetEntities.COSMETIC_PET_BEE.get(),     BeeRenderer::new);
            EntityRenderers.register(PetEntities.COSMETIC_PET_RABBIT.get(),  RabbitRenderer::new);
            EntityRenderers.register(PetEntities.COSMETIC_PET_PIG.get(),     PigRenderer::new);
            EntityRenderers.register(PetEntities.COSMETIC_PET_SHEEP.get(),   SheepRenderer::new);
            EntityRenderers.register(PetEntities.COSMETIC_PET_PANDA.get(),   PandaRenderer::new);
            EntityRenderers.register(PetEntities.COSMETIC_PET_PARROT.get(),  ParrotRenderer::new);

            // === Newer pets ===
            EntityRenderers.register(PetEntities.COSMETIC_PET_HORSE.get(),     HorseRenderer::new);
            EntityRenderers.register(PetEntities.COSMETIC_PET_LLAMA.get(),
                    ctx -> new LlamaRenderer(ctx, ModelLayers.LLAMA));
            EntityRenderers.register(PetEntities.COSMETIC_PET_FROG.get(),      FrogRenderer::new);
            EntityRenderers.register(PetEntities.COSMETIC_PET_MOOSHROOM.get(), MushroomCowRenderer::new);

            // === Recently added base mobs ===
            EntityRenderers.register(PetEntities.COSMETIC_PET_DONKEY.get(), CosmeticDonkeyRenderer::new);
            EntityRenderers.register(PetEntities.COSMETIC_PET_MULE.get(),   CosmeticMuleRenderer::new);
            EntityRenderers.register(PetEntities.COSMETIC_PET_CAMEL.get(),
                    ctx -> new CamelRenderer(ctx, ModelLayers.CAMEL));
            EntityRenderers.register(PetEntities.COSMETIC_PET_GOAT.get(), GoatRenderer::new);
            EntityRenderers.register(PetEntities.COSMETIC_PET_OCELOT.get(), OcelotRenderer::new);
            EntityRenderers.register(PetEntities.COSMETIC_PET_COW.get(), CowRenderer::new);

            // These take ONLY context
            EntityRenderers.register(PetEntities.COSMETIC_PET_VILLAGER.get(), VillagerRenderer::new);
            EntityRenderers.register(PetEntities.COSMETIC_PET_VEX.get(), VexRenderer::new);
            EntityRenderers.register(PetEntities.COSMETIC_PET_BLAZE.get(), BlazeRenderer::new);
            EntityRenderers.register(PetEntities.COSMETIC_PET_SNOW_GOLEM.get(), SnowGolemRenderer::new);
            EntityRenderers.register(PetEntities.COSMETIC_PET_IRON_GOLEM.get(), IronGolemRenderer::new);
        });
    }
}
