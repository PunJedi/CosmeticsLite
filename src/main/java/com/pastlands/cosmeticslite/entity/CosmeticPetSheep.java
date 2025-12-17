// src/main/java/com/pastlands/cosmeticslite/entity/CosmeticPetSheep.java
package com.pastlands.cosmeticslite.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.UUID;

/**
 * Cosmetic Pet Sheep - a fluffy companion with colorful wool.
 * - No random color ever
 * - Color persists across relogs via NBT ("CosLiteDye")
 * - Always invulnerable & unshearable
 */
public class CosmeticPetSheep extends Sheep {

    private static final EntityDataAccessor<String> OWNER_UUID =
            SynchedEntityData.defineId(CosmeticPetSheep.class, EntityDataSerializers.STRING);

    // NBT keys (keep OwnerUUID for backward compatibility)
    private static final String NBT_OWNER     = "OwnerUUID";
    private static final String NBT_DYE       = "CosLiteDye";

    // Timer for random friendly bleats
    private int bleatCooldown = 0;

    public CosmeticPetSheep(EntityType<? extends Sheep> entityType, Level level) {
        super(entityType, level);

        // DO NOT RANDOMIZE COLOR — start deterministic then override from NBT/PlayerData
        this.setColor(DyeColor.WHITE);
        this.setSheared(false); // always fluffy
        this.setInvulnerable(true);
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

    /* --------------------------- Ownership --------------------------- */

    public void setOwner(@Nullable Player player) {
        if (player != null) {
            this.entityData.set(OWNER_UUID, player.getUUID().toString());
        }
    }

    @Nullable
    public Player getOwner() {
        String uuidStr = this.entityData.get(OWNER_UUID);
        if (uuidStr == null || uuidStr.isEmpty()) return null;

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

    private boolean isOwner(Player player) {
        String ownerUuidStr = this.entityData.get(OWNER_UUID);
        return ownerUuidStr != null && !ownerUuidStr.isEmpty() && ownerUuidStr.equals(player.getUUID().toString());
    }

    /* --------------------------- Interact --------------------------- */

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

    /* --------------------------- Overrides to keep it cosmetic-only --------------------------- */

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource damageSource, float amount) {
        return false; // invulnerable
    }

    @Override
    public boolean canBeLeashed(Player player) {
        return false;
    }

    @Override
    public boolean isFood(ItemStack stack) {
        return false;
    }

    /* --------------------------- Main tick / behavior --------------------------- */

    @Override
    public void aiStep() {
        // Guard against any base-class surprises
        try {
            super.aiStep();
        } catch (NullPointerException ignored) {}

        // Always fluffy and invulnerable (server & client)
        this.setSheared(false);
        this.setInvulnerable(true);

        // Cosmetic follow-owner behavior (server only)
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

        // Random friendly bleat every ~5–15 seconds (server side)
        if (!this.level().isClientSide) {
            if (bleatCooldown > 0) {
                bleatCooldown--;
            } else {
                if (this.random.nextInt(200) == 0) { // ~1/200 chance each tick when off cooldown
                    this.level().playSound(null, this.blockPosition(),
                            SoundEvents.SHEEP_AMBIENT, SoundSource.NEUTRAL,
                            1.0f, 1.0f);
                    bleatCooldown = 100 + this.random.nextInt(200); // reset ~5–15s
                }
            }
        }
    }

    @Override
    public void tick() {
        super.tick();

        // Keep invulnerable & fluffy
        this.setInvulnerable(true);
        this.setSheared(false);
    }

    /* --------------------------- Save/Load (persist dye & owner) --------------------------- */

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);

        // Owner
        String ownerUuid = this.entityData.get(OWNER_UUID);
        if (ownerUuid != null && !ownerUuid.isEmpty()) {
            compound.putString(NBT_OWNER, ownerUuid);
        }

        // Persist current dye color (authoritative across relogs)
        DyeColor c = this.getColor();
        if (c != null) {
            compound.putString(NBT_DYE, c.getName());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);

        // Owner
        if (compound.contains(NBT_OWNER)) {
            this.entityData.set(OWNER_UUID, compound.getString(NBT_OWNER));
        }

        // Restore saved dye color; if absent, keep current (WHITE) until PetManager applies PlayerData
        if (compound.contains(NBT_DYE)) {
            String name = compound.getString(NBT_DYE);
            if (name != null && !name.isBlank()) {
                DyeColor saved = DyeColor.byName(name.toLowerCase(Locale.ROOT), null);
                if (saved != null) {
                    this.setColor(saved);
                }
            }
        }

        // Never sheared
        this.setSheared(false);
        // And invulnerable
        this.setInvulnerable(true);
    }

    /* --------------------------- Despawn policy --------------------------- */

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
}
