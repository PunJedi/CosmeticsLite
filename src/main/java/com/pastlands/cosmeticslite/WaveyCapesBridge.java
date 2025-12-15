// src/main/java/com/pastlands/cosmeticslite/WaveyCapesBridge.java
package com.pastlands.cosmeticslite;

import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.logging.LogUtils;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Optional integration with WaveyCapes mod (tr7zw) for Forge 1.20.1.
 * 
 * <p><strong>IMPORTANT:</strong> WaveyCapes does NOT provide a public API for custom cape integration.
 * After thorough research, WaveyCapes works by automatically intercepting capes rendered through
 * Minecraft's vanilla cape layer. It hooks into the vanilla rendering pipeline and replaces
 * static cape rendering with physics-based animation.
 * 
 * <p><strong>Integration Strategy:</strong> To enable WaveyCapes physics for CosmeticsLite capes,
 * we use a hybrid rendering approach:
 * <ol>
 *   <li>Detect WaveyCapes presence via Forge ModList (soft dependency)</li>
 *   <li>When WaveyCapes is present: Temporarily inject our cape texture into the vanilla cape system</li>
 *   <li>Let vanilla rendering + WaveyCapes hooks handle the physics animation</li>
 *   <li>When WaveyCapes is absent: Use standard CosmeticsLite static rendering</li>
 * </ol>
 * 
 * <p><strong>Safety:</strong> All WaveyCapes interactions use reflection and are wrapped in try/catch.
 * Any failure gracefully falls back to static rendering. Zero hard dependencies.
 * 
 * @author Josh R. (PunJedi), Ava (ChatGPT)
 * @since 1.0.0
 */
public final class WaveyCapesBridge {
    
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String WAVEY_MOD_ID = "waveycapes";
    
    // Lazy-initialized state
    private static Boolean waveyCapesPresent = null;
    private static boolean reflectionFailed = false;
    
    /**
     * Reflection handle for {@link AbstractClientPlayer#playerInfo}.
     */
    private static Field playerInfoField = null;
    
    /**
     * Reflection handle for {@link PlayerInfo#textureLocations}.
     * 
     * <p>This is a {@code Map<MinecraftProfileTexture.Type, ResourceLocation>} that backs
     * {@link PlayerInfo#getCapeLocation()} and friends. We temporarily swap the
     * {@link MinecraftProfileTexture.Type#CAPE} entry.
     */
    private static Field textureLocationsField = null;
    
    // Temporary storage for original cape textures (for restoration after rendering)
    private static final Map<AbstractClientPlayer, ResourceLocation> originalCapes = new java.util.WeakHashMap<>();
    
    private WaveyCapesBridge() {
        // Utility class - no instantiation
    }
    
    /**
     * Checks if the WaveyCapes mod is loaded.
     * 
     * <p>This check is cached after the first call for performance.
     * 
     * @return {@code true} if WaveyCapes is present and reflection succeeded, {@code false} otherwise
     */
    public static boolean isWaveyCapesPresent() {
        if (waveyCapesPresent != null) {
            return waveyCapesPresent;
        }
        
        // Check if mod is loaded via Forge
        boolean modLoaded = ModList.get().isLoaded(WAVEY_MOD_ID);
        if (!modLoaded) {
            LOGGER.debug("[CosmeticsLite] WaveyCapes mod not detected. Using static cape rendering.");
            waveyCapesPresent = false;
            return false;
        }
        
        // Mod is loaded - attempt to initialize reflection
        LOGGER.info("[CosmeticsLite] WaveyCapes mod detected! Attempting integration...");
        
        boolean success = initializeReflection();
        waveyCapesPresent = success;
        
        if (success) {
            LOGGER.info("[CosmeticsLite] WaveyCapes integration initialized successfully!");
        } else {
            LOGGER.warn("[CosmeticsLite] WaveyCapes detected but reflection failed. Falling back to static rendering.");
        }
        
        return waveyCapesPresent;
    }
    
    /**
     * Attempts to initialize reflection access to the internal cape texture map.
     * 
     * <p>On 1.20.1, the cape texture is not stored directly on {@link AbstractClientPlayer},
     * but inside {@link PlayerInfo} in its {@code textureLocations} map, keyed by
     * {@link MinecraftProfileTexture.Type} (including {@link MinecraftProfileTexture.Type#CAPE}).
     * 
     * <p>This method establishes reflective handles to:
     * <ul>
     *   <li>{@link AbstractClientPlayer#playerInfo}</li>
     *   <li>{@link PlayerInfo#textureLocations}</li>
     * </ul>
     * 
     * <p>Uses {@link ObfuscationReflectionHelper} to work in both dev (mapped) and
     * production (reobfuscated) environments. Tries dev name first, then SRG name.
     * 
     * @return {@code true} if reflection succeeded, {@code false} otherwise
     */
    private static boolean initializeReflection() {
        if (reflectionFailed) {
            return false; // Don't retry if we already failed
        }
        
        try {
            // Access AbstractClientPlayer.playerInfo
            // ObfuscationReflectionHelper handles mapping in dev, but in reobfuscated builds
            // we need the SRG name. Try dev name first, then SRG name.
            String[] playerInfoNames = {"playerInfo", "f_108546_"};
            playerInfoField = null;
            for (String fieldName : playerInfoNames) {
                try {
                    playerInfoField = ObfuscationReflectionHelper.findField(
                            AbstractClientPlayer.class,
                            fieldName
                    );
                    break; // Success, stop trying
                } catch (Exception ignored) {
                    // Try next name
                }
            }
            
            if (playerInfoField == null) {
                throw new NoSuchFieldException("Could not find playerInfo field with any known name");
            }
            
            // Access PlayerInfo.textureLocations
            // Try dev name first, then SRG name for reobfuscated builds
            String[] textureLocationsNames = {"textureLocations", "f_105299_"};
            textureLocationsField = null;
            for (String fieldName : textureLocationsNames) {
                try {
                    textureLocationsField = ObfuscationReflectionHelper.findField(
                            PlayerInfo.class,
                            fieldName
                    );
                    break; // Success, stop trying
                } catch (Exception ignored) {
                    // Try next name
                }
            }
            
            if (textureLocationsField == null) {
                throw new NoSuchFieldException("Could not find textureLocations field with any known name");
            }
            
            LOGGER.debug("[CosmeticsLite] Successfully initialized PlayerInfo texture map reflection for WaveyCapes integration.");
            return true;
            
        } catch (Exception e) {
            // ObfuscationReflectionHelper.findField throws RuntimeException if field not found
            // Catch all exceptions to handle both dev and production failures gracefully
            LOGGER.debug("[CosmeticsLite] Could not access playerInfo/textureLocations fields. "
                    + "WaveyCapes integration disabled. Using static rendering.");
            reflectionFailed = true;
            return false;
        }
    }
    
    /**
     * Injects a CosmeticsLite cape texture into the vanilla cape system.
     * 
     * <p>This temporarily sets the player's cape texture so WaveyCapes can detect and animate it.
     * The original cape texture is stored for later restoration.
     * 
     * <p><strong>IMPORTANT:</strong> Must call {@link #restoreVanillaCape(AbstractClientPlayer)}
     * after rendering to restore the original state.
     * 
     * @param player      The player entity
     * @param capeTexture The CosmeticsLite cape texture to inject
     * @return {@code true} if injection succeeded, {@code false} if it failed
     */
    @SuppressWarnings("unchecked")
    public static boolean injectCapeToVanillaLayer(AbstractClientPlayer player, ResourceLocation capeTexture) {
        if (!isWaveyCapesPresent() || player == null || capeTexture == null) {
            return false;
        }
        
        if (playerInfoField == null || textureLocationsField == null) {
            // Reflection failed earlier, fall back
            return false;
        }
        
        try {
            // Get the PlayerInfo for this client player
            Object infoObj = playerInfoField.get(player);
            if (!(infoObj instanceof PlayerInfo info)) {
                LOGGER.debug("[CosmeticsLite] PlayerInfo was null or unexpected type. Skipping WaveyCapes injection.");
                return false;
            }
            
            // Access the internal texture map
            Map<MinecraftProfileTexture.Type, ResourceLocation> textureMap =
                    (Map<MinecraftProfileTexture.Type, ResourceLocation>) textureLocationsField.get(info);
            
            if (textureMap == null) {
                LOGGER.debug("[CosmeticsLite] PlayerInfo.textureLocations is null. Skipping WaveyCapes injection.");
                return false;
            }
            
            // Store the original cape texture for restoration (only if we haven't stored it before)
            // If we already have an entry, we're re-injecting on a subsequent frame, so don't overwrite the original
            if (!originalCapes.containsKey(player)) {
                ResourceLocation original = textureMap.get(MinecraftProfileTexture.Type.CAPE);
                originalCapes.put(player, original);
            }
            
            // Inject our custom cape texture into the vanilla cape slot
            textureMap.put(MinecraftProfileTexture.Type.CAPE, capeTexture);
            
            return true;
            
        } catch (Throwable t) {
            LOGGER.debug("[CosmeticsLite] Failed to inject cape texture for WaveyCapes: {}", t.getMessage());
            return false;
        }
    }
    
    /**
     * Restores the player's original vanilla cape texture after rendering.
     * 
     * <p>This cleans up after {@link #injectCapeToVanillaLayer(AbstractClientPlayer, ResourceLocation)}
     * by restoring the player's original cape state.
     * 
     * @param player The player entity
     */
    @SuppressWarnings("unchecked")
    public static void restoreVanillaCape(AbstractClientPlayer player) {
        if (!isWaveyCapesPresent() || player == null) {
            return;
        }
        
        if (playerInfoField == null || textureLocationsField == null) {
            return;
        }
        
        try {
            ResourceLocation original = originalCapes.remove(player);
            
            Object infoObj = playerInfoField.get(player);
            if (!(infoObj instanceof PlayerInfo info)) {
                return;
            }
            
            Map<MinecraftProfileTexture.Type, ResourceLocation> textureMap =
                    (Map<MinecraftProfileTexture.Type, ResourceLocation>) textureLocationsField.get(info);
            
            if (textureMap == null) {
                return;
            }
            
            if (original != null) {
                textureMap.put(MinecraftProfileTexture.Type.CAPE, original);
            } else {
                // No original cape -> remove custom entry entirely
                textureMap.remove(MinecraftProfileTexture.Type.CAPE);
            }
            
        } catch (Throwable t) {
            LOGGER.debug("[CosmeticsLite] Failed to restore original cape texture: {}", t.getMessage());
        }
    }
    
    /**
     * Determines if WaveyCapes should handle rendering for a specific cape.
     * 
     * <p>When this returns {@code true}, the calling code should:
     * <ol>
     *   <li>Call {@link #injectCapeToVanillaLayer(AbstractClientPlayer, ResourceLocation)}</li>
     *   <li>Skip custom rendering (let vanilla + WaveyCapes handle it)</li>
     *   <li>Call {@link #restoreVanillaCape(AbstractClientPlayer)} after rendering</li>
     * </ol>
     * 
     * @param player      The player entity
     * @param capeTexture The cape texture ResourceLocation
     * @return {@code true} if WaveyCapes should handle this cape, {@code false} to use static rendering
     */
    public static boolean shouldDelegateToVanilla(AbstractClientPlayer player, ResourceLocation capeTexture) {
        // Only delegate if WaveyCapes is present and we can inject the texture
        return isWaveyCapesPresent() && player != null && capeTexture != null;
    }
    
    /**
     * Resets the integration state.
     * 
     * <p>This can be called to force re-detection of WaveyCapes, useful for
     * debugging or if mods are loaded/unloaded dynamically.
     */
    public static void reset() {
        waveyCapesPresent = null;
        reflectionFailed = false;
        playerInfoField = null;
        textureLocationsField = null;
        originalCapes.clear();
        LOGGER.debug("[CosmeticsLite] WaveyCapes integration state reset.");
    }
    
    /**
     * Client-side event handlers for WaveyCapes integration.
     * Handles injection before rendering and restoration after rendering.
     */
    @Mod.EventBusSubscriber(modid = CosmeticsLite.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class EventHandler {
        private EventHandler() {}
        
        /**
         * Inject cape before player rendering starts (before vanilla cape layer).
         * This allows WaveyCapes to detect and animate the cape.
         * 
         * <p>Made stateless per frame: every render tick, we check the current cosmetics cape state.
         * If there's a cosmetics cape, we inject it. If there's no cosmetics cape, we restore
         * the original (if we had injected one previously).
         */
        @SubscribeEvent
        public static void onPreRenderPlayer(RenderPlayerEvent.Pre event) {
            if (!isWaveyCapesPresent()) return;
            
            if (!(event.getEntity() instanceof AbstractClientPlayer player)) return;
            
            // Resolve which cape to show (same logic as CosmeticCapeLayer)
            ResourceLocation ov = CosmeticsChestScreen.PreviewResolver.getOverride("capes", player);
            ResourceLocation id = (ov != null) ? ov : ClientState.getEquippedId(player, "capes");
            
            // Check if we have a cosmetics cape equipped
            if (isAir(id)) {
                // No cosmetics cape - restore original if we had injected one before
                if (originalCapes.containsKey(player)) {
                    restoreVanillaCape(player);
                    // Clean up: remove from map since we've restored
                    originalCapes.remove(player);
                }
                return;
            }
            
            // We have a cosmetics cape - get the texture
            CosmeticDef def = CosmeticsRegistry.get(id);
            if (def == null) {
                // Invalid cosmetic - restore original if we had one
                if (originalCapes.containsKey(player)) {
                    restoreVanillaCape(player);
                    originalCapes.remove(player);
                }
                return;
            }
            
            Map<String, String> props = def.properties();
            String texStr = (props == null) ? null : props.get("texture");
            if (texStr == null || texStr.isBlank()) {
                // No texture - restore original if we had one
                if (originalCapes.containsKey(player)) {
                    restoreVanillaCape(player);
                    originalCapes.remove(player);
                }
                return;
            }
            
            ResourceLocation tex = ResourceLocation.tryParse(texStr);
            if (tex == null) {
                // Invalid texture - restore original if we had one
                if (originalCapes.containsKey(player)) {
                    restoreVanillaCape(player);
                    originalCapes.remove(player);
                }
                return;
            }
            
            // Inject cape into vanilla system for WaveyCapes to detect
            // This will store the original if not already stored
            injectCapeToVanillaLayer(player, tex);
        }
        
        /**
         * Restore original cape after player rendering completes.
         * 
         * <p>Note: With the stateless per-frame approach, we only restore here if
         * we injected something this frame. The Pre event handles clearing when
         * the cosmetics cape is removed.
         */
        @SubscribeEvent
        public static void onPostRenderPlayer(RenderPlayerEvent.Post event) {
            if (!isWaveyCapesPresent()) return;
            
            if (!(event.getEntity() instanceof AbstractClientPlayer player)) return;
            
            // Only restore if we still have an entry (meaning we injected this frame)
            // If the cape was cleared, onPreRenderPlayer already restored and removed it
            if (originalCapes.containsKey(player)) {
                restoreVanillaCape(player);
                // Don't remove here - we need to keep it for the next frame's Pre event
                // The Pre event will remove it when the cosmetics cape is cleared
            }
        }
        
        private static boolean isAir(ResourceLocation id) {
            return id == null || ("minecraft".equals(id.getNamespace()) && "air".equals(id.getPath()));
        }
    }
}
