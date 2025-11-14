package com.pastlands.cosmeticslite.minigame.client;

import com.pastlands.cosmeticslite.minigame.impl.snake.SnakeGame;
import com.pastlands.cosmeticslite.minigame.impl.minesweeper.MinesweeperGame;
import com.pastlands.cosmeticslite.minigame.impl.slide.SlidePuzzleGame;
import com.pastlands.cosmeticslite.minigame.impl.memory.MemoryMatchGame;
import com.pastlands.cosmeticslite.minigame.impl.tilt.MarbleTiltGame;
import com.pastlands.cosmeticslite.minigame.impl.battleship.BattleshipGame;
import com.pastlands.cosmeticslite.minigame.impl.centipede.CentipedeGame;
import com.pastlands.cosmeticslite.minigame.impl.roguelike.RogueGame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Hub screen for selecting mini-games with CosmeticsLite styling.
 */
public class MiniGameHubScreen extends Screen {
    
    // Shared visual style constants
    private static final int COL_BG            = 0xCC000000; // dim behind panel
    private static final int COL_PANEL_BASE    = 0xFF2A2A35; // main panel
    private static final int COL_PANEL_BORDER  = 0xFF5A5A65; // outer border
    private static final int COL_PANEL_INNER   = 0xFF3A3A45; // inner inset
    private static final int COL_TEXT_MAIN     = 0xFFFFFFFF; // white with shadow
    private static final int COL_TEXT_SUBTLE   = 0xFFCCCCCC;
    private static final int COL_SELECTED_BTN  = 0x4000AAFF; // Subtle blue highlight for selected button
    
    private static final int BUTTON_WIDTH = 180;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_SPACING = 25;
    private static final int PANEL_WIDTH = 500;
    private static final int PANEL_HEIGHT = 300;
    private static final int OUTER_MARGIN = 16;
    
    private Button snakeButton;
    private Button minesweeperButton;
    private Button slidePuzzleButton;
    private Button memoryMatchButton;
    private Button marbleTiltButton;
    private Button battleshipButton;
    private Button centipedeButton;
    private Button rogueButton;
    private Button backButton;
    
    private int selectedGameIndex = -1; // -1 = none, 0-7 = game index
    
    private int panelLeft, panelTop, panelRight, panelBottom;
    private int buttonAreaLeft, buttonAreaRight;
    private int descAreaLeft, descAreaRight;
    
    private final Screen parent;
    
    public MiniGameHubScreen(Screen parent) {
        super(Component.translatable("cosmeticslite.minigame.title"));
        this.parent = parent;
    }
    
    @Override
    protected void init() {
        super.init();
        
        // Calculate panel bounds (centered)
        panelLeft = (width - PANEL_WIDTH) / 2;
        panelTop = (height - PANEL_HEIGHT) / 2;
        panelRight = panelLeft + PANEL_WIDTH;
        panelBottom = panelTop + PANEL_HEIGHT;
        
        // Split layout: buttons on left, description on right
        int splitX = panelLeft + PANEL_WIDTH / 2;
        buttonAreaLeft = panelLeft + 20;
        buttonAreaRight = splitX - 20;
        descAreaLeft = splitX + 20;
        descAreaRight = panelRight - 20;
        
        int startY = panelTop + 50;
        
        // Mini Snake button
        snakeButton = Button.builder(
            Component.translatable("cosmeticslite.minigame.snake"),
            button -> {
                selectedGameIndex = 0;
                Minecraft.getInstance().setScreen(new MiniGamePlayScreen(this, new SnakeGame()));
            }
        ).bounds(buttonAreaLeft, startY, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        addRenderableWidget(snakeButton);
        
        // Mini Minesweeper button
        minesweeperButton = Button.builder(
            Component.translatable("cosmeticslite.minigame.minesweeper"),
            button -> {
                selectedGameIndex = 1;
                Minecraft.getInstance().setScreen(new MiniGamePlayScreen(this, new MinesweeperGame()));
            }
        ).bounds(buttonAreaLeft, startY + BUTTON_SPACING, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        addRenderableWidget(minesweeperButton);
        
        // Slide Puzzle button
        slidePuzzleButton = Button.builder(
            Component.translatable("cosmeticslite.minigame.slide"),
            button -> {
                selectedGameIndex = 2;
                Minecraft.getInstance().setScreen(new MiniGamePlayScreen(this, new SlidePuzzleGame()));
            }
        ).bounds(buttonAreaLeft, startY + BUTTON_SPACING * 2, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        addRenderableWidget(slidePuzzleButton);
        
        // Memory Match button
        memoryMatchButton = Button.builder(
            Component.translatable("cosmeticslite.minigame.memory"),
            button -> {
                selectedGameIndex = 3;
                Minecraft.getInstance().setScreen(new MiniGamePlayScreen(this, new MemoryMatchGame()));
            }
        ).bounds(buttonAreaLeft, startY + BUTTON_SPACING * 3, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        addRenderableWidget(memoryMatchButton);
        
        // Marble Tilt button
        marbleTiltButton = Button.builder(
            Component.translatable("cosmeticslite.minigame.tilt"),
            button -> {
                selectedGameIndex = 4;
                Minecraft.getInstance().setScreen(new MiniGamePlayScreen(this, new MarbleTiltGame()));
            }
        ).bounds(buttonAreaLeft, startY + BUTTON_SPACING * 4, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        addRenderableWidget(marbleTiltButton);
        
        // Battleship button
        battleshipButton = Button.builder(
            Component.translatable("cosmeticslite.minigame.battleship"),
            button -> {
                selectedGameIndex = 5;
                Minecraft.getInstance().setScreen(new MiniGamePlayScreen(this, new BattleshipGame()));
            }
        ).bounds(buttonAreaLeft, startY + BUTTON_SPACING * 5, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        addRenderableWidget(battleshipButton);
        
        // Centipede button
        centipedeButton = Button.builder(
            Component.translatable("cosmeticslite.minigame.centipede"),
            button -> {
                selectedGameIndex = 6;
                Minecraft.getInstance().setScreen(new MiniGamePlayScreen(this, new CentipedeGame()));
            }
        ).bounds(buttonAreaLeft, startY + BUTTON_SPACING * 6, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        addRenderableWidget(centipedeButton);
        
        // Roguelike button
        rogueButton = Button.builder(
            Component.translatable("cosmeticslite.minigame.rogue"),
            button -> {
                selectedGameIndex = 7;
                Minecraft.getInstance().setScreen(new MiniGamePlayScreen(this, new RogueGame()));
            }
        ).bounds(buttonAreaLeft, startY + BUTTON_SPACING * 7, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        addRenderableWidget(rogueButton);
        
        // Back button
        backButton = Button.builder(
            Component.translatable("cosmeticslite.minigame.button.back"),
            button -> {
                this.minecraft.setScreen(this.parent);
            }
        ).bounds(buttonAreaLeft, panelBottom - 40, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        addRenderableWidget(backButton);
    }
    
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        // Draw semi-transparent full-screen dim
        g.fill(0, 0, width, height, COL_BG);
        
        // Draw CosmeticsLite-style panel
        drawPanel(g);
        
        // Draw title - white with shadow
        int titleY = panelTop + 20;
        Component titleText = Component.translatable("cosmeticslite.minigame.title");
        int titleX = (panelLeft + panelRight) / 2;
        g.drawString(font, titleText, titleX - font.width(titleText) / 2, titleY, COL_TEXT_MAIN, true);
        
        // Update selected game based on hover
        updateSelectedGame(mouseX, mouseY);
        
        // Highlight selected button
        highlightSelectedButton(g);
        
        // Draw description box
        drawDescriptionBox(g);
        
        super.render(g, mouseX, mouseY, partialTicks);
    }
    
    private void highlightSelectedButton(GuiGraphics g) {
        if (selectedGameIndex < 0) return;
        
        Button selectedBtn = switch (selectedGameIndex) {
            case 0 -> snakeButton;
            case 1 -> minesweeperButton;
            case 2 -> slidePuzzleButton;
            case 3 -> memoryMatchButton;
            case 4 -> marbleTiltButton;
            case 5 -> battleshipButton;
            case 6 -> centipedeButton;
            case 7 -> rogueButton;
            default -> null;
        };
        
        if (selectedBtn != null) {
            // Draw subtle border/highlight around selected button
            int x = selectedBtn.getX();
            int y = selectedBtn.getY();
            int w = selectedBtn.getWidth();
            int h = selectedBtn.getHeight();
            g.fill(x - 1, y - 1, x + w + 1, y, COL_SELECTED_BTN);
            g.fill(x - 1, y + h, x + w + 1, y + h + 1, COL_SELECTED_BTN);
            g.fill(x - 1, y, x, y + h, COL_SELECTED_BTN);
            g.fill(x + w, y, x + w + 1, y + h, COL_SELECTED_BTN);
        }
    }
    
    private void drawPanel(GuiGraphics g) {
        // Shadow
        drawSoftRoundedShadow(g, panelLeft, panelTop, panelRight, panelBottom);
        
        // Outer border
        fillRounded(g, panelLeft - 2, panelTop - 2, panelRight + 2, panelBottom + 2, 8, COL_PANEL_BORDER);
        
        // Main panel base
        fillRounded(g, panelLeft, panelTop, panelRight, panelBottom, 8, COL_PANEL_BASE);
        
        // Inner inset stripe at top
        int insetHeight = 4;
        fillRounded(g, panelLeft + 2, panelTop + 2, panelRight - 2, panelTop + 2 + insetHeight, 2, COL_PANEL_INNER);
        
        // Vertical divider line
        int splitX = panelLeft + PANEL_WIDTH / 2;
        g.fill(splitX, panelTop + 40, splitX + 1, panelBottom - 10, COL_PANEL_BORDER);
    }
    
    private void updateSelectedGame(double mouseX, double mouseY) {
        if (snakeButton.isMouseOver(mouseX, mouseY)) {
            selectedGameIndex = 0;
        } else if (minesweeperButton.isMouseOver(mouseX, mouseY)) {
            selectedGameIndex = 1;
        } else if (slidePuzzleButton.isMouseOver(mouseX, mouseY)) {
            selectedGameIndex = 2;
        } else if (memoryMatchButton.isMouseOver(mouseX, mouseY)) {
            selectedGameIndex = 3;
        } else if (marbleTiltButton.isMouseOver(mouseX, mouseY)) {
            selectedGameIndex = 4;
        } else if (battleshipButton.isMouseOver(mouseX, mouseY)) {
            selectedGameIndex = 5;
        } else if (centipedeButton.isMouseOver(mouseX, mouseY)) {
            selectedGameIndex = 6;
        } else if (rogueButton.isMouseOver(mouseX, mouseY)) {
            selectedGameIndex = 7;
        }
    }
    
    private void drawDescriptionBox(GuiGraphics g) {
        if (selectedGameIndex < 0) return;
        
        int descY = panelTop + 50;
        int descHeight = 200;
        int descWidth = descAreaRight - descAreaLeft;
        
        // Description box background (using panel inner color)
        fillRounded(g, descAreaLeft, descY, descAreaRight, descY + descHeight, 4, COL_PANEL_BORDER);
        fillRounded(g, descAreaLeft + 1, descY + 1, descAreaRight - 1, descY + descHeight - 1, 3, COL_PANEL_INNER);
        
        // Title - white with shadow
        String title = getGameTitle(selectedGameIndex);
        int titleX = descAreaLeft + 10;
        int titleY = descY + 10;
        g.drawString(font, Component.literal(title), titleX, titleY, COL_TEXT_MAIN, true);
        
        // Description text - white with shadow, increased spacing
        String[] description = getGameDescription(selectedGameIndex);
        int textY = titleY + 28; // Increased from 25 to 28 for better spacing
        for (String line : description) {
            g.drawString(font, Component.literal(line), titleX, textY, COL_TEXT_MAIN, true);
            textY += 14; // Increased from 12 to 14 for better readability
        }
        
        // Controls hint - subtle text with shadow
        String controls = getGameControls(selectedGameIndex);
        if (controls != null) {
            textY += 12; // Increased spacing
            g.drawString(font, Component.literal("Controls:"), titleX, textY, COL_TEXT_SUBTLE, true);
            textY += 14; // Increased from 12 to 14
            g.drawString(font, Component.literal(controls), titleX + 5, textY, COL_TEXT_SUBTLE, true);
        }
    }
    
    private String getGameTitle(int index) {
        return switch (index) {
            case 0 -> "Mini Snake";
            case 1 -> "Mini Minesweeper";
            case 2 -> "Slide Puzzle";
            case 3 -> "Memory Match";
            case 4 -> "Marble Tilt";
            case 5 -> "Battleship";
            case 6 -> "Mini Centipede";
            case 7 -> "Mini Roguelike";
            default -> "";
        };
    }
    
    private String[] getGameDescription(int index) {
        return switch (index) {
            case 0 -> new String[]{"Classic snake game!", "Eat food to grow longer.", "Avoid hitting walls or yourself."};
            case 1 -> new String[]{"Find all mines!", "Left-click to reveal cells.", "Right-click to flag mines."};
            case 2 -> new String[]{"Slide tiles to solve!", "Click tiles next to the", "empty space to move them."};
            case 3 -> new String[]{"Match pairs of cards!", "Click two cards to flip them.", "Find all matching pairs."};
            case 4 -> new String[]{"Guide the marble!", "Tilt the board to roll", "the marble to the goal."};
            case 5 -> new String[]{"Sink the enemy fleet!", "Place your ships, then", "take turns firing at the enemy.", "", "Controls:", "Arrows/WASD: Move ship cursor,", "Q/E: Rotate, Enter or Click: Place/Fire"};
            case 6 -> new String[]{"Arcade shooter!", "Shoot the centipede segments", "before they reach you.", "", "Controls:", "Left/Right: Move, Space: Shoot"};
            case 7 -> new String[]{"You are the cyan square.", "Red: monsters. Yellow: gold.", "Green: exit tile.", "Move: Arrows/WASD. Space: wait."};
            default -> new String[]{};
        };
    }
    
    private String getGameControls(int index) {
        return switch (index) {
            case 0 -> "Arrow keys or WASD";
            case 1 -> "Left-click: Reveal, Right-click: Flag";
            case 2 -> "Click tiles or use arrow keys";
            case 3 -> "Click cards to flip";
            case 4 -> "Arrow keys or WASD";
            case 5 -> null; // Controls are now in description for Battleship
            case 6 -> null; // Controls are now in description for Centipede
            case 7 -> null; // Controls are now in description for Roguelike
            default -> null;
        };
    }
    
    // Helper methods for rounded rectangles
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
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    
    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
