package com.pastlands.cosmeticslite.gui.slot;

import com.pastlands.cosmeticslite.CosmeticDef;

/**
 * Simple value object used by the UI grid. Holds the cosmetic to display
 * (or null for an empty/placeholder cell) and the top-left position of the
 * inner visual slot area.
 */
public class CosmeticSlot {

    private final CosmeticDef def; // null = placeholder cell
    private final int x;
    private final int y;

    public CosmeticSlot(CosmeticDef def, int x, int y) {
        this.def = def;
        this.x = x;
        this.y = y;
    }

    /** Returns the cosmetic for this cell, or null if it's a placeholder. */
    public CosmeticDef getDef() {
        return def;
    }

    /** X coordinate of the inner visual slot (matches where the item/icon is drawn). */
    public int getX() {
        return x;
    }

    /** Y coordinate of the inner visual slot (matches where the item/icon is drawn). */
    public int getY() {
        return y;
    }
}
