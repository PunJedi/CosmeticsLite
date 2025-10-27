package com.pastlands.cosmeticslite;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Clears client-side per-entity cosmetic caches when the client changes network state.
 * This prevents stale cosmetics persisting across sessions/world switches.
 *
 * MC 1.20.1 • Forge 47.4.0
 */
@Mod.EventBusSubscriber(modid = CosmeticsLite.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientCacheHygiene {

    private ClientCacheHygiene() {}

    /** Fired when the local client player is logging out (leaving a server/world). */
    @SubscribeEvent
    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientState.clearAllEntities();
        // Note: we intentionally do NOT clear LOCAL; the next login will mirror from the server packet.
    }

    /** Also clear on fresh login to ensure a clean slate before snapshots arrive. */
    @SubscribeEvent
    public static void onClientLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        ClientState.clearAllEntities();
    }
}
