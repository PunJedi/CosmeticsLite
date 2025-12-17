package com.pastlands.cosmeticslite;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

public class Perms {

    public static boolean has(CommandSourceStack source, String node) {
        return source.hasPermission(2); // Simplified to permission level 2+ for admin actions
    }

    public static boolean has(ServerPlayer player, String node) {
        return player.hasPermissions(2); // Match server permission level
    }
}
