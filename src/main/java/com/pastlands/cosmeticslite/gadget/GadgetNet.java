// src/main/java/com/pastlands/cosmeticslite/gadget/GadgetNet.java
package com.pastlands.cosmeticslite.gadget;

import com.pastlands.cosmeticslite.CosmeticDef;
import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.CosmeticsRegistry;
import com.pastlands.cosmeticslite.PlayerData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraft.core.registries.BuiltInRegistries;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Networking + action dispatch for active-use gadgets.
 * - Server validates gadget id + cooldown, then broadcasts S2C FX.
 * - Client renders FX based on JSON properties + helper effects below.
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

                action.serverPerform(sp, origin, dir, seed);
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
    // Action registry
    // ------------------------------------------------------------------------
    public interface IGadgetAction {
        default void serverPerform(ServerPlayer sp, Vec3 origin, Vec3 dir, long seed) {}
        int cooldownTicks(ServerPlayer sp, @Nullable CosmeticDef def);
        @OnlyIn(Dist.CLIENT)
        void clientFx(ClientLevel level, Vec3 origin, Vec3 dir, long seed, CosmeticDef def);
    }

    public static final class GadgetActions {
        private static final Map<ResourceLocation, IGadgetAction> REGISTRY = new ConcurrentHashMap<>();
        public static void register(ResourceLocation id, IGadgetAction action) { REGISTRY.put(id, action); }
        public static IGadgetAction get(ResourceLocation id) { return REGISTRY.get(id); }
		// Map an alias id to the same action instance as a target id.
    private static void aliasTo(ResourceLocation alias, ResourceLocation target) {
        IGadgetAction base = REGISTRY.get(target);
        if (base != null) {
            REGISTRY.put(alias, base);
        }
    }

        public static void bootstrapDefaults() {
            // ----------------------------------------------------------------
            // PHASE 1: Core Gadgets
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

            // ----------------------------------------------------------------
            // PHASE 2: Cinematic Gadgets
            // ----------------------------------------------------------------
            register(rl("cosmeticslite","supernova_burst"), new IGadgetAction() {
                @Override public int cooldownTicks(ServerPlayer sp,@Nullable CosmeticDef def){return Props.i(def,"cooldown",1000);}
                @Override @OnlyIn(Dist.CLIENT)
                public void clientFx(ClientLevel level,Vec3 origin,Vec3 dir,long seed,CosmeticDef def){
                    final GadgetTiming t=GadgetTiming.from(def.properties());
                    final int rays=Props.i(def,"rays",16);
                    final float length=Props.f(def,"length",16f);
                    final var snd=Props.rl(def,"sound",rl("minecraft","entity.firework_rocket.blast"));
                    final float vol=Props.f(def,"volume",1.0f),pit=Props.f(def,"pitch",0.9f);
                    final long[] burst={0};
                    GadgetTiming.ClientBurstScheduler.scheduleBursts(t,()->{
                        long s=seed^(0x9E3779B97F4A7C15L*++burst[0]);
                        FastFx.starburstRays(level,origin,dir,s,rays,length);
                        FastFx.playSound(level,origin,snd,vol,pit);
                    });
                }
            });

            register(rl("cosmeticslite","expanding_ring"), new IGadgetAction() {
                @Override public int cooldownTicks(ServerPlayer sp,@Nullable CosmeticDef def){return Props.i(def,"cooldown",900);}
                @Override @OnlyIn(Dist.CLIENT)
                public void clientFx(ClientLevel level,Vec3 origin,Vec3 dir,long seed,CosmeticDef def){
                    final GadgetTiming t=GadgetTiming.from(def.properties());
                    final float radius=Props.f(def,"radius_max",8f);
                    final int rings=Props.i(def,"rings",2);
                    final var snd=Props.rl(def,"sound",rl("minecraft","block.amethyst_block.chime"));
                    final float vol=Props.f(def,"volume",0.7f),pit=Props.f(def,"pitch",1.1f);
                    final long[] burst={0};
                    GadgetTiming.ClientBurstScheduler.scheduleBursts(t,()->{
                        long s=seed^(0xC2B2AE3D27D4EB4FL*++burst[0]);
                        FastFx.expandingRing(level,origin,dir,s,radius,rings);
                        FastFx.playSound(level,origin,snd,vol,pit);
                    });
                }
            });

            register(rl("cosmeticslite","helix_stream"), new IGadgetAction() {
                @Override public int cooldownTicks(ServerPlayer sp,@Nullable CosmeticDef def){return Props.i(def,"cooldown",700);}
                @Override @OnlyIn(Dist.CLIENT)
                public void clientFx(ClientLevel level,Vec3 origin,Vec3 dir,long seed,CosmeticDef def){
                    final GadgetTiming t=GadgetTiming.from(def.properties());
                    final float length=Props.f(def,"length",12f);
                    final int coils=Props.i(def,"coils",3);
                    final var snd=Props.rl(def,"sound",rl("minecraft","block.beacon.power_select"));
                    final float vol=Props.f(def,"volume",0.8f),pit=Props.f(def,"pitch",1.2f);
                    final long[] burst={0};
                    GadgetTiming.ClientBurstScheduler.scheduleBursts(t,()->{
                        long s=seed^(0x165667B19E3779F9L*++burst[0]);
                        FastFx.helixStream(level,origin,dir,s,length,coils);
                        FastFx.playSound(level,origin,snd,vol,pit);
                    });
                }
            });

            register(rl("cosmeticslite","firefly_orbit"), new IGadgetAction() {
                @Override public int cooldownTicks(ServerPlayer sp,@Nullable CosmeticDef def){return Props.i(def,"cooldown",800);}
                @Override @OnlyIn(Dist.CLIENT)
                public void clientFx(ClientLevel level,Vec3 origin,Vec3 dir,long seed,CosmeticDef def){
                    final GadgetTiming t=GadgetTiming.from(def.properties());
                    final float radius=Props.f(def,"radius_max",2.5f);
                    final var snd=Props.rl(def,"sound",rl("minecraft","block.amethyst_block.hit"));
                    final float vol=Props.f(def,"volume",0.6f),pit=Props.f(def,"pitch",1.2f);
                    final long[] burst={0};
                    GadgetTiming.ClientBurstScheduler.scheduleBursts(t,()->{
                        long s=seed^(0xA54FF53AL*++burst[0]);
                        FastFx.fireflyOrbit(level,origin,dir,s,radius);
                        FastFx.playSound(level,origin,snd,vol,pit);
                    });
                }
            });

            register(rl("cosmeticslite","ground_ripple"), new IGadgetAction() {
                @Override public int cooldownTicks(ServerPlayer sp,@Nullable CosmeticDef def){return Props.i(def,"cooldown",1000);}
                @Override @OnlyIn(Dist.CLIENT)
                public void clientFx(ClientLevel level,Vec3 origin,Vec3 dir,long seed,CosmeticDef def){
                    final GadgetTiming t=GadgetTiming.from(def.properties());
                    final float radius=Props.f(def,"radius_max",6f);
                    final var snd=Props.rl(def,"sound",rl("minecraft","block.basalt.break"));
                    final float vol=Props.f(def,"volume",0.9f),pit=Props.f(def,"pitch",0.9f);
                    final long[] burst={0};
                    GadgetTiming.ClientBurstScheduler.scheduleBursts(t,()->{
                        long s=seed^(0x3C6EF372L*++burst[0]);
                        FastFx.groundRipple(level,origin,s,radius);
                        FastFx.playSound(level,origin,snd,vol,pit);
                    });
                }
            });

            register(rl("cosmeticslite","sky_beacon"), new IGadgetAction() {
                @Override public int cooldownTicks(ServerPlayer sp,@Nullable CosmeticDef def){return Props.i(def,"cooldown",1200);}
                @Override @OnlyIn(Dist.CLIENT)
                public void clientFx(ClientLevel level,Vec3 origin,Vec3 dir,long seed,CosmeticDef def){
                    final GadgetTiming t=GadgetTiming.from(def.properties());
                    final float height=Props.f(def,"height",25f);
                    final var snd=Props.rl(def,"sound",rl("minecraft","block.beacon.activate"));
                    final float vol=Props.f(def,"volume",1.0f),pit=Props.f(def,"pitch",1.0f);
                    final long[] burst={0};
                    GadgetTiming.ClientBurstScheduler.scheduleBursts(t,()->{
                        long s=seed^(0xBB67AE85L*++burst[0]);
                        FastFx.skyBeacon(level,origin,s,height);
                        FastFx.playSound(level,origin,snd,vol,pit);
                    });
                }
            });
            // ----------------------------------------------------------------
            // Aliases: map preset IDs to base implementations
            // These reuse the same IGadgetAction but read their own JSON props.
            // ----------------------------------------------------------------
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
			
        }

        // --- Props + helpers unchanged from your current version ---
        private static Vec3 originWithOffsets(Vec3 origin, Vec3 look, float forward, float up, float right) {
            Vec3 f = look.normalize();
            Vec3 r = f.cross(new Vec3(0, 1, 0));
            if (r.lengthSqr() < 1e-4) r = f.cross(new Vec3(1, 0, 0));
            r = r.normalize();
            Vec3 u = r.cross(f).normalize();
            return origin.add(f.scale(forward)).add(u.scale(up)).add(r.scale(right));
        }
        private static final class Props {
            static int i(@Nullable CosmeticDef def,String key,int d){try{String s=g(def,key);return s==null?d:Integer.parseInt(s);}catch(Exception ignored){return d;}}
            static float f(@Nullable CosmeticDef def,String key,float d){try{String s=g(def,key);return s==null?d:Float.parseFloat(s);}catch(Exception ignored){return d;}}
            static ResourceLocation rl(@Nullable CosmeticDef def,String key,ResourceLocation d){try{String s=g(def,key);ResourceLocation r=(s==null)?null:ResourceLocation.tryParse(s);return r==null?d:r;}catch(Exception ignored){return d;}}
            private static String g(@Nullable CosmeticDef def,String key){Map<String,String> p=(def==null)?null:def.properties();return(p==null)?null:p.get(key);}
        }
        private static ResourceLocation rl(String ns,String path){return ResourceLocation.fromNamespaceAndPath(ns,path);}
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

        private static Vec3 randomCone(Vec3 dir, float coneRad, java.util.Random r) {
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

    private static ResourceLocation rl(String ns,String path){return ResourceLocation.fromNamespaceAndPath(ns,path);}
}
