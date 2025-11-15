package com.pastlands.cosmeticslite.minigame.impl.memory;

import com.pastlands.cosmeticslite.minigame.api.MiniGame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Memory Match (card flip matching) mini-game implementation.
 */
public class MemoryMatchGame implements MiniGame {
    
    // Difficulty scaling
    private int currentLevel = 1;
    private static final int BASE_PAIRS = 6;
    private static final int PAIRS_PER_LEVEL = 2;
    private static final int MAX_PAIRS = 14;
    
    // Grid layout (dynamic based on card count)
    private int gridColumns = 4;
    private int gridRows = 4;
    
    private static final int MATCH_CHECK_DELAY = 15; // ticks before resolving match/mismatch
    private static final float FLIP_SPEED = 0.3f; // Animation speed per tick
    private static final float HIGHLIGHT_SPEED = 0.1f; // Highlight animation speed
    
    // Card list (flat list, easier to manage)
    private List<Card> cards;
    
    // Selection state
    private int firstSelectedIndex = -1;
    private int secondSelectedIndex = -1;
    private int pendingResolveTicks = 0;
    private boolean inputLocked = false;
    
    // Level complete message
    private int levelCompleteTicks = 0;
    private static final int LEVEL_COMPLETE_DURATION = 40;
    
    private int moves;
    private boolean gameOver;
    private Random random;
    private int areaX, areaY, areaWidth, areaHeight; // Store for mouse calculations
    
    // Hover and pressed state tracking
    private int hoveredCardIndex = -1;
    private boolean cardPressed = false;
    private int pressedCardIndex = -1;
    
    private static class Card {
        int pairId; // pair ID, from 0 to N-1
        boolean isRevealed;
        boolean isMatched;
        float flipProgress; // 0.0 = back, 1.0 = front, animates during flip
        boolean isFlipping;
        float highlightProgress; // 0.0 → 1.0 for green glow animation
        
        Card(int pairId) {
            this.pairId = pairId;
            this.isRevealed = false;
            this.isMatched = false;
            this.flipProgress = 0.0f;
            this.isFlipping = false;
            this.highlightProgress = 0.0f;
        }
    }
    
    // Color palette per pair ID (one color per pair)
    private static final int[] PAIR_COLORS = {
        0xFFE57373, // soft red
        0xFF64B5F6, // blue
        0xFF81C784, // green
        0xFFFFD54F, // yellow
        0xFFBA68C8, // purple
        0xFFFF8A65, // orange
        0xFFA1887F, // brown
        0xFF4DB6AC  // teal
    };
    
    private int getPairColor(int value) {
        return PAIR_COLORS[Math.floorMod(value, PAIR_COLORS.length)];
    }
    
    @Override
    public void initGame() {
        random = new Random();
        gameOver = false;
        currentLevel = 1;
        moves = 0;
        levelCompleteTicks = 0;
        clearSelectionState();
        buildBoardForLevel(currentLevel);
    }
    
    private void clearSelectionState() {
        firstSelectedIndex = -1;
        secondSelectedIndex = -1;
        pendingResolveTicks = 0;
        inputLocked = false;
        hoveredCardIndex = -1;
        cardPressed = false;
        pressedCardIndex = -1;
    }
    
    private void buildBoardForLevel(int level) {
        // Calculate pair count for this level
        int pairCount = Math.min(BASE_PAIRS + (level - 1) * PAIRS_PER_LEVEL, MAX_PAIRS);
        int totalCards = pairCount * 2;
        
        // Determine grid layout
        // Prefer 4 columns, adjust rows as needed
        gridColumns = 4;
        gridRows = (totalCards + gridColumns - 1) / gridColumns; // Ceiling division
        
        // Create list of pair IDs (each appears twice)
        List<Integer> pairIds = new ArrayList<>();
        for (int i = 0; i < pairCount; i++) {
            pairIds.add(i);
            pairIds.add(i);
        }
        
        // Shuffle
        Collections.shuffle(pairIds, random);
        
        // Create cards
        cards = new ArrayList<>();
        for (int pairId : pairIds) {
            cards.add(new Card(pairId));
        }
        
        // Fill remaining slots with dummy cards (if any)
        int totalSlots = gridRows * gridColumns;
        while (cards.size() < totalSlots) {
            cards.add(null); // Dummy card, won't be rendered
        }
    }
    
    @Override
    public void tick() {
        if (gameOver) return;
        
        // Update level complete message
        if (levelCompleteTicks > 0) {
            levelCompleteTicks--;
        }
        
        // Update flip animations for all cards
        for (Card card : cards) {
            if (card == null) continue;
            
            if (card.isFlipping) {
                if (card.isRevealed) {
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
            
            // Update highlight animation for matched cards
            if (card.isMatched && card.highlightProgress < 1.0f) {
                card.highlightProgress += HIGHLIGHT_SPEED;
                if (card.highlightProgress > 1.0f) {
                    card.highlightProgress = 1.0f;
                }
            }
        }
        
        // Reset pressed state
        cardPressed = false;
        pressedCardIndex = -1;
        
        // Handle match resolution
        if (pendingResolveTicks > 0) {
            pendingResolveTicks--;
            
            if (pendingResolveTicks == 0) {
                resolveMatch();
            }
        }
    }
    
    private void resolveMatch() {
        Card cardA = cards.get(firstSelectedIndex);
        Card cardB = cards.get(secondSelectedIndex);
        
        if (cardA.pairId == cardB.pairId) {
            // Match!
            cardA.isMatched = true;
            cardB.isMatched = true;
            cardA.isRevealed = true; // Keep revealed
            cardB.isRevealed = true; // Keep revealed
            cardA.highlightProgress = 0.0f; // Start highlight animation
            cardB.highlightProgress = 0.0f;
            playLocalSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.35F, 1.3F);
            
            // Check if board is complete
            checkBoardComplete();
        } else {
            // Mismatch - flip back
            cardA.isRevealed = false;
            cardB.isRevealed = false;
            cardA.isFlipping = true; // Start flip animation
            cardB.isFlipping = true;
            playLocalSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.3F, 0.6F);
        }
        
        // Clear selections
        clearSelectionState();
    }
    
    private void checkBoardComplete() {
        // Check if all cards are matched
        for (Card card : cards) {
            if (card != null && !card.isMatched) {
                return; // Not all matched yet
            }
        }
        
        // All matched! Advance to next level
        currentLevel++;
        levelCompleteTicks = LEVEL_COMPLETE_DURATION;
        buildBoardForLevel(currentLevel);
    }
    
    
    @Override
    public void handleMouseClick(double mouseX, double mouseY, int button) {
        if (gameOver || button != 0) return; // Only left click
        
        // Ignore clicks if input is locked
        if (inputLocked) return;
        
        // Convert mouse position to card index
        int cardIndex = getCardIndexAt(mouseX, mouseY);
        if (cardIndex < 0 || cardIndex >= cards.size()) {
            return;
        }
        
        Card card = cards.get(cardIndex);
        if (card == null) return; // Dummy card
        
        // Ignore if already matched or already revealed
        if (card.isMatched || card.isRevealed) {
            return;
        }
        
        // Track pressed state for visual feedback
        cardPressed = true;
        pressedCardIndex = cardIndex;
        
        // Start flipping the card
        card.isRevealed = true;
        card.isFlipping = true;
        card.flipProgress = 0.0f; // Start from back
        
        // Play flip sound
        playLocalSound(SoundEvents.BOOK_PAGE_TURN, 0.3F, 1.5F);
        
        if (firstSelectedIndex == -1) {
            // First selection
            firstSelectedIndex = cardIndex;
            moves++;
        } else if (secondSelectedIndex == -1) {
            // Second selection
            secondSelectedIndex = cardIndex;
            pendingResolveTicks = MATCH_CHECK_DELAY;
            inputLocked = true;
        }
    }
    
    private int getCardIndexAt(double mouseX, double mouseY) {
        if (areaWidth == 0 || areaHeight == 0) return -1;
        
        double cardWidth = (double) areaWidth / gridColumns;
        double cardHeight = (double) areaHeight / gridRows;
        
        int cardX = (int) Math.floor((mouseX - areaX) / cardWidth);
        int cardY = (int) Math.floor((mouseY - areaY) / cardHeight);
        
        if (cardX < 0 || cardX >= gridColumns || cardY < 0 || cardY >= gridRows) {
            return -1;
        }
        
        return cardY * gridColumns + cardX;
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
        
        int cardWidth = areaWidth / gridColumns;
        int cardHeight = areaHeight / gridRows;
        
        for (int index = 0; index < cards.size(); index++) {
            Card card = cards.get(index);
            if (card == null) continue; // Skip dummy cards
            
            int x = index % gridColumns;
            int y = index / gridColumns;
            
            int px = areaX + x * cardWidth;
            int py = areaY + y * cardHeight;
            
            // Card spacing (small gaps)
            int gap = 2;
            int cardPX = px + gap;
            int cardPY = py + gap;
            int cardPW = cardWidth - gap * 2;
            int cardPH = cardHeight - gap * 2;
            
            // Calculate flip animation progress with partial ticks
            float currentFlipProgress = card.flipProgress;
            if (card.isFlipping) {
                float progressDelta = card.isRevealed ? FLIP_SPEED * partialTicks : -FLIP_SPEED * partialTicks;
                currentFlipProgress = Math.max(0.0f, Math.min(1.0f, card.flipProgress + progressDelta));
            }
            
            // Calculate scale for flip animation using simpler formula
            float scaleX = 1.0f - Math.abs(2.0f * currentFlipProgress - 1.0f);
            float scaledWidth = cardPW * (0.25f + 0.75f * scaleX);
            
            // Determine which side to show (back if < 0.5, front if >= 0.5)
            boolean showFront = currentFlipProgress >= 0.5f;
            
            // Calculate scaled card dimensions (centered)
            int centerX = cardPX + cardPW / 2;
            int scaledLeft = (int)(centerX - scaledWidth / 2);
            int scaledRight = (int)(centerX + scaledWidth / 2);
            int scaledW = scaledRight - scaledLeft;
            
            // Check if hovered and flippable
            boolean isHovered = (hoveredCardIndex == index) && 
                !card.isMatched && !card.isRevealed && !card.isFlipping && !inputLocked;
            boolean isPressed = cardPressed && pressedCardIndex == index;
            
            // Draw card with scale effect
            if (card.isMatched) {
                // Matched card - draw front with matched styling
                drawCardFront(g, font, scaledLeft, cardPY, scaledW, cardPH, card.pairId, true);
                // Matched highlight - 2px outline glow (animated)
                if (card.highlightProgress > 0.0f) {
                    drawMatchedGlow(g, cardPX, cardPY, cardPW, cardPH, card.highlightProgress);
                }
            } else if (showFront) {
                // Face up - draw front
                drawCardFront(g, font, scaledLeft, cardPY, scaledW, cardPH, card.pairId, false);
            } else {
                // Face down - draw back
                drawCardBack(g, scaledLeft, cardPY, scaledW, cardPH);
            }
            
            // Hover outline (only on flippable cards)
            if (isHovered) {
                int hoverColor = 0x80FFFFFF; // soft white
                g.fill(cardPX, cardPY, cardPX + cardPW, cardPY + 1, hoverColor); // Top
                g.fill(cardPX, cardPY + cardPH - 1, cardPX + cardPW, cardPY + cardPH, hoverColor); // Bottom
                g.fill(cardPX, cardPY, cardPX + 1, cardPY + cardPH, hoverColor); // Left
                g.fill(cardPX + cardPW - 1, cardPY, cardPX + cardPW, cardPY + cardPH, hoverColor); // Right
            }
            
            // Pressed state - darken overlay
            if (isPressed) {
                g.fill(cardPX, cardPY, cardPX + cardPW, cardPY + cardPH, 0x20000000);
            }
        }
        
        // Draw level complete message
        if (levelCompleteTicks > 0) {
            String message = "Level " + (currentLevel - 1) + " complete!";
            int textX = areaX + areaWidth / 2 - font.width(message) / 2;
            int textY = areaY + areaHeight / 2;
            float alpha = levelCompleteTicks / (float)LEVEL_COMPLETE_DURATION;
            int alphaByte = (int)(alpha * 255) & 0xFF;
            int textColor = (alphaByte << 24) | 0xFFFFFF;
            g.drawString(font, net.minecraft.network.chat.Component.literal(message), textX, textY, textColor, true);
        }
    }
    
    public void updateMouse(double mouseX, double mouseY) {
        hoveredCardIndex = getCardIndexAt(mouseX, mouseY);
    }
    
    private void drawCardFront(GuiGraphics g, Font font, int x, int y, int width, int height, int cardId, boolean isMatched) {
        // Base tile: light neutral panel
        int baseColor = 0xFFE4E4EC;
        int borderColor = 0xFFB0B0BE;
        
        // Fill full rect with base color
        g.fill(x, y, x + width, y + height, baseColor);
        
        // Draw border
        g.fill(x, y, x + width, y + 1, borderColor); // Top
        g.fill(x, y + height - 1, x + width, y + height, borderColor); // Bottom
        g.fill(x, y, x + 1, y + height, borderColor); // Left
        g.fill(x + width - 1, y, x + width, y + height, borderColor); // Right
        
        // Get pair color for icon
        int iconColor = getPairColor(cardId);
        
        // Draw colored icon (diamond shape) centered
        int centerX = x + width / 2;
        int centerY = y + height / 2;
        int iconSize = Math.min(width, height) / 3;
        
        // Draw diamond shape in pair color
        for (int i = 0; i < iconSize; i++) {
            int w = iconSize - i;
            if (centerX - w >= x + 2 && centerX + w < x + width - 2) {
                g.fill(centerX - w, centerY - iconSize + i, 
                    centerX + w, centerY - iconSize + i + 1, iconColor);
                g.fill(centerX - w, centerY + iconSize - i, 
                    centerX + w, centerY + iconSize - i + 1, iconColor);
            }
        }
        
        // If matched, overlay with translucent white to brighten/desaturate
        if (isMatched) {
            g.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0x30FFFFFF);
        }
    }
    
    private void drawCardBack(GuiGraphics g, int x, int y, int width, int height) {
        // Base: dark slate
        int baseColor = 0xFF3A3A3F;
        int innerColor = 0xFF2E2E34;
        int borderColor = 0xFF5A5A65;
        
        // Fill full rect with base color
        g.fill(x, y, x + width, y + height, baseColor);
        
        // Draw 1-2px inset with inner color
        int insetPad = 2;
        g.fill(x + insetPad, y + insetPad, 
            x + width - insetPad, y + height - insetPad, innerColor);
        
        // Border around full rect
        g.fill(x, y, x + width, y + 1, borderColor); // Top
        g.fill(x, y + height - 1, x + width, y + height, borderColor); // Bottom
        g.fill(x, y, x + 1, y + height, borderColor); // Left
        g.fill(x + width - 1, y, x + width, y + height, borderColor); // Right
        
        // Draw simple white hourglass/X icon in the middle
        int centerX = x + width / 2;
        int centerY = y + height / 2;
        int iconSize = Math.min(width, height) / 4;
        
        // Draw X shape (two crossing lines)
        int lineThickness = 2;
        // Diagonal from top-left to bottom-right
        for (int i = -iconSize; i <= iconSize; i++) {
            int px = centerX + i;
            int py1 = centerY - iconSize + Math.abs(i);
            int py2 = centerY + iconSize - Math.abs(i);
            if (px >= x + 3 && px < x + width - 3 && py1 >= y + 3 && py1 < y + height - 3) {
                g.fill(px, py1, px + lineThickness, py1 + 1, 0xFFFFFFFF);
            }
            if (px >= x + 3 && px < x + width - 3 && py2 >= y + 3 && py2 < y + height - 3) {
                g.fill(px, py2, px + lineThickness, py2 + 1, 0xFFFFFFFF);
            }
        }
    }
    
    private void drawMatchedGlow(GuiGraphics g, int x, int y, int width, int height, float progress) {
        // 2px outline glow for matched cards (animated)
        int matchOutline = 0xFF7CFC00; // lime accent
        int thickness = 2;
        
        // Fade in the glow based on progress
        int alpha = (int)(progress * 255) & 0xFF;
        int glowColor = (alpha << 24) | (matchOutline & 0x00FFFFFF);
        
        // Top edge
        g.fill(x, y, x + width, y + thickness, glowColor);
        // Bottom edge
        g.fill(x, y + height - thickness, x + width, y + height, glowColor);
        // Left edge
        g.fill(x, y, x + thickness, y + height, glowColor);
        // Right edge
        g.fill(x + width - thickness, y, x + width, y + height, glowColor);
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
        // Score = number of matched pairs found
        int matchedPairs = 0;
        for (Card card : cards) {
            if (card != null && card.isMatched) {
                matchedPairs++;
            }
        }
        return matchedPairs / 2; // Each pair has 2 cards
    }
    
    @Override
    public void onClose() {
        // No cleanup needed
    }
    
    /**
     * Reset the current level (reshuffle, keep level).
     */
    public void resetLevel() {
        clearSelectionState();
        buildBoardForLevel(currentLevel);
        moves = 0;
    }
}

