package com.pastlands.cosmeticslite.util;

import org.jetbrains.annotations.Nullable;
import java.util.Locale;

/**
 * Utility for sanitizing particle ID path segments.
 * Converts user input into valid ResourceLocation path segments.
 */
public final class ParticleIdUtil {
    
    private ParticleIdUtil() {
        // Utility class - no instantiation
    }
    
    /**
     * Sanitizes a raw string into a valid ResourceLocation path segment.
     * 
     * Rules:
     * - Converts to lowercase (using Locale.ROOT)
     * - Replaces spaces with underscores
     * - Strips illegal characters (only allows [a-z0-9._-])
     * - Collapses multiple consecutive underscores into one
     * - Trims leading/trailing underscores, dots, and hyphens
     * - Rejects slashes completely
     * 
     * @param raw The raw input string to sanitize
     * @return The sanitized path segment, or null if the result would be empty
     */
    @Nullable
    public static String sanitizePath(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        
        // Convert to lowercase using Locale.ROOT
        String lower = raw.toLowerCase(Locale.ROOT);
        
        // Reject slashes completely
        if (lower.contains("/")) {
            return null;
        }
        
        // Replace spaces with underscores
        lower = lower.replace(' ', '_');
        
        // Strip illegal characters - only allow [a-z0-9._-]
        StringBuilder sb = new StringBuilder();
        for (char c : lower.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || 
                (c >= '0' && c <= '9') || 
                c == '.' || c == '_' || c == '-') {
                sb.append(c);
            }
        }
        
        if (sb.length() == 0) {
            return null;
        }
        
        // Collapse multiple consecutive underscores into one
        String result = sb.toString().replaceAll("_{2,}", "_");
        
        // Trim leading/trailing underscores, dots, and hyphens
        result = result.replaceAll("^[._-]+", "").replaceAll("[._-]+$", "");
        
        // Return null if result is empty after trimming
        if (result.isEmpty()) {
            return null;
        }
        
        return result;
    }
}

