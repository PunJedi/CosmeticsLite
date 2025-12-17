package com.pastlands.cosmeticslite.minigame.score;

import com.pastlands.cosmeticslite.CosmeticsLite;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * File path configuration for minigame scores.
 * Similar to CosmeticParticleConfigPaths.
 */
public final class MiniGameScorePaths {
    
    private MiniGameScorePaths() {}
    
    /**
     * Get the path to the scores JSON file.
     * Location: config/cosmeticslite/minigames/scores.json
     * Uses FMLPaths.CONFIGDIR with fallback to Path.of("config", ...)
     */
    public static Path getScoresFile() {
        try {
            return FMLPaths.CONFIGDIR.get().resolve(CosmeticsLite.MODID).resolve("minigames").resolve("scores.json");
        } catch (Exception e) {
            // Fallback to relative path (client-side)
            return Path.of("config", CosmeticsLite.MODID, "minigames", "scores.json");
        }
    }
    
    /**
     * Ensure the scores directory exists.
     */
    public static void ensureScoresDirectory() {
        try {
            java.nio.file.Files.createDirectories(getScoresFile().getParent());
        } catch (java.io.IOException e) {
            com.mojang.logging.LogUtils.getLogger().error("[cosmeticslite] Failed to create minigame scores directory: {}", e.getMessage(), e);
        }
    }
}
