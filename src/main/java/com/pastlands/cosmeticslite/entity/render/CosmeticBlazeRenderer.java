package com.pastlands.cosmeticslite.entity.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pastlands.cosmeticslite.entity.CosmeticPetBlaze;
import com.pastlands.cosmeticslite.entity.render.layer.CosmeticGlowLayer;
import net.minecraft.client.model.BlazeModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

public class CosmeticBlazeRenderer extends MobRenderer<CosmeticPetBlaze, EntityModel<CosmeticPetBlaze>> {

    private static final ResourceLocation BLAZE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("cosmeticslite", "textures/entity/pet/blaze.png");

    @SuppressWarnings("unchecked")
    public CosmeticBlazeRenderer(EntityRendererProvider.Context context) {
        // Cast the vanilla BlazeModel to EntityModel<CosmeticPetBlaze>
        super(context, (EntityModel<CosmeticPetBlaze>) (Object)
                new BlazeModel<>(context.bakeLayer(ModelLayers.BLAZE)), 0.5F);

        // Add custom glow layer
        this.addLayer(new CosmeticGlowLayer<>(this, BLAZE_TEXTURE));
    }

    @Override
    public ResourceLocation getTextureLocation(CosmeticPetBlaze entity) {
        return BLAZE_TEXTURE;
    }

    @Override
    public void render(CosmeticPetBlaze entity, float entityYaw, float partialTicks,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        poseStack.scale(0.7F, 0.7F, 0.7F);
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }
}
