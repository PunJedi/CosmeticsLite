// src/main/java/com/pastlands/cosmeticslite/CosmeticCommand.java
package com.pastlands.cosmeticslite;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
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
                    )
                )
                // ---- New: Entitlements (pack/cosmetic) management
                .then(Commands.literal("entitlements")
                    // /cosmetics admin entitlements grant pack|cosmetic <player(s)> <namespace:path>
                    .then(Commands.literal("grant")
                        .then(Commands.literal("pack")
                            .then(Commands.argument("player", EntityArgument.players())
                                .then(Commands.argument("id", ResourceLocationArgument.id())
                                    .executes(ctx -> {
                                        ServerPlayer sender = ctx.getSource().getPlayerOrException();
                                        if (!canUseAdmin(sender)) {
                                            ctx.getSource().sendFailure(Component.literal("§cYou do not have permission to use admin commands."));
                                            return 0;
                                        }
                                        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "player");
                                        ResourceLocation id = ResourceLocationArgument.getId(ctx, "id");
                                        for (ServerPlayer target : targets) {
                                            PlayerEntitlements.get(target).ifPresent(cap -> cap.grantPack(id));
                                            CosmeticsLite.sendEntitlements(target);
                                        }
                                        ctx.getSource().sendSuccess(() -> Component.literal("§aGranted pack " + id + " to " +
                                                targets.stream().map(p -> p.getName().getString()).collect(Collectors.joining(", "))), true);
                                        return 1;
                                    })
                                )
                            )
                        )
                        .then(Commands.literal("cosmetic")
                            .then(Commands.argument("player", EntityArgument.players())
                                .then(Commands.argument("id", ResourceLocationArgument.id())
                                    .executes(ctx -> {
                                        ServerPlayer sender = ctx.getSource().getPlayerOrException();
                                        if (!canUseAdmin(sender)) {
                                            ctx.getSource().sendFailure(Component.literal("§cYou do not have permission to use admin commands."));
                                            return 0;
                                        }
                                        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "player");
                                        ResourceLocation id = ResourceLocationArgument.getId(ctx, "id");
                                        for (ServerPlayer target : targets) {
                                            PlayerEntitlements.get(target).ifPresent(cap -> cap.grantCosmetic(id));
                                            CosmeticsLite.sendEntitlements(target);
                                        }
                                        ctx.getSource().sendSuccess(() -> Component.literal("§aGranted cosmetic " + id + " to " +
                                                targets.stream().map(p -> p.getName().getString()).collect(Collectors.joining(", "))), true);
                                        return 1;
                                    })
                                )
                            )
                        )
                    )
                    // /cosmetics admin entitlements revoke pack|cosmetic <player(s)> <namespace:path>
                    .then(Commands.literal("revoke")
                        .then(Commands.literal("pack")
                            .then(Commands.argument("player", EntityArgument.players())
                                .then(Commands.argument("id", ResourceLocationArgument.id())
                                    .executes(ctx -> {
                                        ServerPlayer sender = ctx.getSource().getPlayerOrException();
                                        if (!canUseAdmin(sender)) {
                                            ctx.getSource().sendFailure(Component.literal("§cYou do not have permission to use admin commands."));
                                            return 0;
                                        }
                                        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "player");
                                        ResourceLocation id = ResourceLocationArgument.getId(ctx, "id");
                                        for (ServerPlayer target : targets) {
                                            PlayerEntitlements.get(target).ifPresent(cap -> cap.revokePack(id));
                                            CosmeticsLite.sendEntitlements(target);
                                        }
                                        ctx.getSource().sendSuccess(() -> Component.literal("§cRevoked pack " + id + " from " +
                                                targets.stream().map(p -> p.getName().getString()).collect(Collectors.joining(", "))), true);
                                        return 1;
                                    })
                                )
                            )
                        )
                        .then(Commands.literal("cosmetic")
                            .then(Commands.argument("player", EntityArgument.players())
                                .then(Commands.argument("id", ResourceLocationArgument.id())
                                    .executes(ctx -> {
                                        ServerPlayer sender = ctx.getSource().getPlayerOrException();
                                        if (!canUseAdmin(sender)) {
                                            ctx.getSource().sendFailure(Component.literal("§cYou do not have permission to use admin commands."));
                                            return 0;
                                        }
                                        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "player");
                                        ResourceLocation id = ResourceLocationArgument.getId(ctx, "id");
                                        for (ServerPlayer target : targets) {
                                            PlayerEntitlements.get(target).ifPresent(cap -> cap.revokeCosmetic(id));
                                            CosmeticsLite.sendEntitlements(target);
                                        }
                                        ctx.getSource().sendSuccess(() -> Component.literal("§cRevoked cosmetic " + id + " from " +
                                                targets.stream().map(p -> p.getName().getString()).collect(Collectors.joining(", "))), true);
                                        return 1;
                                    })
                                )
                            )
                        )
                    )
                    // /cosmetics admin entitlements list <player>
                    .then(Commands.literal("list")
                        .then(Commands.argument("player", EntityArgument.player())
                            .executes(ctx -> {
                                ServerPlayer sender = ctx.getSource().getPlayerOrException();
                                if (!canUseAdmin(sender)) {
                                    ctx.getSource().sendFailure(Component.literal("§cYou do not have permission to use admin commands."));
                                    return 0;
                                }
                                ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                                return listEntitlements(ctx.getSource(), target);
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
    // Entitlements helpers
    // -------------------------
    private static int listEntitlements(CommandSourceStack src, ServerPlayer target) {
        return PlayerEntitlements.get(target).map(cap -> {
            int p = cap.allPacks().size();
            int c = cap.allCosmetics().size();
            src.sendSuccess(() -> Component.literal("[CosLite] Entitlements for " + target.getGameProfile().getName() + ": packs=" + p + ", cosmetics=" + c), false);

            String packs = cap.allPacks().stream().limit(10).collect(Collectors.joining(", "));
            String cos = cap.allCosmetics().stream().limit(10).collect(Collectors.joining(", "));
            if (!packs.isEmpty()) src.sendSuccess(() -> Component.literal(" packs: " + packs), false);
            if (!cos.isEmpty()) src.sendSuccess(() -> Component.literal(" cosmetics: " + cos), false);
            if (p > 10 || c > 10) src.sendSuccess(() -> Component.literal(" (…truncated…)"), false);
            return 1;
        }).orElseGet(() -> {
            src.sendFailure(Component.literal("[CosLite] No entitlements capability present on target."));
            return 0;
        });
    }
}
