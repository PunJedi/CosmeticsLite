package com.pastlands.cosmeticslite.client;

import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.gadget.GadgetClientCommands;
import com.pastlands.cosmeticslite.gadget.GadgetQuickMenuScreen;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Ensures the quick gadget menu never lingers across world/server exits or title screen.
 * - Cancels any pending "auto-reopen"
 * - Closes the quickmenu screen if it is open
 */
@Mod.EventBusSubscriber(modid = CosmeticsLite.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class QuickMenuGuards {

    /** Close the quickmenu screen if open and cancel any pending reopen timer. */
    private static void hardCloseQuickMenu() {
        // Stop any scheduled reopen immediately
        GadgetClientCommands.armReopenAfterUse(false);

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        // If our quickmenu is currently showing, close it
        if (mc.screen instanceof GadgetQuickMenuScreen) {
            mc.setScreen(null);
        }
    }

    /** Fired when the client logs out / leaves a world. */
    @SubscribeEvent
    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut e) {
        hardCloseQuickMenu();
    }

    /** Also ensure it’s hidden when opening the Title Screen (e.g., after disconnect). */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening e) {
        if (e.getScreen() instanceof net.minecraft.client.gui.screens.TitleScreen) {
            hardCloseQuickMenu();
        }
    }
}
