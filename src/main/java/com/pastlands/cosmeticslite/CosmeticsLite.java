// src/main/java/com/pastlands/cosmeticslite/CosmeticsLite.java
package com.pastlands.cosmeticslite;

import com.mojang.logging.LogUtils;
import com.pastlands.cosmeticslite.entity.PetEntities;
import com.pastlands.cosmeticslite.entity.PetManager;
import com.pastlands.cosmeticslite.network.PacketSetPetColor;
import com.pastlands.cosmeticslite.network.PacketSetPetVariant;
import com.pastlands.cosmeticslite.network.S2CEntitlementsSync;
import com.pastlands.cosmeticslite.network.S2CEquipDenied;
import com.pastlands.cosmeticslite.network.SyncCosmeticsAccessPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Mod entrypoint. */
@Mod(CosmeticsLite.MODID)
public class CosmeticsLite {

    public static final String MODID = "cosmeticslite";
    private static final Logger LOG = LogUtils.getLogger();
    // Public alias to satisfy logs used in other classes
    public static final Logger LOGGER = LOG;
    
    // Debug flag for sync logging (set to true to enable detailed cape sync logs)
    public static final boolean DEBUG_SYNC = false;

    // ---- Networking ----
    private static final String NET_VERSION = "1";
    public static final SimpleChannel NETWORK = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(MODID, "main"),
            () -> NET_VERSION,
            NET_VERSION::equals,
            NET_VERSION::equals
    );

    private static int NEXT_ID = 0;
    private static int id() { return NEXT_ID++; }

    // ---- Permissions ----
    public static final PermissionNode<Boolean> PERM_MENU =
            new PermissionNode<>(MODID, "menu", PermissionTypes.BOOLEAN,
                    (player, uuid, ctx) -> true); // default: allow all

    public static final PermissionNode<Boolean> PERM_ADMIN =
            new PermissionNode<>(MODID, "admin", PermissionTypes.BOOLEAN,
                    (player, uuid, ctx) -> player.hasPermissions(2)); // default: OPs only

@SuppressWarnings("removal")
public CosmeticsLite() {
    IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
    modBus.addListener(CosmeticsLite::onRegisterCapabilities);

    DistExecutor.unsafeRunWhenOn(
            net.minecraftforge.api.distmarker.Dist.CLIENT,
            () -> () -> {
                modBus.addListener(this::onRegisterLayers);
                // Ensure ParticlePresetReloader class is loaded so its @EventBusSubscriber annotation is processed
                com.pastlands.cosmeticslite.particle.ParticlePresetReloader.ensureLoaded();
                // Initialize particle registry from config (client-side)
                modBus.addListener(this::onClientSetup);
            }
    );

    PetEntities.register(modBus);
    MinecraftForge.EVENT_BUS.register(this);

    registerPackets();

    // ✅ Ensure registry fully populated before dev seed
    if (CosmeticsRegistry.all().isEmpty()) {
        LOG.info("[{}] Registry empty at init; installing dev seed.", MODID);
        CosmeticsRegistry.replaceAll(Collections.emptyList(), /*addDevSeed=*/true);
    }


    LOG.info("[{}] Registry at init: {} cosmetic(s).", MODID, CosmeticsRegistry.all().size());
    LOG.info("[{}] Initialized.", MODID);
}



    // --------------------------------------------------------------------------------------------
    // Packets
    // --------------------------------------------------------------------------------------------
    private static void registerPackets() {
        NETWORK.registerMessage(
                id(), PacketEquipRequest.class,
                PacketEquipRequest::encode, PacketEquipRequest::decode, PacketEquipRequest::handle
        );
        NETWORK.registerMessage(
                id(), PacketSyncCosmetics.class,
                PacketSyncCosmetics::encode, PacketSyncCosmetics::decode, PacketSyncCosmetics::handle
        );
        NETWORK.registerMessage(
                id(), OpenCosmeticsScreenPacket.class,
                OpenCosmeticsScreenPacket::encode, OpenCosmeticsScreenPacket::decode, OpenCosmeticsScreenPacket::handle
        );
        NETWORK.registerMessage(
                id(), SyncCosmeticsAccessPacket.class,
                SyncCosmeticsAccessPacket::encode,
                SyncCosmeticsAccessPacket::decode,
                SyncCosmeticsAccessPacket::handle
        );
        // 🔹 PET color wheel UI -> server
        NETWORK.registerMessage(
                id(), PacketSetPetColor.class,
                PacketSetPetColor::encode,
                PacketSetPetColor::decode,
                PacketSetPetColor::handle
        );
        // 🔹 PET variant dropdown -> server
        NETWORK.registerMessage(
                id(), PacketSetPetVariant.class,
                PacketSetPetVariant::encode,
                PacketSetPetVariant::decode,
                PacketSetPetVariant::handle
        );
        // 🔹 NEW: Entitlements snapshot (server -> client)
        NETWORK.registerMessage(
                id(), S2CEntitlementsSync.class,
                S2CEntitlementsSync::encode,
                S2CEntitlementsSync::decode,
                S2CEntitlementsSync::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
		// 🔹 Denied equip feedback (server -> client)
        NETWORK.registerMessage(
                id(), S2CEquipDenied.class,
                S2CEquipDenied::encode,
                S2CEquipDenied::decode,
                S2CEquipDenied::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        // 🔹 Particle definitions sync (server -> client)
        NETWORK.registerMessage(
                id(), com.pastlands.cosmeticslite.network.ParticleDefinitionsSyncPacket.class,
                com.pastlands.cosmeticslite.network.ParticleDefinitionsSyncPacket::encode,
                com.pastlands.cosmeticslite.network.ParticleDefinitionsSyncPacket::decode,
                com.pastlands.cosmeticslite.network.ParticleDefinitionsSyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        // 🔹 Particle Lab delete (client -> server)
        NETWORK.registerMessage(
                id(), com.pastlands.cosmeticslite.network.ParticleLabDeletePacket.class,
                com.pastlands.cosmeticslite.network.ParticleLabDeletePacket::encode,
                com.pastlands.cosmeticslite.network.ParticleLabDeletePacket::decode,
                com.pastlands.cosmeticslite.network.ParticleLabDeletePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        // 🔹 Particle definition change (client -> server)
        NETWORK.registerMessage(
                id(), com.pastlands.cosmeticslite.network.ParticleDefinitionChangePacket.class,
                com.pastlands.cosmeticslite.network.ParticleDefinitionChangePacket::encode,
                com.pastlands.cosmeticslite.network.ParticleDefinitionChangePacket::decode,
                com.pastlands.cosmeticslite.network.ParticleDefinitionChangePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        // 🔹 Particle preview mode (client -> server)
        NETWORK.registerMessage(
                id(), com.pastlands.cosmeticslite.network.ParticlePreviewPacket.class,
                com.pastlands.cosmeticslite.network.ParticlePreviewPacket::encode,
                com.pastlands.cosmeticslite.network.ParticlePreviewPacket::decode,
                com.pastlands.cosmeticslite.network.ParticlePreviewPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        // 🔹 Publish cosmetic (client -> server)
        NETWORK.registerMessage(
                id(), com.pastlands.cosmeticslite.network.PublishCosmeticPacket.class,
                com.pastlands.cosmeticslite.network.PublishCosmeticPacket::encode,
                com.pastlands.cosmeticslite.network.PublishCosmeticPacket::decode,
                com.pastlands.cosmeticslite.network.PublishCosmeticPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        // 🔹 Cosmetic particles catalog sync (server -> client)
        NETWORK.registerMessage(
                id(), com.pastlands.cosmeticslite.network.CosmeticParticlesSyncPacket.class,
                com.pastlands.cosmeticslite.network.CosmeticParticlesSyncPacket::encode,
                com.pastlands.cosmeticslite.network.CosmeticParticlesSyncPacket::decode,
                com.pastlands.cosmeticslite.network.CosmeticParticlesSyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        // 🔹 Mini-game hub access request (client -> server)
        NETWORK.registerMessage(
                id(), com.pastlands.cosmeticslite.network.OpenMiniGameHubPacket.class,
                com.pastlands.cosmeticslite.network.OpenMiniGameHubPacket::encode,
                com.pastlands.cosmeticslite.network.OpenMiniGameHubPacket::decode,
                com.pastlands.cosmeticslite.network.OpenMiniGameHubPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        // 🔹 Mini-game hub access granted (server -> client)
        NETWORK.registerMessage(
                id(), com.pastlands.cosmeticslite.network.OpenMiniGameHubResponsePacket.class,
                com.pastlands.cosmeticslite.network.OpenMiniGameHubResponsePacket::encode,
                com.pastlands.cosmeticslite.network.OpenMiniGameHubResponsePacket::decode,
                com.pastlands.cosmeticslite.network.OpenMiniGameHubResponsePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

    }

    /** Helper: send current entitlements snapshot to a single player. */
    public static void sendEntitlements(ServerPlayer sp) {
        if (sp == null) return;
        PlayerEntitlements.get(sp).ifPresent(cap -> {
            NETWORK.send(PacketDistributor.PLAYER.with(() -> sp),
                    new S2CEntitlementsSync(cap.allPacks(), cap.allCosmetics()));
        });
		
    }

    // --------------------------------------------------------------------------------------------
    // Capability registration & attachment
    // --------------------------------------------------------------------------------------------
    private static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.register(PlayerData.class);
        event.register(PlayerEntitlements.class); // NEW
        LOG.debug("[{}] Registered capabilities: PlayerData, PlayerEntitlements", MODID);
    }

    @SubscribeEvent
    public void onAttachCapabilitiesEntity(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof Player) {
            event.addCapability(PlayerData.CAP_ID, new PlayerData.Provider());
            event.addCapability(PlayerEntitlements.CAP_ID, new PlayerEntitlements.Provider()); // NEW
        }
    }

    // --------------------------------------------------------------------------------------------
    // Command registration (SERVER)
    // --------------------------------------------------------------------------------------------
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LOG.info("[{}] Registering commands via CosmeticCommand.register", MODID);
        CosmeticCommand.register(event.getDispatcher()); // /cosmetics menu
    }

    // --------------------------------------------------------------------------------------------
    // Sync points (server -> client) + Pet Management
    // --------------------------------------------------------------------------------------------
    @SubscribeEvent
    public void onServerStarting(net.minecraftforge.event.server.ServerStartingEvent event) {
        // Load particle definitions from particle_lab/ on server startup
        // Server doesn't have access to assets, so only loads from config
        // Built-in definitions remain in assets (client-side only)
        int loadedCount = com.pastlands.cosmeticslite.particle.config.CosmeticParticleRegistry.reloadFromConfig(null);
        LOG.info("[cosmeticslite] Server loaded {} particle definition(s) from particle_lab config", loadedCount);
        
        // Initialize cosmetic particle catalog on server startup
        com.pastlands.cosmeticslite.network.PublishCosmeticPacket.initializeServerCatalog();
        // Sync catalog to all connected players (if any)
        com.pastlands.cosmeticslite.network.CosmeticParticlesSyncPacket syncPacket = 
            new com.pastlands.cosmeticslite.network.CosmeticParticlesSyncPacket(
                com.pastlands.cosmeticslite.network.PublishCosmeticPacket.getCatalog().all()
            );
        NETWORK.send(net.minecraftforge.network.PacketDistributor.ALL.noArg(), syncPacket);
        
        // Sync particle definitions to all connected players
        java.util.Map<net.minecraft.resources.ResourceLocation, com.pastlands.cosmeticslite.particle.config.ParticleDefinition> allDefs = new java.util.HashMap<>();
        for (var def : com.pastlands.cosmeticslite.particle.config.CosmeticParticleRegistry.all()) {
            allDefs.put(def.id(), def);
        }
        com.pastlands.cosmeticslite.network.ParticleDefinitionsSyncPacket defSyncPacket = 
            new com.pastlands.cosmeticslite.network.ParticleDefinitionsSyncPacket(allDefs);
        NETWORK.send(net.minecraftforge.network.PacketDistributor.ALL.noArg(), defSyncPacket);
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            CosmeticsSync.sync(sp);
            CosmeticCommand.sendAccessSync(sp);
            sendEntitlements(sp); // NEW: push entitlements on login
            sp.level().getServer().execute(() -> PetManager.updatePlayerPet(sp));
            // Sync particle definitions on login
            sendParticleDefinitions(sp);
            // Sync cosmetic particle catalog on login
            com.pastlands.cosmeticslite.network.CosmeticParticlesSyncPacket catalogSync = 
                new com.pastlands.cosmeticslite.network.CosmeticParticlesSyncPacket(
                    com.pastlands.cosmeticslite.network.PublishCosmeticPacket.getCatalog().all()
                );
            NETWORK.send(PacketDistributor.PLAYER.with(() -> sp), catalogSync);
        }
    }
    
    /** Helper: send current particle definitions to a single player. */
    public static void sendParticleDefinitions(ServerPlayer sp) {
        if (sp == null) return;
        java.util.Map<ResourceLocation, com.pastlands.cosmeticslite.particle.config.ParticleDefinition> allDefs = new java.util.HashMap<>();
        for (var def : com.pastlands.cosmeticslite.particle.config.CosmeticParticleRegistry.all()) {
            allDefs.put(def.id(), def);
        }
        com.pastlands.cosmeticslite.network.ParticleDefinitionsSyncPacket syncPacket = 
            new com.pastlands.cosmeticslite.network.ParticleDefinitionsSyncPacket(allDefs);
        NETWORK.send(PacketDistributor.PLAYER.with(() -> sp), syncPacket);
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            CosmeticsSync.sync(sp);
            CosmeticCommand.sendAccessSync(sp);
            sendEntitlements(sp); // keep in step with other syncs
            PetManager.updatePlayerPet(sp);
        }
    }

    @SubscribeEvent
    public void onPlayerClone(PlayerEvent.Clone event) {
        var oldP = event.getOriginal();
        var newP = event.getEntity();

        oldP.reviveCaps();
        // Copy PlayerData
        PlayerData.get(oldP instanceof ServerPlayer ? (ServerPlayer) oldP : null).ifPresent(oldData ->
            PlayerData.get(newP instanceof ServerPlayer ? (ServerPlayer) newP : null).ifPresent(newData ->
                newData.deserializeNBT(oldData.serializeNBT())
            )
        );
        // Copy PlayerEntitlements (round-trip via NBT for forward-compat)
        PlayerEntitlements.get(oldP instanceof ServerPlayer ? (ServerPlayer) oldP : null).ifPresent(oldEnt ->
            PlayerEntitlements.get(newP instanceof ServerPlayer ? (ServerPlayer) newP : null).ifPresent(newEnt ->
                newEnt.deserializeNBT(oldEnt.serializeNBT())
            )
        );
        oldP.invalidateCaps();

        if (newP instanceof ServerPlayer sp) {
            CosmeticsSync.sync(sp);
            CosmeticCommand.sendAccessSync(sp);
            sendEntitlements(sp);
            sp.level().getServer().execute(() -> PetManager.updatePlayerPet(sp));
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            CosmeticsSync.sync(sp);
            CosmeticCommand.sendAccessSync(sp);
            sendEntitlements(sp);
            PetManager.updatePlayerPet(sp);
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            PetManager.cleanupPlayer(sp.getUUID());
        }
    }

    @SubscribeEvent
    public void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer viewer)) return;
        var target = event.getTarget();
        if (target instanceof ServerPlayer subject) {
            CosmeticsSync.syncTo(viewer, subject);
            // Intentionally NOT sending entitlements here: they are private to the owner client.
        }
    }

    // --------------------------------------------------------------------------------------------
    // Client model layer registration
    // --------------------------------------------------------------------------------------------
        @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private void onRegisterLayers(net.minecraftforge.client.event.EntityRenderersEvent.RegisterLayerDefinitions event) {
        com.pastlands.cosmeticslite.client.model.CosmeticsModels.registerLayers(event);
    }

    // --------------------------------------------------------------------------------------------
    // Client setup: Initialize particle registry
    // --------------------------------------------------------------------------------------------
    @net.minecraftforge.api.distmarker.OnlyIn(net.minecraftforge.api.distmarker.Dist.CLIENT)
    private void onClientSetup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        // Initialize particle registry from config folder on client startup
        event.enqueueWork(() -> {
            // Initialize effect capabilities registry
            com.pastlands.cosmeticslite.client.editor.EffectCapabilitiesRegistry.initDefaults();
            
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.getResourceManager() != null) {
                int loaded = com.pastlands.cosmeticslite.particle.config.CosmeticParticleRegistry.reloadFromConfig(mc.getResourceManager());
                LOG.info("[{}] Loaded {} particle definition(s) from config on client setup", MODID, loaded);
            }
        });
    }

}

