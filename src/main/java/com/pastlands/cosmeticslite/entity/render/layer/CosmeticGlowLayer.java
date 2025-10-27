package com.pastlands.cosmeticslite.entity.render.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * Generic glow layer for cosmetic pets.
 * Renders the base texture as emissive (ignores light).
 */
public class CosmeticGlowLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {

    private final ResourceLocation texture;

    public CosmeticGlowLayer(RenderLayerParent<T, M> parent, ResourceLocation texture) {
        super(parent);
        this.texture = texture;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       T entity, float limbSwing, float limbSwingAmount,
                       float partialTicks, float ageInTicks, float netHeadYaw, float headPitch) {
        // This version matches RenderLayer’s LivingEntity requirement
        renderColoredCutoutModel(getParentModel(), texture, poseStack, buffer,
                0xF000F0, entity, 1.0F, 1.0F, 1.0F);
    }
}
