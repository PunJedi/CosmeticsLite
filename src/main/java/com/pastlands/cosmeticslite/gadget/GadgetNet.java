// src/main/java/com/pastlands/cosmeticslite/gadget/GadgetNet.java
package com.pastlands.cosmeticslite.gadget;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pastlands.cosmeticslite.CosmeticDef;
import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.CosmeticsRegistry;
import org.joml.Vector3f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Networking + action dispatch for active-use gadgets.
 * - Server validates gadget id + cooldown, then broadcasts S2C FX.
 * - Client renders FX based on JSON properties or hardwired helpers below.
 *
 * Hybrid model:
 * - Hardwired actions remain as baseline.
 * - If a gadget JSON includes 'pattern', GenericGadgetAction takes over for that id.
 *
 * Uniform cooldown policy:
 * - ALL gadgets (hardwired or JSON-driven) use a global 45s server cooldown.
 * - Client additionally debounces visual play for the same gadget id for 45s
 *   to prevent any accidental repeats from key-repeat or UI re-triggers.
 */
public final class GadgetNet {
    private GadgetNet() {}

    private static final String PROTOCOL = "1";
    private static final ResourceLocation CHANNEL_NAME =
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "gadget");

    private static SimpleChannel CHANNEL;
    private static int NEXT_ID = 0;
    private static boolean BOOTSTRAPPED = false;

    /** Global uniform cooldown: 45 seconds in ticks. */
    private static final int GLOBAL_COOLDOWN_TICKS = 45 * 20;

    private static int id() { return NEXT_ID++; }

    /** Call once during common setup (FMLCommonSetupEvent → enqueueWork). */
    public static void init() {
        if (BOOTSTRAPPED) return;

        CHANNEL = NetworkRegistry.newSimpleChannel(
                CHANNEL_NAME, () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

        CHANNEL.registerMessage(id(), UseGadgetC2S.class,
                UseGadgetC2S::encode, UseGadgetC2S::decode, UseGadgetC2S::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));

        CHANNEL.registerMessage(id(), PlayGadgetFxS2C.class,
                PlayGadgetFxS2C::encode, PlayGadgetFxS2C::decode, PlayGadgetFxS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        GadgetActions.bootstrapDefaults();
        BOOTSTRAPPED = true;
    }

    public static SimpleChannel channel() { return CHANNEL; }

    // ------------------------------------------------------------------------
    // Cooldowns (server-side)
    // ------------------------------------------------------------------------
    private static final Map<UUID, Map<ResourceLocation, Long>> LAST_USE_TICK = new ConcurrentHashMap<>();

    private static boolean checkAndStampCooldown(ServerPlayer sp, ResourceLocation gadgetId, long now, int cooldownTicks) {
        Map<ResourceLocation, Long> perPlayer = LAST_USE_TICK.computeIfAbsent(sp.getUUID(), k -> new ConcurrentHashMap<>());
        long last = perPlayer.getOrDefault(gadgetId, Long.MIN_VALUE / 4);
        if (now - last < cooldownTicks) return false;
        perPlayer.put(gadgetId, now);
        return true;
    }

    // ------------------------------------------------------------------------
    // C2S: use gadget
    // ------------------------------------------------------------------------
    public static record UseGadgetC2S(ResourceLocation gadgetId) {
        public static void encode(UseGadgetC2S msg, FriendlyByteBuf buf) { buf.writeResourceLocation(msg.gadgetId); }
        public static UseGadgetC2S decode(FriendlyByteBuf buf) { return new UseGadgetC2S(buf.readResourceLocation()); }

        public static void handle(UseGadgetC2S msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            ServerPlayer sp = c.getSender();
            c.enqueueWork(() -> {
                if (sp == null) return;
                if (channel() == null) return;

                ServerLevel level = sp.serverLevel();
                long now = level.getGameTime();

                CosmeticDef def = CosmeticsRegistry.get(msg.gadgetId());
                if (def == null || !"gadgets".equals(def.type())) return;

                IGadgetAction action = GadgetActions.get(msg.gadgetId());
                if (action == null) return;

                // --- Uniform 45s cooldown across ALL gadgets ---
                int cooldown = GLOBAL_COOLDOWN_TICKS;

                if (!checkAndStampCooldown(sp, msg.gadgetId, now, cooldown)) return;

                long seed = sp.getRandom().nextLong();
                Vec3 origin = sp.getEyePosition();
                Vec3 dir = sp.getLookAngle().normalize();

                // Server-side interaction hook (safe, optional)
                try { action.serverPerform(sp, origin, dir, seed); } catch (Throwable ignored) {}

                channel().send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> sp),
    new PlayGadgetFxS2C(msg.gadgetId, origin, dir, seed, cooldown));

            });
            c.setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------------------
    // S2C: play gadget FX
    // ------------------------------------------------------------------------
public static record PlayGadgetFxS2C(ResourceLocation gadgetId,
                                     double x, double y, double z,
                                     float dx, float dy, float dz,
                                     long seed,
                                     int cooldownTicks) {

    public PlayGadgetFxS2C(ResourceLocation id, Vec3 origin, Vec3 dir, long seed, int cooldownTicks) {
        this(id, origin.x, origin.y, origin.z, (float) dir.x, (float) dir.y, (float) dir.z, seed, cooldownTicks);
    }
    public static void encode(PlayGadgetFxS2C msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.gadgetId);
        buf.writeDouble(msg.x); buf.writeDouble(msg.y); buf.writeDouble(msg.z);
        buf.writeFloat(msg.dx); buf.writeFloat(msg.dy); buf.writeFloat(msg.dz);
        buf.writeLong(msg.seed);
        buf.writeVarInt(msg.cooldownTicks);
    }
    public static PlayGadgetFxS2C decode(FriendlyByteBuf buf) {
        ResourceLocation id = buf.readResourceLocation();
        double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
        float dx = buf.readFloat(), dy = buf.readFloat(), dz = buf.readFloat();
        long seed = buf.readLong();
        int cooldown = buf.readVarInt();
        return new PlayGadgetFxS2C(id, x, y, z, dx, dy, dz, seed, cooldown);
    }


        public static void handle(PlayGadgetFxS2C msg, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.level != null) {
                    ClientHooks.playFxClient(msg);
                }
            });
            c.setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------------------
    // Action registry + Generic action
    // ------------------------------------------------------------------------
    public interface IGadgetAction {
        default void serverPerform(ServerPlayer sp, Vec3 origin, Vec3 dir, long seed) {}
        /** Cooldown is enforced server-side to a global 45s; per-action value is ignored for policy. */
        int cooldownTicks(ServerPlayer sp, @Nullable CosmeticDef def);
        @OnlyIn(Dist.CLIENT)
        void clientFx(ClientLevel level, Vec3 origin, Vec3 dir, long seed, CosmeticDef def);
    }

    /** JSON-driven action: reads 'pattern' and simple knobs from CosmeticDef.properties. */
    private static final class GenericGadgetAction implements IGadgetAction {
        @Override
        public int cooldownTicks(ServerPlayer sp, @Nullable CosmeticDef def) {
            return GLOBAL_COOLDOWN_TICKS;
        }

        @Override
        @OnlyIn(Dist.CLIENT)
        public void clientFx(ClientLevel level, Vec3 origin, Vec3 dir, long seed, CosmeticDef def) {
            String pattern = Props.g(def, "pattern");
            if (pattern == null || pattern.isEmpty()) {
                // Nothing to do; keep silent.
                return;
            }

            // ----- Optional sound -----
            ResourceLocation snd = Props.rl(def, "sound", null);
            float vol = Props.f(def, "volume", 1.0f);
            float pit = Props.f(def, "pitch", 1.0f);
            if (snd != null) FastFx.playSound(level, origin, snd, vol, pit);

            // ----- Pattern dispatch -----
            java.util.Random r = new java.util.Random(seed);
            switch (pattern) {
                case "confetti_burst" -> ConfettiBurstFx.play(level, origin, dir, def, r);
                case "confetti_cyclone" -> FastFx.confettiCyclone(level, origin, seed,
                        Props.i(def, "count", 400),
                        Props.f(def, "height", 6f),
                        Props.f(def, "radius", 2.5f),
                        Props.ms(def, "duration_ms", 4000L));
                case "spark_fan" -> {
                    int count  = Props.i(def, "count", 50);
                    float arcDeg = Props.f(def, "arc_deg", 70f);
                    double arcRad = Math.toRadians(arcDeg);

                    Vec3 f = dir.normalize();
                    Vec3 right = f.cross(new Vec3(0, 1, 0));
                    if (right.lengthSqr() < 1e-4) right = f.cross(new Vec3(1, 0, 0));
                    right = right.normalize();
                    Vec3 up = right.cross(f).normalize();

                    for (int i = 0; i < count; i++) {
                        double a = (r.nextDouble() - 0.5) * arcRad;
                        double ca = Math.cos(a), sa = Math.sin(a);
                        double speed = 0.35 + r.nextDouble() * 0.25;
                        Vec3 sweep = f.scale(ca).add(right.scale(sa)).normalize().scale(speed);
                        
                        // Add slight upward component for more dynamic feel
                        sweep = sweep.add(up.scale(0.1 + r.nextDouble() * 0.15));

                        level.addParticle(ParticleTypes.ELECTRIC_SPARK, origin.x, origin.y + 0.2, origin.z,
                                sweep.x, sweep.y * 0.4, sweep.z);
                        if (r.nextFloat() < 0.6f) {
                            level.addParticle(ParticleTypes.CRIT, origin.x, origin.y + 0.2, origin.z,
                                    sweep.x * 0.7, sweep.y * 0.3, sweep.z * 0.7);
                        }
                        if (r.nextFloat() < 0.2f) {
                            level.addParticle(ParticleTypes.ENCHANT, origin.x, origin.y + 0.2, origin.z,
                                    sweep.x * 0.4, sweep.y * 0.2, sweep.z * 0.4);
                        }
                    }
                }
                // Stacked spinning spark rings (runs for ~duration_ms)
                case "spark_ring_stack" -> {
                    long  showMsRaw = Props.ms(def, "duration_ms", 4000L);
                    int   rateMsRaw = (int) Props.ms(def, "ring_rate_ms", 200L);
                    final long  showMs    = Math.max(200L, Math.min(8000L, showMsRaw)); // hard clamp
                    final int   rateMs    = Math.max(40, Math.min(500, rateMsRaw));     // hard clamp
                    float height    = Props.f(def, "stack_height", 1.0f);       // height offset from origin
                    int   rings     = Math.max(1, Props.i(def, "stack_rings", 4));
                    float radius    = Props.f(def, "ring_radius", 1.2f);
                    int   perRing   = Math.max(8, Props.i(def, "ring_particles", 40));
                    double spinSpeed = Props.f(def, "spin_speed", 0.05f);      // radians/ms (increased for visible rotation)

                    for (int t = 0; t < showMs; t += rateMs) {
                        final int ti = t;
                        ClientScheduler.scheduleMs(ti, () -> {
                            Minecraft mc = Minecraft.getInstance();
                            if (mc == null || mc.player == null || mc.level != level) return;
                            
                            Vec3 playerPos = mc.player.getEyePosition().add(0, -0.8, 0);
                            double baseRot = ti * spinSpeed;
                            
                            // Calculate expansion progress (0.0 to 1.0)
                            float expansionProgress = ti / (float)showMs;
                            
                            for (int i = 0; i < rings; i++) {
                                float y = (rings == 1) ? 0f : (i / (float) (rings - 1)) * height;
                                double rot = baseRot + i * 0.8;
                                
                                // Current radius expands from initial to 3x over time
                                float currentRadius = radius * (1.0f + expansionProgress * 2.0f);

                                for (int k = 0; k < perRing; k++) {
                                    double a  = rot + (2.0 * Math.PI * k / perRing);
                                    double cosA = Math.cos(a);
                                    double sinA = Math.sin(a);
                                    
                                    // Position on expanding ring
                                    double px = playerPos.x + cosA * currentRadius;
                                    double py = playerPos.y + y;
                                    double pz = playerPos.z + sinA * currentRadius;

                                    // Strong outward expansion velocity (particles fly away)
                                    double expansionSpeed = 0.08 + expansionProgress * 0.12; // Faster as it expands
                                    double vx = cosA * expansionSpeed;
                                    double vz = sinA * expansionSpeed;
                                    double vy = 0.01 + (r.nextDouble() - 0.5) * 0.02; // Slight vertical variation
                                    
                                    // Fade effect: particles become less visible as they expand
                                    float fadeFactor = 1.0f - expansionProgress * 0.6f; // Fade to 40% visibility
                                    if (r.nextFloat() > fadeFactor) continue; // Skip some particles for fade effect
                                    
                                    // Main spark particles with expansion
                                    level.addParticle(ParticleTypes.ELECTRIC_SPARK, px, py, pz, vx, vy, vz);
                                    
                                    // Add more particles for density, but fade them too
                                    if (r.nextFloat() < 0.25f * fadeFactor) {
                                        level.addParticle(ParticleTypes.CRIT, px, py, pz, vx * 0.7, vy * 0.7, vz * 0.7);
                                    }
                                    if (r.nextFloat() < 0.12f * fadeFactor) {
                                        level.addParticle(ParticleTypes.ENCHANT, px, py, pz, vx * 0.5, vy * 0.5, vz * 0.5);
                                    }
                                    
                                    // Add occasional trailing particles for expansion effect
                                    if (r.nextFloat() < 0.15f * fadeFactor) {
                                        // Spawn a trailing particle slightly behind
                                        double trailOffset = 0.15;
                                        level.addParticle(ParticleTypes.END_ROD, 
                                                px - cosA * trailOffset, 
                                                py, 
                                                pz - sinA * trailOffset, 
                                                vx * 0.6, vy * 0.6, vz * 0.6);
                                    }
                                }
                            }
                        });
                    }
                }
                case "ring" -> FastFx.expandingRing(level, origin, dir, seed,
                        Props.f(def, "radius_max", 6f),
                        Props.i(def, "rings", 2));
                case "helix" -> FastFx.helixStream(level, origin, dir, seed,
                        Props.f(def, "length", 12f),
                        Props.i(def, "coils", 3));
                case "orbit" -> FastFx.fireflyOrbit(level, origin, dir, seed,
                        Props.f(def, "radius_max", 2.5f));
                case "beacon" -> FastFx.skyBeacon(level, origin, seed,
                        Props.f(def, "height", 25f));
                case "fire_tornado" -> FastFx.fireTornado(level, origin, seed,
                        Props.f(def, "height", 8f),
                        Props.f(def, "radius", 2.5f),
                        Props.i(def, "duration_ticks", 100));
                case "sparkle_burst" -> FastFx.sparkleBurst(level, origin, dir, seed,
                        Props.i(def, "count", 80),
                        Props.f(def, "radius", 4f));
                case "bubble_burst" -> FastFx.bubbleBurst(level, origin, seed,
                        Props.i(def, "count", 60),
                        Props.f(def, "speed", 0.12f),
                        Props.f(def, "cone_deg", 120f));
                default -> {
                    // Tiny neutral pop so it never feels broken.
                    level.addParticle(ParticleTypes.END_ROD, origin.x, origin.y, origin.z, 0, 0.05, 0);
                }
            }
        }
    }

    public static final class GadgetActions {
        private static final Map<ResourceLocation, IGadgetAction> REGISTRY = new ConcurrentHashMap<>();
        private static final IGadgetAction GENERIC = new GenericGadgetAction();

        public static void register(ResourceLocation id, IGadgetAction action) { REGISTRY.put(id, action); }
        public static IGadgetAction get(ResourceLocation id) { return REGISTRY.get(id); }

        // Map an alias id to the same action as a target id.
        private static void aliasTo(ResourceLocation alias, ResourceLocation target) {
            IGadgetAction base = REGISTRY.get(target);
            if (base != null) REGISTRY.put(alias, base);
        }

        public static void bootstrapDefaults() {
            REGISTRY.clear();

            // ----------------------------------------------------------------
            // PHASE 1: Hardwired baseline (kept for continuity) — all 45s CD
            // ----------------------------------------------------------------

            // 1) confetti_popper
            register(rl("cosmeticslite","confetti_popper"), new IGadgetAction() {
                @Override public int cooldownTicks(ServerPlayer sp, @Nullable CosmeticDef def) { return GLOBAL_COOLDOWN_TICKS; }
                @Override @OnlyIn(Dist.CLIENT)
                public void clientFx(ClientLevel level, Vec3 origin, Vec3 dir, long seed, CosmeticDef def) {
                    final int count = Props.i(def, "count", 60);
                    final float coneDeg = Props.f(def, "cone_deg", 40f);
                    final var snd = Props.rl(def, "sound", rl("minecraft","entity.firework_rocket.blast"));
                    final float vol = Props.f(def, "volume", 1.0f), pit = Props.f(def, "pitch", 1.0f);

                    java.util.Random r = new java.util.Random(seed);
                    float coneRad = (float)Math.toRadians(coneDeg);
                    for (int i = 0; i < count; i++) {
                        Vec3 v = FastFx.randomCone(dir, coneRad, r).scale(0.35 + r.nextDouble() * 0.25);
                        level.addParticle(ParticleTypes.FIREWORK, origin.x, origin.y, origin.z, v.x, v.y * 0.6, v.z);
                        if (r.nextFloat() < 0.15f) {
                            level.addParticle(ParticleTypes.HAPPY_VILLAGER, origin.x, origin.y, origin.z, v.x * 0.2, v.y * 0.2, v.z * 0.2);
                        }
                    }
                    FastFx.playSound(level, origin, snd, vol, pit);
                }
            });

            // 2) bubble_blower
            register(rl("cosmeticslite","bubble_blower"), new IGadgetAction() {
                @Override public int cooldownTicks(ServerPlayer sp, @Nullable CosmeticDef def) { return GLOBAL_COOLDOWN_TICKS; }
                @Override @OnlyIn(Dist.CLIENT)
                public void clientFx(ClientLevel level, Vec3 origin, Vec3 dir, long seed, CosmeticDef def) {
                    final double speed = Props.f(def, "speed", 0.15f);
                    final int life = Props.i(def, "lifetime", 40);
                    final var snd = Props.rl(def, "sound", rl("minecraft","block.bubble_column.upwards_inside"));
                    final float vol = Props.f(def, "volume", 0.8f), pit = Props.f(def, "pitch", 1.0f);

                    Vec3 v = dir.normalize().scale(speed);
                    Vec3 p = origin.add(0, 0.2, 0);
                    for (int t = 0; t < life; t++) {
                        level.addParticle(ParticleTypes.BUBBLE, p.x, p.y, p.z, 0.0, 0.01, 0.0);
                        if (t % 6 == 0) level.addParticle(ParticleTypes.SPLASH, p.x, p.y, p.z, 0.0, 0.0, 0.0);
                        p = p.add(v);
                    }
                    FastFx.playSound(level, origin, snd, vol, pit);
                }
            });

            // 3) gear_spark_emitter — stacked rings that expand outward and fade
            register(rl("cosmeticslite","gear_spark_emitter"), new IGadgetAction() {
                @Override public int cooldownTicks(ServerPlayer sp, @Nullable CosmeticDef def) { return GLOBAL_COOLDOWN_TICKS; }
                @Override @OnlyIn(Dist.CLIENT)
                public void clientFx(ClientLevel level, Vec3 origin, Vec3 dir, long seed, CosmeticDef def) {
                    final long  showMs     = Math.max(200L, Math.min(8000L, Props.ms(def, "duration_ms", 4000L)));
                    final int   rateMs     = (int) Math.max(40, Math.min(500, Props.ms(def, "ring_rate_ms", 200L)));
                    final float height     = Props.f(def, "stack_height", 1.0f);
                    final int   rings      = Math.max(1, Props.i(def, "stack_rings", 4));
                    final float radius     = Props.f(def, "ring_radius", 1.8f);
                    final int   perRing    = Math.max(8, Props.i(def, "ring_particles", 42));
                    final double spinSpeed = Props.f(def, "spin_speed", 0.05f); // Increased for visible rotation
                    final var snd = Props.rl(def, "sound", rl("minecraft","block.iron_door.open"));
                    final float vol = Props.f(def, "volume", 0.7f);
                    final float pit = Props.f(def, "pitch", 0.8f);

                    java.util.Random r = new java.util.Random(seed);
                    
                    // Play spooling sound that changes pitch as rings expand
                    // Start high pitch, gradually lower as effect progresses (like unwinding)
                    for (int s = 0; s < showMs; s += 150) { // More frequent for smoother spooling effect
                        final int soundTime = s;
                        ClientScheduler.scheduleMs(soundTime, () -> {
                            Minecraft mc = Minecraft.getInstance();
                            if (mc != null && mc.player != null && mc.level == level) {
                                Vec3 playerPos = mc.player.getEyePosition().add(0, -0.8, 0);
                                // Pitch starts high and decreases over time (spool unwinding effect)
                                float progress = soundTime / (float)showMs;
                                float spoolPitch = 1.2f - (progress * 0.5f); // Start at 1.2, end at 0.7
                                float pitchVar = spoolPitch + (r.nextFloat() - 0.5f) * 0.15f;
                                FastFx.playSound(level, playerPos, snd, vol * 0.7f, pitchVar);
                                
                                // Add occasional mechanical click/clunk sound
                                if (r.nextFloat() < 0.2f) {
                                    ResourceLocation clickSound = ResourceLocation.fromNamespaceAndPath("minecraft", "block.iron_trapdoor.open");
                                    FastFx.playSound(level, playerPos, clickSound, vol * 0.4f, 1.0f + r.nextFloat() * 0.3f);
                                }
                            }
                        });
                    }

                    // Track expansion for each ring wave
                    for (int t = 0; t < showMs; t += rateMs) {
                        final int ti = t;
                        ClientScheduler.scheduleMs(ti, () -> {
                            Minecraft mc = Minecraft.getInstance();
                            if (mc == null || mc.player == null || mc.level != level) return;
                            
                            Vec3 playerPos = mc.player.getEyePosition().add(0, -0.8, 0);
                            double baseRot = ti * spinSpeed;
                            
                            // Calculate expansion progress (0.0 to 1.0)
                            float expansionProgress = ti / (float)showMs;
                            
                            for (int i = 0; i < rings; i++) {
                                float y = (rings == 1) ? 0f : (i / (float) (rings - 1)) * height;
                                double rot = baseRot + i * 0.8;
                                
                                // Current radius expands from initial to 3x over time
                                float currentRadius = radius * (1.0f + expansionProgress * 2.0f);
                                
                                for (int k = 0; k < perRing; k++) {
                                    double a  = rot + (2.0 * Math.PI * k / perRing);
                                    double cosA = Math.cos(a);
                                    double sinA = Math.sin(a);
                                    
                                    // Position on expanding ring
                                    double px = playerPos.x + cosA * currentRadius;
                                    double py = playerPos.y + y;
                                    double pz = playerPos.z + sinA * currentRadius;

                                    // Strong outward expansion velocity (particles fly away)
                                    double expansionSpeed = 0.08 + expansionProgress * 0.12; // Faster as it expands
                                    double vx = cosA * expansionSpeed;
                                    double vz = sinA * expansionSpeed;
                                    double vy = 0.01 + (r.nextDouble() - 0.5) * 0.02; // Slight vertical variation
                                    
                                    // Fade effect: particles become less visible as they expand
                                    // Spawn fewer particles as expansion progresses
                                    float fadeFactor = 1.0f - expansionProgress * 0.6f; // Fade to 40% visibility
                                    if (r.nextFloat() > fadeFactor) continue; // Skip some particles for fade effect
                                    
                                    // Main spark particles with expansion
                                    level.addParticle(ParticleTypes.ELECTRIC_SPARK, px, py, pz, vx, vy, vz);
                                    
                                    // Add more particles for density, but fade them too
                                    if (r.nextFloat() < 0.25f * fadeFactor) {
                                        level.addParticle(ParticleTypes.CRIT, px, py, pz, vx * 0.7, vy * 0.7, vz * 0.7);
                                    }
                                    if (r.nextFloat() < 0.12f * fadeFactor) {
                                        level.addParticle(ParticleTypes.ENCHANT, px, py, pz, vx * 0.5, vy * 0.5, vz * 0.5);
                                    }
                                    
                                    // Add occasional trailing particles for expansion effect
                                    if (r.nextFloat() < 0.15f * fadeFactor) {
                                        // Spawn a trailing particle slightly behind
                                        double trailOffset = 0.15;
                                        level.addParticle(ParticleTypes.END_ROD, 
                                                px - cosA * trailOffset, 
                                                py, 
                                                pz - sinA * trailOffset, 
                                                vx * 0.6, vy * 0.6, vz * 0.6);
                                    }
                                }
                            }
                        });
                    }
                }
            });

            // ---- Cinematic presets (kept; all 45s CD) ----
            register(rl("cosmeticslite","supernova_burst"), new BasicFx(GLOBAL_COOLDOWN_TICKS) {
                @Override @OnlyIn(Dist.CLIENT) public void fx(ClientLevel level, Vec3 origin, Vec3 dir, long seed, CosmeticDef def) {
                    FastFx.starburstRays(level, origin, dir, seed, Props.i(def,"rays",16), Props.f(def,"length",16f));
                    FastFx.playSound(level, origin, Props.rl(def,"sound",rl("minecraft","entity.firework_rocket.blast")),
                            Props.f(def,"volume",1.0f), Props.f(def,"pitch",0.9f));
                }
            });
            register(rl("cosmeticslite","expanding_ring"), new BasicFx(GLOBAL_COOLDOWN_TICKS) {
                @Override @OnlyIn(Dist.CLIENT) public void fx(ClientLevel level, Vec3 origin, Vec3 dir, long seed, CosmeticDef def) {
                    FastFx.expandingRing(level, origin, dir, seed, Props.f(def,"radius_max",8f), Props.i(def,"rings",2));
                    FastFx.playSound(level, origin, Props.rl(def,"sound",rl("minecraft","block.amethyst_block.chime")),
                            Props.f(def,"volume",0.7f), Props.f(def,"pitch",1.1f));
                }
            });
            register(rl("cosmeticslite","helix_stream"), new BasicFx(GLOBAL_COOLDOWN_TICKS) {
                @Override @OnlyIn(Dist.CLIENT) public void fx(ClientLevel level, Vec3 origin, Vec3 dir, long seed, CosmeticDef def) {
                    FastFx.helixStream(level, origin, dir, seed, Props.f(def,"length",12f), Props.i(def,"coils",3));
                    FastFx.playSound(level, origin, Props.rl(def,"sound",rl("minecraft","block.beacon.power_select")),
                            Props.f(def,"volume",0.8f), Props.f(def,"pitch",1.2f));
                }
            });
            register(rl("cosmeticslite","firefly_orbit"), new BasicFx(GLOBAL_COOLDOWN_TICKS) {
                @Override @OnlyIn(Dist.CLIENT) public void fx(ClientLevel level, Vec3 origin, Vec3 dir, long seed, CosmeticDef def) {
                    FastFx.fireflyOrbit(level, origin, dir, seed, Props.f(def,"radius_max",2.5f));
                    FastFx.playSound(level, origin, Props.rl(def,"sound",rl("minecraft","block.amethyst_block.hit")),
                            Props.f(def,"volume",0.6f), Props.f(def,"pitch",1.2f));
                }
            });
            register(rl("cosmeticslite","ground_ripple"), new BasicFx(GLOBAL_COOLDOWN_TICKS) {
                @Override @OnlyIn(Dist.CLIENT) public void fx(ClientLevel level, Vec3 origin, Vec3 dir, long seed, CosmeticDef def) {
                    FastFx.groundRipple(level, origin, seed, Props.f(def,"radius_max",6f));
                    FastFx.playSound(level, origin, Props.rl(def,"sound",rl("minecraft","block.basalt.break")),
                            Props.f(def,"volume",0.9f), Props.f(def,"pitch",0.9f));
                }
            });
            register(rl("cosmeticslite","sky_beacon"), new BasicFx(GLOBAL_COOLDOWN_TICKS) {
                @Override @OnlyIn(Dist.CLIENT) public void fx(ClientLevel level, Vec3 origin, Vec3 dir, long seed, CosmeticDef def) {
                    FastFx.skyBeacon(level, origin, seed, Props.f(def,"height",25f));
                    FastFx.playSound(level, origin, Props.rl(def,"sound",rl("minecraft","block.beacon.activate")),
                            Props.f(def,"volume",1.0f), Props.f(def,"pitch",1.0f));
                }
            });

            // Aliases → reuse same action instances as baseline
            aliasTo(rl("cosmeticslite","star_shower"),       rl("cosmeticslite","confetti_popper"));
            aliasTo(rl("cosmeticslite","confetti_fountain"), rl("cosmeticslite","confetti_popper"));
            aliasTo(rl("cosmeticslite","glitter_pop"),       rl("cosmeticslite","confetti_popper"));
            aliasTo(rl("cosmeticslite","starlight_burst"),   rl("cosmeticslite","confetti_popper"));
            aliasTo(rl("cosmeticslite","glitter_veil"),      rl("cosmeticslite","confetti_popper"));

            aliasTo(rl("cosmeticslite","sparkle_ring"),      rl("cosmeticslite","gear_spark_emitter"));
            aliasTo(rl("cosmeticslite","spark_fan"),         rl("cosmeticslite","gear_spark_emitter"));
            aliasTo(rl("cosmeticslite","shimmer_wave"),      rl("cosmeticslite","gear_spark_emitter"));

            aliasTo(rl("cosmeticslite","bubble_stream"),     rl("cosmeticslite","bubble_blower"));
            aliasTo(rl("cosmeticslite","bubble_blast"),      rl("cosmeticslite","bubble_blower"));

            // ----------------------------------------------------------------
            // PHASE 2: Apply JSON overrides → bind to GENERIC if 'pattern' exists
            // ----------------------------------------------------------------
            try {
                for (CosmeticDef d : CosmeticsRegistry.getByType("gadgets")) {
                    if (d == null || d.id() == null) continue;
                    String pattern = Props.g(d, "pattern");
                    if (pattern != null && !pattern.isBlank()) {
                        REGISTRY.put(d.id(), GENERIC);
                    }
                }
            } catch (Throwable ignored) {}
        }

        /** Tiny base for one-shot FX; cooldown value unused (global policy). */
        private static abstract class BasicFx implements IGadgetAction {
            private final int cooldownTicks;
            BasicFx(int cooldownTicks) { this.cooldownTicks = Math.max(1, cooldownTicks); }
            @Override public int cooldownTicks(ServerPlayer sp, @Nullable CosmeticDef def) { return GLOBAL_COOLDOWN_TICKS; }
            @Override @OnlyIn(Dist.CLIENT) public void clientFx(ClientLevel level, Vec3 origin, Vec3 dir, long seed, CosmeticDef def) {
                fx(level, origin, dir, seed, def);
            }
            @OnlyIn(Dist.CLIENT) public abstract void fx(ClientLevel level, Vec3 origin, Vec3 dir, long seed, CosmeticDef def);
        }
    }

    // ------------------------------------------------------------------------
    // Client hooks
    // ------------------------------------------------------------------------
    @OnlyIn(Dist.CLIENT)
    private static final class ClientHooks {
        static void playFxClient(PlayGadgetFxS2C msg) {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null) return;
            CosmeticDef def = CosmeticsRegistry.get(msg.gadgetId());
            if (def == null) return;
            Vec3 origin = new Vec3(msg.x(), msg.y(), msg.z());
            Vec3 dir = new Vec3(msg.dx(), msg.dy(), msg.dz()).normalize();
            IGadgetAction action = GadgetActions.get(msg.gadgetId());
            if (action == null) return;

            // --- Unified menu hold + unified client debounce for ALL gadgets ---
            long durationMs = Props.ms(def, "duration_ms", 1200L);
            long padMs      = Props.ms(def, "menu_pad_ms", 300L);
            if (durationMs < 300L) durationMs = 300L;
            if (durationMs > 6000L) durationMs = 6000L;
            if (padMs < 0L) padMs = 0L;
            if (padMs > 1500L) padMs = 1500L;

            long holdTotal = durationMs + padMs;

            // Debounce (covers hardwired + generic); suppress if inside 45s or current hold window
            if (!ClientCooldownGate.begin(def.id(), holdTotal, GLOBAL_COOLDOWN_TICKS)) {
                return;
            }

            // Sync cooldown with GadgetClientCommands for UI display
            // This ensures remainingMs() works correctly
            com.pastlands.cosmeticslite.gadget.GadgetClientCommands.noteJustUsed(def.id());

            // Hold the menu hidden for the full FX window
            String idStr = def.id().toString();
            ClientMenuHold.notifyStart(idStr);
            ClientScheduler.scheduleMs(holdTotal, () -> ClientMenuHold.notifyEnd(idStr));

            // Run the actual gadget FX
            action.clientFx(level, origin, dir, msg.seed(), def);
        }
    }

    // ------------------------------------------------------------------------
    // FX helpers (client-side only)
    // ------------------------------------------------------------------------
    @OnlyIn(Dist.CLIENT)
    private static final class FastFx {
        static void playSound(ClientLevel level, Vec3 pos, ResourceLocation soundId, float volume, float pitch) {
            if (level == null || soundId == null) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level != level) return;
            SoundEvent event = BuiltInRegistries.SOUND_EVENT.get(soundId);
            if (event == null) return;
            mc.level.playLocalSound(pos.x, pos.y, pos.z, event, SoundSource.PLAYERS, volume, pitch, false);
        }

        static void starburstRays(ClientLevel level, Vec3 origin, Vec3 dir, long seed, int rays, float length) {
            java.util.Random r = new java.util.Random(seed);
            // Create vibrant firework-like starburst
            for (int i = 0; i < rays; i++) {
                Vec3 v = randomCone(dir, (float)Math.toRadians(180f), r).normalize().scale(length * (0.8 + r.nextDouble()*0.2));
                // Create colorful ray trail
                for (int j = 0; j < 8; j++) {
                    Vec3 p = origin.add(v.scale(j/8.0));
                    float fade = 1.0f - (j / 8.0f) * 0.8f;
                    // Main ray particles
                    level.addParticle(ParticleTypes.END_ROD, p.x, p.y, p.z, 0, 0, 0);
                    // Add sparkle trail
                    if (j % 2 == 0 && r.nextFloat() < 0.6f) {
                        level.addParticle(ParticleTypes.FIREWORK, p.x, p.y, p.z, 
                                v.x * 0.05, v.y * 0.05, v.z * 0.05);
                    }
                    // Add happy villager sparkles at the end
                    if (j == 7) {
                        level.addParticle(ParticleTypes.HAPPY_VILLAGER, p.x, p.y, p.z, 0, 0.03, 0);
                        // Burst effect at the end
                        for (int k = 0; k < 3; k++) {
                            Vec3 burst = randomCone(v, (float)Math.toRadians(30f), r).scale(0.3);
                            level.addParticle(ParticleTypes.FIREWORK, p.x, p.y, p.z, 
                                    burst.x, burst.y, burst.z);
                        }
                    }
                }
            }
            // Add central burst
            for (int i = 0; i < 12; i++) {
                Vec3 burst = randomCone(dir, (float)Math.toRadians(360f), r).scale(0.5);
                level.addParticle(ParticleTypes.FIREWORK, origin.x, origin.y, origin.z, 
                        burst.x, burst.y, burst.z);
            }
        }
        
        /** Fire tornado that surrounds the player - spinning flames going upward */
        static void fireTornado(ClientLevel level, Vec3 origin, long seed, float height, float radius, int durationTicks) {
            java.util.Random r = new java.util.Random(seed);
            int layers = (int)(height * 2);
            int particlesPerLayer = 24;
            
            for (int tick = 0; tick < durationTicks; tick += 2) {
                final int currentTick = tick;
                ClientScheduler.schedule(tick / 2, () -> {
                    double rotation = currentTick * 0.1; // Spinning speed
                    for (int layer = 0; layer < layers; layer++) {
                        float y = (layer / (float)layers) * height;
                        float layerRadius = radius * (0.7f + layer / (float)layers * 0.3f); // Wider at top
                        
                        for (int i = 0; i < particlesPerLayer; i++) {
                            double angle = (i / (double)particlesPerLayer) * 2 * Math.PI + rotation;
                            double x = Math.cos(angle) * layerRadius;
                            double z = Math.sin(angle) * layerRadius;
                            
                            // Upward spiral motion
                            double vy = 0.15 + (layer / (float)layers) * 0.1;
                            double vx = -Math.sin(angle) * 0.05;
                            double vz = Math.cos(angle) * 0.05;
                            
                            // Main flame particles
                            level.addParticle(ParticleTypes.FLAME, 
                                    origin.x + x, origin.y + y, origin.z + z, 
                                    vx, vy, vz);
                            
                            // Add smoke and embers
                            if (r.nextFloat() < 0.3f) {
                                level.addParticle(ParticleTypes.SMOKE, 
                                        origin.x + x, origin.y + y, origin.z + z, 
                                        vx * 0.5, vy * 0.3, vz * 0.5);
                            }
                            if (r.nextFloat() < 0.2f) {
                                level.addParticle(ParticleTypes.LAVA, 
                                        origin.x + x, origin.y + y, origin.z + z, 
                                        vx * 0.3, vy * 0.5, vz * 0.3);
                            }
                        }
                    }
                });
            }
        }
        
        /** Sparkle burst - firework-like explosion with colorful sparkles */
        static void sparkleBurst(ClientLevel level, Vec3 origin, Vec3 dir, long seed, int count, float radius) {
            java.util.Random r = new java.util.Random(seed);
            // Initial burst
            for (int i = 0; i < count; i++) {
                Vec3 v = randomCone(dir, (float)Math.toRadians(180f), r).normalize()
                        .scale(radius * (0.5 + r.nextDouble() * 0.5));
                
                // Colorful sparkle particles
                level.addParticle(ParticleTypes.FIREWORK, origin.x, origin.y, origin.z, 
                        v.x, v.y, v.z);
                
                // Add end rod sparkles
                if (r.nextFloat() < 0.4f) {
                    level.addParticle(ParticleTypes.END_ROD, origin.x, origin.y, origin.z, 
                            v.x * 0.6, v.y * 0.6, v.z * 0.6);
                }
                
                // Add happy villager sparkles
                if (r.nextFloat() < 0.3f) {
                    level.addParticle(ParticleTypes.HAPPY_VILLAGER, origin.x, origin.y, origin.z, 
                            v.x * 0.4, v.y * 0.4, v.z * 0.4);
                }
            }
            
            // Secondary burst after delay
            ClientScheduler.scheduleMs(200, () -> {
                for (int i = 0; i < count / 2; i++) {
                    Vec3 v = randomCone(dir, (float)Math.toRadians(180f), r).normalize()
                            .scale(radius * 0.3 * (0.5 + r.nextDouble()));
                    level.addParticle(ParticleTypes.FIREWORK, origin.x, origin.y, origin.z, 
                            v.x, v.y, v.z);
                }
            });
        }
        
        /** Bubble burst - various sized bubbles exploding outward and drifting away */
        static void bubbleBurst(ClientLevel level, Vec3 origin, long seed, int count, float baseSpeed, float coneDeg) {
            // Spawn bubbles continuously every tick (50ms) for smooth, blended effect
            // This eliminates the jarring pulse and creates a seamless fade
            long durationMs = 4000L;
            int totalTicks = (int)(durationMs / 50L); // ~80 ticks over 4 seconds
            int totalBubbles = Math.max(count, 280);
            
            // Define bubble colors (light blue, cyan, white, pale blue variations)
            Vector3f[] bubbleColors = new Vector3f[] {
                new Vector3f(0.53f, 0.81f, 0.92f), // Light blue (#87CEEB)
                new Vector3f(0.0f, 1.0f, 1.0f),    // Cyan (#00FFFF)
                new Vector3f(1.0f, 1.0f, 1.0f),    // White (#FFFFFF)
                new Vector3f(0.69f, 0.88f, 0.90f), // Pale blue (#B0E0E6)
                new Vector3f(0.5f, 0.9f, 0.9f),    // Light cyan
                new Vector3f(0.7f, 0.9f, 1.0f),    // Sky blue
                new Vector3f(0.6f, 0.85f, 0.95f)   // Powder blue
            };
            
            // Schedule bubble popping sounds throughout the effect
            java.util.Random soundRng = new java.util.Random(seed);
            // Use a sound that definitely exists - try multiple bubble-related sounds
            ResourceLocation[] popSounds = new ResourceLocation[] {
                rl("minecraft", "block.bubble_column.upwards_inside"),
                rl("minecraft", "block.bubble_column.whirlpool_inside"),
                rl("minecraft", "entity.guardian.attack")
            };
            int soundCount = 20; // More sounds for better effect
            for (int s = 0; s < soundCount; s++) {
                long soundDelay = (long)(durationMs * (soundRng.nextDouble() * 0.85 + 0.05)); // Random timing
                float pitch = 0.6f + soundRng.nextFloat() * 0.8f; // Pitch between 0.6 and 1.4
                float volume = 0.4f + soundRng.nextFloat() * 0.5f; // Volume between 0.4 and 0.9
                final float finalPitch = pitch;
                final float finalVolume = volume;
                final long soundSeed = seed + s;
                final ResourceLocation chosenSound = popSounds[soundRng.nextInt(popSounds.length)];
                ClientScheduler.scheduleMs(soundDelay, () -> {
                    java.util.Random posRng = new java.util.Random(soundSeed);
                    Vec3 soundPos = origin.add(
                        (posRng.nextDouble() - 0.5) * 2.5,
                        (posRng.nextDouble() - 0.5) * 2.0,
                        (posRng.nextDouble() - 0.5) * 2.5
                    );
                    FastFx.playSound(level, soundPos, chosenSound, finalVolume, finalPitch);
                });
            }
            
            // Calculate bubbles per tick with smooth fade-out curve
            // Use exponential decay for natural fade: start dense, fade smoothly
            for (int tick = 0; tick < totalTicks; tick++) {
                final int currentTick = tick;
                ClientScheduler.schedule(tick, () -> {
                    // Smooth fade curve: exponential decay from 1.0 to ~0.1
                    float timeProgress = currentTick / (float)totalTicks;
                    float fadeFactor = (float)(1.0 - Math.pow(timeProgress, 1.5)); // Smooth exponential fade
                    
                    // Bubbles per tick: starts high, fades smoothly
                    int bubblesThisTick = (int)((totalBubbles / (float)totalTicks) * (1.0f + fadeFactor * 2.0f));
                    bubblesThisTick = Math.max(1, bubblesThisTick); // At least 1 bubble per tick
                    
                    java.util.Random tickRng = new java.util.Random(seed + currentTick);
                    
                    for (int i = 0; i < bubblesThisTick; i++) {
                        // True spherical distribution - bubbles go in ALL directions
                        double theta = tickRng.nextDouble() * 2 * Math.PI;
                        double phi = tickRng.nextDouble() * Math.PI;
                        double sinPhi = Math.sin(phi);
                        Vec3 dir = new Vec3(
                            Math.cos(theta) * sinPhi,
                            Math.cos(phi), // -1 to 1 (down to up)
                            Math.sin(theta) * sinPhi
                        );
                        
                        // Vary speeds - faster early, slower later (creates size illusion via motion)
                        float speedVariation = 0.3f + tickRng.nextFloat() * 1.4f; // 0.3x to 1.7x
                        float timeSpeedFactor = 1.0f - timeProgress * 0.6f; // Slower over time
                        float speed = baseSpeed * speedVariation * timeSpeedFactor;
                        
                        // Spawn position: early bubbles at origin, later ones drift outward
                        float driftDistance = timeProgress * 1.2f; // Bubbles have drifted outward
                        Vec3 spawnPos = origin.add(dir.scale(driftDistance)).add(
                            (tickRng.nextDouble() - 0.5) * 0.4,
                            (tickRng.nextDouble() - 0.5) * 0.3,
                            (tickRng.nextDouble() - 0.5) * 0.4
                        );
                        
                        Vec3 velocity = dir.scale(speed);
                        
                        // Spawn bubble - vary spawn density to simulate size variation
                        // Mix regular BUBBLE particles with colored DUST particles for variety
                        float sizeRoll = tickRng.nextFloat();
                        boolean useColored = tickRng.nextFloat() < 0.7f; // 70% colored, 30% regular for more visible colors
                        // Choose bubble color randomly (only used if useColored is true)
                        Vector3f bubbleColor = bubbleColors[tickRng.nextInt(bubbleColors.length)];
                        
                        if (sizeRoll < 0.15f) {
                            // Large bubbles: spawn 2-3 close together
                            int clusterSize = 2 + tickRng.nextInt(2);
                            for (int c = 0; c < clusterSize; c++) {
                                Vec3 clusterOffset = new Vec3(
                                    (tickRng.nextDouble() - 0.5) * 0.15,
                                    (tickRng.nextDouble() - 0.5) * 0.15,
                                    (tickRng.nextDouble() - 0.5) * 0.15
                                );
                                if (useColored) {
                                    // Use larger dust particles for better visibility
                                    level.addParticle(new DustParticleOptions(bubbleColor, 1.2f),
                                            spawnPos.x + clusterOffset.x, 
                                            spawnPos.y + clusterOffset.y, 
                                            spawnPos.z + clusterOffset.z, 
                                            velocity.x * 0.9, velocity.y * 0.9, velocity.z * 0.9);
                                    // Also add a regular bubble for bubble-like appearance
                                    level.addParticle(ParticleTypes.BUBBLE, 
                                            spawnPos.x + clusterOffset.x * 0.5, 
                                            spawnPos.y + clusterOffset.y * 0.5, 
                                            spawnPos.z + clusterOffset.z * 0.5, 
                                            velocity.x * 0.8, velocity.y * 0.8, velocity.z * 0.8);
                                } else {
                                    level.addParticle(ParticleTypes.BUBBLE, 
                                            spawnPos.x + clusterOffset.x, 
                                            spawnPos.y + clusterOffset.y, 
                                            spawnPos.z + clusterOffset.z, 
                                            velocity.x * 0.9, velocity.y * 0.9, velocity.z * 0.9);
                                }
                            }
                        } else if (sizeRoll < 0.4f) {
                            // Medium bubbles: spawn 1-2
                            int clusterSize = 1 + tickRng.nextInt(2);
                            for (int c = 0; c < clusterSize; c++) {
                                Vec3 clusterOffset = new Vec3(
                                    (tickRng.nextDouble() - 0.5) * 0.1,
                                    (tickRng.nextDouble() - 0.5) * 0.1,
                                    (tickRng.nextDouble() - 0.5) * 0.1
                                );
                                if (useColored) {
                                    // Use larger dust particles for better visibility
                                    level.addParticle(new DustParticleOptions(bubbleColor, 1.0f),
                                            spawnPos.x + clusterOffset.x, 
                                            spawnPos.y + clusterOffset.y, 
                                            spawnPos.z + clusterOffset.z, 
                                            velocity.x, velocity.y, velocity.z);
                                    // Also add a regular bubble for bubble-like appearance
                                    level.addParticle(ParticleTypes.BUBBLE, 
                                            spawnPos.x + clusterOffset.x * 0.5, 
                                            spawnPos.y + clusterOffset.y * 0.5, 
                                            spawnPos.z + clusterOffset.z * 0.5, 
                                            velocity.x * 0.9, velocity.y * 0.9, velocity.z * 0.9);
                                } else {
                                    level.addParticle(ParticleTypes.BUBBLE, 
                                            spawnPos.x + clusterOffset.x, 
                                            spawnPos.y + clusterOffset.y, 
                                            spawnPos.z + clusterOffset.z, 
                                            velocity.x, velocity.y, velocity.z);
                                }
                            }
                        } else {
                            // Small bubbles: single spawn
                            if (useColored) {
                                // Use larger dust particles for better visibility
                                level.addParticle(new DustParticleOptions(bubbleColor, 0.8f),
                                        spawnPos.x, spawnPos.y, spawnPos.z, 
                                        velocity.x * 1.1, velocity.y * 1.1, velocity.z * 1.1);
                                // Also add a regular bubble for bubble-like appearance
                                level.addParticle(ParticleTypes.BUBBLE, 
                                        spawnPos.x, spawnPos.y, spawnPos.z, 
                                        velocity.x * 1.0, velocity.y * 1.0, velocity.z * 1.0);
                            } else {
                                level.addParticle(ParticleTypes.BUBBLE, 
                                        spawnPos.x, spawnPos.y, spawnPos.z, 
                                        velocity.x * 1.1, velocity.y * 1.1, velocity.z * 1.1);
                            }
                        }
                    }
                });
            }
        }

        static void confettiCyclone(ClientLevel level, Vec3 origin, long seed, int totalCount, float height, float baseRadius, long durationMs) {
            java.util.Random r = new java.util.Random(seed);
            
            // Track start time for absolute duration checking
            final long startTimeMs = System.currentTimeMillis();
            
            // Use atomic flag to stop spawning exactly at duration
            final java.util.concurrent.atomic.AtomicBoolean isActive = new java.util.concurrent.atomic.AtomicBoolean(true);
            
            // Schedule a task to stop spawning at exactly the duration
            ClientScheduler.scheduleMs(durationMs, () -> {
                isActive.set(false);
            });
            
            // Vibrant confetti colors
            Vector3f[] colors = new Vector3f[] {
                new Vector3f(1.0f, 0.23f, 0.19f), // Red (#ff3b30)
                new Vector3f(1.0f, 0.84f, 0.04f), // Yellow (#ffd60a)
                new Vector3f(0.20f, 0.78f, 0.35f), // Green (#34c759)
                new Vector3f(0.04f, 0.52f, 1.0f), // Blue (#0a84ff)
                new Vector3f(1.0f, 0.62f, 0.04f), // Orange (#ff9f0a)
                new Vector3f(0.75f, 0.35f, 0.95f), // Purple (#bf5af2)
                new Vector3f(0.39f, 0.82f, 1.0f), // Light Blue (#64d2ff)
                new Vector3f(1.0f, 0.27f, 0.23f), // Bright Red (#ff453a)
                new Vector3f(0.19f, 0.82f, 0.35f), // Bright Green (#30d158)
                new Vector3f(1.0f, 1.0f, 1.0f)    // White
            };
            
            // Play whoosh sound at start
            ResourceLocation whooshSound = ResourceLocation.fromNamespaceAndPath("minecraft", "entity.ender_dragon.flap");
            playSound(level, origin, whooshSound, 0.6f, 0.8f);
            
            // Calculate ticks for smooth continuous spawning - ensure we stop exactly at duration
            int totalTicks = (int)(durationMs / 50L); // ~80 ticks over 4 seconds
            int particlesPerTick = Math.max(8, totalCount / totalTicks); // More particles per tick for density
            
            // Spawn particles continuously every tick for smooth cyclone - STRICT DURATION LIMIT
            for (int tick = 0; tick < totalTicks; tick++) {
                final int currentTick = tick;
                // Schedule at exact tick - will not fire after duration
                ClientScheduler.schedule(tick, () -> {
                    // Double-check: both flag and elapsed time must be valid
                    long elapsedMs = System.currentTimeMillis() - startTimeMs;
                    if (!isActive.get() || elapsedMs >= durationMs) return; // Stop spawning if duration exceeded
                    
                    Minecraft mc = Minecraft.getInstance();
                    if (mc == null || mc.player == null || mc.level != level) return;
                    
                    // Get player's current position - cyclone follows player
                    Vec3 playerPos = mc.player.getEyePosition().add(0, -0.5, 0);
                    
                    float timeProgress = currentTick / (float)totalTicks;
                    
                    // Cyclone parameters - create a dense tornado effect
                    float currentHeight = height * (0.4f + timeProgress * 0.6f); // Grows from 40% to 100%
                    float currentRadius = baseRadius * (0.9f + timeProgress * 0.2f); // Slight expansion
                    float spinSpeed = 0.25f + timeProgress * 0.15f; // Faster spin over time
                    
                    // Calculate rotation angle for this tick (spiraling upward)
                    double baseAngle = currentTick * spinSpeed;
                    
                    // Dense particle count - more particles for tornado effect
                    int particlesThisTick = particlesPerTick + (int)(particlesPerTick * (1.2f - timeProgress * 0.4f));
                    
                    for (int i = 0; i < particlesThisTick; i++) {
                        // Check both flag and elapsed time multiple times during loop to stop immediately
                        long elapsedCheck = System.currentTimeMillis() - startTimeMs;
                        if (!isActive.get() || elapsedCheck >= durationMs) break; // Stop spawning if duration exceeded
                        
                        // Distribute particles in a tornado shape - denser at bottom, wider at top
                        float normalizedY = r.nextFloat(); // 0 to 1
                        float layerY = (normalizedY * currentHeight) - (currentHeight * 0.2f); // Start slightly below player
                        
                        // Tornado shape: narrower at bottom, wider at top
                        float radiusFactor = 0.3f + normalizedY * 0.7f; // 0.3 at bottom, 1.0 at top
                        double radiusVariation = currentRadius * radiusFactor * (0.85f + r.nextFloat() * 0.3f);
                        
                        // Spiral angle - tight spiral for tornado effect
                        // More rotations at higher Y for tornado twist
                        double spiralTwist = normalizedY * Math.PI * 6; // 6 full rotations up the column
                        double angle = baseAngle + spiralTwist;
                        
                        // Add some chaos for tornado effect - particles don't follow perfect spiral
                        double chaosAngle = (r.nextDouble() - 0.5) * 0.3; // ±0.3 radians of chaos
                        angle += chaosAngle;
                        
                        // Position on tornado spiral
                        double x = Math.cos(angle) * radiusVariation;
                        double z = Math.sin(angle) * radiusVariation;
                        
                        Vec3 particlePos = playerPos.add(x, layerY, z);
                        
                        // Velocity - strong upward spiral motion for tornado
                        double upwardVel = 0.12 + normalizedY * 0.15; // Stronger upward at top
                        double spinVel = spinSpeed * 0.4; // Strong tangential velocity
                        double inwardVel = -0.02 * (1.0 - normalizedY); // Slight inward pull at bottom
                        
                        // Tangential velocity (perpendicular to radius)
                        double vx = Math.cos(angle + Math.PI/2) * spinVel + Math.cos(angle) * inwardVel;
                        double vy = upwardVel + (r.nextDouble() - 0.5) * 0.06; // Upward with variation
                        double vz = Math.sin(angle + Math.PI/2) * spinVel + Math.sin(angle) * inwardVel;
                        
                        // Choose random color
                        Vector3f color = colors[r.nextInt(colors.length)];
                        float size = 0.18f + r.nextFloat() * 0.25f; // Larger particles for visibility
                        
                        // Main confetti particle - dense tornado
                        level.addParticle(new DustParticleOptions(color, size),
                                particlePos.x, particlePos.y, particlePos.z,
                                vx, vy, vz);
                        
                        // Add more particles for density - tornado should be thick
                        if (r.nextFloat() < 0.4f && isActive.get()) {
                            // Secondary particle slightly offset
                            Vector3f altColor = colors[r.nextInt(colors.length)];
                            level.addParticle(new DustParticleOptions(altColor, size * 0.8f),
                                    particlePos.x + (r.nextDouble() - 0.5) * 0.1,
                                    particlePos.y + (r.nextDouble() - 0.5) * 0.1,
                                    particlePos.z + (r.nextDouble() - 0.5) * 0.1,
                                    vx * 0.9, vy * 0.9, vz * 0.9);
                        }
                        
                        // Add sparkle particles for tornado shimmer
                        if (r.nextFloat() < 0.2f && isActive.get()) {
                            level.addParticle(ParticleTypes.END_ROD,
                                    particlePos.x, particlePos.y, particlePos.z,
                                    vx * 0.6, vy * 0.6, vz * 0.6);
                        }
                    }
                });
            }
            
            // Final upward burst at the end - schedule BEFORE duration ends
            ClientScheduler.scheduleMs(durationMs - 150, () -> {
                // Double-check: both flag and elapsed time must be valid
                long elapsedMs = System.currentTimeMillis() - startTimeMs;
                if (!isActive.get() || elapsedMs >= durationMs) return; // Stop if duration exceeded
                
                Minecraft mc = Minecraft.getInstance();
                if (mc == null || mc.player == null || mc.level != level) return;
                
                Vec3 playerPos = mc.player.getEyePosition().add(0, -0.5, 0);
                
                // Play burst sound
                ResourceLocation burstSound = ResourceLocation.fromNamespaceAndPath("minecraft", "entity.firework_rocket.launch");
                playSound(level, playerPos, burstSound, 0.8f, 1.2f);
                
                // Massive upward burst
                for (int i = 0; i < 120; i++) {
                    // Check both flag and elapsed time during burst loop
                    long elapsedCheck = System.currentTimeMillis() - startTimeMs;
                    if (!isActive.get() || elapsedCheck >= durationMs) break; // Stop if duration exceeded
                    
                    double angle = r.nextDouble() * Math.PI * 2;
                    double radius = baseRadius * (0.5 + r.nextDouble() * 1.5);
                    double x = Math.cos(angle) * radius;
                    double z = Math.sin(angle) * radius;
                    
                    Vector3f color = colors[r.nextInt(colors.length)];
                    float size = 0.2f + r.nextFloat() * 0.3f;
                    
                    // Strong upward velocity
                    double vx = (r.nextDouble() - 0.5) * 0.15;
                    double vy = 0.3 + r.nextDouble() * 0.4; // Strong upward
                    double vz = (r.nextDouble() - 0.5) * 0.15;
                    
                    level.addParticle(new DustParticleOptions(color, size),
                            playerPos.x + x, playerPos.y, playerPos.z + z,
                            vx, vy, vz);
                    
                    // Add firework particles for the burst
                    if (r.nextFloat() < 0.3f && isActive.get()) {
                        level.addParticle(ParticleTypes.FIREWORK,
                                playerPos.x + x, playerPos.y, playerPos.z + z,
                                vx * 0.8, vy * 0.8, vz * 0.8);
                    }
                }
            });
        }

        static void expandingRing(ClientLevel level, Vec3 origin, Vec3 dir, long seed, float radius, int rings) {
            java.util.Random r = new java.util.Random(seed);
            Vec3 right = dir.cross(new Vec3(0,1,0)).normalize();
            Vec3 forward = dir.normalize();
            Vec3 up = right.cross(forward).normalize();
            for (int k=0;k<rings;k++){
                float rad = radius*(0.5f+k*0.5f);
                float delay = k * 0.1f; // Stagger rings slightly
                for (int i=0;i<72;i++){
                    double a = i/72.0*2*Math.PI;
                    Vec3 off = right.scale(Math.cos(a)*rad).add(up.scale(Math.sin(a)*rad));
                    // Add outward velocity for expansion effect
                    Vec3 vel = off.normalize().scale(0.05 + delay * 0.02);
                    level.addParticle(ParticleTypes.ELECTRIC_SPARK,origin.x+off.x,origin.y,origin.z+off.z,vel.x,0.02+delay*0.01,vel.z);
                    if (r.nextFloat() < 0.15f) {
                        level.addParticle(ParticleTypes.CRIT,origin.x+off.x,origin.y,origin.z+off.z,vel.x*0.5,0.015,vel.z*0.5);
                    }
                }
            }
        }

        static void helixStream(ClientLevel level, Vec3 origin, Vec3 dir, long seed, float length, int coils){
            java.util.Random r=new java.util.Random(seed);
            Vec3 right=dir.cross(new Vec3(0,1,0)); if(right.lengthSqr()<1e-4) right=dir.cross(new Vec3(1,0,0)); right=right.normalize();
            Vec3 up=right.cross(dir).normalize();
            int steps=100;
            for(int i=0;i<steps;i++){
                double t=i/(double)steps;
                double ang=t*coils*2*Math.PI;
                double radius = 0.5 + Math.sin(t * Math.PI) * 0.3; // Pulsing radius
                Vec3 pos=origin.add(dir.scale(length*t))
                        .add(right.scale(Math.cos(ang)*radius))
                        .add(up.scale(Math.sin(ang)*radius));
                // Add forward velocity along the helix
                Vec3 forwardVel = dir.scale(0.1);
                level.addParticle(ParticleTypes.ELECTRIC_SPARK,pos.x,pos.y,pos.z,forwardVel.x,0.01,forwardVel.z);
                if(r.nextFloat()<0.15f) level.addParticle(ParticleTypes.ENCHANT,pos.x,pos.y,pos.z,forwardVel.x*0.5,0.015,forwardVel.z*0.5);
                if(r.nextFloat()<0.05f) level.addParticle(ParticleTypes.CRIT,pos.x,pos.y,pos.z,0,0.01,0);
            }
        }

        static void fireflyOrbit(ClientLevel level, Vec3 origin, Vec3 dir, long seed, float radius){
            java.util.Random r=new java.util.Random(seed);
            int count=50;
            for(int i=0;i<count;i++){
                double a=i/(double)count*2*Math.PI;
                double height = 0.2 + Math.sin(a*3)*0.15; // Varying height
                Vec3 off=new Vec3(Math.cos(a)*radius,height,Math.sin(a)*radius);
                Vec3 pos=origin.add(off);
                // Orbital velocity
                Vec3 vel = new Vec3(-Math.sin(a)*0.02, 0.005, Math.cos(a)*0.02);
                level.addParticle(ParticleTypes.SPORE_BLOSSOM_AIR,pos.x,pos.y,pos.z,vel.x,vel.y,vel.z);
                if(i%8==0) level.addParticle(ParticleTypes.HAPPY_VILLAGER,pos.x,pos.y+0.1,pos.z,vel.x*0.5,0.03,vel.z*0.5);
                if(r.nextFloat()<0.1f) level.addParticle(ParticleTypes.END_ROD,pos.x,pos.y,pos.z,0,0.01,0);
            }
        }

        static void groundRipple(ClientLevel level, Vec3 origin, long seed, float radius){
            java.util.Random r=new java.util.Random(seed);
            for(int i=0;i<72;i++){
                double a=i/72.0*2*Math.PI;
                Vec3 pos=origin.add(Math.cos(a)*radius,0,Math.sin(a)*radius);
                level.addParticle(ParticleTypes.CLOUD,pos.x,pos.y+0.05,pos.z,0,0.01,0);
                if(r.nextFloat()<0.3f) level.addParticle(ParticleTypes.CRIT,pos.x,pos.y,pos.z,0,0.02,0);
            }
        }

        static void skyBeacon(ClientLevel level, Vec3 origin, long seed, float height){
            java.util.Random r=new java.util.Random(seed);
            int steps=(int)(height * 2);
            // Main beam column
            for(int i=0;i<steps;i++){
                double y=origin.y+i*0.5;
                // Dense core beam
                if(i%2==0) level.addParticle(ParticleTypes.END_ROD,origin.x, y, origin.z,0,0.05,0);
                if(i%3==0) level.addParticle(ParticleTypes.ENCHANT,origin.x, y, origin.z,0,0.02,0);
                // Add sparkles along the beam
                if(i%5==0 && r.nextFloat()<0.5f) {
                    level.addParticle(ParticleTypes.FIREWORK, origin.x, y, origin.z, 
                            (r.nextDouble()-0.5)*0.1, 0.01, (r.nextDouble()-0.5)*0.1);
                }
            }
            // Top burst effect
            for(int j=0;j<60;j++){
                double vx=(r.nextDouble()-0.5)*0.3, vz=(r.nextDouble()-0.5)*0.3;
                level.addParticle(ParticleTypes.FIREWORK,origin.x,origin.y+height,origin.z,vx,-0.15,vz);
                if(j%3==0) {
                    level.addParticle(ParticleTypes.HAPPY_VILLAGER, origin.x, origin.y+height, origin.z, 
                            vx*0.5, -0.1, vz*0.5);
                }
            }
        }

        static Vec3 randomCone(Vec3 dir, float coneRad, java.util.Random r) {
            double u=r.nextDouble(),v=r.nextDouble();
            double theta=2*Math.PI*u;
            double z=1 - v*(1-Math.cos(coneRad));
            double sinT=Math.sqrt(1 - z*z);
            Vec3 local=new Vec3(Math.cos(theta)*sinT,Math.sin(theta)*sinT,z);
            Vec3 w=dir.normalize();
            Vec3 uvec=w.cross(new Vec3(0,1,0));
            if(uvec.lengthSqr()<1e-4)uvec=w.cross(new Vec3(1,0,0));
            uvec=uvec.normalize();
            Vec3 vvec=w.cross(uvec).normalize();
            return uvec.scale(local.x).add(vvec.scale(local.y)).add(w.scale(local.z));
        }
    }

    // ------------------------------------------------------------------------
    // Client-side menu hold signaling + tiny scheduler
    // ------------------------------------------------------------------------
    @OnlyIn(Dist.CLIENT)
    public static final class ClientMenuHold {
        public interface Listener {
            void onHoldStart(String id);
            void onHoldEnd(String id);
        }
        private static final List<Listener> LISTENERS = new ArrayList<>();
        private static int HOLD_COUNT = 0;

        public static void register(Listener l) {
            if (l != null && !LISTENERS.contains(l)) LISTENERS.add(l);
        }
        static void notifyStart(String id) {
            HOLD_COUNT++;
            for (Listener l : LISTENERS) try { l.onHoldStart(id); } catch (Throwable ignored) {}
        }
        static void notifyEnd(String id) {
            if (HOLD_COUNT > 0) HOLD_COUNT--;
            for (Listener l : LISTENERS) try { l.onHoldEnd(id); } catch (Throwable ignored) {}
        }
        /** Check if any gadget effect is currently holding the menu. */
        public static boolean isHolding() {
            return HOLD_COUNT > 0;
        }
    }

    @Mod.EventBusSubscriber(modid = CosmeticsLite.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ClientScheduler {
        private static final Deque<Task> TASKS = new ArrayDeque<>();
        private record Task(int ticks, Runnable r) {}

        /** Schedule by ticks. */
        public static void schedule(int ticks, Runnable r) {
            if (ticks < 0 || r == null) return;
            synchronized (TASKS) { TASKS.addLast(new Task(ticks, r)); }
        }
        /** Schedule by milliseconds (converted to ticks). */
        public static void scheduleMs(long ms, Runnable r) {
            int t = (int)Math.max(1L, Math.min(Integer.MAX_VALUE, ms / 50L));
            schedule(t, r);
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            if (TASKS.isEmpty()) return;
            synchronized (TASKS) {
                int n = TASKS.size();
                for (int i = 0; i < n; i++) {
                    Task task = TASKS.pollFirst();
                    if (task == null) break;
                    int left = task.ticks - 1;
                    if (left <= 0) {
                        try { task.r.run(); } catch (Throwable ignored) {}
                    } else {
                        TASKS.addLast(new Task(left, task.r));
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // Client: 45s gadget gate to suppress unintended repeats
    // ------------------------------------------------------------------------
    @OnlyIn(Dist.CLIENT)
    private static final class ClientCooldownGate {
        private static final Map<ResourceLocation, Long> lastEndMsById = new HashMap<>();
        /** @return true if this play is allowed and registered; false to suppress. */
        static boolean begin(ResourceLocation id, long expectedDurationMs, int cooldownTicks) {
            long now = System.currentTimeMillis();
            long minGapMs = Math.max(expectedDurationMs, (long) cooldownTicks * 50L);
            long lastEnd = lastEndMsById.getOrDefault(id, 0L);
            if (now - lastEnd < minGapMs) return false;
            lastEndMsById.put(id, now + expectedDurationMs);
            // also schedule a cleanup to set it to "cooldown end" time
            long cooldownMs = (long) cooldownTicks * 50L;
            long finalEnd = now + Math.max(expectedDurationMs, cooldownMs);
            ClientScheduler.scheduleMs(finalEnd - now, () -> lastEndMsById.put(id, finalEnd));
            return true;
        }
    }

    // ------------------------------------------------------------------------
    // Confetti Burst (waves) — client-side only
    // ------------------------------------------------------------------------
    @OnlyIn(Dist.CLIENT)
    private static final class ConfettiBurstFx {

        static void play(ClientLevel level, Vec3 origin, Vec3 dir, CosmeticDef def, java.util.Random r) {
            // Prefer 'waves' array; fallback to 'waves_json'; final fallback = legacy single pop.
            String wavesRaw = Props.g(def, "waves");
            if (wavesRaw == null || wavesRaw.isBlank()) wavesRaw = Props.g(def, "waves_json");

            List<Wave> waves = parseWaves(wavesRaw);
            if (waves.isEmpty()) {
                // Legacy single colored-ish pop fallback (uses FIREWORK + HAPPY_VILLAGER)
                int count = Props.i(def, "count", 60);
                float coneDeg = Props.f(def, "cone_deg", 40f);
                float coneRad = (float)Math.toRadians(coneDeg);
                for (int i = 0; i < count; i++) {
                    Vec3 v = FastFx.randomCone(dir, coneRad, r).scale(0.35 + r.nextDouble() * 0.25);
                    level.addParticle(ParticleTypes.FIREWORK, origin.x, origin.y, origin.z, v.x, v.y * 0.6, v.z);
                    if (r.nextFloat() < 0.15f) {
                        level.addParticle(ParticleTypes.HAPPY_VILLAGER, origin.x, origin.y, origin.z, v.x * 0.2, v.y * 0.2, v.z * 0.2);
                    }
                }
                return;
            }

            // Schedule each wave by its delay
            for (Wave w : waves) {
                final Wave wave = w;
                ClientScheduler.scheduleMs(w.delayMs, () -> emitWave(level, origin, dir, wave, r));
            }
        }

        private static void emitWave(ClientLevel level, Vec3 origin, Vec3 dir, Wave w, java.util.Random r) {
            // Play fireworks sound for each wave with varied pitch
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                float pitch = 0.9f + r.nextFloat() * 0.3f; // Vary pitch between 0.9 and 1.2
                float volume = 0.5f + r.nextFloat() * 0.3f; // Vary volume between 0.5 and 0.8
                ResourceLocation sound = ResourceLocation.fromNamespaceAndPath("minecraft", "entity.firework_rocket.blast");
                FastFx.playSound(level, origin, sound, volume, pitch);
            }
            float coneRad = (float)Math.toRadians(w.coneDeg);
            for (int i = 0; i < w.count; i++) {
                Vec3 v = FastFx.randomCone(dir, coneRad, r).scale(w.speed * (0.85 + r.nextDouble() * 0.3));
                Vector3f color = w.colors.get(r.nextInt(w.colors.size()));
                float size = (float)(w.sizeMin + r.nextDouble() * Math.max(0.0, w.sizeMax - w.sizeMin));
                String shape = w.shapes.get(r.nextInt(w.shapes.size()));

                // Map "shape" to a spawn pattern. Enhanced with multiple particle types for more impact.
                switch (shape) {
                    case "streamer" -> {
                        // Long colorful trail with multiple particle types - make it very visible
                        Vec3 p = origin;
                        int trailLength = 8 + r.nextInt(5); // 8-12 particles per streamer for longer trails
                        for (int t = 0; t < trailLength; t++) {
                            p = p.add(v.scale(0.10 + r.nextDouble()*0.10));
                            float fade = 1.0f - (t / (float)trailLength) * 0.6f; // Less fade for more visibility
                            float trailSize = size * fade * 1.2f; // Make particles larger
                            
                            // Main colored dust particle - ensure it's visible
                            level.addParticle(new DustParticleOptions(color, trailSize),
                                    p.x, p.y, p.z, v.x * 0.15, v.y * 0.15, v.z * 0.15);
                            
                            // Add additional colored particles for density
                            if (t % 2 == 0) {
                                Vector3f altColor = w.colors.get(r.nextInt(w.colors.size()));
                                level.addParticle(new DustParticleOptions(altColor, trailSize * 0.8f),
                                        p.x + (r.nextDouble() - 0.5) * 0.1, 
                                        p.y + (r.nextDouble() - 0.5) * 0.1, 
                                        p.z + (r.nextDouble() - 0.5) * 0.1,
                                        v.x * 0.1, v.y * 0.1, v.z * 0.1);
                            }
                            
                            // Add sparkle effects every few particles
                            if (t % 3 == 0 && r.nextFloat() < 0.5f) {
                                level.addParticle(ParticleTypes.END_ROD, p.x, p.y, p.z, 
                                        v.x * 0.08, v.y * 0.08, v.z * 0.08);
                            }
                            // Add occasional firework particles for extra pop
                            if (t == trailLength - 1 && r.nextFloat() < 0.4f) {
                                level.addParticle(ParticleTypes.FIREWORK, p.x, p.y, p.z, 
                                        v.x * 0.25, v.y * 0.25, v.z * 0.25);
                            }
                        }
                    }
                    case "disc" -> {
                        // Enhanced disc with multiple particle layers - make it more colorful
                        float discSize = size * 1.3f; // Larger for visibility
                        level.addParticle(new DustParticleOptions(color, discSize),
                                origin.x, origin.y, origin.z, v.x, v.y * 0.5, v.z);
                        // Add a second layer with a different color for depth
                        if (r.nextFloat() < 0.6f) {
                            Vector3f altColor = w.colors.get(r.nextInt(w.colors.size()));
                            level.addParticle(new DustParticleOptions(altColor, discSize * 0.7f),
                                    origin.x + (r.nextDouble() - 0.5) * 0.15, 
                                    origin.y + (r.nextDouble() - 0.5) * 0.15, 
                                    origin.z + (r.nextDouble() - 0.5) * 0.15,
                                    v.x * 0.8, v.y * 0.4, v.z * 0.8);
                        }
                        // Add sparkle accent
                        if (r.nextFloat() < 0.4f) {
                            level.addParticle(ParticleTypes.END_ROD, origin.x, origin.y, origin.z, 
                                    v.x * 0.12, v.y * 0.12, v.z * 0.12);
                        }
                    }
                    case "triangle", "rect" -> {
                        // Enhanced shapes with more visual variety and color
                        double damp = shape.equals("triangle") ? 0.65 : 0.8;
                        float shapeSize = size * 1.2f; // Larger for visibility
                        level.addParticle(new DustParticleOptions(color, shapeSize),
                                origin.x, origin.y, origin.z, v.x * damp, v.y * damp, v.z * damp);
                        // Add a second particle with different color for variety
                        if (r.nextFloat() < 0.5f) {
                            Vector3f altColor = w.colors.get(r.nextInt(w.colors.size()));
                            level.addParticle(new DustParticleOptions(altColor, shapeSize * 0.75f),
                                    origin.x + (r.nextDouble() - 0.5) * 0.12, 
                                    origin.y + (r.nextDouble() - 0.5) * 0.12, 
                                    origin.z + (r.nextDouble() - 0.5) * 0.12,
                                    v.x * damp * 0.9, v.y * damp * 0.9, v.z * damp * 0.9);
                        }
                        // Add occasional firework burst
                        if (r.nextFloat() < 0.3f) {
                            level.addParticle(ParticleTypes.FIREWORK, origin.x, origin.y, origin.z, 
                                    v.x * 0.35, v.y * 0.35, v.z * 0.35);
                        }
                    }
                    default -> {
                        // Default with enhanced visuals and color
                        float defaultSize = size * 1.2f; // Larger for visibility
                        level.addParticle(new DustParticleOptions(color, defaultSize),
                                origin.x, origin.y, origin.z, v.x, v.y, v.z);
                        // Add a second colored particle for density
                        if (r.nextFloat() < 0.4f) {
                            Vector3f altColor = w.colors.get(r.nextInt(w.colors.size()));
                            level.addParticle(new DustParticleOptions(altColor, defaultSize * 0.8f),
                                    origin.x + (r.nextDouble() - 0.5) * 0.1, 
                                    origin.y + (r.nextDouble() - 0.5) * 0.1, 
                                    origin.z + (r.nextDouble() - 0.5) * 0.1,
                                    v.x * 0.9, v.y * 0.9, v.z * 0.9);
                        }
                        if (r.nextFloat() < 0.25f) {
                            level.addParticle(ParticleTypes.END_ROD, origin.x, origin.y, origin.z, 
                                    v.x * 0.15, v.y * 0.15, v.z * 0.15);
                        }
                    }
                }
            }
        }

        private static List<Wave> parseWaves(@Nullable String json) {
            if (json == null || json.isBlank()) {
                CosmeticsLite.LOGGER.warn("[CosmeticsLite] ConfettiBurstFx: waves_json is null or blank");
                return Collections.emptyList();
            }
            try {
                // Handle escaped JSON strings (from JSON file storage)
                String cleaned = json.trim();
                
                // Remove outer quotes if present
                if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
                    cleaned = cleaned.substring(1, cleaned.length() - 1);
                }
                
                // Unescape all escape sequences (handle escaped JSON from file)
                // The JSON parser already unescapes once, so we get \" in the string
                // We need to unescape those to get actual quotes
                cleaned = cleaned.replace("\\\"", "\"");  // Unescape quotes
                cleaned = cleaned.replace("\\\\", "\\");  // Unescape backslashes (if any remain)
                
                CosmeticsLite.LOGGER.debug("[CosmeticsLite] ConfettiBurstFx: Parsing waves_json (length: {})", cleaned.length());
                
                JsonElement root = JsonParser.parseString(cleaned);
                JsonArray arr = root.isJsonArray() ? root.getAsJsonArray() : null;
                if (arr == null || arr.size() == 0) {
                    CosmeticsLite.LOGGER.warn("[CosmeticsLite] ConfettiBurstFx: waves_json parsed but not an array or empty. Root type: {}", 
                            root != null ? root.getClass().getSimpleName() : "null");
                    return Collections.emptyList();
                }

                List<Wave> out = new ArrayList<>(arr.size());
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    Wave w = new Wave();
                    w.delayMs = intOr(o, "delay_ms", 0);
                    w.count   = intOr(o, "count", 32);
                    w.coneDeg = floatOr(o,"cone_deg", 65f);
                    w.speed   = floatOr(o,"speed", 1.0f);
                    w.sizeMin = floatOr(o,"size_min", 0.10f);
                    w.sizeMax = floatOr(o,"size_max", 0.20f);
                    w.shapes  = strList(o, "shapes", List.of("disc","rect"));
                    w.colors  = colorList(o, "colors",
                            List.of("#ffd166","#ef476f","#06d6a0","#118ab2"));
                    // guards
                    if (w.sizeMax < w.sizeMin) {
                        float tmp = w.sizeMin; w.sizeMin = w.sizeMax; w.sizeMax = tmp;
                    }
                    if (w.count < 1) w.count = 1;
                    out.add(w);
                }
                CosmeticsLite.LOGGER.info("[CosmeticsLite] ConfettiBurstFx: Successfully parsed {} wave(s)", out.size());
                return out;
            } catch (Throwable t) {
                CosmeticsLite.LOGGER.error("[CosmeticsLite] ConfettiBurstFx: Failed to parse waves_json", t);
                return Collections.emptyList();
            }
        }

        private static int intOr(JsonObject o, String k, int d) {
            try { return o.has(k) ? o.get(k).getAsInt() : d; } catch (Exception e) { return d; }
        }
        private static float floatOr(JsonObject o, String k, float d) {
            try { return o.has(k) ? o.get(k).getAsFloat() : d; } catch (Exception e) { return d; }
        }
        private static List<String> strList(JsonObject o, String k, List<String> d) {
            try {
                if (!o.has(k) || !o.get(k).isJsonArray()) return d;
                List<String> out = new ArrayList<>();
                for (JsonElement e : o.get(k).getAsJsonArray()) out.add(e.getAsString());
                return out.isEmpty() ? d : out;
            } catch (Exception e) { return d; }
        }
        private static List<Vector3f> colorList(JsonObject o, String k, List<String> defHex) {
            List<String> hexes = defHex;
            try {
                if (o.has(k) && o.get(k).isJsonArray()) {
                    hexes = new ArrayList<>();
                    for (JsonElement e : o.get(k).getAsJsonArray()) hexes.add(e.getAsString());
                }
            } catch (Exception ignored) {}
            List<Vector3f> out = new ArrayList<>();
            for (String h : hexes) out.add(hexToColor(h));
            if (out.isEmpty()) out.add(new Vector3f(1,1,1));
            return out;
        }

        private static Vector3f hexToColor(String hex) {
            String s = hex.trim();
            if (s.startsWith("#")) s = s.substring(1);
            try {
                int v = (int)Long.parseLong(s, 16);
                int r = (s.length() >= 6) ? ((v >> 16) & 0xFF) : 255;
                int g = (s.length() >= 6) ? ((v >> 8)  & 0xFF) : 255;
                int b = (s.length() >= 6) ? (v & 0xFF) : 255;
                return new Vector3f(r / 255f, g / 255f, b / 255f);
            } catch (Exception e) {
                return new Vector3f(1f,1f,1f);
            }
        }

        private static final class Wave {
            int delayMs;
            int count;
            float coneDeg;
            float speed;
            float sizeMin, sizeMax;
            List<String> shapes;
            List<Vector3f> colors;
        }
    }

    // ------------------------------------------------------------------------
    // Props + helpers
    // ------------------------------------------------------------------------
    private static final class Props {
        static int i(@Nullable CosmeticDef def,String key,int d){try{String s=g(def,key);return s==null?d:Integer.parseInt(s);}catch(Exception ignored){return d;}}
        static float f(@Nullable CosmeticDef def,String key,float d){try{String s=g(def,key);return s==null?d:Float.parseFloat(s);}catch(Exception ignored){return d;}}
        static ResourceLocation rl(@Nullable CosmeticDef def,String key,@Nullable ResourceLocation d){try{String s=g(def,key);ResourceLocation r=(s==null)?null:ResourceLocation.tryParse(s);return r==null?d:r;}catch(Exception ignored){return d;}}
        static String g(@Nullable CosmeticDef def,String key){Map<String,String> p=(def==null)?null:def.properties();return(p==null)?null:p.get(key);}
        static long ms(@Nullable CosmeticDef def, String key, long d) {
            try {
                String raw = g(def, key);
                if (raw == null || raw.isBlank()) return d;
                return parseMs(raw, d);
            } catch (Exception ignored) { return d; }
        }
        static long msGuess(@Nullable CosmeticDef def, String... keys) {
            if (def == null) return 0L;
            for (String k : keys) {
                String s = g(def, k);
                if (s != null && !s.isBlank()) return parseMs(s, 0L);
            }
            return 0L;
        }
        private static long parseMs(String s, long dflt) {
            String raw = s.trim().toLowerCase(Locale.ROOT);
            try {
                if (raw.endsWith("ms")) return Math.max(0L, Long.parseLong(raw.substring(0, raw.length()-2).trim()));
                if (raw.endsWith("t"))  return Math.max(0L, Long.parseLong(raw.substring(0, raw.length()-1).trim()) * 50L);
                if (raw.endsWith("s"))  return Math.max(0L, Long.parseLong(raw.substring(0, raw.length()-1).trim()) * 1000L);
                long v = Long.parseLong(raw);
                if (v <= 0) return 0L;
                // Heuristic: small → seconds, large → ticks
                return (v <= 120L) ? v * 1000L : v * 50L;
            } catch (Exception ignored) { return dflt; }
        }
    }

    private static ResourceLocation rl(String ns,String path){return ResourceLocation.fromNamespaceAndPath(ns,path);}
}
