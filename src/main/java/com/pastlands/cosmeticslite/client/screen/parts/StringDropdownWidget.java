package com.pastlands.cosmeticslite.client.screen.parts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Simple string-based dropdown widget (simplified from VariantDropdownWidget).
 */
public class StringDropdownWidget extends AbstractWidget {
    private static final int MAX_VISIBLE_ENTRIES = 10; // How many entries to show at once (not a limit on total entries)
    
    private List<String> options = new ArrayList<>();
    private int selected = 0;
    private int scrollOffset = 0; // Scroll position when expanded
    private boolean expanded = false;
    private boolean active = true;
    private final Consumer<String> onSelect;
    private Runnable onExpandedChanged; // Callback when expanded state changes
    
    // Track expanded list bounds for mouse wheel scrolling
    private int listX, listY, listWidth, listHeight;
    private boolean listBoundsValid = false;
    
    // Optional tooltip provider: maps display string to tooltip text
    private java.util.function.Function<String, String> tooltipProvider = null;

    public StringDropdownWidget(int x, int y, int width, int height,
                               List<String> initialOptions,
                               Consumer<String> onSelect) {
        super(x, y, width, height, Component.literal(""));
        this.options = new ArrayList<>(initialOptions);
        this.onSelect = onSelect;
    }

    public void setOptions(List<String> newOptions) {
        this.options = new ArrayList<>(newOptions);
        this.selected = Math.min(selected, options.size() - 1);
        this.expanded = false;
        this.scrollOffset = 0; // Reset scroll when options change
    }

    public void setSelected(String value) {
        int idx = options.indexOf(value);
        if (idx >= 0) {
            this.selected = idx;
        }
    }

    public String getSelected() {
        if (options.isEmpty()) return "";
        return options.get(Math.min(selected, options.size() - 1));
    }

    public void setActive(boolean active) { this.active = active; }

    public boolean isExpanded() { return expanded; }
    
    public void setExpanded(boolean expanded) { 
        boolean wasExpanded = this.expanded;
        this.expanded = expanded;
        // Reset scroll when closing - clamp to nearest valid position
        if (!expanded) {
            int total = this.options.size();
            if (total > MAX_VISIBLE_ENTRIES) {
                int maxOffset = Math.max(0, total - MAX_VISIBLE_ENTRIES);
                this.scrollOffset = Mth.clamp(this.scrollOffset, 0, maxOffset);
            } else {
                this.scrollOffset = 0;
            }
            this.listBoundsValid = false;
            this.lastScrollTime = 0; // Reset scroll cooldown
        }
        if (wasExpanded != expanded && onExpandedChanged != null) {
            onExpandedChanged.run();
        }
    }
    
    public void setOnExpandedChanged(Runnable callback) {
        this.onExpandedChanged = callback;
    }
    
    /**
     * Set a tooltip provider function that maps display strings to tooltip text.
     * Used for showing full IDs or additional info when hovering over dropdown entries.
     */
    public void setTooltipProvider(java.util.function.Function<String, String> provider) {
        this.tooltipProvider = provider;
    }

    private int getExpandedHeight() { 
        int visibleCount = Math.min(options.size(), MAX_VISIBLE_ENTRIES);
        return visibleCount * this.height; 
    }

    private boolean isOverExpanded(double mouseX, double mouseY) {
        if (!expanded) return false;
        int dropX = getX();
        int dropY = getY() + height;
        int dropW = width;
        int dropH = getExpandedHeight();
        return mouseX >= dropX && mouseX <= dropX + dropW &&
               mouseY >= dropY && mouseY <= dropY + dropH;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float pt) {
        var font = Minecraft.getInstance().font;
        int bg = this.active ? 0xFF3C3C3C : 0xFF555555;
        fillRounded(g, getX(), getY(), getX() + width, getY() + height, 4, bg);

        String text = options.isEmpty() ? "—" : getSelected();
        int color = options.isEmpty() ? 0xFFAAAAAA : 0xFFFFFFFF;
        
        // Truncate selected text if needed (leave space for arrow)
        int padding = 4;
        int arrowWidth = 10;
        int maxTextWidth = width - padding * 2 - arrowWidth - 4;
        String displayText = text;
        if (font.width(text) > maxTextWidth) {
            int ellipsisWidth = font.width("...");
            int availableWidth = maxTextWidth - ellipsisWidth;
            if (availableWidth > 0) {
                displayText = font.plainSubstrByWidth(text, availableWidth) + "...";
            } else {
                displayText = "...";
            }
        }
        
        g.drawString(font, displayText, getX() + padding, getY() + (height - 8) / 2, color, false);

        g.drawString(font, expanded ? "▲" : "▼",
                getX() + width - arrowWidth, getY() + (height - 8) / 2, 0xFFFFFFFF, false);
    }

    public void renderOnTop(GuiGraphics g, int mouseX, int mouseY, float pt) {
        if (!expanded || options.isEmpty()) {
            this.listBoundsValid = false;
            return;
        }

        var font = Minecraft.getInstance().font;
        int dropX = getX();
        int dropY = getY() + height;
        int dropW = width;
        int dropH = getExpandedHeight();
        
        // Track expanded list bounds for mouse wheel scrolling
        this.listX = dropX;
        this.listY = dropY;
        this.listWidth = dropW;
        this.listHeight = dropH; // Visible height (MAX_VISIBLE_ENTRIES * rowHeight)
        this.listBoundsValid = true;
        
        // Calculate visible range based on scroll offset
        int start = scrollOffset;
        int end = Math.min(start + MAX_VISIBLE_ENTRIES, options.size());
        int visibleCount = end - start;

        g.pose().pushPose();
        g.pose().translate(0, 0, 500);

        fillRounded(g, dropX, dropY, dropX + dropW, dropY + dropH, 4, 0xFF202020);

        int optionY = dropY;
        String hoveredTooltip = null;
        int hoveredTooltipY = 0;
        
        for (int i = 0; i < visibleCount; i++) {
            int absoluteIndex = start + i;
            if (absoluteIndex >= options.size()) break;
            
            String optionText = options.get(absoluteIndex);
            boolean isHovered = mouseX >= dropX && mouseX <= dropX + dropW &&
                mouseY >= optionY && mouseY <= optionY + height;
            
            if (isHovered) {
                g.fill(dropX, optionY, dropX + dropW, optionY + height, 0xFF444444);
                // Get tooltip if provider is set
                if (tooltipProvider != null) {
                    hoveredTooltip = tooltipProvider.apply(optionText);
                    hoveredTooltipY = optionY;
                }
            }
            
            // Truncate text with ellipsis if needed
            int padding = 4;
            int maxTextWidth = dropW - padding * 2 - 4; // Leave some space for arrow/scrollbar
            String displayText = optionText;
            if (font.width(optionText) > maxTextWidth) {
                // Calculate how much space we have for text (minus ellipsis)
                int ellipsisWidth = font.width("...");
                int availableWidth = maxTextWidth - ellipsisWidth;
                if (availableWidth > 0) {
                    displayText = font.plainSubstrByWidth(optionText, availableWidth) + "...";
                } else {
                    displayText = "..."; // Fallback if even ellipsis doesn't fit
                }
            }
            
            g.drawString(font, displayText,
                    dropX + padding, optionY + (height - 8) / 2, 0xFFFFFFFF, false);
            optionY += height;
        }
        
        // Render tooltip if hovering over an entry
        if (hoveredTooltip != null && !hoveredTooltip.isEmpty()) {
            int tooltipWidth = font.width(hoveredTooltip) + 6;
            int tooltipHeight = font.lineHeight + 4;
            
            // Try to position tooltip to the right first, but fall back to left if needed
            int tooltipX = dropX + dropW + 4;
            int tooltipY = hoveredTooltipY;
            
            // Simple positioning: if tooltip would go off-screen to the right, position to the left
            // (In a real implementation, you'd check against screen bounds, but this is a reasonable default)
            // For now, we'll position to the right as that's the most common case
            
            // Draw tooltip background with border
            g.fill(tooltipX, tooltipY, tooltipX + tooltipWidth, tooltipY + tooltipHeight, 0xFF000000);
            g.fill(tooltipX + 1, tooltipY + 1, tooltipX + tooltipWidth - 1, tooltipY + tooltipHeight - 1, 0xFF404040);
            
            // Draw tooltip text
            g.drawString(font, hoveredTooltip, tooltipX + 3, tooltipY + 2, 0xFFFFFFFF, false);
        }

        g.pose().popPose();
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        if (expanded) {
            if (isOverExpanded(mouseX, mouseY)) return true;
            if (mouseX >= getX() && mouseX <= getX() + width &&
                mouseY >= getY() && mouseY <= getY() + height) return true;
            return false;
        }
        return super.isMouseOver(mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.active) return false;

        if (expanded) {
            int dropX = getX();
            int dropY = getY() + height;
            int dropW = width;
            int dropH = getExpandedHeight();

            if (mouseX >= dropX && mouseX <= dropX + dropW &&
                mouseY >= dropY && mouseY <= dropY + dropH) {
                // Calculate which visible row was clicked
                int visibleRow = (int)((mouseY - dropY) / height);
                // Convert visible row to absolute index using scroll offset
                int absoluteIndex = this.scrollOffset + visibleRow;
                
                if (absoluteIndex >= 0 && absoluteIndex < options.size()) {
                    selected = absoluteIndex;
                    expanded = false;
                    scrollOffset = 0; // Reset scroll when closing
                    this.listBoundsValid = false;
                    if (onSelect != null) onSelect.accept(getSelected());
                    return true;
                }
                return true; // Click was in list area, even if invalid row
            }

            if (!(mouseX >= getX() && mouseX <= getX() + width &&
                  mouseY >= getY() && mouseY <= getY() + height)) {
                expanded = false;
                scrollOffset = 0; // Reset scroll when closing
                return true;
            }
        }

        if (isMouseOver(mouseX, mouseY)) {
            // Toggle expanded state - notify callback if it changed
            boolean wasExpanded = expanded;
            expanded = !expanded;
            if (wasExpanded != expanded && onExpandedChanged != null) {
                onExpandedChanged.run();
            }
            return true;
        }

        return false;
    }
    
    // Track last scroll time to prevent multiple events per frame
    private long lastScrollTime = 0;
    private static final long SCROLL_COOLDOWN_MS = 16; // ~1 frame at 60fps
    
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!this.expanded || !this.listBoundsValid || this.options.isEmpty()) {
            return false;
        }

        // Only react if the mouse is over the expanded list area
        boolean overList = mouseX >= this.listX && mouseX <= this.listX + this.listWidth
                && mouseY >= this.listY && mouseY <= this.listY + this.listHeight;

        if (!overList) {
            return false;
        }

        int total = this.options.size();
        if (total <= MAX_VISIBLE_ENTRIES) {
            return false;
        }

        // Prevent multiple scroll events in the same frame/tick
        long currentTime = System.currentTimeMillis();
        if (currentTime - this.lastScrollTime < SCROLL_COOLDOWN_MS) {
            return true; // Still consume the event to prevent parent scrolling
        }
        this.lastScrollTime = currentTime;

        int maxOffset = Math.max(0, total - MAX_VISIBLE_ENTRIES);
        
        // Smooth scrolling: 1 wheel tick = 1 entry
        // delta > 0 => scroll up (decrease index); delta < 0 => scroll down (increase index)
        int newOffset = this.scrollOffset;
        if (delta < 0) {
            // Scroll down (toward end of list) - increase offset
            newOffset = Math.min(this.scrollOffset + 1, maxOffset);
        } else if (delta > 0) {
            // Scroll up (toward start of list) - decrease offset
            newOffset = Math.max(this.scrollOffset - 1, 0);
        }
        
        // Clamp to valid range (double-check safety)
        this.scrollOffset = Mth.clamp(newOffset, 0, maxOffset);

        return true; // we handled the event - do NOT close the dropdown
    }
    
    /**
     * Check if the given mouse coordinates are over the dropdown or its expanded menu.
     * Used by parent screen to determine if click should close this dropdown.
     */
    public boolean containsPoint(double mouseX, double mouseY) {
        // Check if mouse is over the button itself
        if (mouseX >= getX() && mouseX <= getX() + width &&
            mouseY >= getY() && mouseY <= getY() + height) {
            return true;
        }
        // Check if mouse is over the expanded menu
        if (expanded && isOverExpanded(mouseX, mouseY)) {
            return true;
        }
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        if (!options.isEmpty()) {
            narration.add(NarratedElementType.TITLE, Component.literal("Selected: " + getSelected()));
        }
    }

    private static void fillRounded(GuiGraphics g, int x0, int y0, int x1, int y1, int r, int argb) {
        if (r <= 0) {
            g.fill(x0, y0, x1, y1, argb);
            return;
        }
        r = Math.min(r, Math.min((x1 - x0) / 2, (y1 - y0) / 2));
        g.fill(x0 + r, y0, x1 - r, y1, argb);
        g.fill(x0, y0 + r, x0 + r, y1 - r, argb);
        g.fill(x1 - r, y0 + r, x1, y1 - r, argb);
        for (int dy = 0; dy < r; dy++) {
            int w = (int) Math.sqrt(r * r - dy * dy);
            g.fill(x0 + r - w, y0 + dy, x0 + r + w, y0 + dy + 1, argb);
            g.fill(x0 + r - w, y1 - dy - 1, x0 + r + w, y1 - dy, argb);
        }
    }
}

