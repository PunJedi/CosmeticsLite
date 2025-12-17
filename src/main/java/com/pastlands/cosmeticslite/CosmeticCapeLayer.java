package com.pastlands.cosmeticslite;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.PlayerModelPart;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Cosmetic cape layer (Forge 1.20.1).
 *
 * - Correct orientation and anchor
 * - Crouch-safe (stays visible)
 * - Gentle motion with smoothing
 * - Preview parity via CosmeticsChestScreen.PreviewResolver
 */
final class CosmeticCapeLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    /** Tiny per-entity smoothing state. */
    private static final class SwingState {
        float angle; // smoothed X rotation in degrees
    }
    private static final WeakHashMap<AbstractClientPlayer, SwingState> SWING = new WeakHashMap<>();

    CosmeticCapeLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    private static boolean isAir(ResourceLocation id) {
        return id == null || ("minecraft".equals(id.getNamespace()) && "air".equals(id.getPath()));
    }

    @Override
    public void render(
            PoseStack pose, MultiBufferSource buffer, int light, AbstractClientPlayer player,
            float limbSwing, float limbSwingAmount, float partialTick,
            float ageInTicks, float netHeadYaw, float headPitch
    ) {
        // Resolve which cape to show: preview override (mannequin) or equipped on the player.
        ResourceLocation ov = CosmeticsChestScreen.PreviewResolver.getOverride("capes", player);
        ResourceLocation id = (ov != null) ? ov : ClientState.getEquippedId(player, "capes");
        if (isAir(id)) return;

        CosmeticDef def = CosmeticsRegistry.get(id);
        if (def == null) return;

        Map<String, String> props = def.properties();
        String texStr = (props == null) ? null : props.get("texture");
        if (texStr == null || texStr.isBlank()) return;

        ResourceLocation tex = ResourceLocation.tryParse(texStr);
        if (tex == null) return;

        // WaveyCapes Integration: Use hybrid rendering for physics-based animation
        // When WaveyCapes is present, inject our cape into the vanilla system so WaveyCapes can animate it
        boolean useWaveyPhysics = WaveyCapesBridge.shouldDelegateToVanilla(player, tex);
        
        if (useWaveyPhysics) {
            try {
                // Inject our cape texture into vanilla layer for WaveyCapes to detect
                // Note: The vanilla cape layer renders before our custom layer, so we inject
                // at the start of the next frame. Restoration happens at end of frame via tick event.
                boolean injected = WaveyCapesBridge.injectCapeToVanillaLayer(player, tex);
                if (injected) {
                    // Skip our custom rendering - WaveyCapes will animate via vanilla layer
                    // Restoration happens at end of frame via WaveyCapesBridge.RestoreHandler
                    return;
                }
                // If injection failed, fall through to static rendering
            } catch (Throwable t) {
                // Safety: Any failure falls back to static rendering
            }
        }

        // Respect the vanilla "cape visible" toggle in-world; always show in preview.
        if (!CosmeticsChestScreen.PreviewResolver.isPreviewEntity(player)
                && !player.isModelPartShown(PlayerModelPart.CAPE)) {
            return;
        }

        pose.pushPose();

        // Vanilla forward nudge so the cape sits behind the body origin.
        pose.translate(0.0D, 0.0D, 0.125D);

        // Compute vanilla-style swing deltas from cloak vs. entity position.
        double dx = Mth.lerp(partialTick, player.xCloakO, player.xCloak) - Mth.lerp(partialTick, player.xo, player.getX());
        double dy = Mth.lerp(partialTick, player.yCloakO, player.yCloak) - Mth.lerp(partialTick, player.yo, player.getY());
        double dz = Mth.lerp(partialTick, player.zCloakO, player.zCloak) - Mth.lerp(partialTick, player.zo, player.getZ());

        float bodyYaw = Mth.lerp(partialTick, player.yBodyRotO, player.yBodyRot);
        float sin = Mth.sin(bodyYaw * ((float)Math.PI / 180.0F));
        float cos = -Mth.cos(bodyYaw * ((float)Math.PI / 180.0F));

        float pitchFromDy = Mth.clamp((float)(dy * 10.0F), -6.0F, 32.0F);
        float swingFB = Mth.clamp((float)(dx * sin + dz * cos) * 100.0F, 0.0F, 150.0F);
        float swingLR = (float)(dx * cos - dz * sin) * 100.0F;

        float sprintLift = player.isSprinting() ? 10.0F : 0.0F;

        // --- Crouch adjustments (tuned to avoid "cut off") ---
        float crouchExtra = player.isCrouching() ? 20.0F : 0.0F; // vanilla is ~25; slightly less reduces clipping
        boolean crouching = player.isCrouching();
        if (crouching) {
            // lower lift and push a hair farther back so the top edge doesn't sink into the model
            pose.translate(0.0D, 0.12D, 0.03D);
        }

        // In preview: freeze motion so the UI mannequin isn't jittery.
        boolean inPreview = CosmeticsChestScreen.PreviewResolver.isPreviewEntity(player);
        if (inPreview) {
            swingFB = 0.0F;
            swingLR = 0.0F;
            pitchFromDy = 0.0F;
            sprintLift = 0.0F;
            crouchExtra = 0.0F;
        }

        // ---- Z-only thickness control (before rotations) ----
        // 1.0F = vanilla; <1 = thinner; >1 = thicker
        final float zScale = 0.70F;
        // Keep the visual gap the same after scaling (compensate the prior Z translates)
        double gapZ = 0.125D + (crouching ? 0.03D : 0.0D);
        pose.translate(0.0D, 0.0D, (gapZ / zScale) - gapZ);
        pose.scale(1.0F, 1.0F, zScale);

        // Smooth the primary (X) angle toward target.
        float targetX = 6.0F + (swingFB / 2.0F) + pitchFromDy + sprintLift + crouchExtra;
        SwingState st = SWING.computeIfAbsent(player, p -> new SwingState());
        float lerp = inPreview ? 1.0F : 0.25F;
        st.angle += (targetX - st.angle) * lerp;

        // Secondary rotations: roll & yaw (snappier for responsiveness).
        float roll = swingLR / 2.0F;
        float yaw  = 180.0F - (swingLR / 2.0F);

        pose.mulPose(Axis.XP.rotationDegrees(st.angle));
        pose.mulPose(Axis.ZP.rotationDegrees(roll));
        pose.mulPose(Axis.YP.rotationDegrees(yaw));

        // Render the cloak part using our texture.
        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(tex));
        this.getParentModel().renderCloak(pose, vc, light, OverlayTexture.NO_OVERLAY);

        pose.popPose();
    }
}
