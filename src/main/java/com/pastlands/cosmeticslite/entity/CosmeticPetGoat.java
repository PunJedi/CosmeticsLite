package com.pastlands.cosmeticslite.entity;

import com.pastlands.cosmeticslite.entity.goal.PetFollowOwnerGoal;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.level.Level;

/**
 * Cosmetic pet Goat.
 * Forge 47.4.0 / MC 1.20.1
 */
public class CosmeticPetGoat extends Goat {

    public CosmeticPetGoat(EntityType<? extends Goat> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
    }

    @Override
    public boolean isBaby() {
        return false;
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        // Add follow owner goal at high priority (priority 2) so it wins over wandering
        this.goalSelector.addGoal(2, new PetFollowOwnerGoal(this));
    }
}
