package com.pastlands.cosmeticslite.client.screen;

import com.pastlands.cosmeticslite.client.CosmeticParticleClientCatalog;
import com.pastlands.cosmeticslite.particle.CosmeticIconRegistry;
import com.pastlands.cosmeticslite.particle.CosmeticParticleEntry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Dialog screen for publishing a particle definition as a cosmetic.
 * Slot is locked to AURA for Particle Lab cosmetics.
 */
public class PublishCosmeticDialogScreen extends Screen {
    private final Screen parent;
    private final ResourceLocation particleId;
    private final Consumer<PublishData> onConfirm;
    
    private EditBox displayNameField;
    private com.pastlands.cosmeticslite.client.screen.parts.StringDropdownWidget iconDropdown;
    private Button saveBtn, cancelBtn;
    private String errorMessage = null;
    private ResourceLocation selectedIconId;
    
    /**
     * Data passed to the confirm callback.
     */
    public record PublishData(String displayName, ResourceLocation iconId) {}
    
    public PublishCosmeticDialogScreen(Screen parent, ResourceLocation particleId, 
                                       Consumer<PublishData> onConfirm) {
        super(Component.literal("Publish as Cosmetic"));
        this.parent = parent;
        this.particleId = particleId;
        this.onConfirm = onConfirm;
    }
    
    @Override
    protected void init() {
        super.init();
        
        // Normalize base dialog frame
        int dialogWidth = 300;
        int dialogX = (this.width - dialogWidth) / 2;
        int centerX = this.width / 2;
        int currentY = this.height / 2 - 60; // start slightly above center
        
        // Generate default display name from particle ID
        String defaultDisplayName = generateDisplayName(particleId);
        
        // Compute layout positions for labels and fields
        // Title will be at currentY, particle info below it
        int particleInfoY = currentY + 16; // below title
        
        // Display Name section
        int displayNameLabelY = particleInfoY + 20; // gap below particle info
        int displayNameFieldY = displayNameLabelY + 11; // label is 11px above field
        int fieldX = dialogX + 8;
        int fieldWidth = dialogWidth - 16;
        
        // Display name field
        displayNameField = new EditBox(this.font, fieldX, displayNameFieldY, fieldWidth, 20, 
            Component.literal("Display Name"));
        displayNameField.setMaxLength(128);
        displayNameField.setValue(defaultDisplayName);
        displayNameField.setResponder(text -> {
            errorMessage = null;
            updateSaveButton();
        });
        addRenderableWidget(displayNameField);
        
        // Icon dropdown section
        // Check if entry already exists to load current icon
        ResourceLocation cosmeticId = generateCosmeticId(particleId);
        CosmeticParticleEntry existingEntry = CosmeticParticleClientCatalog.get(cosmeticId);
        ResourceLocation defaultIconId = CosmeticIconRegistry.DEFAULT_PARTICLE_ICON;
        if (existingEntry != null && existingEntry.icon() != null && existingEntry.icon().itemId() != null) {
            defaultIconId = existingEntry.icon().itemId();
        }
        selectedIconId = defaultIconId;
        
        // Build icon options list
        List<CosmeticIconRegistry.IconOption> iconOptions = CosmeticIconRegistry.getAvailableIcons();
        List<String> iconDisplayNames = new ArrayList<>();
        for (CosmeticIconRegistry.IconOption icon : iconOptions) {
            iconDisplayNames.add(icon.displayName());
        }
        
        int iconLabelY = displayNameFieldY + 20 + 10; // gap below text field (field height is 20)
        int iconDropdownY = iconLabelY + 11; // label is 11px above dropdown
        
        iconDropdown = new com.pastlands.cosmeticslite.client.screen.parts.StringDropdownWidget(
            fieldX, iconDropdownY, fieldWidth, 20,
            iconDisplayNames,
            selected -> {
                // Find icon by display name
                for (CosmeticIconRegistry.IconOption icon : iconOptions) {
                    if (icon.displayName().equals(selected)) {
                        selectedIconId = icon.itemId();
                        break;
                    }
                }
                updateSaveButton();
            }
        );
        // Set selected to match current icon
        String selectedDisplayName = CosmeticIconRegistry.getDisplayName(defaultIconId);
        iconDropdown.setSelected(selectedDisplayName);
        addRenderableWidget(iconDropdown);
        
        // Buttons row
        int buttonsY = iconDropdownY + iconDropdown.getHeight() + 16; // larger gap below dropdown
        int buttonWidth = 80;
        int buttonGap = 8;
        int totalButtonWidth = buttonWidth * 2 + buttonGap;
        int buttonsX = centerX - totalButtonWidth / 2;
        
        saveBtn = Button.builder(Component.literal("Publish"), btn -> confirm())
            .bounds(buttonsX, buttonsY, buttonWidth, 20).build();
        cancelBtn = Button.builder(Component.literal("Cancel"), btn -> onClose())
            .bounds(buttonsX + buttonWidth + buttonGap, buttonsY, buttonWidth, 20).build();
        addRenderableWidget(saveBtn);
        addRenderableWidget(cancelBtn);
        
        updateSaveButton();
        setInitialFocus(displayNameField);
    }
    
    private ResourceLocation generateCosmeticId(ResourceLocation particleId) {
        String path = particleId.getPath();
        if (path.startsWith("particle/")) {
            path = path.substring("particle/".length());
        }
        return ResourceLocation.fromNamespaceAndPath(
            particleId.getNamespace(),
            "cosmetic/" + path
        );
    }
    
    private String generateDisplayName(ResourceLocation id) {
        // Extract name from path: "particle/angel_wisps_blended" -> "Angel Wisps Blended"
        String path = id.getPath();
        if (path.contains("/")) {
            path = path.substring(path.lastIndexOf('/') + 1);
        }
        // Replace underscores with spaces and capitalize words
        String[] parts = path.split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(" ");
            if (!parts[i].isEmpty()) {
                sb.append(Character.toUpperCase(parts[i].charAt(0)));
                if (parts[i].length() > 1) {
                    sb.append(parts[i].substring(1));
                }
            }
        }
        return sb.toString();
    }
    
    private void updateSaveButton() {
        if (saveBtn != null && displayNameField != null) {
            String name = displayNameField.getValue();
            saveBtn.active = name != null && !name.trim().isEmpty();
        }
    }
    
    private void confirm() {
        String displayName = displayNameField.getValue().trim();
        if (displayName.isEmpty()) {
            errorMessage = "Display name cannot be empty";
            return;
        }
        
        // Use selected icon or fallback to default
        ResourceLocation iconId = selectedIconId != null 
            ? selectedIconId 
            : CosmeticIconRegistry.DEFAULT_PARTICLE_ICON;
        
        onConfirm.accept(new PublishData(displayName, iconId));
        this.minecraft.setScreen(parent);
    }
    
    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gfx);
        
        // Normalize base dialog frame (same as init)
        int dialogWidth = 300;
        int dialogX = (this.width - dialogWidth) / 2;
        int centerX = this.width / 2;
        int currentY = this.height / 2 - 60;
        
        // Title centered at top
        gfx.drawCenteredString(this.font, "Publish as Cosmetic", centerX, currentY, 0xFFFFFF);
        
        // Particle info line below title, left-aligned inside the card
        int particleInfoY = currentY + 16;
        int left = dialogX + 8;
        gfx.drawString(this.font, "Particle: " + particleId.toString(), left, particleInfoY, 0xAAAAAA, false);
        
        // Display Name label above the text field (11px above field)
        if (displayNameField != null) {
            int displayNameLabelY = displayNameField.getY() - 11;
            int fieldX = displayNameField.getX();
            gfx.drawString(this.font, "Display Name", fieldX, displayNameLabelY, 0xFFFFFF, false);
        }
        
        // Activation Item label above the dropdown (11px above dropdown)
        if (iconDropdown != null) {
            int iconLabelY = iconDropdown.getY() - 11;
            int dropdownX = iconDropdown.getX();
            gfx.drawString(this.font, "Activation Item", dropdownX, iconLabelY, 0xFFFFFF, false);
        }
        
        // Error message (if any) - below buttons
        if (errorMessage != null && saveBtn != null) {
            int errorY = saveBtn.getY() + saveBtn.getHeight() + 8;
            gfx.drawCenteredString(this.font, errorMessage, centerX, errorY, 0xFF0000);
        }
        
        super.render(gfx, mouseX, mouseY, partialTick);
        
        // Render dropdown on top
        if (iconDropdown != null) {
            iconDropdown.renderOnTop(gfx, mouseX, mouseY, partialTick);
        }
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
            if (saveBtn.active) {
                confirm();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}

