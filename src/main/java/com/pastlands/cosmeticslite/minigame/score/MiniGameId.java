package com.pastlands.cosmeticslite.minigame.score;

import com.pastlands.cosmeticslite.minigame.api.MiniGame;
import com.pastlands.cosmeticslite.minigame.impl.battleship.BattleshipGame;
import com.pastlands.cosmeticslite.minigame.impl.centipede.CentipedeGame;
import com.pastlands.cosmeticslite.minigame.impl.memory.MemoryMatchGame;
import com.pastlands.cosmeticslite.minigame.impl.minesweeper.MinesweeperGame;
import com.pastlands.cosmeticslite.minigame.impl.roguelike.RogueGame;
import com.pastlands.cosmeticslite.minigame.impl.slide.SlidePuzzleGame;
import com.pastlands.cosmeticslite.minigame.impl.snake.SnakeGame;
import com.pastlands.cosmeticslite.minigame.impl.tilt.MarbleTiltGame;

/**
 * Maps a MiniGame instance to a stable ID string.
 */
public final class MiniGameId {
    
    private MiniGameId() {}
    
    /**
     * Get the stable ID string for a game instance.
     * @param game The game instance
     * @return The game ID (e.g., "snake", "minesweeper", "slide_puzzle")
     */
    public static String from(MiniGame game) {
        if (game instanceof SnakeGame) {
            return "snake";
        } else if (game instanceof MinesweeperGame) {
            return "minesweeper";
        } else if (game instanceof SlidePuzzleGame) {
            return "slide_puzzle";
        } else if (game instanceof MemoryMatchGame) {
            return "memory_match";
        } else if (game instanceof MarbleTiltGame) {
            return "marble_tilt";
        } else if (game instanceof BattleshipGame) {
            return "battleship";
        } else if (game instanceof CentipedeGame) {
            return "centipede";
        } else if (game instanceof RogueGame) {
            return "roguelike";
        } else {
            return "unknown";
        }
    }
}
