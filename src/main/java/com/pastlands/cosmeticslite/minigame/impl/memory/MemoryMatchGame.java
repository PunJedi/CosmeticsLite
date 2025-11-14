package com.pastlands.cosmeticslite.minigame.impl.memory;

import com.pastlands.cosmeticslite.minigame.api.MiniGame;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Memory Match (card flip matching) mini-game implementation.
 */
public class MemoryMatchGame implements MiniGame {
    
    private static final int COLUMNS = 4;
    private static final int ROWS = 4;
    private static final int MISMATCH_DELAY_TICKS = 20; // 1 second at 20 TPS
    private static final float FLIP_SPEED = 0.12f; // Animation speed per tick
    
    private Card[][] grid;
    private Card firstSelection;
    private Card secondSelection;
    private int revealedTicks;
    private int moves;
    private boolean gameOver;
    private Random random;
    private int areaX, areaY, areaWidth, areaHeight; // Store for mouse calculations
    
    private static class Card {
        int id; // pair ID, from 0 to 7
        boolean matched;
        boolean faceUp;
        float flipProgress; // 0.0 = back, 1.0 = front, animates during flip
        boolean isFlipping;
        
        Card(int id) {
            this.id = id;
            this.matched = false;
            this.faceUp = false;
            this.flipProgress = 0.0f;
            this.isFlipping = false;
        }
    }
    
    // Color palette per pair ID (one color per pair)
    private static final int[] PAIR_COLORS = {
        0xFFE74C3C, // red
        0xFF3498DB, // blue
        0xFF2ECC71, // green
        0xFFF1C40F, // yellow
        0xFF9B59B6, // purple
        0xFFE67E22, // orange
        0xFF1ABC9C, // teal
        0xFFEC407A  // pink
    };
    
    private int getPairColor(int value) {
        return PAIR_COLORS[Math.floorMod(value, PAIR_COLORS.length)];
    }
    
    private int darken(int color, float factor) {
        int r = (int)(((color >> 16) & 0xFF) * factor);
        int g = (int)(((color >> 8) & 0xFF) * factor);
        int b = (int)((color & 0xFF) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
    
    @Override
    public void initGame() {
        random = new Random();
        gameOver = false;
        moves = 0;
        revealedTicks = 0;
        firstSelection = null;
        secondSelection = null;
        grid = new Card[ROWS][COLUMNS];
        
        // Create list of IDs (each ID appears twice)
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ids.add(i);
            ids.add(i);
        }
        
        // Shuffle
        Collections.shuffle(ids, random);
        
        // Fill grid
        int index = 0;
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLUMNS; x++) {
                grid[y][x] = new Card(ids.get(index++));
            }
        }
    }
    
    @Override
    public void tick() {
        if (gameOver) return;
        
        // Update flip animations for all cards
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLUMNS; x++) {
                Card card = grid[y][x];
                if (card.isFlipping) {
                    if (card.faceUp) {
                        // Flipping to front
                        card.flipProgress += FLIP_SPEED;
                        if (card.flipProgress >= 1.0f) {
                            card.flipProgress = 1.0f;
                            card.isFlipping = false;
                        }
                    } else {
                        // Flipping to back
                        card.flipProgress -= FLIP_SPEED;
                        if (card.flipProgress <= 0.0f) {
                            card.flipProgress = 0.0f;
                            card.isFlipping = false;
                        }
                    }
                }
            }
        }
        
        // If both cards are revealed, wait for delay then check match
        if (firstSelection != null && secondSelection != null) {
            revealedTicks++;
            
            if (revealedTicks >= MISMATCH_DELAY_TICKS) {
                // Check if IDs match
                if (firstSelection.id == secondSelection.id) {
                    // Match!
                    firstSelection.matched = true;
                    secondSelection.matched = true;
                } else {
                    // Mismatch - start flipping back
                    firstSelection.faceUp = false;
                    firstSelection.isFlipping = true;
                    secondSelection.faceUp = false;
                    secondSelection.isFlipping = true;
                }
                
                // Clear selections
                firstSelection = null;
                secondSelection = null;
                revealedTicks = 0;
                
                // Check win condition
                checkWin();
            }
        }
    }
    
    private void checkWin() {
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLUMNS; x++) {
                if (!grid[y][x].matched) {
                    return; // Not all matched yet
                }
            }
        }
        // All matched!
        gameOver = true;
    }
    
    @Override
    public void handleMouseClick(double mouseX, double mouseY, int button) {
        if (gameOver || button != 0) return; // Only left click
        
        // Can't click if waiting for mismatch delay
        if (secondSelection != null) return;
        
        double cardWidth = (double) areaWidth / COLUMNS;
        double cardHeight = (double) areaHeight / ROWS;
        
        int cardX = (int) Math.floor((mouseX - areaX) / cardWidth);
        int cardY = (int) Math.floor((mouseY - areaY) / cardHeight);
        
        if (cardX < 0 || cardX >= COLUMNS || cardY < 0 || cardY >= ROWS) {
            return;
        }
        
        Card card = grid[cardY][cardX];
        
        // Ignore if already matched or already face up
        if (card.matched || card.faceUp) {
            return;
        }
        
        // Start flipping the card
        card.faceUp = true;
        card.isFlipping = true;
        
        if (firstSelection == null) {
            firstSelection = card;
            moves++;
        } else if (secondSelection == null) {
            secondSelection = card;
        }
    }
    
    @Override
    public void handleKeyPress(int keyCode) {
        // Not used
    }
    
    @Override
    public void render(GuiGraphics g, Font font, int areaX, int areaY, int areaWidth, int areaHeight, float partialTicks) {
        this.areaX = areaX;
        this.areaY = areaY;
        this.areaWidth = areaWidth;
        this.areaHeight = areaHeight;
        
        int cardWidth = areaWidth / COLUMNS;
        int cardHeight = areaHeight / ROWS;
        
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLUMNS; x++) {
                int px = areaX + x * cardWidth;
                int py = areaY + y * cardHeight;
                Card card = grid[y][x];
                
                // Card spacing (small gaps)
                int gap = 2;
                int cardPX = px + gap;
                int cardPY = py + gap;
                int cardPW = cardWidth - gap * 2;
                int cardPH = cardHeight - gap * 2;
                
                // Calculate flip animation progress with partial ticks
                float currentFlipProgress = card.flipProgress;
                if (card.isFlipping) {
                    float progressDelta = card.faceUp ? FLIP_SPEED * partialTicks : -FLIP_SPEED * partialTicks;
                    currentFlipProgress = Math.max(0.0f, Math.min(1.0f, card.flipProgress + progressDelta));
                }
                
                // Calculate scale for flip animation (1 → 0 → 1)
                float scaleX = Math.abs(1.0f - 2.0f * currentFlipProgress);
                
                // Determine which side to show (back if < 0.5, front if >= 0.5)
                boolean showFront = currentFlipProgress >= 0.5f;
                
                // Calculate scaled card dimensions
                int centerX = cardPX + cardPW / 2;
                int scaledWidth = (int)(cardPW * scaleX);
                int scaledLeft = centerX - scaledWidth / 2;
                int scaledRight = centerX + scaledWidth / 2;
                
                // Draw card with scale effect
                if (card.matched) {
                    // Matched card - white panel with symbol and green glow
                    drawCardFront(g, font, scaledLeft, cardPY, scaledRight - scaledLeft, cardPH, card.id, true);
                    // Matched highlight - soft green border/glow
                    drawMatchedGlow(g, cardPX, cardPY, cardPW, cardPH);
                } else if (showFront) {
                    // Face up - white panel with symbol
                    drawCardFront(g, font, scaledLeft, cardPY, scaledRight - scaledLeft, cardPH, card.id, false);
                } else {
                    // Face down - darker card back
                    drawCardBack(g, scaledLeft, cardPY, scaledRight - scaledLeft, cardPH);
                }
                
                // Grid lines
                g.fill(px + cardWidth - 1, py, px + cardWidth, py + cardHeight, 0xFF000000);
                g.fill(px, py + cardHeight - 1, px + cardWidth, py + cardHeight, 0xFF000000);
            }
        }
    }
    
    private void drawCardFront(GuiGraphics g, Font font, int x, int y, int width, int height, int cardId, boolean isMatched) {
        // Get pair color for this card
        int baseColor = getPairColor(cardId);
        
        // Outer fill with base color
        g.fill(x, y, x + width, y + height, baseColor);
        
        // Inner inset with darker color for depth (1px inset)
        int darker = darken(baseColor, 0.8f);
        g.fill(x + 1, y + 1, x + width - 1, y + height - 1, darker);
        
        // Draw number/symbol in neutral black color
        String numStr = String.valueOf(cardId + 1);
        int numX = x + width / 2 - font.width(numStr) / 2;
        int numY = y + height / 2 - font.lineHeight / 2;
        g.drawString(font, Component.literal(numStr), numX, numY, 0xFF000000, true);
    }
    
    private void drawCardBack(GuiGraphics g, int x, int y, int width, int height) {
        // Darker card back with subtle border
        g.fill(x, y, x + width, y + height, 0xFF3A3A3A); // Outer border
        g.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF4A4A4A); // Inner fill
        
        // Subtle inner border
        g.fill(x + 2, y + 2, x + width - 2, y + 3, 0xFF2A2A2A);
        g.fill(x + 2, y + height - 3, x + width - 2, y + height - 2, 0xFF5A5A5A);
        g.fill(x + 2, y + 2, x + 3, y + height - 2, 0xFF2A2A2A);
        g.fill(x + width - 3, y + 2, x + width - 2, y + height - 2, 0xFF5A5A5A);
        
        // Draw central icon (diamond shape) - white for visibility
        int centerX = x + width / 2;
        int centerY = y + height / 2;
        int iconSize = Math.min(width, height) / 4;
        
        // Draw diamond shape (white)
        for (int i = 0; i < iconSize; i++) {
            int w = iconSize - i;
            if (centerX - w >= x + 3 && centerX + w < x + width - 3) {
                g.fill(centerX - w, centerY - iconSize + i, 
                    centerX + w, centerY - iconSize + i + 1, 0xFFFFFFFF);
                g.fill(centerX - w, centerY + iconSize - i, 
                    centerX + w, centerY + iconSize - i + 1, 0xFFFFFFFF);
            }
        }
    }
    
    private void drawMatchedGlow(GuiGraphics g, int x, int y, int width, int height) {
        // Slim green outline for matched cards
        int outlineColor = 0xFF33FF66;
        
        // Top edge
        g.fill(x, y, x + width, y + 1, outlineColor);
        // Bottom edge
        g.fill(x, y + height - 1, x + width, y + height, outlineColor);
        // Left edge
        g.fill(x, y, x + 1, y + height, outlineColor);
        // Right edge
        g.fill(x + width - 1, y, x + width, y + height, outlineColor);
    }
    
    @Override
    public String getTitle() {
        return "Memory Match";
    }
    
    @Override
    public boolean isGameOver() {
        return gameOver;
    }
    
    @Override
    public int getScore() {
        return moves;
    }
    
    @Override
    public void onClose() {
        // No cleanup needed
    }
}

