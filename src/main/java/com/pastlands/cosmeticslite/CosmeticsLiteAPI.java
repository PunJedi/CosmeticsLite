package com.pastlands.cosmeticslite;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * Public API for external mods to query cape textures.
 * Client-side only.
 */
@OnlyIn(Dist.CLIENT)
public final class CosmeticsLiteAPI {

    private CosmeticsLiteAPI() {}

    /**
     * Returns the cape texture ResourceLocation that CosmeticsLite would use for the given player,
     * or null if the player has no cape equipped.
     * 
     * This method resolves capes the same way CosmeticCapeLayer does:
     * - Checks for preview override first (for UI mannequins)
     * - Falls back to the player's equipped cape
     * - Returns null if no cape is equipped or if the cape ID is "air"
     * 
     * @param player The player to query
     * @return The cape texture ResourceLocation, or null if no cape is equipped
     */
    @Nullable
    public static ResourceLocation getCapeTextureForPlayer(AbstractClientPlayer player) {
        // Resolve which cape to show: preview override (mannequin) or equipped on the player.
        ResourceLocation ov = CosmeticsChestScreen.PreviewResolver.getOverride("capes", player);
        ResourceLocation id = (ov != null) ? ov : ClientState.getEquippedId(player, "capes");
        if (isAir(id)) return null;

        CosmeticDef def = CosmeticsRegistry.get(id);
        if (def == null) return null;

        Map<String, String> props = def.properties();
        String texStr = (props == null) ? null : props.get("texture");
        if (texStr == null || texStr.isBlank()) return null;

        ResourceLocation tex = ResourceLocation.tryParse(texStr);
        return tex;
    }

    private static boolean isAir(ResourceLocation id) {
        return id == null || ("minecraft".equals(id.getNamespace()) && "air".equals(id.getPath()));
    }
}
