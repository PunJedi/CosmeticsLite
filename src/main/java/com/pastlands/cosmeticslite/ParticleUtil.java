package com.pastlands.cosmeticslite;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;

public class ParticleUtil {

    public static ParticleOptions resolve(String name) {
        return switch (name) {
            case "flame" -> ParticleTypes.FLAME;
            case "heart" -> ParticleTypes.HEART;
            case "smoke" -> ParticleTypes.SMOKE;
            case "happy_villager" -> ParticleTypes.HAPPY_VILLAGER;
            case "cloud" -> ParticleTypes.CLOUD;
            case "note" -> ParticleTypes.NOTE;
            case "witch" -> ParticleTypes.WITCH;
            case "soul" -> ParticleTypes.SOUL;
            default -> null;
        };
    }
}
