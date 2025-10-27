package com.pastlands.cosmeticslite.gadget;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pastlands.cosmeticslite.ClientState;
import com.pastlands.cosmeticslite.CosmeticDef;
import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.CosmeticsRegistry;
import com.pastlands.cosmeticslite.PacketEquipRequest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
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
 *   /gadget list             → open quick menu (if server didn't override)
 *
 *   /glist                   → always open client quick menu (safe alias)
 *   /guse [id]               → legacy/short alias (now supports bare /guse)
 *
 * Quick menu: mouse-grabbing, shows live cooldowns, can auto-reopen after a use.
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
				PRETTY.put("cosmeticslite:star_shower",      new Pretty("Star Shower", "A wide fan of glittering star sparks."));
PRETTY.put("cosmeticslite:sparkle_ring",     new Pretty("Sparkle Ring", "A shimmering arc that hums with light."));
PRETTY.put("cosmeticslite:bubble_stream",    new Pretty("Bubble Stream", "A steady stream of buoyant bubbles."));
PRETTY.put("cosmeticslite:confetti_fountain",new Pretty("Confetti Fountain", "A dense fountain of festive confetti."));
PRETTY.put("cosmeticslite:spark_fan",        new Pretty("Spark Fan", "Fast sweeping sparks in a wide fan."));
PRETTY.put("cosmeticslite:glitter_pop",      new Pretty("Glitter Pop", "A quick burst of glitter and twinkle."));
PRETTY.put("cosmeticslite:shimmer_wave",     new Pretty("Shimmer Wave", "Graceful wave of shimmering sparks."));
PRETTY.put("cosmeticslite:bubble_blast",     new Pretty("Bubble Blast", "Chunky bubbles that pop into a trail."));
PRETTY.put("cosmeticslite:starlight_burst",  new Pretty("Starlight Burst", "Focused cone of starlit confetti."));
PRETTY.put("cosmeticslite:glitter_veil",     new Pretty("Glitter Veil", "Long, gentle curtain of glitter."));

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
    // Client-side cooldown tracking (UI hint only—server authoritative)
    // --------------------------------------------------------------------------------------------
    private static final Map<String, Long> LAST_USED_MS = new LinkedHashMap<>();

    /** Per-id fallback cooldowns in milliseconds. */
    private static final Map<String, Long> DEFAULT_COOLDOWN_MS = Map.of(
            "cosmeticslite:confetti_popper", 3000L, // 60t ≈ 3s
            "cosmeticslite:bubble_blower",   2000L, // 40t ≈ 2s
            "cosmeticslite:gear_spark_emitter", 2000L
    );

    // ---- NEW: Cache JSON cooldowns so /guse sees correct values even before UI opens ----
    private static final Map<String, Long> COOLDOWN_MS_BY_ID = new LinkedHashMap<>();
    private static volatile boolean COOLDOWNS_PRIMED = false;

    private static void primeCooldownsFromRegistryOnce() {
        if (COOLDOWNS_PRIMED) return;
        COOLDOWNS_PRIMED = true;
        try {
            for (CosmeticDef d : CosmeticsRegistry.getByType("gadgets")) {
                if (d == null || d.id() == null) continue;
                String raw = firstNonEmpty(
                        d.properties().get("cooldown"),
                        d.properties().get("cooldown_ticks"),
                        d.properties().get("cooldown_ms"),
                        d.properties().get("cooldown_seconds"),
                        d.properties().get("cooldown_s"));
                long ms = parseCooldownMs(raw);
                if (ms > 0L) {
                    COOLDOWN_MS_BY_ID.put(d.id().toString(), ms);
                }
            }
        } catch (Throwable ignored) {
            // If discovery fails, we’ll just fall back to per-id defaults safely.
        }
    }

    /**
     * Public so UI can display labels if needed.
     * Reads JSON-driven properties (cached) first, then falls back.
     */
    public static long cooldownMillisFor(ResourceLocation id) {
        if (id == null) return 3000L;

        // Make sure JSON cooldowns are harvested once so /guse works immediately.
        primeCooldownsFromRegistryOnce();

        // 0) Cached JSON (fast path)
        Long cached = COOLDOWN_MS_BY_ID.get(id.toString());
        if (cached != null && cached > 0L) return cached;

        // 1) Direct registry read (covers hot-adds, then memoize)
        CosmeticDef def = CosmeticsRegistry.get(id);
        if (def != null) {
            String raw = firstNonEmpty(
                    def.properties().get("cooldown"),
                    def.properties().get("cooldown_ticks"),
                    def.properties().get("cooldown_ms"),
                    def.properties().get("cooldown_seconds"),
                    def.properties().get("cooldown_s"));
            long parsed = parseCooldownMs(raw);
            if (parsed > 0L) {
                COOLDOWN_MS_BY_ID.put(id.toString(), parsed);
                return parsed;
            }
        }

        // 2) Per-id defaults (ms)
        Long perId = DEFAULT_COOLDOWN_MS.get(id.toString());
        if (perId != null && perId > 0L) return perId;

        // 3) Final safety default
        return 3000L;
    }

    /** mm:ss labels source this. */
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

    // ---------- Cooldown parsing helpers ----------
    private static String firstNonEmpty(String... opts) {
        if (opts == null) return "";
        for (String s : opts) if (s != null && !s.trim().isEmpty()) return s.trim();
        return "";
    }

    /**
     * Parse a cooldown string into milliseconds.
     * Supported:
     *   "1200" (ticks), "1200t", "60s", "3000ms"
     *   Unitless: <=120 → seconds; >120 → ticks.
     */
    private static long parseCooldownMs(String s) {
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

            // Unitless numeric
            long v = Long.parseLong(raw);
            if (v <= 0L) return 0L;
            // Heuristic preserved from original intent:
            return (v <= 120L) ? v * 1000L : v * 50L;
        } catch (Exception ignored) {
            return 0L;
        }
    }
    // ----------------------------------------------

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
    /** Use currently equipped gadget (client-side alias; server validates on receipt). */
    private static int useEquipped() {
        ResourceLocation equipped = ClientState.getEquippedId("gadgets");
        if (equipped == null) {
            toast(Component.literal("No gadget equipped."));
            return 0;
        }
        return sendUse(equipped, true);
    }

    /** Use a specific id (string → RL). */
    private static int useSpecific(ResourceLocation id, boolean showNameToast) {
        if (id == null) {
            toast(Component.literal("Invalid gadget id."));
            return 0;
        }
        // Ensure it's a gadget before sending
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

    // Respect local cooldown (same values the quick menu shows).
    long rem = remainingMs(id);
    if (rem > 0L) {
        pl.displayClientMessage(
            Component.literal("Gadget on cooldown: " + prettyClock(rem)),
            true
        );
        return 0;
    }

    // Align /guse behavior with quick menu: equip first so all UI keys off the same id.
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

    // Send request and start local cooldown immediately for UI responsiveness.
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
    // Quick-menu open & optional auto-reopen after use
    // --------------------------------------------------------------------------------------------
    private static boolean reopenAfterUse = false;
    private static int reopenTicks = -1;

    /** Open the client quick menu. */
    public static void openGadgetList(boolean autoReopenAfterUse) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        reopenAfterUse = autoReopenAfterUse;
        mc.setScreen(new GadgetQuickMenuScreen(prettyMap(), autoReopenAfterUse));
    }

    /** Schedule/drive the auto-reopen behavior. */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (reopenTicks >= 0) {
            if (--reopenTicks <= 0) {
                reopenTicks = -1;
                reopenAfterUse = false;
                // Reopen the list
                openGadgetList(true);
            }
        }
    }

    /** Called by the screen when it wants to close & schedule a reopen in ~3s. */
    public static void armReopenAfterUse(boolean enabled) {
        reopenAfterUse = enabled;
        reopenTicks = enabled ? 60 : -1; // ~3s at 20tps
    }

    // --------------------------------------------------------------------------------------------
    // Clickable chat helpers (unused by menu but kept for convenience)
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

    /** Auto-equip helper (used by quick menu when clicking rows). */
    public static void ensureEquippedBeforeUse(ResourceLocation id) {
        if (id == null) return;
        if (!Objects.equals(ClientState.getEquippedId("gadgets"), id)) {
            PacketEquipRequest.send("gadgets", id);
            ClientState.setEquippedId("gadgets", id);
        }
    }
}
