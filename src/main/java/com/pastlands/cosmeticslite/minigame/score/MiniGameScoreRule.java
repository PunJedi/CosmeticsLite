package com.pastlands.cosmeticslite.minigame.score;

/**
 * Per-game "best" logic + display behavior configuration.
 */
public final class MiniGameScoreRule {
    
    private final boolean lowerIsBetter;
    private final boolean supportsTop10;
    private final boolean supportsWinLoss;
    
    private MiniGameScoreRule(boolean lowerIsBetter, boolean supportsTop10, boolean supportsWinLoss) {
        this.lowerIsBetter = lowerIsBetter;
        this.supportsTop10 = supportsTop10;
        this.supportsWinLoss = supportsWinLoss;
    }
    
    /**
     * Whether lower scores are better (e.g., slide puzzle moves, time).
     */
    public boolean isLowerIsBetter() {
        return lowerIsBetter;
    }
    
    /**
     * Whether this game supports a Top 10 leaderboard.
     */
    public boolean isSupportsTop10() {
        return supportsTop10;
    }
    
    /**
     * Whether this game tracks wins/losses (e.g., battleship).
     */
    public boolean isSupportsWinLoss() {
        return supportsWinLoss;
    }
    
    /**
     * Get the rule for a specific game ID.
     */
    public static MiniGameScoreRule forGame(String gameId) {
        return switch (gameId) {
            case "slide_puzzle" -> new MiniGameScoreRule(true, true, false);
            case "memory_match" -> new MiniGameScoreRule(true, true, false); // Lower moves is better
            case "battleship" -> new MiniGameScoreRule(false, false, true);
            default -> new MiniGameScoreRule(false, true, false); // Most games: higher is better, supports top10
        };
    }
}
