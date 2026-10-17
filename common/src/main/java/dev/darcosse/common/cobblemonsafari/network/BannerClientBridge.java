package dev.darcosse.common.cobblemonsafari.network;

import java.util.function.Consumer;

/**
 * Hands received "open the banner design picker" payloads to the client
 * screen.
 *
 * <p>Same reason as {@link WallScreenClientBridge}: NeoForge registers
 * payload handlers on both dists, and the picker is a client-only class a
 * dedicated server must never load. The client entry point installs the
 * real handler.
 */
public final class BannerClientBridge {

    private static Consumer<SafariBannerOpenPayload> handler = payload -> {
    };

    private BannerClientBridge() {
    }

    /** Installs the client handler. Called once from the client entry point. */
    public static void setHandler(Consumer<SafariBannerOpenPayload> clientHandler) {
        handler = clientHandler;
    }

    /** Delivers a payload; on the client thread. */
    public static void onOpen(SafariBannerOpenPayload payload) {
        handler.accept(payload);
    }
}
