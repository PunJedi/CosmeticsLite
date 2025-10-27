package com.pastlands.cosmeticslite.preview;

import com.mojang.logging.LogUtils;
import com.pastlands.cosmeticslite.ClientState;
import com.pastlands.cosmeticslite.CosmeticsChestScreen;
import com.pastlands.cosmeticslite.client.state.ScreenState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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

    // Enhanced behavior tracking
    private EffectBehavior currentBehavior = EffectBehavior.DEFAULT;

    // Spawn & capacity tunables
    private static final int MAX_PARTICLES = 64;
    private static final int WELL_PAD = 6;
    private static final int PRIME_ON_CHANGE = 6;

    private static final Random RNG = new Random();

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
        currentBehavior = EffectBehavior.DEFAULT;
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
            currentBehavior = analyzeEffect(currentEffect);
            particles.clear();
            spawnTicker = 0;

            // Prime a few dots immediately so it feels responsive on change
            if (currentEffect != null && !isAir(currentEffect)) {
                for (int i = 0; i < PRIME_ON_CHANGE; i++) spawnGuiParticle(currentEffect, currentBehavior);
            }
        }

        if (currentEffect != null && !isAir(currentEffect)) {
            // Adjust spawn rate based on effect type
            int spawnInterval = currentBehavior.spawnRate;
            if ((spawnTicker++ % spawnInterval) == 0 && particles.size() < MAX_PARTICLES) {
                spawnGuiParticle(currentEffect, currentBehavior);
            }
        }

        // Advance/prune particles with behavior-specific physics
        for (int i = particles.size() - 1; i >= 0; i--) {
            GuiParticle p = particles.get(i);
            applyPhysics(p, currentBehavior);
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
            renderParticle(g, p, currentBehavior);
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

    private EffectBehavior analyzeEffect(ResourceLocation effectId) {
        if (effectId == null) return EffectBehavior.DEFAULT;

        String path = effectId.getPath().toLowerCase();

        // Hearts - float up gently, red/pink colors
        if (path.contains("heart")) {
            return new EffectBehavior(
                EffectBehavior.Movement.FLOAT_UP,
                new int[]{0xFFFF69B4, 0xFFFF1493, 0xFFFF6B9D}, // pink shades
                40, 2, 1.5f
            );
        }

        // Happy villager - bounce up, green colors
        if (path.contains("happy_villager")) {
            return new EffectBehavior(
                EffectBehavior.Movement.BOUNCE_UP,
                new int[]{0xFF32CD32, 0xFF00FF00, 0xFF90EE90}, // green shades
                35, 3, 1.2f
            );
        }

        // Angry villager - quick burst, red/dark colors
        if (path.contains("angry_villager")) {
            return new EffectBehavior(
                EffectBehavior.Movement.BURST,
                new int[]{0xFFDC143C, 0xFF8B0000, 0xFFB22222}, // red/dark red
                25, 1, 2.0f
            );
        }

        // Flames - flicker up, orange/yellow
        if (path.contains("flame") || path.contains("fire")) {
            return new EffectBehavior(
                EffectBehavior.Movement.FLICKER_UP,
                new int[]{0xFFFF4500, 0xFFFF8C00, 0xFFFFD700}, // orange to yellow
                30, 2, 1.8f
            );
        }

        // Soul flames - flicker up, blue colors
        if (path.contains("soul")) {
            return new EffectBehavior(
                EffectBehavior.Movement.FLICKER_UP,
                new int[]{0xFF00BFFF, 0xFF87CEEB, 0xFF4169E1}, // blue shades
                35, 2, 1.5f
            );
        }

        // Smoke - drift up slowly, gray colors
        if (path.contains("smoke") || path.contains("ash")) {
            return new EffectBehavior(
                EffectBehavior.Movement.DRIFT_UP,
                new int[]{0xFF696969, 0xFF808080, 0xFFA9A9A9}, // gray shades
                50, 4, 0.8f
            );
        }

        // Water/bubble effects - float up, blue colors
        if (path.contains("bubble") || path.contains("water") || path.contains("splash")) {
            return new EffectBehavior(
                EffectBehavior.Movement.FLOAT_UP,
                new int[]{0xFF00BFFF, 0xFF87CEEB, 0xFF4682B4}, // blue water colors
                45, 3, 1.0f
            );
        }

        // Enchant - swirl around, purple/magic colors
        if (path.contains("enchant") || path.contains("portal")) {
            return new EffectBehavior(
                EffectBehavior.Movement.SWIRL,
                new int[]{0xFF9932CC, 0xFFBA55D3, 0xFF8A2BE2}, // purple shades
                40, 2, 1.3f
            );
        }

        // Explosion - burst outward, white/yellow
        if (path.contains("explosion") || path.contains("flash")) {
            return new EffectBehavior(
                EffectBehavior.Movement.BURST,
                new int[]{0xFFFFFFFF, 0xFFFFFF00, 0xFFFFA500}, // white to orange
                20, 1, 3.0f
            );
        }

        // Falling/dripping effects
        if (path.contains("dripping") || path.contains("falling")) {
            if (path.contains("lava")) {
                return new EffectBehavior(
                    EffectBehavior.Movement.FALL_DOWN,
                    new int[]{0xFFFF4500, 0xFFFF0000, 0xFFFF8C00}, // lava colors
                    60, 4, 0.6f
                );
            } else {
                return new EffectBehavior(
                    EffectBehavior.Movement.FALL_DOWN,
                    new int[]{0xFF4169E1, 0xFF6495ED, 0xFF87CEEB}, // blue shades for water
                    60, 4, 0.6f
                );
            }
        }

        // Lava effects
        if (path.contains("lava")) {
            return new EffectBehavior(
                EffectBehavior.Movement.BUBBLE_POP,
                new int[]{0xFFFF4500, 0xFFFF0000, 0xFFFF8C00}, // lava colors
                25, 2, 2.0f
            );
        }

        // Crit effects - sharp burst
        if (path.contains("crit")) {
            return new EffectBehavior(
                EffectBehavior.Movement.BURST,
                new int[]{0xFFFFFFFF, 0xFFF0F8FF, 0xFFE6E6FA}, // white/silver
                15, 1, 2.5f
            );
        }

        // Note effects - musical float
        if (path.contains("note")) {
            return new EffectBehavior(
                EffectBehavior.Movement.MUSICAL_FLOAT,
                new int[]{0xFF32CD32, 0xFF00FF00, 0xFFADFF2F, 0xFFFF69B4, 0xFF00BFFF}, // rainbow-ish
                50, 3, 1.0f
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

        p.size = 1 + RNG.nextInt(behavior.maxSize);
        p.life = behavior.lifespan + RNG.nextInt(20);
        p.maxLife = p.life;
        p.age = 0;
        p.colors = behavior.colors;

        particles.add(p);
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

        // Alpha based on remaining life
        float alpha = Math.min(1.0f, (float)p.life / Math.max(1, p.maxLife));
        int finalColor = applyAlpha(color, alpha);

        g.fill((int)p.x - renderSize, (int)p.y - renderSize,
               (int)p.x + renderSize, (int)p.y + renderSize, finalColor);
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
        final int maxSize;
        final float initialSpeed;

        EffectBehavior(Movement movement, int[] colors, int lifespan, int spawnRate, float initialSpeed) {
            this.movement = movement;
            this.colors = colors;
            this.lifespan = lifespan;
            this.spawnRate = spawnRate;
            this.maxSize = 3;
            this.initialSpeed = initialSpeed;
        }

        static final EffectBehavior DEFAULT = new EffectBehavior(
            Movement.DEFAULT,
            new int[]{0xFF4BA3FF, 0xFF1B5FC2},
            30, 2, 1.0f
        );
    }

    // ------------------------------- Particle Data -------------------------------

    private static final class GuiParticle {
        float x, y, vx, vy;
        int life, maxLife, age;
        int size;
        int[] colors;
    }
}
