package com.pastlands.cosmeticslite.event;

import com.pastlands.cosmeticslite.CosmeticsLite;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraftforge.event.entity.EntityMobGriefingEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intercepts mob griefing events to prevent cosmetic Snow Golems from placing snow trails.
 * 
 * This uses Forge's EntityMobGriefingEvent to deny griefing for cosmetic pets,
 * which prevents snow trail placement at the source (no flicker, no placement packets).
 * 
 * Key: We're not canceling block placement; we're making the golem believe mobGriefing
 * is false, so the placement never happens in SnowGolem.aiStep().
 * 
 * Forge 47.4.0 / MC 1.20.1
 */
@Mod.EventBusSubscriber(modid = CosmeticsLite.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class PetMobGriefingInterceptor {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ENBT_ROOT = "coslite";
    private static final String ENBT_OWNER = "owner";
    
    // Track which entities we've already logged (once per entity UUID)
    private static final Set<UUID> loggedEntities = ConcurrentHashMap.newKeySet();
    private static final int MAX_LOGGED_ENTITIES = 2048; // Cleanup threshold

    /**
     * Intercept mob griefing events and deny griefing for cosmetic Snow Golems.
     * This prevents snow trail placement by making Forge treat this golem as "mob griefing disabled".
     */
    @SubscribeEvent
    public static void onMobGriefing(EntityMobGriefingEvent event) {
        Entity entity = event.getEntity();
        
        // Only handle on server side (check entity's level)
        if (entity != null && entity.level().isClientSide()) {
            return;
        }
        
        // Only care about Snow Golems
        if (!(entity instanceof SnowGolem)) {
            return;
        }

        // Check if this is a cosmetic pet
        if (!isCosmeticPet(entity)) {
            return;
        }

        // Deny griefing for cosmetic Snow Golems
        // This prevents snow trail placement at the source
        event.setResult(Event.Result.DENY);
        
        // Log once per entity UUID (TRACE level to avoid spam, but available for debugging)
        UUID entityId = entity.getUUID();
        if (!loggedEntities.contains(entityId)) {
            loggedEntities.add(entityId);
            
            // Periodic cleanup: clear set if it grows too large
            if (loggedEntities.size() > MAX_LOGGED_ENTITIES) {
                loggedEntities.clear();
                LOGGER.trace("[PetMobGriefingInterceptor] Cleared logged entities cache (exceeded {} entries)", MAX_LOGGED_ENTITIES);
            }
            
            // Log once per entity at TRACE level (only visible if TRACE logging is enabled)
            CompoundTag tag = entity.getPersistentData().getCompound(ENBT_ROOT);
            if (tag.hasUUID(ENBT_OWNER)) {
                LOGGER.trace("[PetMobGriefingInterceptor] Denied mob griefing for cosmetic snow golem {} owner {}",
                        entityId, tag.getUUID(ENBT_OWNER));
            } else {
                LOGGER.trace("[PetMobGriefingInterceptor] Denied mob griefing for cosmetic snow golem {}", entityId);
            }
        }
    }

    /**
     * Check if an entity is a cosmetic pet using the same logic as PetManager.
     * This checks for the owner tag in persistent NBT.
     */
    private static boolean isCosmeticPet(Entity entity) {
        if (entity == null) {
            return false;
        }

        // Check for owner tag in persistent NBT (same pattern as PetManager uses)
        CompoundTag tag = entity.getPersistentData().getCompound(ENBT_ROOT);
        if (tag.hasUUID(ENBT_OWNER)) {
            return true;
        }

        // Also check class name pattern (fallback - matches PetManager.isCosmeticPet logic)
        return entity.getClass().getSimpleName().startsWith("CosmeticPet");
    }
}

