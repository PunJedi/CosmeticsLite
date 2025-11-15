package com.pastlands.cosmeticslite.minigame.impl.snake;

import com.pastlands.cosmeticslite.minigame.api.MiniGame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Snake mini-game implementation.
 */
public class SnakeGame implements MiniGame {
    
    private static final int GRID_WIDTH = 20;
    private static final int GRID_HEIGHT = 20;
    private static final int TICK_INTERVAL = 5;
    
    private int tickCounter;
    private Direction direction;
    private List<Point> snakeBody;
    private Point food;
    private boolean gameOver;
    private Random random;
    private int initialLength;
    
    // Best score tracking (per session)
    private int bestScoreSnake = 0;
    
    // Visual-only animation fields
    private float foodPulse = 0.0f;
    private float lastHeadX, lastHeadY;
    private float renderHeadX, renderHeadY;
    private int deathFlashTicks = 0; // Death flash animation
    private int foodPopTicks = 0; // Food pop animation (0-6)
    private int moveCount = 0; // Track moves for subtle sound
    
    private enum Direction {
        UP, DOWN, LEFT, RIGHT
    }
    
    private static class Point {
        int x, y;
        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Point point = (Point) o;
            return x == point.x && y == point.y;
        }
        
        @Override
        public int hashCode() {
            return x * 31 + y;
        }
    }
    
    @Override
    public void initGame() {
        random = new Random();
        tickCounter = 0;
        direction = Direction.RIGHT;
        gameOver = false;
        foodPulse = 0.0f;
        deathFlashTicks = 0;
        foodPopTicks = 0;
        moveCount = 0;
        
        // Initialize snake in center with length 3
        snakeBody = new ArrayList<>();
        int centerX = GRID_WIDTH / 2;
        int centerY = GRID_HEIGHT / 2;
        snakeBody.add(new Point(centerX, centerY));
        snakeBody.add(new Point(centerX - 1, centerY));
        snakeBody.add(new Point(centerX - 2, centerY));
        initialLength = snakeBody.size();
        
        // Initialize render positions for smooth movement
        Point head = snakeBody.get(0);
        lastHeadX = head.x;
        lastHeadY = head.y;
        renderHeadX = head.x;
        renderHeadY = head.y;
        
        // Spawn initial food
        spawnFood();
    }
    
    private void spawnFood() {
        do {
            food = new Point(random.nextInt(GRID_WIDTH), random.nextInt(GRID_HEIGHT));
        } while (snakeBody.contains(food));
        // Start pop animation when food spawns
        foodPopTicks = 6;
    }
    
    @Override
    public void tick() {
        // Update animations
        foodPulse += 0.05f;
        if (deathFlashTicks > 0) {
            deathFlashTicks--;
        }
        if (foodPopTicks > 0) {
            foodPopTicks--;
        }
        
        if (gameOver) {
            // Continue animation even when game over
            return;
        }
        
        tickCounter++;
        
        if (tickCounter < TICK_INTERVAL) return;
        tickCounter = 0;
        
        // Calculate new head position
        Point head = snakeBody.get(0);
        
        // Store last position for interpolation
        lastHeadX = head.x;
        lastHeadY = head.y;
        
        Point newHead = new Point(head.x, head.y);
        
        switch (direction) {
            case UP -> newHead.y--;
            case DOWN -> newHead.y++;
            case LEFT -> newHead.x--;
            case RIGHT -> newHead.x++;
        }
        
        // Check bounds
        if (newHead.x < 0 || newHead.x >= GRID_WIDTH ||
            newHead.y < 0 || newHead.y >= GRID_HEIGHT) {
            gameOver = true;
            deathFlashTicks = 4;
            playLocalSound(SoundEvents.PLAYER_HURT, 0.7F, 0.8F);
            playLocalSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.4F, 0.6F);
            updateBestScore();
            return;
        }
        
        // Check collision with body
        for (Point segment : snakeBody) {
            if (newHead.equals(segment)) {
                gameOver = true;
                deathFlashTicks = 4;
                playLocalSound(SoundEvents.PLAYER_HURT, 0.7F, 0.8F);
                playLocalSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.4F, 0.6F);
                updateBestScore();
                return;
            }
        }
        
        // Add new head
        snakeBody.add(0, newHead);
        moveCount++;
        
        // Optional: subtle movement sound every 4 moves
        if (moveCount % 4 == 0) {
            playLocalSound(SoundEvents.NOTE_BLOCK_HAT.value(), 0.2F, 1.8F);
        }
        
        // Check if food eaten
        if (newHead.equals(food)) {
            spawnFood();
            // Food eaten sound with slight pitch variation
            float pitch = 1.1F + (random.nextFloat() * 0.2F - 0.1F); // 1.0-1.2
            playLocalSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.6F, pitch);
            updateBestScore();
        } else {
            // Remove tail
            snakeBody.remove(snakeBody.size() - 1);
        }
    }
    
    @Override
    public void handleMouseClick(double mouseX, double mouseY, int button) {
        // Not used for snake
    }
    
    @Override
    public void handleKeyPress(int keyCode) {
        if (gameOver) return;
        
        // Arrow keys: 265=UP, 264=DOWN, 263=LEFT, 262=RIGHT
        // WASD: 87=W, 83=S, 65=A, 68=D
        Direction newDirection = null;
        
        if (keyCode == 265 || keyCode == 87) { // UP or W
            newDirection = Direction.UP;
        } else if (keyCode == 264 || keyCode == 83) { // DOWN or S
            newDirection = Direction.DOWN;
        } else if (keyCode == 263 || keyCode == 65) { // LEFT or A
            newDirection = Direction.LEFT;
        } else if (keyCode == 262 || keyCode == 68) { // RIGHT or D
            newDirection = Direction.RIGHT;
        }
        
        if (newDirection != null) {
            // Prevent 180° reversal
            if ((direction == Direction.UP && newDirection == Direction.DOWN) ||
                (direction == Direction.DOWN && newDirection == Direction.UP) ||
                (direction == Direction.LEFT && newDirection == Direction.RIGHT) ||
                (direction == Direction.RIGHT && newDirection == Direction.LEFT)) {
                return;
            }
            direction = newDirection;
        }
    }
    
    @Override
    public void render(GuiGraphics g, net.minecraft.client.gui.Font font, int areaX, int areaY, int areaWidth, int areaHeight, float partialTicks) {
        int cellSize = Math.min(areaWidth / GRID_WIDTH, areaHeight / GRID_HEIGHT);
        int gridOffsetX = areaX + (areaWidth - cellSize * GRID_WIDTH) / 2;
        int gridOffsetY = areaY + (areaHeight - cellSize * GRID_HEIGHT) / 2;
        int boardLeft = gridOffsetX;
        int boardTop = gridOffsetY;
        int boardRight = gridOffsetX + GRID_WIDTH * cellSize;
        int boardBottom = gridOffsetY + GRID_HEIGHT * cellSize;
        
        // Draw subtle dark checkerboard background
        for (int x = 0; x < GRID_WIDTH; x++) {
            for (int y = 0; y < GRID_HEIGHT; y++) {
                int px = gridOffsetX + x * cellSize;
                int py = gridOffsetY + y * cellSize;
                // Very low contrast checkerboard
                int color = (x + y) % 2 == 0 ? 0xFF151515 : 0xFF181818;
                g.fill(px, py, px + cellSize, py + cellSize, color);
            }
        }
        
        // Draw subtle grid lines
        int gridLine = 0x20101010; // very low alpha
        for (int x = 0; x <= GRID_WIDTH; x++) {
            int sx = boardLeft + x * cellSize;
            g.fill(sx, boardTop, sx + 1, boardBottom, gridLine);
        }
        for (int y = 0; y <= GRID_HEIGHT; y++) {
            int sy = boardTop + y * cellSize;
            g.fill(boardLeft, sy, boardRight, sy + 1, gridLine);
        }
        
        // Draw border around grid
        int borderThickness = 1;
        g.fill(gridOffsetX - borderThickness, gridOffsetY - borderThickness,
            gridOffsetX + GRID_WIDTH * cellSize + borderThickness, gridOffsetY, 0xFF000000);
        g.fill(gridOffsetX - borderThickness, gridOffsetY + GRID_HEIGHT * cellSize,
            gridOffsetX + GRID_WIDTH * cellSize + borderThickness, gridOffsetY + GRID_HEIGHT * cellSize + borderThickness, 0xFF000000);
        g.fill(gridOffsetX - borderThickness, gridOffsetY,
            gridOffsetX, gridOffsetY + GRID_HEIGHT * cellSize, 0xFF000000);
        g.fill(gridOffsetX + GRID_WIDTH * cellSize, gridOffsetY,
            gridOffsetX + GRID_WIDTH * cellSize + borderThickness, gridOffsetY + GRID_HEIGHT * cellSize, 0xFF000000);
        
        // Update render head position with interpolation
        if (!gameOver && snakeBody.size() > 0) {
            Point head = snakeBody.get(0);
            float targetX = head.x;
            float targetY = head.y;
            
            // Interpolate render position toward target
            float lerpFactor = (tickCounter + partialTicks) / TICK_INTERVAL;
            renderHeadX = lastHeadX + (targetX - lastHeadX) * lerpFactor;
            renderHeadY = lastHeadY + (targetY - lastHeadY) * lerpFactor;
        }
        
        // Determine if we should draw snake in death flash color
        boolean drawDeathFlash = deathFlashTicks > 0;
        int snakeBodyColor = drawDeathFlash ? 0xFFFF4444 : 0xFF2FA94E; // Darker green for body
        int snakeHeadColor = drawDeathFlash ? 0xFFFF4444 : 0xFF6EE774; // Brighter green for head
        
        // Draw snake body (all segments except head)
        for (int i = 1; i < snakeBody.size(); i++) {
            Point segment = snakeBody.get(i);
            int px = gridOffsetX + segment.x * cellSize;
            int py = gridOffsetY + segment.y * cellSize;
            
            // Base body rect
            g.fill(px + 1, py + 1, px + cellSize - 1, py + cellSize - 1, snakeBodyColor);
            
            // Very light inner highlight strip (fake bevel) - only if not death flash
            if (!drawDeathFlash) {
                int highlightHeight = 2;
                g.fill(px + 1, py + 1, px + cellSize - 1, py + 1 + highlightHeight, 0x20FFFFFF);
            }
        }
        
        // Draw snake head with smooth interpolation
        if (snakeBody.size() > 0) {
            float headPx = gridOffsetX + renderHeadX * cellSize;
            float headPy = gridOffsetY + renderHeadY * cellSize;
            int headPxInt = (int)headPx;
            int headPyInt = (int)headPy;
            
            // Head - brighter green
            g.fill(headPxInt + 1, headPyInt + 1, headPxInt + cellSize - 1, headPyInt + cellSize - 1, snakeHeadColor);
            
            // Draw two tiny eye dots (only if not death flash)
            if (!drawDeathFlash) {
                g.fill(headPxInt + 3, headPyInt + 3, headPxInt + 5, headPyInt + 5, 0xFF000000);
                g.fill(headPxInt + cellSize - 5, headPyInt + 3, headPxInt + cellSize - 3, headPyInt + 5, 0xFF000000);
            }
        }
        
        // Draw apple/food
        if (food != null) {
            int px = gridOffsetX + food.x * cellSize;
            int py = gridOffsetY + food.y * cellSize;
            int centerX = px + cellSize / 2;
            int centerY = py + cellSize / 2;
            
            // Calculate pop animation scale (1.2 → 1.0)
            float popScale = foodPopTicks > 0 ? 1.0f + (foodPopTicks / 6.0f) * 0.2f : 1.0f;
            // Calculate pop animation alpha (255 → 200)
            int popAlpha = foodPopTicks > 0 ? 200 + (foodPopTicks * 55 / 6) : 255;
            
            int baseRadius = (int)((cellSize / 3) * popScale);
            
            // Base red circle for apple
            int baseColor = (popAlpha << 24) | 0xE53935; // Red with alpha
            int highlightColor = (popAlpha << 24) | 0xFFFFFF; // White highlight
            
            // Draw apple circle
            for (int dy = -baseRadius; dy <= baseRadius; dy++) {
                for (int dx = -baseRadius; dx <= baseRadius; dx++) {
                    int distSq = dx * dx + dy * dy;
                    if (distSq <= baseRadius * baseRadius) {
                        g.fill(centerX + dx, centerY + dy, centerX + dx + 1, centerY + dy + 1, baseColor);
                    }
                }
            }
            
            // Tiny white highlight dot on upper left
            int highlightX = centerX - baseRadius / 3;
            int highlightY = centerY - baseRadius / 3;
            g.fill(highlightX, highlightY, highlightX + 2, highlightY + 2, highlightColor);
            
            // Tiny brown stem at top
            int stemX = centerX;
            int stemY = centerY - baseRadius;
            g.fill(stemX - 1, stemY, stemX + 1, stemY + 2, (popAlpha << 24) | 0x8B4513); // Brown
        }
        
        // Draw HUD: Score and Best
        int score = getScore();
        String scoreText = "Score: " + score + "     Best: " + bestScoreSnake;
        g.drawString(font, Component.literal(scoreText), areaX + 4, areaY + 4, 0xFFFFFF, true);
        
        // Draw controls hint at bottom left
        String controlsText = "Arrows/WASD: Move";
        g.drawString(font, Component.literal(controlsText), areaX + 4, areaY + areaHeight - 12, 0xCCCCCC, true);
    }
    
    @Override
    public String getTitle() {
        return "Mini Snake";
    }
    
    @Override
    public boolean isGameOver() {
        return gameOver;
    }
    
    @Override
    public int getScore() {
        return snakeBody.size() - initialLength;
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
    
    /**
     * Update best score if current score exceeds it.
     */
    private void updateBestScore() {
        int currentScore = getScore();
        if (currentScore > bestScoreSnake) {
            bestScoreSnake = currentScore;
        }
    }
}

