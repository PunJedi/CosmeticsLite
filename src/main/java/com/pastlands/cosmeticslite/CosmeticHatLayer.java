// src/main/java/com/pastlands/cosmeticslite/CosmeticHatLayer.java
package com.pastlands.cosmeticslite;

import com.pastlands.cosmeticslite.client.model.CosmeticsModels;
import com.pastlands.cosmeticslite.client.model.TophatModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.SkullModelBase;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.SkullBlock;

/**
 * Cosmetic hat renderer.
 * Handles:
 *  - Vanilla/odd block items as hats
 *  - Skull blocks with alignment tweaks
 *  - Hardcoded tophat
 *  - Custom JSON hats (baked models under assets/.../models/hats/*.json)
 *
 * Forge 47.4.0 • MC 1.20.1 • Java 17
 */
final class CosmeticHatLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    // Baseline placement for "item-as-hat"
    private static final double Y_UP = -0.25D;
    private static final float BASE_SCALE = 0.72F;

    // Simple container for tweak data
    private static final class SkullTweak {
        final double dx, dy, dz;
        final float scale, yaw;
        SkullTweak(double dx, double dy, double dz, float scale, float yaw) {
            this.dx = dx; this.dy = dy; this.dz = dz;
            this.scale = scale; this.yaw = yaw;
        }
    }

    // Alignment for skull blocks on the head
    private static final Map<SkullBlock.Type, SkullTweak> SKULL_TWEAKS = Map.of(
        SkullBlock.Types.DRAGON,           new SkullTweak(0.345D, -0.23D, 0.43D, 0.70F, 180F),
        SkullBlock.Types.CREEPER,          new SkullTweak(0.54D,  -0.355D, 0.82D, 1.08F, 180F),
        SkullBlock.Types.SKELETON,         new SkullTweak(0.54D,  -0.355D, 0.82D, 1.08F, 180F),
        SkullBlock.Types.WITHER_SKELETON,  new SkullTweak(0.54D,  -0.355D, 0.82D, 1.08F, 180F),
        SkullBlock.Types.ZOMBIE,           new SkullTweak(0.54D,  -0.355D, 0.82D, 1.08F, 180F),
        SkullBlock.Types.PIGLIN,           new SkullTweak(0.54D,  -0.355D, 0.82D, 1.08F, 180F)
    );

    // Tweak a few novelty items
    private static final Map<String, SkullTweak> ITEM_TWEAKS = Map.of(
        "end_crystal",      new SkullTweak(0.0D, -0.35D, 0.0D, 0.75F, 180F),
        "spyglass",         new SkullTweak(0.0D, -0.20D, 0.25D, 0.80F, 90F),
        "flower_pot",       new SkullTweak(0.0D, -0.25D, 0.0D, 1.0F, 180F),
        "dragon_egg",       new SkullTweak(0.0D, -0.40D, 0.0D, 0.85F, 180F),
        "grindstone",       new SkullTweak(0.0D, -0.30D, 0.0D, 0.85F, 180F),
        "ice",              new SkullTweak(0.0D, -0.25D, 0.0D, 1.0F, 180F),
        "cactus",           new SkullTweak(0.0D, -0.35D, 0.0D, 0.90F, 180F),
        "nether_gold_ore",  new SkullTweak(0.0D, -0.35D, 0.0D, 0.90F, 180F)
    );

    CosmeticHatLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    private static boolean isAir(ResourceLocation id) {
        return id == null || ("minecraft".equals(id.getNamespace()) && "air".equals(id.getPath()));
    }

    private static float scaleFudge(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) return 1.0F;
        String p = id.getPath();
        if (p.equals("carved_pumpkin")) return 0.90F;
        if (p.endsWith("anvil")) return 1.15F;
        return 1.0F;
    }

    /** Normalize a model id string to a BakedModel location (strip models/ and .json if present). */
    private static ResourceLocation normalizeModelId(String s) {
        if (s == null || s.isBlank()) return null;
        ResourceLocation raw = ResourceLocation.tryParse(s.trim());
        if (raw == null) return null;
        String ns = raw.getNamespace();
        String path = raw.getPath();
        path = path.replace('\\', '/');
        if (path.startsWith("models/")) path = path.substring("models/".length());
        if (path.endsWith(".json")) path = path.substring(0, path.length() - 5);
        return ResourceLocation.fromNamespaceAndPath(ns, path);
    }

    // ---- helpers to parse per-hat transforms ----
    private static float f(String s, float d) {
        if (s == null) return d;
        try { return Float.parseFloat(s.trim()); } catch (Exception ignored) { return d; }
    }
    private static float[] f3(String x, String y, String z, float dx, float dy, float dz) {
        return new float[] { f(x, dx), f(y, dy), f(z, dz) };
    }

    @Override
    public void render(
        PoseStack pose, MultiBufferSource buf, int light, AbstractClientPlayer player,
        float limbSwing, float limbSwingAmount, float partialTick,
        float ageInTicks, float netHeadYaw, float headPitch) {

        if (player.isInvisible()) return;

        ResourceLocation ov = CosmeticsChestScreen.PreviewResolver.getOverride("hats", player);
        ResourceLocation id = (ov != null) ? ov : ClientState.getEquippedId(player, "hats");
        if (isAir(id)) return;

        CosmeticDef def = CosmeticsRegistry.get(id);
        if (def == null) return;

        // make sure iconItem is visible everywhere
        Item iconItem = (def.icon() != null) ? BuiltInRegistries.ITEM.get(def.icon()) : null;

        pose.pushPose();
        this.getParentModel().head.translateAndRotate(pose);

        // 1) Skull block hats
        if (iconItem instanceof BlockItem bi && bi.getBlock() instanceof SkullBlock skull) {
            SkullBlock.Type type = skull.getType();
            SkullTweak t = SKULL_TWEAKS.get(type);
            if (t != null) {
                pose.scale(1.1875F, -1.1875F, -1.1875F);
                pose.translate(0.0D, 0.0625D, 0.0D);
                pose.translate(t.dx, t.dy, t.dz);
                pose.scale(t.scale, t.scale, t.scale);
                pose.mulPose(Axis.YP.rotationDegrees(t.yaw));
                SkullModelBase skullModel =
                    SkullBlockRenderer.createSkullRenderers(Minecraft.getInstance().getEntityModels()).get(type);
                RenderType rt = SkullBlockRenderer.getRenderType(type, null);
                SkullBlockRenderer.renderSkull(Direction.NORTH, 0.0F, 0.0F, pose, buf, light, skullModel, rt);
                pose.popPose();
                return;
            }
        }

        // 2) Hard-coded tophat model
        if ("cosmeticslite".equals(id.getNamespace()) && "tophat".equals(id.getPath())) {
            pose.translate(0.0D, Y_UP - 0.05D, -0.02D);
            float s = BASE_SCALE;
            pose.scale(s, s, s);
            pose.mulPose(Axis.YP.rotationDegrees(180.0F));
            var model = CosmeticsModels.getTophatModel(Minecraft.getInstance().getEntityModels());
            var bufTop = buf.getBuffer(RenderType.entityCutoutNoCull(TophatModel.TEXTURE));
            model.renderToBuffer(pose, bufTop, light, OverlayTexture.NO_OVERLAY, 1, 1, 1, 1);
            pose.popPose();
            return;
        }

        // 3) Custom JSON hats
        if ("cosmeticslite".equals(id.getNamespace()) && def.properties().containsKey("model")) {
            String modelPath = def.properties().get("model"); // e.g. cosmeticslite:hats/burger_hat
            ResourceLocation baseLoc = ResourceLocation.tryParse(modelPath);

            try {
                if (baseLoc != null) {
                    var mm  = Minecraft.getInstance().getModelManager();
                    var baked = mm.getModel(baseLoc);

                    if (baked != mm.getMissingModel()) {
                        var p = def.properties();

                        float[] t = f3(p.get("hat_tx"), p.get("hat_ty"), p.get("hat_tz"),
                                       0.0f, (float)(Y_UP + 0.02D), 0.0f);

                        float yaw   = f(p.get("hat_yaw"),   180.0f);
                        float pitch = f(p.get("hat_pitch"),   0.0f);
                        float roll  = f(p.get("hat_roll"),    0.0f);

                        float uni = f(p.get("hat_scale"), 1.0f);
                        float sx  = f(p.get("hat_sx"), uni);
                        float sy  = f(p.get("hat_sy"), uni);
                        float sz  = f(p.get("hat_sz"), uni);

                        pose.mulPose(Axis.YP.rotationDegrees(yaw));
                        pose.mulPose(Axis.XP.rotationDegrees(pitch));
                        pose.mulPose(Axis.ZP.rotationDegrees(roll));
                        pose.translate(t[0], t[1], t[2]);
                        pose.scale(sx, sy, sz);

                        pose.scale(0.5f, -0.5f, -0.5f);
                        pose.translate(0.0f, -0.1f, 0.0f);

                        var ir = Minecraft.getInstance().getItemRenderer();
                        var dummy = new ItemStack(Items.STICK);
                        ir.render(dummy, ItemDisplayContext.HEAD, false, pose, buf, light, OverlayTexture.NO_OVERLAY, baked);

                        pose.popPose();
                        return;
                    }
                }
            } catch (Exception ignored) {
                // fall through
            }

            renderFallbackHat(pose, buf, light, player, def);
            pose.popPose();
            return;
        }

        // 4) Default: vanilla/odd items as hats
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        String path = id.getPath().toLowerCase(Locale.ROOT);
        ItemStack stack = (iconItem != null) ? new ItemStack(iconItem) : new ItemStack(Items.DIAMOND);

        if (ITEM_TWEAKS.containsKey(path)) {
            SkullTweak it = ITEM_TWEAKS.get(path);
            pose.translate(it.dx, it.dy, it.dz);
            pose.scale(it.scale, -it.scale, -it.scale);
            pose.mulPose(Axis.YP.rotationDegrees(it.yaw));
        } else {
            pose.translate(0.0D, Y_UP - 0.05D, -0.02015D);
            float s = BASE_SCALE * scaleFudge(stack);
            pose.scale(s, -s, -s);
        }

        Minecraft.getInstance().getItemRenderer().renderStatic(
            stack,
            ItemDisplayContext.HEAD,
            light,
            OverlayTexture.NO_OVERLAY,
            pose,
            buf,
            player.level(),
            0
        );

        pose.popPose();
    }

    private void renderFallbackHat(PoseStack pose, MultiBufferSource buf, int light, AbstractClientPlayer player, CosmeticDef def) {
        Item item = def.icon() != null ? BuiltInRegistries.ITEM.get(def.icon()) : Items.DIAMOND;
        ItemStack stack = new ItemStack(item);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        pose.translate(0.0D, Y_UP - 0.05D, -0.02015D);
        float s = BASE_SCALE * scaleFudge(stack);
        pose.scale(s, -s, -s);
        Minecraft.getInstance().getItemRenderer().renderStatic(
            stack,
            ItemDisplayContext.HEAD,
            light,
            OverlayTexture.NO_OVERLAY,
            pose,
            buf,
            player.level(),
            0
        );
    }
}
