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
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Cosmetic Pet Wolf - a tamed wolf that follows its owner but doesn't fight or breed.
 * Designed to be a purely cosmetic companion.
 */
public class CosmeticPetWolf extends Wolf {

    // Data for storing owner UUID
    private static final EntityDataAccessor<String> OWNER_UUID = 
            SynchedEntityData.defineId(CosmeticPetWolf.class, EntityDataSerializers.STRING);

    public CosmeticPetWolf(EntityType<? extends Wolf> entityType, Level level) {
        super(entityType, level);
        this.setTame(true);
        this.setOrderedToSit(false);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(OWNER_UUID, "");
    }

    @Override
    protected void registerGoals() {
        // Clear existing goals to start fresh
        this.goalSelector.removeAllGoals(goal -> true);
        this.targetSelector.removeAllGoals(goal -> true);

        // Add cosmetic pet goals only
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new SitWhenOrderedToGoal(this));
        this.goalSelector.addGoal(3, new FollowOwnerGoal(this, 1.0D, 6.0f, 3.0f, false));
        this.goalSelector.addGoal(4, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 8.0f));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));

        // No target goals - cosmetic pets don't fight
    }

    /**
     * Set the owner of this cosmetic pet
     */
    public void setOwner(@Nullable Player player) {
        if (player != null) {
            this.setTame(true);
            this.setOwnerUUID(player.getUUID());
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
        // Only allow the owner to interact
        if (isOwner(player)) {
            if (!this.level().isClientSide) {
                // Toggle sit/follow
                this.setOrderedToSit(!this.isOrderedToSit());
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
        // Cosmetic pets are invulnerable to damage
        return false;
    }

    @Override
    public boolean canBeLeashed(Player player) {
        // Don't allow leashing cosmetic pets
        return false;
    }

    @Override
    public boolean isFood(net.minecraft.world.item.ItemStack stack) {
        // Cosmetic pets don't eat
        return false;
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

    /**
     * Check if this pet should despawn (when owner is offline or too far away)
     */
    public boolean shouldDespawn() {
        Player owner = getOwner();
        if (owner == null) return true; // Owner offline
        
        if (owner instanceof ServerPlayer serverPlayer) {
            // Despawn if owner is in different dimension
            if (!this.level().dimension().equals(serverPlayer.level().dimension())) {
                return true;
            }
            
            // Despawn if too far from owner (beyond render distance)
            double distance = this.distanceTo(owner);
            return distance > 128.0; // 8 chunks
        }
        
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        
        // Server-side despawn check
        if (!this.level().isClientSide && shouldDespawn()) {
            this.discard();
        }
    }
}