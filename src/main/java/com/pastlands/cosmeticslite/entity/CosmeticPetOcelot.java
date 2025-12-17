package com.pastlands.cosmeticslite.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Ocelot;
import net.minecraft.world.level.Level;

/**
 * Cosmetic pet Ocelot.
 * Forge 47.4.0 / MC 1.20.1
 */
public class CosmeticPetOcelot extends Ocelot {

    public CosmeticPetOcelot(EntityType<? extends Ocelot> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
    }

    @Override
    public boolean isBaby() {
        return false;
    }
}
