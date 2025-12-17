package com.pastlands.cosmeticslite.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

public class CosmeticPetFrog extends Frog {

    private static final EntityDataAccessor<String> OWNER_UUID =
            SynchedEntityData.defineId(CosmeticPetFrog.class, EntityDataSerializers.STRING);

    public CosmeticPetFrog(EntityType<? extends Frog> type, Level level) {
        super(type, level);
        this.setPersistenceRequired(); // no-arg
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(OWNER_UUID, "");
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.removeAllGoals(g -> true);
        this.targetSelector.removeAllGoals(g -> true);

        this.goalSelector.addGoal(1, new FloatGoal(this));
        // frogs hop; stroll goal still gives light wandering on land
        this.goalSelector.addGoal(2, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));
    }

    // --- Owner API (PetManager calls setOwner(ServerPlayer), which matches this overload) ---
    public void setOwner(@Nullable Player player) {
        if (player != null) {
            this.entityData.set(OWNER_UUID, player.getUUID().toString());
        }
    }

    @Nullable
    public Player getOwner() {
        String s = this.entityData.get(OWNER_UUID);
        if (s.isEmpty()) return null;
        try {
            UUID id = UUID.fromString(s);
            return this.level().isClientSide
                    ? this.level().getPlayerByUUID(id)
                    : this.level().getServer().getPlayerList().getPlayer(id);
        } catch (Exception e) {
            return null;
        }
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

        Player owner = getOwner();
        if (owner != null && !level().isClientSide) {
            double d = this.distanceTo(owner);
            if (d > 15.0) {
                this.teleportTo(owner.getX() + (random.nextGaussian() * 2.0),
                                owner.getY(),
                                owner.getZ() + (random.nextGaussian() * 2.0));
            } else if (d > 8.0) {
                this.getNavigation().moveTo(owner, 0.85D);
            }
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        String s = this.entityData.get(OWNER_UUID);
        if (!s.isEmpty()) tag.putString("OwnerUUID", s);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("OwnerUUID")) {
            this.entityData.set(OWNER_UUID, tag.getString("OwnerUUID"));
        }
    }
}
