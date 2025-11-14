package com.pastlands.cosmeticslite.minigame.impl.tilt;

import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.minigame.api.MiniGame;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.Random;

/**
 * Marble Tilt (grid-based tilt maze) mini-game implementation with multiple levels.
 */
public class MarbleTiltGame implements MiniGame {
    
    private static final int BOARD_WIDTH = 9;
    private static final int BOARD_HEIGHT = 9;
    
    private static final int TILE_EMPTY = 0;
    private static final int TILE_WALL = 1;
    private static final int TILE_GOAL = 2;
    
    /**
     * Direction enum for tilt movements.
     */
    private enum Direction {
        UP(0, -1),
        DOWN(0, 1),
        LEFT(-1, 0),
        RIGHT(1, 0);
        
        final int dx;
        final int dy;
        
        Direction(int dx, int dy) {
            this.dx = dx;
            this.dy = dy;
        }
    }
    
    /**
     * Result of a tilt simulation.
     */
    private static class TiltResult {
        final int finalX;
        final int finalY;
        final boolean reachedGoal;
        
        TiltResult(int finalX, int finalY, boolean reachedGoal) {
            this.finalX = finalX;
            this.finalY = finalY;
            this.reachedGoal = reachedGoal;
        }
    }
    
    // Generation configuration
    private static final int BASE_MIN_MOVES = 3;   // minimum required solution length for level 1
    private static final int MOVES_PER_LEVEL = 1;   // how much min moves grows per level
    private static final int MAX_MIN_MOVES = 14;  // clamp for very high levels
    
    private static final double BASE_WALL_DENSITY = 0.15; // fraction of interior cells that become walls on level 1
    private static final double WALL_DENSITY_PER_LEVEL = 0.02; // increase per level
    private static final double MAX_WALL_DENSITY = 0.40;
    
    private static final int MAX_GENERATION_ATTEMPTS = 50; // how many tries per level before relaxing constraints
    
    private int[][] grid;
    private int width = BOARD_WIDTH;
    private int height = BOARD_HEIGHT;
    private int ballX, ballY;
    private boolean gameOver;
    private int moves; // moves in current level
    private int totalMoves; // total moves across all levels in this run
    private int currentLevelIndex = 0; // 0-based difficulty level (endless progression)
    private boolean levelComplete; // just finished this level
    private int levelIntroTicks; // for level transition flash effect
    private boolean devLevelUnsolvable = false; // Debug flag for unsolvable levels
    private boolean newRun = false; // Flag to indicate a new run (reset was pressed)
    private final Random random = new Random();
    
    // Visual-only animation fields
    private float portalPulse = 0.0f;
    private int lastBallX = -1, lastBallY = -1;
    private int trailTicks = 0; // Trail fade timer
    
    @Override
    public void initGame() {
        // If this is a new run (reset was pressed), reset score-related fields
        if (newRun) {
            currentLevelIndex = 0;
            totalMoves = 0;
            newRun = false;
        }
        
        // Reset runtime state for current level
        moves = 0;
        levelComplete = false;
        gameOver = false;
        levelIntroTicks = 20; // Flash effect duration
        portalPulse = 0.0f;
        trailTicks = 0;
        
        // Generate level based on current difficulty
        generateLevel(currentLevelIndex);
        
        // Initialize last ball position
        lastBallX = ballX;
        lastBallY = ballY;
    }
    
    /**
     * Reset all levels and start from level 1.
     * This is called when the Reset button is pressed.
     */
    public void resetAllLevels() {
        newRun = true; // Mark as new run to reset score in initGame()
        initGame();
    }
    
    @Override
    public void tick() {
        // Decrement level intro flash counter
        if (levelIntroTicks > 0) {
            levelIntroTicks--;
        }
        
        // Update portal pulse animation
        portalPulse += 0.15f;
        
        // Update trail fade
        if (trailTicks > 0) {
            trailTicks--;
        }
    }
    
    @Override
    public void handleMouseClick(double mouseX, double mouseY, int button) {
        // Not used
    }
    
    @Override
    public void handleKeyPress(int keyCode) {
        if (gameOver) return;
        if (levelComplete) return; // Wait for level transition
        
        Direction dir = null;
        
        // Arrow keys: 265=UP, 264=DOWN, 263=LEFT, 262=RIGHT
        // WASD: 87=W, 83=S, 65=A, 68=D
        if (keyCode == 265 || keyCode == 87) { // UP or W
            dir = Direction.UP;
        } else if (keyCode == 264 || keyCode == 83) { // DOWN or S
            dir = Direction.DOWN;
        } else if (keyCode == 263 || keyCode == 65) { // LEFT or A
            dir = Direction.LEFT;
        } else if (keyCode == 262 || keyCode == 68) { // RIGHT or D
            dir = Direction.RIGHT;
        } else {
            return;
        }
        
        // Use unified tilt simulation
        TiltResult result = simulateTilt(grid, width, height, ballX, ballY, dir);
        
        // Check if position changed
        if (result.finalX != ballX || result.finalY != ballY) {
            // Store previous position for trail
            lastBallX = ballX;
            lastBallY = ballY;
            trailTicks = 2; // Show trail for 2 frames
            
            ballX = result.finalX;
            ballY = result.finalY;
            moves++;
            totalMoves++;
            
            // Check if goal reached
            if (result.reachedGoal) {
                // Level complete!
                levelComplete = true;
                
                // Progress to next level (endless progression)
                currentLevelIndex++; // Increase difficulty
                initGame(); // Generate next level
            }
        }
    }
    
    @Override
    public void render(GuiGraphics g, Font font, int areaX, int areaY, int areaWidth, int areaHeight, float partialTicks) {
        // Debug: Show unsolvable level warning
        if (devLevelUnsolvable) {
            g.drawString(font, Component.literal("UNSOLVABLE LEVEL (DEV)"), areaX + 4, areaY + 4, 0xFFFF4444, true);
        }
        
        int cellWidth = areaWidth / width;
        int cellHeight = areaHeight / height;
        
        // Board background (wood/metal feel - single muted color)
        g.fill(areaX, areaY, areaX + areaWidth, areaY + areaHeight, 0xFF8B7355);
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int px = areaX + x * cellWidth;
                int py = areaY + y * cellHeight;
                int tile = grid[y][x];
                
                if (isTraversable(x, y)) {
                    // Traversable tile (EMPTY or GOAL) - tan color with inset
                    // Outer border
                    g.fill(px, py, px + cellWidth, py + cellHeight, 0xFF7B6345);
                    // Inner fill with slight inset
                    int inset = 2;
                    g.fill(px + inset, py + inset, px + cellWidth - inset, py + cellHeight - inset, 0xFF9B8365);
                    
                    // If it's a goal, draw portal
                    if (tile == TILE_GOAL) {
                        drawPortal(g, px, py, px + cellWidth, py + cellHeight, partialTicks);
                    }
                } else {
                    // Wall - dark blocks with cross-hatch pattern
                    g.fill(px, py, px + cellWidth, py + cellHeight, 0xFF000000); // Outer border
                    g.fill(px + 1, py + 1, px + cellWidth - 1, py + cellHeight - 1, 0xFF202020);
                    
                    // Cross-hatch pattern (plus sign style - thin lines)
                    int hatchColor = 0xFF303030;
                    int centerX = px + cellWidth / 2;
                    int centerY = py + cellHeight / 2;
                    
                    // Vertical line
                    g.fill(centerX, py + 2, centerX + 1, py + cellHeight - 2, hatchColor);
                    // Horizontal line
                    g.fill(px + 2, centerY, px + cellWidth - 2, centerY + 1, hatchColor);
                }
                
                // Grid lines
                g.fill(px + cellWidth - 1, py, px + cellWidth, py + cellHeight, 0xFF6B5B45);
                g.fill(px, py + cellHeight - 1, px + cellWidth, py + cellHeight, 0xFF6B5B45);
            }
        }
        
        // Draw trail if ball just moved
        if (trailTicks > 0 && lastBallX >= 0 && lastBallY >= 0) {
            int trailCellLeft = areaX + lastBallX * cellWidth;
            int trailCellTop = areaY + lastBallY * cellHeight;
            int trailCellRight = trailCellLeft + cellWidth;
            int trailCellBottom = trailCellTop + cellHeight;
            int trailCx = (trailCellLeft + trailCellRight) / 2;
            int trailCy = (trailCellTop + trailCellBottom) / 2;
            int trailRadius = Math.min(cellWidth, cellHeight) / 2 - 2;
            
            // Faint trail circle
            int trailAlpha = (trailTicks * 0x40) / 2; // Fade out over 2 frames
            fillCircle(g, trailCx, trailCy, trailRadius, (trailAlpha << 24) | 0xFFFFFF00);
        }
        
        // Draw glossy marble ball
        int cellLeft = areaX + ballX * cellWidth;
        int cellTop = areaY + ballY * cellHeight;
        int cellRight = cellLeft + cellWidth;
        int cellBottom = cellTop + cellHeight;
        
        int cx = (cellLeft + cellRight) / 2;
        int cy = (cellTop + cellBottom) / 2;
        int radius = Math.min(cellRight - cellLeft, cellBottom - cellTop) / 2 - 2;
        
        // Ball colors
        int baseColor = 0xFFFBC02D;   // golden
        int topColor = 0xFFFFF7C0;    // light sunrise gleam (top)
        int bottomColor = 0xCCCA8A00; // warm subtle shadow (bottom)
        int highlight = 0xCCFFFFFF;   // soft white spec (80% alpha)
        
        // Draw the base circle
        fillCircle(g, cx, cy, radius, baseColor);
        
        // Apply vertical gradient inside the circle
        fillCircleWithGradient(g, cx, cy, radius, topColor, bottomColor);
        
        // Draw a soft specular highlight (smaller, more subtle)
        fillCircle(g, cx - radius / 3, cy - radius / 3, radius / 4, highlight);
        
        // Draw a faint outline for better visibility
        drawCircleOutline(g, cx, cy, radius, 0x55FFFFFF); // soft white outline, 30% opacity
        
        // Level intro flash effect
        if (levelIntroTicks > 0) {
            float alpha = levelIntroTicks / 20.0f;
            int flashColor = (int)(alpha * 0x40) << 24 | 0xFFFFFF; // White flash fading out
            g.fill(areaX, areaY, areaX + areaWidth, areaY + areaHeight, flashColor);
        }
    }
    
    @Override
    public String getTitle() {
        return "Marble Tilt";
    }
    
    @Override
    public boolean isGameOver() {
        return gameOver;
    }
    
    @Override
    public int getScore() {
        // Higher score is better: more levels completed with fewer moves
        // Formula: (levels completed * 100) - total moves, clamped at minimum 0
        return Math.max(0, currentLevelIndex * 100 - totalMoves);
    }
    
    /**
     * Get the current level index (0-based).
     */
    public int getCurrentLevelIndex() {
        return currentLevelIndex;
    }
    
    @Override
    public void onClose() {
        // No cleanup needed
    }
    
    /**
     * Check if a tile at the given coordinates is traversable.
     * Traversable tiles are EMPTY and GOAL (tan color).
     * Non-traversable tiles are WALL (dark color).
     */
    private boolean isTraversable(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return false; // Out of bounds is not traversable
        }
        int tile = grid[y][x];
        return tile == TILE_EMPTY || tile == TILE_GOAL;
    }
    
    /**
     * Check if a tile at the given coordinates is traversable on a specific grid.
     * Pure function that doesn't use instance fields.
     */
    private static boolean isTraversableOnGrid(int[][] grid, int w, int h, int x, int y) {
        if (x < 0 || x >= w || y < 0 || y >= h) {
            return false; // Out of bounds is not traversable
        }
        int tile = grid[y][x];
        return tile == TILE_EMPTY || tile == TILE_GOAL;
    }
    
    /**
     * Shared tilt simulator - used by BOTH gameplay and solver.
     * This is the single source of truth for tilt movement logic.
     * 
     * @param grid The grid to simulate on
     * @param w Width of the grid
     * @param h Height of the grid
     * @param startX Starting X position
     * @param startY Starting Y position
     * @param dir Direction to tilt
     * @return TiltResult with final position and whether goal was reached
     */
    private static TiltResult simulateTilt(int[][] grid, int w, int h, int startX, int startY, Direction dir) {
        int x = startX;
        int y = startY;
        
        while (true) {
            int nextX = x + dir.dx;
            int nextY = y + dir.dy;
            
            // Check bounds
            if (nextX < 0 || nextX >= w || nextY < 0 || nextY >= h) {
                break; // Stop before leaving bounds
            }
            
            // Check if traversable using the same logic as gameplay
            if (!isTraversableOnGrid(grid, w, h, nextX, nextY)) {
                break; // Stop before entering a wall
            }
            
            // Move to next position
            x = nextX;
            y = nextY;
            
            // Check if goal reached
            if (grid[y][x] == TILE_GOAL) {
                return new TiltResult(x, y, true);
            }
        }
        
        return new TiltResult(x, y, false);
    }
    
    /**
     * Returns the minimum number of tilt moves required to reach the goal
     * from (startX, startY) on the given grid, or -1 if no solution exists.
     * Uses the shared simulateTilt method - same logic as gameplay.
     */
    private static int findShortestSolutionLength(int[][] grid, int startX, int startY) {
        int h = grid.length;
        int w = grid[0].length;
        
        // Find goal position
        int goalX = -1, goalY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (grid[y][x] == TILE_GOAL) {
                    goalX = x;
                    goalY = y;
                    break;
                }
            }
        }
        
        if (goalX == -1) {
            return -1; // no goal found
        }
        
        // BFS setup - state is (x, y, movesSoFar)
        boolean[][] visited = new boolean[h][w];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{startX, startY, 0});
        visited[startY][startX] = true;
        
        // Try each tilt direction using shared simulateTilt
        Direction[] directions = {Direction.UP, Direction.DOWN, Direction.LEFT, Direction.RIGHT};
        
        while (!queue.isEmpty()) {
            int[] state = queue.poll();
            int px = state[0];
            int py = state[1];
            int movesSoFar = state[2];
            
            // If this position is the goal
            if (px == goalX && py == goalY) {
                return movesSoFar;
            }
            
            // Try each tilt direction - uses same simulateTilt as gameplay
            for (Direction dir : directions) {
                TiltResult result = simulateTilt(grid, w, h, px, py, dir);
                
                // If goal reached, return immediately
                if (result.reachedGoal) {
                    return movesSoFar + 1;
                }
                
                // If position didn't change, skip
                if (result.finalX == px && result.finalY == py) {
                    continue;
                }
                
                // Already visited?
                if (visited[result.finalY][result.finalX]) {
                    continue;
                }
                
                visited[result.finalY][result.finalX] = true;
                queue.add(new int[]{result.finalX, result.finalY, movesSoFar + 1});
            }
        }
        
        return -1; // No path found
    }
    
    /**
     * Builds a random level grid with walls based on density.
     * Helper method for level generation.
     */
    private void buildRandomLevelGrid(int[][] grid, int startX, int startY, int goalX, int goalY, double density) {
        // Outer border walls
        for (int x = 0; x < BOARD_WIDTH; x++) {
            grid[0][x] = TILE_WALL;
            grid[BOARD_HEIGHT - 1][x] = TILE_WALL;
        }
        for (int y = 0; y < BOARD_HEIGHT; y++) {
            grid[y][0] = TILE_WALL;
            grid[y][BOARD_WIDTH - 1] = TILE_WALL;
        }
        
        // Interior cells start as empty
        for (int y = 1; y < BOARD_HEIGHT - 1; y++) {
            for (int x = 1; x < BOARD_WIDTH - 1; x++) {
                grid[y][x] = TILE_EMPTY;
            }
        }
        
        // Place GOAL tile
        grid[goalY][goalX] = TILE_GOAL;
        
        // Add walls with given density (skip start and goal)
        for (int y = 1; y < BOARD_HEIGHT - 1; y++) {
            for (int x = 1; x < BOARD_WIDTH - 1; x++) {
                if ((x == startX && y == startY) || (x == goalX && y == goalY)) {
                    continue;
                }
                if (random.nextDouble() < density) {
                    grid[y][x] = TILE_WALL;
                }
            }
        }
    }
    
    /**
     * Builds a guaranteed solvable fallback level (simple straight path).
     */
    private void buildGuaranteedFallbackLevel(int[][] grid) {
        // Border walls
        for (int x = 0; x < BOARD_WIDTH; x++) {
            grid[0][x] = TILE_WALL;
            grid[BOARD_HEIGHT - 1][x] = TILE_WALL;
        }
        for (int y = 0; y < BOARD_HEIGHT; y++) {
            grid[y][0] = TILE_WALL;
            grid[y][BOARD_WIDTH - 1] = TILE_WALL;
        }
        
        // Interior empty (open path)
        for (int y = 1; y < BOARD_HEIGHT - 1; y++) {
            for (int x = 1; x < BOARD_WIDTH - 1; x++) {
                grid[y][x] = TILE_EMPTY;
            }
        }
        
        // Goal on right interior (straight horizontal path from left to right)
        int goalX = BOARD_WIDTH - 2;
        int goalY = BOARD_HEIGHT / 2;
        
        grid[goalY][goalX] = TILE_GOAL;
        
        // This layout is always solvable (straight path from start to goal)
    }
    
    /**
     * Generates a new board for the given difficulty level index.
     * Populates the instance fields grid, width, height, ballX, ballY.
     * Only accepts levels that are verified solvable by the shared solver.
     */
    private void generateLevel(int difficultyLevel) {
        // Derive parameters from difficulty
        int minMoves = BASE_MIN_MOVES + difficultyLevel * MOVES_PER_LEVEL;
        if (minMoves > MAX_MIN_MOVES) {
            minMoves = MAX_MIN_MOVES;
        }
        
        double density = BASE_WALL_DENSITY + difficultyLevel * WALL_DENSITY_PER_LEVEL;
        if (density > MAX_WALL_DENSITY) {
            density = MAX_WALL_DENSITY;
        }
        
        // Generation loop - regenerate until solvable with required minimum moves
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            int[][] candidate = new int[BOARD_HEIGHT][BOARD_WIDTH];
            
            // Pick start & goal positions
            int startX, startY, goalX, goalY;
            int tries = 0;
            do {
                startX = 1 + random.nextInt(BOARD_WIDTH - 2);
                startY = 1 + random.nextInt(BOARD_HEIGHT - 2);
                goalX = 1 + random.nextInt(BOARD_WIDTH - 2);
                goalY = 1 + random.nextInt(BOARD_HEIGHT - 2);
                tries++;
            } while (tries < 100 && 
                     ((startX == goalX && startY == goalY) || 
                      (startX == goalX || startY == goalY) ||
                      (Math.abs(startX - goalX) + Math.abs(startY - goalY) < 4)));
            
            if (tries >= 100) {
                continue; // Try again
            }
            
            // Build random level grid
            buildRandomLevelGrid(candidate, startX, startY, goalX, goalY, density);
            
            // Validate solvability using shared solver (same logic as gameplay)
            int solutionLength = findShortestSolutionLength(candidate, startX, startY);
            
            // Only accept if solvable (solutionLength != -1) and meets minimum moves
            if (solutionLength >= 0 && solutionLength >= minMoves) {
                // Accept this candidate
                this.grid = candidate;
                this.width = BOARD_WIDTH;
                this.height = BOARD_HEIGHT;
                this.ballX = startX;
                this.ballY = startY;
                devLevelUnsolvable = false;
                CosmeticsLite.LOGGER.debug("MarbleTiltGame: Generated level {} with solution length {} (min: {}), wall density: {:.2f}", 
                    difficultyLevel + 1, solutionLength, minMoves, String.format("%.2f", density));
                return;
            }
        }
        
        // Fallback: relax constraints and accept any solvable board
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            int[][] candidate = new int[BOARD_HEIGHT][BOARD_WIDTH];
            
            // Pick start & goal
            int startX, startY, goalX, goalY;
            int tries = 0;
            do {
                startX = 1 + random.nextInt(BOARD_WIDTH - 2);
                startY = 1 + random.nextInt(BOARD_HEIGHT - 2);
                goalX = 1 + random.nextInt(BOARD_WIDTH - 2);
                goalY = 1 + random.nextInt(BOARD_HEIGHT - 2);
                tries++;
            } while ((startX == goalX && startY == goalY) && tries < 100);
            
            if (tries >= 100) {
                continue;
            }
            
            // Build with reduced density
            double fallbackDensity = Math.min(density * 0.7, 0.25);
            buildRandomLevelGrid(candidate, startX, startY, goalX, goalY, fallbackDensity);
            
            // Validate solvability - only accept if solvable
            int solutionLength = findShortestSolutionLength(candidate, startX, startY);
            if (solutionLength >= 0) {
                // Accept any solvable board in fallback phase
                this.grid = candidate;
                this.width = BOARD_WIDTH;
                this.height = BOARD_HEIGHT;
                this.ballX = startX;
                this.ballY = startY;
                devLevelUnsolvable = false;
                CosmeticsLite.LOGGER.debug("MarbleTiltGame: Generated fallback level {} with solution length {} (min: {}), wall density: {:.2f}", 
                    difficultyLevel + 1, solutionLength, minMoves, String.format("%.2f", fallbackDensity));
                return;
            }
        }
        
        // Ultimate fallback: simple open board (guaranteed solvable)
        CosmeticsLite.LOGGER.warn("MarbleTiltGame: failed to generate solvable board at difficulty {}, using trivial fallback", difficultyLevel);
        grid = new int[BOARD_HEIGHT][BOARD_WIDTH];
        buildGuaranteedFallbackLevel(grid);
        
        // Set start position
        int fallbackStartX = 1;
        int fallbackStartY = BOARD_HEIGHT / 2;
        ballX = fallbackStartX;
        ballY = fallbackStartY;
        width = BOARD_WIDTH;
        height = BOARD_HEIGHT;
        
        // Verify fallback board is solvable (should always be true)
        int fallbackSolutionLength = findShortestSolutionLength(grid, fallbackStartX, fallbackStartY);
        if (fallbackSolutionLength < 0) {
            // This should never happen, but log if it does
            CosmeticsLite.LOGGER.error("MarbleTiltGame: Ultimate fallback board is unsolvable! This is a bug.");
            devLevelUnsolvable = true;
        } else {
            devLevelUnsolvable = false;
            CosmeticsLite.LOGGER.debug("MarbleTiltGame: Using trivial fallback level {} with solution length {}", 
                difficultyLevel + 1, fallbackSolutionLength);
        }
    }
    
    private void drawPortal(GuiGraphics g, int cellLeft, int cellTop, int cellRight, int cellBottom, float partialTicks) {
        // Base tan fill is already drawn as traversable tile
        
        // Portal colors
        int goalOuter = 0xFF00FF44;  // bright neon green
        int goalInner = 0xFF007F22;  // darker green core
        
        // Outer glowing frame
        int frameThickness = 3;
        fillFrame(g, cellLeft, cellTop, cellRight, cellBottom, frameThickness, goalOuter);
        
        // Inner portal window (slightly inset)
        int inset = frameThickness + 2;
        g.fill(cellLeft + inset, cellTop + inset, cellRight - inset, cellBottom - inset, goalInner);
        
        // Pulsing glow (animation)
        float currentPulse = portalPulse + partialTicks * 0.15f;
        float pulse = 0.5f + 0.5f * (float)Math.sin(currentPulse);
        int pulseInset = inset + 2;
        int pulseAlpha = (int)(80 + 80 * pulse) & 0xFF;
        int pulseColor = (pulseAlpha << 24) | 0x00FF88; // translucent bright green
        
        g.fill(cellLeft + pulseInset, cellTop + pulseInset,
               cellRight - pulseInset, cellBottom - pulseInset, pulseColor);
        
        // Portal chevron / pointer (small white arrow in center)
        int centerX = (cellLeft + cellRight) / 2;
        int centerY = (cellTop + cellBottom) / 2;
        int chevronSize = 4;
        
        // Draw small triangle pointing inward (downward arrow)
        for (int i = 0; i < chevronSize; i++) {
            int width = chevronSize - i;
            g.fill(centerX - width, centerY - chevronSize + i, 
                   centerX + width, centerY - chevronSize + i + 1, 0xFFFFFFFF);
        }
    }
    
    // Helper method to fill a circle
    private void fillCircle(GuiGraphics g, int cx, int cy, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dy * dy <= radius * radius) {
                    g.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
                }
            }
        }
    }
    
    // Helper method to fill a circle with vertical gradient
    private void fillCircleWithGradient(GuiGraphics g, int cx, int cy, int radius, int topColor, int bottomColor) {
        float height = radius * 2f;
        
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dy * dy <= radius * radius) {
                    // Calculate gradient factor (0.0 at top, 1.0 at bottom)
                    float t = (float)(dy + radius) / height;
                    int color = lerpColor(topColor, bottomColor, t);
                    g.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
                }
            }
        }
    }
    
    // Helper method to interpolate between two colors
    private int lerpColor(int color1, int color2, float t) {
        t = Math.max(0.0f, Math.min(1.0f, t)); // Clamp t to [0, 1]
        
        int a1 = (color1 >> 24) & 0xFF;
        int r1 = (color1 >> 16) & 0xFF;
        int g1 = (color1 >> 8) & 0xFF;
        int b1 = color1 & 0xFF;
        
        int a2 = (color2 >> 24) & 0xFF;
        int r2 = (color2 >> 16) & 0xFF;
        int g2 = (color2 >> 8) & 0xFF;
        int b2 = color2 & 0xFF;
        
        int a = (int)(a1 + (a2 - a1) * t);
        int r = (int)(r1 + (r2 - r1) * t);
        int g = (int)(g1 + (g2 - g1) * t);
        int b = (int)(b1 + (b2 - b1) * t);
        
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
    
    // Helper method to draw a circle outline
    private void drawCircleOutline(GuiGraphics g, int cx, int cy, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int distSq = dx * dx + dy * dy;
                // Draw pixels near the edge (within 1-2 pixels of radius)
                if (distSq >= (radius - 1) * (radius - 1) && distSq <= radius * radius) {
                    g.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
                }
            }
        }
    }
    
    // Helper method to fill a frame (border)
    private void fillFrame(GuiGraphics g, int left, int top, int right, int bottom, int thickness, int color) {
        // Top
        g.fill(left, top, right, top + thickness, color);
        // Bottom
        g.fill(left, bottom - thickness, right, bottom, color);
        // Left
        g.fill(left, top, left + thickness, bottom, color);
        // Right
        g.fill(right - thickness, top, right, bottom, color);
    }
    
}

