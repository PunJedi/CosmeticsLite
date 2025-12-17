package com.pastlands.cosmeticslite.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.level.Level;

public class CosmeticPetIronGolem extends IronGolem {
    public CosmeticPetIronGolem(EntityType<? extends IronGolem> type, Level level) {
        super(type, level);
    }
}
