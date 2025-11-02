// src/main/java/com/pastlands/cosmeticslite/network/PacketSetPetVariant.java
package com.pastlands.cosmeticslite.network;

import com.mojang.logging.LogUtils;
import com.pastlands.cosmeticslite.PlayerData;
import com.pastlands.cosmeticslite.entity.CosmeticPetParrot;
import com.pastlands.cosmeticslite.entity.PetManager;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.animal.FrogVariant; // Mojmaps 1.20.1
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.animal.horse.Variant;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Client → Server: choose which variant the cosmetic pet should use.
 * Parrot (custom), Cat (registry), Rabbit (enum), Llama (enum),
 * Frog (FrogVariant registry), Mooshroom (enum), Fox (Type), Horse (Variant/Markings),
 * Villager (VillagerType registry).
 *
 * Random-safety:
 *  - If Random is ON (from PlayerData or PNBT), ignore this packet and scrub PNBT "pet_variant".
 *  - If Random is OFF, apply and persist PNBT "pet_variant", and set PNBT "pet_random" = false.
 *
 * Forge 47.4.0 • MC 1.20.1 • Mojmaps
 */
public class PacketSetPetVariant {
    private static final Logger LOGGER = LogUtils.getLogger();

    // PNBT keys (must match PetManager)
    private static final String PNBT_ROOT        = "coslite";
    private static final String PNBT_PET_RANDOM  = "pet_random";
    private static final String PNBT_PET_VARIANT = "pet_variant";

    private final String variantKey; // e.g. "red", "tabby", "salt_and_pepper", "temperate", "brown", ...

    public PacketSetPetVariant(String variantKey) {
        this.variantKey = variantKey;
    }

    // ---------------- Encoding / Decoding ----------------
    public static void encode(PacketSetPetVariant msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.variantKey);
    }

    public static PacketSetPetVariant decode(FriendlyByteBuf buf) {
        return new PacketSetPetVariant(buf.readUtf());
    }

    // ---------------- Handling ----------------
    public static void handle(PacketSetPetVariant msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            LOGGER.info("[CosmeticsLite] Received pet variant update from {}: variant={}",
                    player.getGameProfile().getName(), msg.variantKey);

            // Respect Random mode: if ON, ignore variant packets and scrub sticky variant
            if (isRandomOn(player)) {
                scrubExplicitVariant(player);
                PetManager.updatePlayerPet(player);
                return;
            }

            // Locate the player's active cosmetic pet
            Entity pet = PetManager.getActivePet(player);
            if (pet == null) {
                LOGGER.debug("[CosmeticsLite] No active pet for {}, ignoring variant packet.",
                        player.getGameProfile().getName());
                return;
            }

            boolean applied = false;

            // 🦜 Parrot (custom cosmetic entity)
            if (pet instanceof CosmeticPetParrot parrot) {
                parrot.setVariantByKey(msg.getVariantKey());
                applied = true;
            }
            // 🦊 Fox: RED / SNOW
            else if (pet instanceof Fox fox) {
                Fox.Type type = switch (msg.getVariantKey().toLowerCase(Locale.ROOT)) {
                    case "snow", "white" -> Fox.Type.SNOW;
                    case "red", "default" -> Fox.Type.RED;
                    default -> null;
                };
                if (type != null) { fox.setVariant(type); applied = true; }
            }
            // 🐱 Cat (registry-backed)
            else if (pet instanceof Cat cat) {
                ResourceLocation rl = parseVariantLocation(msg.variantKey);
                ResourceKey<CatVariant> key = ResourceKey.create(Registries.CAT_VARIANT, rl);
                Optional<Holder.Reference<CatVariant>> holder = BuiltInRegistries.CAT_VARIANT.getHolder(key);
                if (holder.isPresent()) { cat.setVariant(holder.get().value()); applied = true; }
            }
            // 🐇 Rabbit (enum; no 'TOAST' constant — that skin is name-based)
            else if (pet instanceof Rabbit rabbit) {
                Rabbit.Variant v = switch (msg.variantKey.toLowerCase(Locale.ROOT)) {
                    case "brown"            -> Rabbit.Variant.BROWN;
                    case "white"            -> Rabbit.Variant.WHITE;
                    case "black"            -> Rabbit.Variant.BLACK;
                    case "gold"             -> Rabbit.Variant.GOLD;
                    case "salt_and_pepper"  -> Rabbit.Variant.SALT;
                    default -> null;
                };
                if (v != null) { rabbit.setVariant(v); applied = true; }
            }
            // 🦙 Llama (enum)
            else if (pet instanceof Llama llama) {
                Llama.Variant v = switch (msg.variantKey.toLowerCase(Locale.ROOT)) {
                    case "creamy" -> Llama.Variant.CREAMY;
                    case "white"  -> Llama.Variant.WHITE;
                    case "brown"  -> Llama.Variant.BROWN;
                    case "gray"   -> Llama.Variant.GRAY;
                    default -> null;
                };
                if (v != null) { llama.setVariant(v); applied = true; }
            }
            // 🐴 Horse: coat color only (1.20.1)
            else if (pet instanceof Horse horse) {
                Variant colorEnum = switch (msg.getVariantKey().toLowerCase(Locale.ROOT)) {
                    case "white"      -> Variant.WHITE;
                    case "creamy"     -> Variant.CREAMY;
                    case "chestnut"   -> Variant.CHESTNUT;
                    case "brown"      -> Variant.BROWN;
                    case "black"      -> Variant.BLACK;
                    case "gray"       -> Variant.GRAY;
                    case "dark_brown", "darkbrown", "dark-brown" -> Variant.DARK_BROWN;
                    default -> null;
                };
                if (colorEnum != null) { horse.setVariant(colorEnum); applied = true; }
            }
            // 🐸 Frog (registry-backed FrogVariant in 1.20.1)
            else if (pet instanceof Frog frog) {
                ResourceLocation rl = parseVariantLocation(msg.getVariantKey()); // "temperate" | "warm" | "cold"
                ResourceKey<FrogVariant> key = ResourceKey.create(Registries.FROG_VARIANT, rl);
                Optional<Holder.Reference<FrogVariant>> holder = BuiltInRegistries.FROG_VARIANT.getHolder(key);
                if (holder.isPresent()) { frog.setVariant(holder.get().value()); applied = true; }
            }
            // 👨 Villager (registry-backed VillagerType)
            else if (pet instanceof Villager villager) {
                ResourceLocation rl = parseVariantLocation(msg.getVariantKey());
                ResourceKey<VillagerType> key = ResourceKey.create(Registries.VILLAGER_TYPE, rl);
                Optional<Holder.Reference<VillagerType>> holder = BuiltInRegistries.VILLAGER_TYPE.getHolder(key);
                if (holder.isPresent()) {
                    villager.setVillagerData(villager.getVillagerData().setType(holder.get().value()));
                    applied = true;
                }
            }
            // 🍄 Mooshroom (enum)
            else if (pet instanceof MushroomCow cow) {
                MushroomCow.MushroomType t = switch (msg.variantKey.toLowerCase(Locale.ROOT)) {
                    case "red"   -> MushroomCow.MushroomType.RED;
                    case "brown" -> MushroomCow.MushroomType.BROWN;
                    default -> null;
                };
                if (t != null) { cow.setVariant(t); applied = true; }
            }

            if (applied) {
                persistExplicitVariant(player, msg.variantKey);
                LOGGER.info("[CosmeticsLite] Applied variant '{}' to {}'s pet (Random OFF).",
                        msg.getVariantKey(), player.getGameProfile().getName());
            } else {
                LOGGER.warn("[CosmeticsLite] Variant '{}' not handled for active pet type {}",
                        msg.getVariantKey(), (pet != null ? pet.getClass().getName() : "null"));
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static ResourceLocation parseVariantLocation(String key) {
        // Accept bare keys (e.g., "tabby") or namespaced (e.g., "minecraft:tabby")
        ResourceLocation rl = ResourceLocation.tryParse(key);
        if (rl == null || rl.getNamespace().isEmpty()) {
            return ResourceLocation.fromNamespaceAndPath("minecraft", key);
        }
        return rl;
    }

    public String getVariantKey() {
        return variantKey;
    }

    /* ===================== Random/PNBT helpers ===================== */

    private static boolean isRandomOn(ServerPlayer player) {
        // Try PlayerData first
        Optional<Boolean> pd = PlayerData.get(player).flatMap(pdObj -> {
            for (String name : new String[]{"isPetColorRandom", "isRandomPetColor", "isPetRandom", "isRandom"}) {
                try {
                    Method m = pdObj.getClass().getMethod(name);
                    Object v = m.invoke(pdObj);
                    if (v instanceof Boolean b) return Optional.of(b);
                } catch (Exception ignored) {}
            }
            for (String name : new String[]{"getPetColor", "getSelectedPetColor", "getPetColorName"}) {
                try {
                    Method m = pdObj.getClass().getMethod(name);
                    Object v = m.invoke(pdObj);
                    if (v instanceof String s) {
                        String ss = s.toLowerCase(Locale.ROOT);
                        if ("random".equals(ss) || "rnd".equals(ss)) return Optional.of(true);
                    }
                } catch (Exception ignored) {}
            }
            return Optional.empty();
        });
        if (pd.isPresent()) return pd.get();

        // Fallback to PNBT
        CompoundTag root = player.getPersistentData().getCompound(PNBT_ROOT);
        return !root.isEmpty() && root.getBoolean(PNBT_PET_RANDOM);
    }

    private static void scrubExplicitVariant(ServerPlayer player) {
        CompoundTag root = player.getPersistentData().getCompound(PNBT_ROOT);
        if (!root.isEmpty() && root.contains(PNBT_PET_VARIANT)) {
            root.remove(PNBT_PET_VARIANT);
            player.getPersistentData().put(PNBT_ROOT, root);
        }
    }

    private static void persistExplicitVariant(ServerPlayer player, String variantKey) {
        CompoundTag root = player.getPersistentData().getCompound(PNBT_ROOT);
        if (root.isEmpty()) root = new CompoundTag();
        root.putString(PNBT_PET_VARIANT, variantKey);
        root.putBoolean(PNBT_PET_RANDOM, false); // explicit choice cancels random
        player.getPersistentData().put(PNBT_ROOT, root);
    }
}
