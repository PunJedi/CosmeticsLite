// src/main/java/com/pastlands/cosmeticslite/PacketEquipRequest.java
package com.pastlands.cosmeticslite;

import com.pastlands.cosmeticslite.network.S2CEquipDenied;
import net.minecraft.client.Minecraft;
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
    private static final Set<String> VALID_TYPES = Set.of("particles", "hats", "capes", "pets", "gadgets");
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
                    boolean allowed = isEquipAllowed(sp, msg.type, msg.id);
                    if (!allowed) {
                        CosmeticsLite.LOGGER.debug("[CosLite] Denied equip: {} -> {} for {}",
                                msg.type, msg.id, sp.getGameProfile().getName());
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
                    data.clearAll();
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
