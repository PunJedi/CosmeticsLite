// src/main/java/com/pastlands/cosmeticslite/client/screen/parts/ColorWheelWidget.java
package com.pastlands.cosmeticslite.client.screen.parts;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.awt.*;

/**
 * Color wheel selector widget.
 * Forge 47.4.0 / MC 1.20.1
 */
public class ColorWheelWidget extends AbstractWidget {
    private final int radius;
    private int selectedColor = 0xFFFFFFFF; // default white
    private boolean dragging = false;

    public ColorWheelWidget(int x, int y, int size) {
        super(x, y, size, size, Component.empty());
        this.radius = size / 2;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        RenderSystem.enableBlend();

        // Draw the color wheel (simple hue/sat circle)
        for (int r = 0; r < radius; r++) {
            for (int angle = 0; angle < 360; angle++) {
                double rad = Math.toRadians(angle);
                int px = getX() + radius + (int) (r * Math.cos(rad));
                int py = getY() + radius + (int) (r * Math.sin(rad));

                float hue = angle / 360f;
                float sat = (float) r / radius;
                int rgb = Color.HSBtoRGB(hue, sat, 1f);

                graphics.fill(px, py, px + 1, py + 1, 0xFF000000 | rgb);
            }
        }

        // Marker at center
        graphics.fill(getX() + radius - 2, getY() + radius - 2,
                getX() + radius + 2, getY() + radius + 2,
                0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && this.isHovered()) {
            pickColor(mouseX, mouseY);
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
            pickColor(mouseX, mouseY);
        }
    }

    private void pickColor(double mouseX, double mouseY) {
        double dx = mouseX - (getX() + radius);
        double dy = mouseY - (getY() + radius);
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist > radius) return;

        float hue = (float) ((Math.atan2(dy, dx) / (2 * Math.PI) + 1) % 1.0);
        float sat = (float) Math.min(1.0, dist / radius);
        selectedColor = Color.HSBtoRGB(hue, sat, 1f);

        // 🔹 Notify override hook
        onColorSelected(selectedColor);

        // 🔹 Debug log
        System.out.printf("[ColorWheelWidget] Picked color: #%06X%n", selectedColor & 0xFFFFFF);
    }

    /** Returns the currently selected ARGB color. */
    public int getCurrentColor() {
        return selectedColor;
    }

    /**
     * Hook for subclasses/anonymous overrides.
     * Called whenever a new color is picked.
     */
    protected void onColorSelected(int rgb) {
        // No-op by default
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
