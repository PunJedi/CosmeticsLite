package com.pastlands.cosmeticslite;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-side cosmetic particle renderer (local player only).
 *
 * - Spawns a light idle particle effect when a "particles" cosmetic is active.
 * - Particle type comes from CosmeticDef.properties().get("effect"), e.g.:
 *       "properties": { "effect": "minecraft:happy_villager" }
 *   Only supports simple (no-arg) particle types here; others fall back.
 *
 * - Honors in-GUI preview: if the Cosmetics screen is open and a particles
 *   cosmetic is SELECTED, we prefer that id locally (no packets).
 *
 * Forge 47.4.0 • MC 1.20.1 • Java 17
 */
@Mod.EventBusSubscriber(modid = CosmeticsLite.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientCosmeticRenderer {

    private ClientCosmeticRenderer() {}

    // Light rate-limit so we don't spam particles.
    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.isSpectator()) return;
        if (!player.level().isClientSide()) return;

        // Gentle cadence — ~3–4 particles per second feels subtle.
        if (++tickCounter % 6 != 0) return;

        // Prefer preview id (GUI mannequin/try-on) -> fallback to equipped
        ResourceLocation id = previewOrEquipped(player);
        if (isAir(id)) return;

        // Resolve cosmetic and particle
        CosmeticDef def = CosmeticsRegistry.get(id);
        String effectId = (def != null)
                ? def.properties().getOrDefault("effect", "minecraft:happy_villager")
                : "minecraft:happy_villager";

        ParticleOptions particle = resolveParticle(effectId);
        if (particle == null) return;

        // Spawn a couple of particles near upper body
        RandomSource r = player.getRandom();
        double baseX = player.getX();
        double baseY = player.getY() + player.getBbHeight() * 0.85;
        double baseZ = player.getZ();

        int count = 2;
        for (int i = 0; i < count; i++) {
            double ox = (r.nextDouble() - 0.5) * 0.6;
            double oy = (r.nextDouble() - 0.5) * 0.3;
            double oz = (r.nextDouble() - 0.5) * 0.6;
            double vx = 0.0;
            double vy = 0.01 + r.nextDouble() * 0.01;
            double vz = 0.0;
            player.level().addParticle(particle, baseX + ox, baseY + oy, baseZ + oz, vx, vy, vz);
        }
    }

    /** Use GUI preview override (if any for the local player) else equipped. */
    private static ResourceLocation previewOrEquipped(LocalPlayer player) {
        // PreviewResolver is no-op outside the cosmetics screen/mannequin;
        // if preview is active for the local player, it returns a non-null id.
        ResourceLocation override = CosmeticsChestScreen.PreviewResolver.getOverride("particles", player);
        return (override != null) ? override : ClientState.getEquippedId(player, "particles");
    }

    /** Accepts a string like "minecraft:happy_villager". Only supports simple (no-arg) particles. */
    private static ParticleOptions resolveParticle(String idOrNull) {
        if (idOrNull == null || idOrNull.isEmpty()) return ParticleTypes.HAPPY_VILLAGER;

        ResourceLocation rl = ResourceLocation.tryParse(idOrNull);
        if (rl == null) return ParticleTypes.HAPPY_VILLAGER;

        var type = BuiltInRegistries.PARTICLE_TYPE.get(rl);
        if (type instanceof SimpleParticleType simple) {
            return simple; // SimpleParticleType implements ParticleOptions
        }
        // Unsupported parameterized particle type -> visible fallback
        return ParticleTypes.HAPPY_VILLAGER;
    }

    private static boolean isAir(ResourceLocation id) {
        return id == null || ("minecraft".equals(id.getNamespace()) && "air".equals(id.getPath()));
    }
}
