package com.pastlands.cosmeticslite.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * A clickable color swatch button that displays a solid colored square with a border.
 */
public class ColorSwatchButton extends AbstractWidget {
    private int argbColor;
    private final Consumer<ColorSwatchButton> onClick;
    
    public ColorSwatchButton(int x, int y, int width, int height, int argbColor, Consumer<ColorSwatchButton> onClick) {
        super(x, y, width, height, Component.literal(""));
        this.argbColor = argbColor;
        this.onClick = onClick;
    }
    
    public int getColor() {
        return argbColor;
    }
    
    public void setColor(int argbColor) {
        this.argbColor = argbColor;
    }
    
    public void setActive(boolean active) {
        this.active = active;
    }
    
    @Override
    protected void renderWidget(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        // Draw with reduced opacity if disabled
        int alpha = this.active ? 0xFF : 0x80;
        int colorWithAlpha = (argbColor & 0x00FFFFFF) | (alpha << 24);
        
        // Draw solid color rect for the full interior
        gfx.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, colorWithAlpha);
        
        // Draw 1px border (neutral color)
        int borderColor = 0xFF000000; // Black border
        // Top border
        gfx.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + 1, borderColor);
        // Bottom border
        gfx.fill(this.getX(), this.getY() + this.height - 1, this.getX() + this.width, this.getY() + this.height, borderColor);
        // Left border
        gfx.fill(this.getX(), this.getY(), this.getX() + 1, this.getY() + this.height, borderColor);
        // Right border
        gfx.fill(this.getX() + this.width - 1, this.getY(), this.getX() + this.width, this.getY() + this.height, borderColor);
        
        // Optional: Draw hover highlight (only when active and hovered)
        if (this.active && this.isHovered()) {
            gfx.fill(this.getX() + 1, this.getY() + 1, this.getX() + this.width - 1, this.getY() + this.height - 1, 0x40FFFFFF);
        }
    }
    
    @Override
    public void onClick(double mouseX, double mouseY) {
        if (this.active && onClick != null) {
            onClick.accept(this);
        }
    }
    
    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        narrationElementOutput.add(NarratedElementType.TITLE, Component.literal("Color Swatch"));
    }
}

