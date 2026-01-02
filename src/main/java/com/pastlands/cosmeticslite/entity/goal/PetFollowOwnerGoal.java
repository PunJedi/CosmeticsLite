package com.pastlands.cosmeticslite.entity.goal;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Reusable follow-owner goal for cosmetic pets that are not tameable.
 * Works with any Mob by reading owner UUID from persistent NBT.
 * 
 * Behavior:
 * - If within ~10 blocks: hover/idle (stopDist)
 * - If 10-40 blocks: navigate toward owner
 * - If >40-60 blocks or can't path: teleport near owner
 */
public class PetFollowOwnerGoal extends Goal {
    private static final String ENBT_ROOT = "coslite";
    private static final String ENBT_OWNER = "owner";
    
    // Distance thresholds
    private static final double START_DIST = 10.0;      // Start following when beyond this
    private static final double STOP_DIST = 4.0;        // Stop moving when within this
    private static final double TELEPORT_DIST = 48.0;   // Teleport when beyond this
    private static final double SPEED = 1.2;            // Movement speed
    
    private final Mob mob;
    private final Level level;
    private Player owner;
    private int pathStuckTicks = 0;
    private static final int MAX_STUCK_TICKS = 40; // ~2 seconds
    
    public PetFollowOwnerGoal(Mob mob) {
        this.mob = mob;
        this.level = mob.level();
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }
    
    @Override
    public boolean canUse() {
        if (level.isClientSide) return false;
        
        owner = getOwnerFromNBT();
        if (owner == null || owner.isRemoved()) {
            return false;
        }
        
        // Only follow if owner is in same dimension
        if (owner.level() != level) {
            return false;
        }
        
        double distance = mob.distanceTo(owner);
        return distance > START_DIST;
    }
    
    @Override
    public boolean canContinueToUse() {
        if (level.isClientSide) return false;
        
        owner = getOwnerFromNBT();
        if (owner == null || owner.isRemoved()) {
            return false;
        }
        
        if (owner.level() != level) {
            return false;
        }
        
        double distance = mob.distanceTo(owner);
        return distance > STOP_DIST;
    }
    
    @Override
    public void start() {
        pathStuckTicks = 0;
    }
    
    @Override
    public void stop() {
        mob.getNavigation().stop();
        owner = null;
        pathStuckTicks = 0;
    }
    
    @Override
    public void tick() {
        if (owner == null || owner.isRemoved()) {
            return;
        }
        
        // Look at owner
        mob.getLookControl().setLookAt(owner, 10.0F, (float) mob.getMaxHeadXRot());
        
        double distance = mob.distanceTo(owner);
        
        // If too far, teleport
        if (distance > TELEPORT_DIST) {
            teleportNearOwner();
            pathStuckTicks = 0;
            return;
        }
        
        // If close enough, stop moving (hover/idle)
        if (distance <= STOP_DIST) {
            mob.getNavigation().stop();
            pathStuckTicks = 0;
            return;
        }
        
        // Try to navigate toward owner
        if (distance > START_DIST) {
            mob.getNavigation().moveTo(owner, SPEED);
            
            // Check if navigation is stuck
            if (!mob.getNavigation().isDone() && mob.getNavigation().getPath() != null) {
                // Navigation is active, reset stuck counter
                pathStuckTicks = 0;
            } else if (distance > START_DIST) {
                // Navigation is done but we're still far - might be stuck
                pathStuckTicks++;
                if (pathStuckTicks >= MAX_STUCK_TICKS) {
                    teleportNearOwner();
                    pathStuckTicks = 0;
                }
            }
        }
    }
    
    private void teleportNearOwner() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (owner == null || owner.isRemoved()) return;
        
        Vec3 ownerPos = owner.position();
        Vec3 safePos = findSafeSpawnPosition(serverLevel, ownerPos);
        
        mob.teleportTo(safePos.x, safePos.y, safePos.z);
        mob.getNavigation().stop();
    }
    
    private Vec3 findSafeSpawnPosition(ServerLevel level, Vec3 playerPos) {
        for (int r = 0; r < 6; r++) {
            double a = (r * 57.2958) % 360.0;
            double dx = Mth.cos((float) Math.toRadians(a)) * (1.75 + 0.25 * r);
            double dz = Mth.sin((float) Math.toRadians(a)) * (1.75 + 0.25 * r);
            Vec3 pos = new Vec3(playerPos.x + dx, playerPos.y, playerPos.z + dz);
            if (isPositionSafe(level, pos)) {
                return pos;
            }
        }
        return playerPos;
    }
    
    private boolean isPositionSafe(ServerLevel level, Vec3 pos) {
        BlockPos bp = BlockPos.containing(pos);
        return level.isEmptyBlock(bp) && level.isEmptyBlock(bp.above());
    }
    
    @Nullable
    private Player getOwnerFromNBT() {
        CompoundTag root = mob.getPersistentData().getCompound(ENBT_ROOT);
        if (!root.hasUUID(ENBT_OWNER)) {
            return null;
        }
        
        UUID ownerId = root.getUUID(ENBT_OWNER);
        if (level.isClientSide) {
            return level.getPlayerByUUID(ownerId);
        } else if (level instanceof ServerLevel serverLevel) {
            return serverLevel.getServer().getPlayerList().getPlayer(ownerId);
        }
        return null;
    }
}

