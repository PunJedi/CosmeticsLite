package com.pastlands.cosmeticslite.minigame.impl.tilt;

import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.minigame.api.MiniGame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

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
    private int score = 0; // current run's score
    private int bestScore = 0; // highest score this session
    private int levelIntroTicks; // for level transition flash effect
    private boolean devLevelUnsolvable = false; // Debug flag for unsolvable levels
    private boolean newRun = false; // Flag to indicate a new run (reset was pressed)
    private final Random random = new Random();
    
    // Visual-only animation fields
    private float portalPulse = 0.0f;
    private int lastBallX = -1, lastBallY = -1;
    private int trailTicks = 0; // Trail fade timer
    
    // Ball scale animation
    private float ballScale = 1.0f;
    private float ballScaleVelocity = 0.0f;
    private static final float BALL_SCALE_EASE_SPEED = 0.1f; // Easing speed for scale animation
    
    // Movement tracking for sound spam protection
    private boolean isMoving = false; // Track if ball is currently moving
    
    @Override
    public void initGame() {
        // If this is a new run (reset was pressed), reset score-related fields
        if (newRun) {
            // Reset only the current run's score, not bestScore
            score = 0;
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
        ballScale = 1.0f;
        ballScaleVelocity = 0.0f;
        isMoving = false;
        
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
        playLocalSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.3F, 1.0F);
        initGame();
    }
    
    @Override
    public void tick() {
        // Decrement level intro flash counter
        if (levelIntroTicks > 0) {
            levelIntroTicks--;
        }
        
        // Update portal pulse animation (0..1 loop)
        portalPulse = (portalPulse + 0.15f) % 1.0f;
        
        // Update trail fade
        if (trailTicks > 0) {
            trailTicks--;
        }
        
        // Update ball scale animation (ease back to 1.0)
        if (ballScale > 1.0f) {
            ballScaleVelocity -= BALL_SCALE_EASE_SPEED;
            ballScale += ballScaleVelocity;
            if (ballScale <= 1.0f) {
                ballScale = 1.0f;
                ballScaleVelocity = 0.0f;
            }
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
        if (isMoving) return; // Prevent spam - ignore if already moving
        
        int dx = 0, dy = 0;
        
        // Arrow keys: 265=UP, 264=DOWN, 263=LEFT, 262=RIGHT
        // WASD: 87=W, 83=S, 65=A, 68=D
        if (keyCode == 265 || keyCode == 87) { // UP or W
            dy = -1;
        } else if (keyCode == 264 || keyCode == 83) { // DOWN or S
            dy = 1;
        } else if (keyCode == 263 || keyCode == 65) { // LEFT or A
            dx = -1;
        } else if (keyCode == 262 || keyCode == 68) { // RIGHT or D
            dx = 1;
        } else {
            return;
        }
        
        // Use unified tilt simulation
        int[] result = simulateTilt(grid, ballX, ballY, dx, dy);
        int newX = result[0];
        int newY = result[1];
        
        // Check if position changed
        if (newX != ballX || newY != ballY) {
            // Ball moved - start movement state
            isMoving = true;
            
            // Store previous position for trail
            lastBallX = ballX;
            lastBallY = ballY;
            trailTicks = 2; // Show trail for 2 frames
            
            // Start ball scale animation
            ballScale = 1.05f;
            ballScaleVelocity = -0.01f; // Will ease back to 1.0
            
            // Play slide sound
            playLocalSound(SoundEvents.STONE_STEP, 0.25F, 1.2F);
            
            ballX = newX;
            ballY = newY;
            moves++;
            totalMoves++;
            
            // Check if goal reached
            if (grid[ballY][ballX] == TILE_GOAL) {
                // Level complete!
                levelComplete = true;
                isMoving = false; // Reset movement state
                
                // Play goal reached sound
                playLocalSound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.6F, 1.4F);
                
                // Calculate and update score (formula: levels * 100 - totalMoves, clamped at 0)
                // Note: currentLevelIndex will be incremented next, so use currentLevelIndex + 1 for the completed level
                int newScore = Math.max(0, (currentLevelIndex + 1) * 100 - totalMoves);
                score = newScore;
                
                // Update best score only if current score beats it
                if (score > bestScore) {
                    bestScore = score;
                }
                
                // Progress to next level (endless progression)
                currentLevelIndex++; // Increase difficulty
                initGame(); // Generate next level
            } else {
                // Ball stopped (hit wall or edge) - play bump sound
                playLocalSound(SoundEvents.STONE_PLACE, 0.2F, 0.7F);
                isMoving = false; // Reset movement state
            }
        } else {
            // Ball didn't move (blocked) - no sound, no animation
        }
    }
    
    @Override
    public void render(GuiGraphics g, Font font, int areaX, int areaY, int areaWidth, int areaHeight, float partialTicks) {
        // Draw HUD at top
        String levelText = "Level: " + (currentLevelIndex + 1);
        g.drawString(font, Component.literal(levelText), areaX + 4, areaY + 4, 0xFFFFFF, true);
        
        // Draw score and best score in top-right (two lines)
        String scoreText = "Score: " + score;
        String bestText = "Best: " + bestScore;
        int scoreTextWidth = font.width(scoreText);
        int bestTextWidth = font.width(bestText);
        int rightX = areaX + areaWidth - Math.max(scoreTextWidth, bestTextWidth) - 4;
        int topY = areaY + 4;
        int lineSpacing = font.lineHeight + 2;
        g.drawString(font, Component.literal(scoreText), rightX, topY, 0xFFFFFFFF, true);
        g.drawString(font, Component.literal(bestText), rightX, topY + lineSpacing, 0xFFFFFFFF, true);
        
        // Debug: Show unsolvable level warning
        if (devLevelUnsolvable) {
            g.drawString(font, Component.literal("UNSOLVABLE LEVEL (DEV)"), areaX + 4, areaY + 20, 0xFFFF4444, true);
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
                    
                    // If it's a goal, draw portal (base tan is already drawn)
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
        
        // Draw ball highlight box (current position) - draw before ball so ball is on top
        int highlightColor = 0xFF7CFC00; // lime green
        int highlightThickness = 2;
        int ballCellLeft = areaX + ballX * cellWidth;
        int ballCellTop = areaY + ballY * cellHeight;
        int ballCellRight = ballCellLeft + cellWidth;
        int ballCellBottom = ballCellTop + cellHeight;
        g.fill(ballCellLeft, ballCellTop, ballCellRight, ballCellTop + highlightThickness, highlightColor);
        g.fill(ballCellLeft, ballCellBottom - highlightThickness, ballCellRight, ballCellBottom, highlightColor);
        g.fill(ballCellLeft, ballCellTop, ballCellLeft + highlightThickness, ballCellBottom, highlightColor);
        g.fill(ballCellRight - highlightThickness, ballCellTop, ballCellRight, ballCellBottom, highlightColor);
        
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
        
        // Draw glossy marble ball (with scale applied)
        int cellLeft = areaX + ballX * cellWidth;
        int cellTop = areaY + ballY * cellHeight;
        int cellRight = cellLeft + cellWidth;
        int cellBottom = cellTop + cellHeight;
        
        int cx = (cellLeft + cellRight) / 2;
        int cy = (cellTop + cellBottom) / 2;
        int baseRadius = Math.min(cellRight - cellLeft, cellBottom - cellTop) / 2 - 2;
        int radius = (int)(baseRadius * ballScale); // Apply scale animation
        
        // Ball colors
        int baseColor = 0xFFFBC02D;   // golden
        int topColor = 0xFFFFF7C0;    // light sunrise gleam (top)
        int bottomColor = 0xCCCA8A00; // warm subtle shadow (bottom)
        int highlight = 0xCCFFFFFF;   // soft white spec (80% alpha)
        
        // Draw the base circle
        fillCircle(g, cx, cy, radius, baseColor);
        
        // Apply vertical gradient inside the circle
        fillCircleWithGradient(g, cx, cy, radius, topColor, bottomColor);
        
        // Draw a soft specular highlight (upper-left, smaller, more subtle)
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
        // Return the current run's score
        return score;
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
     * Shared tilt simulator - used by BOTH gameplay and solver.
     * This is the single source of truth for tilt movement logic.
     * 
     * Helper: given a grid and a starting (x,y), tilt in (dx,dy) until we stop.
     * Returns final position as int[2] {finalX, finalY}.
     * 
     * @param grid The grid to simulate on
     * @param startX Starting X position
     * @param startY Starting Y position
     * @param dx X direction (-1, 0, or 1)
     * @param dy Y direction (-1, 0, or 1)
     * @return int[2] with final position {finalX, finalY}
     */
    private static int[] simulateTilt(int[][] grid, int startX, int startY, int dx, int dy) {
        int w = grid[0].length;
        int h = grid.length;
        int x = startX;
        int y = startY;
        
        while (true) {
            int nextX = x + dx;
            int nextY = y + dy;
            
            // Bounds check
            if (nextX < 0 || nextX >= w || nextY < 0 || nextY >= h) {
                break;
            }
            
            int tile = grid[nextY][nextX];
            
            // Stop on wall
            if (tile == TILE_WALL) {
                break;
            }
            
            // Move into empty/goal
            x = nextX;
            y = nextY;
            
            // Note: We allow rolling past the goal (don't break here)
            // The caller can check if grid[y][x] == TILE_GOAL
        }
        
        return new int[]{x, y};
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
        
        // Try each tilt direction: (1,0), (-1,0), (0,1), (0,-1)
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        
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
            for (int[] dir : directions) {
                int dx = dir[0];
                int dy = dir[1];
                int[] result = simulateTilt(grid, px, py, dx, dy);
                int nx = result[0];
                int ny = result[1];
                
                // If state didn't change, skip (no-op tilt)
                if (nx == px && ny == py) {
                    continue;
                }
                
                // If goal reached, return immediately
                if (grid[ny][nx] == TILE_GOAL) {
                    return movesSoFar + 1;
                }
                
                // Already visited?
                if (visited[ny][nx]) {
                    continue;
                }
                
                visited[ny][nx] = true;
                queue.add(new int[]{nx, ny, movesSoFar + 1});
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
            
            // Dev assertion: check for unsolvable levels
            if (solutionLength < 0) {
                CosmeticsLite.LOGGER.error("MarbleTiltGame: Solver claims UNSOLVABLE level at difficulty {}. Dumping grid...", difficultyLevel + 1);
                // Dump grid rows with tile values
                for (int y = 0; y < BOARD_HEIGHT; y++) {
                    StringBuilder row = new StringBuilder();
                    for (int x = 0; x < BOARD_WIDTH; x++) {
                        int tile = candidate[y][x];
                        if (tile == TILE_WALL) {
                            row.append('#');
                        } else if (tile == TILE_GOAL) {
                            row.append('G');
                        } else if (x == startX && y == startY) {
                            row.append('S');
                        } else {
                            row.append('.');
                        }
                    }
                    CosmeticsLite.LOGGER.error("  Row {}: {}", y, row.toString());
                }
                continue; // Reject this candidate
            }
            
            // Only accept if solvable (solutionLength != -1) and meets minimum moves
            if (solutionLength >= minMoves) {
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
            
            // Dev assertion: check for unsolvable levels in fallback phase
            if (solutionLength < 0) {
                CosmeticsLite.LOGGER.error("MarbleTiltGame: Solver claims UNSOLVABLE fallback level at difficulty {}. Dumping grid...", difficultyLevel + 1);
                // Dump grid rows with tile values
                for (int y = 0; y < BOARD_HEIGHT; y++) {
                    StringBuilder row = new StringBuilder();
                    for (int x = 0; x < BOARD_WIDTH; x++) {
                        int tile = candidate[y][x];
                        if (tile == TILE_WALL) {
                            row.append('#');
                        } else if (tile == TILE_GOAL) {
                            row.append('G');
                        } else if (x == startX && y == startY) {
                            row.append('S');
                        } else {
                            row.append('.');
                        }
                    }
                    CosmeticsLite.LOGGER.error("  Row {}: {}", y, row.toString());
                }
                continue; // Reject this candidate
            }
            
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
        int goalOutline = 0xFF44FF88; // lighter green outline
        
        // Calculate pulse animation (0..1 loop)
        float currentPulse = (portalPulse + partialTicks * 0.15f) % 1.0f;
        float pulse = 0.5f + 0.5f * (float)Math.sin(currentPulse * 2.0f * (float)Math.PI);
        
        // Inner portal window (bright green square/circle that pulses)
        int inset = 4;
        int innerLeft = cellLeft + inset;
        int innerTop = cellTop + inset;
        int innerRight = cellRight - inset;
        int innerBottom = cellBottom - inset;
        
        // Pulse size slightly (optional - can also pulse brightness)
        float sizePulse = 0.9f + 0.1f * pulse; // 0.9 to 1.0
        int centerX = (cellLeft + cellRight) / 2;
        int centerY = (cellTop + cellBottom) / 2;
        int innerWidth = (int)((innerRight - innerLeft) * sizePulse);
        int innerHeight = (int)((innerBottom - innerTop) * sizePulse);
        int pulsedLeft = centerX - innerWidth / 2;
        int pulsedTop = centerY - innerHeight / 2;
        int pulsedRight = centerX + innerWidth / 2;
        int pulsedBottom = centerY + innerHeight / 2;
        
        // Draw bright green inner square (pulsing)
        int innerBrightness = (int)(180 + 75 * pulse); // Pulse brightness
        int innerColor = (0xFF << 24) | ((innerBrightness & 0xFF) << 16) | 0x00FF44; // Bright green
        g.fill(pulsedLeft, pulsedTop, pulsedRight, pulsedBottom, innerColor);
        
        // Thin lighter green outline (so it pops)
        int outlineThickness = 1;
        g.fill(pulsedLeft, pulsedTop, pulsedRight, pulsedTop + outlineThickness, goalOutline);
        g.fill(pulsedLeft, pulsedBottom - outlineThickness, pulsedRight, pulsedBottom, goalOutline);
        g.fill(pulsedLeft, pulsedTop, pulsedLeft + outlineThickness, pulsedBottom, goalOutline);
        g.fill(pulsedRight - outlineThickness, pulsedTop, pulsedRight, pulsedBottom, goalOutline);
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

