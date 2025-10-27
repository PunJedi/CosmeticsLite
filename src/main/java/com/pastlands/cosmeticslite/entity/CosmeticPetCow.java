package com.pastlands.cosmeticslite.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.Level;

/**
 * Cosmetic pet Cow.
 * Forge 47.4.0 / MC 1.20.1
 */
public class CosmeticPetCow extends Cow {

    public CosmeticPetCow(EntityType<? extends Cow> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
    }

    @Override
    public boolean isBaby() {
        return false;
    }
}
