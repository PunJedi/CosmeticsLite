package com.pastlands.cosmeticslite.client.screen;

import com.pastlands.cosmeticslite.client.screen.parts.ColorWheelWidget;
import com.pastlands.cosmeticslite.client.screen.parts.ValueSliderWidget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.util.function.Consumer;

/**
 * Color picker dialog with color wheel, value slider, and hex input.
 * Allows editing a color via visual controls or hex input (#RRGGBB or #AARRGGBB).
 */
public class ParticleColorEditScreen extends Screen {
    private final Screen parent;
    private final int initialColor; // ARGB
    private final Consumer<Integer> onColorChanged;
    private final Runnable onCancel;
    
    // HSV color model state
    private float currentHue = 0f;        // 0..1
    private float currentSaturation = 0f; // 0..1
    private float currentValue = 1f;      // 0..1
    private int currentAlpha = 255;       // 0..255
    private int currentColor;             // ARGB, kept in sync
    
    private ColorWheelWidget colorWheel;
    private ValueSliderWidget valueSlider;
    private EditBox hexField;
    private ColorPreviewWidget previewWidget;
    private boolean validHex = true;
    private boolean updatingFromHex = false; // Prevent circular updates
    
    // Color model helper methods
    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
    
    private static int argbFromHSV(float h, float s, float v, int alpha) {
        h = clamp(h, 0f, 1f);
        s = clamp(s, 0f, 1f);
        v = clamp(v, 0f, 1f);
        int rgb = Color.HSBtoRGB(h, s, v);
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }
    
    private static float[] hsvFromARGB(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        float[] hsv = Color.RGBtoHSB(r, g, b, null);
        return hsv; // Returns [hue, saturation, value]
    }
    
    public ParticleColorEditScreen(Screen parent, int initialColor, Consumer<Integer> onColorChanged, @Nullable Runnable onCancel) {
        super(Component.literal("Edit Color"));
        this.parent = parent;
        this.initialColor = initialColor;
        this.currentColor = initialColor;
        this.onColorChanged = onColorChanged;
        this.onCancel = onCancel;
        
        // Convert initial color to HSV
        this.currentAlpha = (initialColor >> 24) & 0xFF;
        float[] hsv = hsvFromARGB(initialColor);
        this.currentHue = hsv[0];
        this.currentSaturation = hsv[1];
        this.currentValue = hsv[2];
    }
    
    /**
     * Preview widget that displays the current color as a filled rectangle.
     */
    private static class ColorPreviewWidget extends AbstractWidget {
        private int color;
        
        public ColorPreviewWidget(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty());
            this.color = 0xFFFFFFFF;
        }
        
        public void setColor(int argb) {
            this.color = argb;
        }
        
        @Override
        protected void renderWidget(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
            // Border
            gfx.fill(getX(), getY(), getX() + width, getY() + height, 0xFF000000);
            // Color fill
            gfx.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, color | 0xFF000000);
        }
        
        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, Component.literal("Color Preview"));
        }
    }
    
    private void updateColorFromHSV() {
        currentColor = argbFromHSV(currentHue, currentSaturation, currentValue, currentAlpha);
        if (previewWidget != null) {
            previewWidget.setColor(currentColor);
        }
        if (!updatingFromHex && hexField != null) {
            hexField.setValue(colorToHex(currentColor));
        }
    }
    
    private void updateHSVFromColor(int argb) {
        float[] hsv = hsvFromARGB(argb);
        currentHue = hsv[0];
        currentSaturation = hsv[1];
        currentValue = hsv[2];
        currentAlpha = (argb >> 24) & 0xFF;
        
        if (colorWheel != null) {
            colorWheel.setHueSaturation(currentHue, currentSaturation);
        }
        if (valueSlider != null) {
            valueSlider.setValue(currentValue);
        }
    }
    
    @Override
    protected void init() {
        super.init();
        
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        
        // Layout: Color wheel on left, value slider next to it, preview and hex below
        int wheelSize = 200;
        int wheelX = centerX - wheelSize / 2 - 40;
        int wheelY = centerY - wheelSize / 2 - 30;
        
        // Color wheel widget
        colorWheel = new ColorWheelWidget(
            wheelX, wheelY, wheelSize,
            currentHue, currentSaturation,
            (hs) -> {
                currentHue = hs[0];
                currentSaturation = hs[1];
                // Update slider gradient to match new hue/saturation
                if (valueSlider != null) {
                    valueSlider.setHueSaturation(currentHue, currentSaturation);
                }
                updateColorFromHSV();
            }
        );
        addRenderableWidget(colorWheel);
        
        // Value slider (vertical, to the right of wheel)
        int sliderWidth = 20;
        int sliderHeight = wheelSize;
        int sliderX = wheelX + wheelSize + 10;
        valueSlider = new ValueSliderWidget(
            sliderX, wheelY, sliderWidth, sliderHeight,
            currentValue, currentHue, currentSaturation,
            (v) -> {
                currentValue = v;
                updateColorFromHSV();
            }
        );
        addRenderableWidget(valueSlider);
        
        // Hex input field (below the wheel)
        int hexFieldY = wheelY + wheelSize + 15;
        hexField = new EditBox(this.font, centerX - 100, hexFieldY, 200, 20, Component.literal("Hex"));
        hexField.setValue(colorToHex(initialColor));
        hexField.setEditable(true);
        hexField.setMaxLength(9); // #AARRGGBB
        hexField.setFilter(s -> s.matches("[#0-9A-Fa-f]*"));
        hexField.setResponder(text -> {
            if (updatingFromHex) return;
            try {
                int newColor = hexToColor(text);
                updatingFromHex = true;
                updateHSVFromColor(newColor);
                updateColorFromHSV();
                validHex = true;
                updatingFromHex = false;
            } catch (IllegalArgumentException e) {
                validHex = false;
            }
        });
        addRenderableWidget(hexField);
        
        // Color preview square (to the right of hex field)
        int previewSize = 30;
        int previewX = centerX + 110;
        previewWidget = new ColorPreviewWidget(previewX, hexFieldY, previewSize, previewSize);
        previewWidget.setColor(currentColor);
        addRenderableWidget(previewWidget);
        
        // OK button
        int buttonY = hexFieldY + 35;
        addRenderableWidget(Button.builder(Component.literal("OK"), btn -> {
            onColorChanged.accept(currentColor);
            this.minecraft.setScreen(parent);
        }).bounds(centerX - 60, buttonY, 50, 20).build());
        
        // Cancel button
        addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> {
            if (onCancel != null) {
                onCancel.run();
            }
            this.minecraft.setScreen(parent);
        }).bounds(centerX + 10, buttonY, 50, 20).build());
        
        updateColorFromHSV();
    }
    
    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gfx);
        
        int centerX = this.width / 2;
        
        // Title
        gfx.drawCenteredString(this.font, "Edit Color", centerX, 20, 0xFFFFFF);
        
        // Hex label
        if (hexField != null) {
            gfx.drawString(this.font, "Hex (#RRGGBB or #AARRGGBB):", centerX - 100, hexField.getY() - 12, 0xAAAAAA, false);
        }
        
        // Error message if invalid
        if (!validHex && hexField != null) {
            gfx.drawCenteredString(this.font, "Invalid hex color", centerX, hexField.getY() + 25, 0xFF0000);
        }
        
        super.render(gfx, mouseX, mouseY, partialTick);
    }
    
    @Override
    public void onClose() {
        if (onCancel != null) {
            onCancel.run();
        }
        this.minecraft.setScreen(parent);
    }
    
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // ESC
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    
    private String colorToHex(int argb) {
        int alpha = (argb >> 24) & 0xFF;
        if (alpha == 255) {
            // #RRGGBB format
            return String.format("#%06X", argb & 0x00FFFFFF);
        } else {
            // #AARRGGBB format
            return String.format("#%08X", argb);
        }
    }
    
    private int hexToColor(String hex) {
        if (hex == null || hex.isEmpty()) {
            throw new IllegalArgumentException("Empty hex");
        }
        
        // Remove # if present
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        
        // Parse hex
        if (hex.length() == 6) {
            // RRGGBB - add alpha FF
            return 0xFF000000 | Integer.parseInt(hex, 16);
        } else if (hex.length() == 8) {
            // AARRGGBB
            return (int) Long.parseLong(hex, 16);
        } else {
            throw new IllegalArgumentException("Invalid hex length: " + hex.length());
        }
    }
}

