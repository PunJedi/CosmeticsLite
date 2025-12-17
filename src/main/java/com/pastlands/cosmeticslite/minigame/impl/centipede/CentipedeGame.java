package com.pastlands.cosmeticslite.minigame.impl.centipede;

import com.pastlands.cosmeticslite.minigame.api.MiniGame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

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
        int hitFlashTicks; // Visual flash when hit
        
        Segment(int x, int y) {
            this.x = x;
            this.y = y;
            this.hitFlashTicks = 0;
        }
    }
    
    private static class ImpactSpark {
        int x, y;
        int lifeTicks;
        
        ImpactSpark(int x, int y) {
            this.x = x;
            this.y = y;
            this.lifeTicks = 6; // Lifetime in ticks
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
    private List<ImpactSpark> impactSparks; // Visual sparks on impact
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
    private static final int LEVEL_CLEARED_DURATION = 24; // Show for ~1.2 seconds (24 ticks at 20 TPS)
    
    // Difficulty tweaks
    private static final int SPEED_INCREASE_PER_WAVE = 0; // Additional speed boost (0 = use existing DELAY_PER_LEVEL)
    private static final int SEGMENT_INCREASE_INTERVAL = 3; // Add +1 segment every N waves
    private static final int MAX_SEGMENT_INCREASE = 2; // Maximum additional segments from wave progression
    
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
        if (impactSparks == null) {
            impactSparks = new ArrayList<>();
        }
        
        // Clear any lingering effects
        impactSparks.clear();
        
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
        // Base length + per-level increase + optional wave-based increase
        int waveBasedIncrease = Math.min((level - 1) / SEGMENT_INCREASE_INTERVAL, MAX_SEGMENT_INCREASE);
        int centipedeLength = Math.min(BASE_LENGTH + (level - 1) * LENGTH_PER_LEVEL + waveBasedIncrease, MAX_LENGTH);
        
        // Clamp move delay between MIN_TICK_DELAY and BASE_TICK_DELAY
        // Add small speed increase per wave (faster = lower delay)
        int speedBoost = (level - 1) * SPEED_INCREASE_PER_WAVE;
        int calculatedDelay = BASE_TICK_DELAY + (level - 1) * DELAY_PER_LEVEL - speedBoost;
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
        
        // Update impact sparks
        impactSparks.removeIf(spark -> {
            spark.lifeTicks--;
            return spark.lifeTicks <= 0;
        });
        
        // Update segment hit flashes
        for (CentipedeChain chain : centipedes) {
            for (Segment seg : chain.segments) {
                if (seg.hitFlashTicks > 0) {
                    seg.hitFlashTicks--;
                }
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
                            // Hit! Trigger flash and impact spark
                            seg.hitFlashTicks = 4;
                            impactSparks.add(new ImpactSpark(shot.x, shot.y));
                            
                            // Play hit sound
                            playLocalSound(SoundEvents.NOTE_BLOCK_PLING.value(), 0.4F, 1.5F); // Short "zap" sound
                            
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
                        // Add impact spark
                        impactSparks.add(new ImpactSpark(shot.x, shot.y));
                        
                        // Play mushroom hit sound
                        playLocalSound(SoundEvents.WOOD_HIT, 0.3F, 0.9F); // Muted woody "thunk"
                        
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
                // Wave cleared! Advance to next level
                levelIndex++;
                score += levelIndex * 10; // Bonus score for clearing level
                levelClearedMessage = "Wave " + (levelIndex - 1) + " cleared!";
                levelClearedTurns = LEVEL_CLEARED_DURATION;
                
                // Play wave clear sound
                playLocalSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.5F, 1.3F); // Short, bright success chime
                
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
                    // Play player hit sound (only once per game-over)
                    playLocalSound(SoundEvents.GENERIC_EXPLODE, 0.5F, 0.7F); // Lower pitch "explosion"/thud
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
                    // Play shoot sound when bullet is actually created
                    playLocalSound(SoundEvents.ARROW_SHOOT, 0.4F, 1.3F); // Medium volume, slightly higher pitch
                }
            }
        }
    }
    
    @Override
    public void render(GuiGraphics g, Font font, int areaX, int areaY, int areaWidth, int areaHeight, float partialTicks) {
        int cellWidth = areaWidth / GRID_WIDTH;
        int cellHeight = areaHeight / GRID_HEIGHT;
        
        // Draw grid background (neutral very dark gray, same tone as other mini-games)
        int bgColor = 0xFF1A1A1A; // Very dark gray
        g.fill(areaX, areaY, areaX + areaWidth, areaY + areaHeight, bgColor);
        
        // Draw subtle vertical grid lines (low-alpha lines for movement lanes)
        int gridLineColor = 0x10101010; // Very low alpha
        for (int x = 1; x < GRID_WIDTH; x++) {
            int lineX = areaX + x * cellWidth;
            g.fill(lineX - 1, areaY, lineX, areaY + areaHeight, gridLineColor);
        }
        
        // Draw subtle ground line at the bottom (2-3px tall strip)
        int groundHeight = 3;
        int groundY = areaY + areaHeight - groundHeight;
        g.fill(areaX, groundY, areaX + areaWidth, areaY + areaHeight, 0xFF2A2A2A); // Slightly lighter dark color
        
        // Draw mushrooms (two-tone mushroom: cap with darker top edge, stem)
        for (Mushroom mushroom : mushrooms) {
            int px = areaX + mushroom.x * cellWidth;
            int py = areaY + mushroom.y * cellHeight;
            
            // Check if damaged (partially destroyed)
            float alpha = mushroom.hp <= 0 ? 0.5f : 1.0f;
            int baseColor = 0xFFA0522D; // Warm brown
            int capColor = (int)(alpha * 0xFF) << 24 | (baseColor & 0x00FFFFFF);
            int darkerCap = (int)(alpha * 0xFF) << 24 | 0x00654321; // Darker brown for top edge
            int stemColor = (int)(alpha * 0xFF) << 24 | 0x00D2B48C; // Lighter tan for stem
            
            // Cap: rounded rectangle (or simple rectangle with darker top edge)
            int capHeight = cellHeight * 2 / 3;
            g.fill(px + 1, py + 1, px + cellWidth - 1, py + 1 + capHeight, capColor);
            // Darker top edge stripe
            g.fill(px + 1, py + 1, px + cellWidth - 1, py + 2, darkerCap);
            
            // Stem: small lighter rectangle under the cap
            int stemWidth = cellWidth / 2;
            int stemX = px + (cellWidth - stemWidth) / 2;
            int stemY = py + capHeight;
            g.fill(stemX, stemY, stemX + stemWidth, py + cellHeight - 1, stemColor);
        }
        
        // Draw centipede segments (with hit flash effect and gradient)
        for (CentipedeChain chain : centipedes) {
            for (int i = 0; i < chain.segments.size(); i++) {
                Segment seg = chain.segments.get(i);
                int px = areaX + seg.x * cellWidth;
                int py = areaY + seg.y * cellHeight;
                
                boolean isHead = (i == 0);
                
                // Hit flash: draw bright yellow/white overlay if hit
                if (seg.hitFlashTicks > 0) {
                    int flashAlpha = (seg.hitFlashTicks * 0xFF / 4) << 24;
                    int flashColor = flashAlpha | 0x00FFFF00; // Yellow/white
                    g.fill(px + 1, py + 1, px + cellWidth - 1, py + cellHeight - 1, flashColor);
                }
                
                // Body segments: gradient illusion (darker bottom, lighter top)
                // Head segment: slightly brighter green
                int bottomColor = isHead ? 0xFF00CC00 : 0xFF00AA00; // Darker green bottom
                int topColor = isHead ? 0xFF44FF44 : 0xFF22FF22; // Lighter green top
                
                // Draw gradient: bottom half darker, top half lighter
                int midY = py + cellHeight / 2;
                // Bottom half (darker)
                g.fill(px + 1, midY, px + cellWidth - 1, py + cellHeight - 1, bottomColor);
                // Top half (lighter)
                g.fill(px + 1, py + 1, px + cellWidth - 1, midY, topColor);
                
                // Optional: head segment with two tiny darker dots for eyes
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
        
        // Draw shots (light-yellow rectangle with tiny highlight at front)
        for (Shot shot : shots) {
            int px = areaX + shot.x * cellWidth;
            int py = areaY + shot.y * cellHeight;
            // Light-yellow rectangle
            int bulletWidth = Math.max(2, cellWidth / 3);
            int bulletX = px + (cellWidth - bulletWidth) / 2;
            g.fill(bulletX, py + 1, bulletX + bulletWidth, py + cellHeight - 1, 0xFFFFFFAA); // Light yellow
            // Tiny highlight at the front (top)
            int highlightSize = Math.max(1, bulletWidth / 2);
            g.fill(bulletX + (bulletWidth - highlightSize) / 2, py + 1, 
                   bulletX + (bulletWidth - highlightSize) / 2 + highlightSize, py + 2, 0xFFFFFFFF); // White highlight
        }
        
        // Draw impact sparks (radiating lines from collision points)
        for (ImpactSpark spark : impactSparks) {
            int px = areaX + spark.x * cellWidth + cellWidth / 2;
            int py = areaY + spark.y * cellHeight + cellHeight / 2;
            int sparkLength = Math.min(cellWidth, cellHeight) / 3;
            float alpha = spark.lifeTicks / 6.0f;
            int sparkColor = ((int)(alpha * 0xFF) << 24) | 0x00FFFF00; // Yellow/white, fading
            
            // Draw 4-6 short lines radiating out
            for (int i = 0; i < 6; i++) {
                double angle = (i * Math.PI * 2) / 6;
                int endX = px + (int)(Math.cos(angle) * sparkLength);
                int endY = py + (int)(Math.sin(angle) * sparkLength);
                // Draw line (simple approximation with small rectangles)
                drawLine(g, px, py, endX, endY, sparkColor, 1);
            }
        }
        
        // Draw player cannon (simple cannon sprite: base, turret, barrel)
        int playerPx = areaX + playerX * cellWidth;
        int playerPy = areaY + playerY * cellHeight;
        
        // Base: wider rectangle (cannon body)
        int baseWidth = cellWidth - 2;
        int baseHeight = cellHeight * 2 / 3;
        int baseX = playerPx + 1;
        int baseY = playerPy + cellHeight - baseHeight;
        int baseColor = 0xFFFF4444; // Lighter red (inner highlight)
        int baseBorder = 0xFFCC0000; // Darker red (outer border)
        
        // Outer border
        g.fill(baseX, baseY, baseX + baseWidth, baseY + 1, baseBorder);
        g.fill(baseX, baseY + baseHeight - 1, baseX + baseWidth, baseY + baseHeight, baseBorder);
        g.fill(baseX, baseY, baseX + 1, baseY + baseHeight, baseBorder);
        g.fill(baseX + baseWidth - 1, baseY, baseX + baseWidth, baseY + baseHeight, baseBorder);
        
        // Inner fill (lighter)
        g.fill(baseX + 1, baseY + 1, baseX + baseWidth - 1, baseY + baseHeight - 1, baseColor);
        
        // Turret: smaller rectangle on top
        int turretWidth = baseWidth * 2 / 3;
        int turretHeight = baseHeight / 2;
        int turretX = playerPx + (cellWidth - turretWidth) / 2;
        int turretY = baseY - turretHeight;
        g.fill(turretX, turretY, turretX + turretWidth, turretY + turretHeight, baseColor);
        g.fill(turretX, turretY, turretX + turretWidth, turretY + 1, baseBorder);
        g.fill(turretX, turretY + turretHeight - 1, turretX + turretWidth, turretY + turretHeight, baseBorder);
        g.fill(turretX, turretY, turretX + 1, turretY + turretHeight, baseBorder);
        g.fill(turretX + turretWidth - 1, turretY, turretX + turretWidth, turretY + turretHeight, baseBorder);
        
        // Barrel hint: tiny darker rectangle at the top center
        int barrelWidth = turretWidth / 2;
        int barrelHeight = cellHeight / 4;
        int barrelX = playerPx + (cellWidth - barrelWidth) / 2;
        int barrelY = turretY - barrelHeight;
        g.fill(barrelX, barrelY, barrelX + barrelWidth, barrelY + barrelHeight, baseBorder);
        
        // Draw UI text: Score left, Wave right
        g.drawString(font, Component.literal("Score: " + score), areaX + 4, areaY + 4, 0xFFFFFFFF, true);
        String waveText = "Wave: " + levelIndex;
        int waveTextWidth = font.width(waveText);
        g.drawString(font, Component.literal(waveText), areaX + areaWidth - waveTextWidth - 4, areaY + 4, 0xFFFFFFFF, true);
        
        // Draw wave cleared message (centered white text with shadow)
        if (!levelClearedMessage.isEmpty() && levelClearedTurns > 0) {
            int centerX = areaX + areaWidth / 2;
            int centerY = areaY + areaHeight / 2;
            int textWidth = font.width(levelClearedMessage);
            g.drawString(font, Component.literal(levelClearedMessage), centerX - textWidth / 2, centerY, 0xFFFFFFFF, true); // White with shadow
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
     * Draw a simple line between two points (for impact sparks).
     */
    private void drawLine(GuiGraphics g, int x1, int y1, int x2, int y2, int color, int thickness) {
        // Simple line drawing using Bresenham-like algorithm
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int err = dx - dy;
        
        int x = x1;
        int y = y1;
        
        while (true) {
            // Draw pixel with thickness
            for (int tx = -thickness / 2; tx <= thickness / 2; tx++) {
                for (int ty = -thickness / 2; ty <= thickness / 2; ty++) {
                    g.fill(x + tx, y + ty, x + tx + 1, y + ty + 1, color);
                }
            }
            
            if (x == x2 && y == y2) break;
            
            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                y += sy;
            }
        }
    }
}


