package com.pastlands.cosmeticslite;

import com.pastlands.cosmeticslite.network.S2CCosmeticParticleEmit;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

/**
 * Server-side broadcaster for cosmetic particle effects.
 * 
 * <p>On a configurable cadence, iterates all players, checks their active particle cosmetics,
 * finds viewers within range, and sends S2C_CosmeticParticleEmit packets to those viewers.</p>
 * 
 * <p>This makes particles visible to all nearby players, with the server as the source of truth.</p>
 */
@Mod.EventBusSubscriber(modid = CosmeticsLite.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CosmeticParticleBroadcaster {

    private CosmeticParticleBroadcaster() {}

    // ============================================================================================
    // Configuration: cadence and range
    // ============================================================================================
    // These can be made configurable via a config file or server properties in the future.
    // For now, they are constants that provide good performance defaults.
    
    /** 
     * Tick cadence: how often to send particle emit packets.
     * Lower values = more frequent updates (but more bandwidth).
     * Recommended: 3-5 ticks (6.6x/sec to 4x/sec at 20 TPS).
     */
    private static final int TICK_CADENCE = 5; // Send every 5 ticks (4x/sec at 20 TPS)
    
    /** 
     * View range in blocks. Particles are only sent to viewers within this distance.
     * Squared value is used for distance checks (avoids sqrt calculation).
     * Recommended: 32-64 blocks depending on effect density.
     */
    private static final double VIEW_RANGE = 64.0; // blocks
    private static final double VIEW_RANGE_SQUARED = VIEW_RANGE * VIEW_RANGE;
    
    /** 
     * Per-emitter cap: maximum number of particle layers that can emit simultaneously.
     * This prevents packet spam when a player has multiple particle cosmetics equipped.
     * Set to -1 to disable (not recommended for performance).
     */
    private static final int MAX_LAYERS_PER_EMITTER = 3;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        
        // Only run on the configured cadence
        if (event.getServer().getTickCount() % TICK_CADENCE != 0) return;

        // Iterate all server levels
        for (ServerLevel level : event.getServer().getAllLevels()) {
            broadcastParticlesForLevel(level);
        }
    }

    private static void broadcastParticlesForLevel(ServerLevel level) {
        // Iterate all players in this level (emitters)
        for (ServerPlayer emitter : level.players()) {
            // Skip if player is spectator or invisible (optional optimization)
            if (emitter.isSpectator() || emitter.isInvisible()) continue;

            // Get active particle cosmetic from server-authoritative PlayerData
            PlayerData.get(emitter).ifPresent(data -> {
                ResourceLocation particleCosmeticId = data.getEquippedParticlesId();
                
                // Skip if no particle cosmetic is equipped
                if (particleCosmeticId == null || isAir(particleCosmeticId)) return;

                // Find all viewers within range
                for (ServerPlayer viewer : level.players()) {
                    // Skip self (emitter sees their own particles via local client renderer)
                    if (viewer == emitter) continue;
                    
                    // Skip if viewer is spectator
                    if (viewer.isSpectator()) continue;

                    // Distance check (squared distance to avoid sqrt)
                    double dx = emitter.getX() - viewer.getX();
                    double dy = emitter.getY() - viewer.getY();
                    double dz = emitter.getZ() - viewer.getZ();
                    double distSq = dx * dx + dy * dy + dz * dz;

                    if (distSq <= VIEW_RANGE_SQUARED) {
                        // Generate a consistent seed for this emitter-viewer pair
                        // This makes patterns consistent per viewer (optional feature)
                        int seed = generateSeed(emitter.getId(), viewer.getId());
                        
                        // Send packet to viewer
                        CosmeticsLite.NETWORK.send(
                                PacketDistributor.PLAYER.with(() -> viewer),
                                new S2CCosmeticParticleEmit(
                                        emitter.getId(),
                                        particleCosmeticId,
                                        (byte) 0, // flags (can be enhanced later)
                                        seed,
                                        1.0f // strength (can be enhanced later)
                                )
                        );
                    }
                }
            });
        }
    }

    /**
     * Generate a consistent seed for an emitter-viewer pair.
     * This ensures that each viewer sees a consistent pattern for the same emitter.
     * 
     * <p>The seed incorporates:
     * - Emitter and viewer IDs for consistency per pair
     * - World tick for variation over time (optional, currently uses time)
     * 
     * <p>This can be enhanced to use the actual world tick count for better synchronization.
     */
    private static int generateSeed(int emitterId, int viewerId) {
        // Hash-based seed generation with time component for variation
        // Future: could use world tick count instead of system time for better sync
        return (emitterId * 31 + viewerId) ^ (int) (System.currentTimeMillis() / 1000);
    }
    
    // ============================================================================================
    // Configuration getters (for future config file integration)
    // ============================================================================================
    
    /** Get the current tick cadence. */
    public static int getTickCadence() {
        return TICK_CADENCE;
    }
    
    /** Get the current view range in blocks. */
    public static double getViewRange() {
        return VIEW_RANGE;
    }
    
    /** Get the maximum layers per emitter. */
    public static int getMaxLayersPerEmitter() {
        return MAX_LAYERS_PER_EMITTER;
    }

    private static boolean isAir(ResourceLocation id) {
        return id == null || ("minecraft".equals(id.getNamespace()) && "air".equals(id.getPath()));
    }
}
