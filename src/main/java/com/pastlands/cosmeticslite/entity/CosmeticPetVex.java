package com.pastlands.cosmeticslite.entity;

import com.pastlands.cosmeticslite.entity.goal.PetFollowOwnerGoal;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.level.Level;

public class CosmeticPetVex extends Vex {
    public CosmeticPetVex(EntityType<? extends Vex> type, Level level) {
        super(type, level);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        // Clear targetSelector goals to prevent targeting from blocking follow movement
        this.targetSelector.removeAllGoals(goal -> true);
        // Add follow owner goal at high priority (priority 2) so it wins over wandering
        this.goalSelector.addGoal(2, new PetFollowOwnerGoal(this));
    }
}
