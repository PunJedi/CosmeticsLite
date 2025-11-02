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

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Client → Server: pet color + random toggle.
 *
 * Supported:
 *  - Wolf: sets collar color via Wolf#setCollarColor(DyeColor)
 *  - Sheep: sets fleece color via Sheep#setColor(DyeColor)
 *
 * Behavior:
 *  - If msg.random == true:
 *      Enable Random (PNBT pet_random=true), scrub PNBT "pet_dye",
 *      clear entity lock (coslite.dye), and let PetManager roll once.
 *  - If msg.random == false (explicit color pick):
 *      Disable Random (PNBT pet_random=false), persist PNBT "pet_dye",
 *      clear entity lock (coslite.dye), apply immediately to active pet,
 *      and let PetManager keep it stable across re-equip/relog.
 *
 * Forge 47.4.0 • MC 1.20.1 • Java 17
 */
public class PacketSetPetColor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Random RNG = new Random();

    // PNBT keys (must match PetManager)
    private static final String PNBT_ROOT        = "coslite";
    private static final String PNBT_PET_DYE     = "pet_dye";
    private static final String PNBT_PET_RANDOM  = "pet_random";

    private final int rgb;       // 0xRRGGBB
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

            // Get active pet once (may be null)
            Entity active = PetManager.getActivePet(player);

            if (msg.random) {
                // RANDOM ON: mirror flag, scrub explicit PNBT dye, clear entity's locked dye so applySavedStyle rolls once
                setRandom(player, true);
                scrubExplicitDye(player);
                clearEntityDyeLock(active);
                PetManager.updatePlayerPet(player);
                return;
            }

            // EXPLICIT COLOR: force Random OFF, persist explicit dye, clear entity lock, apply immediately
            DyeColor chosen = nearestDye(msg.rgb);
            setRandom(player, false);
            persistExplicitDye(player, chosen);
            clearEntityDyeLock(active);
            applyToActivePet(player, chosen); // immediate visual
            // PetManager.applySavedStyle will see PNBT pet_dye and keep it stable
        });
        ctx.get().setPacketHandled(true);
    }

    // ---------------- Random / PNBT helpers ----------------

    private static void setRandom(ServerPlayer player, boolean on) {
        CompoundTag root = player.getPersistentData().getCompound(PNBT_ROOT);
        if (root.isEmpty()) root = new CompoundTag();
        root.putBoolean(PNBT_PET_RANDOM, on);
        player.getPersistentData().put(PNBT_ROOT, root);
    }

    private static void scrubExplicitDye(ServerPlayer player) {
        CompoundTag root = player.getPersistentData().getCompound(PNBT_ROOT);
        if (!root.isEmpty() && root.contains(PNBT_PET_DYE)) {
            root.remove(PNBT_PET_DYE);
            player.getPersistentData().put(PNBT_ROOT, root);
        }
    }

    private static void persistExplicitDye(ServerPlayer player, DyeColor dye) {
        CompoundTag root = player.getPersistentData().getCompound(PNBT_ROOT);
        if (root.isEmpty()) root = new CompoundTag();
        root.putString(PNBT_PET_DYE, dye.getName());
        root.putBoolean(PNBT_PET_RANDOM, false);
        player.getPersistentData().put(PNBT_ROOT, root);
    }

    // Clear the entity-side random "lock" so our next style application (or immediate set) wins.
    private static void clearEntityDyeLock(Entity pet) {
        if (pet == null) return;
        CompoundTag etag = pet.getPersistentData().getCompound(PNBT_ROOT);
        if (!etag.isEmpty() && etag.contains("dye")) {
            etag.remove("dye");
            pet.getPersistentData().put(PNBT_ROOT, etag);
        }
    }

    // ---------------- Apply to active pet ----------------

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

    private static DyeColor randomDye() {
        DyeColor[] all = DyeColor.values();
        return all[RNG.nextInt(all.length)];
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
