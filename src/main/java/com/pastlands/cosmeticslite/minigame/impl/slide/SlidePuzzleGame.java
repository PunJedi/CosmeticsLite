package com.pastlands.cosmeticslite.minigame.impl.slide;

import com.pastlands.cosmeticslite.minigame.api.MiniGame;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

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
    private boolean isAnimating = false;
    private int animTileValue = 0;
    private float animFromX = 0, animFromY = 0;
    private float animToX = 0, animToY = 0;
    private float animT = 0.0f;
    private static final float ANIM_SPEED = 0.15f; // Animation speed per tick
    
    @Override
    public void initGame() {
        random = new Random();
        solved = false;
        moveCount = 0;
        isAnimating = false;
        animT = 0.0f;
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
        if (isAnimating) {
            animT += ANIM_SPEED;
            if (animT >= 1.0f) {
                animT = 1.0f;
                isAnimating = false;
            }
        }
    }
    
    @Override
    public void handleMouseClick(double mouseX, double mouseY, int button) {
        if (solved || button != 0) return; // Only left click
        
        double tileWidth = (double) areaWidth / GRID_WIDTH;
        double tileHeight = (double) areaHeight / GRID_HEIGHT;
        
        int tx = (int) Math.floor((mouseX - areaX) / tileWidth);
        int ty = (int) Math.floor((mouseY - areaY) / tileHeight);
        
        if (tx < 0 || tx >= GRID_WIDTH || ty < 0 || ty >= GRID_HEIGHT) {
            return;
        }
        
        // Check if clicked tile is adjacent to empty
        int dx = Math.abs(tx - emptyX);
        int dy = Math.abs(ty - emptyY);
        
        if ((dx == 1 && dy == 0) || (dx == 0 && dy == 1)) {
            // Start animation
            animTileValue = tiles[ty][tx];
            animFromX = tx;
            animFromY = ty;
            animToX = emptyX;
            animToY = emptyY;
            animT = 0.0f;
            isAnimating = true;
            
            // Swap (logic updates immediately)
            tiles[emptyY][emptyX] = tiles[ty][tx];
            tiles[ty][tx] = 0;
            emptyX = tx;
            emptyY = ty;
            moveCount++;
            
            checkSolved();
        }
    }
    
    @Override
    public void handleKeyPress(int keyCode) {
        if (solved) return;
        
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
            // Start animation
            animTileValue = tiles[newY][newX];
            animFromX = newX;
            animFromY = newY;
            animToX = emptyX;
            animToY = emptyY;
            animT = 0.0f;
            isAnimating = true;
            
            // Swap (logic updates immediately)
            tiles[emptyY][emptyX] = tiles[newY][newX];
            tiles[newY][newX] = 0;
            emptyX = newX;
            emptyY = newY;
            moveCount++;
            
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
        solved = true;
    }
    
    @Override
    public void render(GuiGraphics g, net.minecraft.client.gui.Font font, int areaX, int areaY, int areaWidth, int areaHeight, float partialTicks) {
        this.areaX = areaX;
        this.areaY = areaY;
        this.areaWidth = areaWidth;
        this.areaHeight = areaHeight;
        
        int tileWidth = areaWidth / GRID_WIDTH;
        int tileHeight = areaHeight / GRID_HEIGHT;
        
        // Check which tile is hovered (if mouse is in area)
        int hoverX = -1, hoverY = -1;
        if (lastMouseX >= areaX && lastMouseX < areaX + areaWidth &&
            lastMouseY >= areaY && lastMouseY < areaY + areaHeight) {
            hoverX = (int) Math.floor((lastMouseX - areaX) / tileWidth);
            hoverY = (int) Math.floor((lastMouseY - areaY) / tileHeight);
        }
        
        // Calculate interpolated animation position
        float currentAnimT = isAnimating ? (animT + partialTicks * ANIM_SPEED) : 1.0f;
        if (currentAnimT > 1.0f) currentAnimT = 1.0f;
        float animX = animFromX + (animToX - animFromX) * currentAnimT;
        float animY = animFromY + (animToY - animFromY) * currentAnimT;
        
        // Success tint if solved
        int successTint = solved ? 0x2000FF00 : 0x00000000;
        
        for (int y = 0; y < GRID_HEIGHT; y++) {
            for (int x = 0; x < GRID_WIDTH; x++) {
                int value = tiles[y][x];
                
                // Skip drawing the tile that's currently animating (we'll draw it separately)
                if (isAnimating && value == animTileValue && 
                    (int)animToX == x && (int)animToY == y) {
                    continue;
                }
                
                int px = areaX + x * tileWidth;
                int py = areaY + y * tileHeight;
                
                // Check if this tile can move (adjacent to empty)
                boolean canMove = !solved && value != 0 && 
                    ((Math.abs(x - emptyX) == 1 && y == emptyY) || 
                     (Math.abs(y - emptyY) == 1 && x == emptyX));
                
                // Check if hovered
                boolean isHovered = hoverX == x && hoverY == y && canMove;
                
                if (value == 0) {
                    // Empty tile - dark slate color
                    g.fill(px, py, px + tileWidth, py + tileHeight, 0xFF303030);
                } else {
                    drawTile(g, font, px, py, tileWidth, tileHeight, value, isHovered, successTint != 0);
                }
                
                // Grid lines
                g.fill(px + tileWidth - 1, py, px + tileWidth, py + tileHeight, 0xFF000000);
                g.fill(px, py + tileHeight - 1, px + tileWidth, py + tileHeight, 0xFF000000);
            }
        }
        
        // Draw animating tile at interpolated position
        if (isAnimating) {
            float animPx = areaX + animX * tileWidth;
            float animPy = areaY + animY * tileHeight;
            drawTile(g, font, (int)animPx, (int)animPy, tileWidth, tileHeight, animTileValue, false, successTint != 0);
        }
        
        // Draw "Solved!" text at bottom if solved
        if (solved) {
            String solvedText = "Solved!";
            int textX = areaX + areaWidth / 2 - font.width(solvedText) / 2;
            int textY = areaY + areaHeight - font.lineHeight - 4;
            g.drawString(font, Component.literal(solvedText), textX, textY, 0xFFCCCCCC, true);
        }
    }
    
    private void drawTile(GuiGraphics g, Font font, int px, int py, int tileWidth, int tileHeight, int value, boolean isHovered, boolean isSolved) {
        // Outer border
        g.fill(px, py, px + tileWidth, py + tileHeight, 0xFF606060);
        
        // Inner shadow/border for physical tile look
        int innerPad = 3;
        int tileColor = 0xFFE0E0E0;
        if (isSolved) {
            // Apply success tint
            int r = (tileColor >> 16) & 0xFF;
            int gr = ((tileColor >> 8) & 0xFF) + 20;
            int b = (tileColor & 0xFF);
            tileColor = 0xFF000000 | (r << 16) | (Math.min(255, gr) << 8) | b;
        }
        
        // Base tile fill
        g.fill(px + innerPad, py + innerPad, 
            px + tileWidth - innerPad, py + tileHeight - innerPad, tileColor);
        
        // Inner highlight for 3D effect (lighter on top-left)
        int highlightPad = innerPad + 1;
        g.fill(px + highlightPad, py + highlightPad, 
            px + tileWidth - innerPad, py + highlightPad + 2, 0x30FFFFFF);
        g.fill(px + highlightPad, py + highlightPad, 
            px + highlightPad + 2, py + tileHeight - innerPad, 0x30FFFFFF);
        
        // Hover highlight
        if (isHovered) {
            g.fill(px + innerPad, py + innerPad, 
                px + tileWidth - innerPad, py + tileHeight - innerPad, 0x40FFFFFF);
        }
        
        // Draw number - black with shadow for readability
        String numStr = String.valueOf(value);
        int numX = px + tileWidth / 2 - font.width(numStr) / 2;
        int numY = py + tileHeight / 2 - font.lineHeight / 2;
        g.drawString(font, Component.literal(numStr), numX, numY, 0xFF000000, true);
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

