// src/main/java/com/pastlands/cosmeticslite/entity/PetManager.java
package com.pastlands.cosmeticslite.entity;

import com.mojang.logging.LogUtils;
import com.pastlands.cosmeticslite.PlayerData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages spawning and despawning of real AI pets for players.
 * Forge 47.4.0 (MC 1.20.1)
 */
public class PetManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    // Track active pets per player
    private static final Map<UUID, Entity> ACTIVE_PETS = new HashMap<>();

    /**
     * Check if a player should have an active pet and spawn/despawn accordingly.
     */
    public static void updatePlayerPet(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel serverLevel)) {
            LOGGER.warn("[PetManager] updatePlayerPet called with invalid player or non-server level");
            return;
        }

        UUID playerId = player.getUUID();
        Entity currentPet = ACTIVE_PETS.get(playerId);

        PlayerData.get(player).ifPresentOrElse(data -> {
            ResourceLocation petId = data.getEquippedId("pets");
            LOGGER.debug("[PetManager] updatePlayerPet for {} ({}): equipped pet={}",
                    player.getGameProfile().getName(), playerId, petId);

            if (shouldSpawnRealPet(petId)) {
                if (currentPet == null || currentPet.isRemoved() || !isCorrectPetType(currentPet, petId)) {
                    LOGGER.debug("[PetManager] Spawning new {} for {}", petId, player.getGameProfile().getName());
                    spawnPetForId(player, serverLevel, petId);
                } else {
                    LOGGER.debug("[PetManager] {} already has correct active pet: {}",
                            player.getGameProfile().getName(),
                            currentPet.getClass().getSimpleName());
                }
            } else {
                if (currentPet != null && !currentPet.isRemoved()) {
                    LOGGER.debug("[PetManager] Despawning pet {} for {} (equipped was {})",
                            currentPet.getId(), player.getGameProfile().getName(), petId);
                    despawnPet(playerId);
                } else {
                    LOGGER.debug("[PetManager] No pet equipped and none active for {}", player.getGameProfile().getName());
                }
            }
        }, () -> LOGGER.warn("[PetManager] No PlayerData capability for {}", player.getGameProfile().getName()));
    }

    /**
     * Spawn the appropriate pet based on the pet ID.
     */
    private static void spawnPetForId(ServerPlayer player, ServerLevel level, ResourceLocation petId) {
        LOGGER.debug("[PetManager] spawnPetForId for {} with pet {}", player.getGameProfile().getName(), petId);

        // Remove existing pet first
        despawnPet(player.getUUID());

        try {
            Entity pet = createPetEntity(level, petId);
            if (pet == null) {
                LOGGER.error("[PetManager] Unknown pet type {}", petId);
                return;
            }

            // Position near player
            Vec3 playerPos = player.position();
            Vec3 spawnPos = findSafeSpawnPosition(level, playerPos);
            pet.setPos(spawnPos.x, spawnPos.y, spawnPos.z);
            LOGGER.debug("[PetManager] Positioning {} at {}", petId, spawnPos);

            // Ownership / health wiring
            if (pet instanceof AbstractHorse ah) {
                // Covers Horse, Donkey, Mule, Llama
                ah.setTamed(true);
                ah.setOwnerUUID(player.getUUID());
                ah.setHealth(ah.getMaxHealth());
            } else if (pet instanceof CosmeticPetWolf wolf) {
                wolf.setOwner(player); wolf.setHealth(wolf.getMaxHealth());
            } else if (pet instanceof CosmeticPetCat cat) {
                cat.setOwner(player); cat.setHealth(cat.getMaxHealth());
            } else if (pet instanceof CosmeticPetChicken chicken) {
                chicken.setOwner(player); chicken.setHealth(chicken.getMaxHealth());
            } else if (pet instanceof CosmeticPetFox fox) {
                fox.setOwner(player); fox.setHealth(fox.getMaxHealth());
            } else if (pet instanceof CosmeticPetAxolotl axolotl) {
                axolotl.setOwner(player); axolotl.setHealth(axolotl.getMaxHealth());
            } else if (pet instanceof CosmeticPetBee bee) {
                bee.setOwner(player); bee.setHealth(bee.getMaxHealth());
            } else if (pet instanceof CosmeticPetRabbit rabbit) {
                rabbit.setOwner(player); rabbit.setHealth(rabbit.getMaxHealth());
            } else if (pet instanceof CosmeticPetPig pig) {
                pig.setOwner(player); pig.setHealth(pig.getMaxHealth());
            } else if (pet instanceof CosmeticPetSheep sheep) {
                sheep.setOwner(player); sheep.setHealth(sheep.getMaxHealth());
            } else if (pet instanceof CosmeticPetPanda panda) {
                panda.setOwner(player); panda.setHealth(panda.getMaxHealth());
            } else if (pet instanceof CosmeticPetParrot parrot) {
                parrot.setOwner(player); parrot.setHealth(parrot.getMaxHealth());
            } else if (pet instanceof CosmeticPetFrog frog) {
                frog.setOwner(player); frog.setHealth(frog.getMaxHealth());
            } else if (pet instanceof CosmeticPetMooshroom mush) {
                mush.setOwner(player); mush.setHealth(mush.getMaxHealth());
            }
            // Other pets (Golems, Blaze, etc.) can be extended here if needed.

            boolean added = level.addFreshEntity(pet);
            if (added) {
                ACTIVE_PETS.put(player.getUUID(), pet);
                LOGGER.info("[PetManager] Spawned {} for {}", petId, player.getGameProfile().getName());
            } else {
                LOGGER.error("[PetManager] Failed to add {} to world for {}", petId, player.getGameProfile().getName());
            }
        } catch (Exception e) {
            LOGGER.error("[PetManager] Exception while spawning {} for {}",
                    petId, player.getGameProfile().getName(), e);
        }
    }

    /**
     * Map cosmetic pet IDs to the corresponding custom entity classes.
     */
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
        for (int attempts = 0; attempts < 10; attempts++) {
            double angle = Math.random() * Math.PI * 2;
            double distance = 2.0 + Math.random() * 2.0;
            double x = playerPos.x + Math.cos(angle) * distance;
            double z = playerPos.z + Math.sin(angle) * distance;
            double y = playerPos.y;

            for (int yOffset = -3; yOffset <= 3; yOffset++) {
                Vec3 testPos = new Vec3(x, y + yOffset, z);
                if (isPositionSafe(level, testPos)) {
                    return testPos;
                }
            }
        }
        LOGGER.warn("[PetManager] Could not find safe spawn position, defaulting to player position");
        return playerPos;
    }

    private static boolean isPositionSafe(ServerLevel level, Vec3 pos) {
        var blockPos = new net.minecraft.core.BlockPos((int) pos.x, (int) pos.y, (int) pos.z);
        if (!level.getBlockState(blockPos.below()).isSolidRender(level, blockPos.below())) return false;
        if (!level.getBlockState(blockPos).isAir()) return false;
        if (!level.getBlockState(blockPos.above()).isAir()) return false;
        return true;
    }

    public static void despawnPet(UUID playerId) {
        Entity pet = ACTIVE_PETS.remove(playerId);
        if (pet != null && !pet.isRemoved()) {
            LOGGER.debug("[PetManager] Despawning {} for {}", pet.getClass().getSimpleName(), playerId);
            pet.discard();
        }
    }

    public static void despawnAllPets() {
        LOGGER.info("[PetManager] Despawning all pets ({} active)", ACTIVE_PETS.size());
        for (Entity pet : ACTIVE_PETS.values()) {
            if (pet != null && !pet.isRemoved()) {
                pet.discard();
            }
        }
        ACTIVE_PETS.clear();
    }

    public static void cleanupPlayer(UUID playerId) {
        LOGGER.debug("[PetManager] Cleaning up pets for {}", playerId);
        despawnPet(playerId);
    }

    private static boolean shouldSpawnRealPet(ResourceLocation petId) {
        if (petId == null) return false;
        String s = petId.toString();
        return s.startsWith("cosmeticslite:pet_");
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

    public static Entity getActivePet(ServerPlayer player) {
        return ACTIVE_PETS.get(player.getUUID());
    }
}
