package com.pastlands.cosmeticslite.minigame.impl.minesweeper;

import com.pastlands.cosmeticslite.minigame.api.MiniGame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.Random;

/**
 * Minesweeper mini-game implementation.
 */
public class MinesweeperGame implements MiniGame {
    
    private static final int GRID_WIDTH = 9;
    private static final int GRID_HEIGHT = 9;
    private static final int MINE_COUNT = 10;
    
    private Cell[][] grid;
    private boolean gameOver;
    private boolean win;
    private Random random;
    private int areaX, areaY, areaWidth, areaHeight; // Store for mouse calculations
    
    // Visual-only hover tracking
    private int hoveredCellX = -1;
    private int hoveredCellY = -1;
    private boolean mousePressed = false;
    private int pressedCellX = -1;
    private int pressedCellY = -1;
    
    // Animation state
    private int explosionFlashTicks = 0;
    private int explodedCellX = -1;
    private int explodedCellY = -1;
    
    // Time tracking
    private int elapsedTicks = 0;
    
    // Flag count tracking
    private int flagCount = 0;
    
    private static class Cell {
        boolean isMine;
        boolean isRevealed;
        boolean isFlagged;
        int adjacentMines;
    }
    
    @Override
    public void initGame() {
        random = new Random();
        gameOver = false;
        win = false;
        grid = new Cell[GRID_HEIGHT][GRID_WIDTH];
        elapsedTicks = 0;
        flagCount = 0;
        explosionFlashTicks = 0;
        explodedCellX = -1;
        explodedCellY = -1;
        mousePressed = false;
        pressedCellX = -1;
        pressedCellY = -1;
        
        // Initialize grid
        for (int y = 0; y < GRID_HEIGHT; y++) {
            for (int x = 0; x < GRID_WIDTH; x++) {
                grid[y][x] = new Cell();
            }
        }
        
        // Place mines
        int placed = 0;
        while (placed < MINE_COUNT) {
            int x = random.nextInt(GRID_WIDTH);
            int y = random.nextInt(GRID_HEIGHT);
            if (!grid[y][x].isMine) {
                grid[y][x].isMine = true;
                placed++;
            }
        }
        
        // Calculate adjacent mines
        for (int y = 0; y < GRID_HEIGHT; y++) {
            for (int x = 0; x < GRID_WIDTH; x++) {
                if (!grid[y][x].isMine) {
                    int count = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dy == 0) continue;
                            int nx = x + dx;
                            int ny = y + dy;
                            if (nx >= 0 && nx < GRID_WIDTH && ny >= 0 && ny < GRID_HEIGHT) {
                                if (grid[ny][nx].isMine) count++;
                            }
                        }
                    }
                    grid[y][x].adjacentMines = count;
                }
            }
        }
    }
    
    @Override
    public void tick() {
        // Update time while game is active
        if (!gameOver && !win) {
            elapsedTicks++;
        }
        
        // Update explosion flash
        if (explosionFlashTicks > 0) {
            explosionFlashTicks--;
        }
    }
    
    @Override
    public void handleMouseClick(double mouseX, double mouseY, int button) {
        if (gameOver) return;
        
        double cellWidth = (double) areaWidth / GRID_WIDTH;
        double cellHeight = (double) areaHeight / GRID_HEIGHT;
        
        int cellX = (int) Math.floor((mouseX - areaX) / cellWidth);
        int cellY = (int) Math.floor((mouseY - areaY) / cellHeight);
        
        if (cellX < 0 || cellX >= GRID_WIDTH || cellY < 0 || cellY >= GRID_HEIGHT) {
            return;
        }
        
        Cell cell = grid[cellY][cellX];
        
        if (button == 0) { // Left click
            if (cell.isFlagged || cell.isRevealed) return;
            
            // Track pressed state for visual feedback
            mousePressed = true;
            pressedCellX = cellX;
            pressedCellY = cellY;
            
            if (cell.isMine) {
                cell.isRevealed = true;
                gameOver = true;
                win = false;
                explodedCellX = cellX;
                explodedCellY = cellY;
                explosionFlashTicks = 1; // One frame flash
                playLocalSound(SoundEvents.GENERIC_EXPLODE, 0.6F, 1.2F);
                playLocalSound(SoundEvents.PLAYER_HURT, 0.4F, 0.8F);
            } else {
                // Play reveal sound only once per click (before flood fill)
                playLocalSound(SoundEvents.UI_STONECUTTER_TAKE_RESULT, 0.25F, 1.4F);
                revealCell(cellX, cellY);
                checkWin();
            }
        } else if (button == 1) { // Right click
            if (!cell.isRevealed) {
                cell.isFlagged = !cell.isFlagged;
                
                // Update flag count
                if (cell.isFlagged) {
                    flagCount++;
                    playLocalSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.3F, 1.2F);
                } else {
                    flagCount--;
                    playLocalSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.3F, 0.9F);
                }
            }
        }
        
        // Reset pressed state after a short delay
        mousePressed = false;
        pressedCellX = -1;
        pressedCellY = -1;
    }
    
    private void revealCell(int x, int y) {
        if (x < 0 || x >= GRID_WIDTH || y < 0 || y >= GRID_HEIGHT) return;
        
        Cell cell = grid[y][x];
        if (cell.isRevealed || cell.isFlagged) return;
        
        cell.isRevealed = true;
        
        // Flood fill if no adjacent mines
        if (cell.adjacentMines == 0) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    revealCell(x + dx, y + dy);
                }
            }
        }
    }
    
    private void checkWin() {
        for (int y = 0; y < GRID_HEIGHT; y++) {
            for (int x = 0; x < GRID_WIDTH; x++) {
                Cell cell = grid[y][x];
                if (!cell.isMine && !cell.isRevealed) {
                    return; // Not all safe cells revealed
                }
            }
        }
        // All safe cells revealed
        gameOver = true;
        win = true;
        playLocalSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.7F, 1.0F);
    }
    
    @Override
    public void handleKeyPress(int keyCode) {
        // Not used
    }
    
    @Override
    public void render(GuiGraphics g, net.minecraft.client.gui.Font font, int areaX, int areaY, int areaWidth, int areaHeight, float partialTicks) {
        this.areaX = areaX;
        this.areaY = areaY;
        this.areaWidth = areaWidth;
        this.areaHeight = areaHeight;
        
        int cellWidth = areaWidth / GRID_WIDTH;
        int cellHeight = areaHeight / GRID_HEIGHT;
        
        // Draw HUD at top-left
        int seconds = elapsedTicks / 20;
        int minutes = seconds / 60;
        seconds = seconds % 60;
        String timeStr = String.format("%02d:%02d", minutes, seconds);
        String hudText = "Mines: " + MINE_COUNT + "   Flags: " + flagCount + "   Time: " + timeStr;
        g.drawString(font, Component.literal(hudText), areaX + 4, areaY + 4, 0xFFFFFF, true);
        
        for (int y = 0; y < GRID_HEIGHT; y++) {
            for (int x = 0; x < GRID_WIDTH; x++) {
                int px = areaX + x * cellWidth;
                int py = areaY + y * cellHeight;
                Cell cell = grid[y][x];
                
                boolean isHovered = (hoveredCellX == x && hoveredCellY == y);
                boolean isPressed = (mousePressed && pressedCellX == x && pressedCellY == y);
                boolean isExploded = (explodedCellX == x && explodedCellY == y);
                
                if (!cell.isRevealed) {
                    // Hidden tile - dark slate
                    int baseColor = isPressed ? 0xFF303035 : 0xFF3A3A3F;
                    g.fill(px, py, px + cellWidth, py + cellHeight, 0x40202020); // Border
                    g.fill(px + 1, py + 1, px + cellWidth - 1, py + cellHeight - 1, baseColor);
                    
                    // Subtle inner bevel
                    g.fill(px + 1, py + 1, px + cellWidth - 1, py + 2, 0x20FFFFFF); // Highlight at top
                    g.fill(px + 1, py + cellHeight - 2, px + cellWidth - 1, py + cellHeight - 1, 0x20000000); // Shadow at bottom
                    
                    // Hover outline for hidden, unflagged tiles
                    if (isHovered && !cell.isFlagged) {
                        int hoverColor = 0x80FFFFFF; // semi-transparent white
                        g.fill(px + 1, py + 1, px + cellWidth - 1, py + 2, hoverColor); // Top
                        g.fill(px + 1, py + cellHeight - 2, px + cellWidth - 1, py + cellHeight - 1, hoverColor); // Bottom
                        g.fill(px + 1, py + 1, px + 2, py + cellHeight - 1, hoverColor); // Left
                        g.fill(px + cellWidth - 2, py + 1, px + cellWidth - 1, py + cellHeight - 1, hoverColor); // Right
                    }
                    
                    if (cell.isFlagged) {
                        // Draw flag - pole and flag cloth
                        int poleX = px + 3;
                        int poleTop = py + 4;
                        int poleBottom = py + cellHeight - 3;
                        
                        // Flag pole (thin dark vertical line)
                        g.fill(poleX, poleTop, poleX + 1, poleBottom, 0xFF000000);
                        
                        // Flag cloth (red rectangle)
                        int flagLeft = poleX + 1;
                        int flagRight = px + cellWidth - 4;
                        int flagTop = poleTop;
                        int flagBottom = py + 10;
                        g.fill(flagLeft, flagTop, flagRight, flagBottom, 0xFFE53935);
                    }
                } else {
                    // Revealed cell - soft light gray
                    boolean isFlash = (isExploded && explosionFlashTicks > 0);
                    int baseColor = isFlash ? 0xFFFFFFFF : 0xFFE2E2E2;
                    
                    g.fill(px, py, px + cellWidth, py + cellHeight, 0x40202020); // Border
                    g.fill(px + 1, py + 1, px + cellWidth - 1, py + cellHeight - 1, baseColor);
                    
                    // Subtle inner bevel
                    g.fill(px + 1, py + 1, px + cellWidth - 1, py + 2, 0x20FFFFFF); // Highlight at top
                    g.fill(px + 1, py + cellHeight - 2, px + cellWidth - 1, py + cellHeight - 1, 0x20000000); // Shadow at bottom
                    
                    // Explosion highlight border for exploded tile
                    if (isExploded && !isFlash) {
                        int highlightThickness = 2;
                        g.fill(px, py, px + cellWidth, py + highlightThickness, 0xFFFF4444); // Top
                        g.fill(px, py + cellHeight - highlightThickness, px + cellWidth, py + cellHeight, 0xFFFF4444); // Bottom
                        g.fill(px, py, px + highlightThickness, py + cellHeight, 0xFFFF4444); // Left
                        g.fill(px + cellWidth - highlightThickness, py, px + cellWidth, py + cellHeight, 0xFFFF4444); // Right
                    }
                    
                    if (cell.isMine) {
                        // Bomb tile - dark red-brown background
                        g.fill(px + 1, py + 1, px + cellWidth - 1, py + cellHeight - 1, 0xFF2B1B1B);
                        
                        // Bomb circle
                        int centerX = px + cellWidth / 2;
                        int centerY = py + cellHeight / 2;
                        int radius = Math.min(cellWidth, cellHeight) / 4;
                        
                        for (int dy = -radius; dy <= radius; dy++) {
                            for (int dx = -radius; dx <= radius; dx++) {
                                if (dx * dx + dy * dy <= radius * radius) {
                                    g.fill(centerX + dx, centerY + dy, centerX + dx + 1, centerY + dy + 1, 0xFF000000);
                                }
                            }
                        }
                        
                        // Tiny fuse
                        g.fill(centerX, py + 3, centerX + 1, py + 6, 0xFFFFD54F);
                    } else if (cell.adjacentMines > 0) {
                        int color = getNumberColor(cell.adjacentMines);
                        String numStr = String.valueOf(cell.adjacentMines);
                        int textWidth = font.width(numStr);
                        int textX = px + (cellWidth - textWidth) / 2;
                        int textY = py + (cellHeight - font.lineHeight) / 2;
                        g.drawString(font, Component.literal(numStr), textX, textY, color, true);
                    }
                }
                
                // Subtle grid lines
                g.fill(px + cellWidth - 1, py, px + cellWidth, py + cellHeight, 0x40202020);
                g.fill(px, py + cellHeight - 1, px + cellWidth, py + cellHeight, 0x40202020);
            }
        }
    }
    
    private int getNumberColor(int num) {
        // Classic Minesweeper number colors
        return switch (num) {
            case 1 -> 0xFF1976D2; // Blue
            case 2 -> 0xFF388E3C; // Green
            case 3 -> 0xFFD32F2F; // Red
            case 4 -> 0xFF7B1FA2; // Purple
            case 5 -> 0xFFF57C00; // Orange
            case 6 -> 0xFF00838F; // Teal
            case 7 -> 0xFF000000; // Black
            case 8 -> 0xFF808080; // Gray
            default -> 0xFF00838F; // Teal for 6+
        };
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
    
    // Update hovered cell from mouse position (called by MiniGamePlayScreen)
    public void updateMouse(double mouseX, double mouseY) {
        if (areaWidth == 0 || areaHeight == 0) {
            hoveredCellX = -1;
            hoveredCellY = -1;
            return;
        }
        
        double cellWidth = (double) areaWidth / GRID_WIDTH;
        double cellHeight = (double) areaHeight / GRID_HEIGHT;
        
        int cellX = (int) Math.floor((mouseX - areaX) / cellWidth);
        int cellY = (int) Math.floor((mouseY - areaY) / cellHeight);
        
        if (cellX >= 0 && cellX < GRID_WIDTH && cellY >= 0 && cellY < GRID_HEIGHT) {
            hoveredCellX = cellX;
            hoveredCellY = cellY;
        } else {
            hoveredCellX = -1;
            hoveredCellY = -1;
        }
    }
    
    @Override
    public String getTitle() {
        return "Mini Minesweeper";
    }
    
    @Override
    public boolean isGameOver() {
        return gameOver;
    }
    
    @Override
    public int getScore() {
        if (win) {
            int revealed = 0;
            for (int y = 0; y < GRID_HEIGHT; y++) {
                for (int x = 0; x < GRID_WIDTH; x++) {
                    if (grid[y][x].isRevealed && !grid[y][x].isMine) {
                        revealed++;
                    }
                }
            }
            return revealed;
        }
        return 0;
    }
    
    @Override
    public void onClose() {
        // No cleanup needed
    }
}

