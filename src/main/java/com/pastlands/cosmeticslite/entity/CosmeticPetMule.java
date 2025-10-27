package com.pastlands.cosmeticslite.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.horse.Mule;
import net.minecraft.world.level.Level;

/**
 * Cosmetic pet Mule.
 * Forge 47.4.0 / MC 1.20.1
 */
public class CosmeticPetMule extends Mule {

    public CosmeticPetMule(EntityType<? extends Mule> type, Level level) {
        super(type, level);
        this.setPersistenceRequired(); // don’t despawn
    }

    @Override
    public boolean isBaby() {
        return false; // force adult cosmetic
    }
}
