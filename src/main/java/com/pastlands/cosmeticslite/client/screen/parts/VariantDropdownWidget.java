// src/main/java/com/pastlands/cosmeticslite/client/screen/parts/VariantDropdownWidget.java
package com.pastlands.cosmeticslite.client.screen.parts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Dropdown widget for selecting pet variants.
 * Fully opaque, eats clicks/hover inside its region, and renders above all other widgets.
 * Forge 47.4.0 / MC 1.20.1
 */
public class VariantDropdownWidget extends AbstractWidget {

    /** Represents a single option in the dropdown. */
    public static class VariantOption {
        public final String key;
        public final Component label;
        public VariantOption(String key, Component label) { this.key = key; this.label = label; }
        @Override public String toString() { return label.getString(); }
    }

    private List<VariantOption> options = new ArrayList<>();
    private int selected = 0;
    private boolean expanded = false;
    private boolean active = true;

    private final Consumer<VariantOption> onSelect;

    public VariantDropdownWidget(int x, int y, int width, int height,
                                 List<VariantOption> initialOptions,
                                 Consumer<VariantOption> onSelect) {
        super(x, y, width, height, Component.literal("Variant"));
        this.options = new ArrayList<>(initialOptions);
        this.onSelect = onSelect;
    }

    public void setOptions(List<VariantOption> newOptions) {
        this.options = new ArrayList<>(newOptions);
        this.selected = 0;
        this.expanded = false;
    }

    public void setActive(boolean active) { this.active = active; }

    public VariantOption getSelected() {
        if (options.isEmpty()) return null;
        return options.get(Math.min(selected, options.size() - 1));
    }

    public boolean isExpanded() { return expanded; }

    private int getExpandedHeight() { return options.size() * this.height; }

    /** Checks if a point lies within the expanded dropdown rectangle. */
    private boolean isOverExpanded(double mouseX, double mouseY) {
        if (!expanded) return false;
        int dropX = getX();
        int dropY = getY() + height;
        int dropW = width;
        int dropH = getExpandedHeight();
        return mouseX >= dropX && mouseX <= dropX + dropW &&
               mouseY >= dropY && mouseY <= dropY + dropH;
    }

    // --------------------------------------------------------
    // Rendering
    // --------------------------------------------------------

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float pt) {
        // Render collapsed box
        var font = Minecraft.getInstance().font;
        int bg = this.active ? 0xFF3C3C3C : 0xFF555555;
        fillRounded(g, getX(), getY(), getX() + width, getY() + height, 4, bg);

        String text = options.isEmpty() ? "—" : getSelected().toString();
        int color  = options.isEmpty() ? 0xFFAAAAAA : 0xFFFFFFFF;
        g.drawString(font, text, getX() + 4, getY() + (height - 8) / 2, color, false);

        // Dropdown arrow
        g.drawString(font, expanded ? "▲" : "▼",
                getX() + width - 10, getY() + (height - 8) / 2, 0xFFFFFFFF, false);
    }

    /**
     * Render the expanded dropdown *after* all other widgets (call from parent screen).
     */
    public void renderOnTop(GuiGraphics g, int mouseX, int mouseY, float pt) {
        if (!expanded || options.isEmpty()) return;

        var font = Minecraft.getInstance().font;
        int dropX = getX();
        int dropY = getY() + height;
        int dropW = width;
        int dropH = getExpandedHeight();

        g.pose().pushPose();
        g.pose().translate(0, 0, 500); // ensure always on top

        // Opaque solid backdrop
        fillRounded(g, dropX, dropY, dropX + dropW, dropY + dropH, 4, 0xFF202020);

        // Each option
        int optionY = dropY;
        for (int i = 0; i < options.size(); i++) {
            // Hover highlight
            if (mouseX >= dropX && mouseX <= dropX + dropW &&
                mouseY >= optionY && mouseY <= optionY + height) {
                g.fill(dropX, optionY, dropX + dropW, optionY + height, 0xFF444444);
            }
            g.drawString(font, options.get(i).toString(),
                    dropX + 4, optionY + (height - 8) / 2, 0xFFFFFFFF, false);
            optionY += height;
        }

        g.pose().popPose();
    }

    // --------------------------------------------------------
    // Input handling
    // --------------------------------------------------------

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        if (expanded) {
            // While expanded, only this widget can report true
            if (isOverExpanded(mouseX, mouseY)) return true;
            if (mouseX >= getX() && mouseX <= getX() + width &&
                mouseY >= getY() && mouseY <= getY() + height) return true;
            // Explicitly return false so underlying widgets don’t light up
            return false;
        }
        return super.isMouseOver(mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.active) return false;

        // Expanded: swallow all clicks within bounds
        if (expanded) {
            int dropX = getX();
            int dropY = getY() + height;
            int dropW = width;
            int dropH = getExpandedHeight();

            if (mouseX >= dropX && mouseX <= dropX + dropW &&
                mouseY >= dropY && mouseY <= dropY + dropH) {
                int optionY = dropY;
                for (int i = 0; i < options.size(); i++) {
                    if (mouseY >= optionY && mouseY <= optionY + height) {
                        selected = i;
                        expanded = false;
                        if (onSelect != null) onSelect.accept(getSelected());
                        return true;
                    }
                    optionY += height;
                }
                return true; // inside panel but not on option
            }

            // Outside → collapse & eat
            if (!(mouseX >= getX() && mouseX <= getX() + width &&
                  mouseY >= getY() && mouseY <= getY() + height)) {
                expanded = false;
                return true;
            }
        }

        // Collapsed toggle
        if (isMouseOver(mouseX, mouseY)) {
            expanded = !expanded;
            return true;
        }

        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        if (!options.isEmpty()) {
            narration.add(NarratedElementType.TITLE,
                    Component.literal("Variant: " + getSelected().toString()));
        }
    }

    // --------------------------------------------------------
    // Utility
    // --------------------------------------------------------

    private static void fillRounded(GuiGraphics g, int x0, int y0, int x1, int y1, int r, int argb) {
        if (r <= 0) { g.fill(x0, y0, x1, y1, argb); return; }
        r = Math.min(r, Math.min((x1 - x0) / 2, (y1 - y0) / 2));
        g.fill(x0 + r, y0, x1 - r, y1, argb);
        g.fill(x0, y0 + r, x0 + r, y1 - r, argb);
        g.fill(x1 - r, y0 + r, x1, y1 - r, argb);
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
