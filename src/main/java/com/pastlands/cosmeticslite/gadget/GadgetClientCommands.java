package com.pastlands.cosmeticslite.gadget;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pastlands.cosmeticslite.ClientState;
import com.pastlands.cosmeticslite.CosmeticDef;
import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.CosmeticsRegistry;
import com.pastlands.cosmeticslite.PacketEquipRequest;
import com.pastlands.cosmeticslite.CosmeticsChestScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;

/**
 * Client-only gadget commands & minimal UI.
 *
 *   /gadget use              → use equipped gadget
 *   /gadget use <id>         → use specific gadget
 *   /gadget list             → open quick menu (if server didn't override)
 *
 *   /glist                   → always open client quick menu (safe alias)
 *   /guse [id]               → legacy/short alias (now supports bare /guse)
 *
 * Quick menu: mouse-grabbing, shows live cooldowns, can auto-reopen after a use.
 *
 * Provides a lightweight scheduler so the Cosmetics screen can:
 *   - close → short countdown → fire gadget once → reopen Cosmetics screen
 *
 * Forge 47.4.0 (MC 1.20.1)
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
    // Client-side cooldown & duration lookup
    // --------------------------------------------------------------------------------------------
    private static final Map<String, Long> LAST_USED_MS = new LinkedHashMap<>();


/** Default effect durations in milliseconds (short visual window; cooldowns separate). */
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

    // New additions for full set
    Map.entry("cosmeticslite:supernova_burst",    1800L),
    Map.entry("cosmeticslite:expanding_ring",     1400L),
    Map.entry("cosmeticslite:helix_stream",       1600L),
    Map.entry("cosmeticslite:firefly_orbit",      1500L),
    Map.entry("cosmeticslite:ground_ripple",      1500L),
    Map.entry("cosmeticslite:sky_beacon",         1700L)
);
/** Global cooldowns per gadget (milliseconds). All set to ~45 seconds. */
private static final Map<String, Long> PER_ID_COOLDOWN_MS = Map.ofEntries(
    Map.entry("cosmeticslite:confetti_popper",    45_000L),
    Map.entry("cosmeticslite:bubble_blower",      45_000L),
    Map.entry("cosmeticslite:gear_spark_emitter", 45_000L),
    Map.entry("cosmeticslite:star_shower",        45_000L),
    Map.entry("cosmeticslite:sparkle_ring",       45_000L),
    Map.entry("cosmeticslite:bubble_stream",      45_000L),
    Map.entry("cosmeticslite:confetti_fountain",  45_000L),
    Map.entry("cosmeticslite:spark_fan",          45_000L),
    Map.entry("cosmeticslite:glitter_pop",        45_000L),
    Map.entry("cosmeticslite:shimmer_wave",       45_000L),
    Map.entry("cosmeticslite:bubble_blast",       45_000L),
    Map.entry("cosmeticslite:starlight_burst",    45_000L),
    Map.entry("cosmeticslite:glitter_veil",       45_000L),
    Map.entry("cosmeticslite:supernova_burst",    45_000L),
    Map.entry("cosmeticslite:expanding_ring",     45_000L),
    Map.entry("cosmeticslite:helix_stream",       45_000L),
    Map.entry("cosmeticslite:firefly_orbit",      45_000L),
    Map.entry("cosmeticslite:ground_ripple",      45_000L),
    Map.entry("cosmeticslite:sky_beacon",         45_000L)
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
                        d.properties().get("lifetime") // many presets use 'lifetime' in ticks
                );
                long ms = parseTimeMs(raw, /*ticksDefault*/false);
                if (ms > 0L) DURATION_MS_BY_ID.put(d.id().toString(), ms);
            }
        } catch (Throwable ignored) {}
    }

 
public static long cooldownMillisFor(ResourceLocation id) {
    if (id == null) return GLOBAL_DEFAULT_COOLDOWN_MS;

    Long perId = PER_ID_COOLDOWN_MS.get(id.toString());
    if (perId != null && perId > 0L) return perId;

    primeCooldownsFromRegistryOnce();
    Long cached = COOLDOWN_MS_BY_ID.get(id.toString());
    if (cached != null && cached > 0L) return cached;

    return GLOBAL_DEFAULT_COOLDOWN_MS;
}


public static long durationMillisFor(ResourceLocation id) {
    if (id == null) return 1200L; // safe short default
    Long perId = DEFAULT_DURATION_MS.get(id.toString());
    return (perId != null && perId > 0L) ? perId : 1200L;
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
     * Supports:
     *   "1200" (unitless), "1200t", "60s", "3000ms"
     * Unitless:
     *   if ticksDefault==true → interpret as ticks (>120 → ticks, <=120 → seconds)
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
            if (ticksDefault) {
                return (v <= 120L) ? v * 1000L : v * 50L;
            } else {
                return (v <= 120L) ? v * 1000L : v * 50L;
            }
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
                                }))
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
    // Quick-menu open & optional auto-reopen after use — now duration-timed
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

public static void armReopenAfterUse(ResourceLocation id, boolean enabled) {
    lastQuickMenuId = id;
    reopenQuickMenuAfterUse = enabled;
    if (enabled && id != null) {
        long ms = durationMillisFor(id) + 800L; // ~0.8s pad after effect ends
        reopenQuickMenuTicks = (int)Math.max(10, Math.min(Integer.MAX_VALUE, ms / 50L));
    } else {
        reopenQuickMenuTicks = -1;
    }
}


    /** Back-compat for older call sites (reopens after a short fixed delay). */
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

    // Show the countdown toast only if we actually have a pre-fire delay.
    if (armedTicks > 0) {
        int secs = Math.max(1, (int)Math.ceil(armedTicks / 20.0));
        mc.player.displayClientMessage(Component.literal("Firing in " + secs + "…"), true);
    }
}


    // Single tick driver for both quick-menu and cosmetics-driven flows
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;

        // Duration-based quick-menu auto-reopen
        if (reopenQuickMenuTicks >= 0) {
            if (--reopenQuickMenuTicks <= 0) {
                reopenQuickMenuTicks = -1;
                reopenQuickMenuAfterUse = false;
                openGadgetList(true);
            }
        }

        // Cosmetics-driven countdown
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
    // Return based on effect duration, but never too long/short.
    long ms = durationMillisFor(id) + 400L;   // small pad for the last particles
    ms = Math.max(900L, Math.min(6000L, ms)); // clamp: 0.9s .. 6.0s
    reopenCosmeticsTicks = (int)(ms / 50L);
    if (reopenCosmeticsTicks < 20) reopenCosmeticsTicks = 20; // minimum ~1s
}
                    }
                }
            }
        }

        // Reopen Cosmetics screen when done
        if (reopenCosmeticsTicks >= 0) {
            if (--reopenCosmeticsTicks <= 0) {
                reopenCosmeticsTicks = -1;
                reopenCosmeticsAfterUse = false;
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) {
                    mc.setScreen(new CosmeticsChestScreen());
                }
            }
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
}
