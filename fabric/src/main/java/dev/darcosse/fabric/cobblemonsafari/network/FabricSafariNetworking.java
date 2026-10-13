package dev.darcosse.fabric.cobblemonsafari.network;

import dev.darcosse.common.cobblemonsafari.network.SafariBountiesPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariFeaturedPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariRulesPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariTimerPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariShopBuyPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariShopPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariStatsPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariWallScreenOpenPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariWallScreenSetPayload;
import dev.darcosse.common.cobblemonsafari.block.WallScreenSettings;
import dev.darcosse.common.cobblemonsafari.shop.SafariShop;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * Declares the Safari payload types to Fabric.
 *
 * <p>Must run in the common initializer, on both sides: a dedicated server has
 * to know the codecs to encode, and the client to decode.
 */
public final class FabricSafariNetworking {

    private FabricSafariNetworking() {
    }

    /**
     * Registers every payload type, both directions, and the server-side
     * receivers of shop purchases and wall screen settings (Fabric runs them
     * on the server thread).
     */
    public static void registerPayloadTypes() {
        PayloadTypeRegistry.playS2C().register(SafariShopPayload.TYPE, SafariShopPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SafariShopBuyPayload.TYPE, SafariShopBuyPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SafariShopBuyPayload.TYPE,
                (payload, context) -> SafariShop.handleBuy(context.player(), payload));
        PayloadTypeRegistry.playS2C().register(SafariTimerPayload.TYPE, SafariTimerPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SafariRulesPayload.TYPE, SafariRulesPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SafariFeaturedPayload.TYPE, SafariFeaturedPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SafariBountiesPayload.TYPE, SafariBountiesPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SafariStatsPayload.TYPE, SafariStatsPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SafariWallScreenOpenPayload.TYPE, SafariWallScreenOpenPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SafariWallScreenSetPayload.TYPE, SafariWallScreenSetPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SafariWallScreenSetPayload.TYPE,
                (payload, context) -> WallScreenSettings.handleSet(context.player(), payload));
    }
}
