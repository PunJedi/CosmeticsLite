package com.pastlands.cosmeticslite.network;

import com.pastlands.cosmeticslite.CosmeticsLite;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Server → Client snapshot of a player's entitlements.
 *
 * <p>Payload contains two string sets (ResourceLocation#toString form):</p>
 * <ul>
 *   <li>packs — coarse grants like "cosmeticslite:fantasy"</li>
 *   <li>cosmetics — fine-grained cosmetic ids</li>
 * </ul>
 *
 * <p>On receipt, the client cache is updated so the GUI can filter what's visible/equippable.</p>
 */
public final class S2CEntitlementsSync {
    private final Set<String> packs;
    private final Set<String> cosmetics;

    public S2CEntitlementsSync(Set<String> packs, Set<String> cosmetics) {
        this.packs = packs == null ? Collections.emptySet() : Set.copyOf(packs);
        this.cosmetics = cosmetics == null ? Collections.emptySet() : Set.copyOf(cosmetics);
    }

    public Set<String> packs() { return packs; }
    public Set<String> cosmetics() { return cosmetics; }

    // --------------------------------------------------------------------------------------------
    // Codec
    // --------------------------------------------------------------------------------------------

    public void encode(FriendlyByteBuf buf) {
        writeStringSet(buf, packs);
        writeStringSet(buf, cosmetics);
    }

    public static S2CEntitlementsSync decode(FriendlyByteBuf buf) {
        Set<String> packs = readStringSet(buf);
        Set<String> cosmetics = readStringSet(buf);
        return new S2CEntitlementsSync(packs, cosmetics);
    }

    private static void writeStringSet(FriendlyByteBuf buf, Set<String> set) {
        buf.writeVarInt(set.size());
        for (String s : set) buf.writeUtf(s, 256);
    }

    private static Set<String> readStringSet(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Set<String> out = new HashSet<>(Math.max(0, n));
        for (int i = 0; i < n; i++) out.add(buf.readUtf(256));
        return out;
    }

    // --------------------------------------------------------------------------------------------
    // Handler
    // --------------------------------------------------------------------------------------------

    public static void handle(S2CEntitlementsSync msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> EntitlementsClientCache.update(msg.packs, msg.cosmetics));
        ctx.get().setPacketHandled(true);
    }

    // --------------------------------------------------------------------------------------------
    // Client cache (read-only helpers for GUI)
    // --------------------------------------------------------------------------------------------

    @OnlyIn(Dist.CLIENT)
    public static final class EntitlementsClientCache {
        private static Set<String> PACKS = Collections.emptySet();
        private static Set<String> COSMETICS = Collections.emptySet();

        private EntitlementsClientCache() {}

        /** Update cache from network (main thread). */
        public static void update(Set<String> packs, Set<String> cosmetics) {
            // Defensive copies
            PACKS = packs == null ? Collections.emptySet() : Set.copyOf(packs);
            COSMETICS = cosmetics == null ? Collections.emptySet() : Set.copyOf(cosmetics);
            // Optional: debug log
            if (Minecraft.getInstance() != null && Minecraft.getInstance().player != null) {
                CosmeticsLite.LOGGER.debug("[CosLite] Entitlements synced: packs={} cosmetics={}", PACKS.size(), COSMETICS.size());
            }
        }

        public static boolean hasPack(String rlString) {
            return rlString != null && PACKS.contains(rlString);
        }

        public static boolean hasCosmetic(String rlString) {
            return rlString != null && COSMETICS.contains(rlString);
        }

        public static Set<String> allPacks() { return PACKS; }
        public static Set<String> allCosmetics() { return COSMETICS; }
    }
}

