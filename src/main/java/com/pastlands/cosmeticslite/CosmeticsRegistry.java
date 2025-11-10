package com.pastlands.cosmeticslite;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.pastlands.cosmeticslite.gadget.GadgetNet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.awt.Color;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongFunction;

/**
 * In-memory registry for cosmetics, indexed by id, type, and pack.
 * - Seeds hats, pets, particles, gadgets
 * - Auto-discovers capes from /textures/cape/
 * - Auto-discovers custom hat models from /models/hats/<category>/<name>.json
 * - Merges gadget presets from assets/cosmeticslite/gadgets_presets.json at startup and after replaceAll()
 *
 * Forge 47.4.0 (MC 1.20.1)
 */
public final class CosmeticsRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();

    private CosmeticsRegistry() {}

    public static final String TYPE_PARTICLES = "particles";
    public static final String TYPE_HATS      = "hats";
    public static final String TYPE_CAPES     = "capes";
    public static final String TYPE_PETS      = "pets";
    public static final String TYPE_GADGETS   = "gadgets";

    // Master indexes
    private static final Map<ResourceLocation, CosmeticDef> BY_ID   = new LinkedHashMap<>();
    private static final Map<String, List<CosmeticDef>>     BY_TYPE = new LinkedHashMap<>();
    private static final Map<String, List<CosmeticDef>>     BY_PACK = new LinkedHashMap<>();

    // One-time info spam guard for asset discovery when neither client nor server RM is available
    private static boolean assetDiscoveryWarned = false;

    // ------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------

    /** Replace full registry, optionally add dev seed, then merge gadget presets from assets. */
    public static synchronized void replaceAll(Collection<CosmeticDef> defs, boolean addDevSeed) {
        clearUnlocked();
        if (defs != null) {
            for (CosmeticDef d : defs) registerUnlocked(d);
        }
        if (addDevSeed) installDevSeedUnlocked();

        // Always merge gadget presets after seeds/defs so cooldowns/etc. are live.
        loadGadgetPresetsFromAssets();

        // Stable ordering for UI
        for (List<CosmeticDef> list : BY_TYPE.values()) list.sort(Comparator.comparing(cd -> cd.id().getPath()));
        LOGGER.info("[CosmeticsLite] Registry replaced: {} cosmetics (seed={}, presets merged)", BY_ID.size(), addDevSeed);

        // Reinitialize gadget FX actions so cinematic gadgets remain active after registry rebuild
        try {
            GadgetNet.GadgetActions.bootstrapDefaults();
            LOGGER.info("[CosmeticsLite] GadgetActions re-bootstrapped after registry replaceAll()");
        } catch (Throwable t) {
            LOGGER.error("[CosmeticsLite] Failed re-bootstrapping GadgetActions", t);
        }
    }

    public static synchronized List<CosmeticDef> getByType(String type) {
        ensureDevSeedIfEmpty();
        List<CosmeticDef> list = BY_TYPE.get(type);
        return (list == null) ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(list));
    }

    public static synchronized List<CosmeticDef> getByPack(String packId) {
        ensureDevSeedIfEmpty();
        List<CosmeticDef> list = BY_PACK.get(packId);
        return (list == null) ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(list));
    }

    public static synchronized List<CosmeticDef> getByTypeAndPack(String type, String packId) {
        ensureDevSeedIfEmpty();
        List<CosmeticDef> typeList = BY_TYPE.get(type);
        if (typeList == null) return Collections.emptyList();
        List<CosmeticDef> result = new ArrayList<>();
        for (CosmeticDef def : typeList) if (Objects.equals(def.pack(), packId)) result.add(def);
        return Collections.unmodifiableList(result);
    }

    public static synchronized CosmeticDef get(ResourceLocation id) {
        ensureDevSeedIfEmpty();
        return (id == null) ? null : BY_ID.get(id);
    }

    public static synchronized Collection<CosmeticDef> all() {
        ensureDevSeedIfEmpty();
        return Collections.unmodifiableCollection(new ArrayList<>(BY_ID.values()));
    }

    public static synchronized Set<String> getKnownTypes() {
        ensureDevSeedIfEmpty();
        return Collections.unmodifiableSet(new LinkedHashSet<>(BY_TYPE.keySet()));
    }

    public static synchronized Set<String> getKnownPacks() {
        ensureDevSeedIfEmpty();
        return Collections.unmodifiableSet(new LinkedHashSet<>(BY_PACK.keySet()));
    }

    /** Merge/override properties for an existing def by id (used by gadget presets). */
    public static synchronized void mergeProps(ResourceLocation id, Map<String, String> overrides) {
        ensureDevSeedIfEmpty();
        if (id == null || overrides == null || overrides.isEmpty()) return;
        CosmeticDef def = BY_ID.get(id);
        if (def == null) return;

        Map<String, String> merged = new HashMap<>();
        if (def.properties() != null) merged.putAll(def.properties());
        merged.putAll(overrides);

        final Map<String, String> mergedFrozen = Map.copyOf(merged);
        final CosmeticDef updated =
                (def.pack() == null)
                        ? new CosmeticDef(def.id(), def.name(), def.description(), def.type(), def.icon(), mergedFrozen)
                        : new CosmeticDef(def.id(), def.name(), def.description(), def.type(), def.icon(), mergedFrozen, def.pack());

        replaceUnlocked(updated);
    }

    // ------------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------------

    private static void clearUnlocked() {
        BY_ID.clear();
        BY_TYPE.clear();
        BY_PACK.clear();
    }

    private static void registerUnlocked(CosmeticDef def) {
        if (def == null || BY_ID.containsKey(def.id())) return;
        BY_ID.put(def.id(), def);
        BY_TYPE.computeIfAbsent(def.type(), k -> new ArrayList<>()).add(def);
        BY_PACK.computeIfAbsent(def.pack(), k -> new ArrayList<>()).add(def);
    }

    /** Replace an existing def in all indexes while preserving order. */
    private static void replaceUnlocked(CosmeticDef updated) {
        CosmeticDef prev = BY_ID.put(updated.id(), updated);
        if (prev != null) {
            List<CosmeticDef> listT = BY_TYPE.get(prev.type());
            if (listT != null) {
                for (int i = 0; i < listT.size(); i++) {
                    if (listT.get(i).id().equals(prev.id())) { listT.set(i, updated); break; }
                }
            }
            List<CosmeticDef> listP = BY_PACK.get(prev.pack());
            if (listP != null) {
                for (int i = 0; i < listP.size(); i++) {
                    if (listP.get(i).id().equals(prev.id())) { listP.set(i, updated); break; }
                }
            }
        } else {
            registerUnlocked(updated);
        }
    }

    private static void ensureDevSeedIfEmpty() {
        if (BY_ID.isEmpty()) installDevSeedUnlocked();
    }

    private static void installDevSeedUnlocked() {
        seedDevParticlesUnlocked();
        seedDevHatsUnlocked();
        discoverCapesFromAssets();
        discoverCustomHatsFromAssets();
        seedDevPetsUnlocked();
        seedDevGadgetsUnlocked();

        // Merge gadget presets after base entries exist.
        loadGadgetPresetsFromAssets();

        // Stable order
        List<CosmeticDef> capes = BY_TYPE.get(TYPE_CAPES);
        if (capes != null) capes.sort(Comparator.comparing(cd -> cd.id().getPath()));
        List<CosmeticDef> pets = BY_TYPE.get(TYPE_PETS);
        if (pets != null) pets.sort(Comparator.comparing(cd -> cd.id().getPath()));
        List<CosmeticDef> gadgets = BY_TYPE.get(TYPE_GADGETS);
        if (gadgets != null) gadgets.sort(Comparator.comparing(cd -> cd.id().getPath()));

        // Ensure GadgetActions map stays populated for all cinematic gadget effects
        try {
            var field = com.pastlands.cosmeticslite.gadget.GadgetNet.class.getDeclaredField("BOOTSTRAPPED");
            field.setAccessible(true);
            field.setBoolean(null, false); // allow rebootstrap
            com.pastlands.cosmeticslite.gadget.GadgetNet.GadgetActions.bootstrapDefaults();
            field.setBoolean(null, true);
            LOGGER.info("[CosmeticsLite] GadgetActions forcibly re-bootstrapped after dev seed install");
        } catch (Throwable t) {
            LOGGER.error("[CosmeticsLite] Failed to force re-bootstrap GadgetActions after dev seed", t);
        }
    }

    // ------------------------------------------------------------------------
    // Dev seed: Hats (vanilla block/heads etc.)
    // ------------------------------------------------------------------------

    private static void seedDevHatsUnlocked() {
        registerUnlocked(new CosmeticDef(
            rl("cosmeticslite", "tophat"),
            "Tophat",
            "A fancy gentleman's top hat",
            TYPE_HATS,
            rl("minecraft", "black_wool"),
            Map.of(),
            "base"
        ));

        // Skulls
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_dragon"), "Dragon Head", "Wear the mighty dragon’s skull.", TYPE_HATS, rl("minecraft","dragon_head"), Map.of("skull","true"), "base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_skeleton"), "Skeleton Skull", "Spooky skeleton skull.", TYPE_HATS, rl("minecraft","skeleton_skull"), Map.of("skull","true"), "base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_wither_skeleton"), "Wither Skeleton Skull", "Dark and dangerous.", TYPE_HATS, rl("minecraft","wither_skeleton_skull"), Map.of("skull","true"), "base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_creeper"), "Creeper Head", "Boom, but stylish.", TYPE_HATS, rl("minecraft","creeper_head"), Map.of("skull","true"), "base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_zombie"), "Zombie Head", "Brains… as a hat.", TYPE_HATS, rl("minecraft","zombie_head"), Map.of("skull","true"), "base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_piglin"), "Piglin Head", "A snouty nether trophy.", TYPE_HATS, rl("minecraft","piglin_head"), Map.of("skull","true"), "base"));

        // Block hats
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_pumpkin"),"Pumpkin (3D)","Carved pumpkin hat.",TYPE_HATS,rl("minecraft","carved_pumpkin"),Map.of(),"base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_anvil"),"Anvil (3D)","Heavy-duty headgear.",TYPE_HATS,rl("minecraft","anvil"),Map.of(),"base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_honey_block"),"Honey Block","Sticky but stylish.",TYPE_HATS,rl("minecraft","honey_block"),Map.of(),"base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_slime_block"),"Slime Block","Boingy headwear.",TYPE_HATS,rl("minecraft","slime_block"),Map.of(),"base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_tnt"),"TNT","Explosive fashion.",TYPE_HATS,rl("minecraft","tnt"),Map.of(),"base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_redstone_lamp"),"Redstone Lamp","Lights up the night.",TYPE_HATS,rl("minecraft","redstone_lamp"),Map.of(),"base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_glowstone"),"Glowstone","Nether chic lighting.",TYPE_HATS,rl("minecraft","glowstone"),Map.of(),"base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_emerald_block"),"Emerald Block","Show off your wealth.",TYPE_HATS,rl("minecraft","emerald_block"),Map.of(),"base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_lapis_block"),"Lapis Block","A scholarly crown.",TYPE_HATS,rl("minecraft","lapis_block"),Map.of(),"base"));

        // Odd hats
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_cake"),"Cake Hat","Delicious and stylish.",TYPE_HATS,rl("minecraft","cake"),Map.of(),"base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_furnace"),"Furnace Hat","Smelt while you strut.",TYPE_HATS,rl("minecraft","furnace"),Map.of(),"base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_chest"),"Chest Hat","Portable storage on your head.",TYPE_HATS,rl("minecraft","chest"),Map.of(),"base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_jukebox"),"Jukebox Hat","Play that funky music.",TYPE_HATS,rl("minecraft","jukebox"),Map.of(),"base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_beacon"),"Beacon Hat","Beam me up.",TYPE_HATS,rl("minecraft","beacon"),Map.of(),"base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_brewing_stand"),"Brewing Stand","Potion master style.",TYPE_HATS,rl("minecraft","brewing_stand"),Map.of(),"base"));

        // Novelty hats
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_flower_pot"),"Flower Pot","Grow plants on your noggin.",TYPE_HATS,rl("minecraft","flower_pot"),Map.of(),"base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_dragon_egg"),"Dragon Egg","Ultimate flex headwear.",TYPE_HATS,rl("minecraft","dragon_egg"),Map.of(),"base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_spyglass"),"Spyglass Hat","See far… from your forehead.",TYPE_HATS,rl("minecraft","spyglass"),Map.of(),"base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_end_crystal"),"End Crystal","Shiny floating orb crown.",TYPE_HATS,rl("minecraft","end_crystal"),Map.of(),"base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_grindstone"),"Grindstone","Sharpen your wits.",TYPE_HATS,rl("minecraft","grindstone"),Map.of(),"base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_ice"),"Ice Block","Frosty fashion.",TYPE_HATS,rl("minecraft","ice"),Map.of(),"base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_cactus"),"Cactus","Spiky desert hat.",TYPE_HATS,rl("minecraft","cactus"),Map.of(),"base"));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","hat_nether_gold_ore"),"Nether Gold Ore","Glittery headwear.",TYPE_HATS,rl("minecraft","nether_gold_ore"),Map.of(),"base"));
    }

    // ------------------------------------------------------------------------
    // Player-aware access
    // ------------------------------------------------------------------------

    public static synchronized List<CosmeticDef> getUnlockedByType(ServerPlayer player, String type) {
        ensureDevSeedIfEmpty();
        List<CosmeticDef> all = BY_TYPE.get(type);
        if (all == null) return Collections.emptyList();
        List<CosmeticDef> result = new ArrayList<>();
        for (CosmeticDef def : all) if (UnlockManager.isUnlocked(player, def.pack())) result.add(def);
        return Collections.unmodifiableList(result);
    }

    public static synchronized List<CosmeticDef> getAllUnlocked(ServerPlayer player) {
        ensureDevSeedIfEmpty();
        List<CosmeticDef> result = new ArrayList<>();
        for (CosmeticDef def : BY_ID.values()) if (UnlockManager.isUnlocked(player, def.pack())) result.add(def);
        return Collections.unmodifiableList(result);
    }

    public static synchronized List<CosmeticDef> getUnlockedByTypeAndPack(ServerPlayer player, String type, String packId) {
        ensureDevSeedIfEmpty();
        List<CosmeticDef> all = BY_TYPE.get(type);
        if (all == null) return Collections.emptyList();
        List<CosmeticDef> result = new ArrayList<>();
        for (CosmeticDef def : all) {
            if (Objects.equals(def.pack(), packId) && UnlockManager.isUnlocked(player, def.pack())) result.add(def);
        }
        return Collections.unmodifiableList(result);
    }

    // ------------------------------------------------------------------------
    // Auto-discover: Custom Hats (assets/<modid>/models/hats/<category>/<name>.json)
    // ------------------------------------------------------------------------
    private static void discoverCustomHatsFromAssets() {
        try {
            ResourceManager rm = resolveResourceManager();
            if (rm == null) return;

            Map<String, net.minecraft.world.item.Item> CATEGORY_ICONS = Map.of(
                "food",   Items.COOKIE,
                "magic",  Items.ENDER_PEARL,
                "animal", Items.COW_SPAWN_EGG,
                "fantasy", Items.ENCHANTED_BOOK
            );

            Map<ResourceLocation, Resource> found =
                rm.listResources(
                    "models/hats",
                    rl -> rl.getPath().toLowerCase(Locale.ROOT).endsWith(".json")
                );

            LOGGER.info("[cosmeticslite] Custom hat scan: {} file(s) under models/hats", found.size());

            for (ResourceLocation fileLoc : found.keySet()) {
                if (!CosmeticsLite.MODID.equals(fileLoc.getNamespace())) continue;

                String path = fileLoc.getPath();
                int slash = path.lastIndexOf('/');
                String base = (slash >= 0) ? path.substring(slash + 1) : path;
                if (base.endsWith(".json")) base = base.substring(0, base.length() - 5);
                if (base.isEmpty()) continue;

                String category = "misc";
                int hatsIdx = path.indexOf("hats/");
                if (hatsIdx >= 0) {
                    String rest = path.substring(hatsIdx + 5);
                    int slash2 = rest.indexOf('/');
                    if (slash2 > 0) category = rest.substring(0, slash2);
                }

                net.minecraft.world.item.Item iconBase = CATEGORY_ICONS.getOrDefault(category, Items.COOKIE);
                ResourceLocation iconItem = BuiltInRegistries.ITEM.getKey(iconBase);

                String hatId = "hat_" + base.toLowerCase(Locale.ROOT);
                ResourceLocation id = rl(CosmeticsLite.MODID, hatId);
                String title = toTitle(base);

                ResourceLocation modelLoc = rl(CosmeticsLite.MODID, "hats/" + category + "/" + base);
                Map<String, String> props = Map.of("model", modelLoc.toString());

                String displayCategory = toTitle(category);
                String tooltip = displayCategory + " Hat: " + title;

                registerUnlocked(new CosmeticDef(id, title, tooltip, TYPE_HATS, iconItem, props, category));
            }
        } catch (Throwable t) {
            LOGGER.error("[cosmeticslite] Failed discovering custom hats", t);
        }
    }

    // ------------------------------------------------------------------------
    // Dev seed: Pets
    // ------------------------------------------------------------------------
    private static void seedDevPetsUnlocked() {
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_wolf"),     "Wolf Companion",   "A loyal wolf companion.", TYPE_PETS, rl("minecraft","bone"),              Map.of("entity","minecraft:wolf","scale","0.6")));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_cat"),      "Cat Companion",    "A friendly cat companion.", TYPE_PETS, rl("minecraft","cod"),              Map.of("entity","minecraft:cat","scale","0.7")));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_chicken"),  "Chicken Friend",   "A small chicken friend.", TYPE_PETS, rl("minecraft","feather"),            Map.of("entity","minecraft:chicken","scale","0.5")));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_fox"),      "Fox Companion",    "A clever fox.", TYPE_PETS, rl("minecraft","sweet_berries"),                Map.of("entity","minecraft:fox","scale","0.6")));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_axolotl"),  "Axolotl Friend",   "An adorable axolotl.", TYPE_PETS, rl("minecraft","tropical_fish"),         Map.of("entity","minecraft:axolotl","scale","0.7")));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_bee"),      "Buzzing Bee",      "A friendly bee.", TYPE_PETS, rl("minecraft","honeycomb"),                  Map.of("entity","minecraft:bee","scale","0.8")));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_rabbit"),   "Hopping Rabbit",   "A colorful rabbit.", TYPE_PETS, rl("minecraft","carrot"),                  Map.of("entity","minecraft:rabbit","scale","0.6")));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_pig"),      "Pig Pal",          "The classic pig.", TYPE_PETS, rl("minecraft","porkchop"),                   Map.of("entity","minecraft:pig","scale","0.7")));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_sheep"),    "Fluffy Sheep",     "A woolly sheep.", TYPE_PETS, rl("minecraft","white_wool"),                 Map.of("entity","minecraft:sheep","scale","0.6")));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_panda"),    "Lazy Panda",       "A cuddly panda.", TYPE_PETS, rl("minecraft","bamboo"),                      Map.of("entity","minecraft:panda","scale","0.5")));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_parrot"),   "Colorful Parrot",  "A vibrant parrot.", TYPE_PETS, rl("minecraft","parrot_spawn_egg"),         Map.of("entity","minecraft:parrot","scale","0.7")));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_horse"),    "Horse Companion",  "A trusty steed (base coat selectable).", TYPE_PETS, rl("minecraft","saddle"), Map.of("entity","minecraft:horse","scale","0.6")));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_llama"),    "Llama Buddy",      "Stylish spitter (no trader variant).",   TYPE_PETS, rl("minecraft","hay_block"), Map.of("entity","minecraft:llama","scale","0.6")));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_frog"),     "Frog Friend",      "Ribbit! Pick your climate.",             TYPE_PETS, rl("minecraft","slime_ball"), Map.of("entity","minecraft:frog","scale","0.8")));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_mooshroom"),"Mooshroom Pal",    "Red or Brown shroom-cow.",               TYPE_PETS, rl("minecraft","red_mushroom"), Map.of("entity","minecraft:mooshroom","scale","0.6")));

        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_donkey"),   "Donkey Companion", "A stubborn but loyal donkey.", TYPE_PETS, rl("minecraft","hay_block"),     Map.of("entity","minecraft:donkey","scale","0.8")));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_mule"),     "Mule Companion",   "Half horse, half donkey, all workhorse.", TYPE_PETS, rl("minecraft","chest"),   Map.of("entity","minecraft:mule","scale","0.85")));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_camel"),    "Camel Pal",        "Two-seater desert walker.", TYPE_PETS, rl("minecraft","sand"),               Map.of("entity","minecraft:camel","scale","1.0")));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_goat"),     "Goat Buddy",       "Bouncy horned troublemaker.", TYPE_PETS, rl("minecraft","goat_horn"),        Map.of("entity","minecraft:goat","scale","0.9")));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_ocelot"),   "Ocelot Friend",    "Jungle stalker with style.", TYPE_PETS, rl("minecraft","cocoa_beans"),      Map.of("entity","minecraft:ocelot","scale","0.7")));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_cow"),      "Cow Companion",    "Classic moo friend.", TYPE_PETS, rl("minecraft","beef"),                      Map.of("entity","minecraft:cow","scale","0.9")));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_villager"), "Villager Pal",     "Hrrrm! A wandering companion.", TYPE_PETS, rl("minecraft","emerald"),        Map.of("entity","minecraft:villager","scale","0.9")));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_vex"),      "Vex Minion",       "A tiny but fierce spirit.", TYPE_PETS, rl("minecraft","iron_sword"),         Map.of("entity","minecraft:vex","scale","0.5")));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_blaze"),    "Blaze Companion",  "Burning buddy from the Nether.", TYPE_PETS, rl("minecraft","blaze_rod"),    Map.of("entity","minecraft:blaze","scale","0.8")));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_snow_golem"),"Snow Golem Friend","Frosty ranged buddy.", TYPE_PETS, rl("minecraft","snowball"),                Map.of("entity","minecraft:snow_golem","scale","0.9")));
        registerUnlocked(new CosmeticDef(rl("cosmeticslite","pet_iron_golem"),"Iron Golem Guardian","Towering protector companion.", TYPE_PETS, rl("minecraft","iron_block"),   Map.of("entity","minecraft:iron_golem","scale","1.0")));
    }

    // ------------------------------------------------------------------------
    // Dev seed: Particles
    // ------------------------------------------------------------------------
    private static void seedDevParticlesUnlocked() {
        String[] names = {
            "ambient_entity_effect","angry_villager","ash","bubble","bubble_column_up","bubble_pop",
            "campfire_cosy_smoke","campfire_signal_smoke","cherry_leaves","cloud","composter","crit",
            "current_down","damage_indicator","dolphin","dragon_breath","dripping_dripstone_lava",
            "dripping_dripstone_water","dripping_honey","dripping_lava","dripping_obsidian_tear",
            "dripping_water","elder_guardian","electric_spark","enchant","enchanted_hit","end_rod",
            "entity_effect","explosion","explosion_emitter","falling_dripstone_lava","falling_dripstone_water",
            "falling_honey","flame","flash","glow","happy_villager","heart","large_smoke","lava","mycelium",
            "nautilus","note","poof","portal","rain","sculk_charge","sculk_charge_pop","sculk_soul","scrape",
            "shriek","smoke","sneeze","soul","soul_fire_flame","spit","spore_blossom_air","spore_blossom_ambient",
            "squid_ink","sweep_attack","splash","totem_of_undying","underwater","white_ash","witch"
        };
        for (String name : names) registerUnlocked(makeParticleDef(name));

        List<CosmeticDef> particles = BY_TYPE.get(TYPE_PARTICLES);
        if (particles != null) particles.sort(Comparator.comparing(cd -> cd.id().getPath()));
    }

    private static CosmeticDef makeParticleDef(String effectName) {
        String path = "particle/" + effectName;
        ResourceLocation id = rl("cosmeticslite", path);
        String title = toTitle(effectName);
        ResourceLocation iconItem = chooseIconForEffect(effectName);
        Map<String, String> props = Map.of("effect", rl("minecraft", effectName).toString());
        return new CosmeticDef(id, title, "Particle: " + title, TYPE_PARTICLES, iconItem, props);
    }

    private static ResourceLocation chooseIconForEffect(String n) {
        n = n.toLowerCase(Locale.ROOT);
        if (n.equals("heart")) return BuiltInRegistries.ITEM.getKey(Items.PINK_DYE);
        if (n.equals("happy_villager")) return BuiltInRegistries.ITEM.getKey(Items.EMERALD);
        if (n.equals("angry_villager")) return BuiltInRegistries.ITEM.getKey(Items.RED_DYE);
        if (n.equals("flame")) return BuiltInRegistries.ITEM.getKey(Items.BLAZE_POWDER);
        if (n.equals("soul_fire_flame") || n.contains("soul")) return BuiltInRegistries.ITEM.getKey(Items.SOUL_TORCH);
        if (n.equals("enchant")) return BuiltInRegistries.ITEM.getKey(Items.ENCHANTED_BOOK);
        if (n.equals("portal")) return BuiltInRegistries.ITEM.getKey(Items.ENDER_PEARL);
        if (n.equals("end_rod")) return BuiltInRegistries.ITEM.getKey(Items.END_ROD);
        if (n.equals("note")) return BuiltInRegistries.ITEM.getKey(Items.NOTE_BLOCK);
        if (n.equals("crit") || n.equals("enchanted_hit")) return BuiltInRegistries.ITEM.getKey(Items.DIAMOND_SWORD);
        if (n.equals("sweep_attack")) return BuiltInRegistries.ITEM.getKey(Items.IRON_SWORD);
        if (n.equals("explosion") || n.equals("explosion_emitter")) return BuiltInRegistries.ITEM.getKey(Items.TNT);
        if (n.equals("totem_of_undying")) return BuiltInRegistries.ITEM.getKey(Items.TOTEM_OF_UNDYING);
        if (n.equals("dragon_breath")) return BuiltInRegistries.ITEM.getKey(Items.DRAGON_BREATH);
        if (n.equals("elder_guardian")) return BuiltInRegistries.ITEM.getKey(Items.PRISMARINE_SHARD);
        if (n.equals("witch")) return BuiltInRegistries.ITEM.getKey(Items.SPIDER_EYE);
        if (n.equals("damage_indicator")) return BuiltInRegistries.ITEM.getKey(Items.BONE);
        if (n.equals("electric_spark")) return BuiltInRegistries.ITEM.getKey(Items.LIGHTNING_ROD);
        if (n.equals("glow")) return BuiltInRegistries.ITEM.getKey(Items.GLOW_INK_SAC);
        if (n.equals("sneeze")) return BuiltInRegistries.ITEM.getKey(Items.SLIME_BALL);
        if (n.equals("spit")) return BuiltInRegistries.ITEM.getKey(Items.SNOWBALL);
        if (n.equals("squid_ink")) return BuiltInRegistries.ITEM.getKey(Items.INK_SAC);
        if (n.equals("composter")) return BuiltInRegistries.ITEM.getKey(Items.COMPOSTER);
        if (n.equals("mycelium")) return BuiltInRegistries.ITEM.getKey(Items.MYCELIUM);
        if (n.equals("nautilus")) return BuiltInRegistries.ITEM.getKey(Items.NAUTILUS_SHELL);
        if (n.equals("cherry_leaves")) return BuiltInRegistries.ITEM.getKey(Items.CHERRY_LEAVES);
        if (n.contains("spore_blossom")) return BuiltInRegistries.ITEM.getKey(Items.SPORE_BLOSSOM);
        if (n.equals("scrape")) return BuiltInRegistries.ITEM.getKey(Items.IRON_INGOT);
        if (n.equals("poof")) return BuiltInRegistries.ITEM.getKey(Items.WHITE_WOOL);
        if (n.equals("flash")) return BuiltInRegistries.ITEM.getKey(Items.GLOWSTONE_DUST);
        if (n.equals("lava")) return BuiltInRegistries.ITEM.getKey(Items.LAVA_BUCKET);
        if (n.contains("dripping_lava") || (n.contains("falling") && n.contains("lava"))) return BuiltInRegistries.ITEM.getKey(Items.MAGMA_BLOCK);
        if (n.equals("bubble")) return BuiltInRegistries.ITEM.getKey(Items.BUBBLE_CORAL);
        if (n.equals("bubble_pop")) return BuiltInRegistries.ITEM.getKey(Items.TUBE_CORAL);
        if (n.equals("bubble_column_up") || n.equals("current_down")) return BuiltInRegistries.ITEM.getKey(Items.MAGMA_BLOCK);
        if (n.equals("underwater")) return BuiltInRegistries.ITEM.getKey(Items.KELP);
        if (n.equals("dolphin")) return BuiltInRegistries.ITEM.getKey(Items.COD);
        if (n.contains("dripping_water") || (n.contains("falling") && n.contains("water"))) return BuiltInRegistries.ITEM.getKey(Items.WATER_BUCKET);
        if (n.equals("rain")) return BuiltInRegistries.ITEM.getKey(Items.BLUE_ICE);
        if (n.equals("splash")) return BuiltInRegistries.ITEM.getKey(Items.PRISMARINE_CRYSTALS);
        if (n.contains("honey")) return BuiltInRegistries.ITEM.getKey(Items.HONEY_BOTTLE);
        if (n.contains("obsidian")) return BuiltInRegistries.ITEM.getKey(Items.CRYING_OBSIDIAN);
        if (n.contains("sculk_charge")) return BuiltInRegistries.ITEM.getKey(Items.SCULK_SENSOR);
        if (n.contains("sculk_soul")) return BuiltInRegistries.ITEM.getKey(Items.SCULK_SHRIEKER);
        if (n.contains("shriek")) return BuiltInRegistries.ITEM.getKey(Items.ECHO_SHARD);
        if (n.equals("smoke")) return BuiltInRegistries.ITEM.getKey(Items.CHARCOAL);
        if (n.equals("large_smoke")) return BuiltInRegistries.ITEM.getKey(Items.COAL_BLOCK);
        if (n.contains("campfire")) return BuiltInRegistries.ITEM.getKey(Items.CAMPFIRE);
        if (n.equals("ash") || n.equals("white_ash")) return BuiltInRegistries.ITEM.getKey(Items.BONE_MEAL);
        if (n.equals("cloud")) return BuiltInRegistries.ITEM.getKey(Items.WHITE_CONCRETE_POWDER);
        if (n.equals("ambient_entity_effect") || n.equals("entity_effect")) return BuiltInRegistries.ITEM.getKey(Items.POTION);
        if (n.contains("dripstone")) return BuiltInRegistries.ITEM.getKey(Items.POINTED_DRIPSTONE);

        if (n.hashCode() % 3 == 0) return BuiltInRegistries.ITEM.getKey(Items.AMETHYST_SHARD);
        if (n.hashCode() % 3 == 1) return BuiltInRegistries.ITEM.getKey(Items.PRISMARINE_CRYSTALS);
        return BuiltInRegistries.ITEM.getKey(Items.GLOWSTONE_DUST);
    }

    // ------------------------------------------------------------------------
    // Cape discovery
    // ------------------------------------------------------------------------
    private static void discoverCapesFromAssets() {
        try {
            ResourceManager rm = resolveResourceManager();
            if (rm == null) return;

            Map<ResourceLocation, Resource> found =
                rm.listResources("textures/cape", rl -> rl.getPath().toLowerCase(Locale.ROOT).endsWith(".png"));

            for (ResourceLocation rl : found.keySet()) {
                if (!CosmeticsLite.MODID.equals(rl.getNamespace())) continue;

                String path = rl.getPath();
                int slash = path.lastIndexOf('/');
                String base = (slash >= 0) ? path.substring(slash + 1) : path;
                if (base.endsWith(".png")) base = base.substring(0, base.length() - 4);
                if (base.isEmpty()) continue;
                if ("debug_cape".equalsIgnoreCase(base)) continue;

                ResourceLocation id = rl("cosmeticslite", base);
                String title = toTitle(base);

                Color tint = pickBrightColor(base);

                Map<String, String> props = new HashMap<>();
                props.put("texture", rl.toString());
                props.put("tint", String.format("#%06X", tint.getRGB() & 0xFFFFFF));

                ResourceLocation icon = BuiltInRegistries.ITEM.getKey(Items.WHITE_BANNER);

                registerUnlocked(new CosmeticDef(id, title, "Cape: " + title, TYPE_CAPES, icon, props));
            }
        } catch (Throwable t) {
            LOGGER.error("[cosmeticslite] Failed discovering capes", t);
        }
    }

    private static Color pickBrightColor(String seed) {
        Random r = new Random(seed.hashCode());
        float h = r.nextFloat();
        float s = 0.9f;
        float b = 1.0f;
        return Color.getHSBColor(h, s, b);
    }

    // ------------------------------------------------------------------------
    // Dev seed: Gadgets  (duration-driven UX: cooldown ~= duration)
    // ------------------------------------------------------------------------
    public static void seedDevGadgetsUnlocked() {
        LOGGER.info("[CosmeticsLite] Seeding dev gadgets unlocked (duration-aligned cooldowns)…");

        // Helper: ms -> string
        LongFunction<String> ms = v -> Long.toString(Math.max(0L, v));

        // Phase 1 — core gadgets (explicit duration_ms + cooldown_ms so UI returns fast)
        registerUnlocked(new CosmeticDef(
            rl("cosmeticslite","confetti_popper"),
            "Confetti Popper","Burst a cone of confetti with a satisfying pop.",
            TYPE_GADGETS,
            BuiltInRegistries.ITEM.getKey(Items.FIREWORK_STAR),
            Map.of(
                "preset", "cone_burst",
                "sound",  rl("cosmeticslite","confetti_pop").toString(),
                "cone_deg","40",
                "count",  "60",
                // ~20 ticks visual → 1000 ms duration; align cooldown to duration
                "duration_ms",  ms.apply(1000L),
                "cooldown_ms",  ms.apply(1000L)
            ),
            "base"
        ));

        registerUnlocked(new CosmeticDef(
            rl("cosmeticslite","bubble_blower"),
            "Bubble Blower","Blow a shimmering bubble that pops after a short drift.",
            TYPE_GADGETS,
            BuiltInRegistries.ITEM.getKey(Items.GLASS_BOTTLE),
            Map.of(
                "preset", "projectile_bubble",
                "sound",  rl("cosmeticslite","bubble_pop").toString(),
                "speed",  "0.15",
                // ~40 ticks → 2000 ms; align cooldown to duration
                "duration_ms",  ms.apply(2000L),
                "cooldown_ms",  ms.apply(2000L)
            ),
            "base"
        ));

        registerUnlocked(new CosmeticDef(
            rl("cosmeticslite","gear_spark_emitter"),
            "Gear Spark Emitter","Arc of sizzling steampunk cogs and sparks.",
            TYPE_GADGETS,
            BuiltInRegistries.ITEM.getKey(Items.REDSTONE),
            Map.of(
                "preset", "arc",
                "sound",  rl("cosmeticslite","gear_spark").toString(),
                "arc_deg","60",
                "count",  "40",
                // ~14 ticks → 700 ms; align cooldown to duration
                "duration_ms",  ms.apply(700L),
                "cooldown_ms",  ms.apply(700L)
            ),
            "pastlands"
        ));

        // Phase 2 — extended set (give each a sane short duration & matching cooldown)
        registerUnlocked(new CosmeticDef(
            rl("cosmeticslite","star_shower"), "Star Shower","A wide fan of glittering star sparks.",
            TYPE_GADGETS, BuiltInRegistries.ITEM.getKey(Items.FIREWORK_STAR),
            Map.of("duration_ms", ms.apply(1200L), "cooldown_ms", ms.apply(1200L)),
            "base"
        ));
        registerUnlocked(new CosmeticDef(
            rl("cosmeticslite","sparkle_ring"), "Sparkle Ring","A shimmering arc that hums with light.",
            TYPE_GADGETS, BuiltInRegistries.ITEM.getKey(Items.AMETHYST_SHARD),
            Map.of("duration_ms", ms.apply(900L), "cooldown_ms", ms.apply(900L)),
            "base"
        ));
        registerUnlocked(new CosmeticDef(
            rl("cosmeticslite","bubble_stream"), "Bubble Stream","A steady stream of buoyant bubbles.",
            TYPE_GADGETS, BuiltInRegistries.ITEM.getKey(Items.HEART_OF_THE_SEA),
            Map.of("duration_ms", ms.apply(1800L), "cooldown_ms", ms.apply(1800L)),
            "base"
        ));
        registerUnlocked(new CosmeticDef(
            rl("cosmeticslite","confetti_fountain"), "Confetti Fountain","A dense fountain of festive confetti.",
            TYPE_GADGETS, BuiltInRegistries.ITEM.getKey(Items.FIREWORK_ROCKET),
            Map.of("duration_ms", ms.apply(1500L), "cooldown_ms", ms.apply(1500L)),
            "base"
        ));
        registerUnlocked(new CosmeticDef(
            rl("cosmeticslite","spark_fan"), "Spark Fan","Fast sweeping sparks in a wide fan.",
            TYPE_GADGETS, BuiltInRegistries.ITEM.getKey(Items.FLINT_AND_STEEL),
            Map.of("duration_ms", ms.apply(800L), "cooldown_ms", ms.apply(800L)),
            "base"
        ));
        registerUnlocked(new CosmeticDef(
            rl("cosmeticslite","glitter_pop"), "Glitter Pop","A quick burst of glitter and twinkle.",
            TYPE_GADGETS, BuiltInRegistries.ITEM.getKey(Items.GLOWSTONE_DUST),
            Map.of("duration_ms", ms.apply(700L), "cooldown_ms", ms.apply(700L)),
            "base"
        ));
        registerUnlocked(new CosmeticDef(
            rl("cosmeticslite","shimmer_wave"), "Shimmer Wave","Graceful wave of shimmering sparks.",
            TYPE_GADGETS, BuiltInRegistries.ITEM.getKey(Items.PRISMARINE_CRYSTALS),
            Map.of("duration_ms", ms.apply(1400L), "cooldown_ms", ms.apply(1400L)),
            "base"
        ));
        registerUnlocked(new CosmeticDef(
            rl("cosmeticslite","bubble_blast"), "Bubble Blast","Chunky bubbles that pop into a trail.",
            TYPE_GADGETS, BuiltInRegistries.ITEM.getKey(Items.TURTLE_HELMET),
            Map.of("duration_ms", ms.apply(1300L), "cooldown_ms", ms.apply(1300L)),
            "base"
        ));
        registerUnlocked(new CosmeticDef(
            rl("cosmeticslite","starlight_burst"), "Starlight Burst","Focused cone of starlit confetti.",
            TYPE_GADGETS, BuiltInRegistries.ITEM.getKey(Items.NETHER_STAR),
            Map.of("duration_ms", ms.apply(1100L), "cooldown_ms", ms.apply(1100L)),
            "base"
        ));
        registerUnlocked(new CosmeticDef(
            rl("cosmeticslite","glitter_veil"), "Glitter Veil","Long, gentle curtain of glitter.",
            TYPE_GADGETS, BuiltInRegistries.ITEM.getKey(Items.ALLAY_SPAWN_EGG),
            Map.of("duration_ms", ms.apply(2000L), "cooldown_ms", ms.apply(2000L)),
            "base"
        ));
        registerUnlocked(new CosmeticDef(
            rl("cosmeticslite","supernova_burst"), "Supernova Burst","Massive starburst of radiant energy.",
            TYPE_GADGETS, BuiltInRegistries.ITEM.getKey(Items.NETHER_STAR),
            Map.of("duration_ms", ms.apply(1600L), "cooldown_ms", ms.apply(1600L)),
            "base"
        ));
        registerUnlocked(new CosmeticDef(
            rl("cosmeticslite","expanding_ring"), "Expanding Ring","Circular wave expanding outward.",
            TYPE_GADGETS, BuiltInRegistries.ITEM.getKey(Items.AMETHYST_CLUSTER),
            Map.of("duration_ms", ms.apply(1200L), "cooldown_ms", ms.apply(1200L)),
            "base"
        ));
        registerUnlocked(new CosmeticDef(
            rl("cosmeticslite","helix_stream"), "Helix Stream","Spiraling stream of sparks along your aim.",
            TYPE_GADGETS, BuiltInRegistries.ITEM.getKey(Items.LIGHTNING_ROD),
            Map.of("duration_ms", ms.apply(1400L), "cooldown_ms", ms.apply(1400L)),
            "base"
        ));
        registerUnlocked(new CosmeticDef(
            rl("cosmeticslite","firefly_orbit"), "Firefly Orbit","Gentle fireflies circling before lift-off.",
            TYPE_GADGETS, BuiltInRegistries.ITEM.getKey(Items.SPORE_BLOSSOM),
            Map.of("duration_ms", ms.apply(1800L), "cooldown_ms", ms.apply(1800L)),
            "base"
        ));
        registerUnlocked(new CosmeticDef(
            rl("cosmeticslite","ground_ripple"), "Ground Ripple","Flat shockwave rippling from your feet.",
            TYPE_GADGETS, BuiltInRegistries.ITEM.getKey(Items.BASALT),
            Map.of("duration_ms", ms.apply(900L), "cooldown_ms", ms.apply(900L)),
            "base"
        ));
        registerUnlocked(new CosmeticDef(
            rl("cosmeticslite","sky_beacon"), "Sky Beacon","Vertical beam reaching into the sky.",
            TYPE_GADGETS, BuiltInRegistries.ITEM.getKey(Items.BEACON),
            Map.of("duration_ms", ms.apply(1500L), "cooldown_ms", ms.apply(1500L)),
            "base"
        ));
    }

    // ------------------------------------------------------------------------
    // Utility
    // ------------------------------------------------------------------------
    private static ResourceLocation rl(String ns, String path) {
        return ResourceLocation.fromNamespaceAndPath(ns, path);
    }

    private static String toTitle(String base) {
        String[] parts = base.replace('-', ' ').replace('_', ' ').trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1));
            if (i < parts.length - 1) sb.append(' ');
        }
        return sb.length() == 0 ? base : sb.toString();
    }

    // ------------------------------------------------------------------------
    // JSON presets loader (startup + after replaceAll)
    // ------------------------------------------------------------------------
    /** Loads assets/cosmeticslite/gadgets_presets.json and merges properties into matching gadget defs. */
    private static void loadGadgetPresetsFromAssets() {
        try {
            ResourceManager rm = resolveResourceManager();
            if (rm == null) return;

            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "gadgets_presets.json");
            Resource res = rm.getResource(loc).orElse(null);
            if (res == null) {
                LOGGER.info("[CosmeticsLite] No gadget presets found at {}", loc);
                return;
            }

            int applied = 0;
            try (InputStreamReader reader = new InputStreamReader(res.open(), StandardCharsets.UTF_8)) {
                JsonElement rootEl = JsonParser.parseReader(reader);
                if (rootEl == null || !rootEl.isJsonObject()) {
                    LOGGER.warn("[CosmeticsLite] Gadget presets file exists but is not a JSON object: {}", loc);
                    return;
                }
                JsonObject root = rootEl.getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                    ResourceLocation id = ResourceLocation.tryParse(e.getKey());
                    if (id == null) continue;
                    JsonElement val = e.getValue();
                    if (val == null || !val.isJsonObject()) continue;

                    Map<String, String> props = new HashMap<>();
                    JsonObject obj = val.getAsJsonObject();
                    for (Map.Entry<String, JsonElement> pe : obj.entrySet()) {
                        JsonElement pv = pe.getValue();
                        String asString = (pv == null || pv.isJsonNull())
                                ? ""
                                : (pv.isJsonPrimitive() ? pv.getAsJsonPrimitive().getAsString() : pv.toString());
                        props.put(pe.getKey(), asString);
                    }
                    mergeProps(id, props);
                    applied++;
                }
            }
            LOGGER.info("[CosmeticsLite] Applied {} gadget preset(s) from {}", applied, loc);
        } catch (Exception ex) {
            LOGGER.error("[CosmeticsLite] Failed loading gadget presets", ex);
        }
    }

    // ------------------------------------------------------------------------
    // ResourceManager resolution with strict server/client safety
    // ------------------------------------------------------------------------
    private static ResourceManager resolveResourceManager() {
        // Client path: use UNSAFE dist call so validator doesn’t inspect client referents.
        ResourceManager clientRM = DistExecutor.unsafeCallWhenOn(Dist.CLIENT,
                () -> ClientHooks::clientResourceManager);
        if (clientRM != null) return clientRM;

        // Server path: standard server lifecycle hook.
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            return server.getResourceManager();
        }

        // Neither side has an RM yet — skip quietly (log once).
        if (!assetDiscoveryWarned) {
            assetDiscoveryWarned = true;
            LOGGER.debug("[CosmeticsLite] No ResourceManager available (client or server). Asset discovery skipped.");
        }
        return null;
    }

    @OnlyIn(Dist.CLIENT)
    private static final class ClientHooks {
        static ResourceManager clientResourceManager() {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            return (mc != null) ? mc.getResourceManager() : null;
        }
    }
}
