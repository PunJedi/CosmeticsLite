package com.pastlands.cosmeticslite.minigame.client;

import com.pastlands.cosmeticslite.minigame.api.MiniGame;
import com.pastlands.cosmeticslite.minigame.impl.centipede.CentipedeGame;
import com.pastlands.cosmeticslite.minigame.impl.memory.MemoryMatchGame;
import com.pastlands.cosmeticslite.minigame.impl.minesweeper.MinesweeperGame;
import com.pastlands.cosmeticslite.minigame.impl.roguelike.RogueGame;
import com.pastlands.cosmeticslite.minigame.impl.slide.SlidePuzzleGame;
import com.pastlands.cosmeticslite.minigame.impl.snake.SnakeGame;
import com.pastlands.cosmeticslite.minigame.impl.tilt.MarbleTiltGame;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Screen that displays and manages a mini-game with CosmeticsLite styling.
 */
public class MiniGamePlayScreen extends Screen {
    
    private final MiniGame game;
    
    // Shared visual style constants
    private static final int COL_BG            = 0xCC000000; // dim behind panel
    private static final int COL_PANEL_BASE    = 0xFF2A2A35; // main panel
    private static final int COL_PANEL_BORDER  = 0xFF5A5A65; // outer border
    private static final int COL_PANEL_INNER   = 0xFF3A3A45; // inner inset
    private static final int COL_TEXT_MAIN     = 0xFFFFFFFF; // white with shadow
    private static final int COL_TEXT_SUBTLE   = 0xFFCCCCCC;
    
    // Layout constants
    private static final int OUTER_MARGIN = 40;
    private static final int HEADER_HEIGHT = 20;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_SPACING = 8;
    
    private int panelLeft, panelTop, panelRight, panelBottom;
    private int gameAreaX, gameAreaY, gameAreaWidth, gameAreaHeight;
    
    private Button backButton;
    private Button resetButton;
    
    private final Screen parent;
    
    public MiniGamePlayScreen(Screen parent, MiniGame game) {
        super(Component.literal(game.getTitle()));
        this.parent = parent;
        this.game = game;
    }
    
    @Override
    protected void init() {
        super.init();
        
        // Initialize the game
        game.initGame();
        
        // Calculate panel bounds with outer margins (centered)
        int panelWidth = width - OUTER_MARGIN * 2;
        int panelHeight = height - OUTER_MARGIN * 2;
        panelLeft = OUTER_MARGIN;
        panelTop = OUTER_MARGIN;
        panelRight = panelLeft + panelWidth;
        panelBottom = panelTop + panelHeight;
        
        // Header bar inside panel
        int headerTop = panelTop + 6;
        int headerBottom = headerTop + HEADER_HEIGHT;
        
        // Game area (inside panel, below header bar)
        int innerPad = 8;
        gameAreaX = panelLeft + innerPad;
        gameAreaY = headerBottom + 6;
        gameAreaWidth = panelWidth - innerPad * 2;
        gameAreaHeight = panelHeight - HEADER_HEIGHT - 12 - 40; // Leave room for header and buttons
        
        // Buttons centered inside panel at bottom
        int buttonY = panelBottom - 30; // 20px margin from bottom
        int buttonWidth = 80;
        int totalButtonWidth = buttonWidth * 2 + BUTTON_SPACING;
        int buttonStartX = (panelLeft + panelRight) / 2 - totalButtonWidth / 2;
        
        backButton = Button.builder(
            Component.translatable("cosmeticslite.minigame.button.back"),
            button -> {
                this.minecraft.setScreen(this.parent);
            }
        ).bounds(buttonStartX, buttonY, buttonWidth, BUTTON_HEIGHT).build();
        addRenderableWidget(backButton);
        
        resetButton = Button.builder(
            Component.translatable("cosmeticslite.minigame.button.reset"),
            button -> {
                // Only reset game state, do not navigate
                game.onClose();
                // For MarbleTiltGame, use resetAllLevels() to reset score and start from level 1
                if (game instanceof com.pastlands.cosmeticslite.minigame.impl.tilt.MarbleTiltGame) {
                    ((com.pastlands.cosmeticslite.minigame.impl.tilt.MarbleTiltGame) game).resetAllLevels();
                } else if (game instanceof MemoryMatchGame) {
                    // For MemoryMatchGame, use resetLevel() to reshuffle current level
                    ((MemoryMatchGame) game).resetLevel();
                } else if (game instanceof com.pastlands.cosmeticslite.minigame.impl.battleship.BattleshipGame) {
                    // For BattleshipGame, play reset sound and call initGame()
                    net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc.player != null) {
                        mc.player.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.3F, 0.9F);
                    }
                    game.initGame();
                } else {
                    // For other games, just call initGame()
                    game.initGame();
                }
                // Clear keyboard focus so Space doesn't re-activate Reset
                this.setFocused(null);
            }
        ).bounds(buttonStartX + buttonWidth + BUTTON_SPACING, buttonY, buttonWidth, BUTTON_HEIGHT).build();
        addRenderableWidget(resetButton);
    }
    
    @Override
    public void tick() {
        super.tick();
        game.tick();
    }
    
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        // Draw semi-transparent full-screen dim
        g.fill(0, 0, width, height, COL_BG);
        
        // Draw CosmeticsLite-style panel
        drawPanel(g);
        
        // Draw header bar with title and score
        drawHeaderBar(g);
        
        // Render the game inside the panel
        // Update mouse position for games that need it (like SlidePuzzle, Minesweeper, Battleship)
        if (game instanceof SlidePuzzleGame) {
            ((SlidePuzzleGame) game).updateMouse(mouseX, mouseY);
        } else if (game instanceof com.pastlands.cosmeticslite.minigame.impl.minesweeper.MinesweeperGame) {
            ((com.pastlands.cosmeticslite.minigame.impl.minesweeper.MinesweeperGame) game).updateMouse(mouseX, mouseY);
        } else if (game instanceof com.pastlands.cosmeticslite.minigame.impl.battleship.BattleshipGame) {
            ((com.pastlands.cosmeticslite.minigame.impl.battleship.BattleshipGame) game).updateMouse(mouseX, mouseY);
        } else if (game instanceof MemoryMatchGame) {
            ((MemoryMatchGame) game).updateMouse(mouseX, mouseY);
        }
        game.render(g, font, gameAreaX, gameAreaY, gameAreaWidth, gameAreaHeight, partialTicks);
        
        // Draw unified game over overlay if applicable
        if (game.isGameOver()) {
            drawGameOverOverlay(g);
        }
        
        super.render(g, mouseX, mouseY, partialTicks);
    }
    
    private void drawPanel(GuiGraphics g) {
        // Shadow
        drawSoftRoundedShadow(g, panelLeft, panelTop, panelRight, panelBottom);
        
        // Outer border
        fillRounded(g, panelLeft - 2, panelTop - 2, panelRight + 2, panelBottom + 2, 8, COL_PANEL_BORDER);
        
        // Main panel base
        fillRounded(g, panelLeft, panelTop, panelRight, panelBottom, 8, COL_PANEL_BASE);
    }
    
    private void drawHeaderBar(GuiGraphics g) {
        int headerTop = panelTop + 6;
        int headerBottom = headerTop + HEADER_HEIGHT;
        int headerLeft = panelLeft + 6;
        int headerRight = panelRight - 6;
        
        // Header bar background (inner inset)
        fillRounded(g, headerLeft, headerTop, headerRight, headerBottom, 4, COL_PANEL_INNER);
        
        // Title (centered) - white with shadow
        int titleX = (panelLeft + panelRight) / 2;
        String titleText = game.getTitle();
        g.drawString(font, Component.literal(titleText), titleX - font.width(titleText) / 2, headerTop + 6, COL_TEXT_MAIN, true);
        
        // Score (right-aligned) - white with shadow
        int score = game.getScore();
        if (score >= 0) {
            String scoreText = "Score: " + score;
            int scoreX = headerRight - font.width(scoreText) - 8;
            g.drawString(font, Component.literal(scoreText), scoreX, headerTop + 6, COL_TEXT_MAIN, true);
        }
    }
    
    private void drawGameOverOverlay(GuiGraphics g) {
        // Unified overlay centered over game area
        // RogueGame needs 4 lines, so make it taller
        boolean isRogueGame = game instanceof com.pastlands.cosmeticslite.minigame.impl.roguelike.RogueGame;
        int overlayWidth = gameAreaWidth / 3;
        int overlayHeight = isRogueGame ? 80 : 60; // Taller for RogueGame (4 lines vs 3)
        int ox = gameAreaX + (gameAreaWidth - overlayWidth) / 2;
        int oy = gameAreaY + (gameAreaHeight - overlayHeight) / 2;
        
        // Semi-transparent black box
        g.fill(ox, oy, ox + overlayWidth, oy + overlayHeight, 0xAA000000);
        
        // Border
        g.fill(ox, oy, ox + overlayWidth, oy + 1, COL_PANEL_BORDER); // Top
        g.fill(ox, oy + overlayHeight - 1, ox + overlayWidth, oy + overlayHeight, COL_PANEL_BORDER); // Bottom
        g.fill(ox, oy, ox + 1, oy + overlayHeight, COL_PANEL_BORDER); // Left
        g.fill(ox + overlayWidth - 1, oy, ox + overlayWidth, oy + overlayHeight, COL_PANEL_BORDER); // Right
        
        // Get game-specific end title
        Component endTitle = getEndTitle();
        int score = game.getScore();
        
        // Center of overlay
        int centerX = ox + overlayWidth / 2;
        int centerY = oy + overlayHeight / 2;
        int lineHeight = 14;
        int line = 0;
        
        // Special handling for RogueGame - show Depth and Gold
        if (game instanceof com.pastlands.cosmeticslite.minigame.impl.roguelike.RogueGame) {
            com.pastlands.cosmeticslite.minigame.impl.roguelike.RogueGame rogueGame = 
                (com.pastlands.cosmeticslite.minigame.impl.roguelike.RogueGame) game;
            
            // Line 1: Result (You died! / You win!)
            g.drawString(font, endTitle, centerX - font.width(endTitle) / 2, centerY - lineHeight + lineHeight * line, COL_TEXT_MAIN, true);
            line++;
            
            // Line 2: Depth
            String depthText = "Depth: " + rogueGame.getDepth();
            g.drawString(font, Component.literal(depthText), centerX - font.width(depthText) / 2, centerY - lineHeight + lineHeight * line, COL_TEXT_MAIN, true);
            line++;
            
            // Line 3: Gold
            String goldText = "Gold: " + score;
            g.drawString(font, Component.literal(goldText), centerX - font.width(goldText) / 2, centerY - lineHeight + lineHeight * line, COL_TEXT_MAIN, true);
            line++;
            
            // Line 4: Reset hint
            Component resetHint = Component.translatable("cosmeticslite.minigame.generic.press_reset");
            g.drawString(font, resetHint, centerX - font.width(resetHint) / 2, centerY - lineHeight + lineHeight * line, COL_TEXT_MAIN, true);
        } else {
            // Standard format for other games
            // Line 1: End title
            g.drawString(font, endTitle, centerX - font.width(endTitle) / 2, centerY - lineHeight + lineHeight * line, COL_TEXT_MAIN, true);
            line++;
            
            // Line 2: Score
            String scoreText = "Score: " + score;
            g.drawString(font, Component.literal(scoreText), centerX - font.width(scoreText) / 2, centerY - lineHeight + lineHeight * line, COL_TEXT_MAIN, true);
            line++;
            
            // Line 3: Reset hint
            Component resetHint = Component.translatable("cosmeticslite.minigame.generic.press_reset");
            g.drawString(font, resetHint, centerX - font.width(resetHint) / 2, centerY - lineHeight + lineHeight * line, COL_TEXT_MAIN, true);
        }
    }
    
    private Component getEndTitle() {
        String title = game.getTitle();
        int score = game.getScore();
        
        // Special handling for different games
        if (game instanceof MarbleTiltGame) {
            return Component.translatable("cosmeticslite.minigame.goal_reached");
        } else if (title.contains("Minesweeper") && score == -1) {
            return Component.translatable("cosmeticslite.minigame.game_over");
        } else if (title.contains("Slide Puzzle")) {
            return Component.translatable("cosmeticslite.minigame.solved");
        } else if (title.contains("Memory Match")) {
            return Component.translatable("cosmeticslite.minigame.you_win");
        } else if (game instanceof CentipedeGame) {
            // Check if player won (all centipedes cleared) - need to check internal state
            // For now, assume win if game is over (CentipedeGame sets gameOver=true on win)
            // This is a simplification - ideally CentipedeGame would expose playerWon
            return Component.translatable("cosmeticslite.minigame.you_win");
        } else if (game instanceof RogueGame) {
            // RogueGame always shows "You died!" (no win condition implemented)
            return Component.literal("You died!");
        } else {
            return Component.translatable("cosmeticslite.minigame.game_over");
        }
    }
    
    // Helper methods for rounded rectangles (copied from CosmeticsChestScreen style)
    private static void fillRounded(GuiGraphics g, int x0, int y0, int x1, int y1, int r, int argb) {
        if (r <= 0) { 
            g.fill(x0, y0, x1, y1, argb); 
            return; 
        }
        r = Math.min(r, Math.min((x1 - x0) / 2, (y1 - y0) / 2));
        g.fill(x0 + r, y0, x1 - r, y1, argb);
        g.fill(x0, y0 + r, x0 + r, y1 - r, argb);
        g.fill(x1 - r, y0 + r, x1, y1 - r, argb);
        for (int dy = 0; dy < r; dy++) {
            double y = r - dy - 0.5;
            int dx = (int)Math.floor(Math.sqrt(r * r - y * y));
            g.fill(x0 + r - dx, y0 + dy, x0 + r, y0 + dy + 1, argb);
            g.fill(x1 - r, y0 + dy, x1 - r + dx, y0 + dy + 1, argb);
            g.fill(x0 + r - dx, y1 - dy - 1, x0 + r, y1 - dy, argb);
            g.fill(x1 - r, y1 - dy - 1, x1 - r + dx, y1 - dy, argb);
        }
    }
    
    private static void drawSoftRoundedShadow(GuiGraphics g, int l, int t, int r, int b) {
        final int padNear = 6;
        final int padFar = 10;
        final int radius = 10;
        fillRounded(g, l - padFar, t - padFar, r + padFar, b + padFar, radius + 2, 0x11000000);
        fillRounded(g, l - padNear, t - padNear, r + padNear, b + padNear, radius, 0x22000000);
    }
    
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Check if click is in game area
        if (mouseX >= gameAreaX && mouseX < gameAreaX + gameAreaWidth &&
            mouseY >= gameAreaY && mouseY < gameAreaY + gameAreaHeight) {
            game.handleMouseClick(mouseX, mouseY, button);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC key closes the screen
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        
        // R key resets the game (but not for Battleship - it uses Q/E for rotation)
        if (keyCode == GLFW.GLFW_KEY_R) {
            // Skip R key reset for Battleship - rotation uses Q/E, reset must be manual
            if (!(game instanceof com.pastlands.cosmeticslite.minigame.impl.battleship.BattleshipGame)) {
                game.onClose();
                game.initGame();
                return true;
            }
        }
        
        // Space key always goes to the game (for shooting in Centipede, etc.)
        // This prevents Space from activating buttons even if they have focus
        if (keyCode == GLFW.GLFW_KEY_SPACE) {
            if (game != null) {
                game.handleKeyPress(keyCode);
            }
            // Consume the event so it never reaches buttons
            return true;
        }
        
        // Forward other keys to the game
        game.handleKeyPress(keyCode);
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    
    @Override
    public void onClose() {
        game.onClose();
        this.minecraft.setScreen(this.parent);
    }
}
