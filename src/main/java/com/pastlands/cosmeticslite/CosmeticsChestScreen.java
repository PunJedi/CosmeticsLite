package com.pastlands.cosmeticslite;

import com.pastlands.cosmeticslite.client.state.ScreenState;
import com.pastlands.cosmeticslite.client.CosmeticsClientState;
import com.pastlands.cosmeticslite.client.screen.parts.ColorWheelWidget;
import com.pastlands.cosmeticslite.client.screen.parts.GridView;
import com.pastlands.cosmeticslite.client.screen.parts.TabBar;
import com.pastlands.cosmeticslite.client.screen.parts.VariantDropdownWidget;
import com.pastlands.cosmeticslite.gui.slot.CosmeticGuiSlot;
import com.pastlands.cosmeticslite.gui.slot.CosmeticSlot;
import com.pastlands.cosmeticslite.PacketEquipRequest;
import com.pastlands.cosmeticslite.network.PacketSetPetColor;
import com.pastlands.cosmeticslite.network.PacketSetPetVariant;
import com.pastlands.cosmeticslite.preview.MannequinPane;
import com.pastlands.cosmeticslite.preview.ParticlePreviewPane;
import com.pastlands.cosmeticslite.gadget.GadgetClientCommands;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.FormattedCharSequence;

import com.mojang.blaze3d.systems.RenderSystem; // for alpha tinting of the tiled panel texture

import javax.annotation.Nullable;
import java.util.*;

/**
 * Cosmetics UI (Forge 1.20.1 / 47.4.0)
 * Delegates to TabBar, GridView, MannequinPane, ParticlePreviewPane.
 * PETS tab shows Random Toggle + ColorWheelWidget + Variant dropdown.
 * GADGETS tab: shows pretty gadget name + description; no mannequin gadget FX preview.
 */
public class CosmeticsChestScreen extends Screen {
// --- Menu hold for active gadget FX (local to this screen) ---
private static boolean HOLD_REGISTERED = false;
private static int ACTIVE_HOLDS = 0;

static {
    if (!HOLD_REGISTERED) {
        HOLD_REGISTERED = true;
        com.pastlands.cosmeticslite.gadget.GadgetNet.ClientMenuHold.register(new com.pastlands.cosmeticslite.gadget.GadgetNet.ClientMenuHold.Listener() {
            @Override public void onHoldStart(String id) { ACTIVE_HOLDS++; }
            @Override public void onHoldEnd(String id)   { if (ACTIVE_HOLDS > 0) ACTIVE_HOLDS--; }
        });
    }
}

/** Only let the screen auto-return when no gadget effect is holding it. */
private static boolean canAutoReturn() {
    return ACTIVE_HOLDS == 0;
}

    // ---- Panel size ----
    private static final int GUI_WIDTH  = 392;
    private static final int GUI_HEIGHT = 300;

    // ---- Grid layout ----
    private static final int GRID_COLS  = 5;
    private static final int GRID_ROWS  = 4;     // 5 x 4
    private static final int SLOT_SIZE  = 28;
    private static final int SLOT_PAD_X = 5;
    private static final int SLOT_PAD_Y = 5;

    // Visible plate inside each slot (align highlights to this)
    private static final int SLOT_VIS_W  = 16;
    private static final int SLOT_VIS_H  = 16;
    private static final int SLOT_VIS_OX = 1;
    private static final int SLOT_VIS_OY = 1;

    // Tabs & chevrons
    private static final int TAB_W = 64, TAB_H = 20, TAB_GAP = 4;
    private static final int CHEVRON_W = 20, CHEVRON_H = 20;
    // ---- Hats filter chips ----
    private static final int CHIP_H = 18;
    private static final int CHIP_PAD_X = 6;
    private static final int CHIP_PAD_Y = 4;
    private static final int CHIP_GAP = 6;
    private List<FilterChip> hatChips = new ArrayList<>();
    private Set<String> selectedHatPacks = new LinkedHashSet<>(); // pack ids; "*" means ALL
    private int chipsLeft = 0, chipsTop = 0, chipsRight = 0;
    private static final String PACK_ALL = "*";

    private static final class FilterChip {
        final String label;
        final String packId; // "*" for ALL
        int x, y, w, h;
        boolean selected;
        FilterChip(String label, String packId) { this.label = label; this.packId = packId; }
        boolean contains(int mx, int my) { return mx >= x && mx <= x + w && my >= y && my <= y + h; }
    }


    // ===== Palette (ARGB) — Desaturated Industrial =====
    // Graphite foundation with muted blue-gray accents and parchment text.
    private static final int COL_OUTER_RIM    = 0xFF2A2A2E; // dark graphite frame
    private static final int COL_PANEL_BASE   = 0xFF3A3A3F; // soft neutral base
    private static final int COL_BEVEL_ACCENT = 0xE01F1F21; // ~88% opaque bevel
    private static final int COL_PANEL_INNER  = 0xB3515156; // ~70% inner tone

    // Tab strip — subdued steel-blue gradient for calm contrast
    private static final int COL_STRIP_OUTER  = 0xFF494B4E; // cool neutral steel
    private static final int COL_STRIP_INNER  = 0xFF8EA8BF; // dusty blue-gray inner

    // Grid frame — neutral metallic, unchanged for item contrast
    private static final int COL_GRID_RIM2    = 0xFF3E3A34;
    private static final int COL_GRID_RIM1    = 0xFF5A534A;
    private static final int COL_GRID_PLATE   = 0xFF4A4744;

    // Plaques (title + preview labels) — smooth industrial blues
    private static final int COL_PLAQUE_RIM   = 0xFF3E4650; // darker rim for depth
    private static final int COL_PLAQUE_BASE  = 0xFFB7C6D8; // pale bluish-steel base
    private static final int COL_PLAQUE_INNER = 0xFF9BAFC0; // inner blue-gray accent

    // Primary text — parchment warmth for readability
    private static final int COL_TEXT_PRIMARY = 0xFFD7CFA8;

    // Selection + pending highlights (kept neutral with subtle warmth)
    private static final int COL_SELECT_OUTER  = 0xFF4BA3FF; // blue edge highlight
    private static final int COL_SELECT_INNER  = 0x801B5FC2; // soft blue fill
    private static final int COL_PENDING_OUTER = 0xFFE0BD6A; // brass tone highlight
    private static final int COL_PENDING_INNER = 0x66E0BD6A;
    private static final int HILITE_BORDER_THICK = 1;

    // Status text + active tab glow (muted icy blue glow)
    private static final int COL_STATUS_TEXT   = 0xFFF0D060;
    private static final int COL_ACTIVE_TAB_GLOW = 0x403480C0; // subtle desaturated blue aura

    private static final int COL_SHADOW_NEAR = 0x22000000;
    private static final int COL_SHADOW_FAR  = 0x11000000;

    // Preview well (neutral-warm for model contrast)
    private static final int COL_PREVIEW_RIM2  = COL_GRID_RIM2;
    private static final int COL_PREVIEW_RIM1  = COL_GRID_RIM1;
    private static final int COL_PREVIEW_PLATE = 0xFF5A5550;

    // Optional custom PNG title (fallbacks to text if missing)
    private static final ResourceLocation TITLE_TEX =
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "textures/gui/cosmetics_title.png");
    private static final int TITLE_TEX_W = 110, TITLE_TEX_H = 14;

    // Subtle panel texture (32x32 tile), e.g. panel_text.png you added
    private static final ResourceLocation PANEL_TEX =
            ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "textures/gui/panel_text.png");
    private static final int PANEL_TILE = 32;

    // --- Decorative rivets (BlueJeans Edition)
    private static final int RIVET_SIZE       = 3;
    private static final int RIVET_CORE_COLOR = 0xFFE3C27A; // warm brass
    private static final int RIVET_RIM_COLOR  = 0xFF3B2E15; // deep bronze rim
    private static final int RIVET_HILITE     = 0xFFFFE7A8; // soft top highlight
    private static final int RIVET_SHADOW     = 0x66000000; // subtle drop shadow

    // ---- Derived at runtime ----
    private int left, top, right, bottom;

    // Grid bounds (left side)
    private int gridLeft, gridTop, gridW, gridH;

    // Preview well
    private static final int PREVIEW_W = 110;
    private static final int PREVIEW_H = 140;
    private int previewL, previewT, previewR, previewB;

    // ---- Widgets ----
    private Button leftBtn, rightBtn;
    private Button equipBtn, unequipBtn, clearBtn;

    // PETS extras
    private Button randomBtn;
    private ColorWheelWidget colorWheel;
    private boolean randomEnabled = false;
    private VariantDropdownWidget variantDropdown;
    private String lastVariantSpeciesKey = "";
    // geometry we use to rebuild the dropdown when species changes
    private int varDDX, varDDY, varDDW;

    // ---- Refactored parts ----
    private final ScreenState state = new ScreenState();
    private final TabBar tabBar = new TabBar();
    private final GridView grid = new GridView();
    private final MannequinPane mannequinPane = new MannequinPane();
    private final ParticlePreviewPane particlePane = new ParticlePreviewPane();

    // Slot renderer uses CosmeticGuiSlot; it captures the screen's last render mouse.
    private double lastMouseX = 0;
    private double lastMouseY = 0;
    private float  lastPartial = 0;

    public CosmeticsChestScreen() { super(Component.literal("Cosmetics")); }

    public enum Category {
        PARTICLES("particles"), HATS("hats"), CAPES("capes"), PETS("pets"), GADGETS("gadgets");
        final String key; Category(String k){ this.key = k; } String key(){ return key; }
    }

    private static String typeKey(Category c) { return c == null ? "particles" : c.key(); }
    private static boolean isAir(ResourceLocation id) {
        return id == null || ("minecraft".equals(id.getNamespace()) && "air".equals(id.getPath()));
    }

    // ========================================================================
    // Init & layout
    // ========================================================================
    @Override
    protected void init() {
        super.init();
        ensureRegistryPrimedAtInit();

        left   = (this.width  - GUI_WIDTH)  / 2;
        top    = (this.height - GUI_HEIGHT) / 2;
        right  = left + GUI_WIDTH;
        bottom = top  + GUI_HEIGHT;

        // ----- Layout -----
        final int innerL = left  + 14;
        final int innerR = right - 14;

        // Grid geometry
        gridW = GRID_COLS * SLOT_SIZE;
        gridH = GRID_ROWS * SLOT_SIZE;
        gridLeft = innerL + 8;

        // Push the content (grid + preview) farther from the tabs
        final int headerSpace = 76;
        final int footerSpace = 70;
        final int availH = GUI_HEIGHT - headerSpace - footerSpace;
        gridTop  = top + headerSpace + Math.max(0, (availH - gridH) / 2);

        // Preview well on the right, centered vertically to the grid
        final int minGutterPx = CHEVRON_W * 2 + 10;
        final int framePad    = 5;
        final int previewGap  = minGutterPx + framePad * 2;

        previewR = innerR - 6;
        previewL = previewR - PREVIEW_W;
        previewT = gridTop + (gridH - PREVIEW_H) / 2;
        previewB = previewT + PREVIEW_H;

        if (gridLeft + gridW + previewGap > previewL) {
            gridLeft = Math.max(innerL + 6, previewL - previewGap - gridW);
        }

        // ---- Build screen parts ----
        initFromServerState();
        buildWidgets();
        buildTabBar();
        buildHatFilterChips();
        buildGrid();
        buildPreviewPanes();

        rebuildGrid();
        updateActionButtons();

        // ---- Always build PETS extras, then control visibility ----
        buildPetsExtras();

        boolean petsActive = "pets".equals(state.getActiveType());
        if (randomBtn != null) randomBtn.visible = petsActive;
        if (colorWheel != null) {
            colorWheel.visible = petsActive;
            colorWheel.active  = petsActive && !randomEnabled;
        }
        if (variantDropdown != null) variantDropdown.visible = petsActive;

        refreshVariantDropdown();
        // Ensure PETS extras (random toggle + wheel enable state) reflect saved prefs on first open
        updatePetsUiFromPrefs();
    }

    private VariantDropdownWidget createVariantDropdown(int x, int y, int w) {
        return addRenderableWidget(new VariantDropdownWidget(
                x, y, w, 16, Collections.emptyList(),
                choice -> {
                    if (choice != null) {
                        setStatus("Variant: " + choice.label.getString());

                        // remember per-pet variant locally
                        String species = currentPetSpecies();
                        if (!species.isEmpty()) {
                            ClientState.setPetVariantPref(species, choice.key);
                        }

                        // send to server
                        CosmeticsLite.NETWORK.sendToServer(new PacketSetPetVariant(choice.key, false));
                    } else {
                        setStatus("Variant: none");
                    }
                }
        ));
    }

    // Known pack order for the Hats grid; unknown packs fall after these, preserving original order.
    // You can tweak this list to whatever grouping you want to see first.
    private static final List<String> HAT_PACK_ORDER = java.util.Arrays.asList(
            "base", "animal", "fantasy", "food"
    );

    // Rank packs for grouping; unknown packs get a large rank so they appear after known groups.
    // Comparator is stable, so items within the same pack keep their current order.
    private int packRank(String pack) {
        if (pack == null) return Integer.MAX_VALUE - 1;
        int idx = HAT_PACK_ORDER.indexOf(pack.toLowerCase(java.util.Locale.ROOT));
        return (idx >= 0) ? idx : (HAT_PACK_ORDER.size() + 1);
    }

    // -----------------------------------------------------------------
    // Registry priming helper
    // -----------------------------------------------------------------
    private void ensureRegistryPrimedAtInit() {
        boolean any = !CosmeticsRegistry.getByType("particles").isEmpty()
                || !CosmeticsRegistry.getByType("hats").isEmpty()
                || !CosmeticsRegistry.getByType("capes").isEmpty()
                || !CosmeticsRegistry.getByType("pets").isEmpty()
                || !CosmeticsRegistry.getByType("gadgets").isEmpty();
        if (!any) CosmeticsRegistry.replaceAll(Collections.emptyList(), true);
    }

    // ========================================================================
    // Server state initialization
    // ========================================================================
    private void initFromServerState() {
        Category active = Category.PARTICLES;
        for (Category c : Category.values()) {
            ResourceLocation id = ClientState.getEquippedId(typeKey(c));
            if (!isAir(id)) { active = c; break; }
        }
        state.setActiveType(typeKey(active));

        ResourceLocation eq = ClientState.getEquippedId(state.getActiveType());
        if (!isAir(eq)) {
            List<CosmeticDef> list = currentSource();
            for (int i = 0; i < list.size(); i++) {
                if (eq.equals(list.get(i).id())) {
                    state.setPerPage(GRID_COLS * GRID_ROWS);
                    state.setCurrentPage(i / state.getPerPage());
                    state.setSelectedIndex(state.getActiveType(), i);
                    break;
                }
            }
        } else {
            state.clearAllHighlights();
        }
    }

    // ========================================================================
    // Widgets
    // ========================================================================
    private void buildWidgets() {
        this.clearWidgets();

        final int FRAME_PAD  = 5;
        final int GUTTER_PAD = 4;

        int chevY   = gridTop + (gridH - CHEVRON_H) / 2;

        leftBtn  = addRenderableWidget(
                Button.builder(Component.literal("<"), b -> setPage(state.getCurrentPage() - 1))
                        .pos(gridLeft + gridW + FRAME_PAD + GUTTER_PAD, chevY)
                        .size(CHEVRON_W, CHEVRON_H).build());
        rightBtn = addRenderableWidget(
                Button.builder(Component.literal(">"), b -> setPage(state.getCurrentPage() + 1))
                        .pos(previewL - FRAME_PAD - GUTTER_PAD - CHEVRON_W, chevY)
                        .size(CHEVRON_W, CHEVRON_H).build());

        // Bottom action row — 3 buttons (Equip | Unequip | Clear)
        final int sideMargin = 12;
        final int gap = 8;
        final int btnH = 18;

        int innerW = GUI_WIDTH - sideMargin * 2;
        int btnW   = (innerW - gap * 2) / 3; // 3 columns
        int baseX  = left + sideMargin;
        int baseY  = bottom - 34;

        equipBtn = addRenderableWidget(
                Button.builder(Component.literal("Equip"), b -> onEquipClicked())
                        .pos(baseX, baseY).size(btnW, btnH).build());

        unequipBtn = addRenderableWidget(
                Button.builder(Component.literal("Unequip"), b -> onUnequipThisTab())
                        .pos(baseX + (btnW + gap), baseY).size(btnW, btnH).build());
        clearBtn = addRenderableWidget(
                Button.builder(Component.literal("Clear Equipped"), b -> onClearEquipped())
                        .pos(baseX + (btnW + gap) * 2, baseY).size(btnW, btnH).build());
    }

    // ========================================================================
    // PETS-only extras
    // ========================================================================
    private void buildPetsExtras() {
        if (leftBtn == null || rightBtn == null) return;

        int chevMidX = (leftBtn.getX() + rightBtn.getX() + CHEVRON_W) / 2;
        int chevY    = leftBtn.getY();

        // Random Toggle (above chevrons)
        randomBtn = addRenderableWidget(Button.builder(Component.literal("Random: OFF"), b -> {
            randomEnabled = !randomEnabled;
            randomBtn.setMessage(Component.literal(randomEnabled ? "Random: ON" : "Random: OFF"));

            if (colorWheel != null) {
                colorWheel.active = !randomEnabled;
            }

            int rgb = (colorWheel != null) ? colorWheel.getCurrentColor() : 0xFFFFFF;

            // remember per-pet color/random locally
            String species = currentPetSpecies();
            if (!species.isEmpty()) {
                ClientState.setPetColorPref(species, rgb, randomEnabled);
            }

            CosmeticsLite.NETWORK.sendToServer(new PacketSetPetColor(rgb, randomEnabled));
        }).pos(chevMidX - 40, chevY - 34).size(80, 18).build());

        // Color Wheel (below chevrons)
        colorWheel = addRenderableWidget(new ColorWheelWidget(chevMidX - 28, chevY + CHEVRON_H + 6, 56) {
            @Override
            protected void onColorSelected(int rgb) {
                if (!randomEnabled) {
                    // remember per-pet color locally (non-random path)
                    String species = currentPetSpecies();
                    if (!species.isEmpty()) {
                        ClientState.setPetColorPref(species, rgb, false);
                    }
                    CosmeticsLite.NETWORK.sendToServer(new PacketSetPetColor(rgb, false));
                }
            }
        });
        colorWheel.active = !randomEnabled;

        // Variant dropdown (below color wheel) — widened & centered
        int ddW = 132;
        int ddX = ((leftBtn.getX() + rightBtn.getX() + CHEVRON_W) / 2) - (ddW / 2);
        int ddY = colorWheel.getY() + 56 + 8;

        // remember geometry so we can recreate the widget when species changes
        this.varDDX = ddX;
        this.varDDY = ddY;
        this.varDDW = ddW;

        // create the initial dropdown via helper
        this.variantDropdown = createVariantDropdown(varDDX, varDDY, varDDW);
    }

    private void buildTabBar() {
        int stripL = left + 10, stripR = right - 10, stripT = top + 26, stripB = stripT + 28;
        tabBar.setStripBounds(stripL, stripT, stripR, stripB);
        tabBar.setTabGeometry(TAB_W, TAB_H, TAB_GAP);
        tabBar.setColors(COL_STRIP_OUTER, COL_STRIP_INNER, COL_PANEL_BASE, COL_PANEL_INNER, COL_ACTIVE_TAB_GLOW, COL_TEXT_PRIMARY);
        tabBar.setTabs(List.of(
                new TabBar.Tab("particles", "Particles"),
                new TabBar.Tab("hats",      "Hats"),
                new TabBar.Tab("capes",     "Capes"),
                new TabBar.Tab("pets",      "Pets"),
                new TabBar.Tab("gadgets",   "Gadgets")
        ));
        tabBar.setActiveKey(state.getActiveType());
        tabBar.setOnChange(key -> {
            state.setActiveType(key);
            state.setCurrentPage(0);
            state.clearSelection(key);
            rebuildGrid();
            updateActionButtons();

            // Enabled persistently; pane self-gates in its own logic
            particlePane.setEnabled(true);

            // Toggle visibility of PETS extras when switching tabs
            boolean petsActive = "pets".equals(key);
            if (randomBtn != null) randomBtn.visible = petsActive;
            if (colorWheel != null) {
                colorWheel.visible = petsActive;
                colorWheel.active  = petsActive && !randomEnabled;
            }
            if (variantDropdown != null) {
                variantDropdown.visible = petsActive;
            }

            refreshVariantDropdown();
            // NEW: also sync Random toggle + wheel enabled state to the CURRENT pet
            updatePetsUiFromPrefs();

            // Rebuild hat filter chips when switching tabs
            buildHatFilterChips();
        });

    }

    private void buildGrid() {
        grid.setFrame(gridLeft, gridTop);
        grid.setGridGeometry(GRID_COLS, GRID_ROWS, SLOT_SIZE, SLOT_PAD_X, SLOT_PAD_Y);
        grid.setVisiblePlate(SLOT_VIS_W, SLOT_VIS_H, SLOT_VIS_OX, SLOT_VIS_OY);
        grid.setFrameColors(COL_GRID_RIM2, COL_GRID_RIM1, COL_GRID_PLATE);
        grid.setHighlightColors(COL_SELECT_INNER, COL_SELECT_OUTER, COL_PENDING_INNER, COL_PENDING_OUTER, HILITE_BORDER_THICK);

        grid.setSlotRenderer((g, slotX, slotY, visX, visY, globalIndex, def, isEquipped, isSelected) -> {
            CosmeticGuiSlot slot = new CosmeticGuiSlot(new CosmeticSlot(def, slotX, slotY));
            slot.render(g, (int) lastMouseX, (int) lastMouseY, lastPartial);
        });

        grid.setOnSelect(globalIndex -> {
            state.setSelectedIndex(state.getActiveType(), globalIndex);
            updateActionButtons();
            if ("pets".equals(state.getActiveType())) {
                refreshVariantDropdown();
            }
        });
        grid.setOnPlaceholder((local, page) -> {
            state.setSelectedIndex(state.getActiveType(), -1);
            updateActionButtons();
            if ("pets".equals(state.getActiveType())) {
                refreshVariantDropdown();
            }
        });
    }

    private void buildPreviewPanes() {
        particlePane.setBounds(previewL, previewT, previewR, previewB);
        particlePane.setEffectSource(this::resolvePreviewEffectId);

        mannequinPane.setBounds(previewL, previewT, previewR, previewB);
        mannequinPane.setPreviewScope(new MannequinPane.PreviewScope() {
            @Override
            public void begin(LocalPlayer mannequin, Map<String, ResourceLocation> overrides) {
                PreviewResolver.setMannequin(mannequin);
                PreviewResolver.begin(mannequin, overrides);
            }
            @Override
            public void end() { PreviewResolver.end(); }
        });
        mannequinPane.setOverridesSupplier(this::resolvePreviewMap);
    }

    private void refreshVariantDropdown() {
        if (variantDropdown == null) return;

        boolean petsActive = "pets".equals(state.getActiveType());
        variantDropdown.visible = petsActive;
        if (!petsActive) return;

        // Decide which pet to show options for: selected on PETS, else currently equipped
        CosmeticDef def = null;
        CosmeticDef selected = getSelectedDefForType("pets");
        if (selected != null) {
            def = selected;
        } else {
            LocalPlayer p = Minecraft.getInstance().player;
            ResourceLocation petId = (p != null) ? ClientState.getEquippedId(p, "pets")
                    : ClientState.getEquippedId("pets");
            if (petId != null && !isAir(petId)) def = CosmeticsRegistry.get(petId);
        }

        String species = (def != null) ? speciesFromId(def.id()) : "";
        java.util.List<VariantDropdownWidget.VariantOption> options = java.util.Collections.emptyList();
        if (def != null) {
            options = com.pastlands.cosmeticslite.client.screen.parts.PetVariantOptions.forPet(def.id());
        }

        // If the species changed since last time, rebuild the widget to clear stale state
        if (!java.util.Objects.equals(species, lastVariantSpeciesKey)) {
            try { this.removeWidget(variantDropdown); } catch (Throwable ignored) {}
            variantDropdown = createVariantDropdown(varDDX, varDDY, varDDW);
            lastVariantSpeciesKey = species;
        }

        if (options.isEmpty()) {
            variantDropdown.setOptions(java.util.Collections.emptyList());
            variantDropdown.setActive(false);
            setStatus("");              // clear "Variant: ..." label
            updatePetsUiFromPrefs();    // still sync random/wheel
            return;
        }

        // Float saved preference (if any) to top so it becomes the visible choice
        if (!species.isEmpty()) {
            String pref = ClientState.getPetVariantPref(species);
            if (pref != null) {
                final String prefKey = pref;
                options.sort((a, b) -> a.key.equals(prefKey) ? -1 : (b.key.equals(prefKey) ? 1 : 0));
            }
        }

        variantDropdown.setOptions(options);
        variantDropdown.setActive(true);

        // Status line reflects current "top" (either pref or first option)
        setStatus("Variant: " + options.get(0).label.getString());

        // also sync Random toggle + wheel for this species
        updatePetsUiFromPrefs();
    }

    /** Parrot color variants (unchanged). */
    private static List<VariantDropdownWidget.VariantOption> buildParrotVariantOptions() {
        List<VariantDropdownWidget.VariantOption> list = new ArrayList<>();
        list.add(new VariantDropdownWidget.VariantOption("red",    Component.literal("Red")));
        list.add(new VariantDropdownWidget.VariantOption("blue",   Component.literal("Blue")));
        list.add(new VariantDropdownWidget.VariantOption("green",  Component.literal("Green")));
        list.add(new VariantDropdownWidget.VariantOption("yellow", Component.literal("Yellow")));
        list.add(new VariantDropdownWidget.VariantOption("gray",   Component.literal("Gray")));
        return list;
    }

    /** Cat skin variants (vanilla set, 1.20.1). Keys align to common names. */
    private static List<VariantDropdownWidget.VariantOption> buildCatVariantOptions() {
        List<VariantDropdownWidget.VariantOption> list = new ArrayList<>();
        list.add(new VariantDropdownWidget.VariantOption("tabby",               Component.literal("Tabby")));
        list.add(new VariantDropdownWidget.VariantOption("black",               Component.literal("Black")));
        list.add(new VariantDropdownWidget.VariantOption("red",                 Component.literal("Red")));
        list.add(new VariantDropdownWidget.VariantOption("siamese",             Component.literal("Siamese")));
        list.add(new VariantDropdownWidget.VariantOption("british_shorthair",   Component.literal("British Shorthair")));
        list.add(new VariantDropdownWidget.VariantOption("calico",              Component.literal("Calico")));
        list.add(new VariantDropdownWidget.VariantOption("persian",             Component.literal("Persian")));
        list.add(new VariantDropdownWidget.VariantOption("ragdoll",             Component.literal("Ragdoll")));
        list.add(new VariantDropdownWidget.VariantOption("white",               Component.literal("White")));
        list.add(new VariantDropdownWidget.VariantOption("jellie",              Component.literal("Jellie")));
        list.add(new VariantDropdownWidget.VariantOption("all_black",           Component.literal("All Black")));
        return list;
    }

    /** Fox variants (vanilla 1.20.1). Keys align to Fox.Type constants. */
    private static List<VariantDropdownWidget.VariantOption> buildFoxVariantOptions() {
        List<VariantDropdownWidget.VariantOption> list = new ArrayList<>();
        list.add(new VariantDropdownWidget.VariantOption("red",  Component.literal("Red")));
        list.add(new VariantDropdownWidget.VariantOption("snow", Component.literal("Snow")));
        return list;
    }

    private void setPage(int page) {
        state.setPerPage(GRID_COLS * GRID_ROWS);
        state.setCurrentPage(page);
        rebuildGrid();
        updateActionButtons();
    }

    private List<CosmeticDef> currentSource() {
        String type = state.getActiveType();
        List<CosmeticDef> list = CosmeticsRegistry.getByType(type);

        if ("hats".equals(type)) {
            List<CosmeticDef> filtered = new ArrayList<>();

            Set<String> packs = new LinkedHashSet<>();
            if (selectedHatPacks.isEmpty() || selectedHatPacks.contains(PACK_ALL)) {
                // show all
            } else {
                packs.addAll(selectedHatPacks);
            }

            for (CosmeticDef d : list) {
                String p = d.pack();
                if (!packs.isEmpty() && !packs.contains(p)) continue;
                filtered.add(d);
            }

            // Group by pack using a stable sort
            filtered.sort(new java.util.Comparator<>() {
                @Override
                public int compare(CosmeticDef a, CosmeticDef b) {
                    int ra = packRank(a.pack());
                    int rb = packRank(b.pack());
                    return Integer.compare(ra, rb);
                }
            });

            return filtered;
        }

        return list;
    }

    private void rebuildGrid() {
        List<CosmeticDef> src = currentSource();
        state.setPerPage(GRID_COLS * GRID_ROWS);

        grid.setData(src);
        grid.setCurrentPage(state.getCurrentPage());

        // Prevent pre-selection highlight on gadgets tab
        if ("gadgets".equals(state.getActiveType())) {
            state.clearSelection("gadgets");
            grid.setSelectedGlobalIndex(-1);
        } else {
            grid.setSelectedGlobalIndex(state.getSelectedIndex(state.getActiveType()));
        }

        grid.setEquippedId(ClientState.getEquippedId(state.getActiveType()));

        int totalPages = Math.max(1, (int) Math.ceil(src.size() / (double) state.getPerPage()));
        state.setTotalPages(totalPages);
        if (leftBtn  != null) leftBtn.active  = state.getCurrentPage() > 0;
        if (rightBtn != null) rightBtn.active = state.getCurrentPage() < totalPages - 1;

        // NEW: whenever the grid is rebuilt while PETS is active,
        // refresh the dropdown options AND sync the random toggle/wheel state.
        if ("pets".equals(state.getActiveType())) {
            refreshVariantDropdown();
            updatePetsUiFromPrefs();
        }
    }

    // ========================================================================
    // Actions
    // ========================================================================
    private void onEquipClicked() {
        CosmeticDef def = getCurrentlySelectedDef();
        if (def == null) return;

        String type = state.getActiveType();
        ResourceLocation newId = def.id();
        ResourceLocation currentId = ClientState.getEquippedId(type);

        // --- FIX: send only ONE equip packet. Do NOT pre-clear. ---
        if (isAir(newId) || newId.equals(currentId)) {
            // nothing to do
            return;
        }

        PacketEquipRequest.send(type, newId, -1, -1, new CompoundTag());
        ClientState.setEquippedId(type, newId);

        state.clearAllHighlights();
        rebuildGrid();
        updateActionButtons();

        // If we just equipped a PET, refresh its Variant dropdown and color/random UI
        if ("pets".equals(type)) {
            state.clearSelection("pets");
            refreshVariantDropdown();
            updatePetsUiFromPrefs();
            state.setStatus("", 0);
            // if (variantDropdown != null) variantDropdown.collapse();
        }

        // If we just equipped a GADGET, close Cosmetics and arm the 2s countdown → fire → reopen flow
        if ("gadgets".equals(type)) {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                mc.setScreen(null); // close Cosmetics now
                // 40 ticks ≈ 2 seconds (keep this buffer so it doesn't fire instantly)
                GadgetClientCommands.scheduleUseFromCosmetics(newId, 40);
            });
        }
    }

    private void onUnequipThisTab() {
        String type = state.getActiveType();
        ResourceLocation air = ResourceLocation.fromNamespaceAndPath("minecraft", "air");

        PacketEquipRequest.send(type, air, -1, -1, new CompoundTag());
        ClientState.setEquippedId(type, air);

        state.clearSelection(type);
        rebuildGrid();
        updateActionButtons();

        // keep PETS side-panel in sync when unequipping on the Pets tab
        if ("pets".equals(type)) {
            refreshVariantDropdown();
            updatePetsUiFromPrefs();
            state.setStatus("", 0);
            // if (variantDropdown != null) variantDropdown.collapse();
        }
    }

    private void onClearEquipped() {
        ResourceLocation air = ResourceLocation.fromNamespaceAndPath("minecraft", "air");

        // Send one packet to clear everything
        PacketEquipRequest.sendClearAll(air);
        ClientState.clearAll();

        state.clearAllHighlights();
        rebuildGrid();
        updateActionButtons();
    }

    private CosmeticDef getCurrentlySelectedDef() {
        int sel = state.getSelectedIndex(state.getActiveType());
        if (sel < 0) return null;
        List<CosmeticDef> list = currentSource();
        return (sel >= 0 && sel < list.size()) ? list.get(sel) : null;
    }

    private CosmeticDef getSelectedDefForType(String typeKey) {
        int sel = state.getSelectedIndex(typeKey);
        if (sel < 0) return null;

        // IMPORTANT: on the Hats tab the grid is using a filtered/sorted view.
        // The preview must look up the selection from the same source, not the raw registry.
        List<CosmeticDef> list =
                ("hats".equals(typeKey) && typeKey.equals(state.getActiveType()))
                        ? currentSource()                           // filtered + grouped view used by the grid
                        : CosmeticsRegistry.getByType(typeKey);     // others unchanged

        return (sel >= 0 && sel < list.size()) ? list.get(sel) : null;
    }

    private int findEquippedIndexIn(List<CosmeticDef> src, String typeKey) {
        ResourceLocation eqId = ClientState.getEquippedId(typeKey);
        if (isAir(eqId)) return -1;
        for (int i = 0; i < src.size(); i++) if (eqId.equals(src.get(i).id())) return i;
        return -1;
    }

    private void updateActionButtons() {
        if (equipBtn == null || unequipBtn == null || clearBtn == null) return;

        String type = state.getActiveType();
        List<CosmeticDef> src = currentSource();
        int equippedIndex = findEquippedIndexIn(src, type);

        CosmeticDef selected = getCurrentlySelectedDef();
        ResourceLocation equippedId = ClientState.getEquippedId(type);
        boolean selectionIsEquipped = selected != null && !isAir(equippedId) && selected.id().equals(equippedId);

        // Equip: active if something is selected and it’s not already equipped
        equipBtn.active = selected != null && !selectionIsEquipped;

        // Clear Equipped: active only if anything is equipped across categories
        boolean anyEquipped = false;
        for (Category c : Category.values()) {
            if (!isAir(ClientState.getEquippedId(typeKey(c)))) {
                anyEquipped = true;
                break;
            }
        }
        clearBtn.active = anyEquipped;

        // Unequip: active if there’s something equipped in the current tab
        unequipBtn.active = !isAir(equippedId);
    }

    private void setStatus(String msg) { state.setStatus(msg, 60); }

    // ========================================================================
    // Input
    // ========================================================================
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Handle clicks on hat filter chips
        if ("hats".equals(state.getActiveType())) {
            int mx = (int) mouseX, my = (int) mouseY;
            boolean anyHit = false;
            for (FilterChip c : hatChips) {
                if (c.contains(mx, my)) {
                    anyHit = true;
                    if (PACK_ALL.equals(c.packId)) {
                        // Select ALL -> deselect others
                        for (FilterChip x : hatChips) x.selected = PACK_ALL.equals(x.packId);
                        selectedHatPacks.clear();
                        selectedHatPacks.add(PACK_ALL);
                    } else {
                        // Toggle individual
                        c.selected = !c.selected;
                        // Update set
                        selectedHatPacks.clear();
                        for (FilterChip x : hatChips) if (x.selected && !PACK_ALL.equals(x.packId)) selectedHatPacks.add(x.packId);
                        if (selectedHatPacks.isEmpty()) {
                            // fallback to ALL
                            for (FilterChip x : hatChips) x.selected = PACK_ALL.equals(x.packId);
                            selectedHatPacks.add(PACK_ALL);
                        } else {
                            // ensure "All" is off
                            for (FilterChip x : hatChips) if (PACK_ALL.equals(x.packId)) x.selected = false;
                        }
                    }
                    // refresh grid with new filter
                    rebuildGrid();
                    break;
                }
            }
            if (anyHit) return true;
        }

        // Give dropdown first chance to eat clicks
        if (variantDropdown != null && variantDropdown.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        if (randomBtn != null && randomBtn.mouseClicked(mouseX, mouseY, button)) return true;
        if (colorWheel != null && colorWheel.mouseClicked(mouseX, mouseY, button)) return true;

        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (mannequinPane.mousePressed(mouseX, mouseY, button)) return true;
        if (grid.mouseClicked(mouseX, mouseY, button)) return true;
        if (tabBar.mouseClicked(mouseX, mouseY, button)) return true;

        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (mannequinPane.mouseReleased(mouseX, mouseY, button)) return true;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dX, double dY) {
        if (mannequinPane.mouseDragged(mouseX, mouseY, button, dX, dY)) return true;
        return super.mouseDragged(mouseX, mouseY, button, dX, dY);
    }

    @Override public boolean isPauseScreen() { return false; }

    // ========================================================================
    // Tick & Render
    // ========================================================================
    @Override
    public void tick() {
        super.tick();
        if (particlePane != null) particlePane.tick();
        if (mannequinPane != null) mannequinPane.tick();

        // Watchdog: keep PETS side-panel in sync even if a UI path missed a refresh.
        if ("pets".equals(state.getActiveType())) {
            // Determine the currently “effective” pet: selected on the grid, otherwise equipped.
            CosmeticDef def = getSelectedDefForType("pets");
            if (def == null) {
                LocalPlayer p = Minecraft.getInstance().player;
                ResourceLocation petId = (p != null) ? ClientState.getEquippedId(p, "pets")
                        : ClientState.getEquippedId("pets");
                if (petId != null && !isAir(petId)) def = CosmeticsRegistry.get(petId);
            }
            String species = (def != null) ? speciesFromId(def.id()) : "";

            // If species changed since the last dropdown build, rebuild/sync it now.
            if (!Objects.equals(species, lastVariantSpeciesKey)) {
                refreshVariantDropdown();      // also calls updatePetsUiFromPrefs()
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(g);

        // HOVER MASK for dropdown
        int maskedX = mouseX, maskedY = mouseY;
        if (variantDropdown != null && variantDropdown.visible && variantDropdown.isExpanded()
                && variantDropdown.isMouseOver(mouseX, mouseY)) {
            maskedX = Integer.MIN_VALUE / 4;
            maskedY = Integer.MIN_VALUE / 4;
        }

        this.lastMouseX = maskedX;
        this.lastMouseY = maskedY;
        this.lastPartial = partialTicks;

// ======================= PANEL BACKGROUND =======================
drawSoftRoundedShadow(g, left, top, right, bottom);

// Outer frame and base
fillRounded(g, left - 2, top - 2, right + 2, bottom + 2, 8, COL_OUTER_RIM);
fillRounded(g, left,     top,     right,     bottom,     8, COL_PANEL_BASE);

// Inner bevel and plate (flat tone first)
fillRounded(g, left + 4, top + 4, right - 4, bottom - 4, 6, COL_BEVEL_ACCENT);
fillRounded(g, left + 6, top + 6, right - 6, bottom - 6, 6, COL_PANEL_INNER);

// Subtle tiled texture overlay — higher alpha so the pattern reads
RenderSystem.enableBlend();
RenderSystem.defaultBlendFunc();
RenderSystem.setShaderColor(1f, 1f, 1f, 0.45f); // was ~0.28f; try 0.42–0.50
blitTiled(g, PANEL_TEX, left + 6, top + 6, right - 6, bottom - 6, PANEL_TILE);
RenderSystem.setShaderColor(1f, 1f, 1f, 1f);    // reset

// Very light neutral "wash" to unify while keeping the texture visible
// (12% white — brighten the pattern slightly without killing contrast)
fillRounded(g, left + 6, top + 6, right - 6, bottom - 6, 6, 0x1FFFFFFF);


        // ======================= HEADER PLAQUE =======================
        final int PLAQUE_MIN_W = 124;
        final int PLAQUE_H     = 16;
        final int SIDE_PAD     = 7;

        Minecraft mc = this.minecraft;
        boolean hasPng = mc != null && mc.getResourceManager().getResource(TITLE_TEX).isPresent();

        int contentW = hasPng ? TITLE_TEX_W : this.font.width(this.title);
        int plaqueW  = Math.max(PLAQUE_MIN_W, contentW + SIDE_PAD * 2);

        int plaqueL = (this.width - plaqueW) / 2;
        int plaqueT = top + 8;
        int plaqueR = plaqueL + plaqueW;
        int plaqueB = plaqueT + PLAQUE_H;

        fillRounded(g, plaqueL - 1, plaqueT - 1, plaqueR + 1, plaqueB + 1, 4, COL_PLAQUE_RIM);
        fillRounded(g, plaqueL,     plaqueT,     plaqueR,     plaqueB,     4, COL_PLAQUE_BASE);
        fillRounded(g, plaqueL + 1, plaqueT + 1, plaqueR - 1, plaqueB - 1, 3, COL_PLAQUE_INNER);

        if (hasPng) {
            int x = (this.width - TITLE_TEX_W) / 2;
            int y = plaqueT + (PLAQUE_H - TITLE_TEX_H) / 2;
            g.blit(TITLE_TEX, x, y, 0, 0, TITLE_TEX_W, TITLE_TEX_H, TITLE_TEX_W, TITLE_TEX_H);
        } else {
            drawCenteredShadow(g, this.title, this.width / 2, plaqueT + 3, COL_TEXT_PRIMARY);
        }

        // ======================= CONTENT =======================
        tabBar.render(g);
        grid.render(g, maskedX, maskedY, partialTicks);

        fillRounded(g, previewL - 5, previewT - 5, previewR + 5, previewB + 5, 6, COL_PREVIEW_RIM2);
        fillRounded(g, previewL - 4, previewT - 4, previewR + 4, previewB + 4, 6, COL_PREVIEW_RIM1);
        fillRounded(g, previewL - 3, previewT - 3, previewR + 3, previewB + 3, 5, COL_PREVIEW_PLATE);

        final int labelPadX = 5;
        final int labelH    = 12;

        // ---- Gadget-aware caption in preview well (high-contrast & clamped) ----
        ResourceLocation gadgetIdForLabel = resolveSelectedOrEquippedGadget();
        String titleText;
        String descText = "";

        if (gadgetIdForLabel != null) {
            titleText = com.pastlands.cosmeticslite.gadget.GadgetClientCommands.displayName(gadgetIdForLabel);
            descText  = com.pastlands.cosmeticslite.gadget.GadgetClientCommands.shortDescription(gadgetIdForLabel);
        } else {
            titleText = "Preview";
        }

        Component lbl = Component.literal(titleText);
        int textW   = this.font.width(lbl);
        int capW    = Math.max(64, Math.min(PREVIEW_W - 12, textW + labelPadX * 2)); // clamp within pane
        int capL    = Math.max(previewL + 6, (previewL + previewR - capW) / 2);
        int capT    = previewT + 4;
        int capR    = capL + capW;
        int capB    = capT + labelH;

        fillRounded(g, capL,     capT,     capR,     capB,     6, COL_PLAQUE_RIM);
        fillRounded(g, capL + 1, capT + 1, capR - 1, capB - 1, 5, COL_PLAQUE_BASE);
        fillRounded(g, capL + 2, capT + 2, capR - 2, capB - 2, 4, COL_PLAQUE_INNER);

        // Use bright text + dropshadow for readability
        int titleColor = 0xFFEDEFF2;
        g.drawString(this.font, lbl, capL + (capW - textW) / 2, capT + 2, titleColor, /*dropShadow=*/true);

        // Description: wrap inside the preview pane, left-aligned under plaque
        if (gadgetIdForLabel != null && !descText.isEmpty()) {
            int textAreaL = previewL + 8;
            int textAreaR = previewR - 8;
            int maxWidth  = Math.max(40, textAreaR - textAreaL);

            java.util.List<FormattedCharSequence> lines =
                    this.font.split(Component.literal(descText), maxWidth);

            int y = capB + 4;
            int maxLines = 3; // avoid overflow
            int drawn = 0;
            for (var seq : lines) {
                if (y > previewB - 12 || drawn >= maxLines) break;
                g.drawString(this.font, seq, textAreaL, y, 0xFFC9D1D9, /*dropShadow=*/true);
                y += 10;
                drawn++;
            }
        }

        // Let panes decide if they render (Particles tab or equipped effect)
        mannequinPane.render(g);
        particlePane.render(g);

        // Render base widgets with masked mouse when dropdown overlaps
        super.render(g, maskedX, maskedY, partialTicks);

        // --- Cooldown label (only on Gadgets tab), centered above the bottom buttons ---
        if ("gadgets".equals(state.getActiveType())) {
            // Buttons sit at baseY = bottom - 34; put the label just above them.
            int centerX = (left + right) / 2;
            int labelY  = (equipBtn != null ? equipBtn.getY() - 12 : bottom - 52);
            renderGadgetCooldown(g, centerX, labelY);
        }

        // Render dropdown last, so it sits on top of all buttons
        if (variantDropdown != null) {
            variantDropdown.renderOnTop(g, mouseX, mouseY, partialTicks);
        }
		// Finally: draw the tiny corner gears absolutely last
           drawCornerGears(g, left, top, right, bottom, partialTicks);
    }

    private void drawCenteredShadow(GuiGraphics g, Component text, int centerX, int y, int color) {
        int w = this.font.width(text);
        g.drawString(this.font, text, centerX - w / 2, y, color, true);
    }

    @Nullable
    private ResourceLocation resolveSelectedOrEquippedGadget() {
        if (!"gadgets".equals(state.getActiveType())) return null;
        CosmeticDef sel = getCurrentlySelectedDef();
        if (sel != null) return sel.id();
        ResourceLocation eq = ClientState.getEquippedId("gadgets");
        return isAir(eq) ? null : eq;
    }
// -------------------- Tiny rotating gears (corner ornaments) --------------------
private static final int GEAR_COLOR_TEETH = 0xFFB7BFC8; // desaturated steel
private static final int GEAR_COLOR_HUB   = 0xFF8EA8BF; // soft blue-gray

/** Draw a simple 2D gear by alternating inner/outer radii around a circle. */
private void drawGear(GuiGraphics g,
                      float cx, float cy,
                      float rInner, float rOuter,
                      int teeth, float radians,
                      int colorTeeth, int colorHub) {

    // ---- sanity ----
    if (teeth < 3) return;
    if (rInner <= 0 || rOuter <= 0) return;
    if (rInner >= rOuter) rInner = Math.max(1f, rOuter * 0.7f);

    // ---- render state: draw above UI, with alpha, no culling ----
    RenderSystem.disableDepthTest();
    RenderSystem.enableBlend();
    RenderSystem.defaultBlendFunc();
    RenderSystem.disableCull();
    RenderSystem.setShader(GameRenderer::getPositionColorShader);
    RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

    var pose = g.pose();
    pose.pushPose();
    pose.translate(cx, cy, 0f);
    pose.mulPose(Axis.ZP.rotation(radians)); // rotate around center

    // --- teeth ring (triangle fan) ---
    Tesselator t = Tesselator.getInstance();
    BufferBuilder b = t.getBuilder();
    b.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);

    // center
    b.vertex(pose.last().pose(), 0f, 0f, 0f).color(
            (colorTeeth >> 16) & 255,
            (colorTeeth >>  8) & 255,
            (colorTeeth      ) & 255,
            (colorTeeth >>> 24)     ).endVertex();

    // alternating inner/outer points
    final int steps = teeth * 2;
    for (int i = 0; i <= steps; i++) {
        float frac = (float) i / (float) steps;
        float ang  = (float) (frac * Math.PI * 2.0);
        float r    = (i % 2 == 0) ? rOuter : rInner;
        float x    = (float) (Math.cos(ang) * r);
        float y    = (float) (Math.sin(ang) * r);
        b.vertex(pose.last().pose(), x, y, 0f).color(
                (colorTeeth >> 16) & 255,
                (colorTeeth >>  8) & 255,
                (colorTeeth      ) & 255,
                (colorTeeth >>> 24)     ).endVertex();
    }
    t.end();

    // --- hub disk (triangle fan) ---
    float hub = Math.max(1f, rInner * 0.75f);
    b.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
    b.vertex(pose.last().pose(), 0f, 0f, 0f).color(
            (colorHub >> 16) & 255,
            (colorHub >>  8) & 255,
            (colorHub      ) & 255,
            (colorHub >>> 24)     ).endVertex();

    final int hubSeg = 24;
    for (int i = 0; i <= hubSeg; i++) {
        float ang = (float) (i * (Math.PI * 2.0 / hubSeg));
        float x   = (float) (Math.cos(ang) * hub);
        float y   = (float) (Math.sin(ang) * hub);
        b.vertex(pose.last().pose(), x, y, 0f).color(
                (colorHub >> 16) & 255,
                (colorHub >>  8) & 255,
                (colorHub      ) & 255,
                (colorHub >>> 24)     ).endVertex();
    }
    t.end();

    // restore pose + GL state
    pose.popPose();
    RenderSystem.enableCull();
    RenderSystem.disableBlend();
    RenderSystem.enableDepthTest();
}



/** Convenience: draw four small corner gears. */
private void drawCornerGears(GuiGraphics g, int left, int top, int right, int bottom, float partialTicks) {
    // Position inset (stay inside rounded panel)
    final int inset = 10;
    float cx1 = left  + inset;
    float cy1 = top   + inset;
    float cx2 = right - inset;
    float cy2 = top   + inset;
    float cx3 = left  + inset;
    float cy3 = bottom - inset;
    float cx4 = right - inset;
    float cy4 = bottom - inset;

    // Size + animation speed
    float rOuter = 4.0f;    // ~8 px across
    float rInner = 2.8f;
    int   teeth  = 8;

    // rotate with time (counter-rotate pairs for visual interest)
    long ticks = (minecraft != null && minecraft.level != null) ? minecraft.level.getGameTime() : 0L;
    float base = (ticks + partialTicks) * 0.12f; // speed

    drawGear(g, cx1, cy1, rInner, rOuter, teeth,  base,        GEAR_COLOR_TEETH, GEAR_COLOR_HUB);
    drawGear(g, cx2, cy2, rInner, rOuter, teeth, -base * 1.1f, GEAR_COLOR_TEETH, GEAR_COLOR_HUB);
    drawGear(g, cx3, cy3, rInner, rOuter, teeth, -base,        GEAR_COLOR_TEETH, GEAR_COLOR_HUB);
    drawGear(g, cx4, cy4, rInner, rOuter, teeth,  base * 1.1f, GEAR_COLOR_TEETH, GEAR_COLOR_HUB);
}

    private int rainbowColor(long ticks, float speed, float sat, float val) {
        float h = ((ticks % 3600L) / 3600f) * speed;
        int rgb = java.awt.Color.HSBtoRGB(h, sat, val);
        return 0xFF000000 | (rgb & 0x00FFFFFF);
    }

    private ResourceLocation resolvePreviewEffectId() {
        if ("particles".equals(state.getActiveType())) {
            CosmeticDef sel = getSelectedDefForType("particles");
            if (sel != null) {
                String effectStr = sel.properties().getOrDefault("effect",
                        sel.properties().getOrDefault("particle", ""));
                ResourceLocation effectId = ResourceLocation.tryParse(effectStr);
                if (effectId != null && !isAir(effectId)) return effectId;
            }
        }
        LocalPlayer p = Minecraft.getInstance().player;
        ResourceLocation cosmeticId = (p != null) ? ClientState.getEquippedId(p, "particles")
                : ClientState.getEquippedId("particles");
        if (isAir(cosmeticId)) return null;
        CosmeticDef def = CosmeticsRegistry.get(cosmeticId);
        if (def == null) return null;
        String effectStr = def.properties().getOrDefault("effect",
                def.properties().getOrDefault("particle", "minecraft:happy_villager"));
        ResourceLocation effectId = ResourceLocation.tryParse(effectStr);
        return (effectId != null) ? effectId : ResourceLocation.fromNamespaceAndPath("minecraft", "happy_villager");
    }

    private Map<String, ResourceLocation> resolvePreviewMap() {
        Map<String, ResourceLocation> map = new HashMap<>();
        LocalPlayer p = Minecraft.getInstance().player;

        ResourceLocation particleId = null;
        if ("particles".equals(state.getActiveType())) {
            CosmeticDef sel = getSelectedDefForType("particles");
            particleId = (sel != null) ? sel.id() : null;
        }
        if (particleId == null) {
            particleId = (p != null) ? ClientState.getEquippedId(p, "particles")
                    : ClientState.getEquippedId("particles");
        }
        if (!isAir(particleId)) map.put("particles", particleId);

        ResourceLocation hatId;
        if ("hats".equals(state.getActiveType())) {
            CosmeticDef selHat = getSelectedDefForType("hats");
            hatId = (selHat != null) ? selHat.id() : (p != null ? ClientState.getEquippedId(p, "hats")
                    : ClientState.getEquippedId("hats"));
        } else {
            hatId = (p != null) ? ClientState.getEquippedId(p, "hats") : ClientState.getEquippedId("hats");
        }
        if (!isAir(hatId)) map.put("hats", hatId);

        ResourceLocation capeId;
        if ("capes".equals(state.getActiveType())) {
            CosmeticDef selCape = getSelectedDefForType("capes");
            capeId = (selCape != null) ? selCape.id() : (p != null ? ClientState.getEquippedId(p, "capes")
                    : ClientState.getEquippedId("capes"));
        } else {
            capeId = (p != null) ? ClientState.getEquippedId(p, "capes") : ClientState.getEquippedId("capes");
        }
        if (!isAir(capeId)) map.put("capes", capeId);

        ResourceLocation petId;
        if ("pets".equals(state.getActiveType())) {
            CosmeticDef selPet = getSelectedDefForType("pets");
            petId = (selPet != null) ? selPet.id() : (p != null ? ClientState.getEquippedId(p, "pets")
                    : ClientState.getEquippedId("pets"));
        } else {
            petId = (p != null) ? ClientState.getEquippedId(p, "pets") : ClientState.getEquippedId("pets");
        }
        if (!isAir(petId)) map.put("pets", petId);

        // Gadgets are active-use; nothing to include here.

        return map;
    }

    // ------------------------------------------------------------------------
    // PreviewResolver – unified per-type override map for preview mannequins
    // ------------------------------------------------------------------------
    public static final class PreviewResolver {
        private static LocalPlayer MANNEQUIN = null;
        // Active override set (per cosmetic type, e.g. "hats", "pets", etc.)
        private static final Map<String, ResourceLocation> ACTIVE_OVERRIDES = new HashMap<>();

        /** Set the active mannequin used for preview contexts. */
        static void setMannequin(LocalPlayer mannequin) {
            MANNEQUIN = mannequin;
        }

        /** Begin a preview context with a new set of overrides. */
        static void begin(LocalPlayer mannequin, Map<String, ResourceLocation> map) {
            if (mannequin == null || map == null || map.isEmpty()) {
                ACTIVE_OVERRIDES.clear();
                return;
            }
            if (MANNEQUIN != null && mannequin == MANNEQUIN) {
                ACTIVE_OVERRIDES.clear();
                ACTIVE_OVERRIDES.putAll(map);
            }
        }

        /** Retrieve the current override ID for a given cosmetic type. */
        @Nullable
        public static ResourceLocation getOverride(String type, @Nullable net.minecraft.world.entity.Entity ignored) {
            return ACTIVE_OVERRIDES.get(type);
        }

        /** True if this entity is the active mannequin used in the preview screen. */
        public static boolean isPreviewEntity(@Nullable net.minecraft.world.entity.Entity entity) {
            return entity != null && entity == MANNEQUIN;
        }

        /** Compatibility hook — called when preview ends. */
        public static void end() {
            clearAll();
            MANNEQUIN = null;
        }

        /** Clear all overrides (used when closing preview or quickmenu). */
        public static void clearAll() {
            ACTIVE_OVERRIDES.clear();
        }
    }

    // ========================================================================
    // Pref helpers
    // ========================================================================
    /** Best-effort current pet species based on selected/equipped pet id. */
    private String currentPetSpecies() {
        CosmeticDef def = getSelectedDefForType("pets");
        ResourceLocation id = null;
        if (def != null) {
            id = def.id();
        } else {
            LocalPlayer p = Minecraft.getInstance().player;
            id = (p != null) ? ClientState.getEquippedId(p, "pets")
                    : ClientState.getEquippedId("pets");
        }
        return speciesFromId(id);
    }

    /** Normalize a pet id to a simple species key we use for prefs. */
    private static String speciesFromId(@Nullable ResourceLocation id) {
        if (id == null) return "";
        String path = id.getPath().toLowerCase(Locale.ROOT);

        // Existing pets
        if (path.contains("parrot"))        return "parrot";
        if (path.contains("cat"))           return "cat";
        if (path.contains("fox"))           return "fox";
        if (path.contains("wolf"))          return "wolf";
        if (path.contains("sheep"))         return "sheep";
        if (path.contains("rabbit"))        return "rabbit";
        if (path.contains("llama") && !path.contains("trader")) return "llama";
        if (path.contains("frog"))          return "frog";
        if (path.contains("mooshroom"))     return "mooshroom";
        if (path.contains("horse"))         return "horse";
        if (path.contains("pig"))           return "pig";
        if (path.contains("chicken"))       return "chicken";
        if (path.contains("panda"))         return "panda";
        if (path.contains("bee"))           return "bee";
        if (path.contains("axolotl"))       return "axolotl";

        // New additions
        if (path.contains("donkey"))        return "donkey";
        if (path.contains("mule"))          return "mule";
        if (path.contains("camel"))         return "camel";
        if (path.contains("ocelot"))        return "ocelot";
        if (path.equals("cow") || path.contains("cow")) return "cow";
        if (path.contains("villager"))      return "villager";
        if (path.contains("vex"))           return "vex";
        if (path.contains("blaze"))         return "blaze";
        if (path.contains("goat"))          return "goat";
        if (path.contains("snow_golem"))    return "snow_golem";
        if (path.contains("iron_golem"))    return "iron_golem";

        return "pet"; // fallback
    }

    /** Pull saved prefs (if any) to update Random toggle + wheel enabled state. */
    private void updatePetsUiFromPrefs() {
        if (!"pets".equals(state.getActiveType())) return;
        String species = currentPetSpecies();
        if (species.isEmpty()) return;

        ClientState.PetColorPref pref = ClientState.getPetColorPref(species);
        if (pref != null) {
            this.randomEnabled = pref.random();
            if (randomBtn != null) {
                randomBtn.setMessage(Component.literal(randomEnabled ? "Random: ON" : "Random: OFF"));
            }
            if (colorWheel != null) {
                colorWheel.active = !randomEnabled;
            }
        }
    }

    // ========================================================================

    // Build/rebuild filter chips for HATS tab
    private void buildHatFilterChips() {
        hatChips.clear();
        selectedHatPacks.clear();
        if (!"hats".equals(state.getActiveType())) return;

        // Compute geometry: place below tab strip
        int stripL = left + 10, stripR = right - 10, stripT = top + 26, stripB = stripT + 28;
        chipsLeft = stripL;
        chipsTop  = stripB + 6;
        chipsRight = stripR;

        // Collect packs present in HATS
        List<CosmeticDef> hats = CosmeticsRegistry.getByType("hats");
        LinkedHashSet<String> packs = new LinkedHashSet<>();
        for (CosmeticDef d : hats) packs.add(d.pack());
        // Stable order: All, then alphabetical
        List<String> sorted = new ArrayList<>(packs);
        Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);

        // Create "All" chip
        hatChips.add(new FilterChip("All", PACK_ALL));
        selectedHatPacks.add(PACK_ALL);

        // Create pack chips
        for (String p : sorted) {
            String label = capitalize(p);
            hatChips.add(new FilterChip(label, p));
        }

        // Lay out chips
        int x = chipsLeft, y = chipsTop;
        for (FilterChip c : hatChips) {
            int textW = this.font.width(c.label);
            int w = textW + 2 * CHIP_PAD_X;
            if (x + w > chipsRight) {
                // wrap to next line if needed
                x = chipsLeft;
                y += CHIP_H + CHIP_GAP;
            }
            c.x = x; c.y = y; c.w = w; c.h = CHIP_H;
            // Default selection: ALL
            c.selected = PACK_ALL.equals(c.packId);
            x += w + CHIP_GAP;
        }
    }

    private void drawHatFilterChips(GuiGraphics gfx) {
        if (!"hats".equals(state.getActiveType())) return;
        for (FilterChip c : hatChips) {
            int bg = c.selected ? 0xFF2E7D32 : 0xFF455A64; // green vs slate
            int fg = 0xFFFFFFFF;
            fillRounded(gfx, c.x, c.y, c.x + c.w, c.y + c.h, 4, bg);
            int tx = c.x + CHIP_PAD_X;
            int ty = c.y + (c.h - 8) / 2;
            gfx.drawString(this.font, c.label, tx, ty, fg, false);
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // Drawing helpers
    // ========================================================================
    private static void fillRounded(GuiGraphics g, int x0, int y0, int x1, int y1, int r, int argb) {
        if (r <= 0) { g.fill(x0, y0, x1, y1, argb); return; }
        r = Math.min(r, Math.min((x1 - x0) / 2, (y1 - y0) / 2));
        g.fill(x0 + r, y0,       x1 - r, y1,       argb);
        g.fill(x0,     y0 + r,   x0 + r, y1 - r,   argb);
        g.fill(x1 - r, y0 + r,   x1,     y1 - r,   argb);
        for (int dy = 0; dy < r; dy++) {
            double y = r - dy - 0.5;
            int dx = (int)Math.floor(Math.sqrt(r * r - y * y));
            g.fill(x0 + r - dx, y0 + dy,     x0 + r,       y0 + dy + 1, argb);
            g.fill(x1 - r,      y0 + dy,     x1 - r + dx,  y0 + dy + 1, argb);
            g.fill(x0 + r - dx, y1 - dy - 1, x0 + r,       y1 - dy,     argb);
            g.fill(x1 - r,      y1 - dy - 1, x1 - r + dx,  y1 - dy,     argb);
        }
    }

    private static void drawSoftRoundedShadow(GuiGraphics g, int l, int t, int r, int b) {
        final int padNear = 6;
        final int padFar  = 10;
        final int radius  = 10;
        fillRounded(g, l - padFar,  t - padFar,  r + padFar,  b + padFar,  radius + 2, 0x11000000);
        fillRounded(g, l - padNear, t - padNear, r + padNear, b + padNear, radius,     0x22000000);
    }

    /** Tile a small texture across a rectangle. */
    private static void blitTiled(GuiGraphics g, ResourceLocation tex,
                                  int x0, int y0, int x1, int y1, int tile) {
        for (int y = y0; y < y1; y += tile) {
            int h = Math.min(tile, y1 - y);
            for (int x = x0; x < x1; x += tile) {
                int w = Math.min(tile, x1 - x);
                g.blit(tex, x, y, 0, 0, w, h, tile, tile);
            }
        }
    }

    // --- Cooldown label for selected-or-equipped gadget ---
    private void renderGadgetCooldown(net.minecraft.client.gui.GuiGraphics g, int centerX, int y) {
        // Prefer the currently-selected gadget on the grid (if any), else fall back to equipped.
        net.minecraft.resources.ResourceLocation id = null;
        if ("gadgets".equals(state.getActiveType())) {
            com.pastlands.cosmeticslite.CosmeticDef sel = getCurrentlySelectedDef();
            if (sel != null) id = sel.id();
        }
        if (id == null) {
            id = com.pastlands.cosmeticslite.ClientState.getEquippedId("gadgets");
        }
        if (id == null) return;

        long ms = com.pastlands.cosmeticslite.gadget.GadgetClientCommands.remainingMs(id);
        String label = (ms > 0L)
            ? "Cooldown: " + com.pastlands.cosmeticslite.gadget.GadgetClientCommands.prettyClock(ms)
            : "Ready";
        int color = (ms > 0L) ? 0xFFC9A86E : 0xFFB8F18B; // gold-ish vs mint-ish
        int w = this.font.width(label);
        g.drawString(this.font, label, centerX - (w / 2), y, color, false);
    }

// Draw 4 decorative rivets just inside the main rounded frame.
private static void drawRivets(GuiGraphics g, int left, int top, int right, int bottom) {
    final int inset = 10; // distance from the outer frame

    int[][] corners = new int[][]{
        {left  + inset, top    + inset},   // top-left
        {right - inset, top    + inset},   // top-right
        {left  + inset, bottom - inset},   // bottom-left
        {right - inset, bottom - inset}    // bottom-right
    };

    for (int[] c : corners) {
        int cx = c[0], cy = c[1];

        // Shadow ring
        g.fill(cx - RIVET_SIZE - 1, cy - RIVET_SIZE,     cx + RIVET_SIZE + 1, cy + RIVET_SIZE + 1, RIVET_SHADOW);
        // Outer rim
        g.fill(cx - RIVET_SIZE,     cy - RIVET_SIZE,     cx + RIVET_SIZE,     cy + RIVET_SIZE,     RIVET_RIM_COLOR);
        // Core
        g.fill(cx - (RIVET_SIZE - 1), cy - (RIVET_SIZE - 1),
               cx + (RIVET_SIZE - 1), cy + (RIVET_SIZE - 1), RIVET_CORE_COLOR);
        // Highlight (upper-left quadrant)
        g.fill(cx - (RIVET_SIZE - 1), cy - (RIVET_SIZE - 1), cx, cy, RIVET_HILITE);
    }
}


    // ========================================================================
    // Accessors
    // ========================================================================
    /** Expose the current ScreenState so other layers can query the active tab safely. */
    public ScreenState getScreenState() {
        return this.state;
    }
}
