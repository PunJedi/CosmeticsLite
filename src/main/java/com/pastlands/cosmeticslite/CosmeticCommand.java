// src/main/java/com/pastlands/cosmeticslite/CosmeticCommand.java
package com.pastlands.cosmeticslite;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.server.permission.PermissionAPI;

import java.util.Collection;
import java.util.stream.Collectors;

public class CosmeticCommand {

    private static final String UNLOCK_TAG = "cosmeticslite_unlocked";

    // Pack ID constants for shortcut tokens
    private static final ResourceLocation PACK_HATS_FANTASY =
        ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "packs/hats_fantasy");
    private static final ResourceLocation PACK_HATS_ANIMALS =
        ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "packs/hats_animals");
    private static final ResourceLocation PACK_HATS_FOOD =
        ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "packs/hats_food");
    private static final ResourceLocation PACK_MINIGAMES =
        ResourceLocation.fromNamespaceAndPath(CosmeticsLite.MODID, "packs/minigames");

    // Token suggestions for tab completion
    private static final String[] SHORTCUT_TOKENS = {
        "hats.fantasy",
        "hats.animals",
        "hats.food",
        "minigames"
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cosmetics")
            // -------------------------
            // /cosmetics menu
            // -------------------------
            .then(Commands.literal("menu")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();

                    if (!canOpenMenu(player)) {
                        player.sendSystemMessage(Component.literal("§cPlease visit the store first to unlock cosmetics!"));
                        return 0;
                    }

                    // Open cosmetics menu
                    CosmeticsLite.NETWORK.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new OpenCosmeticsScreenPacket()
                    );
                    return 1;
                })
            )
            // -------------------------
            // /cosmetics testpet
            // -------------------------
            .then(Commands.literal("testpet")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();

                    if (!canUseAdmin(player)) {
                        ctx.getSource().sendFailure(Component.literal("§cYou do not have permission to use this command."));
                        return 0;
                    }

                    ctx.getSource().sendSuccess(() ->
                        Component.literal("Testing pet spawn for " + player.getName().getString()), true);

                    // Force call pet manager
                    com.pastlands.cosmeticslite.entity.PetManager.updatePlayerPet(player);
                    return 1;
                })
            )
            // -------------------------
            // /cosmetics admin ...
            // -------------------------
            .then(Commands.literal("admin")
                .executes(ctx -> {
                    ServerPlayer sender = ctx.getSource().getPlayerOrException();
                    if (!canUseAdmin(sender)) {
                        ctx.getSource().sendFailure(Component.literal("§cYou do not have permission to use admin commands."));
                        return 0;
                    }
                    return 1;
                })
                // ---- Legacy global access toggles (kept for compatibility)
                .then(Commands.literal("grant")
                    .then(Commands.argument("player", EntityArgument.players())
                        .executes(ctx -> {
                            ServerPlayer sender = ctx.getSource().getPlayerOrException();
                            if (!canUseAdmin(sender)) {
                                ctx.getSource().sendFailure(Component.literal("§cYou do not have permission to use admin commands."));
                                return 0;
                            }

                            Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "player");
                            for (ServerPlayer target : targets) {
                                grantAccess(target);
                                sendAccessSync(target);
                                ctx.getSource().sendSuccess(() ->
                                    Component.literal("§aGranted cosmetics access to " + target.getName().getString()), true);
                            }
                            return 1;
                        })
                        // Token-based grant shortcut
                        .then(Commands.argument("token", StringArgumentType.word())
                            .suggests((ctx, builder) ->
                                net.minecraft.commands.SharedSuggestionProvider.suggest(SHORTCUT_TOKENS, builder)
                            )
                            .executes(ctx -> {
                                ServerPlayer sender = ctx.getSource().getPlayerOrException();
                                if (!canUseAdmin(sender)) {
                                    ctx.getSource().sendFailure(Component.literal("§cYou do not have permission to use admin commands."));
                                    return 0;
                                }

                                Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "player");
                                String token = StringArgumentType.getString(ctx, "token");

                                boolean anySuccess = false;
                                for (ServerPlayer target : targets) {
                                    if (applyShortcutGrant(target, token)) {
                                        CosmeticsLite.sendEntitlements(target);
                                        anySuccess = true;
                                    }
                                }

                                if (!anySuccess) {
                                    ctx.getSource().sendFailure(
                                        Component.literal("§cUnknown entitlements token: " + token)
                                    );
                                    return 0;
                                }

                                String list = targets.stream()
                                    .map(p -> p.getName().getString())
                                    .collect(Collectors.joining(", "));
                                ctx.getSource().sendSuccess(
                                    () -> Component.literal("§aGranted '" + token + "' to " + list),
                                    true
                                );
                                return 1;
                            })
                        )
                    )
                )
                .then(Commands.literal("revoke")
                    .then(Commands.argument("player", EntityArgument.players())
                        .executes(ctx -> {
                            ServerPlayer sender = ctx.getSource().getPlayerOrException();
                            if (!canUseAdmin(sender)) {
                                ctx.getSource().sendFailure(Component.literal("§cYou do not have permission to use admin commands."));
                                return 0;
                            }

                            Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "player");
                            for (ServerPlayer target : targets) {
                                revokeAccess(target);
                                sendAccessSync(target);
                                ctx.getSource().sendSuccess(() ->
                                    Component.literal("§cRevoked cosmetics access from " + target.getName().getString()), true);
                            }
                            return 1;
                        })
                        // Token-based revoke shortcut
                        .then(Commands.argument("token", StringArgumentType.word())
                            .suggests((ctx, builder) ->
                                net.minecraft.commands.SharedSuggestionProvider.suggest(SHORTCUT_TOKENS, builder)
                            )
                            .executes(ctx -> {
                                ServerPlayer sender = ctx.getSource().getPlayerOrException();
                                if (!canUseAdmin(sender)) {
                                    ctx.getSource().sendFailure(Component.literal("§cYou do not have permission to use admin commands."));
                                    return 0;
                                }

                                Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "player");
                                String token = StringArgumentType.getString(ctx, "token");

                                boolean anySuccess = false;
                                for (ServerPlayer target : targets) {
                                    if (applyShortcutRevoke(target, token)) {
                                        CosmeticsLite.sendEntitlements(target);
                                        anySuccess = true;
                                    }
                                }

                                if (!anySuccess) {
                                    ctx.getSource().sendFailure(
                                        Component.literal("§cUnknown entitlements token: " + token)
                                    );
                                    return 0;
                                }

                                String list = targets.stream()
                                    .map(p -> p.getName().getString())
                                    .collect(Collectors.joining(", "));
                                ctx.getSource().sendSuccess(
                                    () -> Component.literal("§cRevoked '" + token + "' from " + list),
                                    true
                                );
                                return 1;
                            })
                        )
                    )
                )
            )
        );
    }

    // -------------------------
    // Permission helpers (safe, never throw)
    // -------------------------
    private static boolean canOpenMenu(ServerPlayer player) {
        try {
            // Primary: permission node
            if (PermissionAPI.getPermission(player, CosmeticsLite.PERM_MENU)) {
                return true;
            }
            // Fallback: allow if they’re unlocked already or are OPs
            return hasAccess(player) || player.hasPermissions(2);
        } catch (Throwable t) {
            // Any backend error → fallback path
            return hasAccess(player) || player.hasPermissions(2);
        }
    }

    private static boolean canUseAdmin(ServerPlayer player) {
        try {
            return PermissionAPI.getPermission(player, CosmeticsLite.PERM_ADMIN);
        } catch (Throwable t) {
            // Fallback: vanilla OPs
            return player.hasPermissions(2);
        }
    }

    // -------------------------
    // Player Access Helpers (legacy global switch)
    // -------------------------
    private static boolean hasAccess(ServerPlayer player) {
        CompoundTag tag = player.getPersistentData();
        return tag.getBoolean(UNLOCK_TAG);
    }

    private static void grantAccess(ServerPlayer player) {
        CompoundTag tag = player.getPersistentData();
        tag.putBoolean(UNLOCK_TAG, true);
    }

    private static void revokeAccess(ServerPlayer player) {
        CompoundTag tag = player.getPersistentData();
        tag.remove(UNLOCK_TAG);
    }

    public static void sendAccessSync(ServerPlayer player) {
        CosmeticsLite.NETWORK.send(
            PacketDistributor.PLAYER.with(() -> player),
            new com.pastlands.cosmeticslite.network.SyncCosmeticsAccessPacket(hasAccess(player))
        );
    }

    // -------------------------
    // Token → entitlement mapping helpers
    // -------------------------
    private static boolean applyShortcutGrant(ServerPlayer target, String token) {
        return PlayerEntitlements.get(target).map(cap -> {
            switch (token) {
                case "hats.fantasy" -> cap.grantPack(PACK_HATS_FANTASY);
                case "hats.animals" -> cap.grantPack(PACK_HATS_ANIMALS);
                case "hats.food"    -> cap.grantPack(PACK_HATS_FOOD);
                case "minigames"    -> cap.grantPack(PACK_MINIGAMES);
                default -> { return false; }
            }
            return true;
        }).orElse(false);
    }

    private static boolean applyShortcutRevoke(ServerPlayer target, String token) {
        return PlayerEntitlements.get(target).map(cap -> {
            switch (token) {
                case "hats.fantasy" -> cap.revokePack(PACK_HATS_FANTASY);
                case "hats.animals" -> cap.revokePack(PACK_HATS_ANIMALS);
                case "hats.food"    -> cap.revokePack(PACK_HATS_FOOD);
                case "minigames"    -> cap.revokePack(PACK_MINIGAMES);
                default -> { return false; }
            }
            return true;
        }).orElse(false);
    }
}
