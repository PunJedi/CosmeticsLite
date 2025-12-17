package com.pastlands.cosmeticslite.minigame.api;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Interface for mini-games in CosmeticsLite.
 * All games are client-side only, rendered in a bounded rectangle.
 */
public interface MiniGame {
    
    /**
     * Initialize the game state. Called once when the game screen opens.
     */
    void initGame();
    
    /**
     * Called every client tick. Update game logic here.
     */
    void tick();
    
    /**
     * Handle mouse click input.
     * @param mouseX Raw mouse X coordinate (screen space)
     * @param mouseY Raw mouse Y coordinate (screen space)
     * @param button Mouse button (0 = left, 1 = right, 2 = middle)
     */
    void handleMouseClick(double mouseX, double mouseY, int button);
    
    /**
     * Handle keyboard input.
     * @param keyCode The key code (see GLFW constants)
     */
    void handleKeyPress(int keyCode);
    
    /**
     * Render the game.
     * @param g GuiGraphics for rendering
     * @param font Font for text rendering
     * @param areaX X position of the game area (top-left)
     * @param areaY Y position of the game area (top-left)
     * @param areaWidth Width of the game area
     * @param areaHeight Height of the game area
     * @param partialTicks Partial tick for smooth rendering
     */
    void render(GuiGraphics g, Font font, int areaX, int areaY, int areaWidth, int areaHeight, float partialTicks);
    
    /**
     * Get the display title of the game.
     */
    String getTitle();
    
    /**
     * Check if the game is over (won or lost).
     */
    boolean isGameOver();
    
    /**
     * Get the current score. Return 0 if the game doesn't use scoring.
     */
    int getScore();
    
    /**
     * Called when the game screen is closed. Clean up resources if needed.
     */
    void onClose();
}

