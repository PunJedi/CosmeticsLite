// src/main/java/com/pastlands/cosmeticslite/PacketEquipRequest.java
package com.pastlands.cosmeticslite;

import com.pastlands.cosmeticslite.network.S2CEquipDenied;
import com.pastlands.cosmeticslite.permission.CosmeticsFeature;
import com.pastlands.cosmeticslite.permission.CosmeticsPermissions;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client -> Server: request to equip/clear a cosmetic (+ style payload).
 *
 * Semantics:
 *  - (type != null && id != AIR) : equip id for given type with optional style (variant/color/styleTag)
 *  - (type != null && id == AIR) : clear ONLY that type's equipped ID (keep style so it persists)
 *  - (type == null)              : clear ALL types (removes styles too)
 *
 * Server: ALWAYS persists (when allowed) then syncs; pet refresh happens only when pets were touched.
 */
public final class PacketEquipRequest {

    // ---------------- constants ----------------
    private static final Set<String> VALID_TYPES = Set.of("particles", "hats", "capes", "pets");
    private static final ResourceLocation AIR = ResourceLocation.fromNamespaceAndPath("minecraft", "air");

    // client-side debounce state (must exist on both dists to avoid linking errors)
    private static final Map<UUID, Long> LAST_SENT = new HashMap<>();
    private static final long EQUIP_COOLDOWN_MS = 250L;

    // ---------------- payload ----------------
    @Nullable private final String type;       // null => clear all
    private final ResourceLocation id;         // AIR => clear type
    private final int variant;                 // -1 unused
    private final int colorARGB;               // -1 unused
    private final CompoundTag style;           // optional per-type keys

    // ---------------- ctor ----------------
    public PacketEquipRequest(@Nullable String type, @Nullable ResourceLocation id,
                              int variant, int colorARGB, @Nullable CompoundTag style) {
        this.type = type;
        this.id = (id == null) ? AIR : id;
        this.variant = variant;
        this.colorARGB = colorARGB;
        this.style = (style == null) ? new CompoundTag() : style;
    }

    // ---------------- codec ----------------
    public static void encode(PacketEquipRequest msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.type != null);
        if (msg.type != null) buf.writeUtf(msg.type, 64);
        buf.writeResourceLocation(msg.id);
        buf.writeVarInt(msg.variant);
        buf.writeInt(msg.colorARGB);
        buf.writeNbt(msg.style);
    }

    public static PacketEquipRequest decode(FriendlyByteBuf buf) {
        String t = buf.readBoolean() ? buf.readUtf(64) : null;
        ResourceLocation id = buf.readResourceLocation();
        int variant = buf.readVarInt();
        int color = buf.readInt();
        CompoundTag style = buf.readNbt();
        return new PacketEquipRequest(t, id, variant, color, style);
    }

    // ---------------- server handle (authoritative, no debounce) ----------------
    public static void handle(PacketEquipRequest msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sp = ctx.getSender();
            if (sp == null) return;

            PlayerData.get(sp).ifPresent(data -> {
                final boolean isClearAll  = (msg.type == null || msg.type.isBlank());
                final boolean isClearType = !isClearAll && isAir(msg.id);

                // entitlement gate (equip only)
                if (!isClearAll && !isClearType && VALID_TYPES.contains(msg.type)) {
                    // First check entitlements (existing system)
                    boolean allowed = isEquipAllowed(sp, msg.type, msg.id);
                    if (!allowed) {
                        CosmeticsLite.LOGGER.debug("[CosLite] Denied equip (entitlements): {} -> {} for {}",
                                msg.type, msg.id, sp.getGameProfile().getName());
                        CosmeticsLite.NETWORK.send(
                                PacketDistributor.PLAYER.with(() -> sp),
                                new S2CEquipDenied(msg.type, msg.id)
                        );
                        return;
                    }

                    // Then check permissions (rank-based system)
                    // Use centralized check - only message on user-initiated actions (this is one)
                    com.pastlands.cosmeticslite.permission.CosmeticsPermissions.PermissionResult permResult = 
                        checkPermissionForEquipWithReason(sp, msg.type, msg.id);
                    if (!permResult.allowed()) {
                        CosmeticDef def = CosmeticsRegistry.get(msg.id);
                        String reason = (def == null) 
                            ? "Unknown cosmetic. Please relog or contact staff."
                            : "You don't have permission to use this cosmetic.";
                        CosmeticsLite.LOGGER.debug("[CosLite] Denied equip (permissions): {} -> {} for {} (reason: {})",
                                msg.type, msg.id, sp.getGameProfile().getName(), permResult.reasonCode());
                        // Only message on user-initiated actions (equip request is one)
                        sp.sendSystemMessage(Component.literal("§c[CosmeticsLite] " + reason));
                        CosmeticsLite.NETWORK.send(
                                PacketDistributor.PLAYER.with(() -> sp),
                                new S2CEquipDenied(msg.type, msg.id)
                        );
                        return;
                    }
                }

                // --- persist ---
                if (isClearAll) {
                    // Full reset: remove ids + styles
                    // DEBUG: Log cape state before and after clear (gated behind debug flag)
                    if (CosmeticsLite.DEBUG_SYNC) {
                        ResourceLocation capeBefore = data.getEquippedCapeId();
                        CosmeticsLite.LOGGER.debug("[CosLite] ClearAll: cape before={}", capeBefore);
                        data.clearAll();
                        ResourceLocation capeAfter = data.getEquippedCapeId();
                        CosmeticsLite.LOGGER.debug("[CosLite] ClearAll: cape after={}", capeAfter);
                    } else {
                        data.clearAll();
                    }
                } else if (!VALID_TYPES.contains(msg.type)) {
                    // Unknown type; ignore
                    return;
                } else if (isClearType) {
                    // IMPORTANT: clear ONLY the equipped id, KEEP style (so it persists across re-equip)
                    data.setEquippedId(msg.type, null);
                } else {
                    // Equip id + optional style
                    data.setEquippedId(msg.type, msg.id);
                    if (msg.variant >= 0)      data.setEquippedVariant(msg.type, msg.variant);
                    if (msg.colorARGB >= 0)    data.setEquippedColor(msg.type, msg.colorARGB);
                    if (!msg.style.isEmpty())  data.setEquippedStyleTag(msg.type, msg.style);
                }

             // sync snapshot to client
CosmeticsSync.sync(sp);

// pets touched? refresh once (ensure immediate spawn even if a prior spawn just happened)
if (isClearAll || "pets".equals(msg.type)) {
    com.pastlands.cosmeticslite.entity.PetManager.clearSpawnDebounce(sp);
    com.pastlands.cosmeticslite.entity.PetManager.updatePlayerPet(sp);
}

            });
        });
        ctx.setPacketHandled(true);
    }

    // ---------------- helpers ----------------
    private static boolean isAir(ResourceLocation rl) {
        return rl != null && "minecraft".equals(rl.getNamespace()) && "air".equals(rl.getPath());
    }

    private static boolean isEquipAllowed(ServerPlayer sp, String type, ResourceLocation id) {
        return PlayerEntitlements.get(sp).map(cap -> {
            boolean hasAny = !cap.allPacks().isEmpty() || !cap.allCosmetics().isEmpty();
            if (!hasAny) return true;
            return cap.hasCosmetic(id);
        }).orElse(true);
    }

    /**
     * Check rank-based permissions for equipping a cosmetic.
     * Returns a simple boolean for backward compatibility.
     */
    private static boolean checkPermissionForEquip(ServerPlayer sp, String type, ResourceLocation id) {
        return checkPermissionForEquipWithReason(sp, type, id).allowed();
    }
    
    /**
     * Check rank-based permissions for equipping a cosmetic with reason code.
     * Centralized permission check that returns result with reason.
     */
    private static com.pastlands.cosmeticslite.permission.CosmeticsPermissions.PermissionResult 
            checkPermissionForEquipWithReason(ServerPlayer sp, String type, ResourceLocation id) {
        if (sp == null || type == null || id == null) {
            return com.pastlands.cosmeticslite.permission.CosmeticsPermissions.PermissionResult.deny("null_params");
        }

        // Get the cosmetic definition
        CosmeticDef def = CosmeticsRegistry.get(id);
        if (def == null) {
            // Unknown cosmetic: deny by default (security hardening)
            // Only allow OPs to use unknown cosmetics (for dev/testing)
            if (sp.hasPermissions(2)) {
                return com.pastlands.cosmeticslite.permission.CosmeticsPermissions.PermissionResult.allow();
            }
            // Deny unknown cosmetics for normal players
            return com.pastlands.cosmeticslite.permission.CosmeticsPermissions.PermissionResult.deny("unknown_cosmetic");
        }

        // Check permissions based on type
        switch (type) {
            case CosmeticsRegistry.TYPE_HATS:
                boolean hatAllowed = CosmeticsPermissions.canUseHat(sp, def);
                return hatAllowed 
                    ? com.pastlands.cosmeticslite.permission.CosmeticsPermissions.PermissionResult.allow()
                    : com.pastlands.cosmeticslite.permission.CosmeticsPermissions.PermissionResult.deny("hat_permission");

            case CosmeticsRegistry.TYPE_PARTICLES:
                return CosmeticsPermissions.checkParticlePermission(sp, def);

            case CosmeticsRegistry.TYPE_CAPES:
                boolean capeAllowed = CosmeticsPermissions.canUseFeature(sp, CosmeticsFeature.CAPES);
                return capeAllowed
                    ? com.pastlands.cosmeticslite.permission.CosmeticsPermissions.PermissionResult.allow()
                    : com.pastlands.cosmeticslite.permission.CosmeticsPermissions.PermissionResult.deny("cape_permission");

            case CosmeticsRegistry.TYPE_PETS:
                boolean petAllowed = CosmeticsPermissions.canUseFeature(sp, CosmeticsFeature.PETS);
                return petAllowed
                    ? com.pastlands.cosmeticslite.permission.CosmeticsPermissions.PermissionResult.allow()
                    : com.pastlands.cosmeticslite.permission.CosmeticsPermissions.PermissionResult.deny("pet_permission");

            default:
                // Unknown type (not permission-gated, but should be rare)
                return com.pastlands.cosmeticslite.permission.CosmeticsPermissions.PermissionResult.allow();
        }
    }

    // ---------------- client send helpers (timestamp debounce) ----------------
    @OnlyIn(Dist.CLIENT)
    public static void send(String type, ResourceLocation id, int variant, int colorARGB, @Nullable CompoundTag style) {
        if (!VALID_TYPES.contains(type)) return;
        if (!clientDebounceOk()) return;
        CosmeticsLite.NETWORK.sendToServer(new PacketEquipRequest(type, id, variant, colorARGB, style));
    }

    @OnlyIn(Dist.CLIENT)
    public static void sendClearAll(ResourceLocation airSentinel) {
        if (!clientDebounceOk()) return;
        CosmeticsLite.NETWORK.sendToServer(new PacketEquipRequest(null, airSentinel, -1, -1, new CompoundTag()));
    }

    @OnlyIn(Dist.CLIENT)
    private static boolean clientDebounceOk() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return false;
        UUID key = mc.player.getUUID();
        long now = System.currentTimeMillis();
        Long last = LAST_SENT.get(key);
        if (last != null && (now - last) < EQUIP_COOLDOWN_MS) return false;
        LAST_SENT.put(key, now);
        return true;
    }
}
