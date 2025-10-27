// src/main/java/com/pastlands/cosmeticslite/network/PacketSetPetColor.java
package com.pastlands.cosmeticslite.network;

import com.mojang.logging.LogUtils;
import com.pastlands.cosmeticslite.entity.PetManager;
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
 * Supported:
 *  - Wolf: sets collar color via Wolf#setCollarColor(DyeColor)
 *  - Sheep: sets fleece color via Sheep#setColor(DyeColor)
 *
 * Maps arbitrary 0xRRGGBB input from the color wheel to the nearest DyeColor
 * (or picks a random dye when requested).
 *
 * Forge 47.4.0 • MC 1.20.1 • Java 17
 */
public class PacketSetPetColor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Random RNG = new Random();

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

            LOGGER.info("[CosmeticsLite] Received pet color update from {}: rgb=0x{}, random={}",
                    player.getGameProfile().getName(),
                    String.format("%06X", msg.rgb),
                    msg.random
            );

            // 🔹 Locate the player's active cosmetic pet
            Entity pet = PetManager.getActivePet(player);
            if (pet == null) {
                LOGGER.debug("[CosmeticsLite] No active pet for {}, ignoring color packet.", player.getGameProfile().getName());
                return;
            }

            // 🐺 Wolf: map RGB -> DyeColor (or random) and apply collar
            if (pet instanceof Wolf wolf) {
                DyeColor dye = msg.random ? randomDye() : nearestDye(msg.rgb);
                wolf.setCollarColor(dye);
                LOGGER.info("[CosmeticsLite] Applied wolf collar color {} (rgb=0x{}) for {}",
                        dye.getName(), String.format("%06X", dye.getTextColor()), player.getGameProfile().getName());
                return;
            }

            // 🐑 Sheep: map RGB -> DyeColor (or random) and apply fleece color
            if (pet instanceof Sheep sheep) {
                DyeColor dye = msg.random ? randomDye() : nearestDye(msg.rgb);
                sheep.setColor(dye);
                LOGGER.info("[CosmeticsLite] Applied sheep fleece color {} (rgb=0x{}) for {}",
                        dye.getName(), String.format("%06X", dye.getTextColor()), player.getGameProfile().getName());
                return;
            }

            // (Other pets can be added here later; for now, color is wolf/sheep only)
            LOGGER.debug("[CosmeticsLite] Color packet received, but active pet type ({}) not handled for color.",
                    pet.getClass().getName());
        });
        ctx.get().setPacketHandled(true);
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
