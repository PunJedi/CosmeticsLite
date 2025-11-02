// src/main/java/com/pastlands/cosmeticslite/entity/PetManager.java
package com.pastlands.cosmeticslite.entity;

import com.mojang.logging.LogUtils;
import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.PlayerData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cosmetic pet lifecycle + safety (Forge 47.4.0 / MC 1.20.1).
 * - No implicit color fallback (never force WHITE).
 * - Random honored on relog and re-equip; always yields a fresh color.
 * - Live GUI picks persist when Random is OFF.
 */
@Mod.EventBusSubscriber(modid = CosmeticsLite.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class PetManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    // Active pets per player
    private static final Map<UUID, Entity> ACTIVE_PETS = new ConcurrentHashMap<>();

    // Post-login warm-up ticks to re-apply styling while PlayerData initializes
    private static final Map<UUID, Integer> LOGIN_WARMUP = new ConcurrentHashMap<>();
    private static final int WARMUP_TICKS = 120;             // ~6 seconds

    // Pet entity persistent-data (on the entity itself)
    private static final String ENBT_ROOT   = "coslite";
    private static final String ENBT_OWNER  = "owner";
    private static final String ENBT_DYE    = "dye";
    private static final String ENBT_VAR    = "variant";
    private static final String ENBT_LAST_INTERACT_TICK = "lastInteractTick";

    // Player persistent-data (server-side)
    private static final String PNBT_ROOT              = "coslite";
    private static final String PNBT_PET_DYE           = "pet_dye";           // last explicit user-selected dye
    private static final String PNBT_PET_VAR           = "pet_variant";       // optional
    private static final String PNBT_PET_RANDOM        = "pet_random";        // Random toggle
    private static final String PNBT_LAST_RANDOM_DYE   = "pet_last_random_dye"; // last randomly chosen dye name

    private static final int INTERACT_COOLDOWN_TICKS = 20 * 3; // 3s
    private static final int PRESENCE_CHECK_PERIOD   = 40;     // 2s

    /* ====================================================================== */
    /* Public API                                                             */
    /* ====================================================================== */

    public static void updatePlayerPet(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel serverLevel)) {
            LOGGER.warn("[PetManager] updatePlayerPet with invalid player or non-server level");
            return;
        }

        Entity current = ACTIVE_PETS.get(player.getUUID());
        ResourceLocation petId = PlayerData.get(player)
                .map(pd -> pd.getEquippedId("pets"))
                .orElse(null);

        if (!shouldSpawnRealPet(petId)) {
            if (current != null && !current.isRemoved()) despawnPet(player.getUUID(), player);
            return;
        }

        if (current == null || current.isRemoved() || !isCorrectPetType(current, petId)) {
            spawnPetForId(player, serverLevel, petId);
        } else {
            applyCosmeticSafety(current);
            applySavedStyle(player, current);
        }
    }

    /** For packets to fetch the currently spawned cosmetic pet. */
    public static Entity getActivePet(ServerPlayer player) {
        return (player == null) ? null : ACTIVE_PETS.get(player.getUUID());
    }

    /* ====================================================================== */
    /* Spawn / Despawn                                                        */
    /* ====================================================================== */

    private static void spawnPetForId(ServerPlayer player, ServerLevel level, ResourceLocation petId) {
        // remove old (capture style first when Random is OFF)
        despawnPet(player.getUUID(), player);

        try {
            Entity pet = createPetEntity(level, petId);
            if (pet == null) {
                LOGGER.error("[PetManager] Unknown pet type {}", petId);
                return;
            }

            // Position
            Vec3 pos = findSafeSpawnPosition(level, player.position());
            pet.setPos(pos.x, pos.y, pos.z);

            // Ownership / baseline health
            if (pet instanceof AbstractHorse ah) {
                ah.setTamed(true);
                ah.setOwnerUUID(player.getUUID());
                ah.setHealth(ah.getMaxHealth());
            } else if (pet instanceof CosmeticPetWolf w)  { w.setOwner(player); w.setHealth(w.getMaxHealth()); }
              else if (pet instanceof CosmeticPetCat c)   { c.setOwner(player); c.setHealth(c.getMaxHealth()); }
              else if (pet instanceof CosmeticPetChicken ch) { ch.setOwner(player); ch.setHealth(ch.getMaxHealth()); }
              else if (pet instanceof CosmeticPetFox fx)  { fx.setOwner(player); fx.setHealth(fx.getMaxHealth()); }
              else if (pet instanceof CosmeticPetAxolotl ax){ ax.setOwner(player); ax.setHealth(ax.getMaxHealth()); }
              else if (pet instanceof CosmeticPetBee b)   { b.setOwner(player); b.setHealth(b.getMaxHealth()); }
              else if (pet instanceof CosmeticPetRabbit r){ r.setOwner(player); r.setHealth(r.getMaxHealth()); }
              else if (pet instanceof CosmeticPetPig pg)  { pg.setOwner(player); pg.setHealth(pg.getMaxHealth()); }
              else if (pet instanceof CosmeticPetSheep s) { s.setOwner(player); s.setHealth(s.getMaxHealth()); }
              else if (pet instanceof CosmeticPetPanda pa) { pa.setOwner(player); pa.setHealth(pa.getMaxHealth()); }
              else if (pet instanceof CosmeticPetParrot pt){ pt.setOwner(player); pt.setHealth(pt.getMaxHealth()); }
              else if (pet instanceof CosmeticPetFrog fr) { fr.setOwner(player); fr.setHealth(fr.getMaxHealth()); }
              else if (pet instanceof CosmeticPetMooshroom ms){ ms.setOwner(player); ms.setHealth(ms.getMaxHealth()); }

            applyCosmeticSafety(pet);
            applySavedStyle(player, pet);

            if (level.addFreshEntity(pet)) {
                ACTIVE_PETS.put(player.getUUID(), pet);
                tagOwner(pet, player);
                LOGGER.info("[PetManager] Spawned {} for {}", petId, player.getGameProfile().getName());
            } else {
                LOGGER.error("[PetManager] Failed to add {} for {}", petId, player.getGameProfile().getName());
            }
        } catch (Exception ex) {
            LOGGER.error("[PetManager] Exception spawning {} for {}", petId, player.getGameProfile().getName(), ex);
        }
    }

    /** Despawn and (if Random is OFF) persist live style to player NBT. */
    public static void despawnPet(UUID playerId, ServerPlayer ownerIfOnline) {
        Entity pet = ACTIVE_PETS.remove(playerId);
        if (pet != null && !pet.isRemoved()) {
            if (ownerIfOnline != null && !isRandomOn(ownerIfOnline)) {
                captureStyleToPlayer(ownerIfOnline, pet); // persist explicit choice
            }
            pet.discard();
        }
    }

    public static void cleanupPlayer(UUID playerId) {
        despawnPet(playerId, null);
    }

    /* ====================================================================== */
    /* Events                                                                  */
    /* ====================================================================== */

    // Helper: read last-known Random flag directly from player PNBT
    private static boolean getRandomFlagPNBT(ServerPlayer p) {
        CompoundTag root = p.getPersistentData().getCompound(PNBT_ROOT);
        return !root.isEmpty() && root.getBoolean(PNBT_PET_RANDOM);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp)) return;

        // If Random was ON last session, scrub any persisted explicit dye on this login
        if (getRandomFlagPNBT(sp)) {
            CompoundTag root = sp.getPersistentData().getCompound(PNBT_ROOT);
            if (!root.isEmpty() && root.contains(PNBT_PET_DYE)) {
                root.remove(PNBT_PET_DYE);
                sp.getPersistentData().put(PNBT_ROOT, root);
            }
        }

        // Start warm-up window so Random wins as PD becomes available
        LOGIN_WARMUP.put(sp.getUUID(), WARMUP_TICKS);

        // Immediate update; next-tick update too
        updatePlayerPet(sp);
        sp.getServer().execute(() -> updatePlayerPet(sp));
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) updatePlayerPet(sp);
    }

    @SubscribeEvent
    public static void onDim(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) updatePlayerPet(sp);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp)) return;
        Entity pet = ACTIVE_PETS.get(sp.getUUID());
        if (pet != null && !isRandomOn(sp)) captureStyleToPlayer(sp, pet); // don't persist when Random ON
        cleanupPlayer(sp.getUUID());
        LOGIN_WARMUP.remove(sp.getUUID());
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (!(e.player instanceof ServerPlayer sp)) return;

        // During warm-up, keep updating every tick to let Random override once PD is ready
        Integer warm = LOGIN_WARMUP.get(sp.getUUID());
        if (warm != null && warm > 0) {
            LOGIN_WARMUP.put(sp.getUUID(), warm - 1);
            updatePlayerPet(sp);
            // Do not return here; allow normal presence check too (harmless)
        } else if (warm != null && warm <= 0) {
            LOGIN_WARMUP.remove(sp.getUUID());
        }

        if (e.phase != TickEvent.Phase.END) return;
        if (sp.tickCount % PRESENCE_CHECK_PERIOD != 0) return;

        ResourceLocation desired = PlayerData.get(sp).map(pd -> pd.getEquippedId("pets")).orElse(null);
        Entity current = ACTIVE_PETS.get(sp.getUUID());

        if (!shouldSpawnRealPet(desired)) {
            if (current != null && !current.isRemoved()) despawnPet(sp.getUUID(), sp);
            return;
        }
        if (current == null || current.isRemoved() || !isCorrectPetType(current, desired)) {
            updatePlayerPet(sp);
            return;
        }
        applyCosmeticSafety(current);
        applySavedStyle(sp, current);
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide()) return;
        Entity ent = e.getEntity();
        if (isCosmeticPet(ent)) applyCosmeticSafety(ent);
    }

    // No damage IN or OUT.
    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent e) {
        if (isCosmeticPet(e.getEntity())) { e.setCanceled(true); return; }
        Entity src = e.getSource().getEntity();
        if (src != null && isCosmeticPet(src)) e.setCanceled(true);
    }
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent e) {
        if (isCosmeticPet(e.getEntity())) { e.setCanceled(true); return; }
        Entity src = e.getSource().getEntity();
        if (src != null && isCosmeticPet(src)) e.setCanceled(true);
    }
    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent e) {
        if (isCosmeticPet(e.getEntity())) e.setCanceled(true);
    }

    // Block item interactions (shears/bucket/etc.) on cosmetic pets; allow empty-hand “petting”.
    @SubscribeEvent
    public static void onRightClickEntity(PlayerInteractEvent.EntityInteract e) {
        if (!(e.getTarget() instanceof LivingEntity target)) return;
        if (!isCosmeticPet(target)) return;

        if (!e.getEntity().getItemInHand(e.getHand()).isEmpty()) {
            if (shouldThrottleInteraction(target)) {
                e.setCancellationResult(InteractionResult.FAIL);
                e.setCanceled(true);
                return;
            }
            e.setCancellationResult(InteractionResult.FAIL);
            e.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        for (Entity ent : ACTIVE_PETS.values()) {
            if (!(ent instanceof LivingEntity le) || le.isRemoved()) continue;

            if (le instanceof Blaze && le.isInWaterOrRain()) {
                le.setHealth(le.getMaxHealth());
                le.clearFire();
            }
            if (ent.tickCount % 40 == 0 && ent instanceof Mob mob) {
                stripCombatGoals(mob);
            }
        }
    }

    /* ====================================================================== */
    /* Style resolution                                                        */
    /* ====================================================================== */

    private static void applySavedStyle(ServerPlayer owner, Entity pet) {
        boolean random = isRandomOn(owner);

if (random) {
    // Scrub any persisted explicit dye so relog/equip can’t revert to a specific color
    CompoundTag proot = getPlayerCoslite(owner, true);
    if (proot.contains(PNBT_PET_DYE)) {
        proot.remove(PNBT_PET_DYE);
    }

    // If the entity already has a random roll locked in ENBT, keep it (don’t re-roll each tick)
    CompoundTag etag = pet.getPersistentData().getCompound("coslite"); // ENBT_ROOT
    if (etag.contains("dye")) {                                       // ENBT_DYE
        proot.putBoolean(PNBT_PET_RANDOM, true);
        owner.getPersistentData().put(PNBT_ROOT, proot);
        return; // keep current random color until next spawn/equip
    }

    // Roll ONCE now (try to differ from last-random and current live)
    DyeColor live = getEntityLiveDye(pet);
    String lastName = proot.getString(PNBT_LAST_RANDOM_DYE);
    DyeColor[] all = DyeColor.values();
    DyeColor pick = all[owner.level().random.nextInt(all.length)];
    for (int i = 0; i < 8; i++) {
        boolean differsFromLast = (lastName == null || lastName.isEmpty())
                || !pick.getName().equalsIgnoreCase(lastName);
        boolean differsFromLive = (live == null) || (pick != live);
        if (differsFromLast && differsFromLive) break;
        pick = all[owner.level().random.nextInt(all.length)];
    }

    // Apply and LOCK this roll on the entity so subsequent calls don’t change it
    applyDyeToPet(pet, pick);
    putEntityDyeNBT(pet, pick);                // writes coslite.dye into entity ENBT

    // Mirror flags (no explicit PNBT dye while Random is ON)
    proot.putString(PNBT_LAST_RANDOM_DYE, pick.getName());
    proot.putBoolean(PNBT_PET_RANDOM, true);
    owner.getPersistentData().put(PNBT_ROOT, proot);
    return;
}


        // Random OFF: prefer PD -> PNBT -> live ; NEVER inject WHITE.
        Optional<DyeColor> pdDye = tryResolveDyeFromPlayerData(owner);
        DyeColor pdChosen = pdDye.orElse(null);

        DyeColor saved = getPlayerDye(owner);
        if (saved == DyeColor.WHITE) saved = null;   // ignore any old WHITE entries
        DyeColor live  = getEntityLiveDye(pet);      // may reflect a just-received packet

        // If PD didn't specify and live differs from PNBT, persist live now — but NEVER persist WHITE
        if (!pdDye.isPresent() && live != null && live != DyeColor.WHITE && (saved == null || saved != live)) {
            putPlayerDye(owner, live);
            saved = live;
        }

        DyeColor chosen = (pdChosen != null) ? pdChosen : (saved != null ? saved : live);
        if (chosen == null) {
            putPlayerRandom(owner, false);
            return;
        }

        applyDyeToPet(pet, chosen);
        putEntityDyeNBT(pet, chosen);

        if (pdDye.isPresent() || saved != null) {
            putPlayerDye(owner, chosen);
        }
        putPlayerRandom(owner, false);

        // Clear last-random since we’re in explicit mode
        CompoundTag root = getPlayerCoslite(owner, true);
        if (root.contains(PNBT_LAST_RANDOM_DYE)) {
            root.remove(PNBT_LAST_RANDOM_DYE);
            owner.getPersistentData().put(PNBT_ROOT, root);
        }
    }

    /** On despawn/log out with Random OFF, capture the live style. */
    private static void captureStyleToPlayer(ServerPlayer owner, Entity pet) {
        DyeColor live = getEntityLiveDye(pet);
        if (live != null && live != DyeColor.WHITE) putPlayerDye(owner, live);
        Integer liveVar = getEntityLiveVariant(pet);
        if (liveVar != null) putPlayerVariant(owner, liveVar);
    }

    /* ------------------------- Random toggle helpers ----------------------- */

    private static boolean isRandomOn(ServerPlayer owner) {
        // Random is ON if *either* PD or PNBT says true
        boolean pd = tryResolveRandomFromPlayerData(owner).orElse(false);
        CompoundTag root = getPlayerCoslite(owner, false);
        boolean pnbt = root != null && root.getBoolean(PNBT_PET_RANDOM);
        return pd || pnbt;
    }

    private static Optional<Boolean> tryResolveRandomFromPlayerData(ServerPlayer owner) {
        return PlayerData.get(owner).flatMap(pd -> {
            // 1) Boolean-style getters that may exist in PlayerData
            for (String name : new String[]{
                    "isPetColorRandom", "isRandomPetColor", "isPetRandom", "isRandom"
            }) {
                try {
                    Method m = pd.getClass().getMethod(name);
                    Object v = m.invoke(pd);
                    if (v instanceof Boolean b) return Optional.of(b);
                } catch (Exception ignored) {}
            }
            // 2) String-style color getter equal to "random"
            for (String name : new String[]{
                    "getPetColor", "getSelectedPetColor", "getPetColorName"
            }) {
                try {
                    Method m = pd.getClass().getMethod(name);
                    Object v = m.invoke(pd);
                    if (v instanceof String s) {
                        if ("random".equalsIgnoreCase(s) || "rnd".equalsIgnoreCase(s)) {
                            return Optional.of(true);
                        }
                    }
                } catch (Exception ignored) {}
            }
            return Optional.empty();
        });
    }

    private static void putPlayerRandom(ServerPlayer p, boolean on) {
        CompoundTag root = getPlayerCoslite(p, true);
        root.putBoolean(PNBT_PET_RANDOM, on);
        p.getPersistentData().put(PNBT_ROOT, root);
    }

    /* ----------------------- dye/variant helpers --------------------------- */

    private static DyeColor getEntityLiveDye(Entity pet) {
        if (pet instanceof Sheep s) return s.getColor();
        if (pet instanceof Wolf  w) return w.getCollarColor();
        return null;
    }
    private static Integer getEntityLiveVariant(Entity pet) {
        try {
            var m = pet.getClass().getMethod("getVariant");
            Object v = m.invoke(pet);
            if (v instanceof Number n) return n.intValue();
        } catch (Exception ignored) {}
        return null;
    }

    private static Optional<DyeColor> tryResolveDyeFromPlayerData(ServerPlayer owner) {
        return PlayerData.get(owner).flatMap(pd -> {
            for (String name : new String[]{"getPetColorDye", "getSelectedPetColor", "getPetColor"}) {
                try {
                    Method m = pd.getClass().getMethod(name);
                    Object v = m.invoke(pd);
                    if (v instanceof DyeColor dye) return Optional.of(dye);
                    if (v instanceof String s && !s.isBlank())
                        return Optional.ofNullable(DyeColor.byName(s.toLowerCase(Locale.ROOT), null));
                } catch (Exception ignored) {}
            }
            return Optional.empty();
        });
    }

    private static void putPlayerDye(ServerPlayer p, DyeColor dye) {
        CompoundTag root = getPlayerCoslite(p, true);
        root.putString(PNBT_PET_DYE, dye.getName());
        p.getPersistentData().put(PNBT_ROOT, root);
    }
    private static DyeColor getPlayerDye(ServerPlayer p) {
        CompoundTag root = getPlayerCoslite(p, false);
        if (root == null || !root.contains(PNBT_PET_DYE)) return null;
        return DyeColor.byName(root.getString(PNBT_PET_DYE), null);
    }

    private static void putPlayerVariant(ServerPlayer p, int variant) {
        CompoundTag root = getPlayerCoslite(p, true);
        root.putInt(PNBT_PET_VAR, variant);
        p.getPersistentData().put(PNBT_ROOT, root);
    }

    private static boolean applyDyeToPet(Entity pet, DyeColor dye) {
        if (pet instanceof Sheep s) { s.setColor(dye); putEntityDyeNBT(pet, dye); return true; }
        if (pet instanceof Wolf  w) { w.setCollarColor(dye); putEntityDyeNBT(pet, dye); return true; }
        return false;
    }

    private static void putEntityDyeNBT(Entity pet, DyeColor dye) {
        CompoundTag tag = pet.getPersistentData().getCompound(ENBT_ROOT);
        tag.putString(ENBT_DYE, dye.getName());
        pet.getPersistentData().put(ENBT_ROOT, tag);
    }

    private static CompoundTag getPlayerCoslite(ServerPlayer p, boolean create) {
        CompoundTag root = p.getPersistentData().getCompound(PNBT_ROOT);
        if (root.isEmpty() && create) {
            root = new CompoundTag();
            p.getPersistentData().put(PNBT_ROOT, root);
        }
        return root.isEmpty() ? (create ? root : null) : root;
    }

    /* ====================================================================== */
    /* Safety / Interaction                                                   */
    /* ====================================================================== */

    private static void applyCosmeticSafety(Entity e) {
        if (e instanceof LivingEntity le) {
            le.setInvulnerable(true);
            le.setRemainingFireTicks(0);
            if (le instanceof Blaze && le.isInWaterOrRain()) {
                le.setHealth(le.getMaxHealth());
                le.clearFire();
            }
        }
        if (e instanceof Mob mob) {
            mob.setPersistenceRequired();
            stripCombatGoals(mob);
        }
    }

    private static void stripCombatGoals(Mob mob) {
        try {
            var goals = new ArrayList<>(mob.goalSelector.getAvailableGoals());
            for (var w : goals) {
                var g = w.getGoal();
                String n = g.getClass().getName();
                if (n.contains("MeleeAttack") || n.contains("Ranged") || n.contains("Shoot")
                        || n.contains("Projectile") || n.contains("LeapAt") || n.contains("Charge")) {
                    mob.goalSelector.removeGoal(g);
                }
            }
            var targets = new ArrayList<>(mob.targetSelector.getAvailableGoals());
            for (var w : targets) {
                var g = w.getGoal();
                String n = g.getClass().getName();
                if (n.contains("NearestAttackableTarget") || n.contains("HurtByTarget") || n.contains("ResetUniversalAnger")) {
                    mob.targetSelector.removeGoal(g);
                }
            }
            if (mob instanceof IronGolem) {
                var goals2 = new ArrayList<>(mob.goalSelector.getAvailableGoals());
                for (var w2 : goals2) {
                    var g = w2.getGoal();
                    String n = g.getClass().getName();
                    if (n.contains("MeleeAttack") || n.contains("MoveToTarget") || n.contains("DefendVillage")) {
                        mob.goalSelector.removeGoal(g);
                    }
                }
                var targets2 = new ArrayList<>(mob.targetSelector.getAvailableGoals());
                for (var w2 : targets2) {
                    var g = w2.getGoal();
                    String n = g.getClass().getName();
                    if (n.contains("NearestAttackableTarget") || n.contains("HurtByTarget") || n.contains("DefendVillage")) {
                        mob.targetSelector.removeGoal(g);
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[PetManager] stripCombatGoals failed on {}: {}", mob.getClass().getSimpleName(), t.toString());
        }
    }

    private static boolean shouldThrottleInteraction(LivingEntity target) {
        CompoundTag tag = target.getPersistentData().getCompound(ENBT_ROOT);
        int last = tag.getInt(ENBT_LAST_INTERACT_TICK);
        int now  = target.tickCount;
        if (now - last < INTERACT_COOLDOWN_TICKS) return true;
        tag.putInt(ENBT_LAST_INTERACT_TICK, now);
        target.getPersistentData().put(ENBT_ROOT, tag);
        return false;
    }

    private static void tagOwner(Entity e, ServerPlayer owner) {
        CompoundTag tag = e.getPersistentData().getCompound(ENBT_ROOT);
        tag.putUUID(ENBT_OWNER, owner.getUUID());
        e.getPersistentData().put(ENBT_ROOT, tag);
    }

    private static boolean isCosmeticPet(Entity e) {
        if (e == null) return false;
        if (ACTIVE_PETS.containsValue(e)) return true;
        CompoundTag tag = e.getPersistentData().getCompound(ENBT_ROOT);
        if (tag.hasUUID(ENBT_OWNER)) return true;
        return e.getClass().getSimpleName().startsWith("CosmeticPet");
    }

    /* ====================================================================== */
    /* Registry / Helpers                                                     */
    /* ====================================================================== */

    private static Entity createPetEntity(ServerLevel level, ResourceLocation petId) {
        String id = petId.toString();

        // Originals
        if ("cosmeticslite:pet_wolf".equals(id))        return new CosmeticPetWolf(PetEntities.COSMETIC_PET_WOLF.get(), level);
        if ("cosmeticslite:pet_cat".equals(id))         return new CosmeticPetCat(PetEntities.COSMETIC_PET_CAT.get(), level);
        if ("cosmeticslite:pet_chicken".equals(id))     return new CosmeticPetChicken(PetEntities.COSMETIC_PET_CHICKEN.get(), level);
        if ("cosmeticslite:pet_fox".equals(id))         return new CosmeticPetFox(PetEntities.COSMETIC_PET_FOX.get(), level);
        if ("cosmeticslite:pet_axolotl".equals(id))     return new CosmeticPetAxolotl(PetEntities.COSMETIC_PET_AXOLOTL.get(), level);
        if ("cosmeticslite:pet_bee".equals(id))         return new CosmeticPetBee(PetEntities.COSMETIC_PET_BEE.get(), level);
        if ("cosmeticslite:pet_rabbit".equals(id))      return new CosmeticPetRabbit(PetEntities.COSMETIC_PET_RABBIT.get(), level);
        if ("cosmeticslite:pet_pig".equals(id))         return new CosmeticPetPig(PetEntities.COSMETIC_PET_PIG.get(), level);
        if ("cosmeticslite:pet_sheep".equals(id))       return new CosmeticPetSheep(PetEntities.COSMETIC_PET_SHEEP.get(), level);
        if ("cosmeticslite:pet_panda".equals(id))       return new CosmeticPetPanda(PetEntities.COSMETIC_PET_PANDA.get(), level);
        if ("cosmeticslite:pet_parrot".equals(id))      return new CosmeticPetParrot(PetEntities.COSMETIC_PET_PARROT.get(), level);

        // New pets
        if ("cosmeticslite:pet_horse".equals(id))       return new CosmeticPetHorse(PetEntities.COSMETIC_PET_HORSE.get(), level);
        if ("cosmeticslite:pet_llama".equals(id))       return new CosmeticPetLlama(PetEntities.COSMETIC_PET_LLAMA.get(), level);
        if ("cosmeticslite:pet_frog".equals(id))        return new CosmeticPetFrog(PetEntities.COSMETIC_PET_FROG.get(), level);
        if ("cosmeticslite:pet_mooshroom".equals(id))   return new CosmeticPetMooshroom(PetEntities.COSMETIC_PET_MOOSHROOM.get(), level);
        if ("cosmeticslite:pet_donkey".equals(id))      return new CosmeticPetDonkey(PetEntities.COSMETIC_PET_DONKEY.get(), level);

        // Extended (future wiring)
        if ("cosmeticslite:pet_mule".equals(id))        return new CosmeticPetMule(PetEntities.COSMETIC_PET_MULE.get(), level);
        if ("cosmeticslite:pet_camel".equals(id))       return new CosmeticPetCamel(PetEntities.COSMETIC_PET_CAMEL.get(), level);
        if ("cosmeticslite:pet_goat".equals(id))        return new CosmeticPetGoat(PetEntities.COSMETIC_PET_GOAT.get(), level);
        if ("cosmeticslite:pet_ocelot".equals(id))      return new CosmeticPetOcelot(PetEntities.COSMETIC_PET_OCELOT.get(), level);
        if ("cosmeticslite:pet_cow".equals(id))         return new CosmeticPetCow(PetEntities.COSMETIC_PET_COW.get(), level);
        if ("cosmeticslite:pet_villager".equals(id))    return new CosmeticPetVillager(PetEntities.COSMETIC_PET_VILLAGER.get(), level);
        if ("cosmeticslite:pet_vex".equals(id))         return new CosmeticPetVex(PetEntities.COSMETIC_PET_VEX.get(), level);
        if ("cosmeticslite:pet_blaze".equals(id))       return new CosmeticPetBlaze(PetEntities.COSMETIC_PET_BLAZE.get(), level);
        if ("cosmeticslite:pet_snow_golem".equals(id))  return new CosmeticPetSnowGolem(PetEntities.COSMETIC_PET_SNOW_GOLEM.get(), level);
        if ("cosmeticslite:pet_iron_golem".equals(id))  return new CosmeticPetIronGolem(PetEntities.COSMETIC_PET_IRON_GOLEM.get(), level);

        return null;
    }

    private static Vec3 findSafeSpawnPosition(ServerLevel level, Vec3 playerPos) {
        for (int r = 0; r < 6; r++) {
            double a  = (r * 57.2958) % 360.0;
            double dx = Mth.cos((float)Math.toRadians(a)) * (1.75 + 0.25 * r);
            double dz = Mth.sin((float)Math.toRadians(a)) * (1.75 + 0.25 * r);
            Vec3 pos  = new Vec3(playerPos.x + dx, playerPos.y, playerPos.z + dz);
            if (isPositionSafe(level, pos)) return pos;
        }
        return playerPos;
    }

    private static boolean isPositionSafe(ServerLevel level, Vec3 pos) {
        BlockPos bp = BlockPos.containing(pos);
        return level.isEmptyBlock(bp) && level.isEmptyBlock(bp.above());
    }

    private static boolean shouldSpawnRealPet(ResourceLocation petId) {
        return petId != null && petId.toString().startsWith(CosmeticsLite.MODID + ":pet_");
    }

    private static boolean isCorrectPetType(Entity currentPet, ResourceLocation desiredPetId) {
        if (currentPet == null || desiredPetId == null) return false;
        String s = desiredPetId.toString();
        return (s.endsWith("wolf")       && currentPet instanceof CosmeticPetWolf)
            || (s.endsWith("cat")        && currentPet instanceof CosmeticPetCat)
            || (s.endsWith("chicken")    && currentPet instanceof CosmeticPetChicken)
            || (s.endsWith("fox")        && currentPet instanceof CosmeticPetFox)
            || (s.endsWith("axolotl")    && currentPet instanceof CosmeticPetAxolotl)
            || (s.endsWith("bee")        && currentPet instanceof CosmeticPetBee)
            || (s.endsWith("rabbit")     && currentPet instanceof CosmeticPetRabbit)
            || (s.endsWith("pig")        && currentPet instanceof CosmeticPetPig)
            || (s.endsWith("sheep")      && currentPet instanceof CosmeticPetSheep)
            || (s.endsWith("panda")      && currentPet instanceof CosmeticPetPanda)
            || (s.endsWith("parrot")     && currentPet instanceof CosmeticPetParrot)
            || (s.endsWith("horse")      && currentPet instanceof CosmeticPetHorse)
            || (s.endsWith("llama")      && currentPet instanceof CosmeticPetLlama)
            || (s.endsWith("frog")       && currentPet instanceof CosmeticPetFrog)
            || (s.endsWith("mooshroom")  && currentPet instanceof CosmeticPetMooshroom)
            || (s.endsWith("donkey")     && currentPet instanceof CosmeticPetDonkey)
            || (s.endsWith("mule")       && currentPet instanceof CosmeticPetMule)
            || (s.endsWith("camel")      && currentPet instanceof CosmeticPetCamel)
            || (s.endsWith("goat")       && currentPet instanceof CosmeticPetGoat)
            || (s.endsWith("ocelot")     && currentPet instanceof CosmeticPetOcelot)
            || (s.endsWith("cow")        && currentPet instanceof CosmeticPetCow)
            || (s.endsWith("villager")   && currentPet instanceof CosmeticPetVillager)
            || (s.endsWith("vex")        && currentPet instanceof CosmeticPetVex)
            || (s.endsWith("blaze")      && currentPet instanceof CosmeticPetBlaze)
            || (s.endsWith("snow_golem") && currentPet instanceof CosmeticPetSnowGolem)
            || (s.endsWith("iron_golem") && currentPet instanceof CosmeticPetIronGolem);
    }
}
