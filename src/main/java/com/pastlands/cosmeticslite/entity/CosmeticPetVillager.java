// src/main/java/com/pastlands/cosmeticslite/entity/CosmeticPetVillager.java
package com.pastlands.cosmeticslite.entity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Cosmetic (non-trading, non-combat) Villager pet for CosmeticsLite.
 *
 * - Invulnerable, peaceful, light owner-follow.
 * - No trading UI.
 * - Styling via VillagerData using registry order (not enums).
 */
public class CosmeticPetVillager extends Villager {

    // Must match PetManager ENBT keys
    private static final String ENBT_ROOT  = "coslite";
    private static final String ENBT_OWNER = "owner";

    public CosmeticPetVillager(EntityType<? extends Villager> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setInvulnerable(true);
    }

    /* ------------------------------------------------------------------ */
    /* Goals: peaceful idle + wander + look                               */
    /* ------------------------------------------------------------------ */
    @Override
    protected void registerGoals() {
        this.goalSelector.removeAllGoals(g -> true);
        this.targetSelector.removeAllGoals(g -> true);

        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));
    }

    /* ------------------------------------------------------------------ */
    /* Cosmetic: invulnerable & non-interactive                           */
    /* ------------------------------------------------------------------ */
    @Override
    public boolean hurt(DamageSource src, float amt) {
        return false;
    }

    // Villager is not an Animal; no isFood() override here.

    @Override
    public boolean canBeLeashed(Player player) {
        return false;
    }

    /** Block trading UI; allow PASS on empty-hand (so other “petting” handlers can see it). */
    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack inHand = player.getItemInHand(hand);
        return inHand.isEmpty() ? InteractionResult.PASS : InteractionResult.FAIL;
    }

    /* ------------------------------------------------------------------ */
    /* Light owner-follow                                                 */
    /* ------------------------------------------------------------------ */
    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide) return;

        Player owner = getOwnerFromENBT();
        if (owner == null) return;

        double d = this.distanceTo(owner);
        if (d > 18.0) {
            // Far: gentle teleport near owner
            teleportNear(owner.getX(), owner.getY(), owner.getZ());
        } else if (d > 9.0) {
            // Medium: pathfind closer
            this.getNavigation().moveTo(owner, 1.05D);
        } else if (d < 3.0) {
            // Close: tiny drift to avoid crowding
            if (this.tickCount % 40 == 0 && this.getNavigation().isDone()) {
                double dx = (random.nextDouble() - 0.5) * 1.0;
                double dz = (random.nextDouble() - 0.5) * 1.0;
                this.getNavigation().moveTo(this.getX() + dx, this.getY(), this.getZ() + dz, 0.6D);
            }
        }
    }

    private Player getOwnerFromENBT() {
        CompoundTag root = this.getPersistentData().getCompound(ENBT_ROOT);
        if (!root.hasUUID(ENBT_OWNER)) return null;
        UUID id = root.getUUID(ENBT_OWNER);
        // level() already returns a Level; no instanceof pattern needed
        return this.level().getPlayerByUUID(id);
    }

    private void teleportNear(double x, double y, double z) {
        this.setPos(x + (random.nextGaussian() * 1.5), y, z + (random.nextGaussian() * 1.5));
        // Villager ultimately extends PathfinderMob; navigation is available directly
        this.getNavigation().stop();
    }

    /* ------------------------------------------------------------------ */
    /* Styling helpers used by PetManager                                 */
    /* ------------------------------------------------------------------ */

    /** Set villager look by indices into the registries; level clamped to [1..5]. */
    public void setCosmeticDataByOrdinal(int typeIdx, int professionIdx, int level) {
        List<VillagerType> types = regSnapshotTypes();
        List<VillagerProfession> profs = regSnapshotProfs();

        VillagerType t = types.get(clamp(typeIdx, 0, types.size() - 1));
        VillagerProfession p = profs.get(clamp(professionIdx, 0, profs.size() - 1));
        int lvl = clamp(level, 1, 5);

        VillagerData data = this.getVillagerData()
                .setType(t)
                .setProfession(p)
                .setLevel(lvl);
        this.setVillagerData(data);
    }

    /** Index of the current type within the registry iteration order. */
    public int getCosmeticTypeOrdinal() {
        List<VillagerType> types = regSnapshotTypes();
        VillagerType cur = this.getVillagerData().getType();
        for (int i = 0; i < types.size(); i++) {
            if (types.get(i) == cur) return i;
        }
        return 0;
    }

    /** Index of the current profession within the registry iteration order. */
    public int getCosmeticProfessionOrdinal() {
        List<VillagerProfession> profs = regSnapshotProfs();
        VillagerProfession cur = this.getVillagerData().getProfession();
        for (int i = 0; i < profs.size(); i++) {
            if (profs.get(i) == cur) return i;
        }
        return 0;
    }

    /** Current villager cosmetic "level" (1..5). */
    public int getCosmeticLevel() {
        return this.getVillagerData().getLevel();
    }

    /* ------------------------------------------------------------------ */
    /* Small utilities                                                     */
    /* ------------------------------------------------------------------ */
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static List<VillagerType> regSnapshotTypes() {
        List<VillagerType> out = new ArrayList<>();
        BuiltInRegistries.VILLAGER_TYPE.forEach(out::add);
        if (out.isEmpty()) out.add(VillagerType.PLAINS); // safety
        return out;
    }

    private static List<VillagerProfession> regSnapshotProfs() {
        List<VillagerProfession> out = new ArrayList<>();
        BuiltInRegistries.VILLAGER_PROFESSION.forEach(out::add);
        if (out.isEmpty()) out.add(VillagerProfession.NONE); // safety
        return out;
    }
}
