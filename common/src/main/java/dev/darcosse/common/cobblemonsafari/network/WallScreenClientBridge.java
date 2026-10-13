package dev.darcosse.common.cobblemonsafari.network;

import java.util.function.Consumer;

/**
 * Hands received "open the wall screen settings" payloads to the client
 * screen.
 *
 * <p>Same reason as {@link ShopClientBridge}: NeoForge registers payload
 * handlers on both dists, and the settings screen is a client-only class a
 * dedicated server must never load. The client entry point installs the real
 * handler.
 */
public final class WallScreenClientBridge {

    private static Consumer<SafariWallScreenOpenPayload> handler = payload -> {
    };

    private WallScreenClientBridge() {
    }

    /** Installs the client handler. Called once from the client entry point. */
    public static void setHandler(Consumer<SafariWallScreenOpenPayload> clientHandler) {
        handler = clientHandler;
    }

    /** Delivers a payload; on the client thread. */
    public static void onOpen(SafariWallScreenOpenPayload payload) {
        handler.accept(payload);
    }
}
