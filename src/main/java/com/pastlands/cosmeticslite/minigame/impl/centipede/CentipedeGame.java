package com.pastlands.cosmeticslite.minigame.impl.centipede;

import com.pastlands.cosmeticslite.minigame.api.MiniGame;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Centipede-lite arcade mini-game implementation.
 */
public class CentipedeGame implements MiniGame {
    
    private static final int GRID_WIDTH = 20;
    private static final int GRID_HEIGHT = 15;
    
    // Level-based centipede parameters
    private static final int BASE_LENGTH = 8;
    private static final int LENGTH_PER_LEVEL = 1;
    private static final int MAX_LENGTH = 16;
    
    private static final int BASE_TICK_DELAY = 8; // ticks between centipede moves
    private static final int DELAY_PER_LEVEL = -1; // speed up by 1 tick per level
    private static final int MIN_TICK_DELAY = 3;
    
    // Mushroom density parameters
    private static final double BASE_MUSHROOM_DENSITY = 0.05; // 5% at level 1
    private static final double DENSITY_PER_LEVEL = 0.01; // +1% per level
    private static final double MAX_MUSHROOM_DENSITY = 0.25; // 25% cap
    
    private enum Direction {
        LEFT(-1),
        RIGHT(1);
        
        final int dx;
        
        Direction(int dx) {
            this.dx = dx;
        }
    }
    
    private static class Segment {
        int x, y;
        
        Segment(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }
    
    private static class CentipedeChain {
        List<Segment> segments;
        Direction direction;
        
        CentipedeChain(List<Segment> segments, Direction direction) {
            this.segments = segments;
            this.direction = direction;
        }
    }
    
    private static class Shot {
        int x, y;
        
        Shot(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }
    
    private static class Mushroom {
        int x, y;
        int hp;
        
        Mushroom(int x, int y, int hp) {
            this.x = x;
            this.y = y;
            this.hp = hp;
        }
    }
    
    private int playerX;
    private int playerY;
    private List<Shot> shots;
    private List<CentipedeChain> centipedes;
    private List<Mushroom> mushrooms;
    private boolean gameOver;
    private boolean playerWon;
    private int tickCounter;
    private int score;
    private Random random;
    
    // Level tracking
    private int levelIndex = 1; // Starts at 1
    private int centipedeMoveInterval; // Dynamic based on level
    
    // Speed controls
    private static final int SHOT_MOVE_INTERVAL = 1; // Move every tick
    private static final int SHOT_COOLDOWN = 5; // Ticks between shots
    
    private int shotCooldownTicks;
    
    // Level cleared message
    private String levelClearedMessage = "";
    private int levelClearedTurns = 0;
    private static final int LEVEL_CLEARED_DURATION = 60; // Show for 60 ticks (3 seconds at 20 TPS)
    
    @Override
    public void initGame() {
        // Reset to level 1 and starting values
        levelIndex = 1;
        score = 0;
        gameOver = false;
        playerWon = false;
        tickCounter = 0;
        shotCooldownTicks = 0;
        levelClearedMessage = "";
        levelClearedTurns = 0;
        
        // Initialize random if needed
        if (random == null) {
            random = new Random();
        }
        
        // Initialize lists if needed
        if (shots == null) {
            shots = new ArrayList<>();
        }
        if (centipedes == null) {
            centipedes = new ArrayList<>();
        }
        if (mushrooms == null) {
            mushrooms = new ArrayList<>();
        }
        
        // Start first level
        startLevel(levelIndex);
    }
    
    /**
     * Start a new level with procedural generation.
     */
    private void startLevel(int level) {
        // Reset player position
        playerX = GRID_WIDTH / 2;
        playerY = GRID_HEIGHT - 1;
        
        // Clear shots
        shots = new ArrayList<>();
        
        // Calculate level-based parameters
        int centipedeLength = Math.min(BASE_LENGTH + (level - 1) * LENGTH_PER_LEVEL, MAX_LENGTH);
        // Clamp move delay between MIN_TICK_DELAY and BASE_TICK_DELAY
        int calculatedDelay = BASE_TICK_DELAY + (level - 1) * DELAY_PER_LEVEL;
        centipedeMoveInterval = Math.max(MIN_TICK_DELAY, Math.min(BASE_TICK_DELAY, calculatedDelay));
        
        // Generate field for this level
        generateFieldForLevel(level, centipedeLength);
    }
    
    /**
     * Generate the field layout for a given level.
     */
    private void generateFieldForLevel(int level, int centipedeLength) {
        // Clear existing entities
        centipedes.clear();
        mushrooms.clear();
        
        // Calculate mushroom density
        double density = Math.min(BASE_MUSHROOM_DENSITY + (level - 1) * DENSITY_PER_LEVEL, MAX_MUSHROOM_DENSITY);
        
        // Place mushrooms based on density
        // Skip bottom 2-3 rows where player moves, and top row
        int playerSpawnRow = GRID_HEIGHT - 1;
        int safeRows = 2; // Bottom 2 rows are safe
        
        for (int y = 1; y < GRID_HEIGHT - safeRows; y++) {
            for (int x = 0; x < GRID_WIDTH; x++) {
                // Optional: avoid player spawn column
                if (y == playerSpawnRow && x == GRID_WIDTH / 2) {
                    continue;
                }
                
                // Place mushroom with probability
                if (random.nextDouble() < density) {
                    mushrooms.add(new Mushroom(x, y, 1));
                }
            }
        }
        
        // Spawn centipede(s) at top row
        // Level 1: single centipede
        // Level 5+: chance for 2nd centipede
        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < centipedeLength; i++) {
            segments.add(new Segment(i, 0));
        }
        centipedes.add(new CentipedeChain(segments, Direction.RIGHT));
        
        // Optional: At level >= 5, chance for a 2nd centipede
        if (level >= 5 && random.nextDouble() < 0.3) { // 30% chance
            List<Segment> segments2 = new ArrayList<>();
            int startX2 = GRID_WIDTH - centipedeLength; // Start from right side
            for (int i = 0; i < centipedeLength; i++) {
                segments2.add(new Segment(startX2 + i, 0));
            }
            centipedes.add(new CentipedeChain(segments2, Direction.LEFT));
        }
    }
    
    @Override
    public void tick() {
        if (gameOver) return;
        
        tickCounter++;
        shotCooldownTicks = Math.max(0, shotCooldownTicks - 1);
        
        // Update level cleared message fade
        if (levelClearedTurns > 0) {
            levelClearedTurns--;
            if (levelClearedTurns == 0) {
                levelClearedMessage = "";
            }
        }
        
        // Move shots upward
        if (tickCounter % SHOT_MOVE_INTERVAL == 0) {
            // First, move all shots
            for (Shot shot : shots) {
                shot.y--;
            }
            
            // Then, do collision detection pass
            List<Shot> shotsToRemove = new ArrayList<>();
            List<CentipedeChain> chainsToRemove = new ArrayList<>();
            List<CentipedeChain> chainsToAdd = new ArrayList<>();
            
            for (Shot shot : shots) {
                if (shotsToRemove.contains(shot)) continue; // Already processed
                
                // Check if shot hit top border
                if (shot.y < 0) {
                    shotsToRemove.add(shot);
                    continue;
                }
                
                // Check if shot hit a centipede segment
                boolean hitSegment = false;
                for (CentipedeChain chain : centipedes) {
                    if (chainsToRemove.contains(chain)) continue; // Already marked for removal
                    
                    for (int i = 0; i < chain.segments.size(); i++) {
                        Segment seg = chain.segments.get(i);
                        if (seg.x == shot.x && seg.y == shot.y) {
                            // Hit! Remove this segment
                            shotsToRemove.add(shot);
                            hitSegment = true;
                            
                            // Split chain at this segment
                            if (chain.segments.size() > 1) {
                                // Create two new chains
                                List<Segment> before = new ArrayList<>(chain.segments.subList(0, i));
                                List<Segment> after = new ArrayList<>(chain.segments.subList(i + 1, chain.segments.size()));
                                
                                chainsToRemove.add(chain);
                                
                                if (!before.isEmpty()) {
                                    // Reverse direction for the "before" chain
                                    Direction newDir = chain.direction == Direction.LEFT ? Direction.RIGHT : Direction.LEFT;
                                    chainsToAdd.add(new CentipedeChain(before, newDir));
                                }
                                
                                if (!after.isEmpty()) {
                                    // Keep direction for the "after" chain
                                    chainsToAdd.add(new CentipedeChain(after, chain.direction));
                                }
                            } else {
                                // Last segment, remove entire chain
                                chainsToRemove.add(chain);
                            }
                            
                            score += 10;
                            break; // Exit inner loop, continue to next shot
                        }
                    }
                    if (hitSegment) break; // Exit chain loop, continue to next shot
                }
                
                if (hitSegment) continue; // Skip mushroom check for this shot
                
                // Check if shot hit a mushroom
                for (Mushroom mushroom : mushrooms) {
                    if (mushroom.x == shot.x && mushroom.y == shot.y) {
                        mushroom.hp--;
                        shotsToRemove.add(shot);
                        if (mushroom.hp <= 0) {
                            mushrooms.remove(mushroom);
                            score += 5;
                        }
                        break;
                    }
                }
            }
            
            // Apply all modifications
            shots.removeAll(shotsToRemove);
            centipedes.removeAll(chainsToRemove);
            centipedes.addAll(chainsToAdd);
            
            // Check if all centipedes destroyed - advance to next level instead of game over
            if (centipedes.isEmpty() && !gameOver && levelClearedTurns == 0) {
                // Level cleared! Advance to next level
                levelIndex++;
                score += levelIndex * 10; // Bonus score for clearing level
                levelClearedMessage = "Level " + (levelIndex - 1) + " cleared!";
                levelClearedTurns = LEVEL_CLEARED_DURATION;
                
                // Start next level after a short delay (handled by levelClearedTurns)
            }
        }
        
        // Start next level when message duration expires
        if (centipedes.isEmpty() && !gameOver && levelClearedTurns == 1) {
            startLevel(levelIndex);
        }
        
        // Move centipedes (using dynamic interval based on level)
        if (tickCounter % centipedeMoveInterval == 0) {
            for (CentipedeChain chain : new ArrayList<>(centipedes)) {
                if (chain.segments.isEmpty()) {
                    centipedes.remove(chain);
                    continue;
                }
                
                Segment head = chain.segments.get(0);
                int nextX = head.x + chain.direction.dx;
                int nextY = head.y;
                
                // Check if hit wall
                if (nextX < 0 || nextX >= GRID_WIDTH) {
                    // Move down and reverse
                    nextY = head.y + 1;
                    chain.direction = chain.direction == Direction.LEFT ? Direction.RIGHT : Direction.LEFT;
                    nextX = head.x; // Stay in same column when moving down
                } else {
                    // Check if hit mushroom
                    boolean hitMushroom = false;
                    for (Mushroom mushroom : mushrooms) {
                        if (mushroom.x == nextX && mushroom.y == nextY) {
                            hitMushroom = true;
                            break;
                        }
                    }
                    
                    if (hitMushroom) {
                        // Move down and reverse
                        nextY = head.y + 1;
                        chain.direction = chain.direction == Direction.LEFT ? Direction.RIGHT : Direction.LEFT;
                        nextX = head.x;
                    }
                }
                
                // Check if reached player row
                if (nextY >= playerY) {
                    gameOver = true;
                    playerWon = false;
                    return;
                }
                
                // Move chain: each segment takes the position of the previous one
                int prevX = head.x;
                int prevY = head.y;
                head.x = nextX;
                head.y = nextY;
                
                for (int i = 1; i < chain.segments.size(); i++) {
                    Segment seg = chain.segments.get(i);
                    int tempX = seg.x;
                    int tempY = seg.y;
                    seg.x = prevX;
                    seg.y = prevY;
                    prevX = tempX;
                    prevY = tempY;
                }
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
        
        // Left/Right movement
        if (keyCode == 263 || keyCode == 65) { // LEFT or A
            if (playerX > 0) {
                playerX--;
            }
        } else if (keyCode == 262 || keyCode == 68) { // RIGHT or D
            if (playerX < GRID_WIDTH - 1) {
                playerX++;
            }
        } else if (keyCode == 32 || keyCode == 257) { // SPACE or ENTER to shoot
            if (shotCooldownTicks <= 0) {
                // Check if there's already a shot directly above player
                boolean hasShotAbove = false;
                for (Shot shot : shots) {
                    if (shot.x == playerX && shot.y >= playerY - 1) {
                        hasShotAbove = true;
                        break;
                    }
                }
                
                if (!hasShotAbove) {
                    shots.add(new Shot(playerX, playerY - 1));
                    shotCooldownTicks = SHOT_COOLDOWN;
                }
            }
        }
    }
    
    @Override
    public void render(GuiGraphics g, Font font, int areaX, int areaY, int areaWidth, int areaHeight, float partialTicks) {
        int cellWidth = areaWidth / GRID_WIDTH;
        int cellHeight = areaHeight / GRID_HEIGHT;
        
        // Draw grid background (black playfield)
        g.fill(areaX, areaY, areaX + areaWidth, areaY + areaHeight, 0xFF000000);
        
        // Draw subtle ground line at the bottom (2-3px tall strip)
        int groundHeight = 3;
        int groundY = areaY + areaHeight - groundHeight;
        g.fill(areaX, groundY, areaX + areaWidth, areaY + areaHeight, 0xFF2A2A2A); // Slightly lighter dark color
        
        // Draw mushrooms
        for (Mushroom mushroom : mushrooms) {
            int px = areaX + mushroom.x * cellWidth;
            int py = areaY + mushroom.y * cellHeight;
            // Brighter brown
            int color = mushroom.hp > 1 ? 0xFFA0522D : 0xFF8B4513; // Brighter brown shades
            g.fill(px + 1, py + 1, px + cellWidth - 1, py + cellHeight - 1, color);
            // Darker cap stripe at top
            g.fill(px + 1, py + 1, px + cellWidth - 1, py + 2, 0xFF654321); // Darker brown stripe
        }
        
        // Draw centipede segments
        for (CentipedeChain chain : centipedes) {
            for (int i = 0; i < chain.segments.size(); i++) {
                Segment seg = chain.segments.get(i);
                int px = areaX + seg.x * cellWidth;
                int py = areaY + seg.y * cellHeight;
                
                boolean isHead = (i == 0);
                // Head segment slightly brighter
                int outerColor = isHead ? 0xFF00FF00 : ((i % 2 == 0) ? 0xFF00FF00 : 0xFF00AA00); // Green shades
                int innerColor = isHead ? 0xFF44FF44 : 0xFF22FF22; // Lighter green for inner
                
                // Outer square
                g.fill(px + 1, py + 1, px + cellWidth - 1, py + cellHeight - 1, outerColor);
                
                // Inner square (smaller, lighter)
                int innerPad = 2;
                g.fill(px + innerPad, py + innerPad, px + cellWidth - innerPad, py + cellHeight - innerPad, innerColor);
                
                // Optional: head segment with two pixel "eyes"
                if (isHead) {
                    int eyeSize = 1;
                    int eyeOffsetX = cellWidth / 4;
                    int eyeY = py + cellHeight / 3;
                    // Left eye
                    g.fill(px + eyeOffsetX, eyeY, px + eyeOffsetX + eyeSize, eyeY + eyeSize, 0xFF000000);
                    // Right eye
                    g.fill(px + cellWidth - eyeOffsetX - eyeSize, eyeY, px + cellWidth - eyeOffsetX, eyeY + eyeSize, 0xFF000000);
                }
            }
        }
        
        // Draw shots (bright yellow/white for visibility)
        for (Shot shot : shots) {
            int px = areaX + shot.x * cellWidth;
            int py = areaY + shot.y * cellHeight;
            // Bright white/yellow for high contrast
            g.fill(px + cellWidth / 2 - 1, py, px + cellWidth / 2 + 1, py + cellHeight, 0xFFFFFF00); // Bright yellow
            // Add white center for extra visibility
            g.fill(px + cellWidth / 2, py + cellHeight / 4, px + cellWidth / 2 + 1, py + 3 * cellHeight / 4, 0xFFFFFFFF);
        }
        
        // Draw player shooter
        int playerPx = areaX + playerX * cellWidth;
        int playerPy = areaY + playerY * cellHeight;
        // Bright red rectangle
        g.fill(playerPx + 1, playerPy + 1, playerPx + cellWidth - 1, playerPy + cellHeight - 1, 0xFFFF0000);
        
        // Small "gun barrel" offset upwards (tiny rectangle)
        int barrelWidth = cellWidth / 2;
        int barrelHeight = cellHeight / 3;
        int barrelX = playerPx + (cellWidth - barrelWidth) / 2;
        int barrelY = playerPy - barrelHeight;
        g.fill(barrelX, barrelY, barrelX + barrelWidth, barrelY + barrelHeight, 0xFFFF0000);
        
        // Draw score and level
        g.drawString(font, Component.literal("Score: " + score), areaX + 4, areaY + 4, 0xFFFFFF, true);
        g.drawString(font, Component.literal("Level: " + levelIndex), areaX + 4, areaY + 16, 0xFFFFFF, true);
        
        // Draw level cleared message
        if (!levelClearedMessage.isEmpty() && levelClearedTurns > 0) {
            int centerX = areaX + areaWidth / 2;
            int centerY = areaY + areaHeight / 2;
            int textWidth = font.width(levelClearedMessage);
            g.drawString(font, Component.literal(levelClearedMessage), centerX - textWidth / 2, centerY, 0xFFFFFF00, true); // Yellow
        }
        
        // Instructions (only when not game over - overlay is handled by MiniGamePlayScreen)
        if (!gameOver) {
            g.drawString(font, Component.literal("Left/Right: Move, Space: Shoot"), areaX + 4, areaY + areaHeight - 12, 0xFFFFFF, true);
        }
    }
    
    @Override
    public String getTitle() {
        return "Mini Centipede";
    }
    
    @Override
    public boolean isGameOver() {
        return gameOver;
    }
    
    @Override
    public int getScore() {
        return score;
    }
    
    @Override
    public void onClose() {
        // No cleanup needed
    }
}


