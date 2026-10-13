package dev.darcosse.forge.cobblemonsafari.network;

import dev.darcosse.common.cobblemonsafari.client.BountyClientState;
import dev.darcosse.common.cobblemonsafari.client.FeaturedClientState;
import dev.darcosse.common.cobblemonsafari.client.SafariClientState;
import dev.darcosse.common.cobblemonsafari.network.SafariBountiesPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariFeaturedPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariNetwork;
import dev.darcosse.common.cobblemonsafari.network.SafariRulesPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariShopBuyPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariShopPayload;
import dev.darcosse.common.cobblemonsafari.network.ShopClientBridge;
import dev.darcosse.common.cobblemonsafari.network.SafariStatsPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariWallScreenOpenPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariWallScreenSetPayload;
import dev.darcosse.common.cobblemonsafari.network.WallScreenClientBridge;
import dev.darcosse.common.cobblemonsafari.client.StatsClientState;
import dev.darcosse.common.cobblemonsafari.block.WallScreenSettings;
import dev.darcosse.common.cobblemonsafari.shop.SafariShop;
import net.minecraft.server.level.ServerPlayer;
import dev.darcosse.common.cobblemonsafari.network.SafariTimerPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers the Safari payloads on NeoForge.
 *
 * <p>Registered as REQUIRED (no {@code optional()}), unlike JEC: the Safari
 * relies on the client to pre-check Poké Ball use, so a client without the mod
 * would see its ball count desync. NeoForge refuses such clients at login.
 * The handlers are registered on both dists; they only touch the client
 * state holders and the client bridges, which have no client-only imports.
 */
public final class NeoForgeSafariNetworking {

    private NeoForgeSafariNetworking() {
    }

    /** Mod-bus handler for payload registration. */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(SafariNetwork.PROTOCOL_VERSION);

        registrar.playToClient(SafariTimerPayload.TYPE, SafariTimerPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> SafariClientState.onTimer(payload)));
        registrar.playToClient(SafariRulesPayload.TYPE, SafariRulesPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> SafariClientState.onRules(payload)));
        registrar.playToClient(SafariFeaturedPayload.TYPE, SafariFeaturedPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> FeaturedClientState.onFeatured(payload)));
        registrar.playToClient(SafariBountiesPayload.TYPE, SafariBountiesPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> BountyClientState.onBounties(payload)));
        // Through the bridge: the screen is client-only and this handler exists on both dists.
        registrar.playToClient(SafariShopPayload.TYPE, SafariShopPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> ShopClientBridge.onShop(payload)));
        registrar.playToClient(SafariStatsPayload.TYPE, SafariStatsPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> StatsClientState.onStats(payload)));
        // Through the bridge for the same reason as the shop.
        registrar.playToClient(SafariWallScreenOpenPayload.TYPE, SafariWallScreenOpenPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> WallScreenClientBridge.onOpen(payload)));
        registrar.playToServer(SafariWallScreenSetPayload.TYPE, SafariWallScreenSetPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        WallScreenSettings.handleSet(player, payload);
                    }
                }));
        registrar.playToServer(SafariShopBuyPayload.TYPE, SafariShopBuyPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        SafariShop.handleBuy(player, payload);
                    }
                }));
    }
}
