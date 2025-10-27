package com.pastlands.cosmeticslite.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.level.Level;

/**
 * Cosmetic pet Camel.
 * Forge 47.4.0 / MC 1.20.1
 */
public class CosmeticPetCamel extends Camel {

    public CosmeticPetCamel(EntityType<? extends Camel> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
    }

    @Override
    public boolean isBaby() {
        return false;
    }
}
