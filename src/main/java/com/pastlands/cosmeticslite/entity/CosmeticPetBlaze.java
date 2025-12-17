package com.pastlands.cosmeticslite.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.level.Level;

public class CosmeticPetBlaze extends Blaze {
    public CosmeticPetBlaze(EntityType<? extends Blaze> type, Level level) {
        super(type, level);
    }
}
