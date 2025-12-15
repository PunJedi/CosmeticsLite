package com.pastlands.cosmeticslite.network;

import com.pastlands.cosmeticslite.CosmeticsLite;
import com.pastlands.cosmeticslite.permission.CosmeticsPermissions;
import com.pastlands.cosmeticslite.particle.CosmeticParticleCatalog;
import com.pastlands.cosmeticslite.particle.CosmeticParticleEntry;
import com.pastlands.cosmeticslite.particle.config.CosmeticParticleRegistry;
import com.pastlands.cosmeticslite.particle.config.ParticleDefinition;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → Server: Publish a particle definition as a cosmetic entry.
 * Server creates the CosmeticParticleEntry and saves it to the catalog.
 * For Particle Lab, slot is always AURA.
 */
public final class PublishCosmeticPacket {
    private final ResourceLocation particleId;
    private final String displayName;
    private final ResourceLocation iconItemId;
    @org.jetbrains.annotations.Nullable
    private final Integer iconTint;

    public PublishCosmeticPacket(ResourceLocation particleId, String displayName, ResourceLocation iconItemId, 
                                 @org.jetbrains.annotations.Nullable Integer iconTint) {
        this.particleId = particleId;
        this.displayName = displayName;
        this.iconItemId = iconItemId;
        this.iconTint = iconTint;
    }

    public ResourceLocation particleId() { return particleId; }
    public String displayName() { return displayName; }
    public ResourceLocation iconItemId() { return iconItemId; }
    @org.jetbrains.annotations.Nullable
    public Integer iconTint() { return iconTint; }

    // --------------------------------------------------------------------------------------------
    // Codec
    // --------------------------------------------------------------------------------------------

    public static void encode(PublishCosmeticPacket msg, FriendlyByteBuf buf) {
        writeResourceLocation(buf, msg.particleId);
        buf.writeUtf(msg.displayName, 128);
        writeResourceLocation(buf, msg.iconItemId);
        buf.writeBoolean(msg.iconTint != null);
        if (msg.iconTint != null) {
            buf.writeInt(msg.iconTint);
        }
    }

    public static PublishCosmeticPacket decode(FriendlyByteBuf buf) {
        ResourceLocation particleId = readResourceLocation(buf);
        String displayName = buf.readUtf(128);
        ResourceLocation iconItemId = readResourceLocation(buf);
        Integer iconTint = null;
        if (buf.readBoolean()) {
            iconTint = buf.readInt();
        }
        return new PublishCosmeticPacket(particleId, displayName, iconItemId, iconTint);
    }

    // --------------------------------------------------------------------------------------------
    // Serialization Helpers
    // --------------------------------------------------------------------------------------------

    private static void writeResourceLocation(FriendlyByteBuf buf, ResourceLocation rl) {
        buf.writeUtf(rl.toString(), 256);
    }

    private static ResourceLocation readResourceLocation(FriendlyByteBuf buf) {
        return ResourceLocation.parse(buf.readUtf(256));
    }

    // --------------------------------------------------------------------------------------------
    // Handler (Server-side)
    // --------------------------------------------------------------------------------------------

    public static void handle(PublishCosmeticPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // Check permission
            if (!CosmeticsPermissions.canUseParticleLab(player)) {
                player.sendSystemMessage(Component.literal("§cYou do not have permission to publish cosmetics."));
                return;
            }

            // Get particle definition from registry
            ParticleDefinition particleDef = CosmeticParticleRegistry.get(msg.particleId);
            if (particleDef == null) {
                player.sendSystemMessage(Component.literal("§cParticle definition not found: " + msg.particleId));
                return;
            }

            // Generate cosmetic ID
            String path = msg.particleId.getPath();
            if (path.startsWith("particle/")) {
                path = path.substring("particle/".length());
            }
            ResourceLocation cosmeticId = ResourceLocation.fromNamespaceAndPath(
                msg.particleId.getNamespace(),
                "cosmetic/" + path
            );

            // Particle Lab always uses AURA slot
            CosmeticParticleEntry.Slot slot = CosmeticParticleEntry.Slot.AURA;

            // Get catalog instance (singleton on server)
            CosmeticParticleCatalog catalog = getServerCatalog();
            
            // Part A: Preserve metadata if entry already exists, but use new icon from packet
            CosmeticParticleEntry existingEntry = catalog.get(cosmeticId);
            String rarity;
            Integer price;
            
            if (existingEntry != null) {
                // Preserve existing rarity and price
                rarity = existingEntry.rarity();
                price = existingEntry.price();
                CosmeticsLite.LOGGER.info("[cosmeticslite] Updating existing entry {} - using new icon from packet", cosmeticId);
            } else {
                rarity = null;
                price = null;
                CosmeticsLite.LOGGER.info("[cosmeticslite] Creating new entry {} - using icon from packet", cosmeticId);
            }

            // Use icon from packet (client-selected icon)
            // Fallback to default if iconItemId is null
            ResourceLocation iconItemId = msg.iconItemId != null 
                ? msg.iconItemId 
                : com.pastlands.cosmeticslite.particle.CosmeticIconRegistry.DEFAULT_PARTICLE_ICON;
            CosmeticParticleEntry.Icon icon = new CosmeticParticleEntry.Icon(iconItemId, msg.iconTint);

            // Create entry as CONFIG (published entries are always CONFIG)
            // Update particleId, displayName, and icon from packet
            var entry = CosmeticParticleEntry.config(
                cosmeticId,
                msg.particleId,
                Component.literal(msg.displayName),
                slot,
                rarity, // Preserve existing rarity
                price,   // Preserve existing price
                icon    // Use icon from packet
            );

            catalog.addOrUpdate(entry);
            catalog.saveToFile();

            player.sendSystemMessage(Component.literal("§aPublished cosmetic: " + msg.displayName));
            CosmeticsLite.LOGGER.info("[cosmeticslite] {} published cosmetic: {} -> {} [{}]", 
                player.getName().getString(), cosmeticId, msg.particleId, slot);

            // Sync catalog to all clients
            com.pastlands.cosmeticslite.network.CosmeticParticlesSyncPacket syncPacket = 
                new com.pastlands.cosmeticslite.network.CosmeticParticlesSyncPacket(catalog.all());
            CosmeticsLite.NETWORK.send(net.minecraftforge.network.PacketDistributor.ALL.noArg(), syncPacket);
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * Server-side catalog singleton.
     * Initialized on server startup, loaded from file, and synced to clients.
     */
    private static CosmeticParticleCatalog catalogInstance = null;

    /**
     * Get or create the server-side catalog singleton.
     * Loads from file on first access.
     */
    private static CosmeticParticleCatalog getServerCatalog() {
        if (catalogInstance == null) {
            catalogInstance = new CosmeticParticleCatalog();
            // Load built-ins + config entries
            catalogInstance.reloadFromConfig();
        }
        return catalogInstance;
    }

    /**
     * Initialize catalog on server startup.
     * Called from server lifecycle events.
     */
    public static void initializeServerCatalog() {
        getServerCatalog(); // Triggers reload (built-ins + config)
    }

    /**
     * Get the catalog instance (for syncing on player join, etc.).
     */
    public static CosmeticParticleCatalog getCatalog() {
        return getServerCatalog();
    }
}

