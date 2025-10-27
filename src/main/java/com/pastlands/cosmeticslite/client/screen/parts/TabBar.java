package com.pastlands.cosmeticslite.client.screen.parts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * TabBar
 * Rounded tab strip with three or more tabs, click-to-activate, and an onChange callback.
 *
 * Responsibilities:
 *  - Draw the strip background (rounded)
 *  - Draw each tab (rounded), with subtle glow behind the active one
 *  - Handle click hit-testing & active tab updates
 *  - Report changes via onChange callback with the tab's key
 *
 * Not wired to the main screen yet; adding this file does not change behavior.
 *
 * Forge 47.4.0 • MC 1.20.1 • Java 17
 */
public final class TabBar {

    // --------------------- Geometry ---------------------
    private int stripL, stripT, stripR, stripB;
    private int tabW = 72;
    private int tabH = 20;
    private int tabGap = 4;

    // --------------------- Style (matches your palette) ---------------------
    private int colStripOuter = 0xFF474E56;
    private int colStripInner = 0xFFB1B8BF;
    private int colTabBase    = 0xFF3C424A;
    private int colTabInner   = 0xFF555B62;
    private int colActiveGlow = 0x401B5FC2;
    private int colText       = 0xFF101317;

    // --------------------- Data & State ---------------------
    public static final class Tab {
        public final String key;
        public final String label;
        public Tab(String key, String label) { this.key = key; this.label = label; }
    }

    private final List<Tab> tabs = new ArrayList<>();
    private String activeKey = null;

    private Consumer<String> onChange = k -> {};

    public TabBar() {}

    // ======================================================================
    // Configuration
    // ======================================================================

    /** Set the strip rectangle (behind tabs). */
    public void setStripBounds(int left, int top, int right, int bottom) {
        this.stripL = left;
        this.stripT = top;
        this.stripR = right;
        this.stripB = bottom;
    }

    /** Set per-tab size and spacing. */
    public void setTabGeometry(int width, int height, int gap) {
        this.tabW = Math.max(24, width);
        this.tabH = Math.max(12, height);
        this.tabGap = Math.max(0, gap);
    }

    /** Override colors if you want. */
    public void setColors(int stripOuter, int stripInner, int tabBase, int tabInner, int activeGlow, int text) {
        this.colStripOuter = stripOuter;
        this.colStripInner = stripInner;
        this.colTabBase    = tabBase;
        this.colTabInner   = tabInner;
        this.colActiveGlow = activeGlow;
        this.colText       = text;
    }

    /** Replace the tabs (keys must be unique). Keeps activeKey if present; otherwise selects first. */
    public void setTabs(List<Tab> newTabs) {
        this.tabs.clear();
        if (newTabs != null) this.tabs.addAll(newTabs);
        if (tabs.isEmpty()) {
            activeKey = null;
        } else if (!containsKey(activeKey)) {
            activeKey = tabs.get(0).key;
        }
    }

    /** Set the active tab by key (no callback). */
    public void setActiveKey(String key) {
        if (containsKey(key)) activeKey = key;
    }

    /** Get the active tab key (may be null if no tabs). */
    public String getActiveKey() { return activeKey; }

    /** Set a callback to be invoked when the active tab changes via click. */
    public void setOnChange(Consumer<String> onChange) {
        this.onChange = (onChange != null) ? onChange : k -> {};
    }

    // ======================================================================
    // Layout helpers
    // ======================================================================

    /** Pixel width needed to render all tabs including gaps. */
    public int getTabsTotalWidth() {
        if (tabs.isEmpty()) return 0;
        return tabs.size() * tabW + (tabs.size() - 1) * tabGap;
    }

    /** Left origin that would center the tabs within the given full width. */
    public int getCenteredTabsLeft(int fullWidth) {
        int total = getTabsTotalWidth();
        int available = Math.max(0, fullWidth);
        return (available - total) / 2;
    }

    // ======================================================================
    // Input
    // ======================================================================

    /** Returns true if the click hit a tab and changed (or reselected) the active key. */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        if (tabs.isEmpty()) return false;

        // Tabs are horizontally centered within the strip rect.
        int tabsLeft = stripL + getCenteredTabsLeft(stripR - stripL);
        int x = (int)mouseX;
        int y = (int)mouseY;

        // Quick reject: outside vertical or outside whole tabs row
        if (y < stripT || y > stripT + 28) return false; // generous vertical hit range

        for (int i = 0; i < tabs.size(); i++) {
            int tabL = tabsLeft + i * (tabW + tabGap);
            int tabT = stripT + 2;
            int tabR = tabL + tabW;
            int tabB = tabT + tabH;

            if (x >= tabL && x <= tabR && y >= tabT && y <= tabB) {
                String newKey = tabs.get(i).key;
                if (!Objects.equals(newKey, activeKey)) {
                    activeKey = newKey;
                    onChange.accept(newKey);
                } else {
                    // Reselecting same tab is still consumed
                    onChange.accept(newKey);
                }
                return true;
            }
        }
        return false;
    }

    // ======================================================================
    // Render
    // ======================================================================

    public void render(GuiGraphics g) {
        // Strip background (rounded)
        fillRounded(g, stripL, stripT, stripR, stripB, 6, colStripOuter);
        fillRounded(g, stripL + 1, stripT + 1, stripR - 1, stripB - 1, 5, colStripInner);

        if (tabs.isEmpty()) return;

        // Layout
        int tabsLeft = stripL + getCenteredTabsLeft(stripR - stripL);
        int baseT = stripT + 2;

        // Subtle glow behind the active tab
        int activeIndex = indexOf(activeKey);
        if (activeIndex >= 0) {
            int glowL = tabsLeft + activeIndex * (tabW + tabGap) - 2;
            int glowT = baseT - 2;
            int glowR = glowL + tabW + 4;
            int glowB = glowT + tabH + 4;
            g.fill(glowL, glowT, glowR, glowB, colActiveGlow);
        }

        // Draw each tab (rounded button + label)
        Font font = Minecraft.getInstance().font;
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            int tabL = tabsLeft + i * (tabW + tabGap);
            int tabT = baseT;
            int tabR = tabL + tabW;
            int tabB = tabT + tabH;

            // Button layers
            fillRounded(g, tabL, tabT, tabR, tabB, 6, colTabBase);
            fillRounded(g, tabL + 1, tabT + 1, tabR - 1, tabB - 1, 5, colTabInner);

            // Centered label
            String label = tab.label;
            int w = font.width(label);
            int tx = tabL + (tabW - w) / 2;
            int ty = tabT + (tabH - 8) / 2; // ~8px font height baseline
            g.drawString(font, Component.literal(label), tx, ty, colText, false);
        }
    }

    // ======================================================================
    // Internals
    // ======================================================================

    private boolean containsKey(String key) {
        for (Tab t : tabs) if (Objects.equals(t.key, key)) return true;
        return false;
    }

    private int indexOf(String key) {
        for (int i = 0; i < tabs.size(); i++) if (Objects.equals(tabs.get(i).key, key)) return i;
        return -1;
    }

    // Shared rounded rect helper (same math as elsewhere)
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
}
