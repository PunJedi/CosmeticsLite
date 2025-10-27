// src/main/java/com/pastlands/cosmeticslite/entity/CosmeticPetSheep.java
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
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Cosmetic Pet Sheep - a fluffy companion with colorful wool.
 * Comes in all 16 dye colors and never needs shearing.
 */
public class CosmeticPetSheep extends Sheep {

    private static final EntityDataAccessor<String> OWNER_UUID =
            SynchedEntityData.defineId(CosmeticPetSheep.class, EntityDataSerializers.STRING);

    // Timer for random friendly bleats
    private int bleatCooldown = 0;

    public CosmeticPetSheep(EntityType<? extends Sheep> entityType, Level level) {
        super(entityType, level);

        // Random wool color
        RandomSource random = level.random;
        DyeColor[] colors = DyeColor.values();
        this.setColor(colors[random.nextInt(colors.length)]);
        this.setSheared(false); // always fluffy
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(OWNER_UUID, "");
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();

        // Cosmetic-only goals
        this.goalSelector.removeAllGoals(goal -> true);
        this.targetSelector.removeAllGoals(goal -> true);

        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0f));
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));
    }

    public void setOwner(@Nullable Player player) {
        if (player != null) {
            this.entityData.set(OWNER_UUID, player.getUUID().toString());
        }
    }

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
                // Friendly baa on interact
                this.playSound(SoundEvents.SHEEP_AMBIENT, 1.0f, 1.0f);
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
        return false; // invulnerable
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
        // Bandaid: swallow any sheep-internal crashes
        try {
            super.aiStep();
        } catch (NullPointerException ignored) {}

        // Cosmetic follow-owner behavior
        Player owner = getOwner();
        if (owner != null && !this.level().isClientSide) {
            double distance = this.distanceTo(owner);

            if (distance > 12.0) {
                this.teleportTo(
                        owner.getX() + (random.nextGaussian() * 2.0),
                        owner.getY(),
                        owner.getZ() + (random.nextGaussian() * 2.0)
                );
            } else if (distance > 6.0) {
                this.getNavigation().moveTo(owner, 1.0);
            }
        }

        // Always fluffy
        this.setSheared(false);

        // Random friendly bleat every ~5–15 seconds
        if (!this.level().isClientSide) {
            if (bleatCooldown > 0) {
                bleatCooldown--;
            } else {
                if (this.random.nextInt(200) == 0) { // 1/200 chance each tick when off cooldown
                    this.level().playSound(null, this.blockPosition(),
                            SoundEvents.SHEEP_AMBIENT, SoundSource.NEUTRAL,
                            1.0f, 1.0f);
                    bleatCooldown = 100 + this.random.nextInt(200); // reset ~5–15s
                }
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
