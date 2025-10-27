package com.pastlands.cosmeticslite;

import com.pastlands.cosmeticslite.network.S2CEquipDenied;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Client -> Server: request to equip/clear a cosmetic.
 *
 * Semantics:
 *  - (type != null && id != AIR) : equip that id for the given type ("particles"|"hats"|"capes"|"pets")
 *  - (type != null && id == AIR) : clear ONLY that type
 *  - (type == null)              : clear ALL types (id is ignored; UI sends AIR here)
 *
 * Server-authoritative: state is verified and then synced back to the client.
 * This version includes feedback via S2CEquipDenied when a player lacks entitlement.
 */
public final class PacketEquipRequest {

    // Known valid types
    private static final Set<String> VALID_TYPES = Set.of("particles", "hats", "capes", "pets", "gadgets");

    private static final ResourceLocation AIR =
            ResourceLocation.fromNamespaceAndPath("minecraft", "air");

    @Nullable
    private final String type;           // null => clear all
    private final ResourceLocation id;   // cosmetic id (AIR means "clear")

    // ------------------------------------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------------------------------------

    public PacketEquipRequest(@Nullable String type, @Nullable ResourceLocation id) {
        this.type = type;
        this.id = (id == null) ? AIR : id;
    }

    // ------------------------------------------------------------------------------------------------
    // Encode / Decode
    // ------------------------------------------------------------------------------------------------

    public static void encode(PacketEquipRequest msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.type != null);
        if (msg.type != null) buf.writeUtf(msg.type, 64);
        buf.writeResourceLocation(msg.id);
    }

    public static PacketEquipRequest decode(FriendlyByteBuf buf) {
        String t = buf.readBoolean() ? buf.readUtf(64) : null;
        ResourceLocation id = buf.readResourceLocation();
        return new PacketEquipRequest(t, id);
    }

    // ------------------------------------------------------------------------------------------------
    // Handle (server)
    // ------------------------------------------------------------------------------------------------

    public static void handle(PacketEquipRequest msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sp = ctx.getSender();
            if (sp == null) return;

            PlayerData.get(sp).ifPresent(data -> {
                boolean isClearAll = (msg.type == null || msg.type.isBlank());
                boolean isClearType = !isClearAll && isAir(msg.id);

                // ------------------------------------------------------------------------------------------------
                // Permissions gate: equipping is gated if entitlements exist. Clearing is always allowed.
                // ------------------------------------------------------------------------------------------------
                if (!isClearAll && !isClearType && VALID_TYPES.contains(msg.type)) {
                    boolean allowed = isEquipAllowed(sp, msg.type, msg.id);
                    if (!allowed) {
                        // Log for server operators
                        CosmeticsLite.LOGGER.debug("[CosLite] Denied equip: {} -> {} for {}", msg.type, msg.id, sp.getGameProfile().getName());

                        // Send feedback packet to the player
                        CosmeticsLite.NETWORK.send(
                                PacketDistributor.PLAYER.with(() -> sp),
                                new S2CEquipDenied(msg.type, msg.id)
                        );
                        return; // stop here
                    }
                }

                // ------------------------------------------------------------------------------------------------
                // Apply the requested change
                // ------------------------------------------------------------------------------------------------
                if (isClearAll) {
                    data.clearAll();
                } else if (!VALID_TYPES.contains(msg.type)) {
                    // Unknown type -> ignore gracefully
                    return;
                } else if (isClearType) {
                    data.clearEquipped(msg.type);
                } else {
                    data.setEquippedId(msg.type, msg.id);
                }

                // ------------------------------------------------------------------------------------------------
                // Sync the resulting state to the client
                // ------------------------------------------------------------------------------------------------
                CosmeticsSync.sync(sp);

                // If pets were changed, update the player's real pet entity
                if (isClearAll || "pets".equals(msg.type)) {
                    com.pastlands.cosmeticslite.entity.PetManager.updatePlayerPet(sp);
                }
            });
        });
        ctx.setPacketHandled(true);
    }

    // ------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------

    private static boolean isAir(ResourceLocation rl) {
        return rl != null && "minecraft".equals(rl.getNamespace()) && "air".equals(rl.getPath());
    }

    /**
     * Minimal entitlement policy:
     *  - If the player has no entitlements granted (both sets empty), allow equipping (default-open).
     *  - If the player has any entitlements, require explicit grant for this cosmetic.
     */
    private static boolean isEquipAllowed(ServerPlayer sp, String type, ResourceLocation id) {
        return PlayerEntitlements.get(sp).map(cap -> {
            boolean hasAny = !cap.allPacks().isEmpty() || !cap.allCosmetics().isEmpty();
            if (!hasAny) return true; // default-open until grants exist
            return cap.hasCosmetic(id);
        }).orElse(true); // if missing capability, fail-open
    }

    // ------------------------------------------------------------------------------------------------
    // Client helpers
    // ------------------------------------------------------------------------------------------------

    /** Equip one cosmetic for a given type ("particles"|"hats"|"capes"|"pets"). */
    public static void send(String type, ResourceLocation id) {
        CosmeticsLite.NETWORK.sendToServer(new PacketEquipRequest(type, id));
    }

    /** Clear ALL equipped cosmetics (UI passes minecraft:air here; id value is ignored). */
    public static void send(ResourceLocation airSentinel) {
        CosmeticsLite.NETWORK.sendToServer(new PacketEquipRequest(null, airSentinel));
    }
}
