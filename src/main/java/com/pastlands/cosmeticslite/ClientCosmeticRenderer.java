package com.pastlands.cosmeticslite;

import com.pastlands.cosmeticslite.particle.ParticleProfiles;
import com.pastlands.cosmeticslite.particle.config.ParticlePreviewState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.particles.DustParticleOptions;
import org.joml.Vector3f;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cosmetic particle renderer (local player only).
 *
 * - Spawns a light idle particle effect when a "particles" cosmetic is active.
 * - Particle type comes from CosmeticDef.properties().get("effect"), e.g.:
 *       "properties": { "effect": "minecraft:happy_villager" }
 *   Only supports simple (no-arg) particle types here; others fall back.
 *
 * - Honors in-GUI preview: if the Cosmetics screen is open and a particles
 *   cosmetic is SELECTED, we prefer that id locally (no packets).
 *
 * Forge 47.4.0 • MC 1.20.1 • Java 17
 */
@Mod.EventBusSubscriber(modid = CosmeticsLite.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientCosmeticRenderer {

    private ClientCosmeticRenderer() {}

    // Light rate-limit so we don't spam particles.
    private static int tickCounter = 0;
    
    // Debug flag for renderer logging (disabled by default to reduce log noise)
    private static final boolean DEBUG_COSMETICS_RENDERER = false;

    // ------------------------------- JSON-Driven Parameters -------------------------------

    private static final float MIN_INTERVAL = 0.01f; // max ~100 spawns/sec
    private static final float MAX_INTERVAL = 2.0f;

    private static final float MIN_LIFESPAN = 0.1f;
    private static final float MAX_LIFESPAN = 20.0f;

    private static final float MIN_SIZE = 0.05f;
    private static final float MAX_SIZE = 1.2f;

    private static final float MIN_SPEED = 0.0f;
    private static final float MAX_SPEED = 2.0f;

    private record ParticleRuntimeParams(
        float spawnInterval,  // in seconds
        float lifespan,        // in seconds
        float size,
        float speed,
        int count              // per-world-layer count
    ) {}
    
    /**
     * Position offset sample for a world layer particle.
     * Values are relative to player position.
     */
    private static class WorldSample {
        final double offsetX;
        final double offsetY;
        final double offsetZ;
        
        WorldSample(double x, double y, double z) {
            this.offsetX = x;
            this.offsetY = y;
            this.offsetZ = z;
        }
    }

    /**
     * Resolves runtime parameters from GUI layer config.
     * Uses first GUI layer if available, otherwise sensible defaults.
     * @deprecated Use resolveParamsForLayer with index for 1:1 mapping instead
     */
    private static ParticleRuntimeParams resolveParams(ParticleProfiles.ParticleProfile profile) {
        // Use first GUI layer if available
        if (profile.layers() != null && !profile.layers().isEmpty()) {
            ParticleProfiles.GuiLayerConfig layer = profile.layers().get(0);
            // Convert spawnInterval from ticks to seconds (assuming 20 TPS)
            float interval = Mth.clamp(layer.spawnInterval() / 20.0f, MIN_INTERVAL, MAX_INTERVAL);
            // Convert lifespan from ticks to seconds
            float lifespan = Mth.clamp(layer.lifespan() / 20.0f, MIN_LIFESPAN, MAX_LIFESPAN);
            float size = Mth.clamp(layer.size() / 5.0f, MIN_SIZE, MAX_SIZE); // normalize size 1-5 to 0.2-1.0
            float speed = Mth.clamp(layer.speed(), MIN_SPEED, MAX_SPEED);
            return new ParticleRuntimeParams(interval, lifespan, size, speed, 0);
        }
        // Defaults: maxed/juicy behavior
        return new ParticleRuntimeParams(0.05f, 1.0f, 1.0f, 1.0f, 0);
    }
    
    /**
     * Resolves runtime parameters from a specific GUI layer by index.
     * Enforces 1:1 mapping: World Layer i uses Layer i's parameters.
     * Falls back to first layer or defaults if index is out of bounds.
     */
    private static ParticleRuntimeParams resolveParamsForLayer(ParticleProfiles.ParticleProfile profile, int layerIndex) {
        if (profile.layers() != null && !profile.layers().isEmpty()) {
            // Use index-matched layer: World Layer i uses Layer i
            int safeIndex = layerIndex < profile.layers().size() 
                ? layerIndex 
                : (profile.layers().size() > 0 ? profile.layers().size() - 1 : 0);  // Fallback to last layer if index out of bounds
            
            ParticleProfiles.GuiLayerConfig layer = profile.layers().get(safeIndex);
            // Convert spawnInterval from ticks to seconds (assuming 20 TPS)
            float interval = Mth.clamp(layer.spawnInterval() / 20.0f, MIN_INTERVAL, MAX_INTERVAL);
            // Convert lifespan from ticks to seconds
            float lifespan = Mth.clamp(layer.lifespan() / 20.0f, MIN_LIFESPAN, MAX_LIFESPAN);
            float size = Mth.clamp(layer.size() / 5.0f, MIN_SIZE, MAX_SIZE); // normalize size 1-5 to 0.2-1.0
            float speed = Mth.clamp(layer.speed(), MIN_SPEED, MAX_SPEED);
            return new ParticleRuntimeParams(interval, lifespan, size, speed, 0);
        }
        // Defaults: maxed/juicy behavior
        return new ParticleRuntimeParams(0.05f, 1.0f, 1.0f, 1.0f, 0);
    }

    // ------------------------------- Per-Cosmetic State Tracking -------------------------------

    private static class LayerState {
        float elapsedSinceLastSpawn = 0.0f;
    }

    private static final Map<ResourceLocation, LayerState> layerStates = new ConcurrentHashMap<>();
    
    /**
     * Reset spawn timer for a cosmetic ID to force immediate respawn with new settings.
     * Called when movement/style changes to clear old particle streams.
     * Also clears state to force emitter rebuild from preview override (if active).
     */
    public static void resetPreviewState(ResourceLocation cosmeticId) {
        if (cosmeticId != null) {
            // If preview is active for this ID, clear state to force rebuild from preview override
            if (ParticlePreviewState.isActive() && 
                ParticlePreviewState.getCurrentPreviewId() != null &&
                ParticlePreviewState.getCurrentPreviewId().equals(cosmeticId)) {
                // Remove state entirely to force fresh rebuild on next tick
                layerStates.remove(cosmeticId);
            } else {
                // Just reset timer for non-preview (registry-based) particles
                LayerState state = layerStates.get(cosmeticId);
                if (state != null) {
                    state.elapsedSinceLastSpawn = 999.0f; // Force immediate spawn on next tick
                }
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        if (player.isSpectator()) return;
        if (!player.level().isClientSide()) return;

        tickCounter++;

        // Task D1: Priority order for local player:
        // 1. Particle Lab preview mode (if active) - takes absolute priority, even over equipped
        // 2. GUI mannequin/try-on preview
        // 3. Equipped cosmetic
        ResourceLocation cosmeticId = null;
        
        // Check preview state first - if active, use preview ID (even if null/air, don't fall back to equipped)
        if (ParticlePreviewState.isActive()) {
            cosmeticId = ParticlePreviewState.getCurrentPreviewId();
            // If preview is active but ID is null/air, don't render anything (preview overrides equipped)
            if (cosmeticId == null || isAir(cosmeticId)) {
                return; // Preview is active but no ID - don't render, don't fall back to equipped
            }
        } else {
            // Preview is NOT active - use normal equipped/preview logic
            cosmeticId = previewOrEquipped(player);
        }
        if (isAir(cosmeticId)) {
            // Clean up state when no cosmetic is active (only if we have a valid key)
            if (cosmeticId != null) {
                layerStates.remove(cosmeticId);
            }
            return;
        }

        // Step 2: Use clean resolution flow (preview-live → registry → catalog → fallback)
        var resolution = com.pastlands.cosmeticslite.particle.ParticleProfileResolver.resolve(cosmeticId);
        
        String mode = resolution.source();
        int worldLayerCount = 0;
        
        // Branch 0: preview-live mode - use live working copy from Particle Lab
        if ("preview-live".equals(mode) && resolution.profile() != null) {
            worldLayerCount = (resolution.profile().worldLayers() != null) ? resolution.profile().worldLayers().size() : 0;
            
            // Log once per cosmetic ID at first use
            if (tickCounter % 60 == 0) {
                CosmeticsLite.LOGGER.info("[cosmeticslite] ResolveParticle: cosmeticId={}, mode={}, worldLayers={}",
                    cosmeticId, mode, worldLayerCount);
            }
            
            renderBlendedParticleLayers(resolution.profile(), player, (ClientLevel) player.level(), 0.0f);
            return;
        }
        
        // Branch 1: profile-json mode - use registry definition with world layers
        if ("profile-json".equals(mode) && resolution.profile() != null) {
            worldLayerCount = (resolution.profile().worldLayers() != null) ? resolution.profile().worldLayers().size() : 0;
            
            // Log once per cosmetic ID at first use
            if (tickCounter % 60 == 0) {
                CosmeticsLite.LOGGER.info("[cosmeticslite] ResolveParticle: cosmeticId={}, mode={}, worldLayers={}",
                    cosmeticId, mode, worldLayerCount);
            }
            
            renderBlendedParticleLayers(resolution.profile(), player, (ClientLevel) player.level(), 0.0f);
            return;
        }
        
        // Branch 2: simple-catalog mode - use catalog entry for vanilla particles
        if ("simple-catalog".equals(mode) && resolution.catalogEntry() != null) {
            worldLayerCount = 0;
            
            // Log once per cosmetic ID at first use
            if (tickCounter % 60 == 0) {
                CosmeticsLite.LOGGER.info("[cosmeticslite] ResolveParticle: cosmeticId={}, mode={}, worldLayers={}",
                    cosmeticId, mode, worldLayerCount);
            }
            
            spawnSimplePattern(resolution.catalogEntry(), player, (ClientLevel) player.level(), player.getRandom());
            return;
        }
        
        // Branch 3: fallback-default mode - gear-spark fallback
        mode = "fallback-default";
        worldLayerCount = 0;
        
        // Log once per cosmetic ID at first use
        if (tickCounter % 60 == 0) {
            CosmeticsLite.LOGGER.info("[cosmeticslite] ResolveParticle: cosmeticId={}, mode={}, worldLayers={}",
                cosmeticId, mode, worldLayerCount);
        }
        
        // True default: gear-spark fallback
        CosmeticDef def = CosmeticsRegistry.get(cosmeticId);
        String effectId = (def != null)
                ? def.properties().getOrDefault("effect", "minecraft:happy_villager")
                : "minecraft:happy_villager";

        ParticleOptions particle = resolveParticle(effectId);
        if (particle == null) return;

        // Default behavior: 2 particles near body center
        // Use consistent anchor point (body-center at 0.6) to match shared spawner
        if (tickCounter % 6 != 0) return;

        RandomSource r = player.getRandom();
        double baseX = player.getX();
        double baseY = player.getY() + player.getBbHeight() * com.pastlands.cosmeticslite.particle.SharedParticleSpawner.BODY_CENTER_HEIGHT_FACTOR;
        double baseZ = player.getZ();
        int count = 2;
        for (int i = 0; i < count; i++) {
            double ox = (r.nextDouble() - 0.5) * 0.6;
            double oy = (r.nextDouble() - 0.5) * 0.3;
            double oz = (r.nextDouble() - 0.5) * 0.6;
            double vx = 0.0;
            double vy = 0.01 + r.nextDouble() * 0.01;
            double vz = 0.0;
            player.level().addParticle(particle, baseX + ox, baseY + oy, baseZ + oz, vx, vy, vz);
        }
    }

    /** Use GUI preview override (if any for the local player) else equipped. */
    private static ResourceLocation previewOrEquipped(LocalPlayer player) {
        // PreviewResolver is no-op outside the cosmetics screen/mannequin;
        // if preview is active for the local player, it returns a non-null id.
        ResourceLocation override = CosmeticsChestScreen.PreviewResolver.getOverride("particles", player);
        return (override != null) ? override : ClientState.getEquippedId(player, "particles");
    }

    /** Accepts a string like "minecraft:happy_villager". Only supports simple (no-arg) particles. */
    private static ParticleOptions resolveParticle(String idOrNull) {
        if (idOrNull == null || idOrNull.isEmpty()) return ParticleTypes.HAPPY_VILLAGER;

        ResourceLocation rl = ResourceLocation.tryParse(idOrNull);
        if (rl == null) return ParticleTypes.HAPPY_VILLAGER;

        return resolveParticle(rl);
    }

    /**
     * Spawn simple pattern for built-in particles using catalog entry data.
     * Uses the classic behavior that spawns particles based purely on the CosmeticParticleEntry.
     * 
     * @param entry The catalog entry containing particle ID and metadata
     * @param player The player to spawn particles around
     * @param level The client level
     * @param random Random source
     */
    private static void spawnSimplePattern(com.pastlands.cosmeticslite.particle.CosmeticParticleEntry entry,
                                          AbstractClientPlayer player,
                                          ClientLevel level,
                                          RandomSource random) {
        // Get particle effect from entry's particleId
        ResourceLocation particleId = entry.particleId();
        String effectId;
        
        if ("minecraft".equals(particleId.getNamespace())) {
            // Base particles: particleId is already the effect (e.g., minecraft:bubble)
            effectId = particleId.toString();
        } else {
            // For cosmeticslite namespace, try to extract effect from cosmetic definition
            CosmeticDef def = CosmeticsRegistry.get(entry.id());
            effectId = (def != null)
                    ? def.properties().getOrDefault("effect", "minecraft:happy_villager")
                    : "minecraft:happy_villager";
        }
        
        ParticleOptions particle = resolveParticle(effectId);
        if (particle == null) return;
        
        // Classic simple pattern: 2 particles near body center, every 6 ticks
        // Use consistent anchor point (body-center at 0.6) to match shared spawner
        if (tickCounter % 6 != 0) return;
        
        double baseX = player.getX();
        double baseY = player.getY() + player.getBbHeight() * com.pastlands.cosmeticslite.particle.SharedParticleSpawner.BODY_CENTER_HEIGHT_FACTOR;
        double baseZ = player.getZ();
        int count = 2;
        
        for (int i = 0; i < count; i++) {
            double ox = (random.nextDouble() - 0.5) * 0.6;
            double oy = (random.nextDouble() - 0.5) * 0.3;
            double oz = (random.nextDouble() - 0.5) * 0.6;
            double vx = 0.0;
            double vy = 0.01 + random.nextDouble() * 0.01;
            double vz = 0.0;
            level.addParticle(particle, baseX + ox, baseY + oy, baseZ + oz, vx, vy, vz);
        }
    }
    
    private static boolean isAir(ResourceLocation id) {
        return id == null || ("minecraft".equals(id.getNamespace()) && "air".equals(id.getPath()));
    }

    private static void renderBlendedParticleLayers(ParticleProfiles.ParticleProfile profile,
                                                     AbstractClientPlayer player,
                                                     ClientLevel level,
                                                     float partialTicks) {
        ResourceLocation cosmeticId = profile.cosmeticId();
        if (cosmeticId == null) {
            CosmeticsLite.LOGGER.warn("[cosmeticslite] Profile has null cosmeticId, skipping render");
            return;
        }

        // Debug logging for layer colors (disabled by default)
        if (DEBUG_COSMETICS_RENDERER && tickCounter % 60 == 0 && profile.layers() != null && !profile.layers().isEmpty()) {
            CosmeticsLite.LOGGER.info("[RendererDebug] Profile {} has {} GUI layer(s)", cosmeticId, profile.layers().size());
            for (int i = 0; i < profile.layers().size(); i++) {
                var layer = profile.layers().get(i);
                var colors = layer.colors();
                if (colors != null && !colors.isEmpty()) {
                    String colorStr = colors.stream()
                        .map(c -> String.format("#%08X", c))
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("none");
                    CosmeticsLite.LOGGER.info("[RendererDebug]   Layer {}: movement={}, colors=[{}], interval={}, lifespan={}",
                        i, layer.movement(), colorStr, layer.spawnInterval(), layer.lifespan());
                }
            }
        }

        // Get or create state for this cosmetic
        LayerState state = layerStates.computeIfAbsent(cosmeticId, k -> new LayerState());

        // Time delta: 1 tick = 1/20 second at 20 TPS
        float dtSeconds = 1.0f / 20.0f;
        state.elapsedSinceLastSpawn += dtSeconds;

        // Safety cap: max 200 particles per cosmetic per tick
        int totalSpawned = 0;
        final int MAX_PARTICLES_PER_TICK = 200;

        // Render GUI layers (from editor Layers tab) - these drive the actual particle spawning
        if (profile.layers() != null && !profile.layers().isEmpty()) {
            for (int layerIndex = 0; layerIndex < profile.layers().size(); layerIndex++) {
                ParticleProfiles.GuiLayerConfig guiLayer = profile.layers().get(layerIndex);
                if (totalSpawned >= MAX_PARTICLES_PER_TICK) break;
                
                // Check spawn interval for this layer
                float layerInterval = guiLayer.spawnInterval() / 20.0f; // Convert ticks to seconds
                if (state.elapsedSinceLastSpawn < layerInterval) {
                    continue; // Not time to spawn for this layer yet
                }
                
                // Debug log (disabled by default)
                if (DEBUG_COSMETICS_RENDERER) {
                    CosmeticsLite.LOGGER.info("[RendererDebug] Spawning GUI layer {} with movement={}", layerIndex, guiLayer.movement());
                }
                
                // Spawn particles with movement-specific behavior
                spawnGuiLayerParticles(level, player, guiLayer, profile, cosmeticId);
                
                // Reset spawn timer for this layer (simplified - in reality each layer should have its own timer)
                state.elapsedSinceLastSpawn = 0.0f;
            }
        }
        
        // Also render world layers (from World tab) as before
        if (profile.worldLayers() != null && !profile.worldLayers().isEmpty()) {
            // Try to get full ParticleDefinition - check preview override first, then registry
            com.pastlands.cosmeticslite.particle.config.ParticleDefinition fullDef = null;
            if (profile.id() != null) {
                // If preview is active for this ID, use preview override (working copy)
                if (ParticlePreviewState.isActive() && 
                    ParticlePreviewState.getCurrentPreviewId() != null &&
                    ParticlePreviewState.getCurrentPreviewId().equals(profile.id())) {
                    fullDef = ParticlePreviewState.getPreviewOverride(profile.id());
                }
                // Fallback to registry if no override
                if (fullDef == null) {
                    fullDef = com.pastlands.cosmeticslite.particle.config.CosmeticParticleRegistry.get(profile.id());
                }
            }
            
            for (int worldLayerIndex = 0; worldLayerIndex < profile.worldLayers().size(); worldLayerIndex++) {
                if (totalSpawned >= MAX_PARTICLES_PER_TICK) break;

                ParticleProfiles.WorldLayerConfig worldLayer = profile.worldLayers().get(worldLayerIndex);
                ParticleOptions particle = resolveParticle(worldLayer.effect());
                if (particle == null) continue;
                
                // Get full WorldLayerDefinition if available, otherwise use WorldLayerConfig with defaults
                com.pastlands.cosmeticslite.particle.config.WorldLayerDefinition fullWorldLayer = null;
                if (fullDef != null && fullDef.worldLayers() != null && 
                    worldLayerIndex < fullDef.worldLayers().size()) {
                    fullWorldLayer = fullDef.worldLayers().get(worldLayerIndex);
                }
                
                // Check if this effect supports tinting before applying color
                ResourceLocation effectId = worldLayer.effect();
                
                // Fix: Check if bubble effect and swap to bubble_pop if not in water
                if (effectId != null && (
                    effectId.equals(ResourceLocation.fromNamespaceAndPath("minecraft", "bubble")) ||
                    effectId.equals(ResourceLocation.fromNamespaceAndPath("minecraft", "bubble_column_up")) ||
                    effectId.equals(ResourceLocation.fromNamespaceAndPath("minecraft", "bubble_column_down"))
                )) {
                    // Check fluid at player position
                    BlockPos playerPos = BlockPos.containing(player.getX(), player.getY(), player.getZ());
                    if (!level.getFluidState(playerPos).is(FluidTags.WATER)) {
                        // Not in water, use preview-safe fallback
                        effectId = ResourceLocation.fromNamespaceAndPath("minecraft", "bubble_pop");
                        particle = resolveParticle(effectId);
                        if (particle == null) continue;
                    }
                }
                
                var caps = com.pastlands.cosmeticslite.client.editor.EffectCapabilitiesRegistry.get(effectId);
                ParticleOptions finalParticle = particle;
                
                // Only apply color tint if the effect supports it
                if (caps != null && caps.supportsTint()) {
                    // Get color from the corresponding GUI layer (index-matched, not layers[0])
                    int tintColor = 0xFFFFFFFF;
                    if (profile.layers() != null && !profile.layers().isEmpty()) {
                        // Use index-matched layer: World Layer i uses Layer i
                        int layerIndex = worldLayerIndex < profile.layers().size() 
                            ? worldLayerIndex 
                            : profile.layers().size() - 1;  // Safe fallback to last layer if index out of bounds
                        var guiLayer = profile.layers().get(layerIndex);
                        var colors = guiLayer.colors();
                        if (colors != null && !colors.isEmpty()) {
                            tintColor = colors.get(player.getRandom().nextInt(colors.size()));
                        }
                    }
                    finalParticle = applyColorTint(particle, tintColor);
                }
                // If !supportsTint, use the native particle without color override

                // Get speed multiplier and apply it to spawn rate
                float speedMultiplier = worldLayer.speedY();
                if (fullWorldLayer != null) {
                    speedMultiplier = fullWorldLayer.speedY();
                }
                // Clamp speed multiplier to 0.001-3.0 range (matches UI)
                speedMultiplier = Math.max(0.001f, Math.min(3.0f, speedMultiplier));
                
                int count = Math.max(1, worldLayer.count());
                // Apply speed multiplier to spawn count (lower speed = fewer particles)
                int effectiveSpawnCount = Math.max(0, Math.round(count * speedMultiplier));
                int toSpawn = Math.min(effectiveSpawnCount, MAX_PARTICLES_PER_TICK - totalSpawned);
                totalSpawned += toSpawn;

                // Use index-matched layer parameters: World Layer i uses Layer i's params
                ParticleRuntimeParams layerParams = resolveParamsForLayer(profile, worldLayerIndex);
                layerParams = new ParticleRuntimeParams(
                    layerParams.spawnInterval(),
                    layerParams.lifespan(),
                    layerParams.size(),
                    layerParams.speed(),
                    toSpawn
                );

                // Render using full WorldLayerDefinition if available, otherwise fall back to WorldLayerConfig
                if (fullWorldLayer != null) {
                    switch (fullWorldLayer.style()) {
                        case "halo"   -> renderHaloWithFullDef(level, player, finalParticle, fullWorldLayer, layerParams);
                        case "column" -> renderColumnWithFullDef(level, player, finalParticle, fullWorldLayer, layerParams);
                        case "trail"  -> renderTrailWithFullDef(level, player, finalParticle, fullWorldLayer, layerParams);
                        case "cape"   -> renderCapeWithFullDef(level, player, finalParticle, fullWorldLayer, layerParams, cosmeticId);
                        case "ground" -> renderGroundWithFullDef(level, player, finalParticle, fullWorldLayer, layerParams);
                        case "wings"  -> renderWingsWithFullDef(level, player, finalParticle, fullWorldLayer, layerParams);
                        case "belt"   -> renderBeltWithFullDef(level, player, finalParticle, fullWorldLayer, layerParams);
                        case "spiral" -> renderSpiralWithFullDef(level, player, finalParticle, fullWorldLayer, layerParams);
                        default       -> renderHaloWithFullDef(level, player, finalParticle, fullWorldLayer, layerParams);
                    }
                } else {
                    // Fallback to old render methods using WorldLayerConfig (legacy behavior)
                    switch (worldLayer.style()) {
                        case "halo"   -> renderHalo(level, player, finalParticle, worldLayer, layerParams);
                        case "column" -> renderColumn(level, player, finalParticle, worldLayer, layerParams);
                        case "trail"  -> renderTrail(level, player, finalParticle, worldLayer, layerParams);
                        case "cape"   -> renderCape(level, player, finalParticle, worldLayer, layerParams, cosmeticId);
                        case "ground" -> renderGround(level, player, finalParticle, worldLayer, layerParams);
                        case "wings"  -> renderWings(level, player, finalParticle, worldLayer, layerParams);
                        case "belt"   -> renderBelt(level, player, finalParticle, worldLayer, layerParams);
                        case "spiral" -> renderSpiral(level, player, finalParticle, worldLayer, layerParams);
                        default       -> renderHalo(level, player, finalParticle, worldLayer, layerParams);
                    }
                }
            }
        }

    }

    private static ParticleOptions resolveParticle(ResourceLocation effect) {
        if (effect == null) return ParticleTypes.HAPPY_VILLAGER;

        var type = BuiltInRegistries.PARTICLE_TYPE.get(effect);
        if (type instanceof SimpleParticleType simple) {
            return simple;
        }
        // Unsupported parameterized particle type -> visible fallback
        return ParticleTypes.HAPPY_VILLAGER;
    }
    
    /**
     * Apply color tint to a particle. If the particle is a SimpleParticleType and color is not white,
     * converts it to a DustParticleOptions with the tint color. Otherwise returns the original particle.
     */
    private static ParticleOptions applyColorTint(ParticleOptions particle, int argbColor) {
        // Only apply tint if color is not white (0xFFFFFFFF)
        if (argbColor == 0xFFFFFFFF) {
            return particle;
        }
        
        // Convert ARGB to RGB Vector3f (normalized 0..1)
        float r = ((argbColor >> 16) & 0xFF) / 255.0f;
        float g = ((argbColor >> 8) & 0xFF) / 255.0f;
        float b = (argbColor & 0xFF) / 255.0f;
        Vector3f color = new Vector3f(r, g, b);
        
        // Use dust particle with color (size = 1.0 for standard visibility)
        return new DustParticleOptions(color, 1.0f);
    }
    
    /**
     * Spawn particles for a GUI layer with movement-specific behavior.
     */
    private static void spawnGuiLayerParticles(ClientLevel level, AbstractClientPlayer player,
                                               ParticleProfiles.GuiLayerConfig layer,
                                               ParticleProfiles.ParticleProfile profile,
                                               ResourceLocation cosmeticId) {
        ParticleProfiles.Movement movement = layer.movement();
        if (movement == null) {
            movement = ParticleProfiles.Movement.DEFAULT;
        }
        
        // Determine particle effect - use first world layer's effect if available, otherwise default
        ResourceLocation effectId = null;
        if (profile.worldLayers() != null && !profile.worldLayers().isEmpty()) {
            effectId = profile.worldLayers().get(0).effect();
        }
        if (effectId == null) {
            effectId = ResourceLocation.tryParse("minecraft:happy_villager");
        }
        
        // Fix: Check if bubble effect and swap to bubble_pop if not in water
        if (effectId != null && (
            effectId.equals(ResourceLocation.fromNamespaceAndPath("minecraft", "bubble")) ||
            effectId.equals(ResourceLocation.fromNamespaceAndPath("minecraft", "bubble_column_up")) ||
            effectId.equals(ResourceLocation.fromNamespaceAndPath("minecraft", "bubble_column_down"))
        )) {
            // Check fluid at player position
            BlockPos playerPos = BlockPos.containing(player.getX(), player.getY(), player.getZ());
            if (!level.getFluidState(playerPos).is(FluidTags.WATER)) {
                // Not in water, use preview-safe fallback
                effectId = ResourceLocation.fromNamespaceAndPath("minecraft", "bubble_pop");
            }
        }
        
        ParticleOptions baseParticle = resolveParticle(effectId);
        ParticleOptions finalParticle = baseParticle;
        
        // Check if this effect supports tinting before applying color
        var caps = com.pastlands.cosmeticslite.client.editor.EffectCapabilitiesRegistry.get(effectId);
        if (caps != null && caps.supportsTint()) {
            // Get a random color from this layer's colors
            int tintColor = 0xFFFFFFFF; // Default white
            var colors = layer.colors();
            if (colors != null && !colors.isEmpty()) {
                tintColor = colors.get(player.getRandom().nextInt(colors.size()));
            }
            finalParticle = applyColorTint(baseParticle, tintColor);
        }
        // If !supportsTint, use the native particle without color override
        
        // Calculate spawn count based on layer settings
        int count = Math.max(1, Math.round(layer.lifespan() / 20.0f)); // Rough estimate per spawn
        
        // Route to movement-specific spawn functions
        switch (movement) {
            case FLOAT_UP -> spawnFloatUp(level, player, finalParticle, layer, count);
            case BURST -> spawnBurst(level, player, finalParticle, layer, count);
            case FALL_DOWN -> spawnFallDown(level, player, finalParticle, layer, count);
            case MUSICAL_FLOAT -> spawnMusical(level, player, finalParticle, layer, count);
            case SWIRL -> spawnSwirl(level, player, finalParticle, layer, count);
            case BOUNCE_UP -> spawnBounceUp(level, player, finalParticle, layer, count);
            case DRIFT_UP -> spawnDriftUp(level, player, finalParticle, layer, count);
            case FLICKER_UP -> spawnFlickerUp(level, player, finalParticle, layer, count);
            case BUBBLE_POP -> spawnBubblePop(level, player, finalParticle, layer, count);
            default -> spawnFloatUp(level, player, finalParticle, layer, count);
        }
    }
    
    /**
     * FLOAT_UP: Small gentle upward drift
     */
    private static void spawnFloatUp(ClientLevel level, AbstractClientPlayer player,
                                    ParticleOptions particle, ParticleProfiles.GuiLayerConfig layer, int count) {
        RandomSource r = player.getRandom();
        double baseX = player.getX();
        double baseY = player.getY() + player.getBbHeight() * 0.5;
        double baseZ = player.getZ();
        
        float speed = Mth.clamp(layer.speed(), 0.01f, 0.5f);
        float weight = Mth.clamp(layer.weight(), 0.1f, 2.0f);
        
        for (int i = 0; i < count; i++) {
            double ox = (r.nextDouble() - 0.5) * 0.3;
            double oy = (r.nextDouble() - 0.5) * 0.2;
            double oz = (r.nextDouble() - 0.5) * 0.3;
            double vx = (r.nextDouble() - 0.5) * 0.01 * speed;
            double vy = 0.03 * speed / weight; // Gentle upward
            double vz = (r.nextDouble() - 0.5) * 0.01 * speed;
            
            level.addParticle(particle, baseX + ox, baseY + oy, baseZ + oz, vx, vy, vz);
        }
    }
    
    /**
     * BURST: Big radial explosion from player, high horizontal speed, short lifespan
     */
    private static void spawnBurst(ClientLevel level, AbstractClientPlayer player,
                                  ParticleOptions particle, ParticleProfiles.GuiLayerConfig layer, int count) {
        RandomSource r = player.getRandom();
        double baseX = player.getX();
        double baseY = player.getY() + player.getBbHeight() * 0.5;
        double baseZ = player.getZ();
        
        float speed = Mth.clamp(layer.speed(), 0.01f, 0.5f);
        
        for (int i = 0; i < count; i++) {
            // Radial burst outward
            double angle = r.nextDouble() * Math.PI * 2;
            double elevation = (r.nextDouble() - 0.4) * Math.PI / 3; // Slight upward bias
            double burstSpeed = (0.15 + r.nextDouble() * 0.25) * speed;
            
            double vx = Math.cos(elevation) * Math.cos(angle) * burstSpeed;
            double vy = Math.sin(elevation) * burstSpeed;
            double vz = Math.cos(elevation) * Math.sin(angle) * burstSpeed;
            
            level.addParticle(particle, baseX, baseY, baseZ, vx, vy, vz);
        }
    }
    
    /**
     * FALL_DOWN: Spawn slightly above player and accelerate downwards
     */
    private static void spawnFallDown(ClientLevel level, AbstractClientPlayer player,
                                     ParticleOptions particle, ParticleProfiles.GuiLayerConfig layer, int count) {
        RandomSource r = player.getRandom();
        double baseX = player.getX();
        double baseY = player.getY() + player.getBbHeight() * 0.8; // Spawn above
        double baseZ = player.getZ();
        
        float speed = Mth.clamp(layer.speed(), 0.01f, 0.5f);
        float weight = Mth.clamp(layer.weight(), 0.1f, 2.0f);
        
        for (int i = 0; i < count; i++) {
            double ox = (r.nextDouble() - 0.5) * 0.4;
            double oy = r.nextDouble() * 0.3; // Above spawn point
            double oz = (r.nextDouble() - 0.5) * 0.4;
            double vx = (r.nextDouble() - 0.5) * 0.005 * speed;
            double vy = -0.1 * speed * weight; // Strong downward
            double vz = (r.nextDouble() - 0.5) * 0.005 * speed;
            
            level.addParticle(particle, baseX + ox, baseY + oy, baseZ + oz, vx, vy, vz);
        }
    }
    
    /**
     * MUSICAL_FLOAT: Sinusoid on X/Z or Y with small slow drift
     */
    private static void spawnMusical(ClientLevel level, AbstractClientPlayer player,
                                    ParticleOptions particle, ParticleProfiles.GuiLayerConfig layer, int count) {
        RandomSource r = player.getRandom();
        double baseX = player.getX();
        double baseY = player.getY() + player.getBbHeight() * 0.5;
        double baseZ = player.getZ();
        
        float speed = Mth.clamp(layer.speed(), 0.01f, 0.5f);
        float weight = Mth.clamp(layer.weight(), 0.1f, 2.0f);
        
        for (int i = 0; i < count; i++) {
            double phase = (player.tickCount * 0.1 + i * 0.3) % (Math.PI * 2);
            double ox = Math.sin(phase) * 0.2;
            double oy = (r.nextDouble() - 0.5) * 0.3 + Math.cos(phase * 0.7) * 0.1;
            double oz = Math.cos(phase) * 0.2;
            double vx = Math.cos(phase) * 0.015 * speed;
            double vy = 0.02 * speed / weight; // Slow upward drift
            double vz = -Math.sin(phase) * 0.015 * speed;
            
            level.addParticle(particle, baseX + ox, baseY + oy, baseZ + oz, vx, vy, vz);
        }
    }
    
    /**
     * SWIRL: Circular orbit around player (use angle += ω each tick, rotate velocity)
     */
    private static void spawnSwirl(ClientLevel level, AbstractClientPlayer player,
                                  ParticleOptions particle, ParticleProfiles.GuiLayerConfig layer, int count) {
        RandomSource r = player.getRandom();
        double baseX = player.getX();
        double baseY = player.getY() + player.getBbHeight() * 0.5;
        double baseZ = player.getZ();
        
        float speed = Mth.clamp(layer.speed(), 0.01f, 0.5f);
        
        for (int i = 0; i < count; i++) {
            // Circular orbit
            double orbitAngle = (player.tickCount * 0.2 + i * 0.8) % (Math.PI * 2);
            double orbitRadius = 0.6 + r.nextDouble() * 0.4;
            double ox = Math.cos(orbitAngle) * orbitRadius;
            double oy = (r.nextDouble() - 0.5) * 0.2;
            double oz = Math.sin(orbitAngle) * orbitRadius;
            
            // Tangential velocity (perpendicular to radius)
            double vx = -Math.sin(orbitAngle) * 0.05 * speed;
            double vy = (r.nextDouble() - 0.5) * 0.01 * speed;
            double vz = Math.cos(orbitAngle) * 0.05 * speed;
            
            level.addParticle(particle, baseX + ox, baseY + oy, baseZ + oz, vx, vy, vz);
        }
    }
    
    /**
     * BOUNCE_UP: Upward with slight bouncing motion
     */
    private static void spawnBounceUp(ClientLevel level, AbstractClientPlayer player,
                                     ParticleOptions particle, ParticleProfiles.GuiLayerConfig layer, int count) {
        RandomSource r = player.getRandom();
        double baseX = player.getX();
        double baseY = player.getY() + player.getBbHeight() * 0.5;
        double baseZ = player.getZ();
        
        float speed = Mth.clamp(layer.speed(), 0.01f, 0.5f);
        float weight = Mth.clamp(layer.weight(), 0.1f, 2.0f);
        
        for (int i = 0; i < count; i++) {
            double ox = (r.nextDouble() - 0.5) * 0.3;
            double oy = (r.nextDouble() - 0.5) * 0.2;
            double oz = (r.nextDouble() - 0.5) * 0.3;
            double vx = (r.nextDouble() - 0.5) * 0.02 * speed;
            double vy = (0.05 + r.nextDouble() * 0.03) * speed / weight; // Bouncy upward
            double vz = (r.nextDouble() - 0.5) * 0.02 * speed;
            
            level.addParticle(particle, baseX + ox, baseY + oy, baseZ + oz, vx, vy, vz);
        }
    }
    
    /**
     * DRIFT_UP: Slow horizontal drift with gentle upward motion
     */
    private static void spawnDriftUp(ClientLevel level, AbstractClientPlayer player,
                                    ParticleOptions particle, ParticleProfiles.GuiLayerConfig layer, int count) {
        RandomSource r = player.getRandom();
        double baseX = player.getX();
        double baseY = player.getY() + player.getBbHeight() * 0.5;
        double baseZ = player.getZ();
        
        float speed = Mth.clamp(layer.speed(), 0.01f, 0.5f);
        float weight = Mth.clamp(layer.weight(), 0.1f, 2.0f);
        
        for (int i = 0; i < count; i++) {
            double ox = (r.nextDouble() - 0.5) * 0.4;
            double oy = (r.nextDouble() - 0.5) * 0.2;
            double oz = (r.nextDouble() - 0.5) * 0.4;
            double vx = (r.nextDouble() - 0.5) * 0.025 * speed; // More horizontal drift
            double vy = 0.025 * speed / weight; // Gentle upward
            double vz = (r.nextDouble() - 0.5) * 0.025 * speed;
            
            level.addParticle(particle, baseX + ox, baseY + oy, baseZ + oz, vx, vy, vz);
        }
    }
    
    /**
     * FLICKER_UP: Flickering/sparkling upward motion
     */
    private static void spawnFlickerUp(ClientLevel level, AbstractClientPlayer player,
                                      ParticleOptions particle, ParticleProfiles.GuiLayerConfig layer, int count) {
        RandomSource r = player.getRandom();
        double baseX = player.getX();
        double baseY = player.getY() + player.getBbHeight() * 0.5;
        double baseZ = player.getZ();
        
        float speed = Mth.clamp(layer.speed(), 0.01f, 0.5f);
        float weight = Mth.clamp(layer.weight(), 0.1f, 2.0f);
        
        for (int i = 0; i < count; i++) {
            // Random flickering positions
            double ox = (r.nextDouble() - 0.5) * 0.5;
            double oy = (r.nextDouble() - 0.5) * 0.3;
            double oz = (r.nextDouble() - 0.5) * 0.5;
            // Variable upward velocity with flicker
            double vx = (r.nextDouble() - 0.5) * 0.03 * speed;
            double vy = (0.04 + r.nextDouble() * 0.05) * speed / weight; // Variable upward
            double vz = (r.nextDouble() - 0.5) * 0.03 * speed;
            
            level.addParticle(particle, baseX + ox, baseY + oy, baseZ + oz, vx, vy, vz);
        }
    }
    
    /**
     * BUBBLE_POP: Playful upward bubbles
     */
    private static void spawnBubblePop(ClientLevel level, AbstractClientPlayer player,
                                      ParticleOptions particle, ParticleProfiles.GuiLayerConfig layer, int count) {
        RandomSource r = player.getRandom();
        double baseX = player.getX();
        double baseY = player.getY() + player.getBbHeight() * 0.3;
        double baseZ = player.getZ();
        
        float speed = Mth.clamp(layer.speed(), 0.01f, 0.5f);
        float weight = Mth.clamp(layer.weight(), 0.1f, 2.0f);
        
        for (int i = 0; i < count; i++) {
            double ox = (r.nextDouble() - 0.5) * 0.4;
            double oy = (r.nextDouble() - 0.5) * 0.2;
            double oz = (r.nextDouble() - 0.5) * 0.4;
            double vx = (r.nextDouble() - 0.5) * 0.02 * speed;
            double vy = (0.06 + r.nextDouble() * 0.04) * speed / (weight * 0.7f); // Bubbly upward
            double vz = (r.nextDouble() - 0.5) * 0.02 * speed;
            
            level.addParticle(particle, baseX + ox, baseY + oy, baseZ + oz, vx, vy, vz);
        }
    }

    private static void renderHalo(ClientLevel level, AbstractClientPlayer player,
                                   ParticleOptions particle, ParticleProfiles.WorldLayerConfig layer,
                                   ParticleRuntimeParams params) {
        RandomSource r = player.getRandom();
        double baseX = player.getX();
        double baseY = player.getY() + player.getBbHeight() * layer.heightFactor();
        double baseZ = player.getZ();
        float radius = layer.radius();
        int count = params.count();
        double vy = params.speed() + (r.nextDouble() - 0.5) * 0.01;

        // Ring of particles around upper body with slow rotation
        // Use player.tickCount for rotation so halo clearly spins
        double baseAngle = (player.tickCount * 0.15) % (Math.PI * 2.0);
        for (int i = 0; i < count; i++) {
            double angle = baseAngle + (Math.PI * 2.0 * i / count);
            double ox = Math.cos(angle) * radius;
            double oz = Math.sin(angle) * radius;
            double oy = (r.nextDouble() - 0.5) * 0.2;
            double vx = 0.0;
            double vz = 0.0;
            level.addParticle(particle, baseX + ox, baseY + oy, baseZ + oz, vx, vy, vz);
        }
    }

    private static void renderColumn(ClientLevel level, AbstractClientPlayer player,
                                    ParticleOptions particle, ParticleProfiles.WorldLayerConfig layer,
                                    ParticleRuntimeParams params) {
        RandomSource r = player.getRandom();
        double baseX = player.getX();
        double baseY = player.getY() + player.getBbHeight() * layer.heightFactor();
        double baseZ = player.getZ();
        float radius = layer.radius();
        int count = params.count();
        double vy = params.speed() + (r.nextDouble() - 0.5) * 0.01;

        // Vertical column with gentle upward motion
        for (int i = 0; i < count; i++) {
            double ox = (r.nextDouble() - 0.5) * radius;
            double oz = (r.nextDouble() - 0.5) * radius;
            double oy = (r.nextDouble() - 0.5) * 0.4;
            double vx = 0.0;
            double vz = 0.0;
            level.addParticle(particle, baseX + ox, baseY + oy, baseZ + oz, vx, vy, vz);
        }
    }

    private static void renderTrail(ClientLevel level, AbstractClientPlayer player,
                                    ParticleOptions particle, ParticleProfiles.WorldLayerConfig layer,
                                    ParticleRuntimeParams params) {
        RandomSource r = player.getRandom();
        double baseX = player.getX();
        double baseY = player.getY() + player.getBbHeight() * layer.heightFactor();
        double baseZ = player.getZ();
        float radius = layer.radius();
        int count = params.count();
        double vy = params.speed() + (r.nextDouble() - 0.5) * 0.01;

        // Cluster at feet / behind facing direction
        double yaw = Math.toRadians(player.getYRot());
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        for (int i = 0; i < count; i++) {
            double ox = forwardX * radius * 0.5 + (r.nextDouble() - 0.5) * radius * 0.3;
            double oz = forwardZ * radius * 0.5 + (r.nextDouble() - 0.5) * radius * 0.3;
            double oy = (r.nextDouble() - 0.5) * 0.2;
            double vx = 0.0;
            double vz = 0.0;
            level.addParticle(particle, baseX + ox, baseY + oy - 0.5, baseZ + oz, vx, vy, vz);
        }
    }

    // ------------------------------- Vector Utilities -------------------------------

    private static Vec3 forward(AbstractClientPlayer player) {
        Vec3 look = player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0, look.z);
        double len = horizontal.length();
        return (len > 1e-6) ? horizontal.scale(1.0 / len) : new Vec3(0, 0, 1);
    }

    private static Vec3 right(AbstractClientPlayer player) {
        Vec3 f = forward(player);
        // Right-handed perpendicular: rotate 90° CCW in XZ plane
        return new Vec3(-f.z, 0, f.x);
    }

    private static Vec3 up(AbstractClientPlayer player) {
        return new Vec3(0, 1, 0);
    }

    // ------------------------------- World Layer Position Sampling -------------------------------
    
    /**
     * Sample a position for a world layer particle using the new fields.
     * Returns player-relative offset (add to player position to get world position).
     * 
     * @param world The full WorldLayerDefinition (contains all new fields)
     * @param timeSeconds Current time in seconds (for rotation/animation)
     * @param index Particle index (0 to count-1) or seed value
     * @param random Random source for sampling
     * @return WorldSample with offsetX, offsetY, offsetZ relative to player
     */
    private static WorldSample sampleWorldLayerPosition(
        com.pastlands.cosmeticslite.particle.config.WorldLayerDefinition world,
        double timeSeconds,
        int index,
        RandomSource random
    ) {
        // 1. Vertical: baseHeight + stretch + motionCurve
        double minY = world.baseHeight();
        double maxY = world.baseHeight() + Math.max(0.0, world.heightStretch());
        
        double h; // normalized [0, 1] for vertical
        if (maxY > minY) {
            // If user set a stretch, sample along the range
            h = random.nextDouble();
        } else {
            // No stretch → old behavior, single height
            h = 0.0;
        }
        
        // Apply motion curve to h
        double t = h;
        switch (world.motionCurve()) {
            case EASE_IN -> t = t * t;
            case EASE_OUT -> t = 1.0 - (1.0 - t) * (1.0 - t);
            case EASE_IN_OUT -> {
                if (t < 0.5) {
                    t = 2.0 * t * t;
                } else {
                    t = 1.0 - Math.pow(-2.0 * t + 2.0, 2.0) / 2.0;
                }
            }
            case LINEAR -> {} // unchanged
        }
        
        // Compute Y from minY to maxY using eased t
        double y = minY + (maxY > minY ? t * (maxY - minY) : 0.0);
        
        // 2. Radius: apply Spread Start/End
        double radius = world.radius();
        double spreadStart = world.spreadStart();
        double spreadEnd = world.spreadEnd();
        
        double spreadFactor = Mth.lerp(t, spreadStart, spreadEnd);
        double effectiveRadius = radius * spreadFactor;
        
        // 3. Angle: use existing logic (orbit angle based on time and index)
        // Similar to halo: base angle rotates over time, plus per-particle offset
        // Use tickCount equivalent: timeSeconds * 20.0
        double baseAngle = ((timeSeconds * 20.0) * 0.15) % (Math.PI * 2.0);
        double angle = baseAngle + (Math.PI * 2.0 * index / Math.max(1, world.count()));
        
        // 4. Start with base horizontal circle in XZ
        double px = Math.cos(angle) * effectiveRadius;
        double py = 0.0;
        double pz = Math.sin(angle) * effectiveRadius;
        
        // 5. Apply rotationMode
        switch (world.rotationMode()) {
            case VERTICAL_X -> {
                px = 0.0;
                py = Math.sin(angle) * effectiveRadius;
                pz = Math.cos(angle) * effectiveRadius;
            }
            case VERTICAL_Z -> {
                px = Math.cos(angle) * effectiveRadius;
                py = Math.sin(angle) * effectiveRadius;
                pz = 0.0;
            }
            case HORIZONTAL -> {
                // Keep px, pz as circle in XZ, py = 0
            }
        }
        
        // 6. Apply tilt (rotation around Z axis)
        float tiltDeg = world.tiltDegrees();
        if (tiltDeg != 0.0F) {
            double tilt = Math.toRadians(tiltDeg);
            double ny = py * Math.cos(tilt) - pz * Math.sin(tilt);
            double nz = py * Math.sin(tilt) + pz * Math.cos(tilt);
            py = ny;
            pz = nz;
        }
        
        // Combine with vertical Y we computed
        py += y;
        
        // 7. Apply Offset X/Y/Z
        px += world.offsetX();
        py += world.offsetY();
        pz += world.offsetZ();
        
        return new WorldSample(px, py, pz);
    }
    
    // ------------------------------- New World Layer Styles -------------------------------

    // Cape-specific constants for height-based speed computation
    private static final float CAPE_MIN_HEIGHT_BLOCKS = 0.5f;
    private static final float CAPE_MAX_HEIGHT_BLOCKS = 8.0f;

    // Rough estimate of how long a vanilla flame particle lives in seconds.
    // Exact value doesn't need to be perfect; this just sets the scale.
    private static final float ESTIMATED_FLAME_LIFETIME_SEC = 1.6f;

    /**
     * Computes vertical speed for cape-style particles.
     * Reinterprets lifespan as desired max height in blocks, and computes speed from that.
     * Uses params.speed() as a multiplier for fine-tuning.
     */
    private static double computeCapeVerticalSpeed(ParticleRuntimeParams params) {
        // Interpret lifespan as desired max height in blocks for cape
        float desiredHeight = Mth.clamp(
                (float) params.lifespan(), // lifespan is already in seconds in params, but we reinterpret as blocks
                CAPE_MIN_HEIGHT_BLOCKS,
                CAPE_MAX_HEIGHT_BLOCKS
        );

        // Base speed needed to reach desiredHeight over the vanilla flame lifetime
        float baseSpeed = desiredHeight / ESTIMATED_FLAME_LIFETIME_SEC;

        // Use JSON "speed" as an optional multiplier for fine tuning
        float speedMultiplier = (float) params.speed();
        if (speedMultiplier <= 0.0f) {
            speedMultiplier = 1.0f;
        }

        float finalSpeed = baseSpeed * speedMultiplier;

        // Clamp to existing global safety range for speeds
        finalSpeed = Mth.clamp(finalSpeed, 0.0f, 2.0f);

        return finalSpeed;
    }

    // Flame cape: a straight horizontal row of emitters behind the player.
    private static void renderCape(ClientLevel level, AbstractClientPlayer player,
                                    ParticleOptions particle, ParticleProfiles.WorldLayerConfig layer,
                                    ParticleRuntimeParams params, ResourceLocation cosmeticId) {
        // Base position and orientation
        Vec3 basePos = player.position();
        Vec3 look = player.getLookAngle();
        Vec3 forward = new Vec3(look.x, 0.0, look.z).normalize();
        // Perpendicular to forward, on XZ plane
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x).normalize();

        // How wide the bar is (half-width in blocks)
        double halfWidth = Mth.clamp(layer.radius(), 0.05, 0.9);
        // How many emitters along the bar
        int emitters = params.count();

        // Height: treat height_factor as [0..1] from feet → shoulders
        double feetY = player.getY();
        double shouldersY = player.getY() + player.getBbHeight() * 0.9;
        float h = Mth.clamp(layer.heightFactor(), 0.0f, 1.2f); // allow a bit above shoulders
        double backHeight = Mth.lerp(h, feetY, shouldersY) - player.getY();

        // How far behind the player the bar is
        double backOffset = 0.4;

        // Small random jitter so it doesn't look perfectly rigid
        RandomSource rand = player.getRandom();

        // Compute cape-specific vertical speed (height-based) once per spawn cycle
        double baseVy = computeCapeVerticalSpeed(params);

        // Debug logging for flame_cape_blended (disabled by default)
        if (DEBUG_COSMETICS_RENDERER && cosmeticId != null && cosmeticId.getPath().contains("flame_cape_blended") && tickCounter % 20 == 0) {
            float desiredHeight = Mth.clamp((float) params.lifespan(), CAPE_MIN_HEIGHT_BLOCKS, CAPE_MAX_HEIGHT_BLOCKS);
            float baseSpeed = desiredHeight / ESTIMATED_FLAME_LIFETIME_SEC;
            float speedMultiplier = (float) params.speed();
            if (speedMultiplier <= 0.0f) speedMultiplier = 1.0f;
            float finalSpeed = (float) baseVy;
            CosmeticsLite.LOGGER.info("[cosmeticslite] flame_cape_blended cape speed: desiredHeight={} blocks, speedMultiplier={}, baseSpeed={}, finalSpeed={}",
                desiredHeight, speedMultiplier, baseSpeed, finalSpeed);
        }

        for (int i = 0; i < emitters; i++) {
            // t in [0,1] across the bar
            double t = (emitters <= 1) ? 0.5 : (double) i / (emitters - 1);
            // Map t → [-1,1] and scale by halfWidth
            double side = (t - 0.5) * 2.0 * halfWidth;

            // Base emitter position on a straight line behind the player
            Vec3 emitterPos = basePos
                .subtract(forward.scale(backOffset))   // push behind
                .add(right.scale(side))                // left/right along the bar
                .add(0.0, backHeight, 0.0);           // set height

            // One particle per emitter per tick (fast emission)
            // Small jitter so the line feels alive
            double jx = (rand.nextDouble() - 0.5) * 0.05;
            double jy = (rand.nextDouble() - 0.5) * 0.02;
            double jz = (rand.nextDouble() - 0.5) * 0.05;

            // Upwards drift with slight backwards pull - use cape-specific height-based speed
            double vy = baseVy + (rand.nextDouble() - 0.5) * 0.01;
            double vx = -forward.x * 0.03;
            double vz = -forward.z * 0.03;

            level.addParticle(
                particle,
                emitterPos.x + jx,
                emitterPos.y + jy,
                emitterPos.z + jz,
                vx,
                vy,
                vz
            );
        }
    }

    private static void renderGround(ClientLevel level, AbstractClientPlayer player,
                                     ParticleOptions particle, ParticleProfiles.WorldLayerConfig layer,
                                     ParticleRuntimeParams params) {
        RandomSource random = player.getRandom();
        Vec3 basePos = player.position();
        double radius = Mth.clamp(layer.radius(), 0.05, 2.0);
        int count = params.count();
        double y = basePos.y + 0.05;

        for (int i = 0; i < count; i++) {
            double angle = (2.0 * Math.PI * i) / count;
            double ox = Math.cos(angle) * radius;
            double oz = Math.sin(angle) * radius;
            Vec3 pos = basePos.add(ox, 0, oz);

            double vy = params.speed() + (random.nextDouble() - 0.5) * 0.01;

            level.addParticle(particle,
                pos.x, y, pos.z,
                (random.nextDouble() - 0.5) * 0.02,
                vy,
                (random.nextDouble() - 0.5) * 0.02);
        }
    }

    private static void renderWings(ClientLevel level, AbstractClientPlayer player,
                                     ParticleOptions particle, ParticleProfiles.WorldLayerConfig layer,
                                     ParticleRuntimeParams params) {
        RandomSource random = player.getRandom();
        Vec3 basePos = player.position();
        Vec3 f = forward(player);
        Vec3 r = right(player);
        double span = Mth.clamp(layer.radius(), 0.05, 2.0);          // wing length
        double height = Mth.clamp(layer.heightFactor(), 0.0f, 2.0f);  // vertical span
        int segments = params.count();
        double backOffset = 0.4 * span;
        double centerY = basePos.y + player.getBbHeight() * 0.7;

        for (int side = -1; side <= 1; side += 2) { // -1 = left, 1 = right
            for (int i = 0; i < segments; i++) {
                double t = segments <= 1 ? 0.5 : (double) i / (segments - 1);

                // t in [0,1] → arc from shoulder to tip
                double angle = (t - 0.5) * Math.PI * 0.7; // slight curve
                double along = t * span;
                double up    = Math.sin(angle) * height * player.getBbHeight();

                Vec3 wingBase = basePos
                    .subtract(f.scale(backOffset))
                    .add(r.scale(side * 0.4)); // left/right from spine

                Vec3 pos = wingBase
                    .add(r.scale(side * along))      // along wing
                    .add(0, up + centerY - basePos.y, 0);

                double vy = params.speed() + (random.nextDouble() - 0.5) * 0.01;

                level.addParticle(particle,
                    pos.x, pos.y, pos.z,
                    0.0,
                    vy,
                    0.0);
            }
        }
    }

    private static void renderBelt(ClientLevel level, AbstractClientPlayer player,
                                    ParticleOptions particle, ParticleProfiles.WorldLayerConfig layer,
                                    ParticleRuntimeParams params) {
        RandomSource random = player.getRandom();
        Vec3 basePos = player.position();
        double radius = Mth.clamp(layer.radius(), 0.05, 2.0);
        int count = params.count();
        double y = basePos.y + player.getBbHeight() * 0.55;
        double spin = player.tickCount * 0.15;

        for (int i = 0; i < count; i++) {
            double angle = (2.0 * Math.PI * i) / count + spin;
            double ox = Math.cos(angle) * radius;
            double oz = Math.sin(angle) * radius;
            Vec3 pos = basePos.add(ox, 0, oz);

            double vy = params.speed();

            level.addParticle(particle,
                pos.x, y, pos.z,
                0.0,
                vy,
                0.0);
        }
    }

    private static void renderSpiral(ClientLevel level, AbstractClientPlayer player,
                                      ParticleOptions particle, ParticleProfiles.WorldLayerConfig layer,
                                      ParticleRuntimeParams params) {
        RandomSource random = player.getRandom();
        Vec3 basePos = player.position();
        double radius = Mth.clamp(layer.radius(), 0.05, 2.0);
        double height = Mth.clamp(layer.heightFactor(), 0.0f, 2.0f) * player.getBbHeight();
        int count = params.count();
        double baseY = basePos.y + 0.2;
        double turns = 2.0; // number of wraps up the body
        double timeOffset = player.tickCount * 0.05;

        for (int i = 0; i < count; i++) {
            double t = (double) i / count;                 // 0..1 along spiral
            double angle = t * turns * 2.0 * Math.PI + timeOffset;
            double y = baseY + t * height;

            double ox = Math.cos(angle) * radius;
            double oz = Math.sin(angle) * radius;
            Vec3 pos = basePos.add(ox, 0, oz);

            double vy = params.speed();

            level.addParticle(particle,
                pos.x, y, pos.z,
                0.0,
                vy,
                0.0);
        }
    }
    
    // ------------------------------- New Render Methods Using Full WorldLayerDefinition -------------------------------
    
    /**
     * Render methods using full WorldLayerDefinition with all new fields.
     * These methods use sampleWorldLayerPosition() for consistent position computation.
     */
    
    private static void renderHaloWithFullDef(ClientLevel level, AbstractClientPlayer player,
                                              ParticleOptions particle, 
                                              com.pastlands.cosmeticslite.particle.config.WorldLayerDefinition world,
                                              ParticleRuntimeParams params) {
        RandomSource r = player.getRandom();
        Vec3 playerPos = player.position();
        int count = params.count();
        double timeSeconds = player.tickCount / 20.0; // Convert ticks to seconds
        double vy = params.speed() + (r.nextDouble() - 0.5) * 0.01;
        
        for (int i = 0; i < count; i++) {
            WorldSample sample = sampleWorldLayerPosition(world, timeSeconds, i, r);
            double sx = playerPos.x + sample.offsetX;
            double sy = playerPos.y + sample.offsetY;
            double sz = playerPos.z + sample.offsetZ;
            
            double vx = 0.0;
            double vz = 0.0;
            level.addParticle(particle, sx, sy, sz, vx, vy, vz);
        }
    }
    
    private static void renderColumnWithFullDef(ClientLevel level, AbstractClientPlayer player,
                                                ParticleOptions particle,
                                                com.pastlands.cosmeticslite.particle.config.WorldLayerDefinition world,
                                                ParticleRuntimeParams params) {
        RandomSource r = player.getRandom();
        Vec3 playerPos = player.position();
        int count = params.count();
        double timeSeconds = player.tickCount / 20.0;
        double vy = params.speed() + (r.nextDouble() - 0.5) * 0.01;
        
        // For column style, we still use the sampling for Y/offsets, but allow random XZ spread
        float radius = world.radius();
        for (int i = 0; i < count; i++) {
            WorldSample sample = sampleWorldLayerPosition(world, timeSeconds, i, r);
            // Add random spread in XZ for column style
            double ox = (r.nextDouble() - 0.5) * radius * 0.5;
            double oz = (r.nextDouble() - 0.5) * radius * 0.5;
            
            double sx = playerPos.x + sample.offsetX + ox;
            double sy = playerPos.y + sample.offsetY;
            double sz = playerPos.z + sample.offsetZ + oz;
            
            double vx = 0.0;
            double vz = 0.0;
            level.addParticle(particle, sx, sy, sz, vx, vy, vz);
        }
    }
    
    private static void renderTrailWithFullDef(ClientLevel level, AbstractClientPlayer player,
                                               ParticleOptions particle,
                                               com.pastlands.cosmeticslite.particle.config.WorldLayerDefinition world,
                                               ParticleRuntimeParams params) {
        RandomSource r = player.getRandom();
        Vec3 playerPos = player.position();
        int count = params.count();
        double timeSeconds = player.tickCount / 20.0;
        double vy = params.speed() + (r.nextDouble() - 0.5) * 0.01;
        
        // Trail style: cluster behind player, use Y from sampling
        double yaw = Math.toRadians(player.getYRot());
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        float radius = world.radius();
        
        for (int i = 0; i < count; i++) {
            WorldSample sample = sampleWorldLayerPosition(world, timeSeconds, i, r);
            // Trail specific: behind player with spread
            double ox = forwardX * radius * 0.5 + (r.nextDouble() - 0.5) * radius * 0.3;
            double oz = forwardZ * radius * 0.5 + (r.nextDouble() - 0.5) * radius * 0.3;
            
            double sx = playerPos.x + sample.offsetX + ox;
            double sy = playerPos.y + sample.offsetY - 0.5; // Slight downward offset for trail
            double sz = playerPos.z + sample.offsetZ + oz;
            
            double vx = 0.0;
            double vz = 0.0;
            level.addParticle(particle, sx, sy, sz, vx, vy, vz);
        }
    }
    
    private static void renderCapeWithFullDef(ClientLevel level, AbstractClientPlayer player,
                                              ParticleOptions particle,
                                              com.pastlands.cosmeticslite.particle.config.WorldLayerDefinition world,
                                              ParticleRuntimeParams params,
                                              ResourceLocation cosmeticId) {
        // Cape uses its own geometry but can benefit from Y/offsets
        Vec3 basePos = player.position();
        Vec3 look = player.getLookAngle();
        Vec3 forward = new Vec3(look.x, 0.0, look.z).normalize();
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x).normalize();
        
        double halfWidth = Mth.clamp(world.radius(), 0.05, 0.9);
        int emitters = params.count();
        
        // Use baseHeight for cape height positioning
        double feetY = player.getY();
        double shouldersY = player.getY() + player.getBbHeight() * 0.9;
        float h = Mth.clamp(world.baseHeight(), 0.0f, 1.2f);
        double backHeight = Mth.lerp(h, feetY, shouldersY) - player.getY();
        
        double backOffset = 0.4;
        RandomSource rand = player.getRandom();
        double baseVy = computeCapeVerticalSpeed(params);
        
        for (int i = 0; i < emitters; i++) {
            double t = (emitters <= 1) ? 0.5 : (double) i / (emitters - 1);
            double side = (t - 0.5) * 2.0 * halfWidth;
            
            Vec3 emitterPos = basePos
                .subtract(forward.scale(backOffset))
                .add(right.scale(side))
                .add(world.offsetX(), backHeight + world.offsetY(), world.offsetZ());
            
            double jx = (rand.nextDouble() - 0.5) * 0.05;
            double jy = (rand.nextDouble() - 0.5) * 0.02;
            double jz = (rand.nextDouble() - 0.5) * 0.05;
            
            double vy = baseVy + (rand.nextDouble() - 0.5) * 0.01;
            double vx = -forward.x * 0.03;
            double vz = -forward.z * 0.03;
            
            level.addParticle(particle,
                emitterPos.x + jx, emitterPos.y + jy, emitterPos.z + jz,
                vx, vy, vz);
        }
    }
    
    private static void renderGroundWithFullDef(ClientLevel level, AbstractClientPlayer player,
                                                ParticleOptions particle,
                                                com.pastlands.cosmeticslite.particle.config.WorldLayerDefinition world,
                                                ParticleRuntimeParams params) {
        RandomSource random = player.getRandom();
        Vec3 playerPos = player.position();
        int count = params.count();
        double timeSeconds = player.tickCount / 20.0;
        double vy = params.speed() + (random.nextDouble() - 0.5) * 0.01;
        
        // Ground style: use sampling but force Y to ground level
        for (int i = 0; i < count; i++) {
            WorldSample sample = sampleWorldLayerPosition(world, timeSeconds, i, random);
            double sx = playerPos.x + sample.offsetX;
            double sy = playerPos.y + 0.05 + world.offsetY(); // Ground level + offsetY
            double sz = playerPos.z + sample.offsetZ;
            
            level.addParticle(particle,
                sx, sy, sz,
                (random.nextDouble() - 0.5) * 0.02,
                vy,
                (random.nextDouble() - 0.5) * 0.02);
        }
    }
    
    private static void renderWingsWithFullDef(ClientLevel level, AbstractClientPlayer player,
                                               ParticleOptions particle,
                                               com.pastlands.cosmeticslite.particle.config.WorldLayerDefinition world,
                                               ParticleRuntimeParams params) {
        RandomSource random = player.getRandom();
        Vec3 basePos = player.position();
        Vec3 f = forward(player);
        Vec3 r = right(player);
        double span = Mth.clamp(world.radius(), 0.05, 2.0);
        double height = Mth.clamp(world.baseHeight() + world.heightStretch(), 0.0, 2.0) * player.getBbHeight();
        int segments = params.count();
        double backOffset = 0.4 * span;
        double centerY = basePos.y + player.getBbHeight() * 0.7;
        double vy = params.speed() + (random.nextDouble() - 0.5) * 0.01;
        
        for (int side = -1; side <= 1; side += 2) {
            for (int i = 0; i < segments; i++) {
                double t = segments <= 1 ? 0.5 : (double) i / (segments - 1);
                double angle = (t - 0.5) * Math.PI * 0.7;
                double along = t * span;
                double up = Math.sin(angle) * height;
                
                Vec3 wingBase = basePos
                    .subtract(f.scale(backOffset))
                    .add(r.scale(side * 0.4));
                
                Vec3 pos = wingBase
                    .add(r.scale(side * along))
                    .add(world.offsetX(), up + centerY - basePos.y + world.offsetY(), world.offsetZ());
                
                level.addParticle(particle,
                    pos.x, pos.y, pos.z,
                    0.0, vy, 0.0);
            }
        }
    }
    
    private static void renderBeltWithFullDef(ClientLevel level, AbstractClientPlayer player,
                                              ParticleOptions particle,
                                              com.pastlands.cosmeticslite.particle.config.WorldLayerDefinition world,
                                              ParticleRuntimeParams params) {
        Vec3 playerPos = player.position();
        int count = params.count();
        double timeSeconds = player.tickCount / 20.0;
        double vy = params.speed();
        
        // Belt style: horizontal ring at waist height, use sampling for Y/offsets
        double y = playerPos.y + player.getBbHeight() * 0.55 + world.offsetY();
        double spin = timeSeconds * 20.0 * 0.15;
        
        for (int i = 0; i < count; i++) {
            // Belt uses horizontal circle, compute angle-based positioning
            double angle = (2.0 * Math.PI * i) / count + spin;
            double radius = world.radius();
            // Belt uses middle of vertical range for spread
            double spreadFactor = Mth.lerp(0.5, world.spreadStart(), world.spreadEnd());
            double effectiveRadius = radius * spreadFactor;
            double ox = Math.cos(angle) * effectiveRadius;
            double oz = Math.sin(angle) * effectiveRadius;
            
            double sx = playerPos.x + ox + world.offsetX();
            double sy = y;
            double sz = playerPos.z + oz + world.offsetZ();
            
            level.addParticle(particle,
                sx, sy, sz,
                0.0, vy, 0.0);
        }
    }
    
    private static void renderSpiralWithFullDef(ClientLevel level, AbstractClientPlayer player,
                                                ParticleOptions particle,
                                                com.pastlands.cosmeticslite.particle.config.WorldLayerDefinition world,
                                                ParticleRuntimeParams params) {
        Vec3 playerPos = player.position();
        int count = params.count();
        double timeSeconds = player.tickCount / 20.0;
        double vy = params.speed();
        
        // Spiral style: vertical spiral, use sampling concepts but with spiral-specific geometry
        double baseY = playerPos.y + 0.2 + world.offsetY();
        double height = (world.baseHeight() + world.heightStretch()) * player.getBbHeight();
        double turns = 2.0;
        double timeOffset = timeSeconds * 20.0 * 0.05;
        double radius = world.radius();
        
        for (int i = 0; i < count; i++) {
            double t = (double) i / count;
            double spreadFactor = Mth.lerp(t, world.spreadStart(), world.spreadEnd());
            double effectiveRadius = radius * spreadFactor;
            double angle = t * turns * 2.0 * Math.PI + timeOffset;
            double y = baseY + t * height;
            
            // Apply rotation mode to spiral as well
            double ox, oy, oz;
            switch (world.rotationMode()) {
                case VERTICAL_X -> {
                    ox = 0.0;
                    oy = Math.sin(angle) * effectiveRadius;
                    oz = Math.cos(angle) * effectiveRadius;
                }
                case VERTICAL_Z -> {
                    ox = Math.cos(angle) * effectiveRadius;
                    oy = Math.sin(angle) * effectiveRadius;
                    oz = 0.0;
                }
                case HORIZONTAL -> {
                    ox = Math.cos(angle) * effectiveRadius;
                    oy = 0.0;
                    oz = Math.sin(angle) * effectiveRadius;
                }
                default -> {
                    ox = Math.cos(angle) * effectiveRadius;
                    oy = 0.0;
                    oz = Math.sin(angle) * effectiveRadius;
                }
            }
            
            // Apply tilt
            float tiltDeg = world.tiltDegrees();
            if (tiltDeg != 0.0F) {
                double tilt = Math.toRadians(tiltDeg);
                double ny = oy * Math.cos(tilt) - oz * Math.sin(tilt);
                double nz = oy * Math.sin(tilt) + oz * Math.cos(tilt);
                oy = ny;
                oz = nz;
            }
            
            double sx = playerPos.x + ox + world.offsetX();
            double sy = y + oy + world.offsetY();
            double sz = playerPos.z + oz + world.offsetZ();
            
            level.addParticle(particle,
                sx, sy, sz,
                0.0, vy, 0.0);
        }
    }
}
