package com.pastlands.cosmeticslite.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.animal.Panda;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Cosmetic Pet Panda - a lazy, adorable companion with different personalities.
 * Comes in various panda personalities and loves to sit around.
 */
public class CosmeticPetPanda extends Panda {

    // Data for storing owner UUID
    private static final EntityDataAccessor<String> OWNER_UUID = 
            SynchedEntityData.defineId(CosmeticPetPanda.class, EntityDataSerializers.STRING);

    public CosmeticPetPanda(EntityType<? extends Panda> entityType, Level level) {
        super(entityType, level);
        // Set random panda gene (personality)
        RandomSource random = level.random;
        Panda.Gene[] genes = Panda.Gene.values();
        Panda.Gene randomGene = genes[random.nextInt(genes.length)];
        this.setMainGene(randomGene);
        this.setHiddenGene(randomGene);
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

        // Add cosmetic pet goals only - pandas are naturally lazy
        this.goalSelector.addGoal(1, new FloatGoal(this));
        // Remove sit and follow goals since Panda doesn't extend TamableAnimal
        this.goalSelector.addGoal(2, new WaterAvoidingRandomStrollGoal(this, 0.6D)); // Very slow wandering
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0f));
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));

        // No attack or panic goals - just lazy panda behavior
    }

    /**
     * Set the owner of this cosmetic pet
     */
    public void setOwner(@Nullable Player player) {
        if (player != null) {
            // Pandas don't have taming methods in all versions, just store in our data
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
                // Pandas can't sit in this version, just a friendly interaction
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
        // Cosmetic pets don't eat (even bamboo!)
        return false;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        
        // Custom follow behavior
        Player owner = getOwner();
        if (owner != null && !this.level().isClientSide) {
            double distance = this.distanceTo(owner);
            
            // If too far, teleport closer
            if (distance > 15.0) {
                this.teleportTo(owner.getX() + (random.nextGaussian() * 2.0), 
                               owner.getY(), 
                               owner.getZ() + (random.nextGaussian() * 2.0));
            }
            // If moderately far, try to move closer (slow like a lazy panda)
            else if (distance > 8.0) {
                this.getNavigation().moveTo(owner, 0.8);
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