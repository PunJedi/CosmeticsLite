package com.pastlands.cosmeticslite;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.pastlands.cosmeticslite.client.state.ScreenState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pet render layer that displays cosmetic pets near the GUI mannequin ONLY.
 *
 * Requested behavior:
 * - The tiny orbiting pet preview should appear ONLY on the Pets tab in the cosmetics screen.
 * - Real follower pets in-world are managed elsewhere and are not rendered here.
 *
 * Forge 47.4.0 (MC 1.20.1)
 */
public class PetRenderLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    // Cache preview-only pet entities per mannequin player to avoid constant recreation
    private static final Map<AbstractClientPlayer, Entity> PET_CACHE = new ConcurrentHashMap<>();

    public PetRenderLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                       AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {

        // Only render inside the cosmetics GUI
        if (!(Minecraft.getInstance().screen instanceof CosmeticsChestScreen)) {
            return;
        }

        // Gate to the Pets tab only
if (!(Minecraft.getInstance().screen instanceof CosmeticsChestScreen chest)) {
    PET_CACHE.remove(player);
    return;
}

ScreenState state = chest.getScreenState();
if (state == null || !ScreenState.TYPE_PETS.equals(state.getActiveType())) {
    // Clear cached mannequin entity for this player so we don't leak
    PET_CACHE.remove(player);
    return;
}


        // Determine which pet to preview:
        // 1) Preview override (hover/selection on Pets tab)
        // 2) Otherwise the currently equipped pet (for a quick preview)
        ResourceLocation petId = CosmeticsChestScreen.PreviewResolver.getOverride("pets", player);
        if (petId == null) {
            petId = ClientState.getEquippedId(player, "pets");
        }
        if (petId == null || isAir(petId)) {
            PET_CACHE.remove(player);
            return;
        }

        // Resolve cosmetic definition
        CosmeticDef petDef = CosmeticsRegistry.get(petId);
        if (petDef == null) return;

        // Look up entity type from cosmetic definition (fallback: wolf)
        String entityTypeStr = petDef.properties().getOrDefault("entity", "minecraft:wolf");
        ResourceLocation entityTypeId = ResourceLocation.tryParse(entityTypeStr);
        if (entityTypeId == null) return;

        EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.get(entityTypeId);
        if (entityType == null) return;

        // Get or create preview entity
        Entity petEntity = getOrCreatePetEntity(player, entityType);
        if (petEntity == null) return;

        // Configure preview entity (invulnerable, silent, etc.)
        configurePetEntity(petEntity);

        // Render tiny orbiting preview around the mannequin
        renderPetSimple(poseStack, bufferSource, packedLight, petEntity, partialTick, ageInTicks);
    }

    private Entity getOrCreatePetEntity(AbstractClientPlayer player, EntityType<?> entityType) {
        Entity cached = PET_CACHE.get(player);

        if (cached == null || !cached.getType().equals(entityType)) {
            // Discard old entity if it exists and type changed
            if (cached != null && !cached.getType().equals(entityType)) {
                cached.discard();
            }
            
            Level level = player.level();
            if (level == null) return null;

            try {
                Entity newPet = entityType.create(level);
                if (newPet != null) {
                    PET_CACHE.put(player, newPet);
                    return newPet;
                }
            } catch (Exception e) {
                PET_CACHE.remove(player);
                return null;
            }
        }
        return cached;
    }

    private void configurePetEntity(Entity petEntity) {
        petEntity.setInvulnerable(true);
        petEntity.setSilent(true);
        petEntity.setNoGravity(true);

        if (petEntity instanceof Wolf wolf) {
            wolf.setTame(true);
            wolf.setOrderedToSit(false);
        } else if (petEntity instanceof Cat cat) {
            cat.setTame(true);
            cat.setOrderedToSit(false);
        } else if (petEntity instanceof Chicken) {
            // No special handling
        }
    }

    private void renderPetSimple(PoseStack poseStack, MultiBufferSource bufferSource,
                                 int packedLight, Entity petEntity, float partialTick, float ageInTicks) {
        poseStack.pushPose();

        // Orbit position (around mannequin head)
        float time = ageInTicks * 0.020f;
        float radius = 0.9f;
        float offsetX = Mth.cos(time) * radius;
        float offsetZ = Mth.sin(time) * radius;
        float offsetY = 0.2f;

        poseStack.translate(offsetX, offsetY, offsetZ);

        // Scale pet down
        float scale = 0.6f;
        poseStack.scale(scale, scale, scale);

        // Rotate to face center
        float yaw = (float) (Math.atan2(offsetZ, offsetX) * (180.0 / Math.PI)) + 90.0f;
        poseStack.mulPose(Axis.XP.rotationDegrees(180.0f));

        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        try {
            var renderer = dispatcher.getRenderer(petEntity);
            if (renderer != null) {
                dispatcher.render(petEntity, 0, 0, 0, yaw, partialTick, poseStack, bufferSource, packedLight);
            }
        } catch (Exception ignored) {}

        poseStack.popPose();
    }

    private static boolean isAir(ResourceLocation id) {
        return id == null || ("minecraft".equals(id.getNamespace()) && "air".equals(id.getPath()));
    }

    public static void cleanupPlayer(AbstractClientPlayer player) {
        PET_CACHE.remove(player);
    }

    public static void cleanupAll() {
        PET_CACHE.clear();
    }
}
