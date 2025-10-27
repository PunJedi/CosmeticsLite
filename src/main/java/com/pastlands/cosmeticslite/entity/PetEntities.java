// src/main/java/com/pastlands/cosmeticslite/entity/PetEntities.java
package com.pastlands.cosmeticslite.entity;

import com.pastlands.cosmeticslite.CosmeticsLite;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.animal.horse.Donkey;
import net.minecraft.world.entity.animal.horse.Mule;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registry for cosmetic pet entities.
 * Forge 47.4.0 (MC 1.20.1)
 */
public final class PetEntities {

    private PetEntities() {}

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, CosmeticsLite.MODID);

    // =========================
    // Original pets
    // =========================
    public static final RegistryObject<EntityType<CosmeticPetWolf>> COSMETIC_PET_WOLF =
            ENTITY_TYPES.register("cosmetic_pet_wolf", () -> EntityType.Builder
                    .<CosmeticPetWolf>of(CosmeticPetWolf::new, MobCategory.CREATURE)
                    .sized(0.6f, 0.85f)
                    .build("cosmetic_pet_wolf"));

    public static final RegistryObject<EntityType<CosmeticPetCat>> COSMETIC_PET_CAT =
            ENTITY_TYPES.register("cosmetic_pet_cat", () -> EntityType.Builder
                    .<CosmeticPetCat>of(CosmeticPetCat::new, MobCategory.CREATURE)
                    .sized(0.6f, 0.7f)
                    .build("cosmetic_pet_cat"));

    public static final RegistryObject<EntityType<CosmeticPetChicken>> COSMETIC_PET_CHICKEN =
            ENTITY_TYPES.register("cosmetic_pet_chicken", () -> EntityType.Builder
                    .<CosmeticPetChicken>of(CosmeticPetChicken::new, MobCategory.CREATURE)
                    .sized(0.4f, 0.7f)
                    .build("cosmetic_pet_chicken"));

    // =========================
    // Previously added pets
    // =========================
    public static final RegistryObject<EntityType<CosmeticPetFox>> COSMETIC_PET_FOX =
            ENTITY_TYPES.register("cosmetic_pet_fox", () -> EntityType.Builder
                    .<CosmeticPetFox>of(CosmeticPetFox::new, MobCategory.CREATURE)
                    .sized(0.6f, 0.7f)
                    .build("cosmetic_pet_fox"));

    public static final RegistryObject<EntityType<CosmeticPetAxolotl>> COSMETIC_PET_AXOLOTL =
            ENTITY_TYPES.register("cosmetic_pet_axolotl", () -> EntityType.Builder
                    .<CosmeticPetAxolotl>of(CosmeticPetAxolotl::new, MobCategory.WATER_AMBIENT)
                    .sized(0.75f, 0.42f)
                    .build("cosmetic_pet_axolotl"));

    public static final RegistryObject<EntityType<CosmeticPetBee>> COSMETIC_PET_BEE =
            ENTITY_TYPES.register("cosmetic_pet_bee", () -> EntityType.Builder
                    .<CosmeticPetBee>of(CosmeticPetBee::new, MobCategory.CREATURE)
                    .sized(0.7f, 0.6f)
                    .build("cosmetic_pet_bee"));

    public static final RegistryObject<EntityType<CosmeticPetRabbit>> COSMETIC_PET_RABBIT =
            ENTITY_TYPES.register("cosmetic_pet_rabbit", () -> EntityType.Builder
                    .<CosmeticPetRabbit>of(CosmeticPetRabbit::new, MobCategory.CREATURE)
                    .sized(0.4f, 0.5f)
                    .build("cosmetic_pet_rabbit"));

    public static final RegistryObject<EntityType<CosmeticPetPig>> COSMETIC_PET_PIG =
            ENTITY_TYPES.register("cosmetic_pet_pig", () -> EntityType.Builder
                    .<CosmeticPetPig>of(CosmeticPetPig::new, MobCategory.CREATURE)
                    .sized(0.9f, 0.9f)
                    .build("cosmetic_pet_pig"));

    public static final RegistryObject<EntityType<CosmeticPetSheep>> COSMETIC_PET_SHEEP =
        ENTITY_TYPES.register("cosmetic_pet_sheep", () -> EntityType.Builder
                .<CosmeticPetSheep>of(CosmeticPetSheep::new, MobCategory.CREATURE)
                .sized(0.9f, 1.3f)
                .build("cosmetic_pet_sheep"));

    public static final RegistryObject<EntityType<CosmeticPetPanda>> COSMETIC_PET_PANDA =
            ENTITY_TYPES.register("cosmetic_pet_panda", () -> EntityType.Builder
                    .<CosmeticPetPanda>of(CosmeticPetPanda::new, MobCategory.CREATURE)
                    .sized(1.3f, 1.25f)
                    .build("cosmetic_pet_panda"));

    public static final RegistryObject<EntityType<CosmeticPetParrot>> COSMETIC_PET_PARROT =
            ENTITY_TYPES.register("cosmetic_pet_parrot", () -> EntityType.Builder
                    .<CosmeticPetParrot>of(CosmeticPetParrot::new, MobCategory.CREATURE)
                    .sized(0.5f, 0.9f)
                    .build("cosmetic_pet_parrot"));

    // =========================
    // Newer pets
    // =========================
    public static final RegistryObject<EntityType<CosmeticPetHorse>> COSMETIC_PET_HORSE =
            ENTITY_TYPES.register("cosmetic_pet_horse", () -> EntityType.Builder
                    .<CosmeticPetHorse>of(CosmeticPetHorse::new, MobCategory.CREATURE)
                    .sized(1.3965f, 1.6f)
                    .build("cosmetic_pet_horse"));

    public static final RegistryObject<EntityType<CosmeticPetLlama>> COSMETIC_PET_LLAMA =
            ENTITY_TYPES.register("cosmetic_pet_llama", () -> EntityType.Builder
                    .<CosmeticPetLlama>of(CosmeticPetLlama::new, MobCategory.CREATURE)
                    .sized(0.9f, 1.87f)
                    .build("cosmetic_pet_llama"));

    public static final RegistryObject<EntityType<CosmeticPetFrog>> COSMETIC_PET_FROG =
            ENTITY_TYPES.register("cosmetic_pet_frog", () -> EntityType.Builder
                    .<CosmeticPetFrog>of(CosmeticPetFrog::new, MobCategory.CREATURE)
                    .sized(0.5f, 0.5f)
                    .build("cosmetic_pet_frog"));

    public static final RegistryObject<EntityType<CosmeticPetMooshroom>> COSMETIC_PET_MOOSHROOM =
            ENTITY_TYPES.register("cosmetic_pet_mooshroom", () -> EntityType.Builder
                    .<CosmeticPetMooshroom>of(CosmeticPetMooshroom::new, MobCategory.CREATURE)
                    .sized(0.9f, 1.4f)
                    .build("cosmetic_pet_mooshroom"));

    public static final RegistryObject<EntityType<CosmeticPetDonkey>> COSMETIC_PET_DONKEY =
            ENTITY_TYPES.register("cosmetic_pet_donkey", () -> EntityType.Builder
                    .<CosmeticPetDonkey>of(CosmeticPetDonkey::new, MobCategory.CREATURE)
                    .sized(1.3965f, 1.5f)
                    .build("cosmetic_pet_donkey"));

    public static final RegistryObject<EntityType<CosmeticPetMule>> COSMETIC_PET_MULE =
            ENTITY_TYPES.register("cosmetic_pet_mule", () -> EntityType.Builder
                    .<CosmeticPetMule>of(CosmeticPetMule::new, MobCategory.CREATURE)
                    .sized(1.3965f, 1.6f)
                    .build("cosmetic_pet_mule"));

    public static final RegistryObject<EntityType<CosmeticPetCamel>> COSMETIC_PET_CAMEL =
            ENTITY_TYPES.register("cosmetic_pet_camel", () -> EntityType.Builder
                    .<CosmeticPetCamel>of(CosmeticPetCamel::new, MobCategory.CREATURE)
                    .sized(1.7f, 2.375f)
                    .build("cosmetic_pet_camel"));

    public static final RegistryObject<EntityType<CosmeticPetGoat>> COSMETIC_PET_GOAT =
            ENTITY_TYPES.register("cosmetic_pet_goat", () -> EntityType.Builder
                    .<CosmeticPetGoat>of(CosmeticPetGoat::new, MobCategory.CREATURE)
                    .sized(0.9f, 1.3f)
                    .build("cosmetic_pet_goat"));

    public static final RegistryObject<EntityType<CosmeticPetOcelot>> COSMETIC_PET_OCELOT =
            ENTITY_TYPES.register("cosmetic_pet_ocelot", () -> EntityType.Builder
                    .<CosmeticPetOcelot>of(CosmeticPetOcelot::new, MobCategory.CREATURE)
                    .sized(0.6f, 0.7f)
                    .build("cosmetic_pet_ocelot"));

    public static final RegistryObject<EntityType<CosmeticPetCow>> COSMETIC_PET_COW =
            ENTITY_TYPES.register("cosmetic_pet_cow", () -> EntityType.Builder
                    .<CosmeticPetCow>of(CosmeticPetCow::new, MobCategory.CREATURE)
                    .sized(0.9f, 1.4f)
                    .build("cosmetic_pet_cow"));

    public static final RegistryObject<EntityType<CosmeticPetVillager>> COSMETIC_PET_VILLAGER =
            ENTITY_TYPES.register("cosmetic_pet_villager", () -> EntityType.Builder
                    .<CosmeticPetVillager>of(CosmeticPetVillager::new, MobCategory.CREATURE)
                    .sized(0.6f, 1.95f)
                    .build("cosmetic_pet_villager"));

    public static final RegistryObject<EntityType<CosmeticPetVex>> COSMETIC_PET_VEX =
            ENTITY_TYPES.register("cosmetic_pet_vex", () -> EntityType.Builder
                    .<CosmeticPetVex>of(CosmeticPetVex::new, MobCategory.MONSTER)
                    .sized(0.4f, 0.8f)
                    .build("cosmetic_pet_vex"));

    public static final RegistryObject<EntityType<CosmeticPetBlaze>> COSMETIC_PET_BLAZE =
            ENTITY_TYPES.register("cosmetic_pet_blaze", () -> EntityType.Builder
                    .<CosmeticPetBlaze>of(CosmeticPetBlaze::new, MobCategory.MONSTER)
                    .sized(0.6f, 1.8f)
                    .build("cosmetic_pet_blaze"));

    public static final RegistryObject<EntityType<CosmeticPetSnowGolem>> COSMETIC_PET_SNOW_GOLEM =
            ENTITY_TYPES.register("cosmetic_pet_snow_golem", () -> EntityType.Builder
                    .<CosmeticPetSnowGolem>of(CosmeticPetSnowGolem::new, MobCategory.MISC)
                    .sized(0.7f, 1.9f)
                    .build("cosmetic_pet_snow_golem"));

    public static final RegistryObject<EntityType<CosmeticPetIronGolem>> COSMETIC_PET_IRON_GOLEM =
            ENTITY_TYPES.register("cosmetic_pet_iron_golem", () -> EntityType.Builder
                    .<CosmeticPetIronGolem>of(CosmeticPetIronGolem::new, MobCategory.MISC)
                    .sized(1.4f, 2.7f)
                    .build("cosmetic_pet_iron_golem"));

    // =========================
    // Bootstrap
    // =========================
    public static void register(IEventBus eventBus) {
        ENTITY_TYPES.register(eventBus);
    }

    // =========================
    // Attributes
    // =========================
    @Mod.EventBusSubscriber(modid = CosmeticsLite.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class EntityAttributeHandler {
        @SubscribeEvent
        public static void registerAttributes(EntityAttributeCreationEvent event) {
            // Originals
            event.put(COSMETIC_PET_WOLF.get(),    Wolf.createAttributes().build());
            event.put(COSMETIC_PET_CAT.get(),     Cat.createAttributes().build());
            event.put(COSMETIC_PET_CHICKEN.get(), Chicken.createAttributes().build());

            // Previously added
            event.put(COSMETIC_PET_FOX.get(),     Fox.createAttributes().build());
            event.put(COSMETIC_PET_AXOLOTL.get(), Axolotl.createAttributes().build());
            event.put(COSMETIC_PET_BEE.get(),     Bee.createAttributes().build());
            event.put(COSMETIC_PET_RABBIT.get(),  Rabbit.createAttributes().build());
            event.put(COSMETIC_PET_PIG.get(),     Pig.createAttributes().build());
            event.put(COSMETIC_PET_SHEEP.get(),   Sheep.createAttributes().build());
            event.put(COSMETIC_PET_PANDA.get(),   Panda.createAttributes().build());
            event.put(COSMETIC_PET_PARROT.get(),  Parrot.createAttributes().build());

            // Newer
            event.put(COSMETIC_PET_HORSE.get(),   AbstractHorse.createBaseHorseAttributes().build());
            event.put(COSMETIC_PET_LLAMA.get(),   Llama.createAttributes().build());
            event.put(COSMETIC_PET_FROG.get(),    Frog.createAttributes().build());
            event.put(COSMETIC_PET_MOOSHROOM.get(), Cow.createAttributes().build());
            event.put(COSMETIC_PET_DONKEY.get(),  AbstractHorse.createBaseHorseAttributes().build());
            event.put(COSMETIC_PET_MULE.get(),    AbstractHorse.createBaseHorseAttributes().build());
            event.put(COSMETIC_PET_CAMEL.get(), AbstractHorse.createBaseHorseAttributes().build());
            event.put(COSMETIC_PET_GOAT.get(),    Goat.createAttributes().build());
            event.put(COSMETIC_PET_OCELOT.get(),  Ocelot.createAttributes().build());
            event.put(COSMETIC_PET_COW.get(),     Cow.createAttributes().build());
            event.put(COSMETIC_PET_VILLAGER.get(), Villager.createAttributes().build());
            event.put(COSMETIC_PET_VEX.get(),     Vex.createAttributes().build());
            event.put(COSMETIC_PET_BLAZE.get(),   Blaze.createAttributes().build());
            event.put(COSMETIC_PET_SNOW_GOLEM.get(), SnowGolem.createAttributes().build());
            event.put(COSMETIC_PET_IRON_GOLEM.get(), IronGolem.createAttributes().build());
        }
    }
}
