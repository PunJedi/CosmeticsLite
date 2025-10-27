// src/main/java/com/pastlands/cosmeticslite/entity/CosmeticPetParrot.java
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
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.UUID;

/**
 * Cosmetic Pet Parrot - a colorful flying companion that can perch on shoulders.
 * Comes in all 5 parrot variants and flies around the owner.
 */
public class CosmeticPetParrot extends Parrot {

    // Data for storing owner UUID
    private static final EntityDataAccessor<String> OWNER_UUID =
            SynchedEntityData.defineId(CosmeticPetParrot.class, EntityDataSerializers.STRING);

    public CosmeticPetParrot(EntityType<? extends Parrot> type, Level level) {
        super(type, level);
    }


public void setVariantByKey(String key) {
    int variantId = switch (key.toLowerCase(Locale.ROOT)) {
        case "red"    -> 0; // Red/Blue parrot
        case "blue"   -> 1; // Blue parrot
        case "green"  -> 2; // Green parrot
        case "yellow" -> 3; // Yellow parrot
        case "gray"   -> 4; // Gray parrot
        default       -> this.random.nextInt(5); // fallback
    };
    this.setVariant(Parrot.Variant.byId(variantId));
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

        // Add cosmetic pet goals only - flying behavior
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new SitWhenOrderedToGoal(this));
        this.goalSelector.addGoal(3, new FollowOwnerGoal(this, 1.0D, 5.0f, 1.0f, true));
        this.goalSelector.addGoal(4, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 8.0f));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));
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
                if (this.isOrderedToSit()) {
                    this.setOrderedToSit(false);
                } else {
                    if (this.canSitOnShoulder() && player.getShoulderEntityLeft().isEmpty()) {
                        this.setOrderedToSit(true);
                    } else {
                        this.setOrderedToSit(true);
                    }
                }
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
        return false; // cosmetic pets are invulnerable
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
        super.aiStep();
        Player owner = getOwner();
        if (owner != null && !this.level().isClientSide && !this.isOrderedToSit()) {
            double distance = this.distanceTo(owner);
            if (distance > 15.0) {
                double x = owner.getX() + (random.nextGaussian() * 3.0);
                double y = owner.getY() + 2.0 + (random.nextGaussian() * 1.0);
                double z = owner.getZ() + (random.nextGaussian() * 3.0);
                this.teleportTo(x, y, z);
            } else if (distance > 8.0) {
                this.getNavigation().moveTo(owner.getX(), owner.getY() + 2.0, owner.getZ(), 1.2);
            }
        }
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
            return this.distanceTo(owner) > 128.0;
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
