package com.pastlands.cosmeticslite.entity;

import com.pastlands.cosmeticslite.entity.goal.PetFollowOwnerGoal;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Cosmetic Blaze pet - non-hostile, follows owner.
 * 
 * Safety features:
 * - Overrides registerGoals() to remove all hostile AI
 * - Overrides setTarget() to prevent targeting players
 * - Overrides canAttack() and canAttackType() to return false
 */
public class CosmeticPetBlaze extends Blaze {
    public CosmeticPetBlaze(EntityType<? extends Blaze> type, Level level) {
        super(type, level);
    }
    
    @Override
    protected void registerGoals() {
        // Clear all existing goals (including hostile Blaze AI)
        this.goalSelector.removeAllGoals(goal -> true);
        this.targetSelector.removeAllGoals(goal -> true);
        
        // Add only cosmetic-safe goals
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));
        
        // Add follow owner goal (priority 5, so it runs after basic movement)
        this.goalSelector.addGoal(5, new PetFollowOwnerGoal(this));
    }
    
    /**
     * Prevent setting any target - Blaze should never attack.
     * Even if something tries to re-add targeting goals, this blocks it.
     */
    @Override
    public void setTarget(@Nullable LivingEntity target) {
        // Ignore all target setting attempts
        // This ensures Blaze never targets anyone, even if goals are re-added
    }
    
    /**
     * Prevent attacking any living entity.
     */
    @Override
    public boolean canAttack(LivingEntity target) {
        return false;
    }
    
    /**
     * Prevent attacking any entity type.
     */
    @Override
    public boolean canAttackType(EntityType<?> type) {
        return false;
    }
}
