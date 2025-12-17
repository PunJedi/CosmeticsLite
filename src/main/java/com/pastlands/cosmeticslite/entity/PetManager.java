// src/main/java/com/pastlands/cosmeticslite/entity/PetManager.java
package com.pastlands.cosmeticslite.entity;

import com.mojang.logging.LogUtils;
import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.PlayerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.phys.AABB;
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
 * Cosmetic pet lifecycle + safety  (Forge 47.4.0 / MC 1.20.1).
 *
 * Key points:
 *  - Authoritative style push on every spawn/adopt/refresh (from PlayerData, PNBT fallback).
 *  - Clear entity-side locks before applying; mirror applied choices back to ENBT.
 *  - Debounce is BYPASSED when the equipped pet changes (fixes double-click-to-equip).
 *  - Sheep: prefers PlayerData.styles.extra.int("wool") if present; falls back to ARGB → nearest dye.
 *  - Optional login warm-up sweep (dev-safe by default) to clear truly stray vanilla equines near player.
 */
@Mod.EventBusSubscriber(modid = CosmeticsLite.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class PetManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Active cosmetic pet instance per player. */
    private static final Map<UUID, Entity> ACTIVE_PETS = new ConcurrentHashMap<>();

    /** Tracks the last desired pet id string per player to detect equip changes (bypass debounce). */
    private static final Map<UUID, String> LAST_DESIRED = new ConcurrentHashMap<>();

    /** Same-tick spawn guard to stop duplicate spawns from multiple update calls. */
    private static final Set<UUID> SPAWN_GUARD = ConcurrentHashMap.newKeySet();

    /** Warm-up window after login so PlayerData can settle. */
    private static final Map<UUID, Integer> LOGIN_WARMUP = new ConcurrentHashMap<>();
    private static final int WARMUP_TICKS = 120; // ~6s

    /** Debounce after spawn/replace to stop immediate churn. */
    private static final Map<UUID, Integer> DEBOUNCE = new ConcurrentHashMap<>();
    private static final int DEBOUNCE_TICKS = 60; // ~3s

    /** Presence re-check cadence. */
    private static final int PRESENCE_CHECK_PERIOD = 40; // 2s

    /** Interaction cooldown for “petting” w/ empty hand (blocks shears/buckets/etc.). */
    private static final int INTERACT_COOLDOWN_TICKS = 20 * 3; // 3s

private static String normalizeVillagerTypeKey(String key) {
    if (key == null || key.isBlank()) return "minecraft:plains";
    ResourceLocation rl = ResourceLocation.tryParse(key);
    if (rl == null || rl.getNamespace().isEmpty()) {
        rl = ResourceLocation.fromNamespaceAndPath("minecraft", key);
    }
    return rl.toString();
}
	

    // ===== Dev-safe toggles for stray sweep =====
    private static final boolean SWEEP_STRAYS_ENABLED = false; // default OFF (safe for MP)
    private static final boolean SWEEP_STRAYS_DEV_ONLY = true; // only singleplayer unless you flip this

    // Entity persistent data keys
    private static final String ENBT_ROOT = "coslite";
    private static final String ENBT_OWNER = "owner";
    private static final String ENBT_DYE = "dye";       // lock for explicit dye choice
    private static final String ENBT_VAR = "variant";   // lock for explicit variant choice
    private static final String ENBT_LAST_INTERACT_TICK = "lastInteractTick";

    // Player persistent data keys (legacy random toggle + legacy style fallback)
    private static final String PNBT_ROOT        = "coslite";
    private static final String PNBT_PET_RANDOM  = "pet_random";
    private static final String PNBT_PET_DYE     = "pet_dye";     // String (DyeColor#getName)
    private static final String PNBT_PET_VARIANT = "pet_variant"; // String key

    /* ====================================================================== */
    /* Public API                                                             */
    /* ====================================================================== */

    /** Update or (re)spawn the player's cosmetic pet per current selection. */
    public static void updatePlayerPet(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel server)) {
            LOGGER.warn("[PetManager] updatePlayerPet: bad player or non-server level");
            return;
        }

        UUID pid = player.getUUID();

        // Determine desired equipped pet
        ResourceLocation desiredId = PlayerData.get(player)
                .map(pd -> pd.getEquippedId(PlayerData.TYPE_PETS))
                .orElse(null);
        String desiredStr = (desiredId == null) ? "" : desiredId.toString();

        // Detect equip change and record
        String last = LAST_DESIRED.get(pid);
        boolean equipChanged = !Objects.equals(last, desiredStr);
        if (equipChanged) LAST_DESIRED.put(pid, desiredStr);

        // Only block *spawning*. We still allow re-style of an existing/adopted pet.
        Integer db = DEBOUNCE.get(pid);
        boolean spawnBlocked = (db != null && db > 0);

        // BYPASS debounce on an equip change (so first click takes effect)
        if (equipChanged) spawnBlocked = false;

        Entity current = ACTIVE_PETS.get(pid);

        if (!shouldSpawnRealPet(desiredId)) {
            // No pet desired -> remove if present
            if (current != null && !current.isRemoved()) despawnPet(pid, player);
            return;
        }

        // During login warm-up, optionally sweep stray vanilla equines near the player (dev-safe).
        if (SWEEP_STRAYS_ENABLED
                && (player.getServer().isSingleplayer() || !SWEEP_STRAYS_DEV_ONLY)
                && LOGIN_WARMUP.getOrDefault(pid, 0) > 0) {
            cleanupStrayVanillaHorses(server, player);
        }

        // 1) Adopt-if-present: if a correct owned cosmetic entity exists nearby, adopt it
        Entity nearbyOwned = findNearbyOwnedCosmetic(server, player, desiredId);
        if (nearbyOwned != null) {
            ACTIVE_PETS.put(pid, nearbyOwned);
            applyCosmeticSafety(nearbyOwned);
            applySavedStyleFromPlayer(player, nearbyOwned); // authoritative style push
            return; // no spawn churn
        }

        // 2) If we already track one and it's correct, just restyle and keep it
        if (current != null && !current.isRemoved() && isCorrectPetType(current, desiredId)) {
            applyCosmeticSafety(current);
            applySavedStyleFromPlayer(player, current); // authoritative style push
            return;
        }

        // 3) Otherwise, spawn a fresh one (unless debounce blocks spawning this tick)
        if (spawnBlocked) return;

        // Same-tick spawn guard
        if (!SPAWN_GUARD.add(pid)) return;
        try {
            // Set debounce *before* spawn so parallel calls in this tick bail out
            DEBOUNCE.put(pid, DEBOUNCE_TICKS);
            spawnPetForId(player, server, desiredId);
        } finally {
            SPAWN_GUARD.remove(pid);
        }
    }

    /** Expose the currently tracked cosmetic pet (server side). */
    public static Entity getActivePet(ServerPlayer player) {
        return (player == null) ? null : ACTIVE_PETS.get(player.getUUID());
    }

    /** Allow server logic (equip request) to immediately permit a spawn this tick. */
    public static void clearSpawnDebounce(ServerPlayer sp) {
        if (sp != null) {
            DEBOUNCE.put(sp.getUUID(), 0);
        }
    }

    /* ====================================================================== */
    /* Spawn / Despawn                                                        */
    /* ====================================================================== */

    private static void spawnPetForId(ServerPlayer player, ServerLevel level, ResourceLocation petId) {
        // remove old (persist live style if Random OFF)
        despawnPet(player.getUUID(), player);

        try {
            Entity pet = createPetEntity(level, petId);
            if (pet == null) {
                LOGGER.error("[PetManager] Unknown pet type {}", petId);
                return;
            }

            // Place near player
            Vec3 pos = findSafeSpawnPosition(level, player.position());
            pet.setPos(pos.x, pos.y, pos.z);

            // Ownership/baseline
            if (pet instanceof AbstractHorse ah) {
                ah.setTamed(true);
                ah.setOwnerUUID(player.getUUID());
                ah.setHealth(ah.getMaxHealth());
            } else if (pet instanceof CosmeticPetWolf w) { w.setOwner(player); w.setHealth(w.getMaxHealth()); }
            else if (pet instanceof CosmeticPetCat c) { c.setOwner(player); c.setHealth(c.getMaxHealth()); }
            else if (pet instanceof CosmeticPetChicken ch) { ch.setOwner(player); ch.setHealth(ch.getMaxHealth()); }
            else if (pet instanceof CosmeticPetFox fx) { fx.setOwner(player); fx.setHealth(fx.getMaxHealth()); }
            else if (pet instanceof CosmeticPetAxolotl ax) { ax.setOwner(player); ax.setHealth(ax.getMaxHealth()); }
            else if (pet instanceof CosmeticPetBee b) { b.setOwner(player); b.setHealth(b.getMaxHealth()); }
            else if (pet instanceof CosmeticPetRabbit r) { r.setOwner(player); r.setHealth(r.getMaxHealth()); }
            else if (pet instanceof CosmeticPetPig pg) { pg.setOwner(player); pg.setHealth(pg.getMaxHealth()); }
            else if (pet instanceof CosmeticPetSheep s) { s.setOwner(player); s.setHealth(s.getMaxHealth()); }
            else if (pet instanceof CosmeticPetPanda pa) { pa.setOwner(player); pa.setHealth(pa.getMaxHealth()); }
            else if (pet instanceof CosmeticPetParrot pt) { pt.setOwner(player); pt.setHealth(pt.getMaxHealth()); }
            else if (pet instanceof CosmeticPetFrog fr) { fr.setOwner(player); fr.setHealth(fr.getMaxHealth()); }
            else if (pet instanceof CosmeticPetMooshroom ms) { ms.setOwner(player); ms.setHealth(ms.getMaxHealth()); }
            // Villager ownership is tracked via ENBT owner tag below.

            applyCosmeticSafety(pet);
            applySavedStyleFromPlayer(player, pet); // authoritative style push before add

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

    /** Despawn and (if Random is OFF) persist live style to PlayerData. */
    public static void despawnPet(UUID playerId, ServerPlayer ownerIfOnline) {
        Entity pet = ACTIVE_PETS.remove(playerId);
        if (pet != null && !pet.isRemoved()) {
            if (ownerIfOnline != null && !isRandomOn(ownerIfOnline)) {
                captureStyleToPlayer(ownerIfOnline, pet);
            }
            pet.discard();
        }
    }

    public static void cleanupPlayer(UUID playerId) {
        despawnPet(playerId, null);
        LOGIN_WARMUP.remove(playerId);
        DEBOUNCE.remove(playerId);
        LAST_DESIRED.remove(playerId);
    }

    /* ====================================================================== */
    /* Events                                                                 */
    /* ====================================================================== */

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp)) return;

        // Legacy convenience: if Random ON, scrub legacy explicit PNBT dye so we don't fight ourselves
        if (getRandomFlagPNBT(sp)) {
            CompoundTag root = sp.getPersistentData().getCompound(PNBT_ROOT);
            if (!root.isEmpty() && root.contains(PNBT_PET_DYE)) {
                root.remove(PNBT_PET_DYE);
                sp.getPersistentData().put(PNBT_ROOT, root);
            }
        }

        LOGIN_WARMUP.put(sp.getUUID(), WARMUP_TICKS);
        DEBOUNCE.put(sp.getUUID(), 0);

        // Seed last-desired on login to avoid spurious "changed" detection
        ResourceLocation desiredId = PlayerData.get(sp).map(pd -> pd.getEquippedId(PlayerData.TYPE_PETS)).orElse(null);
        LAST_DESIRED.put(sp.getUUID(), desiredId == null ? "" : desiredId.toString());

        // Immediate + next tick
        updatePlayerPet(sp);
        sp.getServer().execute(() -> updatePlayerPet(sp));
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) {
            DEBOUNCE.put(sp.getUUID(), 0);
            updatePlayerPet(sp);
        }
    }

    @SubscribeEvent
    public static void onDim(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) {
            DEBOUNCE.put(sp.getUUID(), 0);
            updatePlayerPet(sp);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp)) return;
        Entity pet = ACTIVE_PETS.get(sp.getUUID());
        if (pet != null && !isRandomOn(sp)) captureStyleToPlayer(sp, pet);
        cleanupPlayer(sp.getUUID());
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (!(e.player instanceof ServerPlayer sp)) return;

        // Warm-up: allow frequent updates so Random (if used) wins once PD is ready
        Integer warm = LOGIN_WARMUP.get(sp.getUUID());
        if (warm != null && warm > 0) {
            LOGIN_WARMUP.put(sp.getUUID(), warm - 1);
            updatePlayerPet(sp);
        } else if (warm != null && warm <= 0) {
            LOGIN_WARMUP.remove(sp.getUUID());
        }

        // Tick down debounce
        Integer db = DEBOUNCE.get(sp.getUUID());
        if (db != null && db > 0) {
            DEBOUNCE.put(sp.getUUID(), db - 1);
        }

        if (e.phase != TickEvent.Phase.END) return;
        if (sp.tickCount % PRESENCE_CHECK_PERIOD != 0) return;

        updatePlayerPet(sp);
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
    /* Style: Authoritative apply from PlayerData (PNBT fallback) + capture   */
    /* ====================================================================== */

    private static void applySavedStyleFromPlayer(ServerPlayer owner, Entity pet) {
        if (owner == null || pet == null) return;

        // 0) Always clear entity locks first (prevents old values resurrecting)
        CompoundTag etag = pet.getPersistentData().getCompound(ENBT_ROOT);
        if (!etag.isEmpty()) {
            if (etag.contains(ENBT_DYE)) etag.remove(ENBT_DYE);
            if (etag.contains(ENBT_VAR)) etag.remove(ENBT_VAR);
            pet.getPersistentData().put(ENBT_ROOT, etag);
        }

        // If Random is ON, do not force explicit values—let your randomizer run elsewhere.
        if (isRandomOn(owner)) {
            return;
        }

        // 1) First preference: PlayerData capability
        var opt = PlayerData.get(owner);
        CompoundTag extra = null;
        int colorARGB = -1;
        int variant = -1;

        if (opt.isPresent()) {
            var pd = opt.get();

            // Extras
            extra = pd.getEquippedStyleTag(PlayerData.TYPE_PETS);
            if (extra != null && !extra.isEmpty()) {
                // Sheep: discrete wool index preferred
                if (pet instanceof Sheep s && extra.contains("wool")) {
                    int idx = Math.max(0, Math.min(15, extra.getInt("wool")));
                    s.setColor(DyeColor.byId(idx));
                    putEntityDyeNBT(pet, DyeColor.byId(idx));
                }

                // Horse/villager extra
                if (pet instanceof net.minecraft.world.entity.animal.horse.Horse) {
                    applyHorseExtra(pet, extra);
                } else if (pet instanceof com.pastlands.cosmeticslite.entity.CosmeticPetVillager v) {
    // --- TYPE ONLY for Villager cosmetics ---
    // Priority: extra["variant"] (string) -> extra["villager_type"] (RL) -> legacy extra["vill"].type
    String typeKey = null;

    // (1) UI string "variant" (normalize to RL)
    if (extra.contains("variant")) {
        typeKey = normalizeVillagerTypeKey(extra.getString("variant"));
        extra.putString("villager_type", typeKey); // canonicalize for persistence
        extra.remove("variant");                   // reduce ambiguity going forward
    }

    // (2) Canonical RL already present
    if ((typeKey == null || typeKey.isEmpty()) && extra.contains("villager_type")) {
        typeKey = normalizeVillagerTypeKey(extra.getString("villager_type"));
        extra.putString("villager_type", typeKey); // ensure normalized form
    }

    // (3) Legacy compound "vill" with string fields
    if ((typeKey == null || typeKey.isEmpty()) && extra.contains("vill")) {
        CompoundTag vill = extra.getCompound("vill");
        if (vill.contains("type")) {
            typeKey = normalizeVillagerTypeKey(vill.getString("type"));
        }
        // We ignore profession/level for cosmetics now.
        // Clean legacy after applying once to avoid future overrides.
        extra.remove("vill");
    }

    if (typeKey != null && !typeKey.isEmpty()) {
        v.setCosmeticTypeByKey(typeKey);
    }
}

            }

            // Color
            colorARGB = pd.getEquippedColor(PlayerData.TYPE_PETS); // -1 if unused
            if (colorARGB >= 0) {
                DyeColor dye = argbToNearestDye(colorARGB);
                if (dye != null) {
                    if (pet instanceof Sheep s) {
                        if (extra == null || !extra.contains("wool")) {
                            s.setColor(dye);
                            putEntityDyeNBT(pet, dye);
                        }
                    } else if (pet instanceof Wolf w) {
                        w.setCollarColor(dye);
                        putEntityDyeNBT(pet, dye);
                    }
                }
            }

            // Variant
            variant = pd.getEquippedVariant(PlayerData.TYPE_PETS); // -1 if unused
            if (variant >= 0) {
                trySetInt(pet, "setVariant", variant);
                putEntityVariantNBT(pet, variant);
            }

            // If PD had any explicit style, we’re done
            if ((extra != null && !extra.isEmpty()) || colorARGB >= 0 || variant >= 0) {
                return;
            }
        }

        // 2) Fallbacks: PNBT legacy keys (explicit choices)
        CompoundTag proot = owner.getPersistentData().getCompound(PNBT_ROOT);
        if (!proot.isEmpty()) {
            boolean applied = false;

            if (proot.contains(PNBT_PET_DYE)) {
                DyeColor dye = tryParseDye(proot.getString(PNBT_PET_DYE));
                if (dye != null) {
                    if (pet instanceof Sheep s) {
                        s.setColor(dye);
                        putEntityDyeNBT(pet, dye);
                        applied = true;
                    } else if (pet instanceof Wolf w) {
                        w.setCollarColor(dye);
                        putEntityDyeNBT(pet, dye);
                        applied = true;
                    }
                }
            }

            if (proot.contains(PNBT_PET_VARIANT)) {
                String key = proot.getString(PNBT_PET_VARIANT);
                if (applyVariantKeyNow(pet, key)) {
                    CompoundTag tag = pet.getPersistentData().getCompound(ENBT_ROOT);
                    if (tag.isEmpty()) tag = new CompoundTag();
                    tag.putString(ENBT_VAR, key);
                    pet.getPersistentData().put(ENBT_ROOT, tag);
                    applied = true;
                }
            }

            if (applied) return;
        }
    }

    /** On despawn/log out with Random OFF, capture the live style into PlayerData. */
    private static void captureStyleToPlayer(ServerPlayer owner, Entity pet) {
        var opt = PlayerData.get(owner);
        if (opt.isEmpty()) return;
        var pd = opt.get();

        // Capture live dye (for dye-aware entities)
        DyeColor liveDye = getEntityLiveDye(pet);
        if (liveDye != null) {
            pd.setEquippedColor(PlayerData.TYPE_PETS, dyeToARGB(liveDye));

            // Sheep: also store discrete wool index
            if (pet instanceof Sheep) {
                CompoundTag extra = pd.getEquippedStyleTag(PlayerData.TYPE_PETS);
                extra.putInt("wool", Math.max(0, Math.min(15, liveDye.getId())));
                pd.setEquippedStyleTag(PlayerData.TYPE_PETS, extra);
            }
        }

        // Capture variant if available
        Integer liveVar = getEntityLiveVariant(pet);
        if (liveVar != null) {
            pd.setEquippedVariant(PlayerData.TYPE_PETS, liveVar);
        }

        // Capture extras
        CompoundTag extra = pd.getEquippedStyleTag(PlayerData.TYPE_PETS);

        // Horse (color/markings)
        if (pet instanceof net.minecraft.world.entity.animal.horse.Horse) {
            CompoundTag h = captureHorseExtra(pet);
            if (!h.isEmpty()) {
                h.getAllKeys().forEach(k -> extra.put(k, h.get(k)));
            }
        }

// Villager (TYPE ONLY)
if (pet instanceof com.pastlands.cosmeticslite.entity.CosmeticPetVillager v) {
    // Capture canonical RL key for villager type
    VillagerType cur = v.getVillagerData().getType();
    ResourceLocation key = BuiltInRegistries.VILLAGER_TYPE.getKey(cur);
    String typeRL = (key == null) ? "minecraft:plains" : key.toString();
    extra.putString("villager_type", typeRL);

    // Clean legacy fields if present
    if (extra.contains("vill")) extra.remove("vill");
    if (extra.contains("variant")) extra.remove("variant");
}


        pd.setEquippedStyleTag(PlayerData.TYPE_PETS, extra);
    }

    /* ------------------------- Random toggle helpers (legacy) -------------- */

    private static boolean isRandomOn(ServerPlayer owner) {
        CompoundTag root = getPlayerCoslite(owner, false);
        return root != null && root.getBoolean(PNBT_PET_RANDOM);
    }

    private static CompoundTag getPlayerCoslite(ServerPlayer p, boolean create) {
        CompoundTag root = p.getPersistentData().getCompound(PNBT_ROOT);
        if (root.isEmpty() && create) {
            root = new CompoundTag();
            p.getPersistentData().put(PNBT_ROOT, root);
        }
        return root.isEmpty() ? (create ? root : null) : root;
    }

    /* -------------------- Variant-string applier (fallback path) ----------- */

    private static boolean applyVariantKeyNow(Entity pet, String key) {
        if (key == null || key.isBlank()) return false;
        String k = key.toLowerCase(java.util.Locale.ROOT);

        // Parrot (custom)
        if (pet instanceof com.pastlands.cosmeticslite.entity.CosmeticPetParrot parrot) {
            parrot.setVariantByKey(k);
            return true;
        }

        // Fox
        if (pet instanceof net.minecraft.world.entity.animal.Fox fox) {
            net.minecraft.world.entity.animal.Fox.Type t =
                    (k.equals("snow") || k.equals("white")) ? net.minecraft.world.entity.animal.Fox.Type.SNOW
                            : (k.equals("red") || k.equals("default")) ? net.minecraft.world.entity.animal.Fox.Type.RED
                            : null;
            if (t != null) { fox.setVariant(t); return true; }
        }

        // Cat (registry)
        if (pet instanceof net.minecraft.world.entity.animal.Cat cat) {
            net.minecraft.resources.ResourceLocation rl = parseRL(k);
            net.minecraft.resources.ResourceKey<net.minecraft.world.entity.animal.CatVariant> rkey =
                    net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.CAT_VARIANT, rl);
            java.util.Optional<net.minecraft.core.Holder.Reference<net.minecraft.world.entity.animal.CatVariant>> h =
                    net.minecraft.core.registries.BuiltInRegistries.CAT_VARIANT.getHolder(rkey);
            if (h.isPresent()) { cat.setVariant(h.get().value()); return true; }
        }

        // Rabbit
        if (pet instanceof net.minecraft.world.entity.animal.Rabbit rabbit) {
            net.minecraft.world.entity.animal.Rabbit.Variant v = switch (k) {
                case "brown" -> net.minecraft.world.entity.animal.Rabbit.Variant.BROWN;
                case "white" -> net.minecraft.world.entity.animal.Rabbit.Variant.WHITE;
                case "black" -> net.minecraft.world.entity.animal.Rabbit.Variant.BLACK;
                case "gold" -> net.minecraft.world.entity.animal.Rabbit.Variant.GOLD;
                case "salt", "salt_and_pepper" -> net.minecraft.world.entity.animal.Rabbit.Variant.SALT;
                default -> null;
            };
            if (v != null) { rabbit.setVariant(v); return true; }
        }

        // Llama
        if (pet instanceof net.minecraft.world.entity.animal.horse.Llama llama) {
            net.minecraft.world.entity.animal.horse.Llama.Variant v = switch (k) {
                case "creamy" -> net.minecraft.world.entity.animal.horse.Llama.Variant.CREAMY;
                case "white"  -> net.minecraft.world.entity.animal.horse.Llama.Variant.WHITE;
                case "brown"  -> net.minecraft.world.entity.animal.horse.Llama.Variant.BROWN;
                case "gray"   -> net.minecraft.world.entity.animal.horse.Llama.Variant.GRAY;
                default -> null;
            };
            if (v != null) { llama.setVariant(v); return true; }
        }

        // Horse (coat color only)
        if (pet instanceof net.minecraft.world.entity.animal.horse.Horse horse) {
            net.minecraft.world.entity.animal.horse.Variant c = switch (k) {
                case "white" -> net.minecraft.world.entity.animal.horse.Variant.WHITE;
                case "creamy" -> net.minecraft.world.entity.animal.horse.Variant.CREAMY;
                case "chestnut" -> net.minecraft.world.entity.animal.horse.Variant.CHESTNUT;
                case "brown" -> net.minecraft.world.entity.animal.horse.Variant.BROWN;
                case "black" -> net.minecraft.world.entity.animal.horse.Variant.BLACK;
                case "gray" -> net.minecraft.world.entity.animal.horse.Variant.GRAY;
                case "dark_brown", "darkbrown", "dark-brown" -> net.minecraft.world.entity.animal.horse.Variant.DARK_BROWN;
                default -> null;
            };
            if (c != null) { horse.setVariant(c); return true; }
        }

        // Frog (registry)
        if (pet instanceof net.minecraft.world.entity.animal.frog.Frog frog) {
            net.minecraft.resources.ResourceLocation rl = parseRL(k);
            net.minecraft.resources.ResourceKey<net.minecraft.world.entity.animal.FrogVariant> rkey =
                    net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.FROG_VARIANT, rl);
            java.util.Optional<net.minecraft.core.Holder.Reference<net.minecraft.world.entity.animal.FrogVariant>> h =
                    net.minecraft.core.registries.BuiltInRegistries.FROG_VARIANT.getHolder(rkey);
            if (h.isPresent()) { frog.setVariant(h.get().value()); return true; }
        }

// Villager (biome type, TYPE ONLY; force NONE/1)
if (pet instanceof net.minecraft.world.entity.npc.Villager villager) {
    net.minecraft.resources.ResourceLocation rl = parseRL(k);
    net.minecraft.resources.ResourceKey<net.minecraft.world.entity.npc.VillagerType> rkey =
            net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.VILLAGER_TYPE, rl);
    java.util.Optional<net.minecraft.core.Holder.Reference<net.minecraft.world.entity.npc.VillagerType>> h =
            net.minecraft.core.registries.BuiltInRegistries.VILLAGER_TYPE.getHolder(rkey);
    if (h.isPresent()) {
        net.minecraft.world.entity.npc.VillagerData cur = villager.getVillagerData();
        villager.setVillagerData(cur
                .setType(h.get().value())
                .setProfession(net.minecraft.world.entity.npc.VillagerProfession.NONE)
                .setLevel(1));
        return true;
    }
}


        // Mooshroom
        if (pet instanceof net.minecraft.world.entity.animal.MushroomCow cow) {
            net.minecraft.world.entity.animal.MushroomCow.MushroomType t = switch (k) {
                case "red" -> net.minecraft.world.entity.animal.MushroomCow.MushroomType.RED;
                case "brown" -> net.minecraft.world.entity.animal.MushroomCow.MushroomType.BROWN;
                default -> null;
            };
            if (t != null) { cow.setVariant(t); return true; }
        }

        return false;
    }

    private static DyeColor tryParseDye(String name) {
        if (name == null || name.isEmpty()) return null;
        for (DyeColor c : DyeColor.values()) if (c.getName().equalsIgnoreCase(name)) return c;
        return null;
    }

    private static ResourceLocation parseRL(String key) {
        ResourceLocation rl = ResourceLocation.tryParse(key);
        if (rl == null || rl.getNamespace().isEmpty()) {
            return ResourceLocation.fromNamespaceAndPath("minecraft", key);
        }
        return rl;
    }

    /* -------------------- Horse style helpers (1.20.1) -------------------- */

    private static void applyHorseExtra(Entity pet, CompoundTag extra) {
        if (!(pet instanceof net.minecraft.world.entity.animal.horse.Horse) || extra == null || extra.isEmpty()) return;

        try {
            Class<?> horseCls = Class.forName("net.minecraft.world.entity.animal.horse.Horse");
            Class<?> variantCls = Class.forName("net.minecraft.world.entity.animal.horse.Horse$Variant");
            Class<?> colorCls   = Class.forName("net.minecraft.world.entity.animal.horse.HorseColor");
            Class<?> marksCls   = Class.forName("net.minecraft.world.entity.animal.horse.HorseMarkings");

            Object[] colors = colorCls.getEnumConstants();
            Object[] marks  = marksCls.getEnumConstants();

            CompoundTag horse = extra.getCompound("horse");
            int ci = horse.contains("color")    ? clamp(horse.getInt("color"), 0, colors.length - 1) : 0;
            int mi = horse.contains("markings") ? clamp(horse.getInt("markings"), 0, marks.length - 1) : 0;

            Object color    = colors[ci];
            Object markings = marks[mi];

            var ctor = variantCls.getDeclaredConstructor(colorCls, marksCls);
            Object variant = ctor.newInstance(color, markings);

            horseCls.getMethod("setVariant", variantCls).invoke(pet, variant);

            // Mirror to ENBT for quick adopt (pack color/markings hint)
            CompoundTag tag = pet.getPersistentData().getCompound(ENBT_ROOT);
            tag.putInt(ENBT_VAR, (ci << 8) | mi);
            pet.getPersistentData().put(ENBT_ROOT, tag);

        } catch (Exception ignored) {}
    }

    private static CompoundTag captureHorseExtra(Entity pet) {
        CompoundTag out = new CompoundTag();
        if (!(pet instanceof net.minecraft.world.entity.animal.horse.Horse)) return out;

        try {
            Class<?> horseCls   = Class.forName("net.minecraft.world.entity.animal.horse.Horse");
            Class<?> variantCls = Class.forName("net.minecraft.world.entity.animal.horse.Horse$Variant");
            Class<?> colorCls   = Class.forName("net.minecraft.world.entity.animal.horse.HorseColor");
            Class<?> marksCls   = Class.forName("net.minecraft.world.entity.animal.horse.HorseMarkings");

            Object variant = horseCls.getMethod("getVariant").invoke(pet);
            if (variant == null) return out;

            Object color    = variantCls.getMethod("color").invoke(variant);
            Object markings = variantCls.getMethod("markings").invoke(variant);

            int cOrd = (int) colorCls.getMethod("ordinal").invoke(color);
            int mOrd = (int) marksCls.getMethod("ordinal").invoke(markings);

            CompoundTag horse = new CompoundTag();
            horse.putInt("color", cOrd);
            horse.putInt("markings", mOrd);
            out.put("horse", horse);
            return out;

        } catch (Exception ignored) {}
        return out;
    }

    /* -------------------- Villager style helpers (1.20.1) -------------------- */

    private static void applyVillagerExtra(Entity pet, CompoundTag extra) {
        if (!(pet instanceof Villager v) || extra == null || !extra.contains("vill")) return;
        CompoundTag vill = extra.getCompound("vill");

        String typeStr = vill.getString("type");
        String profStr = vill.getString("profession");
        int lvl = Mth.clamp(vill.getInt("level"), 1, 5);

        VillagerType type = BuiltInRegistries.VILLAGER_TYPE.get(ResourceLocation.tryParse(
                (typeStr == null || typeStr.isEmpty()) ? "minecraft:plains" : typeStr));
        if (type == null) type = VillagerType.PLAINS;

        VillagerProfession prof = BuiltInRegistries.VILLAGER_PROFESSION.get(ResourceLocation.tryParse(
                (profStr == null || profStr.isEmpty()) ? "minecraft:none" : profStr));
        if (prof == null) prof = VillagerProfession.NONE;

        var data = v.getVillagerData().setType(type).setProfession(prof).setLevel(lvl);
        v.setVillagerData(data);
    }

    private static CompoundTag captureVillagerExtra(Entity pet) {
        CompoundTag out = new CompoundTag();
        if (!(pet instanceof Villager v)) return out;

        var data = v.getVillagerData();
        ResourceLocation typeKey = BuiltInRegistries.VILLAGER_TYPE.getKey(data.getType());
        ResourceLocation profKey = BuiltInRegistries.VILLAGER_PROFESSION.getKey(data.getProfession());

        CompoundTag vill = new CompoundTag();
        vill.putString("type", typeKey == null ? "minecraft:plains" : typeKey.toString());
        vill.putString("profession", profKey == null ? "minecraft:none" : profKey.toString());
        vill.putInt("level", Mth.clamp(data.getLevel(), 1, 5));
        out.put("vill", vill);
        return out;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

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

    private static void putEntityDyeNBT(Entity pet, DyeColor dye) {
        CompoundTag tag = pet.getPersistentData().getCompound(ENBT_ROOT);
        tag.putString(ENBT_DYE, dye.getName());
        pet.getPersistentData().put(ENBT_ROOT, tag);
    }

    private static void putEntityVariantNBT(Entity pet, int variant) {
        CompoundTag tag = pet.getPersistentData().getCompound(ENBT_ROOT);
        tag.putInt(ENBT_VAR, variant);
        pet.getPersistentData().put(ENBT_ROOT, tag);
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

    /* ---------------------------- placement -------------------------------- */

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

    /* ------------------------ adopt-if-present ----------------------------- */

    private static Entity findNearbyOwnedCosmetic(ServerLevel level, ServerPlayer player, ResourceLocation desiredId) {
        double radius = 16.0D;
        AABB box = new AABB(player.getX() - radius, player.getY() - 4, player.getZ() - radius,
                            player.getX() + radius, player.getY() + 4, player.getZ() + radius);
        List<Entity> found = level.getEntities((Entity)null, box, PetManager::isCosmeticPet);
        UUID want = player.getUUID();

        for (Entity e : found) {
            CompoundTag tag = e.getPersistentData().getCompound(ENBT_ROOT);
            if (!tag.hasUUID(ENBT_OWNER)) continue;
            if (!want.equals(tag.getUUID(ENBT_OWNER))) continue;
            if (isCorrectPetType(e, desiredId)) return e;
        }
        return null;
    }

    /* ------------------------------ legacy flags --------------------------- */

    private static boolean getRandomFlagPNBT(ServerPlayer p) {
        CompoundTag root = p.getPersistentData().getCompound(PNBT_ROOT);
        return !root.isEmpty() && root.getBoolean(PNBT_PET_RANDOM);
    }

    /* ====================================================================== */
    /* Small utility: mapping + reflection helpers                            */
    /* ====================================================================== */

    private static void trySetInt(Entity entity, String method, int value) {
        try {
            Method m = entity.getClass().getMethod(method, int.class);
            m.invoke(entity, value);
        } catch (Exception ignored) {}
    }

    private static DyeColor argbToNearestDye(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        DyeColor best = null;
        double bestD = Double.MAX_VALUE;
        for (DyeColor dye : DyeColor.values()) {
            int[] c = dyeRgb(dye);
            double d = sq(r - c[0]) + sq(g - c[1]) + sq(b - c[2]);
            if (d < bestD) { bestD = d; best = dye; }
        }
        return best;
    }

    private static int dyeToARGB(DyeColor dye) {
        int[] c = dyeRgb(dye);
        return (0xFF << 24) | (c[0] << 16) | (c[1] << 8) | c[2];
    }

    private static double sq(double x) { return x * x; }

    private static int[] dyeRgb(DyeColor dye) {
        switch (dye) {
            case WHITE:      return new int[]{240, 240, 240};
            case ORANGE:     return new int[]{235, 136,  68};
            case MAGENTA:    return new int[]{192,  78, 196};
            case LIGHT_BLUE: return new int[]{ 74, 152, 212};
            case YELLOW:     return new int[]{247, 233,  64};
            case LIME:       return new int[]{128, 199,  31};
            case PINK:       return new int[]{237, 141, 172};
            case GRAY:       return new int[]{ 62,  68,  71};
            case LIGHT_GRAY: return new int[]{149, 156, 161};
            case CYAN:       return new int[]{ 22, 156, 156};
            case PURPLE:     return new int[]{122,  42, 174};
            case BLUE:       return new int[]{ 53,  57, 157};
            case BROWN:      return new int[]{100,  59,  33};
            case GREEN:      return new int[]{ 87, 109,  19};
            case RED:        return new int[]{160,  39,  34};
            case BLACK:      return new int[]{ 21,  21,  26};
        }
        return new int[]{255,255,255};
    }

    /* ====================================================================== */
    /* Login warm-up stray vanilla horse cleanup (dev-safe)                   */
    /* ====================================================================== */

    /** Sweep away truly stray vanilla equines (Horse/Donkey/Mule) spawned very near the player. */
    private static void cleanupStrayVanillaHorses(ServerLevel server, ServerPlayer player) {
        final double r = 8.0D; // tight radius around the logging-in player
        AABB box = new AABB(player.getX() - r, player.getY() - 4, player.getZ() - r,
                            player.getX() + r, player.getY() + 4, player.getZ() + r);

        List<Entity> nearby = server.getEntities((Entity) null, box, e ->
                e instanceof net.minecraft.world.entity.animal.horse.AbstractHorse
             && !(e instanceof com.pastlands.cosmeticslite.entity.CosmeticPetHorse)
             && !(e instanceof com.pastlands.cosmeticslite.entity.CosmeticPetDonkey)
             && !(e instanceof com.pastlands.cosmeticslite.entity.CosmeticPetMule)
        );

        for (Entity e : nearby) {
            // never touch anything already tagged as ours
            CompoundTag tag = e.getPersistentData().getCompound(ENBT_ROOT);
            if (tag.hasUUID(ENBT_OWNER)) continue;

            AbstractHorse ah = (AbstractHorse) e;

            // super-conservative: only truly wild and clearly not someone's mount
            boolean safeToRemove =
                    !ah.isTamed() &&
                    !ah.isSaddled() &&
                    ah.getLeashHolder() == null &&
                    !ah.isPersistenceRequired() &&
                    !e.hasCustomName();

            if (safeToRemove) {
                e.discard();
                LOGGER.debug("[PetManager] Cleared stray vanilla {} near {}",
                        e.getClass().getSimpleName(), player.getGameProfile().getName());
            }
        }
    }
}
