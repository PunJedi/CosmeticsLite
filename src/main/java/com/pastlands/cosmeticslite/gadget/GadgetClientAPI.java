// src/main/java/com/pastlands/cosmeticslite/gadget/GadgetClientAPI.java
package com.pastlands.cosmeticslite.gadget;

import com.pastlands.cosmeticslite.CosmeticDef;
import com.pastlands.cosmeticslite.CosmeticsRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * Client-side hook surface for triggering gadgets from the /cosmetics menu.
 *
 * Capabilities:
 *  - useGadgetFromMenu(...)    → server-authoritative activation (respects cooldowns/equip)
 *  - preview(...)              → best-effort LOCAL preview around a client player anchor (client-only)
 *  - previewOnMannequin(...)   → best-effort LOCAL preview anchored to the GUI mannequin (client-only)
 *
 * Notes:
 *  - All preview* methods are tolerant and no-op safely if the downstream client preview API is
 *    unavailable. They return a boolean to indicate whether a local preview was actually fired.
 *  - We DO NOT fall back to server-side "use" from preview calls (no cooldown consumption).
 *  - Reflection lookups are cached after the first resolution.
 *
 * JSON-awareness (no side effects):
 *  - jsonDurationMs(id) / jsonCooldownMs(id): read overrides from per-gadget JSON (if present).
 *  - hasJsonOverride(id): true if JSON declared a 'pattern' (our hybrid path is active for the gadget).
 *  - effectiveDurationMs(id, fallback): returns JSON override if present, else fallback.
 *  - effectiveCooldownMs(id, fallback): returns JSON override if present, else fallback.
 *
 * Mannequin anchor resolution hierarchy:
 *  - Primary: supplier injected via setMannequinSupplier(...)
 *  - Fallback: reflection on known preview classes:
 *        com.pastlands.cosmeticslite.preview.PreviewResolver#getMannequin()
 *        com.pastlands.cosmeticslite.preview.MannequinPane#getMannequin()
 */
public final class GadgetClientAPI {

    private GadgetClientAPI() {}

    // ---------------------------------------------------------------------
    // Optional mannequin supplier injected by the GUI layer
    // ---------------------------------------------------------------------
    private static volatile Supplier<LocalPlayer> MANNEQUIN_SUPPLIER = null;

    /**
     * UI may inject a supplier returning the current mannequin (fake LocalPlayer) used by the preview pane.
     * Passing null clears the supplier.
     */
    public static void setMannequinSupplier(Supplier<LocalPlayer> supplier) {
        MANNEQUIN_SUPPLIER = supplier;
    }

    // ---------------------------------------------------------------------
    // Public surface: server-authoritative activation
    // ---------------------------------------------------------------------

    /**
     * Server-authoritative usage from the cosmetics menu.
     * Cooldowns and equip validation are enforced server-side.
     */
    public static void useGadgetFromMenu(ResourceLocation gadgetId) {
        if (gadgetId == null || isAir(gadgetId)) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;

        var ch = com.pastlands.cosmeticslite.gadget.GadgetNet.channel();
        if (ch == null) return; // never crash UI if init ordering is odd

        ch.sendToServer(new com.pastlands.cosmeticslite.gadget.GadgetNet.UseGadgetC2S(gadgetId));
    }

    // ---------------------------------------------------------------------
    // Public surface: client-only previews
    // ---------------------------------------------------------------------

    /** Best-effort client-only preview around the real player, with default sound ON. */
    public static boolean preview(ResourceLocation gadgetId) {
        return preview(gadgetId, true);
    }

    /** Best-effort client-only preview around the real player. */
    public static boolean preview(ResourceLocation gadgetId, boolean withSound) {
        if (gadgetId == null || isAir(gadgetId)) return false;

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = (mc != null) ? mc.player : null;
        if (mc == null || player == null) return false;

        // Attempt gadget mod generic preview (signature-flexible).
        return PreviewBridge.invokeGenericPreview(mc, player, gadgetId, withSound);
    }

    /** Best-effort client-only preview anchored to the GUI mannequin, with default sound ON. */
    public static boolean previewOnMannequin(ResourceLocation gadgetId) {
        return previewOnMannequin(gadgetId, true);
    }

    /**
     * Best-effort client-only preview anchored to the GUI mannequin.
     * Falls back to player-centric preview if mannequin not available.
     */
    public static boolean previewOnMannequin(ResourceLocation gadgetId, boolean withSound) {
        if (gadgetId == null || isAir(gadgetId)) return false;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return false;

        LocalPlayer mannequin = getMannequin();
        if (mannequin != null) {
            // Try dedicated mannequin-targeted hooks first; then generic preview with mannequin.
            if (PreviewBridge.invokeMannequinPreview(mc, mannequin, gadgetId, withSound)) {
                return true;
            }
            if (PreviewBridge.invokeGenericPreview(mc, mannequin, gadgetId, withSound)) {
                return true;
            }
        }

        // Fallback to normal player-centric preview
        return preview(gadgetId, withSound);
    }

    // ---------------------------------------------------------------------
    // JSON-aware helpers (no allocations on hot paths beyond minimal map lookups)
    // ---------------------------------------------------------------------

    /** Returns true if this gadget has a JSON override active (pattern present). */
    public static boolean hasJsonOverride(ResourceLocation gadgetId) {
        Map<String, String> p = propsFor(gadgetId);
        if (p == null) return false;
        String pattern = p.get("pattern");
        return pattern != null && !pattern.isEmpty();
    }

    /** Optional JSON duration override (ms), clamped to safe range if present. */
    public static OptionalInt jsonDurationMs(ResourceLocation gadgetId) {
        int val = parseIntProp(gadgetId, "duration_ms", -1, 100, 2500);
        return (val >= 0) ? OptionalInt.of(val) : OptionalInt.empty();
    }

    /** Optional JSON cooldown override (ms), clamped to safe range if present. */
    public static OptionalInt jsonCooldownMs(ResourceLocation gadgetId) {
        int val = parseIntProp(gadgetId, "cooldown_ms", -1, 200, 6000);
        return (val >= 0) ? OptionalInt.of(val) : OptionalInt.empty();
    }

    /** Returns JSON duration if present, otherwise the provided fallback. */
    public static int effectiveDurationMs(ResourceLocation gadgetId, int fallback) {
        int val = parseIntProp(gadgetId, "duration_ms", -1, 100, 2500);
        return (val >= 0) ? val : fallback;
    }

    /** Returns JSON cooldown if present, otherwise the provided fallback. */
    public static int effectiveCooldownMs(ResourceLocation gadgetId, int fallback) {
        int val = parseIntProp(gadgetId, "cooldown_ms", -1, 200, 6000);
        return (val >= 0) ? val : fallback;
    }

    private static Map<String, String> propsFor(ResourceLocation id) {
        if (id == null) return null;
        CosmeticDef def = CosmeticsRegistry.get(id);
        return (def != null) ? def.properties() : null; // String->String bag populated by GadgetPresetReloader
    }

    private static int parseIntProp(ResourceLocation id, String key, int missing, int min, int max) {
        Map<String, String> p = propsFor(id);
        if (p == null) return missing;
        String s = p.get(key);
        if (s == null || s.isEmpty()) return missing;
        try {
            int v = Integer.parseInt(s.trim());
            if (v < min) v = min;
            if (v > max) v = max;
            return v;
        } catch (Exception ignored) {
            return missing;
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static boolean isAir(ResourceLocation rl) {
        return rl != null && "minecraft".equals(rl.getNamespace()) && "air".equals(rl.getPath());
    }

    private static LocalPlayer getMannequin() {
        Supplier<LocalPlayer> sup = MANNEQUIN_SUPPLIER;
        if (sup != null) {
            try {
                LocalPlayer p = sup.get();
                if (p != null) return p;
            } catch (Throwable ignored) {}
        }
        return MannequinBridge.tryGetMannequin();
    }

    // ---------------------------------------------------------------------
    // Reflection bridge: mannequin lookup (cached)
    // ---------------------------------------------------------------------
    private static final class MannequinBridge {
        private static final String[][] CANDIDATES = new String[][]{
                {"com.pastlands.cosmeticslite.preview.PreviewResolver", "getMannequin"},
                {"com.pastlands.cosmeticslite.preview.MannequinPane", "getMannequin"}
        };

        private static volatile boolean resolved = false;
        private static Method getter = null;

        static LocalPlayer tryGetMannequin() {
            try {
                Method m = resolve();
                if (m == null) return null;
                return (LocalPlayer) m.invoke(null);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static Method resolve() {
            if (resolved) return getter;
            synchronized (MannequinBridge.class) {
                if (resolved) return getter;
                for (String[] cand : CANDIDATES) {
                    try {
                        Class<?> c = Class.forName(cand[0], false, GadgetClientAPI.class.getClassLoader());
                        Method m = c.getDeclaredMethod(cand[1]);
                        if (!m.canAccess(null)) m.setAccessible(true);
                        // Must be static, return LocalPlayer, no args
                        if ((m.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0
                                && LocalPlayer.class.isAssignableFrom(m.getReturnType())
                                && m.getParameterCount() == 0) {
                            getter = m;
                            resolved = true;
                            return getter;
                        }
                    } catch (Throwable ignored) {}
                }
                resolved = true;
                getter = null;
                return null;
            }
        }
    }

    // ---------------------------------------------------------------------
    // Reflection bridge (cached) — gadget client preview hooks
    // ---------------------------------------------------------------------

    /**
     * Signature-flexible resolver for gadget preview hooks.
     *
     * We try these method names on candidate classes:
     *  - Generic preview:        preview, previewClient, previewAround, tryPreview
     *  - Mannequin preview:      previewOnMannequin, previewOnPreviewEntity, previewOnFakeEntity, previewInGui
     *
     * And accept these common signatures (static methods):
     *  (A) (ResourceLocation)
     *  (B) (ResourceLocation, boolean)
     *  (C) (LocalPlayer, ResourceLocation)
     *  (D) (LocalPlayer, ResourceLocation, boolean)
     *  (E) (Minecraft, ResourceLocation)
     *  (F) (Minecraft, ResourceLocation, boolean)
     *  (G) (Minecraft, LocalPlayer, ResourceLocation)
     *  (H) (Minecraft, LocalPlayer, ResourceLocation, boolean)
     *
     * We adapt provided (mc, anchor, id, withSound) to whatever the method expects.
     */
    private static final class PreviewBridge {
        private static final String[] CANDIDATE_CLASSES = new String[] {
                // Prefer explicit API-style classes first (adjust to your gadget mod)
                "com.pastlands.gadgets.client.GadgetClientAPI",
                "com.pastlands.gadgets.client.GadgetClient",
                "com.pastlands.gadgets.client.ClientHooks",
                "dev.pastlands.gadgets.client.ClientHooks",
                // Generic fallbacks (in case of shading/rename)
                "com.gadgets.client.ClientHooks",
                "com.gadget.client.ClientHooks"
        };

        private static final String[] GENERIC_METHODS = new String[] {
                "preview", "previewClient", "previewAround", "tryPreview"
        };

        private static final String[] MANNEQUIN_METHODS = new String[] {
                "previewOnMannequin", "previewOnPreviewEntity", "previewOnFakeEntity", "previewInGui"
        };

        private static volatile boolean genericResolved = false;
        private static volatile boolean mannequinResolved = false;

        private static Method genericMethod = null;
        private static Method mannequinMethod = null;

        static boolean invokeGenericPreview(Minecraft mc, LocalPlayer anchor, ResourceLocation id, boolean withSound) {
            try {
                Method m = resolveGeneric();
                if (m == null) return false;
                Object[] args = adaptArgs(m, mc, anchor, id, withSound);
                if (args == null) return false;
                m.invoke(null, args);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        static boolean invokeMannequinPreview(Minecraft mc, LocalPlayer mannequin, ResourceLocation id, boolean withSound) {
            try {
                Method m = resolveMannequin();
                if (m == null) return false;
                Object[] args = adaptArgs(m, mc, mannequin, id, withSound);
                if (args == null) return false;
                m.invoke(null, args);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private static Method resolveGeneric() {
            if (genericResolved) return genericMethod;
            synchronized (PreviewBridge.class) {
                if (genericResolved) return genericMethod;
                genericMethod = findMethod(GENERIC_METHODS);
                genericResolved = true;
                return genericMethod;
            }
        }

        private static Method resolveMannequin() {
            if (mannequinResolved) return mannequinMethod;
            synchronized (PreviewBridge.class) {
                if (mannequinResolved) return mannequinMethod;
                mannequinMethod = findMethod(MANNEQUIN_METHODS);
                mannequinResolved = true;
                return mannequinMethod;
            }
        }

        private static Method findMethod(String[] methodNames) {
            for (String className : CANDIDATE_CLASSES) {
                Class<?> clazz = tryLoad(className);
                if (clazz == null) continue;

                for (String mname : methodNames) {
                    // Try exact parameter lists we support
                    for (Class<?>[] sig : SUPPORTED_SIGNATURES) {
                        try {
                            Method m = clazz.getDeclaredMethod(mname, sig);
                            if (!m.canAccess(null)) m.setAccessible(true);
                            if ((m.getModifiers() & java.lang.reflect.Modifier.STATIC) == 0) {
                                continue; // must be static
                            }
                            return m;
                        } catch (NoSuchMethodException ignored) {
                            // try next signature
                        } catch (Throwable ignored) {
                            // continue search
                        }
                    }
                }
            }
            return null;
        }

        private static Class<?> tryLoad(String fqcn) {
            try {
                return Class.forName(fqcn, false, GadgetClientAPI.class.getClassLoader());
            } catch (ClassNotFoundException ignored) {
                return null;
            }
        }

        // Supported signatures (see doc above)
        private static final Class<?>[][] SUPPORTED_SIGNATURES = new Class<?>[][]{
                {ResourceLocation.class},
                {ResourceLocation.class, boolean.class},
                {LocalPlayer.class, ResourceLocation.class},
                {LocalPlayer.class, ResourceLocation.class, boolean.class},
                {Minecraft.class, ResourceLocation.class},
                {Minecraft.class, ResourceLocation.class, boolean.class},
                {Minecraft.class, LocalPlayer.class, ResourceLocation.class},
                {Minecraft.class, LocalPlayer.class, ResourceLocation.class, boolean.class},
        };

        /**
         * Build invocation args by matching the target method parameter list
         * against the full context (mc, anchor, id, withSound).
         */
        private static Object[] adaptArgs(Method target, Minecraft mc, LocalPlayer anchor, ResourceLocation id, boolean withSound) {
            Class<?>[] ps = target.getParameterTypes();
            Object[] out = new Object[ps.length];

            for (int i = 0; i < ps.length; i++) {
                Class<?> p = ps[i];
                if (p == ResourceLocation.class) {
                    out[i] = id;
                } else if (p == boolean.class || p == Boolean.class) {
                    out[i] = withSound;
                } else if (p == LocalPlayer.class) {
                    out[i] = anchor;
                } else if (p == Minecraft.class) {
                    out[i] = mc;
                } else {
                    return null; // unsupported param type
                }
            }
            // sanity
            for (Object o : out) {
                if (o == null) return null;
            }
            return out;
        }
    }
}
