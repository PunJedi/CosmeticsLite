package com.pastlands.cosmeticslite.minigame.impl.battleship;

import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.minigame.api.MiniGame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

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
    
    // Last move indicator (fades out over time)
    private int lastPlayerMoveX = -1, lastPlayerMoveY = -1;
    private int lastPlayerMoveTicks = 0;
    private int lastCPUMoveX = -1, lastCPUMoveY = -1;
    private int lastCPUMoveTicks = 0;
    private static final int LAST_MOVE_DURATION = 12; // ticks to show last move indicator
    
    // Ship placement flash effect
    private Set<int[]> placedShipCells = new HashSet<>(); // Cells that just got a ship placed
    private int placementFlashTicks = 0;
    private static final int PLACEMENT_FLASH_DURATION = 8;
    
    // Track sunk ships for visual distinction (using string keys "x,y")
    private Set<String> sunkPlayerShipCells = new HashSet<>();
    private Set<String> sunkCPUShipCells = new HashSet<>();
    
    // Sound debouncing
    private int lastInvalidPlacementSoundTick = -10; // Prevent spam
    
    @Override
    public void initGame() {
        random = new Random();
        phase = Phase.PLACING;
        currentShipIndex = 0;
        placementX = 0;
        placementY = 0;
        placementHorizontal = true;
        playerWon = false;
        statusMessage = "Place your ships";
        
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
        lastPlayerMoveX = -1;
        lastPlayerMoveY = -1;
        lastPlayerMoveTicks = 0;
        lastCPUMoveX = -1;
        lastCPUMoveY = -1;
        lastCPUMoveTicks = 0;
        placedShipCells.clear();
        placementFlashTicks = 0;
        sunkPlayerShipCells.clear();
        sunkCPUShipCells.clear();
        lastInvalidPlacementSoundTick = -10;
    }
    
    @Override
    public void tick() {
        // Update last move indicators (fade out)
        if (lastPlayerMoveTicks > 0) {
            lastPlayerMoveTicks--;
        }
        if (lastCPUMoveTicks > 0) {
            lastCPUMoveTicks--;
        }
        
        // Update placement flash effect
        if (placementFlashTicks > 0) {
            placementFlashTicks--;
            if (placementFlashTicks == 0) {
                placedShipCells.clear();
            }
        }
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
                        // Valid placement - play sound and flash effect
                        playLocalSound(SoundEvents.STONE_PLACE, 0.3F, 1.0F);
                        
                        // Mark cells for flash effect
                        placedShipCells.clear();
                        int shipLength = SHIP_LENGTHS[currentShipIndex];
                        if (placementHorizontal) {
                            for (int i = 0; i < shipLength; i++) {
                                placedShipCells.add(new int[]{placementX + i, placementY});
                            }
                        } else {
                            for (int i = 0; i < shipLength; i++) {
                                placedShipCells.add(new int[]{placementX, placementY + i});
                            }
                        }
                        placementFlashTicks = PLACEMENT_FLASH_DURATION;
                        
                        placeShip(placementX, placementY, SHIP_LENGTHS[currentShipIndex], placementHorizontal, true);
                        currentShipIndex++;
                        
                        if (currentShipIndex >= SHIP_LENGTHS.length) {
                            // All ships placed, CPU places its ships
                            placeCPUShips();
                            phase = Phase.PLAYER_TURN;
                            statusMessage = "Your turn!";
                        } else {
                            statusMessage = "Place your ships";
                        }
                    } else {
                        // Invalid placement - play error sound (debounced)
                        int currentTick = (int)(System.currentTimeMillis() / 50); // Approximate tick
                        if (currentTick - lastInvalidPlacementSoundTick > 5) {
                            playLocalSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.2F, 0.6F);
                            lastInvalidPlacementSoundTick = currentTick;
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
                    lastPlayerMoveX = cellX;
                    lastPlayerMoveY = cellY;
                    lastPlayerMoveTicks = LAST_MOVE_DURATION;
                    
                    // Play shoot sound
                    playLocalSound(SoundEvents.ARROW_SHOOT, 0.3F, 1.2F);
                    
                    if (cpuShips[cellY][cellX]) {
                        cpuGrid[cellY][cellX] = CellState.HIT;
                        
                        // Check if ship is sunk
                        boolean shipSunk = isShipSunk(cpuShips, cpuGrid, cellX, cellY);
                        if (shipSunk) {
                            // Mark all cells of this ship as sunk
                            boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];
                            List<int[]> shipCells = new ArrayList<>();
                            findShipCells(cpuShips, visited, cellX, cellY, shipCells);
                            for (int[] cell : shipCells) {
                                sunkCPUShipCells.add(cell[0] + "," + cell[1]);
                            }
                            
                            statusMessage = "You sank a ship!";
                            playLocalSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.5F, 1.2F);
                        } else {
                            statusMessage = "Hit!";
                            playLocalSound(SoundEvents.ANVIL_LAND, 0.4F, 1.4F);
                        }
                        
                        // Check if all CPU ships are sunk
                        if (allShipsSunk(cpuShips, cpuGrid)) {
                            phase = Phase.GAME_OVER;
                            playerWon = true;
                            statusMessage = "You win! All enemy ships destroyed.";
                            playLocalSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.7F, 1.0F);
                        } else {
                            phase = Phase.CPU_TURN;
                            statusMessage = "Enemy's turn...";
                            // CPU will fire on next tick or immediately
                            cpuTurn();
                        }
                    } else {
                        cpuGrid[cellY][cellX] = CellState.MISS;
                        statusMessage = "Miss!";
                        playLocalSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.25F, 0.8F);
                        phase = Phase.CPU_TURN;
                        statusMessage = "Enemy's turn...";
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
                // Status message stays as "Place your ships"
            } else if (keyCode == 257) { // Enter to place
                if (canPlaceShip(placementX, placementY, SHIP_LENGTHS[currentShipIndex], placementHorizontal)) {
                    // Valid placement - play sound and flash effect
                    playLocalSound(SoundEvents.STONE_PLACE, 0.3F, 1.0F);
                    
                    // Mark cells for flash effect
                    placedShipCells.clear();
                    int shipLength = SHIP_LENGTHS[currentShipIndex];
                    if (placementHorizontal) {
                        for (int i = 0; i < shipLength; i++) {
                            placedShipCells.add(new int[]{placementX + i, placementY});
                        }
                    } else {
                        for (int i = 0; i < shipLength; i++) {
                            placedShipCells.add(new int[]{placementX, placementY + i});
                        }
                    }
                    placementFlashTicks = PLACEMENT_FLASH_DURATION;
                    
                    placeShip(placementX, placementY, SHIP_LENGTHS[currentShipIndex], placementHorizontal, true);
                    currentShipIndex++;
                    
                    if (currentShipIndex >= SHIP_LENGTHS.length) {
                        placeCPUShips();
                        phase = Phase.PLAYER_TURN;
                        statusMessage = "Your turn!";
                    } else {
                        statusMessage = "Place your ships";
                    }
                } else {
                    // Invalid placement - play error sound (debounced)
                    int currentTick = (int)(System.currentTimeMillis() / 50);
                    if (currentTick - lastInvalidPlacementSoundTick > 5) {
                        playLocalSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.2F, 0.6F);
                        lastInvalidPlacementSoundTick = currentTick;
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
        
        lastCPUMoveX = targetX;
        lastCPUMoveY = targetY;
        lastCPUMoveTicks = LAST_MOVE_DURATION;
        
        // Play quieter enemy shoot sound
        playLocalSound(SoundEvents.ARROW_SHOOT, 0.15F, 1.1F);
        
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
            
            // Check if ship is sunk
            boolean shipSunk = isShipSunk(playerShips, playerGrid, targetX, targetY);
            if (shipSunk) {
                // Mark all cells of this ship as sunk
                boolean[][] visited = new boolean[BOARD_SIZE][BOARD_SIZE];
                List<int[]> shipCells = new ArrayList<>();
                findShipCells(playerShips, visited, targetX, targetY, shipCells);
                for (int[] cell : shipCells) {
                    sunkPlayerShipCells.add(cell[0] + "," + cell[1]);
                }
                
                statusMessage = "CPU sank one of your ships!";
                lastHitX = -1;
                lastHitY = -1;
                cpuTargetQueue.clear();
                playLocalSound(SoundEvents.ANVIL_LAND, 0.25F, 0.8F);
            } else {
                statusMessage = "CPU hit your ship!";
                playLocalSound(SoundEvents.ANVIL_LAND, 0.2F, 1.2F);
            }
            
            // Check if all player ships are sunk
            if (allShipsSunk(playerShips, playerGrid)) {
                phase = Phase.GAME_OVER;
                playerWon = false;
                statusMessage = "You lose. Your fleet has sunk.";
                playLocalSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.5F, 0.5F);
            } else {
                phase = Phase.PLAYER_TURN;
                statusMessage = "Your turn!";
            }
        } else {
            playerGrid[targetY][targetX] = CellState.MISS;
            statusMessage = "CPU missed!";
            playLocalSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.12F, 0.7F);
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
        
        // Draw status message at top-left
        int statusY = areaY + 10;
        String displayStatus = statusMessage;
        if (phase == Phase.CPU_TURN) {
            displayStatus = "Enemy's turn...";
        } else if (phase == Phase.GAME_OVER) {
            if (playerWon) {
                displayStatus = "You win! All enemy ships destroyed.";
            } else {
                displayStatus = "You lose. Your fleet has sunk.";
            }
        }
        int statusColor = (phase == Phase.CPU_TURN) ? 0xFFAAAAAA : 0xFFFFFFFF; // Dimmed for CPU turn
        g.drawString(font, Component.literal(displayStatus), areaX + 10, statusY, statusColor, true);
        
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
        
        // Draw helper text during placement phase
        if (phase == Phase.PLACING && currentShipIndex < SHIP_LENGTHS.length) {
            String helperText = "Q/E: Rotate, Enter/Click: Place ship";
            g.drawString(font, Component.literal(helperText), playerBoardX, playerBoardY - 30, 0xFFAAAAAA, true);
        }
        
        // Draw player board (with ships visible)
        drawBoard(g, font, playerBoardX, playerBoardY, cellSize, playerGrid, playerShips, true, false, 
                 lastPlayerMoveX, lastPlayerMoveY, lastPlayerMoveTicks);
        
        // Draw CPU board (enemy board)
        drawBoard(g, font, cpuBoardX, cpuBoardY, cellSize, cpuGrid, cpuShips, false, true,
                 lastCPUMoveX, lastCPUMoveY, lastCPUMoveTicks);
        
        // Draw placement preview (ghost preview with outline)
        if (phase == Phase.PLACING && currentShipIndex < SHIP_LENGTHS.length) {
            int shipLength = SHIP_LENGTHS[currentShipIndex];
            boolean valid = canPlaceShip(placementX, placementY, shipLength, placementHorizontal);
            
            // Draw semi-transparent ship preview
            int previewColor = 0x604A4A4A; // Semi-transparent ship color
            if (placementHorizontal) {
                for (int i = 0; i < shipLength; i++) {
                    int px = playerBoardX + (placementX + i) * cellSize;
                    int py = playerBoardY + placementY * cellSize;
                    g.fill(px + 1, py + 1, px + cellSize - 1, py + cellSize - 1, previewColor);
                }
            } else {
                for (int i = 0; i < shipLength; i++) {
                    int px = playerBoardX + placementX * cellSize;
                    int py = playerBoardY + (placementY + i) * cellSize;
                    g.fill(px + 1, py + 1, px + cellSize - 1, py + cellSize - 1, previewColor);
                }
            }
            
            // Draw outline (green for valid, red for invalid)
            int outlineColor = valid ? 0xFF00FF00 : 0xFFFF0000; // Bright green or red
            int outlineThickness = 2;
            if (placementHorizontal) {
                int previewX = playerBoardX + placementX * cellSize;
                int previewY = playerBoardY + placementY * cellSize;
                int previewWidth = shipLength * cellSize;
                // Top and bottom
                g.fill(previewX, previewY, previewX + previewWidth, previewY + outlineThickness, outlineColor);
                g.fill(previewX, previewY + cellSize - outlineThickness, previewX + previewWidth, previewY + cellSize, outlineColor);
                // Left and right
                g.fill(previewX, previewY, previewX + outlineThickness, previewY + cellSize, outlineColor);
                g.fill(previewX + previewWidth - outlineThickness, previewY, previewX + previewWidth, previewY + cellSize, outlineColor);
            } else {
                int previewX = playerBoardX + placementX * cellSize;
                int previewY = playerBoardY + placementY * cellSize;
                int previewHeight = shipLength * cellSize;
                // Top and bottom
                g.fill(previewX, previewY, previewX + cellSize, previewY + outlineThickness, outlineColor);
                g.fill(previewX, previewY + previewHeight - outlineThickness, previewX + cellSize, previewY + previewHeight, outlineColor);
                // Left and right
                g.fill(previewX, previewY, previewX + outlineThickness, previewY + previewHeight, outlineColor);
                g.fill(previewX + cellSize - outlineThickness, previewY, previewX + cellSize, previewY + previewHeight, outlineColor);
            }
        }
    }
    
    private void drawBoard(GuiGraphics g, Font font, int boardX, int boardY, int cellSize, 
                          CellState[][] grid, boolean[][] ships, boolean showShips, boolean isEnemyBoard,
                          int lastMoveX, int lastMoveY, int lastMoveTicks) {
        // Draw board background panel with inner border
        int panelBorder = 2;
        int panelBgColor = 0xFF2C3E50; // Dark blue-gray background
        g.fill(boardX - panelBorder, boardY - panelBorder, 
               boardX + BOARD_SIZE * cellSize + panelBorder, 
               boardY + BOARD_SIZE * cellSize + panelBorder, panelBgColor);
        
        // Inner border around the grid
        int innerBorderColor = 0xFF4A5A6A; // Slightly lighter border
        g.fill(boardX - panelBorder, boardY - panelBorder, 
               boardX + BOARD_SIZE * cellSize + panelBorder, boardY - panelBorder + 1, innerBorderColor);
        g.fill(boardX - panelBorder, boardY + BOARD_SIZE * cellSize + panelBorder - 1, 
               boardX + BOARD_SIZE * cellSize + panelBorder, boardY + BOARD_SIZE * cellSize + panelBorder, innerBorderColor);
        g.fill(boardX - panelBorder, boardY - panelBorder, 
               boardX - panelBorder + 1, boardY + BOARD_SIZE * cellSize + panelBorder, innerBorderColor);
        g.fill(boardX + BOARD_SIZE * cellSize + panelBorder - 1, boardY - panelBorder, 
               boardX + BOARD_SIZE * cellSize + panelBorder, boardY + BOARD_SIZE * cellSize + panelBorder, innerBorderColor);
        
        // Draw grid cells
        for (int y = 0; y < BOARD_SIZE; y++) {
            for (int x = 0; x < BOARD_SIZE; x++) {
                int px = boardX + x * cellSize;
                int py = boardY + y * cellSize;
                
                CellState state = grid[y][x];
                boolean isSunk = false;
                String cellKey = x + "," + y;
                if (showShips) {
                    isSunk = sunkPlayerShipCells.contains(cellKey);
                } else {
                    isSunk = sunkCPUShipCells.contains(cellKey);
                }
                
                // Unfired water tile - soft blue-gray fill
                if (state == CellState.UNKNOWN && (!showShips || !ships[y][x])) {
                    int waterColor = 0xFF5A7A9A; // Soft blue-gray
                    g.fill(px, py, px + cellSize, py + cellSize, waterColor);
                    
                    // Very faint inner border
                    int innerBorder = 0x20FFFFFF;
                    g.fill(px + 1, py + 1, px + cellSize - 1, py + 2, innerBorder);
                    g.fill(px + 1, py + 1, px + 2, py + cellSize - 1, innerBorder);
                }
                
                // Ship tile (player board only, unfired)
                if (showShips && ships[y][x] && state == CellState.SHIP_VISIBLE) {
                    // Check if this cell is in the flash effect
                    boolean isFlashing = false;
                    for (int[] cell : placedShipCells) {
                        if (cell[0] == x && cell[1] == y) {
                            isFlashing = true;
                            break;
                        }
                    }
                    
                    // Base ship color (brighter if flashing)
                    int baseColor = isFlashing ? 0xFF6A6A6A : 0xFF4A4A4A;
                    g.fill(px + 1, py + 1, px + cellSize - 1, py + cellSize - 1, baseColor);
                    
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
                
                // Hover highlight on enemy board (only for unfired cells)
                if (isEnemyBoard && hoveredOnEnemyBoard && hoveredCellX == x && hoveredCellY == y && state == CellState.UNKNOWN) {
                    // Thin bright outline (lime or cyan)
                    int hoverColor = 0xFF00FFFF; // Cyan
                    int hoverThickness = 1;
                    g.fill(px, py, px + cellSize, py + hoverThickness, hoverColor);
                    g.fill(px, py + cellSize - hoverThickness, px + cellSize, py + cellSize, hoverColor);
                    g.fill(px, py, px + hoverThickness, py + cellSize, hoverColor);
                    g.fill(px + cellSize - hoverThickness, py, px + cellSize, py + cellSize, hoverColor);
                }
                
                // Last move indicator (fading glow)
                boolean isLastMove = (lastMoveX == x && lastMoveY == y && lastMoveTicks > 0);
                if (isLastMove) {
                    float alpha = lastMoveTicks / (float)LAST_MOVE_DURATION;
                    int glowColor = ((int)(alpha * 0x60) << 24) | 0xFFFF00; // Yellow glow
                    int glowThickness = 2;
                    g.fill(px, py, px + cellSize, py + glowThickness, glowColor);
                    g.fill(px, py + cellSize - glowThickness, px + cellSize, py + cellSize, glowColor);
                    g.fill(px, py, px + glowThickness, py + cellSize, glowColor);
                    g.fill(px + cellSize - glowThickness, py, px + cellSize, py + cellSize, glowColor);
                }
                
                // Miss marker - small white ring/outlined circle
                if (state == CellState.MISS) {
                    int centerX = px + cellSize / 2;
                    int centerY = py + cellSize / 2;
                    int ringRadius = Math.max(2, cellSize / 4);
                    
                    // Draw outlined circle (ring)
                    for (int dy = -ringRadius; dy <= ringRadius; dy++) {
                        for (int dx = -ringRadius; dx <= ringRadius; dx++) {
                            int distSq = dx * dx + dy * dy;
                            // Draw pixels near the edge (ring)
                            if (distSq >= (ringRadius - 1) * (ringRadius - 1) && distSq <= ringRadius * ringRadius) {
                                g.fill(centerX + dx, centerY + dy, centerX + dx + 1, centerY + dy + 1, 0xFFFFFFFF);
                            }
                        }
                    }
                }
                
                // Hit marker - bright red X with drop shadow
                if (state == CellState.HIT) {
                    // Dim overlay for sunk ships
                    if (isSunk) {
                        g.fill(px + 1, py + 1, px + cellSize - 1, py + cellSize - 1, 0x80000000);
                    }
                    
                    // Drop shadow (darker X offset slightly)
                    int margin = Math.max(2, cellSize / 4);
                    int shadowOffset = 1;
                    drawX(g, px + shadowOffset, py + shadowOffset, cellSize, margin, 0xFF800000, 2);
                    
                    // Bright red X on top
                    drawX(g, px, py, cellSize, margin, 0xFFFF0000, 2);
                }
                
                // Grid lines between cells
                if (x < BOARD_SIZE - 1) {
                    g.fill(px + cellSize - 1, py, px + cellSize, py + cellSize, 0x30000000);
                }
                if (y < BOARD_SIZE - 1) {
                    g.fill(px, py + cellSize - 1, px + cellSize, py + cellSize, 0x30000000);
                }
            }
        }
    }
    
    private void drawX(GuiGraphics g, int px, int py, int cellSize, int margin, int color, int thickness) {
        // Draw X shape (two crossing lines)
        int centerX = px + cellSize / 2;
        int centerY = py + cellSize / 2;
        int halfSize = (cellSize - margin * 2) / 2;
        
        // Top-left to bottom-right diagonal
        for (int i = -halfSize; i <= halfSize; i++) {
            for (int t = 0; t < thickness; t++) {
                int offsetX = centerX + i + t;
                int offsetY = centerY + i;
                if (offsetX >= px + margin && offsetX < px + cellSize - margin &&
                    offsetY >= py + margin && offsetY < py + cellSize - margin) {
                    g.fill(offsetX, offsetY, offsetX + 1, offsetY + 1, color);
                }
            }
        }
        
        // Top-right to bottom-left diagonal
        for (int i = -halfSize; i <= halfSize; i++) {
            for (int t = 0; t < thickness; t++) {
                int offsetX = centerX - i - t;
                int offsetY = centerY + i;
                if (offsetX >= px + margin && offsetX < px + cellSize - margin &&
                    offsetY >= py + margin && offsetY < py + cellSize - margin) {
                    g.fill(offsetX, offsetY, offsetX + 1, offsetY + 1, color);
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
    
    /**
     * Check if the player won (only valid when phase == GAME_OVER).
     */
    public boolean didPlayerWin() {
        return playerWon;
    }
    
    @Override
    public void onClose() {
        // No cleanup needed
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
}

