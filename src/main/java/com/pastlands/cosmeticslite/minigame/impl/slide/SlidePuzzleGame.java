package com.pastlands.cosmeticslite.minigame.impl.slide;

import com.pastlands.cosmeticslite.minigame.api.MiniGame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

import java.util.Random;

/**
 * Slide Puzzle mini-game implementation.
 */
public class SlidePuzzleGame implements MiniGame {
    
    private static final int GRID_WIDTH = 4;
    private static final int GRID_HEIGHT = 4;
    
    private int[][] tiles;
    private int emptyX;
    private int emptyY;
    private boolean solved;
    private Random random;
    private int areaX, areaY, areaWidth, areaHeight; // Store for mouse calculations
    private int moveCount;
    private double lastMouseX = -1, lastMouseY = -1; // For hover detection
    
    // Visual-only animation state
    private static class MovingTile {
        int value;
        float fromX, fromY;
        float toX, toY;
        float t; // 0 -> 1
        boolean active;
    }
    
    private MovingTile movingTile = new MovingTile();
    private static final float ANIM_SPEED = 0.2f; // Animation speed per tick
    
    // Win sparkle effect
    private int winPulseTicks = 0;
    
    // Pressed state tracking
    private boolean tilePressed = false;
    private int pressedTileX = -1;
    private int pressedTileY = -1;
    
    @Override
    public void initGame() {
        random = new Random();
        solved = false;
        moveCount = 0;
        movingTile.active = false;
        movingTile.t = 0.0f;
        winPulseTicks = 0;
        tilePressed = false;
        pressedTileX = -1;
        pressedTileY = -1;
        tiles = new int[GRID_HEIGHT][GRID_WIDTH];
        
        // Initialize in solved state
        int value = 1;
        for (int y = 0; y < GRID_HEIGHT; y++) {
            for (int x = 0; x < GRID_WIDTH; x++) {
                tiles[y][x] = value++;
            }
        }
        tiles[GRID_HEIGHT - 1][GRID_WIDTH - 1] = 0; // Empty tile
        emptyX = GRID_WIDTH - 1;
        emptyY = GRID_HEIGHT - 1;
        
        // Shuffle by performing random valid moves
        for (int i = 0; i < 200; i++) {
            int[] directions = {0, 1, 2, 3}; // 0=up, 1=down, 2=left, 3=right
            int dir = directions[random.nextInt(4)];
            
            int newX = emptyX;
            int newY = emptyY;
            
            switch (dir) {
                case 0 -> newY--; // up
                case 1 -> newY++; // down
                case 2 -> newX--; // left
                case 3 -> newX++; // right
            }
            
            if (newX >= 0 && newX < GRID_WIDTH && newY >= 0 && newY < GRID_HEIGHT) {
                // Swap
                tiles[emptyY][emptyX] = tiles[newY][newX];
                tiles[newY][newX] = 0;
                emptyX = newX;
                emptyY = newY;
            }
        }
    }
    
    @Override
    public void tick() {
        // Update sliding animation
        if (movingTile.active) {
            movingTile.t += ANIM_SPEED;
            if (movingTile.t >= 1.0f) {
                movingTile.t = 1.0f;
                movingTile.active = false;
            }
        }
        
        // Update win sparkle
        if (winPulseTicks > 0) {
            winPulseTicks--;
        }
        
        // Reset pressed state
        tilePressed = false;
        pressedTileX = -1;
        pressedTileY = -1;
    }
    
    @Override
    public void handleMouseClick(double mouseX, double mouseY, int button) {
        if (solved || button != 0 || movingTile.active) return; // Only left click, and not during animation
        
        double tileWidth = (double) areaWidth / GRID_WIDTH;
        double tileHeight = (double) areaHeight / GRID_HEIGHT;
        
        int tx = (int) Math.floor((mouseX - areaX) / tileWidth);
        int ty = (int) Math.floor((mouseY - areaY) / tileHeight);
        
        if (tx < 0 || tx >= GRID_WIDTH || ty < 0 || ty >= GRID_HEIGHT) {
            return;
        }
        
        // Track pressed state for visual feedback
        tilePressed = true;
        pressedTileX = tx;
        pressedTileY = ty;
        
        // Check if clicked tile is adjacent to empty
        int dx = Math.abs(tx - emptyX);
        int dy = Math.abs(ty - emptyY);
        
        if ((dx == 1 && dy == 0) || (dx == 0 && dy == 1)) {
            // Valid move - start animation
            int tileValue = tiles[ty][tx];
            int tileWidthPx = areaWidth / GRID_WIDTH;
            int tileHeightPx = areaHeight / GRID_HEIGHT;
            
            movingTile.value = tileValue;
            movingTile.fromX = areaX + tx * tileWidthPx;
            movingTile.fromY = areaY + ty * tileHeightPx;
            movingTile.toX = areaX + emptyX * tileWidthPx;
            movingTile.toY = areaY + emptyY * tileHeightPx;
            movingTile.t = 0.0f;
            movingTile.active = true;
            
            // Swap (logic updates immediately)
            tiles[emptyY][emptyX] = tiles[ty][tx];
            tiles[ty][tx] = 0;
            emptyX = tx;
            emptyY = ty;
            moveCount++;
            
            // Play slide sound
            playLocalSound(SoundEvents.UI_LOOM_TAKE_RESULT, 0.3F, 1.4F);
            
            checkSolved();
        } else if (tiles[ty][tx] != 0) {
            // Invalid click - tile not adjacent to empty
            playLocalSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.2F, 0.8F);
        }
    }
    
    @Override
    public void handleKeyPress(int keyCode) {
        if (solved || movingTile.active) return;
        
        int newX = emptyX;
        int newY = emptyY;
        
        // Arrow keys: 265=UP, 264=DOWN, 263=LEFT, 262=RIGHT
        if (keyCode == 265) { // UP - move empty down (swap with tile below)
            newY++;
        } else if (keyCode == 264) { // DOWN - move empty up
            newY--;
        } else if (keyCode == 263) { // LEFT - move empty right
            newX++;
        } else if (keyCode == 262) { // RIGHT - move empty left
            newX--;
        } else {
            return;
        }
        
        if (newX >= 0 && newX < GRID_WIDTH && newY >= 0 && newY < GRID_HEIGHT) {
            // Valid move - start animation
            int tileValue = tiles[newY][newX];
            int tileWidthPx = areaWidth / GRID_WIDTH;
            int tileHeightPx = areaHeight / GRID_HEIGHT;
            
            movingTile.value = tileValue;
            movingTile.fromX = areaX + newX * tileWidthPx;
            movingTile.fromY = areaY + newY * tileHeightPx;
            movingTile.toX = areaX + emptyX * tileWidthPx;
            movingTile.toY = areaY + emptyY * tileHeightPx;
            movingTile.t = 0.0f;
            movingTile.active = true;
            
            // Swap (logic updates immediately)
            tiles[emptyY][emptyX] = tiles[newY][newX];
            tiles[newY][newX] = 0;
            emptyX = newX;
            emptyY = newY;
            moveCount++;
            
            // Play slide sound
            playLocalSound(SoundEvents.UI_LOOM_TAKE_RESULT, 0.3F, 1.4F);
            
            checkSolved();
        }
    }
    
    private void checkSolved() {
        int expected = 1;
        for (int y = 0; y < GRID_HEIGHT; y++) {
            for (int x = 0; x < GRID_WIDTH; x++) {
                if (y == GRID_HEIGHT - 1 && x == GRID_WIDTH - 1) {
                    if (tiles[y][x] != 0) {
                        return; // Last tile should be empty
                    }
                } else {
                    if (tiles[y][x] != expected++) {
                        return; // Not in order
                    }
                }
            }
        }
        if (!solved) {
            solved = true;
            winPulseTicks = 20; // Start sparkle effect
            playLocalSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.7F, 1.0F);
        }
    }
    
    @Override
    public void render(GuiGraphics g, net.minecraft.client.gui.Font font, int areaX, int areaY, int areaWidth, int areaHeight, float partialTicks) {
        this.areaX = areaX;
        this.areaY = areaY;
        this.areaWidth = areaWidth;
        this.areaHeight = areaHeight;
        
        // Draw board background
        g.fill(areaX, areaY, areaX + areaWidth, areaY + areaHeight, 0xFF2C2F38);
        
        int tileWidth = areaWidth / GRID_WIDTH;
        int tileHeight = areaHeight / GRID_HEIGHT;
        
        // Check which tile is hovered (if mouse is in area)
        int hoverX = -1, hoverY = -1;
        if (lastMouseX >= areaX && lastMouseX < areaX + areaWidth &&
            lastMouseY >= areaY && lastMouseY < areaY + areaHeight) {
            hoverX = (int) Math.floor((lastMouseX - areaX) / tileWidth);
            hoverY = (int) Math.floor((lastMouseY - areaY) / tileHeight);
        }
        
        // Calculate interpolated animation position with easing
        float currentAnimT = movingTile.active ? (movingTile.t + partialTicks * ANIM_SPEED) : 1.0f;
        if (currentAnimT > 1.0f) currentAnimT = 1.0f;
        float easedT = easeOutCubic(currentAnimT);
        float animPx = Mth.lerp(easedT, movingTile.fromX, movingTile.toX);
        float animPy = Mth.lerp(easedT, movingTile.fromY, movingTile.toY);
        
        // Find which tile is at the target position (to skip drawing it)
        int targetTileX = (int)Math.floor((movingTile.toX - areaX) / tileWidth);
        int targetTileY = (int)Math.floor((movingTile.toY - areaY) / tileHeight);
        
        for (int y = 0; y < GRID_HEIGHT; y++) {
            for (int x = 0; x < GRID_WIDTH; x++) {
                int value = tiles[y][x];
                
                // Skip drawing the tile that's currently animating (we'll draw it separately)
                if (movingTile.active && value == movingTile.value && 
                    targetTileX == x && targetTileY == y) {
                    continue;
                }
                
                int px = areaX + x * tileWidth;
                int py = areaY + y * tileHeight;
                
                // Check if this tile can move (adjacent to empty)
                boolean canMove = !solved && value != 0 && !movingTile.active &&
                    ((Math.abs(x - emptyX) == 1 && y == emptyY) || 
                     (Math.abs(y - emptyY) == 1 && x == emptyX));
                
                // Check if hovered
                boolean isHovered = hoverX == x && hoverY == y && canMove;
                
                // Check if pressed
                boolean isPressed = tilePressed && pressedTileX == x && pressedTileY == y;
                
                if (value == 0) {
                    // Empty tile - darker "hole" with slight bevel
                    drawEmptyTile(g, px, py, tileWidth, tileHeight);
                } else {
                    drawTile(g, font, px, py, tileWidth, tileHeight, value, isHovered, isPressed);
                }
            }
        }
        
        // Draw animating tile at interpolated position
        if (movingTile.active) {
            drawTile(g, font, (int)animPx, (int)animPy, tileWidth, tileHeight, movingTile.value, false, false);
        }
        
        // Draw win sparkle overlay
        if (winPulseTicks > 0) {
            float alphaT = winPulseTicks / 20.0f;
            int alpha = (int)(alphaT * 120) & 0xFF;
            int overlayColor = (alpha << 24) | 0xFFFFFF; // white with fading alpha
            
            // Draw a few random sparkle lines/plus shapes
            for (int i = 0; i < 5; i++) {
                int sparkleX = areaX + random.nextInt(areaWidth);
                int sparkleY = areaY + random.nextInt(areaHeight);
                int sparkleSize = 3;
                
                // Small plus shape
                g.fill(sparkleX - sparkleSize, sparkleY, sparkleX + sparkleSize, sparkleY + 1, overlayColor);
                g.fill(sparkleX, sparkleY - sparkleSize, sparkleX + 1, sparkleY + sparkleSize, overlayColor);
            }
        }
        
        // Draw "Solved!" text at bottom if solved
        if (solved) {
            String solvedText = "Solved!";
            int textX = areaX + areaWidth / 2 - font.width(solvedText) / 2;
            int textY = areaY + areaHeight - font.lineHeight - 4;
            g.drawString(font, Component.literal(solvedText), textX, textY, 0xFFCCCCCC, true);
        }
    }
    
    private void drawTile(GuiGraphics g, Font font, int px, int py, int tileWidth, int tileHeight, int value, boolean isHovered, boolean isPressed) {
        // Tile colors - higher contrast wooden look
        int tileBase = 0xFFE7D2AE; // outer base
        int tileInner = 0xFFF8ECD4; // lighter center inset
        int tileBorder = 0xFFB0844C; // warm brown border
        
        // Darken if pressed
        if (isPressed) {
            tileBase = darkenColor(tileBase, 0.9f);
            tileInner = darkenColor(tileInner, 0.9f);
        }
        
        // Draw order: Fill full tile rect with base color
        g.fill(px, py, px + tileWidth, py + tileHeight, tileBase);
        
        // Draw 1px border around with warm brown
        g.fill(px, py, px + tileWidth, py + 1, tileBorder); // Top
        g.fill(px, py + tileHeight - 1, px + tileWidth, py + tileHeight, tileBorder); // Bottom
        g.fill(px, py, px + 1, py + tileHeight, tileBorder); // Left
        g.fill(px + tileWidth - 1, py, px + tileWidth, py + tileHeight, tileBorder); // Right
        
        // Draw inner inset (1px inside all sides) with lighter color
        int insetPad = 1;
        g.fill(px + insetPad, py + insetPad, 
            px + tileWidth - insetPad, py + tileHeight - insetPad, tileInner);
        
        // Hover outline - 2px outline in light blue accent (only if hovered)
        if (isHovered) {
            int hoverColor = 0xFF5AC8FA; // light blue accent
            int hoverThickness = 2;
            // Top
            g.fill(px, py, px + tileWidth, py + hoverThickness, hoverColor);
            // Bottom
            g.fill(px, py + tileHeight - hoverThickness, px + tileWidth, py + tileHeight, hoverColor);
            // Left
            g.fill(px, py, px + hoverThickness, py + tileHeight, hoverColor);
            // Right
            g.fill(px + tileWidth - hoverThickness, py, px + tileWidth, py + tileHeight, hoverColor);
        }
        
        // Draw number - solid dark text with shadow, centered
        String numStr = String.valueOf(value);
        int textWidth = font.width(numStr);
        int textX = px + (tileWidth - textWidth) / 2;
        int textY = py + (tileHeight - font.lineHeight) / 2;
        int numberColor = 0xFF111111; // almost black
        g.drawString(font, Component.literal(numStr), textX, textY, numberColor, true); // shadow = true
    }
    
    private void drawEmptyTile(GuiGraphics g, int px, int py, int tileWidth, int tileHeight) {
        // Empty tile - clearly darker "hole"
        int emptyBase = 0xFF2A2A32;
        int emptyBorder = 0xFF18181E;
        
        // Fill full tile rect with base color
        g.fill(px, py, px + tileWidth, py + tileHeight, emptyBase);
        
        // Draw 1px border (same thickness as numbered tiles for consistency)
        g.fill(px, py, px + tileWidth, py + 1, emptyBorder); // Top
        g.fill(px, py + tileHeight - 1, px + tileWidth, py + tileHeight, emptyBorder); // Bottom
        g.fill(px, py, px + 1, py + tileHeight, emptyBorder); // Left
        g.fill(px + tileWidth - 1, py, px + tileWidth, py + tileHeight, emptyBorder); // Right
    }
    
    private int darkenColor(int color, float factor) {
        int r = (int)(((color >> 16) & 0xFF) * factor);
        int g = (int)(((color >> 8) & 0xFF) * factor);
        int b = (int)((color & 0xFF) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
    
    private static float easeOutCubic(float t) {
        t = 1.0f - t;
        return 1.0f - t * t * t;
    }
    
    /**
     * Play a sound effect locally (client-side only).
     */
    private void playLocalSound(SoundEvent event, float volume, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(event, volume, pitch);
        }
    }
    
    // Helper method to update mouse position (called from screen)
    public void updateMouse(double mouseX, double mouseY) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
    }
    
    @Override
    public String getTitle() {
        return "Slide Puzzle";
    }
    
    @Override
    public boolean isGameOver() {
        return solved;
    }
    
    @Override
    public int getScore() {
        return moveCount;
    }
    
    @Override
    public void onClose() {
        // No cleanup needed
    }
}


