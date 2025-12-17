// src/main/java/com/pastlands/cosmeticslite/entity/CosmeticPetDonkey.java
package com.pastlands.cosmeticslite.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.horse.Donkey;
import net.minecraft.world.level.Level;

/**
 * Cosmetic Donkey pet.
 * Forge 47.4.0 (MC 1.20.1)
 */
public class CosmeticPetDonkey extends Donkey {

    public CosmeticPetDonkey(EntityType<? extends CosmeticPetDonkey> type, Level level) {
        super(type, level);
        this.setPersistenceRequired(); // don’t despawn
    }

    @Override
    public boolean isBaby() {
        return false; // cosmetic pets are always adult
    }

    @Override
    public boolean isSaddled() {
        return false; // no saddle for cosmetic
    }

    @Override
    public boolean canBeLeashed(net.minecraft.world.entity.player.Player player) {
        return false; // cosmetic only
    }

    @Override
    public boolean isFood(net.minecraft.world.item.ItemStack stack) {
        return false; // not feedable
    }

    @Override
    public boolean canWearArmor() {
        return false; // no armor support
    }

    @Override
    public boolean canMate(net.minecraft.world.entity.animal.Animal otherAnimal) {
        return false; // disable breeding
    }
}
