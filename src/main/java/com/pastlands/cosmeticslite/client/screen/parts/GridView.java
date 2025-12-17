package com.pastlands.cosmeticslite.client.screen.parts;

import com.pastlands.cosmeticslite.CosmeticDef;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * GridView
 * Standalone grid component for the Cosmetics screen.
 *
 * Responsibilities:
 *  - Grid geometry & paging (cols/rows/slot size)
 *  - Frame/background rendering
 *  - Slot hit-testing (mouse clicks -> selection or placeholder)
 *  - Selection + equipped highlights
 *  - Delegates actual slot content drawing via SlotRenderer callback
 *
 * This file is self-contained and unused until wired into CosmeticsChestScreen.
 * No behavior changes to your current screen until we replace that code path.
 *
 * Forge 47.4.0 • MC 1.20.1 • Java 17
 */
public final class GridView {

    // ------------------------- Geometry -------------------------
    private int gridLeft;         // top-left corner of the grid frame (outer)
    private int gridTop;
    private int gridWidth;        // computed from cols/rows/slotSize
    private int gridHeight;

    private int cols = 5;
    private int rows = 4;
    private int slotSize = 28;    // full cell size (including padding)
    private int slotPadX = 5;     // inner padding (content origin)
    private int slotPadY = 5;

    // "Visible plate" inside each slot (for highlight alignment)
    private int visW = 16;
    private int visH = 16;
    private int visOX = 1;
    private int visOY = 1;

    // Round frame colors (defaults match your existing palette)
    private int colRim2   = 0xFF3A4149;
    private int colRim1   = 0xFF59616A;
    private int colPlate  = 0xFF4C4C4D;

    // Highlight colors (defaults match your existing palette)
    private int colEquippedInner = 0x801B5FC2; // cyan inner (shared as your "select inner")
    private int colEquippedOuter = 0xFF4BA3FF; // cyan border
    private int colPendingInner  = 0x66E0BD6A; // amber inner
    private int colPendingOuter  = 0xFFE0BD6A; // amber border
    private int highlightBorderT = 1;

    // ------------------------- Data & State -------------------------
    private final List<CosmeticDef> data = new ArrayList<>();
    private int perPage = cols * rows;
    private int totalPages = 1;
    private int currentPage = 0;
    private int pageStartIndex = 0;

    private int selectedGlobalIndex = -1;     // set by caller when selection changes
    private int placeholderLocalIndex = -1;   // for empty cell selection
    private int placeholderPageIndex = -1;

    private ResourceLocation equippedId = null; // to draw "equipped" highlight

    // ------------------------- Callbacks -------------------------
    private SlotRenderer slotRenderer = SlotRenderer.NOOP;
    private Consumer<Integer> onSelectGlobalIndex = i -> {};
    private BiConsumer<Integer, Integer> onSelectPlaceholder = (local, page) -> {};

    public GridView() {}

    // ========================================================================
    // Configuration
    // ========================================================================

    /** Sets the outer frame position and recomputes derived sizes. */
    public void setFrame(int left, int top) {
        this.gridLeft = left;
        this.gridTop = top;
        recomputeSize();
    }

    /** Sets the grid shape and slot geometry. */
    public void setGridGeometry(int cols, int rows, int slotSize, int padX, int padY) {
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
        this.slotSize = Math.max(8, slotSize);
        this.slotPadX = Math.max(0, padX);
        this.slotPadY = Math.max(0, padY);
        this.perPage = this.cols * this.rows;
        clampPaging();
        recomputeSize();
    }

    /** Sets the visible plate size and offset within each slot (for highlight alignment). */
    public void setVisiblePlate(int w, int h, int offX, int offY) {
        this.visW = Math.max(1, w);
        this.visH = Math.max(1, h);
        this.visOX = offX;
        this.visOY = offY;
    }

    /** Sets frame colors. */
    public void setFrameColors(int rim2, int rim1, int plate) {
        this.colRim2 = rim2;
        this.colRim1 = rim1;
        this.colPlate = plate;
    }

    /** Sets highlight colors & thickness. */
    public void setHighlightColors(int equippedInner, int equippedOuter, int pendingInner, int pendingOuter, int borderThickness) {
        this.colEquippedInner = equippedInner;
        this.colEquippedOuter = equippedOuter;
        this.colPendingInner  = pendingInner;
        this.colPendingOuter  = pendingOuter;
        this.highlightBorderT = Math.max(1, borderThickness);
    }

    /** Injects your slot renderer (e.g., use CosmeticGuiSlot under the hood). */
    public void setSlotRenderer(SlotRenderer renderer) {
        this.slotRenderer = (renderer != null) ? renderer : SlotRenderer.NOOP;
    }

    /** Click → select real item (global index). */
    public void setOnSelect(Consumer<Integer> onSelectGlobalIndex) {
        this.onSelectGlobalIndex = (onSelectGlobalIndex != null) ? onSelectGlobalIndex : i -> {};
    }

    /** Click → placeholder for empty cell (local index, page index). */
    public void setOnPlaceholder(BiConsumer<Integer, Integer> onSelectPlaceholder) {
        this.onSelectPlaceholder = (onSelectPlaceholder != null) ? onSelectPlaceholder : (l, p) -> {};
    }

    // ========================================================================
    // Data & Paging
    // ========================================================================

    /** Replace all data for the active type. */
    public void setData(List<CosmeticDef> items) {
        this.data.clear();
        if (items != null) this.data.addAll(items);
        int count = this.data.size();
        this.totalPages = Math.max(1, (int)Math.ceil(count / (double)perPage));
        clampPaging();
        recomputePageStart();
    }

    /** Update currently equipped id for highlight. */
    public void setEquippedId(ResourceLocation id) {
        this.equippedId = id;
    }

    public void setSelectedGlobalIndex(int globalIndex) {
        this.selectedGlobalIndex = globalIndex;
        // clear placeholders if a real selection is set
        if (globalIndex >= 0) {
            this.placeholderLocalIndex = -1;
            this.placeholderPageIndex = -1;
        }
    }

    public void clearSelection() {
        this.selectedGlobalIndex = -1;
        this.placeholderLocalIndex = -1;
        this.placeholderPageIndex = -1;
    }

    public void setPlaceholder(int localIndex, int pageIndex) {
        this.placeholderLocalIndex = localIndex;
        this.placeholderPageIndex = pageIndex;
        this.selectedGlobalIndex = -1;
    }

    public void setCurrentPage(int page) {
        this.currentPage = page;
        clampPaging();
        recomputePageStart();
    }

    public int getCurrentPage()   { return currentPage; }
    public int getTotalPages()    { return totalPages; }
    public int getPerPage()       { return perPage; }
    public int getPageStartIndex(){ return pageStartIndex; }
    public int getGridLeft()      { return gridLeft; }
    public int getGridTop()       { return gridTop; }
    public int getGridWidth()     { return gridWidth; }
    public int getGridHeight()    { return gridHeight; }

    // ========================================================================
    // Input
    // ========================================================================

    /** Returns true if the click was consumed by the grid. */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        if (!isInside(mouseX, mouseY)) return false;

        int col = (int)((mouseX - gridLeft) / slotSize);
        int row = (int)((mouseY - gridTop)  / slotSize);
        int localIndex  = row * cols + col;
        if (localIndex < 0 || localIndex >= perPage) return true; // inside frame but outside slots

        int globalIndex = pageStartIndex + localIndex;
        if (globalIndex < data.size()) {
            setSelectedGlobalIndex(globalIndex);
            onSelectGlobalIndex.accept(globalIndex);
        } else {
            setPlaceholder(localIndex, currentPage);
            onSelectPlaceholder.accept(localIndex, currentPage);
        }
        return true;
    }

    // ========================================================================
    // Render
    // ========================================================================

    /** Draw the rounded frame and all page slots using the provided SlotRenderer. */
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        // Frame (rounded layers like your current look)
        fillRounded(g, gridLeft - 5, gridTop - 5, gridLeft + gridWidth + 5, gridTop + gridHeight + 5, 6, colRim2);
        fillRounded(g, gridLeft - 4, gridTop - 4, gridLeft + gridWidth + 4, gridTop + gridHeight + 4, 6, colRim1);
        fillRounded(g, gridLeft - 3, gridTop - 3, gridLeft + gridWidth + 3, gridTop + gridHeight + 3, 5, colPlate);

        // Render each slot via callback
        int endIndex = Math.min(pageStartIndex + perPage, data.size());
        int col = 0, row = 0;
        for (int i = pageStartIndex; i < endIndex; i++) {
            CosmeticDef def = data.get(i);
            int cellX = gridLeft + col * slotSize;
            int cellY = gridTop  + row * slotSize;
            int slotX = cellX + slotPadX;
            int slotY = cellY + slotPadY;
            int visX  = slotX + visOX;
            int visY  = slotY + visOY;

            boolean isEquipped = isEquipped(def);
            boolean isSelected = (i == selectedGlobalIndex);

            slotRenderer.renderSlot(g, slotX, slotY, visX, visY, i, def, isEquipped, isSelected);

            col++;
            if (col >= cols) { col = 0; row++; }
        }

        // Fill remaining empty cells (still invoke renderer with def = null)
        for (int i = endIndex - pageStartIndex; i < perPage; i++) {
            int cellX = gridLeft + col * slotSize;
            int cellY = gridTop  + row * slotSize;
            int slotX = cellX + slotPadX;
            int slotY = cellY + slotPadY;
            int visX  = slotX + visOX;
            int visY  = slotY + visOY;

            slotRenderer.renderSlot(g, slotX, slotY, visX, visY, pageStartIndex + row * cols + col, null, false, false);

            col++;
            if (col >= cols) { col = 0; row++; }
        }

        // Highlights (equipped + selected) drawn on top to match your current UX
        drawEquippedHighlight(g);
        drawSelectedHighlight(g);
    }

    private void drawEquippedHighlight(GuiGraphics g) {
        if (equippedId == null) return;

        // Find equipped global index in current data
        int equippedGlobalIndex = -1;
        for (int i = 0; i < data.size(); i++) {
            if (Objects.equals(equippedId, data.get(i).id())) {
                equippedGlobalIndex = i;
                break;
            }
        }
        if (equippedGlobalIndex < pageStartIndex || equippedGlobalIndex >= pageStartIndex + perPage) return;

        int local = equippedGlobalIndex - pageStartIndex;
        int col = local % cols;
        int row = local / cols;
        int cellX = gridLeft + col * slotSize;
        int cellY = gridTop  + row * slotSize;
        int visX  = cellX + slotPadX + visOX;
        int visY  = cellY + slotPadY + visOY;

        // Use border only, no fill (keeps icon visible)
        drawRectBorder(g, visX - highlightBorderT, visY - highlightBorderT,
                visW + 2 * highlightBorderT, visH + 2 * highlightBorderT,
                highlightBorderT, colEquippedOuter);
    }

    private void drawSelectedHighlight(GuiGraphics g) {
        if (selectedGlobalIndex < pageStartIndex || selectedGlobalIndex >= pageStartIndex + perPage) return;

        // If selection is the same as equipped, skip the amber overlay (matches your current behavior).
        ResourceLocation selId = data.get(selectedGlobalIndex).id();
        if (equippedId != null && Objects.equals(equippedId, selId)) return;

        int local = selectedGlobalIndex - pageStartIndex;
        int col = local % cols;
        int row = local / cols;
        int cellX = gridLeft + col * slotSize;
        int cellY = gridTop  + row * slotSize;
        int visX  = cellX + slotPadX + visOX;
        int visY  = cellY + slotPadY + visOY;

        // Use border only, no fill (keeps icon visible)
        drawRectBorder(g, visX - highlightBorderT, visY - highlightBorderT,
                visW + 2 * highlightBorderT, visH + 2 * highlightBorderT,
                highlightBorderT, colPendingOuter);
    }

    // ========================================================================
    // Internals
    // ========================================================================

    private void recomputeSize() {
        gridWidth  = cols * slotSize;
        gridHeight = rows * slotSize;
    }

    private void recomputePageStart() {
        pageStartIndex = currentPage * perPage;
    }

    private void clampPaging() {
        if (totalPages < 1) totalPages = 1;
        if (currentPage < 0) currentPage = 0;
        if (currentPage > totalPages - 1) currentPage = totalPages - 1;
    }

    private boolean isInside(double x, double y) {
        return x >= gridLeft && x < gridLeft + gridWidth && y >= gridTop && y < gridTop + gridHeight;
    }

    private boolean isEquipped(CosmeticDef def) {
        if (def == null || equippedId == null) return false;
        return Objects.equals(equippedId, def.id());
    }

    // --------------------------- Drawing helpers -----------------------------

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

    private static void drawRectBorder(GuiGraphics g, int x, int y, int w, int h, int t, int color) {
        g.fill(x, y, x + w, y + t, color);                 // top
        g.fill(x, y + h - t, x + w, y + h, color);         // bottom
        g.fill(x, y + t, x + t, y + h - t, color);         // left
        g.fill(x + w - t, y + t, x + w, y + h - t, color); // right
    }

    // ----------------------------- Callback API -----------------------------

    @FunctionalInterface
    public interface SlotRenderer {
        /**
         * Draws a slot's content at the specified coordinates.
         *
         * @param g        GuiGraphics
         * @param slotX    slot content origin X (after padding)
         * @param slotY    slot content origin Y (after padding)
         * @param visX     "visible plate" X for highlight alignment
         * @param visY     "visible plate" Y for highlight alignment
         * @param globalIndex global index into the full list for the active type
         * @param def      cosmetic definition at this index (null for empty cell)
         * @param isEquipped  true if this slot is the equipped cosmetic
         * @param isSelected  true if this slot is currently selected
         */
        void renderSlot(GuiGraphics g, int slotX, int slotY, int visX, int visY,
                        int globalIndex, CosmeticDef def, boolean isEquipped, boolean isSelected);

        SlotRenderer NOOP = (g, x, y, vx, vy, idx, def, eq, sel) -> {};
    }
}
