package dev.darcosse.common.cobblemonsafari.network;

import java.util.function.Consumer;

/**
 * Hands received travel payloads to the client's loading cinematic.
 *
 * <p>NeoForge registers payload handlers on both dists; going through this
 * bridge keeps the handler free of client-only classes (the screen), which a
 * dedicated server must never load. The client entry point installs the
 * real handler.
 */
public final class TravelClientBridge {

    private static Consumer<SafariTravelPayload> handler = payload -> {
    };

    private TravelClientBridge() {
    }

    /** Installs the client handler. Called once from the client entry point. */
    public static void setHandler(Consumer<SafariTravelPayload> clientHandler) {
        handler = clientHandler;
    }

    /** Delivers a payload; on the client thread. */
    public static void onTravel(SafariTravelPayload payload) {
        handler.accept(payload);
    }
}
