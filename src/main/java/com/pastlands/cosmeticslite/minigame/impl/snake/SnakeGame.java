package com.pastlands.cosmeticslite.minigame.impl.snake;

import com.pastlands.cosmeticslite.minigame.api.MiniGame;
import net.minecraft.client.gui.GuiGraphics;

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
    
    // Visual-only animation fields
    private float foodPulse = 0.0f;
    private float lastHeadX, lastHeadY;
    private float renderHeadX, renderHeadY;
    
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
    }
    
    @Override
    public void tick() {
        if (gameOver) {
            // Continue animation even when game over
            foodPulse += 0.05f;
            return;
        }
        
        tickCounter++;
        
        // Update food pulse animation
        foodPulse += 0.05f;
        
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
            return;
        }
        
        // Check collision with body
        for (Point segment : snakeBody) {
            if (newHead.equals(segment)) {
                gameOver = true;
                return;
            }
        }
        
        // Add new head
        snakeBody.add(0, newHead);
        
        // Check if food eaten
        if (newHead.equals(food)) {
            spawnFood();
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
        
        // Tint grid slightly reddish if game over
        if (gameOver) {
            g.fill(gridOffsetX, gridOffsetY, gridOffsetX + GRID_WIDTH * cellSize, 
                gridOffsetY + GRID_HEIGHT * cellSize, 0x40FF0000);
        }
        
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
        
        // Draw snake body (all segments except head)
        for (int i = 1; i < snakeBody.size(); i++) {
            Point segment = snakeBody.get(i);
            int px = gridOffsetX + segment.x * cellSize;
            int py = gridOffsetY + segment.y * cellSize;
            
            // Body with gradient/shadow effect
            // Base rect
            g.fill(px + 1, py + 1, px + cellSize - 1, py + cellSize - 1, 0xFF00AA88);
            // Inner lighter rect for rounded effect
            int innerPad = 2;
            g.fill(px + innerPad, py + innerPad, px + cellSize - innerPad, py + cellSize - innerPad, 0xFF22CCAA);
        }
        
        // Draw snake head with smooth interpolation
        if (snakeBody.size() > 0) {
            float headPx = gridOffsetX + renderHeadX * cellSize;
            float headPy = gridOffsetY + renderHeadY * cellSize;
            int headPxInt = (int)headPx;
            int headPyInt = (int)headPy;
            
            // Head - brighter light green
            g.fill(headPxInt + 1, headPyInt + 1, headPxInt + cellSize - 1, headPyInt + cellSize - 1, 0xFF44FF44);
            // Inner highlight
            g.fill(headPxInt + 2, headPyInt + 2, headPxInt + cellSize - 2, headPyInt + cellSize - 2, 0xFF66FF66);
            
            // Draw eyes on front edge based on direction
            int eyeSize = 2;
            int eyeOffset = 3;
            int eyeY = headPyInt + cellSize / 2 - 1;
            
            if (direction == Direction.RIGHT) {
                // Eyes on right side
                int eyeX = headPxInt + cellSize - eyeOffset;
                g.fill(eyeX, eyeY, eyeX + eyeSize, eyeY + 1, 0xFFFFFFFF);
                g.fill(eyeX, eyeY + 2, eyeX + eyeSize, eyeY + 3, 0xFFFFFFFF);
            } else if (direction == Direction.LEFT) {
                // Eyes on left side
                int eyeX = headPxInt + eyeOffset - eyeSize;
                g.fill(eyeX, eyeY, eyeX + eyeSize, eyeY + 1, 0xFFFFFFFF);
                g.fill(eyeX, eyeY + 2, eyeX + eyeSize, eyeY + 3, 0xFFFFFFFF);
            } else if (direction == Direction.UP) {
                // Eyes on top
                int eyeX = headPxInt + cellSize / 2 - 1;
                int eyeYPos = headPyInt + eyeOffset - eyeSize;
                g.fill(eyeX, eyeYPos, eyeX + 1, eyeYPos + eyeSize, 0xFFFFFFFF);
                g.fill(eyeX + 2, eyeYPos, eyeX + 3, eyeYPos + eyeSize, 0xFFFFFFFF);
            } else if (direction == Direction.DOWN) {
                // Eyes on bottom
                int eyeX = headPxInt + cellSize / 2 - 1;
                int eyeYPos = headPyInt + cellSize - eyeOffset;
                g.fill(eyeX, eyeYPos, eyeX + 1, eyeYPos + eyeSize, 0xFFFFFFFF);
                g.fill(eyeX + 2, eyeYPos, eyeX + 3, eyeYPos + eyeSize, 0xFFFFFFFF);
            }
        }
        
        // Draw pulsating food orb
        if (food != null) {
            int px = gridOffsetX + food.x * cellSize;
            int py = gridOffsetY + food.y * cellSize;
            int centerX = px + cellSize / 2;
            int centerY = py + cellSize / 2;
            
            // Calculate pulse factor (0.5 to 1.0)
            float pulseFactor = 0.5f + 0.5f * (float)Math.sin(foodPulse);
            int baseRadius = cellSize / 3;
            int pulseRadius = (int)(baseRadius * pulseFactor);
            
            // Base food color (yellow/orange)
            int baseColor = 0xFFFFAA00;
            int highlightColor = 0xFFFFDD44;
            
            // Draw outer circle
            for (int dy = -baseRadius; dy <= baseRadius; dy++) {
                for (int dx = -baseRadius; dx <= baseRadius; dx++) {
                    int distSq = dx * dx + dy * dy;
                    if (distSq <= baseRadius * baseRadius) {
                        int color = distSq <= pulseRadius * pulseRadius ? highlightColor : baseColor;
                        g.fill(centerX + dx, centerY + dy, centerX + dx + 1, centerY + dy + 1, color);
                    }
                }
            }
        }
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
}

