// src/main/java/com/pastlands/cosmeticslite/client/screen/parts/PetVariantOptions.java
package com.pastlands.cosmeticslite.client.screen.parts;

import com.pastlands.cosmeticslite.client.screen.parts.VariantDropdownWidget.VariantOption;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * PetVariantOptions
 *
 * Centralized source for variant dropdown options per pet.
 * Forge 47.4.0 / MC 1.20.1 / Java 17
 *
 * Usage:
 *   List<VariantOption> options = PetVariantOptions.forPet(petDef.id());
 *
 * Supported:
 *   - Parrot:    red, blue, green, yellow, gray
 *   - Cat:       tabby, black, red, siamese, british_shorthair, calico, persian, ragdoll, white, jellie, all_black
 *   - Horse:     white, creamy, chestnut, brown, black, gray, dark_brown
 *   - Fox:       red, snow
 *   - Rabbit:    brown, white, black, gold, salt_and_pepper
 *   - Llama:     creamy, white, brown, gray
 *   - Frog:      temperate, warm, cold
 *   - Mooshroom: red, brown
 *   - Goat:      default, screaming
 *   - Others (Donkey, Mule, Camel, Ocelot, Cow, Villager, Vex, Blaze, Snow Golem, Iron Golem): no variants
 */
public final class PetVariantOptions {

    private PetVariantOptions() {}

    /**
     * Returns a list of displayable {@link VariantOption} for the given pet ID.
     * If the pet has no variants (or the ID is null), returns an empty list.
     */
    public static List<VariantOption> forPet(ResourceLocation petId) {
        if (petId == null) return Collections.emptyList();

        String path = petId.getPath().toLowerCase(Locale.ROOT);

        // Known pets with variants
        if (path.contains("parrot"))         return parrot();
        if (path.contains("cat"))            return cat();
        if (path.contains("horse"))          return horse();
        if (path.contains("fox"))            return fox();
        if (path.contains("rabbit"))         return rabbit();
        if (path.contains("llama") && !path.contains("trader")) return llama();
        if (path.contains("frog"))           return frog();
        if (path.contains("mooshroom"))      return mooshroom();
        if (path.contains("goat"))           return goat();

        // Pets without variants (still need to be recognized for UI)
        if (path.contains("donkey"))         return donkey();
        if (path.contains("mule"))           return mule();
        if (path.contains("camel"))          return camel();
        if (path.contains("ocelot"))         return ocelot();
        if (path.equals("cow") || path.contains("cow")) return cow();
        if (path.contains("villager"))       return villager();
        if (path.contains("vex"))            return vex();
        if (path.contains("blaze"))          return blaze();
        if (path.contains("snow_golem"))     return snowGolem();
        if (path.contains("iron_golem"))     return ironGolem();

        // Unknown pet → no variants
        return Collections.emptyList();
    }

    // --------------------------
    // Concrete variant builders
    // --------------------------

    /** Parrot color variants (vanilla set, 1.20.1). */
    private static List<VariantOption> parrot() {
        List<VariantOption> list = new ArrayList<>();
        list.add(new VariantOption("red",    Component.literal("Red")));
        list.add(new VariantOption("blue",   Component.literal("Blue")));
        list.add(new VariantOption("green",  Component.literal("Green")));
        list.add(new VariantOption("yellow", Component.literal("Yellow")));
        list.add(new VariantOption("gray",   Component.literal("Gray")));
        return list;
    }

    /** Cat skin variants (vanilla set, 1.20.1). Keys align to common names. */
    private static List<VariantOption> cat() {
        List<VariantOption> list = new ArrayList<>();
        list.add(new VariantOption("tabby",               Component.literal("Tabby")));
        list.add(new VariantOption("black",               Component.literal("Black")));          // tuxedo
        list.add(new VariantOption("red",                 Component.literal("Red")));
        list.add(new VariantOption("siamese",             Component.literal("Siamese")));
        list.add(new VariantOption("british_shorthair",   Component.literal("British Shorthair")));
        list.add(new VariantOption("calico",              Component.literal("Calico")));
        list.add(new VariantOption("persian",             Component.literal("Persian")));
        list.add(new VariantOption("ragdoll",             Component.literal("Ragdoll")));
        list.add(new VariantOption("white",               Component.literal("White")));
        list.add(new VariantOption("jellie",              Component.literal("Jellie")));
        list.add(new VariantOption("all_black",           Component.literal("All Black")));     // witch cat
        return list;
    }

    /** Horse base colors (vanilla 1.20.1). */
    private static List<VariantOption> horse() {
        List<VariantOption> list = new ArrayList<>();
        list.add(new VariantOption("white",       Component.literal("White")));
        list.add(new VariantOption("creamy",      Component.literal("Creamy")));
        list.add(new VariantOption("chestnut",    Component.literal("Chestnut")));
        list.add(new VariantOption("brown",       Component.literal("Brown")));
        list.add(new VariantOption("black",       Component.literal("Black")));
        list.add(new VariantOption("gray",        Component.literal("Gray")));
        list.add(new VariantOption("dark_brown",  Component.literal("Dark Brown")));
        return list;
    }

    /** Fox variants (vanilla 1.20.1). Keys align to Fox.Type constants. */
    private static List<VariantOption> fox() {
        List<VariantOption> list = new ArrayList<>();
        list.add(new VariantOption("red",  Component.literal("Red")));
        list.add(new VariantOption("snow", Component.literal("Snow")));
        return list;
    }

    /** Rabbit variants (vanilla 1.20.1). */
    private static List<VariantOption> rabbit() {
        List<VariantOption> list = new ArrayList<>();
        list.add(new VariantOption("brown",           Component.literal("Brown")));
        list.add(new VariantOption("white",           Component.literal("White")));
        list.add(new VariantOption("black",           Component.literal("Black")));
        list.add(new VariantOption("gold",            Component.literal("Gold")));
        list.add(new VariantOption("salt_and_pepper", Component.literal("Salt & Pepper")));
        return list;
    }

    /** Llama variants (vanilla 1.20.1). */
    private static List<VariantOption> llama() {
        List<VariantOption> list = new ArrayList<>();
        list.add(new VariantOption("creamy", Component.literal("Creamy")));
        list.add(new VariantOption("white",  Component.literal("White")));
        list.add(new VariantOption("brown",  Component.literal("Brown")));
        list.add(new VariantOption("gray",   Component.literal("Gray")));
        return list;
    }

    /** Frog variants (vanilla 1.20.1 registry-backed). */
private static List<VariantOption> frog() {
    List<VariantOption> list = new ArrayList<>();
    list.add(new VariantOption("temperate", Component.literal("Temperate (Orange)")));
    list.add(new VariantOption("cold",      Component.literal("Cold (Green)")));
    list.add(new VariantOption("warm",      Component.literal("Warm (White)")));
    return list;
}
/** Villager biome skins (vanilla 1.20.1). */
private static List<VariantOption> villager() {
    List<VariantOption> list = new ArrayList<>();
    list.add(new VariantOption("plains",  Component.literal("Plains")));
    list.add(new VariantOption("desert",  Component.literal("Desert")));
    list.add(new VariantOption("savanna", Component.literal("Savanna")));
    list.add(new VariantOption("jungle",  Component.literal("Jungle")));
    list.add(new VariantOption("taiga",   Component.literal("Taiga")));
    list.add(new VariantOption("snow",    Component.literal("Snow")));
    list.add(new VariantOption("swamp",   Component.literal("Swamp")));
    return list;
}

    /** Mooshroom variants (vanilla 1.20.1). */
    private static List<VariantOption> mooshroom() {
        List<VariantOption> list = new ArrayList<>();
        list.add(new VariantOption("red",   Component.literal("Red")));
        list.add(new VariantOption("brown", Component.literal("Brown")));
        return list;
    }

    /** Goat variants (vanilla 1.20.1). */
    private static List<VariantOption> goat() {
        List<VariantOption> list = new ArrayList<>();
        list.add(new VariantOption("default",   Component.literal("Default")));
        list.add(new VariantOption("screaming", Component.literal("Screaming")));
        return list;
    }

    // -------------------------
    // No-variant pet builders
    // -------------------------
    private static List<VariantOption> donkey()    { return Collections.emptyList(); }
    private static List<VariantOption> mule()      { return Collections.emptyList(); }
    private static List<VariantOption> camel()     { return Collections.emptyList(); }
    private static List<VariantOption> ocelot()    { return Collections.emptyList(); }
    private static List<VariantOption> cow()       { return Collections.emptyList(); }
    private static List<VariantOption> vex()       { return Collections.emptyList(); }
    private static List<VariantOption> blaze()     { return Collections.emptyList(); }
    private static List<VariantOption> snowGolem() { return Collections.emptyList(); }
    private static List<VariantOption> ironGolem() { return Collections.emptyList(); }
}
