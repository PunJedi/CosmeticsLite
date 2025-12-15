package com.pastlands.cosmeticslite.client.screen.parts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A numeric text field with inline ▲/▼ spinner buttons for increment/decrement.
 * Supports manual typing, arrow keys, and clickable spinner buttons.
 */
public class NumberFieldWithSpinnerWidget extends AbstractWidget {
    private double value;
    private final double minValue;
    private final double maxValue;
    private final double step;
    private final Function<Double, String> formatter;
    private final Consumer<Double> onValueChanged;
    
    private final EditBox editBox;
    private static final int SPINNER_WIDTH = 8;
    private static final int PADDING = 2;
    
    private boolean hoveredIncrement = false;
    private boolean hoveredDecrement = false;
    
    public NumberFieldWithSpinnerWidget(
            int x, int y, int width, int height,
            double initialValue,
            double minValue, double maxValue, double step,
            Function<Double, String> formatter,
            Consumer<Double> onValueChanged) {
        super(x, y, width, height, Component.empty());
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.step = step;
        this.formatter = formatter;
        this.onValueChanged = onValueChanged;
        this.value = clamp(initialValue);
        
        // Create internal EditBox - position it on the left side, leaving room for spinner
        // Use all height except small vertical padding
        int editBoxX = x + PADDING;
        int editBoxY = y + 1; // Small vertical padding
        int editBoxWidth = width - SPINNER_WIDTH - PADDING * 2;
        int editBoxHeight = height - 2; // Small vertical padding (1px top, 1px bottom)
        
        this.editBox = new EditBox(Minecraft.getInstance().font, editBoxX, editBoxY, editBoxWidth, editBoxHeight, Component.empty());
        this.editBox.setValue(formatter.apply(this.value));
        this.editBox.setEditable(true);
        this.editBox.setFilter(s -> s.matches("[0-9.\\-]*")); // Allow digits, decimal point, and minus sign
        this.editBox.setResponder(text -> {
            // Parse on text change - but don't update value until Enter or focus loss
            // This allows typing without interrupting
        });
        
        // Update text when value changes externally
        updateText();
    }
    
    private double clamp(double v) {
        if (v < minValue) return minValue;
        if (v > maxValue) return maxValue;
        return v;
    }
    
    private void setValue(double newValue) {
        double clamped = clamp(newValue);
        if (Math.abs(clamped - this.value) > 1e-9) { // Floating point comparison
            this.value = clamped;
            updateText();
            if (onValueChanged != null) {
                onValueChanged.accept(this.value);
            }
        }
    }
    
    private void updateText() {
        String formatted = formatter.apply(value);
        if (!editBox.getValue().equals(formatted)) {
            editBox.setValue(formatted);
        }
    }
    
    private void increment() {
        setValue(value + step);
    }
    
    private void decrement() {
        setValue(value - step);
    }
    
    private double tryParse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return value; // No change if empty
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return Double.NaN; // Invalid
        }
    }
    
    private void commitEditBoxValue() {
        double parsed = tryParse(editBox.getValue());
        if (!Double.isNaN(parsed)) {
            setValue(parsed);
        } else {
            updateText(); // Reset to current value if invalid
        }
    }
    
    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // Draw background for the entire widget
        int bgColor = this.active ? 0xFF2A2A2E : 0xFF1A1A1E;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, bgColor);
        
        // Draw border
        int borderColor = isFocused() ? 0xFFFFFFFF : 0xFF808080;
        graphics.fill(getX(), getY(), getX() + width, getY() + 1, borderColor);
        graphics.fill(getX(), getY() + height - 1, getX() + width, getY() + height, borderColor);
        graphics.fill(getX(), getY(), getX() + 1, getY() + height, borderColor);
        graphics.fill(getX() + width - SPINNER_WIDTH - 1, getY(), getX() + width - SPINNER_WIDTH, getY() + height, borderColor);
        
        // Render EditBox
        editBox.render(graphics, mouseX, mouseY, partialTicks);
        
        // Calculate spinner button bounds - sit flush with right edge
        int spinnerWidth = 8;
        int x0 = getX();
        int y0 = getY();
        int x1 = x0 + getWidth();
        int y1 = y0 + getHeight();
        int spinnerX0 = x1 - spinnerWidth;
        int spinnerX1 = x1;
        int midY = y0 + getHeight() / 2;
        
        // Update hover state
        hoveredIncrement = mouseX >= spinnerX0 && mouseX < spinnerX1 &&
                          mouseY >= y0 && mouseY < midY;
        hoveredDecrement = mouseX >= spinnerX0 && mouseX < spinnerX1 &&
                          mouseY >= midY && mouseY < y1;
        
        // Draw spinner background
        int spinnerBg = hoveredIncrement || hoveredDecrement ? 0xFF3A3A3E : 0xFF2A2A2E;
        graphics.fill(spinnerX0, y0, spinnerX1, y1, spinnerBg);
        
        // Draw divider line
        graphics.fill(spinnerX0, y0, spinnerX0 + 1, y1, 0xFF808080);
        graphics.fill(spinnerX0, midY, spinnerX1, midY + 1, 0xFF808080);
        
        // Draw increment button (▲) using font symbols
        var font = Minecraft.getInstance().font;
        int incrementColor = hoveredIncrement ? 0xFFFFFFFF : 0xFFE0E0E0;
        if (!this.active) {
            incrementColor = 0xFF808080; // Gray when disabled
        }
        String up = "▲";
        int upWidth = font.width(up);
        graphics.drawString(font, up,
            spinnerX0 + (spinnerWidth - upWidth) / 2,
            y0 + (midY - y0 - font.lineHeight) / 2,
            incrementColor, false);
        
        // Draw decrement button (▼) using font symbols
        int decrementColor = hoveredDecrement ? 0xFFFFFFFF : 0xFFE0E0E0;
        if (!this.active) {
            decrementColor = 0xFF808080; // Gray when disabled
        }
        String down = "▼";
        int downWidth = font.width(down);
        graphics.drawString(font, down,
            spinnerX0 + (spinnerWidth - downWidth) / 2,
            midY + (y1 - midY - font.lineHeight) / 2,
            decrementColor, false);
    }
    
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.active || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        
        // Use same geometry as render
        int spinnerWidth = 8;
        int x0 = getX();
        int y0 = getY();
        int x1 = x0 + getWidth();
        int y1 = y0 + getHeight();
        int spinnerX0 = x1 - spinnerWidth;
        int spinnerX1 = x1;
        int midY = y0 + getHeight() / 2;
        
        // Check if click is in spinner area
        if (mouseX >= spinnerX0 && mouseX < spinnerX1 &&
            mouseY >= y0 && mouseY < y1) {
            if (mouseY < midY) {
                // Increment button
                increment();
                return true;
            } else {
                // Decrement button
                decrement();
                return true;
            }
        }
        
        // Check if click is in EditBox area
        if (mouseX >= editBox.getX() && mouseX < editBox.getX() + editBox.getWidth() &&
            mouseY >= editBox.getY() && mouseY < editBox.getY() + editBox.getHeight()) {
            editBox.mouseClicked(mouseX, mouseY, button);
            setFocused(true);
            return true;
        }
        
        // Click outside - commit EditBox value and lose focus
        if (isFocused()) {
            commitEditBoxValue();
            setFocused(false);
        }
        
        return false;
    }
    
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!this.active) return false;
        
        if (editBox.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                commitEditBoxValue();
                setFocused(false);
                return true;
            }
            
            // Handle arrow keys for increment/decrement
            if (keyCode == GLFW.GLFW_KEY_UP) {
                increment();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DOWN) {
                decrement();
                return true;
            }
            
            return editBox.keyPressed(keyCode, scanCode, modifiers);
        }
        
        return false;
    }
    
    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!this.active || !editBox.isFocused()) return false;
        return editBox.charTyped(codePoint, modifiers);
    }
    
    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        editBox.setFocused(focused);
        if (!focused) {
            commitEditBoxValue();
        }
    }
    
    @Override
    public boolean isFocused() {
        return editBox.isFocused();
    }
    
    public double getValue() {
        return value;
    }
    
    public void setValueExternal(double newValue) {
        setValue(newValue);
    }
    
    public void setActive(boolean active) {
        this.active = active;
        this.editBox.setEditable(active);
    }
    
    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        editBox.updateNarration(narration);
    }
}

