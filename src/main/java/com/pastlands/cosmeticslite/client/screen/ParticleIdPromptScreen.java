package com.pastlands.cosmeticslite.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Consumer;

/**
 * Simple screen to prompt for a particle definition ID.
 */
public class ParticleIdPromptScreen extends Screen {
    private final Screen parent;
    private final Consumer<ResourceLocation> onConfirm;
    private EditBox idField;
    private Button confirmBtn, cancelBtn;
    private String errorMessage = null;
    
    public ParticleIdPromptScreen(Screen parent, String promptText, Consumer<ResourceLocation> onConfirm) {
        super(Component.literal("Enter Particle ID"));
        this.parent = parent;
        this.onConfirm = onConfirm;
    }
    
    @Override
    protected void init() {
        super.init();
        
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        
        // ID input field
        idField = new EditBox(this.font, centerX - 150, centerY - 10, 300, 20, Component.literal("ID"));
        idField.setMaxLength(256);
        idField.setValue("cosmeticslite:particle/");
        idField.setResponder(text -> {
            errorMessage = null;
            updateConfirmButton();
        });
        addRenderableWidget(idField);
        
        // Buttons
        confirmBtn = Button.builder(Component.literal("Confirm"), btn -> confirm())
            .bounds(centerX - 100, centerY + 20, 80, 20).build();
        cancelBtn = Button.builder(Component.literal("Cancel"), btn -> onClose())
            .bounds(centerX + 20, centerY + 20, 80, 20).build();
        addRenderableWidget(confirmBtn);
        addRenderableWidget(cancelBtn);
        
        updateConfirmButton();
        setInitialFocus(idField);
    }
    
    private void updateConfirmButton() {
        if (confirmBtn != null && idField != null) {
            String text = idField.getValue();
            String validationError = validateId(text);
            confirmBtn.active = validationError == null;
        }
    }
    
    private String validateId(String text) {
        if (text == null || text.isEmpty()) {
            return "ID cannot be empty";
        }
        
        // Must contain :
        int colonIndex = text.indexOf(':');
        if (colonIndex < 0) {
            return "ID must contain ':' (e.g. cosmeticslite:particle/my_effect)";
        }
        
        // Must contain / after namespace
        int slashIndex = text.indexOf('/', colonIndex);
        if (slashIndex < 0) {
            return "ID must contain '/' after namespace (e.g. cosmeticslite:particle/my_effect)";
        }
        
        // Try to parse as ResourceLocation
        try {
            ResourceLocation id = ResourceLocation.parse(text);
            if (id == null || id.getPath().isEmpty()) {
                return "Invalid ID format";
            }
            return null; // Valid
        } catch (Exception e) {
            return "Invalid ID format: " + e.getMessage();
        }
    }
    
    private void confirm() {
        String text = idField.getValue();
        String validationError = validateId(text);
        
        if (validationError != null) {
            errorMessage = validationError;
            return; // Don't close, show error
        }
        
        try {
            ResourceLocation id = ResourceLocation.parse(text);
            if (id != null && !id.getPath().isEmpty()) {
                onConfirm.accept(id);
                this.minecraft.setScreen(parent);
            }
        } catch (Exception e) {
            errorMessage = "Failed to parse ID: " + e.getMessage();
        }
    }
    
    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gfx);
        
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        
        gfx.drawCenteredString(this.font, "Enter particle ID (e.g. cosmeticslite:particle/my_effect)", 
            centerX, centerY - 60, 0xFFFFFF);
        
        // Show error message in red if present
        if (errorMessage != null) {
            gfx.drawCenteredString(this.font, errorMessage, centerX, centerY + 10, 0xFF0000);
        }
        
        super.render(gfx, mouseX, mouseY, partialTick);
    }
    
    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
    
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // ESC
            onClose();
            return true;
        }
        if (keyCode == 257) { // Enter
            if (confirmBtn.active) {
                confirm();
                return true;
            } else {
                // Show error if trying to confirm invalid ID
                String text = idField != null ? idField.getValue() : "";
                errorMessage = validateId(text);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}

