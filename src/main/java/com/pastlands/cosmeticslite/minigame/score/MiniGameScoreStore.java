package com.pastlands.cosmeticslite.minigame.score;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Singleton service for managing minigame scores.
 */
public final class MiniGameScoreStore {
    
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final MiniGameScoreStore INSTANCE = new MiniGameScoreStore();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    private MiniGameScoreData data;
    private boolean loaded = false;
    
    private MiniGameScoreStore() {
        this.data = new MiniGameScoreData();
    }
    
    public static MiniGameScoreStore getInstance() {
        return INSTANCE;
    }
    
    /**
     * Load scores from disk if not already loaded.
     */
    public void loadIfNeeded() {
        if (loaded) return;
        
        Path scoresFile = MiniGameScorePaths.getScoresFile();
        if (Files.exists(scoresFile)) {
            try {
                String json = Files.readString(scoresFile, StandardCharsets.UTF_8);
                data = GSON.fromJson(json, MiniGameScoreData.class);
                if (data == null) {
                    data = new MiniGameScoreData();
                }
                LOGGER.debug("[cosmeticslite] Loaded minigame scores from {}", scoresFile);
            } catch (IOException e) {
                LOGGER.error("[cosmeticslite] Failed to load minigame scores from {}: {}", scoresFile, e.getMessage(), e);
                data = new MiniGameScoreData();
            }
        } else {
            data = new MiniGameScoreData();
        }
        
        // Recompute best scores cache
        recomputeBestScores();
        
        loaded = true;
    }
    
    /**
     * Save scores to disk immediately.
     */
    public void saveNow() {
        MiniGameScorePaths.ensureScoresDirectory();
        Path scoresFile = MiniGameScorePaths.getScoresFile();
        
        try {
            String json = GSON.toJson(data);
            Files.writeString(scoresFile, json, StandardCharsets.UTF_8);
            LOGGER.debug("[cosmeticslite] Saved minigame scores to {}", scoresFile);
        } catch (IOException e) {
            LOGGER.error("[cosmeticslite] Failed to save minigame scores to {}: {}", scoresFile, e.getMessage(), e);
        }
    }
    
    /**
     * Record a run for a game.
     * @param gameId The game ID
     * @param entry The score entry
     * @param rule The scoring rule for this game
     */
    public void recordRun(String gameId, MiniGameScoreEntry entry, MiniGameScoreRule rule) {
        loadIfNeeded();
        
        // Update latest run
        data.getLatestRun().put(gameId, entry);
        
        // Update wins/losses if supported
        if (rule.isSupportsWinLoss() && entry.getWin() != null) {
            if (entry.getWin()) {
                data.getWins().put(gameId, data.getWins().getOrDefault(gameId, 0) + 1);
            } else {
                data.getLosses().put(gameId, data.getLosses().getOrDefault(gameId, 0) + 1);
            }
        }
        
        // Update top runs if supported
        if (rule.isSupportsTop10()) {
            List<MiniGameScoreEntry> topRuns = data.getTopRuns().computeIfAbsent(gameId, k -> new ArrayList<>());
            
            // Add new entry
            topRuns.add(entry);
            
            // Sort based on rule
            Comparator<MiniGameScoreEntry> comparator = Comparator
                .comparingInt(MiniGameScoreEntry::getScore);
            
            if (rule.isLowerIsBetter()) {
                // Lower is better: ascending order
                topRuns.sort(comparator.thenComparing((e1, e2) -> Long.compare(e2.getTimeEpochMs(), e1.getTimeEpochMs())));
            } else {
                // Higher is better: descending order
                topRuns.sort(comparator.reversed().thenComparing((e1, e2) -> Long.compare(e2.getTimeEpochMs(), e1.getTimeEpochMs())));
            }
            
            // Keep only top 10
            if (topRuns.size() > 10) {
                topRuns.subList(10, topRuns.size()).clear();
            }
        }
        
        // Update best score cache
        updateBestScoreCache(gameId, entry.getScore(), rule);
        
        // Save immediately
        saveNow();
    }
    
    /**
     * Get the best score for a game.
     */
    public Integer getBest(String gameId) {
        loadIfNeeded();
        return data.getBestScoreCached().get(gameId);
    }
    
    /**
     * Get the latest run for a game.
     */
    public MiniGameScoreEntry getLatest(String gameId) {
        loadIfNeeded();
        return data.getLatestRun().get(gameId);
    }
    
    /**
     * Get the top 10 runs for a game.
     */
    public List<MiniGameScoreEntry> getTop10(String gameId) {
        loadIfNeeded();
        List<MiniGameScoreEntry> topRuns = data.getTopRuns().get(gameId);
        return topRuns != null ? new ArrayList<>(topRuns) : Collections.emptyList();
    }
    
    /**
     * Get wins and losses for a game.
     * @return A Map with "wins" and "losses" keys, or null if not supported
     */
    public Map<String, Integer> getWinsLosses(String gameId) {
        loadIfNeeded();
        int wins = data.getWins().getOrDefault(gameId, 0);
        int losses = data.getLosses().getOrDefault(gameId, 0);
        
        Map<String, Integer> result = new HashMap<>();
        result.put("wins", wins);
        result.put("losses", losses);
        return result;
    }
    
    /**
     * Update the best score cache for a game.
     */
    private void updateBestScoreCache(String gameId, int score, MiniGameScoreRule rule) {
        Integer currentBest = data.getBestScoreCached().get(gameId);
        if (currentBest == null) {
            data.getBestScoreCached().put(gameId, score);
        } else {
            boolean isBetter = rule.isLowerIsBetter() ? (score < currentBest) : (score > currentBest);
            if (isBetter) {
                data.getBestScoreCached().put(gameId, score);
            }
        }
    }
    
    /**
     * Recompute all best scores from top runs.
     */
    private void recomputeBestScores() {
        data.getBestScoreCached().clear();
        
        for (Map.Entry<String, List<MiniGameScoreEntry>> entry : data.getTopRuns().entrySet()) {
            String gameId = entry.getKey();
            List<MiniGameScoreEntry> topRuns = entry.getValue();
            
            if (!topRuns.isEmpty()) {
                MiniGameScoreRule rule = MiniGameScoreRule.forGame(gameId);
                // Best is the first entry after sorting
                data.getBestScoreCached().put(gameId, topRuns.get(0).getScore());
            }
        }
        
        // Also check latest runs for games that don't support top10
        for (Map.Entry<String, MiniGameScoreEntry> entry : data.getLatestRun().entrySet()) {
            String gameId = entry.getKey();
            if (!data.getBestScoreCached().containsKey(gameId)) {
                data.getBestScoreCached().put(gameId, entry.getValue().getScore());
            }
        }
    }
}
