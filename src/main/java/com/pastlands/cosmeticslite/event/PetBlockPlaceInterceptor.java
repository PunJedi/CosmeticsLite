package com.pastlands.cosmeticslite.event;

import com.pastlands.cosmeticslite.CosmeticsLite;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Intercepts block placement events to prevent cosmetic Snow Golems from placing snow trails.
 * 
 * This prevents flicker by canceling the placement at the source, before the block
 * is actually placed in the world.
 * 
 * Forge 47.4.0 / MC 1.20.1
 */
@Mod.EventBusSubscriber(modid = CosmeticsLite.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class PetBlockPlaceInterceptor {

    private static final String ENBT_ROOT = "coslite";
    private static final String ENBT_OWNER = "owner";

    /**
     * Intercept block placement events and cancel snow placement from cosmetic Snow Golems.
     * 
     * NOTE: This event may not reliably trigger for Snow Golem snow placement.
     * The proper solution is to use EntityMobGriefingEvent (see PetMobGriefingInterceptor).
     * This handler is kept for reference/debugging purposes.
     */
    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        // Only handle on server side
        if (event.getLevel().isClientSide()) {
            return;
        }

        Entity entity = event.getEntity();
        
        // Only care about Snow Golems
        if (!(entity instanceof SnowGolem)) {
            return;
        }

        // Check if this is a cosmetic pet using the same pattern as PetManager
        if (!isCosmeticPet(entity)) {
            return;
        }

        // Cancel snow layer placement from cosmetic Snow Golems
        if (event.getPlacedBlock().is(Blocks.SNOW)) {
            // Debug: This should rarely/never trigger for Snow Golem trails
            // Snow Golem uses mob griefing system, not direct block placement events
            // com.mojang.logging.LogUtils.getLogger().debug("[PetBlockPlaceInterceptor] Blocked snow placement (unexpected - should use mob griefing event)");
            event.setCanceled(true);
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

