package dev.darcosse.common.cobblemonsafari.client;

import dev.darcosse.common.cobblemonsafari.client.shop.SafariShopScreen;
import dev.darcosse.common.cobblemonsafari.network.ShopClientBridge;
import dev.darcosse.common.cobblemonsafari.network.WallScreenClientBridge;
import dev.darcosse.common.cobblemonsafari.network.BannerClientBridge;
import dev.darcosse.common.cobblemonsafari.client.banner.BannerDesignScreen;
import dev.darcosse.common.cobblemonsafari.client.wallscreen.WallScreenSettingsScreen;
import dev.darcosse.common.cobblemonsafari.client.board.BoardPokemonModels;
import dev.darcosse.common.cobblemonsafari.config.SafariClientConfigManager;

/**
 * Loader-independent client bootstrap. Loaders call {@link #init()} from their
 * client entry point and {@link #onDisconnect()} when leaving a server.
 */
public final class SafariClient {

    private SafariClient() {
    }

    /**
     * Loads display preferences and installs the shop, wall screen settings
     * and banner design screens as payload handlers.
     */
    public static void init() {
        SafariClientConfigManager.load();
        ShopClientBridge.setHandler(SafariShopScreen::handle);
        WallScreenClientBridge.setHandler(WallScreenSettingsScreen::handle);
        BannerClientBridge.setHandler(BannerDesignScreen::handle);
    }

    /** Forgets server-provided state so it cannot leak into the next server. */
    public static void onDisconnect() {
        SafariClientState.clear();
        FeaturedClientState.clear();
        BountyClientState.clear();
        StatsClientState.clear();
        // Animation states hold model references; the next server may ship other models.
        BoardPokemonModels.clear();
    }
}
