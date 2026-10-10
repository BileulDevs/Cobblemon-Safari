package dev.darcosse.common.cobblemonsafari.network;

import java.util.function.Consumer;

/**
 * Hands received shop payloads to the client screen.
 *
 * <p>NeoForge registers payload handlers on both dists; going through this
 * bridge keeps the handler free of client-only classes (the screen), which a
 * dedicated server must never load. The client entry point installs the
 * real handler.
 */
public final class ShopClientBridge {

    private static Consumer<SafariShopPayload> handler = payload -> {
    };

    private ShopClientBridge() {
    }

    /** Installs the client handler. Called once from the client entry point. */
    public static void setHandler(Consumer<SafariShopPayload> clientHandler) {
        handler = clientHandler;
    }

    /** Delivers a payload; on the client thread. */
    public static void onShop(SafariShopPayload payload) {
        handler.accept(payload);
    }
}
