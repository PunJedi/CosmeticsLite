package com.pastlands.cosmeticslite.minigame.impl.minesweeper;

import com.pastlands.cosmeticslite.minigame.api.MiniGame;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

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
        // No continuous updates needed
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
            
            if (cell.isMine) {
                cell.isRevealed = true;
                gameOver = true;
                win = false;
            } else {
                revealCell(cellX, cellY);
                checkWin();
            }
        } else if (button == 1) { // Right click
            if (!cell.isRevealed) {
                cell.isFlagged = !cell.isFlagged;
            }
        }
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
        
        for (int y = 0; y < GRID_HEIGHT; y++) {
            for (int x = 0; x < GRID_WIDTH; x++) {
                int px = areaX + x * cellWidth;
                int py = areaY + y * cellHeight;
                Cell cell = grid[y][x];
                
                boolean isHovered = (hoveredCellX == x && hoveredCellY == y);
                
                if (!cell.isRevealed) {
                    // Hidden tile - darker gray, raised appearance
                    g.fill(px, py, px + cellWidth, py + cellHeight, 0xFF808080); // Border
                    g.fill(px + 1, py + 1, px + cellWidth - 1, py + cellHeight - 1, 0xFFB0B0B0); // Slightly darker base
                    g.fill(px + 1, py + 1, px + cellWidth - 2, py + cellHeight - 2, 0xFFC0C0C0); // Inner light highlight
                    
                    // Hover highlight for unopened tiles
                    if (isHovered) {
                        g.fill(px + 1, py + 1, px + cellWidth - 1, py + cellHeight - 1, 0x20FFFFFF);
                    }
                    
                    if (cell.isFlagged) {
                        // Draw flag - pole at bottom-left, triangle flag on top
                        int poleX = px + 3; // Left side
                        int poleBottom = py + cellHeight - 3;
                        int poleTop = py + 4;
                        
                        // Flag pole (vertical line)
                        g.fill(poleX, poleTop, poleX + 1, poleBottom, 0xFF000000);
                        
                        // Triangle flag (red, pointing right)
                        int flagSize = cellWidth / 3;
                        int flagStartX = poleX + 1;
                        int flagStartY = poleTop;
                        
                        for (int i = 0; i < flagSize && flagStartX + i < px + cellWidth - 2; i++) {
                            for (int j = 0; j <= i && flagStartY + j < poleTop + flagSize; j++) {
                                g.fill(flagStartX + i, flagStartY + j, flagStartX + i + 1, flagStartY + j + 1, 0xFFFF0000);
                            }
                        }
                        
                        // Highlight flagged tiles if win
                        if (win) {
                            g.fill(px + 1, py + 1, px + cellWidth - 1, py + cellHeight - 1, 0x4000FF00);
                        }
                    }
                } else {
                    // Revealed cell - lighter with subtle inner inset (pressed look)
                    g.fill(px, py, px + cellWidth, py + cellHeight, 0xFF808080); // Border
                    g.fill(px + 1, py + 1, px + cellWidth - 1, py + cellHeight - 1, 0xFFE8E8E8); // Lighter base
                    // Inner inset for pressed effect
                    g.fill(px + 1, py + 1, px + cellWidth - 2, py + cellHeight - 2, 0xFFD8D8D8);
                    
                    if (cell.isMine) {
                        // Mine - dark circle with cross
                        int centerX = px + cellWidth / 2;
                        int centerY = py + cellHeight / 2;
                        int radius = Math.min(cellWidth, cellHeight) / 3;
                        
                        // Draw circle
                        for (int dy = -radius; dy <= radius; dy++) {
                            for (int dx = -radius; dx <= radius; dx++) {
                                if (dx * dx + dy * dy <= radius * radius) {
                                    g.fill(centerX + dx, centerY + dy, centerX + dx + 1, centerY + dy + 1, 0xFF000000);
                                }
                            }
                        }
                        // Cross
                        g.fill(centerX - radius, centerY, centerX + radius, centerY + 1, 0xFFFFFFFF);
                        g.fill(centerX, centerY - radius, centerX + 1, centerY + radius, 0xFFFFFFFF);
                    } else if (cell.adjacentMines > 0) {
                        int color = getNumberColor(cell.adjacentMines);
                        // Draw number centered in tile
                        String numStr = String.valueOf(cell.adjacentMines);
                        int numX = px + cellWidth / 2 - font.width(numStr) / 2;
                        int numY = py + cellHeight / 2 - font.lineHeight / 2;
                        g.drawString(font, Component.literal(numStr), numX, numY, color, true);
                    }
                }
                
                // Grid lines
                g.fill(px + cellWidth - 1, py, px + cellWidth, py + cellHeight, 0xFF000000);
                g.fill(px, py + cellHeight - 1, px + cellWidth, py + cellHeight, 0xFF000000);
            }
        }
    }
    
    private int getNumberColor(int num) {
        // Classic Minesweeper number colors
        return switch (num) {
            case 1 -> 0xFF0000FF; // Blue
            case 2 -> 0xFF008000; // Green
            case 3 -> 0xFFFF0000; // Red
            case 4 -> 0xFF000080; // Dark blue
            case 5 -> 0xFF800000; // Dark red
            case 6 -> 0xFF008080; // Teal (cyan)
            case 7 -> 0xFF000000; // Black
            case 8 -> 0xFF808080; // Gray
            default -> 0xFF000000;
        };
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

