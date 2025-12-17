package com.pastlands.cosmeticslite.client.screen;

import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.particle.config.CosmeticParticleRegistry;
import com.pastlands.cosmeticslite.particle.config.ParticleDefinition;
import com.pastlands.cosmeticslite.particle.config.ParticleLayerDefinition;
import com.pastlands.cosmeticslite.particle.config.WorldLayerDefinition;
import com.pastlands.cosmeticslite.particle.config.ParticlePreviewState;
import com.pastlands.cosmeticslite.network.ParticleDefinitionChangePacket;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Particle Lab GUI - Editor for particle definitions.
 * 
 * Left panel: List of definitions (search, scroll, new, duplicate, delete)
 * Right panel: Editor with tabs (General, Layers, World Layers, Preview)
 */
public class ParticleLabScreen extends Screen {
    private static final int GUI_WIDTH = 800;
    private static final int GUI_HEIGHT = 500;
    
    private int left, top, right, bottom;
    private int leftPanelWidth = 250;
    private int rightPanelWidth;
    
    // Preview panel bounds (for translucent glass panel)
    private int previewPanelLeft;
    private int previewPanelTop;
    private int previewPanelRight;
    private int previewPanelBottom;
    
    // Left panel: Definition list
    private EditBox searchBox;
    // In-memory collection of definitions (single source of truth)
    private Map<ResourceLocation, ParticleDefinition> editorDefinitions = new java.util.HashMap<>();
    private List<ResourceLocation> allDefinitions = new ArrayList<>();
    private List<ResourceLocation> filteredDefinitions = new ArrayList<>();
    private int scrollOffset = 0;
    private Button newBtn, duplicateBtn;
    
    // Color selection context for tracking which color is being edited
    private record ColorSelectionContext(
        ResourceLocation definitionId,
        int layerIndex,
        int colorIndex
    ) {}
    
    @org.jetbrains.annotations.Nullable
    private ColorSelectionContext pendingColorSelection = null;
    
    // Editor state
    private static class EditorState {
        ResourceLocation selectedId = null;
        ParticleDefinition originalDefinition = null;  // From registry (immutable reference)
        ParticleDefinition workingCopy = null;         // Mutated by UI
        boolean dirty = false;
        boolean isNew = false;  // True if this is a new definition (not yet saved)
        
        void selectDefinition(ResourceLocation id, ParticleDefinition def) {
            this.selectedId = id;
            this.originalDefinition = ParticleLabScreen.deepCopyDefinition(def);
            this.workingCopy = ParticleLabScreen.deepCopyDefinition(def);
            this.dirty = false;
            this.isNew = false;
        }
        
        void markDirty() {
            this.dirty = true;
        }
        
        void markSaved() {
            this.originalDefinition = ParticleLabScreen.deepCopyDefinition(workingCopy);
            this.dirty = false;
            this.isNew = false;
        }
        
        void revert() {
            if (originalDefinition != null) {
                this.workingCopy = ParticleLabScreen.deepCopyDefinition(originalDefinition);
                this.dirty = false;
            }
        }
    }
    
    private final EditorState editorState = new EditorState();
    
    // Right panel: Editor
    private String activeTab = "general"; // "general", "layers", "world_layers", "preview"
    
    // Camera management for Preview tab
    private CameraType previousCameraType = null;
    
    // Preview refresh tracking (debounced)
    private boolean previewDirty = false;
    private long previewDirtyAtMs = 0;
    private static final long PREVIEW_DEBOUNCE_MS = 100; // 100ms debounce for text field edits
    
    // Tab-based widget tracking to prevent zombie widgets
    private enum Tab {
        GENERAL,
        LAYERS,
        WORLD,
        PREVIEW
    }
    
    private static class TabbedWidget {
        final Tab tab;
        final net.minecraft.client.gui.components.events.GuiEventListener listener;

        TabbedWidget(Tab tab, net.minecraft.client.gui.components.events.GuiEventListener listener) {
            this.tab = tab;
            this.listener = listener;
        }
    }
    
    private final java.util.List<TabbedWidget> tabbedWidgets = new java.util.ArrayList<>();
    
    // Layers tab scroll state
    private static final int LAYERS_CARD_HEIGHT = 140;
    private static final int LAYERS_CARD_GAP = 8;
    private float layersScrollOffset = 0f;
    private float layersMaxScroll = 0f;
    private int layersViewportTop = 0;
    private int layersViewportBottom = 0;
    
    // Layer card dimensions
    private static final int LABEL_HEIGHT = 12; // Height reserved for layer label at top of card
    
    // World tab scroll state
    private static final int WORLD_CARD_HEIGHT = 230 + LABEL_HEIGHT;
    private static final int WORLD_CARD_GAP = 14; // Increased for better visual separation between cards
    private static final int WORLD_VIEWPORT_TOP_MARGIN = 30;   // space under tab buttons and title (accounts for title + warning text)
    private static final int WORLD_VIEWPORT_BOTTOM_MARGIN = 40; // space above bottom buttons (ensures no overlap)
    private float worldScrollOffset = 0f;
    private float worldMaxScroll = 0f;
    private int worldViewportTop = 0;
    private int worldViewportBottom = 0;
    
    // Scrollbar constants (same as Layers)
    private static final int WORLD_SCROLLBAR_WIDTH = 6;
    private static final int WORLD_SCROLLBAR_MARGIN = 2;
    
    // Scrollbar dragging state
    private boolean draggingWorldScrollbar = false;
    
    // Label color - light gray matching field labels
    private static final int LABEL_COLOR = 0xFFAAAAAA;
    
    // Vertical offset for labels above numeric fields
    private static final int FIELD_LABEL_VERTICAL_GAP = 3;
    // Extra horizontal inset so labels are not flush with field left edge
    private static final int FIELD_LABEL_HORIZONTAL_INSET = 2;
    
    // General tab widgets
    private EditBox displayNameField;
    private EditBox notesField;
    
    // Buttons
    private Button saveBtn, revertBtn, previewBtn, publishBtn, deleteBtn;
    private Button addLayerBtn, addWorldLayerBtn; // Tab-specific add buttons
    
    // Layer editor widgets (rebuilt when tab changes or definition changes)
    private static class LayerWidgets {
        int layerIndex; // Index into GUI layers list (ParticleLayerDefinition)
        int worldLayerIndex; // Index into world layers list (WorldLayerDefinition) - for color lockout
        com.pastlands.cosmeticslite.client.screen.parts.StringDropdownWidget movementDropdown;
        com.pastlands.cosmeticslite.client.editor.EffectCapabilitiesRegistry.EffectCapabilities capabilities; // Capabilities for current effect
        List<com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget> numericFields; // lifespan, spawnInterval, size, speed, weight, previewScale
        List<net.minecraft.client.gui.components.Renderable> colorButtons; // ColorSwatchButton widgets + add/remove buttons
        Button removeBtn, moveUpBtn, moveDownBtn;
        com.pastlands.cosmeticslite.client.screen.parts.LabelWidget cardLabel; // Card title label only
        List<com.pastlands.cosmeticslite.client.screen.parts.LabelWidget> fieldLabels; // Field labels
        List<com.pastlands.cosmeticslite.client.screen.parts.LabelWidget> labels; // All labels for cleanup
        
        LayerWidgets(int index, int worldLayerIndex) {
            this.layerIndex = index;
            this.worldLayerIndex = worldLayerIndex;
            this.numericFields = new ArrayList<>();
            this.colorButtons = new ArrayList<>();
            this.fieldLabels = new ArrayList<>();
            this.labels = new ArrayList<>();
        }
    }
    
    private List<LayerWidgets> layerWidgets = new ArrayList<>();
    
    // World layer editor widgets
    private static class WorldLayerWidgets {
        int layerIndex; // Legacy compatibility
        int cardIndex;                 // 0,1,2... (same as layerIndex for World layers)
        final java.util.List<net.minecraft.client.gui.components.AbstractWidget> widgets = new java.util.ArrayList<>();
        
        com.pastlands.cosmeticslite.client.screen.parts.StringDropdownWidget effectDropdown;
        com.pastlands.cosmeticslite.client.screen.parts.StringDropdownWidget styleDropdown;
        
        // Numeric fields stored as individual fields
        com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget radiusField;
        com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget baseHeightField;
        com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget stretchField;
        com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget countField;
        com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget speedMultiplierField;
        com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget offsetXField;
        com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget offsetYField;
        com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget offsetZField;
        com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget tiltField;
        com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget spreadStartField;
        com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget spreadEndField;
        
        // Dropdown widgets
        com.pastlands.cosmeticslite.client.screen.parts.StringDropdownWidget rotationModeDropdown;
        com.pastlands.cosmeticslite.client.screen.parts.StringDropdownWidget motionCurveDropdown;
        
        // Keep old list for backward compatibility (but prefer individual fields)
        List<com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget> numericFields; // radius, heightFactor, count, speedY
        List<EditBox> optionalFields; // yOffset, xScale, direction
        List<net.minecraft.client.gui.components.Renderable> colorWidgets; // Color swatches, color wheel, etc.
        boolean canColorize; // Whether the current effect supports colorization
        Button removeBtn, moveUpBtn, moveDownBtn, duplicateBtn;
        com.pastlands.cosmeticslite.client.screen.parts.LabelWidget cardLabel; // Card title label only
        List<com.pastlands.cosmeticslite.client.screen.parts.LabelWidget> fieldLabels; // Field labels
        List<com.pastlands.cosmeticslite.client.screen.parts.LabelWidget> labels; // All labels for cleanup
        String description; // Generated description for tooltip
        int headerX, headerY, headerWidth; // Header bounds for tooltip hover detection
        
        WorldLayerWidgets(int index) {
            this.layerIndex = index;
            this.cardIndex = index;
            this.numericFields = new ArrayList<>();
            this.optionalFields = new ArrayList<>();
            this.colorWidgets = new ArrayList<>();
            this.canColorize = false;
            this.fieldLabels = new ArrayList<>();
            this.labels = new ArrayList<>();
        }
    }
    
    private List<WorldLayerWidgets> worldLayerWidgets = new ArrayList<>();
    
    // Track header bounds for tooltips (populated during render, cleared each frame)
    private static class WorldLayerHeaderBounds {
        final int layerIndex;
        final int headerX;
        final int headerY;
        final int headerWidth;
        final String description;
        
        WorldLayerHeaderBounds(int layerIndex, int headerX, int headerY, int headerWidth, String description) {
            this.layerIndex = layerIndex;
            this.headerX = headerX;
            this.headerY = headerY;
            this.headerWidth = headerWidth;
            this.description = description;
        }
    }
    private List<WorldLayerHeaderBounds> worldLayerHeaderBounds = new ArrayList<>();
    
    public ParticleLabScreen() {
        super(Component.literal("Particle Lab"));
    }
    
    @Override
    protected void init() {
        super.init();
        
        left = (this.width - GUI_WIDTH) / 2;
        top = (this.height - GUI_HEIGHT) / 2;
        right = left + GUI_WIDTH;
        bottom = top + GUI_HEIGHT;
        rightPanelWidth = GUI_WIDTH - leftPanelWidth - 20;
        
        // Permission check is done server-side; client just opens the screen
        // Server will reject packets if user doesn't have permission
        
        loadDefinitions();
        buildLeftPanel();
        buildRightPanel();
        updateButtonStates();
        updatePreviewButtonLabel();
        rebuildTabWidgets();
        
        // Setup preview mannequin bounds
        updatePreviewBounds();
    }
    
    /**
     * Get the preview panel bounds (the black rectangle where world renders).
     * This matches the viewport background rectangle.
     * Preview area should leave room for buttons below it.
     */
    private void updatePreviewBounds() {
        // Update preview bounds to leave room for button row below
        int panelMargin = 20;
        int buttonRowHeight = 20 + 6; // button height + padding
        this.previewPanelLeft = this.left + this.leftPanelWidth + panelMargin;
        this.previewPanelRight = this.right - panelMargin;
        this.previewPanelTop = this.top + 60;
        // Reserve space for button row below preview
        this.previewPanelBottom = this.bottom - 60 - buttonRowHeight;
        
        // Update preview definition if preview is already active (player-controlled)
        // Do NOT auto-activate preview - player must use "Preview On Player" button
        if (ParticlePreviewState.isActive() && editorState.workingCopy != null && editorState.selectedId != null) {
            // Update preview definition and override map with working copy
            ParticlePreviewState.updatePreviewDefinition(editorState.workingCopy);
            ParticlePreviewState.setPreviewOverride(editorState.selectedId, editorState.workingCopy);
        }
    }
    
    /**
     * Load definitions from registry into editor's in-memory collection.
     * IMPORTANT: This should ONLY be called:
     * - Once at screen initialization (init())
     * - When explicitly reloading from disk/server (manual reload action)
     * 
     * DO NOT call this from:
     * - onColorPicked()
     * - rebuildTabWidgets()
     * - refreshEditorUI()
     * - Any other refresh/update paths
     * 
     * Those paths should only work with editorState.workingCopy in memory.
     */
    private void loadDefinitions() {
        // Load from registry into editor's in-memory collection
        // If registry is empty or very small, reload from config first (for default definitions)
        int registrySize = CosmeticParticleRegistry.all().size();
        if (registrySize < 5) {
            // Registry is small, likely missing config-based definitions - reload from config
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.getResourceManager() != null) {
                int loaded = CosmeticParticleRegistry.reloadFromConfig(mc.getResourceManager());
                CosmeticsLite.LOGGER.info("[ParticleLab] Reloaded {} definition(s) from config folder", loaded);
            }
        }
        
        // Load from registry into editor's in-memory collection
        editorDefinitions.clear();
        int count = 0;
        for (ParticleDefinition def : CosmeticParticleRegistry.all()) {
            ParticleDefinition defCopy = deepCopyDefinition(def);
            // Enforce 1:1 mapping when loading definitions
            defCopy = ensureLayerCountMatchesWorldLayers(defCopy);
            editorDefinitions.put(def.id(), defCopy);
            count++;
        }
        CosmeticsLite.LOGGER.info("[ParticleLab] Loaded {} definition(s) from registry into editor collection", count);
        refreshDefinitionList();
    }
    
    private void refreshDefinitionList() {
        // Rebuild allDefinitions from editorDefinitions
        // Sort by short ID (path only) alphabetically, case-insensitive
        allDefinitions = editorDefinitions.values().stream()
            .map(ParticleDefinition::id)
            .sorted((a, b) -> a.getPath().compareToIgnoreCase(b.getPath()))
            .collect(Collectors.toList());
        filterDefinitions(searchBox != null ? searchBox.getValue() : "");
    }
    
    private void filterDefinitions(String query) {
        if (query.isEmpty()) {
            filteredDefinitions = new ArrayList<>(allDefinitions);
        } else {
            String lowerQuery = query.toLowerCase();
            filteredDefinitions = allDefinitions.stream()
                .filter(id -> id.toString().toLowerCase().contains(lowerQuery))
                .collect(Collectors.toList());
        }
        scrollOffset = 0;
        
        // Initialize preview panel bounds (matches the current preview rectangle position)
        // Preview area should leave room for buttons below it
        updatePreviewBounds(); // Use the same method to ensure consistency
    }
    
    private void buildLeftPanel() {
        int panelLeft = left + 10;
        int panelTop = top + 10;
        int panelRight = left + leftPanelWidth - 10;
        
        // Search box
        searchBox = new EditBox(this.font, panelLeft, panelTop, leftPanelWidth - 20, 20, Component.literal("Search..."));
        searchBox.setResponder(this::filterDefinitions);
        addRenderableWidget(searchBox);
        
        // Action buttons
        int btnY = panelTop + 30;
        int btnW = (leftPanelWidth - 30) / 3;
        newBtn = Button.builder(Component.literal("New"), btn -> createNewDefinition())
            .bounds(panelLeft, btnY, btnW, 20).build();
        duplicateBtn = Button.builder(Component.literal("Duplicate"), btn -> duplicateDefinition())
            .bounds(panelLeft + btnW + 5, btnY, btnW, 20).build();
        deleteBtn = Button.builder(Component.literal("Delete"), btn -> deleteDefinition())
            .bounds(panelLeft + (btnW + 5) * 2, btnY, btnW, 20).build();
        addRenderableWidget(newBtn);
        addRenderableWidget(duplicateBtn);
        addRenderableWidget(deleteBtn);
        
        // Definition list (rendered in render method)
    }
    
    private void buildRightPanel() {
        int panelLeft = left + leftPanelWidth + 10;
        int panelTop = top + 10;
        
        // Tab buttons
        int tabY = panelTop;
        int tabW = 100;
        addRenderableWidget(Button.builder(Component.literal("General"), btn -> setActiveTab("general"))
            .bounds(panelLeft, tabY, tabW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Layers"), btn -> setActiveTab("layers"))
            .bounds(panelLeft + tabW + 5, tabY, tabW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("World"), btn -> setActiveTab("world_layers"))
            .bounds(panelLeft + (tabW + 5) * 2, tabY, tabW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Preview"), btn -> setActiveTab("preview"))
            .bounds(panelLeft + (tabW + 5) * 3, tabY, tabW, 20).build());
        
        // Create footer buttons with proper labels and widths
        int buttonHeight = 20;
        
        // Save button
        String saveLabel = "Save";
        int saveWidth = this.font.width(saveLabel) + 12;
        saveBtn = Button.builder(Component.literal(saveLabel), btn -> saveDefinition())
            .bounds(0, 0, saveWidth, buttonHeight).build();
        
        // Revert button
        String revertLabel = "Revert";
        int revertWidth = this.font.width(revertLabel) + 12;
        revertBtn = Button.builder(Component.literal(revertLabel), btn -> revertChanges())
            .bounds(0, 0, revertWidth, buttonHeight).build();
        
        // Publish button
        String publishLabel = "Publish Cosmetic…";
        int publishWidth = this.font.width(publishLabel) + 12;
        publishBtn = Button.builder(Component.literal(publishLabel), btn -> openPublishDialog())
            .bounds(0, 0, publishWidth, buttonHeight).build();
        
        // Preview button (width uses "Stop Preview" to prevent resizing)
        int previewWidth = this.font.width("Stop Preview") + 12;
        previewBtn = Button.builder(Component.literal("Preview"), btn -> togglePreview())
            .bounds(0, 0, previewWidth, buttonHeight).build();
        
        // Delete button (red, only visible for lab definitions)
        String deleteLabel = "Delete";
        int deleteWidth = this.font.width(deleteLabel) + 12;
        deleteBtn = Button.builder(Component.literal(deleteLabel), btn -> deleteDefinition())
            .bounds(0, 0, deleteWidth, buttonHeight).build();
        // Style delete button as red/dangerous
        deleteBtn.setFGColor(0xFFFF5555); // Red text
        
        // Layout all footer buttons (will be called again when tab changes to update add button)
        layoutFooterButtons(null, saveBtn, revertBtn, publishBtn, previewBtn, deleteBtn);
        
        addRenderableWidget(saveBtn);
        addRenderableWidget(revertBtn);
        addRenderableWidget(publishBtn);
        addRenderableWidget(previewBtn);
        addRenderableWidget(deleteBtn);
    }
    
    private void setActiveTab(String tab) {
        this.activeTab = tab;
        
        // Auto-switch to 3rd person when opening Preview tab
        if ("preview".equals(tab)) {
            // Remember existing camera mode once (only on first switch to Preview)
            if (this.previousCameraType == null) {
                this.previousCameraType = this.minecraft.options.getCameraType();
            }
            // Force third-person behind for better preview view
            this.minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }
        
        // Reset scroll offsets when switching tabs
        layersScrollOffset = 0.0f;
        layersMaxScroll = 0.0f;
        worldScrollOffset = 0.0f;
        worldMaxScroll = 0.0f;
        draggingWorldScrollbar = false;
        rebuildTabWidgets();
        
        // Force footer button labels to update, then autosize and layout
        refreshFooterButtonsForActiveTab();
    }
    
    /**
     * Update footer button labels for the active tab, then autosize and layout them.
     * Ensures "set label → autosize → layout" happens immediately on tab switch.
     */
    private void refreshFooterButtonsForActiveTab() {
        // Update Add button label if it exists (buttons are created during rebuild)
        if ("world_layers".equals(activeTab) && addWorldLayerBtn != null) {
            addWorldLayerBtn.setMessage(Component.literal("+ Add World Layer"));
        } else if ("layers".equals(activeTab) && addLayerBtn != null) {
            addLayerBtn.setMessage(Component.literal("+ Add Layer"));
        }
        // General/Preview tabs don't have add buttons, or they're handled elsewhere
        
        // Get the correct add button for current tab
        Button currentAddButton = null;
        if ("layers".equals(activeTab) && addLayerBtn != null) {
            currentAddButton = addLayerBtn;
        } else if ("world_layers".equals(activeTab) && addWorldLayerBtn != null) {
            currentAddButton = addWorldLayerBtn;
        }
        
        // Force widths + positions to recompute NOW (autosize happens inside layoutFooterButtons)
        layoutFooterButtons(currentAddButton, saveBtn, revertBtn, publishBtn, previewBtn, deleteBtn);
    }
    
    private void rebuildTabWidgets() {
        // Clear existing widgets for this tab
        clearTabWidgets();
        
        if (editorState.workingCopy == null) return;
        
        switch (activeTab) {
            case "general":
                rebuildGeneralWidgets();
                break;
            case "layers":
                rebuildLayerWidgets();
                break;
            case "world_layers":
                rebuildWorldLayerWidgets();
                break;
            default:
                // Preview tab doesn't need widget rebuilding
                break;
        }
    }
    
    /**
     * Helper to register a widget for a specific tab.
     * Tracks widgets so they can be properly cleared when switching tabs.
     */
    @SuppressWarnings("unchecked")
    private <T extends net.minecraft.client.gui.components.events.GuiEventListener & net.minecraft.client.gui.components.Renderable & net.minecraft.client.gui.narration.NarratableEntry> T addTabWidget(Tab tab, T widget) {
        this.addRenderableWidget(widget);
        this.tabbedWidgets.add(new TabbedWidget(tab, widget));
        return widget;
    }
    
    /**
     * Clear all widgets for a specific tab.
     * This prevents zombie widgets from persisting when switching tabs.
     */
    private void clearTabWidgets(Tab tab) {
        // Remove widgets from tracking list and remove them from the screen
        java.util.Iterator<TabbedWidget> it = tabbedWidgets.iterator();
        while (it.hasNext()) {
            TabbedWidget tw = it.next();
            if (tw.tab == tab) {
                this.removeWidget(tw.listener);
                it.remove();
            }
        }
    }
    
    /**
     * Clear all widgets (legacy method for compatibility).
     */
    private void clearTabWidgets() {
        // FIRST: remove any tab-tracked widgets so manual render can't resurrect them
        clearTabWidgets(Tab.GENERAL);
        clearTabWidgets(Tab.LAYERS);
        clearTabWidgets(Tab.WORLD);
        clearTabWidgets(Tab.PREVIEW);
        
        // Remove general tab widgets
        if (displayNameField != null) {
            removeWidget(displayNameField);
            displayNameField = null;
        }
        if (notesField != null) {
            removeWidget(notesField);
            notesField = null;
        }
        
        // Remove layer widgets
        for (LayerWidgets lw : layerWidgets) {
            if (lw.movementDropdown != null) {
                removeWidget(lw.movementDropdown);
            }
            for (var field : lw.numericFields) {
                if (field != null) removeWidget(field);
            }
            for (net.minecraft.client.gui.components.Renderable widget : lw.colorButtons) {
                if (widget instanceof net.minecraft.client.gui.components.events.GuiEventListener listener) {
                    removeWidget(listener);
                }
            }
            if (lw.removeBtn != null) removeWidget(lw.removeBtn);
            if (lw.moveUpBtn != null) removeWidget(lw.moveUpBtn);
            if (lw.moveDownBtn != null) removeWidget(lw.moveDownBtn);
            if (lw.cardLabel != null) removeWidget(lw.cardLabel);
            for (var label : lw.fieldLabels) {
                if (label != null) removeWidget(label);
            }
            for (var label : lw.labels) {
                if (label != null) removeWidget(label);
            }
        }
        layerWidgets.clear();
        
        // Remove world layer widgets (use widgets list for cleanup)
        for (WorldLayerWidgets wlw : worldLayerWidgets) {
            for (net.minecraft.client.gui.components.AbstractWidget widget : wlw.widgets) {
                removeWidget(widget);
            }
        }
        worldLayerWidgets.clear();
        
        // Add-layer buttons are now tab-owned widgets, so they're removed via clearTabWidgets(Tab.LAYERS/WORLD) above
        // Just null the references to ensure clean state
        addLayerBtn = null;
        addWorldLayerBtn = null;
    }
    
    private void rebuildGeneralWidgets() {
        if (editorState.workingCopy == null) return;
        
        int panelLeft = left + leftPanelWidth + 20;
        int fieldX = panelLeft + 120; // Field starts after label
        int fieldWidth = 300; // Match Layers/World tab field widths
        int startY = top + 70; // Moved down ~10px from top
        
        // Display Name - with spacing from ID
        int displayNameY = startY + 40; // 10px spacing from ID row
        displayNameField = new EditBox(this.font, fieldX, displayNameY, fieldWidth, 20, Component.literal("Display Name"));
        displayNameField.setValue(editorState.workingCopy.displayName() != null ? editorState.workingCopy.displayName() : "");
        displayNameField.setEditable(true);
        displayNameField.setMaxLength(128);
        displayNameField.setResponder(text -> updateGeneralField("displayName", text));
        addRenderableWidget(displayNameField);
        
        // Notes - with spacing from Display Name, taller field
        int notesY = displayNameY + 40; // 12px spacing from Display Name
        int notesHeight = 120; // Taller field for multi-line notes
        notesField = new EditBox(this.font, fieldX, notesY, fieldWidth, notesHeight, Component.literal("Notes"));
        notesField.setValue(editorState.workingCopy.notes() != null ? editorState.workingCopy.notes() : "");
        notesField.setEditable(true);
        notesField.setMaxLength(1024);
        notesField.setResponder(text -> updateGeneralField("notes", text));
        addRenderableWidget(notesField);
    }
    
    /**
     * Enable scissor using GUI coordinates (converts to pixel coordinates correctly).
     * @param left GUI X coordinate (left edge)
     * @param top GUI Y coordinate (top edge)
     * @param right GUI X coordinate (right edge)
     * @param bottom GUI Y coordinate (bottom edge)
     */
    private void enableScissorGuiRect(int left, int top, int right, int bottom) {
        var win = net.minecraft.client.Minecraft.getInstance().getWindow();
        double scale = win.getGuiScale();
        
        int sx = (int) Math.floor(left * scale);
        int sw = (int) Math.floor((right - left) * scale);
        int sh = (int) Math.floor((bottom - top) * scale);
        int sy = (int) Math.floor(win.getHeight() - (bottom * scale));
        
        // Guard: never enable a broken scissor
        if (sw <= 0 || sh <= 0) {
            CosmeticsLite.LOGGER.warn("[Scissor] Invalid scissor dimensions: w={}, h={}", sw, sh);
            return;
        }
        
        RenderSystem.enableScissor(sx, sy, sw, sh);
    }
    
    /**
     * Disable scissor.
     */
    private void disableScissor() {
        RenderSystem.disableScissor();
    }
    
    /**
     * Hide/show ONLY World tab widgets that were registered via addTabWidget(Tab.WORLD, ...).
     * Used to prevent World widgets from rendering during super.render() so they can be manually rendered inside scissor.
     * Excludes footer Add button - it should always be visible.
     */
    private void setWorldWidgetsVisible(boolean visible) {
        for (TabbedWidget tw : tabbedWidgets) {
            if (tw.tab == Tab.WORLD && tw.listener instanceof net.minecraft.client.gui.components.AbstractWidget) {
                // Skip the Add World Layer button - it's a footer button that should always be visible
                if (tw.listener == addWorldLayerBtn) {
                    continue;
                }
                ((net.minecraft.client.gui.components.AbstractWidget) tw.listener).visible = visible;
            }
        }
    }
    
    /**
     * Render ONLY World widgets manually (same list as setWorldWidgetsVisible).
     * Used to manually render World widgets inside a scissor viewport.
     */
    private void renderWorldWidgetsManually(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        for (WorldLayerWidgets wlw : worldLayerWidgets) {
            for (net.minecraft.client.gui.components.AbstractWidget w : wlw.widgets) {
                if (w != null && this.renderables.contains(w) && w.visible) {
                    w.render(gfx, mouseX, mouseY, partialTick);
                }
            }
        }
    }
    
    /**
     * Hide/show ONLY Layers tab widgets that were registered via addTabWidget(Tab.LAYERS, ...).
     * Used to prevent Layers widgets from rendering during super.render() so they can be manually rendered inside scissor.
     * Excludes footer Add button - it should always be visible.
     */
    private void setLayersWidgetsVisible(boolean visible) {
        for (TabbedWidget tw : tabbedWidgets) {
            if (tw.tab == Tab.LAYERS && tw.listener instanceof net.minecraft.client.gui.components.AbstractWidget) {
                // Skip the Add Layer button - it's a footer button that should always be visible
                if (tw.listener == addLayerBtn) {
                    continue;
                }
                ((net.minecraft.client.gui.components.AbstractWidget) tw.listener).visible = visible;
            }
        }
    }
    
    /**
     * Render ONLY Layers widgets manually (same list as setLayersWidgetsVisible).
     * Used to manually render Layers widgets inside a scissor viewport.
     */
    private void renderLayersWidgetsManually(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        for (TabbedWidget tw : tabbedWidgets) {
            if (tw.tab == Tab.LAYERS && tw.listener instanceof net.minecraft.client.gui.components.AbstractWidget) {
                net.minecraft.client.gui.components.AbstractWidget w = (net.minecraft.client.gui.components.AbstractWidget) tw.listener;
                if (this.renderables.contains(w) && w.visible) {
                    w.render(gfx, mouseX, mouseY, partialTick);
                }
            }
        }
    }
    
    private void updateGeneralField(String fieldName, String value) {
        if (editorState.workingCopy == null) return;
        editorState.workingCopy = new ParticleDefinition(
            editorState.workingCopy.id(),
            editorState.workingCopy.layers(),
            editorState.workingCopy.worldLayers(),
            editorState.workingCopy.description(),
            editorState.workingCopy.styleHint(),
            fieldName.equals("displayName") ? value : editorState.workingCopy.displayName(),
            fieldName.equals("notes") ? value : editorState.workingCopy.notes()
        );
        editorState.markDirty();
        updateButtonStates();
        updatePreviewIfActive();
        // If Preview tab is active, refresh the preview
        if ("preview".equals(activeTab)) {
            updatePreviewBounds();
        }
    }
    
    // Helper methods for unified panel bounds
    private int getLayersPanelTop() {
        return top + 60; // Increased to account for descriptive label
    }
    
    private int getLayersPanelBottom() {
        // Footer is 2 rows: row1 at bottom-24-20-4=bottom-48, row2 at bottom-24
        // Panel bottom should leave room for both rows (48px total) plus small gap
        int footerHeight = 48; // 2 rows of buttons (20px each) + spacing (4px) + padding (4px)
        return bottom - footerHeight - 4; // 4px gap above footer
    }
    
    private int getWorldPanelLeft() {
        return left + leftPanelWidth + 20;
    }
    
    private int getWorldPanelRight() {
        return right - 20;
    }
    
    // World tab panel bounds - use same as Layers for identical scroll behavior
    private int getWorldPanelTop() {
        return top + 60; // Increased to account for descriptive label (same as Layers)
    }
    
    private int getWorldPanelBottom() {
        return getLayersPanelBottom();
    }
    
    private void rebuildLayerWidgets() {
        if (editorState.workingCopy == null) return;
        
        // Update viewport bounds BEFORE any card positioning (single source of truth)
        updateLayersViewportBounds();
        
        // Base Layers tab on world layers - one card per world layer
        // Enforce 1:1 mapping: ensure layers count matches worldLayers count
        editorState.workingCopy = ensureLayerCountMatchesWorldLayers(editorState.workingCopy);
        
        List<WorldLayerDefinition> worldLayers = editorState.workingCopy.worldLayers();
        List<ParticleLayerDefinition> guiLayers = editorState.workingCopy.layers();
        
        // Update scroll metrics (uses viewport height, so must come after viewport bounds)
        updateLayersScrollMetrics();
        
        // Define fixed Layers content panel
        int panelLeft = left + leftPanelWidth + 20;
        int panelRight = right - 20;
        int panelTop = getLayersPanelTop();
        int panelBottom = getLayersPanelBottom();
        int cardWidth = panelRight - panelLeft - 20;
        
        List<String> movementOptions = List.of(
            "FLOAT_UP", "BOUNCE_UP", "DRIFT_UP", "FLICKER_UP",
            "BURST", "SWIRL", "FALL_DOWN", "BUBBLE_POP", "MUSICAL_FLOAT", "DEFAULT"
        );
        
        // Build widgets for ALL layers (no viewport culling - widgets must exist for scrolling)
        for (int i = 0; i < worldLayers.size(); i++) {
            final int worldLayerIndex = i;
            final int layerIndex = i; // GUI layer index matches world layer index
            
            // Compute cardTop using single source of truth
            int cardTop = getLayersCardTop(i);
            int cardBottom = cardTop + LAYERS_CARD_HEIGHT;
            
            WorldLayerDefinition worldLayer = worldLayers.get(worldLayerIndex);
            ParticleLayerDefinition layer = guiLayers.get(layerIndex);
            LayerWidgets lw = new LayerWidgets(layerIndex, worldLayerIndex);
            
            // Get color lockout state from world layer's effect ID (vanilla particle ID)
            ResourceLocation worldEffectId = worldLayer.effect();
            
            // Store capabilities for this widget set (using world layer effect for color lockout)
            var caps = com.pastlands.cosmeticslite.client.editor.EffectCapabilitiesRegistry.get(worldEffectId);
            lw.capabilities = caps;
            
            // Use supportsTint from capabilities registry for color widget lockout
            boolean canColorize = caps != null && caps.supportsTint();
            
            int cardLeft = panelLeft + 10;
            int cardRight = panelLeft + 10 + cardWidth;
            
            // ===== HEADER ROW =====
            // Card label at top-left - show world layer number
            Component cardLabelText = Component.literal("Layer " + (worldLayerIndex + 1));
            int cardLabelY = cardTop + 3;
            lw.cardLabel = new com.pastlands.cosmeticslite.client.screen.parts.LabelWidget(
                    cardLeft + 2, cardLabelY, 
                    this.font.width(cardLabelText), this.font.lineHeight,
                    cardLabelText, LABEL_COLOR);
            addTabWidget(Tab.LAYERS, lw.cardLabel);
            
            // Optional hint text if capabilities are limited (below card label)
            int headerY = cardTop + 6 + LABEL_HEIGHT;
            if (!caps.supportsEditorMovement() || !caps.supportsColorOverride() || !caps.supportsTint()) {
                String hintText = "⚠ Vanilla-based particle – some editor controls are disabled.";
                int hintY = cardLabelY + this.font.lineHeight + 2;
                com.pastlands.cosmeticslite.client.screen.parts.LabelWidget hintLabel = 
                    new com.pastlands.cosmeticslite.client.screen.parts.LabelWidget(
                        cardLeft + 2, hintY,
                        this.font.width(hintText), this.font.lineHeight,
                        Component.literal(hintText), 0xFFFFAA00); // Muted yellow
                addTabWidget(Tab.LAYERS, hintLabel);
                lw.labels.add(hintLabel);
                // Adjust headerY to account for hint text
                headerY = hintY + this.font.lineHeight + 4;
            }
            
            // Movement dropdown centered horizontally between cardLeft and cardRight
            int movementDropdownWidth = 140;
            int movementDropdownX = (cardLeft + cardRight) / 2 - movementDropdownWidth / 2;
            
            // Create dropdown with callback that closes other dropdowns when opening
            final LayerWidgets finalLw = lw; // Capture for lambda
            lw.movementDropdown = new com.pastlands.cosmeticslite.client.screen.parts.StringDropdownWidget(
                movementDropdownX, headerY, movementDropdownWidth, 18,
                movementOptions,
                newMovement -> {
                    // Close all other dropdowns before handling selection
                    closeAllMovementDropdownsExcept(finalLw);
                    updateLayerMovement(layerIndex, newMovement);
                }
            );
            lw.movementDropdown.setSelected(layer.movement());
            // Disable movement dropdown if editor movement not supported
            lw.movementDropdown.setActive(caps.supportsEditorMovement());
            // Set callback to close other dropdowns when this one opens
            lw.movementDropdown.setOnExpandedChanged(() -> {
                if (lw.movementDropdown.isExpanded()) {
                    closeAllMovementDropdownsExcept(finalLw);
                }
            });
            
            addTabWidget(Tab.LAYERS, lw.movementDropdown);
            
            // Action button on far right: [Remove] only - no up/down buttons for Layers tab
            // (Reordering is disabled because index == world mapping)
            int btnY = headerY;
            int removeBtnWidth = 75;
            int buttonsStartX = cardRight - removeBtnWidth - 6;
            
            lw.removeBtn = Button.builder(Component.literal("Remove"), btn -> removeWorldLayer(worldLayerIndex))
                .bounds(buttonsStartX, btnY, removeBtnWidth, 18).build();
            addTabWidget(Tab.LAYERS, lw.removeBtn);
            
            // ===== COLORS ROW =====
            int colorsY = cardTop + 36 + LABEL_HEIGHT;
            
            // Colors label is drawn inline in renderLayersTab, not as a widget
            
            // Color swatches - use ColorSwatchButton widget
            // Color lockout: only enable if world layer's effect is colorizable
            int colorX = cardLeft + 100; // Start after "Colors:" label
            int swatchSize = 12; // Swatch size
            int swatchSpacing = 3; // Horizontal spacing between swatches
            for (int c = 0; c < layer.colors().size(); c++) {
                final int colorIndex = c;
                final int layerIdx = layerIndex;
                int swatchX = colorX + c * (swatchSize + swatchSpacing);
                ColorSwatchButton swatch = new ColorSwatchButton(
                    swatchX, colorsY, swatchSize, swatchSize,
                    layer.colors().get(colorIndex),
                    btn -> {
                        // Only allow color picker if colorizable
                        if (canColorize) {
                            openColorPicker(layerIdx, colorIndex);
                        }
                    }
                );
                swatch.setActive(canColorize);
                lw.colorButtons.add(swatch);
                addTabWidget(Tab.LAYERS, swatch);
            }
            // [+ Color] and [- Color] buttons
            int addColorX = colorX + layer.colors().size() * (swatchSize + swatchSpacing) + 5;
            Button addColorBtn = Button.builder(Component.literal("+ Color"), btn -> {
                if (canColorize) {
                    addLayerColor(layerIndex);
                }
            }).bounds(addColorX, colorsY, 60, 18).build();
            addColorBtn.active = canColorize;
            lw.colorButtons.add(addColorBtn);
            addTabWidget(Tab.LAYERS, addColorBtn);
            
            if (layer.colors().size() > 0) {
                Button removeColorBtn = Button.builder(Component.literal("- Color"), btn -> {
                    if (canColorize) {
                        removeLayerColor(layerIndex, layer.colors().size() - 1);
                    }
                }).bounds(addColorX + 65, colorsY, 60, 18).build();
                removeColorBtn.active = canColorize;
                lw.colorButtons.add(removeColorBtn);
                addTabWidget(Tab.LAYERS, removeColorBtn);
            }
            
            // ===== NUMERIC ROWS (2×3 grid) =====
            // Grid layout constants
            int firstRowOffset = 70 + LABEL_HEIGHT;
            int fieldWidth = 90;
            int fieldHeight = 18;
            int rowSpacing = 36;          // was 24 – give room for the label + gap
            int col1X = cardLeft + 100;
            int col2X = col1X + 120;
            int col3X = col1X + 240;
            int row1Y = cardTop + firstRowOffset;
            int row2Y = row1Y + rowSpacing;
            
            // Row 1: Lifespan, Interval, Scale (using NumberFieldWithSpinnerWidget)
            var lifespanField = new com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget(
                col1X, row1Y, fieldWidth, fieldHeight,
                layer.lifespan(),
                1.0, 200.0, 1.0,
                v -> String.format(Locale.ROOT, "%.1f", v),
                newVal -> updateLayerField(layerIndex, "lifespan", newVal.floatValue())
            );
            var spawnIntervalField = new com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget(
                col2X, row1Y, fieldWidth, fieldHeight,
                layer.spawnInterval(),
                0.01, 60.0, 0.1,
                v -> String.format(Locale.ROOT, "%.2f", v),
                newVal -> updateLayerField(layerIndex, "spawnInterval", newVal.floatValue())
            );
            var scaleField = new com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget(
                col3X, row1Y, fieldWidth, fieldHeight,
                layer.previewScale(),
                0.5, 3.0, 0.1,
                v -> String.format(Locale.ROOT, "%.2f", v),
                newVal -> updateLayerField(layerIndex, "previewScale", newVal.floatValue())
            );
            // Capabilities are informational only - always allow interaction
            
            // Row 2: Size, Speed, Weight (using NumberFieldWithSpinnerWidget)
            var sizeField = new com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget(
                col1X, row2Y, fieldWidth, fieldHeight,
                layer.size(),
                0.05, 2.0, 0.05,
                v -> String.format(Locale.ROOT, "%.2f", v),
                newVal -> updateLayerField(layerIndex, "size", newVal.floatValue())
            );
            var speedField = new com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget(
                col2X, row2Y, fieldWidth, fieldHeight,
                layer.speed(),
                0.0, 2.0, 0.05,
                v -> String.format(Locale.ROOT, "%.2f", v),
                newVal -> updateLayerField(layerIndex, "speed", newVal.floatValue())
            );
            var weightField = new com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget(
                col3X, row2Y, fieldWidth, fieldHeight,
                layer.weight(),
                0.0, 5.0, 0.1,
                v -> String.format(Locale.ROOT, "%.2f", v),
                newVal -> updateLayerField(layerIndex, "weight", newVal.floatValue())
            );
            
            // Add fields to lists (order: row1, then row2) and register as renderable widgets
            lw.numericFields.add(lifespanField);
            addTabWidget(Tab.LAYERS, lifespanField);
            lw.numericFields.add(spawnIntervalField);
            addTabWidget(Tab.LAYERS, spawnIntervalField);
            lw.numericFields.add(scaleField);
            addTabWidget(Tab.LAYERS, scaleField);
            lw.numericFields.add(sizeField);
            addTabWidget(Tab.LAYERS, sizeField);
            lw.numericFields.add(speedField);
            addTabWidget(Tab.LAYERS, speedField);
            lw.numericFields.add(weightField);
            addTabWidget(Tab.LAYERS, weightField);
            
            // Create field labels as widgets (render with fields, scroll correctly)
            // Use addLabelForField helper for numeric fields
            com.pastlands.cosmeticslite.client.screen.parts.LabelWidget lifespanLabel = addLabelForField("Lifespan:", lifespanField);
            addTabWidget(Tab.LAYERS, lifespanLabel);
            com.pastlands.cosmeticslite.client.screen.parts.LabelWidget intervalLabel = addLabelForField("Interval:", spawnIntervalField);
            addTabWidget(Tab.LAYERS, intervalLabel);
            com.pastlands.cosmeticslite.client.screen.parts.LabelWidget scaleLabel = addLabelForField("Scale:", scaleField);
            addTabWidget(Tab.LAYERS, scaleLabel);
            com.pastlands.cosmeticslite.client.screen.parts.LabelWidget sizeLabel = addLabelForField("Size:", sizeField);
            addTabWidget(Tab.LAYERS, sizeLabel);
            com.pastlands.cosmeticslite.client.screen.parts.LabelWidget speedLabel = addLabelForField("Speed:", speedField);
            addTabWidget(Tab.LAYERS, speedLabel);
            com.pastlands.cosmeticslite.client.screen.parts.LabelWidget weightLabel = addLabelForField("Weight:", weightField);
            addTabWidget(Tab.LAYERS, weightLabel);
            
            // Track the labels
            lw.fieldLabels.add(lifespanLabel);
            lw.fieldLabels.add(intervalLabel);
            lw.fieldLabels.add(scaleLabel);
            lw.fieldLabels.add(sizeLabel);
            lw.fieldLabels.add(speedLabel);
            lw.fieldLabels.add(weightLabel);
            lw.labels.add(lifespanLabel);
            lw.labels.add(intervalLabel);
            lw.labels.add(scaleLabel);
            lw.labels.add(sizeLabel);
            lw.labels.add(speedLabel);
            lw.labels.add(weightLabel);
            
            // Colors label - positioned to align with color swatches vertically
            Component colorsLabelText = Component.literal("Colors:");
            int colorsLabelX = cardLeft + 10;
            int colorsLabelY = colorsY + (swatchSize / 2) - (this.font.lineHeight / 2);
            com.pastlands.cosmeticslite.client.screen.parts.LabelWidget colorsLabel =
                new com.pastlands.cosmeticslite.client.screen.parts.LabelWidget(
                    colorsLabelX,
                    colorsLabelY,
                    this.font.width(colorsLabelText),
                    this.font.lineHeight,
                    colorsLabelText,
                    LABEL_COLOR
                );
            addTabWidget(Tab.LAYERS, colorsLabel);
            lw.fieldLabels.add(colorsLabel);
            lw.labels.add(colorsLabel);
            
            layerWidgets.add(lw);
        }
        
        // + Add Layer button (fixed at bottom, not inside scroll content)
        // Since Layers tab is now based on world layers, this adds a world layer
        String addLayerLabel = "+ Add Layer";
        int addLayerWidth = this.font.width(addLayerLabel) + 12;
        addLayerBtn = Button.builder(Component.literal(addLayerLabel), btn -> addWorldLayer())
            .bounds(0, 0, addLayerWidth, 20).build();
        addLayerBtn.visible = true; // Footer button - always visible
        addTabWidget(Tab.LAYERS, addLayerBtn);
        // Layout footer buttons with add layer button
        layoutFooterButtons(addLayerBtn, saveBtn, revertBtn, publishBtn, previewBtn, deleteBtn);
        
        // Refresh color lockouts after rebuild
        refreshLayerColorLockouts();
    }
    
    private com.pastlands.cosmeticslite.client.screen.parts.LabelWidget addLabelForField(String text, net.minecraft.client.gui.components.AbstractWidget field) {
        Component labelText = Component.literal(text);

        int labelX = field.getX() + 2;
        int labelY = field.getY() - this.font.lineHeight - 2;

        com.pastlands.cosmeticslite.client.screen.parts.LabelWidget label =
            new com.pastlands.cosmeticslite.client.screen.parts.LabelWidget(
                labelX,
                labelY,
                this.font.width(labelText),
                this.font.lineHeight,
                labelText,
                LABEL_COLOR
            );

        // Do NOT register here - caller must register via addTabWidget(Tab.LAYERS, ...)
        return label;
    }
    
    // Slightly larger vertical gap for World tab labels so they don't touch boxes
    private com.pastlands.cosmeticslite.client.screen.parts.LabelWidget addWorldLabelForField(String text, net.minecraft.client.gui.components.AbstractWidget field) {
        int labelX = field.getX() + 2;
        int labelY = field.getY() - this.font.lineHeight - 4; // full font height + gap to prevent overlap

        Component labelText = Component.literal(text);
        com.pastlands.cosmeticslite.client.screen.parts.LabelWidget label =
            new com.pastlands.cosmeticslite.client.screen.parts.LabelWidget(
                labelX,
                labelY,
                this.font.width(labelText),
                this.font.lineHeight,
                labelText,
                LABEL_COLOR
            );

        addRenderableWidget(label);
        return label;
    }
    
    private EditBox createNumericField(int x, int y, int w, int h, String initialValue, java.util.function.Consumer<Float> onUpdate) {
        EditBox field = new EditBox(this.font, x, y, w, h, Component.literal(""));
        field.setValue(initialValue);
        field.setEditable(true);
        field.setFilter(s -> s.matches("[0-9.]*"));
        field.setResponder(text -> {
            try {
                float val = Float.parseFloat(text);
                onUpdate.accept(val);
            } catch (NumberFormatException e) {
                // Invalid, ignore
            }
        });
        addRenderableWidget(field);
        return field;
    }
    
    private void updateLayerMovement(int layerIndex, String newMovement) {
        if (editorState.workingCopy == null) return;
        List<ParticleLayerDefinition> newLayers = new ArrayList<>(editorState.workingCopy.layers());
        ParticleLayerDefinition old = newLayers.get(layerIndex);
        
        // Don't update if movement hasn't changed
        if (old.movement().equals(newMovement)) {
            return;
        }
        
        String oldMovement = old.movement();
        newLayers.set(layerIndex, new ParticleLayerDefinition(
            newMovement, old.colors(), old.lifespan(), old.spawnInterval(),
            old.size(), old.speed(), old.weight(), old.previewScale()
        ));
        
        CosmeticsLite.LOGGER.info("[ParticleLab] Layer {} movement changed: {} -> {}", layerIndex, oldMovement, newMovement);
        updateWorkingCopyLayers(newLayers);
    }
    
    /**
     * Close all movement dropdowns except the specified one.
     */
    private void closeAllMovementDropdownsExcept(LayerWidgets except) {
        for (LayerWidgets lw : layerWidgets) {
            if (lw != except && lw.movementDropdown != null && lw.movementDropdown.isExpanded()) {
                lw.movementDropdown.setExpanded(false);
            }
        }
    }
    
    /**
     * Close all movement dropdowns.
     */
    private void closeAllMovementDropdowns() {
        for (LayerWidgets lw : layerWidgets) {
            if (lw.movementDropdown != null && lw.movementDropdown.isExpanded()) {
                lw.movementDropdown.setExpanded(false);
            }
        }
    }
    
    private void updateLayerField(int layerIndex, String fieldName, float value) {
        if (editorState.workingCopy == null) return;
        List<ParticleLayerDefinition> newLayers = new ArrayList<>(editorState.workingCopy.layers());
        ParticleLayerDefinition old = newLayers.get(layerIndex);
        ParticleLayerDefinition updated = switch (fieldName) {
            case "lifespan" -> new ParticleLayerDefinition(old.movement(), old.colors(), value, old.spawnInterval(),
                old.size(), old.speed(), old.weight(), old.previewScale());
            case "spawnInterval" -> new ParticleLayerDefinition(old.movement(), old.colors(), old.lifespan(), value,
                old.size(), old.speed(), old.weight(), old.previewScale());
            case "size" -> new ParticleLayerDefinition(old.movement(), old.colors(), old.lifespan(), old.spawnInterval(),
                value, old.speed(), old.weight(), old.previewScale());
            case "speed" -> new ParticleLayerDefinition(old.movement(), old.colors(), old.lifespan(), old.spawnInterval(),
                old.size(), value, old.weight(), old.previewScale());
            case "weight" -> new ParticleLayerDefinition(old.movement(), old.colors(), old.lifespan(), old.spawnInterval(),
                old.size(), old.speed(), value, old.previewScale());
            case "previewScale" -> new ParticleLayerDefinition(old.movement(), old.colors(), old.lifespan(), old.spawnInterval(),
                old.size(), old.speed(), old.weight(), value);
            default -> old;
        };
        newLayers.set(layerIndex, updated);
        updateWorkingCopyLayers(newLayers);
    }
    
    private void updateWorkingCopyLayers(List<ParticleLayerDefinition> newLayers) {
        editorState.workingCopy = new ParticleDefinition(
            editorState.workingCopy.id(),
            newLayers,
            editorState.workingCopy.worldLayers(),
            editorState.workingCopy.description(),
            editorState.workingCopy.styleHint(),
            editorState.workingCopy.displayName(),
            editorState.workingCopy.notes()
        );
        editorState.markDirty();
        updateButtonStates();
        // Mark preview dirty so it refreshes from working copy
        markPreviewDirty();
        // If Preview tab is active, refresh the preview
        if ("preview".equals(activeTab)) {
            updatePreviewBounds();
        }
    }
    
    private void addLayerColor(int layerIndex) {
        if (editorState.workingCopy == null) return;
        List<ParticleLayerDefinition> newLayers = new ArrayList<>(editorState.workingCopy.layers());
        ParticleLayerDefinition old = newLayers.get(layerIndex);
        List<Integer> newColors = new ArrayList<>(old.colors());
        // Append new color (default white with full alpha)
        newColors.add(0xFFFFFFFF);
        newLayers.set(layerIndex, new ParticleLayerDefinition(
            old.movement(), newColors, old.lifespan(), old.spawnInterval(),
            old.size(), old.speed(), old.weight(), old.previewScale()
        ));
        editorState.workingCopy = new ParticleDefinition(
            editorState.workingCopy.id(),
            newLayers,
            editorState.workingCopy.worldLayers(),
            editorState.workingCopy.description(),
            editorState.workingCopy.styleHint(),
            editorState.workingCopy.displayName(),
            editorState.workingCopy.notes()
        );
        editorState.markDirty();
        updateButtonStates();
        updatePreviewIfActive();
        rebuildTabWidgets();
    }
    
    
    private void openColorPicker(int layerIndex, int colorIndex) {
        if (editorState.workingCopy == null || editorState.selectedId == null) return;
        ParticleLayerDefinition layer = editorState.workingCopy.layers().get(layerIndex);
        if (colorIndex < 0 || colorIndex >= layer.colors().size()) return;
        
        // Build context for color selection
        pendingColorSelection = new ColorSelectionContext(
            editorState.selectedId,
            layerIndex,
            colorIndex
        );
        
        int currentColor = layer.colors().get(colorIndex);
        
        // Open color edit screen with callback
        net.minecraft.client.Minecraft.getInstance().setScreen(
            new ParticleColorEditScreen(
                ParticleLabScreen.this, // parent screen
                currentColor,
                this::onColorPicked,
                () -> {
                    // Cancel: clear context without applying changes
                    pendingColorSelection = null;
                }
            )
        );
    }
    
    /**
     * Called when user confirms a color selection from the picker.
     * Updates the working copy and refreshes the UI.
     * IMPORTANT: Never reloads from registry - only works with working copy.
     */
    public void onColorPicked(int newColor) {
        if (pendingColorSelection == null) return;
        
        var ctx = pendingColorSelection;
        
        // 1. Get working copy of the current definition (never reload from registry)
        if (editorState.workingCopy == null || 
            !editorState.selectedId.equals(ctx.definitionId()) ||
            ctx.layerIndex() < 0 ||
            ctx.layerIndex() >= editorState.workingCopy.layers().size()) {
            pendingColorSelection = null;
            return;
        }
        
        ParticleLayerDefinition layer = editorState.workingCopy.layers().get(ctx.layerIndex());
        if (ctx.colorIndex() < 0 || 
            ctx.colorIndex() >= layer.colors().size()) {
            pendingColorSelection = null;
            return;
        }
        
        // 2. Get old color for debug logging
        int oldColor = layer.colors().get(ctx.colorIndex());
        
        // 3. Update the color in workingCopy
        List<ParticleLayerDefinition> newLayers = new ArrayList<>(editorState.workingCopy.layers());
        ParticleLayerDefinition old = newLayers.get(ctx.layerIndex());
        List<Integer> newColors = new ArrayList<>(old.colors());
        newColors.set(ctx.colorIndex(), newColor);
        newLayers.set(ctx.layerIndex(), new ParticleLayerDefinition(
            old.movement(), newColors, old.lifespan(), old.spawnInterval(),
            old.size(), old.speed(), old.weight(), old.previewScale()
        ));
        editorState.workingCopy = new ParticleDefinition(
            editorState.workingCopy.id(),
            newLayers,
            editorState.workingCopy.worldLayers(),
            editorState.workingCopy.description(),
            editorState.workingCopy.styleHint(),
            editorState.workingCopy.displayName(),
            editorState.workingCopy.notes()
        );
        
        // 4. Debug logging
        CosmeticsLite.LOGGER.info("[ParticleLab] Color picked for {} layer={} index={} old={} new={}",
            ctx.definitionId(), ctx.layerIndex(), ctx.colorIndex(),
            String.format("#%08X", oldColor),
            String.format("#%08X", newColor));
        
        // 5. Mark working copy dirty so Save & Sync will send it
        editorState.markDirty();
        
        // 6. Always update preview override (even if preview not active, in case it starts later)
        ParticlePreviewState.setPreviewOverride(editorState.selectedId, editorState.workingCopy);
        
        // 7. Update preview state if active
        updatePreviewIfActive();
        
        // 8. Tell the Layers tab UI to refresh its swatches from the working copy
        rebuildTabWidgets(); // This rebuilds from workingCopy, not registry
        
        // 9. Update button states
        updateButtonStates();
        
        // 10. Clear context
        pendingColorSelection = null;
    }
    
    
    private void removeLayerColor(int layerIndex, int colorIndex) {
        if (editorState.workingCopy == null) return;
        List<ParticleLayerDefinition> newLayers = new ArrayList<>(editorState.workingCopy.layers());
        ParticleLayerDefinition old = newLayers.get(layerIndex);
        List<Integer> newColors = new ArrayList<>(old.colors());
        // Remove last color if list size > 1
        if (newColors.size() > 1) {
            newColors.remove(newColors.size() - 1); // Remove last color
            newLayers.set(layerIndex, new ParticleLayerDefinition(
                old.movement(), newColors, old.lifespan(), old.spawnInterval(),
                old.size(), old.speed(), old.weight(), old.previewScale()
            ));
            editorState.workingCopy = new ParticleDefinition(
                editorState.workingCopy.id(),
                newLayers,
                editorState.workingCopy.worldLayers(),
                editorState.workingCopy.description(),
                editorState.workingCopy.styleHint(),
                editorState.workingCopy.displayName(),
                editorState.workingCopy.notes()
            );
            editorState.markDirty();
            updateButtonStates();
            updatePreviewIfActive();
            rebuildTabWidgets();
        }
    }
    
    private void removeLayer(int layerIndex) {
        if (editorState.workingCopy == null) return;
        List<ParticleLayerDefinition> newLayers = new ArrayList<>(editorState.workingCopy.layers());
        if (newLayers.size() > 1) {
            newLayers.remove(layerIndex);
            updateWorkingCopyLayers(newLayers);
            rebuildTabWidgets();
            updatePreviewIfActive();
        }
    }
    
    private void moveLayer(int layerIndex, int direction) {
        if (editorState.workingCopy == null) return;
        List<ParticleLayerDefinition> newLayers = new ArrayList<>(editorState.workingCopy.layers());
        int newIndex = layerIndex + direction;
        if (newIndex >= 0 && newIndex < newLayers.size()) {
            ParticleLayerDefinition temp = newLayers.get(layerIndex);
            newLayers.set(layerIndex, newLayers.get(newIndex));
            newLayers.set(newIndex, temp);
            updateWorkingCopyLayers(newLayers);
            rebuildTabWidgets();
            updatePreviewIfActive();
        }
    }
    
    /**
     * Cached list of all registered particle effect ids (vanilla + modded), sorted.
     */
    private static List<ResourceLocation> allEffectIds = null;
    
    /**
     * Returns all registered particle effect ids (vanilla + modded), sorted alphabetically.
     * Uses ForgeRegistries to include modded particles, falls back to BuiltInRegistries if needed.
     */
    private static List<ResourceLocation> getAllEffectIds() {
        if (allEffectIds == null) {
            List<ResourceLocation> result = new ArrayList<>();
            
            // Prefer Forge registry if present (includes modded particles)
            try {
                if (net.minecraftforge.registries.ForgeRegistries.PARTICLE_TYPES != null) {
                    // Use getKeys() for efficiency
                    for (ResourceLocation id : net.minecraftforge.registries.ForgeRegistries.PARTICLE_TYPES.getKeys()) {
                        if (id != null) {
                            result.add(id);
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Fall back below
            }
            
            // Fallback to vanilla registry (covers dev env and ensures we get all entries)
            for (ResourceLocation id : BuiltInRegistries.PARTICLE_TYPE.keySet()) {
                if (id != null && !result.contains(id)) {
                    result.add(id);
                }
            }
            
            // Sort alphabetically by full id string
            result.sort(Comparator.comparing(ResourceLocation::toString, String.CASE_INSENSITIVE_ORDER));
            allEffectIds = Collections.unmodifiableList(new ArrayList<>(result));
            
            // One-time sanity check: verify important vanilla effects are present
            boolean hasFlame = allEffectIds.contains(ResourceLocation.fromNamespaceAndPath("minecraft", "flame"));
            boolean hasWitch = allEffectIds.contains(ResourceLocation.fromNamespaceAndPath("minecraft", "witch"));
            CosmeticsLite.LOGGER.info("[ParticleLab] Effect list sanity: size={} hasFlame={} hasWitch={}", 
                allEffectIds.size(), hasFlame, hasWitch);
            CosmeticsLite.LOGGER.info("[ParticleLab] Effect registry contains {} particle type(s)", allEffectIds.size());
        }
        return allEffectIds;
    }
    
    /**
     * Generate a human-readable description for a world layer from its effect and style.
     * Example: "white particles on wings around the player"
     */
    private String generateWorldLayerDescription(String effectId, String style) {
        // Extract particle name from effect ID (e.g., "minecraft:flame" -> "flame")
        String particleName = effectId.contains(":") ? effectId.substring(effectId.indexOf(':') + 1) : effectId;
        
        // Map common particle names to descriptive terms
        String particleDesc;
        switch (particleName.toLowerCase()) {
            case "flame":
            case "fire":
                particleDesc = "orange flames";
                break;
            case "snowflake":
                particleDesc = "white particles";
                break;
            case "heart":
                particleDesc = "hearts";
                break;
            case "happy_villager":
            case "happyvillager":
                particleDesc = "green sparkles";
                break;
            case "smoke":
                particleDesc = "gray smoke";
                break;
            case "cloud":
                particleDesc = "white clouds";
                break;
            default:
                particleDesc = particleName.replace("_", " ") + " particles";
                break;
        }
        
        // Map style to location description
        String locationDesc;
        switch (style.toLowerCase()) {
            case "wings":
                locationDesc = "on wings around the player";
                break;
            case "cape":
                locationDesc = "along the cape behind the player";
                break;
            case "halo":
                locationDesc = "as a halo above the player";
                break;
            case "orbit":
                locationDesc = "orbiting around the player";
                break;
            case "aura":
                locationDesc = "in an aura around the player";
                break;
            case "trail":
                locationDesc = "trailing behind the player";
                break;
            case "ground":
                locationDesc = "on the ground beneath the player";
                break;
            case "belt":
                locationDesc = "in a belt around the player's waist";
                break;
            case "spiral":
                locationDesc = "in a spiral pattern around the player";
                break;
            case "column":
                locationDesc = "in a vertical column around the player";
                break;
            default:
                locationDesc = "around the player";
                break;
        }
        
        return particleDesc + " " + locationDesc;
    }
    
    /**
     * Helper to register a World widget and add it to the widget list in one call.
     * Makes it impossible to forget either step.
     */
    private <T extends net.minecraft.client.gui.components.AbstractWidget> T addWorldWidget(WorldLayerWidgets wlw, T widget) {
        addTabWidget(Tab.WORLD, widget);
        wlw.widgets.add(widget);
        return widget;
    }
    
    private void rebuildWorldLayerWidgets() {
        if (editorState.workingCopy == null) return;
        
        // Clear all World tab widgets first - prevents zombie widgets
        clearTabWidgets(Tab.WORLD);
        
        // Also clear our tracking list
        worldLayerWidgets.clear();

        // Update viewport bounds BEFORE any card positioning (single source of truth)
        updateWorldViewportBounds();
        
        // Update scroll metrics (uses viewport height, so must come after viewport bounds)
        updateWorldScrollMetrics();

        int panelLeft   = getWorldPanelLeft();
        int panelRight  = getWorldPanelRight();
        int panelTop = getWorldPanelTop();
        int panelBottom = getWorldPanelBottom();

        int cardWidth = panelRight - panelLeft - 20;

            // Get all particle effects from registry (vanilla + modded), sorted alphabetically
            List<ResourceLocation> effectIds = getAllEffectIds();
            List<String> effectOptions = effectIds.stream()
                .map(id -> {
                    // Strip "minecraft:" prefix for cleaner display
                    if ("minecraft".equals(id.getNamespace())) {
                        return id.getPath();
                    }
                    return id.toString();
                })
                .collect(Collectors.toList());
            

        // Style options
        List<String> styleOptions = List.of(
            "halo", "cape", "wings", "orbit", "aura", "trail", "ground", "belt", "spiral", "column", "default"
        );
        
        // Build widgets for all world layers with viewport culling
        for (int i = 0; i < editorState.workingCopy.worldLayers().size(); i++) {
            final int layerIndex = i;

            // Compute cardTop using single source of truth
            int cardTop = getWorldCardTop(i);
            int cardBottom = cardTop + WORLD_CARD_HEIGHT;
            
            // Viewport culling: skip cards fully off screen
            // Only cull if viewport is valid, otherwise show all cards
            if (worldViewportBottom > worldViewportTop) {
                if (cardBottom < worldViewportTop || cardTop > worldViewportBottom) {
                    continue;
                }
            }

            WorldLayerDefinition worldLayer = editorState.workingCopy.worldLayers().get(i);
            WorldLayerWidgets wlw = new WorldLayerWidgets(layerIndex);
            wlw.cardIndex = i;

            int wlwCardLeft  = panelLeft + 10;
            int wlwCardRight = wlwCardLeft + cardWidth;

            // Card label: just "World Layer N" (no effect/style summary)
            String cardLabelTextStr = "World Layer " + (layerIndex + 1);
            Component cardLabelText = Component.literal(cardLabelTextStr);
            int headerX = wlwCardLeft + 2;
            int headerY = cardTop + 3; // Use cardTop instead of cardY
            int headerWidth = this.font.width(cardLabelText);
            wlw.headerX = headerX;
            wlw.headerY = headerY;
            wlw.headerWidth = headerWidth;
            wlw.cardLabel = new com.pastlands.cosmeticslite.client.screen.parts.LabelWidget(
                    headerX, headerY,
                    headerWidth, this.font.lineHeight,
                    cardLabelText, LABEL_COLOR);
            addWorldWidget(wlw, wlw.cardLabel);
            
            // Generate description for tooltip from effect + style
            String effectId = worldLayer.effect().toString();
            String styleName = worldLayer.style();
            wlw.description = generateWorldLayerDescription(effectId, styleName);
            
            // Step 1: Fix label/field grid and column spacing
            // Centralize column/row constants
            int cardX = wlwCardLeft + 12;
            int contentBaseY = cardTop + 16; // after "World Layer N" label (use cardTop, not cardY)

            // Layout: derive a 3-column grid from the actual card width so columns never drift or overlap.
            // Keep everything inside the card's inner padding (12px on each side).
            int rowGap = 6;
            int colGap = 14;

            int innerRight = wlwCardRight - 12;
            int available = innerRight - cardX;

            // Prefer ~100px columns, but clamp so 3 columns always fit.
            int colWidth = (available - (2 * colGap)) / 3;
            colWidth = Math.max(88, Math.min(110, colWidth));

            // Any leftover space becomes a gentle right-shift for col2/col3 (keeps things from crowding col1).
            int extra = available - ((3 * colWidth) + (2 * colGap));
            int rightShift = Math.max(0, extra / 2);

            int col1X = cardX;
            int col2X = col1X + colWidth + colGap + rightShift;
            int col3X = col2X + colWidth + colGap;

            // Keep "wide" widgets in-column to avoid label/field cross-talk.
            int wideWidth = colWidth;        // auto aligns based on col2X
            
            int labelHeight = this.font.lineHeight; // ~9 or 10
            int fieldHeight = 18;
            
            // Row Y positions - label + field pattern
            int row1LabelY = contentBaseY;
            int row1FieldY = row1LabelY + labelHeight + 2;
            
            int row2LabelY = row1FieldY + fieldHeight + rowGap;
            int row2FieldY = row2LabelY + labelHeight + 2;
            
            int row3LabelY = row2FieldY + fieldHeight + rowGap;
            int row3FieldY = row3LabelY + labelHeight + 2;
            
            int row4LabelY = row3FieldY + fieldHeight + rowGap;
            int row4FieldY = row4LabelY + labelHeight + 2;
            
            int row5LabelY = row4FieldY + fieldHeight + rowGap - 4; // tighten gap (keeps 'Horizontal' closer to Offset X)
            int row5FieldY = row5LabelY + labelHeight + 2;
            
            int row6LabelY = row5FieldY + fieldHeight + rowGap;
            int row6FieldY = row6LabelY + labelHeight + 2;
            
            int row7LabelY = row6FieldY + fieldHeight + rowGap;
            int row7FieldY = row7LabelY + labelHeight + 2;
            
            // buttonsY is now calculated after Curve placement (see below)
            
            // ---- Row 1: Effect + Style dropdowns ----
            // Debug log: effect dropdown entry count (one-time per rebuild)
            if (i == 0) {
                CosmeticsLite.LOGGER.info("[ParticleLab] Effect dropdown has {} entries", effectOptions.size());
            }
            
            // Effect label and dropdown
            Component effectLabelText = Component.literal("Effect:");
            com.pastlands.cosmeticslite.client.screen.parts.LabelWidget effectLabel =
                new com.pastlands.cosmeticslite.client.screen.parts.LabelWidget(
                    col1X, row1LabelY,
                    this.font.width(effectLabelText), this.font.lineHeight,
                    effectLabelText, LABEL_COLOR);
            effectLabel.setX(col1X);
            effectLabel.setY(row1LabelY);
            addWorldWidget(wlw, effectLabel);
            wlw.fieldLabels.add(effectLabel);
            wlw.labels.add(effectLabel);
            
            wlw.effectDropdown = new com.pastlands.cosmeticslite.client.screen.parts.StringDropdownWidget(
                    col1X, row1FieldY, wideWidth, 18,
                    effectOptions,
                    newEffect -> updateWorldLayerEffect(layerIndex, newEffect)
            );
            wlw.effectDropdown.setX(col1X);
            wlw.effectDropdown.setY(row1FieldY);
            wlw.effectDropdown.setWidth(wideWidth);
            
            // Set selected effect, safely handling case where it might not be in the registry
            ResourceLocation currentEffectId = worldLayer.effect();
            int selectedIndex = 0;
            if (currentEffectId != null) {
                for (int idx = 0; idx < effectIds.size(); idx++) {
                    if (effectIds.get(idx).equals(currentEffectId)) {
                        selectedIndex = idx;
                        break;
                    }
                }
            }
            // Set using the string at that index
            if (selectedIndex >= 0 && selectedIndex < effectOptions.size()) {
                wlw.effectDropdown.setSelected(effectOptions.get(selectedIndex));
            } else if (!effectOptions.isEmpty()) {
                wlw.effectDropdown.setSelected(effectOptions.get(0));
            }
            addWorldWidget(wlw, wlw.effectDropdown);
            
            // Check if current effect supports colorization and store state
            var caps = com.pastlands.cosmeticslite.client.editor.EffectCapabilitiesRegistry.get(currentEffectId);
            wlw.canColorize = caps != null && caps.supportsTint();
            updateWorldLayerColorWidgets(wlw);

            // Style label and dropdown
            Component styleLabelText = Component.literal("Style:");
            com.pastlands.cosmeticslite.client.screen.parts.LabelWidget styleLabel =
                new com.pastlands.cosmeticslite.client.screen.parts.LabelWidget(
                    col2X, row1LabelY,
                    this.font.width(styleLabelText), this.font.lineHeight,
                    styleLabelText, LABEL_COLOR);
            styleLabel.setX(col2X);
            styleLabel.setY(row1LabelY);
            addWorldWidget(wlw, styleLabel);
            wlw.fieldLabels.add(styleLabel);
            wlw.labels.add(styleLabel);
            
            wlw.styleDropdown = new com.pastlands.cosmeticslite.client.screen.parts.StringDropdownWidget(
                    col2X, row1FieldY, wideWidth, 18,
                    styleOptions,
                    newStyle -> updateWorldLayerStyle(layerIndex, newStyle)
            );
            wlw.styleDropdown.setX(col2X);
            wlw.styleDropdown.setY(row1FieldY);
            wlw.styleDropdown.setWidth(wideWidth);
            wlw.styleDropdown.setSelected(worldLayer.style());
            addWorldWidget(wlw, wlw.styleDropdown);

            // ---- Row 2: Radius, Base Height, Stretch ----
            // Radius
            Component radiusLabelText = Component.literal("Radius:");
            com.pastlands.cosmeticslite.client.screen.parts.LabelWidget radiusLabel =
                new com.pastlands.cosmeticslite.client.screen.parts.LabelWidget(
                    col1X, row2LabelY,
                    this.font.width(radiusLabelText), this.font.lineHeight,
                    radiusLabelText, LABEL_COLOR);
            radiusLabel.setX(col1X);
            radiusLabel.setY(row2LabelY);
            addWorldWidget(wlw, radiusLabel);
            
            wlw.radiusField = new com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget(
                col1X, row2FieldY, colWidth, fieldHeight,
                worldLayer.radius(),
                0.05, 2.0, 0.05,
                v -> String.format(java.util.Locale.ROOT, "%.2f", v),
                newVal -> updateWorldLayerField(layerIndex, "radius", newVal.floatValue())
            );
            wlw.radiusField.setX(col1X);
            wlw.radiusField.setY(row2FieldY);
            wlw.radiusField.setWidth(colWidth);
            wlw.numericFields.add(wlw.radiusField);
            addWorldWidget(wlw, wlw.radiusField);

            // Base Height
            Component baseHeightLabelText = Component.literal("Base Height:");
            com.pastlands.cosmeticslite.client.screen.parts.LabelWidget baseHeightLabel =
                new com.pastlands.cosmeticslite.client.screen.parts.LabelWidget(
                    col2X, row2LabelY,
                    this.font.width(baseHeightLabelText), this.font.lineHeight,
                    baseHeightLabelText, LABEL_COLOR);
            baseHeightLabel.setX(col2X);
            baseHeightLabel.setY(row2LabelY);
            addWorldWidget(wlw, baseHeightLabel);
            
            wlw.baseHeightField = new com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget(
                col2X, row2FieldY, colWidth, fieldHeight,
                worldLayer.baseHeight(),
                0.0, 2.0, 0.05,
                v -> String.format(java.util.Locale.ROOT, "%.2f", v),
                newVal -> updateWorldLayerField(layerIndex, "baseHeight", newVal.floatValue())
            );
            wlw.baseHeightField.setX(col2X);
            wlw.baseHeightField.setY(row2FieldY);
            wlw.baseHeightField.setWidth(colWidth);
            wlw.numericFields.add(wlw.baseHeightField);
            addWorldWidget(wlw, wlw.baseHeightField);

            // Stretch
            Component stretchLabelText = Component.literal("Stretch:");
            com.pastlands.cosmeticslite.client.screen.parts.LabelWidget stretchLabel =
                new com.pastlands.cosmeticslite.client.screen.parts.LabelWidget(
                    col3X, row2LabelY,
                    this.font.width(stretchLabelText), this.font.lineHeight,
                    stretchLabelText, LABEL_COLOR);
            stretchLabel.setX(col3X);
            stretchLabel.setY(row2LabelY);
            addWorldWidget(wlw, stretchLabel);
            
            wlw.stretchField =
    new com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget(
        col3X, row2FieldY, colWidth, fieldHeight,
        10.0,
        0.0, 20.0, 0.05,
        v -> String.format(java.util.Locale.ROOT, "%.2f", v),
        newVal -> {
            float clamped = Math.max(0.0f, newVal.floatValue());
            updateWorldLayerField(layerIndex, "heightStretch", clamped);
        }
    );
            wlw.stretchField.setX(col3X);
            wlw.stretchField.setY(row2FieldY);
            wlw.stretchField.setWidth(colWidth);
            wlw.numericFields.add(wlw.stretchField);
            addWorldWidget(wlw, wlw.stretchField);

            // ---- Row 3: Count, Speed Multiplier ----
            // Count
            Component countLabelText = Component.literal("Count:");
            com.pastlands.cosmeticslite.client.screen.parts.LabelWidget countLabel =
                new com.pastlands.cosmeticslite.client.screen.parts.LabelWidget(
                    col1X, row3LabelY,
                    this.font.width(countLabelText), this.font.lineHeight,
                    countLabelText, LABEL_COLOR);
            countLabel.setX(col1X);
            countLabel.setY(row3LabelY);
            addWorldWidget(wlw, countLabel);
            
            wlw.countField = new com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget(
                col1X, row3FieldY, colWidth, fieldHeight,
                worldLayer.count(),
                1.0, 64.0, 1.0,
                v -> String.format(java.util.Locale.ROOT, "%.0f", v),
                newVal -> updateWorldLayerField(layerIndex, "count", newVal.floatValue())
            );
            wlw.countField.setX(col1X);
            wlw.countField.setY(row3FieldY);
            wlw.countField.setWidth(colWidth);
            wlw.numericFields.add(wlw.countField);
            addWorldWidget(wlw, wlw.countField);

            // Speed Multiplier
            Component speedLabelText = Component.literal("Speed Multiplier:");
            com.pastlands.cosmeticslite.client.screen.parts.LabelWidget speedLabel =
                new com.pastlands.cosmeticslite.client.screen.parts.LabelWidget(
                    col2X, row3LabelY,
                    this.font.width(speedLabelText), this.font.lineHeight,
                    speedLabelText, LABEL_COLOR);
            speedLabel.setX(col2X);
            speedLabel.setY(row3LabelY);
            addWorldWidget(wlw, speedLabel);
            
            // Speed Multiplier: default to 1.0 if current value is 0 (backward compatibility)
            float speedValue = worldLayer.speedY();
            if (speedValue <= 0.0f) {
                speedValue = 1.0f; // Default multiplier
            }
            wlw.speedMultiplierField = new com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget(
                col2X, row3FieldY, colWidth, fieldHeight,
                speedValue,
                0.001, 3.0, 0.001,
                v -> String.format(java.util.Locale.ROOT, "%.3f", v),
                newVal -> {
                    // Clamp to 0.001-3.0 range for multiplier
                    float clamped = Math.max(0.001f, Math.min(3.0f, newVal.floatValue()));
                    updateWorldLayerField(layerIndex, "speedY", clamped);
                }
            );
            wlw.speedMultiplierField.setX(col2X);
            wlw.speedMultiplierField.setY(row3FieldY);
            wlw.speedMultiplierField.setWidth(colWidth);
            wlw.numericFields.add(wlw.speedMultiplierField);
            addWorldWidget(wlw, wlw.speedMultiplierField);
            
            // ---- Row 4: Offset X, Y, Z ----
            // Offset X
            Component offsetXLabelText = Component.literal("Offset X:");
            com.pastlands.cosmeticslite.client.screen.parts.LabelWidget offsetXLabel =
                new com.pastlands.cosmeticslite.client.screen.parts.LabelWidget(
                    col1X, row4LabelY,
                    this.font.width(offsetXLabelText), this.font.lineHeight,
                    offsetXLabelText, LABEL_COLOR);
            offsetXLabel.setX(col1X);
            offsetXLabel.setY(row4LabelY);
            addWorldWidget(wlw, offsetXLabel);
            
            wlw.offsetXField = new com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget(
                col1X, row4FieldY, colWidth, fieldHeight,
                worldLayer.offsetX(),
                -2.0, 2.0, 0.05,
                v -> String.format(java.util.Locale.ROOT, "%.2f", v),
                newVal -> updateWorldLayerField(layerIndex, "offsetX", newVal.floatValue())
            );
            wlw.offsetXField.setX(col1X);
            wlw.offsetXField.setY(row4FieldY);
            wlw.offsetXField.setWidth(colWidth);
            wlw.numericFields.add(wlw.offsetXField);
            addWorldWidget(wlw, wlw.offsetXField);
            
            // Offset Y
            Component offsetYLabelText = Component.literal("Offset Y:");
            com.pastlands.cosmeticslite.client.screen.parts.LabelWidget offsetYLabel =
                new com.pastlands.cosmeticslite.client.screen.parts.LabelWidget(
                    col2X, row4LabelY,
                    this.font.width(offsetYLabelText), this.font.lineHeight,
                    offsetYLabelText, LABEL_COLOR);
            offsetYLabel.setX(col2X);
            offsetYLabel.setY(row4LabelY);
            addWorldWidget(wlw, offsetYLabel);
            
            wlw.offsetYField = new com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget(
                col2X, row4FieldY, colWidth, fieldHeight,
                worldLayer.offsetY(),
                -2.0, 2.0, 0.05,
                v -> String.format(java.util.Locale.ROOT, "%.2f", v),
                newVal -> updateWorldLayerField(layerIndex, "offsetY", newVal.floatValue())
            );
            wlw.offsetYField.setX(col2X);
            wlw.offsetYField.setY(row4FieldY);
            wlw.offsetYField.setWidth(colWidth);
            wlw.numericFields.add(wlw.offsetYField);
            addWorldWidget(wlw, wlw.offsetYField);
            
            // Offset Z
            Component offsetZLabelText = Component.literal("Offset Z:");
            com.pastlands.cosmeticslite.client.screen.parts.LabelWidget offsetZLabel =
                new com.pastlands.cosmeticslite.client.screen.parts.LabelWidget(
                    col3X, row4LabelY,
                    this.font.width(offsetZLabelText), this.font.lineHeight,
                    offsetZLabelText, LABEL_COLOR);
            offsetZLabel.setX(col3X);
            offsetZLabel.setY(row4LabelY);
            addWorldWidget(wlw, offsetZLabel);
            
            wlw.offsetZField = new com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget(
                col3X, row4FieldY, colWidth, fieldHeight,
                worldLayer.offsetZ(),
                -2.0, 2.0, 0.05,
                v -> String.format(java.util.Locale.ROOT, "%.2f", v),
                newVal -> updateWorldLayerField(layerIndex, "offsetZ", newVal.floatValue())
            );
            wlw.offsetZField.setX(col3X);
            wlw.offsetZField.setY(row4FieldY);
            wlw.offsetZField.setWidth(colWidth);
            wlw.numericFields.add(wlw.offsetZField);
            addWorldWidget(wlw, wlw.offsetZField);
            
            // ---- Curve row: placed directly under Offset Z ----
            int curveLabelY = row4FieldY + fieldHeight + 6; // 6px padding below Offset Z
            int curveFieldY = curveLabelY + labelHeight + 2;
            
            // ---- Row 5: Rotation Mode + Tilt ----
            // Rotation Mode dropdown
            List<String> rotationModeOptions = List.of("Horizontal", "Vertical (X plane)", "Vertical (Z plane)");
            String currentRotationModeText = switch(worldLayer.rotationMode()) {
                case HORIZONTAL -> "Horizontal";
                case VERTICAL_X -> "Vertical (X plane)";
                case VERTICAL_Z -> "Vertical (Z plane)";
            };
            
            Component rotationLabelText = Component.literal("Rotation:");
            com.pastlands.cosmeticslite.client.screen.parts.LabelWidget rotationLabel = 
                new com.pastlands.cosmeticslite.client.screen.parts.LabelWidget(
                    col1X, row5LabelY,
                    this.font.width(rotationLabelText), this.font.lineHeight,
                    rotationLabelText, LABEL_COLOR);
            rotationLabel.setX(col1X);
            rotationLabel.setY(row5LabelY);
            addWorldWidget(wlw, rotationLabel);
            wlw.fieldLabels.add(rotationLabel);
            wlw.labels.add(rotationLabel);
            
            wlw.rotationModeDropdown = new com.pastlands.cosmeticslite.client.screen.parts.StringDropdownWidget(
                col1X, row5FieldY, wideWidth, 18,
                rotationModeOptions,
                newMode -> {
                    com.pastlands.cosmeticslite.particle.config.RotationMode mode = switch(newMode) {
                        case "Horizontal" -> com.pastlands.cosmeticslite.particle.config.RotationMode.HORIZONTAL;
                        case "Vertical (X plane)" -> com.pastlands.cosmeticslite.particle.config.RotationMode.VERTICAL_X;
                        case "Vertical (Z plane)" -> com.pastlands.cosmeticslite.particle.config.RotationMode.VERTICAL_Z;
                        default -> com.pastlands.cosmeticslite.particle.config.RotationMode.HORIZONTAL;
                    };
                    updateWorldLayerRotationMode(layerIndex, mode);
                }
            );
            wlw.rotationModeDropdown.setX(col1X);
            wlw.rotationModeDropdown.setY(row5FieldY);
            wlw.rotationModeDropdown.setWidth(wideWidth);
            wlw.rotationModeDropdown.setSelected(currentRotationModeText);
            addWorldWidget(wlw, wlw.rotationModeDropdown);
            
            // Tilt degrees field
            Component tiltLabelText = Component.literal("Tilt (deg):");
            com.pastlands.cosmeticslite.client.screen.parts.LabelWidget tiltLabelWidget = 
                new com.pastlands.cosmeticslite.client.screen.parts.LabelWidget(
                    col2X, row5LabelY,
                    this.font.width(tiltLabelText), this.font.lineHeight,
                    tiltLabelText, LABEL_COLOR);
            tiltLabelWidget.setX(col2X);
            tiltLabelWidget.setY(row5LabelY);
            addWorldWidget(wlw, tiltLabelWidget);
            wlw.fieldLabels.add(tiltLabelWidget);
            wlw.labels.add(tiltLabelWidget);
            
            wlw.tiltField = new com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget(
                col2X, row5FieldY, colWidth, fieldHeight,
                worldLayer.tiltDegrees(),
                -180.0, 180.0, 1.0,
                v -> String.format(java.util.Locale.ROOT, "%.0f", v),
                newVal -> {
                    float clamped = net.minecraft.util.Mth.clamp(newVal.floatValue(), -180.0f, 180.0f);
                    updateWorldLayerField(layerIndex, "tiltDegrees", clamped);
                }
            );
            wlw.tiltField.setX(col2X);
            wlw.tiltField.setY(row5FieldY);
            wlw.tiltField.setWidth(colWidth);
            wlw.numericFields.add(wlw.tiltField);
            addWorldWidget(wlw, wlw.tiltField);
            
            // ---- Row 6: Spread Start, Spread End ----
            // Spread Start
            Component spreadStartLabelText = Component.literal("Spread Start:");
            com.pastlands.cosmeticslite.client.screen.parts.LabelWidget spreadStartLabel =
                new com.pastlands.cosmeticslite.client.screen.parts.LabelWidget(
                    col1X, row6LabelY,
                    this.font.width(spreadStartLabelText), this.font.lineHeight,
                    spreadStartLabelText, LABEL_COLOR);
            spreadStartLabel.setX(col1X);
            spreadStartLabel.setY(row6LabelY);
            addWorldWidget(wlw, spreadStartLabel);
            
            wlw.spreadStartField = new com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget(
                col1X, row6FieldY, colWidth, fieldHeight,
                worldLayer.spreadStart(),
                0.0, 1.0, 0.01,
                v -> String.format(java.util.Locale.ROOT, "%.2f", v),
                newVal -> {
                    float clamped = net.minecraft.util.Mth.clamp(newVal.floatValue(), 0.0f, 1.0f);
                    updateWorldLayerField(layerIndex, "spreadStart", clamped);
                }
            );
            wlw.spreadStartField.setX(col1X);
            wlw.spreadStartField.setY(row6FieldY);
            wlw.spreadStartField.setWidth(colWidth);
            wlw.numericFields.add(wlw.spreadStartField);
            addWorldWidget(wlw, wlw.spreadStartField);
            
            // Spread End
            Component spreadEndLabelText = Component.literal("Spread End:");
            com.pastlands.cosmeticslite.client.screen.parts.LabelWidget spreadEndLabel =
                new com.pastlands.cosmeticslite.client.screen.parts.LabelWidget(
                    col2X, row6LabelY,
                    this.font.width(spreadEndLabelText), this.font.lineHeight,
                    spreadEndLabelText, LABEL_COLOR);
            spreadEndLabel.setX(col2X);
            spreadEndLabel.setY(row6LabelY);
            addWorldWidget(wlw, spreadEndLabel);
            
            wlw.spreadEndField = new com.pastlands.cosmeticslite.client.screen.parts.NumberFieldWithSpinnerWidget(
                col2X, row6FieldY, colWidth, fieldHeight,
                worldLayer.spreadEnd(),
                0.0, 1.0, 0.01,
                v -> String.format(java.util.Locale.ROOT, "%.2f", v),
                newVal -> {
                    float clamped = net.minecraft.util.Mth.clamp(newVal.floatValue(), 0.0f, 1.0f);
                    updateWorldLayerField(layerIndex, "spreadEnd", clamped);
                }
            );
            wlw.spreadEndField.setX(col2X);
            wlw.spreadEndField.setY(row6FieldY);
            wlw.spreadEndField.setWidth(colWidth);
            wlw.numericFields.add(wlw.spreadEndField);
            addWorldWidget(wlw, wlw.spreadEndField);
            
            // Motion Curve (placed under Offset Z)
            List<String> motionCurveOptions = List.of("Linear", "Ease In", "Ease Out", "Ease In/Out");
            String currentMotionCurveText = switch(worldLayer.motionCurve()) {
                case LINEAR -> "Linear";
                case EASE_IN -> "Ease In";
                case EASE_OUT -> "Ease Out";
                case EASE_IN_OUT -> "Ease In/Out";
            };
            
            Component curveLabelText = Component.literal("Curve:");
            com.pastlands.cosmeticslite.client.screen.parts.LabelWidget curveLabel = 
                new com.pastlands.cosmeticslite.client.screen.parts.LabelWidget(
                    col3X, curveLabelY,
                    this.font.width(curveLabelText), this.font.lineHeight,
                    curveLabelText, LABEL_COLOR);
            curveLabel.setX(col3X);
            curveLabel.setY(curveLabelY);
            addWorldWidget(wlw, curveLabel);
            wlw.fieldLabels.add(curveLabel);
            wlw.labels.add(curveLabel);
            
            wlw.motionCurveDropdown = new com.pastlands.cosmeticslite.client.screen.parts.StringDropdownWidget(
                col3X, curveFieldY, wideWidth, 18,
                motionCurveOptions,
                newCurve -> {
                    com.pastlands.cosmeticslite.particle.config.MotionCurve curve = switch(newCurve) {
                        case "Linear" -> com.pastlands.cosmeticslite.particle.config.MotionCurve.LINEAR;
                        case "Ease In" -> com.pastlands.cosmeticslite.particle.config.MotionCurve.EASE_IN;
                        case "Ease Out" -> com.pastlands.cosmeticslite.particle.config.MotionCurve.EASE_OUT;
                        case "Ease In/Out" -> com.pastlands.cosmeticslite.particle.config.MotionCurve.EASE_IN_OUT;
                        default -> com.pastlands.cosmeticslite.particle.config.MotionCurve.LINEAR;
                    };
                    updateWorldLayerMotionCurve(layerIndex, curve);
                }
            );
            wlw.motionCurveDropdown.setX(col3X);
            wlw.motionCurveDropdown.setY(curveFieldY);
            wlw.motionCurveDropdown.setWidth(wideWidth);
            wlw.motionCurveDropdown.setSelected(currentMotionCurveText);
            addWorldWidget(wlw, wlw.motionCurveDropdown);
            
            // Update buttonsY to be after Curve dropdown (card height accounts for this)
            int buttonsY = curveFieldY + fieldHeight + 6;
            
            // Track all labels (already added above)
            wlw.fieldLabels.add(radiusLabel);
            wlw.fieldLabels.add(baseHeightLabel);
            wlw.fieldLabels.add(stretchLabel);
            wlw.fieldLabels.add(countLabel);
            wlw.fieldLabels.add(speedLabel);
            wlw.fieldLabels.add(offsetXLabel);
            wlw.fieldLabels.add(offsetYLabel);
            wlw.fieldLabels.add(offsetZLabel);
            wlw.fieldLabels.add(spreadStartLabel);
            wlw.fieldLabels.add(spreadEndLabel);
            
            wlw.labels.add(radiusLabel);
            wlw.labels.add(baseHeightLabel);
            wlw.labels.add(stretchLabel);
            wlw.labels.add(countLabel);
            wlw.labels.add(speedLabel);
            wlw.labels.add(offsetXLabel);
            wlw.labels.add(offsetYLabel);
            wlw.labels.add(offsetZLabel);
            wlw.labels.add(spreadStartLabel);
            wlw.labels.add(spreadEndLabel);

            // ---- Controls (↑ ↓ Dup Remove) ----
            // Buttons on their own row
            int btnWidth = 20; // Arrow buttons
            int dupBtnWidth = 50;
            int removeBtnWidth = 70;
            int btnSpacing = 4;
            
            // Calculate total width needed and position from right edge
            int totalButtonsWidth = (layerIndex > 0 ? btnWidth + btnSpacing : 0) + 
                                   (layerIndex < editorState.workingCopy.worldLayers().size() - 1 ? btnWidth + btnSpacing : 0) + 
                                   dupBtnWidth + btnSpacing + removeBtnWidth;
            int buttonsStartX = wlwCardRight - totalButtonsWidth - 6; // 6px padding from right edge
            int currentBtnX = buttonsStartX;
            
            // Up arrow (if not first)
            if (layerIndex > 0) {
                wlw.moveUpBtn = Button.builder(Component.literal("↑"),
                        btn -> moveWorldLayer(layerIndex, -1))
                        .bounds(currentBtnX, buttonsY, btnWidth, 18).build();
                addWorldWidget(wlw, wlw.moveUpBtn);
                currentBtnX += btnWidth + btnSpacing;
            }
            
            // Down arrow (if not last)
            if (layerIndex < editorState.workingCopy.worldLayers().size() - 1) {
                wlw.moveDownBtn = Button.builder(Component.literal("↓"),
                        btn -> moveWorldLayer(layerIndex, 1))
                        .bounds(currentBtnX, buttonsY, btnWidth, 18).build();
                addWorldWidget(wlw, wlw.moveDownBtn);
                currentBtnX += btnWidth + btnSpacing;
            }
            
            // Dup button
            wlw.duplicateBtn = Button.builder(Component.literal("Dup"),
                    btn -> duplicateWorldLayer(layerIndex))
                    .bounds(currentBtnX, buttonsY, dupBtnWidth, 18).build();
            addWorldWidget(wlw, wlw.duplicateBtn);
            currentBtnX += dupBtnWidth + btnSpacing;
            
            // Remove button
            wlw.removeBtn = Button.builder(Component.literal("Remove"),
                    btn -> removeWorldLayer(layerIndex))
                    .bounds(currentBtnX, buttonsY, removeBtnWidth, 18).build();
            addWorldWidget(wlw, wlw.removeBtn);
            
            worldLayerWidgets.add(wlw);
        }
        
        // Bottom "+ Add World Layer" button – anchored, not scrolled
        int addBtnY = bottom - 40;
        String addWorldLayerLabel = "+ Add World Layer";
        int addWorldLayerWidth = this.font.width(addWorldLayerLabel) + 12;
        addWorldLayerBtn = Button.builder(Component.literal(addWorldLayerLabel),
                btn -> addWorldLayer())
                .bounds(0, 0, addWorldLayerWidth, 20).build();
        addWorldLayerBtn.visible = true; // Footer button - always visible
        addTabWidget(Tab.WORLD, addWorldLayerBtn);
        // Layout footer buttons with add world layer button
        layoutFooterButtons(addWorldLayerBtn, saveBtn, revertBtn, publishBtn, previewBtn, deleteBtn);
    }
    
    private void updateWorldLayerEffect(int layerIndex, String newEffect) {
        if (editorState.workingCopy == null) return;
        try {
            // Handle display format: if it's just a path (no colon), assume minecraft namespace
            ResourceLocation effect;
            if (!newEffect.contains(":")) {
                effect = ResourceLocation.fromNamespaceAndPath("minecraft", newEffect);
            } else {
                effect = ResourceLocation.parse(newEffect);
            }
            List<WorldLayerDefinition> newWorldLayers = new ArrayList<>(editorState.workingCopy.worldLayers());
            WorldLayerDefinition old = newWorldLayers.get(layerIndex);
            newWorldLayers.set(layerIndex, new WorldLayerDefinition(
                effect, old.style(), old.radius(), old.heightFactor(), old.count(), old.speedY(),
                old.yOffset(), old.xScale(), old.direction(),
                old.baseHeight(), old.heightStretch(), old.rotationMode(), old.tiltDegrees(),
                old.offsetX(), old.offsetY(), old.offsetZ(), old.spreadStart(), old.spreadEnd(),
                old.jitterDegrees(), old.jitterSpeed(), old.driftX(), old.driftY(), old.driftZ(),
                old.driftVariance(), old.torqueSpeed(), old.torqueAmount(), old.motionCurve(),
                old.spawnDelayVarianceMs()
            ));
            updateWorkingCopyWorldLayers(newWorldLayers);
            
            // Update color widget states based on new effect (World tab)
            if (layerIndex >= 0 && layerIndex < worldLayerWidgets.size()) {
                WorldLayerWidgets wlw = worldLayerWidgets.get(layerIndex);
                var caps = com.pastlands.cosmeticslite.client.editor.EffectCapabilitiesRegistry.get(effect);
                wlw.canColorize = caps != null && caps.supportsTint();
                updateWorldLayerColorWidgets(wlw);
            }
            
            // Refresh Layers tab color lockouts (since Layers tab is now based on world layers)
            if ("layers".equals(activeTab)) {
                rebuildTabWidgets(); // Rebuild to update color lockout
            } else {
                refreshLayerColorLockouts(); // Just update existing widgets if tab is not active
            }
            
            // Force immediate preview refresh for Effect changes (updateWorkingCopyWorldLayers already calls updatePreviewIfActive, but ensure it happens)
            updatePreviewIfActive();
        } catch (Exception e) {
            CosmeticsLite.LOGGER.warn("[ParticleLab] Invalid effect: {}", newEffect);
        }
    }
    
    /**
     * Refresh color lockout state for all Layers tab cards.
     * Called when world layer effects change or when switching to Layers tab.
     */
    private void refreshLayerColorLockouts() {
        if (editorState.workingCopy == null) return;
        
        List<WorldLayerDefinition> worldLayers = editorState.workingCopy.worldLayers();
        
        // Update each layer widget's color lockout based on its world layer's effect
        for (int i = 0; i < layerWidgets.size() && i < worldLayers.size(); i++) {
            LayerWidgets lw = layerWidgets.get(i);
            WorldLayerDefinition worldLayer = worldLayers.get(lw.worldLayerIndex);
            ResourceLocation effectId = worldLayer.effect();
            var caps = com.pastlands.cosmeticslite.client.editor.EffectCapabilitiesRegistry.get(effectId);
            boolean canColorize = caps != null && caps.supportsTint();
            
            // Update all color widgets for this layer
            for (net.minecraft.client.gui.components.Renderable widget : lw.colorButtons) {
                if (widget instanceof com.pastlands.cosmeticslite.client.screen.ColorSwatchButton swatch) {
                    swatch.setActive(canColorize);
                } else if (widget instanceof Button btn) {
                    String btnText = btn.getMessage().getString().toLowerCase();
                    if (btnText.contains("color")) {
                        btn.active = canColorize;
                    }
                }
            }
        }
    }
    
    /**
     * Update enabled/disabled state of color widgets based on canColorize flag.
     * This method is called when effect changes or widgets are rebuilt.
     * 
     * Currently handles:
     * - ColorSwatchButton widgets (via setActive method)
     * - Color-related Button widgets (via active field)
     * - ColorWheelWidget (when added, will need to check canColorize in click handler)
     */
    private void updateWorldLayerColorWidgets(WorldLayerWidgets wlw) {
        if (wlw == null) return;
        
        // Update all color widgets (swatches, color wheel, etc.)
        for (net.minecraft.client.gui.components.Renderable widget : wlw.colorWidgets) {
            if (widget instanceof com.pastlands.cosmeticslite.client.screen.ColorSwatchButton swatch) {
                // Color swatch buttons - disable/enable based on canColorize
                swatch.setActive(wlw.canColorize);
            } else if (widget instanceof Button btn) {
                // Color-related buttons (+ Color, - Color, etc.)
                String btnText = btn.getMessage().getString().toLowerCase();
                if (btnText.contains("color")) {
                    btn.active = wlw.canColorize;
                }
            } else if (widget instanceof com.pastlands.cosmeticslite.client.screen.parts.ColorWheelWidget) {
                // ColorWheelWidget - when implemented, should check wlw.canColorize in its click handler
                // For now, the widget state is tracked in wlw.canColorize for future use
            }
        }
    }
    
    private void updateWorldLayerStyle(int layerIndex, String newStyle) {
        if (editorState.workingCopy == null) return;
        List<WorldLayerDefinition> newWorldLayers = new ArrayList<>(editorState.workingCopy.worldLayers());
        WorldLayerDefinition old = newWorldLayers.get(layerIndex);
        newWorldLayers.set(layerIndex, new WorldLayerDefinition(
            old.effect(), newStyle, old.radius(), old.heightFactor(), old.count(), old.speedY(),
            old.yOffset(), old.xScale(), old.direction(),
            old.baseHeight(), old.heightStretch(), old.rotationMode(), old.tiltDegrees(),
            old.offsetX(), old.offsetY(), old.offsetZ(), old.spreadStart(), old.spreadEnd(),
            old.jitterDegrees(), old.jitterSpeed(), old.driftX(), old.driftY(), old.driftZ(),
            old.driftVariance(), old.torqueSpeed(), old.torqueAmount(), old.motionCurve(),
            old.spawnDelayVarianceMs()
        ));
        updateWorkingCopyWorldLayers(newWorldLayers);
    }
    
    private void updateWorldLayerField(int layerIndex, String fieldName, float value) {
        if (editorState.workingCopy == null) return;
        List<WorldLayerDefinition> newWorldLayers = new ArrayList<>(editorState.workingCopy.worldLayers());
        WorldLayerDefinition old = newWorldLayers.get(layerIndex);
        WorldLayerDefinition updated = switch (fieldName) {
            case "radius" -> new WorldLayerDefinition(
                old.effect(), old.style(), value, old.heightFactor(), old.count(), old.speedY(),
                old.yOffset(), old.xScale(), old.direction(),
                old.baseHeight(), old.heightStretch(), old.rotationMode(), old.tiltDegrees(),
                old.offsetX(), old.offsetY(), old.offsetZ(), old.spreadStart(), old.spreadEnd(),
                old.jitterDegrees(), old.jitterSpeed(), old.driftX(), old.driftY(), old.driftZ(),
                old.driftVariance(), old.torqueSpeed(), old.torqueAmount(), old.motionCurve(),
                old.spawnDelayVarianceMs()
            );
            case "baseHeight" -> new WorldLayerDefinition(
                old.effect(), old.style(), old.radius(), old.heightFactor(), old.count(), old.speedY(),
                old.yOffset(), old.xScale(), old.direction(),
                value, old.heightStretch(), old.rotationMode(), old.tiltDegrees(),
                old.offsetX(), old.offsetY(), old.offsetZ(), old.spreadStart(), old.spreadEnd(),
                old.jitterDegrees(), old.jitterSpeed(), old.driftX(), old.driftY(), old.driftZ(),
                old.driftVariance(), old.torqueSpeed(), old.torqueAmount(), old.motionCurve(),
                old.spawnDelayVarianceMs()
            );
            case "heightStretch" -> new WorldLayerDefinition(
                old.effect(), old.style(), old.radius(), old.heightFactor(), old.count(), old.speedY(),
                old.yOffset(), old.xScale(), old.direction(),
                old.baseHeight(), value, old.rotationMode(), old.tiltDegrees(),
                old.offsetX(), old.offsetY(), old.offsetZ(), old.spreadStart(), old.spreadEnd(),
                old.jitterDegrees(), old.jitterSpeed(), old.driftX(), old.driftY(), old.driftZ(),
                old.driftVariance(), old.torqueSpeed(), old.torqueAmount(), old.motionCurve(),
                old.spawnDelayVarianceMs()
            );
            case "offsetX" -> new WorldLayerDefinition(
                old.effect(), old.style(), old.radius(), old.heightFactor(), old.count(), old.speedY(),
                old.yOffset(), old.xScale(), old.direction(),
                old.baseHeight(), old.heightStretch(), old.rotationMode(), old.tiltDegrees(),
                value, old.offsetY(), old.offsetZ(), old.spreadStart(), old.spreadEnd(),
                old.jitterDegrees(), old.jitterSpeed(), old.driftX(), old.driftY(), old.driftZ(),
                old.driftVariance(), old.torqueSpeed(), old.torqueAmount(), old.motionCurve(),
                old.spawnDelayVarianceMs()
            );
            case "offsetY" -> new WorldLayerDefinition(
                old.effect(), old.style(), old.radius(), old.heightFactor(), old.count(), old.speedY(),
                old.yOffset(), old.xScale(), old.direction(),
                old.baseHeight(), old.heightStretch(), old.rotationMode(), old.tiltDegrees(),
                old.offsetX(), value, old.offsetZ(), old.spreadStart(), old.spreadEnd(),
                old.jitterDegrees(), old.jitterSpeed(), old.driftX(), old.driftY(), old.driftZ(),
                old.driftVariance(), old.torqueSpeed(), old.torqueAmount(), old.motionCurve(),
                old.spawnDelayVarianceMs()
            );
            case "offsetZ" -> new WorldLayerDefinition(
                old.effect(), old.style(), old.radius(), old.heightFactor(), old.count(), old.speedY(),
                old.yOffset(), old.xScale(), old.direction(),
                old.baseHeight(), old.heightStretch(), old.rotationMode(), old.tiltDegrees(),
                old.offsetX(), old.offsetY(), value, old.spreadStart(), old.spreadEnd(),
                old.jitterDegrees(), old.jitterSpeed(), old.driftX(), old.driftY(), old.driftZ(),
                old.driftVariance(), old.torqueSpeed(), old.torqueAmount(), old.motionCurve(),
                old.spawnDelayVarianceMs()
            );
            case "tiltDegrees" -> new WorldLayerDefinition(
                old.effect(), old.style(), old.radius(), old.heightFactor(), old.count(), old.speedY(),
                old.yOffset(), old.xScale(), old.direction(),
                old.baseHeight(), old.heightStretch(), old.rotationMode(), value,
                old.offsetX(), old.offsetY(), old.offsetZ(), old.spreadStart(), old.spreadEnd(),
                old.jitterDegrees(), old.jitterSpeed(), old.driftX(), old.driftY(), old.driftZ(),
                old.driftVariance(), old.torqueSpeed(), old.torqueAmount(), old.motionCurve(),
                old.spawnDelayVarianceMs()
            );
            case "spreadStart" -> new WorldLayerDefinition(
                old.effect(), old.style(), old.radius(), old.heightFactor(), old.count(), old.speedY(),
                old.yOffset(), old.xScale(), old.direction(),
                old.baseHeight(), old.heightStretch(), old.rotationMode(), old.tiltDegrees(),
                old.offsetX(), old.offsetY(), old.offsetZ(), value, old.spreadEnd(),
                old.jitterDegrees(), old.jitterSpeed(), old.driftX(), old.driftY(), old.driftZ(),
                old.driftVariance(), old.torqueSpeed(), old.torqueAmount(), old.motionCurve(),
                old.spawnDelayVarianceMs()
            );
            case "spreadEnd" -> new WorldLayerDefinition(
                old.effect(), old.style(), old.radius(), old.heightFactor(), old.count(), old.speedY(),
                old.yOffset(), old.xScale(), old.direction(),
                old.baseHeight(), old.heightStretch(), old.rotationMode(), old.tiltDegrees(),
                old.offsetX(), old.offsetY(), old.offsetZ(), old.spreadStart(), value,
                old.jitterDegrees(), old.jitterSpeed(), old.driftX(), old.driftY(), old.driftZ(),
                old.driftVariance(), old.torqueSpeed(), old.torqueAmount(), old.motionCurve(),
                old.spawnDelayVarianceMs()
            );
            case "count" -> new WorldLayerDefinition(
                old.effect(), old.style(), old.radius(), old.heightFactor(),
                Math.max(1, Math.min(64, (int)value)), old.speedY(),
                old.yOffset(), old.xScale(), old.direction(),
                old.baseHeight(), old.heightStretch(), old.rotationMode(), old.tiltDegrees(),
                old.offsetX(), old.offsetY(), old.offsetZ(), old.spreadStart(), old.spreadEnd(),
                old.jitterDegrees(), old.jitterSpeed(), old.driftX(), old.driftY(), old.driftZ(),
                old.driftVariance(), old.torqueSpeed(), old.torqueAmount(), old.motionCurve(),
                old.spawnDelayVarianceMs()
            );
            case "speedY" -> {
                // Clamp speed multiplier to 0.001-3.0 range
                float clamped = Math.max(0.001f, Math.min(3.0f, value));
                // Backward compatibility: if value is 0, use 1.0 as default
                float finalValue = (clamped <= 0.0f) ? 1.0f : clamped;
                yield new WorldLayerDefinition(
                    old.effect(), old.style(), old.radius(), old.heightFactor(),
                    old.count(), finalValue, old.yOffset(), old.xScale(), old.direction(),
                    old.baseHeight(), old.heightStretch(), old.rotationMode(), old.tiltDegrees(),
                    old.offsetX(), old.offsetY(), old.offsetZ(), old.spreadStart(), old.spreadEnd(),
                    old.jitterDegrees(), old.jitterSpeed(), old.driftX(), old.driftY(), old.driftZ(),
                    old.driftVariance(), old.torqueSpeed(), old.torqueAmount(), old.motionCurve(),
                    old.spawnDelayVarianceMs()
                );
            }
            default -> old;
        };
        newWorldLayers.set(layerIndex, updated);
        updateWorkingCopyWorldLayers(newWorldLayers);
    }
    
    private void updateWorldLayerRotationMode(int layerIndex, com.pastlands.cosmeticslite.particle.config.RotationMode rotationMode) {
        if (editorState.workingCopy == null) return;
        List<WorldLayerDefinition> newWorldLayers = new ArrayList<>(editorState.workingCopy.worldLayers());
        WorldLayerDefinition old = newWorldLayers.get(layerIndex);
        WorldLayerDefinition updated = new WorldLayerDefinition(
            old.effect(), old.style(), old.radius(), old.heightFactor(), old.count(), old.speedY(),
            old.yOffset(), old.xScale(), old.direction(),
            old.baseHeight(), old.heightStretch(), rotationMode, old.tiltDegrees(),
            old.offsetX(), old.offsetY(), old.offsetZ(), old.spreadStart(), old.spreadEnd(),
            old.jitterDegrees(), old.jitterSpeed(), old.driftX(), old.driftY(), old.driftZ(),
            old.driftVariance(), old.torqueSpeed(), old.torqueAmount(), old.motionCurve(),
            old.spawnDelayVarianceMs()
        );
        newWorldLayers.set(layerIndex, updated);
        updateWorkingCopyWorldLayers(newWorldLayers);
    }
    
    private void updateWorldLayerMotionCurve(int layerIndex, com.pastlands.cosmeticslite.particle.config.MotionCurve motionCurve) {
        if (editorState.workingCopy == null) return;
        List<WorldLayerDefinition> newWorldLayers = new ArrayList<>(editorState.workingCopy.worldLayers());
        WorldLayerDefinition old = newWorldLayers.get(layerIndex);
        WorldLayerDefinition updated = new WorldLayerDefinition(
            old.effect(), old.style(), old.radius(), old.heightFactor(), old.count(), old.speedY(),
            old.yOffset(), old.xScale(), old.direction(),
            old.baseHeight(), old.heightStretch(), old.rotationMode(), old.tiltDegrees(),
            old.offsetX(), old.offsetY(), old.offsetZ(), old.spreadStart(), old.spreadEnd(),
            old.jitterDegrees(), old.jitterSpeed(), old.driftX(), old.driftY(), old.driftZ(),
            old.driftVariance(), old.torqueSpeed(), old.torqueAmount(), motionCurve,
            old.spawnDelayVarianceMs()
        );
        newWorldLayers.set(layerIndex, updated);
        updateWorkingCopyWorldLayers(newWorldLayers);
    }
    
    private void updateWorldLayerOptionalField(int layerIndex, String fieldName, Object value) {
        if (editorState.workingCopy == null) return;
        List<WorldLayerDefinition> newWorldLayers = new ArrayList<>(editorState.workingCopy.worldLayers());
        WorldLayerDefinition old = newWorldLayers.get(layerIndex);
        WorldLayerDefinition updated = switch (fieldName) {
            case "yOffset" -> {
                Float yOffset = null;
                if (value instanceof Float) {
                    yOffset = (Float)value;
                } else if (value instanceof String && !((String)value).isEmpty()) {
                    try {
                        yOffset = Float.parseFloat((String)value);
                    } catch (NumberFormatException e) {
                        // Invalid, keep null
                    }
                }
                yield new WorldLayerDefinition(
                    old.effect(), old.style(), old.radius(), old.heightFactor(), old.count(), old.speedY(),
                    yOffset, old.xScale(), old.direction(),
                    old.baseHeight(), old.heightStretch(), old.rotationMode(), old.tiltDegrees(),
                    old.offsetX(), old.offsetY(), old.offsetZ(), old.spreadStart(), old.spreadEnd(),
                    old.jitterDegrees(), old.jitterSpeed(), old.driftX(), old.driftY(), old.driftZ(),
                    old.driftVariance(), old.torqueSpeed(), old.torqueAmount(), old.motionCurve(),
                    old.spawnDelayVarianceMs()
                );
            }
            case "xScale" -> {
                Float xScale = null;
                if (value instanceof Float) {
                    xScale = (Float)value;
                } else if (value instanceof String && !((String)value).isEmpty()) {
                    try {
                        xScale = Float.parseFloat((String)value);
                    } catch (NumberFormatException e) {
                        // Invalid, keep null
                    }
                }
                yield new WorldLayerDefinition(
                    old.effect(), old.style(), old.radius(), old.heightFactor(), old.count(), old.speedY(),
                    old.yOffset(), xScale, old.direction(),
                    old.baseHeight(), old.heightStretch(), old.rotationMode(), old.tiltDegrees(),
                    old.offsetX(), old.offsetY(), old.offsetZ(), old.spreadStart(), old.spreadEnd(),
                    old.jitterDegrees(), old.jitterSpeed(), old.driftX(), old.driftY(), old.driftZ(),
                    old.driftVariance(), old.torqueSpeed(), old.torqueAmount(), old.motionCurve(),
                    old.spawnDelayVarianceMs()
                );
            }
            case "direction" -> {
                String direction = null;
                if (value instanceof String && !((String)value).isEmpty()) {
                    direction = (String)value;
                }
                yield new WorldLayerDefinition(
                    old.effect(), old.style(), old.radius(), old.heightFactor(), old.count(), old.speedY(),
                    old.yOffset(), old.xScale(), direction,
                    old.baseHeight(), old.heightStretch(), old.rotationMode(), old.tiltDegrees(),
                    old.offsetX(), old.offsetY(), old.offsetZ(), old.spreadStart(), old.spreadEnd(),
                    old.jitterDegrees(), old.jitterSpeed(), old.driftX(), old.driftY(), old.driftZ(),
                    old.driftVariance(), old.torqueSpeed(), old.torqueAmount(), old.motionCurve(),
                    old.spawnDelayVarianceMs()
                );
            }
            default -> old;
        };
        newWorldLayers.set(layerIndex, updated);
        updateWorkingCopyWorldLayers(newWorldLayers);
    }
    
    private void updateWorkingCopyWorldLayers(List<WorldLayerDefinition> newWorldLayers) {
        ParticleDefinition tempDef = new ParticleDefinition(
            editorState.workingCopy.id(),
            editorState.workingCopy.layers(),
            newWorldLayers,
            editorState.workingCopy.description(),
            editorState.workingCopy.styleHint(),
            editorState.workingCopy.displayName(),
            editorState.workingCopy.notes()
        );
        // Enforce 1:1 mapping: ensure layers count matches worldLayers count
        editorState.workingCopy = ensureLayerCountMatchesWorldLayers(tempDef);
        editorState.markDirty();
        updateButtonStates();
        // Mark preview dirty so it refreshes from working copy
        markPreviewDirty();
        // If Preview tab is active, refresh the preview
        if ("preview".equals(activeTab)) {
            updatePreviewBounds();
        }
    }
    
    /**
     * Mark preview as dirty - it needs to be refreshed from workingCopy.
     * Called by all widget change handlers to ensure preview stays in sync.
     */
    private void markPreviewDirty() {
        previewDirty = true;
        previewDirtyAtMs = System.currentTimeMillis();
    }
    
    /**
     * Rebuild preview emitter from current workingCopy.
     * Clears old particles and spawns fresh ones with updated settings.
     */
    private void rebuildPreviewFromWorkingCopy() {
        if (editorState.workingCopy == null || editorState.selectedId == null) {
            return;
        }
        
        // Sanity log to prove it's firing
        ResourceLocation currentPreviewId = ParticlePreviewState.getCurrentPreviewId();
        int layerCount = editorState.workingCopy.layers() != null ? editorState.workingCopy.layers().size() : 0;
        int firstColor = (layerCount > 0 && !editorState.workingCopy.layers().get(0).colors().isEmpty()) 
            ? editorState.workingCopy.layers().get(0).colors().get(0) 
            : 0;
        CosmeticsLite.LOGGER.info("[PreviewRebuild] selectedId={}, currentPreviewId={}, layers={}, firstColor=0x{}",
            editorState.selectedId, currentPreviewId, layerCount, Integer.toHexString(firstColor).toUpperCase());
        
        // Update preview state with working copy (always, even if preview not active)
        ParticlePreviewState.updatePreviewDefinition(editorState.workingCopy);
        ParticlePreviewState.setPreviewOverride(editorState.selectedId, editorState.workingCopy);
        
        // If preview is active for this definition, reset spawn state to clear old particles
        if (ParticlePreviewState.isActive() && 
            ParticlePreviewState.getCurrentPreviewId() != null &&
            ParticlePreviewState.getCurrentPreviewId().equals(editorState.selectedId)) {
            // Reset preview spawn state to clear old particles and spawn fresh ones with new settings
            com.pastlands.cosmeticslite.ClientCosmeticRenderer.resetPreviewState(editorState.selectedId);
        }
    }
    
    /**
     * Legacy method - now just marks preview dirty.
     * Kept for compatibility with existing code.
     */
    private void updatePreviewIfActive() {
        markPreviewDirty();
    }
    
    /**
     * Check if mouse is over the World tab viewport area.
     */
    private boolean isMouseOverWorldViewport(double mouseX, double mouseY) {
        int worldCardLeft = getWorldPanelLeft() + 10;
        int worldCardRight = getWorldPanelRight() - 10;
        return mouseX >= worldCardLeft && mouseX <= worldCardRight
            && mouseY >= worldViewportTop && mouseY <= worldViewportBottom;
    }
    
    private boolean isMouseOverLayersViewport(double mouseX, double mouseY) {
        int panelLeft = left + leftPanelWidth + 20;
        int panelRight = right - 20;
        return mouseX >= panelLeft && mouseX < panelRight
            && mouseY >= layersViewportTop && mouseY <= layersViewportBottom;
    }
    
    /**
     * Update World viewport bounds using current panel geometry.
     * Must match the assumptions used by card drawing + widget placement.
     * Call at the top of rebuildWorldLayerWidgets() before any card positioning.
     */
    private void updateWorldViewportBounds() {
        int panelTop = getWorldPanelTop();
        int panelBottom = getWorldPanelBottom();
        
        // Must match the assumptions used by the card drawing + widget placement
        this.worldViewportTop = panelTop + WORLD_VIEWPORT_TOP_MARGIN;
        this.worldViewportBottom = panelBottom - WORLD_VIEWPORT_BOTTOM_MARGIN;
        
        // Guard: ensure bottom is never above top
        if (this.worldViewportBottom <= this.worldViewportTop) {
            // Fallback: use smaller margins if panel is too small
            this.worldViewportTop = panelTop + 24;
            this.worldViewportBottom = panelBottom - 24;
            // If still invalid, use minimal margins
            if (this.worldViewportBottom <= this.worldViewportTop) {
                this.worldViewportTop = panelTop + 10;
                this.worldViewportBottom = Math.max(panelTop + 20, panelBottom - 10);
            }
        }
    }
    
    /**
     * Update World tab scroll metrics.
     * Call after updateWorldViewportBounds() in rebuildWorldLayerWidgets().
     */
    private void updateWorldScrollMetrics() {
        if (editorState.workingCopy == null) {
            worldMaxScroll = 0.0f;
            worldScrollOffset = 0.0f;
            return;
        }
        
        int layerCount = editorState.workingCopy.worldLayers().size();
        int contentHeight = layerCount * WORLD_CARD_HEIGHT + Math.max(0, (layerCount - 1) * WORLD_CARD_GAP);
        
        int viewportHeight = Math.max(0, worldViewportBottom - worldViewportTop);
        
        worldMaxScroll = Math.max(0.0f, (float)(contentHeight - viewportHeight));
        worldScrollOffset = net.minecraft.util.Mth.clamp(worldScrollOffset, 0f, worldMaxScroll);
    }
    
    /**
     * Single source of truth for World card Y position.
     * index: 0 = World Layer 1, 1 = World Layer 2, etc.
     */
    private int getWorldCardTop(int index) {
        return worldViewportTop + index * (WORLD_CARD_HEIGHT + WORLD_CARD_GAP)
               - Math.round(worldScrollOffset);
    }
    
    /**
     * Update Layers tab scroll metrics.
     * Call at the end of rebuildLayerWidgets().
     */
    /**
     * Update Layers viewport bounds using current panel geometry.
     * Must match the assumptions used by card drawing + widget placement.
     * Call at the top of rebuildLayerWidgets() before any card positioning.
     */
    private void updateLayersViewportBounds() {
        int panelTop = getLayersPanelTop();
        int panelBottom = getLayersPanelBottom();
        
        // Must match the assumptions used by the card drawing + widget placement
        this.layersViewportTop = panelTop + 24; // Just below "Layer Behavior - how each particle moves..." title
        this.layersViewportBottom = panelBottom - 40; // Above bottom buttons with safety margin
        
        // Guard: ensure bottom is never above top
        if (this.layersViewportBottom <= this.layersViewportTop) {
            this.layersViewportTop = panelTop + 24;
            this.layersViewportBottom = Math.max(panelTop + 34, panelBottom - 40);
        }
    }
    
    /**
     * Update Layers tab scroll metrics.
     * Call after updateLayersViewportBounds() in rebuildLayerWidgets().
     */
    private void updateLayersScrollMetrics() {
        if (editorState.workingCopy == null) {
            layersMaxScroll = 0.0f;
            layersScrollOffset = 0.0f;
            return;
        }
        
        int layerCount = editorState.workingCopy.layers().size();
        float viewportH = (float)(layersViewportBottom - layersViewportTop);
        float contentH = layerCount * (LAYERS_CARD_HEIGHT + LAYERS_CARD_GAP) - LAYERS_CARD_GAP;
        
        layersMaxScroll = Math.max(0.0f, contentH - viewportH);
        layersScrollOffset = net.minecraft.util.Mth.clamp(layersScrollOffset, 0f, layersMaxScroll);
    }
    
    /**
     * Single source of truth for Layers card Y position.
     * index: 0 = Layer 1, 1 = Layer 2, etc.
     */
    private int getLayersCardTop(int index) {
        return layersViewportTop + index * (LAYERS_CARD_HEIGHT + LAYERS_CARD_GAP) - (int) layersScrollOffset;
    }
    
    
    /**
     * Creates a default ParticleLayerDefinition with standard default values.
     */
    private static ParticleLayerDefinition createDefaultLayer() {
        return new ParticleLayerDefinition(
            "FLOAT_UP",
            new ArrayList<>(List.of(0xFFFFFFFF)),
            20.0f, 4.0f, 0.20f, 0.10f, 1.0f, 1.0f
        );
    }
    
    /**
     * Ensures that the layers list has exactly the same count as worldLayers list.
     * Adds default layers if too few, removes extras if too many.
     * This enforces the 1:1 mapping between World Layer i and Layer i.
     */
    private ParticleDefinition ensureLayerCountMatchesWorldLayers(ParticleDefinition def) {
        if (def == null) return def;
        
        List<WorldLayerDefinition> worldLayers = def.worldLayers();
        List<ParticleLayerDefinition> layers = new ArrayList<>(def.layers());
        int worldSize = worldLayers.size();
        
        // Add default layers if we have fewer layers than world layers
        while (layers.size() < worldSize) {
            layers.add(createDefaultLayer());
        }
        
        // Remove extra layers if we have more layers than world layers
        while (layers.size() > worldSize) {
            layers.remove(layers.size() - 1);
        }
        
        // Only create new definition if layers actually changed
        if (!layers.equals(def.layers())) {
            return new ParticleDefinition(
                def.id(),
                layers,
                def.worldLayers(),
                def.description(),
                def.styleHint(),
                def.displayName(),
                def.notes()
            );
        }
        
        return def;
    }
    
    private void addWorldLayer() {
        if (editorState.workingCopy == null) return;
        
        List<WorldLayerDefinition> newWorldLayers = new ArrayList<>(editorState.workingCopy.worldLayers());
        // Create new world layer with default values using full constructor
        newWorldLayers.add(new WorldLayerDefinition(
            ResourceLocation.fromNamespaceAndPath("minecraft", "flame"),
            "halo",
            0.5f,  // radius
            0.5f,  // heightFactor (legacy, kept for backward compatibility)
            5,     // count
            1.0f,  // speedY (default speed multiplier)
            null,  // yOffset
            null,  // xScale
            null,  // direction
            0.5f,  // baseHeight (default from heightFactor)
            0.0f,  // heightStretch
            com.pastlands.cosmeticslite.particle.config.RotationMode.HORIZONTAL,  // rotationMode
            0.0f,  // tiltDegrees
            0.0f,  // offsetX
            0.0f,  // offsetY
            0.0f,  // offsetZ
            1.0f,  // spreadStart
            1.0f,  // spreadEnd
            0.0f,  // jitterDegrees
            0.0f,  // jitterSpeed
            0.0f,  // driftX
            0.0f,  // driftY
            0.0f,  // driftZ
            0.0f,  // driftVariance
            0.0f,  // torqueSpeed
            0.0f,  // torqueAmount
            com.pastlands.cosmeticslite.particle.config.MotionCurve.LINEAR,  // motionCurve
            0      // spawnDelayVarianceMs
        ));
        
        // Also add a corresponding layer definition to maintain 1:1 mapping
        List<ParticleLayerDefinition> newLayers = new ArrayList<>(editorState.workingCopy.layers());
        newLayers.add(createDefaultLayer());
        
        ParticleDefinition tempDef = new ParticleDefinition(
            editorState.workingCopy.id(),
            newLayers,
            newWorldLayers,
            editorState.workingCopy.description(),
            editorState.workingCopy.styleHint(),
            editorState.workingCopy.displayName(),
            editorState.workingCopy.notes()
        );
        // Enforce 1:1 mapping (should already be correct, but ensures consistency)
        editorState.workingCopy = ensureLayerCountMatchesWorldLayers(tempDef);
        editorState.markDirty();
        updateButtonStates();
        rebuildTabWidgets(); // This calls computeWorldScrollMetrics() at the start
        updatePreviewIfActive();
    }
    
    private void removeWorldLayer(int layerIndex) {
        if (editorState.workingCopy == null) return;
        List<WorldLayerDefinition> newWorldLayers = new ArrayList<>(editorState.workingCopy.worldLayers());
        List<ParticleLayerDefinition> newLayers = new ArrayList<>(editorState.workingCopy.layers());
        if (newWorldLayers.size() > 1) {
            // Remove both world layer and corresponding layer to maintain 1:1 mapping
            newWorldLayers.remove(layerIndex);
            if (layerIndex < newLayers.size()) {
                newLayers.remove(layerIndex);
            }
            ParticleDefinition tempDef = new ParticleDefinition(
                editorState.workingCopy.id(),
                newLayers,
                newWorldLayers,
                editorState.workingCopy.description(),
                editorState.workingCopy.styleHint(),
                editorState.workingCopy.displayName(),
                editorState.workingCopy.notes()
            );
            // Enforce 1:1 mapping (should already be correct, but ensures consistency)
            editorState.workingCopy = ensureLayerCountMatchesWorldLayers(tempDef);
            editorState.markDirty();
            updateButtonStates();
            rebuildTabWidgets();
            updatePreviewIfActive();
        }
    }
    
    private void duplicateWorldLayer(int layerIndex) {
        if (editorState.workingCopy == null) return;
        List<WorldLayerDefinition> newWorldLayers = new ArrayList<>(editorState.workingCopy.worldLayers());
        List<ParticleLayerDefinition> newLayers = new ArrayList<>(editorState.workingCopy.layers());
        
        // Duplicate both world layer and corresponding layer to maintain 1:1 mapping
        WorldLayerDefinition srcWorld = newWorldLayers.get(layerIndex);
        WorldLayerDefinition duplicatedWorld = new WorldLayerDefinition(
            srcWorld.effect(), srcWorld.style(), srcWorld.radius(), srcWorld.heightFactor(),
            srcWorld.count(), srcWorld.speedY(), srcWorld.yOffset(), srcWorld.xScale(), srcWorld.direction(),
            srcWorld.baseHeight(), srcWorld.heightStretch(), srcWorld.rotationMode(), srcWorld.tiltDegrees(),
            srcWorld.offsetX(), srcWorld.offsetY(), srcWorld.offsetZ(), srcWorld.spreadStart(), srcWorld.spreadEnd(),
            srcWorld.jitterDegrees(), srcWorld.jitterSpeed(), srcWorld.driftX(), srcWorld.driftY(), srcWorld.driftZ(),
            srcWorld.driftVariance(), srcWorld.torqueSpeed(), srcWorld.torqueAmount(), srcWorld.motionCurve(),
            srcWorld.spawnDelayVarianceMs()
        );
        newWorldLayers.add(layerIndex + 1, duplicatedWorld);
        
        // Copy the corresponding layer definition
        if (layerIndex < newLayers.size()) {
            ParticleLayerDefinition srcLayer = newLayers.get(layerIndex);
            ParticleLayerDefinition duplicatedLayer = new ParticleLayerDefinition(
                srcLayer.movement(),
                new ArrayList<>(srcLayer.colors()),  // Deep copy colors list
                srcLayer.lifespan(), srcLayer.spawnInterval(), srcLayer.size(),
                srcLayer.speed(), srcLayer.weight(), srcLayer.previewScale()
            );
            newLayers.add(layerIndex + 1, duplicatedLayer);
        } else {
            // If layer doesn't exist, create a default one
            newLayers.add(layerIndex + 1, createDefaultLayer());
        }
        
        ParticleDefinition tempDef = new ParticleDefinition(
            editorState.workingCopy.id(),
            newLayers,
            newWorldLayers,
            editorState.workingCopy.description(),
            editorState.workingCopy.styleHint(),
            editorState.workingCopy.displayName(),
            editorState.workingCopy.notes()
        );
        // Enforce 1:1 mapping (should already be correct, but ensures consistency)
        editorState.workingCopy = ensureLayerCountMatchesWorldLayers(tempDef);
        editorState.markDirty();
        updateButtonStates();
        rebuildTabWidgets(); // This calls computeWorldScrollMetrics() at the start
        updatePreviewIfActive();
    }
    
    private void moveWorldLayer(int layerIndex, int direction) {
        if (editorState.workingCopy == null) return;
        List<WorldLayerDefinition> newWorldLayers = new ArrayList<>(editorState.workingCopy.worldLayers());
        List<ParticleLayerDefinition> newLayers = new ArrayList<>(editorState.workingCopy.layers());
        int newIndex = layerIndex + direction;
        if (newIndex >= 0 && newIndex < newWorldLayers.size()) {
            // Move both world layer and corresponding layer together to maintain 1:1 mapping
            WorldLayerDefinition tempWorld = newWorldLayers.get(layerIndex);
            newWorldLayers.set(layerIndex, newWorldLayers.get(newIndex));
            newWorldLayers.set(newIndex, tempWorld);
            
            // Swap corresponding layers if they exist
            if (layerIndex < newLayers.size() && newIndex < newLayers.size()) {
                ParticleLayerDefinition tempLayer = newLayers.get(layerIndex);
                newLayers.set(layerIndex, newLayers.get(newIndex));
                newLayers.set(newIndex, tempLayer);
            }
            
            ParticleDefinition tempDef = new ParticleDefinition(
                editorState.workingCopy.id(),
                newLayers,
                newWorldLayers,
                editorState.workingCopy.description(),
                editorState.workingCopy.styleHint(),
                editorState.workingCopy.displayName(),
                editorState.workingCopy.notes()
            );
            // Enforce 1:1 mapping (should already be correct, but ensures consistency)
            editorState.workingCopy = ensureLayerCountMatchesWorldLayers(tempDef);
            editorState.markDirty();
            updateButtonStates();
            rebuildTabWidgets();
            updatePreviewIfActive();
        }
    }
    
    private void createNewDefinition() {
        // Prompt for ID
        net.minecraft.client.Minecraft.getInstance().setScreen(
            new com.pastlands.cosmeticslite.client.screen.ParticleIdPromptScreen(
                this,
                "Enter new particle ID:",
                id -> {
                    if (id != null && !id.getPath().isEmpty()) {
                        // Create default definition
                        ParticleDefinition defaultDef = createDefaultDefinition(id);
                        // Add to editor's in-memory collection
                        editorDefinitions.put(id, deepCopyDefinition(defaultDef));
                        // Refresh list and select new entry
                        refreshDefinitionList();
                        selectDefinition(id);
                        net.minecraft.client.Minecraft.getInstance().setScreen(this);
                    }
                }
            )
        );
    }
    
    private void duplicateDefinition() {
        if (editorState.selectedId == null) return;
        
        net.minecraft.client.Minecraft.getInstance().setScreen(
            new com.pastlands.cosmeticslite.client.screen.ParticleIdPromptScreen(
                this,
                "Enter new ID for duplicate:",
                newId -> {
                    if (newId != null && !newId.getPath().isEmpty()) {
                        // Copy current working copy with new ID (includes both layers and worldLayers)
                        ParticleDefinition dup = new ParticleDefinition(
                            newId,
                            editorState.workingCopy.layers(),
                            editorState.workingCopy.worldLayers(),
                            editorState.workingCopy.description(),
                            editorState.workingCopy.styleHint(),
                            editorState.workingCopy.displayName(),
                            editorState.workingCopy.notes()
                        );
                        // Add to editor collection
                        editorDefinitions.put(newId, deepCopyDefinition(dup));
                        refreshDefinitionList();
                        // Select the new duplicate
                        selectDefinition(newId);
                        editorState.dirty = true;
                        editorState.isNew = true;
                        refreshEditorUI();
                        updateButtonStates();
                        net.minecraft.client.Minecraft.getInstance().setScreen(this);
                    }
                }
            )
        );
    }
    
    private static ParticleDefinition deepCopyDefinition(ParticleDefinition def) {
        if (def == null) return null;
        List<ParticleLayerDefinition> layersCopy = new ArrayList<>();
        for (var layer : def.layers()) {
            layersCopy.add(new ParticleLayerDefinition(
                layer.movement(),
                new ArrayList<>(layer.colors()),
                layer.lifespan(),
                layer.spawnInterval(),
                layer.size(),
                layer.speed(),
                layer.weight(),
                layer.previewScale()
            ));
        }
        List<WorldLayerDefinition> worldLayersCopy = new ArrayList<>();
        for (var worldLayer : def.worldLayers()) {
            worldLayersCopy.add(new WorldLayerDefinition(
                worldLayer.effect(),
                worldLayer.style(),
                worldLayer.radius(),
                worldLayer.heightFactor(),
                worldLayer.count(),
                worldLayer.speedY(),
                worldLayer.yOffset(),
                worldLayer.xScale(),
                worldLayer.direction()
            ));
        }
        return new ParticleDefinition(
            def.id(),
            layersCopy,
            worldLayersCopy,
            def.description(),
            def.styleHint(),
            def.displayName(),
            def.notes()
        );
    }
    
    private void saveDefinition() {
        if (editorState.workingCopy == null) return;
        if (!editorState.dirty && !editorState.isNew) return;  // No changes
        
        ParticleDefinitionChangePacket.ChangeType changeType = 
            editorState.isNew ? ParticleDefinitionChangePacket.ChangeType.CREATE 
                              : ParticleDefinitionChangePacket.ChangeType.UPDATE;
        
        // Log before sending
        CosmeticsLite.LOGGER.info("[ParticleLab] Sending SAVE for {}: {} layer(s), {} world layer(s)",
            editorState.workingCopy.id(), 
            editorState.workingCopy.layers().size(), 
            editorState.workingCopy.worldLayers().size());
        
        ParticleDefinitionChangePacket packet = new ParticleDefinitionChangePacket(
            changeType,
            editorState.workingCopy.id(),
            editorState.workingCopy
        );
        CosmeticsLite.NETWORK.sendToServer(packet);
        
        // Update editor's in-memory collection immediately (optimistic update)
        // Ensure both layers and worldLayers are included
        editorDefinitions.put(editorState.workingCopy.id(), deepCopyDefinition(editorState.workingCopy));
        refreshDefinitionList();
        
        // Keep the saved definition selected after refresh
        ResourceLocation savedId = editorState.workingCopy.id();
        
        // Mark as saved (will be confirmed when sync packet arrives)
        editorState.markSaved();
        updateButtonStates();
        // Preview continues to read from workingCopy (which is now saved), so mark dirty to ensure fresh rebuild
        markPreviewDirty();
        
        // Ensure saved definition remains selected
        if (savedId != null && editorDefinitions.containsKey(savedId)) {
            selectDefinition(savedId);
        }
    }
    
    /**
     * Called when definitions are synced from server.
     * Fully replaces the editor's in-memory collection from the authoritative client registry.
     * Ensures both layers and worldLayers are preserved.
     */
    public void onDefinitionsSynced(Map<ResourceLocation, ParticleDefinition> syncedDefinitions) {
        // Rebuild editorDefinitions entirely from the authoritative client registry
        // (which was just updated by ParticleDefinitionsSyncPacket handler)
        editorDefinitions.clear();
        
        // Get all definitions from the client registry (authoritative source)
        for (var def : com.pastlands.cosmeticslite.particle.config.CosmeticParticleRegistry.all()) {
            // Verify it has both layers and worldLayers
            if (def.layers() != null && def.worldLayers() != null) {
                ParticleDefinition defCopy = deepCopyDefinition(def);
                // Enforce 1:1 mapping when syncing definitions
                defCopy = ensureLayerCountMatchesWorldLayers(defCopy);
                editorDefinitions.put(def.id(), defCopy);
            }
        }
        
        // Check if currently selected definition was deleted
        ResourceLocation previouslySelected = editorState.selectedId;
        boolean wasDeleted = previouslySelected != null && 
            !com.pastlands.cosmeticslite.particle.config.CosmeticParticleRegistry.contains(previouslySelected);
        
        // If the selected definition was deleted, clear selection and stop preview
        if (wasDeleted) {
            editorState.selectedId = null;
            editorState.workingCopy = null;
            editorState.originalDefinition = null;
            editorState.dirty = false;
            editorState.isNew = false;
            ParticlePreviewState.stopPreview();
        }
        
        // Rebuild the left-hand list widget from editorDefinitions (removes stale entries)
        refreshDefinitionList();
        
        // Update button states (Delete button visibility depends on selection)
        updateButtonStates();
        
        // Rebuild tab widgets if selection was cleared
        if (wasDeleted) {
            rebuildTabWidgets();
        } else if (previouslySelected != null && editorDefinitions.containsKey(previouslySelected)) {
            // Re-select the same definition after refresh
            selectDefinition(previouslySelected);
        }
    }
    
    /**
     * Called when catalog entries are synced from server.
     * Updates the UI to reflect catalog changes (e.g., published particles removed).
     */
    public void onCatalogSynced() {
        // Catalog sync doesn't directly affect the particle definition list,
        // but we should refresh button states in case publish status changed
        updateButtonStates();
    }
    
    private void revertChanges() {
        if (editorState.originalDefinition == null) {
            // If it's a new definition, just clear it
            editorState.selectedId = null;
            editorState.originalDefinition = null;
            editorState.workingCopy = null;
            editorState.dirty = false;
            editorState.isNew = false;
        } else {
            editorState.revert();
        }
        refreshEditorUI();
        updateButtonStates();
    }
    
    private void togglePreview() {
        boolean isCurrentlyPreviewing = ParticlePreviewState.isActive() && 
            ParticlePreviewState.getCurrentPreviewId() != null &&
            editorState.selectedId != null &&
            ParticlePreviewState.getCurrentPreviewId().equals(editorState.selectedId);
        
        if (isCurrentlyPreviewing) {
            // Stop preview - send packet to server and clear override
            CosmeticsLite.LOGGER.info("[ParticleLab] Stopping preview for: {}", editorState.selectedId);
            com.pastlands.cosmeticslite.network.ParticlePreviewPacket stopPacket = 
                new com.pastlands.cosmeticslite.network.ParticlePreviewPacket(false, null);
            CosmeticsLite.NETWORK.sendToServer(stopPacket);
            ParticlePreviewState.setPreviewOverride(editorState.selectedId, null);
            ParticlePreviewState.stopPreview();
            previewBtn.setMessage(Component.literal("Preview"));
        } else if (editorState.workingCopy != null && editorState.selectedId != null) {
            // Start preview with workingCopy (unsaved values) - send packet to server
            CosmeticsLite.LOGGER.info("[ParticleLab] Starting preview for: {} (with unsaved workingCopy)", editorState.selectedId);
            com.pastlands.cosmeticslite.network.ParticlePreviewPacket startPacket = 
                new com.pastlands.cosmeticslite.network.ParticlePreviewPacket(true, editorState.selectedId);
            CosmeticsLite.NETWORK.sendToServer(startPacket);
            // Set local preview state with workingCopy (unsaved values)
            ParticlePreviewState.startPreview(editorState.selectedId, editorState.workingCopy);
            ParticlePreviewState.setPreviewOverride(editorState.selectedId, editorState.workingCopy);
            previewBtn.setMessage(Component.literal("Stop Preview"));
        }
    }
    
    private void openPublishDialog() {
        if (editorState.selectedId == null || editorState.workingCopy == null) return;
        
        PublishCosmeticDialogScreen dialog = new PublishCosmeticDialogScreen(
            this,
            editorState.selectedId,
            publishData -> publishCosmetic(publishData.displayName(), publishData.iconId())
        );
        this.minecraft.setScreen(dialog);
    }
    
    private void publishCosmetic(String displayName, net.minecraft.resources.ResourceLocation iconId) {
        if (editorState.selectedId == null || editorState.workingCopy == null) return;
        
        // Part 1: If there are unsaved changes, save first before publishing
        // This ensures publish never discards newer edits than the last SAVE
        if (editorState.dirty || editorState.isNew) {
            CosmeticsLite.LOGGER.info("[ParticleLab] Publish: unsaved changes detected, saving first...");
            // Save the working copy (this will send SAVE packet and mark as saved)
            saveDefinition();
            // Note: We proceed with publish immediately after save.
            // The sync packet will arrive later and update the registry, but the server
            // will use the latest saved definition when publishing.
        }
        
        // Generate cosmetic ID from particle ID
        // e.g. cosmeticslite:particle/angel_wisps_blended -> cosmeticslite:cosmetic/angel_wisps_blended
        ResourceLocation particleId = editorState.selectedId;
        
        // Extract primary color from first GUI layer for optional tint
        Integer argbTint = null;
        if (!editorState.workingCopy.layers().isEmpty()) {
            var layer = editorState.workingCopy.layers().get(0);
            if (!layer.colors().isEmpty()) {
                // Colors are stored as Integer (ARGB)
                argbTint = layer.colors().get(0);
            }
        }
        
        // Use provided iconId or fallback to default
        ResourceLocation iconItemId = iconId != null 
            ? iconId 
            : com.pastlands.cosmeticslite.particle.CosmeticIconRegistry.DEFAULT_PARTICLE_ICON;
        
        // Send packet to server to publish (slot is hard-coded to AURA for Particle Lab)
        com.pastlands.cosmeticslite.network.PublishCosmeticPacket packet = 
            new com.pastlands.cosmeticslite.network.PublishCosmeticPacket(
                particleId,
                displayName,
                iconItemId,
                argbTint
            );
        CosmeticsLite.NETWORK.sendToServer(packet);
        
        CosmeticsLite.LOGGER.info("[ParticleLab] Sent publish request: {} -> {} (icon: {})", 
            particleId, displayName, iconItemId);
    }
    
    /**
     * Client-side check if a particle is a lab definition (can be deleted).
     * Uses the actual client registry state: if it's in the registry AND NOT a built-in preset, it's lab.
     * 
     * @param id The particle ID to check
     * @return true if it's a lab definition that can be deleted
     */
    private boolean isLabDefinitionClient(ResourceLocation id) {
        if (id == null) return false;
        
        // Must be cosmeticslite namespace and particle/ path
        if (!"cosmeticslite".equals(id.getNamespace())) return false;
        if (!id.getPath().startsWith("particle/")) return false;
        
        // If it's not in the client registry at all, it's not deletable
        if (!com.pastlands.cosmeticslite.particle.config.CosmeticParticleRegistry.contains(id)) {
            return false;
        }
        
        // Built-in presets are never deletable
        if (com.pastlands.cosmeticslite.particle.config.CosmeticParticleRegistry.isBuiltinPreset(id)) {
            return false;
        }
        
        // Everything else that currently exists in the client registry is a lab definition
        return true;
    }
    
    /**
     * Delete the currently selected lab definition.
     * Shows confirmation dialog, then sends delete packet to server.
     */
    private void deleteDefinition() {
        if (editorState.selectedId == null) return;
        
        // Guard against deleting already-deleted entries
        if (!com.pastlands.cosmeticslite.particle.config.CosmeticParticleRegistry.contains(editorState.selectedId) ||
            !isLabDefinitionClient(editorState.selectedId)) {
            CosmeticsLite.LOGGER.debug("[ParticleLab] Skipping delete for non-existent or non-lab definition: {}", editorState.selectedId);
            return;
        }
        
        // Show confirmation dialog
        this.minecraft.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(
            confirmed -> {
                if (confirmed) {
                    // Send delete packet to server
                    com.pastlands.cosmeticslite.network.ParticleLabDeletePacket packet = 
                        new com.pastlands.cosmeticslite.network.ParticleLabDeletePacket(editorState.selectedId);
                    CosmeticsLite.NETWORK.sendToServer(packet);
                    
                    CosmeticsLite.LOGGER.info("[ParticleLab] Sent delete request for: {}", editorState.selectedId);
                    
                    // Clear selection immediately (will be confirmed when sync packet arrives)
                    editorState.selectedId = null;
                    editorState.workingCopy = null;
                    editorState.originalDefinition = null;
                    editorState.dirty = false;
                    editorState.isNew = false;
                    
                    // Stop preview if active
                    ParticlePreviewState.stopPreview();
                    
                    // Refresh UI
                    updateButtonStates();
                    rebuildTabWidgets();
                    refreshDefinitionList();
                }
                this.minecraft.setScreen(this);
            },
            Component.literal("Delete this particle?"),
            Component.literal("This will permanently delete:\n" + editorState.selectedId + "\n\nThis action cannot be undone.")
        ));
    }
    
    private void updatePreviewButtonLabel() {
        if (previewBtn == null) return;
        // Task D1: Button label should reflect preview state, not just for current selection
        boolean isPreviewing = ParticlePreviewState.isActive();
        previewBtn.setMessage(Component.literal(isPreviewing ? "Stop Preview" : "Preview"));
    }
    
    private ParticleDefinition createDefaultDefinition(ResourceLocation id) {
        // Create a minimal default definition with neutral, simple defaults
        // GUI layer: Simple FLOAT_UP movement with white particles that rise gently
        List<ParticleLayerDefinition> defaultLayers = new ArrayList<>();
        defaultLayers.add(new ParticleLayerDefinition(
            "FLOAT_UP",  // Simple upward drift - neutral movement
            new ArrayList<>(List.of(0xFFFFFFFF)),  // White color
            20.0f,  // lifespan = 20 seconds (allows particles to be visible)
            4.0f,   // spawnInterval = 4 seconds (spawns every 4 seconds)
            0.20f,  // size = 0.20 (small, visible particles)
            0.10f,  // speed = 0.10 (gentle speed)
            1.0f,   // weight = 1.0 (neutral weight)
            1.0f    // previewScale = 1.0
        ));
        
        // World layer: Simple, non-spiral pattern close to player
        // Use "column" style (simple vertical column, no rotation) instead of "halo" (which rotates and looks spiral-like)
        // Use a fully controllable blended effect for new particles so all editor controls work (no warnings)
        List<WorldLayerDefinition> defaultWorldLayers = new ArrayList<>();
        defaultWorldLayers.add(new WorldLayerDefinition(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("cosmeticslite", "particle/flame_blended"),  // Fully controllable blended effect
            "column",  // Use "column" style (simple vertical column, no rotation) - neutral and visible
            0.20f,     // Small radius (0.15-0.25 range) - keeps particles close to player in a tight clump
            1.0f,      // heightFactor = 1.0 (neutral height, centered on player)
            6,         // count = 6 (4-8 range) - visible but not overwhelming
            1.0f,      // speedY = 1.0 (base speed multiplier)
            null,      // yOffset = null
            null,      // xScale = null
            null       // direction = null (no spiral/rotation)
        ));
        
        ParticleDefinition def = new ParticleDefinition(id, defaultLayers, defaultWorldLayers, null, null, "", "");
        
        // Log the creation for debugging
        CosmeticsLite.LOGGER.info(
            "[ParticleLab] Creating NEW definition: id={} -> {} GUI layer(s), {} world layer(s)",
            id, defaultLayers.size(), defaultWorldLayers.size()
        );
        
        return def;
    }
    
    /**
     * Layout footer buttons anchored to right panel bounds.
     * All buttons on a single horizontal row inside the editor panel.
     * Left group: Add (if present), Save, Revert
     * Right group: Publish Cosmetic..., Preview/Stop Preview
     * 
     * For Preview tab: buttons are positioned below the preview area.
     * For other tabs: buttons are positioned at the bottom of the panel.
     */
    private void layoutFooterButtons(Button addButton, Button saveButton, Button revertButton,
                                     Button publishButton, Button previewButton, Button deleteButton) {
        int buttonHeight = 20;
        int spacing = 4;
        
        // Ensure footer buttons are wide enough for their current labels (prevents text overlap)
        if (addButton != null) {
            addButton.setWidth(this.font.width(addButton.getMessage()) + 8);
        }
        if (saveButton != null) {
            saveButton.setWidth(this.font.width(saveButton.getMessage()) + 8);
        }
        if (revertButton != null) {
            revertButton.setWidth(this.font.width(revertButton.getMessage()) + 8);
        }
        if (deleteButton != null) {
            deleteButton.setWidth(this.font.width(deleteButton.getMessage()) + 8);
        }
        
        // Calculate right panel bounds (same rect used for cards)
        int panelLeft = left + leftPanelWidth + 20;
        int panelRight = right - 20;
        
        int footerY;
        if ("preview".equals(activeTab)) {
            // Preview tab: buttons sit below the preview area
            // Footer Y: below preview area with padding
            footerY = previewPanelBottom + 6; // 6px padding below preview
        } else {
            // Other tabs: buttons at bottom of panel
            int panelBottom = getLayersPanelBottom(); // Same for Layers and World tabs
            // Footer Y: anchored to panel bottom with small padding
            footerY = panelBottom - 22; // 20px button height + 2px padding
        }
        
        // Left group: Add, Save, Revert (aligned from panelLeft)
        int leftX = panelLeft;
        
        if (addButton != null) {
            addButton.setX(leftX);
            addButton.setY(footerY);
            leftX += addButton.getWidth() + spacing;
        }
        
        if (saveButton != null) {
            saveButton.setX(leftX);
            saveButton.setY(footerY);
            leftX += saveButton.getWidth() + spacing;
        }
        
        if (revertButton != null) {
            revertButton.setX(leftX);
            revertButton.setY(footerY);
            leftX += revertButton.getWidth() + spacing;
        }
        
        // Delete button: after Revert (red button)
        if (deleteButton != null) {
            deleteButton.setX(leftX);
            deleteButton.setY(footerY);
        }
        
        // Right group: Publish, Preview (aligned from panelRight)
        // Preview button: fixed width for "Stop Preview" to prevent resizing
        int previewWidth = this.font.width("Stop Preview") + 8;
        if (previewButton != null) {
            previewButton.setWidth(previewWidth);
            int previewX = panelRight - previewWidth;
            previewButton.setX(previewX);
            previewButton.setY(footerY);
            
            // Publish button: to the left of Preview
            if (publishButton != null) {
                int publishWidth = this.font.width("Publish Cosmetic...") + 8;
                int publishX = previewX - spacing - publishWidth;
                publishButton.setWidth(publishWidth);
                publishButton.setX(publishX);
                publishButton.setY(footerY);
            }
        } else if (publishButton != null) {
            // Only publish button (no preview)
            int publishWidth = this.font.width("Publish Cosmetic...") + 8;
            int publishX = panelRight - publishWidth;
            publishButton.setWidth(publishWidth);
            publishButton.setX(publishX);
            publishButton.setY(footerY);
        }
    }
    
    private void updateButtonStates() {
        if (saveBtn != null) {
            saveBtn.active = editorState.dirty || editorState.isNew;
        }
        if (revertBtn != null) {
            revertBtn.active = editorState.dirty || editorState.isNew;
        }
        if (deleteBtn != null) {
            // Delete button: only visible and active for lab definitions
            // Use client-side check: in registry AND NOT a built-in preset
            boolean isLabDef = isLabDefinitionClient(editorState.selectedId);
            deleteBtn.visible = isLabDef;
            deleteBtn.active = isLabDef && !editorState.isNew;
        }
        if (publishBtn != null) {
            // Enable publish button if we have a selected particle definition
            // Disable for vanilla wrapper profiles (optional, based on user requirements)
            boolean canPublish = editorState.selectedId != null && editorState.workingCopy != null;
            publishBtn.active = canPublish;
        }
    }
    
    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        // Debounced preview refresh (rebuild preview emitter when dirty)
        // Refresh whenever preview is dirty, regardless of active tab (preview can be active on player even when not on Preview tab)
        if (previewDirty) {
            long now = System.currentTimeMillis();
            // Debounce: only rebuild if enough time has passed since last edit
            if (now - previewDirtyAtMs >= PREVIEW_DEBOUNCE_MS) {
                previewDirty = false;
                rebuildPreviewFromWorkingCopy();
            }
        }
        
        // Only render dark background if NOT on Preview tab (Preview tab shows world behind)
        if (!"preview".equals(activeTab)) {
            this.renderBackground(gfx);
        }
        
        // Draw panels
        gfx.fill(left, top, left + leftPanelWidth, bottom, 0xFF2A2A2E);
        
        // Draw right panel background, but skip the preview area if on Preview tab
        if ("preview".equals(activeTab)) {
            // Draw right panel background in sections, avoiding the preview area
            int rightPanelLeft = left + leftPanelWidth + 10;
            int previewTop = previewPanelTop;
            int previewBottom = previewPanelBottom;
            
            // Top section (above preview)
            if (top < previewTop) {
                gfx.fill(rightPanelLeft, top, right, previewTop, 0xFF3A3A3F);
            }
            // Bottom section (below preview)
            if (previewBottom < bottom) {
                gfx.fill(rightPanelLeft, previewBottom, right, bottom, 0xFF3A3A3F);
            }
            // Left section (to the left of preview, if any)
            if (rightPanelLeft < previewPanelLeft) {
                gfx.fill(rightPanelLeft, previewTop, previewPanelLeft, previewBottom, 0xFF3A3A3F);
            }
            // Right section (to the right of preview, if any)
            if (previewPanelRight < right) {
                gfx.fill(previewPanelRight, previewTop, right, previewBottom, 0xFF3A3A3F);
            }
        } else if ("layers".equals(activeTab) || "world_layers".equals(activeTab)) {
            // Layers and World tabs use transparent background - don't draw opaque fill here
            // The transparent background will be drawn in renderLayersTab/renderWorldLayersTab
        } else {
            // Normal solid fill for other tabs (General, etc.)
            gfx.fill(left + leftPanelWidth + 10, top, right, bottom, 0xFF3A3A3F);
        }
        
        // Draw definition list
        renderDefinitionList(gfx, mouseX, mouseY);
        
        // Draw editor content (backgrounds, etc.)
        renderEditor(gfx, mouseX, mouseY);
        
        final boolean isWorld = "world_layers".equals(activeTab);
        final boolean isLayers = "layers".equals(activeTab);
        
        // Always start from a known state: hide both tab widgets to prevent wash-over
        setWorldWidgetsVisible(false);
        setLayersWidgetsVisible(false);
        
        // Render widgets (scissor is OFF here - header/footer/tab buttons render normally)
        super.render(gfx, mouseX, mouseY, partialTick);
        
        // Manually render widgets inside scissor - mutually exclusive blocks
        if (isWorld) {
            // Turn World widgets back on for manual rendering
            setWorldWidgetsVisible(true);
            
            // Define the World viewport in GUI coords (inner "World Pattern" window only)
            int scLeft = getWorldPanelLeft();
            int scRight = getWorldPanelRight();
            int scTop = worldViewportTop;
            int scBottom = worldViewportBottom;
            
            // Enable scissor ONLY for World viewport using the conversion helper
            try {
                enableScissorGuiRect(scLeft, scTop, scRight, scBottom);
                
                // Render World widgets clipped
                renderWorldWidgetsManually(gfx, mouseX, mouseY, partialTick);
                
                // Render expanded dropdown overlays clipped too
                renderWorldLayersTabDropdowns(gfx, mouseX, mouseY);
            } finally {
                disableScissor();
            }
        } else if (isLayers) {
            // Turn Layers widgets back on for manual rendering
            setLayersWidgetsVisible(true);
            
            // Define the Layers viewport in GUI coords (inner "Layer Behavior" window only)
            int panelLeft = left + leftPanelWidth + 20;
            int panelRight = right - 20;
            
            // IMPORTANT: scissor only the viewport region (not header/footer)
            // Use Layers viewport vars only (not World vars)
            int scLeft = panelLeft;
            int scTop = layersViewportTop;
            int scRight = panelRight;
            int scBottom = layersViewportBottom;
            
            // Enable scissor ONLY for Layers viewport using the conversion helper
            try {
                enableScissorGuiRect(scLeft, scTop, scRight, scBottom);
                
                // Render Layers widgets clipped
                renderLayersWidgetsManually(gfx, mouseX, mouseY, partialTick);
                
                // Render expanded dropdown overlays clipped too
                renderLayersTabDropdowns(gfx, mouseX, mouseY);
            } finally {
                disableScissor();
            }
        }
    }
    
    private void renderDefinitionList(GuiGraphics gfx, int mouseX, int mouseY) {
        int listTop = top + 60;
        int itemHeight = 24; // Increased from 20 to 24 for better spacing
        int itemPadding = 4; // Vertical padding
        int visibleItems = (bottom - listTop - 10) / itemHeight;
        int listLeft = left + 10;
        int listRight = left + leftPanelWidth - 10;
        
        for (int i = 0; i < Math.min(visibleItems, filteredDefinitions.size() - scrollOffset); i++) {
            int idx = scrollOffset + i;
            if (idx >= filteredDefinitions.size()) break;
            
            ResourceLocation id = filteredDefinitions.get(idx);
            int itemY = listTop + i * itemHeight;
            boolean selected = id.equals(editorState.selectedId);
            boolean hovered = mouseX >= listLeft && mouseX < listRight &&
                              mouseY >= itemY && mouseY < itemY + itemHeight;
            
            // Improved highlight style
            int bgColor = selected ? 0xFF3A4A5E : (hovered ? 0xFF3A3A3E : 0xFF2A2A2E);
            gfx.fill(listLeft, itemY, listRight, itemY + itemHeight, bgColor);
            
            // Show short ID (path only) instead of full ID
            String shortId = id.getPath(); // e.g., "test1", "flame_blended"
            int textColor = selected ? 0xFFFFFF : 0xCCCCCC; // White for selected, gray for inactive
            gfx.drawString(this.font, shortId, listLeft + 8, itemY + itemPadding + 4, textColor, false);
        }
    }
    
    private void renderEditor(GuiGraphics gfx, int mouseX, int mouseY) {
        int panelLeft = left + leftPanelWidth + 20;
        int panelTop = top + 40;
        
        if (editorState.workingCopy == null) {
            gfx.drawCenteredString(this.font, "Select a definition to edit", 
                (left + leftPanelWidth + right) / 2, panelTop, 0xAAAAAA);
            return;
        }
        
        // Render tab content (this draws the panel background for each tab)
        switch (activeTab) {
            case "general":
                renderGeneralTab(gfx, panelLeft, panelTop);
                break;
            case "layers":
                renderLayersTab(gfx, panelLeft, panelTop);
                break;
            case "world_layers":
                renderWorldLayersTab(gfx, mouseX, mouseY);
                break;
            case "preview":
                renderPreviewTab(gfx, panelLeft, panelTop);
                break;
        }
        
        // Show dirty indicator inside the panel background (after tab content is drawn)
        if (editorState.dirty || editorState.isNew) {
            int statusY = panelTop + 4;   // a few pixels inside the panel
            int statusX = panelLeft + 4;  // slight inset from panel left
            String status = editorState.isNew ? "New (unsaved)" : "Modified (unsaved)";
            gfx.drawString(this.font, status, statusX, statusY, 0xFFFF00, false);
        }
        
        // Render dropdowns on top for active tab (mouse coords passed from render method)
    }
    
    private void renderGeneralTab(GuiGraphics gfx, int x, int y) {
        if (editorState.workingCopy == null) return;
        
        int labelX = x + 20; // Consistent left-aligned label offset
        int fieldX = x + 120; // Field starts after label
        int startY = top + 70; // Moved down ~10px from top
        
        // ID row - label and read-only field (styled like EditBox)
        int idY = startY;
        gfx.drawString(this.font, "ID:", labelX, idY + 5, 0xAAAAAA, false);
        // Draw read-only ID field background (styled like EditBox)
        String idText = editorState.workingCopy.id().toString();
        gfx.fill(fieldX, idY, fieldX + 300, idY + 20, 0xFF1A1A1E);
        gfx.fill(fieldX + 1, idY + 1, fieldX + 299, idY + 19, 0xFF0A0A0E);
        gfx.drawString(this.font, idText, fieldX + 4, idY + 6, 0xCCCCCC, false); // Gray text for read-only
        
        // Display Name label
        int displayNameY = startY + 40;
        gfx.drawString(this.font, "Display Name:", labelX, displayNameY + 5, 0xAAAAAA, false);
        // EditBox renders itself
        
        // Notes label
        int notesY = displayNameY + 40;
        gfx.drawString(this.font, "Notes:", labelX, notesY + 5, 0xAAAAAA, false);
        // EditBox renders itself
    }
    
    private void renderLayersTab(GuiGraphics gfx, int x, int y) {
        if (editorState.workingCopy == null) return;
        
        // Define fixed Layers content panel (match rebuildLayerWidgets)
        int panelLeft = left + leftPanelWidth + 20;
        int panelRight = right - 20;
        int basePanelTop = getLayersPanelTop();
        int panelBottom = getLayersPanelBottom();
        int cardWidth = panelRight - panelLeft - 20;
        
        // Add descriptive label at top of Layers tab
        String descriptionText = "Layer Behavior – how each particle moves (path, speed, color).";
        int descBaseY = basePanelTop + 2;
        int descX = panelLeft + 4;
        gfx.drawString(this.font, descriptionText, descX, descBaseY, 0xFFAAAAAA, false);
        
        // Draw transparent background covering the entire content area (behind all controls)
        // Use the same color and blending as Preview tab
        final int PREVIEW_BACKGROUND_COLOR = 0x66000000; // 40% opacity black, same as Preview
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // Cover the full panel area from top to bottom, left to right
        gfx.fill(panelLeft, basePanelTop, panelRight, panelBottom, PREVIEW_BACKGROUND_COLOR);
        RenderSystem.disableBlend();
        
        List<WorldLayerDefinition> worldLayers = editorState.workingCopy.worldLayers();
        
        // Draw card backgrounds - one per world layer (draw-only, no scissor)
        for (int i = 0; i < worldLayers.size(); i++) {
            // Use single source of truth for card position
            int cardTop = getLayersCardTop(i);
            int cardBottom = cardTop + LAYERS_CARD_HEIGHT;
            
            // Viewport culling: skip cards fully off screen
            if (cardBottom < layersViewportTop || cardTop > layersViewportBottom) {
                continue;
            }
            
            int cardLeft = panelLeft + 10;
            int cardRight = panelLeft + 10 + cardWidth;
            
            // Card background - clamp to viewport to prevent overlap with bottom buttons
            int clampedCardTop = Math.max(cardTop, layersViewportTop);
            int clampedCardBottom = Math.min(cardBottom, layersViewportBottom);
            gfx.fill(cardLeft, clampedCardTop, cardRight, clampedCardBottom, 0xFF2A2A2E);
            gfx.fill(cardLeft + 1, clampedCardTop + 1, cardRight - 1, clampedCardBottom - 1, 0xFF1A1A1E);
            
            // Field labels are now rendered as LabelWidget instances, not drawn here
        }
        
        // Draw scrollbar (only if there's more content than viewport)
        if (layersMaxScroll > 0.0f) {
            int trackX = panelRight - 6;                 // 6px in from panel edge
            int trackWidth = 4;
            int trackTop = layersViewportTop + 2;
            int trackBottom = layersViewportBottom - 2;
            int trackHeight = trackBottom - trackTop;
            
            // Thumb height proportional to viewport/total, min 20px
            int layerCount = worldLayers.size();
            int totalHeight = layerCount * LAYERS_CARD_HEIGHT + (layerCount - 1) * LAYERS_CARD_GAP;
            int viewportHeight = layersViewportBottom - layersViewportTop;
            int thumbHeight = Math.max(20, (int)((double)viewportHeight * viewportHeight / totalHeight));
            int thumbTravel = trackHeight - thumbHeight;
            
            int thumbY = trackTop;
            if (thumbTravel > 0 && layersMaxScroll > 0.0f) {
                float scrollRatio = layersScrollOffset / layersMaxScroll;
                thumbY = trackTop + (int)(scrollRatio * thumbTravel);
            }
            
            int thumbBottom = thumbY + thumbHeight;
            
            // Track
            gfx.fill(trackX, trackTop, trackX + trackWidth, trackBottom, 0xFF303035);
            // Thumb (a bit lighter)
            gfx.fill(trackX, thumbY, trackX + trackWidth, thumbBottom, 0xFF808085);
        }
        
        // Render dropdowns on top (will be called from render method with mouse coords)
    }
    
    private void renderLayersTabDropdowns(GuiGraphics gfx, double mouseX, double mouseY) {
        // Render expanded dropdowns on top
        for (LayerWidgets lw : layerWidgets) {
            if (lw.movementDropdown != null && lw.movementDropdown.isExpanded()) {
                lw.movementDropdown.renderOnTop(gfx, (int)mouseX, (int)mouseY, 0);
            }
        }
    }
    
    private void addLayer() {
        if (editorState.workingCopy == null) return;
        
        List<ParticleLayerDefinition> newLayers = new ArrayList<>(editorState.workingCopy.layers());
        newLayers.add(new ParticleLayerDefinition(
            "FLOAT_UP",
            new ArrayList<>(List.of(0xFFFFFFFF)),
            1.0f,  // lifespan = 1.0
            0.2f,  // spawnInterval = 0.2
            0.2f,  // size = 0.2
            0.1f,  // speed = 0.1
            1.0f,  // weight = 1.0
            1.0f   // previewScale = 1.0
        ));
        
        editorState.workingCopy = new ParticleDefinition(
            editorState.workingCopy.id(),
            newLayers,
            editorState.workingCopy.worldLayers(),
            editorState.workingCopy.description(),
            editorState.workingCopy.styleHint(),
            editorState.workingCopy.displayName(),
            editorState.workingCopy.notes()
        );
        editorState.markDirty();
        updateButtonStates();
        // Rebuild widgets so the new layer's UI is created
        rebuildTabWidgets();
        updatePreviewIfActive();
    }
    
    private void renderWorldLayersTab(GuiGraphics gfx, int mouseX, int mouseY) {
        if (editorState.workingCopy == null) return;
        
        int panelLeft   = getWorldPanelLeft();
        int panelRight  = getWorldPanelRight();
        int panelTop    = getWorldPanelTop();
        int panelBottom = getWorldPanelBottom();
        
        // Add descriptive label at top of World tab
        String descriptionText = "World Pattern — particle spawn rules.";
        int descY = panelTop + 2;
        int descX = panelLeft + 4;
        gfx.drawString(this.font, descriptionText, descX, descY, 0xFFAAAAAA, false);
        
        // Show effect capability warnings for first world layer
        int warningY = descY + this.font.lineHeight + 2;
        if (!editorState.workingCopy.worldLayers().isEmpty()) {
            ResourceLocation effectId = editorState.workingCopy.worldLayers().get(0).effect();
            var caps = com.pastlands.cosmeticslite.client.editor.EffectCapabilitiesRegistry.get(effectId);
            if (!caps.supportsColorOverride() || !caps.supportsEditorMovement() || !caps.supportsScaleOverride() || !caps.supportsTint() || !caps.notes().isEmpty()) {
                String effectText = "Effect: " + effectId;
                if (!caps.notes().isEmpty()) {
                    effectText += " (⚠ " + caps.notes() + ")";
                } else {
                    effectText += " (⚠ Vanilla behavior – some controls limited)";
                }
                gfx.drawString(this.font, effectText, descX, warningY, 0xFFFFAA00, false);
                warningY += this.font.lineHeight + 2;
            }
        }
        
        // Clear header bounds list at start of render (safety for tooltip tracking)
        this.worldLayerHeaderBounds.clear();

        // Draw transparent background covering the entire content area (behind all controls)
        // Use the same color and blending as Preview tab
        final int PREVIEW_BACKGROUND_COLOR = 0x66000000; // 40% opacity black, same as Preview
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // Cover the full panel area from top to bottom, left to right
        gfx.fill(panelLeft, panelTop, panelRight, panelBottom, PREVIEW_BACKGROUND_COLOR);
        RenderSystem.disableBlend();
        
        int cardWidth = panelRight - panelLeft - 20;

        // Draw card backgrounds (draw-only, no scissor)
        for (int i = 0; i < editorState.workingCopy.worldLayers().size(); i++) {
            // Use single source of truth for card position
            int cardTop = getWorldCardTop(i);
            int cardBottom = cardTop + WORLD_CARD_HEIGHT;

            // Clamp rendering to viewport - skip cards fully outside view
            // Only cull if viewport is valid, otherwise show all cards
            if (worldViewportBottom > worldViewportTop) {
                if (cardBottom < worldViewportTop || cardTop > worldViewportBottom) {
                    continue;
                }
            }

            int cardLeft = panelLeft + 10;
            int cardRight = cardLeft + cardWidth;
            
            // Card background - clamp to viewport to prevent overlap with bottom buttons
            int clampedCardTop = Math.max(cardTop, worldViewportTop);
            int clampedCardBottom = Math.min(cardTop + WORLD_CARD_HEIGHT, worldViewportBottom);
            gfx.fill(cardLeft, clampedCardTop, cardRight, clampedCardBottom, 0xFF2A2A2E);
            gfx.fill(cardLeft + 1, clampedCardTop + 1, cardRight - 1, clampedCardBottom - 1, 0xFF1A1A1E);
            
            // Track header bounds for tooltip (only for rendered cards)
            String cardTitle = "World Layer " + (i + 1);
            int headerX = cardLeft + 2;
            int headerY = cardTop + 3;
            int headerWidth = this.font.width(cardTitle);
            
            // Get description from widget if available, otherwise generate it
            String description = "";
            if (i < worldLayerWidgets.size()) {
                WorldLayerWidgets wlw = worldLayerWidgets.get(i);
                if (wlw != null && wlw.description != null) {
                    description = wlw.description;
                } else {
                    // Generate description from layer data
                    WorldLayerDefinition worldLayer = editorState.workingCopy.worldLayers().get(i);
                    description = generateWorldLayerDescription(worldLayer.effect().toString(), worldLayer.style());
                }
            } else {
                // Widget not built yet, generate description directly
                WorldLayerDefinition worldLayer = editorState.workingCopy.worldLayers().get(i);
                description = generateWorldLayerDescription(worldLayer.effect().toString(), worldLayer.style());
            }
            
            // Store header bounds for this rendered card
            this.worldLayerHeaderBounds.add(new WorldLayerHeaderBounds(i, headerX, headerY, headerWidth, description));
            
            // Field labels are now rendered as LabelWidget instances, not drawn here
        }
        
        // Tooltip rendering for world layer headers (check hover after scissor is disabled)
        // Only iterate over tracked header bounds (crash-safe)
        for (WorldLayerHeaderBounds bounds : this.worldLayerHeaderBounds) {
            if (bounds.description != null && !bounds.description.isEmpty()) {
                int headerHeight = this.font.lineHeight;
                
                if (mouseX >= bounds.headerX && mouseX <= bounds.headerX + bounds.headerWidth &&
                    mouseY >= bounds.headerY && mouseY <= bounds.headerY + headerHeight) {
                    gfx.renderTooltip(
                        this.font,
                        Component.literal(bounds.description),
                        mouseX,
                        mouseY
                    );
                    break; // Only show one tooltip at a time
                }
            }
        }
        
        // Draw scrollbar (only if there's more content than viewport)
        if (worldMaxScroll > 0.0f) {
            int scrollbarLeft = panelRight - WORLD_SCROLLBAR_WIDTH - WORLD_SCROLLBAR_MARGIN;
            int scrollbarTop = worldViewportTop;
            int scrollbarHeight = worldViewportBottom - worldViewportTop;
            int viewportHeight = worldViewportBottom - worldViewportTop;
            
            // Calculate total content height for thumb size
            int layerCount = editorState.workingCopy.worldLayers().size();
            int totalHeight = layerCount * WORLD_CARD_HEIGHT + (layerCount - 1) * WORLD_CARD_GAP;
            
            // Track
            gfx.fill(scrollbarLeft, scrollbarTop, scrollbarLeft + WORLD_SCROLLBAR_WIDTH,
                     scrollbarTop + scrollbarHeight, 0x55000000);
            
            // Thumb size
            int thumbHeight = Math.max(20, (int)((double)viewportHeight * viewportHeight / totalHeight));
            int thumbTravel = scrollbarHeight - thumbHeight;
            
            int thumbY = scrollbarTop;
            if (thumbTravel > 0 && worldMaxScroll > 0.0f) {
                float progress = (worldMaxScroll == 0.0f) ? 0.0f : (worldScrollOffset / worldMaxScroll);
                thumbY = scrollbarTop + (int)(progress * thumbTravel);
            }
            
            int thumbBottom = thumbY + thumbHeight;
            
            // Thumb (same style as Layers)
            gfx.fill(scrollbarLeft, thumbY, scrollbarLeft + WORLD_SCROLLBAR_WIDTH, thumbBottom,
                     0xFFAAAAAA);
        }
    }
    
    private void renderWorldLayersTabDropdowns(GuiGraphics gfx, double mouseX, double mouseY) {
        // Render expanded dropdowns on top
        for (WorldLayerWidgets wlw : worldLayerWidgets) {
            if (wlw.effectDropdown != null && wlw.effectDropdown.isExpanded()) {
                wlw.effectDropdown.renderOnTop(gfx, (int)mouseX, (int)mouseY, 0);
            }
            if (wlw.styleDropdown != null && wlw.styleDropdown.isExpanded()) {
                wlw.styleDropdown.renderOnTop(gfx, (int)mouseX, (int)mouseY, 0);
            }
            if (wlw.rotationModeDropdown != null && wlw.rotationModeDropdown.isExpanded()) {
                wlw.rotationModeDropdown.renderOnTop(gfx, (int)mouseX, (int)mouseY, 0);
            }
            if (wlw.motionCurveDropdown != null && wlw.motionCurveDropdown.isExpanded()) {
                wlw.motionCurveDropdown.renderOnTop(gfx, (int)mouseX, (int)mouseY, 0);
            }
        }
    }
    
    private void renderPreviewTab(GuiGraphics gfx, int x, int y) {
        if (editorState.workingCopy == null) {
            gfx.drawCenteredString(this.font, "Select a definition to preview", 
                (left + leftPanelWidth + right) / 2, y + 50, 0xAAAAAA);
            return;
        }
        
        // Update preview definition if preview is already active (player-controlled only)
        updatePreviewBounds();
        
        // Panel rect - use consistent preview area bounds
        int previewTop = this.previewPanelTop;
        int previewBottom = this.previewPanelBottom;
        int panelLeft = this.previewPanelLeft;
        int panelRight = this.previewPanelRight;
        
        // 1) Draw border (opaque dark gray)
        int borderColor = 0xFF222222;
        
        // Top & bottom borders
        gfx.fill(panelLeft, previewTop, panelRight, previewTop + 1, borderColor);
        gfx.fill(panelLeft, previewBottom - 1, panelRight, previewBottom, borderColor);
        
        // Left & right borders
        gfx.fill(panelLeft, previewTop, panelLeft + 1, previewBottom, borderColor);
        gfx.fill(panelRight - 1, previewTop, panelRight, previewBottom, borderColor);
        
        // 2) Draw translucent "glass" interior
        int glassLeft = panelLeft + 1;
        int glassTop = previewTop + 1;
        int glassRight = panelRight - 1;
        int glassBottom = previewBottom - 1;
        
        // ARGB: 0x66 = ~40% opacity (more transparent than before). Enable blending for alpha transparency.
        // Dedicated constant for preview background only
        final int PREVIEW_BACKGROUND_COLOR = 0x66000000; // more transparent black
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        gfx.fill(glassLeft, glassTop, glassRight, glassBottom, PREVIEW_BACKGROUND_COLOR);
        RenderSystem.disableBlend();
        
        // 3) Preview mode indicator (if active) - positioned at top inside preview window
        if (ParticlePreviewState.isActive() && ParticlePreviewState.getCurrentPreviewId() != null &&
            ParticlePreviewState.getCurrentPreviewId().equals(editorState.selectedId)) {
            // Position status text at top of preview area (inside the preview window)
            int statusY = previewTop + 6; // Fixed offset from preview top
            gfx.drawString(this.font, "✓ Preview Active (in-world)", glassLeft + 6, statusY, 0x00FF00, false);
        }
    }
    
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Handle Layers tab - close dropdowns if clicking outside
        if ("layers".equals(activeTab)) {
            boolean clickedAnyDropdown = false;
            
            // Check if click is on any movement dropdown
            for (LayerWidgets lw : layerWidgets) {
                if (lw.movementDropdown != null && lw.movementDropdown.containsPoint(mouseX, mouseY)) {
                    clickedAnyDropdown = true;
                    break;
                }
            }
            
            if (!clickedAnyDropdown) {
                // Click is outside all dropdowns - close any open ones
                boolean hadOpenDropdown = false;
                for (LayerWidgets lw : layerWidgets) {
                    if (lw.movementDropdown != null && lw.movementDropdown.isExpanded()) {
                        hadOpenDropdown = true;
                        break;
                    }
                }
                if (hadOpenDropdown) {
                    closeAllMovementDropdowns();
                    // Don't return - let the click continue to other widgets
                }
            }
        }
        
        // Let widgets handle their own clicks (including dropdowns)
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        
        // Preview tab does not handle mouse input - let Minecraft handle it normally
        
        // Handle World tab scrollbar click
        if ("world_layers".equals(activeTab) && editorState.workingCopy != null) {
            if (clickedInsideWorldScrollbar(mouseX, mouseY)) {
                draggingWorldScrollbar = true;
                return true;
            }
        }
        
        // Handle definition list clicks
        int listTop = top + 60;
        int itemHeight = 24; // Match renderDefinitionList itemHeight
        int listLeft = left + 10;
        int listRight = left + leftPanelWidth - 10;
        if (mouseX >= listLeft && mouseX < listRight &&
            mouseY >= listTop && mouseY < bottom - 10) {
            int idx = scrollOffset + (int)((mouseY - listTop) / itemHeight);
            if (idx >= 0 && idx < filteredDefinitions.size()) {
                selectDefinition(filteredDefinitions.get(idx));
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Check if mouse click is inside the World tab scrollbar.
     */
    private boolean clickedInsideWorldScrollbar(double mouseX, double mouseY) {
        if (!"world_layers".equals(activeTab) || editorState.workingCopy == null) {
            return false;
        }
        
        int panelTop = getWorldPanelTop();
        int panelRight = getWorldPanelRight();
        int panelBottom = getWorldPanelBottom();
        
        int scrollbarLeft = panelRight - WORLD_SCROLLBAR_WIDTH - WORLD_SCROLLBAR_MARGIN;
        int scrollbarTop = panelTop;
        int scrollbarHeight = panelBottom - panelTop;
        
        return mouseX >= scrollbarLeft && mouseX <= scrollbarLeft + WORLD_SCROLLBAR_WIDTH &&
               mouseY >= scrollbarTop && mouseY <= scrollbarTop + scrollbarHeight;
    }
    
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        // Handle World tab scrollbar drag
        if (draggingWorldScrollbar && "world_layers".equals(activeTab) && editorState.workingCopy != null) {
            int panelTop = getWorldPanelTop();
            int panelBottom = getWorldPanelBottom();
            
            int scrollbarTop = panelTop;
            int scrollbarHeight = panelBottom - panelTop;
            int viewportHeight = worldViewportBottom - worldViewportTop;
            
            int layerCount = editorState.workingCopy.worldLayers().size();
            int totalHeight = layerCount * WORLD_CARD_HEIGHT + (layerCount - 1) * WORLD_CARD_GAP;
            int thumbHeight = Math.max(20, (int)((double)viewportHeight * viewportHeight / totalHeight));
            int thumbTravel = scrollbarHeight - thumbHeight;
            
            if (thumbTravel > 0) {
                float progress = (float)(mouseY - scrollbarTop) / thumbTravel;
                progress = net.minecraft.util.Mth.clamp(progress, 0.0f, 1.0f);
                worldScrollOffset = net.minecraft.util.Mth.clamp(
                    progress * worldMaxScroll,
                    0.0f,
                    worldMaxScroll
                );
                rebuildWorldLayerWidgets();
            }
            return true;
        }
        
        // Preview tab does not handle drag - let Minecraft handle it normally
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }
    
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingWorldScrollbar) {
            draggingWorldScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }
    
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        // First, let child widgets (like dropdowns) handle scroll events
        // This ensures dropdowns can scroll even when over panel content area
        if (super.mouseScrolled(mouseX, mouseY, delta)) {
            return true; // A child widget handled it (e.g., dropdown scrolling)
        }
        
        // Skip scroll handling for Layers tab if layers are locked
        if ("layers".equals(activeTab) && editorState.workingCopy != null) {
            boolean lockLayers = com.pastlands.cosmeticslite.client.editor.EffectCapabilitiesRegistry.isProfileVanillaWrapper(editorState.selectedId);
            if (lockLayers) {
                return false; // Don't handle scrolling for locked layers
            }
        }
        
        // Layers tab scroll handling (mirror World behavior)
        if ("layers".equals(activeTab) && editorState.workingCopy != null) {
            if (isMouseOverLayersViewport(mouseX, mouseY)) {
                if (layersMaxScroll > 0.0f) {
                    float oldOffset = layersScrollOffset;
                    float step = 12f;
                    layersScrollOffset = net.minecraft.util.Mth.clamp(
                        layersScrollOffset - (float)delta * step,
                        0.0f,
                        layersMaxScroll
                    );
                    if (layersScrollOffset != oldOffset) {
                        // Clear and rebuild ONLY Layers widgets
                        clearTabWidgets(Tab.LAYERS);
                        rebuildLayerWidgets();
                    }
                    return true;
                }
            }
        } else if ("world_layers".equals(activeTab) && editorState.workingCopy != null) {
            // Simple mouse wheel scroll for World tab - move existing widgets, do NOT rebuild
            int worldCardLeft = getWorldPanelLeft() + 10;
            int worldCardRight = getWorldPanelRight() - 10;
            boolean inside = mouseX >= worldCardLeft && mouseX <= worldCardRight
                          && mouseY >= worldViewportTop && mouseY <= worldViewportBottom;
            
            if (inside && worldMaxScroll > 0.0f) {
                float oldOffset = worldScrollOffset;
                // Forge scroll: positive delta = wheel up; we want content to move up
                float step = 12f;
                worldScrollOffset = net.minecraft.util.Mth.clamp(
                    worldScrollOffset - (float)delta * step,
                    0.0f,
                    worldMaxScroll
                );
                
                if (worldScrollOffset != oldOffset) {
                    rebuildWorldLayerWidgets();
                }
                return true;
            }
        } else if ("preview".equals(activeTab)) {
            // Preview tab does not consume scroll - let Minecraft handle it normally
            return false;
        }
        
        return false; // No panel scrolling handled, event not consumed
    }
    
    private void selectDefinition(ResourceLocation id) {
        if (editorState.dirty) {
            // TODO: Show "unsaved changes" dialog - for now just warn in chat
            CosmeticsLite.LOGGER.warn("[ParticleLab] Discarding unsaved changes");
        }
        // Get from editor's in-memory collection (includes unsaved new definitions)
        ParticleDefinition def = editorDefinitions.get(id);
        if (def != null) {
            // Enforce 1:1 mapping when selecting a definition
            def = ensureLayerCountMatchesWorldLayers(def);
            editorState.selectDefinition(id, def);
            // Reset scroll offsets when switching definitions
            layersScrollOffset = 0.0f;
            worldScrollOffset = 0.0f;
            worldMaxScroll = 0.0f;
            refreshEditorUI();
            updatePreviewButtonLabel();
        }
    }
    
    /**
     * Refresh editor UI from working copy.
     * IMPORTANT: Does NOT reload from registry - only rebuilds widgets from editorState.workingCopy.
     * This ensures in-memory edits are preserved.
     */
    private void refreshEditorUI() {
        // Rebuild editor widgets based on current workingCopy (in-memory only, no registry reload)
        // This will be called when switching definitions or reverting
        rebuildTabWidgets();
    }
    
    @Override
    public boolean isPauseScreen() {
        return false; // Don't pause the game - world keeps running, F5 works normally
    }
    
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 1) Let ESC close the screen as usual
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        
        // 2) Do NOT consume F5 (or other global function keys like F1/F3)
        // Let them pass through to Minecraft's normal keybinding system
        if (keyCode == GLFW.GLFW_KEY_F5 || keyCode == GLFW.GLFW_KEY_F1 || keyCode == GLFW.GLFW_KEY_F3) {
            // Hand straight to super, which forwards to normal keybindings
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        
        // 3) Let text fields / search fields handle their own input
        boolean handled = false;
        
        if (this.searchBox != null) {
            if (this.searchBox.keyPressed(keyCode, scanCode, modifiers)) {
                handled = true;
            } else if (this.searchBox.canConsumeInput()) {
                // While typing in the search bar, we generally want to swallow keys
                handled = true;
            }
        }
        
        // Check if any EditBox widgets are focused and can consume input
        // (This covers all the text fields in General, Layers, World tabs)
        if (!handled && this.getFocused() instanceof EditBox editBox) {
            if (editBox.keyPressed(keyCode, scanCode, modifiers)) {
                handled = true;
            } else if (editBox.canConsumeInput()) {
                handled = true;
            }
        }
        
        if (handled) {
            return true;
        }
        
        // 4) For everything else, defer to super so Minecraft can decide
        // This allows normal keybindings to work (including F5 camera switching)
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    
    @Override
    public void onClose() {
        if (editorState.dirty) {
            // TODO: Warn about unsaved changes with proper dialog
            CosmeticsLite.LOGGER.warn("[ParticleLab] Closing with unsaved changes");
        }
        
        // Task D1: Stop preview when closing Particle Lab
        if (ParticlePreviewState.isActive()) {
            CosmeticsLite.LOGGER.info("[ParticleLab] Stopping preview on screen close");
            com.pastlands.cosmeticslite.network.ParticlePreviewPacket stopPacket = 
                new com.pastlands.cosmeticslite.network.ParticlePreviewPacket(false, null);
            CosmeticsLite.NETWORK.sendToServer(stopPacket);
            if (editorState.selectedId != null) {
                ParticlePreviewState.setPreviewOverride(editorState.selectedId, null);
            }
            ParticlePreviewState.stopPreview();
        }
        
        // Restore camera if we changed it for Preview tab
        if (this.previousCameraType != null) {
            this.minecraft.options.setCameraType(this.previousCameraType);
            this.previousCameraType = null;
        }
        
        super.onClose();
    }
}

