package com.pastlands.cosmeticslite.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

public class CosmeticPetLlama extends Llama {

    public CosmeticPetLlama(EntityType<? extends Llama> type, Level level) {
        super(type, level);
        this.setPersistenceRequired(); // no-arg
        this.setTamed(true);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.removeAllGoals(g -> true);
        this.targetSelector.removeAllGoals(g -> true);

        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource src, float amt) { return false; }

    @Override
    public boolean canBeLeashed(Player player) { return false; }

    @Override
    public boolean isFood(net.minecraft.world.item.ItemStack stack) { return false; }

    @Override
    public void aiStep() {
        super.aiStep();

        LivingEntity owner = this.getOwner();
        if (owner instanceof Player p && !level().isClientSide) {
            double d = this.distanceTo(p);
            if (d > 15.0) {
                this.teleportTo(p.getX() + (random.nextGaussian() * 2.0),
                                p.getY(),
                                p.getZ() + (random.nextGaussian() * 2.0));
            } else if (d > 8.0) {
                this.getNavigation().moveTo(p, 0.9D);
            }
        }
    }
}
