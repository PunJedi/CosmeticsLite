// src/main/java/com/pastlands/cosmeticslite/network/PacketSetPetVariant.java
package com.pastlands.cosmeticslite.network;

import com.mojang.logging.LogUtils;
import com.pastlands.cosmeticslite.entity.PetManager;
import com.pastlands.cosmeticslite.entity.CosmeticPetParrot;
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
import net.minecraft.world.entity.animal.FrogVariant;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.animal.horse.Variant;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Client → Server: choose which variant the cosmetic pet should use.
 * Mirrors the Random/Explicit logic used for pet color.
 *
 * Behavior:
 *  - random==true: enable PNBT pet_random, scrub PNBT pet_variant, clear entity lock; let PetManager roll.
 *  - random==false: disable PNBT pet_random, persist PNBT pet_variant, clear entity lock; apply immediately.
 *
 * Forge 47.4.0 • MC 1.20.1 • Mojmaps
 */
public class PacketSetPetVariant {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Random RNG = new Random();

    // PNBT keys (must match PetManager)
    private static final String PNBT_ROOT       = "coslite";
    private static final String PNBT_PET_VAR    = "pet_variant";
    private static final String PNBT_PET_RANDOM = "pet_random";

    private final String variantKey; // e.g. "tabby", "temperate", "brown", "creamy"...
    private final boolean random;

    public PacketSetPetVariant(String variantKey, boolean random) {
        this.variantKey = variantKey;
        this.random = random;
    }

    // ------------- codec -------------
    public static void encode(PacketSetPetVariant msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.variantKey);
        buf.writeBoolean(msg.random);
    }

    public static PacketSetPetVariant decode(FriendlyByteBuf buf) {
        return new PacketSetPetVariant(buf.readUtf(), buf.readBoolean());
    }

    // ------------- handler -------------
    public static void handle(PacketSetPetVariant msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            Entity pet = PetManager.getActivePet(player); // may be null if not spawned yet

            if (msg.random) {
                // RANDOM ON — mirror flag, scrub explicit PNBT variant, clear entity lock, let PetManager roll
                setRandom(player, true);
                scrubExplicitVariant(player);
                clearEntityVariantLock(pet);
                PetManager.updatePlayerPet(player);
                return;
            }

            // EXPLICIT VARIANT — disable Random, persist chosen, clear lock, apply immediately
            setRandom(player, false);
            persistExplicitVariant(player, msg.variantKey);
            clearEntityVariantLock(pet);
            applyVariantToActivePet(player, msg.variantKey);
        });
        ctx.get().setPacketHandled(true);
    }

    // ------------- PNBT helpers -------------
    private static void setRandom(ServerPlayer p, boolean on) {
        CompoundTag root = p.getPersistentData().getCompound(PNBT_ROOT);
        if (root.isEmpty()) root = new CompoundTag();
        root.putBoolean(PNBT_PET_RANDOM, on);
        p.getPersistentData().put(PNBT_ROOT, root);
    }

    private static void scrubExplicitVariant(ServerPlayer p) {
        CompoundTag root = p.getPersistentData().getCompound(PNBT_ROOT);
        if (!root.isEmpty() && root.contains(PNBT_PET_VAR)) {
            root.remove(PNBT_PET_VAR);
            p.getPersistentData().put(PNBT_ROOT, root);
        }
    }

    private static void persistExplicitVariant(ServerPlayer p, String key) {
        CompoundTag root = p.getPersistentData().getCompound(PNBT_ROOT);
        if (root.isEmpty()) root = new CompoundTag();
        root.putString(PNBT_PET_VAR, key);
        root.putBoolean(PNBT_PET_RANDOM, false);
        p.getPersistentData().put(PNBT_ROOT, root);
    }

    private static void clearEntityVariantLock(Entity pet) {
        if (pet == null) return;
        CompoundTag etag = pet.getPersistentData().getCompound(PNBT_ROOT);
        if (!etag.isEmpty() && etag.contains("variant")) {
            etag.remove("variant");
            pet.getPersistentData().put(PNBT_ROOT, etag);
        }
    }

    // ------------- apply now -------------
    private static void applyVariantToActivePet(ServerPlayer player, String key) {
        Entity pet = PetManager.getActivePet(player);
        if (pet == null) return;

        // Parrot (custom cosmetic entity)
        if (pet instanceof CosmeticPetParrot parrot) {
            parrot.setVariantByKey(key);
            return;
        }

        // Fox: RED / SNOW
        if (pet instanceof Fox fox) {
            Fox.Type type = switch (key.toLowerCase(Locale.ROOT)) {
                case "snow", "white" -> Fox.Type.SNOW;
                case "red", "default" -> Fox.Type.RED;
                default -> null;
            };
            if (type != null) fox.setVariant(type);
            return;
        }

        // Cat (registry)
        if (pet instanceof Cat cat) {
            ResourceLocation rl = parseRL(key);
            ResourceKey<CatVariant> rkey = ResourceKey.create(Registries.CAT_VARIANT, rl);
            Optional<Holder.Reference<CatVariant>> holder = BuiltInRegistries.CAT_VARIANT.getHolder(rkey);
            holder.ifPresent(h -> cat.setVariant(h.value()));
            return;
        }

        // Rabbit (enum) — note: no “toast” enum
        if (pet instanceof Rabbit rabbit) {
            Rabbit.Variant v = switch (key.toLowerCase(Locale.ROOT)) {
                case "brown" -> Rabbit.Variant.BROWN;
                case "white" -> Rabbit.Variant.WHITE;
                case "black" -> Rabbit.Variant.BLACK;
                case "gold" -> Rabbit.Variant.GOLD;
                case "salt_and_pepper", "salt" -> Rabbit.Variant.SALT;
                default -> null;
            };
            if (v != null) rabbit.setVariant(v);
            return;
        }

        // Llama (enum)
        if (pet instanceof Llama llama) {
            Llama.Variant v = switch (key.toLowerCase(Locale.ROOT)) {
                case "creamy" -> Llama.Variant.CREAMY;
                case "white"  -> Llama.Variant.WHITE;
                case "brown"  -> Llama.Variant.BROWN;
                case "gray"   -> Llama.Variant.GRAY;
                default -> null;
            };
            if (v != null) llama.setVariant(v);
            return;
        }

        // Horse: coat color only
        if (pet instanceof Horse horse) {
            Variant color = switch (key.toLowerCase(Locale.ROOT)) {
                case "white" -> Variant.WHITE;
                case "creamy" -> Variant.CREAMY;
                case "chestnut" -> Variant.CHESTNUT;
                case "brown" -> Variant.BROWN;
                case "black" -> Variant.BLACK;
                case "gray" -> Variant.GRAY;
                case "dark_brown", "darkbrown", "dark-brown" -> Variant.DARK_BROWN;
                default -> null;
            };
            if (color != null) horse.setVariant(color);
            return;
        }

        // Frog (registry: temperate|warm|cold)
        if (pet instanceof Frog frog) {
            ResourceLocation rl = parseRL(key);
            ResourceKey<FrogVariant> rkey = ResourceKey.create(Registries.FROG_VARIANT, rl);
            Optional<Holder.Reference<FrogVariant>> holder = BuiltInRegistries.FROG_VARIANT.getHolder(rkey);
            holder.ifPresent(h -> frog.setVariant(h.value()));
            return;
        }

        // Villager (biome type)
        if (pet instanceof Villager villager) {
            ResourceLocation rl = parseRL(key);
            ResourceKey<net.minecraft.world.entity.npc.VillagerType> rkey =
        ResourceKey.create(Registries.VILLAGER_TYPE, rl);
Optional<Holder.Reference<net.minecraft.world.entity.npc.VillagerType>> holder =
        BuiltInRegistries.VILLAGER_TYPE.getHolder(rkey);
            holder.ifPresent(h -> villager.setVillagerData(villager.getVillagerData().setType(h.value())));
            return;
        }

        // Mooshroom (enum)
        if (pet instanceof MushroomCow cow) {
            MushroomCow.MushroomType t = switch (key.toLowerCase(Locale.ROOT)) {
                case "red" -> MushroomCow.MushroomType.RED;
                case "brown" -> MushroomCow.MushroomType.BROWN;
                default -> null;
            };
            if (t != null) cow.setVariant(t);
        }
    }

    private static ResourceLocation parseRL(String key) {
        ResourceLocation rl = ResourceLocation.tryParse(key);
        if (rl == null || rl.getNamespace().isEmpty()) {
            return ResourceLocation.fromNamespaceAndPath("minecraft", key);
        }
        return rl;
    }
}
