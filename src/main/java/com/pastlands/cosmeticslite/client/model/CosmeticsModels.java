// src/main/java/com/pastlands/cosmeticslite/client/model/CosmeticsModels.java
package com.pastlands.cosmeticslite.client.model;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.EntityRenderersEvent;

/**
 * Registry holder for custom cosmetic models.
 * Forge 47.4.0 • MC 1.20.1 • Java 17
 */
public class CosmeticsModels {

    // Model layer location for the Tophat
    public static final ModelLayerLocation TOPHAT =
        new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath("cosmeticslite", "tophat"), "main");

    // Register layer definitions
    public static void registerLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(TOPHAT, TophatModel::createBodyLayer);
    }

    // Get baked Tophat model
    public static TophatModel<?> getTophatModel(net.minecraft.client.model.geom.EntityModelSet models) {
        ModelPart part = models.bakeLayer(TOPHAT);
        return new TophatModel<>(part);
    }
}