package dev.darcosse.common.cobblemonsafari.client;

import dev.darcosse.common.cobblemonsafari.client.board.BoardPokemonModels;
import dev.darcosse.common.cobblemonsafari.config.SafariClientConfigManager;

/**
 * Loader-independent client bootstrap. Loaders call {@link #init()} from their
 * client entry point and {@link #onDisconnect()} when leaving a server.
 */
public final class SafariClient {

    private SafariClient() {
    }

    /** Loads display preferences. */
    public static void init() {
        SafariClientConfigManager.load();
    }

    /** Forgets server-provided state so it cannot leak into the next server. */
    public static void onDisconnect() {
        SafariClientState.clear();
        FeaturedClientState.clear();
        // Animation states hold model references; the next server may ship other models.
        BoardPokemonModels.clear();
    }
}
