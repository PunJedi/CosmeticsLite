# Gadget Purge Plan - Phase G1: Inventory & Mapping

## Status: ✅ COMPLETE - All touch points mapped

This document maps all gadget-related code and entry points for surgical removal in later phases.

---

## Core Gadget Classes (6 files in `com.pastlands.cosmeticslite.gadget.*`)

### 1. **GadgetNet.java** (1,656 lines)
**Purpose:** Networking + action dispatch for active-use gadgets
- **Network Channel:** `cosmeticslite:gadget` (SimpleChannel)
- **Packets:**
  - `UseGadgetC2S` - Client-to-server: request gadget activation
  - `PlayGadgetFxS2C` - Server-to-client: broadcast gadget FX
- **Key Components:**
  - `GadgetActions` - Registry of gadget actions (hardwired + JSON-driven)
  - `IGadgetAction` interface - Action implementations
  - `GenericGadgetAction` - JSON-driven action handler
  - `ClientMenuHold` - Menu hold signaling system
  - `ClientScheduler` - Client-side task scheduler
  - `ClientCooldownGate` - 45s cooldown gate
  - `FastFx` - Client-side FX helpers (particles, sounds)
  - `ConfettiBurstFx` - Confetti burst effect implementation
- **Initialization:** Called from `ModLifecycle.java` and `CosmeticsLite.java`

### 2. **GadgetClientCommands.java** (650 lines)
**Purpose:** Client-only gadget commands & minimal UI
- **Commands:**
  - `/gadget use` - Use equipped gadget
  - `/gadget use <id>` - Use specific gadget
  - `/gadget list` - Open quick menu
  - `/glist` - Alias for quick menu
- **Features:**
  - Pretty catalog (id -> name/desc)
  - Cooldown tracking (`remainingMs()`, `noteJustUsed()`)
  - Quick menu integration
  - Cosmetics screen integration (scheduler for close → use → reopen)
- **Event Subscriber:** `@Mod.EventBusSubscriber` for client commands

### 3. **GadgetQuickMenuScreen.java** (284 lines)
**Purpose:** Simple mouse-grabbing gadget picker UI
- Opened by `/glist` or `/gadget list`
- Shows live cooldown text (mm:ss)
- Auto-reopens timed to gadget duration
- **Dependencies:** Uses `GadgetClientCommands` for cooldown info

### 4. **GadgetClientAPI.java** (424 lines)
**Purpose:** Client-side hook surface for triggering gadgets
- `useGadgetFromMenu()` - Server-authoritative activation
- `preview()` - Client-only preview around player
- `previewOnMannequin()` - Client-only preview on GUI mannequin
- JSON-aware helpers (duration, cooldown overrides)
- Reflection bridges for mannequin/preview integration

### 5. **GadgetPresetReloader.java** (310 lines)
**Purpose:** Client resource reloader for gadget JSON presets
- **Paths:**
  - New: `assets/cosmeticslite/gadgets/*.json` (per-gadget files)
  - Legacy: `assets/cosmeticslite/gadgets_presets.json` (single file)
- Merges JSON properties into `CosmeticDef.properties()`
- Validates & clamps numeric fields
- **Event Subscriber:** `@Mod.EventBusSubscriber` for resource reload

### 6. **GadgetTiming.java** (226 lines)
**Purpose:** Lifetime & duration semantics for client FX
- JSON key parsing (duration_ms, cooldown_ms, etc.)
- Menu hold timing calculation
- `ClientBurstScheduler` - Multi-burst action scheduler
- **Event Subscriber:** Tick listener for burst scheduler

---

## Cross-References in Shared Files

### **CosmeticsChestScreen.java**
**Gadget References:** 54+ lines
- **Line 16:** Import `GadgetClientCommands`
- **Line 45:** Comment: "GADGETS tab: shows pretty gadget name + description"
- **Lines 55-59:** Menu hold listener registration (`GadgetNet.ClientMenuHold`)
- **Line 207:** Tab enum includes `GADGETS("gadgets")`
- **Line 329:** Tab visibility check: `!CosmeticsRegistry.getByType("gadgets").isEmpty()`
- **Line 481:** Tab creation: `new TabBar.Tab("gadgets", "Gadgets")`
- **Lines 705-719:** Gadget-specific selection logic (auto-select equipped)
- **Lines 758-770:** Gadget re-equip logic (allow re-equipping to fire)
- **Lines 787-813:** Gadget equip handler (close menu, fire gadget, cooldown check)
- **Lines 891-897:** Gadget button state logic
- **Lines 1105-1111:** Gadget-aware caption in preview well
- **Lines 1160-1165:** Cooldown label rendering (only on Gadgets tab)
- **Lines 1182-1186:** `resolveSelectedOrEquippedGadget()` helper
- **Lines 1608-1623:** `renderGadgetCooldown()` method

### **CosmeticsRegistry.java**
**Gadget References:** 54 lines
- **Line 7:** Import `GadgetNet`
- **Line 28:** Comment mentions "gadgets" in seed list
- **Line 31:** Comment: "Merges gadget presets from assets/cosmeticslite/gadgets_presets.json"
- **Line 44:** Constant: `TYPE_GADGETS = "gadgets"`
- **Lines 66-67:** Comment: "Always merge gadget presets after seeds/defs"
- **Line 67:** Call: `loadGadgetPresetsFromAssets()`
- **Lines 73-77:** Re-bootstrap `GadgetActions` after registry rebuild
- **Line 193:** Call: `loadGadgetPresetsFromAssets()`
- **Lines 201-202:** Sort gadgets list
- **Lines 205-209:** Re-bootstrap `GadgetActions` after dev seed
- **Lines 529-810:** `seedDevGadgetsUnlocked()` - Creates all dev gadget entries
- **Lines 810-856:** `loadGadgetPresetsFromAssets()` - Loads JSON presets

### **PlayerData.java**
**Gadget References:** 8 lines
- **Line 24:** Comment mentions "gadgets" in category list
- **Line 38:** Comment: `gadgets: "namespace:path"`
- **Line 45:** Comment: `gadgets: { ... }`
- **Line 64:** Constant: `TYPE_GADGETS = "gadgets"`
- **Lines 249-252:** Gadget-specific getter/setter methods:
  - `getEquippedGadgetId()`
  - `setEquippedGadgetId()`
  - `clearGadget()`

### **CosmeticsLite.java**
**Gadget References:** 8 lines
- **Line 6:** Import `GadgetNet`
- **Line 86:** Call: `GadgetNet.init()` in common setup
- **Lines 279-291:** Client setup - primes `GadgetTiming.ClientBurstScheduler`

### **ModLifecycle.java**
**Gadget References:** 2 lines
- **Line 4:** Import `GadgetNet`
- **Line 15:** Call: `event.enqueueWork(GadgetNet::init)`

---

## Resource Files

### **gadgets_presets.json**
**Location:** `src/main/resources/assets/cosmeticslite/gadgets_presets.json`
**Purpose:** Legacy single-file gadget preset definitions
**Status:** Will be removed in later phase

### **gadgets/** directory (if exists)
**Location:** `src/main/resources/assets/cosmeticslite/gadgets/*.json`
**Purpose:** Per-gadget JSON preset files (new preferred path)
**Status:** Will be removed in later phase

---

## Command Registration

### Client Commands (via `RegisterClientCommandsEvent`)
- **GadgetClientCommands.java:**
  - `/gadget use` (with optional id argument)
  - `/gadget list`
  - `/glist` (alias)

---

## Network Channels

### Gadget Network Channel
- **Name:** `cosmeticslite:gadget`
- **Protocol:** "1"
- **Packets:**
  1. `UseGadgetC2S` (C2S) - Request gadget activation
  2. `PlayGadgetFxS2C` (S2C) - Broadcast gadget FX
- **Initialization:** `GadgetNet.init()` called from:
  - `ModLifecycle.java` (FMLCommonSetupEvent)
  - `CosmeticsLite.java` (common setup)

---

## Mini-Game Dependency Check

### ✅ VERIFIED: No Dependencies
**Search Results:** No matches found for "gadget" in `src/main/java/com/pastlands/cosmeticslite/minigame/`

**Mini-Game Files Checked:**
- `MiniGameHubScreen.java` - No gadget imports
- `MiniGamePlayScreen.java` - No gadget imports
- `MiniGame.java` (interface) - No gadget references
- All game implementations (Snake, Minesweeper, etc.) - No gadget references

**Conclusion:** Mini-games are completely independent of gadgets. Safe to remove gadgets without affecting mini-games.

---

## Summary Statistics

- **Core Gadget Classes:** 6 files
- **Total Gadget Code:** ~3,550 lines
- **Cross-Reference Files:** 5 files (CosmeticsChestScreen, CosmeticsRegistry, PlayerData, CosmeticsLite, ModLifecycle)
- **Resource Files:** 1+ (gadgets_presets.json + potential gadgets/ directory)
- **Network Packets:** 2 (UseGadgetC2S, PlayGadgetFxS2C)
- **Client Commands:** 3 commands + 1 alias
- **Event Subscribers:** 3 (@Mod.EventBusSubscriber annotations)

---

## Next Steps (Phase G2+)

1. **Phase G2:** Remove gadget tab from CosmeticsChestScreen
2. **Phase G3:** Remove gadget commands
3. **Phase G4:** Remove gadget network packets
4. **Phase G5:** Remove gadget classes
5. **Phase G6:** Remove gadget JSON presets
6. **Phase G7:** Clean up cross-references in shared files

---

## Notes

- All gadget functionality is isolated to the `gadget.*` package except for cross-references
- Mini-games have zero dependencies on gadgets ✅
- Gadget removal will require careful handling of:
  - Tab bar (remove "gadgets" tab)
  - Registry type filtering (remove "gadgets" type checks)
  - PlayerData storage (remove gadget slot)
  - Network initialization (remove GadgetNet.init() calls)

