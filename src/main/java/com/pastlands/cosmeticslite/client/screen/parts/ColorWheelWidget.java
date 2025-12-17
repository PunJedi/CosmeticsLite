// src/main/java/com/pastlands/cosmeticslite/client/screen/parts/ColorWheelWidget.java
package com.pastlands.cosmeticslite.client.screen.parts;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.awt.*;
import java.util.function.Consumer;
import net.minecraft.util.Mth;

/**
 * Color wheel selector widget for hue and saturation.
 * Reports HSV changes via callback: float[]{hue, saturation}
 * Also supports backward-compatible RGB callback for CosmeticsChestScreen.
 */
public class ColorWheelWidget extends AbstractWidget {
    private final int radius;
    private float hue = 0f;        // 0..1
    private float saturation = 0f; // 0..1
    private float value = 1f;      // 0..1 (for RGB conversion)
    private boolean dragging = false;
    private final Consumer<float[]> onColorChanged;
    private Consumer<Integer> onRGBChanged; // Optional RGB callback for backward compatibility
    
    // Store the actual angle and distance for color calculation (avoids conversion drift)
    private float selectedAngle = 0.0f;  // -π to π, same as Math.atan2 returns
    private float selectedDistance = 0.0f;  // 0..1 (normalized distance)

    // Main constructor for HSV-based color picking
    public ColorWheelWidget(int x, int y, int size, float initialHue, float initialSaturation, Consumer<float[]> onColorChanged) {
        super(x, y, size, size, Component.empty());
        this.radius = size / 2;
        this.hue = Math.max(0f, Math.min(1f, initialHue));
        this.saturation = Math.max(0f, Math.min(1f, initialSaturation));
        this.onColorChanged = onColorChanged;
        
        // Initialize from starting color
        initFromInitialColor();
    }
    
    // Backward-compatible constructor for CosmeticsChestScreen (simple RGB callback)
    public ColorWheelWidget(int x, int y, int size) {
        super(x, y, size, size, Component.empty());
        this.radius = size / 2;
        this.hue = 0f;
        this.saturation = 0f;
        this.value = 1f;
        this.onColorChanged = null;
        // Anonymous subclass can override onColorSelectedRGB
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        RenderSystem.enableBlend();

        // Draw the color wheel (simple hue/sat circle) - sample every 2-3 pixels for performance
        for (int y = 0; y < this.height; y += 2) {
            for (int x = 0; x < this.width; x += 2) {
                float dx = x - radius;
                float dy = y - radius;
                float dist = Mth.sqrt(dx * dx + dy * dy);
                
                if (dist <= radius) {
                    float h = (float) ((Math.atan2(dy, dx) / (2 * Math.PI) + 1) % 1.0);
                    float s = Mth.clamp(dist / radius, 0.0f, 1.0f);
                    int rgb = Color.HSBtoRGB(h, s, 1f);
                    graphics.fill(getX() + x, getY() + y, getX() + x + 2, getY() + y + 2, 0xFF000000 | rgb);
                }
            }
        }
        
        // Marker rendering removed - wheel is clickable but shows no visual indicator
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && this.isHovered()) {
            updateSelectionFromMouse(mouseX, mouseY);
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
            updateSelectionFromMouse(mouseX, mouseY);
        }
    }
    
    /**
     * Centralized method to update selection from mouse position.
     * Uses the same coordinate convention for input and rendering.
     */
    private void updateSelectionFromMouse(double mouseX, double mouseY) {
        // Use the same cx, cy as render
        float cx = getX() + radius;
        float cy = getY() + radius;
        
        // Compute mouse position relative to wheel center
        float dx = (float)(mouseX - cx);
        float dy = (float)(mouseY - cy);
        
        float distance = Mth.sqrt(dx * dx + dy * dy);
        float clamped = Mth.clamp(distance, 0.0f, this.radius);
        
        // Store normalized distance (0..1) and raw angle
        this.selectedDistance = clamped / this.radius;  // 0..1
        this.selectedAngle = (float) Math.atan2(dy, dx); // -π to π, store raw angle
        
        // Recompute the current color from angle + distance (hue + saturation)
        updateSelectedColorFromPolar();
    }
    
    /**
     * Derive HSB from stored angle/distance and notify callbacks.
     */
    private void updateSelectedColorFromPolar() {
        // Derive hue from stored angle: (angle / (2π) + 1) % 1.0
        float hue = (this.selectedAngle / (2.0f * (float)Math.PI) + 1.0f) % 1.0f;
        // Saturation is the stored normalized distance
        float saturation = this.selectedDistance;
        // Brightness comes from the vertical bar (value field)
        
        this.hue = hue;
        this.saturation = saturation;
        
        if (onColorChanged != null) {
            onColorChanged.accept(new float[]{hue, saturation});
        }
        
        // Also call RGB callback if set (for backward compatibility)
        if (onRGBChanged != null || this.getClass() != ColorWheelWidget.class) {
            int rgb = Color.HSBtoRGB(hue, saturation, value);
            onColorSelectedRGB(rgb);
        }
    }
    
    /**
     * Initialize selection from initial hue/saturation values.
     */
    private void initFromInitialColor() {
        // Convert hue (0..1) to angle (-π to π)
        this.selectedAngle = (float)((hue * 2 * Math.PI) - Math.PI);
        // Saturation is already 0..1
        this.selectedDistance = saturation;
    }
    
    public void setHueSaturation(float h, float s) {
        this.hue = Math.max(0f, Math.min(1f, h));
        this.saturation = Math.max(0f, Math.min(1f, s));
        
        // Update stored angle/distance to match new hue/saturation
        this.selectedAngle = (float)((h * 2 * Math.PI) - Math.PI);
        this.selectedDistance = s;  // Already normalized 0..1
    }
    
    public float getHue() { return hue; }
    public float getSaturation() { return saturation; }
    
    /**
     * Returns the currently selected RGB color (for backward compatibility).
     * Uses value=1.0 for conversion.
     */
    public int getCurrentColor() {
        return Color.HSBtoRGB(hue, saturation, value);
    }
    
    /**
     * Hook for backward compatibility - called when color changes (RGB path).
     * Subclasses can override this for simple RGB callbacks.
     */
    protected void onColorSelectedRGB(int rgb) {
        // No-op by default, can be overridden
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
