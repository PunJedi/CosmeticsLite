package com.pastlands.cosmeticslite.client.screen.parts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.network.chat.Component;

/**
 * Simple text label widget that just renders text at a position.
 */
public class LabelWidget extends AbstractWidget {
    private final Component text;
    private int textColor;
    
    public LabelWidget(int x, int y, int width, int height, Component text, int textColor) {
        super(x, y, width, height, text);
        this.text = text;
        this.textColor = textColor;
    }
    
    public void setColor(int color) {
        this.textColor = color;
    }
    
    @Override
    protected void renderWidget(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        // Draw with shadow for better visibility
        gfx.drawString(font, text, getX(), getY(), textColor, true);
    }
    
    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        narrationElementOutput.add(NarratedElementType.TITLE, text);
    }
    
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Labels don't intercept clicks - let clicks pass through to fields/dropdowns
        return false;
    }
}

