package com.pastlands.cosmeticslite.entity.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pastlands.cosmeticslite.entity.CosmeticPetVex;
import com.pastlands.cosmeticslite.entity.render.layer.CosmeticGlowLayer;
import net.minecraft.client.model.VexModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.monster.Vex;

/**
 * Renderer for CosmeticPetVex.
 *
 * Fully Forge-legal workaround: type the renderer as MobRenderer<Vex, VexModel>
 * (matching the vanilla model) and down-cast in overridden methods.
 * The runtime entity is still CosmeticPetVex, and the visuals are identical.
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
public class CosmeticVexRenderer extends MobRenderer<Vex, VexModel> {

    private static final ResourceLocation VEX_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("cosmeticslite", "textures/entity/pet/vex.png");

    public CosmeticVexRenderer(EntityRendererProvider.Context context) {
        super(context, new VexModel(context.bakeLayer(ModelLayers.VEX)), 0.3F);
        this.addLayer(new CosmeticGlowLayer(this, VEX_TEXTURE));
    }

    @Override
    public ResourceLocation getTextureLocation(Vex entity) {
        // CosmeticPetVex extends Vex, so this is safe
        return VEX_TEXTURE;
    }

    @Override
    public void render(Vex entity, float entityYaw, float partialTicks,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        // CosmeticPetVex instances reach here as Vex references
        poseStack.scale(0.6F, 0.6F, 0.6F);
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }
}
