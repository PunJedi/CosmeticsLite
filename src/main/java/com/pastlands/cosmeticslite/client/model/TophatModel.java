// src/main/java/com/pastlands/cosmeticslite/client/model/TophatModel.java
package com.pastlands.cosmeticslite.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

public class TophatModel<T extends Entity> extends EntityModel<T> {
    // Try alternate texture path format
    public static final ResourceLocation TEXTURE =
    ResourceLocation.fromNamespaceAndPath("cosmeticslite", "textures/item/tophat.png");

    private final ModelPart root;

    public TophatModel(ModelPart root) {
        this.root = root;
    }

    public static final ModelLayerLocation LAYER =
        new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath("cosmeticslite", "tophat"), "main");

    // Body layer definition - Fixed UV mapping and face winding
    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

       // Base rim — brim just above head
root.addOrReplaceChild("base",
    CubeListBuilder.create()
        .texOffs(0, 0)
        .addBox(-8.0F, 0.0F, -8.0F,
                16, 1, 16),
    PartPose.offset(0.0F, -5.5F, 0.0F)); // raise brim

// Hat top — cylinder part stacked above brim
root.addOrReplaceChild("top",
    CubeListBuilder.create()
        .texOffs(0, 20)
        .addBox(-5.0F, -14.0F, -5.0F,
                10, 14, 10),
    PartPose.offset(0.0F, -2.5F, 0.0F)); // same offset so it sits together

        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void setupAnim(T entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        // No animation needed for static hat
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer,
                               int packedLight, int packedOverlay,
                               float red, float green, float blue, float alpha) {
        poseStack.pushPose();
        
        // Remove the Z-axis flip that was causing brim issues
        // poseStack.scale(1.0F, 1.0F, -1.0F);
        
        // Slight offset to prevent z-fighting with player head
        poseStack.translate(0.0F, 0.0F, 0.001F);
        
        root.render(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha);
        
        poseStack.popPose();
    }
}