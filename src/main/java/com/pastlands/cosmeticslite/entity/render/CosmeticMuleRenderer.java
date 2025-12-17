package com.pastlands.cosmeticslite.entity.render;

import com.pastlands.cosmeticslite.entity.CosmeticPetMule;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.ChestedHorseRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

public class CosmeticMuleRenderer extends ChestedHorseRenderer<CosmeticPetMule> {

    private static final ResourceLocation MULE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/horse/mule.png");

    public CosmeticMuleRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, 0.92F, ModelLayers.MULE);
    }

    @Override
    public ResourceLocation getTextureLocation(CosmeticPetMule mule) {
        return MULE_TEXTURE;
    }
}
