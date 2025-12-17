package com.pastlands.cosmeticslite.preview;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.pastlands.cosmeticslite.ClientState;
import com.pastlands.cosmeticslite.CosmeticsChestScreen;
import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.client.state.ScreenState;
import com.pastlands.cosmeticslite.particle.ParticleProfiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Enhanced ParticlePreviewPane
 * Makes generic GUI particles behave like the actual equipped effects.
 *
 * Behavior change (requested):
 * - Particles render on the Particles tab as usual.
 * - If a particle is EQUIPPED, it keeps rendering across all tabs.
 * - If no particle is equipped and the current tab is not "particles", nothing renders.
 *
 * Forge 47.4.0 • MC 1.20.1 • Java 17
 */
public final class ParticlePreviewPane {
    private static final Logger LOGGER = LogUtils.getLogger();

    // ---- Bounds ----
    private int l, t, r, b;

    // ---- State ----
    private boolean enabled = true;

    // Default source: equipped particle on the client.
    private Supplier<ResourceLocation> effectSource = EquippedParticleSource::get;

    private final List<GuiParticle> particles = new ArrayList<>();
    private ResourceLocation currentEffect = null;
    private int spawnTicker = 0;

    // Multi-layer behavior tracking
    private List<EffectBehavior> activeBehaviors = List.of(EffectBehavior.DEFAULT);

    // World layer tracking for shape-aware preview
    @org.jetbrains.annotations.Nullable
    private ParticleProfiles.ParticleProfile currentProfile;
    private List<ParticleProfiles.WorldLayerConfig> currentWorldLayers = List.of();

    // Spawn & capacity tunables
    private static final int MAX_PARTICLES = 64;
    private static final int WELL_PAD = 6;
    private static final int PRIME_ON_CHANGE = 6;

    // Preview anchor constants (tuned to match mannequin position in preview pane)
    // These are relative to the preview pane bounds, calculated dynamically
    private static final float HEAD_OFFSET_Y = -20.0f;
    private static final float FEET_OFFSET_Y = 25.0f;

    private static final Random RNG = new Random();

    /**
     * Anchor point for positioning particles relative to the mannequin.
     */
    private static final class Anchor {
        final float x;
        final float y;
        Anchor(float x, float y) { this.x = x; this.y = y; }
    }

    private Anchor headAnchor() {
        // Center of preview pane, adjusted for mannequin head position
        float cx = (l + r) * 0.5f;
        float cy = (t + b) * 0.5f + 6f; // Match the cy used in spawnGuiParticle
        return new Anchor(cx, cy + HEAD_OFFSET_Y);
    }

    private Anchor feetAnchor() {
        // Center of preview pane, adjusted for mannequin feet position
        float cx = (l + r) * 0.5f;
        float cy = (t + b) * 0.5f + 6f; // Match the cy used in spawnGuiParticle
        return new Anchor(cx, cy + FEET_OFFSET_Y);
    }

    public ParticlePreviewPane() {}

    // ----------------------------- Configuration -----------------------------

    /** Set the preview rectangle. Call whenever the layout is (re)computed. */
    public void setBounds(int left, int top, int right, int bottom) {
        this.l = left;
        this.t = top;
        this.r = right;
        this.b = bottom;
    }

    /** Enable/disable updates and rendering without reallocating. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            particles.clear();
            currentEffect = null;
            spawnTicker = 0;
        }
    }

    /**
     * Override the effect source. By default it uses the equipped particle (EquippedParticleSource::get).
     * You may pass null to restore the default.
     */
    public void setEffectSource(Supplier<ResourceLocation> supplier) {
        this.effectSource = (supplier != null) ? supplier : EquippedParticleSource::get;
    }

    /** Clears all particles and resets the internal effect cache. */
    public void reset() {
        particles.clear();
        currentEffect = null;
        spawnTicker = 0;
        activeBehaviors = List.of(EffectBehavior.DEFAULT);
        currentProfile = null;
        currentWorldLayers = List.of();
    }

    // ------------------------------- Lifetime -------------------------------

    /** Advance timers, spawn new particles, and prune dead ones. Call once per tick. */
    public void tick() {
        if (!enabled) return;

        // Only render if:
        // - We are inside the cosmetics GUI, AND
        //   - the active tab is "particles", OR
        //   - an effect is actually equipped (so it persists across tabs)
        if (!shouldRenderNow()) {
            particles.clear();
            return;
        }

        // Resolve the live effect (null means "show nothing")
        ResourceLocation live = effectSource != null ? effectSource.get() : null;

        if (!Objects.equals(live, currentEffect)) {
            currentEffect = live;
            activeBehaviors = resolveBehaviors(currentEffect);
            updateProfileFor(currentEffect);
            particles.clear();
            spawnTicker = 0;

            // Prime a few dots immediately so it feels responsive on change
            if (currentEffect != null && !isAir(currentEffect)) {
                Minecraft mc = Minecraft.getInstance();
                int tick = (mc.player != null) ? mc.player.tickCount : 0;
                
                if (!currentWorldLayers.isEmpty()) {
                    // Prime using world layer shapes
                    for (ParticleProfiles.WorldLayerConfig layer : currentWorldLayers) {
                        int count = Math.min(2, layer.count()); // Just a couple per layer for priming
                        EffectBehavior behavior = activeBehaviors.isEmpty() 
                            ? EffectBehavior.DEFAULT 
                            : activeBehaviors.get(0);
                        
                        for (int i = 0; i < count && particles.size() < MAX_PARTICLES; i++) {
                            GuiParticle p = createGuiParticleBaseFor(currentEffect, behavior);
                            applyWorldStyleToSpawn(layer, p, i, count, tick, currentEffect);
                            particles.add(p);
                        }
                    }
                } else {
                    // Fallback to behavior-based priming
                    for (int i = 0; i < PRIME_ON_CHANGE; i++) {
                        EffectBehavior behavior = activeBehaviors.get(RNG.nextInt(activeBehaviors.size()));
                        spawnGuiParticle(currentEffect, behavior);
                    }
                }
            }
        }

        if (currentEffect != null && !isAir(currentEffect)) {
            Minecraft mc = Minecraft.getInstance();
            int tick = (mc.player != null) ? mc.player.tickCount : 0;

            if (!currentWorldLayers.isEmpty()) {
                // World-aware shape: use each world layer
                if (spawnTicker % 60 == 0) { // Log once per 3 seconds
                    CosmeticsLite.LOGGER.debug(
                        "[cosmeticslite] Preview world spawn: cosmeticId={}, layers={}",
                        currentEffect,
                        currentWorldLayers.size()
                    );
                }
                
                // Spawn particles periodically based on behavior spawn intervals
                for (ParticleProfiles.WorldLayerConfig layer : currentWorldLayers) {
                    // Use first behavior for spawn rate and other properties
                    EffectBehavior behavior = activeBehaviors.isEmpty() 
                        ? EffectBehavior.DEFAULT 
                        : activeBehaviors.get(0);
                    
                    int spawnInterval = behavior.spawnRate;
                    if (spawnInterval <= 0) {
                        continue;
                    }
                    
                    // Only spawn on the right tick interval
                    if (spawnTicker % spawnInterval != 0) {
                        continue;
                    }
                    
                    // Clamp to keep preview from exploding with particles
                    int count = Math.max(1, Math.min(layer.count(), 8));

                    for (int i = 0; i < count; i++) {
                        if (particles.size() >= MAX_PARTICLES) {
                            break;
                        }
                        
                        GuiParticle p = createGuiParticleBaseFor(currentEffect, behavior);
                        applyWorldStyleToSpawn(layer, p, i, count, tick, currentEffect);
                        particles.add(p);
                    }
                }
                spawnTicker++;
            } else {
                // Existing behavior-only spawn (no world profile)
                for (EffectBehavior behavior : activeBehaviors) {
                    int spawnInterval = behavior.spawnRate;
                    if (spawnInterval <= 0) {
                        continue;
                    }

                    int attempts = Math.max(1, Math.round(behavior.weight));
                    for (int i = 0; i < attempts; i++) {
                        if ((spawnTicker + i) % spawnInterval == 0 && particles.size() < MAX_PARTICLES) {
                            spawnGuiParticle(currentEffect, behavior);
                        }
                    }
                }
                spawnTicker++;
            }
        }

        // Advance/prune particles with behavior-specific physics
        for (int i = particles.size() - 1; i >= 0; i--) {
            GuiParticle p = particles.get(i);
            applyPhysics(p, p.behavior);
            p.life--;

            if (p.life <= 0 || isOutOfBounds(p)) {
                particles.remove(i);
            }
        }
    }

    /** Draw the sandbox dots. Call during render after the preview frame is drawn. */
    public void render(GuiGraphics g) {
        if (!enabled) return;
        if (!shouldRenderNow()) return;
        if (currentEffect == null || isAir(currentEffect)) return;

        for (GuiParticle p : particles) {
            renderParticle(g, p, p.behavior);
        }
    }

    // ------------------------------- Tab gating -------------------------------

/**
 * Render if:
 * - We are inside CosmeticsChestScreen, and
 * - ScreenState active tab == particles, OR
 * - An effect is actually equipped (persistent across tabs)
 */
private boolean shouldRenderNow() {
    if (!(Minecraft.getInstance().screen instanceof CosmeticsChestScreen chest)) return false;

    ScreenState state = chest.getScreenState();
    String active = (state != null) ? state.getActiveType() : null;

    if (ScreenState.TYPE_PARTICLES.equals(active)) {
        return true; // always show on the particles tab
    }

    // Otherwise, show only if an effect is actually equipped
    ResourceLocation equipped = EquippedParticleSource.get();
    return equipped != null && !isAir(equipped);
}


    // ------------------------------- Effect Analysis -------------------------------

    /** Get the actual effect name from the cosmetic definition's properties (not required, kept for completeness). */
    private String getActualEffectName(ResourceLocation cosmeticId) {
        try {
            var def = com.pastlands.cosmeticslite.CosmeticsRegistry.get(cosmeticId);
            if (def != null && def.properties() != null) {
                String effect = def.properties().get("effect");
                if (effect != null && !effect.isEmpty()) {
                    int colonIndex = effect.lastIndexOf(':');
                    if (colonIndex >= 0 && colonIndex < effect.length() - 1) {
                        return effect.substring(colonIndex + 1);
                    }
                    return effect;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Resolve behaviors for an effect. First checks ParticleProfiles, then falls back to path-based analysis.
     */
    private List<EffectBehavior> resolveBehaviors(ResourceLocation effectId) {
        if (effectId == null) return List.of(EffectBehavior.DEFAULT);

        // Try to get profile first
        ParticleProfiles.ParticleProfile profile = ParticleProfiles.get(effectId);
        if (profile != null && !profile.layers().isEmpty()) {
            CosmeticsLite.LOGGER.info("[cosmeticslite] ParticlePreviewPane using profile for {} with {} layer(s)",
                effectId, profile.layers().size());
            return profile.layers().stream()
                    .map(this::toEffectBehavior)
                    .toList();
        }

        // Fallback to path-based analysis (single behavior)
        return List.of(analyzeEffect(effectId));
    }

    /**
     * Update the current profile and world layers for the given cosmetic ID.
     * Uses the same clean resolution flow as ClientCosmeticRenderer: registry → catalog → fallback.
     */
    private void updateProfileFor(ResourceLocation cosmeticId) {
        if (cosmeticId == null || isAir(cosmeticId)) {
            this.currentProfile = null;
            this.currentWorldLayers = List.of();
            return;
        }
        
        // Use clean resolution flow (preview-live → registry → catalog → fallback)
        var resolution = com.pastlands.cosmeticslite.particle.ParticleProfileResolver.resolve(cosmeticId);
        
        // Branch 0: preview-live mode - use live working copy from Particle Lab
        if ("preview-live".equals(resolution.source()) && resolution.profile() != null) {
            this.currentProfile = resolution.profile();
            this.currentWorldLayers = resolution.profile().worldLayers();
            return;
        }
        
        // Branch 1: profile-json mode - use registry definition with world layers
        if ("profile-json".equals(resolution.source()) && resolution.profile() != null) {
            this.currentProfile = resolution.profile();
            this.currentWorldLayers = resolution.profile().worldLayers();
            return;
        }
        
        // Branch 2: simple-catalog mode - no profile needed
        if ("simple-catalog".equals(resolution.source())) {
            this.currentProfile = null;
            this.currentWorldLayers = List.of();
            return;
        }
        
        // Branch 3: fallback-default mode - no profile
        this.currentProfile = null;
        this.currentWorldLayers = List.of();
    }

    /**
     * Convert a GuiLayerConfig to an EffectBehavior for use in the preview pane.
     */
    private EffectBehavior toEffectBehavior(ParticleProfiles.GuiLayerConfig layer) {
        EffectBehavior.Movement movement = mapMovement(layer.movement());
        int[] colors = layer.colors().stream().mapToInt(i -> i).toArray();
        if (colors.length == 0) {
            colors = new int[]{0xFF4BA3FF}; // default blue
        }
        return new EffectBehavior(movement, colors, layer.lifespan(), layer.spawnInterval(), layer.size(), layer.speed(), layer.weight(), layer.previewScale());
    }

    /**
     * Map ParticleProfiles.Movement to EffectBehavior.Movement.
     * Handles aliases: ORBIT -> SWIRL, PULSE -> BURST
     */
    private EffectBehavior.Movement mapMovement(ParticleProfiles.Movement profileMovement) {
        return switch (profileMovement) {
            case FLOAT_UP -> EffectBehavior.Movement.FLOAT_UP;
            case BOUNCE_UP -> EffectBehavior.Movement.BOUNCE_UP;
            case DRIFT_UP -> EffectBehavior.Movement.DRIFT_UP;
            case FLICKER_UP -> EffectBehavior.Movement.FLICKER_UP;
            case BURST -> EffectBehavior.Movement.BURST;
            case SWIRL -> EffectBehavior.Movement.SWIRL;
            case FALL_DOWN -> EffectBehavior.Movement.FALL_DOWN;
            case BUBBLE_POP -> EffectBehavior.Movement.BUBBLE_POP;
            case MUSICAL_FLOAT -> EffectBehavior.Movement.MUSICAL_FLOAT;
            case DEFAULT -> EffectBehavior.Movement.DEFAULT;
        };
    }


    private EffectBehavior analyzeEffect(ResourceLocation effectId) {
        if (effectId == null) return EffectBehavior.DEFAULT;

        String path = effectId.getPath().toLowerCase();

        // Hearts - float up gently, red/pink colors
        if (path.contains("heart")) {
            return new EffectBehavior(
                EffectBehavior.Movement.FLOAT_UP,
                new int[]{0xFFFF69B4, 0xFFFF1493, 0xFFFF6B9D}, // pink shades
                40, 2, 2, 1.5f, 1.0f, 1.0f
            );
        }

        // Happy villager - bounce up, green colors
        if (path.contains("happy_villager")) {
            return new EffectBehavior(
                EffectBehavior.Movement.BOUNCE_UP,
                new int[]{0xFF32CD32, 0xFF00FF00, 0xFF90EE90}, // green shades
                35, 3, 2, 1.2f, 1.0f, 1.0f
            );
        }

        // Angry villager - quick burst, red/dark colors
        if (path.contains("angry_villager")) {
            return new EffectBehavior(
                EffectBehavior.Movement.BURST,
                new int[]{0xFFDC143C, 0xFF8B0000, 0xFFB22222}, // red/dark red
                25, 1, 2, 2.0f, 1.0f, 1.0f
            );
        }

        // Flames - flicker up, orange/yellow
        if (path.contains("flame") || path.contains("fire")) {
            return new EffectBehavior(
                EffectBehavior.Movement.FLICKER_UP,
                new int[]{0xFFFF4500, 0xFFFF8C00, 0xFFFFD700}, // orange to yellow
                30, 2, 2, 1.8f, 1.0f, 1.0f
            );
        }

        // Soul flames - flicker up, blue colors
        if (path.contains("soul")) {
            return new EffectBehavior(
                EffectBehavior.Movement.FLICKER_UP,
                new int[]{0xFF00BFFF, 0xFF87CEEB, 0xFF4169E1}, // blue shades
                35, 2, 2, 1.5f, 1.0f, 1.0f
            );
        }

        // Smoke - drift up slowly, gray colors
        if (path.contains("smoke") || path.contains("ash")) {
            return new EffectBehavior(
                EffectBehavior.Movement.DRIFT_UP,
                new int[]{0xFF696969, 0xFF808080, 0xFFA9A9A9}, // gray shades
                50, 4, 2, 0.8f, 1.0f, 1.0f
            );
        }

        // Water/bubble effects - float up, blue colors
        if (path.contains("bubble") || path.contains("water") || path.contains("splash")) {
            return new EffectBehavior(
                EffectBehavior.Movement.FLOAT_UP,
                new int[]{0xFF00BFFF, 0xFF87CEEB, 0xFF4682B4}, // blue water colors
                45, 3, 2, 1.0f, 1.0f, 1.0f
            );
        }

        // Enchant - swirl around, purple/magic colors
        if (path.contains("enchant") || path.contains("portal")) {
            return new EffectBehavior(
                EffectBehavior.Movement.SWIRL,
                new int[]{0xFF9932CC, 0xFFBA55D3, 0xFF8A2BE2}, // purple shades
                40, 2, 2, 1.3f, 1.0f, 1.0f
            );
        }

        // Explosion - burst outward, white/yellow
        if (path.contains("explosion") || path.contains("flash")) {
            return new EffectBehavior(
                EffectBehavior.Movement.BURST,
                new int[]{0xFFFFFFFF, 0xFFFFFF00, 0xFFFFA500}, // white to orange
                20, 1, 2, 3.0f, 1.0f, 1.0f
            );
        }

        // Falling/dripping effects
        if (path.contains("dripping") || path.contains("falling")) {
            if (path.contains("lava")) {
                return new EffectBehavior(
                    EffectBehavior.Movement.FALL_DOWN,
                    new int[]{0xFFFF4500, 0xFFFF0000, 0xFFFF8C00}, // lava colors
                    60, 4, 2, 0.6f, 1.0f, 1.0f
                );
            } else {
                return new EffectBehavior(
                    EffectBehavior.Movement.FALL_DOWN,
                    new int[]{0xFF4169E1, 0xFF6495ED, 0xFF87CEEB}, // blue shades for water
                    60, 4, 2, 0.6f, 1.0f, 1.0f
                );
            }
        }

        // Lava effects
        if (path.contains("lava")) {
            return new EffectBehavior(
                EffectBehavior.Movement.BUBBLE_POP,
                new int[]{0xFFFF4500, 0xFFFF0000, 0xFFFF8C00}, // lava colors
                25, 2, 2, 2.0f, 1.0f, 1.0f
            );
        }

        // Crit effects - sharp burst
        if (path.contains("crit")) {
            return new EffectBehavior(
                EffectBehavior.Movement.BURST,
                new int[]{0xFFFFFFFF, 0xFFF0F8FF, 0xFFE6E6FA}, // white/silver
                15, 1, 2, 2.5f, 1.0f, 1.0f
            );
        }

        // Note effects - musical float
        if (path.contains("note")) {
            return new EffectBehavior(
                EffectBehavior.Movement.MUSICAL_FLOAT,
                new int[]{0xFF32CD32, 0xFF00FF00, 0xFFADFF2F, 0xFFFF69B4, 0xFF00BFFF}, // rainbow-ish
                50, 3, 2, 1.0f, 1.0f, 1.0f
            );
        }

        // Default behavior for unknown effects
        return EffectBehavior.DEFAULT;
    }

    // ------------------------------- Physics -------------------------------

    private void applyPhysics(GuiParticle p, EffectBehavior behavior) {
        switch (behavior.movement) {
            case FLOAT_UP -> {
                p.vy -= 0.008f; // gentle upward
                p.x += p.vx * 0.98f; // slight horizontal drift with damping
                p.y += p.vy;
            }
            case BOUNCE_UP -> {
                p.vy -= 0.012f; // stronger upward
                p.x += p.vx * 0.95f;
                p.y += p.vy;
                if (p.age % 10 == 0) p.vy += 0.005f; // slight bounce
            }
            case DRIFT_UP -> {
                p.vy -= 0.003f; // very slow upward
                p.x += p.vx + (RNG.nextFloat() - 0.5f) * 0.1f; // random drift
                p.y += p.vy;
            }
            case FLICKER_UP -> {
                p.vy -= 0.010f + (RNG.nextFloat() * 0.008f); // variable upward
                p.x += p.vx + (RNG.nextFloat() - 0.5f) * 0.2f; // flicker side to side
                p.y += p.vy;
            }
            case BURST -> {
                p.vx *= 0.96f;
                p.vy *= 0.96f;
                p.x += p.vx;
                p.y += p.vy;
            }
            case SWIRL -> {
                float cx = (l + r) * 0.5f;
                float cy = (t + b) * 0.5f;
                float dx = p.x - cx;
                float dy = p.y - cy;
                float angle = (float)Math.atan2(dy, dx) + 0.1f; // rotate
                float radius = (float)Math.sqrt(dx * dx + dy * dy) * 0.99f; // slowly spiral in
                p.x = cx + (float)Math.cos(angle) * radius;
                p.y = cy + (float)Math.sin(angle) * radius;
            }
            case FALL_DOWN -> {
                p.vy += 0.008f; // gravity
                p.x += p.vx * 0.98f;
                p.y += p.vy;
            }
            case BUBBLE_POP -> {
                p.vy -= 0.005f; // slight float
                p.x += p.vx * 0.90f; // more damping
                p.y += p.vy;
                if (p.age % 8 == 0) {
                    p.vx += (RNG.nextFloat() - 0.5f) * 0.3f;
                    p.vy -= 0.01f;
                }
            }
            case MUSICAL_FLOAT -> {
                p.vy -= 0.006f;
                p.x += p.vx + (float)Math.sin(p.age * 0.2f) * 0.1f;
                p.y += p.vy;
            }
            default -> {
                p.vy -= 0.005f;
                p.x += p.vx;
                p.y += p.vy;
            }
        }
        p.age++;
    }

    // ------------------------------- Spawning -------------------------------

    private void spawnGuiParticle(ResourceLocation effectId, EffectBehavior behavior) {
        float cx = (l + r) * 0.5f;
        float cy = (t + b) * 0.5f + 6f;

        GuiParticle p = new GuiParticle();

        // Spawn position based on movement type
        switch (behavior.movement) {
            case FALL_DOWN -> {
                // Spawn at top
                p.x = cx + (RNG.nextFloat() - 0.5f) * 40f;
                p.y = t + 10;
                p.vx = (RNG.nextFloat() - 0.5f) * 0.1f;
                p.vy = 0.02f;
            }
            case BURST, BUBBLE_POP -> {
                // Spawn at center, burst outward
                p.x = cx + (RNG.nextFloat() - 0.5f) * 8f;
                p.y = cy + (RNG.nextFloat() - 0.5f) * 8f;
                float angle = RNG.nextFloat() * (float)Math.PI * 2f;
                float speed = behavior.initialSpeed * (0.5f + RNG.nextFloat() * 0.5f);
                p.vx = (float)Math.cos(angle) * speed;
                p.vy = (float)Math.sin(angle) * speed;
            }
            case SWIRL -> {
                // Spawn at edge of circle
                float angle = RNG.nextFloat() * (float)Math.PI * 2f;
                float radius = 25f + RNG.nextFloat() * 10f;
                p.x = cx + (float)Math.cos(angle) * radius;
                p.y = cy + (float)Math.sin(angle) * radius * 0.6f;
                p.vx = 0;
                p.vy = 0;
            }
            default -> {
                // Default ring spawn
                float radius = 20f + RNG.nextFloat() * 6f;
                double ang = RNG.nextDouble() * Math.PI * 2.0;
                p.x = (float)(cx + Math.cos(ang) * radius);
                p.y = (float)(cy + Math.sin(ang) * radius * 0.55);
                p.vx = (float)((p.x - cx) * 0.02) + (RNG.nextFloat() - 0.5f) * 0.2f;
                p.vy = (float)((p.y - cy) * 0.02) + (RNG.nextFloat() - 0.5f) * 0.1f;
            }
        }

        // Use behavior settings for size, lifespan, and speed
        p.size = behavior.size;
        p.life = behavior.lifespan + RNG.nextInt(10);
        p.maxLife = p.life;
        p.age = 0;
        p.colors = behavior.colors;
        p.behavior = behavior;
        p.scale = behavior.previewScale;
        
        // Resolve sprite from the effect
        ResourceLocation effectLocation = resolveEffectLocation(effectId);
        p.sprite = resolveSprite(effectLocation);
        
        // Apply initial speed to velocity if applicable
        if (behavior.movement == EffectBehavior.Movement.BURST || behavior.movement == EffectBehavior.Movement.BUBBLE_POP) {
            // Speed is already applied in the spawn position logic
        } else {
            // Adjust velocity based on speed
            p.vx *= behavior.initialSpeed;
            p.vy *= behavior.initialSpeed;
        }

        particles.add(p);
    }

    /**
     * Create a base GuiParticle with behavior settings but without position/velocity.
     * Used for world-layer-based spawning where position comes from applyWorldStyleToSpawn.
     */
    private GuiParticle createGuiParticleBaseFor(ResourceLocation effectId, EffectBehavior behavior) {
        GuiParticle p = new GuiParticle();
        
        // Use behavior settings for size, lifespan, colors, etc.
        p.size = behavior.size;
        p.life = behavior.lifespan + RNG.nextInt(10);
        p.maxLife = p.life;
        p.age = 0;
        p.colors = behavior.colors;
        p.behavior = behavior;
        p.scale = behavior.previewScale;
        
        // Resolve sprite from the effect
        ResourceLocation effectLocation = resolveEffectLocation(effectId);
        p.sprite = resolveSprite(effectLocation);
        
        // Position and velocity will be set by applyWorldStyleToSpawn
        p.x = 0;
        p.y = 0;
        p.vx = 0;
        p.vy = 0;
        
        return p;
    }

    /**
     * Apply world layer style to set spawn position and initial velocity for a particle.
     */
    private void applyWorldStyleToSpawn(ParticleProfiles.WorldLayerConfig layer,
                                        GuiParticle p,
                                        int index,
                                        int count,
                                        int tickCount,
                                        ResourceLocation cosmeticId) {
        Anchor head = headAnchor();
        Anchor feet = feetAnchor();

        // Scale world radius/height into preview pixels
        float radiusPx = layer.radius() * 20.0f;       // tweak as needed
        float heightPx = layer.heightFactor() * 15.0f; // tweak as needed

        switch (layer.style()) {
            case "halo" -> {
                // Ring around the head, with a bit of rotation
                float baseAngle = tickCount * 0.03f;
                float angle = baseAngle + ((float) index / Math.max(1, count)) * (float) (2.0 * Math.PI);

                p.x = head.x + (float) Math.cos(angle) * radiusPx;
                p.y = head.y + (float) Math.sin(angle) * (radiusPx * 0.5f);

                // Tangential initial velocity for a "crown" feel
                p.vx = -(float) Math.sin(angle) * 0.10f;
                p.vy =  (float) Math.cos(angle) * 0.05f;
                
                // Debug log for Emerald Aura (happy_villager_blended) halo style
                if (tickCount % 120 == 0 && index == 0) { // Log once per 6 seconds, first particle only
                    ResourceLocation emeraldId = ResourceLocation.fromNamespaceAndPath("cosmeticslite", "particle/happy_villager_blended");
                    if (emeraldId.equals(cosmeticId)) {
                        CosmeticsLite.LOGGER.debug("[cosmeticslite] Preview applyWorldStyleToSpawn: halo style for Emerald Aura");
                    }
                }
            }

            case "column" -> {
                // Vertical band above head
                float t = (count <= 1) ? 0.5f : (index / (float) (count - 1));
                p.x = head.x;
                p.y = head.y - heightPx * 0.5f + heightPx * t;

                p.vx = 0.0f;
                p.vy = -0.04f; // gentle upward motion
            }

            case "trail" -> {
                // Trail behind / to the left of the player
                float sideOffset = radiusPx;
                float t = (count <= 1) ? 0.5f : (index / (float) (count - 1));

                p.x = head.x - sideOffset;
                p.y = feet.y - heightPx * 0.5f + heightPx * t;

                p.vx = -0.08f; // drifting further back
                p.vy = 0.00f;
            }

            case "cape" -> {
                // Cape sheet behind player
                int cols = Math.max(3, Math.round(layer.count() / 2.0f));
                int row = index / cols;
                int col = index % cols;
                float u = cols <= 1 ? 0.5f : (col / (float) (cols - 1));
                float v = (layer.count() / 2.0f) <= 1 ? 0.5f : (row / (float) (Math.max(1, Math.round(layer.count() / 2.0f)) - 1));
                float offsetSide = (u - 0.5f) * radiusPx * 2.0f;
                float offsetUp = (v - 0.5f) * heightPx;
                float backOffset = 0.6f * radiusPx;
                float midY = head.y + (head.y - feet.y) * 0.3f;
                
                p.x = head.x - backOffset + offsetSide;
                p.y = midY + offsetUp;
                p.vx = -0.02f;
                p.vy = layer.speedY() * 10.0f;
            }

            case "ground" -> {
                // Ring at feet
                float angle = ((float) index / Math.max(1, count)) * (float) (2.0 * Math.PI);
                p.x = head.x + (float) Math.cos(angle) * radiusPx;
                p.y = feet.y + 2.0f;
                p.vx = 0.0f;
                p.vy = layer.speedY() * 10.0f;
            }

            case "wings" -> {
                // Two curved arcs (left and right)
                int segments = Math.max(6, count);
                int side = (index < segments) ? -1 : 1;
                int wingIndex = (index < segments) ? index : (index - segments);
                float t = segments <= 1 ? 0.5f : (wingIndex / (float) (segments - 1));
                float angle = (t - 0.5f) * (float) Math.PI * 0.7f;
                float along = t * radiusPx;
                float up = (float) Math.sin(angle) * heightPx;
                float sideOffset = side * 0.4f * radiusPx;
                float backOffset = 0.4f * radiusPx;
                float centerY = head.y + (head.y - feet.y) * 0.2f;
                
                p.x = head.x - backOffset + sideOffset + side * along;
                p.y = centerY + up;
                p.vx = 0.0f;
                p.vy = layer.speedY() * 10.0f;
            }

            case "belt" -> {
                // Ring at waist height
                float spin = tickCount * 0.15f;
                float angle = ((float) index / Math.max(1, count)) * (float) (2.0 * Math.PI) + spin;
                float waistY = head.y + (feet.y - head.y) * 0.45f;
                p.x = head.x + (float) Math.cos(angle) * radiusPx;
                p.y = waistY;
                p.vx = 0.0f;
                p.vy = layer.speedY() * 10.0f;
            }

            case "spiral" -> {
                // Vertical spiral
                float t = (float) index / Math.max(1, count);
                float turns = 2.0f;
                float timeOffset = tickCount * 0.05f;
                float angle = t * turns * (float) (2.0 * Math.PI) + timeOffset;
                float baseY = feet.y + 5.0f;
                float y = baseY + t * heightPx;
                p.x = head.x + (float) Math.cos(angle) * radiusPx;
                p.y = y;
                p.vx = 0.0f;
                p.vy = layer.speedY() * 10.0f;
            }

            default -> {
                // Fallback: cluster at head
                p.x = head.x;
                p.y = head.y;
                p.vx = 0.0f;
                p.vy = -0.03f;
            }
        }
    }

    /**
     * Resolve the actual particle effect ResourceLocation from a cosmetic ID.
     * First checks profile world layers, then falls back to cosmetic definition properties.
     */
    private ResourceLocation resolveEffectLocation(ResourceLocation cosmeticId) {
        // Try profile first
        ParticleProfiles.ParticleProfile profile = ParticleProfiles.get(cosmeticId);
        if (profile != null && !profile.worldLayers().isEmpty()) {
            // Use the first world layer's effect
            return profile.worldLayers().get(0).effect();
        }
        
        // Fallback to cosmetic definition
        try {
            var def = com.pastlands.cosmeticslite.CosmeticsRegistry.get(cosmeticId);
            if (def != null && def.properties() != null) {
                String effect = def.properties().get("effect");
                if (effect != null && !effect.isEmpty()) {
                    return ResourceLocation.tryParse(effect);
                }
            }
        } catch (Exception ignored) {}
        
        return null;
    }

    /**
     * Resolve a TextureAtlasSprite for a particle effect ResourceLocation.
     * Returns null if the sprite cannot be resolved (fallback to colored square).
     * 
     * Note: ParticleEngine.spriteSets is not directly accessible in 1.20.1.
     * This is a placeholder that returns null for now, falling back to colored squares.
     * Future enhancement: use reflection or particle manager API to access sprite sets.
     */
    private TextureAtlasSprite resolveSprite(ResourceLocation effect) {
        if (effect == null) return null;
        
        // TODO: Implement sprite resolution using ParticleEngine API
        // For now, return null to use colored square fallback
        // This can be enhanced later with proper sprite lookup
        return null;
    }

    // ------------------------------- Rendering -------------------------------

    private void renderParticle(GuiGraphics g, GuiParticle p, EffectBehavior behavior) {
        // Choose color based on age/life
        int color = getParticleColor(p);

        // Size variations based on behavior
        int renderSize = p.size;
        if (behavior.movement == EffectBehavior.Movement.FLICKER_UP) {
            // Flickering size
            if (p.age % 4 < 2) renderSize = Math.max(1, renderSize - 1);
        } else if (behavior.movement == EffectBehavior.Movement.BURST) {
            // Shrink over time
            float lifeFactor = (float)p.life / p.maxLife;
            renderSize = Math.max(1, (int)(renderSize * lifeFactor));
        }

        // Apply preview scale
        float scale = (p.scale <= 0f) ? 1.0f : p.scale;
        renderSize = Math.max(1, Math.round(renderSize * scale));

        // Alpha based on remaining life
        float alpha = Math.min(1.0f, (float)p.life / Math.max(1, p.maxLife));
        int finalColor = applyAlpha(color, alpha);

        // Render sprite if available, otherwise fallback to colored square
        if (p.sprite != null) {
            blitParticleSprite(g, p.sprite, (int)p.x - renderSize, (int)p.y - renderSize,
                             (int)p.x + renderSize, (int)p.y + renderSize, finalColor);
        } else {
            g.fill((int)p.x - renderSize, (int)p.y - renderSize,
                   (int)p.x + renderSize, (int)p.y + renderSize, finalColor);
        }
    }

    /**
     * Render a particle sprite with tint color.
     */
    private void blitParticleSprite(GuiGraphics gfx, TextureAtlasSprite sprite,
                                   int x0, int y0, int x1, int y1, int tintColor) {
        // Extract color components
        int a = (tintColor >>> 24) & 0xFF;
        int r = (tintColor >>> 16) & 0xFF;
        int g = (tintColor >>> 8) & 0xFF;
        int b = tintColor & 0xFF;
        
        // Bind particle atlas texture
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_PARTICLES);
        
        // Set color tint
        RenderSystem.setShaderColor(
            r / 255.0f, g / 255.0f, b / 255.0f, a / 255.0f
        );
        
        // Enable blending
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        
        // Get sprite UVs and convert to pixel coordinates (particle atlas is typically 256x256)
        float u0 = sprite.getU0();
        float v0 = sprite.getV0();
        int atlasSize = 256;
        int uOffset = (int)(u0 * atlasSize);
        int vOffset = (int)(v0 * atlasSize);
        int renderWidth = x1 - x0;
        int renderHeight = y1 - y0;
        
        // Draw quad using GuiGraphics blit
        // Signature: blit(texture, x, y, uOffset, vOffset, width, height, textureWidth, textureHeight)
        gfx.blit(TextureAtlas.LOCATION_PARTICLES,
               x0, y0, uOffset, vOffset, renderWidth, renderHeight, atlasSize, atlasSize);
        
        // Reset color
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private int getParticleColor(GuiParticle p) {
        if (p.colors.length == 0) return 0xFF4BA3FF;

        // Cycle through colors based on age for some effects
        if (p.colors.length > 1) {
            int index = (p.age / 5) % p.colors.length;
            return p.colors[index];
        }

        return p.colors[0];
    }

    private int applyAlpha(int color, float alpha) {
        alpha = Math.max(0f, Math.min(1f, alpha));
        int a = (int)((color >>> 24) * alpha);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    // ------------------------------- Bounds Checking -------------------------------

    private boolean isOutOfBounds(GuiParticle p) {
        return p.x < l - 10 || p.x > r + 10 || p.y < t - 10 || p.y > b + 10;
    }

    private static boolean isAir(ResourceLocation id) {
        if (id == null) return true;
        return "minecraft".equals(id.getNamespace()) && "air".equals(id.getPath());
    }

    // ------------------------------- Effect Behavior Definition -------------------------------

    private static class EffectBehavior {
        enum Movement {
            FLOAT_UP, BOUNCE_UP, DRIFT_UP, FLICKER_UP, BURST, SWIRL,
            FALL_DOWN, BUBBLE_POP, MUSICAL_FLOAT, DEFAULT
        }

        final Movement movement;
        final int[] colors;
        final int lifespan;
        final int spawnRate;
        final int size;
        final int maxSize;
        final float initialSpeed;
        final float weight;
        final float previewScale;

        EffectBehavior(Movement movement, int[] colors, int lifespan, int spawnRate, int size, float initialSpeed, float weight, float previewScale) {
            this.movement = movement;
            this.colors = colors;
            this.lifespan = lifespan;
            this.spawnRate = spawnRate;
            this.size = size;
            this.maxSize = 3; // kept for backward compatibility
            this.initialSpeed = initialSpeed;
            this.weight = weight;
            this.previewScale = previewScale;
        }

        // Legacy constructor for backward compatibility
        EffectBehavior(Movement movement, int[] colors, int lifespan, int spawnRate, float initialSpeed) {
            this(movement, colors, lifespan, spawnRate, 2, initialSpeed, 1.0f, 1.0f);
        }

        // Legacy constructor with size
        EffectBehavior(Movement movement, int[] colors, int lifespan, int spawnRate, int size, float initialSpeed) {
            this(movement, colors, lifespan, spawnRate, size, initialSpeed, 1.0f, 1.0f);
        }

        // Legacy constructor with size and weight
        EffectBehavior(Movement movement, int[] colors, int lifespan, int spawnRate, int size, float initialSpeed, float weight) {
            this(movement, colors, lifespan, spawnRate, size, initialSpeed, weight, 1.0f);
        }

        static final EffectBehavior DEFAULT = new EffectBehavior(
            Movement.DEFAULT,
            new int[]{0xFF4BA3FF, 0xFF1B5FC2},
            30, 2, 2, 1.0f, 1.0f, 1.0f
        );
    }

    // ------------------------------- Particle Data -------------------------------

    private static final class GuiParticle {
        float x, y, vx, vy;
        int life, maxLife, age;
        int size;
        int[] colors;
        EffectBehavior behavior; // Each particle tracks its own behavior
        float scale = 1.0f; // preview scale multiplier
        net.minecraft.client.renderer.texture.TextureAtlasSprite sprite; // may be null for "old" square mode
    }
}
