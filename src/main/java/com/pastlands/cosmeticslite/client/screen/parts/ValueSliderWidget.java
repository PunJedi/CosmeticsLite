package com.pastlands.cosmeticslite.client.screen.parts;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.function.Consumer;

/**
 * Vertical slider for brightness/value in HSV color model.
 * Renders a gradient from black (V=0) to full color (V=1) at current hue/saturation.
 */
public class ValueSliderWidget extends AbstractWidget {
    private float hue;
    private float saturation;
    private float value = 1f; // 0..1
    private boolean dragging = false;
    private final Consumer<Float> onValueChanged;
    
    public ValueSliderWidget(int x, int y, int width, int height, float initialValue, 
                             float hue, float saturation, Consumer<Float> onValueChanged) {
        super(x, y, width, height, Component.empty());
        this.value = Math.max(0f, Math.min(1f, initialValue));
        this.hue = Math.max(0f, Math.min(1f, hue));
        this.saturation = Math.max(0f, Math.min(1f, saturation));
        this.onValueChanged = onValueChanged;
    }
    
    public void setHueSaturation(float h, float s) {
        this.hue = Math.max(0f, Math.min(1f, h));
        this.saturation = Math.max(0f, Math.min(1f, s));
    }
    
    @Override
    protected void renderWidget(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        RenderSystem.enableBlend();
        
        // Draw vertical gradient from black (V=0) to full color (V=1)
        for (int y = 0; y < this.height; y++) {
            float v = 1f - ((float) y / this.height); // 0 at bottom, 1 at top
            int rgb = Color.HSBtoRGB(hue, saturation, v);
            gfx.fill(getX(), getY() + y, getX() + this.width, getY() + y + 1, 0xFF000000 | rgb);
        }
        
        // Draw slider thumb at current value
        int thumbY = getY() + (int) ((1f - value) * this.height);
        // Thumb indicator (horizontal line with small rectangles)
        gfx.fill(getX() - 2, thumbY - 1, getX() + this.width + 2, thumbY + 1, 0xFFFFFFFF);
        gfx.fill(getX() - 3, thumbY - 2, getX() + this.width + 3, thumbY, 0xFF000000);
        gfx.fill(getX() - 3, thumbY, getX() + this.width + 3, thumbY + 2, 0xFF000000);
    }
    
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && this.isHovered()) {
            setValueFromY(mouseY);
            dragging = true;
            return true;
        }
        return false;
    }
    
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            dragging = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }
    
    @Override
    public void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        if (dragging) {
            setValueFromY(mouseY);
        }
    }
    
    private void setValueFromY(double mouseY) {
        double relativeY = mouseY - getY();
        float newValue = (float) Math.max(0.0, Math.min(1.0, 1.0 - (relativeY / this.height)));
        this.value = newValue;
        if (onValueChanged != null) {
            onValueChanged.accept(value);
        }
    }
    
    public void setValue(float v) {
        this.value = Math.max(0f, Math.min(1f, v));
    }
    
    public float getValue() {
        return value;
    }
    
        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, Component.literal("Value Slider"));
        }
}

