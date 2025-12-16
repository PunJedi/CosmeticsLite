package com.pastlands.cosmeticslite.minigame.score;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Root persisted structure for all score data.
 */
public class MiniGameScoreData {
    
    @SerializedName("topRuns")
    private Map<String, List<MiniGameScoreEntry>> topRuns = new HashMap<>();
    
    @SerializedName("latestRun")
    private Map<String, MiniGameScoreEntry> latestRun = new HashMap<>();
    
    @SerializedName("bestScoreCached")
    private Map<String, Integer> bestScoreCached = new HashMap<>();
    
    @SerializedName("wins")
    private Map<String, Integer> wins = new HashMap<>();
    
    @SerializedName("losses")
    private Map<String, Integer> losses = new HashMap<>();
    
    // Default constructor for Gson
    public MiniGameScoreData() {}
    
    public Map<String, List<MiniGameScoreEntry>> getTopRuns() {
        if (topRuns == null) {
            topRuns = new HashMap<>();
        }
        return topRuns;
    }
    
    public void setTopRuns(Map<String, List<MiniGameScoreEntry>> topRuns) {
        this.topRuns = topRuns;
    }
    
    public Map<String, MiniGameScoreEntry> getLatestRun() {
        if (latestRun == null) {
            latestRun = new HashMap<>();
        }
        return latestRun;
    }
    
    public void setLatestRun(Map<String, MiniGameScoreEntry> latestRun) {
        this.latestRun = latestRun;
    }
    
    public Map<String, Integer> getBestScoreCached() {
        if (bestScoreCached == null) {
            bestScoreCached = new HashMap<>();
        }
        return bestScoreCached;
    }
    
    public void setBestScoreCached(Map<String, Integer> bestScoreCached) {
        this.bestScoreCached = bestScoreCached;
    }
    
    public Map<String, Integer> getWins() {
        if (wins == null) {
            wins = new HashMap<>();
        }
        return wins;
    }
    
    public void setWins(Map<String, Integer> wins) {
        this.wins = wins;
    }
    
    public Map<String, Integer> getLosses() {
        if (losses == null) {
            losses = new HashMap<>();
        }
        return losses;
    }
    
    public void setLosses(Map<String, Integer> losses) {
        this.losses = losses;
    }
}
