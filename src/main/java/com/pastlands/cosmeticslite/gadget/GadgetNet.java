// src/main/java/com/pastlands/cosmeticslite/gadget/GadgetNet.java
package com.pastlands.cosmeticslite.gadget;

import com.pastlands.cosmeticslite.CosmeticDef;
import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.CosmeticsRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.core.registries.BuiltInRegistries;

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
 */
public final class GadgetNet {
    private GadgetNet() {}

    private static final String PROTOCOL = "1";
    private static final ResourceLocation CHANNEL_NAME =
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "gadget");

    private static SimpleChannel CHANNEL;
    private static int NEXT_ID = 0;
    private static boolean BOOTSTRAPPED = false;

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

                int cooldown = action.cooldownTicks(sp, def);
                if (!checkAndStampCooldown(sp, msg.gadgetId, now, cooldown)) return;

                long seed = sp.getRandom().nextLong();
                Vec3 origin = sp.getEyePosition();
                Vec3 dir = sp.getLookAngle().normalize();

                // Server-side interaction hook (safe, optional)
                try { action.serverPerform(sp, origin, dir, seed); } catch (Throwable ignored) {}

                channel().send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> sp),
                        new PlayGadgetFxS2C(msg.gadgetId, origin, dir, seed));
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
                                         long seed) {

        public PlayGadgetFxS2C(ResourceLocation id, Vec3 origin, Vec3 dir, long seed) {
            this(id, origin.x, origin.y, origin.z, (float) dir.x, (float) dir.y, (float) dir.z, seed);
        }

        public static void encode(PlayGadgetFxS2C msg, FriendlyByteBuf buf) {
            buf.writeResourceLocation(msg.gadgetId);
            buf.writeDouble(msg.x); buf.writeDouble(msg.y); buf.writeDouble(msg.z);
            buf.writeFloat(msg.dx); buf.writeFloat(msg.dy); buf.writeFloat(msg.dz);
            buf.writeLong(msg.seed);
        }

        public static PlayGadgetFxS2C decode(FriendlyByteBuf buf) {
            ResourceLocation id = buf.readResourceLocation();
            double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
            float dx = buf.readFloat(), dy = buf.readFloat(), dz = buf.readFloat();
            long seed = buf.readLong();
            return new PlayGadgetFxS2C(id, x, y, z, dx, dy, dz, seed);
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
        int cooldownTicks(ServerPlayer sp, @Nullable CosmeticDef def);
        @OnlyIn(Dist.CLIENT)
        void clientFx(ClientLevel level, Vec3 origin, Vec3 dir, long seed, CosmeticDef def);
    }

    /** JSON-driven action: reads 'pattern' and simple knobs from CosmeticDef.properties. */
    private static final class GenericGadgetAction implements IGadgetAction {
        @Override
        public int cooldownTicks(ServerPlayer sp, @Nullable CosmeticDef def) {
            // Prefer explicit ms (cooldown_ms), then ticks/seconds heuristics; default ~45s.
            int fallbackTicks = 45 * 20;
            long ms = Props.ms(def, "cooldown_ms",
                    Props.msGuess(def, "cooldown", "cooldown_ticks", "cooldown_seconds", "cooldown_s"));
            return (ms > 0) ? (int)Math.max(1, ms / 50L) : fallbackTicks;
        }

        @Override
        @OnlyIn(Dist.CLIENT)
        public void clientFx(ClientLevel level, Vec3 origin, Vec3 dir, long seed, CosmeticDef def) {
            String pattern = Props.g(def, "pattern");
            if (pattern == null || pattern.isEmpty()) {
                // Nothing to do; keep silent.
                return;
            }

            long durationMs = Props.ms(def, "duration_ms", 1200L);
            if (durationMs < 300L) durationMs = 300L;
            if (durationMs > 6000L) durationMs = 6000L;

            // Notify UI to hold while this effect runs.
            ClientMenuHold.notifyStart(def.id().toString());
            ClientScheduler.scheduleMs(durationMs + 50L, () ->
                    ClientMenuHold.notifyEnd(def.id().toString()));

            // Optional sound
            ResourceLocation snd = Props.rl(def, "sound", null);
            float vol = Props.f(def, "volume", 1.0f);
            float pit = Props.f(def, "pitch", 1.0f);
            if (snd != null) FastFx.playSound(level, origin, snd, vol, pit);

            // Pattern dispatch (initial set—expand as we migrate)
            java.util.Random r = new java.util.Random(seed);
            switch (pattern) {
                case "confetti_burst" -> {
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
                }
                case "spark_fan" -> {
                    int count = Props.i(def, "count", 40);
                    float arcDeg = Props.f(def, "arc_deg", 60f);
                    double arcRad = Math.toRadians(arcDeg);
                    Vec3 f = dir.normalize();
                    Vec3 right = f.cross(new Vec3(0,1,0));
                    if (right.lengthSqr() < 1e-4) right = f.cross(new Vec3(1,0,0));
                    right = right.normalize();
                    for (int i = 0; i < count; i++) {
                        double a = (r.nextDouble() - 0.5) * arcRad;
                        double ca = Math.cos(a), sa = Math.sin(a);
                        Vec3 sweep = f.scale(ca).add(right.scale(sa)).normalize().scale(0.4 + r.nextDouble() * 0.2);
                        level.addParticle(ParticleTypes.ELECTRIC_SPARK, origin.x, origin.y + 0.2, origin.z, sweep.x, sweep.y * 0.3, sweep.z);
                        if (r.nextFloat() < 0.5f) {
                            level.addParticle(ParticleTypes.CRIT, origin.x, origin.y + 0.2, origin.z, sweep.x * 0.6, sweep.y * 0.2, sweep.z * 0.6);
                        }
                    }
                }
                case "ring" -> {
                    float radius = Props.f(def, "radius_max", 6f);
                    int rings = Props.i(def, "rings", 2);
                    FastFx.expandingRing(level, origin, dir, seed, radius, rings);
                }
                case "helix" -> {
                    float length = Props.f(def, "length", 12f);
                    int coils = Props.i(def, "coils", 3);
                    FastFx.helixStream(level, origin, dir, seed, length, coils);
                }
                case "orbit" -> {
                    float radius = Props.f(def, "radius_max", 2.5f);
                    FastFx.fireflyOrbit(level, origin, dir, seed, radius);
                }
                case "beacon" -> {
                    float height = Props.f(def, "height", 25f);
                    FastFx.skyBeacon(level, origin, seed, height);
                }
                default -> {
                    // Unknown pattern: do a tiny neutral pop so it never feels broken.
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
            // ----------------------------------------------------------------
            // PHASE 1: Hardwired baseline (kept for continuity)
            // ----------------------------------------------------------------

            // 1) confetti_popper
            register(rl("cosmeticslite","confetti_popper"), new IGadgetAction() {
                @Override public int cooldownTicks(ServerPlayer sp, @Nullable CosmeticDef def) {
                    return Props.i(def, "cooldown", 600);
                }
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
                @Override public int cooldownTicks(ServerPlayer sp, @Nullable CosmeticDef def) {
                    return Props.i(def, "cooldown", 400);
                }
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

            // 3) gear_spark_emitter
            register(rl("cosmeticslite","gear_spark_emitter"), new IGadgetAction() {
                @Override public int cooldownTicks(ServerPlayer sp, @Nullable CosmeticDef def) {
                    return Props.i(def, "cooldown", 500);
                }
                @Override @OnlyIn(Dist.CLIENT)
                public void clientFx(ClientLevel level, Vec3 origin, Vec3 dir, long seed, CosmeticDef def) {
                    final int count = Props.i(def, "count", 40);
                    final float arcDeg = Props.f(def, "arc_deg", 60f);
                    final var snd = Props.rl(def, "sound", rl("minecraft","block.anvil.place"));
                    final float vol = Props.f(def, "volume", 0.9f), pit = Props.f(def, "pitch", 0.9f);

                    java.util.Random r = new java.util.Random(seed);
                    double arcRad = Math.toRadians(arcDeg);
                    for (int i = 0; i < count; i++) {
                        double a = (r.nextDouble() - 0.5) * arcRad;
                        double ca = Math.cos(a), sa = Math.sin(a);
                        Vec3 f = dir.normalize();
                        Vec3 right = f.cross(new Vec3(0,1,0));
                        if (right.lengthSqr() < 1e-4) right = f.cross(new Vec3(1,0,0));
                        right = right.normalize();
                        Vec3 sweep = f.scale(ca).add(right.scale(sa)).normalize().scale(0.4 + r.nextDouble() * 0.2);

                        level.addParticle(ParticleTypes.ELECTRIC_SPARK, origin.x, origin.y + 0.2, origin.z, sweep.x, sweep.y * 0.3, sweep.z);
                        if (r.nextFloat() < 0.5f) {
                            level.addParticle(ParticleTypes.CRIT, origin.x, origin.y + 0.2, origin.z, sweep.x * 0.6, sweep.y * 0.2, sweep.z * 0.6);
                        }
                    }
                    FastFx.playSound(level, origin, snd, vol, pit);
                }
            });

            // ---- Cinematic presets (no GadgetTiming; use FastFx helpers you already have)
            register(rl("cosmeticslite","supernova_burst"), new BasicFx(45*20) {
                @Override @OnlyIn(Dist.CLIENT) public void fx(ClientLevel level, Vec3 origin, Vec3 dir, long seed, CosmeticDef def) {
                    FastFx.starburstRays(level, origin, dir, seed, Props.i(def,"rays",16), Props.f(def,"length",16f));
                    FastFx.playSound(level, origin, Props.rl(def,"sound",rl("minecraft","entity.firework_rocket.blast")),
                            Props.f(def,"volume",1.0f), Props.f(def,"pitch",0.9f));
                }
            });
            register(rl("cosmeticslite","expanding_ring"), new BasicFx(45*20) {
                @Override @OnlyIn(Dist.CLIENT) public void fx(ClientLevel level, Vec3 origin, Vec3 dir, long seed, CosmeticDef def) {
                    FastFx.expandingRing(level, origin, dir, seed, Props.f(def,"radius_max",8f), Props.i(def,"rings",2));
                    FastFx.playSound(level, origin, Props.rl(def,"sound",rl("minecraft","block.amethyst_block.chime")),
                            Props.f(def,"volume",0.7f), Props.f(def,"pitch",1.1f));
                }
            });
            register(rl("cosmeticslite","helix_stream"), new BasicFx(45*20) {
                @Override @OnlyIn(Dist.CLIENT) public void fx(ClientLevel level, Vec3 origin, Vec3 dir, long seed, CosmeticDef def) {
                    FastFx.helixStream(level, origin, dir, seed, Props.f(def,"length",12f), Props.i(def,"coils",3));
                    FastFx.playSound(level, origin, Props.rl(def,"sound",rl("minecraft","block.beacon.power_select")),
                            Props.f(def,"volume",0.8f), Props.f(def,"pitch",1.2f));
                }
            });
            register(rl("cosmeticslite","firefly_orbit"), new BasicFx(45*20) {
                @Override @OnlyIn(Dist.CLIENT) public void fx(ClientLevel level, Vec3 origin, Vec3 dir, long seed, CosmeticDef def) {
                    FastFx.fireflyOrbit(level, origin, dir, seed, Props.f(def,"radius_max",2.5f));
                    FastFx.playSound(level, origin, Props.rl(def,"sound",rl("minecraft","block.amethyst_block.hit")),
                            Props.f(def,"volume",0.6f), Props.f(def,"pitch",1.2f));
                }
            });
            register(rl("cosmeticslite","ground_ripple"), new BasicFx(45*20) {
                @Override @OnlyIn(Dist.CLIENT) public void fx(ClientLevel level, Vec3 origin, Vec3 dir, long seed, CosmeticDef def) {
                    FastFx.groundRipple(level, origin, seed, Props.f(def,"radius_max",6f));
                    FastFx.playSound(level, origin, Props.rl(def,"sound",rl("minecraft","block.basalt.break")),
                            Props.f(def,"volume",0.9f), Props.f(def,"pitch",0.9f));
                }
            });
            register(rl("cosmeticslite","sky_beacon"), new BasicFx(60*20) {
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
            // PHASE 2: Apply JSON overrides
            // If a gadget has properties.pattern, bind that id to GENERIC.
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

        /** Tiny base for one-shot FX with simple cooldown field. */
        private static abstract class BasicFx implements IGadgetAction {
            private final int cooldownTicks;
            BasicFx(int cooldownTicks) { this.cooldownTicks = Math.max(1, cooldownTicks); }
            @Override public int cooldownTicks(ServerPlayer sp, @Nullable CosmeticDef def) {
                int cd = Props.i(def, "cooldown", cooldownTicks);
                return Math.max(1, cd);
            }
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
            if (action != null) action.clientFx(level, origin, dir, msg.seed(), def);
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
            for (int i = 0; i < rays; i++) {
                Vec3 v = randomCone(dir, (float)Math.toRadians(180f), r).normalize().scale(length * (0.8 + r.nextDouble()*0.2));
                for (int j = 0; j < 6; j++) {
                    Vec3 p = origin.add(v.scale(j/6.0));
                    level.addParticle(ParticleTypes.END_ROD, p.x, p.y, p.z, 0,0,0);
                    if (j==5) level.addParticle(ParticleTypes.HAPPY_VILLAGER,p.x,p.y,p.z,0,0.02,0);
                }
            }
        }

        static void expandingRing(ClientLevel level, Vec3 origin, Vec3 dir, long seed, float radius, int rings) {
            java.util.Random r = new java.util.Random(seed);
            Vec3 right = dir.cross(new Vec3(0,1,0)).normalize();
            Vec3 forward = dir.normalize();
            Vec3 up = right.cross(forward).normalize();
            for (int k=0;k<rings;k++){
                float rad = radius*(0.5f+k*0.5f);
                for (int i=0;i<64;i++){
                    double a = i/64.0*2*Math.PI;
                    Vec3 off = right.scale(Math.cos(a)*rad).add(up.scale(Math.sin(a)*rad));
                    level.addParticle(ParticleTypes.ELECTRIC_SPARK,origin.x+off.x,origin.y,origin.z+off.z,0,0.02,0);
                }
            }
        }

        static void helixStream(ClientLevel level, Vec3 origin, Vec3 dir, long seed, float length, int coils){
            java.util.Random r=new java.util.Random(seed);
            Vec3 right=dir.cross(new Vec3(0,1,0)); if(right.lengthSqr()<1e-4) right=dir.cross(new Vec3(1,0,0)); right=right.normalize();
            Vec3 up=right.cross(dir).normalize();
            int steps=80;
            for(int i=0;i<steps;i++){
                double t=i/(double)steps;
                double ang=t*coils*2*Math.PI;
                Vec3 pos=origin.add(dir.scale(length*t))
                        .add(right.scale(Math.cos(ang)*0.6))
                        .add(up.scale(Math.sin(ang)*0.6));
                level.addParticle(ParticleTypes.ELECTRIC_SPARK,pos.x,pos.y,pos.z,0,0,0);
                if(r.nextFloat()<0.1f) level.addParticle(ParticleTypes.ENCHANT,pos.x,pos.y,pos.z,0,0.02,0);
            }
        }

        static void fireflyOrbit(ClientLevel level, Vec3 origin, Vec3 dir, long seed, float radius){
            java.util.Random r=new java.util.Random(seed);
            int count=40;
            for(int i=0;i<count;i++){
                double a=i/(double)count*2*Math.PI;
                Vec3 off=new Vec3(Math.cos(a)*radius,0.1+Math.sin(a*2)*0.1,Math.sin(a)*radius);
                Vec3 pos=origin.add(off);
                level.addParticle(ParticleTypes.SPORE_BLOSSOM_AIR,pos.x,pos.y,pos.z,0,0.01,0);
                if(i%10==0) level.addParticle(ParticleTypes.HAPPY_VILLAGER,pos.x,pos.y+0.2,pos.z,0,0.05,0);
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
            int steps=(int)height;
            for(int i=0;i<steps;i++){
                double y=origin.y+i*0.5;
                if(i%2==0) level.addParticle(ParticleTypes.END_ROD,origin.x, y, origin.z,0,0.05,0);
                if(i%4==0) level.addParticle(ParticleTypes.ENCHANT,origin.x, y, origin.z,0,0.02,0);
            }
            for(int j=0;j<40;j++){
                double vx=(r.nextDouble()-0.5)*0.2, vz=(r.nextDouble()-0.5)*0.2;
                level.addParticle(ParticleTypes.FIREWORK,origin.x,origin.y+height,origin.z,vx,-0.1,vz);
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

        public static void register(Listener l) {
            if (l != null && !LISTENERS.contains(l)) LISTENERS.add(l);
        }
        static void notifyStart(String id) {
            for (Listener l : LISTENERS) try { l.onHoldStart(id); } catch (Throwable ignored) {}
        }
        static void notifyEnd(String id) {
            for (Listener l : LISTENERS) try { l.onHoldEnd(id); } catch (Throwable ignored) {}
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
