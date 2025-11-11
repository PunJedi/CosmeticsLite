package com.pastlands.cosmeticslite.gadget;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Adds explicit lifetime & duration semantics for client FX, plus a tiny burst scheduler.
 *
 * JSON keys supported (all optional; safe defaults):
 *   // Primary duration for UX/hold timing (preferred)
 *   - duration_ms              : int (100..2500)  // preferred; menu hold uses this when present
 *   // Legacy/fallbacks
 *   - duration_ticks           : int (=> ms via *50)
 *   - burst_count              : int
 *   - burst_interval_ticks     : int
 *   - lifetime_ticks           : int              // for long-lived emitter lifetimes (not used by menu hold)
 *   // UX tuning
 *   - menu_pad_ms              : int (0..500)     // extra pad after duration before menu auto-returns; default 220
 */
public final class GadgetTiming {

    // ---------------------------------------------------------------------
    // Instance timing model (optional if you need a structured object)
    // ---------------------------------------------------------------------
    private final int durationTicks;
    private final int burstCount;
    private final int burstIntervalTicks;
    private final int lifetimeTicks;

    private GadgetTiming(int durationTicks, int burstCount, int burstIntervalTicks, int lifetimeTicks) {
        this.durationTicks = Math.max(0, durationTicks);
        this.burstIntervalTicks = Math.max(1, burstIntervalTicks);
        this.lifetimeTicks = Math.max(0, lifetimeTicks);

        if (burstCount > 0) {
            this.burstCount = burstCount;
        } else if (durationTicks > 0) {
            int approx = Math.max(1, durationTicks / Math.max(10, this.burstIntervalTicks));
            this.burstCount = approx;
        } else {
            this.burstCount = 1; // legacy single burst
        }
    }

    public int durationTicks()       { return durationTicks; }
    public int burstCount()          { return burstCount; }
    public int burstIntervalTicks()  { return burstIntervalTicks; }
    public int lifetimeTicks()       { return lifetimeTicks; }

    /** Build from your existing gadget config map (stringly-typed). */
    public static GadgetTiming from(Map<String, String> m) {
        Objects.requireNonNull(m, "config map");
        int duration = estimateDurationTicks(m);              // prefer JSON ms
        int bursts   = parseInt(m, "burst_count", 0);
        int interval = parseInt(m, "burst_interval_ticks", 10);
        int life     = parseInt(m, "lifetime_ticks", 0);
        return new GadgetTiming(duration, bursts, interval, life);
    }

    // ---------------------------------------------------------------------
    // Menu auto-return helpers (call these from the Quick Menu)
    // ---------------------------------------------------------------------

    /** Compute how long (in ms) the Quick Menu should stay hidden after activation. */
    public static int holdMillisFor(Map<String, String> props) {
        // Prefer explicit JSON milliseconds when present
        int coreMs = clamp(parseInt(props, "duration_ms", -1), 100, 2500);
        if (coreMs < 0) {
            // Fallback to ticks
            int ticks = parseInt(props, "duration_ticks", -1);
            if (ticks >= 0) {
                coreMs = ticksToMs(ticks);
            } else {
                // Last resort: infer from burst pacing
                int inferredTicks = inferFromBurstsTicks(props);
                coreMs = ticksToMs(inferredTicks);
            }
            // Guard if everything was missing or zero-ish
            coreMs = clamp(coreMs, 150, 2500);
        }

        int padMs = clamp(parseInt(props, "menu_pad_ms", 220), 0, 500);
        // If you noticed the menu popping early, a slightly larger default helps; we keep it moderate.
        return coreMs + padMs;
    }

    /** Estimate a sensible duration in ticks for systems that still use ticks internally. */
    public static int estimateDurationTicks(Map<String, String> props) {
        int ms = clamp(parseInt(props, "duration_ms", -1), 100, 2500);
        if (ms >= 0) return msToTicks(ms);

        int ticks = parseInt(props, "duration_ticks", -1);
        if (ticks >= 0) return Math.max(0, ticks);

        return inferFromBurstsTicks(props);
    }

    private static int inferFromBurstsTicks(Map<String, String> m) {
        int interval = Math.max(1, parseInt(m, "burst_interval_ticks", 10));
        int count    = parseInt(m, "burst_count", 0);
        if (count <= 0) {
            // If no explicit count, guess based on legacy heuristic
            int approx = Math.max(1, 12); // ~12 bursts over 12*interval ≈ half a second if interval=10
            return approx * interval;
        }
        // total = first burst (0) + (count-1) * interval
        int total = Math.max(0, (count - 1) * interval);
        // Keep within UX-friendly bounds
        return clamp(total, 3, msToTicks(2500));
    }

    // ---------------------------------------------------------------------
    // Parsing helpers
    // ---------------------------------------------------------------------

    private static int parseInt(Map<String, String> m, String key, int def) {
        if (m == null) return def;
        String s = m.get(key);
        if (s == null || s.isEmpty()) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { return def; }
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static int msToTicks(int ms) {
        // ceil(ms / 50.0) keeps slightly-longer visual tails from being cut off
        int t = (ms + 49) / 50;
        return Math.max(0, t);
    }

    private static int ticksToMs(int ticks) {
        // Use exact 50ms per tick
        return Math.max(0, ticks * 50);
    }

    // ---------------------------------------------------------------------
    // Client-side burst scheduler (unchanged)
    // ---------------------------------------------------------------------
    public static final class ClientBurstScheduler {
        private static final Queue<Task> TASKS = new ConcurrentLinkedQueue<>();
        private static volatile boolean registered = false;

        /** Queue a multi-burst action driven by the current timing. */
        public static void scheduleBursts(GadgetTiming timing, Runnable burstAction) {
            if (timing == null || burstAction == null) return;
            if (Minecraft.getInstance() == null) return;

            ensureRegistered();

            int total = Math.max(1, timing.burstCount());
            int interval = Math.max(1, timing.burstIntervalTicks());

            // If duration is set, cap bursts so we don't exceed it.
            if (timing.durationTicks() > 0) {
                int maxBursts = Math.max(1, 1 + (timing.durationTicks() / interval));
                total = Math.min(total, maxBursts);
            }

            TASKS.add(new Task(burstAction, total, interval));
        }

        private static void ensureRegistered() {
            if (registered) return;
            synchronized (ClientBurstScheduler.class) {
                if (registered) return;
                MinecraftForge.EVENT_BUS.register(Listener.INSTANCE);
                registered = true;
            }
        }

        // Tick listener (client thread)
        private enum Listener {
            INSTANCE;

            @SubscribeEvent
            public void onClientTick(TickEvent.ClientTickEvent e) {
                if (e.phase != TickEvent.Phase.END) return;
                if (TASKS.isEmpty()) return;

                for (Task t : TASKS) t.tick();
                TASKS.removeIf(Task::isDone);
            }
        }

        private static final class Task {
            private final Runnable action;
            private final int intervalTicks;
            private int remainingBursts;
            private int ticksUntilNext;

            Task(Runnable action, int totalBursts, int intervalTicks) {
                this.action = action;
                this.remainingBursts = totalBursts;
                this.intervalTicks = Math.max(1, intervalTicks);
                this.ticksUntilNext = 0; // fire immediately on next tick
            }

            void tick() {
                if (remainingBursts <= 0) return;
                if (ticksUntilNext > 0) { ticksUntilNext--; return; }

                // Run one burst (never crash the loop)
                try { action.run(); } catch (Throwable ignored) {}

                remainingBursts--;
                if (remainingBursts > 0) {
                    ticksUntilNext = intervalTicks;
                }
            }

            boolean isDone() { return remainingBursts <= 0; }
        }
    }
}
