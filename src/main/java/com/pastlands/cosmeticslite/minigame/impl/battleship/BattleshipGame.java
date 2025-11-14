package com.pastlands.cosmeticslite.minigame.impl.battleship;

import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.minigame.api.MiniGame;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Battleship mini-game implementation (Player vs CPU).
 */
public class BattleshipGame implements MiniGame {
    
    private static final int BOARD_SIZE = 10;
    private static final int[] SHIP_LENGTHS = {5, 4, 3, 3, 2}; // Standard fleet
    
    private enum Phase {
        PLACING,
        PLAYER_TURN,
        CPU_TURN,
        GAME_OVER
    }
    
    private enum CellState {
        UNKNOWN,    // Not yet fired upon
        MISS,       // Fired, no ship
        HIT,        // Fired, hit ship
        SHIP_VISIBLE // For player board rendering (shows ship)
    }
    
    private Phase phase;
    private CellState[][] playerGrid;  // Player's board (shows ships)
    private CellState[][] cpuGrid;     // CPU's board (hides ships, shows hits/misses)
    private boolean[][] playerShips;   // Where player ships are
    private boolean[][] cpuShips;      // Where CPU ships are
    
    private int currentShipIndex;      // Which ship is being placed (0-4)
    private int placementX, placementY; // Cursor position for ship placement
    private boolean placementHorizontal; // Ship orientation
    
    private Random random;
    private int areaX, areaY, areaWidth, areaHeight;
    private String statusMessage;
    private boolean playerWon;
    
    // Visual-only hover tracking
    private int hoveredCellX = -1;
    private int hoveredCellY = -1;
    private boolean hoveredOnEnemyBoard = false;
    
    // CPU targeting state
    private int lastHitX = -1, lastHitY = -1;
    private List<int[]> cpuTargetQueue; // Queue of cells to try after a hit
    
    @Override
    public void initGame() {
        random = new Random();
        phase = Phase.PLACING;
        currentShipIndex = 0;
        placementX = 0;
        placementY = 0;
        placementHorizontal = true;
        playerWon = false;
        statusMessage = "Place ship 1 (length " + SHIP_LENGTHS[0] + "). " + 
            (placementHorizontal ? "Horizontal" : "Vertical") + ". Press Q/E to rotate.";
        
        playerGrid = new CellState[BOARD_SIZE][BOARD_SIZE];
        cpuGrid = new CellState[BOARD_SIZE][BOARD_SIZE];
        playerShips = new boolean[BOARD_SIZE][BOARD_SIZE];
        cpuShips = new boolean[BOARD_SIZE][BOARD_SIZE];
        
        // Initialize grids
        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                playerGrid[y][x] = CellState.UNKNOWN;
                cpuGrid[y][x] = CellState.UNKNOWN;
            }
        }
        
        cpuTargetQueue = new ArrayList<>();
        lastHitX = -1;
        lastHitY = -1;
    }
    
    @Override
    public void tick() {
        // No continuous updates needed
    }
    
    @Override
    public void handleMouseClick(double mouseX, double mouseY, int button) {
        // Calculate cell size (same as in render)
        int availableHeight = areaHeight - 60;
        int availableWidth = areaWidth - 40;
        int cellSize = Math.min(availableWidth / (BOARD_SIZE * 2 + 2), availableHeight / BOARD_SIZE);
        cellSize = Math.max(cellSize, 6);
        int boardWidth = BOARD_SIZE * cellSize;
        int boardHeight = BOARD_SIZE * cellSize;
        
        int playerBoardX = areaX + areaWidth / 4 - boardWidth / 2;
        int playerBoardY = areaY + 50 + (availableHeight - boardHeight) / 2;
        int cpuBoardX = areaX + 3 * areaWidth / 4 - boardWidth / 2;
        int cpuBoardY = areaY + 50 + (availableHeight - boardHeight) / 2;
        
        if (phase == Phase.PLACING) {
            // Map click to player board for ship placement
            int cellX = (int) Math.floor((mouseX - playerBoardX) / cellSize);
            int cellY = (int) Math.floor((mouseY - playerBoardY) / cellSize);
            
            if (cellX >= 0 && cellX < BOARD_SIZE && cellY >= 0 && cellY < BOARD_SIZE) {
                placementX = cellX;
                placementY = cellY;
                
                if (button == 0) { // Left click to place
                    if (canPlaceShip(placementX, placementY, SHIP_LENGTHS[currentShipIndex], placementHorizontal)) {
                        placeShip(placementX, placementY, SHIP_LENGTHS[currentShipIndex], placementHorizontal, true);
                        currentShipIndex++;
                        
                        if (currentShipIndex >= SHIP_LENGTHS.length) {
                            // All ships placed, CPU places its ships
                            placeCPUShips();
                            phase = Phase.PLAYER_TURN;
                            statusMessage = "Your turn! Click on the right board to fire.";
                        } else {
                            statusMessage = "Place ship " + (currentShipIndex + 1) + " (length " + SHIP_LENGTHS[currentShipIndex] + 
                                "). " + (placementHorizontal ? "Horizontal" : "Vertical") + ". Press Q/E to rotate.";
                        }
                    }
                }
            }
        } else if (phase == Phase.PLAYER_TURN) {
            // Map click to CPU board for firing
            int cellX = (int) Math.floor((mouseX - cpuBoardX) / cellSize);
            int cellY = (int) Math.floor((mouseY - cpuBoardY) / cellSize);
            
            if (cellX >= 0 && cellX < BOARD_SIZE && cellY >= 0 && cellY < BOARD_SIZE) {
                if (cpuGrid[cellY][cellX] == CellState.UNKNOWN) {
                    // Fire at this cell
                    if (cpuShips[cellY][cellX]) {
                        cpuGrid[cellY][cellX] = CellState.HIT;
                        statusMessage = "Hit!";
                        
                        // Check if ship is sunk
                        if (isShipSunk(cpuShips, cpuGrid, cellX, cellY)) {
                            statusMessage = "You sank a ship!";
                        }
                        
                        // Check if all CPU ships are sunk
                        if (allShipsSunk(cpuShips, cpuGrid)) {
                            phase = Phase.GAME_OVER;
                            playerWon = true;
                            statusMessage = "You win! All enemy ships destroyed!";
                        } else {
                            phase = Phase.CPU_TURN;
                            statusMessage = "CPU's turn...";
                            // CPU will fire on next tick or immediately
                            cpuTurn();
                        }
                    } else {
                        cpuGrid[cellY][cellX] = CellState.MISS;
                        statusMessage = "Miss!";
                        phase = Phase.CPU_TURN;
                        statusMessage = "CPU's turn...";
                        cpuTurn();
                    }
                }
            }
        }
    }
    
    @Override
    public void handleKeyPress(int keyCode) {
        if (phase == Phase.PLACING) {
            // Arrow keys or WASD to move placement cursor
            if (keyCode == 265 || keyCode == 87) { // UP or W
                if (placementY > 0) placementY--;
            } else if (keyCode == 264 || keyCode == 83) { // DOWN or S
                if (placementY < BOARD_SIZE - 1) placementY++;
            } else if (keyCode == 263 || keyCode == 65) { // LEFT or A
                if (placementX > 0) placementX--;
            } else if (keyCode == 262 || keyCode == 68) { // RIGHT or D
                if (placementX < BOARD_SIZE - 1) placementX++;
            } else if (keyCode == 81 || keyCode == 69) { // Q or E to rotate
                placementHorizontal = !placementHorizontal;
                // Update status to show current orientation
                statusMessage = "Place ship " + (currentShipIndex + 1) + " (length " + SHIP_LENGTHS[currentShipIndex] + 
                    "). " + (placementHorizontal ? "Horizontal" : "Vertical") + ". Press Q/E to rotate.";
            } else if (keyCode == 257) { // Enter to place
                if (canPlaceShip(placementX, placementY, SHIP_LENGTHS[currentShipIndex], placementHorizontal)) {
                    placeShip(placementX, placementY, SHIP_LENGTHS[currentShipIndex], placementHorizontal, true);
                    currentShipIndex++;
                    
                    if (currentShipIndex >= SHIP_LENGTHS.length) {
                        placeCPUShips();
                        phase = Phase.PLAYER_TURN;
                        statusMessage = "Your turn! Click on the right board to fire.";
                    } else {
                        statusMessage = "Place ship " + (currentShipIndex + 1) + " (length " + SHIP_LENGTHS[currentShipIndex] + ").";
                    }
                }
            }
        }
    }
    
    private boolean canPlaceShip(int x, int y, int length, boolean horizontal) {
        if (horizontal) {
            if (x + length > BOARD_SIZE) return false;
            for (int i = 0; i < length; i++) {
                if (playerShips[y][x + i]) return false;
            }
        } else {
            if (y + length > BOARD_SIZE) return false;
            for (int i = 0; i < length; i++) {
                if (playerShips[y + i][x]) return false;
            }
        }
        return true;
    }
    
    private void placeShip(int x, int y, int length, boolean horizontal, boolean isPlayer) {
        boolean[][] ships = isPlayer ? playerShips : cpuShips;
        CellState[][] grid = isPlayer ? playerGrid : null;
        
        if (horizontal) {
            for (int i = 0; i < length; i++) {
                ships[y][x + i] = true;
                if (grid != null) {
                    grid[y][x + i] = CellState.SHIP_VISIBLE;
                }
            }
        } else {
            for (int i = 0; i < length; i++) {
                ships[y + i][x] = true;
                if (grid != null) {
                    grid[y + i][x] = CellState.SHIP_VISIBLE;
                }
            }
        }
    }
    
    private void placeCPUShips() {
        for (int shipLength : SHIP_LENGTHS) {
            boolean placed = false;
            int attempts = 0;
            while (!placed && attempts < 1000) {
                int x = random.nextInt(BOARD_SIZE);
                int y = random.nextInt(BOARD_SIZE);
                boolean horizontal = random.nextBoolean();
                
                if (canPlaceShip(x, y, shipLength, horizontal)) {
                    placeShip(x, y, shipLength, horizontal, false);
                    placed = true;
                }
                attempts++;
            }
        }
    }
    
    private void cpuTurn() {
        int targetX = -1;
        int targetY = -1;
        boolean foundTarget = false;
        
        // Helper to check if a cell is unfired (can be targeted)
        // UNKNOWN = never fired, SHIP_VISIBLE = has ship but not fired yet
        // HIT and MISS = already fired, cannot target again
        java.util.function.BiFunction<Integer, Integer, Boolean> isUnfired = (x, y) -> {
            CellState state = playerGrid[y][x];
            return state == CellState.UNKNOWN || state == CellState.SHIP_VISIBLE;
        };
        
        // If we have a queue of targets (from previous hit), use those first
        if (!cpuTargetQueue.isEmpty()) {
            // Remove any targets that are already fired upon
            while (!cpuTargetQueue.isEmpty()) {
                int[] target = cpuTargetQueue.remove(0);
                if (isUnfired.apply(target[0], target[1])) {
                    targetX = target[0];
                    targetY = target[1];
                    foundTarget = true;
                    break;
                }
            }
        }
        
        if (!foundTarget && lastHitX >= 0 && lastHitY >= 0) {
            // Try adjacent cells to last hit
            List<int[]> adjacent = new ArrayList<>();
            if (lastHitX > 0 && isUnfired.apply(lastHitX - 1, lastHitY)) {
                adjacent.add(new int[]{lastHitX - 1, lastHitY});
            }
            if (lastHitX < BOARD_SIZE - 1 && isUnfired.apply(lastHitX + 1, lastHitY)) {
                adjacent.add(new int[]{lastHitX + 1, lastHitY});
            }
            if (lastHitY > 0 && isUnfired.apply(lastHitX, lastHitY - 1)) {
                adjacent.add(new int[]{lastHitX, lastHitY - 1});
            }
            if (lastHitY < BOARD_SIZE - 1 && isUnfired.apply(lastHitX, lastHitY + 1)) {
                adjacent.add(new int[]{lastHitX, lastHitY + 1});
            }
            
            if (!adjacent.isEmpty()) {
                int[] target = adjacent.get(random.nextInt(adjacent.size()));
                targetX = target[0];
                targetY = target[1];
                foundTarget = true;
            }
        }
        
        if (!foundTarget) {
            // Random untried cell - collect all unfired cells first
            // Only consider shot history (UNKNOWN or SHIP_VISIBLE), never check playerShips
            List<int[]> unfiredCells = new ArrayList<>();
            for (int y = 0; y < BOARD_SIZE; y++) {
                for (int x = 0; x < BOARD_SIZE; x++) {
                    if (isUnfired.apply(x, y)) {
                        unfiredCells.add(new int[]{x, y});
                    }
                }
            }
            
            if (unfiredCells.isEmpty()) {
                // No unfired cells left (shouldn't happen, but handle gracefully)
                phase = Phase.PLAYER_TURN;
                statusMessage = "Your turn!";
                return;
            }
            
            int[] target = unfiredCells.get(random.nextInt(unfiredCells.size()));
            targetX = target[0];
            targetY = target[1];
        }
        
        // Safety check - should never happen, but prevent crash
        if (targetX < 0 || targetY < 0 || targetX >= BOARD_SIZE || targetY >= BOARD_SIZE) {
            phase = Phase.PLAYER_TURN;
            statusMessage = "Your turn!";
            return;
        }
        
        // Fire at target - check ship presence AFTER choosing the cell
        // Only now do we check playerShips to determine hit/miss
        boolean hit = playerShips[targetY][targetX];
        
        CosmeticsLite.LOGGER.debug("Battleship CPU fires at {},{} -> {}", targetX, targetY, hit ? "HIT" : "MISS");
        
        if (hit) {
            playerGrid[targetY][targetX] = CellState.HIT;
            lastHitX = targetX;
            lastHitY = targetY;
            
            // Add adjacent cells to queue (only if unfired)
            if (targetX > 0) {
                CellState adjState = playerGrid[targetY][targetX - 1];
                if (adjState == CellState.UNKNOWN || adjState == CellState.SHIP_VISIBLE) {
                    cpuTargetQueue.add(new int[]{targetX - 1, targetY});
                }
            }
            if (targetX < BOARD_SIZE - 1) {
                CellState adjState = playerGrid[targetY][targetX + 1];
                if (adjState == CellState.UNKNOWN || adjState == CellState.SHIP_VISIBLE) {
                    cpuTargetQueue.add(new int[]{targetX + 1, targetY});
                }
            }
            if (targetY > 0) {
                CellState adjState = playerGrid[targetY - 1][targetX];
                if (adjState == CellState.UNKNOWN || adjState == CellState.SHIP_VISIBLE) {
                    cpuTargetQueue.add(new int[]{targetX, targetY - 1});
                }
            }
            if (targetY < BOARD_SIZE - 1) {
                CellState adjState = playerGrid[targetY + 1][targetX];
                if (adjState == CellState.UNKNOWN || adjState == CellState.SHIP_VISIBLE) {
                    cpuTargetQueue.add(new int[]{targetX, targetY + 1});
                }
            }
            
            statusMessage = "CPU hit your ship!";
            
            // Check if ship is sunk
            if (isShipSunk(playerShips, playerGrid, targetX, targetY)) {
                statusMessage = "CPU sank one of your ships!";
                lastHitX = -1;
                lastHitY = -1;
                cpuTargetQueue.clear();
            }
            
            // Check if all player ships are sunk
            if (allShipsSunk(playerShips, playerGrid)) {
                phase = Phase.GAME_OVER;
                playerWon = false;
                statusMessage = "You lose! All your ships were destroyed!";
            } else {
                phase = Phase.PLAYER_TURN;
                statusMessage = "Your turn!";
            }
        } else {
            playerGrid[targetY][targetX] = CellState.MISS;
            statusMessage = "CPU missed!";
            phase = Phase.PLAYER_TURN;
            statusMessage = "Your turn!";
        }
    }
    
    private boolean isShipSunk(boolean[][] ships, CellState[][] grid, int x, int y) {
        // Find all cells of this ship and check if all are hit
        boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];
        List<int[]> shipCells = new ArrayList<>();
        findShipCells(ships, visited, x, y, shipCells);
        
        for (int[] cell : shipCells) {
            if (grid[cell[1]][cell[0]] != CellState.HIT) {
                return false;
            }
        }
        return true;
    }
    
    private void findShipCells(boolean[][] ships, boolean[][] visited, int x, int y, List<int[]> result) {
        if (x < 0 || x >= BOARD_SIZE || y < 0 || y >= BOARD_SIZE) return;
        if (visited[y][x] || !ships[y][x]) return;
        
        visited[y][x] = true;
        result.add(new int[]{x, y});
        
        findShipCells(ships, visited, x - 1, y, result);
        findShipCells(ships, visited, x + 1, y, result);
        findShipCells(ships, visited, x, y - 1, result);
        findShipCells(ships, visited, x, y + 1, result);
    }
    
    private boolean allShipsSunk(boolean[][] ships, CellState[][] grid) {
        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                if (ships[y][x] && grid[y][x] != CellState.HIT) {
                    return false;
                }
            }
        }
        return true;
    }
    
    @Override
    public void render(GuiGraphics g, Font font, int areaX, int areaY, int areaWidth, int areaHeight, float partialTicks) {
        this.areaX = areaX;
        this.areaY = areaY;
        this.areaWidth = areaWidth;
        this.areaHeight = areaHeight;
        
        // Draw status message at top
        int statusY = areaY + 10;
        g.drawString(font, Component.literal(statusMessage), areaX + 10, statusY, 0xFFFFFF, true);
        
        // Calculate board positions (side by side)
        // Reserve space for status and labels
        int availableHeight = areaHeight - 60;
        int availableWidth = areaWidth - 40;
        int cellSize = Math.min(availableWidth / (BOARD_SIZE * 2 + 2), availableHeight / BOARD_SIZE);
        cellSize = Math.max(cellSize, 6); // Minimum cell size
        int boardWidth = BOARD_SIZE * cellSize;
        int boardHeight = BOARD_SIZE * cellSize;
        
        int playerBoardX = areaX + areaWidth / 4 - boardWidth / 2;
        int playerBoardY = areaY + 50 + (availableHeight - boardHeight) / 2;
        int cpuBoardX = areaX + 3 * areaWidth / 4 - boardWidth / 2;
        int cpuBoardY = areaY + 50 + (availableHeight - boardHeight) / 2;
        
        // Draw labels
        g.drawString(font, Component.literal("Your Board"), playerBoardX, playerBoardY - 15, 0xFFFFFF, true);
        g.drawString(font, Component.literal("Enemy Board"), cpuBoardX, cpuBoardY - 15, 0xFFFFFF, true);
        
        // Draw player board (with ships visible)
        drawBoard(g, font, playerBoardX, playerBoardY, cellSize, playerGrid, playerShips, true, false);
        
        // Draw CPU board (enemy board)
        drawBoard(g, font, cpuBoardX, cpuBoardY, cellSize, cpuGrid, cpuShips, false, true);
        
        // Draw placement preview
        if (phase == Phase.PLACING && currentShipIndex < SHIP_LENGTHS.length) {
            int previewX = playerBoardX + placementX * cellSize;
            int previewY = playerBoardY + placementY * cellSize;
            int shipLength = SHIP_LENGTHS[currentShipIndex];
            boolean valid = canPlaceShip(placementX, placementY, shipLength, placementHorizontal);
            int color = valid ? 0x4000FF00 : 0x40FF0000; // Green if valid, red if invalid
            
            if (placementHorizontal) {
                g.fill(previewX, previewY, previewX + shipLength * cellSize, previewY + cellSize, color);
            } else {
                g.fill(previewX, previewY, previewX + cellSize, previewY + shipLength * cellSize, color);
            }
        }
    }
    
    private void drawBoard(GuiGraphics g, Font font, int boardX, int boardY, int cellSize, 
                          CellState[][] grid, boolean[][] ships, boolean showShips, boolean isEnemyBoard) {
        // Draw grid cells
        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                int px = boardX + x * cellSize;
                int py = boardY + y * cellSize;
                
                // Background
                int bgColor = 0xFF9B8365; // Tan color
                g.fill(px, py, px + cellSize, py + cellSize, bgColor);
                
                // Outer border
                g.fill(px, py, px + cellSize, py + 1, 0xFF6B5B45);
                g.fill(px, py, px + 1, py + cellSize, 0xFF6B5B45);
                
                // Faint 1px inner border on water cells (if not a ship or hit/miss)
                CellState state = grid[y][x];
                boolean isWater = !showShips || (state != CellState.SHIP_VISIBLE && state != CellState.HIT);
                if (isWater) {
                    int innerBorderColor = 0x40FFFFFF; // Subtle white inner border
                    g.fill(px + 1, py + 1, px + cellSize - 1, py + 2, innerBorderColor);
                    g.fill(px + 1, py + 1, px + 2, py + cellSize - 1, innerBorderColor);
                }
                
                // Hover highlight on enemy board (only for unfired cells)
                if (isEnemyBoard && hoveredOnEnemyBoard && hoveredCellX == x && hoveredCellY == y && state == CellState.UNKNOWN) {
                    // Subtle light gray outline
                    g.fill(px + 1, py + 1, px + cellSize - 1, py + 2, 0x80CCCCCC);
                    g.fill(px + 1, py + cellSize - 2, px + cellSize - 1, py + cellSize - 1, 0x80CCCCCC);
                    g.fill(px + 1, py + 1, px + 2, py + cellSize - 1, 0x80CCCCCC);
                    g.fill(px + cellSize - 2, py + 1, px + cellSize - 1, py + cellSize - 1, 0x80CCCCCC);
                }
                
                // Show ships on player board with beveled effect
                if (showShips && ships[y][x] && state == CellState.SHIP_VISIBLE) {
                    // Base ship color
                    g.fill(px + 1, py + 1, px + cellSize - 1, py + cellSize - 1, 0xFF4A4A4A);
                    
                    // Beveled effect: lighter top edge, darker bottom edge
                    int bevelSize = 1;
                    // Top edge (lighter)
                    g.fill(px + 1, py + 1, px + cellSize - 1, py + 1 + bevelSize, 0xFF6A6A6A);
                    // Left edge (lighter)
                    g.fill(px + 1, py + 1, px + 1 + bevelSize, py + cellSize - 1, 0xFF6A6A6A);
                    // Bottom edge (darker)
                    g.fill(px + 1, py + cellSize - 1 - bevelSize, px + cellSize - 1, py + cellSize - 1, 0xFF2A2A2A);
                    // Right edge (darker)
                    g.fill(px + cellSize - 1 - bevelSize, py + 1, px + cellSize - 1, py + cellSize - 1, 0xFF2A2A2A);
                }
                
                // Draw hits and misses
                if (state == CellState.HIT) {
                    // Optional darker box under the X as "hole"
                    int holeSize = cellSize / 3;
                    int holeX = px + (cellSize - holeSize) / 2;
                    int holeY = py + (cellSize - holeSize) / 2;
                    g.fill(holeX, holeY, holeX + holeSize, holeY + holeSize, 0xFF1A0000);
                    
                    // Bright red X (two lines crossing)
                    int margin = Math.max(2, cellSize / 4);
                    int lineThickness = Math.max(1, cellSize / 8);
                    // Top-left to bottom-right diagonal
                    for (int i = 0; i < cellSize - margin * 2; i++) {
                        for (int t = 0; t < lineThickness; t++) {
                            int offsetX = margin + i + t;
                            int offsetY = margin + i;
                            if (offsetX < cellSize - margin && offsetY < cellSize - margin) {
                                g.fill(px + offsetX, py + offsetY, px + offsetX + 1, py + offsetY + 1, 0xFFFF0000);
                            }
                        }
                    }
                    // Top-right to bottom-left diagonal
                    for (int i = 0; i < cellSize - margin * 2; i++) {
                        for (int t = 0; t < lineThickness; t++) {
                            int offsetX = cellSize - margin - i - 1 - t;
                            int offsetY = margin + i;
                            if (offsetX >= margin && offsetY < cellSize - margin) {
                                g.fill(px + offsetX, py + offsetY, px + offsetX + 1, py + offsetY + 1, 0xFFFF0000);
                            }
                        }
                    }
                } else if (state == CellState.MISS) {
                    // Small white dot with soft ring (splash effect)
                    int centerX = px + cellSize / 2;
                    int centerY = py + cellSize / 2;
                    int dotRadius = Math.max(1, cellSize / 8);
                    int ringRadius = dotRadius + 2;
                    
                    // Soft ring (splash)
                    for (int dy = -ringRadius; dy <= ringRadius; dy++) {
                        for (int dx = -ringRadius; dx <= ringRadius; dx++) {
                            int distSq = dx * dx + dy * dy;
                            if (distSq >= dotRadius * dotRadius && distSq <= ringRadius * ringRadius) {
                                // Fade out from inner to outer
                                float alpha = 1.0f - (float)(distSq - dotRadius * dotRadius) / (ringRadius * ringRadius - dotRadius * dotRadius);
                                int ringColor = ((int)(alpha * 0x40) << 24) | 0xFFFFFF;
                                g.fill(centerX + dx, centerY + dy, centerX + dx + 1, centerY + dy + 1, ringColor);
                            }
                        }
                    }
                    
                    // White dot
                    for (int dy = -dotRadius; dy <= dotRadius; dy++) {
                        for (int dx = -dotRadius; dx <= dotRadius; dx++) {
                            if (dx * dx + dy * dy <= dotRadius * dotRadius) {
                                g.fill(centerX + dx, centerY + dy, centerX + dx + 1, centerY + dy + 1, 0xFFFFFFFF);
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Update hovered cell from mouse position (called by MiniGamePlayScreen)
    public void updateMouse(double mouseX, double mouseY) {
        if (areaWidth == 0 || areaHeight == 0) {
            hoveredCellX = -1;
            hoveredCellY = -1;
            hoveredOnEnemyBoard = false;
            return;
        }
        
        // Calculate board positions (same as in render)
        int availableHeight = areaHeight - 60;
        int availableWidth = areaWidth - 40;
        int cellSize = Math.min(availableWidth / (BOARD_SIZE * 2 + 2), availableHeight / BOARD_SIZE);
        cellSize = Math.max(cellSize, 6);
        int boardWidth = BOARD_SIZE * cellSize;
        int boardHeight = BOARD_SIZE * cellSize;
        
        int cpuBoardX = areaX + 3 * areaWidth / 4 - boardWidth / 2;
        int cpuBoardY = areaY + 50 + (availableHeight - boardHeight) / 2;
        
        // Check if mouse is over enemy board
        if (mouseX >= cpuBoardX && mouseX < cpuBoardX + boardWidth &&
            mouseY >= cpuBoardY && mouseY < cpuBoardY + boardHeight) {
            int cellX = (int) Math.floor((mouseX - cpuBoardX) / cellSize);
            int cellY = (int) Math.floor((mouseY - cpuBoardY) / cellSize);
            
            if (cellX >= 0 && cellX < BOARD_SIZE && cellY >= 0 && cellY < BOARD_SIZE) {
                hoveredCellX = cellX;
                hoveredCellY = cellY;
                hoveredOnEnemyBoard = true;
                return;
            }
        }
        
        // Not hovering over enemy board
        hoveredCellX = -1;
        hoveredCellY = -1;
        hoveredOnEnemyBoard = false;
    }
    
    @Override
    public String getTitle() {
        return "Battleship";
    }
    
    @Override
    public boolean isGameOver() {
        return phase == Phase.GAME_OVER;
    }
    
    @Override
    public int getScore() {
        if (phase == Phase.GAME_OVER) {
            return playerWon ? 1 : 0;
        }
        return 0;
    }
    
    @Override
    public void onClose() {
        // No cleanup needed
    }
}

