package com.pastlands.cosmeticslite.entity.render;

import com.pastlands.cosmeticslite.entity.CosmeticPetDonkey;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.ChestedHorseRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

public class CosmeticDonkeyRenderer extends ChestedHorseRenderer<CosmeticPetDonkey> {

    private static final ResourceLocation DONKEY_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/horse/donkey.png");

    public CosmeticDonkeyRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, 0.87F, ModelLayers.DONKEY);
    }

    @Override
    public ResourceLocation getTextureLocation(CosmeticPetDonkey donkey) {
        return DONKEY_TEXTURE;
    }
}
