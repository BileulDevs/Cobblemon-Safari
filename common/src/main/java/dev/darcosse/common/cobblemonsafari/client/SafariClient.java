package dev.darcosse.common.cobblemonsafari.client;

import dev.darcosse.common.cobblemonsafari.client.gate.GateAmbience;
import dev.darcosse.common.cobblemonsafari.gate.GateClientHooks;
import dev.darcosse.common.cobblemonsafari.client.shop.SafariShopScreen;
import dev.darcosse.common.cobblemonsafari.network.ShopClientBridge;
import dev.darcosse.common.cobblemonsafari.client.board.BoardPokemonModels;
import dev.darcosse.common.cobblemonsafari.config.SafariClientConfigManager;

/**
 * Loader-independent client bootstrap. Loaders call {@link #init()} from their
 * client entry point and {@link #onDisconnect()} when leaving a server.
 */
public final class SafariClient {

    private SafariClient() {
    }

    /** Loads display preferences and installs the shop screen as the shop payload handler. */
    public static void init() {
        SafariClientConfigManager.load();
        ShopClientBridge.setHandler(SafariShopScreen::handle);
        GateClientHooks.setPaneAnimated(GateAmbience::onPaneAnimated);
    }

    /** Forgets server-provided state so it cannot leak into the next server. */
    public static void onDisconnect() {
        SafariClientState.clear();
        FeaturedClientState.clear();
        BountyClientState.clear();
        // Animation states hold model references; the next server may ship other models.
        BoardPokemonModels.clear();
        GateAmbience.clear();
    }
}
