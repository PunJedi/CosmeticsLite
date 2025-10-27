package com.pastlands.cosmeticslite.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;

public class CosmeticPetVillager extends Villager {
    public CosmeticPetVillager(EntityType<? extends Villager> type, Level level) {
        super(type, level);
    }
}
