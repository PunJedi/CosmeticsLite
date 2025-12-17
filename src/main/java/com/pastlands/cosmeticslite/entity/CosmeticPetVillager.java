// src/main/java/com/pastlands/cosmeticslite/entity/CosmeticPetVillager.java
package com.pastlands.cosmeticslite.entity;

import com.pastlands.cosmeticslite.PlayerData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
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
 * Scope: TYPE-ONLY styling (Plains/Taiga/Desert/Snow/etc.). No profession/level cosmetics.
 * Reads desired type from PlayerData in this order:
 *   1) styles.pets.extra["variant"]        ← your UI's current write (string key, e.g. "taiga")
 *   2) styles.pets.extra["villager_type"]  ← canonical RL string (e.g. "minecraft:taiga")
 *   3) styles.pets.variant (int)           ← legacy ordinal fallback
 *
 * Persists a tiny lock (ENBT "villager_type") and re-asserts every ~2s.
 */
public class CosmeticPetVillager extends Villager {

    // Must match PetManager ENBT keys
    private static final String ENBT_ROOT  = "coslite";
    private static final String ENBT_OWNER = "owner";

    // Style-lock key (canonical, type-only)
    private static final String LOCK_TYPE  = "villager_type"; // RL string, e.g. "minecraft:taiga"

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
    public boolean hurt(DamageSource src, float amt) { return false; }

    @Override
    public boolean canBeLeashed(Player player) { return false; }

    /** Block trading UI; allow PASS on empty-hand (so other “petting” handlers can see it). */
    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack inHand = player.getItemInHand(hand);
        return inHand.isEmpty() ? InteractionResult.PASS : InteractionResult.FAIL;
    }

    /* ------------------------------------------------------------------ */
    /* Light owner-follow + stable style re-assert                        */
    /* ------------------------------------------------------------------ */
    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide) return;

        // gentle follow / teleport
        Player owner = getOwnerFromENBT();
        if (owner != null) {
            double d = this.distanceTo(owner);
            if (d > 18.0) {
                teleportNear(owner.getX(), owner.getY(), owner.getZ());
            } else if (d > 9.0) {
                this.getNavigation().moveTo(owner, 1.05D);
            } else if (d < 3.0) {
                if (this.tickCount % 40 == 0 && this.getNavigation().isDone()) {
                    double dx = (random.nextDouble() - 0.5) * 1.0;
                    double dz = (random.nextDouble() - 0.5) * 1.0;
                    this.getNavigation().moveTo(this.getX() + dx, this.getY(), this.getZ() + dz, 0.6D);
                }
            }
        }

        // Every ~2s: enforce lock or pull desired type from PlayerData
        if (this.tickCount % 40 == 0) {
            enforceTypeFromLockOrPlayerData(owner);
        }
    }

    private Player getOwnerFromENBT() {
        CompoundTag root = this.getPersistentData().getCompound(ENBT_ROOT);
        if (!root.hasUUID(ENBT_OWNER)) return null;
        UUID id = root.getUUID(ENBT_OWNER);
        return this.level().getPlayerByUUID(id);
    }

    private void teleportNear(double x, double y, double z) {
        this.setPos(x + (random.nextGaussian() * 1.5), y, z + (random.nextGaussian() * 1.5));
        this.getNavigation().stop();
    }

    /* ------------------------------------------------------------------ */
    /* Styling helpers (TYPE ONLY)                                        */
    /* ------------------------------------------------------------------ */

    /** Canonical RL-based TYPE setter. Profession NONE, level 1 for cosmetics. */
    public void setCosmeticTypeByKey(String typeRL) {
        VillagerType t = resolveTypeOrDefault(typeRL);
        VillagerData data = this.getVillagerData()
                .setType(t)
                .setProfession(VillagerProfession.NONE)
                .setLevel(1);
        this.setVillagerData(data);

        // persist tiny lock (type only)
        CompoundTag root = this.getPersistentData().getCompound(ENBT_ROOT);
        root.putString(LOCK_TYPE, keyOf(t));
        this.getPersistentData().put(ENBT_ROOT, root);
    }

    /** Ordinal-based setter kept for back-compat; delegates to RL and TYPE only. */
    public void setCosmeticDataByOrdinal(int typeIdx, int ignoredProfessionIdx, int ignoredLevel) {
        List<VillagerType> types = regSnapshotTypes();
        VillagerType t = types.get(clamp(typeIdx, 0, types.size() - 1));
        setCosmeticTypeByKey(keyOf(t));
    }

    /** Index of the current type within a snapshot of the registry. */
    public int getCosmeticTypeOrdinal() {
        List<VillagerType> types = regSnapshotTypes();
        VillagerType cur = this.getVillagerData().getType();
        for (int i = 0; i < types.size(); i++) if (types.get(i) == cur) return i;
        return 0;
    }

    /* ------------------------------------------------------------------ */
    /* Lock + PlayerData sync                                             */
    /* ------------------------------------------------------------------ */

    private void enforceTypeFromLockOrPlayerData(Player owner) {
        VillagerData cur = this.getVillagerData();

        // 1) If we have a lock, prefer it
        CompoundTag root = this.getPersistentData().getCompound(ENBT_ROOT);
        String lockedRL = root.getString(LOCK_TYPE);
        if (lockedRL != null && !lockedRL.isEmpty()) {
            VillagerType want = resolveTypeOrDefault(lockedRL);
            if (cur.getType() != want || cur.getProfession() != VillagerProfession.NONE || cur.getLevel() != 1) {
                this.setVillagerData(cur.setType(want).setProfession(VillagerProfession.NONE).setLevel(1));
            }
            return;
        }

        // 2) No lock? Ask the owner's PlayerData for desired type
        if (!(owner instanceof ServerPlayer sp)) return;

        PlayerData.get(sp).ifPresent(pd -> {
            CompoundTag extra = pd.getEquippedStyleTag(PlayerData.TYPE_PETS);

            // (a) Preferred: UI's current write → extra["variant"] as villager type key (string)
            String k = extra.getString("variant");
            if (k != null && !k.isEmpty()) {
                setCosmeticTypeByKey(asVillagerTypeKey(k)); // writes lock
                return;
            }

            // (b) Canonical RL key (if present)
            String k2 = extra.getString("villager_type");
            if (k2 != null && !k2.isEmpty()) {
                setCosmeticTypeByKey(k2); // writes lock
                return;
            }

            // (c) Legacy fallback: use 'variant' INT as ordinal
            int ord = pd.getEquippedVariant(PlayerData.TYPE_PETS);
            if (ord >= 0) {
                setCosmeticDataByOrdinal(ord, 0, 1); // writes lock
            }
        });
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

    private static VillagerType resolveTypeOrDefault(String rl) {
        ResourceLocation key = ResourceLocation.tryParse((rl == null || rl.isEmpty()) ? "minecraft:plains" : rl);
        VillagerType t = (key == null) ? null : BuiltInRegistries.VILLAGER_TYPE.get(key);
        return t != null ? t : VillagerType.PLAINS;
    }

    /** Accept bare keys like "taiga" and normalize to "minecraft:taiga". */
    private static String asVillagerTypeKey(String key) {
        if (key == null || key.isEmpty()) return "minecraft:plains";
        ResourceLocation rl = ResourceLocation.tryParse(key);
        if (rl == null || rl.getNamespace().isEmpty()) {
            rl = ResourceLocation.fromNamespaceAndPath("minecraft", key);
        }
        return rl.toString();
    }

    private static String keyOf(VillagerType t) {
        ResourceLocation rl = BuiltInRegistries.VILLAGER_TYPE.getKey(t);
        return rl == null ? "minecraft:plains" : rl.toString();
    }
}
