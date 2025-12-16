package com.pastlands.cosmeticslite.minigame.score;

import com.google.gson.annotations.SerializedName;

/**
 * Data object for a single run.
 */
public class MiniGameScoreEntry {
    
    @SerializedName("timeEpochMs")
    private long timeEpochMs;
    
    @SerializedName("score")
    private int score;
    
    @SerializedName("depth")
    private Integer depth; // Optional - for Roguelike
    
    @SerializedName("win")
    private Boolean win; // Optional - for Battleship
    
    @SerializedName("moves")
    private Integer moves; // Optional - for Slide Puzzle (can be same as score)
    
    // Default constructor for Gson
    public MiniGameScoreEntry() {}
    
    public MiniGameScoreEntry(long timeEpochMs, int score) {
        this.timeEpochMs = timeEpochMs;
        this.score = score;
    }
    
    public long getTimeEpochMs() {
        return timeEpochMs;
    }
    
    public void setTimeEpochMs(long timeEpochMs) {
        this.timeEpochMs = timeEpochMs;
    }
    
    public int getScore() {
        return score;
    }
    
    public void setScore(int score) {
        this.score = score;
    }
    
    public Integer getDepth() {
        return depth;
    }
    
    public void setDepth(Integer depth) {
        this.depth = depth;
    }
    
    public Boolean getWin() {
        return win;
    }
    
    public void setWin(Boolean win) {
        this.win = win;
    }
    
    public Integer getMoves() {
        return moves;
    }
    
    public void setMoves(Integer moves) {
        this.moves = moves;
    }
}
