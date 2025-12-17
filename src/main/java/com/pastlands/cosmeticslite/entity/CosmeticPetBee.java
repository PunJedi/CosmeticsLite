// src/main/java/com/pastlands/cosmeticslite/entity/CosmeticPetBee.java
package com.pastlands.cosmeticslite.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Cosmetic Pet Bee - a friendly bee that buzzes around its owner.
 * Never gets angry and doesn't collect pollen.
 */
public class CosmeticPetBee extends Bee {

    // Data for storing owner UUID
    private static final EntityDataAccessor<String> OWNER_UUID =
            SynchedEntityData.defineId(CosmeticPetBee.class, EntityDataSerializers.STRING);

    public CosmeticPetBee(EntityType<? extends Bee> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(OWNER_UUID, "");
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();

        // Strip out default behavior (pollination, hive, anger, etc.)
        this.goalSelector.removeAllGoals(goal -> true);
        this.targetSelector.removeAllGoals(goal -> true);

        // Cosmetic goals only
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0f));
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));
    }

    /**
     * Set the owner of this cosmetic pet
     */
    public void setOwner(@Nullable Player player) {
        if (player != null) {
            this.entityData.set(OWNER_UUID, player.getUUID().toString());
        }
    }

    /**
     * Get the owner player if they're online and in the same dimension
     */
    @Nullable
    public Player getOwner() {
        String uuidStr = this.entityData.get(OWNER_UUID);
        if (uuidStr.isEmpty()) return null;

        try {
            UUID ownerUUID = UUID.fromString(uuidStr);
            if (this.level().isClientSide) {
                return this.level().getPlayerByUUID(ownerUUID);
            } else {
                return this.level().getServer().getPlayerList().getPlayer(ownerUUID);
            }
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (isOwner(player)) {
            if (!this.level().isClientSide) {
                // Friendly buzz, no real action
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    private boolean isOwner(Player player) {
        String ownerUuidStr = this.entityData.get(OWNER_UUID);
        return !ownerUuidStr.isEmpty() && ownerUuidStr.equals(player.getUUID().toString());
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource damageSource, float amount) {
        // Cosmetic pets are invulnerable
        return false;
    }

    @Override
    public boolean canBeLeashed(Player player) {
        return false;
    }

    @Override
    public boolean isFood(net.minecraft.world.item.ItemStack stack) {
        return false;
    }

    @Override
    public void aiStep() {
        // Wrap super.aiStep() to swallow NPEs from missing beePollinateGoal
        try {
            super.aiStep();
        } catch (NullPointerException ignored) {
            // Pollination code tried to run but pollinateGoal is null — ignore it
        }

        // Cosmetic follow-owner logic
        Player owner = getOwner();
        if (owner != null && !this.level().isClientSide) {
            double distance = this.distanceTo(owner);

            if (distance > 12.0) {
                this.teleportTo(
                        owner.getX() + (random.nextGaussian() * 2.0),
                        owner.getY() + 1.5 + (random.nextGaussian() * 1.0),
                        owner.getZ() + (random.nextGaussian() * 2.0)
                );
            } else if (distance > 6.0) {
                this.getNavigation().moveTo(owner.getX(), owner.getY() + 1.5, owner.getZ(), 1.2);
            }
        }

        // Reset anger
        try {
            this.getPersistentAngerTarget();
            this.stopBeingAngry();
        } catch (Exception ignored) {}
    }

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        String ownerUuid = this.entityData.get(OWNER_UUID);
        if (!ownerUuid.isEmpty()) {
            compound.putString("OwnerUUID", ownerUuid);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        if (compound.contains("OwnerUUID")) {
            this.entityData.set(OWNER_UUID, compound.getString("OwnerUUID"));
        }
    }

    public boolean shouldDespawn() {
        Player owner = getOwner();
        if (owner == null) return true;

        if (owner instanceof ServerPlayer serverPlayer) {
            if (!this.level().dimension().equals(serverPlayer.level().dimension())) {
                return true;
            }

            double distance = this.distanceTo(owner);
            return distance > 128.0;
        }
        return false;
    }

    @Override
    public void tick() {
        super.tick();

        if (!this.level().isClientSide && shouldDespawn()) {
            this.discard();
        }
    }
}
