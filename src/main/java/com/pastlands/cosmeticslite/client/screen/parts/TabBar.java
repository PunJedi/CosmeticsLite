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
 * Visual tweaks in this edition:
 *  - More top padding so tabs don't hug the strip edge
 *  - Taller tabs (24px) and wider gaps (6px)
 *  - Soft drop shadow + inner-face bevel for a "real button" look
 *  - Active glow kept inside the tab bounds
 *  - Very subtle glint sweep on the active tab (low alpha, time-based)
 *
 * Forge 47.4.0 • MC 1.20.1 • Java 17
 */
public final class TabBar {

    // --------------------- Geometry ---------------------
    private int stripL, stripT, stripR, stripB;

    // Default geometry tuned for better feel; can still be overridden via setters.
    private int tabW   = 72;
    private int tabH   = 24; // was 20
    private int tabGap = 6;  // was 4

    // How far tabs sit down from the top of the strip (extra breathing room)
    private int tabTopInset = 3; // was effectively 6

    // --------------------- Style (palette) ---------------------
    // Strip
    private int colStripOuter = 0xFF474E56;
    private int colStripInner = 0xFFB1B8BF;

    // Tab base + inner-face
    private int colTabBase    = 0xFF3C424A;
    private int colTabInner   = 0xFF555B62;

    // Accents
    private int colActiveGlow = 0x401B5FC2; // background glow under active tab (kept subtle)
    private int colText       = 0xFF101317;

    // Shadow/highlight tones (semi-transparent overlays)
    private int colShadowSoft   = 0x2A000000; // soft drop shadow under tab
    private int colShadowDeep   = 0x1A000000; // deeper offset
    private int colBevelTopHi   = 0x18FFFFFF; // faint top highlight for bevel
    private int colBevelBottom  = 0x14000000; // faint bottom shade

    // Glint sweep across the active tab (very subtle)
    private int colGlint        = 0x12FFFFFF;

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

    /** Optional extra vertical inset for the tab row inside the strip. */
    public void setTabTopInset(int insetPx) {
        this.tabTopInset = Math.max(0, insetPx);
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

        // Updated vertical acceptance to match taller visuals and inset.
        int baseT = stripT + tabTopInset;
        int totalHitBottom = baseT + tabH + 8; // generous extra for comfy clicks
        if (y < stripT || y > totalHitBottom) return false;

        for (int i = 0; i < tabs.size(); i++) {
            int tabL = tabsLeft + i * (tabW + tabGap);
            int tabT = baseT;
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
        // Strip background (rounded, with a faint inner shadow for inset feel)
        fillRounded(g, stripL, stripT, stripR, stripB, 6, colStripOuter);
        fillRounded(g, stripL + 1, stripT + 1, stripR - 1, stripB - 1, 5, colStripInner);
        // very faint inner darkening to make tabs pop
        fillRounded(g, stripL + 2, stripT + 2, stripR - 2, stripB - 2, 5, 0x0E000000);

        if (tabs.isEmpty()) return;

        // Layout
        int tabsLeft = stripL + getCenteredTabsLeft(stripR - stripL);
        int baseT = stripT + tabTopInset;

        // Active index
        int activeIndex = indexOf(activeKey);

        // Draw each tab (shadow → base → inner face → bevels → text)
        Font font = Minecraft.getInstance().font;
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            int tabL = tabsLeft + i * (tabW + tabGap);
            int tabT = baseT;
            int tabR = tabL + tabW;
            int tabB = tabT + tabH;

            // Soft drop shadow (two passes, slightly offset)
            fillRounded(g, tabL + 1, tabT + 2, tabR + 1, tabB + 2, 6, colShadowSoft);
            fillRounded(g, tabL + 2, tabT + 3, tabR + 2, tabB + 3, 6, colShadowDeep);

            // Button base + inner face
            fillRounded(g, tabL, tabT, tabR, tabB, 6, colTabBase);
            fillRounded(g, tabL + 1, tabT + 1, tabR - 1, tabB - 1, 5, colTabInner);

            // Bevel: faint top highlight + faint bottom shade
            g.fill(tabL + 2, tabT + 1, tabR - 2, tabT + 2, colBevelTopHi);
            g.fill(tabL + 2, tabB - 2, tabR - 2, tabB - 1, colBevelBottom);

            // Active treatment (glow inside bounds + glint sweep)
            if (i == activeIndex) {
                // Inner glow (kept inside the tab, 2px inset)
                fillRounded(g, tabL + 2, tabT + 2, tabR - 2, tabB - 2, 5, colActiveGlow);

                // Very subtle diagonal glint moving across the tab
                int ticks = getUiTicks();
                int span = Math.max(12, tabW / 3);       // width of the glint band
                int offset = (ticks % (tabW + span));     // sweep across
                int glintL = tabL - span + offset;
                int glintR = glintL + span;

                // Clamp within tab face area
                int gL = Math.max(glintL, tabL + 2);
                int gR = Math.min(glintR, tabR - 2);
                if (gR > gL) {
                    // Simple vertical band; "diagonal" illusion comes from bevels + band motion
                    fillRounded(g, gL, tabT + 2, gR, tabB - 2, 4, colGlint);
                }
            }

            // Centered label
            String label = tab.label;
            int w = font.width(label);
            int tx = tabL + (tabW - w) / 2;
            int ty = tabT + (tabH - 8) / 2 + 1; // +1 for nicer optical centering with taller tab
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

    /** UI ticks for light animation; falls back to system time if player is null. */
    private static int getUiTicks() {
        var mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) return mc.player.tickCount;
        long ms = System.currentTimeMillis();
        return (int)(ms / 50L);
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
