// src/main/java/com/pastlands/cosmeticslite/network/PacketSetPetColor.java
package com.pastlands.cosmeticslite.network;

import com.mojang.logging.LogUtils;
import com.pastlands.cosmeticslite.PlayerData;
import com.pastlands.cosmeticslite.entity.PetManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.item.DyeColor;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;

import java.util.Random;
import java.util.function.Supplier;

/**
 * Client → Server: pet color + random toggle.
 *
 * Behavior:
 *  - Random ON  : set PNBT "pet_random"=true, CLEAR PlayerData color for PETS (-1),
 *                 clear entity dye lock, then let PetManager restyle.
 *  - Random OFF : set PNBT "pet_random"=false, WRITE PlayerData color (ARGB),
 *                 clear entity dye lock, apply to active pet immediately, then update.
 *
 * Notes:
 *  - We no longer persist explicit dye to PNBT ("pet_dye"). PlayerData.styles is authoritative.
 *  - Immediate entity update keeps UX snappy; persistence guarantees re-equip/relog stability.
 */
public class PacketSetPetColor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Random RNG = new Random();

    // PNBT keys (must match PetManager legacy handling of the random toggle)
    private static final String PNBT_ROOT       = "coslite";
    private static final String PNBT_PET_RANDOM = "pet_random";

    private final int rgb;       // 0xRRGGBB from client
    private final boolean random;

    public PacketSetPetColor(int rgb, boolean random) {
        this.rgb = rgb;
        this.random = random;
    }

    // ---------------- Encoding / Decoding ----------------
    public static void encode(PacketSetPetColor msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.rgb);
        buf.writeBoolean(msg.random);
    }

    public static PacketSetPetColor decode(FriendlyByteBuf buf) {
        int rgb = buf.readInt();
        boolean random = buf.readBoolean();
        return new PacketSetPetColor(rgb, random);
    }

    // ---------------- Handling ----------------
    public static void handle(PacketSetPetColor msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            Entity active = PetManager.getActivePet(player);

            if (msg.random) {
                // RANDOM ON → PNBT flag true + clear PlayerData color
                setRandomFlag(player, true);

                PlayerData.get(player).ifPresent(pd ->
                        pd.setEquippedColor(PlayerData.TYPE_PETS, -1) // clear explicit color
                );

                clearEntityDyeLock(active);
                PetManager.updatePlayerPet(player);
                return;
            }

            // RANDOM OFF → persist explicit ARGB in PlayerData + immediate visual apply
            int argb = toARGB(msg.rgb);
            DyeColor chosen = nearestDye(msg.rgb);

            setRandomFlag(player, false);

            PlayerData.get(player).ifPresent(pd ->
                    pd.setEquippedColor(PlayerData.TYPE_PETS, argb)
            );

            clearEntityDyeLock(active);
            applyToActivePet(player, chosen); // instant feedback
            PetManager.updatePlayerPet(player); // ensure server-side restyle uses persisted value
        });
        ctx.get().setPacketHandled(true);
    }

    // ---------------- Legacy random toggle PNBT ----------------
    private static void setRandomFlag(ServerPlayer player, boolean on) {
        CompoundTag root = player.getPersistentData().getCompound(PNBT_ROOT);
        if (root.isEmpty()) root = new CompoundTag();
        root.putBoolean(PNBT_PET_RANDOM, on);
        player.getPersistentData().put(PNBT_ROOT, root);
    }

    // Clear the entity-side lock so the next style application wins.
    private static void clearEntityDyeLock(Entity pet) {
        if (pet == null) return;
        CompoundTag etag = pet.getPersistentData().getCompound(PNBT_ROOT);
        if (!etag.isEmpty() && etag.contains("dye")) {
            etag.remove("dye");
            pet.getPersistentData().put(PNBT_ROOT, etag);
        }
    }

    // ---------------- Immediate visual apply ----------------
    private static void applyToActivePet(ServerPlayer player, DyeColor dye) {
        Entity pet = PetManager.getActivePet(player);
        if (pet == null) return;

        if (pet instanceof Wolf wolf) {
            wolf.setCollarColor(dye);
            return;
        }
        if (pet instanceof Sheep sheep) {
            sheep.setColor(dye);
        }
    }

    // ---------------- Helpers ----------------
    private static int toARGB(int rgb) {
        return (0xFF << 24) | (rgb & 0x00FFFFFF);
    }

    /**
     * Find nearest vanilla dye to an arbitrary 0xRRGGBB color by squared RGB distance.
     * Uses DyeColor#getTextColor() as canonical RGB for the dye.
     */
    private static DyeColor nearestDye(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8)  & 0xFF;
        int b = (rgb)       & 0xFF;

        DyeColor best = DyeColor.WHITE;
        long bestDist = Long.MAX_VALUE;

        for (DyeColor dye : DyeColor.values()) {
            int drgb = dye.getTextColor(); // Mojmaps 1.20.1
            int rr = (drgb >> 16) & 0xFF;
            int gg = (drgb >> 8)  & 0xFF;
            int bb = (drgb)       & 0xFF;

            long dr = r - rr;
            long dg = g - gg;
            long db = b - bb;
            long dist = dr * dr + dg * dg + db * db;

            if (dist < bestDist) {
                bestDist = dist;
                best = dye;
            }
        }
        return best;
    }
}
