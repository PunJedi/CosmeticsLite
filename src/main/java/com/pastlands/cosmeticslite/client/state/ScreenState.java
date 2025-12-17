package com.pastlands.cosmeticslite.client.state;

import java.util.HashMap;
import java.util.Map;

/**
 * ScreenState
 * Pure UI state holder for the Cosmetics screen.
 * No Minecraft/Forge types here; just state + tiny helpers.
 *
 * Tabs are identified by type keys: "particles", "hats", "capes", "pets".
 * This lets us migrate away from static maps and giant files safely.
 *
 * Forge 47.4.0 • MC 1.20.1 • Java 17
 */
public final class ScreenState {

    // ---- Type keys (stable across UI + client cache) ----
    public static final String TYPE_PARTICLES = "particles";
    public static final String TYPE_HATS      = "hats";
    public static final String TYPE_CAPES     = "capes";
    public static final String TYPE_PETS      = "pets";      // NEW: pets type

    // ---- Active tab ----
    private String activeType = TYPE_PARTICLES;

    // ---- Paging ----
    private int currentPage = 0;
    private int totalPages  = 1;
    private int perPage     = 20; // default; caller sets based on grid size
    private int pageStartIndex = 0;

    // ---- Selection & placeholders (per type) ----
    // selectedIndex: global index into the full list for that type (or -1 if none)
    private final Map<String, Integer> selectedIndex    = new HashMap<>();
    // placeholderLocal: local cell index within the current page (for empty cells); -1 if none
    private final Map<String, Integer> placeholderLocal = new HashMap<>();
    // placeholderPage: page index where the placeholderLocal applies; -1 if none
    private final Map<String, Integer> placeholderPage  = new HashMap<>();

    // ---- Preview drag state ----
    public boolean draggingPreview = false;
    public double  lastDragX = 0.0;
    public float   dragAccumX = -30.0f;      // nice starting angle
    public static final float DRAG_SENS = 1.8f;

    // ---- Status flash ----
    private String statusMsg = null;
    private int statusTicks = 0;

    public ScreenState() {
        initType(TYPE_PARTICLES);
        initType(TYPE_HATS);
        initType(TYPE_CAPES);
        initType(TYPE_PETS);      // NEW: initialize pets type
    }

    private void initType(String type) {
        selectedIndex.put(type, -1);
        placeholderLocal.put(type, -1);
        placeholderPage.put(type, -1);
    }

    // -------------------- Active tab --------------------
    public String getActiveType() {
        return activeType;
    }

    public void setActiveType(String typeKey) {
        if (typeKey == null) return;
        activeType = typeKey;
        // Reset paging when switching tabs; caller can override.
        currentPage = 0;
        recomputePageStart();
    }

    // -------------------- Paging --------------------
    public int getCurrentPage() {
        return currentPage;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public int getPerPage() {
        return perPage;
    }

    public int getPageStartIndex() {
        return pageStartIndex;
    }

    public void setPerPage(int perPage) {
        if (perPage <= 0) perPage = 1;
        this.perPage = perPage;
        recomputePageStart();
    }

    public void setTotalPages(int totalPages) {
        this.totalPages = Math.max(1, totalPages);
        clampPage();
        recomputePageStart();
    }

    public void setCurrentPage(int page) {
        currentPage = page;
        clampPage();
        recomputePageStart();
    }

    private void clampPage() {
        if (currentPage < 0) currentPage = 0;
        if (currentPage > totalPages - 1) currentPage = Math.max(0, totalPages - 1);
    }

    private void recomputePageStart() {
        pageStartIndex = currentPage * perPage;
    }

    // -------------------- Selection --------------------
    public int getSelectedIndex(String typeKey) {
        return selectedIndex.getOrDefault(typeKey, -1);
    }

    public void setSelectedIndex(String typeKey, int globalIndex) {
        selectedIndex.put(typeKey, globalIndex);
    }

    public void clearSelection(String typeKey) {
        selectedIndex.put(typeKey, -1);
    }

    // -------------------- Placeholders --------------------
    public int getPlaceholderLocal(String typeKey) {
        return placeholderLocal.getOrDefault(typeKey, -1);
    }

    public int getPlaceholderPage(String typeKey) {
        return placeholderPage.getOrDefault(typeKey, -1);
    }

    public void setPlaceholder(String typeKey, int localIndex, int pageIndex) {
        placeholderLocal.put(typeKey, localIndex);
        placeholderPage.put(typeKey, pageIndex);
    }

    public void clearPlaceholder(String typeKey) {
        placeholderLocal.put(typeKey, -1);
        placeholderPage.put(typeKey, -1);
    }

    public void clearAllHighlights() {
        clearSelection(TYPE_PARTICLES);
        clearSelection(TYPE_HATS);
        clearSelection(TYPE_CAPES);
        clearSelection(TYPE_PETS);
    }

    // -------------------- Preview drag --------------------
    public boolean isDraggingPreview() {
        return draggingPreview;
    }

    public void setDraggingPreview(boolean dragging) {
        this.draggingPreview = dragging;
    }

    public double getLastDragX() {
        return lastDragX;
    }

    public void setLastDragX(double x) {
        this.lastDragX = x;
    }

    public float getDragAccumX() {
        return dragAccumX;
    }

    public void setDragAccumX(float accum) {
        this.dragAccumX = accum;
    }

    // -------------------- Status flash --------------------
    public String getStatusMsg() {
        return statusMsg;
    }

    public int getStatusTicks() {
        return statusTicks;
    }

    public void setStatus(String msg, int ticks) {
        this.statusMsg = msg;
        this.statusTicks = ticks;
    }

    public void tickStatus() {
        if (statusTicks > 0) {
            statusTicks--;
            if (statusTicks <= 0) {
                statusMsg = null;
            }
        }
    }

    public void clearStatus() {
        statusMsg = null;
        statusTicks = 0;
    }
}

