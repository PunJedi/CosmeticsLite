package com.pastlands.cosmeticslite.preview;

import com.pastlands.cosmeticslite.ClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;

/**
 * EquippedParticleSource
 * Single source of truth for the *equipped* particle effect on the client.
 *
 * Usage (later from UI/preview code):
 *   ResourceLocation id = EquippedParticleSource.get();
 *   // id may be null if nothing equipped or "minecraft:air"
 *
 * Forge 47.4.0 • MC 1.20.1 • Java 17
 */
public final class EquippedParticleSource {

    private EquippedParticleSource() {}

    /** Returns the equipped particle for the current local player, or null if none/air. */
    public static ResourceLocation get() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = (mc != null) ? mc.player : null;
        return get(player);
    }

    /** Returns the equipped particle for the given local player, or null if none/air. */
    public static ResourceLocation get(LocalPlayer player) {
        // Prefer the player-specific accessor if you have per-player client cache; fall back to global.
        ResourceLocation eq = (player != null)
                ? ClientState.getEquippedId(player, "particles")
                : ClientState.getEquippedId("particles");

        if (isAir(eq)) return null;
        return eq;
    }

    private static boolean isAir(ResourceLocation id) {
        if (id == null) return true;
        return "minecraft".equals(id.getNamespace()) && "air".equals(id.getPath());
    }
}
