// src/main/java/com/pastlands/cosmeticslite/CosmeticDef.java
package com.pastlands.cosmeticslite;

import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical cosmetic definition used everywhere (UI, registry, packets, loader).
 * Field order and accessor names are stable:
 *   id(), name(), description(), type(), icon(), properties(), pack()
 *
 * Forge 47.4.0 (MC 1.20.1)
 */
public final class CosmeticDef {

    private final ResourceLocation id;
    private final String name;
    private final String description;
    /** One of "particles", "hats", "capes", "pets". */
    private final String type;
    /** Item ID used to render the slot icon, e.g. minecraft:diamond. */
    private final ResourceLocation icon;
    /** Optional free-form key/value properties (may be empty). */
    private final Map<String, String> properties;
    /** Pack identifier (e.g. "base", "magic", "beanie"). */
    private final String pack;

    /**
     * Full constructor including pack id.
     */
    public CosmeticDef(ResourceLocation id,
                       String name,
                       String description,
                       String type,
                       ResourceLocation icon,
                       Map<String, String> properties,
                       String pack) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = name == null ? "" : name;
        this.description = description == null ? "" : description;
        this.type = Objects.requireNonNull(type, "type");
        this.icon = icon;
        this.properties = (properties == null) ? Collections.emptyMap() : Map.copyOf(properties);
        this.pack = (pack == null || pack.isBlank()) ? "base" : pack;
    }

    /**
     * Backward-compatible constructor: defaults pack to "base".
     */
    public CosmeticDef(ResourceLocation id,
                       String name,
                       String description,
                       String type,
                       ResourceLocation icon,
                       Map<String, String> properties) {
        this(id, name, description, type, icon, properties, "base");
    }

    // ------------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------------

    public ResourceLocation id() { return id; }
    public String name() { return name; }
    public String description() { return description; }
    public String type() { return type; }
    public ResourceLocation icon() { return icon; }
    public Map<String, String> properties() { return properties; }
    public String pack() { return pack; }

    @Override
    public String toString() {
        return id.toString() + " [pack=" + pack + "]";
    }
}
