package com.pastlands.cosmeticslite.minigame.impl.roguelike;

import com.pastlands.cosmeticslite.minigame.api.MiniGame;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.*;

/**
 * Mini Roguelike - Turn-based dungeon crawler mini-game.
 */
public class RogueGame implements MiniGame {
    
    private static final int GRID_WIDTH = 30;
    private static final int GRID_HEIGHT = 18;
    
    // Generation constants
    private static final int MAX_GENERATION_ATTEMPTS = 20;
    private static final int MIN_REACHABLE_TILES = 40;
    
    // Color constants
    private static final int COL_WALL   = 0xFF252525; // Slightly lighter than floor
    private static final int COL_FLOOR  = 0xFF101010; // Very dark gray
    private static final int COL_PLAYER = 0xFF00FFFF; // Cyan
    private static final int COL_MONSTER = 0xFFFF4040; // Red
    private static final int COL_LOOT   = 0xFFFFD840; // Yellow (gold)
    private static final int COL_EXIT   = 0xFF40FF40; // Green
    private static final int COL_TEXT_MAIN = 0xFFFFFFFF; // White with shadow (matches MiniGamePlayScreen)
    
    private enum TileType {
        WALL,
        FLOOR,
        EXIT
    }
    
    private static class Room {
        int x, y, width, height;
        
        Room(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
        
        int centerX() { return x + width / 2; }
        int centerY() { return y + height / 2; }
        
        boolean overlaps(Room other) {
            return x < other.x + other.width && x + width > other.x &&
                   y < other.y + other.height && y + height > other.y;
        }
    }
    
    private static class Monster {
        int x, y;
        int hp;
        int attack;
        int defense;
        boolean alive;
        
        Monster(int x, int y, int hp, int attack, int defense) {
            this.x = x;
            this.y = y;
            this.hp = hp;
            this.attack = attack;
            this.defense = defense;
            this.alive = true;
        }
    }
    
    private static class Loot {
        int x, y;
        int amount;
        boolean collected;
        
        Loot(int x, int y, int amount) {
            this.x = x;
            this.y = y;
            this.amount = amount;
            this.collected = false;
        }
    }
    
    private TileType[][] tiles;
    private Random random;
    
    // Player
    private int playerX, playerY;
    private int playerMaxHp = 10;
    private int playerHp = 10;
    private int playerAttack = 1; // Base attack (starts low, upgrades increase it)
    private int playerArmor = 0; // Flat damage reduction (starts at 0, upgrades increase it)
    private int playerGold = 0;
    private int upgradesTaken = 0; // How many upgrades we've earned
    
    // Entities
    private List<Monster> monsters;
    private List<Loot> loot;
    
    // Game state
    private boolean gameOver;
    private boolean playerWon;
    private int turnCount;
    private int depth = 1; // Current dungeon level
    
    // Upgrade message display
    private String upgradeMessage = "";
    private int upgradeMessageTicks = 0; // How many ticks to keep it visible
    
    @Override
    public void initGame() {
        // Reset game state flags
        gameOver = false;
        playerWon = false;
        turnCount = 0;
        depth = 1; // Reset to level 1
        
        // Reset player stats (new game - reset everything)
        playerMaxHp = 10;
        playerHp = 10;
        playerAttack = 1;
        playerArmor = 0;
        playerGold = 0;
        upgradesTaken = 0;
        playerX = 0;
        playerY = 0;
        upgradeMessage = "";
        upgradeMessageTicks = 0;
        
        // Initialize random and data structures
        random = new Random();
        tiles = new TileType[GRID_HEIGHT][GRID_WIDTH];
        monsters = new ArrayList<>();
        loot = new ArrayList<>();
        
        // Generate first dungeon
        generateNewDungeon();
    }
    
    private void generateNewDungeon() {
        // Clear game over state (but keep player stats)
        gameOver = false;
        playerWon = false;
        
        // Clear entities
        monsters.clear();
        loot.clear();
        
        // Initialize all tiles as walls
        for (int y = 0; y < GRID_HEIGHT; y++) {
            for (int x = 0; x < GRID_WIDTH; x++) {
                tiles[y][x] = TileType.WALL;
            }
        }
        
        // Generate dungeon with retry loop for guaranteed reachability
        boolean generated = false;
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            // Reset tiles to walls
            for (int y = 0; y < GRID_HEIGHT; y++) {
                for (int x = 0; x < GRID_WIDTH; x++) {
                    tiles[y][x] = TileType.WALL;
                }
            }
            monsters.clear();
            loot.clear();
            
            // Generate layout
            generateDungeonLayout();
            
            // Pick player start
            List<int[]> floorTiles = collectFloorTiles();
            if (floorTiles.isEmpty()) {
                continue; // Retry
            }
            
            int[] playerPos = floorTiles.get(random.nextInt(floorTiles.size()));
            playerX = playerPos[0];
            playerY = playerPos[1];
            
            // Compute reachable area
            List<int[]> reachable = computeReachable(playerX, playerY);
            if (reachable.size() < MIN_REACHABLE_TILES) {
                continue; // Retry - too small
            }
            
            // Place exit in reachable area (farthest from player)
            int[] exitPos = findFarthestInReachable(playerX, playerY, reachable);
            tiles[exitPos[1]][exitPos[0]] = TileType.EXIT;
            
            // Place monsters and loot only on reachable tiles
            placeMonstersAndLoot(reachable, exitPos);
            
            generated = true;
            break;
        }
        
        // Fallback if all attempts failed
        if (!generated) {
            generateFallbackDungeon();
        }
    }
    
    private void generateDungeonLayout() {
        // Generate rooms
        List<Room> rooms = new ArrayList<>();
        int numRooms = 6 + random.nextInt(5); // 6-10 rooms
        
        for (int i = 0; i < numRooms * 3; i++) { // Try up to 3x attempts
            if (rooms.size() >= numRooms) break;
            
            int roomWidth = 4 + random.nextInt(5); // 4-8
            int roomHeight = 3 + random.nextInt(4); // 3-6
            int roomX = 1 + random.nextInt(GRID_WIDTH - roomWidth - 2);
            int roomY = 1 + random.nextInt(GRID_HEIGHT - roomHeight - 2);
            
            Room newRoom = new Room(roomX, roomY, roomWidth, roomHeight);
            
            // Check for overlaps
            boolean overlaps = false;
            for (Room existing : rooms) {
                if (newRoom.overlaps(existing)) {
                    overlaps = true;
                    break;
                }
            }
            
            if (!overlaps) {
                rooms.add(newRoom);
                // Carve room
                for (int y = newRoom.y; y < newRoom.y + newRoom.height; y++) {
                    for (int x = newRoom.x; x < newRoom.x + newRoom.width; x++) {
                        tiles[y][x] = TileType.FLOOR;
                    }
                }
            }
        }
        
        // Connect rooms with corridors
        for (int i = 0; i < rooms.size() - 1; i++) {
            Room r1 = rooms.get(i);
            Room r2 = rooms.get(i + 1);
            
            int x1 = r1.centerX();
            int y1 = r1.centerY();
            int x2 = r2.centerX();
            int y2 = r2.centerY();
            
            // Horizontal then vertical
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
                tiles[y1][x] = TileType.FLOOR;
            }
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                tiles[y][x2] = TileType.FLOOR;
            }
        }
        
    }
    
    private List<int[]> collectFloorTiles() {
        List<int[]> floorTiles = new ArrayList<>();
        for (int y = 0; y < GRID_HEIGHT; y++) {
            for (int x = 0; x < GRID_WIDTH; x++) {
                if (tiles[y][x] == TileType.FLOOR) {
                    floorTiles.add(new int[]{x, y});
                }
            }
        }
        return floorTiles;
    }
    
    private List<int[]> computeReachable(int startX, int startY) {
        List<int[]> reachable = new ArrayList<>();
        boolean[][] visited = new boolean[GRID_HEIGHT][GRID_WIDTH];
        Queue<int[]> queue = new ArrayDeque<>();
        
        queue.add(new int[]{startX, startY});
        visited[startY][startX] = true;
        
        int[][] dirs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
        
        while (!queue.isEmpty()) {
            int[] pos = queue.poll();
            int x = pos[0];
            int y = pos[1];
            
            // Add to reachable if it's a floor tile
            if (tiles[y][x] == TileType.FLOOR || tiles[y][x] == TileType.EXIT) {
                reachable.add(new int[]{x, y});
            }
            
            // Check neighbors
            for (int[] dir : dirs) {
                int nx = x + dir[0];
                int ny = y + dir[1];
                
                if (nx >= 0 && nx < GRID_WIDTH && ny >= 0 && ny < GRID_HEIGHT &&
                    !visited[ny][nx] &&
                    (tiles[ny][nx] == TileType.FLOOR || tiles[ny][nx] == TileType.EXIT)) {
                    visited[ny][nx] = true;
                    queue.add(new int[]{nx, ny});
                }
            }
        }
        
        return reachable;
    }
    
    private int[] findFarthestInReachable(int startX, int startY, List<int[]> reachable) {
        // BFS to find distances
        int[][] distances = new int[GRID_HEIGHT][GRID_WIDTH];
        for (int y = 0; y < GRID_HEIGHT; y++) {
            Arrays.fill(distances[y], -1);
        }
        
        Queue<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{startX, startY});
        distances[startY][startX] = 0;
        
        int[][] dirs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
        
        while (!queue.isEmpty()) {
            int[] pos = queue.poll();
            int x = pos[0];
            int y = pos[1];
            
            for (int[] dir : dirs) {
                int nx = x + dir[0];
                int ny = y + dir[1];
                
                if (nx >= 0 && nx < GRID_WIDTH && ny >= 0 && ny < GRID_HEIGHT &&
                    distances[ny][nx] == -1 &&
                    (tiles[ny][nx] == TileType.FLOOR || tiles[ny][nx] == TileType.EXIT)) {
                    distances[ny][nx] = distances[y][x] + 1;
                    queue.add(new int[]{nx, ny});
                }
            }
        }
        
        // Find farthest reachable tile
        int maxDist = -1;
        List<int[]> farthestTiles = new ArrayList<>();
        
        for (int[] pos : reachable) {
            int dist = distances[pos[1]][pos[0]];
            if (dist > maxDist) {
                maxDist = dist;
                farthestTiles.clear();
                farthestTiles.add(pos);
            } else if (dist == maxDist && dist > 0) {
                farthestTiles.add(pos);
            }
        }
        
        // Return one of the farthest tiles (random if multiple)
        if (farthestTiles.isEmpty()) {
            return reachable.get(reachable.size() - 1); // Fallback
        }
        return farthestTiles.get(random.nextInt(farthestTiles.size()));
    }
    
    private void placeMonstersAndLoot(List<int[]> reachable, int[] exitPos) {
        // Filter out player and exit positions
        List<int[]> available = new ArrayList<>();
        for (int[] pos : reachable) {
            if ((pos[0] != playerX || pos[1] != playerY) &&
                (pos[0] != exitPos[0] || pos[1] != exitPos[1])) {
                available.add(pos);
            }
        }
        
        // Shuffle for randomness
        Collections.shuffle(available, random);
        
        // Scale difficulty with depth: more monsters and stronger enemies
        // Reduced base monster count (1-2 fewer) for softer difficulty
        int baseMonsters = 3 + random.nextInt(5); // Reduced from 5-10 to 3-7
        int numMonsters = Math.min(baseMonsters + (depth - 1) * 2, available.size());
        
        // Avoid spawning monsters too close to start (prevent spawn-camping)
        List<int[]> safePositions = new ArrayList<>();
        List<int[]> nearStartPositions = new ArrayList<>();
        for (int[] pos : available) {
            int distFromStart = Math.abs(pos[0] - playerX) + Math.abs(pos[1] - playerY);
            if (distFromStart <= 3) {
                nearStartPositions.add(pos);
            } else {
                safePositions.add(pos);
            }
        }
        
        // Prefer positions away from start, but allow some near start if needed
        Collections.shuffle(safePositions, random);
        Collections.shuffle(nearStartPositions, random);
        
        // Use safe positions first, then near-start if needed
        List<int[]> monsterPositions = new ArrayList<>(safePositions);
        if (monsterPositions.size() < numMonsters) {
            monsterPositions.addAll(nearStartPositions);
        }
        
        // Limit to available positions
        numMonsters = Math.min(numMonsters, monsterPositions.size());
        
        for (int i = 0; i < numMonsters; i++) {
            int[] pos = monsterPositions.get(i);
            // Increase monster HP and attack with depth
            int hp = 4 + random.nextInt(3) + (depth - 1); // 4-6 base, +1 per level
            // Reduced base monster damage (from 2 to 1-2)
            int attack = 1 + (depth - 1) / 3; // 1 base, +1 every 3 levels (softer)
            int defense = random.nextInt(2); // 0-1
            monsters.add(new Monster(pos[0], pos[1], hp, attack, defense));
        }
        
        // Place loot from remaining positions (exclude monster positions)
        List<int[]> lootPositions = new ArrayList<>(available);
        // Remove monster positions from loot positions
        for (int i = 0; i < numMonsters && i < monsterPositions.size(); i++) {
            lootPositions.remove(monsterPositions.get(i));
        }
        Collections.shuffle(lootPositions, random);
        int numLoot = Math.min(5 + random.nextInt(6), lootPositions.size());
        for (int i = 0; i < numLoot; i++) {
            int[] pos = lootPositions.get(i);
            // Slightly more gold at higher levels
            int amount = 1 + random.nextInt(5) + (depth - 1); // 1-5 base, +1 per level
            loot.add(new Loot(pos[0], pos[1], amount));
        }
    }
    
    private void generateFallbackDungeon() {
        // Simple guaranteed-playable layout
        // Create a large rectangular room
        for (int y = 2; y < GRID_HEIGHT - 2; y++) {
            for (int x = 2; x < GRID_WIDTH - 2; x++) {
                tiles[y][x] = TileType.FLOOR;
            }
        }
        
        // Player in one corner
        playerX = 3;
        playerY = 3;
        
        // Exit in opposite corner
        int exitX = GRID_WIDTH - 3;
        int exitY = GRID_HEIGHT - 3;
        tiles[exitY][exitX] = TileType.EXIT;
        
        // Place a few monsters and loot
        List<int[]> available = new ArrayList<>();
        for (int y = 3; y < GRID_HEIGHT - 3; y++) {
            for (int x = 3; x < GRID_WIDTH - 3; x++) {
                if ((x != playerX || y != playerY) && (x != exitX || y != exitY)) {
                    available.add(new int[]{x, y});
                }
            }
        }
        
        Collections.shuffle(available, random);
        
        // 5 monsters
        for (int i = 0; i < Math.min(5, available.size()); i++) {
            int[] pos = available.get(i);
            monsters.add(new Monster(pos[0], pos[1], 5, 2, 0));
        }
        
        // 5 loot
        for (int i = 5; i < Math.min(10, available.size()); i++) {
            int[] pos = available.get(i);
            loot.add(new Loot(pos[0], pos[1], 3));
        }
    }
    
    @Override
    public void tick() {
        // Turn-based, but update upgrade message timer every tick
        if (upgradeMessageTicks > 0) {
            upgradeMessageTicks--;
            if (upgradeMessageTicks == 0) {
                upgradeMessage = "";
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
        
        int dx = 0;
        int dy = 0;
        boolean wait = false;
        
        // Movement keys
        if (keyCode == 263 || keyCode == 65) { // LEFT or A
            dx = -1;
        } else if (keyCode == 262 || keyCode == 68) { // RIGHT or D
            dx = 1;
        } else if (keyCode == 264 || keyCode == 83) { // DOWN or S
            dy = 1;
        } else if (keyCode == 265 || keyCode == 87) { // UP or W
            dy = -1;
        } else if (keyCode == 32 || keyCode == 46) { // SPACE or PERIOD (wait)
            wait = true;
        } else {
            return; // Invalid key, no turn passes
        }
        
        boolean actionTaken = false;
        
        if (wait) {
            actionTaken = true;
        } else {
            // Try to move
            int tx = playerX + dx;
            int ty = playerY + dy;
            
            // Check bounds and walls
            if (tx < 0 || tx >= GRID_WIDTH || ty < 0 || ty >= GRID_HEIGHT ||
                tiles[ty][tx] == TileType.WALL) {
                return; // Invalid move, no turn passes
            }
            
            // Check for monster
            Monster targetMonster = null;
            for (Monster monster : monsters) {
                if (monster.alive && monster.x == tx && monster.y == ty) {
                    targetMonster = monster;
                    break;
                }
            }
            
            if (targetMonster != null) {
                // Attack monster
                int damage = Math.max(1, playerAttack - targetMonster.defense);
                targetMonster.hp -= damage;
                
                if (targetMonster.hp <= 0) {
                    targetMonster.alive = false;
                }
                actionTaken = true;
            } else {
                // Move player
                playerX = tx;
                playerY = ty;
                actionTaken = true;
                
                // Check for loot
                for (Loot l : loot) {
                    if (!l.collected && l.x == playerX && l.y == playerY) {
                        playerGold += l.amount;
                        l.collected = true;
                        // Check for upgrades after gold pickup
                        maybeGrantUpgrade();
                        break;
                    }
                }
                
                // Check for exit - advance to next level
                if (tiles[playerY][playerX] == TileType.EXIT) {
                    // Increase dungeon depth
                    depth++;
                    
                    // Heal player partially (roguelike tradition) - reduced from +5 to +3
                    playerHp = Math.min(playerHp + 3, playerMaxHp);
                    
                    // Generate new dungeon (keeps player stats and gold)
                    generateNewDungeon();
                    return;
                }
            }
        }
        
        if (actionTaken) {
            turnCount++;
            
            // Monster turn
            if (!gameOver) {
                processMonsterTurn();
            }
        }
    }
    
    private void processMonsterTurn() {
        for (Monster monster : monsters) {
            if (!monster.alive) continue;
            
            int distX = playerX - monster.x;
            int distY = playerY - monster.y;
            int manhattanDist = Math.abs(distX) + Math.abs(distY);
            
            if (manhattanDist == 1) {
                // Adjacent - attack player
                // Use armor for flat damage reduction (minimum 1 damage)
                int rawDamage = monster.attack;
                int reduced = Math.max(1, rawDamage - playerArmor);
                playerHp -= reduced;
                
                if (playerHp <= 0) {
                    playerHp = 0;
                    gameOver = true;
                    playerWon = false;
                    return;
                }
            } else if (manhattanDist > 1) {
                // Move towards player
                int moveX = 0;
                int moveY = 0;
                
                if (Math.abs(distX) > Math.abs(distY)) {
                    moveX = distX > 0 ? 1 : -1;
                } else if (Math.abs(distY) > Math.abs(distX)) {
                    moveY = distY > 0 ? 1 : -1;
                } else {
                    // Equal distance, prefer horizontal
                    moveX = distX > 0 ? 1 : -1;
                }
                
                int newX = monster.x + moveX;
                int newY = monster.y + moveY;
                
                // Check if move is valid
                if (newX >= 0 && newX < GRID_WIDTH && newY >= 0 && newY < GRID_HEIGHT &&
                    tiles[newY][newX] != TileType.WALL) {
                    // Check if tile is occupied by another monster
                    boolean occupied = false;
                    for (Monster other : monsters) {
                        if (other != monster && other.alive && other.x == newX && other.y == newY) {
                            occupied = true;
                            break;
                        }
                    }
                    
                    if (!occupied && (newX != playerX || newY != playerY)) {
                        monster.x = newX;
                        monster.y = newY;
                    }
                }
            }
        }
    }
    
    // Helper to apply brightness based on distance to player
    private int applyBrightness(int color, float brightness) {
        brightness = Math.max(0.3f, Math.min(1.0f, brightness)); // Clamp to [0.3, 1.0]
        int a = (color >> 24) & 0xFF;
        int r = (int)(((color >> 16) & 0xFF) * brightness);
        int g = (int)(((color >> 8) & 0xFF) * brightness);
        int b = (int)((color & 0xFF) * brightness);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
    
    @Override
    public void render(GuiGraphics g, Font font, int areaX, int areaY, int areaWidth, int areaHeight, float partialTicks) {
        int cellWidth = areaWidth / GRID_WIDTH;
        int cellHeight = areaHeight / GRID_HEIGHT;
        
        // Draw dungeon grid with fog of war lighting
        for (int y = 0; y < GRID_HEIGHT; y++) {
            for (int x = 0; x < GRID_WIDTH; x++) {
                int px = areaX + x * cellWidth;
                int py = areaY + y * cellHeight;
                
                // Compute Manhattan distance to player
                int manhattanDist = Math.abs(x - playerX) + Math.abs(y - playerY);
                float brightness = Math.max(0.3f, Math.min(1.0f, 1.0f - 0.08f * manhattanDist));
                
                // Draw tile with brightness applied
                int tileColor;
                if (tiles[y][x] == TileType.WALL) {
                    tileColor = COL_WALL;
                } else if (tiles[y][x] == TileType.EXIT) {
                    tileColor = COL_FLOOR; // Exit uses floor color as base
                } else {
                    tileColor = COL_FLOOR;
                }
                
                g.fill(px, py, px + cellWidth, py + cellHeight, applyBrightness(tileColor, brightness));
                
                // Draw exit highlight with brightness
                if (tiles[y][x] == TileType.EXIT) {
                    g.fill(px + 1, py + 1, px + cellWidth - 1, py + cellHeight - 1, applyBrightness(COL_EXIT, brightness));
                }
            }
        }
        
        // Draw loot with brightness
        for (Loot l : loot) {
            if (!l.collected) {
                int px = areaX + l.x * cellWidth;
                int py = areaY + l.y * cellHeight;
                int manhattanDist = Math.abs(l.x - playerX) + Math.abs(l.y - playerY);
                float brightness = Math.max(0.3f, Math.min(1.0f, 1.0f - 0.08f * manhattanDist));
                g.fill(px + 1, py + 1, px + cellWidth - 1, py + cellHeight - 1, applyBrightness(COL_LOOT, brightness));
            }
        }
        
        // Draw monsters with brightness
        for (Monster monster : monsters) {
            if (monster.alive) {
                int px = areaX + monster.x * cellWidth;
                int py = areaY + monster.y * cellHeight;
                int manhattanDist = Math.abs(monster.x - playerX) + Math.abs(monster.y - playerY);
                float brightness = Math.max(0.3f, Math.min(1.0f, 1.0f - 0.08f * manhattanDist));
                g.fill(px + 1, py + 1, px + cellWidth - 1, py + cellHeight - 1, applyBrightness(COL_MONSTER, brightness));
            }
        }
        
        // Draw player with bright contrasting outline
        int playerPx = areaX + playerX * cellWidth;
        int playerPy = areaY + playerY * cellHeight;
        g.fill(playerPx + 1, playerPy + 1, playerPx + cellWidth - 1, playerPy + cellHeight - 1, COL_PLAYER);
        
        // Bright contrasting outline box (1-2px) around player tile
        int outlineThickness = 2;
        int outlineColor = 0xFFFFFF00; // Bright yellow for high contrast
        // Top
        g.fill(playerPx, playerPy, playerPx + cellWidth, playerPy + outlineThickness, outlineColor);
        // Bottom
        g.fill(playerPx, playerPy + cellHeight - outlineThickness, playerPx + cellWidth, playerPy + cellHeight, outlineColor);
        // Left
        g.fill(playerPx, playerPy, playerPx + outlineThickness, playerPy + cellHeight, outlineColor);
        // Right
        g.fill(playerPx + cellWidth - outlineThickness, playerPy, playerPx + cellWidth, playerPy + cellHeight, outlineColor);
        
        // Draw HUD (top-left and top-right inside game area)
        // Top-left: HP and Gold using COL_TEXT_MAIN
        String hpText = "HP: " + playerHp + "/" + playerMaxHp;
        int hpColor = COL_TEXT_MAIN;
        // If HP is low (<= 3), draw HP text in orange/red
        if (playerHp <= 3) {
            hpColor = playerHp <= 1 ? 0xFFFF0000 : 0xFFFF8800; // Red if 1, orange if 2-3
        }
        g.drawString(font, Component.literal(hpText), areaX + 4, areaY + 4, hpColor, true);
        
        String goldText = "Gold: " + playerGold;
        g.drawString(font, Component.literal(goldText), areaX + 4, areaY + 16, COL_TEXT_MAIN, true);
        
        // Top-right: Depth and Turns using COL_TEXT_MAIN
        String depthText = "Depth: " + depth;
        int depthX = areaX + areaWidth - font.width(depthText) - 4;
        g.drawString(font, Component.literal(depthText), depthX, areaY + 4, COL_TEXT_MAIN, true);
        
        String turnsText = "Turns: " + turnCount;
        int turnsX = areaX + areaWidth - font.width(turnsText) - 4;
        g.drawString(font, Component.literal(turnsText), turnsX, areaY + 16, COL_TEXT_MAIN, true);
        
        // Bottom-left: Legend
        if (!gameOver) {
            int legendX = areaX + 8;
            int legendY = areaY + areaHeight - 12 - 14 * 2; // 3 lines up from bottom
            int lineHeight = 12;
            
            g.drawString(font, Component.literal("You: cyan  | Monsters: red"), legendX, legendY, COL_TEXT_MAIN, true);
            g.drawString(font, Component.literal("Gold: yellow | Exit: green"), legendX, legendY + lineHeight, COL_TEXT_MAIN, true);
            g.drawString(font, Component.literal("Move: Arrows/WASD, Space: wait"), legendX, legendY + lineHeight * 2, COL_TEXT_MAIN, true);
        }
        
        // Draw upgrade message if present (only while timer is active)
        if (upgradeMessageTicks > 0 && !upgradeMessage.isEmpty()) {
            int messageY = areaY + 32; // Below HUD
            g.drawString(font, Component.literal(upgradeMessage), areaX + 4, messageY, 0xFFFFFF00, true); // Yellow
        }
        
        // Game over overlay is handled by MiniGamePlayScreen
    }
    
    /**
     * Check if player should receive an upgrade based on gold collected.
     * Grants one upgrade every 5 gold.
     */
    private void maybeGrantUpgrade() {
        int upgradesShouldHave = playerGold / 5; // One upgrade every 5 gold
        
        while (upgradesTaken < upgradesShouldHave) {
            grantRandomUpgrade();
            upgradesTaken++;
        }
    }
    
    /**
     * Grant a random upgrade to the player.
     */
    private void grantRandomUpgrade() {
        int choice = random.nextInt(3);
        
        switch (choice) {
            case 0 -> { // Tougher
                playerMaxHp += 3;
                playerHp = Math.min(playerHp + 3, playerMaxHp);
                showUpgradeMessage("You feel tougher! (+3 max HP)");
            }
            case 1 -> { // Stronger
                playerAttack += 1;
                showUpgradeMessage("Your blows strike harder. (+1 ATK)");
            }
            case 2 -> { // Safer
                playerArmor += 1;
                showUpgradeMessage("You feel more protected. (+1 Armor)");
            }
        }
    }
    
    /**
     * Show an upgrade message for ~2 seconds.
     */
    private void showUpgradeMessage(String message) {
        this.upgradeMessage = message;
        this.upgradeMessageTicks = 40; // ~2 seconds at 20 ticks per second
    }
    
    @Override
    public String getTitle() {
        return "Mini Roguelike";
    }
    
    @Override
    public boolean isGameOver() {
        return gameOver;
    }
    
    @Override
    public int getScore() {
        return playerGold;
    }
    
    @Override
    public void onClose() {
        // No cleanup needed
    }
}

