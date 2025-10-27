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
 *   - duration_ticks
 *   - burst_count
 *   - burst_interval_ticks
 *   - lifetime_ticks
 */
public final class GadgetTiming {

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
        int duration = parseInt(m, "duration_ticks", 0);
        int bursts   = parseInt(m, "burst_count", 0);
        int interval = parseInt(m, "burst_interval_ticks", 10);
        int life     = parseInt(m, "lifetime_ticks", 0);
        return new GadgetTiming(duration, bursts, interval, life);
    }

    private static int parseInt(Map<String, String> m, String key, int def) {
        String s = m.get(key);
        if (s == null || s.isEmpty()) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { return def; }
    }

    // --------------------------------------------------------------------------------------------
    // Client-side burst scheduler
    // --------------------------------------------------------------------------------------------
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
