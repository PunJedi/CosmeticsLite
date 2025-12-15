# CosmeticsLite Structure Report
## Hats and Particles Hierarchy Analysis

---

## 1. Hats Hierarchy

### Directories and Resource Paths

**Java Classes:**
- `com.pastlands.cosmeticslite.CosmeticsRegistry` - Main registry for all cosmetics
- `com.pastlands.cosmeticslite.CosmeticDef` - Canonical cosmetic definition record
- `com.pastlands.cosmeticslite.CosmeticHatLayer` - Client-side rendering layer
- `com.pastlands.cosmeticslite.client.HatModelPreloader` - Preloads hat models from JSON
- `com.pastlands.cosmeticslite.client.model.CosmeticsModels` - Model management
- `com.pastlands.cosmeticslite.client.model.TophatModel` - Hardcoded tophat model

**Resource Paths:**
- **Hat Models:** `src/main/resources/assets/cosmeticslite/models/hats/<category>/<name>.json`
  - Categories: `animal/`, `fantasy/`, `food/`
  - Examples: `models/hats/animal/bunny_hat.json`, `models/hats/fantasy/wizard_dragon_hat.json`
- **Hat Textures:** `src/main/resources/assets/cosmeticslite/textures/block/<category>/<name>_texture.png`
  - Mirrors the model structure under `textures/block/`

### Registration Mechanism

**Base Hats (Hardcoded):**
- Defined in `CosmeticsRegistry.seedDevHatsUnlocked()`
- Registered with `pack = "base"`
- Includes:
  - Tophat (hardcoded model)
  - Skull blocks (dragon, skeleton, creeper, zombie, piglin)
  - Block hats (pumpkin, anvil, honey_block, slime_block, TNT, etc.)
  - Odd hats (cake, furnace, chest, jukebox, beacon, etc.)
- ResourceLocation pattern: `cosmeticslite:hat_<name>` or `cosmeticslite:tophat`

**Custom Hats (Auto-Discovered):**
- Discovered via `CosmeticsRegistry.discoverCustomHatsFromAssets()`
- Scans `models/hats/` directory recursively
- Category extracted from folder path: `models/hats/<category>/<name>.json`
- Registered with `pack = <category>` (e.g., "animal", "fantasy", "food", "misc")
- ResourceLocation pattern: `cosmeticslite:hat_<name>` (lowercased)
- Model reference stored in properties: `{"model": "cosmeticslite:hats/<category>/<name>"}`

### Base vs Custom Distinction

**YES - Distinction exists via pack field:**

1. **Base Hats:**
   - `pack = "base"`
   - Hardcoded in `seedDevHatsUnlocked()`
   - Include vanilla blocks/items as hats (skulls, blocks, etc.)

2. **Custom Hats:**
   - `pack = <category>` (e.g., "animal", "fantasy", "food", "misc")
   - Auto-discovered from JSON files
   - Custom JSON models under `models/hats/<category>/`

**Note:** The distinction is implicit via the `pack` field, not via separate registries or enums. All hats use the same `CosmeticDef` structure and are stored in the same `CosmeticsRegistry` maps.

---

## 2. Particles Hierarchy

### Directories and Resource Paths

**Java Classes:**
- `com.pastlands.cosmeticslite.CosmeticsRegistry` - Main cosmetic registry (includes particle cosmetic definitions)
- `com.pastlands.cosmeticslite.particle.CosmeticParticleCatalog` - Server-side catalog for published particles
- `com.pastlands.cosmeticslite.particle.CosmeticParticleEntry` - Catalog entry structure
- `com.pastlands.cosmeticslite.particle.LegacyCosmeticParticles` - Legacy built-in particle definitions
- `com.pastlands.cosmeticslite.particle.config.CosmeticParticleRegistry` - Registry for particle definitions (Particle Lab)
- `com.pastlands.cosmeticslite.particle.config.ParticleDefinition` - Complete particle definition with layers
- `com.pastlands.cosmeticslite.particle.ParticleProfiles` - Client-side particle profile system
- `com.pastlands.cosmeticslite.client.screen.ParticleLabScreen` - Particle Lab editor GUI

**Resource Paths:**
- **Built-in Particle Definitions:** `src/main/resources/assets/cosmeticslite/particles/particle/*.json`
  - Examples: `particles/particle/flame.json`, `particles/particle/heart_blended.json`
- **Particle Lab Config:** `config/cosmeticslite/particle_lab/*.json` (runtime, not in source)
  - User-created particle definitions saved here
  - Format: `cosmeticslite_particle_<name>.json`

### Registration Mechanism

**Base Particles (Hardcoded):**
- Defined in `CosmeticsRegistry.seedDevParticlesUnlocked()`
- Maps directly to vanilla Minecraft particle effects
- ResourceLocation pattern: `cosmeticslite:particle/<effectName>`
- Examples: `particle/flame`, `particle/heart`, `particle/happy_villager`
- Properties: `{"effect": "minecraft:<effectName>"}`
- Pack: Defaults to `"base"` (or null, which becomes "base")

**Blended Particles (Hardcoded):**
- Defined in `CosmeticsRegistry.seedDevBlendedParticlesUnlocked()`
- ResourceLocation pattern: `cosmeticslite:particle/<name>_blended`
- Examples: `particle/heart_blended`, `particle/flame_cape_blended`
- Pack: Explicitly set to `"blended"` (via `PACK_BLENDED` constant)
- Properties: `{"effect": "minecraft:<baseEffectName>"}` (same underlying particle as base)

**Particle Lab Definitions (User-Created):**
- Loaded from `config/cosmeticslite/particle_lab/*.json` via `CosmeticParticleRegistry.reloadFromConfig()`
- Can override built-in definitions if IDs match
- ResourceLocation pattern: `cosmeticslite:particle/<name>`
- Stored in `CosmeticParticleRegistry` (separate from `CosmeticsRegistry`)
- Can be published to catalog via `PublishCosmeticPacket`

**Detection Method:**
```java
public static boolean isBlendedParticle(CosmeticDef def) {
    if (!TYPE_PARTICLES.equals(def.type())) return false;
    String path = def.id().getPath(); // e.g. "particle/heart_blended"
    if (path.contains("blended")) return true;
    String pack = def.pack();
    return pack != null && pack.equalsIgnoreCase(PACK_BLENDED);
}
```

### Base vs Blended/Lab Distinction

**YES - Multiple distinctions exist:**

1. **Via Naming Convention:**
   - Blended particles have `"_blended"` suffix in ID path
   - Example: `particle/heart_blended` vs `particle/heart`

2. **Via Pack Field:**
   - Base particles: `pack = "base"` (or null/default)
   - Blended particles: `pack = "blended"` (explicit)
   - Lab particles: Pack may vary (not consistently set)

3. **Via Registry:**
   - Base/Blended: Stored in `CosmeticsRegistry` as `CosmeticDef` entries
   - Lab definitions: Stored in `CosmeticParticleRegistry` as `ParticleDefinition` entries
   - Lab definitions can be converted to `CosmeticDef` when published

4. **Via Resource Location:**
   - Base particles: `cosmeticslite:particle/<vanillaEffectName>`
   - Blended particles: `cosmeticslite:particle/<name>_blended`
   - Lab particles: `cosmeticslite:particle/<customName>` (may overlap with base/blended)

5. **Via Source:**
   - Built-in particles: Defined in `LegacyCosmeticParticles.builtins()`
   - Lab particles: Loaded from config files, marked as `Source.CONFIG` in catalog

**Note:** The distinction is **multi-layered** - naming, pack field, and separate registries all contribute. The `isBlendedParticle()` helper method checks both naming and pack.

---

## 3. Quick Conclusions

### Hats

✅ **Can infer "base vs custom" from current structure:**
- Base hats: `pack == "base"`
- Custom hats: `pack == <category>` (animal, fantasy, food, misc)
- **Recommendation:** Use `pack` field for permission checks. No new enum/tag needed.

### Particles

✅ **Can infer "base vs blended/lab" from current structure:**
- Base particles: `pack == "base"` (or null) AND no `"_blended"` in path
- Blended particles: `pack == "blended"` OR `"_blended"` in path
- Lab particles: Present in `CosmeticParticleRegistry` but may not have consistent pack marking
- **Recommendation:** 
  1. Use `isBlendedParticle()` helper for blended detection
  2. Check `CosmeticParticleRegistry.contains(id)` for lab-created particles
  3. Consider adding explicit `pack` marking for lab particles if needed for permissions

### Implementation Notes

- **Hats:** The `pack` field cleanly separates base ("base") from custom (category names). Permission system can check `def.pack().equals("base")` for base hats.
- **Particles:** More complex - use `isBlendedParticle()` for blended, and registry lookup for lab-created. May need to enhance lab particles with explicit pack marking if permission granularity requires it.
- **No new enums/tags needed** - existing structure (pack field, naming conventions, registry separation) is sufficient for permission checks.

