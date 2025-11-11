// src/main/java/com/pastlands/cosmeticslite/gadget/GadgetClientCommands.java
package com.pastlands.cosmeticslite.gadget;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pastlands.cosmeticslite.ClientState;
import com.pastlands.cosmeticslite.CosmeticDef;
import com.pastlands.cosmeticslite.CosmeticsChestScreen;
import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.CosmeticsRegistry;
import com.pastlands.cosmeticslite.PacketEquipRequest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Client-only gadget commands & minimal UI.
 *
 *   /gadget use              → use equipped gadget
 *   /gadget use <id>         → use specific gadget
 *   /gadget list             → open quick menu (client)
 *
 *   /glist                   → always open client quick menu (safe alias)
 *   /guse [id]               → legacy/short alias (supports bare /guse)
 *
 * Quick menu shows live cooldowns and (when opened) can be auto-reopened timed to actual effect duration.
 *
 * Forge 47.4.x (MC 1.20.1)
 */
@Mod.EventBusSubscriber(modid = CosmeticsLite.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class GadgetClientCommands {

    private GadgetClientCommands() {}

    // --------------------------------------------------------------------------------------------
    // Pretty catalog (id -> name/desc) exposed to quick menu
    // --------------------------------------------------------------------------------------------
    private static final Map<String, Pretty> PRETTY = new LinkedHashMap<>();
    public static record Pretty(String name, String desc) {}
    public static Map<String, Pretty> prettyMap() { return PRETTY; }

    static {
        PRETTY.put("cosmeticslite:confetti_popper",
                new Pretty("Confetti Power", "Pop a shower of colorful confetti around you."));
        PRETTY.put("cosmeticslite:bubble_blower",
                new Pretty("Bubble Madness", "Big buoyant bubbles drift upward and linger."));
        PRETTY.put("cosmeticslite:gear_spark_emitter",
                new Pretty("Sparkling Frenzy", "Wide arc of grinding sparks with bite."));
        PRETTY.put("cosmeticslite:star_shower",
                new Pretty("Star Shower", "A wide fan of glittering star sparks."));
        PRETTY.put("cosmeticslite:sparkle_ring",
                new Pretty("Sparkle Ring", "A shimmering arc that hums with light."));
        PRETTY.put("cosmeticslite:bubble_stream",
                new Pretty("Bubble Stream", "A steady stream of buoyant bubbles."));
        PRETTY.put("cosmeticslite:confetti_fountain",
                new Pretty("Confetti Fountain", "A dense fountain of festive confetti."));
        PRETTY.put("cosmeticslite:spark_fan",
                new Pretty("Spark Fan", "Fast sweeping sparks in a wide fan."));
        PRETTY.put("cosmeticslite:glitter_pop",
                new Pretty("Glitter Pop", "A quick burst of glitter and twinkle."));
        PRETTY.put("cosmeticslite:shimmer_wave",
                new Pretty("Shimmer Wave", "Graceful wave of shimmering sparks."));
        PRETTY.put("cosmeticslite:bubble_blast",
                new Pretty("Bubble Blast", "Chunky bubbles that pop into a trail."));
        PRETTY.put("cosmeticslite:starlight_burst",
                new Pretty("Starlight Burst", "Focused cone of starlit confetti."));
        PRETTY.put("cosmeticslite:glitter_veil",
                new Pretty("Glitter Veil", "Long, gentle curtain of glitter."));
        PRETTY.put("cosmeticslite:supernova_burst",
                new Pretty("Supernova Burst", "A blinding stellar flash that fades in sparks."));
        PRETTY.put("cosmeticslite:expanding_ring",
                new Pretty("Expanding Ring", "A fast outward pulse that ripples the air."));
        PRETTY.put("cosmeticslite:helix_stream",
                new Pretty("Helix Stream", "Twinned spirals that corkscrew upward."));
        PRETTY.put("cosmeticslite:firefly_orbit",
                new Pretty("Firefly Orbit", "Soft glows that circle you like fireflies."));
        PRETTY.put("cosmeticslite:ground_ripple",
                new Pretty("Ground Ripple", "A low shock ring that rolls along the ground."));
        PRETTY.put("cosmeticslite:sky_beacon",
                new Pretty("Sky Beacon", "A column of light that marks the sky."));
    }

    public static String displayName(ResourceLocation id) {
        if (id == null) return "Unknown Gadget";
        Pretty p = PRETTY.get(id.toString());
        return (p != null) ? p.name() : id.toString();
    }

    public static String shortDescription(ResourceLocation id) {
        if (id == null) return "";
        Pretty p = PRETTY.get(id.toString());
        return (p != null) ? p.desc() : "";
    }

    // --------------------------------------------------------------------------------------------
    // Client-side cooldown & duration lookup (JSON-aware)
    // --------------------------------------------------------------------------------------------
    private static final Map<String, Long> LAST_USED_MS = new LinkedHashMap<>();

    /** Default effect durations in milliseconds (fallback if no JSON present). */
    private static final Map<String, Long> DEFAULT_DURATION_MS = Map.ofEntries(
            Map.entry("cosmeticslite:confetti_popper",    1200L),
            Map.entry("cosmeticslite:bubble_blower",      2000L),
            Map.entry("cosmeticslite:gear_spark_emitter", 1500L),
            Map.entry("cosmeticslite:star_shower",        1600L),
            Map.entry("cosmeticslite:sparkle_ring",       1500L),
            Map.entry("cosmeticslite:bubble_stream",      2000L),
            Map.entry("cosmeticslite:confetti_fountain",  1800L),
            Map.entry("cosmeticslite:spark_fan",          1500L),
            Map.entry("cosmeticslite:glitter_pop",         900L),
            Map.entry("cosmeticslite:shimmer_wave",       1600L),
            Map.entry("cosmeticslite:bubble_blast",       1800L),
            Map.entry("cosmeticslite:starlight_burst",    1500L),
            Map.entry("cosmeticslite:glitter_veil",       1600L),

            Map.entry("cosmeticslite:supernova_burst",    1800L),
            Map.entry("cosmeticslite:expanding_ring",     1400L),
            Map.entry("cosmeticslite:helix_stream",       1600L),
            Map.entry("cosmeticslite:firefly_orbit",      1500L),
            Map.entry("cosmeticslite:ground_ripple",      1500L),
            Map.entry("cosmeticslite:sky_beacon",         1700L)
    );

    /** Global default gadget cooldown (ms) if nothing else says otherwise. */
    private static final long GLOBAL_DEFAULT_COOLDOWN_MS = 45_000L;

    // Cached values from registry JSON so UI/commands can read without opening menus first.
    private static final Map<String, Long> COOLDOWN_MS_BY_ID = new LinkedHashMap<>();
    private static final Map<String, Long> DURATION_MS_BY_ID = new LinkedHashMap<>();
    private static volatile boolean COOLDOWNS_PRIMED = false;
    private static volatile boolean DURATIONS_PRIMED = false;

    private static void primeCooldownsFromRegistryOnce() {
        if (COOLDOWNS_PRIMED) return;
        COOLDOWNS_PRIMED = true;
        try {
            for (CosmeticDef d : CosmeticsRegistry.getByType("gadgets")) {
                if (d == null || d.id() == null) continue;
                String raw = firstNonEmpty(
                        d.properties().get("cooldown_ms"),
                        d.properties().get("cooldown"),
                        d.properties().get("cooldown_ticks"),
                        d.properties().get("cooldown_seconds"),
                        d.properties().get("cooldown_s")
                );
                long ms = parseTimeMs(raw, /*ticksDefault*/true);
                if (ms > 0L) COOLDOWN_MS_BY_ID.put(d.id().toString(), ms);
            }
        } catch (Throwable ignored) {}
    }

    private static void primeDurationsFromRegistryOnce() {
        if (DURATIONS_PRIMED) return;
        DURATIONS_PRIMED = true;
        try {
            for (CosmeticDef d : CosmeticsRegistry.getByType("gadgets")) {
                if (d == null || d.id() == null) continue;
                String raw = firstNonEmpty(
                        d.properties().get("duration_ms"),
                        d.properties().get("duration"),
                        d.properties().get("duration_ticks"),
                        d.properties().get("duration_seconds"),
                        d.properties().get("duration_s"),
                        d.properties().get("lifetime") // some presets use 'lifetime' in ticks
                );
                long ms = parseTimeMs(raw, /*ticksDefault*/false);
                if (ms > 0L) DURATION_MS_BY_ID.put(d.id().toString(), ms);
            }
        } catch (Throwable ignored) {}
    }

    /** JSON-aware cooldown, falling back to hard default. */
    public static long cooldownMillisFor(ResourceLocation id) {
        if (id == null) return GLOBAL_DEFAULT_COOLDOWN_MS;

        primeCooldownsFromRegistryOnce();
        Long json = COOLDOWN_MS_BY_ID.get(id.toString());
        if (json != null && json > 0L) return json;

        // final fallback
        return GLOBAL_DEFAULT_COOLDOWN_MS;
    }

    /** JSON-aware duration, falling back to per-id default or 1200ms. */
    public static long durationMillisFor(ResourceLocation id) {
        if (id == null) return 1200L; // safe default

        primeDurationsFromRegistryOnce();
        Long json = DURATION_MS_BY_ID.get(id.toString());
        if (json != null && json > 0L) return json;

        Long def = DEFAULT_DURATION_MS.get(id.toString());
        return (def != null && def > 0L) ? def : 1200L;
    }

    /** mm:ss labels source this (cooldown remaining). */
    public static long remainingMs(ResourceLocation id) {
        long last = LAST_USED_MS.getOrDefault(id.toString(), 0L);
        long cd   = cooldownMillisFor(id);
        long rem  = cd - (System.currentTimeMillis() - last);
        return Math.max(0L, rem);
    }

    /** Mark local start of cooldown so the UI ticks immediately after a use. */
    public static void noteJustUsed(ResourceLocation id) {
        if (id != null) LAST_USED_MS.put(id.toString(), System.currentTimeMillis());
    }

    /** Optional helper for mm:ss formatting. */
    public static String prettyClock(long ms) {
        long total = (ms + 999) / 1000; // ceil to seconds
        long m = total / 60;
        long s = total % 60;
        if (m > 0) return String.format("%d:%02d", m, s);
        return s + "s";
    }

    // ---------- Parsing helpers ----------
    private static String firstNonEmpty(String... opts) {
        if (opts == null) return "";
        for (String s : opts) if (s != null && !s.trim().isEmpty()) return s.trim();
        return "";
    }

    /**
     * Parse a time string into milliseconds.
     * Supports: "1200", "1200t", "60s", "3000ms"
     * Unitless:
     *   if ticksDefault==true  → interpret as ticks (>120 → ticks, <=120 → seconds)
     *   if ticksDefault==false → interpret as seconds if <=120, else ticks
     */
    private static long parseTimeMs(String s, boolean ticksDefault) {
        if (s == null || s.isEmpty()) return 0L;
        String raw = s.trim().toLowerCase();
        try {
            if (raw.endsWith("ms")) {
                long v = Long.parseLong(raw.substring(0, raw.length() - 2).trim());
                return Math.max(0L, v);
            }
            if (raw.endsWith("t")) {
                long v = Long.parseLong(raw.substring(0, raw.length() - 1).trim());
                return (v > 0L) ? v * 50L : 0L;
            }
            if (raw.endsWith("s")) {
                long v = Long.parseLong(raw.substring(0, raw.length() - 1).trim());
                return (v > 0L) ? v * 1000L : 0L;
            }
            long v = Long.parseLong(raw);
            if (v <= 0L) return 0L;
            // unitless heuristic
            return (ticksDefault ? (v <= 120L) : (v <= 120L)) ? v * 1000L : v * 50L;
        } catch (Exception ignored) {
            return 0L;
        }
    }
    // ------------------------------------

    // --------------------------------------------------------------------------------------------
    // Registration
    // --------------------------------------------------------------------------------------------
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();

        // /gadget use [id]
        d.register(Commands.literal("gadget")
                .then(Commands.literal("use")
                        .executes(ctx -> useEquipped())
                        .then(Commands.argument("id", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String raw = StringArgumentType.getString(ctx, "id").trim();
                                    ResourceLocation id = ResourceLocation.tryParse(raw);
                                    return useSpecific(id, false);
                                })
                        )
                )
                .then(Commands.literal("list").executes(ctx -> {
                    openGadgetList(false);
                    return Command.SINGLE_SUCCESS;
                }))
        );

        // Always-works client menu alias
        d.register(Commands.literal("glist").executes(ctx -> {
            openGadgetList(false);
            return Command.SINGLE_SUCCESS;
        }));
    }

    // --------------------------------------------------------------------------------------------
    // Command impls
    // --------------------------------------------------------------------------------------------
    private static int useEquipped() {
        ResourceLocation equipped = ClientState.getEquippedId("gadgets");
        if (equipped == null) {
            toast(Component.literal("No gadget equipped."));
            return 0;
        }
        return sendUse(equipped, true);
    }

    private static int useSpecific(ResourceLocation id, boolean showNameToast) {
        if (id == null) {
            toast(Component.literal("Invalid gadget id."));
            return 0;
        }
        CosmeticDef def = CosmeticsRegistry.get(id);
        if (def == null || !"gadgets".equals(def.type())) {
            toast(Component.literal("Not a valid gadget: " + id));
            return 0;
        }
        return sendUse(id, showNameToast);
    }

    private static int sendUse(ResourceLocation id, boolean showNameToast) {
        Minecraft mc = Minecraft.getInstance();
        Player pl = (mc != null) ? mc.player : null;
        if (pl == null) return 0;

        long rem = remainingMs(id);
        if (rem > 0L) {
            pl.displayClientMessage(
                    Component.literal("Gadget on cooldown: " + prettyClock(rem)),
                    true
            );
            return 0;
        }

        ensureEquippedBeforeUse(id);

        var ch = GadgetNet.channel();
        if (ch == null) {
            GadgetNet.init();
            ch = GadgetNet.channel();
            if (ch == null) {
                pl.displayClientMessage(Component.literal("Gadget system not ready yet."), true);
                return 0;
            }
        }

        ch.sendToServer(new GadgetNet.UseGadgetC2S(id));
        noteJustUsed(id);

        if (showNameToast) {
            pl.displayClientMessage(
                    Component.literal("Using gadget: ")
                            .append(Component.literal(displayName(id)).withStyle(s -> s.withColor(0xFFE066))),
                    true
            );
        }
        return Command.SINGLE_SUCCESS;
    }

    private static void toast(Component msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) mc.player.displayClientMessage(msg, true);
    }

    // --------------------------------------------------------------------------------------------
    // Quick-menu open & legacy auto-reopen after use — duration-timed (for non-QuickMenu callers)
    // --------------------------------------------------------------------------------------------
    private static boolean reopenQuickMenuAfterUse = false;
    private static int reopenQuickMenuTicks = -1;
    private static ResourceLocation lastQuickMenuId = null;

    public static void openGadgetList(boolean autoReopenAfterUse) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        reopenQuickMenuAfterUse = autoReopenAfterUse;
        mc.setScreen(new GadgetQuickMenuScreen(prettyMap(), autoReopenAfterUse));
    }

    /**
     * Legacy hook retained for older call sites. The new Quick Menu handles its own reopen
     * internally and does NOT call this. If external code arms this, we compute a hold based on
     * JSON (duration_ms + pad) and reopen the Quick Menu after that delay.
     */
    public static void armReopenAfterUse(ResourceLocation id, boolean enabled) {
        lastQuickMenuId = id;
        reopenQuickMenuAfterUse = enabled;
        if (enabled && id != null) {
            CosmeticDef def = CosmeticsRegistry.get(id);
            int holdMs = (def != null)
                    ? GadgetTiming.holdMillisFor(def.properties())
                    : (int)Math.max(200, Math.min(2500, durationMillisFor(id) + 220));
            // ticks (ceil)
            int ticks = Math.max(10, (holdMs + 49) / 50);
            reopenQuickMenuTicks = Math.min(Integer.MAX_VALUE, ticks);
        } else {
            reopenQuickMenuTicks = -1;
        }
    }

    /** Back-compat for older call sites (kept; prefer the id-aware overload). */
    @Deprecated
    public static void armReopenAfterUse(boolean enabled) {
        armReopenAfterUse(null, enabled);
    }

    // --------------------------------------------------------------------------------------------
    // Cosmetics-driven scheduler: close → countdown → use → reopen Cosmetics UI
    // --------------------------------------------------------------------------------------------
    private static ResourceLocation armedId = null;
    private static int armedTicks = -1;
    private static boolean reopenCosmeticsAfterUse = false;
    private static int reopenCosmeticsTicks = -1;

    // HOLD-aware waiting state (don’t reopen until FX hold ends)
    private static boolean waitingForHold = false;
    private static int holdSafetyTicks = -1; // ~10s max wait fallback

    public static void scheduleUseFromCosmetics(ResourceLocation id, int delayTicks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || id == null) return;

        long rem = remainingMs(id);
        if (rem > 0L) {
            mc.player.displayClientMessage(
                    Component.literal("Gadget on cooldown: " + prettyClock(rem)),
                    true
            );
            return;
        }

        ensureEquippedBeforeUse(id);

        armedId = id;
        armedTicks = Math.max(0, delayTicks);
        reopenCosmeticsAfterUse = true;

        if (armedTicks > 0) {
            int secs = Math.max(1, (int)Math.ceil(armedTicks / 20.0));
            mc.player.displayClientMessage(Component.literal("Firing in " + secs + "…"), true);
        }
    }

    // Single tick driver for both quick-menu (legacy) and cosmetics-driven flows
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;

        // Duration-based quick-menu auto-reopen (legacy path; Quick Menu now self-schedules)
        if (reopenQuickMenuTicks >= 0) {
            if (--reopenQuickMenuTicks <= 0) {
                reopenQuickMenuTicks = -1;
                reopenQuickMenuAfterUse = false;
                openGadgetList(true);
            }
        }

        // Cosmetics-driven countdown → fire once
        if (armedTicks >= 0) {
            if (armedTicks > 0) {
                armedTicks--;
                if (armedTicks % 10 == 0) {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null && mc.player != null && armedId != null) {
                        int secs = Math.max(0, (int)Math.ceil(armedTicks / 20.0));
                        mc.player.displayClientMessage(Component.literal("Firing in " + secs + "…"), true);
                    }
                }
            } else {
                ResourceLocation id = armedId;
                armedId = null;
                armedTicks = -1;

                if (id != null) {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null && mc.player != null) {
                        var ch = GadgetNet.channel();
                        if (ch == null) { GadgetNet.init(); ch = GadgetNet.channel(); }
                        if (ch != null) {
                            ch.sendToServer(new GadgetNet.UseGadgetC2S(id));
                            noteJustUsed(id);
                        } else {
                            mc.player.displayClientMessage(Component.literal("Gadget system not ready yet."), true);
                        }

                        if (reopenCosmeticsAfterUse) {
                            CosmeticDef def = CosmeticsRegistry.get(id);
                            int holdMs = (def != null)
                                    ? GadgetTiming.holdMillisFor(def.properties())
                                    : (int)Math.max(900, Math.min(6000, durationMillisFor(id) + 220));
                            // clamp and convert to ticks (ceil)
                            holdMs = Math.max(900, Math.min(6000, holdMs));
                            reopenCosmeticsTicks = Math.max(20, (holdMs + 49) / 50);
                        }
                    }
                }
            }
        }

        // Reopen Cosmetics screen when done — HOLD-AWARE
        if (reopenCosmeticsTicks >= 0) {
            if (--reopenCosmeticsTicks <= 0) {
                reopenCosmeticsTicks = -1;
                if (isMenuHoldActive()) {
                    waitingForHold = true;
                    holdSafetyTicks = 200; // ~10s max wait (200 * 50ms)
                } else {
                    reopenCosmeticsAfterUse = false;
                    reopenCosmeticsScreen();
                }
            }
        }

        // While waiting for hold to clear, poll each tick
        if (waitingForHold) {
            if (!isMenuHoldActive() || --holdSafetyTicks <= 0) {
                waitingForHold = false;
                reopenCosmeticsAfterUse = false;
                reopenCosmeticsScreen();
            }
        }
    }

    private static void reopenCosmeticsScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.setScreen(new CosmeticsChestScreen());
        }
    }

    // --------------------------------------------------------------------------------------------
    // Clickable chat helpers
    // --------------------------------------------------------------------------------------------
    public static void sendClickableListToChat() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = (mc != null) ? mc.player : null;
        if (p == null) return;

        p.displayClientMessage(Component.literal("Available Gadgets:"), false);
        for (Map.Entry<String, Pretty> e : PRETTY.entrySet()) {
            ResourceLocation id = ResourceLocation.tryParse(e.getKey());
            if (id == null) continue;
            Pretty pr = e.getValue();
            MutableComponent line = Component.literal(" - ")
                    .append(Component.literal(pr.name()))
                    .withStyle(s -> s
                            .withUnderlined(false)
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.literal(pr.desc())))
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                    "/gadget use " + id))
                    );
            p.displayClientMessage(line, false);
        }
    }

    /** Auto-equip helper (used by quick menu when clicking rows and by scheduler). */
    public static void ensureEquippedBeforeUse(ResourceLocation id) {
        if (id == null) return;
        if (!Objects.equals(ClientState.getEquippedId("gadgets"), id)) {
            PacketEquipRequest.send("gadgets", id, -1, -1, new CompoundTag());
            ClientState.setEquippedId("gadgets", id);
        }
    }

    // --------------------------------------------------------------------------------------------
    // Hold detection (reflective, so it compiles even if the helper doesn't exist yet)
    // --------------------------------------------------------------------------------------------
    private static boolean isMenuHoldActive() {
        try {
            // Expect: com.pastlands.cosmeticslite.gadget.GadgetNet$ClientMenuHold.isHolding():boolean
            Class<?> cls = Class.forName("com.pastlands.cosmeticslite.gadget.GadgetNet$ClientMenuHold");
            try {
                var m = cls.getDeclaredMethod("isHolding");
                m.setAccessible(true);
                Object v = m.invoke(null);
                return (v instanceof Boolean) && (Boolean) v;
            } catch (NoSuchMethodException noMethod) {
                // Fallback: look for a HOLD_COUNT field (int) and treat >0 as active
                try {
                    var f = cls.getDeclaredField("HOLD_COUNT");
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v instanceof Integer) return ((Integer) v) > 0;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        // If the helper isn't present, don't block reopening.
        return false;
    }
}
