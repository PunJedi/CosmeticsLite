// src/main/java/com/pastlands/cosmeticslite/network/PacketSetPetVariant.java
package com.pastlands.cosmeticslite.network;

import com.mojang.logging.LogUtils;
import com.pastlands.cosmeticslite.entity.PetManager;
import com.pastlands.cosmeticslite.entity.CosmeticPetParrot;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.CatVariant;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.entity.animal.MushroomCow;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.FrogVariant; // 1.20.1 location
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.animal.horse.Variant;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Client → Server: choose which variant the cosmetic pet should use.
 * Parrot (custom), Cat (registry), Rabbit (enum), Llama (enum),
 * Frog (FrogVariant registry), Mooshroom (enum), Fox (Type), Horse (Variant/Markings).
 *
 * Forge 47.4.0 • MC 1.20.1 • Mojmaps
 */
public class PacketSetPetVariant {
    private static final Logger LOGGER = LogUtils.getLogger();

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

            // 🔹 Look up player's active cosmetic pet
            Entity pet = PetManager.getActivePet(player);
            if (pet == null) {
                LOGGER.debug("[CosmeticsLite] No active pet for {}, ignoring variant packet.",
                        player.getGameProfile().getName());
                return;
            }

            // 🦜 Parrot (custom cosmetic entity)
            if (pet instanceof CosmeticPetParrot parrot) {
                parrot.setVariantByKey(msg.getVariantKey());
                LOGGER.info("[CosmeticsLite] Applied parrot variant '{}' to {}'s pet",
                        msg.getVariantKey(), player.getGameProfile().getName());
                return;
            }

            // 🦊 Fox: RED / SNOW
            if (pet instanceof Fox fox) {
                Fox.Type type = switch (msg.getVariantKey().toLowerCase(Locale.ROOT)) {
                    case "snow", "white" -> Fox.Type.SNOW;
                    case "red", "default" -> Fox.Type.RED;
                    default -> null;
                };

                if (type != null) {
                    fox.setVariant(type);
                    LOGGER.info("[CosmeticsLite] Applied fox variant '{}' to {}'s pet",
                            msg.getVariantKey(), player.getGameProfile().getName());
                } else {
                    LOGGER.warn("[CosmeticsLite] Unknown fox variant '{}'. No change applied.",
                            msg.getVariantKey());
                }
                return;
            }

            // 🐱 Cat (registry-backed)
            if (pet instanceof Cat cat) {
                ResourceLocation rl = parseVariantLocation(msg.variantKey);
                ResourceKey<CatVariant> key = ResourceKey.create(Registries.CAT_VARIANT, rl);
                Optional<Holder.Reference<CatVariant>> holder = BuiltInRegistries.CAT_VARIANT.getHolder(key);
                if (holder.isPresent()) {
                    cat.setVariant(holder.get().value());
                    LOGGER.info("[CosmeticsLite] Applied cat variant '{}' ({}) to {}'s pet",
                            msg.getVariantKey(), rl, player.getGameProfile().getName());
                } else {
                    LOGGER.warn("[CosmeticsLite] Unknown cat variant '{}'(resolved RL: {}). No change applied.",
                            msg.getVariantKey(), rl);
                }
                return;
            }

            // 🐇 Rabbit (enum; no 'TOAST' constant — that skin is name-based)
            if (pet instanceof Rabbit rabbit) {
                Rabbit.Variant v = switch (msg.variantKey.toLowerCase(Locale.ROOT)) {
                    case "brown"            -> Rabbit.Variant.BROWN;
                    case "white"            -> Rabbit.Variant.WHITE;
                    case "black"            -> Rabbit.Variant.BLACK;
                    case "gold"             -> Rabbit.Variant.GOLD;
                    case "salt_and_pepper"  -> Rabbit.Variant.SALT;
                    // "toast" is not an enum variant; skip it gracefully
                    default -> null;
                };
                if (v != null) {
                    rabbit.setVariant(v);
                    LOGGER.info("[CosmeticsLite] Applied rabbit variant '{}' to {}'s pet",
                            msg.getVariantKey(), player.getGameProfile().getName());
                } else {
                    LOGGER.warn("[CosmeticsLite] Unknown/unsupported rabbit variant '{}'", msg.getVariantKey());
                }
                return;
            }

            // 🦙 Llama (enum; trader llama intentionally excluded in UI)
            if (pet instanceof Llama llama) {
                Llama.Variant v = switch (msg.variantKey.toLowerCase(Locale.ROOT)) {
                    case "creamy" -> Llama.Variant.CREAMY;
                    case "white"  -> Llama.Variant.WHITE;
                    case "brown"  -> Llama.Variant.BROWN;
                    case "gray"   -> Llama.Variant.GRAY;
                    default -> null;
                };
                if (v != null) {
                    llama.setVariant(v);
                    LOGGER.info("[CosmeticsLite] Applied llama variant '{}' to {}'s pet",
                            msg.getVariantKey(), player.getGameProfile().getName());
                } else {
                    LOGGER.warn("[CosmeticsLite] Unknown llama variant '{}'", msg.getVariantKey());
                }
                return;
            }

            // 🐴 Horse: coat color only (1.20.1)
if (pet instanceof Horse horse) {
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

    if (colorEnum != null) {
        horse.setVariant(colorEnum); // set coat color only
        LOGGER.info("[CosmeticsLite] Applied horse color '{}' to {}'s pet",
                msg.getVariantKey(), player.getGameProfile().getName());
    } else {
        LOGGER.warn("[CosmeticsLite] Unknown horse color '{}'. No change applied.", msg.getVariantKey());
    }
    return;
}


            // 🐸 Frog (registry-backed FrogVariant in 1.20.1)
if (pet instanceof Frog frog) {
    ResourceLocation rl = parseVariantLocation(msg.getVariantKey()); // "temperate" | "warm" | "cold"
    ResourceKey<FrogVariant> key = ResourceKey.create(Registries.FROG_VARIANT, rl);
    Optional<Holder.Reference<FrogVariant>> holder = BuiltInRegistries.FROG_VARIANT.getHolder(key);

    if (holder.isPresent()) {
        frog.setVariant(holder.get().value());

        // Add a friendly label for logs
        String colorLabel = switch (msg.getVariantKey().toLowerCase(Locale.ROOT)) {
            case "cold"      -> "White";
            case "warm"      -> "Orange";
            case "temperate" -> "Green";
            default          -> "Unknown";
        };

        LOGGER.info("[CosmeticsLite] Applied frog variant '{}' → {} to {}'s pet",
                msg.getVariantKey(), colorLabel, player.getGameProfile().getName());
    } else {
        LOGGER.warn("[CosmeticsLite] Unknown frog variant '{}'(resolved RL: {}). No change applied.",
                msg.getVariantKey(), rl);
    }
    return;
}
// 👨 Villager (registry-backed VillagerType in 1.20.1)
if (pet instanceof net.minecraft.world.entity.npc.Villager villager) {
    ResourceLocation rl = parseVariantLocation(msg.getVariantKey()); 
    ResourceKey<net.minecraft.world.entity.npc.VillagerType> key =
            ResourceKey.create(Registries.VILLAGER_TYPE, rl);
    Optional<Holder.Reference<net.minecraft.world.entity.npc.VillagerType>> holder =
            BuiltInRegistries.VILLAGER_TYPE.getHolder(key);

    if (holder.isPresent()) {
        villager.setVillagerData(
                villager.getVillagerData().setType(holder.get().value()));
        LOGGER.info("[CosmeticsLite] Applied villager biome '{}' ({}) to {}'s pet",
                msg.getVariantKey(), rl, player.getGameProfile().getName());
    } else {
        LOGGER.warn("[CosmeticsLite] Unknown villager biome '{}'(resolved RL: {}). No change applied.",
                msg.getVariantKey(), rl);
    }
    return;
}


            // 🍄 Mooshroom (enum)
            if (pet instanceof MushroomCow cow) {
                MushroomCow.MushroomType t = switch (msg.variantKey.toLowerCase(Locale.ROOT)) {
                    case "red"   -> MushroomCow.MushroomType.RED;
                    case "brown" -> MushroomCow.MushroomType.BROWN;
                    default -> null;
                };
                if (t != null) {
                    cow.setVariant(t);
                    LOGGER.info("[CosmeticsLite] Applied mooshroom variant '{}' to {}'s pet",
                            msg.getVariantKey(), player.getGameProfile().getName());
                } else {
                    LOGGER.warn("[CosmeticsLite] Unknown mooshroom variant '{}'", msg.getVariantKey());
                }
                return;
            }

            // Unknown pet type; ignore quietly
            LOGGER.debug("[CosmeticsLite] Variant packet received but active pet type ({}) is not handled.",
                    pet.getClass().getName());
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
}
