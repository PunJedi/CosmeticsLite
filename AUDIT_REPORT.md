# CosmeticsLite Code Audit & Optimization Report

**Mod:** CosmeticsLite  
**Minecraft Version:** 1.20.1  
**Forge Version:** 47.4.0  
**Date:** 2025-01-XX  
**Auditor:** Code Review Analysis

---

## Executive Summary

This audit examines the CosmeticsLite mod codebase for architecture clarity, code quality, performance bottlenecks, lifecycle safety, security, and optimization opportunities. The mod implements a comprehensive cosmetics system with hats, particles, capes, pets, mini-games, and a Particle Lab editor.

**Overall Assessment:** The codebase is well-structured with clear separation of concerns. However, there are several areas for improvement in performance, code duplication, and security coverage.

---

## 1. Architecture Overview

### Core Packages

- **`com.pastlands.cosmeticslite`** – Main mod entrypoint, registry, data models, and sync logic
  - `CosmeticsLite.java` – Mod initialization, packet registration, lifecycle hooks
  - `CosmeticsRegistry.java` – Central registry for all cosmetic definitions (hats, particles, capes, pets)
  - `CosmeticDef.java` – Immutable data class for cosmetic definitions
  - `PlayerData.java` – Server-authoritative player cosmetic state (capability)
  - `PlayerEntitlements.java` – Pack/cosmetic unlock tracking (capability)
  - `CosmeticsSync.java` – Server→client sync helper

- **`permission`** – Rank-based permission system
  - `CosmeticsPermissions.java` – Permission checking API (VIP/VIP+/MVP/MVP+ matrix)
  - `CosmeticsFeature.java` – Feature enum (BASE_PARTICLES, BASE_HATS, CAPES, etc.)
  - `CosmeticsRank.java` – Rank enum

- **`network`** – Packet definitions and handlers
  - `PacketEquipRequest.java` – Client→server equip/clear requests
  - `PacketSyncCosmetics.java` – Server→client cosmetic state sync
  - `PublishCosmeticPacket.java` – Particle Lab publish requests
  - `ParticleDefinitionChangePacket.java` – Particle Lab save/update
  - `ParticlePreviewPacket.java` – Preview mode toggle
  - `S2CEquipDenied.java` – Equip denial feedback
  - `S2CEntitlementsSync.java` – Entitlements sync

- **`client`** – Client-only rendering and UI
  - `ClientCosmeticRenderer.java` – Particle rendering (tick handler)
  - `CosmeticHatLayer.java`, `CosmeticCapeLayer.java` – Entity render layers
  - `screen/` – GUI screens (CosmeticsChestScreen, ParticleLabScreen, etc.)
  - `model/` – Custom hat models

- **`entity`** – Cosmetic pet system
  - `PetManager.java` – Pet lifecycle, spawning, styling (large class, ~1169 lines)
  - `PetEntities.java` – Entity type registrations
  - `CosmeticPet*.java` – Custom pet entity classes (30+ files)

- **`particle`** – Particle system
  - `CosmeticParticleCatalog.java` – Published particle catalog
  - `CosmeticParticleEntry.java` – Catalog entry model
  - `ParticleProfiles.java` – Asset-based particle profiles
  - `config/` – Particle Lab definitions (ParticleDefinition, registry, etc.)

- **`minigame`** – Mini-game system
  - `api/MiniGame.java` – Mini-game interface
  - `impl/` – Game implementations (Snake, Minesweeper, etc.)
  - `client/` – Mini-game UI screens

- **`gadget`** – Gadget system (appears partially removed/legacy)

### Key Systems

- **Cosmetics Registry:**
  - Centralized `CosmeticsRegistry` with synchronized access
  - Auto-discovers capes from `/textures/cape/`
  - Auto-discovers custom hats from `/models/hats/<category>/`
  - Dev seed installs base cosmetics on first access
  - Indexed by ID, type, and pack

- **Networking:**
  - Single `SimpleChannel` with version "1"
  - 12+ packet types registered
  - Client debounce on equip requests (250ms cooldown)
  - Server-authoritative state

- **UI / Screens:**
  - `CosmeticsChestScreen` – Main cosmetics menu
  - `ParticleLabScreen` – Particle editor (complex, ~2000+ lines)
  - `CosmeticsCategoryScreen` – Category navigation
  - Custom widgets in `screen/parts/`

- **Particle Lab:**
  - JSON-based particle definitions
  - Client editor with real-time preview
  - Server-side save/load from config folder
  - Catalog system for published particles

- **Permissions:**
  - Rank-based matrix (VIP → MVP+)
  - Feature-based checks (BASE_PARTICLES, BLENDED_PARTICLES, etc.)
  - Permission node caching
  - Staff/OP bypass

- **Integrations:**
  - `WaveyCapesBridge.java` – Optional WaveyCapes compatibility

---

## 2. Code Quality & Maintainability

### Potential Issues

- **`PetManager.java:108-1168`** – Very large class (~1169 lines) handling pet spawning, styling, safety, and events. Consider splitting into:
  - `PetSpawner.java` – Spawn/despawn logic
  - `PetStyler.java` – Style application/capture
  - `PetSafety.java` – Safety flags, combat goal stripping
  - `PetEvents.java` – Event handlers

- **`CosmeticCommand.java:223-389`** – Duplicate command definitions (`particles reload` and `particles preview` appear twice, lines 223-252 and 308-388). Remove duplicate.

- **`ClientCosmeticRenderer.java:182-189`** – Debug logging every 60 ticks (3 seconds) in hot render path. Should be conditional or removed in production.

- **`CosmeticsRegistry.java:666-684`** – `resolveResourceManager()` uses `DistExecutor.unsafeCallWhenOn()` with client-only class reference. Works but could be cleaner with proper `@OnlyIn` guards.

- **`PacketEquipRequest.java:38`** – `VALID_TYPES` includes "gadgets" but gadgets appear to be removed/legacy. Clean up.

- **`PetManager.java:891-931`** – `stripCombatGoals()` uses reflection and string matching on class names. Fragile if Minecraft internals change. Consider using mixins or more stable API.

- **`CosmeticsRegistry.java:183-493`** – Dev seed methods are very long with hardcoded cosmetic definitions. Consider moving to JSON or external config.

- **`ClientCosmeticRenderer.java:250-1003`** – Large render methods with duplicated particle spawning logic. Extract common spawn helpers.

- **`CosmeticsLite.java:238-262`** – `onServerStarting()` creates new HashMap every time. Could reuse or optimize.

- **`CosmeticsRegistry.java:80-119`** – Multiple synchronized methods that create new ArrayList/Collections on every call. Consider caching unmodifiable views.

### Suggested Refactors

- **[Impact: High] [Risk: Low]** Split `PetManager.java` into focused classes (spawner, styler, safety, events). Reduces complexity and improves testability.

- **[Impact: Medium] [Risk: Low]** Remove duplicate command definitions in `CosmeticCommand.java`. Simple cleanup.

- **[Impact: Low] [Risk: Low]** Remove or make conditional the debug logging in `ClientCosmeticRenderer.java:182-189`. Performance and log noise.

- **[Impact: Medium] [Risk: Medium]** Extract dev seed cosmetics to JSON config files. Makes it easier to add/modify base cosmetics without code changes.

- **[Impact: Low] [Risk: Low]** Remove "gadgets" from `VALID_TYPES` in `PacketEquipRequest.java` if gadgets are truly removed.

- **[Impact: Medium] [Risk: Medium]** Refactor `stripCombatGoals()` to use mixins or more stable API. Reduces fragility.

- **[Impact: Medium] [Risk: Low]** Extract common particle spawning logic in `ClientCosmeticRenderer.java` to reduce duplication.

---

## 3. Performance Hotspots

### Possible Issues

- **`ClientCosmeticRenderer.java:113-226`** – Runs every client tick. Allocates new lists/collections, performs registry lookups, and logs every 60 ticks. Hot path.

- **`ClientCosmeticRenderer.java:278-357`** – `renderBlendedParticleLayers()` creates new `LayerState` objects and performs multiple registry lookups per tick per player.

- **`ClientCosmeticRenderer.java:286`** – `MAX_PARTICLES_PER_TICK = 200` is high. With multiple players, this could cause lag.

- **`PetManager.java:320-342`** – `onPlayerTick()` runs for every player every tick. Presence check only every 40 ticks, but still checks debounce/warmup every tick.

- **`CosmeticsRegistry.java:80-119`** – Synchronized methods create new ArrayList/Collections on every call. Called frequently from UI.

- **`CosmeticsSync.java:23-32`** – `sync()` creates new HashMap on every call. Called on equip, login, respawn, dimension change.

- **`CosmeticsLite.java:283-292`** – `sendParticleDefinitions()` creates new HashMap and iterates all definitions on every player login.

- **`PetManager.java:1054-1068`** – `findNearbyOwnedCosmetic()` scans entities in 16-block radius on every presence check (every 40 ticks per player).

- **`ClientCosmeticRenderer.java:394-671`** – Multiple movement-specific spawn methods (`spawnFloatUp`, `spawnBurst`, etc.) create new random offsets/velocities every call. Could cache some calculations.

- **`CosmeticsRegistry.java:254-279`** – `getUnlockedByType()` iterates all cosmetics and calls `UnlockManager.isUnlocked()` for each. No caching.

### Optimization Suggestions

- **[Impact: High] [Risk: Low]** Cache `LayerState` objects in `ClientCosmeticRenderer` instead of recreating. Use weak references or cleanup on cosmetic change.

- **[Impact: High] [Risk: Low]** Reduce or remove debug logging in `ClientCosmeticRenderer.onClientTick()`. Log only on cosmetic change, not every 60 ticks.

- **[Impact: Medium] [Risk: Low]** Cache unmodifiable views in `CosmeticsRegistry` instead of creating new collections. Store `Collections.unmodifiableList()` results.

- **[Impact: Medium] [Risk: Low]** Reuse HashMap in `CosmeticsSync.sync()` or use a pool. Avoid allocation per sync.

- **[Impact: Medium] [Risk: Low]** Cache particle definition map in `CosmeticsLite` instead of rebuilding on every login. Only rebuild on config reload.

- **[Impact: Medium] [Risk: Medium]** Optimize `PetManager.onPlayerTick()` to skip work when debounce/warmup are zero. Use a more efficient scheduling system.

- **[Impact: Low] [Risk: Low]** Reduce `MAX_PARTICLES_PER_TICK` from 200 to 50-100. Most effects don't need 200 particles/tick.

- **[Impact: Medium] [Risk: Medium]** Cache unlocked cosmetics per player in `CosmeticsRegistry` with TTL. Invalidate on entitlements change.

- **[Impact: Low] [Risk: Low]** Optimize `findNearbyOwnedCosmetic()` to use a spatial index or reduce search radius when pet is already tracked.

---

## 4. Forge / Lifecycle Safety

### Potential Issues

- **`CosmeticsRegistry.java:666-692`** – `resolveResourceManager()` uses `DistExecutor.unsafeCallWhenOn()` with client-only `ClientHooks` class. Works but not ideal. The `@OnlyIn(Dist.CLIENT)` inner class is correct, but the pattern is fragile.

- **`CosmeticsLite.java:74-83`** – Client setup uses `DistExecutor.unsafeRunWhenOn()` correctly, but the nested lambda could be clearer.

- **`ClientCosmeticRenderer.java:41`** – `@Mod.EventBusSubscriber` with `Dist.CLIENT` is correct, but the class has no `@OnlyIn` annotations on methods. Methods are only called client-side, but explicit guards would be safer.

- **`PacketEquipRequest.java:201-224`** – Client-side methods use `@OnlyIn(Dist.CLIENT)` correctly.

- **`CosmeticsLite.java:362-365`** – `onRegisterLayers()` uses `@OnlyIn(Dist.CLIENT)` correctly.

### Recommendations

- Add explicit `@OnlyIn(Dist.CLIENT)` annotations to client-only methods in `ClientCosmeticRenderer` for clarity and safety.

- Consider refactoring `resolveResourceManager()` to use a cleaner pattern, possibly with a client-side event or capability.

- Ensure all client-only packet handlers have proper `@OnlyIn` guards (already present, but verify consistency).

---

## 5. Permissions & Security

### Checks Coverage

- **Hat equip:** ✅ OK – `PacketEquipRequest.java:182-183` calls `CosmeticsPermissions.canUseHat()`
- **Particle equip:** ✅ OK – `PacketEquipRequest.java:185-186` calls `CosmeticsPermissions.canUseParticle()`
- **Capes:** ✅ OK – `PacketEquipRequest.java:188-189` calls `CosmeticsPermissions.canUseFeature(CAPES)`
- **Pets:** ✅ OK – `PacketEquipRequest.java:191-192` calls `CosmeticsPermissions.canUseFeature(PETS)`
- **Mini-games:** ⚠️ **MISSING** – No permission check found when opening mini-game hub. `MiniGameHubScreen` can be opened without permission check.
- **Particle Lab:** ✅ OK – Multiple checks:
  - `PublishCosmeticPacket.java:75` – `canUseParticleLab()` on publish
  - `ParticleDefinitionChangePacket.java:84` – `canUseParticleLab()` on save/update
  - `CosmeticCommand.java:228, 258, 287, 313, 343, 372` – `canUseParticleLab()` on commands

### Potential Vulnerabilities

- **`MiniGameHubScreen.java`** – No server-side permission check when opening mini-game hub. Client can open screen directly. Need to check `CosmeticsPermissions.canUseFeature(MINI_GAMES)` before allowing access.

- **`PacketEquipRequest.java:174-178`** – If `CosmeticDef` is null (legacy/custom cosmetic), permission check returns `true` by default. This could allow equipping cosmetics that don't exist in registry. Consider requiring explicit permission or denying unknown cosmetics.

- **`CosmeticsPermissions.java:255-285`** – `hasNode()` falls back to OP check if PermissionAPI fails. This is safe, but the fallback could mask permission system issues.

### Suggested Fixes

- Add permission check in `OpenCosmeticsScreenPacket` handler or in the command/screen opening logic to verify `canUseFeature(MINI_GAMES)` before allowing mini-game access.

- Consider changing `PacketEquipRequest.checkPermissionForEquip()` to deny unknown cosmetics by default, or require a separate permission node for "legacy" cosmetics.

- Add logging when PermissionAPI fails in `hasNode()` to help diagnose permission system issues.

---

## 6. UI / UX Observations

- **`CosmeticsChestScreen.java`** – Large screen class. Consider splitting into tab-specific classes or using composition.

- **`ParticleLabScreen.java`** – Very large class (~2000+ lines). Complex editor UI. Consider splitting into:
  - `ParticleLabScreen.java` – Main screen
  - `ParticleLayerEditor.java` – Layer editing UI
  - `ParticlePreviewPane.java` – Preview rendering (already exists but could be integrated better)

- **Permission feedback** – When equip is denied (`S2CEquipDenied`), client receives packet but may not show clear feedback to user. Verify UI shows denial message.

- **Mini-game access** – If mini-game permission is missing, user may see mini-game button but get no feedback when clicking. Add permission check and disable/hide button if no access.

- **Particle Lab access** – Similar to mini-games, verify UI disables/hides Particle Lab button if permission is missing.

---

## 7. Optimization Roadmap

### Tier 1 – High Impact, Low Risk

1. **Remove duplicate command definitions** (`CosmeticCommand.java:223-389`)
   - **File:** `src/main/java/com/pastlands/cosmeticslite/CosmeticCommand.java`
   - **Issue:** `particles reload` and `particles preview` commands defined twice
   - **Fix:** Remove lines 308-388 (duplicate)

2. **Cache LayerState objects in ClientCosmeticRenderer**
   - **File:** `src/main/java/com/pastlands/cosmeticslite/ClientCosmeticRenderer.java`
   - **Issue:** Creates new `LayerState` per cosmetic per tick
   - **Fix:** Use `ConcurrentHashMap<ResourceLocation, LayerState>` and reuse instances

3. **Remove/reduce debug logging in hot path**
   - **File:** `src/main/java/com/pastlands/cosmeticslite/ClientCosmeticRenderer.java:182-189`
   - **Issue:** Logs every 60 ticks in render loop
   - **Fix:** Remove or make conditional on debug flag

4. **Cache unmodifiable collections in CosmeticsRegistry**
   - **File:** `src/main/java/com/pastlands/cosmeticslite/CosmeticsRegistry.java:80-119`
   - **Issue:** Creates new ArrayList/Collections on every call
   - **Fix:** Cache `Collections.unmodifiableList()` results, invalidate on registry change

5. **Add mini-game permission check**
   - **File:** `src/main/java/com/pastlands/cosmeticslite/minigame/client/MiniGameHubScreen.java` or screen opening logic
   - **Issue:** No permission check for mini-game access
   - **Fix:** Check `CosmeticsPermissions.canUseFeature(MINI_GAMES)` before allowing access

### Tier 2 – Medium Impact or Medium Risk

6. **Split PetManager into focused classes**
   - **File:** `src/main/java/com/pastlands/cosmeticslite/entity/PetManager.java`
   - **Issue:** 1169-line class doing too much
   - **Fix:** Split into `PetSpawner`, `PetStyler`, `PetSafety`, `PetEvents`

7. **Optimize CosmeticsSync to reuse HashMap**
   - **File:** `src/main/java/com/pastlands/cosmeticslite/CosmeticsSync.java:23-32`
   - **Issue:** Creates new HashMap on every sync
   - **Fix:** Reuse or use object pool

8. **Cache particle definition map in CosmeticsLite**
   - **File:** `src/main/java/com/pastlands/cosmeticslite/CosmeticsLite.java:283-292`
   - **Issue:** Rebuilds map on every player login
   - **Fix:** Cache map, rebuild only on config reload

9. **Reduce MAX_PARTICLES_PER_TICK**
   - **File:** `src/main/java/com/pastlands/cosmeticslite/ClientCosmeticRenderer.java:286`
   - **Issue:** 200 particles/tick is very high
   - **Fix:** Reduce to 50-100, or make configurable

10. **Extract common particle spawn logic**
    - **File:** `src/main/java/com/pastlands/cosmeticslite/ClientCosmeticRenderer.java:394-671`
    - **Issue:** Duplicated spawn logic across movement types
    - **Fix:** Extract common helpers

11. **Optimize PetManager tick handler**
    - **File:** `src/main/java/com/pastlands/cosmeticslite/entity/PetManager.java:320-342`
    - **Issue:** Checks debounce/warmup every tick even when zero
    - **Fix:** Skip work when values are zero, use more efficient scheduling

### Tier 3 – Low Impact / Nice-to-have

12. **Remove "gadgets" from VALID_TYPES**
    - **File:** `src/main/java/com/pastlands/cosmeticslite/PacketEquipRequest.java:38`
    - **Issue:** Legacy type no longer used
    - **Fix:** Remove from set

13. **Extract dev seed to JSON config**
    - **File:** `src/main/java/com/pastlands/cosmeticslite/CosmeticsRegistry.java:183-493`
    - **Issue:** Hardcoded cosmetic definitions
    - **Fix:** Move to JSON, load on init

14. **Add @OnlyIn annotations to ClientCosmeticRenderer methods**
    - **File:** `src/main/java/com/pastlands/cosmeticslite/ClientCosmeticRenderer.java`
    - **Issue:** Missing explicit client-only guards
    - **Fix:** Add `@OnlyIn(Dist.CLIENT)` to client-only methods

15. **Refactor stripCombatGoals to use mixins**
    - **File:** `src/main/java/com/pastlands/cosmeticslite/entity/PetManager.java:891-931`
    - **Issue:** Fragile reflection-based approach
    - **Fix:** Use mixins or more stable API

---

## Summary Statistics

- **Total Java Files:** ~131
- **Largest Classes:**
  - `PetManager.java` – ~1169 lines
  - `ParticleLabScreen.java` – ~2000+ lines (estimated)
  - `ClientCosmeticRenderer.java` – ~1003 lines
  - `CosmeticsRegistry.java` – ~693 lines

- **Permission Checks:** ✅ Good coverage (hats, particles, capes, pets, particle lab)
- **Missing Checks:** ⚠️ Mini-games

- **Performance Hotspots:** 10 identified
- **Code Quality Issues:** 9 identified
- **Security Concerns:** 2 identified (mini-games, unknown cosmetics)

---

## Conclusion

The CosmeticsLite mod is well-architected with clear separation of concerns. The permission system is mostly complete, and the codebase follows Forge best practices. The main areas for improvement are:

1. **Performance:** Several hot paths allocate unnecessarily and could benefit from caching
2. **Code Organization:** Some classes are very large and should be split
3. **Security:** Mini-game access needs permission checks
4. **Code Quality:** Some duplication and legacy code should be cleaned up

The Tier 1 optimizations are low-risk, high-impact changes that should be prioritized. Tier 2 and 3 can be tackled as time permits.

---

**End of Report**

