package com.pastlands.cosmeticslite;

import net.minecraft.server.level.ServerPlayer;

/**
 * @deprecated DO NOT USE THIS CLASS. This is the old permission system.
 * 
 * <p><b>Use {@link com.pastlands.cosmeticslite.permission.CosmeticsPermissions} instead.</b></p>
 * 
 * <p>This class only provides basic OP-level checks and does NOT support:
 * <ul>
 *   <li>Rank-based permissions (VIP, MVP, etc.)</li>
 *   <li>Feature-based permissions (base vs custom hats, base vs blended particles)</li>
 *   <li>Permission node integration</li>
 * </ul>
 * </p>
 * 
 * <p>All new code should import:
 * <pre>{@code
 * import com.pastlands.cosmeticslite.permission.CosmeticsPermissions;
 * }</pre>
 * </p>
 * 
 * <p>This class will be removed in a future version.</p>
 * 
 * @see com.pastlands.cosmeticslite.permission.CosmeticsPermissions
 */
@Deprecated(forRemoval = true)
public final class CosmeticsPermissions {
    private CosmeticsPermissions() {}
    
    /**
     * @deprecated Use {@link com.pastlands.cosmeticslite.permission.CosmeticsPermissions#canUseParticleLab(ServerPlayer)} instead.
     * This method only checks OP level and does not respect rank-based permissions.
     * 
     * @param player The server player to check
     * @return true if the player can use Particle Lab
     */
    @Deprecated(forRemoval = true)
    public static boolean canUseParticleLab(ServerPlayer player) {
        // Option B: Simple OP level check (level 2 = OP)
        // Level 2 is typically "OP" in vanilla Minecraft
        return player.hasPermissions(2);
    }
    
    /**
     * @deprecated This method is kept for backward compatibility but should not be used in new code.
     * Consider using {@link com.pastlands.cosmeticslite.permission.CosmeticsPermissions#canUseFeature(ServerPlayer, com.pastlands.cosmeticslite.permission.CosmeticsFeature)}
     * with appropriate feature checks instead.
     * 
     * @param player The server player to check
     * @return true if the player has admin permissions
     */
    @Deprecated(forRemoval = true)
    public static boolean isAdmin(ServerPlayer player) {
        // Use the existing permission system
        try {
            return net.minecraftforge.server.permission.PermissionAPI.getPermission(player, CosmeticsLite.PERM_ADMIN);
        } catch (Throwable t) {
            // Fallback: vanilla OPs
            return player.hasPermissions(2);
        }
    }
}

